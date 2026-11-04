package ch.nokillswit.metrics

import ch.nokillswit.norm.FieldChangeRow
import ch.nokillswit.norm.projectKeyFromIssueKey

data class EstimatePoint(val atMs: Long, val estimateMd: Double?)

data class EstimateSnapshots(
    val atStartMd: Double?,
    val atDoneMd: Double?,
    val currentMd: Double?,
    val estimatedLate: Boolean,
    val estimatedAfterDone: Boolean,
    val changesAfterStart: Int,
)

/** One point of `task_domain`'s history (v0.3.0 M3 review round 2a) — the project key active from [atMs] onward. */
data class ProjectKeyPoint(val atMs: Long, val projectKey: String?)

internal const val UNESTIMATED: Double = 0.0

/** `0` or a missing value both mean "unestimated" (`.claude/docs/domain-model.md`). */
private fun Double?.asEstimateOrNull(): Double? = if (this == null || this == UNESTIMATED) null else this

/**
 * The configured estimate field's value timeline for one item, built from its raw
 * `norm.work_item_field_changes` rows for that field id (already sorted by `changedAt`
 * ascending — `WorkItemStore.fieldChangesByFieldIds`) plus its CURRENT value. The first point
 * sits at [createdAtMs]: the value BEFORE the first tracked change, or the current value when
 * there is no change history at all (an estimate set once at creation and never touched since).
 * A real Jira Cloud number custom field (Story Points) carries its changelog value ONLY in
 * `fromString`/`toString` — `fromValue`/`toValue` (the `from`/`to` id fields, meaningful for a
 * select/option field) are null — so every read falls back to the text pair (review round 1
 * fix); event times before [createdAtMs] (clock skew/bad data) are clamped to it, never
 * producing a point that predates the item's own creation. The LAST point is always anchored to
 * [currentValueMd] (the ground truth the caller already read off `norm.work_items.custom_fields`)
 * rather than trusted from the last changelog event — a re-fetched/derived field value should
 * never disagree with the timeline's own idea of "current".
 */
fun estimateTimeline(createdAtMs: Long, changes: List<FieldChangeRow>, currentValueMd: Double?): List<EstimatePoint> {
    if (changes.isEmpty()) return listOf(EstimatePoint(createdAtMs, currentValueMd.asEstimateOrNull()))
    val firstRaw = changes.first().let { it.fromValue ?: it.fromText }
    val points = mutableListOf(EstimatePoint(createdAtMs, firstRaw?.toDoubleOrNull().asEstimateOrNull()))
    changes.forEach { change ->
        val raw = change.toValue ?: change.toText
        points += EstimatePoint(maxOf(change.changedAt, createdAtMs), raw?.toDoubleOrNull().asEstimateOrNull())
    }
    points[points.lastIndex] = points.last().copy(estimateMd = currentValueMd.asEstimateOrNull())
    return points
}

/** The estimate active at [atMs] — the last timeline point at or before it. */
fun estimateAt(timeline: List<EstimatePoint>, atMs: Long): Double? = timeline.lastOrNull { it.atMs <= atMs }?.estimateMd

/**
 * At-start/at-done/current snapshots (`.claude/docs/domain-model.md` "Estimate snapshots", amendment A35). An estimate
 * change is an ADJUSTMENT only while the item is being worked on: after `startedAtMs` and at or before `doneAtMs` (no upper
 * bound while the item is open). An estimate that is missing at start AND at done but present now is a BACKFILL.
 *
 * - **estimated late** = no estimate at start but one by the end of the work: for a done item the estimate AT DONE, for an open
 *   one the CURRENT estimate (only meaningful for an item that has started);
 * - **estimated after done** = a done item with no estimate at start, none at done, but one now (the backfill; the item
 *   stays unestimated for every start → done measure). Needs no start: a done item that never started and has an estimate
 *   only now is a backfill all the same;
 * - **changes after start** = every timeline point with `startedAtMs < atMs <= doneAtMs` (the item's own creation point is never
 *   "after start"; an open item has no upper bound). A reopened item's `doneAtMs` is its FINAL done, so changes made while it
 *   was reopened count.
 *
 * Sub-task `SUBTASKS` roll-up and epic `CHILDREN` fallback are applied by the CALLER at fact
 * time (`metrics/DeriveEstimateComposite.kt`), not here — this kernel only ever sees one item's own
 * timeline (the roll-up caller passes the PARENT's `startedAtMs`/`doneAtMs`).
 */
fun estimateSnapshots(timeline: List<EstimatePoint>, startedAtMs: Long?, doneAtMs: Long?): EstimateSnapshots {
    val current = timeline.last().estimateMd
    val atStart = startedAtMs?.let { estimateAt(timeline, it) }
    val atDone = doneAtMs?.let { estimateAt(timeline, it) }
    val estimatedByEnd = if (doneAtMs != null) atDone else current
    val estimatedLate = startedAtMs != null && atStart == null && estimatedByEnd != null
    val estimatedAfterDone = doneAtMs != null && atStart == null && atDone == null && current != null
    val changesAfterStart = if (startedAtMs == null) {
        0
    } else {
        timeline.count { it.atMs > startedAtMs && (doneAtMs == null || it.atMs <= doneAtMs) }
    }
    return EstimateSnapshots(atStart, atDone, current, estimatedLate, estimatedAfterDone, changesAfterStart)
}

/**
 * A sub-task-rollup composite timeline (`SUBTASKS` estimate source, review round 2a): sums, at
 * every distinct change instant across every subtask's OWN [estimateTimeline], each subtask's
 * value AT THAT INSTANT (`estimateAt`, `0.0` for a subtask that hasn't started existing yet or
 * carries no estimate there) — never the CURRENT sum re-used at every point, which would wrongly
 * report the parent's at-start/at-done snapshots as if every subtask had always carried its
 * present-day estimate. A merged point whose sum is exactly `0.0` collapses to `null` (the same
 * "0 = unestimated" rule [estimateTimeline] applies per subtask, generalized to the aggregate) so
 * [estimateSnapshots]' `estimatedLate` check still fires correctly for a parent whose subtasks
 * were ALL unestimated at start and gained estimates only later. A point that leaves the sum unchanged (a sub-task created
 * with no estimate, say; before the first point the sum is "unestimated") is dropped, so it never counts as a change after start (A35).
 */
fun mergeEstimateTimelines(timelines: List<List<EstimatePoint>>): List<EstimatePoint> {
    if (timelines.isEmpty()) return emptyList()
    val allTimes = timelines.flatMap { timeline -> timeline.map { it.atMs } }.distinct().sorted()
    val merged = mutableListOf<EstimatePoint>()
    for (at in allTimes) {
        val sum = timelines.sumOf { timeline -> estimateAt(timeline, at) ?: 0.0 }.asEstimateOrNull()
        // A point whose sum equals the previous one (before the first point: unestimated) is no change -- a sub-task merely created,
        // or re-estimated to the same total. Keeping it would count as an estimate change after start in [estimateSnapshots].
        if (merged.lastOrNull()?.estimateMd != sum) merged += EstimatePoint(at, sum)
    }
    return merged
}

/**
 * `task_domain`'s history (review round 2a): the project key active over time, tiled from the
 * `issuekey` changelog field rather than the `project` field itself — a real Jira project-move
 * changelog item carries the OLD/NEW project's numeric id and display NAME (`fromValue`/
 * `toValue`/`fromText`/`toText`), never its key, so it cannot be resolved back to a domain-map
 * key without a project id/name -> key lookup `norm` does not keep. A project move ALWAYS moves
 * the issue's own key to the new project's prefix in the SAME changelog history
 * (`jira/JiraNormalizer.kt` tracks `"issuekey"` verbatim beside `"project"`), so replaying THAT
 * field's history instead sidesteps the lookup entirely. [issueKeyChanges] must already be
 * ordered by `changedAt` ascending (`WorkItemStore.fieldChangesByFieldIds`); events before
 * [createdAtMs] are clamped to it, the [sprintMembership]/[estimateTimeline] convention.
 */
fun projectKeyTimeline(createdAtMs: Long, issueKeyChanges: List<FieldChangeRow>, currentIssueKey: String): List<ProjectKeyPoint> {
    if (issueKeyChanges.isEmpty()) return listOf(ProjectKeyPoint(createdAtMs, projectKeyFromIssueKey(currentIssueKey)))
    val firstRaw = issueKeyChanges.first().let { it.fromValue ?: it.fromText }
    val points = mutableListOf(ProjectKeyPoint(createdAtMs, projectKeyFromIssueKey(firstRaw)))
    issueKeyChanges.forEach { change ->
        val raw = change.toValue ?: change.toText
        points += ProjectKeyPoint(maxOf(change.changedAt, createdAtMs), projectKeyFromIssueKey(raw))
    }
    points[points.lastIndex] = points.last().copy(projectKey = projectKeyFromIssueKey(currentIssueKey))
    return points
}
