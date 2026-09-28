package ch.nokillswit.metrics

import ch.nokillswit.norm.FieldChangeRow
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedStatusInterval

/**
 * `metrics.item_stage`'s own stage vocabulary (v0.3.0 M3 commit 7) — `NOT_STARTED`/`IN_PROGRESS`/
 * `DONE` mirror `metrics.status_stage_map`'s CHECK (`MetricsStage`, `metrics/DataSourceMetricsConfig.kt`),
 * plus `UNMAPPED` for a status carrying no configured stage — flagged, never guessed
 * (`.claude/docs/domain-model.md`: "an unmapped status is flagged, never guessed").
 */
enum class ItemStage { NOT_STARTED, IN_PROGRESS, DONE, UNMAPPED }

/** One `metrics.item_stage` row (pure, pre-persistence) — the status tiling's stage attached to each interval. */
data class StageInterval(val stage: ItemStage, val statusId: String, val fromAtMs: Long, val toAtMs: Long?)

data class StartedDoneResult(val startedAtMs: Long?, val doneAtMs: Long?, val reopenCount: Int)

/** One merged, clipped `metrics.item_blocked` interval — always closed (`toAtMs` is the window end, never open-ended). */
data class BlockedInterval(val fromAtMs: Long, val toAtMs: Long)

data class EstimatePoint(val atMs: Long, val estimateMd: Double?)

data class EstimateSnapshots(
    val atStartMd: Double?,
    val atDoneMd: Double?,
    val currentMd: Double?,
    val estimatedLate: Boolean,
    val changesAfterStart: Int,
)

/** One `metrics.task_sprint` row (pure) — a task's membership in ONE sprint, diffed from the Sprint field's set-valued changes. */
data class SprintMembershipInterval(val sprintId: Long, val fromAtMs: Long, val toAtMs: Long?)

/**
 * The per-item derivation math (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "Analytical
 * model"/"The three dimensions") — pure Kotlin, no DB, the `norm/Tiling.kt` pattern:
 * property-testable, called once per issue by `metrics/MetricsDeriver.kt` over ALREADY-persisted
 * `norm.*` rows and the connection's effective metrics configuration.
 */
object DeriveKernels {

    /**
     * Tiles `norm` status intervals into `metrics.item_stage` rows via [stageMap] (`statusId ->
     * ItemStage`, `metrics.status_stage_map` read into memory once per DERIVE run — NEVER re-queried
     * per issue) — a status carrying no entry maps to [ItemStage.UNMAPPED].
     */
    fun stageIntervals(statusIntervals: List<NormalizedStatusInterval>, stageMap: Map<String, ItemStage>): List<StageInterval> =
        statusIntervals.map { interval ->
            StageInterval(stageMap[interval.statusId] ?: ItemStage.UNMAPPED, interval.statusId, interval.fromAtMs, interval.toAtMs)
        }

    /**
     * `startedAtMs` = the item's FIRST-ever entry into [ItemStage.IN_PROGRESS] (even across a later
     * reopen — the FIRST one always wins). `doneAtMs` is set ONLY while the item's CURRENT (last,
     * open) stage interval is itself [ItemStage.DONE] — a reopened item currently back in progress
     * has no `doneAtMs` until it reaches DONE again — and is the start of the TRAILING unbroken DONE
     * run (not merely the last transition into DONE). `reopenCount` = every DONE -> non-DONE
     * transition.
     */
    fun startedDoneAt(stageIntervals: List<StageInterval>): StartedDoneResult {
        if (stageIntervals.isEmpty()) return StartedDoneResult(null, null, 0)
        val startedAt = stageIntervals.firstOrNull { it.stage == ItemStage.IN_PROGRESS }?.fromAtMs
        val reopenCount = stageIntervals.zipWithNext().count { (a, b) -> a.stage == ItemStage.DONE && b.stage != ItemStage.DONE }
        val last = stageIntervals.last()
        val doneAt = if (last.stage == ItemStage.DONE) {
            var i = stageIntervals.lastIndex
            while (i > 0 && stageIntervals[i - 1].stage == ItemStage.DONE) i--
            stageIntervals[i].fromAtMs
        } else {
            null
        }
        return StartedDoneResult(startedAt, doneAt, reopenCount)
    }

    /**
     * The union of FLAGGED=true intervals and configured-blocked-status intervals
     * (`metrics.blocked_statuses`), merged (overlap/adjacency collapsed) and clipped to the item's
     * own cycle window `[windowFromMs, windowToMs)` — empty when [windowFromMs] is null (the item
     * never started, so it has no cycle window to intersect against).
     */
    fun blockedIntervals(
        flaggedIntervals: List<NormalizedFieldInterval>,
        statusIntervals: List<NormalizedStatusInterval>,
        blockedStatusIds: Set<String>,
        windowFromMs: Long?,
        windowToMs: Long,
    ): List<BlockedInterval> {
        if (windowFromMs == null) return emptyList()
        val raw = mutableListOf<Pair<Long, Long>>()
        flaggedIntervals.filter { it.valueId == "true" }.forEach { raw += it.fromAtMs to (it.toAtMs ?: windowToMs) }
        statusIntervals.filter { it.statusId in blockedStatusIds }.forEach { raw += it.fromAtMs to (it.toAtMs ?: windowToMs) }
        val clipped = raw.mapNotNull { (from, to) ->
            val clampedFrom = maxOf(from, windowFromMs)
            val clampedTo = minOf(to, windowToMs)
            if (clampedTo > clampedFrom) clampedFrom to clampedTo else null
        }.sortedBy { it.first }
        val merged = mutableListOf<Pair<Long, Long>>()
        for ((from, to) in clipped) {
            val last = merged.lastOrNull()
            if (last != null && from <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, to)
            } else {
                merged += from to to
            }
        }
        return merged.map { BlockedInterval(it.first, it.second) }
    }

    private const val UNESTIMATED: Double = 0.0

    /** `0` or a missing value both mean "unestimated" (`.claude/docs/domain-model.md`). */
    private fun Double?.asEstimateOrNull(): Double? = if (this == null || this == UNESTIMATED) null else this

    /**
     * The configured estimate field's value timeline for one item, built from its raw
     * `norm.work_item_field_changes` rows for that field id (already sorted by `changedAt`
     * ascending — `WorkItemStore.fieldChangesByFieldIds`) plus its CURRENT value. The first point
     * sits at [createdAtMs]: the value BEFORE the first tracked change, or the current value when
     * there is no change history at all (an estimate set once at creation and never touched since).
     */
    fun estimateTimeline(createdAtMs: Long, changes: List<FieldChangeRow>, currentValueMd: Double?): List<EstimatePoint> {
        if (changes.isEmpty()) return listOf(EstimatePoint(createdAtMs, currentValueMd.asEstimateOrNull()))
        val points = mutableListOf(EstimatePoint(createdAtMs, changes.first().fromValue?.toDoubleOrNull().asEstimateOrNull()))
        changes.forEach { change -> points += EstimatePoint(change.changedAt, change.toValue?.toDoubleOrNull().asEstimateOrNull()) }
        return points
    }

    /** The estimate active at [atMs] — the last timeline point at or before it. */
    fun estimateAt(timeline: List<EstimatePoint>, atMs: Long): Double? = timeline.lastOrNull { it.atMs <= atMs }?.estimateMd

    /**
     * At-start/at-done/current snapshots (`.claude/docs/domain-model.md` "Estimate snapshots"):
     * **estimated late** = the item had NO estimate when it started but has gained one since (only
     * meaningful for an item that has actually started); **changes after start** = every timeline
     * point strictly AFTER `startedAtMs` (the item's own creation point is never "after start").
     * Sub-task `SUBTASKS` roll-up and epic `CHILDREN` fallback are applied by the CALLER at fact
     * time (`metrics/MetricsDeriver.kt`), not here — this kernel only ever sees one item's own
     * timeline.
     */
    fun estimateSnapshots(timeline: List<EstimatePoint>, startedAtMs: Long?, doneAtMs: Long?): EstimateSnapshots {
        val current = timeline.last().estimateMd
        val atStart = startedAtMs?.let { estimateAt(timeline, it) }
        val atDone = doneAtMs?.let { estimateAt(timeline, it) }
        val estimatedLate = startedAtMs != null && atStart == null && current != null
        val changesAfterStart = if (startedAtMs == null) 0 else timeline.count { it.atMs > startedAtMs }
        return EstimateSnapshots(atStart, atDone, current, estimatedLate, changesAfterStart)
    }

    /**
     * The value active at [atMs] from an ordered (ascending by change instant) list of `(changedAt,
     * newValue)` points — the ONE as-of helper (work category at `done_at`, assignee at commitment,
     * a sprint's team at an instant).
     */
    fun <T> valueAsOf(points: List<Pair<Long, T>>, initial: T, atMs: Long): T {
        var current = initial
        for ((changedAt, value) in points) {
            if (changedAt > atMs) break
            current = value
        }
        return current
    }

    /**
     * Diffs the Sprint field's raw changelog text into per-sprint set-valued membership intervals —
     * Jira renders both `fromValue`/`toValue` on a Sprint changelog item as a comma-joined id list,
     * so a task moved from sprint A directly into sprint B (never leaving the field empty in
     * between) still produces ONE closed interval for A and one opened interval for B, rather than
     * losing A's membership entirely the way the `norm` field interval's LAST-id-only tiling would.
     * [changes] must already be ordered by `changedAt` ascending
     * (`WorkItemStore.fieldChangesByFieldIds`).
     */
    fun sprintMembership(createdAtMs: Long, changes: List<FieldChangeRow>, currentSprintIds: List<Long>): List<SprintMembershipInterval> {
        fun parseIds(text: String?): Set<Long> = text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

        val open = linkedMapOf<Long, Long>() // sprintId -> the instant it was opened
        val closed = mutableListOf<SprintMembershipInterval>()
        var previous = if (changes.isEmpty()) currentSprintIds.toSet() else parseIds(changes.first().fromValue)
        previous.forEach { open[it] = createdAtMs }
        changes.forEach { change ->
            val next = parseIds(change.toValue)
            (next - previous).forEach { sprintId -> open[sprintId] = change.changedAt }
            (previous - next).forEach { sprintId ->
                val openedAt = open.remove(sprintId)
                if (openedAt != null) closed += SprintMembershipInterval(sprintId, openedAt, change.changedAt)
            }
            previous = next
        }
        val stillOpen = open.map { (sprintId, openedAt) -> SprintMembershipInterval(sprintId, openedAt, null) }
        return (closed + stillOpen).sortedBy { it.fromAtMs }
    }
}
