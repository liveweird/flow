package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.log
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory

class LogoutTest {

    @Test
    fun `logout revokes the access token - a repeat call with it is 401`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("logout")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw").body<LoginResponse>()

        val logout = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)

        // The jti is on the blocklist now: the same bearer no longer authenticates.
        val replay = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
    }

    @Test
    fun `a logout clears the browser cache the report GETs may have filled`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("logout-csd")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw").body<LoginResponse>()

        val logout = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)
        assertEquals("\"cache\"", logout.headers["Clear-Site-Data"], "Clear-Site-Data with the quoted cache directive")
        // A rejected logout (no valid token) clears nothing.
        val replay = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
        assertEquals(null, replay.headers["Clear-Site-Data"])
    }

    @Test
    fun `logout without a token is 401`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().post("/api/v1/logout")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a malformed logout body's parse failure never logs the raw exception or its message`() =
        testApplication {
            usePostgresTestcontainer()
            val email = uniqueEmail("logout-malformed")
            TestUsers.seed(email = email, password = "pw")
            val client = jsonClient()
            val session = client.login(email, "pw").body<LoginResponse>()

            // A distinctive marker standing in for the body excerpt kotlinx's decode-error
            // message would otherwise embed — the same errorType-only rule as
            // login.mfa_send_failed/password_reset.send_failed (.claude/docs/observability.md).
            val marker = "zzz-marker-${UUID.randomUUID().toString().take(8)}"

            // The event never reaches an appender above its logger's level: force DEBUG for
            // this test and restore it in `finally` (io.ktor.server.Application defaults to
            // the root INFO level).
            // Resolve the application logger by its RUNTIME name — the test engine names it
            // differently from EngineMain — so the level flip and the capture hit the real one.
            val loggerName = application.log.name
            val logger = LoggerFactory.getLogger(loggerName) as Logger
            val originalLevel = logger.level
            logger.level = Level.DEBUG
            val appLog = LogCapture(loggerName)
            try {
                val response = client.post("/api/v1/logout") {
                    header(HttpHeaders.Authorization, "Bearer ${session.token}")
                    contentType(ContentType.Application.Json)
                    setBody("""{ not valid json $marker""")
                }
                // Best-effort by design: a malformed body never blocks logout itself.
                assertEquals(HttpStatusCode.NoContent, response.status)

                // The debug line itself must have been captured (otherwise the leak check below
                // would pass vacuously) and must name the exception CLASS only.
                val debugLine = appLog.events.firstOrNull { it.formattedMessage.startsWith("Logout body unparsable") }
                assertNotNull(debugLine, "expected the logout parse-failure debug line to be captured")
                assertTrue(debugLine.throwableProxy == null, "the throwable itself must not be logged")

                val leaked = appLog.events.any { event ->
                    event.formattedMessage?.contains(marker) == true ||
                        event.message?.contains(marker) == true ||
                        event.argumentArray?.any { it?.toString()?.contains(marker) == true } == true ||
                        event.throwableProxy?.message?.contains(marker) == true
                }
                assertFalse(leaked, "the malformed body's content must never reach the application log")
            } finally {
                logger.level = originalLevel
                appLog.detach()
            }
        }

    @Test
    fun `an invalid refresh token on logout never logs the raw exception`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("logout-badrefresh")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw").body<LoginResponse>()

        val loggerName = application.log.name
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val originalLevel = logger.level
        logger.level = Level.DEBUG
        val appLog = LogCapture(loggerName)
        try {
            val response = client.post("/api/v1/logout") {
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
                contentType(ContentType.Application.Json)
                // Not a JWT auth0 can even attempt to decode structurally — but the point
                // stands for any shape: auth0's JWTDecodeException message can embed a decoded
                // token segment, so the CAUSE itself (not just its class name) must never reach
                // the application log.
                setBody("""{"refreshToken":"not-a-jwt"}""")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)

            val debugLine = appLog.events.firstOrNull {
                it.formattedMessage.startsWith("Logout refresh token invalid")
            }
            assertNotNull(debugLine, "expected the invalid-refresh-token debug line to be captured")
            assertTrue(debugLine.throwableProxy == null, "the throwable itself must not be logged")
        } finally {
            logger.level = originalLevel
            appLog.detach()
        }
    }
}
