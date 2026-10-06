package ch.nokillswit.metrics

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The batch writers/readers over the `metrics` schema's derived star (v0.3.0 M3 commit 7,
 * `.claude/docs/domain-model.md` "Analytical model") that `metrics/MetricsDeriver.kt` calls inside
 * its ONE per-connection transaction. The table objects live in [MetricsTables] and the row shapes
 * in `MetricsRows.kt` (checkup D2). Every table is rebuilt WHOLESALE per DERIVE run (delete this
 * connection's rows, insert the freshly derived ones) EXCEPT [MetricsTables.DimDate] (global,
 * reconciled by [ensureDimDate] in its own transaction) and [MetricsTables.FactSprintSnapshot]
 * (append-only — no update/delete writer exists here at all; the DB's own trigger,
 * `.claude/docs/persistence.md`, is the actual enforcement).
 *
 * This class is the ONE entry point every other file uses (`MetricsDeriver`, the reports, the tests). The behaviour
 * lives in one small collaborator per concern, each in its own file — every method below is a one-line delegation, so
 * a caller never learns which concern serves it and the SQL/transactions are exactly the collaborators' (a write
 * method that opens no transaction of its own still participates in the CALLER's, as before):
 *
 * - [MetricsDimDateStore] — `dim_date` reconciliation under [DIM_DATE_LOCK_KEY] and the fact reads that size its span;
 * - [MetricsDimWrites] — `dim_domain`/`dim_task`/`dim_epic` and the effective-dated bridges;
 * - [MetricsFactWrites] — `fact_task_delivery`, `fact_epic_delivery`, `fact_worklog`, `fact_epic_plan`;
 * - [MetricsSprintWrites] — `dim_sprint`, `fact_sprint_scope`, `fact_sprint` and the append-only snapshot;
 * - [MetricsAggregateStore] — `agg_daily_wip`/`agg_daily_flow` (delete, raw `INSERT ... SELECT`, row counts);
 * - [MetricsAnalyzer] — `ANALYZE` over [ANALYZED_TABLES], in or after the DERIVE transaction;
 * - [MetricsDeriveRunStore] — the `derive_runs` reads and the retention prune;
 * - [MetricsPurge] — the PURGE job's drain.
 *
 * A new `metrics.*` read or write goes in the collaborator that owns its concern, plus its one-line delegation here.
 */
class MetricsStore(private val database: R2dbcDatabase) {
    private val dimDate = MetricsDimDateStore(database)
    private val dims = MetricsDimWrites
    private val facts = MetricsFactWrites
    private val sprints = MetricsSprintWrites(database)
    private val aggregates = MetricsAggregateStore(database)
    private val analyzer = MetricsAnalyzer(database)
    private val runs = MetricsDeriveRunStore(database)
    private val purge = MetricsPurge(database)

    /** Reconciles the global `dim_date` in its OWN transaction — [MetricsDimDateStore.ensureDimDate] holds the full contract. */
    suspend fun ensureDimDate(calendar: WorkingCalendar, range: DimDateRange, configRevision: Long): Int =
        dimDate.ensureDimDate(calendar, range, configRevision)

    /** `(start_at, due_at)` of every CURRENT epic baseline — the windows the PV spreading reads `dim_date` over. */
    suspend fun currentEpicPlanWindows(connectionId: UInt): List<Pair<Long, Long>> = dimDate.currentEpicPlanWindows(connectionId)

    /** The earliest timestamp any flow-aggregate join places on a `dim_date` day for this connection, or null. */
    suspend fun earliestFactEventMs(connectionId: UInt): Long? = dimDate.earliestFactEventMs(connectionId)

    /** Deletes `dim_domain`/`dim_task`/`dim_epic` for one connection — call ONCE, before the batched inserts. */
    suspend fun deleteDims(connectionId: UInt) = dims.deleteDims(connectionId)

    suspend fun insertDomains(connectionId: UInt, domains: List<DimDomainRow>, configRevision: Long) =
        dims.insertDomains(connectionId, domains, configRevision)

    /** One batch's worth of `dim_task` rows — call per batch, AFTER [deleteDims] ran once for the connection. */
    suspend fun insertTasks(connectionId: UInt, tasks: List<DimTaskRow>, configRevision: Long) =
        dims.insertTasks(connectionId, tasks, configRevision)

    /** One batch's worth of `dim_epic` rows — call per batch, AFTER [deleteDims] ran once for the connection. */
    suspend fun insertEpics(connectionId: UInt, epics: List<DimEpicRow>, configRevision: Long) =
        dims.insertEpics(connectionId, epics, configRevision)

    /** Wholesale-rebuilds the three dimensions in ONE call (the pre-batching shape, for a caller with every row in memory). */
    suspend fun replaceDims(
        connectionId: UInt,
        domains: List<DimDomainRow>,
        tasks: List<DimTaskRow>,
        epics: List<DimEpicRow>,
        configRevision: Long,
    ) = dims.replaceDims(connectionId, domains, tasks, epics, configRevision)

    /** Deletes every bridge the task step populates — call ONCE, before the batched bridge inserts. */
    suspend fun deleteBridges(connectionId: UInt) = dims.deleteBridges(connectionId)

    suspend fun insertTaskEpic(connectionId: UInt, rows: List<TaskEpicRow>) = dims.insertTaskEpic(connectionId, rows)

    suspend fun insertTaskDomain(connectionId: UInt, rows: List<TaskDomainRow>) = dims.insertTaskDomain(connectionId, rows)

    suspend fun insertTaskAssignee(connectionId: UInt, rows: List<TaskAssigneeRow>) = dims.insertTaskAssignee(connectionId, rows)

    suspend fun insertTaskSprint(connectionId: UInt, rows: List<TaskSprintRow>) = dims.insertTaskSprint(connectionId, rows)

    suspend fun insertItemEstimate(connectionId: UInt, rows: List<ItemEstimateRow>) = dims.insertItemEstimate(connectionId, rows)

    suspend fun insertItemStage(connectionId: UInt, rows: List<ItemStageRow>) = dims.insertItemStage(connectionId, rows)

    suspend fun insertItemBlocked(connectionId: UInt, rows: List<ItemBlockedRow>) = dims.insertItemBlocked(connectionId, rows)

    suspend fun deleteFactTaskDelivery(connectionId: UInt) = facts.deleteFactTaskDelivery(connectionId)

    /** One batch's worth of `fact_task_delivery` rows — call per batch, AFTER [deleteFactTaskDelivery] ran once. */
    suspend fun insertFactTaskDelivery(connectionId: UInt, rows: List<FactTaskDeliveryRow>, configRevision: Long) =
        facts.insertFactTaskDelivery(connectionId, rows, configRevision)

    suspend fun deleteFactEpicDelivery(connectionId: UInt) = facts.deleteFactEpicDelivery(connectionId)

    /** One batch's worth of `fact_epic_delivery` rows — call per batch, AFTER [deleteFactEpicDelivery] ran once. */
    suspend fun insertFactEpicDelivery(connectionId: UInt, rows: List<FactEpicDeliveryRow>, configRevision: Long) =
        facts.insertFactEpicDelivery(connectionId, rows, configRevision)

    /** Deletes `dim_sprint`/`fact_sprint_scope`/`fact_sprint` for one connection; the append-only snapshot is never deleted here. */
    suspend fun deleteSprintFacts(connectionId: UInt) = sprints.deleteSprintFacts(connectionId)

    suspend fun insertDimSprints(connectionId: UInt, rows: List<DimSprintRow>, configRevision: Long) =
        sprints.insertDimSprints(connectionId, rows, configRevision)

    /** One batch's worth of `fact_sprint_scope` rows — call per batch, AFTER [deleteSprintFacts] ran once for the connection. */
    suspend fun insertFactSprintScope(connectionId: UInt, rows: List<FactSprintScopeRow>, configRevision: Long) =
        sprints.insertFactSprintScope(connectionId, rows, configRevision)

    suspend fun insertFactSprint(connectionId: UInt, rows: List<FactSprintRow>, configRevision: Long) =
        sprints.insertFactSprint(connectionId, rows, configRevision)

    /** Every `fact_sprint_snapshot` sprint id already frozen for this connection — a DERIVE run never re-inserts one (D13). */
    suspend fun existingSnapshotSprintIds(connectionId: UInt): Set<Long> = sprints.existingSnapshotSprintIds(connectionId)

    /** ONE append-only `fact_sprint_snapshot` row (D13, invariant 11) — never called for an id [existingSnapshotSprintIds] names. */
    suspend fun insertFactSprintSnapshot(
        connectionId: UInt,
        row: FactSprintRow,
        scopeRows: List<SprintScopeItem>,
        configRevision: Long,
        processingVersion: Int,
        reconstructed: Boolean,
        snapshotAt: Long,
    ) = sprints.insertFactSprintSnapshot(
        connectionId, row, scopeRows, configRevision, processingVersion, reconstructed, snapshotAt,
    )

    /** The earliest `started_at` of any SUCCEEDED `derive_runs` row for this connection — `null` before its first. */
    suspend fun firstSuccessfulDeriveRunStartedAt(connectionId: UInt): Long? = runs.firstSuccessfulDeriveRunStartedAt(connectionId)

    /** The `row_counts` of this connection's newest SUCCEEDED `derive_runs` row, `null` before any run succeeded. */
    suspend fun newestSucceededRunRowCounts(connectionId: UInt): Map<String, Int>? = runs.newestSucceededRunRowCounts(connectionId)

    suspend fun deleteFactWorklog(connectionId: UInt) = facts.deleteFactWorklog(connectionId)

    /** One batch's worth of `fact_worklog` rows (v0.3.0 M3 commit 9) — call per batch, AFTER [deleteFactWorklog] ran once. */
    suspend fun insertFactWorklog(connectionId: UInt, rows: List<FactWorklogRow>, configRevision: Long) =
        facts.insertFactWorklog(connectionId, rows, configRevision)

    suspend fun deleteFactEpicPlan(connectionId: UInt) = facts.deleteFactEpicPlan(connectionId)

    /** One batch's worth of `fact_epic_plan` rows (v0.3.0 M3 commit 9b) — call per batch, AFTER [deleteFactEpicPlan] ran once. */
    suspend fun insertFactEpicPlan(connectionId: UInt, rows: List<FactEpicPlanRow>, configRevision: Long) =
        facts.insertFactEpicPlan(connectionId, rows, configRevision)

    suspend fun deleteAggDailyWip(connectionId: UInt) = aggregates.deleteAggDailyWip(connectionId)

    /** Report 9's WIP row count for `derive_runs.row_counts` — read back after [execAggDailyWip]'s own inserts commit. */
    suspend fun countAggDailyWip(connectionId: UInt): Int = aggregates.countAggDailyWip(connectionId)

    /** Runs one `INSERT ... SELECT` against `metrics.agg_daily_wip` in the CALLER's enclosing transaction (never a new one). */
    suspend fun execAggDailyWip(sql: String) = aggregates.execAggDailyWip(sql)

    suspend fun deleteAggDailyFlow(connectionId: UInt) = aggregates.deleteAggDailyFlow(connectionId)

    /** The flow aggregate's row count for `derive_runs.row_counts` — read back after every [execAggDailyFlow] commits. */
    suspend fun countAggDailyFlow(connectionId: UInt): Int = aggregates.countAggDailyFlow(connectionId)

    /** Runs one additive `INSERT ... SELECT ... ON CONFLICT DO UPDATE` against `metrics.agg_daily_flow` (same transaction rules). */
    suspend fun execAggDailyFlow(sql: String) = aggregates.execAggDailyFlow(sql)

    /** `ANALYZE` over [ANALYZED_TABLES] — [MetricsAnalyzer.analyzeDerivedTables] holds the in-/post-commit contract and its timeouts. */
    suspend fun analyzeDerivedTables(lockTimeoutMs: Long? = null, statementTimeoutMs: Long? = null) =
        analyzer.analyzeDerivedTables(lockTimeoutMs, statementTimeoutMs)

    /** Hard-deletes terminal `derive_runs` rows older than [retentionMillis], always keeping each connection's newest SUCCEEDED one. */
    suspend fun pruneDeriveRuns(retentionMillis: Long, now: Long): Int = runs.pruneDeriveRuns(retentionMillis, now)

    /** Every rebuildable `metrics.*` row for one connection (the PURGE job's step) — runs inside its own transaction. */
    suspend fun purgeAll(connectionId: UInt) = purge.purgeAll(connectionId)

    /** Test/diagnostic reads — `MetricsDerivationTest`'s own invariant sweeps read the table objects above directly via this database. */
    suspend fun <T> query(block: suspend () -> T): T = suspendTransaction(database) { block() }
}
