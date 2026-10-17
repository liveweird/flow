package ch.nokillswit

import ch.nokillswit.metrics.EstimatePoint
import ch.nokillswit.metrics.estimateSnapshots
import ch.nokillswit.metrics.mergeEstimateTimelines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The estimate-adjustment rule (domain-model A35): a change counts as an adjustment only after `started_at` and at or before
 * `done_at`; an estimate that first appears after `done_at` is a backfill (`estimatedAfterDone`), not "estimated late". Pure -- hand-built
 * timelines, started at t=100 and (where done) done at t=200.
 */
class EstimateSnapshotsTest {
    private val started = 100L
    private val done = 200L

    private fun timeline(vararg points: Pair<Long, Double?>) = points.map { (at, md) -> EstimatePoint(at, md) }

    @Test
    fun `a change after done is not counted and the estimate at done stays`() {
        // 5 until a week after done, then 8: a correction after closing.
        val snapshots = estimateSnapshots(timeline(0L to 5.0, 250L to 8.0), started, done)
        assertEquals(5.0, snapshots.atStartMd)
        assertEquals(5.0, snapshots.atDoneMd)
        assertEquals(8.0, snapshots.currentMd)
        assertEquals(0, snapshots.changesAfterStart)
        assertFalse(snapshots.estimatedLate)
        assertFalse(snapshots.estimatedAfterDone)
    }

    @Test
    fun `a change exactly at done_at is counted, one exactly at started_at is not`() {
        val atDone = estimateSnapshots(timeline(0L to 5.0, done to 8.0), started, done)
        assertEquals(1, atDone.changesAfterStart, "done_at is inclusive")
        assertEquals(8.0, atDone.atDoneMd)

        val atStart = estimateSnapshots(timeline(0L to null, started to 5.0), started, done)
        assertEquals(0, atStart.changesAfterStart, "started_at is exclusive: the value at start is the at-start snapshot")
        assertEquals(5.0, atStart.atStartMd)
        assertFalse(atStart.estimatedLate)
    }

    @Test
    fun `an estimate first set after done is a backfill, not estimated late`() {
        val snapshots = estimateSnapshots(timeline(0L to null, 300L to 5.0), started, done)
        assertNull(snapshots.atStartMd)
        assertNull(snapshots.atDoneMd)
        assertEquals(5.0, snapshots.currentMd)
        assertFalse(snapshots.estimatedLate)
        assertTrue(snapshots.estimatedAfterDone)
        assertEquals(0, snapshots.changesAfterStart)
    }

    @Test
    fun `an estimate set between start and done is estimated late and a change`() {
        val snapshots = estimateSnapshots(timeline(0L to null, 150L to 5.0), started, done)
        assertNull(snapshots.atStartMd)
        assertEquals(5.0, snapshots.atDoneMd)
        assertTrue(snapshots.estimatedLate)
        assertFalse(snapshots.estimatedAfterDone)
        assertEquals(1, snapshots.changesAfterStart)
    }

    @Test
    fun `set late, then corrected after done, is still estimated late with one adjustment`() {
        val snapshots = estimateSnapshots(timeline(0L to null, 150L to 5.0, 300L to 8.0), started, done)
        assertTrue(snapshots.estimatedLate)
        assertFalse(snapshots.estimatedAfterDone)
        assertEquals(1, snapshots.changesAfterStart, "the correction after done is not an adjustment")
        assertEquals(5.0, snapshots.atDoneMd)
        assertEquals(8.0, snapshots.currentMd)
    }

    @Test
    fun `an open item behaves as before, with no upper bound`() {
        val late = estimateSnapshots(timeline(0L to null, 150L to 5.0, 400L to 6.0), started, null)
        assertTrue(late.estimatedLate)
        assertFalse(late.estimatedAfterDone)
        assertEquals(2, late.changesAfterStart)
        assertNull(late.atDoneMd)

        val unestimated = estimateSnapshots(timeline(0L to null), started, null)
        assertFalse(unestimated.estimatedLate)
        assertFalse(unestimated.estimatedAfterDone, "open is never after done")
        assertEquals(0, unestimated.changesAfterStart)
    }

    @Test
    fun `a reopened item uses the final done_at, so changes made while it was reopened count`() {
        // First done at 200, reopened, final done at 500: the change at 300 happened while reopened, before the FINAL done.
        val snapshots = estimateSnapshots(timeline(0L to 5.0, 300L to 8.0), started, 500L)
        assertEquals(1, snapshots.changesAfterStart)
        assertEquals(8.0, snapshots.atDoneMd)
        // The same timeline against the first done (200) would call it a post-done correction.
        assertEquals(0, estimateSnapshots(timeline(0L to 5.0, 300L to 8.0), started, done).changesAfterStart)
    }

    @Test
    fun `an item that never started has no changes after start and no estimated late, but a backfill is still flagged`() {
        val neverStarted = estimateSnapshots(timeline(0L to null, 300L to 5.0), null, done)
        assertEquals(0, neverStarted.changesAfterStart)
        assertFalse(neverStarted.estimatedLate)
        assertTrue(neverStarted.estimatedAfterDone)

        val open = estimateSnapshots(timeline(0L to null, 300L to 5.0), null, null)
        assertFalse(open.estimatedLate)
        assertFalse(open.estimatedAfterDone)
    }

    @Test
    fun `a sub-task created mid-work and estimated only after done is a backfill, not a change`() {
        // Sub-task A is created at 150 (while the parent is worked on) with no estimate and gets 3 at 300, after the parent's done.
        val merged = mergeEstimateTimelines(listOf(timeline(150L to null, 300L to 3.0)))
        assertEquals(listOf<Pair<Long, Double?>>(300L to 3.0), merged.map { it.atMs to it.estimateMd })
        val snapshots = estimateSnapshots(merged, started, done)
        assertEquals(0, snapshots.changesAfterStart, "the creation point leaves the sum unchanged")
        assertTrue(snapshots.estimatedAfterDone)
        assertFalse(snapshots.estimatedLate)

        // A second sub-task created mid-work with no estimate adds no point of its own either.
        val two = mergeEstimateTimelines(listOf(timeline(0L to 2.0), timeline(150L to null)))
        assertEquals(listOf(0L to 2.0), two.map { it.atMs to it.estimateMd })
        assertEquals(0, estimateSnapshots(two, started, done).changesAfterStart)
    }

    @Test
    fun `a sub-task roll-up whose estimates arrive after the parent's done is treated as after done`() {
        val subtaskA = timeline(0L to null, 300L to 3.0)
        val subtaskB = timeline(0L to null, 350L to 2.0)
        val merged = mergeEstimateTimelines(listOf(subtaskA, subtaskB))
        // The roll-up is read against the PARENT's started/done, like DeriveEstimateComposite.taskEstimate does.
        val snapshots = estimateSnapshots(merged, started, done)
        assertNull(snapshots.atStartMd)
        assertNull(snapshots.atDoneMd)
        assertEquals(5.0, snapshots.currentMd)
        assertTrue(snapshots.estimatedAfterDone)
        assertFalse(snapshots.estimatedLate)
        assertEquals(0, snapshots.changesAfterStart)

        // One sub-task estimated while the parent was worked on: the roll-up is estimated late.
        val mixed = estimateSnapshots(mergeEstimateTimelines(listOf(timeline(0L to null, 150L to 3.0), subtaskB)), started, done)
        assertTrue(mixed.estimatedLate)
        assertFalse(mixed.estimatedAfterDone)
        assertEquals(1, mixed.changesAfterStart)
        assertEquals(3.0, mixed.atDoneMd)
    }
}
