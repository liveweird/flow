package ch.nokillswit.ingest

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.gitlab.GitLabSettingsKey
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.jira.JiraHeapShare
import ch.nokillswit.jira.JiraHeapShareKey
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.resources.href
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/data-sources")
class DataSourcesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: DataSourcesRoute = DataSourcesRoute(), val id: UInt) {
        @Serializable
        @Resource("test")
        class Test(val parent: Id)
    }

    @Serializable
    @Resource("test")
    class Test(val parent: DataSourcesRoute = DataSourcesRoute())

    @Serializable
    @Resource("kinds")
    class Kinds(val parent: DataSourcesRoute = DataSourcesRoute())
}

/**
 * ADMIN-only CRUD for Jira Cloud and GitLab connections (v0.2.0 plan §9). Shaped after Covenant's
 * `toadie/ToadieRoutes.kt`: `requireAdmin` runs BEFORE `call.receive()` on every mutation, so a
 * non-admin's malformed body still 403s. Test-connection, sync-jobs, status and profile endpoints
 * arrive with the Jira client and the sync-job queue (plan commits 4-9) — this commit is CRUD
 * rows only.
 */
fun Application.configureDataSourceRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val service = attributes[DataSourceServiceKey]
    val connectors = connectorRegistry()
    val heapShare = attributes[JiraHeapShareKey]
    val syncJobs = attributes[SyncJobsServiceKey]
    val gitLabSettings = attributes[GitLabSettingsKey]

    routing {
        authenticate {
            testConnectionRoutes(service, connectors, heapShare)
            get<DataSourcesRoute.Kinds> {
                requireAdmin(call.caller())
                call.respond(HttpStatusCode.OK, dataSourceKinds(gitLabSettings))
            }
            get<DataSourcesRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val paging = call.parsePaging(sortable = DATA_SOURCE_SORT_FIELDS)
                val filter = DataSourceListFilter(name = call.request.queryParameters.optionalString("name"))
                val result = service.list(filter, paging)
                val runningJobIds = syncJobs.runningJobIdsByConnection(result.items.map { it.id })
                val items = result.items.map { it.withRunningJobId(runningJobIds[it.id]) }
                call.respond(HttpStatusCode.OK, paging.toPage(items, result.total))
            }
            post<DataSourcesRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val policy = service.backfillPolicy() // read ONCE: the blank default and the floor must come from the same horizon
                val request = sanitizedDataSourceRequest(call.receive(), policy.defaultFrom)
                validateDataSource(request, apiTokenRequired = true, service.gitLabBaseUrlPolicy)
                val id = auditingScopeRejection(caller.userId, dataSourceId = null) { service.create(request, policy) }
                audit(
                    "data_source.created",
                    "byUserId" to caller.userId.toLong(),
                    "dataSourceId" to id.toLong(),
                    "kind" to request.kindOrBadRequest().name,
                    "name" to request.name,
                    "host" to request.host(service.gitLabBaseUrlPolicy),
                )
                call.response.header(HttpHeaders.Location, call.application.href(DataSourcesRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, service.read(id).orVanished("Data source", id))
            }
            get<DataSourcesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val found = service.read(route.id).orNotFound("Data source")
                val runningJobId = syncJobs.runningJobId(route.id)
                call.respond(HttpStatusCode.OK, found.withRunningJobId(runningJobId))
            }
            put<DataSourcesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val policy = service.backfillPolicy() // read ONCE, as on create
                val request = sanitizedDataSourceRequest(call.receive(), policy.defaultFrom)
                validateDataSource(request, apiTokenRequired = false, service.gitLabBaseUrlPolicy)
                val outcome = auditingScopeRejection(caller.userId, dataSourceId = route.id) { service.update(route.id, request, policy) }
                    .orNotFound("Data source")
                audit(
                    "data_source.updated",
                    "byUserId" to caller.userId.toLong(),
                    "dataSourceId" to route.id.toLong(),
                    "kind" to outcome.kind.name,
                    "name" to request.name,
                    "host" to outcome.host,
                )
                outcome.resumed.forEach { auditResumed(caller.userId, route.id, "UPDATE", it) }
                if (outcome.tokenRotated) {
                    audit("data_source.token_rotated", "byUserId" to caller.userId.toLong(), "dataSourceId" to route.id.toLong())
                }
                call.respond(HttpStatusCode.NoContent)
            }
            delete<DataSourcesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                service.delete(route.id).orNotFound("Data source")
                // Cancels open jobs and, once ingest.purgeGraceDays elapses, makes the connection
                // eligible for the worker's due-PURGE scan (plan §0 A2).
                syncJobs.cancelOpenForConnection(route.id)
                audit("data_source.deleted", "byUserId" to caller.userId.toLong(), "dataSourceId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/**
 * The rate-limited (`DATA_SOURCE_TEST`) ad-hoc and stored Test-connection endpoints — split out of
 * `configureDataSourceRoutes` (`LongMethod`). Each probe run parses up to one full response tree, so it holds the WHOLE web heap share
 * (`jira/JiraHeapShare.kt`: one Test connection = one tree, and it excludes the raw-issue inspector meanwhile; GitLab's trees are
 * capped at or below Jira's, so the same hold covers them); only the connector call is inside the hold, and the call's own 30 s budget
 * starts inside it, so waiting for the share never turns into TIMEOUT rows. The connector is the registry's entry for the target's
 * kind; a kind nobody registered (GitLab switched off) is a 409 `data-source-kind-unsupported`.
 */
private fun Route.testConnectionRoutes(service: DataSourceService, connectors: ConnectorRegistry, heapShare: JiraHeapShare) {
    suspend fun probe(target: ConnectionTarget): ConnectionTestResult {
        val connector = connectors.connectorFor(target.kind) ?: throw DataSourceKindUnsupportedException(target.kind,
            "registered connector")
        return heapShare.withShare(heapShare.capacityBytes) { connector.testConnection(target) }
    }
    rateLimit(RateLimitName(RateLimits.DATA_SOURCE_TEST)) {
        post<DataSourcesRoute.Test> {
            val caller = call.caller()
            requireAdmin(caller)
            val target = call.receive<DataSourceTestRequest>().toTarget(service.gitLabBaseUrlPolicy)
            val result = probe(target)
            auditConnectionTest(caller.userId, target.kind, target.host(), result)
            call.respond(HttpStatusCode.OK, result)
        }
        post<DataSourcesRoute.Id.Test> { route ->
            val caller = call.caller()
            requireAdmin(caller)
            val target = service.readForTest(route.parent.id).orNotFound("Data source")
            val result = probe(target)
            result.cloudId?.let { service.persistCloudId(route.parent.id, it) }
            auditConnectionTest(caller.userId, target.kind, target.host(), result)
            call.respond(HttpStatusCode.OK, result)
        }
    }
}

/**
 * Runs a create/update and, when the scope rules reject it (one Jira site and no project in two data sources, one GitLab instance
 * and no overlapping group path — `DataSourceScope.kt`, `GitLabScope.kt`), emits `data_source.scope_rejected` before rethrowing the
 * 409 for the error mapper. The event carries the kind, the requested host and the shared keys or group paths, never the token or
 * a full URL.
 */
private suspend fun <T> auditingScopeRejection(byUserId: UInt, dataSourceId: UInt?, block: suspend () -> T): T =
    try {
        block()
    } catch (rejected: DataSourceScopeConflictException) {
        audit(
            "data_source.scope_rejected",
            "byUserId" to byUserId.toLong(),
            "dataSourceId" to dataSourceId?.toLong(),
            "kind" to rejected.reason.kind.name,
            "reason" to rejected.reason.name,
            "conflictingDataSourceId" to rejected.conflictingId.toLong(),
            "host" to rejected.host,
            "projectKeys" to rejected.keys.takeIf { rejected.reason.kind == DataSourceKind.JIRA_CLOUD }.orEmpty(),
            "groupPaths" to rejected.keys.takeIf { rejected.reason.kind == DataSourceKind.GITLAB }.orEmpty(),
        )
        throw rejected
    }

/**
 * Overlays the actual `runningJobId` (from `SyncJobsService`, populated with the sync-job queue)
 * onto an otherwise-complete response.
 */
private fun DataSourceResponse.withRunningJobId(runningJobId: UInt?): DataSourceResponse =
    copy(status = status.copy(runningJobId = runningJobId))

/**
 * `data_source.tested` (v0.2.0 plan §9): kind, host, overall ok and the failed probe names — NEVER the token. `ok` is judged on the
 * REQUIRED rows only (what the UI calls a pass); `failedEndpoints` lists every failed row, optional ones included.
 */
private fun auditConnectionTest(byUserId: UInt, kind: DataSourceKind, host: String, result: ConnectionTestResult) {
    val failedEndpoints = result.rows.filterNot { it.ok }.map { it.name }
    audit(
        "data_source.tested",
        "byUserId" to byUserId.toLong(),
        "kind" to kind.name,
        "host" to host,
        "ok" to result.rows.none { it.required && !it.ok },
        "failedEndpoints" to failedEndpoints,
    )
}
