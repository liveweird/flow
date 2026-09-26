package ch.nokillswit

import ch.nokillswit.auth.MfaChallenges
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Unit tests for the in-memory email-MFA challenge store (deterministic via an injected clock). */
class MfaChallengesTest {

    private var now = 1_000_000L
    private fun store(ttlMillis: Long = 300_000, maxAttempts: Int = 5, maxTracked: Int = 10_000) =
        MfaChallenges(ttlMillis, maxAttempts, clock = { now }, maxTracked = maxTracked)

    @Test
    fun `a correct code succeeds exactly once - the challenge is single-use`() {
        val s = store()
        val issued = s.issue(42u, credentialRevision = 7)
        assertEquals(6, issued.code.length)
        assertTrue(issued.code.all { it.isDigit() })
        assertEquals(now + 300_000, issued.expiresAt)

        val outcome = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Success>(outcome)
        assertEquals(42u, outcome.userId)
        assertEquals(7L, outcome.credentialRevision)

        // Replay of the consumed challenge is indistinguishable from an unknown one.
        val replay = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Failure>(replay)
        assertEquals("unknown_challenge", replay.reason)
    }

    @Test
    fun `an expired challenge fails and is dropped`() {
        val s = store(ttlMillis = 60_000)
        val issued = s.issue(7u, credentialRevision = 0)
        now += 60_000
        val outcome = s.verify(issued.challengeId, issued.code)
        assertIs<MfaChallenges.Outcome.Failure>(outcome)
        assertEquals("expired", outcome.reason)
        // The drop is permanent — a later attempt sees unknown, not expired.
        assertEquals(
            "unknown_challenge",
            (s.verify(issued.challengeId, issued.code) as MfaChallenges.Outcome.Failure).reason,
        )
    }

    @Test
    fun `wrong codes count toward the attempt cap and exhausting it kills the challenge`() {
        val s = store(maxAttempts = 3)
        val issued = s.issue(7u, credentialRevision = 0)
        assertEquals("wrong_code", (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason)
        assertEquals("wrong_code", (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason)
        assertEquals(
            "too_many_attempts",
            (s.verify(issued.challengeId, "x") as MfaChallenges.Outcome.Failure).reason,
        )
        // Even the correct code no longer works — the challenge is gone.
        assertEquals(
            "unknown_challenge",
            (s.verify(issued.challengeId, issued.code) as MfaChallenges.Outcome.Failure).reason,
        )
    }

    @Test
    fun `an unknown challenge id fails uniformly`() {
        val s = store()
        val outcome = s.verify("no-such-challenge", "123456")
        assertIs<MfaChallenges.Outcome.Failure>(outcome)
        assertEquals("unknown_challenge", outcome.reason)
    }

    @Test
    fun `challenge ids are unique and opaque`() {
        val s = store()
        val a = s.issue(1u, credentialRevision = 0)
        val b = s.issue(1u, credentialRevision = 0)
        assertNotEquals(a.challengeId, b.challengeId)
        assertEquals(32, a.challengeId.length)
        // Both stay independently verifiable (repeated logins may coexist within the TTL).
        assertIs<MfaChallenges.Outcome.Success>(s.verify(b.challengeId, b.code))
        assertIs<MfaChallenges.Outcome.Success>(s.verify(a.challengeId, a.code))
    }

    @Test
    fun `the store prunes expired entries once oversized instead of growing without bound`() {
        val s = store(ttlMillis = 1_000, maxTracked = 2)
        val stale = (1..2).map { s.issue(it.toUInt(), credentialRevision = 0) }
        assertFailsWith<MfaChallenges.CapacityExceededException> { s.issue(3u, credentialRevision = 0) }
        now += 2_000
        val fresh = s.issue(99u, credentialRevision = 0)
        assertEquals(
            "unknown_challenge",
            (s.verify(stale.first().challengeId, stale.first().code) as MfaChallenges.Outcome.Failure).reason,
        )
        assertIs<MfaChallenges.Outcome.Success>(s.verify(fresh.challengeId, fresh.code))
    }

    @Test
    fun `capacity keeps existing challenges verifiable`() {
        val s = store(maxTracked = 1)
        val issued = s.issue(1u, credentialRevision = 9)
        assertFailsWith<MfaChallenges.CapacityExceededException> { s.issue(2u, credentialRevision = 0) }
        val outcome = assertIs<MfaChallenges.Outcome.Success>(s.verify(issued.challengeId, issued.code))
        assertEquals(9L, outcome.credentialRevision)
        assertTrue(s.issue(2u, credentialRevision = 0).challengeId.isNotBlank())
    }

    @Test
    fun `discarding an undelivered challenge immediately releases its slot`() {
        val s = store(maxTracked = 1)
        val undelivered = s.issue(1u, credentialRevision = 0)
        s.discard(undelivered.challengeId)
        val replacement = s.issue(2u, credentialRevision = 0)
        assertEquals(
            "unknown_challenge",
            (s.verify(undelivered.challengeId, undelivered.code) as MfaChallenges.Outcome.Failure).reason,
        )
        assertIs<MfaChallenges.Outcome.Success>(s.verify(replacement.challengeId, replacement.code))
    }

    @Test
    fun `concurrent issuance cannot exceed capacity`() {
        val s = store(maxTracked = 1)
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { userId ->
                executor.submit<Boolean> {
                    ready.countDown()
                    go.await()
                    runCatching { s.issue(userId.toUInt(), credentialRevision = 0) }.isSuccess
                }
            }
            ready.await()
            go.countDown()
            assertEquals(listOf(false, true), results.map { it.get() }.sorted())
        } finally {
            executor.shutdownNow()
        }
    }
}
