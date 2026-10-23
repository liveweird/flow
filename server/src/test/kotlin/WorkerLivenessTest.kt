package ch.nokillswit

import ch.nokillswit.ingest.MIN_LIVENESS_THRESHOLD
import ch.nokillswit.ingest.WorkerLiveness
import ch.nokillswit.ingest.livenessThreshold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The scan-loop liveness tracker (`ingest/WorkerLiveness.kt`, checkup D9-2) under a pinned monotonic time source; no database. */
class WorkerLivenessTest {

    private val time = TestTimeSource()
    private val liveness = WorkerLiveness(time, 60.seconds)
    private val log = LoggerFactory.getLogger(WorkerLivenessTest::class.java)
    private var leaked = 0

    private suspend fun tick(block: suspend () -> Unit = {}) = liveness.completingTick(log, { leaked }, block)

    @Test
    fun `fresh then stale once the last completed tick is older than the threshold`() = runBlocking {
        assertTrue(liveness.isLive(), "the origin is the construction time")
        tick()
        time += 60.seconds
        assertTrue(liveness.isLive(), "exactly the threshold is still live")
        time += 1.seconds
        assertFalse(liveness.isLive())
        tick()
        assertTrue(liveness.isLive(), "a completed tick makes it fresh again")
    }

    @Test
    fun `a loop that never completes its first tick goes stale from construction`() {
        time += 61.seconds
        assertFalse(liveness.isLive())
    }

    @Test
    fun `a failing tick still counts as a completed tick`() = runBlocking {
        time += 50.seconds
        tick { error("boom") }
        time += 50.seconds
        assertTrue(liveness.isLive(), "the failure was swallowed and the tick stamped")
        assertEquals(1, liveness.completedTicks.get())
    }

    @Test
    fun `a cancelled tick propagates and does not count`() {
        time += 61.seconds
        assertFailsWith<CancellationException> { runBlocking { tick { throw CancellationException("stop") } } }
        assertFalse(liveness.isLive())
        assertEquals(0, liveness.completedTicks.get())
    }

    @Test
    fun `permits held beyond the running jobs fail liveness once they persist past the threshold`() = runBlocking {
        leaked = 1
        repeat(6) {
            tick()
            time += 10.seconds
        }
        assertTrue(liveness.isLive(), "the ticks stamp, and the leak is only 60 s old at most")
        tick()
        time += 1.seconds
        assertFalse(liveness.isLive(), "more than 60 s of unbroken leaking samples, although every tick completed")
        leaked = 0
        tick()
        assertTrue(liveness.isLive(), "a consistent sample clears it (a transient acquire-to-launch window)")
    }

    @Test
    fun `an intermittent leak sample never accumulates`() = runBlocking {
        repeat(20) {
            leaked = it % 2
            tick()
            time += 10.seconds
        }
        assertTrue(liveness.isLive())
    }

    @Test
    fun `the threshold is three ticks with a floor of a minute`() {
        assertEquals(MIN_LIVENESS_THRESHOLD, livenessThreshold(5))
        assertEquals(MIN_LIVENESS_THRESHOLD, livenessThreshold(15), "the default tick: 45 s rounds up to the floor")
        assertEquals(60.seconds, livenessThreshold(20))
        assertEquals(90.seconds, livenessThreshold(30))
        assertEquals(900.seconds, livenessThreshold(300))
    }
}
