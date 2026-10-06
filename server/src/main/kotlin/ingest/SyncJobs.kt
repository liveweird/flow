package ch.nokillswit.ingest

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.paging.PageRequest
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val SyncJobsServiceKey = AttributeKey<SyncJobsService>("SyncJobsService")

/**
 * The sync-job queue (v0.2.0 plan §4/§5/§9, V9 `sync_jobs`): both history and command queue,
 * claimed with a lease/heartbeat by `ingest/IngestWorker.kt` (the worker role) and driven by
 * `ingest/SyncJobRoutes.kt` (the web role). [clock] is injectable for tests (the `TokenBlocklistService`
 * idiom, `auth/TokenBlocklistService.kt`).
 */
class SyncJobsService internal constructor(
    private val database: R2dbcDatabase,
    private val defaultMaxAttempts: Int,
    private val clock: () -> Long,
    /**
     * Test seam (`SyncJobQueueTest`; reachable only through the `internal` constructor, never production wiring): runs
     * inside [claim]'s transaction right after a connection's claim lock is held and before the "already RUNNING?"
     * check — how a test holds one claimer there while a second one races it.
     */
    afterClaimLock: suspend (connectionId: UInt) -> Unit,
) {
    constructor(
        database: R2dbcDatabase,
        defaultMaxAttempts: Int,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(database, defaultMaxAttempts, clock, {})

    private val reads = SyncJobReads(database)
    private val leases = SyncJobLeases(database, clock, afterClaimLock)

    object Jobs : UIntIdTable("sync_jobs") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val kind = varchar("kind", 20)
        val status = varchar("status", 20)
        val priority = integer("priority")
        val requestedByUserId = long("requested_by_user_id").nullable()
        val configRevision = long("config_revision")
        val requestedAt = long("requested_at")
        val startedAt = long("started_at").nullable()
        val finishedAt = long("finished_at").nullable()
        val attempt = integer("attempt")
        val maxAttempts = integer("max_attempts")
        val leaseOwner = varchar("lease_owner", 200).nullable()
        val leaseUntil = long("lease_until").nullable()
        val heartbeatAt = long("heartbeat_at").nullable()
        val cancelRequestedAt = long("cancel_requested_at").nullable()
        val currentStream = varchar("current_stream", 50).nullable()
        val progress = jsonb("progress").nullable()
        val errorCode = varchar("error_code", 100).nullable()
        val errorDetail = text("error_detail").nullable()
    }

    // ---- Web-role surface (ingest/SyncJobRoutes.kt) ------------------------------------------

    /**
     * `POST .../sync-jobs {kind}` — PURGE is internal-only and the disabled-connection check both
     * live in the route (`ingest/SyncJobRoutes.kt`).
     */
    suspend fun requestJob(
        connectionId: UInt,
        kind: SyncJobKind,
        requestedByUserId: UInt,
        configRevision: Long,
    ): SyncJobEnqueueResult = suspendTransaction(database) {
        val (id, coalesced) = enqueue(
            connectionId = connectionId,
            kind = kind,
            priority = SYNC_JOB_PRIORITY_MANUAL,
            requestedByUserId = requestedByUserId,
            configRevision = configRevision,
        )
        SyncJobEnqueueResult(id, coalesced)
    }

    // The read side — see [SyncJobReads].

    suspend fun list(connectionId: UInt, filter: SyncJobListFilter, paging: PageRequest): SyncJobListResult =
        reads.list(connectionId, filter, paging)

    suspend fun read(connectionId: UInt, jobId: UInt): SyncJobResponse? = reads.read(connectionId, jobId)

    /** The connection's currently RUNNING job id, if any — feeds `DataSourceStatus.runningJobId`. */
    suspend fun runningJobId(connectionId: UInt): UInt? = reads.runningJobId(connectionId)

    /** Batch form of [runningJobId] for the list endpoint — one query for the whole page. */
    suspend fun runningJobIdsByConnection(connectionIds: List<UInt>): Map<UInt, UInt> = reads.runningJobIdsByConnection(connectionIds)

    /** The connection's open job, in full (RUNNING first, else the PENDING one a claim would take) — see [SyncJobReads.openJob]. */
    suspend fun openJob(connectionId: UInt): SyncJobResponse? = reads.openJob(connectionId)

    /** `GET …/{id}/status`'s `lastJobs`: the most recently requested job per kind — see [SyncJobReads.lastJobsByKind]. */
    suspend fun lastJobsByKind(connectionId: UInt): Map<String, SyncJobResponse> = reads.lastJobsByKind(connectionId)

    /**
     * `PENDING` → `CANCELLED` immediately; `RUNNING` → `cancel_requested_at` (the worker honours
     * it); terminal → [CancelOutcome.ALREADY_TERMINAL].
     */
    suspend fun requestCancel(connectionId: UInt, jobId: UInt, now: Long = clock()): CancelOutcome? =
        suspendTransaction(database) {
            val row = Jobs.selectAll().where { (Jobs.id eq jobId) and (Jobs.connectionId eq connectionId) }
                .forUpdate().toList().singleOrNull() ?: return@suspendTransaction null
            when (SyncJobStatus.valueOf(row[Jobs.status])) {
                SyncJobStatus.PENDING -> {
                    Jobs.update({ Jobs.id eq jobId }) {
                        it[status] = SyncJobStatus.CANCELLED.name
                        it[finishedAt] = now
                    }
                    CancelOutcome.CANCELLED_NOW
                }
                SyncJobStatus.RUNNING -> {
                    Jobs.update({ Jobs.id eq jobId }) { it[cancelRequestedAt] = now }
                    CancelOutcome.CANCEL_REQUESTED
                }
                else -> CancelOutcome.ALREADY_TERMINAL
            }
        }

    /** Soft-deleting a connection cancels its open jobs (plan §9): `PENDING` → `CANCELLED` now, `RUNNING` → `cancel_requested_at`. */
    suspend fun cancelOpenForConnection(connectionId: UInt, now: Long = clock()): Unit = suspendTransaction(database) {
        Jobs.update({ (Jobs.connectionId eq connectionId) and (Jobs.status eq SyncJobStatus.PENDING.name) }) {
            it[status] = SyncJobStatus.CANCELLED.name
            it[finishedAt] = now
        }
        Jobs.update({ (Jobs.connectionId eq connectionId) and (Jobs.status eq SyncJobStatus.RUNNING.name) }) {
            it[cancelRequestedAt] = now
        }
    }

    // ---- Worker-role surface (ingest/IngestWorker.kt) ----------------------------------------

    /** Enqueues a scheduler-driven job (`ON CONFLICT DO NOTHING` coalescing via `uq_sync_jobs_open_per_kind`). */
    suspend fun enqueueScheduled(connectionId: UInt, kind: SyncJobKind, configRevision: Long, now: Long = clock()) {
        suspendTransaction(database) {
            enqueue(connectionId, kind, SYNC_JOB_PRIORITY_SCHEDULED, requestedByUserId = null, configRevision = configRevision, now = now)
        }
    }

    /** Shared by [requestJob] (manual) and [enqueueScheduled] — must run INSIDE the caller's transaction. */
    private suspend fun enqueue(
        connectionId: UInt,
        kind: SyncJobKind,
        priority: Int,
        requestedByUserId: UInt?,
        configRevision: Long,
        now: Long = clock(),
    ): Pair<UInt, Boolean> {
        val insertedId = Jobs.insertIgnoreAndGetId {
            it[Jobs.connectionId] = connectionId
            it[Jobs.kind] = kind.name
            it[status] = SyncJobStatus.PENDING.name
            it[Jobs.priority] = priority
            it[Jobs.requestedByUserId] = requestedByUserId?.toLong()
            it[Jobs.configRevision] = configRevision
            it[requestedAt] = now
            it[attempt] = 0
            it[maxAttempts] = defaultMaxAttempts
        }
        if (insertedId != null) return insertedId.value to false
        // Coalesced: an open (PENDING/RUNNING) job for this (connection, kind) already exists.
        val existing = Jobs.selectAll().where {
            (Jobs.connectionId eq connectionId) and (Jobs.kind eq kind.name) and
                (Jobs.status inList listOf(SyncJobStatus.PENDING.name, SyncJobStatus.RUNNING.name))
        }.toList().single()
        return existing[Jobs.id].value to true
    }

    // The claim/lease path — see [SyncJobLeases] (the claim lock and the write fences are documented there).

    /** Claims one PENDING (or lease-expired RUNNING) job under the per-connection claim lock — see [SyncJobLeases.claim]. */
    suspend fun claim(workerId: String, leaseSeconds: Long, now: Long = clock()): SyncJobClaim? =
        leases.claim(workerId, leaseSeconds, now)

    /** Extends the lease, fenced by `(id, lease owner, attempt)` — see [SyncJobLeases.heartbeat]. */
    suspend fun heartbeat(
        jobId: UInt,
        workerId: String,
        attempt: Int,
        leaseSeconds: Long,
        now: Long = clock(),
        progress: String? = null,
        currentStream: String? = null,
    ): Boolean = leases.heartbeat(jobId, workerId, attempt, leaseSeconds, now, progress, currentStream)

    /** The ticker's lease renewal plus the cancel check in one transaction — see [SyncJobLeases.renewLease]. */
    suspend fun renewLease(
        jobId: UInt,
        workerId: String,
        attempt: Int,
        leaseSeconds: Long,
        now: Long = clock(),
        boundMillis: Long? = null,
    ): HeartbeatOutcome = leases.renewLease(jobId, workerId, attempt, leaseSeconds, now, boundMillis)

    /** Fenced terminal write (SUCCEEDED) — see [SyncJobLeases.finish]. */
    suspend fun finish(jobId: UInt, attempt: Int, now: Long = clock()): Boolean = leases.finish(jobId, attempt, now)

    /** Fenced terminal write (FAILED) — see [SyncJobLeases.fail]. */
    suspend fun fail(jobId: UInt, attempt: Int, errorCode: String, errorDetail: String? = null, now: Long = clock()): Boolean =
        leases.fail(jobId, attempt, errorCode, errorDetail, now)

    /** The worker honoured `cancel_requested_at` — see [SyncJobLeases.markCancelled]. */
    suspend fun markCancelled(jobId: UInt, attempt: Int, now: Long = clock()): Boolean = leases.markCancelled(jobId, attempt, now)

    /** Shutdown: releases a still-RUNNING job back to `PENDING` for reclaim — see [SyncJobLeases.release]. */
    suspend fun release(jobId: UInt, workerId: String, attempt: Int): Boolean = leases.release(jobId, workerId, attempt)

    /**
     * Hard-deletes terminal rows older than `ingest.jobRetentionDays` — the documented
     * `sync_jobs` hard-delete exception (`.claude/docs/persistence.md`).
     */
    suspend fun prune(retentionMillis: Long, now: Long = clock()): Int = suspendTransaction(database) {
        Jobs.deleteWhere {
            (Jobs.status inList listOf(SyncJobStatus.SUCCEEDED.name, SyncJobStatus.FAILED.name, SyncJobStatus.CANCELLED.name)) and
                (Jobs.finishedAt less (now - retentionMillis))
        }
    }
}
