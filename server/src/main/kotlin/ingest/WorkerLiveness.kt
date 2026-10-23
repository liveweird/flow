package ch.nokillswit.ingest

import ch.nokillswit.plugins.ReadinessProbe
import ch.nokillswit.plugins.WorkerLivenessKey
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The floor of [livenessThreshold]: a short scheduler tick (15 s by default) still gets a minute of slack. */
internal val MIN_LIVENESS_THRESHOLD = 60.seconds

/** How many scheduler ticks of silence the scan loop is allowed before `/api/v1/ready` calls the worker stalled. */
private const val LIVENESS_TICKS = 3L

/** `max(3 x tick, 60 s)`: a tick that only enqueues and claims takes milliseconds, so three missed ones mean a dead or stuck loop. */
internal fun livenessThreshold(schedulerTickSeconds: Long): Duration =
    maxOf((LIVENESS_TICKS * schedulerTickSeconds).seconds, MIN_LIVENESS_THRESHOLD)

/**
 * Whether the scan loop is alive, for `/api/v1/ready` (checkup D9-2). Two signals, both aged on a MONOTONIC [timeSource]
 * (a host suspend or an NTP step must not read as a stall; `TimeSource.Monotonic` in production, a `TestTimeSource` in tests):
 *
 * - **Ticks.** The age of the last COMPLETED tick — one that returned or failed and was caught, never a cancelled one —
 *   against [threshold]. A dead tick coroutine and a hung tick (a database call that never returns) stop the stamps; a tick
 *   that merely fails keeps stamping (the loop is alive, its error is in the log and the failing job's own row), so a flaky
 *   Jira does not take the pod out of the Service. The origin is the construction time: a loop that never completes its
 *   first tick goes stale too.
 * - **Leaked permits.** A tick that finds MORE slot permits held than jobs running ([completingTick]'s `leakedPermits`)
 *   marks the accounting inconsistent; it must stay so, at every tick, for longer than [threshold] (the brief window between
 *   a permit's acquire and its job's start, or its release and the job's end, never persists across ticks). A leaked permit
 *   does not stop the stamps — `tryAcquire` never suspends — it just shrinks the worker's capacity for good, so this is the
 *   only signal for it (D9-1's class).
 */
class WorkerLiveness internal constructor(private val timeSource: TimeSource, private val threshold: Duration) {
    private val lastTick = AtomicReference(timeSource.markNow())
    private val leakingSince = AtomicReference<TimeMark?>(null)

    /** How many ticks have completed so far (a test awaits the first one before moving its time source). */
    internal val completedTicks = AtomicLong()

    /**
     * Runs one scan-loop tick and stamps it completed. A failure is logged to [log] and swallowed — the loop goes on — and
     * still stamps; a cancellation propagates unstamped (the loop is being shut down). Afterwards [leakedPermits] (permits
     * held beyond the running jobs) is sampled: positive starts or keeps the leak clock, zero clears it.
     */
    suspend fun completingTick(log: Logger, leakedPermits: () -> Int, tick: suspend () -> Unit) {
        try {
            tick()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            log.warn("Ingest scheduler tick failed", e)
        }
        lastTick.set(timeSource.markNow())
        leakingSince.updateAndGet { since -> if (leakedPermits() > 0) since ?: timeSource.markNow() else null }
        completedTicks.incrementAndGet()
    }

    /** False once the last completed tick, or an unbroken run of leaking samples, is older than the threshold. */
    fun isLive(): Boolean = lastTick.get().elapsedNow() <= threshold && (leakingSince.get()?.elapsedNow() ?: Duration.ZERO) <= threshold
}

/** Test seam for the worker's monotonic time source (`WorkerReadinessTest`) — set BEFORE `startApplication()`, like [IngestClockKey]. */
internal val IngestTimeSourceKey = AttributeKey<TimeSource>("IngestTimeSource")

/** The running worker's tracker itself — `WorkerReadinessTest`'s seam to await the first completed tick. */
internal val WorkerLivenessTrackerKey = AttributeKey<WorkerLiveness>("WorkerLivenessTracker")

/** Hands [liveness] to `/api/v1/ready` (plugins/Health.kt); called once the scan loop has started. */
internal fun Application.publishLiveness(liveness: WorkerLiveness) {
    attributes.put(WorkerLivenessTrackerKey, liveness)
    attributes.put(WorkerLivenessKey, ReadinessProbe { liveness.isLive() })
}
