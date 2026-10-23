package ch.nokillswit

import ch.nokillswit.ingest.MIN_LIVENESS_THRESHOLD_MS
import ch.nokillswit.ingest.WorkerLiveness
import ch.nokillswit.ingest.livenessThresholdMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The scan-loop liveness tracker (`ingest/WorkerLiveness.kt`, checkup D9-2) under a pinned clock; no database. */
class WorkerLivenessTest {

    private var now = 1_000_000L
    private val liveness = WorkerLiveness({ now }, thresholdMillis = 60_000)

    @Test
    fun `fresh then stale once the last completed tick is older than the threshold`() = runBlocking {
        assertTrue(liveness.isLive(), "the origin is the construction time")
        liveness.completingTick { }
        now += 60_000
        assertTrue(liveness.isLive(), "exactly the threshold is still live")
        now += 1
        assertFalse(liveness.isLive())
        liveness.completingTick { }
        assertTrue(liveness.isLive(), "a completed tick makes it fresh again")
    }

    @Test
    fun `a loop that never completes its first tick goes stale from construction`() {
        now += 61_000
        assertFalse(liveness.isLive())
    }

    @Test
    fun `a failing tick still counts as a completed tick`() = runBlocking {
        now += 50_000
        liveness.completingTick { error("boom") }
        now += 50_000
        assertTrue(liveness.isLive(), "the failure was swallowed and the tick stamped")
        assertEquals(1, liveness.completedTicks.get())
    }

    @Test
    fun `a cancelled tick propagates and does not count`() {
        now += 61_000
        assertFailsWith<CancellationException> { runBlocking { liveness.completingTick { throw CancellationException("stop") } } }
        assertFalse(liveness.isLive())
        assertEquals(0, liveness.completedTicks.get())
    }

    @Test
    fun `the threshold is three ticks with a floor of a minute`() {
        assertEquals(MIN_LIVENESS_THRESHOLD_MS, livenessThresholdMillis(5))
        assertEquals(MIN_LIVENESS_THRESHOLD_MS, livenessThresholdMillis(15), "the default tick: 45 s rounds up to the floor")
        assertEquals(60_000, livenessThresholdMillis(20))
        assertEquals(90_000, livenessThresholdMillis(30))
        assertEquals(900_000, livenessThresholdMillis(300))
    }
}
