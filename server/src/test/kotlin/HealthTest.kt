package ch.nokillswit

import ch.nokillswit.plugins.HealthResponse
import ch.nokillswit.plugins.ReadinessProbe
import ch.nokillswit.plugins.ReadinessProbeKey
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
