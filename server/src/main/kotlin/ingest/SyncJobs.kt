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
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
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
 * The sync-job queue (v0.2.0 plan §4/§5/§9, V9 `sync_jobs`): both history and command queue,
 * claimed with a lease/heartbeat by `ingest/IngestWorker.kt` (the worker role) and driven by
 * `ingest/SyncJobRoutes.kt` (the web role). [clock] is injectable for tests (the `TokenBlocklistService`
 * idiom, `auth/TokenBlocklistService.kt`).
 */
class SyncJobsService(
    private val database: R2dbcDatabase,
    private val defaultMaxAttempts: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
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
     * connection (checked in Kotlin: the partial unique index only dedupes per KIND). A candidate
     * past `max_attempts` fails `RETRIES_EXHAUSTED`; a `config_revision` mismatch (the connection
     * was edited since enqueue) cancels it `CONFIG_CHANGED` — either way the scan continues to the
     * next candidate rather than returning null.
     */
    suspend fun claim(workerId: String, leaseSeconds: Long, now: Long = clock()): SyncJobClaim? = suspendTransaction(database) {
        val expiredLease = (Jobs.status eq SyncJobStatus.RUNNING.name) and (Jobs.leaseUntil less now)
        val candidates = Jobs.selectAll()
            .where { (Jobs.status eq SyncJobStatus.PENDING.name) or expiredLease }
            .orderBy(Jobs.priority to SortOrder.ASC, Jobs.requestedAt to SortOrder.ASC)
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
            val alreadyRunning = Jobs.selectAll()
                .where { (Jobs.connectionId eq connId) and (Jobs.status eq SyncJobStatus.RUNNING.name) and (Jobs.id neq jobId) }
                .limit(1).toList().isNotEmpty()
            if (alreadyRunning) continue
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

    private suspend fun terminal(jobId: UInt, status: SyncJobStatus, errorCode: String, now: Long) {
        Jobs.update({ Jobs.id eq jobId }) {
            it[Jobs.status] = status.name
            it[Jobs.errorCode] = errorCode
            it[finishedAt] = now
        }
    }

    /**
     * Extends the lease; `false` means the lease was already lost (reclaimed by another worker, or
     * the job is no longer RUNNING). [progress]/[currentStream] (v0.2.0 plan §12 item 7,
     * `ingest/Stream.kt`'s `StreamContext`) are written only when non-null — the ticker's own
     * lease-only heartbeat (`ingest/IngestWorker.kt`) omits them so it never blanks out the last
     * value a stream's own heartbeat flushed.
     */
    suspend fun heartbeat(
        jobId: UInt,
        workerId: String,
        leaseSeconds: Long,
        now: Long = clock(),
        progress: String? = null,
        currentStream: String? = null,
    ): Boolean =
        suspendTransaction(database) {
            Jobs.update({ (Jobs.id eq jobId) and (Jobs.leaseOwner eq workerId) and (Jobs.status eq SyncJobStatus.RUNNING.name) }) {
                it[heartbeatAt] = now
                it[leaseUntil] = now + leaseSeconds * 1000
                if (progress != null) it[Jobs.progress] = progress
                if (currentStream != null) it[Jobs.currentStream] = currentStream
            } > 0
        }

    suspend fun isCancelRequested(jobId: UInt): Boolean = suspendTransaction(database) {
        Jobs.selectAll().where { Jobs.id eq jobId }.toList().singleOrNull()?.get(Jobs.cancelRequestedAt) != null
    }

    suspend fun finish(jobId: UInt, now: Long = clock()) {
        suspendTransaction(database) {
            Jobs.update({ Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.SUCCEEDED.name
                it[finishedAt] = now
                it[leaseOwner] = null
                it[leaseUntil] = null
            }
        }
    }

    suspend fun fail(jobId: UInt, errorCode: String, errorDetail: String? = null, now: Long = clock()) {
        suspendTransaction(database) {
            Jobs.update({ Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.FAILED.name
                it[Jobs.errorCode] = errorCode
                it[Jobs.errorDetail] = errorDetail
                it[finishedAt] = now
                it[leaseOwner] = null
                it[leaseUntil] = null
            }
        }
    }

    /** The worker honoured `cancel_requested_at`. */
    suspend fun markCancelled(jobId: UInt, now: Long = clock()) {
        suspendTransaction(database) {
            Jobs.update({ Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.CANCELLED.name
                it[finishedAt] = now
                it[leaseOwner] = null
                it[leaseUntil] = null
            }
        }
    }

    /** Shutdown (`ApplicationStopping`): releases a still-RUNNING job back to `PENDING` for reclaim — the attempt count is not reset. */
    suspend fun release(jobId: UInt) {
        suspendTransaction(database) {
            Jobs.update({ Jobs.id eq jobId }) {
                it[status] = SyncJobStatus.PENDING.name
                it[leaseOwner] = null
                it[leaseUntil] = null
                it[heartbeatAt] = null
            }
        }
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
