package ch.nokillswit

import ch.nokillswit.infra.db.PoolSnapshot
import ch.nokillswit.telemetry.CgroupProbe
import ch.nokillswit.telemetry.ClusterCollector
import ch.nokillswit.telemetry.HOURLY_RESOLUTION
import ch.nokillswit.telemetry.JvmProbe
import ch.nokillswit.telemetry.ProcessCollector
import ch.nokillswit.telemetry.ProcessIdentity
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.SampleRow
import ch.nokillswit.telemetry.TelemetryConfig
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.TelemetrySampler
import ch.nokillswit.telemetry.hourFloor
import ch.nokillswit.telemetry.insertSampleRows
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `telemetry/TelemetrySampler.kt` over the shared container with a pinned clock: what a tick writes, a failing collector isolated
 * and logged once then "recovered", a cluster collector's SQL failure undone by its savepoint, two samplers deduping the
 * cluster section under the lock, the hourly rollup + prune riding the cluster section, a vanished process row re-registered, and
 * the tick budget. `ops` is the telemetry tests' own schema (see `TelemetryStoreTest`).
 */
class TelemetrySamplerTest {
    private val store = telemetryStore()
    private val interval = 300_000L
    private val config = TelemetryConfig(
        enabled = true, sampleIntervalSeconds = 300, storageIntervalSeconds = 3600, connectionEstimateIntervalSeconds = 21_600,
    )

    // Hour-aligned, plus an offset so the tick time is NOT on a bucket boundary.
    private val base = hourFloor(1_800_000_000_000L)
    private val t0 = base + 5 * 3_600_000L + 123_456

    @Before
    fun emptyOps() = runBlocking { wipeTelemetry(store) }

    private fun cgroupDir(limit: String = "536870912"): Path {
        val dir = Files.createTempDirectory("sampler-cgroup")
        dir.resolve("memory.current").writeText("300000000")
        dir.resolve("memory.max").writeText(limit)
        dir.resolve("memory.stat").writeText("anon 200000000\ninactive_file 40000000\n")
        return dir
    }

    private fun sampler(
        clock: () -> Long,
        identity: ProcessIdentity = testIdentity(),
        config: TelemetryConfig = this.config,
        pool: PoolSnapshot? = PoolSnapshot(1, 2, 3, 4, 5),
        extra: List<ProcessCollector> = emptyList(),
        cluster: List<ClusterCollector> = emptyList(),
    ) = TelemetrySampler(
        store, config, identity, clock, JvmProbe(),
        CgroupProbe(cgroupDir(), Path.of("/nonexistent/status"), Path.of("/nonexistent/cgroup")),
        { pool }, extra, cluster,
    )

    private fun bucketOf(ts: Long) = ts - ts % interval

    private val logName = TelemetrySampler::class.java.name

    @Test
    fun `a tick writes this process's own samples into one bucket and stamps the process`() = runBlocking {
        val identity = testIdentity(role = "worker")
        var now = t0
        val sampler = sampler({ now }, identity)
        sampler.tick()
        val pid = assertNotNull(store.processId(identity))
        val rows = store.samples(pid)
        assertTrue(rows.all { it.resolution == RAW_RESOLUTION && it.ts == bucketOf(t0) && it.n == 1 && it.avg == it.max })
        val byMetric = rows.associateBy { it.metric + it.label }
        val expected = listOf(
            "mem.heap_used", "mem.heap_committed", "mem.non_heap_used", "jvm.threads", "mem.cgroup_current", "mem.cgroup_working_set",
        )
        for (metric in expected) {
            assertTrue(metric in byMetric, "missing $metric in ${byMetric.keys}")
        }
        assertEquals(260_000_000.0, byMetric.getValue("mem.cgroup_working_set").avg)
        val pool = listOf("acquired", "idle", "pending", "allocated").map { byMetric.getValue("pool.$it").avg }
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), pool)
        assertTrue("telemetry.cluster_ms" in byMetric, "the cluster section ran and left its marker")

        val before = store.processView(pid)
        assertEquals("worker", before.role)
        assertEquals(536_870_912L, before.limit, "the container limit comes from the cgroup")
        assertEquals(t0, before.lastSeen)

        now = t0 + 10_000 // the same bucket again: nothing is duplicated, last_seen moves
        sampler.tick()
        assertEquals(rows.size, store.samples(pid).size)
        assertEquals(now, store.processView(pid).lastSeen)
    }

    @Test
    fun `raw rows carry the real sample interval as their resolution and align to it`() = runBlocking {
        val identity = testIdentity()
        val every10Minutes = config.copy(sampleIntervalSeconds = 600)
        sampler({ t0 }, identity, config = every10Minutes).tick()
        val rows = store.samples(assertNotNull(store.processId(identity)))
        assertTrue(rows.isNotEmpty() && rows.all { it.resolution == 600 && it.ts == t0 - t0 % 600_000 }, "$rows")
    }

    @Test
    fun `a failing collector is isolated, logged once, then recovered once`() = runBlocking {
        val identity = testIdentity()
        var now = t0
        var failing = true
        val boom = ProcessCollector("boom") {
            if (failing) error("kaboom") else listOf(Reading(TelemetryMetrics.HEAP_USED, 1.0, "boom-ok"))
        }
        val sampler = sampler({ now }, identity, extra = listOf(boom))
        val capture = LogCapture(logName)
        try {
            sampler.tick()
            val pid = assertNotNull(store.processId(identity))
            assertTrue(store.samples(pid, metric = "mem.heap_committed").isNotEmpty(), "the other collectors' rows are written")
            assertTrue(store.samples(pid).none { it.label == "boom-ok" })
            fun lines(level: ch.qos.logback.classic.Level) = capture.events.filter { it.level == level }.map { it.formattedMessage }
            assertEquals(
                listOf("Telemetry collector boom failed: java.lang.IllegalStateException: kaboom"),
                lines(ch.qos.logback.classic.Level.WARN),
            )

            now += interval
            sampler.tick() // still failing: no second WARN
            assertEquals(1, lines(ch.qos.logback.classic.Level.WARN).size)
            assertTrue(lines(ch.qos.logback.classic.Level.INFO).none { "recovered" in it })

            failing = false
            now += interval
            sampler.tick()
            assertEquals(listOf("Telemetry collector boom recovered"), lines(ch.qos.logback.classic.Level.INFO))
            assertTrue(store.samples(pid).any { it.label == "boom-ok" })

            now += interval
            sampler.tick() // healthy: silence
            assertEquals(1, lines(ch.qos.logback.classic.Level.INFO).count { "recovered" in it })

            failing = true
            now += interval
            sampler.tick() // a new episode warns again
            assertEquals(2, lines(ch.qos.logback.classic.Level.WARN).size)
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a cluster collector that fails in SQL is undone by its savepoint and the others still run`() = runBlocking {
        val identity = testIdentity()
        val now = t0
        val broken = ClusterCollector("broken-sql") { exec("SELECT 1/0"); emptyList() }
        val throwing = ClusterCollector("throwing") { error("plain failure") }
        val healthy = ClusterCollector("healthy") { listOf(Reading(TelemetryMetrics.HEAP_USED, 42.0, "cluster-ok")) }
        val sampler = sampler({ now }, identity, cluster = listOf(broken, throwing, healthy))
        val capture = LogCapture(logName)
        try {
            sampler.tick()
            val pid = assertNotNull(store.processId(identity))
            val rows = store.samples(pid)
            assertEquals(42.0, rows.single { it.label == "cluster-ok" }.avg, "a failure before it did not poison the transaction")
            assertTrue(rows.any { it.metric == "telemetry.cluster_ms" }, "the marker is committed with the readings")
            val warns = capture.events.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
            assertEquals(2, warns.size)
            assertTrue(warns[0].startsWith("Telemetry collector broken-sql failed:"), warns[0])
            assertTrue(warns[1].startsWith("Telemetry collector throwing failed:"), warns[1])
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `two samplers on one database run the cluster section once per interval`() = runBlocking {
        val runs = AtomicInteger()
        val slow = ClusterCollector("count") { runs.incrementAndGet(); delay(400); emptyList() }
        var now = t0
        val a = testIdentity()
        val b = testIdentity()
        val samplerA = sampler({ now }, a, cluster = listOf(slow))
        val samplerB = sampler({ now }, b, cluster = listOf(slow))

        samplerA.tick()
        samplerB.tick() // sequential: B finds A's committed marker for the bucket
        assertEquals(1, runs.get())
        fun markers() = runBlocking {
            listOfNotNull(store.processId(a), store.processId(b)).flatMap { store.samples(it, metric = "telemetry.cluster_ms") }
        }
        assertEquals(1, markers().size)

        now += interval
        coroutineScope { // concurrent: B's try-lock fails while A holds it (or B finds the marker if A is already done)
            launch { samplerA.tick() }
            launch { samplerB.tick() }
        }
        assertEquals(2, runs.get())
        assertEquals(listOf(bucketOf(t0), bucketOf(t0) + interval), markers().map { it.ts }.sorted())
        // Both processes still wrote their own samples every time.
        assertEquals(2, store.samples(assertNotNull(store.processId(a)), metric = "mem.heap_used").size)
        assertEquals(2, store.samples(assertNotNull(store.processId(b)), metric = "mem.heap_used").size)
    }

    @Test
    fun `the cluster section rolls complete hours up and prunes expired rows`() = runBlocking {
        val helperIdentity = testIdentity()
        val helper = store.registerProcess(helperIdentity, t0, null)
        val completeHour = hourFloor(t0) - 3 * 3_600_000L
        val eightDaysAgo = t0 - 8 * 24 * 3_600_000L
        store.transaction {
            insertSampleRows(
                listOf(
                    SampleRow(RAW_RESOLUTION, completeHour, helper, "m", "", 1, 10.0, 20.0),
                    SampleRow(RAW_RESOLUTION, completeHour + interval, helper, "m", "", 1, 30.0, 30.0),
                    SampleRow(RAW_RESOLUTION, eightDaysAgo, helper, "m", "", 1, 1.0, 1.0),
                ),
            )
        }
        val identity = testIdentity()
        sampler({ t0 }, identity).tick()
        val hourly = store.samples(helper, HOURLY_RESOLUTION).associateBy { it.ts }
        assertEquals(2, hourly.getValue(completeHour).n)
        assertEquals(20.0, hourly.getValue(completeHour).avg)
        assertEquals(30.0, hourly.getValue(completeHour).max)
        assertTrue(hourFloor(eightDaysAgo) in hourly, "the expired raw row was rolled up before it was pruned")
        assertEquals(listOf(completeHour, completeHour + interval), store.samples(helper, RAW_RESOLUTION).map { it.ts })
    }

    @Test
    fun `a process whose row vanished registers again on the next tick`() = runBlocking {
        val identity = testIdentity()
        var now = t0
        val sampler = sampler({ now }, identity)
        sampler.tick()
        val first = assertNotNull(store.processId(identity))
        wipeTelemetry(store) // an operator pruned the table by hand
        now += interval
        sampler.tick()
        val second = assertNotNull(store.processId(identity))
        assertNotEquals(first, second)
        assertTrue(store.samples(second, metric = "mem.heap_used").isNotEmpty())
    }

    @Test
    fun `a tick that outruns half the interval is cancelled and warned about`() = runBlocking {
        val stuck = ProcessCollector("stuck") { awaitCancellation() }
        val tight = config.copy(sampleIntervalSeconds = 2) // budget 1 s: bypasses the boot bounds on purpose
        val sampler = sampler({ t0 }, config = tight, extra = listOf(stuck))
        val capture = LogCapture(logName)
        try {
            val started = System.nanoTime()
            sampler.tickWithinBudget()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "the tick is bounded")
            val warns = capture.events.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
            assertEquals(1, warns.size)
            assertTrue(warns.single().startsWith("Telemetry collector tick failed: java.util.concurrent.TimeoutException"), warns.single())
        } finally {
            capture.detach()
        }
    }

    private companion object {
        const val RAW_RESOLUTION = 300
    }
}
