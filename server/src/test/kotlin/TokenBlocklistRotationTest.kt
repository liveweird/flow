package ch.nokillswit

import ch.nokillswit.auth.REFRESH_REPLAY_GRACE_MS
import ch.nokillswit.auth.RefreshClaim
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The refresh-rotation half of auth/TokenBlocklistService.kt, straight against the database: the
 * atomic claim (the HTTP replay tests are answered by the `isRevoked` fast path (a plain select), so only
 * these prove the row insert itself decides the winner) and the replay-grace verdicts under an
 * injected clock.
 */
class TokenBlocklistRotationTest {

    private val farFuture get() = System.currentTimeMillis() + 600_000

    private companion object {
        const val USER = 42L
    }

    @Test
    fun `revokeIfAbsent inserts once - the second call for the same jti reports false`() = testApplication {
        usePostgresTestcontainer()
        val service = newTokenBlocklistService { 1_000_000L }
        val jti = UUID.randomUUID().toString()

        assertTrue(service.revokeIfAbsent(jti, farFuture))
        assertFalse(service.revokeIfAbsent(jti, farFuture))
        // A fresh instance has no cache and no memory: the DATABASE row is what says "already there".
        assertFalse(newTokenBlocklistService { 1_000_000L }.revokeIfAbsent(jti, farFuture))
    }

    @Test
    fun `concurrent claims of one jti produce exactly one winner`() = testApplication {
        usePostgresTestcontainer()
        val jti = UUID.randomUUID().toString()
        val expiresAt = farFuture

        val results = coroutineScope {
            // One service per caller: separate caches and no shared state, only the database row.
            (1..12).map { async(Dispatchers.IO) { newTokenBlocklistService { 1_000_000L }.revokeIfAbsent(jti, expiresAt) } }
                .awaitAll()
        }

        assertEquals(1, results.count { it }, "exactly one insert wins: $results")
    }

    @Test
    fun `a replay is a grace re-mint inside the window, at most twice, and replayed after it`() = testApplication {
        usePostgresTestcontainer()
        var now = 1_000_000L
        val service = newTokenBlocklistService { now }
        val jti = UUID.randomUUID().toString()

        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(jti, farFuture, USER))
        now += REFRESH_REPLAY_GRACE_MS - 1
        assertTrue(service.inReplayGrace(jti))
        assertEquals(RefreshClaim.GRACE_REPLAY, service.claimRefresh(jti, farFuture, USER))
        assertEquals(RefreshClaim.GRACE_REPLAY, service.claimRefresh(jti, farFuture, USER))
        // The re-mints are used up: still inside the window, but now a replay.
        assertFalse(service.inReplayGrace(jti))
        assertEquals(RefreshClaim.REPLAYED, service.claimRefresh(jti, farFuture, USER))

        val late = UUID.randomUUID().toString()
        now += 10
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(late, farFuture, USER))
        now += REFRESH_REPLAY_GRACE_MS
        assertEquals(RefreshClaim.REPLAYED, service.claimRefresh(late, farFuture, USER), "past the window, unused re-mints do not matter")
    }

    @Test
    fun `forgetting a user's rotations ends their grace but not another user's`() = testApplication {
        usePostgresTestcontainer()
        val service = newTokenBlocklistService { 1_000_000L }
        val mine = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(mine, farFuture, USER))
        assertEquals(RefreshClaim.CLAIMED, service.claimRefresh(other, farFuture, USER + 1))

        service.forgetRotations(USER)

        assertEquals(RefreshClaim.REVOKED, service.claimRefresh(mine, farFuture, USER))
        assertEquals(RefreshClaim.GRACE_REPLAY, service.claimRefresh(other, farFuture, USER + 1))
    }

    @Test
    fun `a logout-revoked or not-remembered token is revoked, never a grace replay`() = testApplication {
        usePostgresTestcontainer()
        val service = newTokenBlocklistService { 1_000_000L }
        val loggedOut = UUID.randomUUID().toString()
        service.revoke(loggedOut, farFuture)
        assertEquals(RefreshClaim.REVOKED, service.claimRefresh(loggedOut, farFuture, USER))

        // Rotated by another instance (or before a restart): the row exists, this instance remembers nothing.
        val rotatedElsewhere = UUID.randomUUID().toString()
        assertEquals(RefreshClaim.CLAIMED, newTokenBlocklistService { 1_000_000L }.claimRefresh(rotatedElsewhere, farFuture, USER))
        assertEquals(RefreshClaim.REVOKED, service.claimRefresh(rotatedElsewhere, farFuture, USER))
    }
}
