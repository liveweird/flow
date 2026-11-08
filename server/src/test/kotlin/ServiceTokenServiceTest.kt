package ch.nokillswit

import ch.nokillswit.integration.MAX_SERVICE_TOKEN_LIFETIME_MILLIS
import ch.nokillswit.integration.MAX_SERVICE_TOKEN_NAME_LENGTH
import ch.nokillswit.integration.SERVICE_TOKEN_LIST_LIMIT
import ch.nokillswit.integration.SERVICE_TOKEN_PATTERN
import ch.nokillswit.integration.SERVICE_TOKEN_PREFIX
import ch.nokillswit.integration.ServiceTokenRejection
import ch.nokillswit.integration.ServiceTokenRevokeOutcome
import ch.nokillswit.integration.ServiceTokenService
import ch.nokillswit.integration.ServiceTokenService.ServiceTokens
import ch.nokillswit.integration.ServiceTokenVerdict
import ch.nokillswit.integration.validateServiceTokenCreate
import ch.nokillswit.plugins.isUniqueViolation
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The service-token registry (`integration/ServiceTokenService.kt`, V48): digests, lookup verdicts, revoke, the live-name index. */
class ServiceTokenServiceTest {
    private fun unique(marker: String) = "$marker-${java.util.UUID.randomUUID()}"

    private suspend fun admin(marker: String): UInt = TestUsers.seed(uniqueEmail(marker), "pw", name = "Admin $marker")

    private suspend fun storedHashes(id: UInt): List<String> = suspendTransaction(sharedDatabaseForTests()) {
        ServiceTokens.selectAll().where { ServiceTokens.id eq id }.map { it[ServiceTokens.tokenHash] }.toList()
    }

    @Test
    fun `create returns a 52 character flow_int_ secret whose digest is stored and whose plaintext is not`() = testApplication {
        usePostgresTestcontainer()
        val tokens = ServiceTokenService(sharedDatabaseForTests())
        val creator = admin("st-create")
        val (token, secret) = tokens.create(unique("create"), expiresAt = null, byUserId = creator)
        assertEquals(52, secret.length)
        assertTrue(secret.startsWith(SERVICE_TOKEN_PREFIX))
        assertTrue(SERVICE_TOKEN_PATTERN.matches(secret))
        val hash = storedHashes(token.id).single()
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(secret, hash)
        assertEquals(ch.nokillswit.infra.json.sha256Hex(secret), hash)
        assertFalse(secret in token.toString(), "the DTO never carries the plaintext")
        assertEquals("Admin st-create", token.createdByName)
        assertFalse(token.revoked)
        assertNull(token.expiresAt)
        assertNull(token.lastUsedAt)
        assertEquals(token, tokens.read(token.id))
        assertNotEquals(secret, tokens.create(unique("create"), null, creator).second, "every token is fresh randomness")
        assertNull(tokens.read(0u))
    }

    @Test
    fun `list is live first then newest first and read joins the creator and revoker names`() = testApplication {
        usePostgresTestcontainer()
        var now = System.currentTimeMillis()
        val tokens = ServiceTokenService(sharedDatabaseForTests()) { now }
        val creator = admin("st-list-a")
        val revoker = admin("st-list-b")
        val oldest = tokens.create(unique("list"), null, creator).first
        now += 10
        val middle = tokens.create(unique("list"), null, creator).first
        now += 10
        val newest = tokens.create(unique("list"), null, creator).first
        now += 10
        // The NEWEST token is revoked: liveness outranks age, and within the live pair the newer one comes first.
        assertEquals(ServiceTokenRevokeOutcome.REVOKED, tokens.revoke(newest.id, revoker))
        val listed = tokens.list()
        assertTrue(listed.size <= 100)
        val ours = listed.filter { it.id in setOf(oldest.id, middle.id, newest.id) }
        assertEquals(listOf(middle.id, oldest.id, newest.id), ours.map { it.id }, "live first, each group newest first")
        val revoked = ours.last()
        assertTrue(revoked.revoked)
        assertEquals(now, revoked.revokedAt)
        assertEquals("Admin st-list-b", revoked.revokedByName)
        assertNull(ours.first().revokedByName)
    }

    @Test
    fun `list always carries a live token, however old, ahead of more than a page of newer revoked ones`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val tokens = ServiceTokenService(database)
        val creator = admin("st-list-live")
        // Seeded directly with the OLDEST created_at of the table (1..101), so the live token is far outside the newest 100 by age alone.
        val ids = suspendTransaction(database) {
            (0..SERVICE_TOKEN_LIST_LIMIT).map { i ->
                ServiceTokens.insert {
                    it[name] = unique("page")
                    it[tokenHash] = ch.nokillswit.infra.json.sha256Hex(unique("digest"))
                    it[createdBy] = creator
                    it[createdAt] = 1L + i
                    if (i > 0) {
                        it[revokedAt] = 1_000L + i
                        it[revokedBy] = creator
                    }
                }[ServiceTokens.id].value
            }
        }
        try {
            val listed = tokens.list()
            assertEquals(SERVICE_TOKEN_LIST_LIMIT, listed.size)
            assertTrue(ids.first() in listed.map { it.id }, "the old live token is listed")
            val flags = listed.map { it.revoked }
            assertEquals(flags.sortedBy { it }, flags, "every live token precedes every revoked one")
        } finally {
            suspendTransaction(database) { ServiceTokens.deleteWhere { ServiceTokens.id inList ids } }
        }
    }

    @Test
    fun `authenticate tells a live token from an unknown, a revoked and an expired one`() = testApplication {
        usePostgresTestcontainer()
        val base = System.currentTimeMillis()
        val tokens = ServiceTokenService(sharedDatabaseForTests())
        val creator = admin("st-auth")
        val (live, liveSecret) = tokens.create(unique("live"), expiresAt = base + 60_000, byUserId = creator)

        val first = assertIs<ServiceTokenVerdict.Live>(tokens.authenticate(liveSecret, base))
        assertEquals(live.id, first.tokenId)
        assertEquals(live.name, first.name)
        assertEquals(live.createdAt, first.createdAt)
        assertEquals(base + 60_000, first.expiresAt)
        assertNull(first.lastUsedAtBefore, "never used before this call")
        val second = assertIs<ServiceTokenVerdict.Live>(tokens.authenticate(liveSecret, base + 5))
        assertEquals(base, second.lastUsedAtBefore, "the stamp of the previous call")
        assertEquals(base + 5, tokens.read(live.id)!!.lastUsedAt)

        val unknown = SERVICE_TOKEN_PREFIX + "a".repeat(43)
        assertEquals(ServiceTokenVerdict.Rejected(ServiceTokenRejection.UNKNOWN_OR_REVOKED, null), tokens.authenticate(unknown, base))

        // The expiry instant itself is already expired (`expires_at > now` is the live test).
        assertEquals(ServiceTokenVerdict.Rejected(ServiceTokenRejection.EXPIRED, live.id), tokens.authenticate(liveSecret, base + 60_000))
        assertEquals(base + 5, tokens.read(live.id)!!.lastUsedAt, "an expired token is never stamped")

        assertEquals(ServiceTokenRevokeOutcome.REVOKED, tokens.revoke(live.id, creator))
        assertEquals(
            ServiceTokenVerdict.Rejected(ServiceTokenRejection.UNKNOWN_OR_REVOKED, live.id),
            tokens.authenticate(liveSecret, base + 6),
            "revocation takes effect on the very next call",
        )
        assertEquals(base + 5, tokens.read(live.id)!!.lastUsedAt, "a revoked token is never stamped")
    }

    @Test
    fun `a token with no expiry stays live`() = testApplication {
        usePostgresTestcontainer()
        val tokens = ServiceTokenService(sharedDatabaseForTests())
        val (_, secret) = tokens.create(unique("forever"), null, admin("st-forever"))
        assertIs<ServiceTokenVerdict.Live>(tokens.authenticate(secret, Long.MAX_VALUE / 2))
    }

    @Test
    fun `revoke is terminal and reports not found and already revoked`() = testApplication {
        usePostgresTestcontainer()
        var now = System.currentTimeMillis()
        val tokens = ServiceTokenService(sharedDatabaseForTests()) { now }
        val creator = admin("st-revoke")
        val (token, _) = tokens.create(unique("revoke"), null, creator)
        assertEquals(ServiceTokenRevokeOutcome.NOT_FOUND, tokens.revoke(0u, creator))
        assertEquals(ServiceTokenRevokeOutcome.REVOKED, tokens.revoke(token.id, creator))
        val stamped = tokens.read(token.id)!!
        assertTrue(stamped.revoked)
        assertEquals(now, stamped.revokedAt)
        now += 1_000
        assertEquals(ServiceTokenRevokeOutcome.ALREADY_REVOKED, tokens.revoke(token.id, creator))
        assertEquals(stamped, tokens.read(token.id), "a repeat revoke changes nothing")
    }

    @Test
    fun `a live name clashes case-insensitively until the first token is revoked`() = testApplication {
        usePostgresTestcontainer()
        val tokens = ServiceTokenService(sharedDatabaseForTests())
        val creator = admin("st-name")
        val name = unique("Clash")
        val (first, _) = tokens.create(name, null, creator)
        val clash = assertFailsWith<Throwable> { tokens.create(name.lowercase(), null, creator) }
        assertTrue(clash.isUniqueViolation(), "the live-name index answers a unique violation: $clash")
        assertEquals(ServiceTokenRevokeOutcome.REVOKED, tokens.revoke(first.id, creator))
        assertEquals(name, tokens.create(name, null, creator).first.name, "a revoked token frees its name")
    }

    @Test
    fun `an expired but unrevoked token still holds its name`() = testApplication {
        usePostgresTestcontainer()
        var now = System.currentTimeMillis()
        val tokens = ServiceTokenService(sharedDatabaseForTests()) { now }
        val creator = admin("st-expired-name")
        val name = unique("Expiring")
        tokens.create(name, expiresAt = now + 1_000, byUserId = creator)
        now += 2_000
        val clash = assertFailsWith<Throwable> { tokens.create(name, null, creator) }
        assertTrue(clash.isUniqueViolation(), "$clash")
    }

    @Test
    fun `create validates the request again`() = testApplication {
        usePostgresTestcontainer()
        val tokens = ServiceTokenService(sharedDatabaseForTests())
        val creator = admin("st-validate")
        assertFailsWith<BadRequestException> { tokens.create("   ", null, creator) }
        assertFailsWith<BadRequestException> { tokens.create(unique("past"), System.currentTimeMillis() - 1, creator) }
    }

    @Test
    fun `the validator trims the name and bounds its length`() {
        val now = 1_000_000L
        assertEquals("Astrolabe", validateServiceTokenCreate("  Astrolabe  ", null, now))
        val longest = "n".repeat(MAX_SERVICE_TOKEN_NAME_LENGTH)
        assertEquals(longest, validateServiceTokenCreate(longest, null, now))
        for (bad in listOf("", "   ", "n".repeat(MAX_SERVICE_TOKEN_NAME_LENGTH + 1), "a\nb", "a\u0000b")) {
            assertFailsWith<BadRequestException>(bad) { validateServiceTokenCreate(bad, null, now) }
        }
    }

    @Test
    fun `the validator accepts no expiry or an expiry in the future up to ten years`() {
        val now = 1_000_000L
        assertEquals("t", validateServiceTokenCreate("t", null, now))
        assertEquals("t", validateServiceTokenCreate("t", now + 1, now))
        assertEquals("t", validateServiceTokenCreate("t", now + MAX_SERVICE_TOKEN_LIFETIME_MILLIS, now))
        for (bad in listOf(now, now - 1, 0L, -5L, now + MAX_SERVICE_TOKEN_LIFETIME_MILLIS + 1)) {
            assertFailsWith<BadRequestException>("$bad") { validateServiceTokenCreate("t", bad, now) }
        }
    }

    @Test
    fun `the token pattern accepts exactly the minted shape`() {
        val good = SERVICE_TOKEN_PREFIX + "A1_-".repeat(10) + "abc"
        assertEquals(52, good.length)
        assertTrue(SERVICE_TOKEN_PATTERN.matches(good))
        val malformed = listOf(
            "", good.dropLast(1), good + "a", good + "\n", "flow_svc_" + good.drop(9), good.dropLast(1) + "=", good.dropLast(1) + " ",
            "Bearer $good",
        )
        for (bad in malformed) {
            assertFalse(SERVICE_TOKEN_PATTERN.matches(bad), bad)
        }
    }
}
