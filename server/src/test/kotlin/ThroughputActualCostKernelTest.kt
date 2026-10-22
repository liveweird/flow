package ch.nokillswit

import ch.nokillswit.reports.ActualCostItem
import ch.nokillswit.reports.notDoneBefore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure "not done before the bucket began" item rule of Throughput's actual cost (`ThroughputActualCost.kt`). */
class ThroughputActualCostKernelTest {

    private fun item(done: Long?) = ActualCostItem(1u, 1L, done)

    @Test
    fun `a task counts for a bucket unless it was done before the bucket began`() {
        // A bucket beginning at 100: done exactly then, inside the bucket or after it all count; done a millisecond earlier does not.
        assertTrue(item(100L).notDoneBefore(100))
        assertTrue(item(150L).notDoneBefore(100))
        assertTrue(item(5_000L).notDoneBefore(100), "delivered after the bucket: its earlier logs still count")
        assertFalse(item(99L).notDoneBefore(100), "done before the bucket: whatever is logged afterwards is out")
    }

    @Test
    fun `an open task counts for every bucket, started or not`() {
        // There is no started_at condition: time logged while the task was still To Do counts.
        assertTrue(item(null).notDoneBefore(100))
        assertTrue(item(null).notDoneBefore(Long.MAX_VALUE))
    }
}
