package ch.nokillswit.ingest

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("sync-jobs")
class SyncJobsResource(val parent: DataSourcesRoute.Id) {
    @Serializable
    @Resource("{jobId}")
    class Id(val parent: SyncJobsResource, val jobId: UInt) {
        @Serializable
        @Resource("cancel")
        class Cancel(val parent: Id)
    }
}

/**
 * The sync-job API (v0.2.0 plan §9): ADMIN-only, `requireAdmin` before `call.receive()` on the
 * enqueue mutation (403 wins over 400 even for a malformed body). `PURGE` is internal-only — the
 * scheduler enqueues it (`ingest/IngestWorker.kt`'s `enqueueDue`), a caller requesting it is 400.
 */
fun Application.configureSyncJobRoutes() {
    if (!servesApi()) return

    val syncJobs = attributes[SyncJobsServiceKey]
    val dataSources = attributes[DataSourceServiceKey]

    suspend fun requireConnection(id: UInt) = dataSources.read(id).orNotFound("Data source")

    routing {
        authenticate {
            post<SyncJobsResource> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = call.receive<SyncJobRequest>()
                if (request.kind == SyncJobKind.PURGE) throw BadRequestException("PURGE jobs are scheduled internally, not requested")
                val connectionId = route.parent.id
                val connection = requireConnection(connectionId)
                if (!connection.enabled) throw BadRequestException("Data source is disabled")
                val result = syncJobs.requestJob(connectionId, request.kind, caller.userId, connection.configRevision)
                val job = syncJobs.read(connectionId, result.jobId).orVanished("Sync job", result.jobId)
                audit(
                    "sync_job.requested",
                    "byUserId" to caller.userId.toLong(),
                    "dataSourceId" to connectionId.toLong(),
                    "jobId" to result.jobId.toLong(),
                    "kind" to request.kind.name,
                    "coalesced" to result.coalesced,
                )
                call.respond(HttpStatusCode.Accepted, SyncJobActionResult(job, result.coalesced))
            }
            get<SyncJobsResource> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val connectionId = route.parent.id
                requireConnection(connectionId)
                val paging = call.parsePaging(
                    sortable = SYNC_JOB_SORT_FIELDS,
                    defaultSort = listOf(SortField("requestedAt", descending = true)),
                )
                val filter = syncJobListFilter(call)
                val result = syncJobs.list(connectionId, filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            get<SyncJobsResource.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val connectionId = route.parent.parent.id
                requireConnection(connectionId)
                val job = syncJobs.read(connectionId, route.jobId).orNotFound("Sync job")
                call.respond(HttpStatusCode.OK, job)
            }
            post<SyncJobsResource.Id.Cancel> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val connectionId = route.parent.parent.parent.id
                requireConnection(connectionId)
                val jobId = route.parent.jobId
                when (syncJobs.requestCancel(connectionId, jobId) ?: throw NotFoundException("Sync job not found")) {
                    CancelOutcome.ALREADY_TERMINAL -> throw ConflictException("Sync job has already finished")
                    CancelOutcome.CANCELLED_NOW, CancelOutcome.CANCEL_REQUESTED -> Unit
                }
                audit(
                    "sync_job.cancel_requested",
                    "byUserId" to caller.userId.toLong(),
                    "dataSourceId" to connectionId.toLong(),
                    "jobId" to jobId.toLong(),
                )
                val job = syncJobs.read(connectionId, jobId).orVanished("Sync job", jobId)
                call.respond(HttpStatusCode.Accepted, job)
            }
        }
    }
}

private fun syncJobListFilter(call: ApplicationCall): SyncJobListFilter = SyncJobListFilter(
    kind = call.request.queryParameters.optionalEnum<SyncJobKind>("kind"),
    status = call.request.queryParameters.optionalEnum<SyncJobStatus>("status"),
)
