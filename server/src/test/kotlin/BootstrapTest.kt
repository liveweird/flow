package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.infra.db.SEED_ADMIN_EMAIL
import ch.nokillswit.infra.db.SEED_PASSWORD_HASH
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Startup bootstrap (infra/db/Bootstrap.kt): ADMIN_INITIAL_PASSWORD rotates the V3 seed admin
 * away from the well-known "changeme", and outside development mode startup fails closed while
 * any active account still carries the seed password. Tests restore the shared container's
 * seed state afterwards (TestSeedState).
 */
class BootstrapTest {

    @Test
    fun `ADMIN_INITIAL_PASSWORD rotates the seed admin so changeme stops working`() = testApplication {
        val newPassword = "rotated-${UUID.randomUUID()}"
        configureApp("bootstrap.adminInitialPassword" to newPassword)
        withSeedRestored {
            startApplication()
            val client = jsonClient()
            assertEquals(
                1L,
                TestUsers.service.findWithIdByEmail(SEED_ADMIN_EMAIL)?.second?.credentialRevision,
                "bootstrap rotation must use the shared credential-generation increment",
            )

            val withOld = client.login(SEED_ADMIN_EMAIL, "changeme")
            assertEquals(HttpStatusCode.Unauthorized, withOld.status)

            val withNew = client.login(SEED_ADMIN_EMAIL, newPassword)
            assertEquals(HttpStatusCode.OK, withNew.status)
            assertTrue(withNew.body<LoginResponse>().token.isNotBlank())
        }
    }

    @Test
    fun `rotation is idempotent - an admin-chosen password is never overwritten`() = testApplication {
        val chosen = "chosen-${UUID.randomUUID()}"
        // First boot rotates away from the seed hash…
        configureApp("bootstrap.adminInitialPassword" to chosen)
        withSeedRestored {
            startApplication()
            // …then simulate a later boot with a DIFFERENT initial password: the admin's password
            // no longer matches the seed hash, so nothing may change.
            val rotatedAgain = TestUsers.service.rotatePasswordIfHashMatches(
                email = SEED_ADMIN_EMAIL,
                expectedHash = ch.nokillswit.infra.db.SEED_PASSWORD_HASH,
                newHash = "never-applied",
            )
            assertEquals(0, rotatedAgain)

            val stillChosen = jsonClient().postJson("/api/v1/login", LoginRequest(SEED_ADMIN_EMAIL, chosen))
            assertEquals(HttpStatusCode.OK, stillChosen.status)
        }
    }

    @Test
    fun `production mode refuses to start while seed passwords are active`() = testApplication {
        // No ADMIN_INITIAL_PASSWORD; strong JWT secret so the failure is the seed check.
        configureApp(
            "jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey(),
            // The dev-default `log` mail transport is refused in production (see infra/mail).
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("seed password") { startApplication() }
        }
    }

    @Test
    fun `production mode boots once the admin is rotated`() = testApplication {
        val newPassword = "rotated-${UUID.randomUUID()}"
        configureApp(
            "bootstrap.adminInitialPassword" to newPassword,
            "jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey(),
            // The dev-default `log` mail transport is refused in production (see infra/mail).
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            startApplication() // must not throw: rotation happens before the fail-closed check
        }
    }

    @Test
    fun `production mode refuses a freshly hashed burned initial password`() = testApplication {
        configureApp(
            "bootstrap.adminInitialPassword" to "changeme",
            "jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("ADMIN_INITIAL_PASSWORD is a publicly known value") { startApplication() }
        }
    }

    @Test
    fun `production mode refuses the Kubernetes initial-password placeholder`() = testApplication {
        configureApp(
            "bootstrap.adminInitialPassword" to "CHANGE-ME",
            "jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("ADMIN_INITIAL_PASSWORD is a publicly known value") { startApplication() }
        }
    }

    @Test
    fun `production mode applies the account password length policy to bootstrap`() = testApplication {
        configureApp(
            "bootstrap.adminInitialPassword" to "short",
            "jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("ADMIN_INITIAL_PASSWORD must meet") { startApplication() }
        }
    }

    @Test
    fun `production mode detects legacy fresh hashes of burned admin passwords`() = runBlocking {
        // Apply migrations in a development boot, then simulate the old bootstrap's fresh salt.
        testApplication { usePostgresTestcontainer() }
        for (burned in listOf("changeme", "CHANGE-ME", "change-me")) {
            withSeedRestored {
                val changed = TestUsers.service.rotatePasswordIfHashMatches(
                    SEED_ADMIN_EMAIL,
                    SEED_PASSWORD_HASH,
                    hashPassword(burned, cost = 4),
                )
                assertEquals(1, changed)
                testApplication {
                    configureApp(
                        "jwt.secret" to strongJwtSecret(), "security.encryption.key" to strongEncryptionKey(),
                        "mail.transport" to "disabled",
                    )
                    serverConfig { developmentMode = false }
                    assertStartupFails("seed admin still uses a publicly known password") { startApplication() }
                }
            }
        }
    }
}
