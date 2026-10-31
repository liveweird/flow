package ch.nokillswit.fetch

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What Jira's rate limiting cost ONE sync job (`.claude/docs/jira-integration.md` "Rate limits and backoff"): a coroutine-context
 * element, so [SourceHttp] finds it with no parameter threaded through the clients and the streams. `JiraConnector` wraps a run in
 * `withContext(tally)`; the streams' fetch-ahead children (`async` inherits the context) count into the SAME tally, and
 * `StreamContext.extraProgress` reads [snapshot] into `sync_jobs.progress` on every heartbeat. A call outside any tally (the
 * Test-connection probes, direct callers) counts nothing.
 *
 * The four counters are the telemetry panel's "Jira throttling" figures: how many responses were a 429, how long calls slept in
 * their own back-off, how long they waited for a tenant pause another call set, and how many [RateLimitSignal] pauses (Jira:
 * `X-RateLimit-NearLimit`) were taken. Times are the waits REQUESTED of the sleeper
 * (summed over concurrent calls, so they can exceed the job's wall time).
 */
class CallTally(carried: Map<String, Long> = emptyMap()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CallTally>

    // [carried]: what the job's previous attempt flushed, so a retried attempt's counters ADD to it instead of replacing it.
    private val rateLimited = AtomicLong(carried[RATE_LIMITED_KEY] ?: 0L)
    private val backoffMillis = AtomicLong(carried[BACKOFF_MS_KEY] ?: 0L)
    private val tenantPausedMillis = AtomicLong(carried[TENANT_PAUSED_MS_KEY] ?: 0L)
    private val signalPauses = AtomicLong(carried[NEAR_LIMIT_PAUSES_KEY] ?: 0L)

    internal fun countRateLimited() {
        rateLimited.incrementAndGet()
    }

    internal fun addBackoff(millis: Long) {
        backoffMillis.addAndGet(millis)
    }

    internal fun addTenantPause(millis: Long) {
        tenantPausedMillis.addAndGet(millis)
    }

    internal fun countSignalPause() {
        signalPauses.incrementAndGet()
    }

    /** The NON-ZERO counters under their `sync_jobs.progress` keys (the `issuesSkipped` convention: absent means zero). */
    fun snapshot(): Map<String, Long> = buildMap {
        put(RATE_LIMITED_KEY, rateLimited.get())
        put(BACKOFF_MS_KEY, backoffMillis.get())
        put(TENANT_PAUSED_MS_KEY, tenantPausedMillis.get())
        put(NEAR_LIMIT_PAUSES_KEY, signalPauses.get())
    }.filterValues { it > 0L }
}

/** The four tally keys, which a retried attempt carries over (`jira/JiraConnector.kt` adds the ISSUES stream's own marker). */
internal val CALL_TALLY_KEYS = setOf(RATE_LIMITED_KEY, BACKOFF_MS_KEY, TENANT_PAUSED_MS_KEY, NEAR_LIMIT_PAUSES_KEY)

/**
 * The `sync_jobs.progress` keys of [CallTally.snapshot]. Persisted and read by the web's telemetry panel: the values keep their
 * `jira` prefix (the one connector so far) and must not change without a migration of stored progress.
 */
const val RATE_LIMITED_KEY = "jiraRateLimited"
const val BACKOFF_MS_KEY = "jiraBackoffMs"
const val TENANT_PAUSED_MS_KEY = "jiraTenantPausedMs"
const val NEAR_LIMIT_PAUSES_KEY = "jiraNearLimitPauses"
