package ch.nokillswit

import ch.nokillswit.ingest.BusyTracker
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ingest/IdleHeapGiveBack.kt`'s [BusyTracker]: the idle hook (production: one explicit `System.gc()`) fires exactly once each time
 * the running-job count returns to zero. Pure — no database, no GC; `IngestWorkerTest` pins the wiring through a real tick.
 */
class BusyTrackerTest {
    @Test
    fun `the hook fires once when the last of several running jobs ends, not before`() {
        val idle = AtomicInteger()
        val tracker = BusyTracker { idle.incrementAndGet() }

        tracker.started()
        tracker.started()
        tracker.started()
        tracker.finished(fire = true)
        tracker.finished(fire = true)
        assertEquals(0, idle.get(), "a job is still running")

        tracker.finished(fire = true)
        assertEquals(1, idle.get(), "the third and last job ended")
    }

    @Test
    fun `every idle transition fires once, a new burst re-arms it`() {
        val idle = AtomicInteger()
        val tracker = BusyTracker { idle.incrementAndGet() }

        repeat(3) { burst ->
            tracker.started()
            tracker.started()
            tracker.finished(fire = true)
            tracker.finished(fire = true)
            assertEquals(burst + 1, idle.get())
        }
    }

    @Test
    fun `a job ending during shutdown fires nothing but still counts down`() {
        val idle = AtomicInteger()
        val tracker = BusyTracker { idle.incrementAndGet() }

        tracker.started()
        tracker.started()
        tracker.finished(fire = false)
        tracker.finished(fire = false)
        assertEquals(0, idle.get(), "a cancelled burst has nothing to give back")

        tracker.started()
        tracker.finished(fire = true)
        assertEquals(1, idle.get(), "the count was back at zero, so the next burst fires")
    }

    @Test
    fun `a failing hook is swallowed and does not break the counting`() {
        val idle = AtomicInteger()
        val tracker = BusyTracker {
            idle.incrementAndGet()
            error("boom")
        }

        tracker.started()
        tracker.finished(fire = true)
        tracker.started()
        tracker.finished(fire = true)
        assertEquals(2, idle.get())
    }
}
