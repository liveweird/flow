package ch.nokillswit.metrics

import ch.nokillswit.norm.HierarchyBucket
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.hierarchyBucket

/**
 * The worklog step's own body (v0.3.0 M3 commit 9, moved to `DeriveWorklogStep.kt` purely to keep
 * [MetricsDeriver] under detekt's `LargeClass` threshold — the sprint step's own precedent above): one
 * `fact_worklog` row per `norm.work_item_worklogs` row, carrying the author's team AND the task's
 * domain/epic, BOTH as-of the worklog's own `started_at` — never the item's current/done-time
 * values (`.claude/docs/domain-model.md` "Cross-team time"/D3, `.claude/docs/metrics.md` "Worklog
 * cost facts (fact_worklog)"). Batches over only the items that actually carry a worklog,
 * re-reading each batch's PARENT/`issuekey`/SPRINT intervals the same way pass 2 does (the raw
 * intervals are batch-scoped, never held for the whole connection at once — the review round 2b
 * memory bound applies here too).
 */
internal suspend fun runWorklogStep(
    workItemStore: WorkItemStore,
    metricsStore: MetricsStore,
    connectionId: UInt,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    context: DeriveContext,
    derivedById: Map<Long, ItemDerived>,
    configRevision: Long,
): Int {
    val worklogsByIssue = workItemStore.worklogsByIssue(connectionId)
    if (worklogsByIssue.isEmpty()) return 0
    val itemsWithWorklogs = workItems.filter { worklogsByIssue.containsKey(it.issueId) }
    var count = 0
    for (batch in itemsWithWorklogs.chunked(DERIVE_BATCH_SIZE)) {
        val ids = batch.map { it.issueId }
        context.parentIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.PARENT, ids)
        context.issueKeyChangesByIssue =
            workItemStore.fieldChangesByFieldIds(connectionId, listOf(ISSUE_KEY_FIELD_ID), ids).groupBy { it.issueId }
        context.sprintIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.SPRINT, ids)
        // A21 (commit 9d): the assignments half of foreign-work detection needs each batch's OWN
        // assignee intervals too — the same batch-scoped read pass2 already does.
        context.assigneeIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.ASSIGNEE, ids)

        val rowsBatch = mutableListOf<FactWorklogRow>()
        for (item in batch) {
            rowsBatch += worklogRowsForItem(item, worklogsByIssue.getValue(item.issueId), context, derivedById)
        }
        metricsStore.insertFactWorklog(connectionId, rowsBatch, configRevision)
        count += rowsBatch.size
    }
    return count
}

/**
 * What a worklogged item is to the model (A31): a TASK (or sub-task), an EPIC, or ABOVE_EPIC — a level-2+ issue, outside the model
 * (no `dim_*`/bridge/fact row of its own). Its worklogs are still kept (invariant 6) and attributed like an epic-logged worklog's,
 * minus the epic: no `epic_id`/`epic_domain_key`/sprint, never in epic or domain EVM.
 */
private enum class WorklogItemKind { TASK, EPIC, ABOVE_EPIC }

private fun worklogItemKind(item: WorkItemStore.DerivationWorkItemRow): WorklogItemKind = when (hierarchyBucket(item.hierarchyLevel)) {
    HierarchyBucket.EPIC -> WorklogItemKind.EPIC
    HierarchyBucket.TASK -> WorklogItemKind.TASK
    null -> WorklogItemKind.ABOVE_EPIC
}

/** Every `fact_worklog` row for [item]'s own raw worklogs — see [runWorklogStep]. */
private fun worklogRowsForItem(
    item: WorkItemStore.DerivationWorkItemRow,
    worklogs: List<WorkItemStore.DerivationWorklogRow>,
    context: DeriveContext,
    derivedById: Map<Long, ItemDerived>,
): List<FactWorklogRow> {
    val kind = worklogItemKind(item)
    val isTask = kind == WorklogItemKind.TASK
    val currentDomainKey = context.domainByProject[item.projectKey] ?: item.projectKey
    val currentEpicId = when (kind) {
        WorklogItemKind.EPIC -> item.issueId
        WorklogItemKind.ABOVE_EPIC -> null
        WorklogItemKind.TASK -> epicIdOf(item, context)
    }
    val domainHistory = if (isTask) taskDomainHistory(item, context) else emptyList()
    val epicHistory = if (isTask && !item.isSubtask) taskEpicHistory(item, context) else emptyList()
    val activityType = context.activityTypeByIssueType[item.issueType] ?: item.issueType
    // An above-epic item never enters passes 1-3, so it has no ItemDerived: its own category is read straight off the configured field.
    val ownCategory = if (kind == WorklogItemKind.ABOVE_EPIC) ownWorkCategory(item, context) else derivedById[item.issueId]?.ownCategory
    val setup = WorklogRowSetup(kind, currentDomainKey, currentEpicId, domainHistory, epicHistory, activityType, ownCategory)

    return worklogs.map { wl -> worklogRow(item, wl, context, derivedById, setup) }
}

/** [worklogRowsForItem]'s own per-item, per-worklog-batch inputs — split out purely to keep [worklogRow]'s own parameter count sane. */
private data class WorklogRowSetup(
    val kind: WorklogItemKind,
    val currentDomainKey: String,
    val currentEpicId: Long?,
    val domainHistory: List<TaskDomainRow>,
    val epicHistory: List<TaskEpicRow>,
    val activityType: String,
    val ownCategory: String?,
)

/**
 * One `fact_worklog` row (extracted out of [worklogRowsForItem] purely to stay under detekt's
 * `CyclomaticComplexMethod` threshold — the per-worklog branching lives here instead, unchanged).
 */
private fun worklogRow(
    item: WorkItemStore.DerivationWorkItemRow,
    wl: WorkItemStore.DerivationWorklogRow,
    context: DeriveContext,
    derivedById: Map<Long, ItemDerived>,
    setup: WorklogRowSetup,
): FactWorklogRow {
    val kind = setup.kind
    val isTask = kind == WorklogItemKind.TASK
    val currentDomainKey = setup.currentDomainKey
    val currentEpicId = setup.currentEpicId
    val activityType = setup.activityType
    val startedAt = wl.startedAt
    // Commit 9d: a worklog logged directly on an EPIC (A31: or on an above-epic item) gets its own (current) domain, never
    // null — a task's worklog keeps reading task_domain history as-of the worklog's own started.
    // A covering row with an unparseable (null) domain falls back to the current domain — a task has
    // exactly one domain at any instant (unlike the epic, where a covering null means "no epic").
    val coveringDomain = if (isTask) domainRowAt(setup.domainHistory, startedAt) else null
    val taskDomainKey = if (isTask) coveringDomain?.domainKey ?: currentDomainKey else currentDomainKey
    val coveringEpic = if (!isTask || item.isSubtask) null else epicRowAt(setup.epicHistory, startedAt)
    // An epic-logged worklog is its own epic; an above-epic one has none (currentEpicId is null); a sub-task keeps its current epic.
    val epicId = if (coveringEpic != null) coveringEpic.epicId else currentEpicId
    val epicItem = epicId?.let { context.itemsById[it] }
    val epicDomainKey =
        if (kind == WorklogItemKind.EPIC) currentDomainKey else epicItem?.let { context.domainByProject[it.projectKey] ?: it.projectKey }
    val epicCategory = epicId?.let { derivedById[it]?.ownCategory }
    val workCategory = setup.ownCategory ?: epicCategory
    val authorTeamId = teamAt(wl.authorAccountId, startedAt, context.membershipsByAccount)
    val sprintId = valueAt(context.sprintIntervalsByIssue[item.issueId].orEmpty(), startedAt)?.toLongOrNull()
        .takeIf { kind != WorklogItemKind.ABOVE_EPIC }
    val sprintTeamId = sprintId?.let { context.sprintBoardById[it] }?.let { context.boardTeamByBoardId[it] }
    // Commit 9d, A21: the assignments half — the task's assignee (and their team) at the
    // WORKLOG's own started_at, foreign work's fallback when no sprint team is known there.
    val (assigneeAtStarted, assigneeTeamAtStarted) = assigneeAndTeamAt(item, context, startedAt)
    val md = (wl.timeSpentSeconds / SECONDS_PER_HOUR) / context.hoursPerDay
    val lateMs = wl.createdAt?.let { createdAt -> maxOf(0L, createdAt - startedAt) }
    // A22 (commit 9e): a worklog logged DIRECTLY on an epic (A31: or an above-epic item) compares the author's team against
    // the item's own domain's OWNER team, never its assignee's team (no sprint, so the sprint-team argument is always null
    // here) — a task-logged worklog keeps the existing sprint-else-assignee rule unchanged.
    val foreignWork = if (isTask) {
        isForeignWork(authorTeamId, sprintTeamId, assigneeTeamAtStarted)
    } else {
        isForeignWork(authorTeamId, null, context.ownerTeamByDomain[currentDomainKey])
    }
    return FactWorklogRow(
        worklogId = wl.worklogId,
        issueId = item.issueId,
        authorAccountId = wl.authorAccountId,
        authorTeamId = authorTeamId,
        startedAt = startedAt,
        createdAt = wl.createdAt,
        lateMs = lateMs,
        md = md,
        taskDomainKey = taskDomainKey,
        epicId = epicId,
        epicDomainKey = epicDomainKey,
        activityType = activityType,
        workCategory = workCategory,
        sprintIdAtStarted = sprintId,
        sprintTeamIdAtStarted = sprintTeamId,
        foreignWork = foreignWork,
        assigneeAccountIdAtStarted = assigneeAtStarted,
        assigneeTeamIdAtStarted = assigneeTeamAtStarted,
    )
}
