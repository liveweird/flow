package ch.nokillswit.ingest

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.ingest.SyncJobsService.Jobs
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to SyncJobsService.Jobs.id,
    "requestedAt" to SyncJobsService.Jobs.requestedAt,
)

/** The ONE sortable whitelist — derived from the column map so the two can never drift. */
val SYNC_JOB_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The read side of the sync-job queue (`SyncJobsService`'s facade delegates here): the paged list, single reads, and
 * the running/open/last-job lookups behind `ingest/SyncJobRoutes.kt` and `ingest/SyncStatusRoutes.kt`. Nothing here
 * writes, and nothing takes a lease or a lock.
 */
internal class SyncJobReads(private val database: R2dbcDatabase) {

    suspend fun list(connectionId: UInt, filter: SyncJobListFilter, paging: PageRequest): SyncJobListResult =
        suspendTransaction(database) {
            var predicate: Op<Boolean> = Jobs.connectionId eq connectionId
            filter.kind?.let { predicate = predicate and (Jobs.kind eq it.name) }
            filter.status?.let { predicate = predicate and (Jobs.status eq it.name) }
            val total = Jobs.selectAll().where { predicate }.count()
            val rows = Jobs.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
            SyncJobListResult(rows.map { it.toResponse() }, total)
        }

    suspend fun read(connectionId: UInt, jobId: UInt): SyncJobResponse? = suspendTransaction(database) {
        Jobs.selectAll().where { (Jobs.id eq jobId) and (Jobs.connectionId eq connectionId) }
            .toList().singleOrNull()?.toResponse()
    }

    /** The connection's currently RUNNING job id, if any — feeds `DataSourceStatus.runningJobId`. */
    suspend fun runningJobId(connectionId: UInt): UInt? = suspendTransaction(database) {
        Jobs.selectAll().where { (Jobs.connectionId eq connectionId) and (Jobs.status eq SyncJobStatus.RUNNING.name) }
            .limit(1).toList().singleOrNull()?.let { it[Jobs.id].value }
    }

    /** Batch form of [runningJobId] for the list endpoint — one query for the whole page. */
    suspend fun runningJobIdsByConnection(connectionIds: List<UInt>): Map<UInt, UInt> {
        if (connectionIds.isEmpty()) return emptyMap()
        return suspendTransaction(database) {
            Jobs.selectAll().where { (Jobs.connectionId inList connectionIds) and (Jobs.status eq SyncJobStatus.RUNNING.name) }
                .toList().associate { it[Jobs.connectionId].value to it[Jobs.id].value }
        }
    }

    /**
     * The connection's open job, in full — `GET …/{id}/status`'s `currentJob`, in ONE query: the RUNNING job if
     * any, else the PENDING one [SyncJobLeases.claim] would take first (`priority`, `requested_at`, then id — so a manual job
     * outranks an earlier scheduled one). A client thus sees, and polls, a job that has not been claimed yet,
     * and a claim landing mid-read can never make both halves miss. `runningJobId` stays RUNNING-only.
     */
    suspend fun openJob(connectionId: UInt): SyncJobResponse? = suspendTransaction(database) {
        val runningFirst = Case().When(Jobs.status eq SyncJobStatus.RUNNING.name, intLiteral(0)).Else(intLiteral(1))
        Jobs.selectAll()
            .where {
                (Jobs.connectionId eq connectionId) and
                    (Jobs.status inList listOf(SyncJobStatus.RUNNING.name, SyncJobStatus.PENDING.name))
            }
            .orderBy(
                runningFirst to SortOrder.ASC,
                Jobs.priority to SortOrder.ASC,
                Jobs.requestedAt to SortOrder.ASC,
                Jobs.id to SortOrder.ASC,
            )
            .limit(1).toList().singleOrNull()?.toResponse()
    }

    /**
     * `GET …/{id}/status`'s `lastJobs` (v0.2.0 plan §9): the most recently requested job of each
     * kind ever run against this connection (terminal or not), keyed by [SyncJobKind.name] — a kind
     * never requested is simply absent from the map.
     */
    suspend fun lastJobsByKind(connectionId: UInt): Map<String, SyncJobResponse> = suspendTransaction(database) {
        SyncJobKind.entries.mapNotNull { kind ->
            Jobs.selectAll().where { (Jobs.connectionId eq connectionId) and (Jobs.kind eq kind.name) }
                .orderBy(Jobs.requestedAt to SortOrder.DESC)
                .limit(1)
                .toList().singleOrNull()?.let { kind.name to it.toResponse() }
        }.toMap()
    }

    private fun ResultRow.toResponse() = SyncJobResponse(
        id = this[Jobs.id].value,
        connectionId = this[Jobs.connectionId].value,
        kind = SyncJobKind.valueOf(this[Jobs.kind]),
        status = SyncJobStatus.valueOf(this[Jobs.status]),
        priority = this[Jobs.priority],
        requestedByUserId = this[Jobs.requestedByUserId]?.toUInt(),
        configRevision = this[Jobs.configRevision],
        requestedAt = this[Jobs.requestedAt],
        startedAt = this[Jobs.startedAt],
        finishedAt = this[Jobs.finishedAt],
        attempt = this[Jobs.attempt],
        maxAttempts = this[Jobs.maxAttempts],
        leaseUntil = this[Jobs.leaseUntil],
        heartbeatAt = this[Jobs.heartbeatAt],
        cancelRequestedAt = this[Jobs.cancelRequestedAt],
        currentStream = this[Jobs.currentStream],
        progress = this[Jobs.progress]?.let { Json.parseToJsonElement(it) as? JsonObject },
        errorCode = this[Jobs.errorCode],
        errorDetail = this[Jobs.errorDetail],
    )
}
