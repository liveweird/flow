package ch.nokillswit

import ch.nokillswit.auth.PasswordResetThrottle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests for the per-email password-reset throttle (deterministic via an injected clock). */
class PasswordResetThrottleTest {

    private var now = 1_000_000L
    private fun throttle(minIntervalMillis: Long = 60_000) =
        PasswordResetThrottle(minIntervalMillis) { now }

    @Test
    fun `the first request acquires, an immediate second one does not`() {
        val t = throttle()
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("a@x"))
        assertEquals(PasswordResetThrottle.AcquireResult.COOLDOWN, t.tryAcquire("a@x"))
    }

    @Test
    fun `the slot frees up after the interval`() {
        val t = throttle(minIntervalMillis = 60_000)
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("a@x"))
        now += 59_999
        assertEquals(PasswordResetThrottle.AcquireResult.COOLDOWN, t.tryAcquire("a@x"))
        now += 1
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("a@x"))
    }

    @Test
    fun `a rejected attempt does not extend the wait`() {
        val t = throttle(minIntervalMillis = 60_000)
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("a@x"))
        now += 30_000
        assertEquals(PasswordResetThrottle.AcquireResult.COOLDOWN, t.tryAcquire("a@x"))
        now += 30_000 // 60s after the ORIGINAL acquire, not the rejected retry
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("a@x"))
    }

    @Test
    fun `emails are tracked independently and the key is normalized`() {
        val t = throttle()
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("a@x"))
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("b@x"))
        assertEquals(PasswordResetThrottle.AcquireResult.COOLDOWN, t.tryAcquire("  A@X  "))
    }

    @Test
    fun `capacity preserves cooldowns and expiry frees a slot`() {
        val t = PasswordResetThrottle(60_000, clock = { now }, maxTracked = 1)
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("a@x"))
        assertEquals(PasswordResetThrottle.AcquireResult.CAPACITY_EXCEEDED, t.tryAcquire("b@x"))
        assertEquals(PasswordResetThrottle.AcquireResult.COOLDOWN, t.tryAcquire("a@x"))
        now += 60_000
        assertEquals(PasswordResetThrottle.AcquireResult.ACQUIRED, t.tryAcquire("b@x"))
    }

    @Test
    fun `concurrent new identities cannot exceed capacity`() {
        val t = PasswordResetThrottle(60_000, clock = { now }, maxTracked = 1)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("a@x", "b@x").map { email ->
                executor.submit<PasswordResetThrottle.AcquireResult> {
                    ready.countDown()
                    go.await()
                    t.tryAcquire(email)
                }
            }
            ready.await()
            go.countDown()
            assertEquals(
                setOf(
                    PasswordResetThrottle.AcquireResult.ACQUIRED,
                    PasswordResetThrottle.AcquireResult.CAPACITY_EXCEEDED,
                ),
                results.map { it.get() }.toSet(),
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
