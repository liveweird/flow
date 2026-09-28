package ch.nokillswit.metrics

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.teams.TeamService
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val MetricsStoreKey = AttributeKey<MetricsStore>("MetricsStore")

private fun stringArrayJson(values: List<String>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

// ---- Row shapes MetricsDeriver assembles per connection --------------------------------------

data class DimDomainRow(val domainKey: String, val name: String, val projectKeys: List<String>)

data class DimTaskRow(
    val issueId: Long,
    val issueKey: String,
    val issueType: String,
    val activityType: String,
    val workCategory: String?,
    val workCategorySource: String,
    val isSubtask: Boolean,
    val parentTaskId: Long?,
    val domainKey: String?,
    val epicId: Long?,
)

data class DimEpicRow(
    val issueId: Long,
    val issueKey: String,
    val summary: String?,
    val domainKey: String?,
    val workCategory: String?,
    val currentStage: String,
    val startAt: Long?,
    val dueAt: Long?,
)

data class TaskEpicRow(val issueId: Long, val epicId: Long?, val fromAtMs: Long, val toAtMs: Long?)
data class TaskDomainRow(val issueId: Long, val domainKey: String?, val fromAtMs: Long, val toAtMs: Long?)
data class TaskAssigneeRow(val issueId: Long, val accountId: String?, val fromAtMs: Long, val toAtMs: Long?)
data class TaskSprintRow(val issueId: Long, val sprintId: Long, val fromAtMs: Long, val toAtMs: Long?)
data class ItemEstimateRow(val issueId: Long, val estimateMd: Double?, val fromAtMs: Long, val toAtMs: Long?)
data class ItemStageRow(val issueId: Long, val stage: String, val statusId: String, val fromAtMs: Long, val toAtMs: Long?)
data class ItemBlockedRow(val issueId: Long, val reason: String, val fromAtMs: Long, val toAtMs: Long?)

data class FactTaskDeliveryRow(
    val issueId: Long,
    val issueKey: String,
    val createdAt: Long,
    val startedAt: Long?,
    val doneAt: Long?,
    val reopenCount: Int,
    val estimateAtStartMd: Double?,
    val estimateAtDoneMd: Double?,
    val estimateCurrentMd: Double?,
    val estimateSource: String,
    val estimateChangesAfterStart: Int,
    val estimatedLate: Boolean,
    val actualMd: Double,
    val hasWorklogs: Boolean,
    val blockedMs: Long,
    val blockedWorkingDays: Double,
    val cycleMs: Long?,
    val cycleWorkingDays: Double?,
    val leadMs: Long?,
    val leadWorkingDays: Double?,
    val activeMs: Long,
    val waitMs: Long,
    val assigneeAccountIdAtDone: String?,
    val assigneeTeamIdAtDone: UInt?,
    val sprintIdAtDone: Long?,
    val sprintTeamIdAtDone: UInt?,
    /** D5's delivery credit (sprint team, else assignee's team) — `null` when neither resolves (no sentinel FK row exists for that). */
    val creditTeamId: UInt?,
    val domainKey: String?,
    val epicId: Long?,
    val epicDomainKey: String?,
    val crossDomain: Boolean,
    val activityType: String,
    val workCategory: String?,
    val isSubtask: Boolean,
    val parentTaskId: Long?,
    val currentStage: String,
    val flags: List<String>,
)

data class FactEpicDeliveryRow(
    val issueId: Long,
    val startedAt: Long?,
    val doneAt: Long?,
    val ownEstimateAtStartMd: Double?,
    val ownEstimateAtDoneMd: Double?,
    val ownEstimateCurrentMd: Double?,
    val estimateChangesAfterStart: Int,
    val childSumEstimateMd: Double,
    val budgetSource: String,
    val actualMd: Double,
    val cycleMs: Long?,
    val cycleWorkingDays: Double?,
    val blockedMs: Long,
    val blockedWorkingDays: Double,
    val domainKey: String?,
    val workCategory: String?,
    val driftFlags: List<String>,
)

/** Row counts one DERIVE run wrote — `metrics.derive_runs.row_counts` (`MetricsDeriver`). */
data class DeriveRowCounts(val tasks: Int, val epics: Int)

/**
 * The `metrics` schema's derived star (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md`
 * "Analytical model") — Exposed table objects (the `norm` schema-qualification precedent, `Table(
 * "metrics.…")`) plus batch writers `metrics/MetricsDeriver.kt` calls inside its ONE per-connection
 * transaction. Every table here is rebuilt WHOLESALE per DERIVE run (delete this connection's rows,
 * insert the freshly derived ones) EXCEPT [DimDate] (global, upserted `ON CONFLICT (day) DO
 * UPDATE`) and [FactSprintSnapshot] (append-only — no update/delete writer exists here at all; the
 * DB's own trigger, `.claude/docs/persistence.md`, is the actual enforcement). `dim_sprint`/
 * `task_sprint`/`fact_sprint*`/`fact_worklog`/`fact_epic_plan`/`agg_daily_*` writers arrive with
 * commits 8/9 — this commit creates their table objects (so [purgeAll] already drains them) but
 * only writes dims/most-bridges/`fact_task_delivery`/`fact_epic_delivery`.
 */
class MetricsStore(private val database: R2dbcDatabase) {

    object DimDate : Table("metrics.dim_date") {
        val day = varchar("day", 10)
        val dayStartMs = long("day_start_ms")
        val dayEndMs = long("day_end_ms")
        val isWorkingDay = bool("is_working_day")
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(day)
    }

    object DimDomain : Table("metrics.dim_domain") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val domainKey = varchar("domain_key", 50)
        val name = varchar("name", 100)
        val projectKeys = jsonb("project_keys")
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, domainKey)
    }

    object DimTask : Table("metrics.dim_task") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val issueKey = varchar("issue_key", 20)
        val issueType = varchar("issue_type", 50)
        val activityType = varchar("activity_type", 50)
        val workCategory = varchar("work_category", 100).nullable()
        val workCategorySource = varchar("work_category_source", 10)
        val isSubtask = bool("is_subtask").default(false)
        val parentTaskId = long("parent_task_id").nullable()
        val domainKey = varchar("domain_key", 50).nullable()
        val epicId = long("epic_id").nullable()
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, issueId)
    }

    object DimEpic : Table("metrics.dim_epic") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val issueKey = varchar("issue_key", 20)
        val summary = text("summary").nullable()
        val domainKey = varchar("domain_key", 50).nullable()
        val workCategory = varchar("work_category", 100).nullable()
        val currentStage = varchar("current_stage", 20)
        val startAt = long("start_at").nullable()
        val dueAt = long("due_at").nullable()
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, issueId)
    }

    object DimSprint : Table("metrics.dim_sprint") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val boardId = long("board_id").nullable()
        val teamId = reference("team_id", TeamService.Teams).nullable()
        val name = varchar("name", 200)
        val state = varchar("state", 20)
        val startAt = long("start_at").nullable()
        val endAt = long("end_at").nullable()
        val completeAt = long("complete_at").nullable()
        val capacityMd = decimal("capacity_md", precision = 8, scale = 2).nullable()
        val capacitySource = varchar("capacity_source", 10).nullable()
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    object TaskEpic : Table("metrics.task_epic") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val epicId = long("epic_id").nullable()
        val validFrom = long("valid_from")
        val validTo = long("valid_to").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object TaskDomain : Table("metrics.task_domain") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val domainKey = varchar("domain_key", 50).nullable()
        val validFrom = long("valid_from")
        val validTo = long("valid_to").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object TaskAssignee : Table("metrics.task_assignee") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val accountId = varchar("account_id", 100).nullable()
        val validFrom = long("valid_from")
        val validTo = long("valid_to").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object TaskSprint : Table("metrics.task_sprint") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val sprintId = long("sprint_id")
        val validFrom = long("valid_from")
        val validTo = long("valid_to").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object ItemEstimate : Table("metrics.item_estimate") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val estimateMd = decimal("estimate_md", precision = 8, scale = 2).nullable()
        val validFrom = long("valid_from")
        val validTo = long("valid_to").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object ItemStage : Table("metrics.item_stage") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val stage = varchar("stage", 20)
        val statusId = varchar("status_id", 50)
        val validFrom = long("valid_from")
        val validTo = long("valid_to").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object ItemBlocked : Table("metrics.item_blocked") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val reason = varchar("reason", 10)
        val validFrom = long("valid_from")
        val validTo = long("valid_to").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object FactTaskDelivery : Table("metrics.fact_task_delivery") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val issueKey = varchar("issue_key", 20)
        val createdAt = long("created_at")
        val startedAt = long("started_at").nullable()
        val doneAt = long("done_at").nullable()
        val reopenCount = integer("reopen_count").default(0)
        val estimateAtStartMd = decimal("estimate_at_start_md", precision = 8, scale = 2).nullable()
        val estimateAtDoneMd = decimal("estimate_at_done_md", precision = 8, scale = 2).nullable()
        val estimateCurrentMd = decimal("estimate_current_md", precision = 8, scale = 2).nullable()
        val estimateSource = varchar("estimate_source", 10)
        val estimateChangesAfterStart = integer("estimate_changes_after_start").default(0)
        val estimatedLate = bool("estimated_late").default(false)
        val actualMd = decimal("actual_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val hasWorklogs = bool("has_worklogs").default(false)
        val blockedMs = long("blocked_ms").default(0)
        val blockedWorkingDays = decimal("blocked_working_days", precision = 10, scale = 4).default(java.math.BigDecimal.ZERO)
        val cycleMs = long("cycle_ms").nullable()
        val cycleWorkingDays = decimal("cycle_working_days", precision = 10, scale = 4).nullable()
        val leadMs = long("lead_ms").nullable()
        val leadWorkingDays = decimal("lead_working_days", precision = 10, scale = 4).nullable()
        val activeMs = long("active_ms").default(0)
        val waitMs = long("wait_ms").default(0)
        val assigneeAccountIdAtDone = varchar("assignee_account_id_at_done", 100).nullable()
        val assigneeTeamIdAtDone = reference("assignee_team_id_at_done", TeamService.Teams).nullable()
        val sprintIdAtDone = long("sprint_id_at_done").nullable()
        val sprintTeamIdAtDone = reference("sprint_team_id_at_done", TeamService.Teams).nullable()
        val creditTeamId = reference("credit_team_id", TeamService.Teams).nullable()
        val domainKey = varchar("domain_key", 50).nullable()
        val epicId = long("epic_id").nullable()
        val epicDomainKey = varchar("epic_domain_key", 50).nullable()
        val crossDomain = bool("cross_domain").default(false)
        val activityType = varchar("activity_type", 50)
        val workCategory = varchar("work_category", 100).nullable()
        val isSubtask = bool("is_subtask").default(false)
        val parentTaskId = long("parent_task_id").nullable()
        val currentStage = varchar("current_stage", 20)
        val flags = jsonb("flags")
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, issueId)
    }

    object FactEpicDelivery : Table("metrics.fact_epic_delivery") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val startedAt = long("started_at").nullable()
        val doneAt = long("done_at").nullable()
        val ownEstimateAtStartMd = decimal("own_estimate_at_start_md", precision = 8, scale = 2).nullable()
        val ownEstimateAtDoneMd = decimal("own_estimate_at_done_md", precision = 8, scale = 2).nullable()
        val ownEstimateCurrentMd = decimal("own_estimate_current_md", precision = 8, scale = 2).nullable()
        val estimateChangesAfterStart = integer("estimate_changes_after_start").default(0)
        val childSumEstimateMd = decimal("child_sum_estimate_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val budgetSource = varchar("budget_source", 10)
        val actualMd = decimal("actual_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val cycleMs = long("cycle_ms").nullable()
        val cycleWorkingDays = decimal("cycle_working_days", precision = 10, scale = 4).nullable()
        val blockedMs = long("blocked_ms").default(0)
        val blockedWorkingDays = decimal("blocked_working_days", precision = 10, scale = 4).default(java.math.BigDecimal.ZERO)
        val domainKey = varchar("domain_key", 50).nullable()
        val workCategory = varchar("work_category", 100).nullable()
        val driftFlags = jsonb("drift_flags")
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, issueId)
    }

    object FactSprintScope : Table("metrics.fact_sprint_scope") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val issueId = long("issue_id")
        override val primaryKey = PrimaryKey(connectionId, sprintId, issueId)
    }

    object FactSprint : Table("metrics.fact_sprint") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    object FactSprintSnapshot : Table("metrics.fact_sprint_snapshot") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    object FactWorklog : Table("metrics.fact_worklog") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val worklogId = long("worklog_id")
        override val primaryKey = PrimaryKey(connectionId, worklogId)
    }

    object FactEpicPlan : Table("metrics.fact_epic_plan") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        override val primaryKey = PrimaryKey(id)
    }

    object AggDailyWip : Table("metrics.agg_daily_wip") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
    }

    object AggDailyFlow : Table("metrics.agg_daily_flow") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
    }

    /** `dim_date` is global — upserted `ON CONFLICT (day) DO UPDATE`, never deleted per connection. */
    suspend fun upsertDimDate(rows: List<WorkingCalendar.DimDateRow>, configRevision: Long) = suspendTransaction(database) {
        if (rows.isEmpty()) return@suspendTransaction
        DimDate.batchUpsert(rows, DimDate.day) { row ->
            this[DimDate.day] = row.day
            this[DimDate.dayStartMs] = row.dayStartMs
            this[DimDate.dayEndMs] = row.dayEndMs
            this[DimDate.isWorkingDay] = row.isWorkingDay
            this[DimDate.configRevision] = configRevision
        }
    }

    /** Wholesale-rebuilds `dim_domain`/`dim_task`/`dim_epic` for one connection — call inside the caller's own transaction. */
    suspend fun replaceDims(
        connectionId: UInt,
        domains: List<DimDomainRow>,
        tasks: List<DimTaskRow>,
        epics: List<DimEpicRow>,
        configRevision: Long,
    ) {
        DimDomain.deleteWhere { DimDomain.connectionId eq connectionId }
        if (domains.isNotEmpty()) {
            DimDomain.batchInsert(domains) {
                this[DimDomain.connectionId] = connectionId
                this[DimDomain.domainKey] = it.domainKey
                this[DimDomain.name] = it.name
                this[DimDomain.projectKeys] = stringArrayJson(it.projectKeys)
                this[DimDomain.configRevision] = configRevision
            }
        }
        DimTask.deleteWhere { DimTask.connectionId eq connectionId }
        if (tasks.isNotEmpty()) {
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
        DimEpic.deleteWhere { DimEpic.connectionId eq connectionId }
        if (epics.isNotEmpty()) {
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
    }

    /**
     * Wholesale-rebuilds every bridge this commit populates (`task_epic`/`task_domain`/
     * `task_assignee`/`task_sprint`/`item_estimate`/`item_stage`/`item_blocked`).
     */
    suspend fun replaceBridges(
        connectionId: UInt,
        taskEpic: List<TaskEpicRow>,
        taskDomain: List<TaskDomainRow>,
        taskAssignee: List<TaskAssigneeRow>,
        taskSprint: List<TaskSprintRow>,
        itemEstimate: List<ItemEstimateRow>,
        itemStage: List<ItemStageRow>,
        itemBlocked: List<ItemBlockedRow>,
    ) {
        TaskEpic.deleteWhere { TaskEpic.connectionId eq connectionId }
        if (taskEpic.isNotEmpty()) {
            TaskEpic.batchInsert(taskEpic) {
                this[TaskEpic.connectionId] = connectionId
                this[TaskEpic.issueId] = it.issueId
                this[TaskEpic.epicId] = it.epicId
                this[TaskEpic.validFrom] = it.fromAtMs
                this[TaskEpic.validTo] = it.toAtMs
            }
        }
        TaskDomain.deleteWhere { TaskDomain.connectionId eq connectionId }
        if (taskDomain.isNotEmpty()) {
            TaskDomain.batchInsert(taskDomain) {
                this[TaskDomain.connectionId] = connectionId
                this[TaskDomain.issueId] = it.issueId
                this[TaskDomain.domainKey] = it.domainKey
                this[TaskDomain.validFrom] = it.fromAtMs
                this[TaskDomain.validTo] = it.toAtMs
            }
        }
        TaskAssignee.deleteWhere { TaskAssignee.connectionId eq connectionId }
        if (taskAssignee.isNotEmpty()) {
            TaskAssignee.batchInsert(taskAssignee) {
                this[TaskAssignee.connectionId] = connectionId
                this[TaskAssignee.issueId] = it.issueId
                this[TaskAssignee.accountId] = it.accountId
                this[TaskAssignee.validFrom] = it.fromAtMs
                this[TaskAssignee.validTo] = it.toAtMs
            }
        }
        TaskSprint.deleteWhere { TaskSprint.connectionId eq connectionId }
        if (taskSprint.isNotEmpty()) {
            TaskSprint.batchInsert(taskSprint) {
                this[TaskSprint.connectionId] = connectionId
                this[TaskSprint.issueId] = it.issueId
                this[TaskSprint.sprintId] = it.sprintId
                this[TaskSprint.validFrom] = it.fromAtMs
                this[TaskSprint.validTo] = it.toAtMs
            }
        }
        ItemEstimate.deleteWhere { ItemEstimate.connectionId eq connectionId }
        if (itemEstimate.isNotEmpty()) {
            ItemEstimate.batchInsert(itemEstimate) {
                this[ItemEstimate.connectionId] = connectionId
                this[ItemEstimate.issueId] = it.issueId
                this[ItemEstimate.estimateMd] = it.estimateMd?.toBigDecimal()
                this[ItemEstimate.validFrom] = it.fromAtMs
                this[ItemEstimate.validTo] = it.toAtMs
            }
        }
        ItemStage.deleteWhere { ItemStage.connectionId eq connectionId }
        if (itemStage.isNotEmpty()) {
            ItemStage.batchInsert(itemStage) {
                this[ItemStage.connectionId] = connectionId
                this[ItemStage.issueId] = it.issueId
                this[ItemStage.stage] = it.stage
                this[ItemStage.statusId] = it.statusId
                this[ItemStage.validFrom] = it.fromAtMs
                this[ItemStage.validTo] = it.toAtMs
            }
        }
        ItemBlocked.deleteWhere { ItemBlocked.connectionId eq connectionId }
        if (itemBlocked.isNotEmpty()) {
            ItemBlocked.batchInsert(itemBlocked) {
                this[ItemBlocked.connectionId] = connectionId
                this[ItemBlocked.issueId] = it.issueId
                this[ItemBlocked.reason] = it.reason
                this[ItemBlocked.validFrom] = it.fromAtMs
                this[ItemBlocked.validTo] = it.toAtMs
            }
        }
    }

    suspend fun replaceFactTaskDelivery(connectionId: UInt, rows: List<FactTaskDeliveryRow>, configRevision: Long) {
        FactTaskDelivery.deleteWhere { FactTaskDelivery.connectionId eq connectionId }
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

    suspend fun replaceFactEpicDelivery(connectionId: UInt, rows: List<FactEpicDeliveryRow>, configRevision: Long) {
        FactEpicDelivery.deleteWhere { FactEpicDelivery.connectionId eq connectionId }
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
            this[FactEpicDelivery.configRevision] = configRevision
        }
    }

    /**
     * Every rebuildable `metrics.*` row for one connection, in dependency-safe order — the PURGE
     * job's generic step (`ingest/IngestWorker.kt`, `.claude/docs/ingestion.md` "PURGE"). Snapshot
     * rows need `SET LOCAL metrics.allow_snapshot_delete = 'on'` first (the ONE sanctioned bypass of
     * the immutability trigger, `.claude/docs/persistence.md`) — scoped to the CALLER's transaction,
     * so this whole method must run inside `suspendTransaction`.
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
        Unit
    }

    /** Test/diagnostic reads — `MetricsDerivationTest`'s own invariant sweeps read the table objects above directly via this database. */
    suspend fun <T> query(block: suspend () -> T): T = suspendTransaction(database) { block() }
}
