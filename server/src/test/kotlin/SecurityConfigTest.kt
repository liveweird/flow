package ch.nokillswit

import io.ktor.server.testing.testApplication
import kotlin.test.Test

/**
 * The JWT-secret fail-closed check (plugins/Security.kt): a production secret must be a
 * private 64-hex key. Development keeps the demo/placeholder behavior. The check runs
 * before Flyway/Bootstrap, so no seed handling is needed.
 */
class SecurityConfigTest {

    private fun assertRefusedInProduction(vararg overrides: Pair<String, String>) = testApplication {
        configureApp(*overrides)
        serverConfig { developmentMode = false }
        assertStartupFails("JWT secret") { startApplication() }
    }

    @Test
    fun `production mode refuses the placeholder secret`() {
        // application.yaml's default is the literal "secret" — burned by definition.
        assertRefusedInProduction()
    }

    @Test
    fun `production mode refuses a blank secret`() {
        assertRefusedInProduction("jwt.secret" to "")
    }

    @Test
    fun `production mode refuses one-character and repeated hex keys`() {
        assertRefusedInProduction("jwt.secret" to "x")
        assertRefusedInProduction("jwt.secret" to "0".repeat(64))
    }

    @Test
    fun `production mode refuses the committed docker-compose demo key`() {
        assertRefusedInProduction(
            "jwt.secret" to "dev-only-6923ce629cb95ce814e8e2464ca2c40124802f6b7738c1fced65ae115817faa8",
        )
    }

    @Test
    fun `production mode refuses the k8s secret template placeholder`() {
        assertRefusedInProduction("jwt.secret" to "CHANGE-ME-openssl-rand-hex-32")
    }

    @Test
    fun `development mode tolerates the placeholder secret`() = testApplication {
        usePostgresTestcontainer() // boots with the "secret" default — no throw expected
    }
}
