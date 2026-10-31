package ch.nokillswit.fetch

/**
 * Pure backoff math (v0.2.0 plan §6): `min(30s, 2s·2^n) × U(0.7,1.3)`, honouring a `Retry-After`
 * header (clamped to 120s) when the upstream sends one. [random] is injectable (`JiraBackoffTest`)
 * so jitter bounds can be pinned exactly instead of asserted with float tolerance games.
 */
object Backoff {
    private const val BASE_DELAY_MS = 2_000L
    private const val MAX_DELAY_MS = 30_000L
    private const val MAX_RETRY_AFTER_SECONDS = 120L
    private const val JITTER_FLOOR = 0.7
    private const val JITTER_SPAN = 0.6

    fun delayMillis(attempt: Int, retryAfterSeconds: Long?, random: () -> Double = Math::random): Long {
        if (retryAfterSeconds != null) return retryAfterSeconds.coerceIn(0, MAX_RETRY_AFTER_SECONDS) * 1_000L
        val exponential = minOf(MAX_DELAY_MS, BASE_DELAY_MS * (1L shl attempt.coerceIn(0, 20)))
        val jitter = JITTER_FLOOR + random() * JITTER_SPAN
        return (exponential * jitter).toLong()
    }
}
