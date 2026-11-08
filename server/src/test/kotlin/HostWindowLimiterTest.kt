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
    fun `a clock that steps back cannot leave a tracked host on an expired window`() {
        var now = 100_000L
        val limiter = HostWindowLimiter(limit = 1, windowMillis = 1_000, maxTracked = 3, clock = { now })
        assertEquals(Verdict.Allowed, limiter.tryAcquire("late")) // window start 100_000
        now = 10_000 // the clock steps back: the next window starts BEFORE the earlier one, so the map is no longer ordered by start
        assertEquals(Verdict.Allowed, limiter.tryAcquire("early")) // window start 10_000, behind "late" in the map
        now = 11_500 // "early" has expired (10_000 + 1_000 <= 11_500) but sits behind the head: head reclaim stops at "late"
        // Without the own-window check "early" stays blocked on its spent window, with a negative retry-after.
        assertEquals(Verdict.Allowed, limiter.tryAcquire("early"), "an expired own window is fresh")
        val refused = assertIs<Verdict.Refused>(limiter.tryAcquire("early"))
        assertEquals(1_000, refused.retryAfterMillis, "the fresh window started at 11_500")
        assertEquals(false, refused.saturated)
    }

    @Test
    fun `the bounds must be positive`() {
        assertFailsWith<IllegalArgumentException> { HostWindowLimiter(limit = 0) }
        assertFailsWith<IllegalArgumentException> { HostWindowLimiter(limit = 1, maxTracked = 0) }
    }
}
