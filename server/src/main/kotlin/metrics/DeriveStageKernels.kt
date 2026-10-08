package ch.nokillswit.metrics

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedStatusInterval

/**
 * `metrics.item_stage`'s own stage vocabulary (v0.3.0 M3 commit 7) — `NOT_STARTED`/`IN_PROGRESS`/
 * `WAITING`/`DONE` mirror `metrics.status_stage_map`'s CHECK (`MetricsStage`, `metrics/DataSourceMetricsConfig.kt`),
 * plus `UNMAPPED` for a status carrying no configured stage — flagged, never guessed
 * (`.claude/docs/domain-model.md`: "an unmapped status is flagged, never guessed"). `WAITING` (A30) is work
 * that has started with nothing actively worked on: it starts the item and counts as WIP, but is wait time.
 */
enum class ItemStage { NOT_STARTED, IN_PROGRESS, WAITING, DONE, UNMAPPED }

/** One `metrics.item_stage` row (pure, pre-persistence) — the status tiling's stage attached to each interval. */
data class StageInterval(val stage: ItemStage, val statusId: String, val fromAtMs: Long, val toAtMs: Long?)

data class StartedDoneResult(val startedAtMs: Long?, val doneAtMs: Long?, val reopenCount: Int)

/**
 * One merged, clipped `metrics.item_blocked` interval — always closed (`toAtMs` is the window end,
 * never open-ended). [reason] is `"FLAGGED"` when every raw interval merged into this one came from
 * the Flagged field alone, `"STATUS"` when at least one came from a configured blocked status
 * (review round 2b fix — the merge used to always report `"FLAGGED"`, silently losing the
 * blocked-status source whenever the two overlapped or a status-only interval merged in).
 */
data class BlockedInterval(val fromAtMs: Long, val toAtMs: Long, val reason: String)

/**
 * Flow efficiency (A18, `.claude/docs/domain-model.md` "Delivery — EV (earned value)",
 * `.claude/docs/measures.md` report 7/8): [activeMs] is time in `IN_PROGRESS`-stage intervals inside
 * `[startedAtMs, doneAtMs)` MINUS blocked time in that same window; [waitMs] is the rest of the
 * cycle (`cycle - active`) — so `WAITING` time (A30) is wait, never active. Both are `0` for an item that is not done — see [activeWaitMs].
 */
data class ActiveWait(val activeMs: Long, val waitMs: Long)

/**
 * D11's three epic/children drift codes (`.claude/docs/domain-model.md` "D11", `fact_epic_delivery
 * .driftFlags`) — flagged, never corrected (the `norm` anomaly convention).
 */
enum class EpicDriftFlag { EPIC_NOT_STARTED_WITH_ACTIVE_CHILDREN, EPIC_OPEN_AFTER_CHILDREN_DONE, EPIC_DONE_WITH_OPEN_CHILDREN }

/** One child task's delivery state, as [epicDriftFlags] needs it — never the full [ItemDerived]. */
data class ChildDeliveryStatus(val startedAtMs: Long?, val doneAtMs: Long?)

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
 * `startedAtMs` = the item's FIRST-ever entry into [ItemStage.IN_PROGRESS] or [ItemStage.WAITING] (A30: work that
 * is waiting has started; even across a later reopen — the FIRST one always wins). `doneAtMs` is set ONLY while the item's CURRENT (last,
 * open) stage interval is itself [ItemStage.DONE] — a reopened item currently back in progress
 * has no `doneAtMs` until it reaches DONE again — and is the start of the TRAILING unbroken DONE
 * run (not merely the last transition into DONE). `reopenCount` = every DONE -> non-DONE
 * transition.
 */
fun startedDoneAt(stageIntervals: List<StageInterval>): StartedDoneResult {
    if (stageIntervals.isEmpty()) return StartedDoneResult(null, null, 0)
    val startedAt = stageIntervals.firstOrNull { it.stage == ItemStage.IN_PROGRESS || it.stage == ItemStage.WAITING }?.fromAtMs
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
private const val REASON_FLAGGED = "FLAGGED"
private const val REASON_STATUS = "STATUS"

fun blockedIntervals(
    flaggedIntervals: List<NormalizedFieldInterval>,
    statusIntervals: List<NormalizedStatusInterval>,
    blockedStatusIds: Set<String>,
    windowFromMs: Long?,
    windowToMs: Long,
): List<BlockedInterval> {
    if (windowFromMs == null) return emptyList()
    // Each raw interval carries its OWN source (review round 2b fix) — the merge below folds
    // overlapping/adjacent pieces together but must never lose track of WHICH source(s)
    // contributed, since a merged interval's reason must reflect all of them, not just the first.
    val raw = mutableListOf<Triple<Long, Long, Boolean>>() // (from, to, isStatusSourced)
    flaggedIntervals.filter { it.valueId == "true" }.forEach { raw += Triple(it.fromAtMs, it.toAtMs ?: windowToMs, false) }
    statusIntervals.filter { it.statusId in blockedStatusIds }.forEach { raw += Triple(it.fromAtMs, it.toAtMs ?: windowToMs, true) }
    val clipped = raw.mapNotNull { (from, to, isStatusSourced) ->
        val clampedFrom = maxOf(from, windowFromMs)
        val clampedTo = minOf(to, windowToMs)
        if (clampedTo > clampedFrom) Triple(clampedFrom, clampedTo, isStatusSourced) else null
    }.sortedBy { it.first }
    val merged = mutableListOf<Triple<Long, Long, Boolean>>()
    for ((from, to, isStatusSourced) in clipped) {
        val last = merged.lastOrNull()
        if (last != null && from <= last.second) {
            merged[merged.lastIndex] = Triple(last.first, maxOf(last.second, to), last.third || isStatusSourced)
        } else {
            merged += Triple(from, to, isStatusSourced)
        }
    }
    return merged.map { (from, to, isStatusSourced) ->
        BlockedInterval(from, to, if (isStatusSourced) REASON_STATUS else REASON_FLAGGED)
    }
}

/** The overlap, in milliseconds, of `[from, to ?: windowTo)` with `[windowFrom, windowTo)` — never negative. */
private fun overlapMs(from: Long, to: Long?, windowFrom: Long, windowTo: Long): Long {
    val clampedFrom = maxOf(from, windowFrom)
    val clampedTo = minOf(to ?: windowTo, windowTo)
    return maxOf(0L, clampedTo - clampedFrom)
}

/**
 * The overlap, in milliseconds, of ONE interval `[from, to ?: windowTo)` with EVERY IN_PROGRESS
 * stage interval, each pairwise overlap clamped to `[windowFrom, windowTo)` and summed (review
 * round 2c bug fix, `.claude/docs/measures.md`) — IN_PROGRESS stage intervals never overlap each
 * other by construction (`stageIntervals` tiles one contiguous timeline), so summing pairwise
 * overlaps can never double count the SAME instant twice, even when this one blocked interval
 * spans more than one IN_PROGRESS stretch (a reopen with the blocked span continuing across the
 * transition back into IN_PROGRESS).
 */
private fun overlapWithInProgressMs(
    from: Long,
    to: Long?,
    inProgressStages: List<StageInterval>,
    windowFrom: Long,
    windowTo: Long,
): Long = inProgressStages.sumOf { stage ->
    val overlapFrom = maxOf(from, stage.fromAtMs, windowFrom)
    val overlapTo = minOf(to ?: windowTo, stage.toAtMs ?: windowTo, windowTo)
    maxOf(0L, overlapTo - overlapFrom)
}

/**
 * A18's flow efficiency, pure (`.claude/docs/measures.md` report 7/8: "active = IN_PROGRESS-stage
 * time in `[started_at, done_at)` minus the blocked time that occurred WHILE IN_PROGRESS; wait =
 * cycle − active"). `null` [startedAtMs]/[doneAtMs] (the item never started, or is not yet done)
 * answers `(0, 0)` — flow efficiency is a DELIVERED measure. [stages] and [blocked] are the item's
 * OWN full histories ([stageIntervals]/[blockedIntervals]) — [blocked]
 * is already clipped to `[startedAtMs, doneAtMs)` by its own caller when the item is done, so no
 * re-clipping happens here beyond [overlapMs]'s own window intersection (harmless either way,
 * since a blocked interval outside the window would contribute `0`). A reopened item's
 * IN_PROGRESS time is summed across its WHOLE history inside the window, including any stretch
 * before the reopen — the window is `[startedAtMs, doneAtMs)`, not just the trailing DONE run's
 * own lead-up.
 *
 * **The blocked subtraction is scoped to IN_PROGRESS time only (review round 2c bug fix).** Time
 * blocked while the item is NOT IN_PROGRESS (e.g. blocked while UNMAPPED, or a blocked span that
 * outlives an IN_PROGRESS stretch) is already counted as WAIT by construction — it was never
 * summed into `inProgressMs` in the first place, since that sum only ever counts IN_PROGRESS-stage
 * overlap. Subtracting the item's WHOLE blocked time (as the pre-fix version did) therefore
 * double-subtracted that already-excluded time, silently inflating `wait` at active's expense.
 * [overlapWithInProgressMs] instead intersects each blocked interval against the IN_PROGRESS stage
 * intervals specifically (summing every pairwise overlap — safe from double counting, since
 * IN_PROGRESS stage intervals never overlap each other by construction) before subtracting. Never
 * negative: `active` is still clamped into `[0, cycle]` — a purely DEFENSIVE backstop now (rounding
 * at interval boundaries, not a real "more blocked-while-IN_PROGRESS time than IN_PROGRESS time"
 * case, since the intersection above can never exceed `inProgressMs` by construction) — and `wait`
 * is `cycle - active`, itself then never negative either.
 *
 * **WAITING is wait (A30).** Only [ItemStage.IN_PROGRESS] intervals count as active, so time in a `WAITING` stage
 * is wait by construction; a status that is both WAITING and a configured blocked status (e.g. "On Hold") is
 * likewise never subtracted, since the blocked subtraction only ever intersects IN_PROGRESS intervals.
 */
fun activeWaitMs(
    stages: List<StageInterval>,
    blocked: List<BlockedInterval>,
    startedAtMs: Long?,
    doneAtMs: Long?,
): ActiveWait {
    if (startedAtMs == null || doneAtMs == null) return ActiveWait(0, 0)
    val cycleMs = doneAtMs - startedAtMs
    val inProgressStages = stages.filter { it.stage == ItemStage.IN_PROGRESS }
    val inProgressMs = inProgressStages.sumOf { overlapMs(it.fromAtMs, it.toAtMs, startedAtMs, doneAtMs) }
    val blockedWhileInProgressMs = blocked.sumOf {
        overlapWithInProgressMs(it.fromAtMs, it.toAtMs, inProgressStages, startedAtMs, doneAtMs)
    }
    val activeMs = (inProgressMs - blockedWhileInProgressMs).coerceIn(0L, cycleMs)
    val waitMs = (cycleMs - activeMs).coerceAtLeast(0L)
    return ActiveWait(activeMs, waitMs)
}

/**
 * D11: an epic follows its OWN status (`epicStage`); these three flags only ever compare it
 * with its children, never re-date it. [epicDriftDays] is `metrics.settings
 * .epic_drift_days` (plain elapsed calendar days — the domain model's own "days an epic may stay
 * open" wording, not working days) and [nowMs] is the DERIVE run's own pinned clock — both
 * supplied by the caller, since this kernel has no clock or calendar of its own. An epic with no
 * children at all drifts from nothing, so it never flags.
 */
fun epicDriftFlags(
    epicStage: ItemStage,
    children: List<ChildDeliveryStatus>,
    epicDriftDays: Int,
    nowMs: Long,
): List<EpicDriftFlag> {
    if (children.isEmpty()) return emptyList()
    val epicStarted = epicStage != ItemStage.NOT_STARTED
    val epicDone = epicStage == ItemStage.DONE
    val anyChildActive = children.any { it.startedAtMs != null || it.doneAtMs != null }
    val allChildrenDone = children.all { it.doneAtMs != null }
    val anyChildOpen = children.any { it.doneAtMs == null }
    val flags = mutableListOf<EpicDriftFlag>()
    if (!epicStarted && anyChildActive) flags += EpicDriftFlag.EPIC_NOT_STARTED_WITH_ACTIVE_CHILDREN
    if (!epicDone && allChildrenDone) {
        val lastChildDoneAtMs = children.mapNotNull { it.doneAtMs }.max()
        if ((nowMs - lastChildDoneAtMs) / MILLIS_PER_DAY >= epicDriftDays) flags += EpicDriftFlag.EPIC_OPEN_AFTER_CHILDREN_DONE
    }
    if (epicDone && anyChildOpen) flags += EpicDriftFlag.EPIC_DONE_WITH_OPEN_CHILDREN
    return flags
}
