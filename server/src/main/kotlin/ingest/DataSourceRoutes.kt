package ch.nokillswit.ingest

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/data-sources")
class DataSourcesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: DataSourcesRoute = DataSourcesRoute(), val id: UInt)
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

    routing {
        authenticate {
            get<DataSourcesRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val paging = call.parsePaging(sortable = DATA_SOURCE_SORT_FIELDS)
                val filter = DataSourceListFilter(name = call.request.queryParameters.optionalString("name"))
                val result = service.list(filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
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
                call.respond(HttpStatusCode.OK, service.read(route.id).orNotFound("Data source"))
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
                audit("data_source.deleted", "byUserId" to caller.userId.toLong(), "dataSourceId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
