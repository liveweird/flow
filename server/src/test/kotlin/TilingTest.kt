package ch.nokillswit

import ch.nokillswit.norm.FieldChangeEvent
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.StatusChangeEvent
import ch.nokillswit.norm.StatusInterval
import ch.nokillswit.norm.Tiling
import ch.nokillswit.norm.TilingAnomaly
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `norm/Tiling.kt` (v0.2.0 plan §8/§11): pure, so every case here is plain data in, plain data out —
 * no DB, no coroutines. Property-style coverage generates random (but chain-consistent) event
 * sequences and re-checks the plan's own invariant list on every one of them, plus a dedicated case
 * per anomaly and the two named edge cases (empty changelog, same-millisecond transitions).
 */
class TilingTest {

    private val statusIds = listOf("1", "3", "10001", "10002", "10003")

    /** A random walk of chain-consistent events (`events[i].fromStatusId == events[i-1].toStatusId`), strictly increasing times. */
    private fun randomChain(random: Random, createdAtMs: Long, length: Int): Pair<String, List<StatusChangeEvent>> {
        val initial = statusIds[random.nextInt(statusIds.size)]
        var current = initial
        var t = createdAtMs
        val events = (0 until length).map {
            t += random.nextLong(0, 1000)
            val next = statusIds.filterNot { it == current }.random(random)
            val event = StatusChangeEvent(t, current, next)
            current = next
            event
        }
        return current to events
    }

    private fun assertInvariants(createdAtMs: Long, intervals: List<StatusInterval>) {
        assertEquals(intervals.indices.map { it + 1 }, intervals.map { it.seq }, "intervals must be sorted by seq")
        assertEquals(createdAtMs, intervals.first().fromAtMs, "the first interval must start at created_at")
        intervals.zipWithNext().forEach { (a, b) ->
            assertEquals(a.toAtMs, b.fromAtMs, "each interval's to_at must equal the next interval's from_at")
        }
        assertEquals(1, intervals.count { it.toAtMs == null }, "exactly one interval must be open")
        assertNull(intervals.last().toAtMs, "the open interval must be the LAST one")
        assertEquals(IntervalSource.CREATED, intervals.first().source, "the first interval's source must be CREATED")
        intervals.drop(1).forEach { assertEquals(IntervalSource.CHANGE, it.source) }
    }

    @Test
    fun `property - random chain-consistent event sequences always satisfy the interval invariants`() {
        val random = Random(42)
        repeat(200) {
            val createdAtMs = random.nextLong(0, 1_000_000)
            val length = random.nextInt(0, 12)
            val (current, events) = randomChain(random, createdAtMs, length)
            val result = Tiling.statusIntervals(createdAtMs, current, events)
            assertInvariants(createdAtMs, result.intervals)
            assertEquals(events.size + 1, result.intervals.size, "one interval per event plus the initial one")
            // A chain-consistent, in-order walk that ends at [current] must never anomaly.
            assertEquals(emptyList(), result.anomalies)
        }
    }

    @Test
    fun `property - tiling is idempotent (a pure function called twice on the same input agrees)`() {
        val random = Random(7)
        repeat(20) {
            val createdAtMs = random.nextLong(0, 1_000_000)
            val (current, events) = randomChain(random, createdAtMs, random.nextInt(1, 8))
            val first = Tiling.statusIntervals(createdAtMs, current, events)
            val second = Tiling.statusIntervals(createdAtMs, current, events)
            assertEquals(first, second)
        }
    }

    @Test
    fun `empty changelog yields a single open interval at the current status`() {
        val result = Tiling.statusIntervals(createdAtMs = 1_000L, currentStatusId = "1", events = emptyList())
        assertEquals(
            listOf(StatusInterval(1, "1", 1_000L, null, IntervalSource.CREATED)),
            result.intervals,
        )
        assertEquals(emptyList(), result.anomalies)
    }

    @Test
    fun `same-millisecond transitions produce zero-length intervals without violating invariants`() {
        val createdAtMs = 1_000L
        val events = listOf(
            StatusChangeEvent(1_000L, "1", "3"),
            StatusChangeEvent(1_000L, "3", "10001"),
            StatusChangeEvent(2_000L, "10001", "10002"),
        )
        val result = Tiling.statusIntervals(createdAtMs, "10002", events)
        assertInvariants(createdAtMs, result.intervals)
        assertEquals(emptyList(), result.anomalies)
        assertEquals(1_000L, result.intervals[0].toAtMs)
        assertEquals(1_000L, result.intervals[1].fromAtMs)
        assertEquals(1_000L, result.intervals[1].toAtMs, "a same-millisecond transition is a zero-length interval, not skipped")
    }

    @Test
    fun `STATUS_CHANGE_BEFORE_CREATED - the first event predating created_at is clamped, not dropped`() {
        val createdAtMs = 5_000L
        val events = listOf(StatusChangeEvent(1_000L, "1", "3"))
        val result = Tiling.statusIntervals(createdAtMs, "3", events)
        assertEquals(listOf(TilingAnomaly.STATUS_CHANGE_BEFORE_CREATED), result.anomalies)
        assertInvariants(createdAtMs, result.intervals)
        assertEquals(createdAtMs, result.intervals[0].toAtMs, "clamped to created_at, producing a zero-length first interval")
    }

    @Test
    fun `STATUS_CHAIN_BROKEN - a from that disagrees with the previous to is flagged, trusting the to chain`() {
        val createdAtMs = 1_000L
        val events = listOf(
            StatusChangeEvent(2_000L, "1", "3"),
            // fromStatusId "10001" disagrees with the previous event's toStatusId "3".
            StatusChangeEvent(3_000L, "10001", "10002"),
        )
        val result = Tiling.statusIntervals(createdAtMs, "10002", events)
        assertEquals(listOf(TilingAnomaly.STATUS_CHAIN_BROKEN), result.anomalies)
        assertInvariants(createdAtMs, result.intervals)
        // The chain is trusted over the disagreeing "from": interval seq 2 is "3" (the previous TO), never "10001".
        assertEquals("3", result.intervals[1].statusId)
    }

    @Test
    fun `STATUS_MISMATCH_WITH_CURRENT - a last computed status disagreeing with the issue's current status is flagged`() {
        val createdAtMs = 1_000L
        val events = listOf(StatusChangeEvent(2_000L, "1", "3"))
        val result = Tiling.statusIntervals(createdAtMs, currentStatusId = "10002", events)
        assertEquals(listOf(TilingAnomaly.STATUS_MISMATCH_WITH_CURRENT), result.anomalies)
        assertInvariants(createdAtMs, result.intervals)
        assertEquals("3", result.intervals.last().statusId, "the stored value is what the chain computed, never the current status")
    }

    @Test
    fun `multiple anomalies on the same issue are all reported, not just the first`() {
        val createdAtMs = 5_000L
        val events = listOf(
            StatusChangeEvent(1_000L, "1", "3"), // before created_at
            StatusChangeEvent(6_000L, "10001", "10002"), // chain broken vs "3"
        )
        val result = Tiling.statusIntervals(createdAtMs, currentStatusId = "1", events) // also mismatches "10002"
        assertEquals(
            listOf(
                TilingAnomaly.STATUS_CHANGE_BEFORE_CREATED,
                TilingAnomaly.STATUS_CHAIN_BROKEN,
                TilingAnomaly.STATUS_MISMATCH_WITH_CURRENT,
            ),
            result.anomalies,
        )
        assertInvariants(createdAtMs, result.intervals)
    }

    @Test
    fun `field intervals - no events yields a single open interval at the current value`() {
        val intervals = Tiling.fieldIntervals(1_000L, currentValueId = "acc-1", currentValueText = "Ann", events = emptyList())
        assertEquals(1, intervals.size)
        assertEquals(1_000L, intervals[0].fromAtMs)
        assertNull(intervals[0].toAtMs)
        assertEquals("acc-1", intervals[0].valueId)
    }

    @Test
    fun `field intervals - assignee reassignment chain produces contiguous intervals with exactly one open`() {
        val createdAtMs = 1_000L
        val events = listOf(
            FieldChangeEvent(2_000L, null, null, "acc-1", "Ann"),
            FieldChangeEvent(3_000L, "acc-1", "Ann", "acc-2", "Bo"),
        )
        val intervals = Tiling.fieldIntervals(createdAtMs, currentValueId = "acc-2", currentValueText = "Bo", events = events)
        assertEquals(3, intervals.size)
        assertEquals(createdAtMs, intervals.first().fromAtMs)
        assertNull(intervals.first().valueId, "unassigned before the first assignment")
        intervals.zipWithNext().forEach { (a, b) -> assertEquals(a.toAtMs, b.fromAtMs) }
        assertEquals(1, intervals.count { it.toAtMs == null })
        assertEquals("acc-2", intervals.last().valueId)
    }

    @Test
    fun `field intervals - a null value id (unassigned or unflagged) is preserved, not coerced`() {
        val intervals = Tiling.fieldIntervals(1_000L, currentValueId = null, currentValueText = null, events = emptyList())
        assertNull(intervals.single().valueId)
        assertNull(intervals.single().valueText)
    }

    @Test
    fun `field intervals - out-of-order event times are clamped monotonically, never producing a negative-length interval`() {
        val createdAtMs = 1_000L
        val events = listOf(
            FieldChangeEvent(500L, null, null, "true", null), // predates createdAtMs
            FieldChangeEvent(400L, "true", null, "false", null), // predates the previous boundary too
        )
        val intervals = Tiling.fieldIntervals(createdAtMs, currentValueId = "false", currentValueText = null, events = events)
        intervals.zipWithNext().forEach { (a, b) ->
            assertTrue((b.fromAtMs ?: 0) >= (a.fromAtMs), "boundaries must never move backward")
        }
        assertEquals(createdAtMs, intervals[0].fromAtMs)
    }
}
