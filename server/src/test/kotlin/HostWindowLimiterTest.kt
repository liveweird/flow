package ch.nokillswit

import ch.nokillswit.integration.HostWindowLimiter
import ch.nokillswit.integration.HostWindowLimiter.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The pre-lookup per-host budget of the integration namespace (`integration/HostWindowLimiter.kt`): fixed window, capacity-bounded. */
class HostWindowLimiterTest {

    @Test
    fun `a host gets limit attempts per window, then a retry-after that shrinks, then a fresh window`() {
        var now = 1_000L
        val limiter = HostWindowLimiter(limit = 2, windowMillis = 60_000, clock = { now })
        assertEquals(Verdict.Allowed, limiter.tryAcquire("a"))
        now += 10_000
        assertEquals(Verdict.Allowed, limiter.tryAcquire("a"))
        now += 10_000
        val refused = assertIs<Verdict.Refused>(limiter.tryAcquire("a"))
        assertEquals(40_000, refused.retryAfterMillis)
        assertEquals(false, refused.saturated)
        assertEquals(Verdict.Allowed, limiter.tryAcquire("b"), "another host is independent")
        now += 40_000
        assertEquals(Verdict.Allowed, limiter.tryAcquire("a"), "the window ended")
    }

    @Test
    fun `a full map refuses a new host but lets a tracked one carry on, and expiry reclaims room`() {
        var now = 0L
        val limiter = HostWindowLimiter(limit = 5, windowMillis = 1_000, maxTracked = 2, clock = { now })
        assertEquals(Verdict.Allowed, limiter.tryAcquire("a"))
        now += 400
        assertEquals(Verdict.Allowed, limiter.tryAcquire("b"))
        val saturated = assertIs<Verdict.Refused>(limiter.tryAcquire("c"))
        assertTrue(saturated.saturated)
        assertEquals(600, saturated.retryAfterMillis, "when the oldest tracked window ends")
        assertEquals(Verdict.Allowed, limiter.tryAcquire("a"), "a tracked host is not affected by saturation")
        now += 700
        assertEquals(Verdict.Allowed, limiter.tryAcquire("c"), "a's window expired and was reclaimed")
    }

    @Test
    fun `the bounds must be positive`() {
        assertFailsWith<IllegalArgumentException> { HostWindowLimiter(limit = 0) }
        assertFailsWith<IllegalArgumentException> { HostWindowLimiter(limit = 1, maxTracked = 0) }
    }
}
