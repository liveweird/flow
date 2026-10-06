package ch.nokillswit.metrics

import ch.nokillswit.metrics.MetricsTables.AggDailyFlow
import ch.nokillswit.metrics.MetricsTables.AggDailyWip
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The daily aggregates' (`agg_daily_wip`/`agg_daily_flow`) delete, raw-SQL run and row-count reads ([MetricsStore]'s delegations). */
internal class MetricsAggregateStore(private val database: R2dbcDatabase) {

    suspend fun deleteAggDailyWip(connectionId: UInt) {
        AggDailyWip.deleteWhere { AggDailyWip.connectionId eq connectionId }
    }

    /** Report 9's WIP row count for `derive_runs.row_counts` — read back after [execAggDailyWip]'s own inserts commit. */
    suspend fun countAggDailyWip(connectionId: UInt): Int =
        AggDailyWip.selectAll().where { AggDailyWip.connectionId eq connectionId }.count().toInt()

    /**
     * Runs one `INSERT ... SELECT` statement against `metrics.agg_daily_wip` — `DeriveWipStep.kt`
     * builds the SQL text itself (pure aggregation over already-persisted `metrics.*`/`norm.*` rows,
     * no Kotlin-side computation needed), run through `exec(...)` — the raw-SQL route [MetricsStore.purgeAll]
     * also uses — since only a store holding [database] has the `exec` receiver. Reuses the
     * CALLER's enclosing transaction (`derive()`'s own), never opens a new one, the same way every
     * nested `suspendTransaction` call in this file does.
     */
    suspend fun execAggDailyWip(sql: String) = suspendTransaction(database) { exec(sql) }

    suspend fun deleteAggDailyFlow(connectionId: UInt) {
        AggDailyFlow.deleteWhere { AggDailyFlow.connectionId eq connectionId }
    }

    /** The flow aggregate's row count for `derive_runs.row_counts` — read back after every [execAggDailyFlow] commits. */
    suspend fun countAggDailyFlow(connectionId: UInt): Int =
        AggDailyFlow.selectAll().where { AggDailyFlow.connectionId eq connectionId }.count().toInt()

    /**
     * Runs one additive `INSERT ... SELECT ... ON CONFLICT DO UPDATE` contribution against
     * `metrics.agg_daily_flow` (`DeriveFlowStep.kt` builds the text; same transaction-reuse rules as
     * [execAggDailyWip]).
     */
    suspend fun execAggDailyFlow(sql: String) = suspendTransaction(database) { exec(sql) }
}
