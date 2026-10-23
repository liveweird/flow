package ch.nokillswit

import ch.nokillswit.ingest.IngestClockKey
import ch.nokillswit.ingest.WorkerLivenessTrackerKey
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `/api/v1/ready` on a role that runs the ingest worker also answers for the scan loop (`plugins/Health.kt`,
 * `ingest/WorkerLiveness.kt`, checkup D9-2): fresh -> 200, a last completed tick older than the threshold -> 503.
 * The worker's clock is the real one plus an offset the test moves; the tick interval is the maximum (300 s) so the loop
 * cannot stamp again behind the test's back.
 */
class WorkerReadinessTest {

    private val offsetMillis = AtomicLong()
    private val skewedClock: () -> Long = { System.currentTimeMillis() + offsetMillis.get() }

    @Test
    fun `a worker role is ready while its scan loop ticks and not ready once the tick is stale`() = testApplication {
        configureApp("app.role" to "worker", "ingest.schedulerTickSeconds" to "300")
        lateinit var app: Application
        application {
            attributes.put(IngestClockKey, skewedClock)
            app = this
        }
        startApplication()
        awaitFirstTick(app)

        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
        offsetMillis.set(899_000)
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status, "within 3 x 300 s")
        offsetMillis.set(901_000)
        val stale = jsonClient().get("/api/v1/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, stale.status)
        assertEquals("application/problem+json", stale.contentType()?.withoutParameters().toString())
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/health").status, "liveness never depends on the scan loop")
    }

    @Test
    fun `the all role is governed the same way`() = testApplication {
        configureApp("app.role" to "all", "ingest.schedulerTickSeconds" to "300")
        lateinit var app: Application
        application {
            attributes.put(IngestClockKey, skewedClock)
            app = this
        }
        startApplication()
        awaitFirstTick(app)

        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
        offsetMillis.set(901_000)
        assertEquals(HttpStatusCode.ServiceUnavailable, jsonClient().get("/api/v1/ready").status)
    }

    @Test
    fun `the web role runs no scan loop and its readiness is unaffected`() = testApplication {
        configureApp("app.role" to "web", "ingest.schedulerTickSeconds" to "300")
        application { attributes.put(IngestClockKey, skewedClock) }
        startApplication()

        offsetMillis.set(10_000_000)
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
    }

    /** The loop starts on ApplicationStarted: wait until it has stamped once, or a later clock move would be stamped over. */
    private fun awaitFirstTick(app: Application) = runBlocking {
        withTimeout(30_000) {
            while (app.attributes.getOrNull(WorkerLivenessTrackerKey)?.completedTicks?.get()?.let { it < 1 } != false) delay(25)
        }
    }
}
