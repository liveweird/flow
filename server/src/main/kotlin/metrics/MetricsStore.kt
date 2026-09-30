package ch.nokillswit.metrics

import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.metrics.MetricsTables.AggDailyFlow
import ch.nokillswit.metrics.MetricsTables.AggDailyWip
import ch.nokillswit.metrics.MetricsTables.DeriveRuns
import ch.nokillswit.metrics.MetricsTables.DimDate
import ch.nokillswit.metrics.MetricsTables.DimDomain
import ch.nokillswit.metrics.MetricsTables.DimEpic
import ch.nokillswit.metrics.MetricsTables.DimSprint
import ch.nokillswit.metrics.MetricsTables.DimTask
import ch.nokillswit.metrics.MetricsTables.FactEpicDelivery
import ch.nokillswit.metrics.MetricsTables.FactEpicPlan
import ch.nokillswit.metrics.MetricsTables.FactSprint
import ch.nokillswit.metrics.MetricsTables.FactSprintScope
import ch.nokillswit.metrics.MetricsTables.FactSprintSnapshot
import ch.nokillswit.metrics.MetricsTables.FactTaskDelivery
import ch.nokillswit.metrics.MetricsTables.FactWorklog
import ch.nokillswit.metrics.MetricsTables.ItemBlocked
import ch.nokillswit.metrics.MetricsTables.ItemEstimate
import ch.nokillswit.metrics.MetricsTables.ItemStage
import ch.nokillswit.metrics.MetricsTables.TaskAssignee
import ch.nokillswit.metrics.MetricsTables.TaskDomain
import ch.nokillswit.metrics.MetricsTables.TaskEpic
import ch.nokillswit.metrics.MetricsTables.TaskSprint
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.inTopLevelSuspendTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.LocalDate

/**
 * The ONE PostgreSQL advisory-lock key of [MetricsStore.ensureDimDate] — the ASCII bytes of
 * "FlowDate" as a positive `bigint`. `pg_advisory_xact_lock` keys share one namespace per database,
 * so no other code may take this key (`.claude/docs/persistence.md` lists it).
 */
internal const val DIM_DATE_LOCK_KEY = 0x466C6F7744617465L

/** Rows per `batchUpsert` in [MetricsStore.ensureDimDate] (a cold table is tens of thousands of rows). */
private const val DIM_DATE_WRITE_BATCH = 1000

/**
 * The tables `DeriveWipStep.kt`/`DeriveFlowStep.kt`'s SQL reads and `MetricsDeriver` has just
 * rebuilt in its transaction ([MetricsStore.analyzeDerivedTables]): the connection-scoped dims,
 * bridges and facts, plus the global `dim_date` (ensured in the same run, in its own transaction; every step joins it).
 * Deliberately NOT here: `agg_daily_*` (written by those steps, never read by them),
 * `fact_epic_delivery`/`item_blocked`/`fact_sprint_snapshot` (read by neither), `team_membership`
 * (config, not rebuilt by DERIVE) and `norm.*` (PROCESS already committed those rows, so autovacuum
 * analyzes them). `MetricsAnalyzeTest` checks the list against the steps' SQL sources.
 */
internal val ANALYZED_TABLES: List<String> = listOf(
    "metrics.dim_date", "metrics.dim_domain", "metrics.dim_task", "metrics.dim_epic", "metrics.dim_sprint",
    "metrics.task_epic", "metrics.task_domain", "metrics.task_assignee", "metrics.task_sprint",
    "metrics.item_estimate", "metrics.item_stage",
    "metrics.fact_task_delivery", "metrics.fact_sprint", "metrics.fact_sprint_scope", "metrics.fact_worklog", "metrics.fact_epic_plan",
)

/** `metrics.fact_sprint_snapshot.scope` — the sprint's own [SprintScopeItem] rows, frozen as JSON (D13). */
private fun sprintScopeItemsJson(items: List<SprintScopeItem>): String = buildJsonArray {
    items.forEach { item ->
        add(
            buildJsonObject {
                put("issueId", item.issueId)
                put("addedAtMs", item.addedAtMs)
                put("removedAtMs", item.removedAtMs)
                put("committed", item.committed)
                put("inScopeAtClose", item.inScopeAtClose)
                put("estimateAtCommitmentMd", item.estimateAtCommitmentMd)
                put("estimateAtCloseMd", item.estimateAtCloseMd)
                put("estimateAtDoneMd", item.estimateAtDoneMd)
                put("assigneeAtCommitment", item.assigneeAtCommitment)
                put("doneInSprint", item.doneInSprint)
                put("carriedOver", item.carriedOver)
                put("dropped", item.dropped)
            },
        )
    }
}.toString()


/**
 * The batch writers/readers over the `metrics` schema's derived star (v0.3.0 M3 commit 7,
 * `.claude/docs/domain-model.md` "Analytical model") that `metrics/MetricsDeriver.kt` calls inside
 * its ONE per-connection transaction. The table objects live in [MetricsTables] and the row shapes
 * in `MetricsRows.kt` (checkup D2). Every table is rebuilt WHOLESALE per DERIVE run (delete this
 * connection's rows, insert the freshly derived ones) EXCEPT [MetricsTables.DimDate] (global,
 * reconciled by [ensureDimDate] in its own transaction) and [MetricsTables.FactSprintSnapshot]
 * (append-only — no update/delete writer exists here at all; the DB's own trigger,
 * `.claude/docs/persistence.md`, is the actual enforcement).
 */
class MetricsStore(private val database: R2dbcDatabase) {

    /**
     * Brings the global `metrics.dim_date` in line with [calendar] over the whole span the table and
     * [range] together cover, in its OWN short transaction — never the caller's. Returns the number of
     * rows written (0 in steady state).
     *
     * Why a separate transaction: `dim_date` is shared by every connection's DERIVE, and a write made
     * inside a DERIVE's one big transaction holds its row locks until that (minutes-long) commit —
     * two DERIVEs with different ranges could deadlock (SQLSTATE 40P01) or, at best, serialized on
     * the locks. [inTopLevelSuspendTransaction] takes a fresh pooled connection and COMMITS before
     * returning even when called from inside a `suspendTransaction`, so the caller's later statements
     * (PostgreSQL `READ COMMITTED` — nothing in this repo sets another level — take a fresh snapshot
     * per statement) see the committed rows while the DERIVE holds no `dim_date` lock at all. The one
     * serialization point left is [DIM_DATE_LOCK_KEY]: a transaction-scoped advisory lock taken FIRST,
     * so concurrent ensures queue (milliseconds) instead of deadlocking on row locks taken in
     * different orders. No table references `dim_date(day)` (V16/V17 declare no foreign key to it).
     *
     * What is written: the target span is the union of the stored days' `[min, max]` and [range]
     * (gaps between disjoint ranges are filled); every day in it is recomputed with [calendar] and
     * upserted ONLY when missing or when its `day_start_ms`/`day_end_ms`/`is_working_day` differ — so
     * a time-zone/weekend/holiday change rewrites EVERY stored row, not just the run's own range,
     * while an unchanged calendar writes nothing. `config_revision` is the revision the row's current
     * content was written under (stamped on written rows only); the `day` key is never updated.
     *
     * **A stale caller only inserts.** Under the advisory lock the CURRENT global revision
     * (`metrics.settings.config_revision`) is read: a caller whose [configRevision] is lower started under an
     * older calendar, and rewriting differing rows to it would roll the table back under a newer run (also
     * across an A→B→A change, where row stamps alone cannot tell) — so it inserts MISSING rows only and never
     * updates one. That stale run is itself re-derived (`bumpRevision` enqueued it). Only a caller at the
     * current revision rewrites differing rows.
     */
    suspend fun ensureDimDate(calendar: WorkingCalendar, range: DimDateRange, configRevision: Long): Int =
        inTopLevelSuspendTransaction(database) {
            exec("SELECT pg_advisory_xact_lock($DIM_DATE_LOCK_KEY)")
            val currentRevision = MetricsSettingsService.Settings.select(MetricsSettingsService.Settings.configRevision).toList()
                .single()[MetricsSettingsService.Settings.configRevision]
            val mayRewrite = configRevision >= currentRevision
            val stored = DimDate.selectAll().toList().associate {
                it[DimDate.day] to WorkingCalendar.DimDateRow(
                    it[DimDate.day], it[DimDate.dayStartMs], it[DimDate.dayEndMs], it[DimDate.isWorkingDay],
                )
            }
            val rangeFrom = calendar.dayOf(range.fromMs)
            val rangeTo = calendar.dayOf(range.toMs)
            val fromDay = stored.keys.minOrNull()?.let { minOf(LocalDate.parse(it), rangeFrom) } ?: rangeFrom
            val toDay = stored.keys.maxOrNull()?.let { maxOf(LocalDate.parse(it), rangeTo) } ?: rangeTo
            val changed = calendar.dimDateRows(fromDay, toDay).filter { wanted ->
                val existing = stored[wanted.day]
                existing == null || (mayRewrite && existing != wanted)
            }
            changed.chunked(DIM_DATE_WRITE_BATCH).forEach { chunk ->
                DimDate.batchUpsert(chunk, DimDate.day) { row ->
                    this[DimDate.day] = row.day
                    this[DimDate.dayStartMs] = row.dayStartMs
                    this[DimDate.dayEndMs] = row.dayEndMs
                    this[DimDate.isWorkingDay] = row.isWorkingDay
                    this[DimDate.configRevision] = configRevision
                }
            }
            changed.size
        }

    /**
     * `(start_at, due_at)` of every CURRENT epic baseline (`superseded_at IS NULL`, start/due/budget
     * all set) — the windows [runFlowStep]'s PV spreading reads `dim_date` over. Not horizon-filtered
     * here: [DeriveKernels.dimDateRange] applies [DeriveKernels.inPvHorizon] itself.
     */
    suspend fun currentEpicPlanWindows(connectionId: UInt): List<Pair<Long, Long>> = suspendTransaction(database) {
        FactEpicPlan.select(FactEpicPlan.startAt, FactEpicPlan.dueAt).where {
            (FactEpicPlan.connectionId eq connectionId) and FactEpicPlan.supersededAt.isNull() and
                FactEpicPlan.startAt.isNotNull() and FactEpicPlan.dueAt.isNotNull() and FactEpicPlan.budgetMd.isNotNull()
        }.toList().map { it[FactEpicPlan.startAt]!! to it[FactEpicPlan.dueAt]!! }
    }

    /**
     * The earliest timestamp any flow-aggregate join places on a `dim_date` day for this connection:
     * `fact_worklog.started_at`, `dim_sprint.start_at`, `fact_task_delivery.done_at`. Null when there
     * are none.
     */
    suspend fun earliestFactEventMs(connectionId: UInt): Long? = suspendTransaction(database) {
        val worklog = FactWorklog.startedAt.min()
        val sprint = DimSprint.startAt.min()
        val done = FactTaskDelivery.doneAt.min()
        listOfNotNull(
            FactWorklog.select(worklog).where { FactWorklog.connectionId eq connectionId }.toList().single()[worklog],
            DimSprint.select(sprint).where { DimSprint.connectionId eq connectionId }.toList().single()[sprint],
            FactTaskDelivery.select(done).where { FactTaskDelivery.connectionId eq connectionId }.toList().single()[done],
        ).minOrNull()
    }

    /**
     * Deletes `dim_domain`/`dim_task`/`dim_epic` for one connection — call ONCE, before the batched
     * [insertTasks]/[insertEpics] inserts below (v0.3.0 M3 review round 2b: `MetricsDeriver.kt` now
     * writes tasks/epics in batches of 200 rather than one final list, so delete and insert are
     * split into their own methods instead of one `replaceDims` doing both).
     */
    suspend fun deleteDims(connectionId: UInt) {
        DimDomain.deleteWhere { DimDomain.connectionId eq connectionId }
        DimTask.deleteWhere { DimTask.connectionId eq connectionId }
        DimEpic.deleteWhere { DimEpic.connectionId eq connectionId }
    }

    suspend fun insertDomains(connectionId: UInt, domains: List<DimDomainRow>, configRevision: Long) {
        if (domains.isEmpty()) return
        DimDomain.batchInsert(domains) {
            this[DimDomain.connectionId] = connectionId
            this[DimDomain.domainKey] = it.domainKey
            this[DimDomain.name] = it.name
            this[DimDomain.projectKeys] = stringArrayJson(it.projectKeys)
            this[DimDomain.ownerTeamId] = it.ownerTeamId
            this[DimDomain.configRevision] = configRevision
        }
    }

    /** One batch's worth of `dim_task` rows — call per batch, AFTER [deleteDims] ran once for the connection. */
    suspend fun insertTasks(connectionId: UInt, tasks: List<DimTaskRow>, configRevision: Long) {
        if (tasks.isEmpty()) return
        DimTask.batchInsert(tasks) {
            this[DimTask.connectionId] = connectionId
            this[DimTask.issueId] = it.issueId
            this[DimTask.issueKey] = it.issueKey
            this[DimTask.issueType] = it.issueType
            this[DimTask.activityType] = it.activityType
            this[DimTask.workCategory] = it.workCategory
            this[DimTask.workCategorySource] = it.workCategorySource
            this[DimTask.isSubtask] = it.isSubtask
            this[DimTask.parentTaskId] = it.parentTaskId
            this[DimTask.domainKey] = it.domainKey
            this[DimTask.epicId] = it.epicId
            this[DimTask.configRevision] = configRevision
        }
    }

    /** One batch's worth of `dim_epic` rows — call per batch, AFTER [deleteDims] ran once for the connection. */
    suspend fun insertEpics(connectionId: UInt, epics: List<DimEpicRow>, configRevision: Long) {
        if (epics.isEmpty()) return
        DimEpic.batchInsert(epics) {
            this[DimEpic.connectionId] = connectionId
            this[DimEpic.issueId] = it.issueId
            this[DimEpic.issueKey] = it.issueKey
            this[DimEpic.summary] = it.summary
            this[DimEpic.domainKey] = it.domainKey
            this[DimEpic.workCategory] = it.workCategory
            this[DimEpic.currentStage] = it.currentStage
            this[DimEpic.startAt] = it.startAt
            this[DimEpic.dueAt] = it.dueAt
            this[DimEpic.configRevision] = configRevision
        }
    }

    /**
     * Wholesale-rebuilds `dim_domain`/`dim_task`/`dim_epic` for one connection in ONE call — the
     * pre-batching shape, kept for a caller (a test, or a future small connection) that has every
     * row in memory already; `MetricsDeriver.kt`'s own batched write calls [deleteDims]/
     * [insertDomains]/[insertTasks]/[insertEpics] directly instead.
     */
    suspend fun replaceDims(
        connectionId: UInt,
        domains: List<DimDomainRow>,
        tasks: List<DimTaskRow>,
        epics: List<DimEpicRow>,
        configRevision: Long,
    ) {
        deleteDims(connectionId)
        insertDomains(connectionId, domains, configRevision)
        insertTasks(connectionId, tasks, configRevision)
        insertEpics(connectionId, epics, configRevision)
    }

    /** Deletes every bridge this commit populates — call ONCE, before the batched insert methods below. */
    suspend fun deleteBridges(connectionId: UInt) {
        TaskEpic.deleteWhere { TaskEpic.connectionId eq connectionId }
        TaskDomain.deleteWhere { TaskDomain.connectionId eq connectionId }
        TaskAssignee.deleteWhere { TaskAssignee.connectionId eq connectionId }
        TaskSprint.deleteWhere { TaskSprint.connectionId eq connectionId }
        ItemEstimate.deleteWhere { ItemEstimate.connectionId eq connectionId }
        ItemStage.deleteWhere { ItemStage.connectionId eq connectionId }
        ItemBlocked.deleteWhere { ItemBlocked.connectionId eq connectionId }
    }

    suspend fun insertTaskEpic(connectionId: UInt, rows: List<TaskEpicRow>) {
        if (rows.isEmpty()) return
        TaskEpic.batchInsert(rows) {
            this[TaskEpic.connectionId] = connectionId
            this[TaskEpic.issueId] = it.issueId
            this[TaskEpic.epicId] = it.epicId
            this[TaskEpic.validFrom] = it.fromAtMs
            this[TaskEpic.validTo] = it.toAtMs
        }
    }

    suspend fun insertTaskDomain(connectionId: UInt, rows: List<TaskDomainRow>) {
        if (rows.isEmpty()) return
        TaskDomain.batchInsert(rows) {
            this[TaskDomain.connectionId] = connectionId
            this[TaskDomain.issueId] = it.issueId
            this[TaskDomain.domainKey] = it.domainKey
            this[TaskDomain.validFrom] = it.fromAtMs
            this[TaskDomain.validTo] = it.toAtMs
        }
    }

    suspend fun insertTaskAssignee(connectionId: UInt, rows: List<TaskAssigneeRow>) {
        if (rows.isEmpty()) return
        TaskAssignee.batchInsert(rows) {
            this[TaskAssignee.connectionId] = connectionId
            this[TaskAssignee.issueId] = it.issueId
            this[TaskAssignee.accountId] = it.accountId
            this[TaskAssignee.validFrom] = it.fromAtMs
            this[TaskAssignee.validTo] = it.toAtMs
        }
    }

    suspend fun insertTaskSprint(connectionId: UInt, rows: List<TaskSprintRow>) {
        if (rows.isEmpty()) return
        TaskSprint.batchInsert(rows) {
            this[TaskSprint.connectionId] = connectionId
            this[TaskSprint.issueId] = it.issueId
            this[TaskSprint.sprintId] = it.sprintId
            this[TaskSprint.validFrom] = it.fromAtMs
            this[TaskSprint.validTo] = it.toAtMs
        }
    }

    suspend fun insertItemEstimate(connectionId: UInt, rows: List<ItemEstimateRow>) {
        if (rows.isEmpty()) return
        ItemEstimate.batchInsert(rows) {
            this[ItemEstimate.connectionId] = connectionId
            this[ItemEstimate.issueId] = it.issueId
            this[ItemEstimate.estimateMd] = it.estimateMd?.toBigDecimal()
            this[ItemEstimate.validFrom] = it.fromAtMs
            this[ItemEstimate.validTo] = it.toAtMs
        }
    }

    suspend fun insertItemStage(connectionId: UInt, rows: List<ItemStageRow>) {
        if (rows.isEmpty()) return
        ItemStage.batchInsert(rows) {
            this[ItemStage.connectionId] = connectionId
            this[ItemStage.issueId] = it.issueId
            this[ItemStage.stage] = it.stage
            this[ItemStage.statusId] = it.statusId
            this[ItemStage.validFrom] = it.fromAtMs
            this[ItemStage.validTo] = it.toAtMs
        }
    }

    suspend fun insertItemBlocked(connectionId: UInt, rows: List<ItemBlockedRow>) {
        if (rows.isEmpty()) return
        ItemBlocked.batchInsert(rows) {
            this[ItemBlocked.connectionId] = connectionId
            this[ItemBlocked.issueId] = it.issueId
            this[ItemBlocked.reason] = it.reason
            this[ItemBlocked.validFrom] = it.fromAtMs
            this[ItemBlocked.validTo] = it.toAtMs
        }
    }

    suspend fun deleteFactTaskDelivery(connectionId: UInt) {
        FactTaskDelivery.deleteWhere { FactTaskDelivery.connectionId eq connectionId }
    }

    /** One batch's worth of `fact_task_delivery` rows — call per batch, AFTER [deleteFactTaskDelivery] ran once. */
    suspend fun insertFactTaskDelivery(connectionId: UInt, rows: List<FactTaskDeliveryRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactTaskDelivery.batchInsert(rows) {
            this[FactTaskDelivery.connectionId] = connectionId
            this[FactTaskDelivery.issueId] = it.issueId
            this[FactTaskDelivery.issueKey] = it.issueKey
            this[FactTaskDelivery.createdAt] = it.createdAt
            this[FactTaskDelivery.startedAt] = it.startedAt
            this[FactTaskDelivery.doneAt] = it.doneAt
            this[FactTaskDelivery.reopenCount] = it.reopenCount
            this[FactTaskDelivery.estimateAtStartMd] = it.estimateAtStartMd?.toBigDecimal()
            this[FactTaskDelivery.estimateAtDoneMd] = it.estimateAtDoneMd?.toBigDecimal()
            this[FactTaskDelivery.estimateCurrentMd] = it.estimateCurrentMd?.toBigDecimal()
            this[FactTaskDelivery.estimateSource] = it.estimateSource
            this[FactTaskDelivery.estimateChangesAfterStart] = it.estimateChangesAfterStart
            this[FactTaskDelivery.estimatedLate] = it.estimatedLate
            this[FactTaskDelivery.actualMd] = it.actualMd.toBigDecimal()
            this[FactTaskDelivery.hasWorklogs] = it.hasWorklogs
            this[FactTaskDelivery.blockedMs] = it.blockedMs
            this[FactTaskDelivery.blockedWorkingDays] = it.blockedWorkingDays.toBigDecimal()
            this[FactTaskDelivery.cycleMs] = it.cycleMs
            this[FactTaskDelivery.cycleWorkingDays] = it.cycleWorkingDays?.toBigDecimal()
            this[FactTaskDelivery.leadMs] = it.leadMs
            this[FactTaskDelivery.leadWorkingDays] = it.leadWorkingDays?.toBigDecimal()
            this[FactTaskDelivery.activeMs] = it.activeMs
            this[FactTaskDelivery.waitMs] = it.waitMs
            this[FactTaskDelivery.assigneeAccountIdAtDone] = it.assigneeAccountIdAtDone
            this[FactTaskDelivery.assigneeTeamIdAtDone] = it.assigneeTeamIdAtDone
            this[FactTaskDelivery.sprintIdAtDone] = it.sprintIdAtDone
            this[FactTaskDelivery.sprintTeamIdAtDone] = it.sprintTeamIdAtDone
            this[FactTaskDelivery.creditTeamId] = it.creditTeamId
            this[FactTaskDelivery.currentTeamId] = it.currentTeamId
            this[FactTaskDelivery.currentAssigneeAccountId] = it.currentAssigneeAccountId
            this[FactTaskDelivery.domainKey] = it.domainKey
            this[FactTaskDelivery.epicId] = it.epicId
            this[FactTaskDelivery.epicDomainKey] = it.epicDomainKey
            this[FactTaskDelivery.crossDomain] = it.crossDomain
            this[FactTaskDelivery.activityType] = it.activityType
            this[FactTaskDelivery.workCategory] = it.workCategory
            this[FactTaskDelivery.isSubtask] = it.isSubtask
            this[FactTaskDelivery.parentTaskId] = it.parentTaskId
            this[FactTaskDelivery.currentStage] = it.currentStage
            this[FactTaskDelivery.flags] = stringArrayJson(it.flags)
            this[FactTaskDelivery.configRevision] = configRevision
        }
    }

    /** Wholesale-replace shape (one call), kept for a caller with every row in memory — `IngestWorkerTest`'s own fixture seeding. */
    suspend fun replaceFactTaskDelivery(connectionId: UInt, rows: List<FactTaskDeliveryRow>, configRevision: Long) {
        deleteFactTaskDelivery(connectionId)
        insertFactTaskDelivery(connectionId, rows, configRevision)
    }

    suspend fun deleteFactEpicDelivery(connectionId: UInt) {
        FactEpicDelivery.deleteWhere { FactEpicDelivery.connectionId eq connectionId }
    }

    /** One batch's worth of `fact_epic_delivery` rows — call per batch, AFTER [deleteFactEpicDelivery] ran once. */
    suspend fun insertFactEpicDelivery(connectionId: UInt, rows: List<FactEpicDeliveryRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactEpicDelivery.batchInsert(rows) {
            this[FactEpicDelivery.connectionId] = connectionId
            this[FactEpicDelivery.issueId] = it.issueId
            this[FactEpicDelivery.startedAt] = it.startedAt
            this[FactEpicDelivery.doneAt] = it.doneAt
            this[FactEpicDelivery.ownEstimateAtStartMd] = it.ownEstimateAtStartMd?.toBigDecimal()
            this[FactEpicDelivery.ownEstimateAtDoneMd] = it.ownEstimateAtDoneMd?.toBigDecimal()
            this[FactEpicDelivery.ownEstimateCurrentMd] = it.ownEstimateCurrentMd?.toBigDecimal()
            this[FactEpicDelivery.estimateChangesAfterStart] = it.estimateChangesAfterStart
            this[FactEpicDelivery.childSumEstimateMd] = it.childSumEstimateMd.toBigDecimal()
            this[FactEpicDelivery.budgetSource] = it.budgetSource
            this[FactEpicDelivery.actualMd] = it.actualMd.toBigDecimal()
            this[FactEpicDelivery.cycleMs] = it.cycleMs
            this[FactEpicDelivery.cycleWorkingDays] = it.cycleWorkingDays?.toBigDecimal()
            this[FactEpicDelivery.blockedMs] = it.blockedMs
            this[FactEpicDelivery.blockedWorkingDays] = it.blockedWorkingDays.toBigDecimal()
            this[FactEpicDelivery.domainKey] = it.domainKey
            this[FactEpicDelivery.workCategory] = it.workCategory
            this[FactEpicDelivery.driftFlags] = stringArrayJson(it.driftFlags)
            this[FactEpicDelivery.ownerTeamId] = it.ownerTeamId
            this[FactEpicDelivery.configRevision] = configRevision
        }
    }

    /** Wholesale-replace shape (one call), kept for a caller with every row in memory already. */
    suspend fun replaceFactEpicDelivery(connectionId: UInt, rows: List<FactEpicDeliveryRow>, configRevision: Long) {
        deleteFactEpicDelivery(connectionId)
        insertFactEpicDelivery(connectionId, rows, configRevision)
    }

    // ---- Sprint step (v0.3.0 M3 commit 8: dim_sprint, fact_sprint_scope, fact_sprint, snapshots) ----

    /** Deletes `dim_sprint`/`fact_sprint_scope`/`fact_sprint` for one connection — `fact_sprint_snapshot`
     * is append-only and never deleted here. */
    suspend fun deleteSprintFacts(connectionId: UInt) {
        FactSprintScope.deleteWhere { FactSprintScope.connectionId eq connectionId }
        FactSprint.deleteWhere { FactSprint.connectionId eq connectionId }
        DimSprint.deleteWhere { DimSprint.connectionId eq connectionId }
    }

    suspend fun insertDimSprints(connectionId: UInt, rows: List<DimSprintRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        DimSprint.batchInsert(rows) {
            this[DimSprint.connectionId] = connectionId
            this[DimSprint.sprintId] = it.sprintId
            this[DimSprint.boardId] = it.boardId
            this[DimSprint.teamId] = it.teamId
            this[DimSprint.name] = it.name
            this[DimSprint.state] = it.state
            this[DimSprint.startAt] = it.startAt
            this[DimSprint.endAt] = it.endAt
            this[DimSprint.completeAt] = it.completeAt
            this[DimSprint.capacityMd] = it.capacityMd?.toBigDecimal()
            this[DimSprint.capacitySource] = it.capacitySource
            this[DimSprint.configRevision] = configRevision
        }
    }

    /** One batch's worth of `fact_sprint_scope` rows — call per batch, AFTER [deleteSprintFacts] ran once for the connection. */
    suspend fun insertFactSprintScope(connectionId: UInt, rows: List<FactSprintScopeRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactSprintScope.batchInsert(rows) { (sprintId, item) ->
            this[FactSprintScope.connectionId] = connectionId
            this[FactSprintScope.sprintId] = sprintId
            this[FactSprintScope.issueId] = item.issueId
            this[FactSprintScope.addedAt] = item.addedAtMs
            this[FactSprintScope.removedAt] = item.removedAtMs
            this[FactSprintScope.committed] = item.committed
            this[FactSprintScope.inScopeAtClose] = item.inScopeAtClose
            this[FactSprintScope.estimateAtCommitmentMd] = item.estimateAtCommitmentMd?.toBigDecimal()
            this[FactSprintScope.estimateAtCloseMd] = item.estimateAtCloseMd?.toBigDecimal()
            this[FactSprintScope.estimateAtDoneMd] = item.estimateAtDoneMd?.toBigDecimal()
            this[FactSprintScope.assigneeAtCommitment] = item.assigneeAtCommitment
            this[FactSprintScope.doneInSprint] = item.doneInSprint
            this[FactSprintScope.carriedOver] = item.carriedOver
            this[FactSprintScope.dropped] = item.dropped
            this[FactSprintScope.configRevision] = configRevision
        }
    }

    suspend fun insertFactSprint(connectionId: UInt, rows: List<FactSprintRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactSprint.batchInsert(rows) {
            this[FactSprint.connectionId] = connectionId
            this[FactSprint.sprintId] = it.sprintId
            this[FactSprint.teamId] = it.teamId
            this[FactSprint.completeAt] = it.completeAt
            this[FactSprint.committedMd] = it.totals.committedMd.toBigDecimal()
            this[FactSprint.committedItems] = it.totals.committedItems
            this[FactSprint.addedMd] = it.totals.addedMd.toBigDecimal()
            this[FactSprint.addedItems] = it.totals.addedItems
            this[FactSprint.removedMd] = it.totals.removedMd.toBigDecimal()
            this[FactSprint.removedItems] = it.totals.removedItems
            this[FactSprint.finalMd] = it.totals.finalMd.toBigDecimal()
            this[FactSprint.finalItems] = it.totals.finalItems
            this[FactSprint.deliveredMd] = it.totals.deliveredMd.toBigDecimal()
            this[FactSprint.deliveredItems] = it.totals.deliveredItems
            this[FactSprint.carriedOverMd] = it.totals.carriedOverMd.toBigDecimal()
            this[FactSprint.carriedOverItems] = it.totals.carriedOverItems
            this[FactSprint.droppedMd] = it.totals.droppedMd.toBigDecimal()
            this[FactSprint.droppedItems] = it.totals.droppedItems
            this[FactSprint.capacityMd] = it.capacityMd?.toBigDecimal()
            this[FactSprint.load] = it.load?.toBigDecimal()
            this[FactSprint.configRevision] = configRevision
        }
    }

    /** Every `metrics.fact_sprint_snapshot` sprint id already frozen for this connection — a DERIVE
     * run never re-inserts one (D13, invariant 11). */
    suspend fun existingSnapshotSprintIds(connectionId: UInt): Set<Long> = suspendTransaction(database) {
        FactSprintSnapshot.select(FactSprintSnapshot.sprintId).where { FactSprintSnapshot.connectionId eq connectionId }
            .toList().map { it[FactSprintSnapshot.sprintId] }.toSet()
    }

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
     * ONE `metrics.fact_sprint_snapshot` row (D13, invariant 11: append-only, immutable once
     * written) — INSERT only, never called for a sprint id [existingSnapshotSprintIds] already
     * names; the DB trigger (`.claude/docs/persistence.md`) is the actual enforcement against a
     * hand-rolled `UPDATE`/`DELETE`, this method simply never attempts one. [scopeRows] freezes the
     * sprint's own `fact_sprint_scope` rows as JSON so a per-user velocity read from the snapshot
     * needs no child table.
     */
    suspend fun insertFactSprintSnapshot(
        connectionId: UInt,
        row: FactSprintRow,
        scopeRows: List<SprintScopeItem>,
        configRevision: Long,
        processingVersion: Int,
        reconstructed: Boolean,
        snapshotAt: Long,
    ) = suspendTransaction(database) {
        FactSprintSnapshot.insert {
            it[FactSprintSnapshot.connectionId] = connectionId
            it[sprintId] = row.sprintId
            it[teamId] = row.teamId
            it[completeAt] = row.completeAt
            it[committedMd] = row.totals.committedMd.toBigDecimal()
            it[committedItems] = row.totals.committedItems
            it[addedMd] = row.totals.addedMd.toBigDecimal()
            it[addedItems] = row.totals.addedItems
            it[removedMd] = row.totals.removedMd.toBigDecimal()
            it[removedItems] = row.totals.removedItems
            it[finalMd] = row.totals.finalMd.toBigDecimal()
            it[finalItems] = row.totals.finalItems
            it[deliveredMd] = row.totals.deliveredMd.toBigDecimal()
            it[deliveredItems] = row.totals.deliveredItems
            it[carriedOverMd] = row.totals.carriedOverMd.toBigDecimal()
            it[carriedOverItems] = row.totals.carriedOverItems
            it[droppedMd] = row.totals.droppedMd.toBigDecimal()
            it[droppedItems] = row.totals.droppedItems
            it[capacityMd] = row.capacityMd?.toBigDecimal()
            it[load] = row.load?.toBigDecimal()
            it[scope] = sprintScopeItemsJson(scopeRows)
            it[FactSprintSnapshot.configRevision] = configRevision
            it[FactSprintSnapshot.processingVersion] = processingVersion
            it[FactSprintSnapshot.reconstructed] = reconstructed
            it[FactSprintSnapshot.snapshotAt] = snapshotAt
        }
        Unit
    }

    suspend fun deleteFactWorklog(connectionId: UInt) {
        FactWorklog.deleteWhere { FactWorklog.connectionId eq connectionId }
    }

    /** One batch's worth of `fact_worklog` rows (v0.3.0 M3 commit 9) — call per batch, AFTER [deleteFactWorklog] ran once. */
    suspend fun insertFactWorklog(connectionId: UInt, rows: List<FactWorklogRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactWorklog.batchInsert(rows) {
            this[FactWorklog.connectionId] = connectionId
            this[FactWorklog.worklogId] = it.worklogId
            this[FactWorklog.issueId] = it.issueId
            this[FactWorklog.authorAccountId] = it.authorAccountId
            this[FactWorklog.authorTeamId] = it.authorTeamId
            this[FactWorklog.startedAt] = it.startedAt
            this[FactWorklog.createdAt] = it.createdAt
            this[FactWorklog.lateMs] = it.lateMs
            this[FactWorklog.md] = it.md.toBigDecimal()
            this[FactWorklog.taskDomainKey] = it.taskDomainKey
            this[FactWorklog.epicId] = it.epicId
            this[FactWorklog.epicDomainKey] = it.epicDomainKey
            this[FactWorklog.activityType] = it.activityType
            this[FactWorklog.workCategory] = it.workCategory
            this[FactWorklog.sprintIdAtStarted] = it.sprintIdAtStarted
            this[FactWorklog.sprintTeamIdAtStarted] = it.sprintTeamIdAtStarted
            this[FactWorklog.foreignWork] = it.foreignWork
            this[FactWorklog.assigneeAccountIdAtStarted] = it.assigneeAccountIdAtStarted
            this[FactWorklog.assigneeTeamIdAtStarted] = it.assigneeTeamIdAtStarted
            this[FactWorklog.configRevision] = configRevision
        }
    }

    suspend fun deleteFactEpicPlan(connectionId: UInt) {
        FactEpicPlan.deleteWhere { FactEpicPlan.connectionId eq connectionId }
    }

    /** One batch's worth of `fact_epic_plan` rows (v0.3.0 M3 commit 9b) — call per batch, AFTER [deleteFactEpicPlan] ran once. */
    suspend fun insertFactEpicPlan(connectionId: UInt, rows: List<FactEpicPlanRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactEpicPlan.batchInsert(rows) {
            this[FactEpicPlan.connectionId] = connectionId
            this[FactEpicPlan.issueId] = it.issueId
            this[FactEpicPlan.baselineSeq] = it.baselineSeq
            this[FactEpicPlan.baselinedAt] = it.baseline.baselinedAtMs
            this[FactEpicPlan.startAt] = it.baseline.startAtMs
            this[FactEpicPlan.dueAt] = it.baseline.dueAtMs
            this[FactEpicPlan.budgetMd] = it.baseline.budgetMd.toBigDecimal()
            this[FactEpicPlan.budgetSource] = it.baseline.budgetSource
            this[FactEpicPlan.supersededAt] = it.baseline.supersededAtMs
            this[FactEpicPlan.configRevision] = configRevision
        }
    }

    suspend fun deleteAggDailyWip(connectionId: UInt) {
        AggDailyWip.deleteWhere { AggDailyWip.connectionId eq connectionId }
    }

    /** Report 9's WIP row count for `derive_runs.row_counts` — read back after [execAggDailyWip]'s own inserts commit. */
    suspend fun countAggDailyWip(connectionId: UInt): Int =
        AggDailyWip.selectAll().where { AggDailyWip.connectionId eq connectionId }.count().toInt()

    /**
     * Runs one `INSERT ... SELECT` statement against `metrics.agg_daily_wip` — `DeriveWipStep.kt`
     * builds the SQL text itself (pure aggregation over already-persisted `metrics.*`/`norm.*` rows,
     * no Kotlin-side computation needed), run through `exec(...)` — the raw-SQL route [purgeAll]
     * also uses — since only [MetricsStore] holds [database] (the `exec` receiver). Reuses the
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

    /**
     * `ANALYZE` over [ANALYZED_TABLES] — every table the WIP and flow `INSERT ... SELECT`s read —
     * so the planner sees the rows THIS run just rebuilt. DERIVE rewrites them inside ONE
     * transaction, and autovacuum can neither see uncommitted rows nor run in time for the very next
     * statement, so without it those statements plan against stale (or, on a fresh table, default
     * `rows=1`) estimates: nested loops over tens of thousands of rows, and every re-derive of the
     * same connection slower than the last as dead tuples pile up. Unlike `VACUUM`, `ANALYZE` is
     * legal inside a transaction block, and it counts the transaction's own inserted rows as live and
     * its own deletes as dead, so the statistics describe the rebuilt state. Table names are fixed
     * constants (no user input); reuses the CALLER's transaction like [execAggDailyWip].
     * The tables stay locked (`SHARE UPDATE EXCLUSIVE`, which conflicts with itself) until that
     * transaction commits. Two DERIVEs therefore serialize from this statement to their commit: the
     * second one's ANALYZE waits for the first's commit (no deadlock — every DERIVE ANALYZEs the same
     * tables in the same order). The fact-building phase before it overlaps freely (`dim_date` is
     * ensured in its own transaction). The lock also conflicts with VACUUM/autovacuum and DDL: autovacuum
     * on these tables is skipped or cancelled meanwhile (an anti-wraparound vacuum would make the derive
     * wait — rare). It must NOT be made skippable: it has to see this transaction's uncommitted rows.
     */
    suspend fun analyzeDerivedTables() = suspendTransaction(database) { exec("ANALYZE ${ANALYZED_TABLES.joinToString(", ")}") }

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
     * Every rebuildable `metrics.*` row for one connection, in dependency-safe order — the PURGE
     * job's generic step (`ingest/IngestWorker.kt`, `.claude/docs/ingestion.md` "PURGE"). Snapshot
     * rows need `SET LOCAL metrics.allow_snapshot_delete = 'on'` first (the ONE sanctioned bypass of
     * the immutability trigger, `.claude/docs/persistence.md`) — scoped to the CALLER's transaction,
     * so this whole method must run inside `suspendTransaction`. `derive_runs` (review round 2b)
     * joins the drain too — a purged connection's own run HISTORY has no reader left once its raw/
     * norm/star rows are all gone, the `sync_jobs` hard-delete-on-terminal precedent applied to a
     * table that, unlike `sync_jobs`, has no opportunistic retention window of its own until a
     * connection is actually deleted.
     */
    suspend fun purgeAll(connectionId: UInt) = suspendTransaction(database) {
        exec("SET LOCAL metrics.allow_snapshot_delete = 'on'")
        FactSprintSnapshot.deleteWhere { FactSprintSnapshot.connectionId eq connectionId }
        FactSprint.deleteWhere { FactSprint.connectionId eq connectionId }
        FactSprintScope.deleteWhere { FactSprintScope.connectionId eq connectionId }
        FactWorklog.deleteWhere { FactWorklog.connectionId eq connectionId }
        FactEpicPlan.deleteWhere { FactEpicPlan.connectionId eq connectionId }
        FactEpicDelivery.deleteWhere { FactEpicDelivery.connectionId eq connectionId }
        FactTaskDelivery.deleteWhere { FactTaskDelivery.connectionId eq connectionId }
        AggDailyWip.deleteWhere { AggDailyWip.connectionId eq connectionId }
        AggDailyFlow.deleteWhere { AggDailyFlow.connectionId eq connectionId }
        ItemBlocked.deleteWhere { ItemBlocked.connectionId eq connectionId }
        ItemStage.deleteWhere { ItemStage.connectionId eq connectionId }
        ItemEstimate.deleteWhere { ItemEstimate.connectionId eq connectionId }
        TaskSprint.deleteWhere { TaskSprint.connectionId eq connectionId }
        TaskAssignee.deleteWhere { TaskAssignee.connectionId eq connectionId }
        TaskDomain.deleteWhere { TaskDomain.connectionId eq connectionId }
        TaskEpic.deleteWhere { TaskEpic.connectionId eq connectionId }
        DimSprint.deleteWhere { DimSprint.connectionId eq connectionId }
        DimEpic.deleteWhere { DimEpic.connectionId eq connectionId }
        DimTask.deleteWhere { DimTask.connectionId eq connectionId }
        DimDomain.deleteWhere { DimDomain.connectionId eq connectionId }
        DeriveRuns.deleteWhere { DeriveRuns.connectionId eq connectionId.toInt() }
        Unit
    }

    /** Test/diagnostic reads — `MetricsDerivationTest`'s own invariant sweeps read the table objects above directly via this database. */
    suspend fun <T> query(block: suspend () -> T): T = suspendTransaction(database) { block() }
}
