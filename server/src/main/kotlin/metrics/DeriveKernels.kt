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

/**
 * One merged, clipped `metrics.item_blocked` interval — always closed (`toAtMs` is the window end,
 * never open-ended). [reason] is `"FLAGGED"` when every raw interval merged into this one came from
 * the Flagged field alone, `"STATUS"` when at least one came from a configured blocked status
 * (review round 2b fix — the merge used to always report `"FLAGGED"`, silently losing the
 * blocked-status source whenever the two overlapped or a status-only interval merged in).
 */
data class BlockedInterval(val fromAtMs: Long, val toAtMs: Long, val reason: String)

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

/** One point of `task_domain`'s history (v0.3.0 M3 review round 2a) — the project key active from [atMs] onward. */
data class ProjectKeyPoint(val atMs: Long, val projectKey: String?)

/**
 * D11's three epic/children drift codes (`.claude/docs/domain-model.md` "D11", `fact_epic_delivery
 * .driftFlags`) — flagged, never corrected (the `norm` anomaly convention).
 */
enum class EpicDriftFlag { EPIC_NOT_STARTED_WITH_ACTIVE_CHILDREN, EPIC_OPEN_AFTER_CHILDREN_DONE, EPIC_DONE_WITH_OPEN_CHILDREN }

/** One child task's delivery state, as [DeriveKernels.epicDriftFlags] needs it — never the full [ItemDerived]. */
data class ChildDeliveryStatus(val startedAtMs: Long?, val doneAtMs: Long?)

/**
 * One `metrics.fact_sprint_scope` row (pure, pre-persistence, v0.3.0 M3 commit 8) — a single task's
 * scope in ONE sprint, produced by [DeriveKernels.sprintScope]. `sprintId`/`connectionId` are added
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

    private const val UNESTIMATED: Double = 0.0

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
     * A sub-task-rollup composite timeline (`SUBTASKS` estimate source, review round 2a): sums, at
     * every distinct change instant across every subtask's OWN [estimateTimeline], each subtask's
     * value AT THAT INSTANT (`estimateAt`, `0.0` for a subtask that hasn't started existing yet or
     * carries no estimate there) — never the CURRENT sum re-used at every point, which would wrongly
     * report the parent's at-start/at-done snapshots as if every subtask had always carried its
     * present-day estimate. A merged point whose sum is exactly `0.0` collapses to `null` (the same
     * "0 = unestimated" rule [estimateTimeline] applies per subtask, generalized to the aggregate) so
     * [estimateSnapshots]' `estimatedLate` check still fires correctly for a parent whose subtasks
     * were ALL unestimated at start and gained estimates only later.
     */
    fun mergeEstimateTimelines(timelines: List<List<EstimatePoint>>): List<EstimatePoint> {
        if (timelines.isEmpty()) return emptyList()
        val allTimes = timelines.flatMap { timeline -> timeline.map { it.atMs } }.distinct().sorted()
        return allTimes.map { at ->
            val sum = timelines.sumOf { timeline -> estimateAt(timeline, at) ?: 0.0 }
            EstimatePoint(at, sum.asEstimateOrNull())
        }
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

    /** `ingest/DataSource.kt`'s own `PROJECT_KEY_PATTERN`, duplicated here (pure-kernel package boundary) — a real Jira project key. */
    private val ISSUE_KEY_PROJECT_PATTERN = Regex("^([A-Z][A-Z0-9_]{1,9})-\\d+$")

    /** The project-key prefix of a full issue key (e.g. `"OPS-9001"` -> `"OPS"`); `null` for a malformed/missing key. */
    fun projectKeyFromIssueKey(issueKey: String?): String? = issueKey?.let { ISSUE_KEY_PROJECT_PATTERN.find(it)?.groupValues?.get(1) }

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

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

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
     *   (D13's snapshot figures vs. a future per-item report). Carried-over/dropped are evaluated
     *   ONLY for a `committed` task that is not done: [inLaterSprintOfTeam] (precomputed by the
     *   caller — `.claude/docs/domain-model.md`'s D10 "one board per team" makes "later sprint of
     *   this task's own board" equivalent to "later sprint of the same team") decides which.
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
        val carriedOver = committed && notDoneAsOfClose && inLaterSprintOfTeam
        val dropped = committed && notDoneAsOfClose && !inLaterSprintOfTeam
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
     * REMOVED shape), matching that reference exactly.
     */
    fun sprintTotals(items: List<SprintScopeItem>): SprintTotals {
        fun md(pred: (SprintScopeItem) -> Boolean, value: (SprintScopeItem) -> Double?) = items.filter(pred).sumOf { value(it) ?: 0.0 }
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
}
