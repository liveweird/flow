package ch.nokillswit.metrics

import ch.nokillswit.norm.WorkItemStore

/*
 * A task's composite estimate (OWN, else the SUBTASKS roll-up) as `fact_task_delivery` stores it, split out of
 * `DeriveTaskRows.kt` (the `SourceFileSizeTest` ratchet). Every snapshot and flag comes from the ONE `estimateSnapshots` kernel
 * (`DeriveTimelineKernels.kt`, domain-model A35).
 */

/**
 * The task's composite estimate (review round 2a fix): OWN wins outright; otherwise SUBTASKS
 * sums its sub-tasks' OWN timelines AS-OF the PARENT's own `startedAt`/`doneAt`
 * ([mergeEstimateTimelines]) — never their CURRENT sum reused at both snapshots,
 * which silently ignored every subtask estimate change and reported `estimatedLate`/
 * `changesAfterStart` off the (usually estimate-less, hence always-false) parent's OWN timeline
 * instead of the subtasks' actual history. The bounds are the PARENT's (A35): sub-task estimates that arrive
 * after the parent's `doneAt` make the roll-up `estimatedAfterDone`, not late.
 */
internal fun taskEstimate(
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
            derived.ownSnapshots.estimatedAfterDone,
            derived.ownSnapshots.changesAfterStart,
        )
    }
    val merged = mergeEstimateTimelines(childSubtasks.map { derivedById.getValue(it.issueId).estimateTimeline })
    val current = merged.lastOrNull()?.estimateMd
    if (current == null) return EstimateComposite(null, null, null, "NONE", false, false, 0)
    val snapshots = estimateSnapshots(merged, startedAt, doneAt)
    return EstimateComposite(
        snapshots.atStartMd, snapshots.atDoneMd, current, "SUBTASKS", snapshots.estimatedLate, snapshots.estimatedAfterDone,
        snapshots.changesAfterStart,
    )
}

internal data class EstimateComposite(
    val atStart: Double?,
    val atDone: Double?,
    val current: Double?,
    val source: String,
    val estimatedLate: Boolean,
    val estimatedAfterDone: Boolean,
    val changesAfterStart: Int,
)
