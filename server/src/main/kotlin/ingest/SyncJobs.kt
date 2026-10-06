package ch.nokillswit.ingest

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val SyncJobsServiceKey = AttributeKey<SyncJobsService>("SyncJobsService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to SyncJobsService.Jobs.id,
    "requestedAt" to SyncJobsService.Jobs.requestedAt,
)

/** The ONE sortable whitelist — derived from the column map so the two can never drift. */
val SYNC_JOB_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

private val SKIP_LOCKED = ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED)

/**
 * The first key of the claim's per-connection advisory lock — the ASCII bytes of "SYNC" as a positive `int`; the second
 * key is the connection id. The two-`int` form of `pg_try_advisory_xact_lock` lives in its own key space, apart from
 * the single-`bigint` keys (`metrics/MetricsStore.kt`'s `DIM_DATE_LOCK_KEY`), so the two can never collide.
 * `.claude/docs/persistence.md` lists it.
 */
internal const val CLAIM_LOCK_NAMESPACE = 0x53594E43

private const val MILLIS_PER_SECOND = 1000L

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
    private val afterClaimLock: suspend (connectionId: UInt) -> Unit,
) {
    constructor(
        database: R2dbcDatabase,
        defaultMaxAttempts: Int,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(database, defaultMaxAttempts, clock, {})

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
     * any, else the PENDING one [claim] would take first (`priority`, `requested_at`, then id — so a manual job
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

    /**
     * Claims one PENDING job, or a RUNNING job whose lease has expired (`FOR UPDATE SKIP LOCKED`,
     * so concurrent claimers never contend on the same row) — at most one RUNNING job per
     * connection, of ANY kind (the partial unique index only dedupes per KIND). A candidate
     * past `max_attempts` fails `RETRIES_EXHAUSTED`; a `config_revision` mismatch (the connection
     * was edited since enqueue) cancels it `CONFIG_CHANGED` — either way the scan continues to the
     * next candidate rather than returning null.
     *
     * **Per-connection exclusivity under concurrency.** The "no other job of this connection is RUNNING" read alone
     * is a check-then-act race: `SKIP LOCKED` only separates claimers on the SAME row, so two claimers (two worker
     * PROCESSES — one process's slots claim sequentially) taking different kinds of one connection — say its SYNC and
     * its DERIVE — could both read "nothing RUNNING" before either commits. Each claimer therefore takes a transaction-scoped advisory
     * try-lock on the connection ([CLAIM_LOCK_NAMESPACE], connection id) BEFORE that read and holds it until its
     * claim commits (`READ COMMITTED`: the loser's next statement would see the winner's RUNNING row anyway; the
     * lock is what makes the loser not get that far concurrently). A claimer that cannot get the lock does NOT wait:
     * it skips the candidate, leaving it PENDING for a later tick, so other connections' candidates are never held
     * up and slots never serialise. An advisory try-lock cannot deadlock — it never waits, and it touches no table
     * row, so it cannot conflict with the writers of the connection row (`DataSourceService`'s `FOR UPDATE`,
     * config PUT, soft delete) nor with the foreign-key share lock every job insert takes on it.
     */
    suspend fun claim(workerId: String, leaseSeconds: Long, now: Long = clock()): SyncJobClaim? = suspendTransaction(database) {
        val expiredLease = (Jobs.status eq SyncJobStatus.RUNNING.name) and (Jobs.leaseUntil less now)
        val candidates = Jobs.selectAll()
            .where { (Jobs.status eq SyncJobStatus.PENDING.name) or expiredLease }
            .orderBy(Jobs.priority to SortOrder.ASC, Jobs.requestedAt to SortOrder.ASC, Jobs.id to SortOrder.ASC)
            .forUpdate(SKIP_LOCKED)
            .toList()
        for (row in candidates) {
            val jobId = row[Jobs.id].value
            val connId = row[Jobs.connectionId].value
            val attemptCount = row[Jobs.attempt]
            val maxAttemptsForRow = row[Jobs.maxAttempts]
            if (attemptCount >= maxAttemptsForRow) {
                terminal(jobId, SyncJobStatus.FAILED, "RETRIES_EXHAUSTED", now)
                continue
            }
            val connectionRow = DataSourceService.Connections.selectAll()
                .where { DataSourceService.Connections.id eq connId }
                .toList().singleOrNull()
            val currentConfigRevision = connectionRow?.get(DataSourceService.Connections.configRevision)
            if (currentConfigRevision != null && currentConfigRevision != row[Jobs.configRevision]) {
                terminal(jobId, SyncJobStatus.CANCELLED, "CONFIG_CHANGED", now)
                continue
            }
            if (!claimableForConnection(connId, jobId)) continue
            val syncIntervalMinutes = connectionRow?.get(DataSourceService.Connections.syncIntervalMinutes) ?: 0
            val connectorKind = connectionRow?.get(DataSourceService.Connections.kind)
                ?.let { DataSourceKind.valueOf(it) } ?: DataSourceKind.JIRA_CLOUD
            Jobs.update({ Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.RUNNING.name
                it[leaseOwner] = workerId
                it[leaseUntil] = now + leaseSeconds * 1000
                it[heartbeatAt] = now
                it[attempt] = attemptCount + 1
                if (row[Jobs.startedAt] == null) it[startedAt] = now
            }
            return@suspendTransaction SyncJobClaim(
                id = jobId,
                connectionId = connId,
                connectorKind = connectorKind,
                kind = SyncJobKind.valueOf(row[Jobs.kind]),
                attempt = attemptCount + 1,
                maxAttempts = maxAttemptsForRow,
                syncIntervalMinutes = syncIntervalMinutes,
            )
        }
        null
    }

    /**
     * The per-connection exclusivity gate of [claim] (see its KDoc), run INSIDE its transaction: true only when this
     * transaction holds the connection's claim lock AND no other job of the connection is RUNNING.
     */
    private suspend fun R2dbcTransaction.claimableForConnection(connectionId: UInt, jobId: UInt): Boolean {
        if (!tryLockConnectionClaim(connectionId)) return false
        afterClaimLock(connectionId)
        return Jobs.selectAll()
            .where { (Jobs.connectionId eq connectionId) and (Jobs.status eq SyncJobStatus.RUNNING.name) and (Jobs.id neq jobId) }
            .limit(1).toList().isEmpty()
    }

    /**
     * `pg_try_advisory_xact_lock` on (claim namespace, connection id): true when this transaction now holds it
     * (re-entrant), false when another claimer does.
     */
    private suspend fun R2dbcTransaction.tryLockConnectionClaim(connectionId: UInt): Boolean =
        exec(
            "SELECT pg_try_advisory_xact_lock($CLAIM_LOCK_NAMESPACE, ${connectionId.toInt()})",
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Boolean::class.javaObjectType) == true }?.toList()?.single() == true

    private suspend fun terminal(jobId: UInt, status: SyncJobStatus, errorCode: String, now: Long) {
        Jobs.update({ Jobs.id eq jobId }) {
            it[Jobs.status] = status.name
            it[Jobs.errorCode] = errorCode
            it[finishedAt] = now
        }
    }

    /**
     * Extends the lease; `false` means the lease was already lost (reclaimed by another worker, a newer attempt took
     * over, or the job is no longer RUNNING). The run is fenced by `(id, lease owner, attempt)`: [attempt] is the
     * claim's attempt number, which increments on every claim, so a stale run of the SAME worker can never act on
     * the row a later claim now owns. [progress]/[currentStream] (v0.2.0 plan §12 item 7,
     * `ingest/Stream.kt`'s `StreamContext`) are written only when non-null — the ticker's own
     * lease-only heartbeat (`ingest/IngestWorker.kt`) omits them so it never blanks out the last
     * value a stream's own heartbeat flushed.
     */
    suspend fun heartbeat(
        jobId: UInt,
        workerId: String,
        attempt: Int,
        leaseSeconds: Long,
        now: Long = clock(),
        progress: String? = null,
        currentStream: String? = null,
    ): Boolean = suspendTransaction(database) { renew(jobId, workerId, attempt, leaseSeconds, now, progress, currentStream) }

    /**
     * The ticker's lease renewal — [heartbeat] plus the cancel check in ONE transaction (one pooled connection, one
     * failure policy for the caller): [HeartbeatOutcome.CANCEL_REQUESTED] means the lease WAS extended and
     * `cancel_requested_at` is set on the row. [boundMillis] bounds the transaction server-side (see the body).
     */
    suspend fun renewLease(
        jobId: UInt,
        workerId: String,
        attempt: Int,
        leaseSeconds: Long,
        now: Long = clock(),
        boundMillis: Long? = null,
    ): HeartbeatOutcome =
        suspendTransaction(database) {
            if (boundMillis != null) {
                // Server-side bound, so an attempt the worker abandoned at its slack ends in the database too (a lock wait or a slow
                // statement), not only on the client: `lock_timeout` as a SET LOCAL (it survives Exposed's per-statement reset) and the
                // transaction's queryTimeout, whole seconds rounded UP — NOT `SET LOCAL statement_timeout`, which Exposed overwrites
                // before every statement (`.claude/docs/persistence.md`).
                exec("SET LOCAL lock_timeout = $boundMillis")
                queryTimeout = Math.ceilDiv(boundMillis, MILLIS_PER_SECOND).toInt()
            }
            when {
                !renew(jobId, workerId, attempt, leaseSeconds, now, null, null) -> HeartbeatOutcome.LOST
                Jobs.selectAll().where { Jobs.id eq jobId }.toList().singleOrNull()?.get(Jobs.cancelRequestedAt) != null ->
                    HeartbeatOutcome.CANCEL_REQUESTED
                else -> HeartbeatOutcome.RENEWED
            }
        }

    private suspend fun renew(
        jobId: UInt,
        workerId: String,
        attempt: Int,
        leaseSeconds: Long,
        now: Long,
        progress: String?,
        currentStream: String?,
    ): Boolean =
        Jobs.update(
            {
                (Jobs.id eq jobId) and (Jobs.leaseOwner eq workerId) and (Jobs.attempt eq attempt) and
                    (Jobs.status eq SyncJobStatus.RUNNING.name)
            },
        ) {
            it[heartbeatAt] = now
            it[leaseUntil] = now + leaseSeconds * 1000
            if (progress != null) it[Jobs.progress] = progress
            if (currentStream != null) it[Jobs.currentStream] = currentStream
        } > 0

    /**
     * The terminal and release writes below are fenced like [heartbeat] and return `false`, changing NOTHING, when they
     * do not match: [finish], [fail] and [markCancelled] need `(id, attempt, status = RUNNING)`, [release] additionally
     * `lease_owner = workerId`. A later claim (a higher attempt) owns the row, or the claimer / an earlier write already
     * closed it (`RETRIES_EXHAUSTED`, `CONFIG_CHANGED`, SUCCEEDED...) without bumping the attempt — either way a stale run
     * can never reopen, re-close or overwrite it, and a shutdown release after a committed `finish` cannot reopen it.
     */
    suspend fun finish(jobId: UInt, attempt: Int, now: Long = clock()): Boolean = suspendTransaction(database) {
        Jobs.update({ runningAttempt(jobId, attempt) }) {
            it[status] = SyncJobStatus.SUCCEEDED.name
            it[finishedAt] = now
            it[leaseOwner] = null
            it[leaseUntil] = null
        } > 0
    }

    suspend fun fail(jobId: UInt, attempt: Int, errorCode: String, errorDetail: String? = null, now: Long = clock()): Boolean =
        suspendTransaction(database) {
            Jobs.update({ runningAttempt(jobId, attempt) }) {
                it[status] = SyncJobStatus.FAILED.name
                it[Jobs.errorCode] = errorCode
                it[Jobs.errorDetail] = errorDetail
                it[finishedAt] = now
                it[leaseOwner] = null
                it[leaseUntil] = null
            } > 0
        }

    /** The worker honoured `cancel_requested_at`. */
    suspend fun markCancelled(jobId: UInt, attempt: Int, now: Long = clock()): Boolean = suspendTransaction(database) {
        Jobs.update({ runningAttempt(jobId, attempt) }) {
            it[status] = SyncJobStatus.CANCELLED.name
            it[finishedAt] = now
            it[leaseOwner] = null
            it[leaseUntil] = null
        } > 0
    }

    private fun runningAttempt(jobId: UInt, attempt: Int): Op<Boolean> =
        (Jobs.id eq jobId) and (Jobs.attempt eq attempt) and (Jobs.status eq SyncJobStatus.RUNNING.name)

    /** Shutdown (`ApplicationStopping`): releases a still-RUNNING job back to `PENDING` for reclaim — the attempt count is not reset. */
    suspend fun release(jobId: UInt, workerId: String, attempt: Int): Boolean = suspendTransaction(database) {
        Jobs.update({ runningAttempt(jobId, attempt) and (Jobs.leaseOwner eq workerId) }) {
            it[status] = SyncJobStatus.PENDING.name
            it[leaseOwner] = null
            it[leaseUntil] = null
            it[heartbeatAt] = null
        } > 0
    }

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
