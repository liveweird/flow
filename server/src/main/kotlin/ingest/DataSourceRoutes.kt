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
    val syncJobs = attributes[SyncJobsServiceKey]

    routing {
        authenticate {
            testConnectionRoutes(service, connector)
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
                val request = sanitizedDataSourceRequest(call.receive())
                validateDataSource(request, apiTokenRequired = true)
                val id = service.create(request)
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
                val request = sanitizedDataSourceRequest(call.receive())
                validateDataSource(request, apiTokenRequired = false)
                val outcome = service.update(route.id, request).orNotFound("Data source")
                audit(
                    "data_source.updated",
                    "byUserId" to caller.userId.toLong(),
                    "dataSourceId" to route.id.toLong(),
                    "name" to request.name,
                    "siteHost" to outcome.siteHost,
                )
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
 * `configureDataSourceRoutes` (`LongMethod`).
 */
private fun Route.testConnectionRoutes(service: DataSourceService, connector: Connector) {
    rateLimit(RateLimitName(RateLimits.DATA_SOURCE_TEST)) {
        post<DataSourcesRoute.Test> {
            val caller = call.caller()
            requireAdmin(caller)
            val jira = sanitizedJiraConnectionRequest(call.receive<DataSourceTestRequest>().jira)
            validateJira(jira, apiTokenRequired = true)
            val result = connector.testConnection(
                siteUrl = jira.siteUrl,
                email = jira.email,
                apiToken = checkNotNull(jira.apiToken) { "apiToken is required on the ad-hoc test" },
                projectKeys = jira.projectKeys,
                authScheme = jira.authScheme,
            )
            auditConnectionTest(caller.userId, siteHost(jira.siteUrl), result)
            call.respond(HttpStatusCode.OK, result)
        }
        post<DataSourcesRoute.Id.Test> { route ->
            val caller = call.caller()
            requireAdmin(caller)
            val stored = service.readForTest(route.parent.id).orNotFound("Data source")
            val result = connector.testConnection(
                siteUrl = stored.siteUrl,
                email = stored.email,
                apiToken = stored.apiToken,
                projectKeys = stored.projectKeys,
                authScheme = stored.authScheme,
            )
            result.cloudId?.let { service.persistCloudId(route.parent.id, it) }
            auditConnectionTest(caller.userId, siteHost(stored.siteUrl), result)
            call.respond(HttpStatusCode.OK, result)
        }
    }
}

/**
 * Overlays the actual `runningJobId` (from `SyncJobsService`, populated with the sync-job queue)
 * onto an otherwise-complete response.
 */
private fun DataSourceResponse.withRunningJobId(runningJobId: UInt?): DataSourceResponse =
    copy(status = status.copy(runningJobId = runningJobId))

/** `data_source.tested` (v0.2.0 plan §9): siteHost, overall ok and the failed probe names — NEVER the token. */
private fun auditConnectionTest(byUserId: UInt, siteHost: String, result: ConnectionTestResult) {
    val failedEndpoints = result.rows.filterNot { it.ok }.map { it.name }
    audit(
        "data_source.tested",
        "byUserId" to byUserId.toLong(),
        "siteHost" to siteHost,
        "ok" to failedEndpoints.isEmpty(),
        "failedEndpoints" to failedEndpoints,
    )
}
