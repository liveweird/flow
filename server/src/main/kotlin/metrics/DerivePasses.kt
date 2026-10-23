package ch.nokillswit.metrics

import ch.nokillswit.norm.HierarchyBucket
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.hierarchyBucket

// The three item passes of the DERIVE run (`MetricsDeriver.runDerivation` calls them in order). Each is a top-level function
// that takes only the collaborators it needs, the `Derive*Step.kt` idiom, so the orchestrator carries none of their size.

/**
 * Pass 1 (review round 2b): item-level bridges shared by tasks AND epics alike (`item_stage`/
 * `item_blocked`/`item_estimate` — `.claude/docs/domain-model.md`'s bridges table has no task/epic
 * split for these three), computed for EVERY item in batches of [DERIVE_BATCH_SIZE] — each
 * batch's status/flagged/estimate-field-change intervals are read scoped to that batch's own
 * issue ids, computed, and inserted before the next batch's own read begins. Returns the `item_estimate` row count.
 */
internal suspend fun runPass1(
    workItemStore: WorkItemStore,
    metricsStore: MetricsStore,
    connectionId: UInt,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    context: DeriveContext,
    config: DataSourceMetricsConfig,
    derivedById: MutableMap<Long, ItemDerived>,
    blockedByIssue: MutableMap<Long, Pair<Long, Double>>,
): Int {
    var estimateCount = 0
    val estimateFieldIds = listOfNotNull(config.fields.estimateTask, config.fields.estimateEpic).distinct()
    for (batch in workItems.chunked(DERIVE_BATCH_SIZE)) {
        val ids = batch.map { it.issueId }
        context.statusIntervalsByIssue = workItemStore.statusIntervalsByIssue(connectionId, ids)
        context.flaggedIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.FLAGGED, ids)
        context.estimateChangesByIssueAndField =
            workItemStore.fieldChangesByFieldIds(connectionId, estimateFieldIds, ids).groupBy { it.issueId }

        val itemStageBatch = mutableListOf<ItemStageRow>()
        val itemBlockedBatch = mutableListOf<ItemBlockedRow>()
        val itemEstimateBatch = mutableListOf<ItemEstimateRow>()
        for (item in batch) {
            val derived = deriveItem(item, context, config)
            derivedById[item.issueId] = derived
            val blockedMs = derived.blocked.sumOf { it.toAtMs - it.fromAtMs }
            val blockedWorkingDays = derived.blocked.sumOf { context.calendar.workingDaysBetween(it.fromAtMs, it.toAtMs) }
            blockedByIssue[item.issueId] = blockedMs to blockedWorkingDays
            itemStageBatch += derived.stages.map { ItemStageRow(item.issueId, it.stage.name, it.statusId, it.fromAtMs, it.toAtMs) }
            itemBlockedBatch += derived.blocked.map { ItemBlockedRow(item.issueId, it.reason, it.fromAtMs, it.toAtMs) }
            itemEstimateBatch += buildEstimateBridge(item, derived.estimateTimeline, context.estimateFieldIdFor(item, config))
        }
        metricsStore.insertItemStage(connectionId, itemStageBatch)
        metricsStore.insertItemBlocked(connectionId, itemBlockedBatch)
        metricsStore.insertItemEstimate(connectionId, itemEstimateBatch)
        estimateCount += itemEstimateBatch.size
    }
    return estimateCount
}

/**
 * Pass 2 (review round 2b): tasks (review round 2a: "skip epics for task_* bridges" —
 * `task_epic`/`task_domain`/`task_assignee` are TASK-only bridges), in batches of
 * [DERIVE_BATCH_SIZE] — each batch's assignee/sprint/parent field intervals and `issuekey` field
 * changes are read scoped to that batch's own issue ids. `factTasksByIssueId` (a small map of
 * ALREADY-derived fact rows, not raw inputs) accumulates across every batch so pass 3 can roll an
 * epic's cost/estimate up from its children (D2, finding 6) regardless of which batch a child
 * task landed in. Returns the total task row count.
 */
internal suspend fun runPass2(
    workItemStore: WorkItemStore,
    metricsStore: MetricsStore,
    connectionId: UInt,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    context: DeriveContext,
    derivedById: Map<Long, ItemDerived>,
    blockedByIssue: Map<Long, Pair<Long, Double>>,
    factTasksByIssueId: MutableMap<Long, FactTaskDeliveryRow>,
    configRevision: Long,
): Int {
    var count = 0
    val taskItems = workItems.filter { hierarchyBucket(it.hierarchyLevel) == HierarchyBucket.TASK }
    for (batch in taskItems.chunked(DERIVE_BATCH_SIZE)) {
        val ids = batch.map { it.issueId }
        context.assigneeIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.ASSIGNEE, ids)
        context.sprintIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.SPRINT, ids)
        context.parentIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.PARENT, ids)
        context.issueKeyChangesByIssue =
            workItemStore.fieldChangesByFieldIds(connectionId, listOf(ISSUE_KEY_FIELD_ID), ids).groupBy { it.issueId }

        val tasksBatch = mutableListOf<DimTaskRow>()
        val factsBatch = mutableListOf<FactTaskDeliveryRow>()
        val taskEpicBatch = mutableListOf<TaskEpicRow>()
        val taskDomainBatch = mutableListOf<TaskDomainRow>()
        val taskAssigneeBatch = mutableListOf<TaskAssigneeRow>()
        for (item in batch) {
            val derived = derivedById.getValue(item.issueId)
            val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
            val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
            val (blockedMs, blockedWorkingDays) = blockedByIssue.getValue(item.issueId)

            val itemTaskEpicRows = taskEpicHistory(item, context)
            val itemTaskDomainRows = taskDomainHistory(item, context)
            taskEpicBatch += itemTaskEpicRows
            taskDomainBatch += itemTaskDomainRows
            taskAssigneeBatch += taskAssigneeHistory(item, context)

            val composition = buildTaskRow(
                item, derived, context, workItems, derivedById, domainKey, currentStage, blockedMs, blockedWorkingDays,
                itemTaskDomainRows, itemTaskEpicRows,
            )
            tasksBatch += composition.dim
            factsBatch += composition.fact
            factTasksByIssueId[item.issueId] = composition.fact
        }
        metricsStore.insertTasks(connectionId, tasksBatch, configRevision)
        metricsStore.insertTaskEpic(connectionId, taskEpicBatch)
        metricsStore.insertTaskDomain(connectionId, taskDomainBatch)
        metricsStore.insertTaskAssignee(connectionId, taskAssigneeBatch)
        metricsStore.insertFactTaskDelivery(connectionId, factsBatch, configRevision)
        count += factsBatch.size
    }
    return count
}

/**
 * Pass 3 (review round 2b): epics, in batches of [DERIVE_BATCH_SIZE] — reads pass 2's cached
 * `factTasksByIssueId` for the D2 roll-up; needs no per-batch interval reads of its own (an
 * epic's own facts come entirely from [derivedById]/[factTasksByIssueId]/config, already in
 * memory). [factEpicsByIssueId] accumulates every computed `fact_epic_delivery` row across every
 * batch (v0.3.0 M3 commit 9b) — the epic plan step (`runEpicPlanStep`) reads its
 * `childSumEstimateMd` back for the D4 CHILDREN fallback, so it is never re-derived a second way.
 * Returns the total epic row count.
 */
internal suspend fun runPass3(
    metricsStore: MetricsStore,
    connectionId: UInt,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    context: DeriveContext,
    derivedById: Map<Long, ItemDerived>,
    blockedByIssue: Map<Long, Pair<Long, Double>>,
    factTasksByIssueId: Map<Long, FactTaskDeliveryRow>,
    factEpicsByIssueId: MutableMap<Long, FactEpicDeliveryRow>,
    configRevision: Long,
): Int {
    var count = 0
    val epicItems = workItems.filter { hierarchyBucket(it.hierarchyLevel) == HierarchyBucket.EPIC }
    for (batch in epicItems.chunked(DERIVE_BATCH_SIZE)) {
        val epicsBatch = mutableListOf<DimEpicRow>()
        val factEpicsBatch = mutableListOf<FactEpicDeliveryRow>()
        for (item in batch) {
            val derived = derivedById.getValue(item.issueId)
            val domainKey = context.domainByProject[item.projectKey] ?: item.projectKey
            val currentStage = derived.stages.lastOrNull()?.stage ?: ItemStage.NOT_STARTED
            val (blockedMs, blockedWorkingDays) = blockedByIssue.getValue(item.issueId)
            val (dim, fact) = buildEpicRow(
                item, derived, context, workItems, derivedById, factTasksByIssueId, domainKey, currentStage,
                blockedMs, blockedWorkingDays,
            )
            epicsBatch += dim
            factEpicsBatch += fact
            factEpicsByIssueId[item.issueId] = fact
        }
        metricsStore.insertEpics(connectionId, epicsBatch, configRevision)
        metricsStore.insertFactEpicDelivery(connectionId, factEpicsBatch, configRevision)
        count += factEpicsBatch.size
    }
    return count
}
