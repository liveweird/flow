package ch.nokillswit.ingest

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabRawStoreKey
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
 * each kind, and the connection's open job (RUNNING, else the PENDING one the worker would claim first), if any. Split out of
 * `DataSourceRoutes.kt` like `SyncJobRoutes.kt` — one file per sub-resource.
 */
fun Application.configureSyncStatusRoutes() {
    if (!servesApi()) return

    val dataSources = attributes[DataSourceServiceKey]
    val syncJobs = attributes[SyncJobsServiceKey]
    val cursors = attributes[SyncCursorsServiceKey]
    val rawStore = attributes[JiraRawStoreKey]
    val gitLabStore = attributes.getOrNull(GitLabRawStoreKey)

    routing {
        authenticate {
            get<DataSourceStatusResource> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val connectionId = route.parent.id
                val connection = dataSources.read(connectionId).orNotFound("Data source")
                val openJob = syncJobs.openJob(connectionId)
                val runningJobId = openJob?.takeIf { it.status == SyncJobStatus.RUNNING }?.id
                val response = SyncStatusResponse(
                    connection = connection.copy(status = connection.status.copy(runningJobId = runningJobId)),
                    cursors = cursors.getAll(connectionId).map {
                        SyncCursorSummary(it.stream, it.watermarkAt, it.cursor, it.lastCompletedAt)
                    },
                    counts = counts(connection.kind, rawStore, gitLabStore, connectionId),
                    lastJobs = syncJobs.lastJobsByKind(connectionId),
                    currentJob = openJob,
                )
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}

/**
 * The counts of the connection's own kind: a GitLab row reports `gitlab` (null when this process has no GitLab store) and zeros for the
 * Jira integers, which count `raw.jira_*` rows it can never have.
 */
private suspend fun counts(kind: DataSourceKind, rawStore: JiraRawStore, gitLabStore: GitLabRawStore?, connectionId: UInt): SyncCounts =
    when (kind) {
        DataSourceKind.JIRA_CLOUD -> jiraCounts(rawStore, connectionId)
        DataSourceKind.GITLAB -> SyncCounts(
            rawIssues = 0, tombstonedDeleted = 0, tombstonedMovedOut = 0, changelogs = 0, worklogs = 0, entitiesByKind = emptyMap(),
            needsProcessing = 0, gitlab = gitLabStore?.counts(connectionId),
        )
    }

private suspend fun jiraCounts(rawStore: JiraRawStore, connectionId: UInt): SyncCounts = SyncCounts(
    rawIssues = rawStore.countIssues(connectionId),
    tombstonedDeleted = rawStore.countIssuesDeleted(connectionId),
    tombstonedMovedOut = rawStore.countIssuesMovedOut(connectionId),
    changelogs = rawStore.countChangelogs(connectionId),
    worklogs = rawStore.countWorklogs(connectionId, excludeDeleted = true),
    entitiesByKind = rawStore.entityCountsByKind(connectionId),
    needsProcessing = rawStore.countNeedsProcessing(connectionId),
)
