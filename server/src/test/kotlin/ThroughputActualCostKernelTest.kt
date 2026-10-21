package ch.nokillswit

import ch.nokillswit.reports.ActualCostItem
import ch.nokillswit.reports.activeIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure "delivered inside or in progress during `[lo, hi)`" rule of Throughput's actual cost (`ThroughputActualCost.kt`). */
class ThroughputActualCostKernelTest {

    private fun item(started: Long?, done: Long?) = ActualCostItem(1u, 1L, started, done)

    @Test
    fun `a task is active when delivered inside the interval or in progress during it`() {
        // [100, 200): delivered inside (the lower bound is in, the upper bound is out).
        assertTrue(item(10L, 100L).activeIn(100, 200))
        assertTrue(item(10L, 199L).activeIn(100, 200))
        assertFalse(item(10L, 99L).activeIn(100, 200), "done before the interval")
        // In progress across the whole interval, an open task, one that starts inside it.
        assertTrue(item(10L, 300L).activeIn(100, 200))
        assertTrue(item(10L, null).activeIn(100, 200))
        assertTrue(item(150L, null).activeIn(100, 200))
        assertFalse(item(200L, null).activeIn(100, 200), "started when the interval ended")
        assertFalse(item(250L, 300L).activeIn(100, 200), "starts after the interval")
    }

    @Test
    fun `a task that never started counts only when delivered inside the interval`() {
        assertFalse(item(null, null).activeIn(100, 200))
        assertFalse(item(null, 250L).activeIn(100, 200))
        assertTrue(item(null, 150L).activeIn(100, 200), "created straight into DONE")
        assertEquals(false, item(null, 99L).activeIn(100, 200))
    }
}
