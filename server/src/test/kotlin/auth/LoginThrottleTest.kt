package ch.nokillswit.auth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure unit tests for the in-memory per-account throttle (no server, no database). */
class LoginThrottleTest {

    @Test
    fun `locks after threshold failures and unlocks when the window elapses`() {
        var now = 1_000L
        val throttle = LoginThrottle(threshold = 3, lockoutMillis = 60_000, clock = { now })
        val email = "a@test"

        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email))
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email))
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, throttle.recordFailure(email))
        assertEquals(LoginThrottle.Preflight.LOCKED, throttle.preflight(email))

        now += 60_001
        assertEquals(LoginThrottle.Preflight.ALLOWED, throttle.preflight(email))
    }

    @Test
    fun `a success clears the counter`() {
        val throttle = LoginThrottle(threshold = 3, lockoutMillis = 60_000, clock = { 1_000L })
        val email = "b@test"

        throttle.recordFailure(email)
        throttle.recordFailure(email)
        throttle.recordSuccess(email)
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure(email))
        assertEquals(LoginThrottle.Preflight.ALLOWED, throttle.preflight(email))
    }

    @Test
    fun `keys fold email case and padding into one bucket`() {
        val now = 1_000L
        val throttle = LoginThrottle(threshold = 2, lockoutMillis = 60_000, clock = { now })

        throttle.recordFailure("C@Test")
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, throttle.recordFailure("  c@test "))
        assertEquals(LoginThrottle.Preflight.LOCKED, throttle.preflight("c@TEST"))
    }

    @Test
    fun `unknown accounts throttle exactly like existing ones`() {
        val throttle = LoginThrottle(threshold = 1, lockoutMillis = 60_000, clock = { 0 })
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, throttle.recordFailure("ghost@nowhere"))
        assertEquals(LoginThrottle.Preflight.LOCKED, throttle.preflight("ghost@nowhere"))
    }

    @Test
    fun `capacity retains fresh counters and expired state frees a slot`() {
        var now = 1_000L
        val throttle = LoginThrottle(3, 60_000, maxTracked = 2, clock = { now })

        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure("a@test"))
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure("b@test"))
        assertEquals(LoginThrottle.Preflight.CAPACITY_EXCEEDED, throttle.preflight("c@test"))
        assertEquals(LoginThrottle.Preflight.ALLOWED, throttle.preflight("a@test"))

        now += 60_000
        assertEquals(LoginThrottle.Preflight.ALLOWED, throttle.preflight("c@test"))
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure("c@test"))
    }

    @Test
    fun `replacing a counter moves its expiry index entry`() {
        var now = 1_000L
        val throttle = LoginThrottle(5, 60_000, maxTracked = 1, clock = { now })
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure("a@test"))
        now += 30_000
        assertEquals(LoginThrottle.FailureResult.RECORDED, throttle.recordFailure("a@test"))
        now += 30_000
        assertEquals(LoginThrottle.Preflight.CAPACITY_EXCEEDED, throttle.preflight("b@test"))
        now += 30_000
        assertEquals(LoginThrottle.Preflight.ALLOWED, throttle.preflight("b@test"))
    }

    @Test
    fun `capacity never evicts active locks and a concurrent admission cannot exceed the cap`() {
        var now = 1_000L
        val throttle = LoginThrottle(1, 60_000, maxTracked = 1, clock = { now })
        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, throttle.recordFailure("locked@test"))
        assertEquals(LoginThrottle.Preflight.CAPACITY_EXCEEDED, throttle.preflight("new@test"))
        assertEquals(LoginThrottle.FailureResult.ALREADY_LOCKED, throttle.recordFailure("locked@test"))
        assertEquals(LoginThrottle.SuccessResult.LOCKED, throttle.recordSuccess("locked@test"))
        now += 60_000
        assertEquals(LoginThrottle.Preflight.ALLOWED, throttle.preflight("new@test"))

        val concurrent = LoginThrottle(3, 60_000, maxTracked = 1, clock = { 1_000L })
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("one@test", "two@test").map { email ->
                executor.submit<LoginThrottle.FailureResult> {
                    ready.countDown()
                    go.await()
                    concurrent.recordFailure(email)
                }
            }
            ready.await()
            go.countDown()
            assertEquals(
                setOf(LoginThrottle.FailureResult.RECORDED, LoginThrottle.FailureResult.CAPACITY_EXCEEDED),
                results.map { it.get() }.toSet(),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `stored login keys are fixed-size digests of the canonical identity`() {
        val longIdentity = " A${"x".repeat(1_000_000)}@TEST "
        assertEquals(64, loginIdentityKey(longIdentity).length)
        assertEquals(loginIdentityKey(longIdentity), loginIdentityKey(longIdentity.trim().lowercase()))
    }
}
