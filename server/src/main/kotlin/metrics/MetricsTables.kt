package ch.nokillswit.metrics

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.teams.TeamService
import org.jetbrains.exposed.v1.core.*

/**
 * The `metrics` schema's derived star (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md`
 * "Analytical model") — the Exposed table objects (the `norm` schema-qualification precedent, `Table(
 * "metrics.…")`), split out of `MetricsStore.kt` (checkup D2) so the batch writers and the row shapes
 * each live in their own file. Every table here is rebuilt WHOLESALE per DERIVE run (delete this
 * connection's rows, insert the freshly derived ones) EXCEPT [DimDate] (global, reconciled by
 * [MetricsStore.ensureDimDate] in its own transaction) and [FactSprintSnapshot] (append-only — no
 * update/delete writer exists at all; the DB's own trigger, `.claude/docs/persistence.md`, is the
 * actual enforcement). `dim_sprint`/`task_sprint`/`fact_sprint*`/`fact_worklog`/`fact_epic_plan`
 * writers landed across commits 7-9b (this commit created every `metrics.*` table object, so
 * [MetricsStore.purgeAll] already drains them all); `agg_daily_*` is written by raw SQL.
 */
object MetricsTables {
    /**
     * One DERIVE run's own bookkeeping row (v0.3.0 M3 commit 7, V15) — moved here from
     * `MetricsDeriver.kt` (review round 2b) so [MetricsStore.pruneDeriveRuns]/[MetricsStore.purgeAll] can reach it without a
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
     * (`DeriveWipStep.kt`, [MetricsStore.execAggDailyWip]) — this table object exists for [MetricsStore.deleteAggDailyWip]/
     * [MetricsStore.countAggDailyWip] and for [MetricsStore.purgeAll]'s own drain, never for a Kotlin-side row insert.
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
     * (`DeriveFlowStep.kt`, [MetricsStore.execAggDailyFlow]) — every statement is its own additive
     * `INSERT ... ON CONFLICT DO UPDATE` contribution; this table object exists for
     * [MetricsStore.deleteAggDailyFlow]/[MetricsStore.countAggDailyFlow], [MetricsStore.purgeAll]'s drain and test reads.
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
}
