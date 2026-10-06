package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.plugins.RateLimitAuditThrottle
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-IP login rate limit (Ktor RateLimit in auth/AuthRoutes.kt): exhausting the bucket
 * answers 429 — and StatusPages dresses the plugin's bodiless rejection in the same RFC 7807
 * problem body every other error carries.
 */
class RateLimitResponseTest {

    @Test
    fun `exhausting the login bucket answers a 429 problem body`() = testApplication {
        configureApp("security.rateLimit.loginPerMinute" to "3")
        startApplication()
        val client = jsonClient()

        repeat(3) {
            client.login(uniqueEmail("bucket"), "whatever")
        }
        val throttled = client.login(uniqueEmail("bucket"), "whatever")

        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
        assertTrue(
            throttled.headers["Content-Type"]?.startsWith("application/problem+json") == true,
            "the RateLimit plugin's bodiless 429 must be dressed as problem+json",
        )
        assertContains(throttled.bodyAsText(), "Rate limit exceeded")
    }

    @Test
    fun `a per-IP rejection is audited with the bucket and the path only, coalesced to one event per window`() = testApplication {
        configureApp("security.rateLimit.loginPerMinute" to "3")
        startApplication()
        val client = jsonClient()
        withAuditCapture { capture ->
            repeat(3) { client.login(uniqueEmail("bucket-audit"), "whatever") }
            val throttled = client.postJson("/api/v1/login?probe=secret-value", LoginRequest(uniqueEmail("bucket-audit"), "whatever"))
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
            repeat(4) { assertEquals(HttpStatusCode.TooManyRequests, client.login(uniqueEmail("bucket-audit"), "whatever").status) }
            val events = capture.events.filter { it.message == "rate_limit.exceeded" }
            assertEquals(1, events.size, "five rejections inside one window fold into ONE audit event")
            val event = events.single()
            assertTrue(event.hasKeyValue("bucket", "login"))
            assertTrue(event.hasKeyValue("method", "POST"))
            assertTrue(event.hasKeyValue("path", "/api/v1/login"), "the path carries no query string")
            assertTrue(event.hasKeyValue("suppressed", 0L), "the first event of a window has nothing folded into it")
            assertTrue(event.keyValuePairs.none { "secret-value" in it.value.toString() }, "no query value in the event")
            assertTrue(event.keyValuePairs.none { it.key == "remoteHost" || it.key == "ip" }, "no client address in the event")
        }
    }

    @Test
    fun `the audit throttle emits once per bucket per window and counts what it folded`() {
        var now = 0L
        val throttle = RateLimitAuditThrottle(intervalMs = 60_000, clock = { now })
        assertEquals(0L, throttle.admit("login"), "the first rejection emits, nothing suppressed yet")
        assertEquals(0L, throttle.admit("mfa"), "another bucket has its own window")
        now = 1_000
        assertEquals(null, throttle.admit("login"))
        now = 59_999
        assertEquals(null, throttle.admit("login"))
        assertEquals(null, throttle.admit("mfa"))
        now = 60_000
        assertEquals(2L, throttle.admit("login"), "the next window's event carries the two folded rejections")
        assertEquals(1L, throttle.admit("mfa"))
        now = 120_000
        assertEquals(0L, throttle.admit("login"), "an idle window folds nothing")
    }
}
