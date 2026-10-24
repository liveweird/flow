package ch.nokillswit.ingest

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.ingest.SyncJobsService.Jobs
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to SyncJobsService.Jobs.id,
    "requestedAt" to SyncJobsService.Jobs.requestedAt,
)

/** The ONE sortable whitelist — derived from the column map so the two can never drift. */
val SYNC_JOB_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/** The queue depth [SyncJobReads.statusCounts] reads: jobs a worker could claim now, and jobs running. */
data class SyncQueueCounts(val pending: Long, val running: Long)

/** One job of [SyncJobReads.latestSyncs], reduced to what the telemetry panel shows: when it ran and how it stands. */
data class SyncJobBrief(val startedAt: Long?, val finishedAt: Long?, val status: String)

/**
 * The SYNC jobs of one connection that FINISHED inside one time bucket ([SyncJobReads.bucketStats]): how many ended ([runs], a
 * cancelled job is no run), how many [failures], the wall-clock [avgDurationMs]/[maxDurationMs] of those that started, and the sums
 * of the four `jira*` progress counters the Jira connector stamps (`.claude/docs/ingestion.md` "Progress counters").
 */
data class SyncBucketStat(
    val connectionId: UInt,
    val bucketStart: Long,
    val runs: Long,
    val failures: Long,
    val avgDurationMs: Double?,
    val maxDurationMs: Long?,
    val rateLimited: Long,
    val backoffMs: Long,
    val tenantPausedMs: Long,
    val nearLimitPauses: Long,
)

/** The sum of a numeric `progress` key over the group; a missing or non-numeric key adds nothing. */
private fun progressSum(key: String) =
    "COALESCE(SUM(CASE WHEN jsonb_typeof(progress -> '$key') = 'number' THEN (progress ->> '$key')::numeric END), 0)::bigint"

/**
 * The read side of the sync-job queue (`SyncJobsService`'s facade delegates here): the paged list, single reads, and
 * the running/open/last-job lookups behind `ingest/SyncJobRoutes.kt` and `ingest/SyncStatusRoutes.kt`. Nothing here
 * writes, and nothing takes a lease or a lock.
 */
internal class SyncJobReads(private val database: R2dbcDatabase, private val clock: () -> Long) {

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
     * any, else the PENDING one [SyncJobLeases.claim] would take first (a due job before a delayed one, then `priority`,
     * `requested_at`, then id — so a manual job outranks an earlier scheduled one).
     * A client thus sees, and polls, a job that has not been claimed yet, and a claim landing mid-read can never make both
     * halves miss. `runningJobId` stays RUNNING-only.
     */
    suspend fun openJob(connectionId: UInt): SyncJobResponse? = suspendTransaction(database) {
        val now = clock()
        // RUNNING first, then the due PENDING jobs, then a delayed one (`not_before` ahead) that a claim would skip for now.
        val runningFirst = Case()
            .When(Jobs.status eq SyncJobStatus.RUNNING.name, intLiteral(0))
            .When(Jobs.notBefore.isNotNull() and (Jobs.notBefore greater now), intLiteral(2))
            .Else(intLiteral(1))
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
     * The queue's depth for the telemetry sampler (`telemetry/`): the jobs a worker could take right now ([pending]: PENDING with
     * `not_before` unset or reached - a debounced DERIVE still inside its quiet window is waiting by design, not queued) and the
     * jobs it is running ([running]). Whole-table figures over every connection, in ONE grouped statement; it joins the caller's
     * transaction when there is one (the telemetry cluster section), so it runs under that transaction's statement budget.
     * [connectionIds] narrows the count to those connections (null = every one: what telemetry reads; a test seam otherwise).
     */
    suspend fun statusCounts(connectionIds: Collection<UInt>? = null): SyncQueueCounts = suspendTransaction(database) {
        val now = clock()
        val claimable = (Jobs.status eq SyncJobStatus.PENDING.name) and (Jobs.notBefore.isNull() or (Jobs.notBefore lessEq now))
        val counts = Jobs.select(Jobs.status, Jobs.id.count())
            .where {
                val open = (Jobs.status eq SyncJobStatus.RUNNING.name) or claimable
                if (connectionIds == null) open else open and (Jobs.connectionId inList connectionIds)
            }
            .groupBy(Jobs.status)
            .toList().associate { it[Jobs.status] to it[Jobs.id.count()] }
        SyncQueueCounts(pending = counts[SyncJobStatus.PENDING.name] ?: 0L, running = counts[SyncJobStatus.RUNNING.name] ?: 0L)
    }

    /**
     * The queue's depth per connection - [statusCounts]' two figures grouped by connection, in ONE statement (the telemetry panel's
     * per-connection `queue`). A connection with neither a due PENDING nor a RUNNING job is absent.
     */
    suspend fun queueByConnection(): Map<UInt, SyncQueueCounts> = suspendTransaction(database) {
        val now = clock()
        val claimable = (Jobs.status eq SyncJobStatus.PENDING.name) and (Jobs.notBefore.isNull() or (Jobs.notBefore lessEq now))
        Jobs.select(Jobs.connectionId, Jobs.status, Jobs.id.count())
            .where { (Jobs.status eq SyncJobStatus.RUNNING.name) or claimable }
            .groupBy(Jobs.connectionId, Jobs.status)
            .toList()
            .groupBy { it[Jobs.connectionId].value }
            .mapValues { (_, rows) ->
                val byStatus = rows.associate { it[Jobs.status] to it[Jobs.id.count()] }
                SyncQueueCounts(pending = byStatus[SyncJobStatus.PENDING.name] ?: 0L, running = byStatus[SyncJobStatus.RUNNING.name] ?: 0L)
            }
    }

    /**
     * The most recently REQUESTED SYNC job of every connection that has one - or, with [backfillOnly], of every connection that has one
     * whose `progress` carries `backfill = 1` (a first download or a widened scope, `ingest/Stream.kt`). One statement, no payload read.
     */
    suspend fun latestSyncs(backfillOnly: Boolean): Map<UInt, SyncJobBrief> = suspendTransaction(database) {
        val marker = if (backfillOnly) {
            "AND jsonb_typeof(progress -> 'backfill') = 'number' AND (progress ->> 'backfill')::numeric > 0"
        } else {
            ""
        }
        exec(
            """
            SELECT DISTINCT ON (connection_id) connection_id::integer, started_at::bigint, finished_at::bigint, status
            FROM sync_jobs WHERE kind = '${SyncJobKind.SYNC.name}' $marker
            ORDER BY connection_id, requested_at DESC, id DESC
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            row.get(0, Int::class.javaObjectType)!!.toUInt() to
                SyncJobBrief(
                    startedAt = row.get(1, Long::class.javaObjectType),
                    finishedAt = row.get(2, Long::class.javaObjectType),
                    status = row.get(3, String::class.java).orEmpty(),
                )
        }?.toList().orEmpty().filterNotNull().toMap()
    }

    /**
     * The SYNC jobs that ended (SUCCEEDED or FAILED) in `[from, to)`, per connection and per [bucketMillis]-wide bucket of
     * `finished_at` (`bucketStart` = the bucket's first millisecond, aligned to the epoch): the telemetry history's read-time
     * aggregation (no sampler involved). One grouped statement; a running job is not counted until it ends, so a long-running job's
     * counters appear when it finishes.
     */
    suspend fun bucketStats(
        from: Long,
        bucketMillis: Long,
        to: Long = Long.MAX_VALUE,
    ): List<SyncBucketStat> = suspendTransaction(database) {
        exec(
            """
            SELECT connection_id::integer, (finished_at - finished_at % $bucketMillis)::bigint AS bucket, count(*)::bigint,
                   (count(*) FILTER (WHERE status = '${SyncJobStatus.FAILED.name}'))::bigint,
                   (avg(finished_at - started_at) FILTER (WHERE started_at IS NOT NULL))::float8,
                   (max(finished_at - started_at) FILTER (WHERE started_at IS NOT NULL))::bigint,
                   ${progressSum("jiraRateLimited")}, ${progressSum("jiraBackoffMs")},
                   ${progressSum("jiraTenantPausedMs")}, ${progressSum("jiraNearLimitPauses")}
            FROM sync_jobs
            WHERE kind = '${SyncJobKind.SYNC.name}' AND status IN ('${SyncJobStatus.SUCCEEDED.name}', '${SyncJobStatus.FAILED.name}')
              AND finished_at >= $from AND finished_at < $to
            GROUP BY connection_id, bucket
            ORDER BY connection_id, bucket
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            SyncBucketStat(
                connectionId = row.get(0, Int::class.javaObjectType)!!.toUInt(),
                bucketStart = row.get(1, Long::class.javaObjectType)!!,
                runs = row.get(2, Long::class.javaObjectType)!!,
                failures = row.get(3, Long::class.javaObjectType)!!,
                avgDurationMs = row.get(4, Double::class.javaObjectType),
                maxDurationMs = row.get(5, Long::class.javaObjectType),
                rateLimited = row.get(6, Long::class.javaObjectType)!!,
                backoffMs = row.get(7, Long::class.javaObjectType)!!,
                tenantPausedMs = row.get(8, Long::class.javaObjectType)!!,
                nearLimitPauses = row.get(9, Long::class.javaObjectType)!!,
            )
        }?.toList().orEmpty().filterNotNull()
    }

    /** The oldest `finished_at` of any ended (SUCCEEDED or FAILED) SYNC job before [to], or null: how far back history reaches. */
    suspend fun oldestFinishedAt(to: Long): Long? = suspendTransaction(database) {
        exec(
            "SELECT min(finished_at)::bigint FROM sync_jobs WHERE kind = '${SyncJobKind.SYNC.name}' " +
                "AND status IN ('${SyncJobStatus.SUCCEEDED.name}', '${SyncJobStatus.FAILED.name}') AND finished_at < $to",
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Long::class.javaObjectType) }?.toList().orEmpty().filterNotNull().firstOrNull()
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
        notBefore = this[Jobs.notBefore],
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
