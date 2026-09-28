package ch.nokillswit

import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.ItemStage
import ch.nokillswit.norm.FieldChangeRow
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TrackedField
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

        // [5,15) (flagged) and [10,20) (blocked status) overlap and must merge into one [5,20) interval.
        assertEquals(1, result.size)
        assertEquals(5, result.single().fromAtMs)
        assertEquals(20, result.single().toAtMs)
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
}
