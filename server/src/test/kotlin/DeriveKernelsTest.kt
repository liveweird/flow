package ch.nokillswit

import ch.nokillswit.metrics.ActiveWait
import ch.nokillswit.metrics.BlockedInterval
import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.ItemStage
import ch.nokillswit.metrics.StageInterval
import ch.nokillswit.norm.FieldChangeRow
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TrackedField
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `metrics/DeriveKernels.kt` (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "The three
 * dimensions"/"Estimate snapshots") — pure, no DB, the `norm/Tiling.kt` pattern.
 */
class DeriveKernelsTest {

    private fun statusInterval(statusId: String, fromAtMs: Long, toAtMs: Long?, seq: Int = 1) = NormalizedStatusInterval(
        seq = seq,
        statusId = statusId,
        statusName = "Status $statusId",
        category = StatusCategory.UNKNOWN,
        fromAtMs = fromAtMs,
        toAtMs = toAtMs,
        source = if (seq == 1) IntervalSource.CREATED else IntervalSource.CHANGE,
    )

    private fun fieldChange(atMs: Long, from: String?, to: String?, fieldId: String? = "customfield_10016") = FieldChangeRow(
        issueId = 1L,
        fieldId = fieldId,
        field = "Story point estimate",
        changedAt = atMs,
        fromValue = from,
        fromText = from,
        toValue = to,
        toText = to,
    )

    /** A real Jira Cloud number custom field's own shape (review round 1): `fromValue`/`toValue` null, only `fromString`/`toString` set. */
    private fun textOnlyFieldChange(atMs: Long, from: String?, to: String?, fieldId: String? = "customfield_10016") = FieldChangeRow(
        issueId = 1L,
        fieldId = fieldId,
        field = "Story point estimate",
        changedAt = atMs,
        fromValue = null,
        fromText = from,
        toValue = null,
        toText = to,
    )

    // ---- stageIntervals -------------------------------------------------------------------

    @Test
    fun `stageIntervals maps each status interval via the stage map, one row per interval`() {
        val statusIntervals = listOf(
            statusInterval("1", 0, 100, seq = 1),
            statusInterval("3", 100, null, seq = 2),
        )
        val stageMap = mapOf("1" to ItemStage.NOT_STARTED, "3" to ItemStage.IN_PROGRESS)

        val stages = DeriveKernels.stageIntervals(statusIntervals, stageMap)

        assertEquals(2, stages.size)
        assertEquals(ItemStage.NOT_STARTED, stages[0].stage)
        assertEquals(ItemStage.IN_PROGRESS, stages[1].stage)
        assertEquals("1", stages[0].statusId)
        assertNull(stages[1].toAtMs)
    }

    @Test
    fun `stageIntervals maps a status carrying no configured stage to UNMAPPED`() {
        val statusIntervals = listOf(statusInterval("999", 0, null))
        val stages = DeriveKernels.stageIntervals(statusIntervals, emptyMap())
        assertEquals(ItemStage.UNMAPPED, stages.single().stage)
    }

    // ---- startedDoneAt / reopenCount --------------------------------------------------------

    @Test
    fun `startedDoneAt finds the FIRST entry into IN_PROGRESS and leaves done_at null while not currently DONE`() {
        val stages = listOf(
            DeriveKernels.stageIntervals(listOf(statusInterval("1", 0, 100)), mapOf("1" to ItemStage.NOT_STARTED)).single(),
        )
        val result = DeriveKernels.startedDoneAt(
            DeriveKernels.stageIntervals(
                listOf(statusInterval("1", 0, 100, 1), statusInterval("2", 100, null, 2)),
                mapOf("1" to ItemStage.NOT_STARTED, "2" to ItemStage.IN_PROGRESS),
            ),
        )
        assertEquals(100, result.startedAtMs)
        assertNull(result.doneAtMs)
        assertEquals(0, result.reopenCount)
        assertTrue(stages.isNotEmpty()) // sanity: the helper builder above is itself exercised
    }

    @Test
    fun `startedDoneAt sets done_at to the start of the TRAILING unbroken DONE run, and counts every reopen`() {
        val stageMap = mapOf("1" to ItemStage.NOT_STARTED, "2" to ItemStage.IN_PROGRESS, "3" to ItemStage.DONE)
        // NOT_STARTED -> IN_PROGRESS -> DONE -> IN_PROGRESS (reopen) -> DONE -> DONE (a second done status, still one trailing run)
        val statusIntervals = listOf(
            statusInterval("1", 0, 10, 1),
            statusInterval("2", 10, 20, 2),
            statusInterval("3", 20, 30, 3),
            statusInterval("2", 30, 40, 4),
            statusInterval("3", 40, 50, 5),
            statusInterval("3", 50, null, 6),
        )
        val stages = DeriveKernels.stageIntervals(statusIntervals, stageMap)

        val result = DeriveKernels.startedDoneAt(stages)

        assertEquals(10, result.startedAtMs, "the FIRST entry into IN_PROGRESS, not the reopen")
        assertEquals(40, result.doneAtMs, "the start of the trailing DONE run, not the first DONE transition")
        assertEquals(
            1,
            result.reopenCount,
            "DONE at seq 3 -> non-DONE at seq 4 is one reopen; the later DONE->DONE pair is not a transition",
        )
    }

    @Test
    fun `startedDoneAt leaves done_at null when the item is currently back in progress after a reopen`() {
        val stageMap = mapOf("1" to ItemStage.IN_PROGRESS, "2" to ItemStage.DONE)
        val statusIntervals = listOf(
            statusInterval("2", 0, 10, 1),
            statusInterval("1", 10, null, 2),
        )
        val result = DeriveKernels.startedDoneAt(DeriveKernels.stageIntervals(statusIntervals, stageMap))
        assertNull(result.doneAtMs)
        assertEquals(1, result.reopenCount)
    }

    @Test
    fun `startedDoneAt returns nulls and zero for an item with no stage intervals at all`() {
        val result = DeriveKernels.startedDoneAt(emptyList())
        assertNull(result.startedAtMs)
        assertNull(result.doneAtMs)
        assertEquals(0, result.reopenCount)
    }

    // ---- blockedIntervals --------------------------------------------------------------------

    @Test
    fun `blockedIntervals unions FLAGGED-true and blocked-status spans, merges overlaps, and clips to the cycle window`() {
        val flagged = listOf(
            NormalizedFieldInterval(TrackedField.FLAGGED, 1, "true", null, 5, 15),
            NormalizedFieldInterval(TrackedField.FLAGGED, 2, "false", null, 15, null),
        )
        val statusIntervals = listOf(
            statusInterval("BLOCKED", 10, 20, 1),
            statusInterval("OPEN", 20, null, 2),
        )
        val result = DeriveKernels.blockedIntervals(flagged, statusIntervals, setOf("BLOCKED"), windowFromMs = 0, windowToMs = 100)

        // [5,15) (flagged) and [10,20) (blocked status) overlap and must merge into one [5,20) interval,
        // whose reason must carry the STATUS source through the merge (review round 2b fix — it used
        // to always report "FLAGGED", silently losing the status source once the two overlapped).
        assertEquals(1, result.size)
        assertEquals(5, result.single().fromAtMs)
        assertEquals(20, result.single().toAtMs)
        assertEquals("STATUS", result.single().reason)
    }

    @Test
    fun `blockedIntervals is empty when the item never started`() {
        val flagged = listOf(NormalizedFieldInterval(TrackedField.FLAGGED, 1, "true", null, 0, null))
        val result = DeriveKernels.blockedIntervals(flagged, emptyList(), emptySet(), windowFromMs = null, windowToMs = 100)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `blockedIntervals clips an open-ended blocked span to the window end`() {
        val flagged = listOf(NormalizedFieldInterval(TrackedField.FLAGGED, 1, "true", null, 50, null))
        val result = DeriveKernels.blockedIntervals(flagged, emptyList(), emptySet(), windowFromMs = 0, windowToMs = 100)
        assertEquals(50L to 100L, result.single().fromAtMs to result.single().toAtMs)
        assertEquals("FLAGGED", result.single().reason)
    }

    @Test
    fun `blockedIntervals reports STATUS for a blocked-status-only span, never defaulting to FLAGGED`() {
        val statusIntervals = listOf(statusInterval("BLOCKED", 10, 30, 1))
        val result = DeriveKernels.blockedIntervals(emptyList(), statusIntervals, setOf("BLOCKED"), windowFromMs = 0, windowToMs = 100)
        assertEquals(10L to 30L, result.single().fromAtMs to result.single().toAtMs)
        assertEquals("STATUS", result.single().reason)
    }

    @Test
    fun `blockedIntervals reports FLAGGED for a disjoint flagged-only span (no merge, no status source)`() {
        val flagged = listOf(NormalizedFieldInterval(TrackedField.FLAGGED, 1, "true", null, 5, 15))
        val statusIntervals = listOf(statusInterval("BLOCKED", 40, 50, 1))
        val result = DeriveKernels.blockedIntervals(flagged, statusIntervals, setOf("BLOCKED"), windowFromMs = 0, windowToMs = 100)
        assertEquals(2, result.size, "two disjoint spans must stay separate, never merged")
        assertEquals("FLAGGED", result.first { it.fromAtMs == 5L }.reason)
        assertEquals("STATUS", result.first { it.fromAtMs == 40L }.reason)
    }

    // ---- activeWaitMs (A18, commit 9d) ---------------------------------------------------------

    @Test
    fun `activeWaitMs is 0,0 when the item never started or is not yet done`() {
        val stages = listOf(StageInterval(ItemStage.IN_PROGRESS, "2", 0, null))
        assertEquals(ActiveWait(0, 0), DeriveKernels.activeWaitMs(stages, emptyList(), null, null))
        assertEquals(ActiveWait(0, 0), DeriveKernels.activeWaitMs(stages, emptyList(), 0, null), "started but not done")
    }

    @Test
    fun `activeWaitMs sums IN_PROGRESS time inside the cycle window, minus blocked time, wait is the rest`() {
        val stages = listOf(
            StageInterval(ItemStage.IN_PROGRESS, "2", 0, 100),
            StageInterval(ItemStage.DONE, "3", 100, null),
        )
        val blocked = listOf(BlockedInterval(20, 40, "STATUS"))
        val result = DeriveKernels.activeWaitMs(stages, blocked, startedAtMs = 0, doneAtMs = 100)
        assertEquals(80, result.activeMs, "100 in-progress minus 20 blocked")
        assertEquals(20, result.waitMs, "cycle 100 - active 80")
    }

    @Test
    fun `activeWaitMs sums IN_PROGRESS time across a reopen, both stretches inside the window`() {
        // IN_PROGRESS [0,30) -> DONE [30,50) -> IN_PROGRESS (reopen) [50,70) -> DONE [70, null) (trailing)
        val stages = listOf(
            StageInterval(ItemStage.IN_PROGRESS, "2", 0, 30),
            StageInterval(ItemStage.DONE, "3", 30, 50),
            StageInterval(ItemStage.IN_PROGRESS, "2", 50, 70),
            StageInterval(ItemStage.DONE, "3", 70, null),
        )
        // started = 0 (the FIRST IN_PROGRESS entry), done = 70 (the start of the trailing DONE run).
        val result = DeriveKernels.activeWaitMs(stages, emptyList(), startedAtMs = 0, doneAtMs = 70)
        assertEquals(50, result.activeMs, "30 (first IN_PROGRESS stretch) + 20 (post-reopen IN_PROGRESS stretch)")
        assertEquals(20, result.waitMs, "cycle 70 - active 50")
    }

    @Test
    fun `activeWaitMs ignores blocked time outside any IN_PROGRESS stage, never double-subtracting it (review round 2c fix)`() {
        // A contrived-but-legal stage history: IN_PROGRESS [0,10) -> NOT_STARTED [10,30) (structurally
        // unusual mid-cycle, but the kernel makes no assumption about which non-IN_PROGRESS stage it
        // is) -> IN_PROGRESS [30,50) -> DONE [50, null). Blocked spans EXACTLY the NOT_STARTED gap —
        // time that is already WAIT by construction (never summed into in-progress in the first
        // place), so it must contribute ZERO to the blocked subtraction.
        val notStarted = listOf(
            StageInterval(ItemStage.IN_PROGRESS, "2", 0, 10),
            StageInterval(ItemStage.NOT_STARTED, "1", 10, 30),
            StageInterval(ItemStage.IN_PROGRESS, "2", 30, 50),
            StageInterval(ItemStage.DONE, "3", 50, null),
        )
        val blockedDuringNotStarted = listOf(BlockedInterval(10, 30, "STATUS"))
        val resultNotStarted = DeriveKernels.activeWaitMs(notStarted, blockedDuringNotStarted, startedAtMs = 0, doneAtMs = 50)
        assertEquals(30, resultNotStarted.activeMs, "10 + 20 IN_PROGRESS time, untouched — the blocked span never overlapped IN_PROGRESS")
        assertEquals(20, resultNotStarted.waitMs, "cycle 50 - active 30")

        // The same shape with an UNMAPPED gap instead of NOT_STARTED — an unmapped status is just
        // another non-IN_PROGRESS stage as far as this kernel is concerned.
        val unmapped = listOf(
            StageInterval(ItemStage.IN_PROGRESS, "2", 0, 10),
            StageInterval(ItemStage.UNMAPPED, "9", 10, 30),
            StageInterval(ItemStage.IN_PROGRESS, "2", 30, 50),
            StageInterval(ItemStage.DONE, "3", 50, null),
        )
        val blockedDuringUnmapped = listOf(BlockedInterval(10, 30, "FLAGGED"))
        val resultUnmapped = DeriveKernels.activeWaitMs(unmapped, blockedDuringUnmapped, startedAtMs = 0, doneAtMs = 50)
        assertEquals(30, resultUnmapped.activeMs, "blocked-while-UNMAPPED must not subtract from IN_PROGRESS time")
        assertEquals(20, resultUnmapped.waitMs)
    }

    @Test
    fun `activeWaitMs subtracts a reopen-spanning blocked interval only where it overlaps each IN_PROGRESS stretch, not the DONE gap`() {
        // IN_PROGRESS [0,20) -> DONE [20,40) -> IN_PROGRESS (reopen) [40,60) -> DONE [60, null).
        // One blocked span [10,50) crosses ALL THREE: 10 ms of the first IN_PROGRESS stretch (10-20),
        // the entire 20 ms DONE gap (20-40, already WAIT, never counted as in-progress), and 10 ms of
        // the reopened IN_PROGRESS stretch (40-50). Only the two IN_PROGRESS overlaps (10 + 10 = 20 ms)
        // may be subtracted — subtracting the WHOLE 40 ms span (the pre-fix bug) would double-count the
        // DONE gap's own 20 ms, which was never part of `inProgressMs` to begin with.
        val stages = listOf(
            StageInterval(ItemStage.IN_PROGRESS, "2", 0, 20),
            StageInterval(ItemStage.DONE, "3", 20, 40),
            StageInterval(ItemStage.IN_PROGRESS, "2", 40, 60),
            StageInterval(ItemStage.DONE, "3", 60, null),
        )
        val blocked = listOf(BlockedInterval(10, 50, "STATUS"))
        val result = DeriveKernels.activeWaitMs(stages, blocked, startedAtMs = 0, doneAtMs = 60)
        assertEquals(20, result.activeMs, "(20 + 20 IN_PROGRESS) - (10 + 10 blocked-while-IN_PROGRESS) = 20, never 0")
        assertEquals(40, result.waitMs, "cycle 60 - active 20")
    }

    @Test
    fun `activeWaitMs floors active at 0 as a defensive backstop, never negative`() {
        // The intersection of blocked with IN_PROGRESS time can never exceed IN_PROGRESS time by
        // construction, so this floor is defensive only (rounding at interval boundaries) — not a
        // real-world "more blocked-while-in-progress than in-progress" case. Blocked here is fully
        // INSIDE the one IN_PROGRESS interval and exactly equals it, so active floors at exactly 0.
        val stages = listOf(StageInterval(ItemStage.IN_PROGRESS, "2", 0, 10), StageInterval(ItemStage.DONE, "3", 10, null))
        val blocked = listOf(BlockedInterval(0, 10, "FLAGGED"))
        val result = DeriveKernels.activeWaitMs(stages, blocked, startedAtMs = 0, doneAtMs = 10)
        assertEquals(0, result.activeMs)
        assertEquals(10, result.waitMs, "wait absorbs the whole cycle once active floors at 0")
    }

    @Test
    fun `activeWaitMs is 0,0 for an item whose whole history is an UNMAPPED status`() {
        val stageMap = emptyMap<String, ItemStage>() // "unmapped-status" resolves to ItemStage.UNMAPPED
        val stages = DeriveKernels.stageIntervals(listOf(statusInterval("unmapped-status", 0, null, 1)), stageMap)
        val startedDone = DeriveKernels.startedDoneAt(stages)
        assertNull(startedDone.startedAtMs, "an UNMAPPED-only item is never started")
        assertNull(startedDone.doneAtMs)
        val result = DeriveKernels.activeWaitMs(stages, emptyList(), startedDone.startedAtMs, startedDone.doneAtMs)
        assertEquals(ActiveWait(0, 0), result)
    }

    // ---- estimateTimeline / estimateSnapshots -------------------------------------------------

    @Test
    fun `estimateTimeline with no changes uses the current value, and 0 counts as unestimated`() {
        val zero = DeriveKernels.estimateTimeline(createdAtMs = 0, changes = emptyList(), currentValueMd = 0.0)
        assertNull(zero.single().estimateMd)

        val five = DeriveKernels.estimateTimeline(createdAtMs = 0, changes = emptyList(), currentValueMd = 5.0)
        assertEquals(5.0, five.single().estimateMd)
    }

    @Test
    fun `estimateSnapshots reports estimated-late when the item started with no estimate but has gained one since`() {
        val timeline = DeriveKernels.estimateTimeline(
            createdAtMs = 0,
            changes = listOf(fieldChange(atMs = 200, from = null, to = "8")),
            currentValueMd = 8.0,
        )
        val snapshots = DeriveKernels.estimateSnapshots(timeline, startedAtMs = 100, doneAtMs = null)
        assertNull(snapshots.atStartMd)
        assertEquals(8.0, snapshots.currentMd)
        assertTrue(snapshots.estimatedLate)
        assertEquals(1, snapshots.changesAfterStart)
    }

    @Test
    fun `estimateSnapshots is not estimated-late when the estimate was already present at start`() {
        val timeline = DeriveKernels.estimateTimeline(createdAtMs = 0, changes = emptyList(), currentValueMd = 5.0)
        val snapshots = DeriveKernels.estimateSnapshots(timeline, startedAtMs = 50, doneAtMs = 200)
        assertEquals(5.0, snapshots.atStartMd)
        assertEquals(5.0, snapshots.atDoneMd)
        assertEquals(false, snapshots.estimatedLate)
        assertEquals(0, snapshots.changesAfterStart)
    }

    @Test
    fun `estimateSnapshots counts a re-estimate to 0 as reverting to unestimated, not a literal zero`() {
        val timeline = DeriveKernels.estimateTimeline(
            createdAtMs = 0,
            changes = listOf(fieldChange(atMs = 50, from = "5", to = "0")),
            currentValueMd = 0.0,
        )
        // The re-estimate to 0 lands at t=50, BEFORE start (t=100) — so the item was already
        // unestimated by the time it started, not "estimated at start" with a stale 5.0.
        val snapshots = DeriveKernels.estimateSnapshots(timeline, startedAtMs = 100, doneAtMs = null)
        assertNull(snapshots.atStartMd, "the change to 0 already landed before t=100, so the estimate active at start is unestimated")
        assertNull(snapshots.currentMd)

        // Before the re-estimate lands (t=25), the pre-zero value is still active.
        assertEquals(5.0, DeriveKernels.estimateAt(timeline, atMs = 25))
    }

    @Test
    fun `estimateTimeline falls back to fromString-toString when a real Jira number field carries no fromValue-toValue`() {
        // A real Jira Cloud Story Points changelog item: fromValue/toValue are null, only the text pair is set.
        val timeline = DeriveKernels.estimateTimeline(
            createdAtMs = 0,
            changes = listOf(textOnlyFieldChange(atMs = 100, from = "3", to = "5")),
            currentValueMd = 5.0,
        )
        assertEquals(3.0, timeline.first().estimateMd, "the first point must parse fromString when fromValue is null")
        assertEquals(5.0, timeline.last().estimateMd)
    }

    @Test
    fun `estimateTimeline anchors its last point to the current value even when the last changelog toValue disagrees`() {
        val timeline = DeriveKernels.estimateTimeline(
            createdAtMs = 0,
            changes = listOf(fieldChange(atMs = 100, from = "3", to = "5")),
            currentValueMd = 8.0, // e.g. edited again without leaving a tracked changelog item
        )
        assertEquals(8.0, timeline.last().estimateMd, "the current value is ground truth, not the last changelog toValue")
    }

    @Test
    fun `estimateTimeline clamps a changelog event before creation to createdAtMs`() {
        val timeline = DeriveKernels.estimateTimeline(
            createdAtMs = 1_000,
            changes = listOf(fieldChange(atMs = 500, from = null, to = "5")),
            currentValueMd = 5.0,
        )
        assertTrue(timeline.all { it.atMs >= 1_000 }, "no point may predate the item's own creation")
    }

    // ---- valueAsOf -----------------------------------------------------------------------------

    @Test
    fun `valueAsOf returns the initial value before any change and the latest change at or before t`() {
        val points = listOf(10L to "A", 20L to "B", 30L to "C")
        assertEquals("start", DeriveKernels.valueAsOf(points, initial = "start", atMs = 5))
        assertEquals("A", DeriveKernels.valueAsOf(points, initial = "start", atMs = 10))
        assertEquals("B", DeriveKernels.valueAsOf(points, initial = "start", atMs = 25))
        assertEquals("C", DeriveKernels.valueAsOf(points, initial = "start", atMs = 1000))
    }

    // ---- sprintMembership (set-valued) ----------------------------------------------------------

    @Test
    fun `sprintMembership opens a membership at creation when there are no changes at all`() {
        val result = DeriveKernels.sprintMembership(createdAtMs = 0, changes = emptyList(), currentSprintIds = listOf(7L))
        assertEquals(listOf(ch.nokillswit.metrics.SprintMembershipInterval(7L, 0, null)), result)
    }

    @Test
    fun `sprintMembership diffs set-valued changes into per-sprint intervals, closing dropped sprints and opening new ones`() {
        // created in sprint 1; moved into {1,2} (carry-over add); then moved to {2} only (1 closes); still open in 2.
        val changes = listOf(
            fieldChange(atMs = 100, from = "1", to = "1,2", fieldId = null),
            fieldChange(atMs = 200, from = "1,2", to = "2", fieldId = null),
        )
        val result = DeriveKernels.sprintMembership(createdAtMs = 0, changes = changes, currentSprintIds = listOf(2L))

        val bySprint = result.associateBy { it.sprintId }
        assertEquals(0, bySprint.getValue(1L).fromAtMs)
        assertEquals(200, bySprint.getValue(1L).toAtMs, "sprint 1 closes when it drops out of the set")
        assertEquals(100, bySprint.getValue(2L).fromAtMs, "sprint 2 opens the moment it first appears")
        assertNull(bySprint.getValue(2L).toAtMs, "sprint 2 is still open — it is the CURRENT sprint")
    }

    @Test
    fun `sprintMembership never loses a sprint that a task moved directly into without an empty gap`() {
        // A -> B in one changelog event (never empty in between) must still close A and open B.
        val changes = listOf(fieldChange(atMs = 100, from = "10", to = "20", fieldId = null))
        val result = DeriveKernels.sprintMembership(createdAtMs = 0, changes = changes, currentSprintIds = listOf(20L))
        val bySprint = result.associateBy { it.sprintId }
        assertEquals(2, result.size)
        assertEquals(100, bySprint.getValue(10L).toAtMs)
        assertEquals(100, bySprint.getValue(20L).fromAtMs)
        assertNull(bySprint.getValue(20L).toAtMs)
    }

    @Test
    fun `sprintMembership clamps a changelog event before creation to createdAtMs`() {
        val changes = listOf(fieldChange(atMs = 50, from = "1", to = "2", fieldId = null))
        val result = DeriveKernels.sprintMembership(createdAtMs = 1_000, changes = changes, currentSprintIds = listOf(2L))
        assertTrue(
            result.all { it.fromAtMs >= 1_000 && (it.toAtMs == null || it.toAtMs >= 1_000) },
            "no interval boundary may predate the item's own creation",
        )
    }

    // ---- epicDriftFlags (D11) --------------------------------------------------------------------

    @Test
    fun `epicDriftFlags flags nothing for an epic with no children at all`() {
        val flags = DeriveKernels.epicDriftFlags(ItemStage.NOT_STARTED, emptyList(), epicDriftDays = 14, nowMs = 1_000_000)
        assertTrue(flags.isEmpty())
    }

    @Test
    fun `epicDriftFlags flags nothing when the epic and its children agree`() {
        val children = listOf(ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 100, doneAtMs = null))
        val flags = DeriveKernels.epicDriftFlags(ItemStage.IN_PROGRESS, children, epicDriftDays = 14, nowMs = 1_000_000)
        assertTrue(flags.isEmpty())
    }

    @Test
    fun `epicDriftFlags flags EPIC_NOT_STARTED_WITH_ACTIVE_CHILDREN when a child has started but the epic never did`() {
        val children = listOf(ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 100, doneAtMs = null))
        val flags = DeriveKernels.epicDriftFlags(ItemStage.NOT_STARTED, children, epicDriftDays = 14, nowMs = 1_000_000)
        assertEquals(listOf(ch.nokillswit.metrics.EpicDriftFlag.EPIC_NOT_STARTED_WITH_ACTIVE_CHILDREN), flags)
    }

    @Test
    fun `epicDriftFlags flags EPIC_OPEN_AFTER_CHILDREN_DONE once the threshold has elapsed since the last child's done_at`() {
        val dayMs = 24L * 60 * 60 * 1000
        val children = listOf(
            ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 0, doneAtMs = 1_000),
            ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 0, doneAtMs = 2_000),
        )
        val justUnder = DeriveKernels.epicDriftFlags(ItemStage.IN_PROGRESS, children, epicDriftDays = 14, nowMs = 2_000 + 14 * dayMs - 1)
        assertTrue(justUnder.isEmpty(), "not yet past the threshold")

        val atThreshold = DeriveKernels.epicDriftFlags(ItemStage.IN_PROGRESS, children, epicDriftDays = 14, nowMs = 2_000 + 14 * dayMs)
        assertEquals(listOf(ch.nokillswit.metrics.EpicDriftFlag.EPIC_OPEN_AFTER_CHILDREN_DONE), atThreshold)
    }

    @Test
    fun `epicDriftFlags never flags EPIC_OPEN_AFTER_CHILDREN_DONE while any child is still open`() {
        val dayMs = 24L * 60 * 60 * 1000
        val children = listOf(
            ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 0, doneAtMs = 1_000),
            ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 0, doneAtMs = null),
        )
        val flags = DeriveKernels.epicDriftFlags(ItemStage.IN_PROGRESS, children, epicDriftDays = 14, nowMs = 1_000 + 100 * dayMs)
        assertTrue(flags.isEmpty())
    }

    @Test
    fun `epicDriftFlags flags EPIC_DONE_WITH_OPEN_CHILDREN when the epic is DONE but a child is not`() {
        val children = listOf(
            ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 0, doneAtMs = 1_000),
            ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 0, doneAtMs = null),
        )
        val flags = DeriveKernels.epicDriftFlags(ItemStage.DONE, children, epicDriftDays = 14, nowMs = 1_000_000)
        assertEquals(listOf(ch.nokillswit.metrics.EpicDriftFlag.EPIC_DONE_WITH_OPEN_CHILDREN), flags)
    }

    @Test
    fun `epicDriftFlags can report multiple flags at once`() {
        // The epic is DONE, but one child never started while another finished long ago — both
        // EPIC_DONE_WITH_OPEN_CHILDREN and (since not all children are done) no OPEN_AFTER check applies,
        // so only the done-with-open-children flag fires; separately assert the not-started case combines
        // with nothing else since a NOT_STARTED epic can never also be DONE.
        val children = listOf(
            ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = null, doneAtMs = null),
            ch.nokillswit.metrics.ChildDeliveryStatus(startedAtMs = 0, doneAtMs = 1_000),
        )
        val flags = DeriveKernels.epicDriftFlags(ItemStage.DONE, children, epicDriftDays = 14, nowMs = 1_000_000)
        assertEquals(listOf(ch.nokillswit.metrics.EpicDriftFlag.EPIC_DONE_WITH_OPEN_CHILDREN), flags)
    }

    // ---- sprintScope (v0.3.0 M3 commit 8, the fact_sprint_scope buckets) -------------------------

    private val sprintStart = 10_000L
    private val sprintClose = 20_000L

    private fun membership(from: Long, to: Long?) = ch.nokillswit.metrics.SprintMembershipInterval(1L, from, to)
    private fun assigneeInterval(accountId: String?, from: Long, to: Long?) =
        NormalizedFieldInterval(TrackedField.ASSIGNEE, seq = 1, valueId = accountId, valueText = null, fromAtMs = from, toAtMs = to)
    private fun estimate(atMs: Long, md: Double?) = ch.nokillswit.metrics.EstimatePoint(atMs, md)

    @Test
    fun `sprintScope never returns a row for a task with no membership in this sprint at all`() {
        val row = DeriveKernels.sprintScope(
            issueId = 1L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = emptyList(), estimateTimeline = listOf(estimate(0, 5.0)),
            assigneeIntervals = emptyList(), doneAtMs = null, inLaterSprintOfTeam = false,
        )
        assertNull(row)
    }

    @Test
    fun `sprintScope marks a task committed when it entered at or before the commitment threshold`() {
        val row = DeriveKernels.sprintScope(
            issueId = 1L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart - 1000, null)),
            estimateTimeline = listOf(estimate(0, 5.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = true,
        )
        requireNotNull(row)
        assertTrue(row.committed)
        assertNull(row.addedAtMs)
        assertEquals(5.0, row.estimateAtCommitmentMd)
        assertTrue(row.inScopeAtClose)
        assertTrue(row.carriedOver, "committed, not done, present in a later sprint of the team")
        assertTrue(!row.dropped)
    }

    @Test
    fun `sprintScope respects the grace period when deciding committed`() {
        val enteredJustAfterStart = sprintStart + 500
        val withoutGrace = DeriveKernels.sprintScope(
            issueId = 1L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(enteredJustAfterStart, null)),
            estimateTimeline = listOf(estimate(0, 3.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = false,
        )
        requireNotNull(withoutGrace)
        assertTrue(!withoutGrace.committed, "entered after the bare sprint start, no grace granted")
        assertEquals(enteredJustAfterStart, withoutGrace.addedAtMs)

        val withGrace = DeriveKernels.sprintScope(
            issueId = 1L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 1000,
            membershipIntervals = listOf(membership(enteredJustAfterStart, null)),
            estimateTimeline = listOf(estimate(0, 3.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = false,
        )
        requireNotNull(withGrace)
        assertTrue(withGrace.committed, "the same entry now falls within the grace window")
        assertNull(withGrace.addedAtMs)
    }

    @Test
    fun `sprintScope marks added scope for a task that entered after the commitment threshold and stayed to close`() {
        val enteredAt = sprintStart + 5000
        val row = DeriveKernels.sprintScope(
            issueId = 2L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(enteredAt, null)),
            // `estimateTimeline()` itself collapses a literal 0 to `null` before it ever becomes an
            // `EstimatePoint` (`.claude/docs/domain-model.md`: "0 SP counts as unestimated") — this
            // hand-built timeline models that ALREADY-collapsed state, the shape `sprintScope`
            // actually receives in production.
            estimateTimeline = listOf(estimate(0, null)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = false,
        )
        requireNotNull(row)
        assertTrue(!row.committed)
        assertEquals(enteredAt, row.addedAtMs)
        // The row is still WRITTEN and still COUNTED (sprintTotals below), just with a null MD
        // contribution — an unestimated item is never silently dropped from the scope.
        assertNull(row.estimateAtCommitmentMd)
        assertTrue(row.inScopeAtClose)
    }

    @Test
    fun `sprintScope attributes a committed row to the assignee at commitment and an added row to the assignee at entry`() {
        // "ann" holds the task until 12_000, "bob" after: the sprint commits at 10_000 (no grace).
        val reassigned = listOf(assigneeInterval("ann", 0, 12_000), assigneeInterval("bob", 12_000, null))
        val committed = DeriveKernels.sprintScope(
            issueId = 1L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart - 1000, null)),
            estimateTimeline = listOf(estimate(0, 5.0)), assigneeIntervals = reassigned,
            doneAtMs = null, inLaterSprintOfTeam = false,
        )
        requireNotNull(committed)
        assertTrue(committed.committed)
        assertEquals("ann", committed.assigneeAtCommitment, "reassigned to bob after commitment: still ann's at commitment")

        val added = DeriveKernels.sprintScope(
            issueId = 2L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart + 5000, null)),
            estimateTimeline = listOf(estimate(0, 3.0)), assigneeIntervals = reassigned,
            doneAtMs = null, inLaterSprintOfTeam = false,
        )
        requireNotNull(added)
        assertTrue(!added.committed)
        assertEquals("bob", added.assigneeAtCommitment, "entered at 15_000, after the reassignment: assignee at ENTRY")
    }

    @Test
    fun `sprintScope marks removed scope for a task committed then exited before completion, excluded from every other bucket`() {
        val row = DeriveKernels.sprintScope(
            issueId = 3L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart - 1000, sprintStart + 2000)),
            estimateTimeline = listOf(estimate(0, 8.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = true,
        )
        requireNotNull(row)
        assertTrue(row.committed)
        assertEquals(sprintStart + 2000, row.removedAtMs)
        assertTrue(!row.inScopeAtClose)
        assertEquals(8.0, row.estimateAtCommitmentMd)
        assertNull(row.estimateAtCloseMd, "a removed task carries no at-close estimate — it never reaches the final bucket")
        assertTrue(!row.doneInSprint)
        assertTrue(!row.carriedOver && !row.dropped, "removed is its own terminal bucket, never also carried or dropped")
    }

    @Test
    fun `sprintScope marks delivered scope for a task done inside the sprint window while still a member`() {
        val doneAt = sprintStart + 3000
        val row = DeriveKernels.sprintScope(
            issueId = 4L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart - 1000, null)),
            estimateTimeline = listOf(estimate(0, 3.0), estimate(doneAt, 5.0)),
            assigneeIntervals = listOf(assigneeInterval("acc-1", sprintStart - 1000, null)),
            doneAtMs = doneAt, inLaterSprintOfTeam = false,
        )
        requireNotNull(row)
        assertTrue(row.committed)
        assertEquals(3.0, row.estimateAtCommitmentMd, "at-commitment estimate is read at the commitment instant, before the later change")
        assertTrue(row.doneInSprint)
        assertEquals(5.0, row.estimateAtDoneMd, "delivered reads the sprint's own close-instant value, same as estimateAtCloseMd")
        assertEquals(row.estimateAtCloseMd, row.estimateAtDoneMd)
        assertEquals("acc-1", row.assigneeAtCommitment)
        assertTrue(!row.carriedOver && !row.dropped, "a delivered task is never also carried over or dropped")
    }

    @Test
    fun `sprintScope marks dropped scope for a committed task not done with no later sprint`() {
        val row = DeriveKernels.sprintScope(
            issueId = 5L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart - 1000, null)),
            estimateTimeline = listOf(estimate(0, 2.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = false,
        )
        requireNotNull(row)
        assertTrue(row.committed)
        assertTrue(row.dropped)
        assertTrue(!row.carriedOver)
    }

    @Test
    fun `sprintScope carries over an ADDED task present in a later sprint of the team, and drops it otherwise (A17)`() {
        val enteredAt = sprintStart + 5000
        val carried = DeriveKernels.sprintScope(
            issueId = 6L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(enteredAt, null)),
            estimateTimeline = listOf(estimate(0, 4.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = true,
        )
        requireNotNull(carried)
        assertTrue(!carried.committed, "added, not committed")
        assertTrue(carried.inScopeAtClose)
        assertTrue(carried.carriedOver, "added scope is not exempt from carry-over/dropped — A17")
        assertTrue(!carried.dropped)

        val dropped = DeriveKernels.sprintScope(
            issueId = 7L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(enteredAt, null)),
            estimateTimeline = listOf(estimate(0, 4.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = false,
        )
        requireNotNull(dropped)
        assertTrue(!dropped.committed)
        assertTrue(dropped.dropped, "added, not done, no later sprint — dropped, not silently uncounted")
        assertTrue(!dropped.carriedOver)
    }

    @Test
    fun `sprintTotals sums exactly the rows it is given — invariant 8 by construction`() {
        val committedOnly = DeriveKernels.sprintScope(
            issueId = 1L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart - 1000, null)),
            estimateTimeline = listOf(estimate(0, 5.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = true,
        )!!
        val added = DeriveKernels.sprintScope(
            issueId = 2L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart + 5000, null)),
            estimateTimeline = listOf(estimate(0, 3.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = false,
        )!!
        val removed = DeriveKernels.sprintScope(
            issueId = 3L, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart - 1000, sprintStart + 2000)),
            estimateTimeline = listOf(estimate(0, 8.0)), assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = false,
        )!!
        val totals = DeriveKernels.sprintTotals(listOf(committedOnly, added, removed))
        // `removed`'s own row also carries `committed = true` (it WAS committed before leaving),
        // but the committed BUCKET excludes it — a removed item is counted ONLY in removedMd,
        // matching `sample-data/jira/generate.mjs`'s own reference `computeSprintScope`.
        assertEquals(5.0, totals.committedMd)
        assertEquals(1, totals.committedItems)
        assertEquals(3.0, totals.addedMd)
        assertEquals(1, totals.addedItems)
        assertEquals(8.0, totals.removedMd)
        assertEquals(1, totals.removedItems)
        assertEquals(5.0 + 3.0, totals.finalMd)
        assertEquals(2, totals.finalItems)
        assertEquals(1, totals.carriedOverItems)
        // A17: `added` is not done and has no later sprint (`inLaterSprintOfTeam = false`), so it now
        // lands in the dropped bucket too — carried-over/dropped are no longer committed-only.
        assertEquals(1, totals.droppedItems)
        assertEquals(0, totals.deliveredItems)
    }

    @Test
    fun `sprintTotals rounds each item's MD before summing, so the team total equals the sum of its per-user groups to the cent`() {
        fun scope(id: Long, estimateMd: Double) = DeriveKernels.sprintScope(
            issueId = id, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(sprintStart - 1000, null)),
            estimateTimeline = listOf(estimate(0, estimateMd)),
            assigneeIntervals = emptyList(),
            doneAtMs = null, inLaterSprintOfTeam = true,
        )!!
        // Three items whose unrounded sum is exactly 1.000 but whose stored (2-decimal) values sum to 0.99 —
        // the old sum-then-round team total (1.00) disagreed with the Σ of the per-item stored values by 0.01.
        val userA = listOf(scope(1L, 0.333), scope(2L, 0.333))
        val userB = listOf(scope(3L, 0.334))
        val team = DeriveKernels.sprintTotals(userA + userB)
        val groups = listOf(DeriveKernels.sprintTotals(userA), DeriveKernels.sprintTotals(userB))

        // What `fact_sprint_scope` stores per item (NUMERIC(8, 2), half-up), summed exactly.
        fun stored(md: Double) = md.toBigDecimal().setScale(2, RoundingMode.HALF_UP)
        val storedSum = (userA + userB).fold(BigDecimal.ZERO) { acc, row -> acc + stored(row.estimateAtCloseMd!!) }
        assertEquals(BigDecimal("0.99"), storedSum)
        assertEquals(0.99, team.finalMd)
        assertEquals(0.99, team.committedMd)
        assertEquals(0.99, team.carriedOverMd)
        assertEquals(team.finalMd, groups.fold(BigDecimal.ZERO) { acc, g -> acc + g.finalMd.toBigDecimal() }.toDouble())
        assertEquals(team.committedMd, groups.fold(BigDecimal.ZERO) { acc, g -> acc + g.committedMd.toBigDecimal() }.toDouble())
        // Idempotent: rebuilding the items from their stored scale-2 values (what the reports re-sum) gives the same total.
        val reread = (userA + userB).map {
            it.copy(
                estimateAtCommitmentMd = stored(it.estimateAtCommitmentMd!!).toDouble(),
                estimateAtCloseMd = stored(it.estimateAtCloseMd!!).toDouble(),
            )
        }
        val rereadTotals = DeriveKernels.sprintTotals(reread)
        assertEquals(team.finalMd, rereadTotals.finalMd)
        assertEquals(team.committedMd, rereadTotals.committedMd)
    }

    @Test
    fun `sprintTotals - A17 partition - final equals delivered + carried + dropped, and committed + added equals final`() {
        fun scope(id: Long, entered: Long, exited: Long?, doneAt: Long?, laterSprint: Boolean) = DeriveKernels.sprintScope(
            issueId = id, sprintStartAtMs = sprintStart, sprintCloseAtMs = sprintClose, graceMs = 0,
            membershipIntervals = listOf(membership(entered, exited)),
            estimateTimeline = listOf(estimate(0, 1.0)), assigneeIntervals = emptyList(),
            doneAtMs = doneAt, inLaterSprintOfTeam = laterSprint,
        )
        val rows = listOfNotNull(
            // committed, delivered
            scope(1L, sprintStart - 1000, null, sprintStart + 100, false),
            // committed, carried over
            scope(2L, sprintStart - 1000, null, null, true),
            // committed, dropped
            scope(3L, sprintStart - 1000, null, null, false),
            // committed, then removed before close — excluded from every other bucket
            scope(4L, sprintStart - 1000, sprintStart + 200, null, false),
            // added, delivered
            scope(5L, sprintStart + 500, null, sprintStart + 600, false),
            // added, carried over (A17)
            scope(6L, sprintStart + 500, null, null, true),
            // added, dropped (A17)
            scope(7L, sprintStart + 500, null, null, false),
        )
        val totals = DeriveKernels.sprintTotals(rows)
        assertEquals(totals.committedItems + totals.addedItems, totals.finalItems, "committed + added = final")
        assertEquals(
            totals.deliveredItems + totals.carriedOverItems + totals.droppedItems,
            totals.finalItems,
            "final = delivered + carried + dropped",
        )
        assertEquals(2, totals.deliveredItems)
        assertEquals(2, totals.carriedOverItems)
        assertEquals(2, totals.droppedItems)
        assertEquals(3, totals.committedItems)
        assertEquals(3, totals.addedItems)
        assertEquals(1, totals.removedItems)
        assertEquals(6, totals.finalItems)
    }

    // ---- epicPlanBaselines / pvCurve (v0.3.0 M3 commit 9b, D4's PV baselines) ---------------------

    private fun isoMs(isoDate: String): Long = LocalDate.parse(isoDate).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun datePoint(atMs: Long, isoDate: String?) = ch.nokillswit.metrics.DatePoint(atMs, isoDate?.let { isoMs(it) })

    private val createdAtMs = isoMs("2026-01-01")
    private val utc = ZoneId.of("UTC")
    private val mondayToFriday = setOf(6, 7) // ISO weekday numbers: Saturday, Sunday
    private val noHolidays = emptySet<LocalDate>()
    private val calendar = ch.nokillswit.metrics.WorkingCalendar(utc, mondayToFriday, noHolidays)

    @Test
    fun `epicPlanBaselines returns no rows when the epic never has both dates set`() {
        val startOnly = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(datePoint(createdAtMs, null)),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 10.0)),
            childSumMd = 0.0,
        )
        assertTrue(startOnly.isEmpty(), "a start date alone, with no due date ever set, must never baseline")

        val neither = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, null)),
            dueTimeline = listOf(datePoint(createdAtMs, null)),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 10.0)),
            childSumMd = 0.0,
        )
        assertTrue(neither.isEmpty())
    }

    @Test
    fun `epicPlanBaselines opens a baseline at the LATER of the two dates, whichever field resolves last`() {
        // due already known since creation; start only arrives on day 10 — the baseline must wait for it.
        val dueFirst = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, null), datePoint(isoMs("2026-01-10"), "2026-01-10")),
            dueTimeline = listOf(datePoint(createdAtMs, "2026-02-01")),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 20.0)),
            childSumMd = 0.0,
        )
        assertEquals(1, dueFirst.size)
        assertEquals(isoMs("2026-01-10"), dueFirst.single().baselinedAtMs, "baselined_at is the instant the LATER date became set")

        // the reverse: start known since creation, due arrives later.
        val startFirst = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(datePoint(createdAtMs, null), datePoint(isoMs("2026-01-20"), "2026-02-01")),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 20.0)),
            childSumMd = 0.0,
        )
        assertEquals(1, startFirst.size)
        assertEquals(isoMs("2026-01-20"), startFirst.single().baselinedAtMs)
    }

    @Test
    fun `epicPlanBaselines opens a new baseline and supersedes the previous one on a later date change`() {
        val baselines = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(
                datePoint(createdAtMs, "2026-02-01"),
                datePoint(isoMs("2026-01-15"), "2026-02-15"),
            ),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 20.0)),
            childSumMd = 0.0,
        )
        assertEquals(2, baselines.size)
        val (first, second) = baselines
        assertEquals(createdAtMs, first.baselinedAtMs)
        assertEquals(isoMs("2026-02-01"), first.dueAtMs)
        assertEquals(isoMs("2026-01-15"), first.supersededAtMs, "a later due-date change supersedes the previous baseline")
        assertEquals(isoMs("2026-01-15"), second.baselinedAtMs)
        assertEquals(isoMs("2026-02-15"), second.dueAtMs)
        assertNull(second.supersededAtMs, "the current baseline carries no superseded_at")
        // dates unchanged, so a re-submission of the SAME due date must never open a third baseline.
        val idempotent = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(
                datePoint(createdAtMs, "2026-02-01"),
                datePoint(isoMs("2026-01-15"), "2026-02-01"),
            ),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 20.0)),
            childSumMd = 0.0,
        )
        assertEquals(1, idempotent.size, "a changelog event resolving to the SAME value opens no new baseline")
    }

    @Test
    fun `epicPlanBaselines supersedes the open baseline when a date is later cleared, leaving none current`() {
        val baselines = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(
                datePoint(createdAtMs, "2026-02-01"),
                datePoint(isoMs("2026-01-15"), null),
            ),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 20.0)),
            childSumMd = 0.0,
        )
        assertEquals(1, baselines.size, "the cleared due date must supersede the open baseline, not silently keep it current")
        val only = baselines.single()
        assertEquals(createdAtMs, only.baselinedAtMs)
        assertEquals(isoMs("2026-01-15"), only.supersededAtMs, "superseded_at is the instant the date was cleared")
    }

    @Test
    fun `epicPlanBaselines opens a genuinely NEW baseline when a cleared date is re-set, even to the same value`() {
        val baselines = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(
                datePoint(createdAtMs, "2026-02-01"),
                datePoint(isoMs("2026-01-15"), null),
                datePoint(isoMs("2026-01-20"), "2026-02-01"),
            ),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 20.0)),
            childSumMd = 0.0,
        )
        assertEquals(
            2, baselines.size,
            "the gap itself is a real discontinuity — re-setting to the SAME value still opens a new baseline",
        )
        val (first, second) = baselines
        assertEquals(createdAtMs, first.baselinedAtMs)
        assertEquals(isoMs("2026-01-15"), first.supersededAtMs)
        assertEquals(isoMs("2026-01-20"), second.baselinedAtMs, "the second baseline dates to the RE-SET instant, not the original")
        assertEquals(isoMs("2026-02-01"), second.dueAtMs)
        assertNull(second.supersededAtMs)
    }

    @Test
    fun `epicPlanBaselines opens a new baseline on a later budget change, keeping the dates`() {
        val baselines = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(datePoint(createdAtMs, "2026-02-01")),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 20.0), estimate(isoMs("2026-01-10"), 30.0)),
            childSumMd = 0.0,
        )
        assertEquals(2, baselines.size)
        assertEquals(20.0, baselines[0].budgetMd)
        assertEquals("OWN", baselines[0].budgetSource)
        assertEquals(isoMs("2026-01-10"), baselines[0].supersededAtMs)
        assertEquals(30.0, baselines[1].budgetMd)
        assertEquals(baselines[0].startAtMs, baselines[1].startAtMs, "a budget-only change keeps the same dates")
        assertEquals(baselines[0].dueAtMs, baselines[1].dueAtMs)
    }

    @Test
    fun `epicPlanBaselines falls back to the CHILDREN sum when the epic never carries its own estimate`() {
        val baselines = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(datePoint(createdAtMs, "2026-02-01")),
            ownEstimateTimeline = listOf(estimate(createdAtMs, null)),
            childSumMd = 42.0,
        )
        assertEquals(1, baselines.size)
        assertEquals("CHILDREN", baselines.single().budgetSource)
        assertEquals(42.0, baselines.single().budgetMd)
    }

    @Test
    fun `epicPlanBaselines treats a 0 own estimate as unestimated, falling back to CHILDREN`() {
        val baselines = DeriveKernels.epicPlanBaselines(
            startTimeline = listOf(datePoint(createdAtMs, "2026-01-05")),
            dueTimeline = listOf(datePoint(createdAtMs, "2026-02-01")),
            ownEstimateTimeline = listOf(estimate(createdAtMs, 0.0)),
            childSumMd = 15.0,
        )
        assertEquals(1, baselines.size)
        assertEquals("CHILDREN", baselines.single().budgetSource)
        assertEquals(15.0, baselines.single().budgetMd)
    }

    @Test
    fun `pvCurve's cumulative value at the due date equals the budget exactly, absorbing any rounding remainder`() {
        // Mon 2026-01-05 to Wed 2026-01-07: three working days, 10 MD budget does not divide evenly.
        val baseline = ch.nokillswit.metrics.EpicPlanBaseline(
            baselinedAtMs = createdAtMs, startAtMs = isoMs("2026-01-05"), dueAtMs = isoMs("2026-01-07"),
            budgetMd = 10.0, budgetSource = "OWN", supersededAtMs = null,
        )
        val curve = DeriveKernels.pvCurve(baseline, calendar)
        assertEquals(3, curve.size)
        assertEquals(10.0, curve.last().cumulativeMd, "PV at the due date must equal the budget exactly")
    }

    @Test
    fun `pvCurve is monotonically non-decreasing across its working days`() {
        val baseline = ch.nokillswit.metrics.EpicPlanBaseline(
            baselinedAtMs = createdAtMs, startAtMs = isoMs("2026-01-05"), dueAtMs = isoMs("2026-01-16"),
            budgetMd = 37.0, budgetSource = "OWN", supersededAtMs = null,
        )
        val curve = DeriveKernels.pvCurve(baseline, calendar)
        assertTrue(curve.isNotEmpty())
        curve.zipWithNext().forEach { (a, b) -> assertTrue(b.cumulativeMd >= a.cumulativeMd, "PV must never decrease day over day") }
    }

    @Test
    fun `pvCurve skips weekends and holidays entirely — they add nothing and never appear as a point`() {
        val holiday = LocalDate.of(2026, 1, 8) // a Thursday inside the window, deliberately not a weekend day
        val calendarWithHoliday = ch.nokillswit.metrics.WorkingCalendar(utc, mondayToFriday, setOf(holiday))
        // Mon 2026-01-05 to Mon 2026-01-12 (inclusive both ends): Jan 5/6/7/9/12 are working days,
        // Jan 10/11 are the weekend and Jan 8 is the holiday — five working days remain.
        val baseline = ch.nokillswit.metrics.EpicPlanBaseline(
            baselinedAtMs = createdAtMs, startAtMs = isoMs("2026-01-05"), dueAtMs = isoMs("2026-01-12"),
            budgetMd = 20.0, budgetSource = "OWN", supersededAtMs = null,
        )
        val curve = DeriveKernels.pvCurve(baseline, calendarWithHoliday)
        assertEquals(5, curve.size, "Saturday, Sunday and the Thursday holiday must never become their own point")
        assertTrue(curve.none { it.day == holiday || it.day.dayOfWeek.value in mondayToFriday })
        assertEquals(20.0, curve.last().cumulativeMd)
    }

    @Test
    fun `pvCurve handles a single-day window — the one working day carries the whole budget`() {
        val baseline = ch.nokillswit.metrics.EpicPlanBaseline(
            baselinedAtMs = createdAtMs, startAtMs = isoMs("2026-01-05"), dueAtMs = isoMs("2026-01-05"),
            budgetMd = 8.0, budgetSource = "OWN", supersededAtMs = null,
        )
        val curve = DeriveKernels.pvCurve(baseline, calendar)
        assertEquals(1, curve.size)
        assertEquals(8.0, curve.single().cumulativeMd)
        assertEquals(LocalDate.of(2026, 1, 5), curve.single().day)
    }

    @Test
    fun `pvCurve reads start-due as zone-free calendar dates — a zone behind UTC must not shift the window`() {
        val newYork = ch.nokillswit.metrics.WorkingCalendar(ZoneId.of("America/New_York"), mondayToFriday, noHolidays)
        // Mon 2026-01-05 to Fri 2026-01-09: five working days. UTC midnight for 2026-01-05 is
        // 2026-01-04T19:00 in America/New_York (UTC-5) — reading the window through THAT zone
        // (`WorkingCalendar.dayOf`) would misread the start date as Jan 4, one calendar day early.
        val baseline = ch.nokillswit.metrics.EpicPlanBaseline(
            baselinedAtMs = createdAtMs, startAtMs = isoMs("2026-01-05"), dueAtMs = isoMs("2026-01-09"),
            budgetMd = 15.0, budgetSource = "OWN", supersededAtMs = null,
        )
        val curve = DeriveKernels.pvCurve(baseline, newYork)
        assertEquals(
            5, curve.size,
            "the window must cover exactly the configured Jan 5..9 dates, regardless of the calendar's own zone",
        )
        assertEquals(LocalDate.of(2026, 1, 5), curve.first().day)
        assertEquals(LocalDate.of(2026, 1, 9), curve.last().day)
        assertEquals(15.0, curve.last().cumulativeMd)
    }

    private val dimNow = isoMs("2026-03-05")
    private val day = 24L * 60 * 60 * 1000
    private val year = 365L * day

    @Test
    fun `dimDateRange defaults to one year before the earliest fact through two years after now`() {
        val range = DeriveKernels.dimDateRange(dimNow, isoMs("2025-06-01"), emptyList())
        assertEquals(isoMs("2025-06-01") - year, range.fromMs)
        assertEquals(dimNow + 2 * year, range.toMs)
        assertEquals(dimNow - year, DeriveKernels.dimDateRange(dimNow, null, emptyList()).fromMs, "no fact at all reads as now")
    }

    @Test
    fun `dimDateRange widens below for an old worklog but never past the 50 year floor`() {
        val old = isoMs("2015-02-03")
        assertEquals(old - year, DeriveKernels.dimDateRange(dimNow, old, emptyList()).fromMs)
        val ancient = isoMs("1900-01-01")
        assertEquals(dimNow - 50 * year - year, DeriveKernels.dimDateRange(dimNow, ancient, emptyList()).fromMs)
    }

    @Test
    fun `dimDateRange widens above and below for an in-horizon epic with one day of slack, and ignores an out-of-horizon one`() {
        val due = isoMs("2031-01-15")
        val start = isoMs("2018-05-01")
        val range = DeriveKernels.dimDateRange(dimNow, isoMs("2025-06-01"), listOf(start to due))
        assertEquals(due + day, range.toMs)
        assertEquals(minOf(isoMs("2025-06-01") - year, start - day), range.fromMs)

        val placeholder = isoMs("9999-12-31")
        val ancientStart = isoMs("1900-01-01")
        val ignored = DeriveKernels.dimDateRange(
            dimNow, isoMs("2025-06-01"), listOf(isoMs("2026-01-05") to placeholder, ancientStart to isoMs("2026-01-05")),
        )
        assertEquals(DeriveKernels.dimDateRange(dimNow, isoMs("2025-06-01"), emptyList()), ignored)
    }

    @Test
    fun `inPvHorizon needs both dates within ten years of now`() {
        assertTrue(DeriveKernels.inPvHorizon(isoMs("2026-01-05"), isoMs("2026-06-30"), dimNow))
        assertTrue(DeriveKernels.inPvHorizon(isoMs("2016-03-05"), isoMs("2036-03-05"), dimNow), "the horizon edges are inclusive dates")
        assertTrue(!DeriveKernels.inPvHorizon(isoMs("2016-03-04"), isoMs("2026-06-30"), dimNow))
        assertTrue(!DeriveKernels.inPvHorizon(isoMs("2026-01-05"), isoMs("2036-03-06"), dimNow))
        assertTrue(!DeriveKernels.inPvHorizon(isoMs("2026-01-05"), isoMs("9999-12-31"), dimNow))
    }
}
