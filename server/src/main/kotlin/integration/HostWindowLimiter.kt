package ch.nokillswit.integration

/**
 * The per-host budget of the `/api/v1/integration` namespace, spent BEFORE the digest lookup: at most [limit] attempts per host per
 * [windowMillis] (a fixed window), valid or not. Ktor's `RateLimit` plugin cannot do this job - its interceptors run after the
 * authentication phase and a refused call never reaches them - so the service-token provider consults this instead.
 *
 * In-memory and per-instance (the deployment is single-replica, like the login throttle it follows); a restart only resets the budgets.
 * Capacity-bounded like `auth/LoginThrottle.kt`: at most [maxTracked] hosts are tracked, expired windows are reclaimed first, and when
 * none can be a NEW host is refused ([Verdict.Refused.saturated]) rather than the map growing; a tracked host carries on. The map
 * is ordered by window start (a refilled host is re-inserted), so reclaiming is a pop from the head. A clock that steps back can break
 * that order, so a tracked host whose OWN window has expired is also treated as fresh (re-inserted at the tail) wherever it sits.
 */
internal class HostWindowLimiter(
    private val limit: Int,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxTracked: Int = DEFAULT_MAX_TRACKED,
    /** Milliseconds on a MONOTONIC clock: a wall-clock step (NTP, a VM resume) must not shrink or stretch a host's window. */
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) {
    init {
        require(limit > 0 && windowMillis > 0 && maxTracked > 0) { "limit, windowMillis and maxTracked must be positive" }
    }

    sealed interface Verdict {
        data object Allowed : Verdict

        /** Over the budget (or no room for a new host): retry after [retryAfterMillis], when the window that blocks it ends. */
        data class Refused(val retryAfterMillis: Long, val saturated: Boolean) : Verdict
    }

    private class Window(val startMs: Long, var count: Int)

    private val windows = LinkedHashMap<String, Window>()
    private val lock = Any()

    fun tryAcquire(host: String): Verdict = synchronized(lock) {
        val now = clock()
        reclaimExpired(now)
        // A tracked host whose own window ended yet survived head reclaim (a clock step-back broke the order): fresh window, at the tail.
        val tracked = windows[host]
        val current = tracked?.takeIf { it.startMs + windowMillis > now }
        if (tracked != null && current == null) windows.remove(host)
        when {
            current == null && windows.size >= maxTracked ->
                Verdict.Refused(retryAfterMillis = windows.values.first().startMs + windowMillis - now, saturated = true)
            current == null -> Verdict.Allowed.also { windows[host] = Window(now, 1) }
            current.count < limit -> Verdict.Allowed.also { current.count++ }
            else -> Verdict.Refused(retryAfterMillis = current.startMs + windowMillis - now, saturated = false)
        }
    }

    private fun reclaimExpired(now: Long) {
        val iterator = windows.values.iterator()
        while (iterator.hasNext() && iterator.next().startMs + windowMillis <= now) iterator.remove()
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Hosts tracked at once: far above the clients of one unit, small enough that the map is a few hundred KB at most. */
        const val DEFAULT_MAX_TRACKED = 10_000
    }
}
