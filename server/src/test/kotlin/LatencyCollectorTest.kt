package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.plugins.MetricRegistryKey
import ch.nokillswit.telemetry.LatencyCollector
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.TelemetryOverrides
import ch.nokillswit.telemetry.TelemetryOverridesKey
import ch.nokillswit.telemetry.TelemetrySamplerKey
import ch.nokillswit.telemetry.normaliseRoute
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OK_ROUTE = "/api/v1/reports/zz-latency-ok"
private const val BOOM_ROUTE = "/api/v1/reports/zz-latency-boom"
private const val SLOW_DOWN_ROUTE = "/api/v1/reports/zz-latency-slow-down"
private const val THROWN_ROUTE = "/api/v1/reports/zz-latency-thrown"
private const val OTHER_ROUTE = "/api/v1/zz-latency-other"
private const val TELEMETRY_ROUTE = "/api/v1/telemetry/zz-latency"
private const val PAGE_ROUTE = "/zz-latency-page"
private const val HANDLER_DELAY_MILLIS = 25L

/**
 * `telemetry/LatencyCollector.kt` over a real web `testApplication`: Ktor's Dropwizard series become per-report-route and `*` rows
 * (count and 5xx/429 deltas, p50/p95 of the windowed timers), the telemetry's own routes and non-API paths stay out of the total, and the
 * series-name normalisation is pinned against the names Ktor 3.6 really writes, so an upgrade that renames them fails here instead of
 * silently emptying the panel. Synthetic routes stand in for the failures a report cannot be made to produce on demand (the default
 * test `client`, which bypasses the OpenAPI conformance plugin, calls them).
 */
class LatencyCollectorTest {
    private val store = telemetryStore()

    @Before
    fun emptyOps() = runBlocking { wipeTelemetry(store) }

    private fun Application.syntheticRoutes() {
        routing {
            get(OK_ROUTE) {
                delay(HANDLER_DELAY_MILLIS)
                call.respondText("ok")
            }
            get(BOOM_ROUTE) { call.respond(HttpStatusCode.InternalServerError) }
            // A bodiless 429, as the RateLimit plugin answers: StatusPages answers it AGAIN with the problem body, which Ktor's status
            // meter counts as a second 429 (the collector corrects for it). The thrown one is answered once.
            get(SLOW_DOWN_ROUTE) { call.respond(HttpStatusCode.TooManyRequests) }
            get(THROWN_ROUTE) { throw TooManyRequestsException("slow down") }
            get("$BOOM_ROUTE-thrown") { error("boom") }
            get(OTHER_ROUTE) { call.respond(HttpStatusCode.BadGateway) }
            get(TELEMETRY_ROUTE) { call.respondText("t") }
            get(PAGE_ROUTE) { call.respondText("page") }
        }
    }

    private fun List<Reading>.value(metric: ch.nokillswit.telemetry.MetricDef, label: String): Double? =
        firstOrNull { it.metric == metric && it.label == label }?.value

    private fun List<Reading>.labels(): Set<String> = map { it.label }.toSet()

    /** A started web application with the synthetic routes, a collector over its registry and the ADMIN client's login already counted. */
    private fun withCollector(block: suspend ApplicationTestBuilder.(LatencyCollector) -> Unit) = testApplication {
        configureApp()
        application { syntheticRoutes() }
        startApplication()
        val collector = LatencyCollector(application.attributes[MetricRegistryKey])
        collector.collect() // the baseline: whatever the startup and the login counted
        block(collector)
    }

    @Test
    fun `a route's count and percentiles come from its timer, the total adds every API route`() = withCollector { collector ->
        val admin = seededClient("lat", FlowUserRole.ADMIN)
        collector.collect() // the login
        repeat(4) { client.get(OK_ROUTE) }
        admin.get("/api/v1/reports/throughput")

        val readings = collector.collect()
        assertEquals(4.0, readings.value(TelemetryMetrics.HTTP_COUNT, OK_ROUTE))
        val p50 = assertNotNull(readings.value(TelemetryMetrics.HTTP_P50, OK_ROUTE))
        val p95 = assertNotNull(readings.value(TelemetryMetrics.HTTP_P95, OK_ROUTE))
        assertTrue(p50 >= HANDLER_DELAY_MILLIS - 5 && p95 >= p50, "milliseconds of a $HANDLER_DELAY_MILLIS ms handler: p50 $p50 p95 $p95")
        assertEquals(1.0, readings.value(TelemetryMetrics.HTTP_COUNT, "/api/v1/reports/throughput"), "the real route, selectors stripped")
        assertEquals(5.0, readings.value(TelemetryMetrics.HTTP_COUNT, "*"))
        val totalP95 = assertNotNull(readings.value(TelemetryMetrics.HTTP_P95, "*"))
        assertTrue(totalP95 >= p50, "the total's percentile spans every route's observations")
    }

    @Test
    fun `counts are deltas of the previous collection, and a label with nothing new has no row`() = withCollector { collector ->
        repeat(3) { client.get(OK_ROUTE) }
        assertEquals(3.0, collector.collect().value(TelemetryMetrics.HTTP_COUNT, OK_ROUTE))
        assertEquals(emptyList(), collector.collect().filter { it.label == OK_ROUTE }, "an idle interval writes nothing for the route")
        client.get(OK_ROUTE)
        val next = collector.collect()
        assertEquals(1.0, next.value(TelemetryMetrics.HTTP_COUNT, OK_ROUTE), "the next interval's own delta, not the running total")
    }

    @Test
    fun `5xx and 429 responses are counted per report route and in the total`() = withCollector { collector ->
        repeat(2) { client.get(BOOM_ROUTE) }
        client.get("$BOOM_ROUTE-thrown")
        repeat(3) { client.get(SLOW_DOWN_ROUTE) }
        repeat(2) { client.get(THROWN_ROUTE) }
        repeat(3) { client.get(OTHER_ROUTE) } // a 502 on a non-report API route: in the total only

        val readings = collector.collect()
        assertEquals(2.0, readings.value(TelemetryMetrics.HTTP_5XX, BOOM_ROUTE))
        assertEquals(1.0, readings.value(TelemetryMetrics.HTTP_5XX, "$BOOM_ROUTE-thrown"), "a thrown exception is one 5xx")
        assertNull(readings.value(TelemetryMetrics.HTTP_429, BOOM_ROUTE))
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_429, SLOW_DOWN_ROUTE), "a bodiless 429 is counted once, not twice")
        assertEquals(2.0, readings.value(TelemetryMetrics.HTTP_429, THROWN_ROUTE))
        assertNull(readings.value(TelemetryMetrics.HTTP_5XX, SLOW_DOWN_ROUTE))
        assertEquals(6.0, readings.value(TelemetryMetrics.HTTP_5XX, "*"))
        assertEquals(5.0, readings.value(TelemetryMetrics.HTTP_429, "*"))
        assertEquals(11.0, readings.value(TelemetryMetrics.HTTP_COUNT, "*"))
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_COUNT, SLOW_DOWN_ROUTE), "the request count is the timer's, never doubled")
        assertNull(readings.value(TelemetryMetrics.HTTP_COUNT, OTHER_ROUTE), "only report routes get a label of their own")
    }

    @Test
    fun `the telemetry's own routes, the probes and non-API paths stay out of the total`() = withCollector { collector ->
        repeat(3) { client.get(TELEMETRY_ROUTE) }
        repeat(2) { client.get(PAGE_ROUTE) }
        repeat(4) { client.get("/api/v1/health") }
        client.get("/api/v1/ready")
        assertEquals(emptyList(), collector.collect().filter { it.metric.group == ch.nokillswit.telemetry.MetricGroup.REQUESTS })

        client.get(OK_ROUTE)
        assertEquals(1.0, collector.collect().value(TelemetryMetrics.HTTP_COUNT, "*"))
    }

    @Test
    fun `a real rate-limited report route counts each 429 once, a query string notwithstanding`() = testApplication {
        configureApp("security.rateLimit.reportsPerMinute" to "2")
        startApplication()
        val email = uniqueEmail("lat-limit")
        TestUsers.seed(email, "pw")
        val user = authedClient(email, "pw")
        val collector = LatencyCollector(application.attributes[MetricRegistryKey])
        collector.collect() // the login
        val route = "/api/v1/reports/filters"
        repeat(2) { assertEquals(HttpStatusCode.OK, user.get("$route?probe=a$it").status) }
        repeat(3) { assertEquals(HttpStatusCode.TooManyRequests, user.get("$route?probe=b$it").status) }

        val readings = collector.collect()
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_429, route), "the bucket's three rejections, not six")
        assertEquals(3.0, readings.value(TelemetryMetrics.HTTP_429, "*"))
        assertEquals(5.0, readings.value(TelemetryMetrics.HTTP_COUNT, route), "two answered, three rejected: five requests")
        assertNull(readings.value(TelemetryMetrics.HTTP_5XX, route))
        // StatusPages' second answer to a bodiless 429 is marked under a fallback name (the URI, query included): never read.
        assertTrue(readings.none { '?' in it.label }, "no label carries a query string: ${readings.map { it.label }}")
    }

    @Test
    fun `an unrouted request leaves no row`() = withCollector { collector ->
        client.get(OK_ROUTE)
        collector.collect()
        client.get("/api/v1/reports/zz-latency-unknown?q=1") // unrouted: a 404 meter under its URI-named fallback, no timer
        val readings = collector.collect()
        assertEquals(emptyList(), readings.filter { it.label.contains("unknown") })
        assertEquals(null, readings.value(TelemetryMetrics.HTTP_COUNT, OK_ROUTE))
    }

    @Test
    fun `route normalisation drops the selectors Ktor puts in a series name`() {
        val raw = "/(authenticate \"default\")/(RateLimit reports)/api/v1/reports/throughput/(method:GET)"
        assertEquals("/api/v1/reports/throughput", normaliseRoute(raw))
        val auth = "/(authenticate \"default\")"
        val reports = "$auth/(RateLimit reports)"
        assertEquals("/api/v1/reports/throughput/items", normaliseRoute("$reports/api/v1/reports/throughput/items/(method:GET)"))
        assertEquals("/api/v1/teams", normaliseRoute("$auth/api/v1/teams/(method:GET)"))
        assertEquals("/api/v1/data-sources/{id}", normaliseRoute("$auth/api/v1/data-sources/{id}/(method:GET)"))
        assertEquals("/api/v1/login", normaliseRoute("/(RateLimit login)/api/v1/login/(method:POST)"))
        assertEquals("/api/v1/health", normaliseRoute("/api/v1/health/(method:GET)"))
        assertEquals("/", normaliseRoute("/(method:GET)"))
    }

    @Test
    fun `the series Ktor really writes normalise to the labels the panel will read`() = testApplication {
        configureApp()
        application { syntheticRoutes() }
        startApplication()
        val admin = seededClient("lat-names", FlowUserRole.ADMIN)
        admin.get("/api/v1/reports/throughput")
        client.get(OK_ROUTE)
        val names = application.attributes[MetricRegistryKey].timers.keys
        // The raw names, with the whole selector chain: if Ktor stops writing them this way the normaliser's input has changed.
        val raw = names.single { it.contains("reports/throughput") }
        assertTrue(raw.startsWith("ktor.calls./") && raw.endsWith(".timer") && raw.contains("(method:GET)"), raw)
        assertTrue(raw.contains("(authenticate"), raw)
        assertTrue("ktor.calls.$OK_ROUTE/(method:GET).timer" in names, "$names")
        assertEquals("/api/v1/reports/throughput", normaliseRoute(raw.removePrefix("ktor.calls.").removeSuffix(".timer")))
    }

    private fun runSampler(role: String): Pair<Set<String>, Set<String>> = runBlocking {
        val identity = testIdentity(role = role)
        var app: Application? = null
        var labels = emptySet<String>()
        var metrics = emptySet<String>()
        testApplication {
            configureApp("app.role" to role, "telemetry.enabled" to "true")
            application {
                app = this
                syntheticRoutes()
                attributes.put(TelemetryOverridesKey, TelemetryOverrides(identity = identity, firstTickDelayMillis = Long.MAX_VALUE / 4))
            }
            startApplication()
            client.get(OK_ROUTE) // registered by the test in either role: only a web process times it
            val sampler = assertNotNull(assertNotNull(app).attributes.getOrNull(TelemetrySamplerKey))
            sampler.tick()
            val rows = store.samples(assertNotNull(store.processId(identity))).filter { it.metric.startsWith("http.") }
            labels = rows.map { it.label }.toSet()
            metrics = rows.map { it.metric }.toSet()
        }
        labels to metrics
    }

    @Test
    fun `a web process samples its requests, a worker process does not`() {
        val (webLabels, webMetrics) = runSampler("web")
        assertEquals(setOf("*", OK_ROUTE), webLabels)
        assertTrue("http.count" in webMetrics && "http.p50" in webMetrics && "http.p95" in webMetrics, "$webMetrics")
        val (workerLabels, _) = runSampler("worker")
        assertEquals(emptySet(), workerLabels, "the worker serves only probes: it writes no request rows")
    }
}
