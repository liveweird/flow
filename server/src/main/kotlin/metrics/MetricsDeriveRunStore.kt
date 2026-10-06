package ch.nokillswit.metrics

import ch.nokillswit.metrics.MetricsTables.DeriveRuns
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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
}
