package ch.nokillswit

import ch.nokillswit.infra.db.buildFlyway
import ch.nokillswit.infra.db.readFlywayConnectRetries
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cold-start resilience (`infra/db/Flyway.kt`): Flyway retries its boot connection instead of failing at once,
 * so a pod that starts before Postgres accepts connections waits rather than crash-looping. The two tests are
 * cheap on purpose — the key is read and bounds-validated, and the retry setting is provably wired to Flyway
 * (a closed port fails, but only after at least the first 1 s back-off when one retry is allowed).
 */
class FlywayConnectRetriesTest {

    private fun config(value: String) = MapApplicationConfig("postgres.connectRetries" to value)

    @Test
    fun `connectRetries is read and bounds-validated with the key in the message`() {
        assertEquals(0, readFlywayConnectRetries(config("0")))
        assertEquals(10, readFlywayConnectRetries(config("10")))
        assertEquals(15, readFlywayConnectRetries(config("15")))
        listOf("-1", "16", "abc", "").forEach { bad ->
            val failure = assertFailsWith<IllegalStateException>("value \"$bad\"") { readFlywayConnectRetries(config(bad)) }
            assertTrue("postgres.connectRetries" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `the shipped default is ten retries`() = testApplication {
        configureApp()
        startApplication()
        assertEquals(10, readFlywayConnectRetries(application.environment.config))
    }

    @Test
    fun `Flyway waits and retries on an unreachable database, and fails fast at zero retries`() {
        val url = "jdbc:postgresql://127.0.0.1:1/flow"
        fun elapsedMsOfFailure(retries: Int): Long {
            val started = System.nanoTime()
            assertFailsWith<Exception> { buildFlyway(url, "u", "p", retries).migrate() }
            return (System.nanoTime() - started) / 1_000_000
        }
        val failFast = elapsedMsOfFailure(0)
        val oneRetry = elapsedMsOfFailure(1)
        assertTrue(oneRetry >= 1_000, "one retry waits the 1 s first back-off, took $oneRetry ms")
        assertTrue(oneRetry > failFast, "retrying takes longer than failing fast ($oneRetry vs $failFast ms)")
    }
}
