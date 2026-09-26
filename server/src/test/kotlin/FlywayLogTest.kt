package ch.nokillswit

import ch.nokillswit.infra.db.jdbcUrlForLogging
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * infra/db/Flyway.kt logs the operator-supplied JDBC URL at startup. A
 * `jdbc:postgresql://host/db?user=...&password=...` form must never land a credential in the
 * log (.claude/docs/observability.md, "never log secrets"); only `host:port/db` is logged.
 */
class FlywayLogTest {

    @Test
    fun `a URL without a host authority degrades to its scheme, never throws`() {
        assertEquals("not a url at all:…", jdbcUrlForLogging("not a url at all"))
        assertEquals("", jdbcUrlForLogging("jdbc:postgresql://"))
        assertEquals("jdbc:…", jdbcUrlForLogging("jdbc:postgresql:flow"))
    }

    @Test
    fun `a query string carrying credentials never reaches the pure helper's rendered value`() {
        val marker = "marker-${UUID.randomUUID().toString().take(8)}"
        val rendered = jdbcUrlForLogging("jdbc:postgresql://localhost:5435/flow?user=admin&password=$marker")
        assertEquals("localhost:5435/flow", rendered)
        assertFalse(rendered.contains(marker))
        assertFalse(rendered.contains("password"))
    }

    @Test
    fun `a semicolon-delimited parameter block carrying credentials never reaches the rendered value`() {
        // Some JDBC URLs append properties after a `;` rather than a `?query` — sails straight
        // through java.net.URI's path component untouched (the bug this string-based port fixes).
        val marker = "marker-${UUID.randomUUID().toString().take(8)}"
        val rendered = jdbcUrlForLogging("jdbc:postgresql://db:5432/flow;password=$marker")
        assertEquals("db:5432/flow", rendered)
        assertFalse(rendered.contains(marker))
        assertFalse(rendered.contains("password"))
    }

    @Test
    fun `an unescaped slash in the userinfo password never leaks past the authority`() {
        // A password containing a raw '/' (or '@') can confuse java.net.URI's authority parsing
        // outright; substringAfterLast('@') strips userinfo without relying on strict URI syntax.
        val rendered = jdbcUrlForLogging("jdbc:postgresql://user:5678/abc@db:5432/flow")
        assertEquals("db:5432/flow", rendered)
        assertFalse(rendered.contains("5678"))
        assertFalse(rendered.contains("abc"))
    }

    @Test
    fun `startup logs the host and database but never a credential riding the JDBC URL's query string`() =
        testApplication {
            val marker = "marker-${UUID.randomUUID().toString().take(8)}"
            val separator = if (PostgresTestSupport.jdbcUrl.contains("?")) "&" else "?"
            val urlWithMarker = "${PostgresTestSupport.jdbcUrl}${separator}ApplicationName=$marker"
            configureApp("postgres.jdbcUrl" to urlWithMarker)
            // The Flyway module logs on Application.log, whose NAME the test engine chooses
            // ("io.ktor.test" under testApplication, "io.ktor.server.Application" under EngineMain):
            // capture the ROOT logger, which every child's INFO event propagates to.
            val appLog = LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME)
            try {
                startApplication()
                val event = appLog.events.firstOrNull { it.formattedMessage.startsWith("Running Flyway migrations") }
                assertNotNull(event, "expected the Flyway startup log line")
                assertFalse(
                    event.formattedMessage.contains(marker),
                    "the query-string marker must never reach the log line",
                )
                assertTrue(
                    event.formattedMessage.contains(jdbcUrlForLogging(PostgresTestSupport.jdbcUrl)),
                    "expected the host/db to still be logged, got '${event.formattedMessage}'",
                )
            } finally {
                appLog.detach()
            }
        }
}
