package ch.nokillswit

import ch.nokillswit.plugins.HealthResponse
import ch.nokillswit.plugins.ReadinessProbe
import ch.nokillswit.plugins.ReadinessProbeKey
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The two public probes (plugins/Health.kt): liveness answers regardless, readiness follows the database round trip. */
class HealthTest {

    @Test
    fun `health and ready answer 200 without a token`() = testApplication {
        usePostgresTestcontainer()
        val anon = jsonClient()
        val health = anon.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("ok", health.body<HealthResponse>().status)
        val ready = anon.get("/api/v1/ready")
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals("ok", ready.body<HealthResponse>().status)
    }

    @Test
    fun `ready answers a 503 problem while the database probe fails`() = testApplication {
        configureApp()
        application { attributes.put(ReadinessProbeKey, ReadinessProbe { false }) }
        startApplication()
        val ready = jsonClient().get("/api/v1/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, ready.status)
        assertEquals("application/problem+json", ready.contentType()?.withoutParameters().toString())
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/health").status, "liveness never depends on the database")
    }

    @Test
    fun `ready is rate limited per IP and answers a 429 problem past the bucket while health stays 200`() = testApplication {
        // The bucket lives in this application instance, so the burst cannot starve another test's probes.
        configureApp("security.rateLimit.readyPerMinute" to "5")
        application { attributes.put(ReadinessProbeKey, ReadinessProbe { true }) }
        startApplication()
        val anon = jsonClient()
        withAuditCapture { capture ->
            repeat(5) { assertEquals(HttpStatusCode.OK, anon.get("/api/v1/ready").status) }
            val throttled = anon.get("/api/v1/ready")
            assertEquals(HttpStatusCode.TooManyRequests, throttled.status)
            assertEquals("application/problem+json", throttled.contentType()?.withoutParameters().toString())
            assertNotNull(throttled.headers[HttpHeaders.RetryAfter], "the plugin's Retry-After survives the problem dressing")
            repeat(3) { assertEquals(HttpStatusCode.TooManyRequests, anon.get("/api/v1/ready").status) }

            repeat(20) { assertEquals(HttpStatusCode.OK, anon.get("/api/v1/health").status, "liveness is unbucketed") }

            val events = capture.events.filter { it.message == "rate_limit.exceeded" }
            assertEquals(1, events.size, "the rejections coalesce into one audit event")
            assertTrue(events.single().hasKeyValue("bucket", "ready"))
            assertTrue(events.single().hasKeyValue("path", "/api/v1/ready"))
        }
    }
}
