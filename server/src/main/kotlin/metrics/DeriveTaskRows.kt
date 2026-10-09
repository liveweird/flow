package ch.nokillswit.metrics

import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.fieldValueOptions
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

internal const val SECONDS_PER_HOUR = 3600.0
private const val UNMAPPED_STATUS_FLAG = "UNMAPPED_STATUS"

/** The item's OWN configured-estimate-field timeline — empty when no field is configured for its role (epic vs. task). */
private fun ownEstimateTimeline(
    item: WorkItemStore.DerivationWorkItemRow,
    context: DeriveContext,
    config: DataSourceMetricsConfig,
): List<EstimatePoint> {
    val fieldId = context.estimateFieldIdFor(item, config) ?: return emptyList()
    val changes = context.estimateChangesByIssueAndField[item.issueId].orEmpty().filter { it.fieldId == fieldId }
    val current = item.customFields[fieldId]?.jsonPrimitive?.doubleOrNull
    return estimateTimeline(item.createdAt, changes, current)
}

private fun ownEstimateSnapshots(timeline: List<EstimatePoint>, startedAt: Long?, doneAt: Long?): EstimateSnapshots =
    if (timeline.isEmpty()) {
        EstimateSnapshots(null, null, null, false, 0)
    } else {
        estimateSnapshots(timeline, startedAt, doneAt)
    }

internal fun ownWorkCategory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): String? {
    val fieldId = context.workCategoryFieldId ?: return null
    val valueId = fieldValueOptions(item.customFields[fieldId]).firstOrNull()?.first ?: return null
    return context.workCategoryMap[valueId]
}

// epicIdOf lives in `DeriveTaskRows.kt` (below — the `LargeClass` idiom, it takes no instance state).

internal fun deriveItem(
    item: WorkItemStore.DerivationWorkItemRow,
    context: DeriveContext,
    config: DataSourceMetricsConfig,
): ItemDerived {
    val statusIntervals = context.statusIntervalsByIssue[item.issueId].orEmpty()
    // The item's domain NOW (`domain_map`, else the project key itself — the same read `buildTaskRow`'s
    // callers use): a per-domain stage override applies to every status interval of the item.
    val stages = stageIntervals(
        statusIntervals,
        context.stageMapFor(context.domainByProject[item.projectKey] ?: item.projectKey),
    )
    val startedDone = startedDoneAt(stages)
    val blocked = blockedIntervals(
        context.flaggedIntervalsByIssue[item.issueId].orEmpty(),
        statusIntervals,
        context.blockedStatusIds,
        startedDone.startedAtMs,
        startedDone.doneAtMs ?: context.now,
    )
    val timeline = ownEstimateTimeline(item, context, config)
    val snapshots = ownEstimateSnapshots(timeline, startedDone.startedAtMs, startedDone.doneAtMs)
    val category = ownWorkCategory(item, context)
    return ItemDerived(
        stages, startedDone.startedAtMs, startedDone.doneAtMs, startedDone.reopenCount, blocked, snapshots, timeline, category,
    )
}

internal fun domainDims(context: DeriveContext): List<DimDomainRow> =
    context.domainByProject.entries
        .groupBy({ it.value }) { it.key }
        .map { (domainKey, projectKeys) ->
            DimDomainRow(domainKey, domainKey, projectKeys.distinct(), context.ownerTeamByDomain[domainKey])
        }

/**
 * D2's roll-up, applied at the epic level (review round 2a fix): `child_sum_estimate_md` sums
 * each LEVEL-0 child's own COMPOSITE estimate (`estimateCurrentMd` — OWN, else its own SUBTASKS
 * roll-up, never the child's OWN field alone), and `actual_md` is the epic's OWN worklogs PLUS
 * every child's ALREADY-ROLLED-UP `actualMd` (which itself already folds in that child's own
 * sub-tasks) — never a fresh worklog-seconds sum over the direct children alone, which silently
 * dropped every sub-task's worklog time from the epic's own cost entirely. [childFacts] are the
 * SAME `fact_task_delivery` rows already written for this epic's level-0 children — computed in
 * an earlier pass over [workItems] specifically so this rollup never re-derives them.
 */
internal fun buildEpicRow(
    item: WorkItemStore.DerivationWorkItemRow,
    derived: ItemDerived,
    context: DeriveContext,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    derivedById: Map<Long, ItemDerived>,
    factTasksByIssueId: Map<Long, FactTaskDeliveryRow>,
    domainKey: String,
    currentStage: ItemStage,
    blockedMs: Long,
    blockedWorkingDays: Double,
): Pair<DimEpicRow, FactEpicDeliveryRow> {
    val ownCategory = derived.ownCategory
    val epicStartAt = context.epicDateValue(item, context.epicStartFieldId)
    val epicDueAt = context.epicDateValue(item, context.epicDueFieldId)
    val dim = DimEpicRow(
        item.issueId, item.issueKey, item.summary, domainKey, ownCategory, currentStage.name, epicStartAt, epicDueAt,
    )
    val childTasks = workItems.filter { !it.isSubtask && it.parentIssueId == item.issueId }
    val childFacts = childTasks.mapNotNull { factTasksByIssueId[it.issueId] }
    val childSum = childFacts.sumOf { it.estimateCurrentMd ?: 0.0 }
    val ownCurrent = derived.ownSnapshots.currentMd
    val childStatuses = childTasks.map { derivedById.getValue(it.issueId).let { d -> ChildDeliveryStatus(d.started, d.done) } }
    val driftFlags = epicDriftFlags(currentStage, childStatuses, context.epicDriftDays, context.now)
    val ownWorklogSeconds = context.worklogSecondsByIssue[item.issueId] ?: 0L
    val actualMd = ownWorklogSeconds / SECONDS_PER_HOUR / context.hoursPerDay + childFacts.sumOf { it.actualMd }
    val fact = FactEpicDeliveryRow(
        issueId = item.issueId,
        startedAt = derived.started,
        doneAt = derived.done,
        ownEstimateAtStartMd = derived.ownSnapshots.atStartMd,
        ownEstimateAtDoneMd = derived.ownSnapshots.atDoneMd,
        ownEstimateCurrentMd = ownCurrent,
        estimateChangesAfterStart = derived.ownSnapshots.changesAfterStart,
        childSumEstimateMd = childSum,
        budgetSource = if (ownCurrent != null) "OWN" else "CHILDREN",
        actualMd = actualMd,
        cycleMs = cycleMs(derived),
        cycleWorkingDays = cycleWorkingDays(derived, context.calendar),
        blockedMs = blockedMs,
        blockedWorkingDays = blockedWorkingDays,
        domainKey = domainKey,
        workCategory = ownCategory,
        driftFlags = driftFlags.map { it.name },
        ownerTeamId = context.ownerTeamByDomain[domainKey],
    )
    return dim to fact
}

/**
 * The task's composite estimate (review round 2a fix): OWN wins outright; otherwise SUBTASKS
 * sums its sub-tasks' OWN timelines AS-OF the PARENT's own `startedAt`/`doneAt`
 * ([mergeEstimateTimelines]) — never their CURRENT sum reused at both snapshots,
 * which silently ignored every subtask estimate change and reported `estimatedLate`/
 * `changesAfterStart` off the (usually estimate-less, hence always-false) parent's OWN timeline
 * instead of the subtasks' actual history.
 */
private fun taskEstimate(
    derived: ItemDerived,
    childSubtasks: List<WorkItemStore.DerivationWorkItemRow>,
    derivedById: Map<Long, ItemDerived>,
    startedAt: Long?,
    doneAt: Long?,
): EstimateComposite {
    val ownCurrent = derived.ownSnapshots.currentMd
    if (ownCurrent != null) {
        return EstimateComposite(
            derived.ownSnapshots.atStartMd,
            derived.ownSnapshots.atDoneMd,
            ownCurrent,
            "OWN",
            derived.ownSnapshots.estimatedLate,
            derived.ownSnapshots.changesAfterStart,
        )
    }
    val merged = mergeEstimateTimelines(childSubtasks.map { derivedById.getValue(it.issueId).estimateTimeline })
    val current = merged.lastOrNull()?.estimateMd
    if (current == null) return EstimateComposite(null, null, null, "NONE", false, 0)
    val snapshots = estimateSnapshots(merged, startedAt, doneAt)
    return EstimateComposite(
        snapshots.atStartMd, snapshots.atDoneMd, current, "SUBTASKS", snapshots.estimatedLate, snapshots.changesAfterStart,
    )
}

internal fun buildTaskRow(
    item: WorkItemStore.DerivationWorkItemRow,
    derived: ItemDerived,
    context: DeriveContext,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    derivedById: Map<Long, ItemDerived>,
    domainKey: String,
    currentStage: ItemStage,
    blockedMs: Long,
    blockedWorkingDays: Double,
    taskDomainRows: List<TaskDomainRow>,
    taskEpicRows: List<TaskEpicRow>,
): TaskComposition {
    val epicId = epicIdOf(item, context)
    val epicDerived = epicId?.let { derivedById[it] }
    val epicCategory = epicDerived?.ownCategory
    val ownCategory = derived.ownCategory
    val workCategory = ownCategory ?: epicCategory
    val workCategorySource = if (ownCategory != null) "OWN" else if (epicCategory != null) "EPIC" else "NONE"
    val activityType = context.activityTypeByIssueType[item.issueType] ?: item.issueType

    val dimTask = DimTaskRow(
        item.issueId, item.issueKey, item.issueType, activityType, workCategory, workCategorySource,
        item.isSubtask, item.parentIssueId?.takeIf { item.isSubtask }, domainKey, epicId,
    )

    val childSubtasks = if (!item.isSubtask) workItems.filter { it.isSubtask && it.parentIssueId == item.issueId } else emptyList()
    val estimate = taskEstimate(derived, childSubtasks, derivedById, derived.started, derived.done)
    val actualMd = actualMdFor(item, childSubtasks, context.worklogSecondsByIssue, context.hoursPerDay)
    val sprintIdAtDone = derived.done?.let { valueAt(context.sprintIntervalsByIssue[item.issueId].orEmpty(), it)?.toLongOrNull() }
    val sprintTeamIdAtDone = sprintIdAtDone?.let { context.sprintBoardById[it] }?.let { context.boardTeamByBoardId[it] }
    val (assigneeAtDone, assigneeTeamAtDone) = derived.done?.let { assigneeAndTeamAt(item, context, it) } ?: (null to null)
    val creditTeamId = sprintTeamIdAtDone ?: assigneeTeamAtDone
    val flags = if (currentStage == ItemStage.UNMAPPED) listOf(UNMAPPED_STATUS_FLAG) else emptyList()

    // A21: attribution (domain, epic) is AS-WAS at `done_at ?: now` — the same effective-dated
    // task_domain/task_epic histories fact_worklog already reads at its own `started_at`, here
    // evaluated at the task's own delivery instant instead. A sub-task carries no task_epic
    // history of its own (D2 — see [taskEpicHistory]'s own doc), so it falls back to the
    // CURRENT (one-indirection) epic, the same [worklogRowsForItem] fallback.
    val asWas = asWasAttribution(item, context, taskDomainRows, taskEpicRows, domainKey, epicId, derived.done ?: context.now)

    // A21: `current_team_id`/`current_assignee_account_id` — D5 evaluated NOW, for every task,
    // done or not (the aging-WIP report's own team attribution for still-open items).
    val current = currentAttribution(item, context)

    // A18: flow efficiency — active/wait time, 0/0 while not done (`activeWaitMs`).
    val activeWait = activeWaitMs(derived.stages, derived.blocked, derived.started, derived.done)

    val fact = FactTaskDeliveryRow(
        issueId = item.issueId,
        issueKey = item.issueKey,
        createdAt = item.createdAt,
        startedAt = derived.started,
        doneAt = derived.done,
        reopenCount = derived.reopenCount,
        estimateAtStartMd = estimate.atStart,
        estimateAtDoneMd = estimate.atDone,
        estimateCurrentMd = estimate.current,
        estimateSource = estimate.source,
        estimateChangesAfterStart = estimate.changesAfterStart,
        estimatedLate = estimate.estimatedLate,
        actualMd = actualMd,
        hasWorklogs = actualMd > 0.0,
        blockedMs = blockedMs,
        blockedWorkingDays = blockedWorkingDays,
        cycleMs = cycleMs(derived),
        cycleWorkingDays = cycleWorkingDays(derived, context.calendar),
        leadMs = derived.done?.let { it - item.createdAt },
        leadWorkingDays = derived.done?.let { context.calendar.workingDaysBetween(item.createdAt, it) },
        activeMs = activeWait.activeMs,
        waitMs = activeWait.waitMs,
        assigneeAccountIdAtDone = assigneeAtDone,
        assigneeTeamIdAtDone = assigneeTeamAtDone,
        sprintIdAtDone = sprintIdAtDone,
        sprintTeamIdAtDone = sprintTeamIdAtDone,
        creditTeamId = creditTeamId,
        currentTeamId = current.teamId,
        currentAssigneeAccountId = current.assigneeAccountId,
        domainKey = asWas.domainKey,
        epicId = asWas.epicId,
        epicDomainKey = asWas.epicDomainKey,
        crossDomain = asWas.epicDomainKey != null && asWas.epicDomainKey != asWas.domainKey,
        activityType = activityType,
        workCategory = workCategory,
        isSubtask = item.isSubtask,
        parentTaskId = item.parentIssueId?.takeIf { item.isSubtask },
        currentStage = currentStage.name,
        flags = flags,
    )
    return TaskComposition(dimTask, fact)
}

private fun cycleMs(derived: ItemDerived): Long? =
    if (derived.started != null && derived.done != null) derived.done - derived.started else null

private fun cycleWorkingDays(derived: ItemDerived, calendar: WorkingCalendar): Double? =
    if (derived.started != null && derived.done != null) calendar.workingDaysBetween(derived.started, derived.done) else null

// taskEpicHistory/taskDomainHistory live in `DeriveTaskRows.kt` (below — the `LargeClass` idiom).

/** `task_assignee`'s effective-dated history (review round 2a fix) — a direct mirror of `norm`'s own ASSIGNEE field intervals. */
internal fun taskAssigneeHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskAssigneeRow> =
    context.assigneeIntervalsByIssue[item.issueId].orEmpty().map {
        TaskAssigneeRow(item.issueId, it.valueId, it.fromAtMs, it.toAtMs)
    }

private data class EstimateComposite(
    val atStart: Double?,
    val atDone: Double?,
    val current: Double?,
    val source: String,
    val estimatedLate: Boolean,
    val changesAfterStart: Int,
)
internal data class TaskComposition(val dim: DimTaskRow, val fact: FactTaskDeliveryRow)

internal fun buildEstimateBridge(
    item: WorkItemStore.DerivationWorkItemRow,
    timeline: List<EstimatePoint>,
    fieldId: String?,
): List<ItemEstimateRow> {
    if (fieldId == null) return emptyList()
    return timeline.mapIndexed { index, point ->
        val to = timeline.getOrNull(index + 1)?.atMs
        ItemEstimateRow(item.issueId, point.estimateMd, point.atMs, to)
    }
}

// valueAt/teamAt live in `DeriveTaskRows.kt` (below — the `LargeClass` idiom).

/** A task's `actual_md` is its own worklogs plus its sub-tasks' (D2); an epic's is every child's plus its own. */
private fun actualMdFor(
    item: WorkItemStore.DerivationWorkItemRow,
    children: List<WorkItemStore.DerivationWorkItemRow>,
    worklogSecondsByIssue: Map<Long, Long>,
    hoursPerDay: Double,
): Double {
    val ownSeconds = worklogSecondsByIssue[item.issueId] ?: 0L
    val childSeconds = children.sumOf { child -> worklogSecondsByIssue[child.issueId] ?: 0L }
    return (ownSeconds + childSeconds) / SECONDS_PER_HOUR / hoursPerDay
}

/** D2: a sub-task's own epic is its parent TASK's epic (one indirection); a level-0 task's epic is its own `parentIssueId`. */
internal fun epicIdOf(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): Long? {
    val parent = item.parentIssueId?.let { context.itemsById[it] } ?: return null
    val candidate = if (item.isSubtask) parent.parentIssueId else item.parentIssueId
    return candidate?.takeIf { isEpicItem(context.itemsById[it]) }
}

/**
 * `task_epic`'s effective-dated history (review round 2a fix — was one open row carrying only
 * the CURRENT epic). Built from the item's OWN `PARENT` field intervals, kept ONLY when the
 * interval's parent id resolves to an epic ([hierarchyBucket]) (a defensive filter — a
 * level-0 task's PARENT is expected to always be an epic or nothing, never another task).
 * **Sub-tasks get NO `task_epic` row at all** (D2's roll-up, `.claude/docs/domain-model.md`): a
 * sub-task's own PARENT interval names its parent TASK, not an epic, and reconstructing "which
 * epic was my parent TASK under, at each historical instant" needs a second effective-dated join
 * (parent-of-parent, over time) this commit does not build — a sub-task's CURRENT epic stays
 * available via `dim_task.epic_id` (`epicIdOf`, D2's one-indirection rule) for ordinary reads;
 * only the BRIDGE's own history is the part left undone, documented in `.claude/docs/metrics.md`.
 */
internal fun taskEpicHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskEpicRow> {
    if (item.isSubtask) return emptyList()
    return context.parentIntervalsByIssue[item.issueId].orEmpty().map { interval ->
        val parentId = interval.valueId?.toLongOrNull()
        val epicId = parentId?.takeIf { isEpicItem(context.itemsById[it]) }
        TaskEpicRow(item.issueId, epicId, interval.fromAtMs, interval.toAtMs)
    }
}

/** `task_domain`'s effective-dated history (review round 2a fix) — see [projectKeyTimeline]. */
internal fun taskDomainHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskDomainRow> {
    val issueKeyChanges = context.issueKeyChangesByIssue[item.issueId].orEmpty()
    val timeline = projectKeyTimeline(item.createdAt, issueKeyChanges, item.issueKey)
    return timeline.mapIndexed { index, point ->
        val to = timeline.getOrNull(index + 1)?.atMs
        val domainKey = point.projectKey?.let { context.domainByProject[it] ?: it }
        TaskDomainRow(item.issueId, domainKey, point.atMs, to)
    }
}

internal fun valueAt(intervals: List<NormalizedFieldInterval>, atMs: Long): String? =
    intervals.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }?.valueId

internal fun teamAt(accountId: String?, atMs: Long, memberships: Map<String, List<TeamMembershipService.MembershipInterval>>): UInt? {
    if (accountId == null) return null
    return memberships[accountId]?.firstOrNull { it.validFrom <= atMs && (it.validTo == null || atMs < it.validTo) }?.teamId
}

/** The task's assignee (and their team) at [atMs] — the assignee field interval's own value, then team_membership at that instant. */
internal fun assigneeAndTeamAt(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext, atMs: Long): Pair<String?, UInt?> {
    val accountId = valueAt(context.assigneeIntervalsByIssue[item.issueId].orEmpty(), atMs)
    return accountId to teamAt(accountId, atMs, context.membershipsByAccount)
}

/**
 * A21's "sprint team known, else assignee team" rule (D5's delivery-credit fallback, and the
 * `fact_worklog` foreign-work fallback): `true` only when the author's team is known AND it
 * differs from whichever of sprint/assignee team resolves first — never true off an unknown team
 * on either side.
 */
internal fun isForeignWork(authorTeamId: UInt?, sprintTeamId: UInt?, assigneeTeamId: UInt?): Boolean = when {
    authorTeamId == null -> false
    sprintTeamId != null -> authorTeamId != sprintTeamId
    else -> assigneeTeamId != null && authorTeamId != assigneeTeamId
}

/** A21: current-instant D5 team + the open `task_assignee` row — [MetricsDeriver.buildTaskRow]'s own doc. */
private data class CurrentAttribution(val teamId: UInt?, val assigneeAccountId: String?)

/**
 * A21/A22: D5 evaluated at [DeriveContext.now]. Two A22 corrections over the plain "sprint team,
 * else assignee team" rule: a sprint whose [DeriveContext.sprintClosedById] marks it CLOSED is never
 * an open item's current sprint at all (a not-done task left in a closed sprint is effectively
 * backlog, so this falls straight to the assignee fallback — the closed sprint's team is not simply
 * skipped in favour of a DIFFERENT sprint, since an item is a member of at most one sprint at any
 * instant); and both the sprint's team AND the assignee's team are filtered through
 * [DeriveContext.activeTeamIds] — a board mapping or a stale membership row pointing at a
 * soft-deleted team resolves to NONE here, never a team a report would then have to explain. AS-WAS
 * reads (`credit_team_id`, `sprintTeamIdAtDone`, `fact_worklog`'s author/sprint/assignee teams) are
 * untouched by either correction — only this NOW-evaluated read applies them.
 */
private fun currentAttribution(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): CurrentAttribution {
    val sprintId = valueAt(context.sprintIntervalsByIssue[item.issueId].orEmpty(), context.now)?.toLongOrNull()
    val openSprintId = sprintId?.takeIf { context.sprintClosedById[it] != true }
    val sprintTeamId = openSprintId?.let { context.sprintBoardById[it] }?.let { context.boardTeamByBoardId[it] }
        ?.takeIf { it in context.activeTeamIds }
    val (assigneeAccountId, assigneeTeamIdRaw) = assigneeAndTeamAt(item, context, context.now)
    val assigneeTeamId = assigneeTeamIdRaw?.takeIf { it in context.activeTeamIds }
    return CurrentAttribution(sprintTeamId ?: assigneeTeamId, assigneeAccountId)
}

/** A21: domain/epic attribution AS-WAS at [atMs] — [MetricsDeriver.buildTaskRow]'s own doc. */
private data class AsWasAttribution(val domainKey: String?, val epicId: Long?, val epicDomainKey: String?)

/**
 * (review round 2c bug fix, `.claude/docs/measures.md`) A covering [TaskEpicRow] whose OWN `epicId`
 * is `null` — "this task genuinely had no epic at [atMs]" — must never be conflated with "no history
 * covers [atMs] at all" (a task that has never moved, falling back to its CURRENT epic).
 * [domainRowAt]/[epicRowAt] return the covering row itself (or `null` when none covers [atMs]) so the
 * caller can tell the two cases apart. The DOMAIN is different: a task always has exactly one, so a
 * covering row whose key failed to parse falls back to the current domain (re-review round 2d).
 */
internal fun domainRowAt(history: List<TaskDomainRow>, atMs: Long): TaskDomainRow? =
    history.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }

internal fun epicRowAt(history: List<TaskEpicRow>, atMs: Long): TaskEpicRow? =
    history.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }

private fun asWasAttribution(
    item: WorkItemStore.DerivationWorkItemRow,
    context: DeriveContext,
    taskDomainRows: List<TaskDomainRow>,
    taskEpicRows: List<TaskEpicRow>,
    currentDomainKey: String,
    currentEpicId: Long?,
    atMs: Long,
): AsWasAttribution {
    val coveringDomain = domainRowAt(taskDomainRows, atMs)
    // A task has exactly one domain at any instant: a covering row whose key failed to parse
    // (a project key longer than the default limit) falls back to the current domain.
    val domainKey = coveringDomain?.domainKey ?: currentDomainKey
    val epicId = if (item.isSubtask) {
        currentEpicId
    } else {
        val coveringEpic = epicRowAt(taskEpicRows, atMs)
        if (coveringEpic != null) coveringEpic.epicId else currentEpicId
    }
    val epicDomainKey = epicId?.let { context.itemsById[it] }?.let { context.domainByProject[it.projectKey] ?: it.projectKey }
    return AsWasAttribution(domainKey, epicId, epicDomainKey)
}
