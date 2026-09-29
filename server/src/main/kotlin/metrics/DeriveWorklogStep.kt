package ch.nokillswit.metrics

import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore

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

/** Every `fact_worklog` row for [item]'s own raw worklogs — see [runWorklogStep]. */
private fun worklogRowsForItem(
    item: WorkItemStore.DerivationWorkItemRow,
    worklogs: List<WorkItemStore.DerivationWorklogRow>,
    context: DeriveContext,
    derivedById: Map<Long, ItemDerived>,
): List<FactWorklogRow> {
    val isEpic = item.hierarchyLevel == EPIC_HIERARCHY_LEVEL
    val currentDomainKey = context.domainByProject[item.projectKey] ?: item.projectKey
    val currentEpicId = if (isEpic) item.issueId else epicIdOf(item, context)
    val domainHistory = if (!isEpic) taskDomainHistory(item, context) else emptyList()
    val epicHistory = if (!isEpic && !item.isSubtask) taskEpicHistory(item, context) else emptyList()
    val activityType = context.activityTypeByIssueType[item.issueType] ?: item.issueType
    val setup = WorklogRowSetup(isEpic, currentDomainKey, currentEpicId, domainHistory, epicHistory, activityType)

    return worklogs.map { wl -> worklogRow(item, wl, context, derivedById, setup) }
}

/** [worklogRowsForItem]'s own per-item, per-worklog-batch inputs — split out purely to keep [worklogRow]'s own parameter count sane. */
private data class WorklogRowSetup(
    val isEpic: Boolean,
    val currentDomainKey: String,
    val currentEpicId: Long?,
    val domainHistory: List<TaskDomainRow>,
    val epicHistory: List<TaskEpicRow>,
    val activityType: String,
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
    val isEpic = setup.isEpic
    val currentDomainKey = setup.currentDomainKey
    val currentEpicId = setup.currentEpicId
    val domainHistory = setup.domainHistory
    val epicHistory = setup.epicHistory
    val activityType = setup.activityType
    val startedAt = wl.startedAt
    // Commit 9d: a worklog logged directly on an EPIC gets its own (current) domain, never
    // null — a task's worklog keeps reading task_domain history as-of the worklog's own started.
    // A covering row with an unparseable (null) domain falls back to the current domain — a task has
    // exactly one domain at any instant (unlike the epic, where a covering null means "no epic").
    val coveringDomain = if (isEpic) null else domainRowAt(domainHistory, startedAt)
    val taskDomainKey = if (isEpic) currentDomainKey else coveringDomain?.domainKey ?: currentDomainKey
    val coveringEpic = if (isEpic || item.isSubtask) null else epicRowAt(epicHistory, startedAt)
    val epicId = when {
        isEpic -> item.issueId
        item.isSubtask -> currentEpicId
        coveringEpic != null -> coveringEpic.epicId
        else -> currentEpicId
    }
    val epicItem = epicId?.let { context.itemsById[it] }
    val epicDomainKey = if (isEpic) currentDomainKey else epicItem?.let { context.domainByProject[it.projectKey] ?: it.projectKey }
    val ownCategory = derivedById[item.issueId]?.ownCategory
    val epicCategory = epicId?.let { derivedById[it]?.ownCategory }
    val workCategory = ownCategory ?: epicCategory
    val authorTeamId = teamAt(wl.authorAccountId, startedAt, context.membershipsByAccount)
    val sprintId = valueAt(context.sprintIntervalsByIssue[item.issueId].orEmpty(), startedAt)?.toLongOrNull()
    val sprintTeamId = sprintId?.let { context.sprintBoardById[it] }?.let { context.boardTeamByBoardId[it] }
    // Commit 9d, A21: the assignments half — the task's assignee (and their team) at the
    // WORKLOG's own started_at, foreign work's fallback when no sprint team is known there.
    val (assigneeAtStarted, assigneeTeamAtStarted) = assigneeAndTeamAt(item, context, startedAt)
    val md = (wl.timeSpentSeconds / SECONDS_PER_HOUR) / context.hoursPerDay
    val lateMs = wl.createdAt?.let { createdAt -> maxOf(0L, createdAt - startedAt) }
    // A22 (commit 9e): a worklog logged DIRECTLY on an epic compares the author's team against
    // the epic's own OWNER team, never its assignee's team (epics have no sprint, so the
    // sprint-team argument is always null here) — a task-logged worklog keeps the existing
    // sprint-else-assignee rule unchanged.
    val foreignWork = if (isEpic) {
        isForeignWork(authorTeamId, null, context.ownerTeamByDomain[currentDomainKey])
    } else {
        isForeignWork(authorTeamId, sprintTeamId, assigneeTeamAtStarted)
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
