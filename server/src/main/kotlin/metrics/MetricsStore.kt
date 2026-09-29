package ch.nokillswit.metrics

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.teams.TeamService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive
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
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val MetricsStoreKey = AttributeKey<MetricsStore>("MetricsStore")

/**
 * The tables `DeriveWipStep.kt`/`DeriveFlowStep.kt`'s SQL reads and `MetricsDeriver` has just
 * rebuilt in its transaction ([MetricsStore.analyzeDerivedTables]): the connection-scoped dims,
 * bridges and facts, plus the global `dim_date` (upserted in the same run; every step joins it).
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

private fun stringArrayJson(values: List<String>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

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

// ---- Row shapes MetricsDeriver assembles per connection --------------------------------------

/** [ownerTeamId] (V17, A19/A22) — this domain's resolved owner team, `MetricsDeriver.ownerTeamByDomain`'s own output. */
data class DimDomainRow(val domainKey: String, val name: String, val projectKeys: List<String>, val ownerTeamId: UInt?)

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
    /** D5's delivery credit (sprint team, else assignee's team) — null when neither resolves (no sentinel FK row for that). */
    val creditTeamId: UInt?,
    /** A21/A22: D5 evaluated NOW — the current (not closed) sprint's team, else the assignee's team, for every task. */
    val currentTeamId: UInt?,
    /** A21: the assignee at now (the norm ASSIGNEE interval containing it). */
    val currentAssigneeAccountId: String?,
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
    /** A19: the epic's own domain's owner team — configured, else the one mapped board on that project; null = UNOWNED. */
    val ownerTeamId: UInt?,
)

/**
 * Row counts one DERIVE run wrote — `metrics.derive_runs.row_counts` (`MetricsDeriver`).
 * [sprintFieldUnresolved] is `true` when the connection HAS sprints but its profile detected no
 * Sprint-shaped custom field (`MetricsConfigService.detectedSprintFieldId` returned `null`) — the
 * sprint step then skips writing any sprint facts for this run rather than fabricating membership
 * from a display-name match; recorded here so the skip is visible on the run, not silent.
 */
data class DeriveRowCounts(
    val tasks: Int,
    val epics: Int,
    val sprints: Int,
    val sprintFieldUnresolved: Boolean = false,
    val worklogs: Int = 0,
    val epicPlans: Int = 0,
    val aggWipRows: Int = 0,
    val aggFlowRows: Int = 0,
)

// ---- Sprint step row shapes (v0.3.0 M3 commit 8) ---------------------------------------------

/** One `metrics.dim_sprint` row — `capacitySource`/`teamId`/`capacityMd` are all `null` together
 * when the sprint's board maps to no team. */
data class DimSprintRow(
    val sprintId: Long,
    val boardId: Long?,
    val teamId: UInt?,
    val name: String,
    val state: String,
    val startAt: Long?,
    val endAt: Long?,
    val completeAt: Long?,
    val capacityMd: Double?,
    val capacitySource: String?,
)

/** One `metrics.fact_sprint_scope` row — [DeriveKernels.SprintScopeItem] plus the `sprintId` its own kernel call didn't carry. */
data class FactSprintScopeRow(val sprintId: Long, val item: SprintScopeItem)

/**
 * One `metrics.fact_worklog` row (v0.3.0 M3 commit 9, `.claude/docs/domain-model.md` "Cross-team
 * time"/D3, `.claude/docs/metrics.md` "Worklog cost facts (fact_worklog)") — the author's team AND
 * the task's domain/epic, both as-of `startedAt`, so cost can be sliced by who spent it and what it
 * was spent on at once.
 */
data class FactWorklogRow(
    val worklogId: Long,
    val issueId: Long,
    val authorAccountId: String?,
    val authorTeamId: UInt?,
    val startedAt: Long,
    val createdAt: Long?,
    val lateMs: Long?,
    val md: Double,
    val taskDomainKey: String?,
    val epicId: Long?,
    val epicDomainKey: String?,
    val activityType: String,
    val workCategory: String?,
    val sprintIdAtStarted: Long?,
    val sprintTeamIdAtStarted: UInt?,
    val foreignWork: Boolean,
    /** A21: the task's assignee (and their team) at the worklog's own started_at — foreign work's fallback with no sprint team. */
    val assigneeAccountIdAtStarted: String?,
    val assigneeTeamIdAtStarted: UInt?,
)

/**
 * One `metrics.fact_epic_plan` row (v0.3.0 M3 commit 9b, `.claude/docs/domain-model.md`
 * "Plan — PV", D4) — [DeriveKernels.EpicPlanBaseline] plus the `issueId`/`baselineSeq` its own
 * kernel call didn't carry (the [FactSprintScopeRow] precedent).
 */
data class FactEpicPlanRow(val issueId: Long, val baselineSeq: Int, val baseline: EpicPlanBaseline)

/** One `metrics.fact_sprint`/`metrics.fact_sprint_snapshot` row's shared figures —
 * [SprintTotals] plus capacity/load and identity. */
data class FactSprintRow(
    val sprintId: Long,
    val teamId: UInt?,
    val completeAt: Long?,
    val totals: SprintTotals,
    val capacityMd: Double?,
    val load: Double?,
)

/**
 * The `metrics` schema's derived star (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md`
 * "Analytical model") — Exposed table objects (the `norm` schema-qualification precedent, `Table(
 * "metrics.…")`) plus batch writers `metrics/MetricsDeriver.kt` calls inside its ONE per-connection
 * transaction. Every table here is rebuilt WHOLESALE per DERIVE run (delete this connection's rows,
 * insert the freshly derived ones) EXCEPT [DimDate] (global, upserted `ON CONFLICT (day) DO
 * UPDATE`) and [FactSprintSnapshot] (append-only — no update/delete writer exists here at all; the
 * DB's own trigger, `.claude/docs/persistence.md`, is the actual enforcement). `dim_sprint`/
 * `task_sprint`/`fact_sprint*`/`fact_worklog`/`fact_epic_plan` writers landed across commits 7-9b
 * (this commit created every `metrics.*` table object, so [purgeAll] already drains them all);
 * `agg_daily_*` still awaits its own writer.
 */
class MetricsStore(private val database: R2dbcDatabase) {

    /**
     * One DERIVE run's own bookkeeping row (v0.3.0 M3 commit 7, V15) — moved here from
     * `MetricsDeriver.kt` (review round 2b) so [pruneDeriveRuns]/[purgeAll] can reach it without a
     * cross-class table reference; `MetricsDeriver.kt` still owns every WRITE to it (`derive()`),
     * this class owns the table object and its read/prune/purge paths, the `sync_jobs`/`sync_cursors`
     * split precedent. `status` is `CHECK`-constrained to `RUNNING`/`SUCCEEDED`/`FAILED` (V15's
     * immutable bytes) — a run a cancelled coroutine interrupts is marked FAILED, not a fourth
     * `CANCELLED` value, since adding one would need altering that CHECK in a new migration
     * (`.claude/docs/metrics.md` documents this choice).
     */
    object DeriveRuns : Table("metrics.derive_runs") {
        val id = integer("id").autoIncrement()
        val connectionId = integer("connection_id")
        val jobId = integer("job_id").nullable()
        val configRevision = long("config_revision")
        val processingVersion = integer("processing_version")
        val startedAt = long("started_at")
        val finishedAt = long("finished_at").nullable()
        val status = varchar("status", 20)
        val rowCounts = jsonb("row_counts").nullable()
        val errorDetail = text("error_detail").nullable()
        override val primaryKey = PrimaryKey(id)
    }

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
        /** V17, A19/A22 — this domain's resolved owner team (`MetricsDeriver.ownerTeamByDomain`). */
        val ownerTeamId = reference("owner_team_id", TeamService.Teams).nullable()
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
        val currentTeamId = reference("current_team_id", TeamService.Teams).nullable()
        val currentAssigneeAccountId = varchar("current_assignee_account_id", 100).nullable()
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
        val ownerTeamId = reference("owner_team_id", TeamService.Teams).nullable()
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, issueId)
    }

    object FactSprintScope : Table("metrics.fact_sprint_scope") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val issueId = long("issue_id")
        val addedAt = long("added_at").nullable()
        val removedAt = long("removed_at").nullable()
        val committed = bool("committed").default(false)
        val inScopeAtClose = bool("in_scope_at_close").default(false)
        val estimateAtCommitmentMd = decimal("estimate_at_commitment_md", precision = 8, scale = 2).nullable()
        val estimateAtCloseMd = decimal("estimate_at_close_md", precision = 8, scale = 2).nullable()
        val estimateAtDoneMd = decimal("estimate_at_done_md", precision = 8, scale = 2).nullable()
        val assigneeAtCommitment = varchar("assignee_at_commitment", 100).nullable()
        val doneInSprint = bool("done_in_sprint").default(false)
        val carriedOver = bool("carried_over").default(false)
        val dropped = bool("dropped").default(false)
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, sprintId, issueId)
    }

    object FactSprint : Table("metrics.fact_sprint") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val teamId = reference("team_id", TeamService.Teams).nullable()
        val completeAt = long("complete_at").nullable()
        val committedMd = decimal("committed_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val committedItems = integer("committed_items").default(0)
        val addedMd = decimal("added_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val addedItems = integer("added_items").default(0)
        val removedMd = decimal("removed_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val removedItems = integer("removed_items").default(0)
        val finalMd = decimal("final_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val finalItems = integer("final_items").default(0)
        val deliveredMd = decimal("delivered_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val deliveredItems = integer("delivered_items").default(0)
        val carriedOverMd = decimal("carried_over_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val carriedOverItems = integer("carried_over_items").default(0)
        val droppedMd = decimal("dropped_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val droppedItems = integer("dropped_items").default(0)
        val capacityMd = decimal("capacity_md", precision = 8, scale = 2).nullable()
        val load = decimal("load", precision = 8, scale = 4).nullable()
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    /** Append-only (D13, invariant 11) — no update/delete writer exists here; the DB trigger is the actual enforcement, see class doc. */
    object FactSprintSnapshot : Table("metrics.fact_sprint_snapshot") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val teamId = reference("team_id", TeamService.Teams).nullable()
        val completeAt = long("complete_at").nullable()
        val committedMd = decimal("committed_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val committedItems = integer("committed_items").default(0)
        val addedMd = decimal("added_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val addedItems = integer("added_items").default(0)
        val removedMd = decimal("removed_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val removedItems = integer("removed_items").default(0)
        val finalMd = decimal("final_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val finalItems = integer("final_items").default(0)
        val deliveredMd = decimal("delivered_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val deliveredItems = integer("delivered_items").default(0)
        val carriedOverMd = decimal("carried_over_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val carriedOverItems = integer("carried_over_items").default(0)
        val droppedMd = decimal("dropped_md", precision = 10, scale = 2).default(java.math.BigDecimal.ZERO)
        val droppedItems = integer("dropped_items").default(0)
        val capacityMd = decimal("capacity_md", precision = 8, scale = 2).nullable()
        val load = decimal("load", precision = 8, scale = 4).nullable()
        val scope = jsonb("scope")
        val configRevision = long("config_revision")
        val processingVersion = integer("processing_version")
        val reconstructed = bool("reconstructed").default(false)
        val snapshotAt = long("snapshot_at")
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    object FactWorklog : Table("metrics.fact_worklog") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val worklogId = long("worklog_id")
        val issueId = long("issue_id")
        val authorAccountId = varchar("author_account_id", 100).nullable()
        val authorTeamId = reference("author_team_id", TeamService.Teams).nullable()
        val startedAt = long("started_at")
        val createdAt = long("created_at").nullable()
        val lateMs = long("late_ms").nullable()
        val md = decimal("md", precision = 8, scale = 4)
        val taskDomainKey = varchar("task_domain_key", 50).nullable()
        val epicId = long("epic_id").nullable()
        val epicDomainKey = varchar("epic_domain_key", 50).nullable()
        val activityType = varchar("activity_type", 50).nullable()
        val workCategory = varchar("work_category", 100).nullable()
        val sprintIdAtStarted = long("sprint_id_at_started").nullable()
        val sprintTeamIdAtStarted = reference("sprint_team_id_at_started", TeamService.Teams).nullable()
        val foreignWork = bool("foreign_work").default(false)
        val assigneeAccountIdAtStarted = varchar("assignee_account_id_at_started", 100).nullable()
        val assigneeTeamIdAtStarted = reference("assignee_team_id_at_started", TeamService.Teams).nullable()
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, worklogId)
    }

    object FactEpicPlan : Table("metrics.fact_epic_plan") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val baselineSeq = integer("baseline_seq")
        val baselinedAt = long("baselined_at")
        val startAt = long("start_at").nullable()
        val dueAt = long("due_at").nullable()
        val budgetMd = decimal("budget_md", precision = 10, scale = 2).nullable()
        val budgetSource = varchar("budget_source", 10)
        val supersededAt = long("superseded_at").nullable()
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(id)
    }

    /**
     * Report 9's WIP snapshot (`.claude/docs/metrics.md` "Daily WIP aggregate") — one row per
     * `(scope_kind, scope_id, day, item_kind, status_id, stage)`, `item_count` the number of items
     * whose `item_stage` interval covered the END of that day. Written entirely via raw SQL
     * (`DeriveWipStep.kt`, [execAggDailyWip]) — this table object exists for [deleteAggDailyWip]/
     * [countAggDailyWip] and for [purgeAll]'s own drain, never for a Kotlin-side row insert.
     */
    object AggDailyWip : Table("metrics.agg_daily_wip") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val scopeKind = varchar("scope_kind", 10)
        val scopeId = varchar("scope_id", 60)
        val day = varchar("day", 10)
        val itemKind = varchar("item_kind", 10)
        val statusId = varchar("status_id", 50)
        val stage = varchar("stage", 20)
        val itemCount = integer("item_count").default(0)
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, scopeKind, scopeId, day, itemKind, statusId, stage)
    }

    /**
     * Report 16's daily flow aggregate (`.claude/docs/metrics.md` "Daily flow aggregate") — one row
     * per `(scope_kind, scope_id, day)`, sparse. Written entirely via raw SQL
     * (`DeriveFlowStep.kt`, [execAggDailyFlow]) — every statement is its own additive
     * `INSERT ... ON CONFLICT DO UPDATE` contribution; this table object exists for
     * [deleteAggDailyFlow]/[countAggDailyFlow], [purgeAll]'s drain and test reads.
     */
    object AggDailyFlow : Table("metrics.agg_daily_flow") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val scopeKind = varchar("scope_kind", 10)
        val scopeId = varchar("scope_id", 60)
        val day = varchar("day", 10)
        val backlogItems = integer("backlog_items").default(0)
        val backlogMd = decimal("backlog_md", 10, 2).default(java.math.BigDecimal.ZERO)
        val pvMd = decimal("pv_md", 10, 2).default(java.math.BigDecimal.ZERO)
        val evMd = decimal("ev_md", 10, 2).default(java.math.BigDecimal.ZERO)
        val acMd = decimal("ac_md", 10, 2).default(java.math.BigDecimal.ZERO)
        val throughputItems = integer("throughput_items").default(0)
        val throughputMd = decimal("throughput_md", 10, 2).default(java.math.BigDecimal.ZERO)
        val configRevision = long("config_revision")
        override val primaryKey = PrimaryKey(connectionId, scopeKind, scopeId, day)
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

    /**
     * Wholesale-rebuilds every bridge this commit populates (`task_epic`/`task_domain`/
     * `task_assignee`/`task_sprint`/`item_estimate`/`item_stage`/`item_blocked`) in ONE call — kept
     * for a caller with every row in memory already; `MetricsDeriver.kt`'s own batched write calls
     * [deleteBridges] plus the per-table insert methods above directly instead.
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
        deleteBridges(connectionId)
        insertTaskEpic(connectionId, taskEpic)
        insertTaskDomain(connectionId, taskDomain)
        insertTaskAssignee(connectionId, taskAssignee)
        insertTaskSprint(connectionId, taskSprint)
        insertItemEstimate(connectionId, itemEstimate)
        insertItemStage(connectionId, itemStage)
        insertItemBlocked(connectionId, itemBlocked)
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
     * The tables stay locked (`SHARE UPDATE EXCLUSIVE`) until that transaction commits. Between
     * DERIVEs this is never contended — they are already serialized by the global `dim_date` upsert
     * (overlapping ranges, row locks held to commit) — and it conflicts only with VACUUM/ANALYZE/
     * autovacuum and DDL: autovacuum on these tables is skipped or cancelled meanwhile (an
     * anti-wraparound vacuum would make the derive wait — rare).
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
