package ch.nokillswit.ingest

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.paging.PageRequest
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val SyncJobsServiceKey = AttributeKey<SyncJobsService>("SyncJobsService")

/** `GREATEST(column, value)`: the larger of a stored and a new value, computed by the database inside the UPDATE (no read-modify-write). */
private fun greatest(column: Column<*>, value: Long): CustomFunction<Long?> =
    CustomFunction("GREATEST", LongColumnType(), column, longLiteral(value))

/**
 * The sync-job queue (v0.2.0 plan §4/§5/§9, V9 `sync_jobs`): both history and command queue,
 * claimed with a lease/heartbeat by `ingest/IngestWorker.kt` (the worker role) and driven by
 * `ingest/SyncJobRoutes.kt` (the web role). [clock] is injectable for tests (the `TokenBlocklistService`
 * idiom, `auth/TokenBlocklistService.kt`). [deriveQuietMillis] is the DERIVE debounce window (`ingest.deriveQuietSeconds`, see
 * [enqueueDebouncedDerive]); 0 — the default of a directly constructed service — means a configuration-caused DERIVE is not delayed.
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
    private val deriveQuietMillis: Long = 0,
) {
    constructor(
        database: R2dbcDatabase,
        defaultMaxAttempts: Int,
        clock: () -> Long = System::currentTimeMillis,
        deriveQuietMillis: Long = 0,
    ) : this(database, defaultMaxAttempts, clock, {}, deriveQuietMillis)

    private val reads = SyncJobReads(database, clock)
    private val leases = SyncJobLeases(database, clock, afterClaimLock)

    object Jobs : UIntIdTable("sync_jobs") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val kind = varchar("kind", 20)
        val status = varchar("status", 20)
        val priority = integer("priority")
        val requestedByUserId = long("requested_by_user_id").nullable()
        val configRevision = long("config_revision")
        val requestedAt = long("requested_at")
        val notBefore = long("not_before").nullable()
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

    /** The queue depth (due PENDING, RUNNING) over every connection, or the given ones - the telemetry sampler's `queue.*` gauges. */
    suspend fun statusCounts(connectionIds: Collection<UInt>? = null): SyncQueueCounts = reads.statusCounts(connectionIds)

    /** [statusCounts] per connection (a connection with nothing due or running is absent) - the telemetry panel's per-connection queue. */
    suspend fun queueByConnection(): Map<UInt, SyncQueueCounts> = reads.queueByConnection()

    /** The latest requested SYNC job per connection (with [backfillOnly]: carrying `backfill = 1`) - see [SyncJobReads.latestSyncs]. */
    suspend fun latestSyncs(backfillOnly: Boolean): Map<UInt, SyncJobBrief> = reads.latestSyncs(backfillOnly)

    /** The oldest ended SYNC job's `finished_at` before [to], or null - how far back the retained history reaches. */
    suspend fun oldestFinishedAt(to: Long): Long? = reads.oldestFinishedAt(to)

    /** Finished SYNC jobs per connection and `finished_at` bucket in `[from, to)` - see [SyncJobReads.bucketStats]. */
    suspend fun bucketStats(from: Long, bucketMillis: Long, to: Long = Long.MAX_VALUE): List<SyncBucketStat> =
        reads.bucketStats(from, bucketMillis, to)

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

    /**
     * Enqueues a scheduler-driven job, due at once (`ON CONFLICT DO NOTHING` coalescing via `uq_sync_jobs_open_per_kind`). Also what a
     * chained DERIVE (after a SYNC/RECONCILE/REPROCESS) uses: coalescing into a PENDING DERIVE that [enqueueDebouncedDerive] delayed
     * pulls its `not_before` back to "now" instead of inheriting the delay.
     */
    suspend fun enqueueScheduled(connectionId: UInt, kind: SyncJobKind, configRevision: Long, now: Long = clock()) {
        suspendTransaction(database) {
            enqueue(connectionId, kind, SYNC_JOB_PRIORITY_SCHEDULED, requestedByUserId = null, configRevision = configRevision, now = now)
        }
    }

    /**
     * Enqueues the DERIVE a CONFIGURATION change causes (`MetricsSettingsService.bumpRevision`, and the worker's follow-up when the
     * revision moved under a RUNNING derive): scheduled no earlier than `now + ingest.deriveQuietSeconds`, so a burst of edits
     * becomes one DERIVE a quiet period after the last one. With a PENDING DERIVE already queued its `not_before` moves out
     * (`GREATEST`, atomically) instead of a second row being inserted — and an already-due PENDING one (`not_before` null: a chained or
     * manual DERIVE) is left alone, it runs sooner anyway; a RUNNING one coalesces as before and the worker's post-run revision check
     * re-derives (debounced again). A zero quiet period makes this exactly [enqueueScheduled]. Must run INSIDE the caller's transaction
     * or opens its own (nested into the caller's, `bumpRevision`'s shape).
     */
    suspend fun enqueueDebouncedDerive(connectionId: UInt, configRevision: Long, now: Long = clock()) {
        suspendTransaction(database) {
            enqueue(
                connectionId, SyncJobKind.DERIVE, SYNC_JOB_PRIORITY_SCHEDULED, requestedByUserId = null, configRevision = configRevision,
                now = now, notBefore = if (deriveQuietMillis > 0) now + deriveQuietMillis else null,
            )
        }
    }

    /**
     * Shared by [requestJob] (manual), [enqueueScheduled] and [enqueueDebouncedDerive] — must run INSIDE the caller's transaction.
     * [notBefore] null = due at once (and pulls a delayed PENDING job of the same (connection, kind) forward); non-null = debounced.
     */
    private suspend fun enqueue(
        connectionId: UInt,
        kind: SyncJobKind,
        priority: Int,
        requestedByUserId: UInt?,
        configRevision: Long,
        now: Long = clock(),
        notBefore: Long? = null,
    ): Pair<UInt, Boolean> {
        val insertedId = Jobs.insertIgnoreAndGetId {
            it[Jobs.connectionId] = connectionId
            it[Jobs.kind] = kind.name
            it[status] = SyncJobStatus.PENDING.name
            it[Jobs.priority] = priority
            it[Jobs.requestedByUserId] = requestedByUserId?.toLong()
            it[Jobs.configRevision] = configRevision
            it[requestedAt] = now
            it[Jobs.notBefore] = notBefore
            it[attempt] = 0
            it[maxAttempts] = defaultMaxAttempts
        }
        if (insertedId != null) return insertedId.value to false
        // Coalesced: an open (PENDING/RUNNING) job for this (connection, kind) already exists.
        val existing = Jobs.selectAll().where {
            (Jobs.connectionId eq connectionId) and (Jobs.kind eq kind.name) and
                (Jobs.status inList listOf(SyncJobStatus.PENDING.name, SyncJobStatus.RUNNING.name))
        }.toList().single()
        reschedule(existing[Jobs.id].value, configRevision, notBefore)
        return existing[Jobs.id].value to true
    }

    /**
     * A coalesced enqueue's effect on the PENDING job it merged into (a RUNNING one is not matched: it already started). Only a DELAYED
     * job (`not_before` set) is touched: a debounced request pushes it out to the later of the two times, an immediate one
     * clears it. Both raise the job's `config_revision` to the caller's (`GREATEST`, so a concurrent bump can never lower it). A connection
     * edit does the same for a delayed DERIVE inside its own transaction (`DataSourceService.update`), which is what keeps the claim from
     * cancelling it `CONFIG_CHANGED`. One conditional UPDATE each, so concurrent bumps cannot lose a push.
     */
    private suspend fun reschedule(jobId: UInt, configRevision: Long, notBefore: Long?) {
        val delayedPending = (Jobs.id eq jobId) and (Jobs.status eq SyncJobStatus.PENDING.name) and Jobs.notBefore.isNotNull()
        if (notBefore == null) {
            Jobs.update({ delayedPending }) {
                it[Jobs.notBefore] = null
                it[Jobs.configRevision] = greatest(Jobs.configRevision, configRevision)
            }
        } else {
            Jobs.update({ delayedPending }) {
                it[Jobs.notBefore] = greatest(Jobs.notBefore, notBefore)
                it[Jobs.configRevision] = greatest(Jobs.configRevision, configRevision)
            }
        }
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

    /**
     * Fenced terminal write (FAILED) of a SYNC that stopped at a ceiling,
     * with the trip counter and pause - see [SyncJobLeases.failTripped].
     */
    suspend fun failTripped(
        jobId: UInt,
        attempt: Int,
        trip: CeilingTripException,
        errorDetail: String?,
        pauseAfterTrips: Int,
        now: Long = clock(),
    ): FailedWrite? = leases.failTripped(jobId, attempt, trip, errorDetail, pauseAfterTrips, now)

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

/**
 * Run INSIDE a data-source PUT's transaction (`DataSourceService.update`, which bumps `source_connections.config_revision`, even on a
 * rename): restamps the connection's DELAYED PENDING DERIVE (V27 `not_before`) with the new revision. The claim cancels a job whose
 * stored `config_revision` differs from the connection's (`CONFIG_CHANGED`), and nothing would re-derive until the next interval SYNC.
 * The caller only calls it while the connection stays enabled (a disabled one is not derived).
 */
internal suspend fun R2dbcTransaction.restampDelayedDerive(connectionId: UInt, newRevision: Long) {
    SyncJobsService.Jobs.update({
        (SyncJobsService.Jobs.connectionId eq connectionId) and (SyncJobsService.Jobs.kind eq SyncJobKind.DERIVE.name) and
            (SyncJobsService.Jobs.status eq SyncJobStatus.PENDING.name) and SyncJobsService.Jobs.notBefore.isNotNull()
    }) { it[SyncJobsService.Jobs.configRevision] = newRevision }
}
