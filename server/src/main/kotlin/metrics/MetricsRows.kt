package ch.nokillswit.metrics

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
 * time"/D3, `.claude/docs/metrics.md` "Worklog cost facts") — the author's team AND
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
