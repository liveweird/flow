package ch.nokillswit.metrics

import ch.nokillswit.metrics.MetricsTables.DeriveRuns
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The DERIVE runs of one connection that ended (SUCCEEDED or FAILED) inside one time bucket ([MetricsDeriveRunStore.runBuckets]):
 * how many, how many [failures], the wall-clock [avgDurationMs]/[maxDurationMs], and the [rowsWritten] (the sum of the integer
 * `row_counts` entries of those runs; a failed run records none).
 */
data class DeriveBucketStat(
    val connectionId: UInt,
    val bucketStart: Long,
    val runs: Long,
    val failures: Long,
    val avgDurationMs: Double?,
    val maxDurationMs: Long?,
    val rowsWritten: Long,
)

/** A connection's latest ended DERIVE run ([MetricsDeriveRunStore.latestRuns]); [rowsWritten] is null for a run without `row_counts`. */
data class DeriveRunBrief(val connectionId: UInt, val finishedAt: Long, val durationMs: Long, val status: String, val rowsWritten: Long?)

/** The sum of the numeric entries of a run's `row_counts` object (0 when it has none), as a scalar subquery over the alias [run]. */
private fun rowsWrittenSql(run: String) =
    """(SELECT COALESCE(SUM((e.value)::numeric), 0)::bigint
        FROM jsonb_each(CASE WHEN jsonb_typeof($run.row_counts) = 'object' THEN $run.row_counts ELSE '{}'::jsonb END) e
        WHERE jsonb_typeof(e.value) = 'number')"""

/** The `derive_runs` bookkeeping reads and the retention prune ([MetricsStore]'s delegations). */
internal class MetricsDeriveRunStore(private val database: R2dbcDatabase) {

    /** The earliest `started_at` of any SUCCEEDED `derive_runs` row for this connection — `null`
     * before this run is the connection's first. */
    suspend fun firstSuccessfulDeriveRunStartedAt(connectionId: UInt): Long? = suspendTransaction(database) {
        DeriveRuns.select(DeriveRuns.startedAt)
            .where { (DeriveRuns.connectionId eq connectionId.toInt()) and (DeriveRuns.status eq "SUCCEEDED") }
            .orderBy(DeriveRuns.startedAt to SortOrder.ASC)
            .limit(1)
            .toList().map { it[DeriveRuns.startedAt] }.firstOrNull()
    }

    /**
     * The whole `row_counts` (every integer entry) of this connection's newest SUCCEEDED `derive_runs` row, `null` before
     * any run succeeded (an empty map when that row recorded none). That row is never pruned ([pruneDeriveRuns]), so it
     * exists for any connection that ever derived. `MetricsDeriver` compares it, key by key, with the counts it is about
     * to write to tell whether the planner statistics still describe the connection's rows ([statisticsDescribeRows]).
     */
    suspend fun newestSucceededRunRowCounts(connectionId: UInt): Map<String, Int>? = suspendTransaction(database) {
        DeriveRuns.select(DeriveRuns.rowCounts)
            .where { (DeriveRuns.connectionId eq connectionId.toInt()) and (DeriveRuns.status eq "SUCCEEDED") }
            .orderBy(DeriveRuns.startedAt to SortOrder.DESC, DeriveRuns.id to SortOrder.DESC)
            .limit(1)
            .toList().singleOrNull()
            ?.let { row ->
                row[DeriveRuns.rowCounts]?.let { json ->
                    Json.parseToJsonElement(json).jsonObject
                        .mapNotNull { (key, value) -> value.jsonPrimitive.intOrNull?.let { key to it } }
                        .toMap()
                } ?: emptyMap()
            }
    }

    /**
     * Hard-deletes terminal `derive_runs` rows older than [retentionMillis] (v0.3.0 M3 review round
     * 2b) — the `SyncJobsService.prune` shape, called once per DERIVE run
     * (`MetricsDeriver.kt`, right before it inserts its OWN new RUNNING row). `derive_runs` is
     * unbounded operational history exactly like `sync_jobs` (`.claude/docs/persistence.md` "Soft
     * delete (convention)" — the `sync_jobs` prune hard-delete exception applies here too): a
     * connection with a short `DERIVE` cadence would otherwise grow this table forever.
     *
     * Each connection's NEWEST SUCCEEDED run (by `started_at`, ties by `id`) is always kept, however old: it is
     * the connection's DERIVE clock (`reports/SnapshotSupport.kt` `deriveClocks`), and pruning it would make
     * every snapshot report read the still-derived connection as "not derived yet".
     */
    suspend fun pruneDeriveRuns(retentionMillis: Long, now: Long): Int = suspendTransaction(database) {
        // ONE statement (keep-set as a subquery), so a run another DERIVE flips to SUCCEEDED mid-prune can
        // never fall between a separate keep-set read and the delete.
        val newestSucceeded = DeriveRuns.select(DeriveRuns.id)
            .where { DeriveRuns.status eq "SUCCEEDED" }
            .withDistinctOn(DeriveRuns.connectionId)
            .orderBy(DeriveRuns.connectionId to SortOrder.ASC, DeriveRuns.startedAt to SortOrder.DESC, DeriveRuns.id to SortOrder.DESC)
        DeriveRuns.deleteWhere {
            (DeriveRuns.status inList listOf("SUCCEEDED", "FAILED")) and
                (DeriveRuns.finishedAt less (now - retentionMillis)) and
                (DeriveRuns.id notInSubQuery newestSucceeded)
        }
    }

    /**
     * The DERIVE runs that ended (SUCCEEDED or FAILED) in `[from, to)`, per connection and per [bucketMillis]-wide bucket of
     * `finished_at` (`bucketStart` aligned to the epoch): the telemetry history's read-time aggregation. One grouped statement over
     * the table's retained rows (`ingest.jobRetentionDays`).
     */
    suspend fun runBuckets(
        from: Long,
        bucketMillis: Long,
        to: Long = Long.MAX_VALUE,
    ): List<DeriveBucketStat> = suspendTransaction(database) {
        exec(
            """
            SELECT connection_id::integer, (finished_at - finished_at % $bucketMillis)::bigint AS bucket, count(*)::bigint,
                   (count(*) FILTER (WHERE status = 'FAILED'))::bigint,
                   avg(finished_at - started_at)::float8, max(finished_at - started_at)::bigint, COALESCE(sum(rows_written), 0)::bigint
            FROM (
                SELECT r.connection_id, r.started_at, r.finished_at, r.status, ${rowsWrittenSql("r")} AS rows_written
                FROM metrics.derive_runs r
                WHERE r.status IN ('SUCCEEDED', 'FAILED') AND r.finished_at >= $from AND r.finished_at < $to
            ) runs
            GROUP BY connection_id, bucket
            ORDER BY connection_id, bucket
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            DeriveBucketStat(
                connectionId = row.get(0, Int::class.javaObjectType)!!.toUInt(),
                bucketStart = row.get(1, Long::class.javaObjectType)!!,
                runs = row.get(2, Long::class.javaObjectType)!!,
                failures = row.get(3, Long::class.javaObjectType)!!,
                avgDurationMs = row.get(4, Double::class.javaObjectType),
                maxDurationMs = row.get(5, Long::class.javaObjectType),
                rowsWritten = row.get(6, Long::class.javaObjectType)!!,
            )
        }?.toList().orEmpty().filterNotNull()
    }

    /** Every connection's latest ENDED run (SUCCEEDED or FAILED, by `finished_at`), one statement - the telemetry panel's "last DERIVE". */
    suspend fun latestRuns(): Map<UInt, DeriveRunBrief> = suspendTransaction(database) {
        exec(
            """
            SELECT DISTINCT ON (r.connection_id) r.connection_id::integer, r.finished_at::bigint, (r.finished_at - r.started_at)::bigint,
                   r.status, CASE WHEN r.row_counts IS NULL THEN NULL ELSE ${rowsWrittenSql("r")} END
            FROM metrics.derive_runs r
            WHERE r.status IN ('SUCCEEDED', 'FAILED') AND r.finished_at IS NOT NULL
            ORDER BY r.connection_id, r.finished_at DESC, r.id DESC
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            val brief = DeriveRunBrief(
                connectionId = row.get(0, Int::class.javaObjectType)!!.toUInt(),
                finishedAt = row.get(1, Long::class.javaObjectType)!!,
                durationMs = row.get(2, Long::class.javaObjectType)!!,
                status = row.get(3, String::class.java).orEmpty(),
                rowsWritten = row.get(4, Long::class.javaObjectType),
            )
            brief.connectionId to brief
        }?.toList().orEmpty().filterNotNull().toMap()
    }
}
