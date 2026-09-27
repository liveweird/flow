package ch.nokillswit.ingest

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
@Resource("profile")
class DataSourceProfileResource(val parent: DataSourcesRoute.Id)

private val PROFILE_ROUTE_JSON = Json { ignoreUnknownKeys = true }

/**
 * `GET /api/v1/data-sources/{id}/profile` (v0.2.0 plan §8/§9/§12 item 9): ADMIN only, read-only —
 * the stored `source_connections.profile`/`profile_at`, computed by the PROFILE stream after every
 * SYNC/REPROCESS (`jira/JiraProfileStream.kt`). `computedAt` is null before the connection's first
 * successful PROCESS pass. Split out of `DataSourceRoutes.kt`/`SyncStatusRoutes.kt` — one file per
 * sub-resource.
 */
fun Application.configureDataProfileRoutes() {
    if (!servesApi()) return

    val dataSources = attributes[DataSourceServiceKey]

    routing {
        authenticate {
            get<DataSourceProfileResource> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val stored = dataSources.readProfile(route.parent.id).orNotFound("Data source")
                val sections = stored.profileJson
                    ?.let { PROFILE_ROUTE_JSON.decodeFromString<DataProfileSections>(it) }
                    ?: DataProfileSections()
                call.respond(HttpStatusCode.OK, sections.withComputedAt(stored.profileAt))
            }
        }
    }
}
