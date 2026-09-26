package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proxy trust (plugins/Http.kt — `http.behindProxy` + `http.proxyHops`): the per-IP rate-limit
 * buckets key on the client address the TRUSTED proxy reported, read from the END of
 * X-Forwarded-For — never the first value, which is whatever the client sent when a proxy
 * appends instead of replacing (Lettuce's v3.6.2 hardening, ported via Toadie). Every attempt
 * uses a distinct unknown email so the per-account lockout (5 per email) never trips, and the
 * login bucket is pinned to 10 like RateLimitResponseTest; every testApplication boots a fresh
 * app, so buckets never leak.
 */
class ForwardedHeadersTest {

    private suspend fun HttpClient.login(forwardedFor: String?): HttpStatusCode =
        post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            if (forwardedFor != null) header(HttpHeaders.XForwardedFor, forwardedFor)
            setBody(LoginRequest(uniqueEmail("xff"), "wrong"))
        }.status

    @Test
    fun `behind a proxy the login bucket keys on the LAST X-Forwarded-For value`() = testApplication {
        configureApp("http.behindProxy" to "true", "security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()
        // The first value rotates (a spoofing client); the last is what the proxy appended.
        repeat(10) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.$i.1, 203.0.113.7"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("10.9.99.1, 203.0.113.7"))
        // A different proxy-reported address is a different client.
        assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.99.1, 203.0.113.8"))
    }

    @Test
    fun `proxyHops 2 trusts the value before the last one`() = testApplication {
        configureApp(
            "http.behindProxy" to "true",
            "http.proxyHops" to "2",
            "security.rateLimit.loginPerMinute" to "10",
        )
        startApplication()
        val client = jsonClient()
        // A client-supplied spoof, then what the first trusted proxy appended, then the second's.
        repeat(10) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.$i.1, 203.0.113.7, 198.51.100.1"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("10.9.99.1, 203.0.113.7, 198.51.100.1"))
        assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.99.1, 203.0.113.9, 198.51.100.1"))
    }

    @Test
    fun `without behindProxy X-Forwarded-For is ignored`() = testApplication {
        configureApp("security.rateLimit.loginPerMinute" to "10")
        startApplication()
        val client = jsonClient()
        // Every attempt claims a different address; all of them share the direct client's bucket.
        repeat(10) { i ->
            assertEquals(HttpStatusCode.Unauthorized, client.login("10.9.$i.1"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("10.9.99.1"))
    }

    @Test
    fun `a non-numeric proxyHops fails startup naming the key`() = testApplication {
        configureApp("http.behindProxy" to "true", "http.proxyHops" to "abc")
        assertStartupFails("Config \"http.proxyHops\" must be an integer") { startApplication() }
    }

    @Test
    fun `proxyHops below 1 fails startup`() = testApplication {
        configureApp("http.behindProxy" to "true", "http.proxyHops" to "0")
        assertStartupFails("http.proxyHops") { startApplication() }
    }
}
