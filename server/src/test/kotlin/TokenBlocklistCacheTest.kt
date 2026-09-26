package ch.nokillswit

import ch.nokillswit.auth.REVOCATION_CACHE_TTL_MS
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-memory revocation cache in auth/TokenBlocklistService.kt: verdicts are served from
 * memory for REVOCATION_CACHE_TTL_MS, revoke() seeds its own instance immediately, and a
 * revocation written elsewhere becomes visible once the TTL passes. Clock-injected — the
 * testApplication only guarantees the container is migrated.
 */
class TokenBlocklistCacheTest {

    @Test
    fun `a not-revoked verdict is served from cache for the TTL, then re-read`() = testApplication {
        usePostgresTestcontainer()
        var now = 1_000_000L
        val service = newTokenBlocklistService { now }
        val jti = UUID.randomUUID().toString()
        assertFalse(service.isRevoked(jti))

        // Another instance (a hypothetical second replica / pre-restart process) revokes it.
        newTokenBlocklistService { now }.revoke(jti, System.currentTimeMillis() + 600_000)

        // Within the TTL the first instance still serves its cached not-revoked verdict…
        assertFalse(service.isRevoked(jti), "verdict inside the TTL must come from the cache")
        // …and past the TTL it re-reads the DB and sees the revocation.
        now += REVOCATION_CACHE_TTL_MS + 1
        assertTrue(service.isRevoked(jti), "a stale verdict must be re-read after the TTL")
    }

    @Test
    fun `revoke seeds the cache, overriding a fresh not-revoked verdict immediately`() = testApplication {
        usePostgresTestcontainer()
        var now = 1_000_000L
        val service = newTokenBlocklistService { now }
        val jti = UUID.randomUUID().toString()
        assertFalse(service.isRevoked(jti))

        service.revoke(jti, System.currentTimeMillis() + 600_000)

        // No clock movement: the revoked verdict is visible despite the cached negative.
        assertTrue(service.isRevoked(jti), "the revoking instance must see its own revocation at once")
    }
}
