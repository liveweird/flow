package ch.nokillswit.ingest

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.jira.JiraConnectorKey
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
}

/**
 * ADMIN-only CRUD for Jira Cloud connections (v0.2.0 plan §9). Shaped after Covenant's
 * `toadie/ToadieRoutes.kt`: `requireAdmin` runs BEFORE `call.receive()` on every mutation, so a
 * non-admin's malformed body still 403s. Test-connection, sync-jobs, status and profile endpoints
 * arrive with the Jira client and the sync-job queue (plan commits 4-9) — this commit is CRUD
 * rows only.
 */
fun Application.configureDataSourceRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val service = attributes[DataSourceServiceKey]
    val connector = attributes[JiraConnectorKey]
    val heapShare = attributes[JiraHeapShareKey]
    val syncJobs = attributes[SyncJobsServiceKey]

    routing {
        authenticate {
            testConnectionRoutes(service, connector, heapShare)
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
                val request = sanitizedDataSourceRequest(call.receive(), service.backfillPolicy().defaultFrom)
                validateDataSource(request, apiTokenRequired = true)
                val id = auditingScopeRejection(caller.userId, dataSourceId = null) { service.create(request) }
                audit(
                    "data_source.created",
                    "byUserId" to caller.userId.toLong(),
                    "dataSourceId" to id.toLong(),
                    "name" to request.name,
                    "siteHost" to siteHost(request.jira.siteUrl),
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
                val request = sanitizedDataSourceRequest(call.receive(), service.backfillPolicy().defaultFrom)
                validateDataSource(request, apiTokenRequired = false)
                val outcome = auditingScopeRejection(caller.userId, dataSourceId = route.id) { service.update(route.id, request) }
                    .orNotFound("Data source")
                audit(
                    "data_source.updated",
                    "byUserId" to caller.userId.toLong(),
                    "dataSourceId" to route.id.toLong(),
                    "name" to request.name,
                    "siteHost" to outcome.siteHost,
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
 * (`jira/JiraHeapShare.kt`: one Test connection = one tree, and it excludes the raw-issue inspector meanwhile); only the connector call is
 * inside the hold, and the call's own 30 s budget starts inside it, so waiting for the share never turns into TIMEOUT rows.
 */
private fun Route.testConnectionRoutes(service: DataSourceService, connector: Connector, heapShare: JiraHeapShare) {
    rateLimit(RateLimitName(RateLimits.DATA_SOURCE_TEST)) {
        post<DataSourcesRoute.Test> {
            val caller = call.caller()
            requireAdmin(caller)
            val jira = sanitizedJiraConnectionRequest(call.receive<DataSourceTestRequest>().jira)
            validateJira(jira, apiTokenRequired = true)
            val result = heapShare.withShare(heapShare.capacityBytes) {
                connector.testConnection(
                    siteUrl = jira.siteUrl,
                    email = jira.email,
                    apiToken = checkNotNull(jira.apiToken) { "apiToken is required on the ad-hoc test" },
                    projectKeys = jira.projectKeys,
                    authScheme = jira.authScheme,
                )
            }
            auditConnectionTest(caller.userId, siteHost(jira.siteUrl), result)
            call.respond(HttpStatusCode.OK, result)
        }
        post<DataSourcesRoute.Id.Test> { route ->
            val caller = call.caller()
            requireAdmin(caller)
            val stored = service.readForTest(route.parent.id).orNotFound("Data source")
            val result = heapShare.withShare(heapShare.capacityBytes) {
                connector.testConnection(
                    siteUrl = stored.siteUrl,
                    email = stored.email,
                    apiToken = stored.apiToken,
                    projectKeys = stored.projectKeys,
                    authScheme = stored.authScheme,
                )
            }
            result.cloudId?.let { service.persistCloudId(route.parent.id, it) }
            auditConnectionTest(caller.userId, siteHost(stored.siteUrl), result)
            call.respond(HttpStatusCode.OK, result)
        }
    }
}

/**
 * Runs a create/update and, when the scope rules reject it (one Jira site, no project in two data sources —
 * `DataSourceScope.kt`), emits `data_source.scope_rejected` before rethrowing the 409 for the error mapper. The event
 * carries the requested site's host and the shared keys, never the token or a full URL.
 */
private suspend fun <T> auditingScopeRejection(byUserId: UInt, dataSourceId: UInt?, block: suspend () -> T): T =
    try {
        block()
    } catch (rejected: DataSourceScopeConflictException) {
        audit(
            "data_source.scope_rejected",
            "byUserId" to byUserId.toLong(),
            "dataSourceId" to dataSourceId?.toLong(),
            "reason" to rejected.reason.name,
            "conflictingDataSourceId" to rejected.conflictingId.toLong(),
            "siteHost" to rejected.siteHost,
            "projectKeys" to rejected.keys,
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
 * `data_source.tested` (v0.2.0 plan §9): siteHost, overall ok and the failed probe names — NEVER the token. `ok` is judged on the
 * REQUIRED rows only (what the UI calls a pass); `failedEndpoints` lists every failed row, optional ones included.
 */
private fun auditConnectionTest(byUserId: UInt, siteHost: String, result: ConnectionTestResult) {
    val failedEndpoints = result.rows.filterNot { it.ok }.map { it.name }
    audit(
        "data_source.tested",
        "byUserId" to byUserId.toLong(),
        "siteHost" to siteHost,
        "ok" to result.rows.none { it.required && !it.ok },
        "failedEndpoints" to failedEndpoints,
    )
}
