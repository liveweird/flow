package ch.nokillswit.ingest

import ch.nokillswit.plugins.ReadinessProbe
import ch.nokillswit.plugins.WorkerLivenessKey
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/** The floor of [livenessThresholdMillis]: a short scheduler tick (15 s by default) still gets a minute of slack. */
internal const val MIN_LIVENESS_THRESHOLD_MS = 60_000L

/** How many scheduler ticks of silence the scan loop is allowed before `/api/v1/ready` calls the worker stalled. */
private const val LIVENESS_TICKS = 3L

/** `max(3 x tick, 60 s)`: a tick that only enqueues and claims takes milliseconds, so three missed ones mean a dead or stuck loop. */
internal fun livenessThresholdMillis(schedulerTickSeconds: Long): Long =
    maxOf(LIVENESS_TICKS * schedulerTickSeconds * 1000, MIN_LIVENESS_THRESHOLD_MS)

/**
 * Whether the scan loop is alive, for `/api/v1/ready` (checkup D9-2): the age of the last COMPLETED tick — one that
 * returned or failed and was caught, never a cancelled one — against [thresholdMillis]. A dead tick coroutine, a hung
 * tick (a database call that never returns) and a loop whose slot permits leaked all stop the stamps; a tick that merely
 * fails keeps stamping (the loop is alive, its error is in the log and the failing job's own row), so a flaky Jira does
 * not take the pod out of the Service. The origin is the construction time: a loop that never completes its first tick
 * goes stale too. [clock] is the worker's own (injectable) clock, `IngestWorkerTest`/`WorkerLivenessTest` pin it.
 */
class WorkerLiveness internal constructor(private val clock: () -> Long, private val thresholdMillis: Long) {
    private val lastTickMillis = AtomicLong(clock())

    private companion object {
        val log = LoggerFactory.getLogger(WorkerLiveness::class.java)
    }

    /**
     * Runs one scan-loop tick and stamps it completed. A failure is logged and swallowed — the loop goes on — and still
     * stamps; a cancellation propagates unstamped (the loop is being shut down).
     */
    suspend fun completingTick(tick: suspend () -> Unit) {
        try {
            tick()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            log.warn("Ingest scheduler tick failed", e)
        }
        lastTickMillis.set(clock())
        completedTicks.incrementAndGet()
    }

    /** How many ticks have completed so far (a test awaits the first one before moving its clock). */
    internal val completedTicks = AtomicLong()

    /** False once the last completed tick is older than the threshold. */
    fun isLive(): Boolean = clock() - lastTickMillis.get() <= thresholdMillis
}

/** The running worker's tracker itself — `WorkerReadinessTest`'s seam to await the first completed tick. */
internal val WorkerLivenessTrackerKey = AttributeKey<WorkerLiveness>("WorkerLivenessTracker")

/** Hands [liveness] to `/api/v1/ready` (plugins/Health.kt); called once the scan loop has started. */
internal fun Application.publishLiveness(liveness: WorkerLiveness) {
    attributes.put(WorkerLivenessTrackerKey, liveness)
    attributes.put(WorkerLivenessKey, ReadinessProbe { liveness.isLive() })
}
