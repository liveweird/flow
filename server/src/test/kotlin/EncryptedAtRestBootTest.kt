package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Jira API token (`source_connections.secret`, V8) is the first `EncryptedAtRest` consumer
 * (`infra/db/Bootstrap.kt`'s `encryptedAtRestServices()`). Seeds a connection under one key, then
 * boots the app again with a NEW current key and the old one as `security.encryption.previousKey`
 * — the rotation backfill (`reencryptAll = true` while a previous key is configured,
 * `.claude/docs/security.md` "Encryption at rest") must rewrite the row so it still decrypts,
 * under the NEW key alone this time.
 *
 * The suite shares ONE database and every other test boots under the dev default key, so the
 * seed is written under that key and the test always rotates back to it in `finally` — a row
 * left under a random key would make the NEXT boot's rotation backfill fail closed
 * (AEADBadTagException), exactly as production would with a wrong key.
 */
class EncryptedAtRestBootTest {

    private suspend fun rawSecret(id: UInt): String = suspendTransaction(sharedDatabaseForTests()) {
        val connections = DataSourceService.Connections
        connections.selectAll().where { connections.id eq id }.toList().single()[connections.secret]
    }

    @Test
    fun `a rotated encryption key re-encrypts the stored Jira token and it still decrypts`() = runBlocking {
        val keyTwo = strongEncryptionKey()
        val plaintextToken = "jira-token-${UUID.randomUUID()}"
        var dataSourceId = 0u

        // Boot 1: the ordinary shared-suite dev-default key (`usePostgresTestcontainer()`, no
        // override) — every OTHER test's source_connections row is encrypted under this same key,
        // since `reencryptAll` below rewrites every row in the (shared) table, not just this one.
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("earb-boot1", UserRole.ADMIN)
            val jira = JiraConnectionRequest(
                siteUrl = "https://${UUID.randomUUID().toString().take(8)}.atlassian.net",
                email = "svc-${UUID.randomUUID()}@example.com",
                apiToken = plaintextToken,
                projectKeys = listOf("ENG"),
                authScheme = JiraAuthScheme.BASIC,
            )
            val created = admin.postJson(
                "/api/v1/data-sources",
                DataSourceRequest(name = "earb-${UUID.randomUUID()}", syncIntervalMinutes = 60, jira = jira),
            ).body<DataSourceResponse>()
            dataSourceId = created.id
        }

        val rawUnderDevKey = rawSecret(dataSourceId)
        // FieldCipher.decrypt passes a non-enveloped value through UNCHANGED (legacy-plaintext
        // support), so decrypt(raw) == plaintextToken would also hold for a raw column that was
        // never encrypted at all. Pin the envelope shape first so that sanity check actually
        // proves encryption happened, not merely that decrypt is a no-op passthrough.
        assertTrue(rawUnderDevKey.startsWith(FieldCipher.PREFIX), "the stored secret must be enveloped, not plaintext")
        assertFalse(plaintextToken in rawUnderDevKey, "the raw stored secret must never contain the plaintext token")
        assertEquals(
            plaintextToken,
            FieldCipher(DEV_DATA_ENCRYPTION_KEY).decrypt(rawUnderDevKey),
            "sanity: seeded under the shared dev-default key",
        )

        try {
            // Boot 2: keyTwo is now current, the dev-default key is the decrypt-only previousKey —
            // the rotation backfill re-encrypts EVERY row in the table (including every other
            // test's) under keyTwo, so previousKey must be the key everything was actually
            // encrypted under.
            testApplication {
                configureApp("security.encryption.key" to keyTwo, "security.encryption.previousKey" to DEV_DATA_ENCRYPTION_KEY)
                startApplication()
            }

            val rawUnderKeyTwo = rawSecret(dataSourceId)
            assertNotEquals(rawUnderDevKey, rawUnderKeyTwo, "the row must be rewritten (re-encrypted), not left as-is")
            assertEquals(plaintextToken, FieldCipher(keyTwo).decrypt(rawUnderKeyTwo), "must decrypt under the NEW key alone after rotation")
        } finally {
            // Rotate every row back under the shared dev-default key (current, unoverridden),
            // with keyTwo as the decrypt-only previousKey — restoring the suite-wide invariant so
            // a later ordinary boot (CryptoBootTest's included) does not fail closed on a row this
            // test left under keyTwo.
            testApplication {
                configureApp("security.encryption.previousKey" to keyTwo)
                startApplication()
            }
        }
    }
}
