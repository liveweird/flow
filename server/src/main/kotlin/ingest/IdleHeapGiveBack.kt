package ch.nokillswit.ingest

import ch.nokillswit.infra.catchingFailures
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts the jobs a worker is running and fires [onIdle] each time that count falls back to zero — the moment a burst of
 * sync/derive jobs is over (`build-times.md` WHY 26). A pair of DERIVEs claimed in one tick fires it once, when the SECOND
 * one ends; a job that ends while another still runs fires nothing. [onIdle] runs on the thread of the job that ended last,
 * and a failure in it is logged, never rethrown (the slot is already released by then).
 */
internal class BusyTracker(private val onIdle: () -> Unit) {
    private val running = AtomicInteger()

    /** Jobs running right now (`IngestWorker.leakedPermits` compares it with the permits held). */
    fun runningJobs(): Int = running.get()

    fun started() {
        running.incrementAndGet()
    }

    /** One job ended. [fire] is false while the worker is shutting down: a cancelled burst has nothing to give back. */
    fun finished(fire: Boolean) {
        if (running.decrementAndGet() == 0 && fire) {
            catchingFailures({ onIdle() }) { log.warn("Idle hook after the last running job failed", it) }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(BusyTracker::class.java)
    }
}

/**
 * The production [BusyTracker] hook: one explicit full collection once the worker is idle. A DERIVE burst promotes its
 * medium-lived garbage into the old generation and the heap, once grown, stays committed (SerialGC shrinks it only at a
 * full collection, and only in steps unless `-XX:-ShrinkHeapInSteps`, `server/build.gradle.kts`); collecting while nothing runs costs
 * ~50 ms and hands the slack back (scale 20, `build-times.md` WHY 26: anon 398 -> ~318 MiB within one 3 s sample of a
 * pair ending, ~690 -> ~325 MiB 100 s after it against the baseline that never shrinks). The ONE call site
 * of `System.gc()` — `ExplicitGarbageCollectionCall` is excluded for this file only (`config/detekt/detekt.yml`); never run the
 * JVM with `-XX:+DisableExplicitGC`, it would turn this into a no-op.
 */
internal fun giveHeapBackWhenIdle() {
    System.gc()
}
