package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.telemetry.HistoryGroup
import ch.nokillswit.telemetry.HistoryRange
import ch.nokillswit.telemetry.TelemetryCurrent
import ch.nokillswit.telemetry.TelemetryHistory
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.TelemetryOverrides
import ch.nokillswit.telemetry.TelemetryOverridesKey
import ch.nokillswit.telemetry.TelemetrySeries
import ch.nokillswit.telemetry.hourFloor
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The telemetry read API (`telemetry/TelemetryRoutes.kt`, `GET /api/v1/telemetry/current` and `/history`): ADMIN only, 403 before
 * 400, and the shapes the panel reads - over rows seeded straight into `ops` and into the job tables, never over a running sampler.
 *
 * The clock is pinned (`TelemetryOverridesKey`) 1000 days in the past, hour-aligned plus 20 minutes: the shared database holds the
 * job and DERIVE rows of every other class, all stamped with the real time, so the job aggregations (which end at the pinned `now`)
 * see only what this class seeds. `ops` is the telemetry tests' own schema, emptied before each test.
 */
class TelemetryRoutesTest {
    private val store = telemetryStore()

    @Before
    fun emptyOps() = runBlocking { wipeTelemetry(store) }

    private val now = hourFloor(System.currentTimeMillis() - 1000 * TELE_DAY) + 20 * TELE_MINUTE
    private val hourStart = hourFloor(now)

    /** The newest completed raw bucket at the pinned `now`. */
    private val latest = now - 5 * TELE_MINUTE

    private suspend fun ApplicationTestBuilder.boot(vararg overrides: Pair<String, String>) {
        configureApp("app.role" to "web", *overrides)
        val clockNow = now
        application { attributes.put(TelemetryOverridesKey, TelemetryOverrides(clock = { clockNow })) }
        startApplication()
    }

    private suspend fun HttpClient.current(): TelemetryCurrent {
        val response = get("/api/v1/telemetry/current")
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun HttpClient.history(group: String, range: String): TelemetryHistory {
        val response = get("/api/v1/telemetry/history?group=$group&range=$range")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private fun TelemetryHistory.series(key: String): TelemetrySeries =
        assertNotNull(series.firstOrNull { it.key == key }, "series $key in ${series.map { it.key }}")

    private fun TelemetrySeries.ts() = points.map { it.t }
    private fun TelemetrySeries.avgs() = points.map { it.avg }
    private fun TelemetrySeries.maxes() = points.map { it.max }

    // ---- guard and parsing ---------------------------------------------------------------------------------------------

    @Test
    fun `an ADMIN reads an empty ops, every group and range`() = testApplication {
        boot()
        val admin = seededClient("telemetry-empty", FlowUserRole.ADMIN)

        val current = admin.current()
        assertEquals(now, current.generatedAt)
        assertEquals(TELE_RAW_SECONDS, current.sampleIntervalSeconds)
        assertTrue(current.processes.isEmpty())
        assertNull(current.storage)
        assertNull(current.requests.measuredAt)
        assertEquals(TELE_RAW_SECONDS, current.requests.windowSeconds)
        assertEquals(0L, current.requests.total.count)
        assertNull(current.requests.total.p50Ms)
        assertTrue(current.requests.routes.isEmpty())

        val buckets = mapOf("24h" to TELE_RAW_SECONDS, "7d" to 3600, "90d" to 21600)
        for (group in HistoryGroup.entries) {
            for (range in HistoryRange.entries) {
                val history = admin.history(group.param, range.param)
                assertEquals(group.param, history.group)
                assertEquals(range.param, history.range)
                assertEquals(buckets.getValue(range.param), history.bucketSeconds, "${group.param} ${range.param}")
                assertEquals(now - range.millis, history.from)
                assertEquals(now, history.to)
                assertFalse(history.truncated, "${group.param} ${range.param}")
                assertTrue(history.series.size <= 102)
                if (group != HistoryGroup.SYNC && group != HistoryGroup.DERIVE) {
                    assertTrue(history.series.isEmpty(), "${group.param} ${range.param}: no samples, no series")
                    assertEquals(now, history.historyFrom, "no sampler ever ran: nothing to hold")
                }
            }
        }
    }

    @Test
    fun `a USER is refused 403 on both routes, before the query is read, and the denial is audited`() = testApplication {
        boot()
        val user = seededClient("telemetry-user")
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/current").status)
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/history?group=memory&range=24h").status)
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/history?group=nope&range=never").status, "403 outranks 400")
            assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/telemetry/history").status, "403 outranks 400")
            assertNotNull(capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", "/api/v1/telemetry/current") })
            assertNotNull(capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", "/api/v1/telemetry/history") })
        }
    }

    @Test
    fun `an unknown, missing or repeated group or range is a 400 for an ADMIN`() = testApplication {
        boot()
        val admin = seededClient("telemetry-400", FlowUserRole.ADMIN)
        for (query in listOf(
            "", "?range=24h", "?group=memory", "?group=nope&range=24h", "?group=memory&range=1h", "?group=MEMORY&range=24h",
            "?group=memory&range=24h&group=jvm", "?group=memory&range=24h&range=7d", "?group=&range=24h",
        )) {
            assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/telemetry/history$query").status, query)
        }
    }

    @Test
    fun `the worker role serves no telemetry route`() = testApplication {
        configureApp("app.role" to "worker")
        startApplication()
        // The default client on purpose (RoleTest): an undeclared 404 is drift as far as OpenApiConformance is concerned.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/telemetry/current").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/telemetry/history?group=memory&range=24h").status)
    }

    // ---- current -------------------------------------------------------------------------------------------------------

    @Test
    fun `current lists the live processes with their latest sample and the percentage of the container limit`() = testApplication {
        boot()
        val webLimit = 512L shl 20
        val web = store.seedProcess("web", lastSeen = now - 10_000, startedAt = now - 3 * TELE_HOUR, limitBytes = webLimit)
        val worker = store.seedProcess("worker", lastSeen = now - 20_000)
        val replaced = store.seedProcess("web", lastSeen = now - 2 * TELE_HOUR) // stale long ago, its role is alive: not listed
        val wedged = store.seedProcess("web", lastSeen = now - 30 * TELE_MINUTE) // stale but seen within the hour: listed, flagged
        val silent = store.seedProcess("worker", lastSeen = now - 10_000, startedAt = now - 3 * TELE_HOUR) // ticks but samples are old
        val stoppedAll = store.seedProcess("all", lastSeen = now - 2 * TELE_HOUR) // stale and alone in its role: listed, flagged
        val ancient = store.seedProcess("batch", lastSeen = now - 25 * TELE_HOUR) // unseen for more than a day: not listed
        val m = TelemetryMetrics
        store.put(
            // An older tick of the web process: superseded by the newest bucket, and a metric only it wrote must not be carried over.
            rawRow(web, latest - 5 * TELE_MINUTE, m.HEAP_USED, 1.0), rawRow(web, latest - 5 * TELE_MINUTE, m.CGROUP_PEAK, 777.0),
            rawRow(web, latest, m.HEAP_USED, 100_000_000.0), rawRow(web, latest, m.HEAP_COMMITTED, 150_000_000.0),
            rawRow(web, latest, m.OLD_GEN_PEAK, 60_000_000.0), rawRow(web, latest, m.NON_HEAP_USED, 70_000_000.0),
            rawRow(web, latest, m.DIRECT_BUFFERS, 1_000_000.0), rawRow(web, latest, m.RSS, 300_000_000.0),
            rawRow(web, latest, m.CGROUP_CURRENT, 280_000_000.0), rawRow(web, latest, m.CGROUP_ANON, 250_000_000.0),
            rawRow(web, latest, m.CGROUP_WORKING_SET, (256L shl 20).toDouble()),
            rawRow(web, latest, m.CPU_RATIO, 0.25), rawRow(web, latest, m.THREADS, 42.0),
            rawRow(web, latest, m.GC_COUNT, 3.0, "G1 Young"), rawRow(web, latest, m.GC_COUNT, 1.0, "G1 Old"),
            rawRow(web, latest, m.GC_TIME, 20.0, "G1 Young"), rawRow(web, latest, m.GC_TIME, 90.0, "G1 Old"),
            rawRow(web, latest, m.POOL_ACQUIRED, 2.0), rawRow(web, latest, m.POOL_IDLE, 3.0),
            rawRow(web, latest, m.POOL_PENDING, 0.0), rawRow(web, latest, m.POOL_ALLOCATED, 5.0),
            rawRow(worker, latest, m.HEAP_USED, 50_000_000.0), rawRow(worker, latest, m.CGROUP_WORKING_SET, 200_000_000.0),
            rawRow(silent, now - TELE_HOUR, m.HEAP_USED, 1.0),
        )
        val admin = seededClient("telemetry-current", FlowUserRole.ADMIN)

        val processes = admin.current().processes.associateBy { it.id }
        assertEquals(setOf(web, worker, stoppedAll, wedged, silent), processes.keys, "$replaced and $ancient are not listed")
        assertTrue(processes.getValue(wedged).stale, "a wedged replica stays visible, flagged")
        assertTrue(processes.getValue(silent).stale, "fresh ticks but a sample an hour old is stale")
        assertFalse(processes.getValue(worker).stale)

        val w = processes.getValue(web)
        assertEquals("web", w.role)
        assertEquals(now - 3 * TELE_HOUR, w.startedAt)
        assertEquals(now - 10_000, w.lastSeenAt)
        assertEquals(latest, w.sampledAt)
        assertFalse(w.stale)
        assertEquals("9.9.9", w.version)
        assertEquals("abc1234", w.commit)
        assertEquals(100_000_000L, w.memory.heapUsedBytes)
        assertEquals(150_000_000L, w.memory.heapCommittedBytes)
        assertEquals(256L shl 20, w.memory.heapMaxBytes)
        assertEquals(60_000_000L, w.memory.oldGenPeakBytes)
        assertEquals(70_000_000L, w.memory.nonHeapBytes)
        assertEquals(1_000_000L, w.memory.directBytes)
        assertEquals(300_000_000L, w.memory.rssBytes)
        assertEquals(280_000_000L, w.memory.cgroupCurrentBytes)
        assertEquals(250_000_000L, w.memory.cgroupAnonBytes)
        assertEquals(256L shl 20, w.memory.workingSetBytes)
        assertNull(w.memory.cgroupPeakBytes, "an older tick's figure is not carried into the newest")
        assertEquals(webLimit, w.memory.containerLimitBytes)
        assertEquals(50.0, w.memory.percentOfLimit)
        assertEquals(0.25, w.jvm.cpuRatio)
        assertEquals(42, w.jvm.threads)
        assertEquals(4L, w.jvm.gcCount, "collectors add up")
        assertEquals(110L, w.jvm.gcTimeMs)
        assertEquals(listOf(2, 3, 0, 5), listOf(w.jvm.poolAcquired, w.jvm.poolIdle, w.jvm.poolPending, w.jvm.poolAllocated))

        val wk = processes.getValue(worker)
        assertNull(wk.memory.containerLimitBytes)
        assertNull(wk.memory.percentOfLimit, "no limit, no percentage")
        assertEquals(200_000_000L, wk.memory.workingSetBytes)
        assertNull(wk.jvm.cpuRatio)

        val gone = processes.getValue(stoppedAll)
        assertTrue(gone.stale)
        assertNull(gone.sampledAt)
        assertNull(gone.memory.heapUsedBytes)
    }

    @Test
    fun `current reads the newest storage measurement and the busiest routes of the latest interval`() = testApplication {
        boot()
        val web = store.seedProcess("web", lastSeen = now - 10_000)
        val m = TelemetryMetrics
        val older = latest - TELE_HOUR
        val routeRows = (1..17).flatMap { i ->
            val route = "/api/v1/reports/r%02d".format(i)
            listOf(rawRow(web, latest, m.HTTP_COUNT, i.toDouble(), route), rawRow(web, latest, m.HTTP_P95, i * 10.0, route))
        }
        store.put(
            rawRow(web, latest, m.HEAP_USED, 1.0),
            rawRow(web, older, m.DB_SIZE, 1.0), rawRow(web, older, m.DB_SCHEMA_SIZE, 1.0, "raw"),
            rawRow(web, latest - 30 * TELE_MINUTE, m.DB_SIZE, 9_000_000.0),
            rawRow(web, latest - 30 * TELE_MINUTE, m.DB_SCHEMA_SIZE, 6_000_000.0, "raw"),
            rawRow(web, latest - 30 * TELE_MINUTE, m.DB_SCHEMA_SIZE, 1_000_000.0, "ops"),
            rawRow(web, latest, m.HTTP_COUNT, 200.0, "*"), rawRow(web, latest, m.HTTP_P50, 12.0, "*"),
            rawRow(web, latest, m.HTTP_P95, 80.0, "*"),
            rawRow(web, latest, m.HTTP_5XX, 3.0, "*"), rawRow(web, latest, m.HTTP_429, 2.0, "*"),
            rawRow(web, latest, m.HTTP_P50, 5.0, "/api/v1/reports/r17"), rawRow(web, latest, m.HTTP_5XX, 1.0, "/api/v1/reports/r17"),
            rawRow(web, latest - 5 * TELE_MINUTE, m.HTTP_COUNT, 999.0, "/api/v1/reports/old"), // an older interval: not "latest"
            *routeRows.toTypedArray(),
        )
        val current = seededClient("telemetry-storage", FlowUserRole.ADMIN).current()

        val storage = assertNotNull(current.storage)
        assertEquals(latest - 30 * TELE_MINUTE, storage.measuredAt, "the newest measurement")
        assertEquals(9_000_000L, storage.databaseBytes)
        assertEquals(listOf("ops" to 1_000_000L, "raw" to 6_000_000L), storage.schemas.map { it.name to it.bytes })

        val requests = current.requests
        assertEquals(latest, requests.measuredAt)
        assertEquals(200L, requests.total.count)
        assertEquals(12.0, requests.total.p50Ms)
        assertEquals(80.0, requests.total.p95Ms)
        assertEquals(3L, requests.total.serverErrors)
        assertEquals(2L, requests.total.rateLimited)
        assertEquals((17 downTo 3).map { "/api/v1/reports/r%02d".format(it) }, requests.routes.map { it.route }, "top 15 by count")
        val top = requests.routes.first()
        assertEquals(17L, top.count)
        assertEquals(5.0, top.p50Ms)
        assertEquals(170.0, top.p95Ms)
        assertEquals(1L, top.serverErrors)
        assertEquals(0L, top.rateLimited, "an absent 429 row is zero")
        assertNull(requests.routes.last().p50Ms, "no p50 row, no p50")
    }

    @Test
    fun `current lists each data source with its estimate, freshness, queue, runs and DERIVE, nothing of its settings`() = testApplication {
        boot()
        val (a, dataSources) = seedConnection("tele-a")
        val (b, _) = seedConnection("tele-b")
        // The exact secrets of the two seeded connections, not a name prefix (other tests leave similar names in the shared DB).
        val secrets = listOf(a, b).flatMap {
            val stored = assertNotNull(dataSources.readForSync(it))
            listOf(stored.email, stored.apiToken)
        }
        val web = store.seedProcess("web", lastSeen = now - 10_000)
        val m = TelemetryMetrics
        val estimated = latest - 30 * TELE_MINUTE
        store.put(
            rawRow(web, latest, m.HEAP_USED, 1.0),
            rawRow(web, estimated, m.CONN_ISSUES, 1234.0, a.toString()),
            rawRow(web, estimated, m.CONN_BYTES_EST, 5_000_000.0, a.toString()),
            rawRow(web, estimated - TELE_HOUR, m.CONN_ISSUES, 1.0, b.toString()),
        )
        dataSources.recordSyncOutcome(a, succeeded = true, errorCode = null, now = now)
        dataSources.recordSyncOutcome(b, succeeded = false, errorCode = "Authentication failed for user x@y.z (free text)", now = now)
        dataSources.delete(b)
        try {
            // a: the latest SYNC is still running; a finished backfill and an older incremental run, a 24h and an older window.
            val threeDays = now - 3 * TELE_DAY
            seedJob(a, SyncJobStatus.SUCCEEDED, threeDays, threeDays, threeDays + 5 * TELE_MINUTE,
                progress = """{"jiraRateLimited":50,"jiraBackoffMs":9000}""")
            val sixHours = now - 6 * TELE_HOUR
            seedJob(a, SyncJobStatus.SUCCEEDED, sixHours, sixHours, sixHours + 40 * TELE_MINUTE,
                progress = """{"backfill":1,"jiraRateLimited":4,"jiraBackoffMs":3000,"issuesUpserted":100}""")
            val twoHours = now - 2 * TELE_HOUR
            seedJob(a, SyncJobStatus.FAILED, twoHours, twoHours, twoHours + TELE_MINUTE,
                progress = """{"jiraRateLimited":1,"jiraBackoffMs":500}""")
            seedJob(a, SyncJobStatus.RUNNING, requestedAt = now - 10 * TELE_MINUTE, startedAt = now - 9 * TELE_MINUTE)
            seedJob(a, SyncJobStatus.PENDING, requestedAt = now - TELE_MINUTE, kind = "RECONCILE")
            seedDeriveRun(a, now - 3 * TELE_HOUR - 3000, now - 3 * TELE_HOUR, "SUCCEEDED", """{"tasks":10,"epics":5}""")
            seedDeriveRun(a, now - 2 * TELE_HOUR - 1000, now - 2 * TELE_HOUR, "SUCCEEDED", """{"tasks":20,"epics":10,"note":"x"}""")
            seedDeriveRun(a, now - TELE_HOUR - 500, now - TELE_HOUR, "FAILED", null)

            val response = seededClient("telemetry-connections", FlowUserRole.ADMIN).get("/api/v1/telemetry/current")
            assertEquals(HttpStatusCode.OK, response.status)
            val text = response.bodyAsText()
            assertFalse("flow-test.atlassian.net" in text, "no site URL")
            assertTrue(secrets.size == 4 && secrets.all { it.isNotBlank() })
            secrets.forEach { assertFalse(it in text, "neither a seeded Jira account email nor an API token is in the response") }
            val connections = kotlinx.serialization.json.Json.decodeFromString<TelemetryCurrent>(text).connections.associateBy { it.id }

            val ca = connections.getValue(a.toLong())
            assertTrue(ca.name.startsWith("tele-a"))
            assertFalse(ca.enabled)
            assertFalse(ca.deleted)
            assertEquals(1234L, ca.issues)
            assertEquals(5_000_000L, ca.estimatedBytes)
            assertEquals(estimated, ca.estimatedAt)
            assertEquals(now, ca.freshness.lastSyncSucceededAt)
            assertEquals(0, ca.freshness.consecutiveFailures)
            assertNull(ca.freshness.lastSyncErrorCode)
            assertEquals(now + 60 * TELE_MINUTE, ca.freshness.nextSyncAt)
            assertNull(ca.freshness.reconcilePausedAt, "no RECONCILE pause")
            assertNull(ca.freshness.reconcilePauseReason)
            assertEquals(1L, ca.queue.pending, "the due RECONCILE")
            assertEquals(1L, ca.queue.running)
            val lastSync = assertNotNull(ca.lastSync)
            assertEquals("RUNNING", lastSync.status, "the latest requested SYNC job")
            assertEquals(now - 9 * TELE_MINUTE, lastSync.startedAt)
            assertNull(lastSync.finishedAt)
            val backfill = assertNotNull(ca.lastBackfill)
            assertEquals(now - 6 * TELE_HOUR, backfill.startedAt)
            assertEquals(40 * TELE_MINUTE, backfill.durationMs)
            assertEquals("SUCCEEDED", backfill.status)
            assertEquals(5L, ca.rateLimited24h, "4 + 1 inside the last 24 hours, the 3-day-old run excluded")
            assertEquals(3500L, ca.backoffMs24h)
            val derive = assertNotNull(ca.derive)
            assertEquals(now - TELE_HOUR, derive.lastFinishedAt)
            assertEquals(500L, derive.lastDurationMs)
            assertEquals("FAILED", derive.lastStatus)
            assertNull(derive.rowsWritten, "a failed run recorded no row counts")
            assertEquals(3000L, derive.peakDurationMs)

            val cb = connections.getValue(b.toLong())
            assertTrue(cb.deleted, "soft-deleted, awaiting its purge")
            assertEquals(1, cb.freshness.consecutiveFailures)
            assertEquals("RUN_FAILED", cb.freshness.lastSyncErrorCode, "free text is stored as the generic code")
            assertNull(cb.estimatedBytes)
            assertNull(cb.issues, "only the labels of the newest estimate run count")
            assertNull(cb.lastSync)
            assertNull(cb.lastBackfill)
            assertNull(cb.derive)
            assertEquals(0L, cb.queue.pending)
        } finally {
            cancelOpenJobs(a)
            dataSources.recordPurgeSucceeded(b)
        }
    }

    // ---- history -------------------------------------------------------------------------------------------------------

    @Test
    fun `history merges the processes of one role per bucket and keeps the trend across a restart`() = testApplication {
        boot()
        val first = store.seedProcess("web", lastSeen = now - TELE_HOUR, startedAt = now - 2 * TELE_HOUR) // stopped
        val second = store.seedProcess("web", lastSeen = now - 10_000, startedAt = now - TELE_HOUR) // its replacement
        val worker = store.seedProcess("worker", lastSeen = now - 10_000, startedAt = now - 90 * TELE_MINUTE)
        val m = TelemetryMetrics
        val a = latest - 10 * TELE_MINUTE
        val b = latest - 5 * TELE_MINUTE
        store.put(
            rawRow(first, a, m.HEAP_USED, 100.0, max = 120.0), rawRow(second, a, m.HEAP_USED, 300.0, max = 350.0),
            rawRow(second, b, m.HEAP_USED, 400.0), rawRow(second, latest, m.HEAP_USED, 500.0),
            rawRow(worker, a, m.HEAP_USED, 50.0), rawRow(worker, a, m.RSS, 7.0),
            rawRow(first, a, m.GC_COUNT, 2.0, "G1 Young"), rawRow(second, a, m.GC_COUNT, 3.0, "G1 Young"),
            rawRow(second, b, m.THREADS, 40.0), rawRow(second, a, m.POOL_IDLE, 4.0),
        )
        val admin = seededClient("telemetry-merge", FlowUserRole.ADMIN)

        val memory = admin.history("memory", "24h")
        assertEquals(listOf("web:mem.heap_used", "worker:mem.heap_used", "worker:mem.rss"), memory.series.map { it.key })
        val heap = memory.series("web:mem.heap_used")
        assertEquals(listOf(a, b, latest), heap.ts())
        assertEquals(listOf(200.0, 400.0, 500.0), heap.avgs(), "the mean of the two processes in their shared bucket")
        assertEquals(listOf(350.0, 400.0, 500.0), heap.maxes(), "the largest of the two")
        assertEquals("web", heap.role)
        assertEquals("mem.heap_used", heap.metric)
        assertEquals("", heap.label)
        assertNull(heap.connectionId)
        assertEquals("BYTES", heap.unit)
        assertEquals("GAUGE", heap.kind)
        assertEquals(listOf(50.0), memory.series("worker:mem.heap_used").avgs())
        assertEquals(now - 2 * TELE_HOUR, memory.historyFrom, "the first sampler of the retained processes booted two hours ago")
        assertFalse(memory.truncated)
        assertEquals(TELE_RAW_SECONDS, memory.bucketSeconds)

        val jvm = admin.history("jvm", "24h")
        val gc = jvm.series("web:jvm.gc_count:G1 Young")
        assertEquals("DELTA", gc.kind)
        assertEquals("COUNT", gc.unit)
        assertEquals("G1 Young", gc.label)
        assertEquals(listOf(5.0), gc.avgs(), "a DELTA adds up: the bucket's total")
        assertEquals(listOf(3.0), gc.maxes(), "and keeps the largest single interval")
        assertEquals(setOf("web:jvm.gc_count:G1 Young", "web:jvm.threads", "web:pool.idle"), jvm.series.map { it.key }.toSet())
    }

    @Test
    fun `the 7 day history reads hourly rows plus the hours the rollup has not folded yet`() = testApplication {
        boot()
        val web = store.seedProcess("web", lastSeen = now - 10_000, startedAt = now - 3 * TELE_DAY)
        val m = TelemetryMetrics
        val rolled = hourStart - 2 * TELE_HOUR
        val unrolled = hourStart - TELE_HOUR
        store.put(
            hourlyRow(web, rolled - 2 * TELE_HOUR, m.THREADS, avg = 100.0, max = 120.0),
            hourlyRow(web, rolled - TELE_HOUR, m.THREADS, avg = 200.0, max = 260.0),
            hourlyRow(web, rolled, m.THREADS, avg = 300.0, max = 310.0),
            hourlyRow(web, rolled, m.GC_COUNT, avg = 0.5, max = 2.0, label = "G1 Young"),
            // Raw rows of an hour that IS rolled up: already inside the hourly row, so counted once.
            rawRow(web, rolled + 5 * TELE_MINUTE, m.THREADS, 999.0), rawRow(web, rolled + 5 * TELE_MINUTE, m.GC_COUNT, 77.0, "G1 Young"),
            // Raw rows of the two hours after the newest hourly row: read time folds them.
            rawRow(web, unrolled, m.THREADS, 10.0), rawRow(web, unrolled + 5 * TELE_MINUTE, m.THREADS, 30.0),
            rawRow(web, hourStart, m.THREADS, 50.0),
            rawRow(web, unrolled, m.GC_COUNT, 1.0, "G1 Young"), rawRow(web, unrolled + 5 * TELE_MINUTE, m.GC_COUNT, 2.0, "G1 Young"),
        )
        val history = seededClient("telemetry-7d", FlowUserRole.ADMIN).history("jvm", "7d")

        assertEquals(3600, history.bucketSeconds)
        val threads = history.series("web:jvm.threads")
        assertEquals(listOf(rolled - 2 * TELE_HOUR, rolled - TELE_HOUR, rolled, unrolled, hourStart), threads.ts())
        assertEquals(listOf(100.0, 200.0, 300.0, 20.0, 50.0), threads.avgs())
        assertEquals(listOf(120.0, 260.0, 310.0, 30.0, 50.0), threads.maxes())
        val gc = history.series("web:jvm.gc_count:G1 Young")
        assertEquals(listOf(rolled, unrolled), gc.ts())
        assertEquals(listOf(6.0, 3.0), gc.avgs(), "an hourly DELTA row's avg * n is the hour's total; the raw rows add up")
        assertEquals(listOf(2.0, 2.0), gc.maxes())
        assertEquals(now - 3 * TELE_DAY, history.historyFrom)
    }

    @Test
    fun `the 90 day history folds the hourly rows into six hour buckets`() = testApplication {
        boot()
        val web = store.seedProcess("web", lastSeen = now - 10_000, startedAt = now - 50 * TELE_DAY)
        val m = TelemetryMetrics
        val bucket = 6 * TELE_HOUR
        val base = Math.floorDiv(now - 40 * TELE_DAY, bucket) * bucket
        store.put(
            hourlyRow(web, base, m.THREADS, avg = 100.0, max = 120.0),
            hourlyRow(web, base + TELE_HOUR, m.THREADS, avg = 200.0, max = 260.0),
            hourlyRow(web, base + bucket, m.THREADS, avg = 50.0, max = 60.0, n = 6),
            hourlyRow(web, base, m.GC_COUNT, avg = 1.0, max = 4.0, label = "G1 Old"),
            hourlyRow(web, base + TELE_HOUR, m.GC_COUNT, avg = 2.0, max = 3.0, label = "G1 Old"),
        )
        val history = seededClient("telemetry-90d", FlowUserRole.ADMIN).history("jvm", "90d")

        assertEquals(21600, history.bucketSeconds)
        val threads = history.series("web:jvm.threads")
        assertEquals(listOf(base, base + bucket), threads.ts())
        assertEquals(listOf(150.0, 50.0), threads.avgs(), "the n-weighted mean of the hours in the bucket")
        assertEquals(listOf(260.0, 60.0), threads.maxes())
        assertEquals(listOf(12.0 + 24.0), history.series("web:jvm.gc_count:G1 Old").avgs(), "totals add up: 1 * 12 + 2 * 12")
        assertEquals(now - 50 * TELE_DAY, history.historyFrom)
    }

    @Test
    fun `a history carries at most 400 points per series and 40 series`() = testApplication {
        boot("telemetry.sampleIntervalSeconds" to "60")
        val web = store.seedProcess("web", lastSeen = now - 10_000, startedAt = now - 2 * TELE_DAY)
        val oneMinute = 60_000L
        val rows = (0 until 1440).map { rawRow(web, latest - it * oneMinute, TelemetryMetrics.HEAP_USED, it.toDouble(), resolution = 60) }
        store.put(rows)
        val admin = seededClient("telemetry-caps", FlowUserRole.ADMIN)

        assertEquals(60, admin.current().sampleIntervalSeconds)
        val heap = admin.history("memory", "24h")
        assertEquals(240, heap.bucketSeconds, "a day of 60 s samples is 1440 points: four of them per bucket")
        val points = heap.series("web:mem.heap_used").points
        assertTrue(points.size in 358..361, "${points.size} points")
        assertTrue(points.size <= 400)
        assertEquals(points.map { it.t }.sorted(), points.map { it.t })
        assertTrue(points.all { it.t % 240_000L == 0L })
    }

    @Test
    fun `more than 40 series are cut to 40 and flagged`() = testApplication {
        boot()
        val web = store.seedProcess("web", lastSeen = now - 10_000)
        store.put((1..45).map { rawRow(web, latest, TelemetryMetrics.GC_COUNT, it.toDouble(), "collector-%02d".format(it)) })
        val history = seededClient("telemetry-series-cap", FlowUserRole.ADMIN).history("jvm", "24h")

        assertEquals(40, history.series.size)
        assertTrue(history.truncated)
        assertTrue(history.omittedConnectionIds.isEmpty())
    }

    @Test
    fun `more than 25 connections in the storage history leave the smallest out whole and keep the cluster series`() = testApplication {
        boot()
        val web = store.seedProcess("web", lastSeen = now - 10_000)
        val m = TelemetryMetrics
        // Connection ids 1..27 (labels), estimated bytes growing with the id: 1 and 2 are the smallest.
        val rows = (1..27).flatMap { id ->
            listOf(
                rawRow(web, latest, m.CONN_ISSUES, id.toDouble(), id.toString()),
                rawRow(web, latest, m.CONN_BYTES_EST, id * 1.0e6, id.toString()),
            )
        } + listOf(rawRow(web, latest, m.DB_SIZE, 1.0e9), rawRow(web, latest, m.DB_SCHEMA_SIZE, 5.0e8, "raw"))
        store.put(rows)
        val history = seededClient("telemetry-storage-cap", FlowUserRole.ADMIN).history("storage", "24h")

        assertTrue(history.truncated)
        assertEquals(listOf(1L, 2L), history.omittedConnectionIds)
        assertEquals(setOf("db.size", "db.schema.size:raw"), history.series.filter { it.connectionId == null }.map { it.key }.toSet())
        val perConnection = history.series.mapNotNull { it.connectionId }.groupBy { it }
        assertEquals((3L..27L).toSet(), perConnection.keys, "25 whole connections")
        assertTrue(perConnection.values.all { it.size == 2 }, "both series of every kept connection")
        assertEquals(2 + 50, history.series.size)
    }

    @Test
    fun `the requests history keeps the total and the ten busiest routes`() = testApplication {
        boot()
        val web = store.seedProcess("web", lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        val all = store.seedProcess("all", lastSeen = now - 10_000, startedAt = now - 2 * TELE_HOUR)
        val m = TelemetryMetrics
        val earlier = latest - 5 * TELE_MINUTE
        fun route(i: Int) = "/api/v1/reports/r%02d".format(i)
        val rows = (1..12).flatMap { i ->
            listOf(earlier, latest).flatMap { ts ->
                listOf(
                    rawRow(web, ts, m.HTTP_COUNT, i.toDouble(), route(i)), rawRow(web, ts, m.HTTP_P50, i.toDouble(), route(i)),
                    rawRow(web, ts, m.HTTP_P95, i * 2.0, route(i)),
                )
            }
        } + listOf(
            rawRow(web, latest, m.HTTP_COUNT, 500.0, "*"), rawRow(web, latest, m.HTTP_P50, 9.0, "*"),
            rawRow(web, latest, m.HTTP_P95, 90.0, "*"), rawRow(web, latest, m.HTTP_5XX, 4.0, "*"),
            rawRow(web, latest, m.HTTP_429, 1.0, "*"), rawRow(web, latest, m.HTTP_5XX, 2.0, route(12)),
            // Another serving role of the same fleet: one series per route, not one per role.
            rawRow(all, latest, m.HTTP_COUNT, 3.0, route(12)), rawRow(all, latest, m.HTTP_COUNT, 100.0, "*"),
        )
        store.put(rows)
        val history = seededClient("telemetry-requests", FlowUserRole.ADMIN).history("requests", "24h")

        val keys = history.series.map { it.key }.toSet()
        assertEquals(35, history.series.size, "5 for the total + 3 for each of ten routes")
        val totals = keys.filter { it.endsWith(":*") }.map { it.removePrefix("http.").removeSuffix(":*") }
        assertEquals(setOf("count", "p50", "p95", "5xx", "429"), totals.toSet())
        for (i in 3..12) assertTrue("http.count:${route(i)}" in keys && "http.p95:${route(i)}" in keys, route(i))
        assertTrue(keys.none { route(1) in it || route(2) in it }, "the two quietest routes are out")
        assertFalse("http.5xx:${route(12)}" in keys, "per-route errors are not in the history")
        val count = history.series("http.count:${route(12)}")
        assertEquals(route(12), count.label)
        assertEquals("DELTA", count.kind)
        assertNull(count.role, "the serving roles are one series per route")
        assertEquals(listOf(12.0, 15.0), count.avgs(), "web and all add up in the shared bucket")
        assertEquals(600.0, history.series("http.count:*").avgs().single())
        assertEquals("MILLISECONDS", history.series("http.p95:*").unit)
        assertEquals(4.0, history.series("http.5xx:*").avgs().single())
    }

    @Test
    fun `the storage history is cluster-wide, whichever process took the measurement`() = testApplication {
        boot()
        val web = store.seedProcess("web", lastSeen = now - 10_000, startedAt = now - 5 * TELE_HOUR)
        val worker = store.seedProcess("worker", lastSeen = now - 10_000, startedAt = now - 5 * TELE_HOUR)
        val m = TelemetryMetrics
        val earlier = latest - 2 * TELE_HOUR
        val later = latest - TELE_HOUR
        store.put(
            rawRow(web, earlier, m.DB_SIZE, 1.0e9), rawRow(worker, later, m.DB_SIZE, 2.0e9),
            rawRow(web, earlier, m.DB_SCHEMA_SIZE, 5.0e8, "raw"), rawRow(worker, later, m.DB_SCHEMA_SIZE, 6.0e8, "raw"),
            rawRow(worker, later, m.CONN_ISSUES, 1234.0, "42"), rawRow(worker, later, m.CONN_BYTES_EST, 3.0e8, "42"),
        )
        val history = seededClient("telemetry-storage-history", FlowUserRole.ADMIN).history("storage", "24h")

        val size = history.series("db.size")
        assertNull(size.role)
        assertEquals(listOf(earlier, later), size.ts())
        assertEquals(listOf(1.0e9, 2.0e9), size.avgs())
        assertEquals(listOf(5.0e8, 6.0e8), history.series("db.schema.size:raw").avgs())
        val issues = history.series("conn.issues:42")
        assertEquals(42L, issues.connectionId)
        assertEquals("COUNT", issues.unit)
        assertEquals("BYTES", history.series("conn.bytes.est:42").unit)
        assertNull(history.series("conn.bytes.est:42").role)
    }

    @Test
    fun `the sync history aggregates the finished SYNC jobs per connection and bucket at read time`() = testApplication {
        boot()
        val (c, _) = seedConnection("tele-sync")
        val web = store.seedProcess("web", lastSeen = now - 10_000)
        store.put(rawRow(web, latest, TelemetryMetrics.QUEUE_PENDING, 3.0), rawRow(web, latest, TelemetryMetrics.QUEUE_RUNNING, 1.0))
        val ended1 = now - 90 * TELE_MINUTE
        val ended2 = now - 85 * TELE_MINUTE
        val started1 = ended1 - 10 * TELE_MINUTE
        seedJob(c, SyncJobStatus.SUCCEEDED, started1, started1, ended1, progress = """{"jiraRateLimited":3,"jiraBackoffMs":4000}""")
        seedJob(c, SyncJobStatus.FAILED, ended2 - TELE_MINUTE, ended2 - TELE_MINUTE, ended2, progress = """{"jiraRateLimited":2}""")
        seedJob(c, SyncJobStatus.SUCCEEDED, now - 10 * TELE_DAY, now - 10 * TELE_DAY - TELE_MINUTE, now - 10 * TELE_DAY)
        seedJob(c, SyncJobStatus.SUCCEEDED, ended1 - TELE_HOUR, ended1 - TELE_HOUR, ended1, kind = "RECONCILE")
        seedJob(c, SyncJobStatus.CANCELLED, ended1, null, ended1)
        val admin = seededClient("telemetry-sync-history", FlowUserRole.ADMIN)

        val week = admin.history("sync", "7d")
        val mine = week.series.filter { it.connectionId == c.toLong() }.associateBy { it.metric }
        assertEquals(setOf("sync.runs", "sync.failures", "sync.duration_ms", "sync.rate_limited"), mine.keys)
        val hour = hourFloor(ended1)
        assertEquals(listOf(hour), mine.getValue("sync.runs").ts(), "one hour bucket; the older, cancelled and RECONCILE jobs are out")
        assertEquals(listOf(2.0), mine.getValue("sync.runs").avgs())
        assertEquals(listOf(1.0), mine.getValue("sync.failures").avgs())
        assertEquals(listOf(330_000.0), mine.getValue("sync.duration_ms").avgs())
        assertEquals(listOf(600_000.0), mine.getValue("sync.duration_ms").maxes())
        assertEquals(listOf(5.0), mine.getValue("sync.rate_limited").avgs())
        assertEquals("sync.runs:$c", mine.getValue("sync.runs").key)
        assertEquals(c.toString(), mine.getValue("sync.runs").label)
        assertNull(mine.getValue("sync.runs").role)
        assertEquals("MILLISECONDS", mine.getValue("sync.duration_ms").unit)
        assertEquals("GAUGE", mine.getValue("sync.duration_ms").kind)
        assertEquals(listOf(3.0), week.series("queue.pending").avgs())
        assertEquals(week.from, week.historyFrom, "the oldest ended job (10 days old) is older than the range")

        val day = admin.history("sync", "24h").series.filter { it.connectionId == c.toLong() }.associateBy { it.metric }
        assertEquals(listOf(1.0, 1.0), day.getValue("sync.runs").avgs(), "five-minute buckets: the two runs ended apart")
        assertEquals(listOf(0.0, 1.0), day.getValue("sync.failures").avgs())

        val quarter = admin.history("sync", "90d")
        val oldestSeeded = now - 10 * TELE_DAY
        assertTrue(quarter.historyFrom in quarter.from..oldestSeeded, "the oldest retained ended job bounds it: ${quarter.historyFrom}")
        assertEquals(2, quarter.series.first { it.key == "sync.runs:$c" }.points.size, "the 10 day old run is inside 90 days")
    }

    @Test
    fun `the derive history aggregates the ended DERIVE runs per connection and bucket at read time`() = testApplication {
        boot()
        val (c, _) = seedConnection("tele-derive")
        val third = now - 3 * TELE_HOUR
        seedDeriveRun(c, third - 3000, third, "SUCCEEDED", """{"tasks":10,"epics":5}""")
        seedDeriveRun(c, third + 10 * TELE_MINUTE - 1000, third + 10 * TELE_MINUTE, "SUCCEEDED", """{"tasks":20,"epics":10,"note":"x"}""")
        seedDeriveRun(c, now - TELE_HOUR - 500, now - TELE_HOUR, "FAILED", null)
        seedDeriveRun(c, now - 2 * TELE_HOUR, now - TELE_HOUR, "RUNNING", null) // not ended: not a run
        val history = seededClient("telemetry-derive-history", FlowUserRole.ADMIN).history("derive", "7d")

        val mine = history.series.filter { it.connectionId == c.toLong() }.associateBy { it.metric }
        assertEquals(setOf("derive.runs", "derive.failures", "derive.duration_ms", "derive.rows_written"), mine.keys)
        val earlyHour = hourFloor(third)
        val lateHour = hourFloor(now - TELE_HOUR)
        assertEquals(listOf(earlyHour, lateHour), mine.getValue("derive.runs").ts())
        assertEquals(listOf(2.0, 1.0), mine.getValue("derive.runs").avgs())
        assertEquals(listOf(0.0, 1.0), mine.getValue("derive.failures").avgs())
        assertEquals(listOf(2000.0, 500.0), mine.getValue("derive.duration_ms").avgs())
        assertEquals(listOf(3000.0, 500.0), mine.getValue("derive.duration_ms").maxes())
        assertEquals(listOf(45.0, 0.0), mine.getValue("derive.rows_written").avgs(), "the integer row_counts entries of the runs added up")
        assertEquals("derive.runs:$c", mine.getValue("derive.runs").key)
        assertTrue(history.historyFrom in history.from..third, "no older than the range, no younger than the oldest ended run")
    }

    @Test
    fun `both telemetry reads share one per-user bucket of 30 a minute`() = testApplication {
        boot()
        val admin = seededClient("telemetry-bucket", FlowUserRole.ADMIN)
        val other = seededClient("telemetry-bucket-other", FlowUserRole.ADMIN)
        repeat(15) {
            assertEquals(HttpStatusCode.OK, admin.get("/api/v1/telemetry/current").status)
            assertEquals(HttpStatusCode.OK, admin.get("/api/v1/telemetry/history?group=memory&range=24h").status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, admin.get("/api/v1/telemetry/current").status)
        assertEquals(HttpStatusCode.TooManyRequests, admin.get("/api/v1/telemetry/history?group=memory&range=24h").status)
        assertEquals(HttpStatusCode.OK, other.get("/api/v1/telemetry/current").status, "keyed by the user, not the address")
    }

    @Test
    fun `more than 25 connections in the derive history leave the least active ones out whole`() = testApplication {
        boot()
        val ids = (1..27).map { seedConnection("tele-cap").first }
        val ended = now - 2 * TELE_HOUR
        ids.forEachIndexed { index, id ->
            // The first two connections have one run, the other 25 have two.
            repeat(if (index < 2) 1 else 2) { n ->
                seedDeriveRun(id, ended + n * TELE_MINUTE - 1000, ended + n * TELE_MINUTE, "SUCCEEDED", """{"tasks":1}""")
            }
        }
        val history = seededClient("telemetry-derive-cap", FlowUserRole.ADMIN).history("derive", "7d")

        // Other classes' DERIVE runs may sit in the same (pinned) window and take slots too: assert the contract, not the exact set.
        assertTrue(history.truncated)
        val omitted = history.omittedConnectionIds
        assertTrue(ids.take(2).all { it.toLong() in omitted }, "the two least active of mine are out: $omitted")
        assertEquals(omitted.sorted(), omitted, "ascending")
        val kept = history.series.mapNotNull { it.connectionId }.toSet()
        assertEquals(25, kept.size, "exactly 25 connections stay")
        assertTrue(kept.none { it in omitted }, "an omitted connection keeps no partial series")
        val perConnection = history.series.groupBy { it.connectionId }
        assertTrue(perConnection.values.all { it.size <= 4 } && history.series.size <= 100)
        val whole = setOf("derive.runs", "derive.duration_ms", "derive.rows_written")
        val mineKept = ids.map { it.toLong() }.filter { it in kept }
        assertTrue(mineKept.all { perConnection.getValue(it).map { s -> s.metric }.toSet() == whole }, "whole series (no zero failures)")
    }

    @Test
    fun `the raw tier filter lists exactly the resolutions the V36 check allows below an hour`() {
        assertEquals((60 until 3600).filter { 3600 % it == 0 }, ch.nokillswit.telemetry.RAW_RESOLUTIONS)
    }
}
