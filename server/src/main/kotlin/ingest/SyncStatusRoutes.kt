package ch.nokillswit.ingest

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraRawStoreKey
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("status")
class DataSourceStatusResource(val parent: DataSourcesRoute.Id)

/**
 * `GET /api/v1/data-sources/{id}/status` (v0.2.0 plan §9/§12 item 7): ADMIN only, read-only — a
 * connection summary, every persisted stream cursor, raw-store row counts, the most recent job of
 * each kind, and the connection's currently RUNNING job, if any. Split out of
 * `DataSourceRoutes.kt` like `SyncJobRoutes.kt` — one file per sub-resource.
 */
fun Application.configureSyncStatusRoutes() {
    if (!servesApi()) return

    val dataSources = attributes[DataSourceServiceKey]
    val syncJobs = attributes[SyncJobsServiceKey]
    val cursors = attributes[SyncCursorsServiceKey]
    val rawStore = attributes[JiraRawStoreKey]

    routing {
        authenticate {
            get<DataSourceStatusResource> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val connectionId = route.parent.id
                val connection = dataSources.read(connectionId).orNotFound("Data source")
                val runningJob = syncJobs.runningJob(connectionId)
                val response = SyncStatusResponse(
                    connection = connection.copy(status = connection.status.copy(runningJobId = runningJob?.id)),
                    cursors = cursors.getAll(connectionId).map {
                        SyncCursorSummary(it.stream, it.watermarkAt, it.cursor, it.lastCompletedAt)
                    },
                    counts = counts(rawStore, connectionId),
                    lastJobs = syncJobs.lastJobsByKind(connectionId),
                    currentJob = runningJob,
                )
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}

private suspend fun counts(rawStore: JiraRawStore, connectionId: UInt): SyncCounts = SyncCounts(
    rawIssues = rawStore.countIssues(connectionId),
    tombstonedDeleted = rawStore.countIssuesDeleted(connectionId),
    tombstonedMovedOut = rawStore.countIssuesMovedOut(connectionId),
    changelogs = rawStore.countChangelogs(connectionId),
    worklogs = rawStore.countWorklogs(connectionId, excludeDeleted = true),
    entitiesByKind = rawStore.entityCountsByKind(connectionId),
    needsProcessing = rawStore.countNeedsProcessing(connectionId),
)
