package ch.nokillswit

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
}
