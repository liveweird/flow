package ch.nokillswit.metrics

import ch.nokillswit.norm.FieldChangeRow
import ch.nokillswit.norm.NormalizedFieldInterval
import java.math.BigDecimal
import java.math.RoundingMode

/** One `metrics.task_sprint` row (pure) — a task's membership in ONE sprint, diffed from the Sprint field's set-valued changes. */
data class SprintMembershipInterval(val sprintId: Long, val fromAtMs: Long, val toAtMs: Long?)

/**
 * One `metrics.fact_sprint_scope` row (pure, pre-persistence, v0.3.0 M3 commit 8) — a single task's
 * scope in ONE sprint, produced by [sprintScope]. `sprintId`/`connectionId` are added
 * by the caller (`metrics/MetricsDeriver.kt`) when persisting; this shape carries only what the
 * kernel itself computes.
 */
data class SprintScopeItem(
    val issueId: Long,
    val addedAtMs: Long?,
    val removedAtMs: Long?,
    val committed: Boolean,
    val inScopeAtClose: Boolean,
    val estimateAtCommitmentMd: Double?,
    val estimateAtCloseMd: Double?,
    val estimateAtDoneMd: Double?,
    val assigneeAtCommitment: String?,
    val doneInSprint: Boolean,
    val carriedOver: Boolean,
    val dropped: Boolean,
)

/** One `metrics.fact_sprint` row's own figures (minus `capacity`/`load`, which the caller adds) —
 * Σ of a sprint's own [SprintScopeItem] rows. */
data class SprintTotals(
    val committedMd: Double,
    val committedItems: Int,
    val addedMd: Double,
    val addedItems: Int,
    val removedMd: Double,
    val removedItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val carriedOverMd: Double,
    val carriedOverItems: Int,
    val droppedMd: Double,
    val droppedItems: Int,
)

/** Decimal places of every man-day figure: the stored `NUMERIC(_, 2)` scale and the scale every report emits. */
internal const val MD_SCALE = 2

/**
 * Σ of [values] in man-days, each value rounded half-up to [MD_SCALE] BEFORE it is summed (`null` counts as `0`), the sum
 * exact (BigDecimal, no float drift). The ONE rule for a sprint's MD figure: [sprintTotals] (`fact_sprint`)
 * applies it to the per-item estimates, and the readers that re-sum stored figures (Velocity's and Sprint consistency's
 * per-user and per-team groups) apply it to values that are already two-decimal, so Σ groups equals the team figure to the
 * cent. A plain Double sum of those figures would drift in the last bits (12.34 + 5.67 = 18.009999999999998), so the
 * readers use this too. The per-item rounding matches what the database stores (`NUMERIC(_, 2)`, half away from zero =
 * half-up for the non-negative estimates here), pinned against Postgres by `MetricsDerivationTest`.
 */
internal fun sumMd(values: Iterable<Double?>): Double = values
    .fold(BigDecimal.ZERO) { acc, v -> if (v == null) acc else acc + v.toBigDecimal().setScale(MD_SCALE, RoundingMode.HALF_UP) }
    .toDouble()

/**
 * Diffs the Sprint field's raw changelog text into per-sprint set-valued membership intervals —
 * Jira renders both `fromValue`/`toValue` on a Sprint changelog item as a comma-joined id list,
 * so a task moved from sprint A directly into sprint B (never leaving the field empty in
 * between) still produces ONE closed interval for A and one opened interval for B, rather than
 * losing A's membership entirely the way the `norm` field interval's LAST-id-only tiling would.
 * [changes] must already be ordered by `changedAt` ascending
 * (`WorkItemStore.fieldChangesByFieldIds`). Every event's own instant is clamped to
 * [createdAtMs] (review round 1 fix) — a changelog event landing BEFORE the item's own creation
 * (clock skew/bad data) must never open or close a membership interval earlier than the item
 * itself existed, which would otherwise produce a backwards `[from, to)` range the `int8range`
 * GiST index (`metrics.task_sprint`, V16) cannot store.
 */
fun sprintMembership(createdAtMs: Long, changes: List<FieldChangeRow>, currentSprintIds: List<Long>): List<SprintMembershipInterval> {
    fun parseIds(text: String?): Set<Long> = text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()

    val open = linkedMapOf<Long, Long>() // sprintId -> the instant it was opened
    val closed = mutableListOf<SprintMembershipInterval>()
    var previous = if (changes.isEmpty()) currentSprintIds.toSet() else parseIds(changes.first().fromValue)
    previous.forEach { open[it] = createdAtMs }
    changes.forEach { change ->
        val at = maxOf(change.changedAt, createdAtMs)
        val next = parseIds(change.toValue)
        (next - previous).forEach { sprintId -> open[sprintId] = at }
        (previous - next).forEach { sprintId ->
            val openedAt = open.remove(sprintId)
            if (openedAt != null) closed += SprintMembershipInterval(sprintId, openedAt, at)
        }
        previous = next
    }
    val stillOpen = open.map { (sprintId, openedAt) -> SprintMembershipInterval(sprintId, openedAt, null) }
    return (closed + stillOpen).sortedBy { it.fromAtMs }
}

/**
 * One task's `metrics.fact_sprint_scope` row for ONE sprint (v0.3.0 M3 commit 8,
 * `.claude/docs/domain-model.md` "Glossary"/"Plan — PV") — `null` when the task never touched
 * this sprint at all (no row is written). Mirrors `sample-data/jira/generate.mjs`'s own
 * `computeSprintScope` reference implementation EXACTLY (the golden FLO sprint fixture was
 * computed by that script, so this kernel must reproduce its bucket assignment bit for bit):
 *
 * - **`committed`** = the task's FIRST-EVER entry into this sprint happened at or before
 *   `commitAt` (`sprintStartAtMs + graceMs`) — a fixed point in time, independent of whether the
 *   task later left and came back.
 * - **`in_scope_at_close`** = the task is a member of this sprint (some membership interval
 *   covers it) at [sprintCloseAtMs] (the sprint's own `completeAt`, or `now` for a still-open
 *   sprint — `fact_sprint` is always the live recomputation, D13's frozen figure is a separate
 *   snapshot).
 * - **`committed && !inScopeAtClose`** → **removed** scope ONLY: `estimate_at_commitment_md` is
 *   read at `commitAt` (never re-evaluated at the exit instant — the schema carries no separate
 *   "at removal" column), `removed_at` is the LAST exit at-or-before [sprintCloseAtMs]. This task
 *   contributes to NEITHER `committed` NOR `final` NOR delivered/carried/dropped (the generator's
 *   own `continue` after the removed branch) — removal is a terminal bucket of its own.
 * - **Otherwise, when NOT in scope at close at all** (entered after `commitAt` and already exited
 *   before [sprintCloseAtMs]) → no row (`null`) — never final, never committed, never removed.
 * - **Otherwise (`in_scope_at_close`)** → committed OR added (mutually exclusive: `addedAtMs` is
 *   the first entry, set only when NOT committed), contributing to `final` — `estimate_at_close_md`
 *   is always read at [sprintCloseAtMs] regardless of bucket. `done_in_sprint` = `doneAtMs` falls
 *   inside `[sprintStartAtMs, sprintCloseAtMs]` AND the task was a sprint member at that instant;
 *   a delivered task's `estimate_at_done_md` is read at the SAME [sprintCloseAtMs] instant as
 *   `estimate_at_close_md` (the generator's own `spAtCompletion`, reused verbatim for both
 *   "final" and "delivered" — a task done mid-sprint is not re-estimated on the way out), never
 *   re-evaluated at the task's own `doneAt` — so the two columns carry the same number whenever
 *   both are set, kept separate only because the schema names them for two different readers
 *   (D13's snapshot figures vs. a future per-item report). **Carried-over/dropped (A17) apply to
 *   EVERY in-scope-at-close task not done in the sprint, committed OR added** — every row
 *   reaching this branch is already in scope at close, so the predicate is simply "not done":
 *   [inLaterSprintOfTeam] (precomputed by the caller — `.claude/docs/domain-model.md`'s D10 "one
 *   board per team" makes "later sprint of this task's own board" equivalent to "later sprint of
 *   the same team") decides carried-over vs dropped. This makes `final = delivered + carried-over +
 *   dropped` always, and `final = committed + added` in items ([sprintTotals]).
 */
fun sprintScope(
    issueId: Long,
    sprintStartAtMs: Long,
    sprintCloseAtMs: Long,
    graceMs: Long,
    membershipIntervals: List<SprintMembershipInterval>,
    estimateTimeline: List<EstimatePoint>,
    assigneeIntervals: List<NormalizedFieldInterval>,
    doneAtMs: Long?,
    inLaterSprintOfTeam: Boolean,
): SprintScopeItem? {
    if (membershipIntervals.isEmpty()) return null
    val commitAt = sprintStartAtMs + graceMs
    val enteredAt = membershipIntervals.minOf { it.fromAtMs }
    val committed = enteredAt <= commitAt
    fun inScopeAt(atMs: Long) = membershipIntervals.any { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }
    fun assigneeAt(atMs: Long) =
        assigneeIntervals.firstOrNull { it.fromAtMs <= atMs && (it.toAtMs == null || atMs < it.toAtMs) }?.valueId
    val inScopeAtClose = inScopeAt(sprintCloseAtMs)

    if (committed && !inScopeAtClose) {
        val removedAt = membershipIntervals.mapNotNull { it.toAtMs }.filter { it <= sprintCloseAtMs }.maxOrNull()
        return SprintScopeItem(
            issueId = issueId,
            addedAtMs = null,
            removedAtMs = removedAt,
            committed = true,
            inScopeAtClose = false,
            estimateAtCommitmentMd = estimateAt(estimateTimeline, commitAt),
            estimateAtCloseMd = null,
            estimateAtDoneMd = null,
            assigneeAtCommitment = assigneeAt(commitAt),
            doneInSprint = false,
            carriedOver = false,
            dropped = false,
        )
    }
    if (!inScopeAtClose) return null

    val addedAtMs = if (!committed) enteredAt else null
    val entryInstant = addedAtMs ?: commitAt
    val estimateAtClose = estimateAt(estimateTimeline, sprintCloseAtMs)
    val doneInSprint = doneAtMs != null && doneAtMs in sprintStartAtMs..sprintCloseAtMs && inScopeAt(doneAtMs)
    val notDoneAsOfClose = !doneInSprint
    // A17: carried-over/dropped apply to EVERY in-scope-at-close item not done in the sprint,
    // committed OR added — not just committed ones. Every row reaching this point is already
    // in scope at close (the `if (!inScopeAtClose) return null` guard above), so the predicate
    // is simply "not done" — final = delivered + carried + dropped, always.
    val carriedOver = notDoneAsOfClose && inLaterSprintOfTeam
    val dropped = notDoneAsOfClose && !inLaterSprintOfTeam
    return SprintScopeItem(
        issueId = issueId,
        addedAtMs = addedAtMs,
        removedAtMs = null,
        committed = committed,
        inScopeAtClose = true,
        estimateAtCommitmentMd = estimateAt(estimateTimeline, entryInstant),
        estimateAtCloseMd = estimateAtClose,
        estimateAtDoneMd = if (doneInSprint) estimateAtClose else null,
        assigneeAtCommitment = assigneeAt(entryInstant),
        doneInSprint = doneInSprint,
        carriedOver = carriedOver,
        dropped = dropped,
    )
}

/**
 * Σ of [items] (invariant 8, "`fact_sprint` = Σ `fact_sprint_scope`" — true BY CONSTRUCTION,
 * since `MetricsDeriver` writes exactly this function's own output as `fact_sprint`'s row): an
 * unestimated item (its own estimate-column value `null`) contributes `0` MD but still counts
 * toward the item total. **`committed` alone is not the committed-bucket predicate** — a
 * REMOVED row also carries `committed = true` (it WAS committed, before it left), but
 * `sample-data/jira/generate.mjs`'s own reference `computeSprintScope` counts a removed item
 * ONLY in its own removed bucket, never also in committed/final — so the committed bucket here
 * additionally requires `inScopeAtClose` (true for every non-removed row, `false` only for the
 * REMOVED shape), matching that reference exactly. **Each item's MD is rounded to [MD_SCALE] BEFORE
 * summing** ([sumMd]) — the scope rows persist the per-item rounded value, so this is what makes
 * Σ `fact_sprint_scope` (and every per-user report group) equal `fact_sprint` exactly.
 */
fun sprintTotals(items: List<SprintScopeItem>): SprintTotals {
    fun md(pred: (SprintScopeItem) -> Boolean, value: (SprintScopeItem) -> Double?) = sumMd(items.filter(pred).map(value))
    fun count(pred: (SprintScopeItem) -> Boolean) = items.count(pred)
    return SprintTotals(
        committedMd = md({ it.committed && it.inScopeAtClose }) { it.estimateAtCommitmentMd },
        committedItems = count { it.committed && it.inScopeAtClose },
        addedMd = md({ it.addedAtMs != null }) { it.estimateAtCommitmentMd },
        addedItems = count { it.addedAtMs != null },
        removedMd = md({ it.removedAtMs != null }) { it.estimateAtCommitmentMd },
        removedItems = count { it.removedAtMs != null },
        finalMd = md({ it.inScopeAtClose }) { it.estimateAtCloseMd },
        finalItems = count { it.inScopeAtClose },
        deliveredMd = md({ it.doneInSprint }) { it.estimateAtDoneMd },
        deliveredItems = count { it.doneInSprint },
        carriedOverMd = md({ it.carriedOver }) { it.estimateAtCloseMd },
        carriedOverItems = count { it.carriedOver },
        droppedMd = md({ it.dropped }) { it.estimateAtCloseMd },
        droppedItems = count { it.dropped },
    )
}
