package ch.nokillswit.metrics

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
        EstimateSnapshots(null, null, null, false, false, 0)
    } else {
        estimateSnapshots(timeline, startedAt, doneAt)
    }

internal fun ownWorkCategory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): String? {
    val fieldId = context.workCategoryFieldId ?: return null
    val valueId = fieldValueOptions(item.customFields[fieldId]).firstOrNull()?.first ?: return null
    return context.workCategoryMap[valueId]
}

// epicIdOf lives in `DeriveAttribution.kt` (the `LargeClass` idiom, it takes no instance state).

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
        stages,
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
 *
 * A49: the FACT's `domain_key` and `owner_team_id` are the epic's domain AS-WAS at `done_at ?: now` (the task rule, A21;
 * [epicDomainKeyAt] over the epic's own `issuekey` history, half-open `[from, to)` so an epic done exactly at its move is in the
 * NEW domain) and that domain's owner; an open epic is read now, so only a finished epic moves. [domainKey] — the epic's domain NOW —
 * is the fallback and stays the dimension's `dim_epic.domain_key` (like `dim_task.domain_key`).
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
    val asOfDomainKey =
        epicDomainKeyAt(context.epicDomainHistoryById[item.issueId].orEmpty(), derived.done ?: context.now, domainKey) ?: domainKey
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
        estimatedLate = derived.ownSnapshots.estimatedLate,
        estimatedAfterDone = derived.ownSnapshots.estimatedAfterDone,
        childSumEstimateMd = childSum,
        budgetSource = if (ownCurrent != null) "OWN" else "CHILDREN",
        actualMd = actualMd,
        cycleMs = cycleMs(derived),
        cycleWorkingDays = cycleWorkingDays(derived, context.calendar),
        blockedMs = blockedMs,
        blockedWorkingDays = blockedWorkingDays,
        domainKey = asOfDomainKey,
        workCategory = ownCategory,
        driftFlags = driftFlags.map { it.name },
        ownerTeamId = context.ownerTeamByDomain[asOfDomainKey],
    )
    return dim to fact
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
    // CURRENT (one-indirection) epic, the same fallback [worklogFactSql] applies.
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
        estimatedAfterDone = estimate.estimatedAfterDone,
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

// taskEpicHistory/taskDomainHistory live in `DeriveAttribution.kt` (the `LargeClass` idiom).

/** `task_assignee`'s effective-dated history (review round 2a fix) — a direct mirror of `norm`'s own ASSIGNEE field intervals. */
internal fun taskAssigneeHistory(item: WorkItemStore.DerivationWorkItemRow, context: DeriveContext): List<TaskAssigneeRow> =
    context.assigneeIntervalsByIssue[item.issueId].orEmpty().map {
        TaskAssigneeRow(item.issueId, it.valueId, it.fromAtMs, it.toAtMs)
    }

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

// valueAt/teamAt live in `DeriveAttribution.kt` (the `LargeClass` idiom).

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
