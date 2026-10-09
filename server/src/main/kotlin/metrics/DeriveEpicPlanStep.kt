package ch.nokillswit.metrics

import ch.nokillswit.norm.HierarchyBucket
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.hierarchyBucket

/**
 * The epic plan step's own body (v0.3.0 M3 commit 9b, moved to `DeriveEpicPlanStep.kt` purely to
 * keep [MetricsDeriver] under detekt's `LargeClass` threshold — the sprint/worklog steps' own
 * precedent above): D4's PV baselines (`.claude/docs/domain-model.md` "Plan — PV", D4, D11;
 * `.claude/docs/metrics.md` "Epic plans and PV") — one `metrics.fact_epic_plan` row per baseline,
 * `baseline_seq` assigned 1-based in the order [epicPlanBaselines] returns them.
 * Batches over EPIC items only, re-reading each batch's own configured EPIC_START/EPIC_DUE field
 * changes the same way pass 1 reads the estimate field's own changes (the review round 2b memory
 * bound applies here too) — the epic's own estimate timeline is already available from pass 1's
 * [ItemDerived.estimateTimeline], never re-read, and the D4 CHILDREN fallback value comes from pass
 * 3's own `factEpicsByIssueId` (`childSumEstimateMd`), never re-derived. An epic whose connection
 * has no EPIC_START or no EPIC_DUE field configured at all writes no rows — the same "no dates, no
 * baseline" rule an individual epic with unset date VALUES hits inside the kernel itself.
 */
internal suspend fun runEpicPlanStep(
    workItemStore: WorkItemStore,
    metricsStore: MetricsStore,
    connectionId: UInt,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    context: DeriveContext,
    derivedById: Map<Long, ItemDerived>,
    factEpicsByIssueId: Map<Long, FactEpicDeliveryRow>,
    configRevision: Long,
): Int {
    val startFieldId = context.epicStartFieldId
    val dueFieldId = context.epicDueFieldId
    val epicItems = workItems.filter { hierarchyBucket(it.hierarchyLevel) == HierarchyBucket.EPIC }
    if (epicItems.isEmpty() || startFieldId == null || dueFieldId == null) return 0
    val dateFieldIds = listOf(startFieldId, dueFieldId).distinct()

    var count = 0
    for (batch in epicItems.chunked(DERIVE_BATCH_SIZE)) {
        val ids = batch.map { it.issueId }
        val changesByIssue = workItemStore.fieldChangesByFieldIds(connectionId, dateFieldIds, ids).groupBy { it.issueId }

        val rowsBatch = mutableListOf<FactEpicPlanRow>()
        for (item in batch) {
            val changes = changesByIssue[item.issueId].orEmpty()
            val startTimeline = dateFieldTimeline(
                item.createdAt, changes.filter { it.fieldId == startFieldId }, context.epicDateValue(item, startFieldId),
            )
            val dueTimeline = dateFieldTimeline(
                item.createdAt, changes.filter { it.fieldId == dueFieldId }, context.epicDateValue(item, dueFieldId),
            )
            val ownEstimateTimeline = derivedById.getValue(item.issueId).estimateTimeline
            val childSumMd = factEpicsByIssueId[item.issueId]?.childSumEstimateMd ?: 0.0
            val baselines = epicPlanBaselines(startTimeline, dueTimeline, ownEstimateTimeline, childSumMd)
            baselines.forEachIndexed { index, baseline -> rowsBatch += FactEpicPlanRow(item.issueId, index + 1, baseline) }
        }
        metricsStore.insertFactEpicPlan(connectionId, rowsBatch, configRevision)
        count += rowsBatch.size
    }
    return count
}
