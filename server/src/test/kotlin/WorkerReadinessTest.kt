package ch.nokillswit

import ch.nokillswit.ingest.IngestTimeSourceKey
import ch.nokillswit.ingest.WorkerLivenessTrackerKey
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * `/api/v1/ready` on a role that runs the ingest worker also answers for the scan loop (`plugins/Health.kt`,
 * `ingest/WorkerLiveness.kt`, checkup D9-2): fresh -> 200, a last completed tick older than the threshold -> 503.
 * The worker's monotonic time source is a [TestTimeSource] the test moves; the tick interval is the maximum (300 s, threshold
 * 900 s) so the loop cannot stamp again behind the test's back.
 */
class WorkerReadinessTest {

    private val time = TestTimeSource()

    @Test
    fun `a worker role is ready while its scan loop ticks and not ready once the tick is stale`() = testApplication {
        configureApp("app.role" to "worker", "ingest.schedulerTickSeconds" to "300")
        lateinit var app: Application
        application {
            attributes.put(IngestTimeSourceKey, time)
            app = this
        }
        startApplication()
        awaitFirstTick(app)

        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
        time += 800.seconds
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status, "800 s into the 900 s threshold")
        time += 101.seconds
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
            attributes.put(IngestTimeSourceKey, time)
            app = this
        }
        startApplication()
        awaitFirstTick(app)

        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
        time += 901.seconds
        assertEquals(HttpStatusCode.ServiceUnavailable, jsonClient().get("/api/v1/ready").status)
    }

    @Test
    fun `permits leaked across ticks make a worker not ready although every tick completes`() = testApplication {
        configureApp("app.role" to "worker", "ingest.schedulerTickSeconds" to "300")
        lateinit var app: Application
        application {
            attributes.put(IngestTimeSourceKey, time)
            app = this
        }
        startApplication()
        awaitFirstTick(app)
        val tracker = app.attributes[WorkerLivenessTrackerKey]
        val log = LoggerFactory.getLogger(WorkerReadinessTest::class.java)

        // The worker's own accounting is proven in IngestWorkerTest; here the tracker is fed the sample a leak produces.
        runBlocking { tracker.completingTick(log, { 1 }) { } }
        time += 800.seconds
        runBlocking { tracker.completingTick(log, { 1 }) { } }
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status, "800 s of leaking, the last tick 0 s old")
        time += 101.seconds
        assertEquals(HttpStatusCode.ServiceUnavailable, jsonClient().get("/api/v1/ready").status, "901 s of unbroken leaking")
    }

    @Test
    fun `a busy worker whose slots are held by running jobs stays ready`() = testApplication {
        configureApp("app.role" to "worker", "ingest.schedulerTickSeconds" to "300")
        lateinit var app: Application
        application {
            attributes.put(IngestTimeSourceKey, time)
            app = this
        }
        startApplication()
        awaitFirstTick(app)
        val tracker = app.attributes[WorkerLivenessTrackerKey]
        val log = LoggerFactory.getLogger(WorkerReadinessTest::class.java)

        repeat(4) {
            runBlocking { tracker.completingTick(log, { 0 }) { } } // every slot held by a RUNNING job: no permit beyond the jobs
            time += 300.seconds
        }
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
    }

    @Test
    fun `the web role runs no scan loop and its readiness is unaffected`() = testApplication {
        configureApp("app.role" to "web", "ingest.schedulerTickSeconds" to "300")
        application { attributes.put(IngestTimeSourceKey, time) }
        startApplication()

        time += 10_000.seconds
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
    }

    /** The loop starts on ApplicationStarted: wait until it has stamped once, or a later clock move would be stamped over. */
    private fun awaitFirstTick(app: Application) = runBlocking {
        withTimeout(30_000) {
            while (app.attributes.getOrNull(WorkerLivenessTrackerKey)?.completedTicks?.get()?.let { it < 1 } != false) delay(25)
        }
    }
}
