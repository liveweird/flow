package ch.nokillswit

import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * The data-encryption-key fail-closed check (infra/crypto/Crypto.kt): a burned or blank key is
 * tolerated in development but refuses production; a malformed key refuses in any mode.
 */
class CryptoBootTest {

    @Test
    fun `production mode refuses the committed dev default key`() = testApplication {
        configureApp("jwt.secret" to strongJwtSecret(), "mail.transport" to "disabled")
        serverConfig { developmentMode = false }
        assertStartupFails("Data encryption key") { startApplication() }
    }

    @Test
    fun `production mode refuses a blank key`() = testApplication {
        configureApp("jwt.secret" to strongJwtSecret(), "mail.transport" to "disabled", "security.encryption.key" to "")
        serverConfig { developmentMode = false }
        assertStartupFails("Data encryption key") { startApplication() }
    }

    @Test
    fun `a malformed key refuses to start even in development`() = testApplication {
        configureApp("security.encryption.key" to "CHANGE-ME-openssl-rand-hex-32")
        assertStartupFails("hex characters") { startApplication() }
    }

    @Test
    fun `development mode tolerates the dev default, and a rotation previous key is accepted`() = testApplication {
        configureApp("security.encryption.previousKey" to strongEncryptionKey())
        startApplication() // the dev default is burned but permitted here; the previous key is decrypt-only
    }
}
