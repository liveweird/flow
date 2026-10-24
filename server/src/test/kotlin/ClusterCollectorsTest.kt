package ch.nokillswit

import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncQueueCounts
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.telemetry.CgroupProbe
import ch.nokillswit.telemetry.HOURLY_RESOLUTION
import ch.nokillswit.telemetry.HeavyCollector
import ch.nokillswit.telemetry.JvmProbe
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.TelemetryConfig
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.TelemetrySampler
import ch.nokillswit.telemetry.clusterCollectors
import ch.nokillswit.telemetry.heavyCollectors
import ch.nokillswit.telemetry.hourFloor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.Before
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_5_MINUTES = 300_000L

/**
 * The cluster collectors (`telemetry/ClusterCollectors.kt`): the sync queue's depth - the due PENDING jobs and the RUNNING ones, over
 * every connection - and the cadence of the heavy collectors (storage hourly, the per-connection estimate every 6 hours) driven
 * through a real `TelemetrySampler` on a pinned clock. The queue test works with DELTAS around jobs of its own connections (the
 * shared queue holds other tests' rows) and cancels them afterwards.
 */
class ClusterCollectorsTest {
    private val store = telemetryStore()
    private val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
    private val config = TelemetryConfig(
        enabled = true, sampleIntervalSeconds = 300, storageIntervalSeconds = 3600, connectionEstimateIntervalSeconds = 21_600,
    )

    @Before
    fun emptyOps() = runBlocking { wipeTelemetry(store) }

    private suspend fun connection(prefix: String): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = prefix, enabled = false)
    }

    private val identity = testIdentity()

    @Test
    fun `the queue counts the due PENDING and the RUNNING jobs, not a delayed one`() = runBlocking {
        val a = connection("queue-a")
        val b = connection("queue-b")
        val c = connection("queue-c")
        val mine = setOf(a, b, c)
        assertEquals(SyncQueueCounts(0, 0), jobs.statusCounts(mine))
        val ids = mutableListOf<UInt>()
        try {
            ids += jobs.requestJob(a, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
            ids += jobs.requestJob(b, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId
            val delayed = jobs.requestJob(c, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId.also { ids += it }
            suspendTransaction(sharedDatabaseForTests()) {
                SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq delayed }) {
                    it[notBefore] = System.currentTimeMillis() + MILLIS_PER_HOUR
                }
            }
            val running = jobs.requestJob(a, SyncJobKind.RECONCILE, requestedByUserId = 1u, configRevision = 1L).jobId.also { ids += it }
            suspendTransaction(sharedDatabaseForTests()) {
                SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id eq running }) {
                    it[status] = SyncJobStatus.RUNNING.name
                    it[attempt] = 1
                    it[leaseOwner] = "queue-test"
                    it[leaseUntil] = PARKED_LEASE_UNTIL
                }
            }

            assertEquals(
                SyncQueueCounts(pending = 2, running = 1), jobs.statusCounts(mine),
                "two due PENDING jobs; the delayed one is waiting by design",
            )
            val everyone = jobs.statusCounts()
            assertTrue(everyone.pending >= 2 && everyone.running >= 1, "the unscoped count (what telemetry reads) covers every connection")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                SyncJobsService.Jobs.update({ SyncJobsService.Jobs.id inList ids }) { it[status] = SyncJobStatus.CANCELLED.name }
            }
        }
        assertEquals(SyncQueueCounts(0, 0), jobs.statusCounts(mine), "cancelled jobs leave the queue")
    }

    private fun sampler(
        config: TelemetryConfig = this.config,
        heavy: List<HeavyCollector> = heavyCollectors(config),
        now: () -> Long,
    ) = TelemetrySampler(
        store, config, identity, now, JvmProbe(),
        CgroupProbe(Path.of("/nonexistent/cgroup"), Path.of("/nonexistent/status"), Path.of("/nonexistent/proc-cgroup")),
        { null }, emptyList(), clusterCollectors(jobs), heavy,
    )

    private suspend fun bucketsOf(metric: String): List<Long> {
        val pid = assertNotNull(store.processId(identity))
        return store.samples(pid, config.sampleIntervalSeconds, metric).map { it.ts }.sorted()
    }

    @Test
    fun `the queue is sampled every tick, storage hourly and the per-connection estimate every six hours`() = runBlocking {
        val base = hourFloor(1_800_000_000_000L) + 5 * MILLIS_PER_HOUR
        var now = base + 123_456
        val sampler = sampler(now = { now })
        val ticks = mutableListOf<Long>()
        // Seven hours of 5-minute ticks.
        repeat(7 * 12 + 1) {
            sampler.tick()
            ticks += now - Math.floorMod(now, MILLIS_PER_5_MINUTES)
            now += MILLIS_PER_5_MINUTES
        }

        assertEquals(ticks, bucketsOf("queue.pending"), "the queue every tick")
        assertEquals(ticks, bucketsOf("queue.running"))
        val hourly = bucketsOf("db.size")
        assertEquals(8, hourly.size, "storage once per hour over seven hours of ticks")
        assertTrue(hourly.zipWithNext().all { (x, y) -> y - x == MILLIS_PER_HOUR }, "$hourly")
        assertEquals(hourly, bucketsOf("telemetry.storage_ms"), "the marker is written with the readings")
        val estimates = bucketsOf("telemetry.estimate_ms")
        assertEquals(2, estimates.size, "the estimate on the first tick and six hours later: $estimates")
        assertEquals(6 * MILLIS_PER_HOUR, estimates[1] - estimates[0])
    }

    @Test
    fun `the storage collector reports every schema`() = runBlocking {
        val sampler = sampler(now = { hourFloor(1_800_000_000_000L) + 5 * MILLIS_PER_HOUR })
        sampler.tick()
        val pid = assertNotNull(store.processId(identity))
        val schemas = store.samples(pid, metric = "db.schema.size").map { it.label }.toSet()
        assertEquals(setOf("raw", "norm", "metrics", "public", "ops"), schemas)
    }

    private val samplerLog = TelemetrySampler::class.java.name

    private suspend fun rawTs(metric: String, of: TelemetryConfig = config) =
        store.samples(assertNotNull(store.processId(identity)), of.sampleIntervalSeconds, metric)

    @Test
    fun `a failing heavy collector claims its marker, is not retried this interval, and takes nothing else down`() = runBlocking {
        val calls = AtomicInteger()
        var failing = true
        val heavy = HeavyCollector("boom", TelemetryMetrics.STORAGE_TICK, intervalMillis = 2 * MILLIS_PER_HOUR) {
            calls.incrementAndGet()
            if (failing) error("kaboom") else listOf(Reading(TelemetryMetrics.DB_SIZE, 42.0))
        }
        var now = hourFloor(1_800_000_000_000L) + 5 * MILLIS_PER_HOUR + 1_000
        val sampler = sampler(heavy = listOf(heavy)) { now }
        val capture = LogCapture(samplerLog)
        try {
            // Three hours of ticks: long enough for an hourly rollup (retention) to commit while the heavy collector keeps failing.
            repeat(3 * 12 + 1) {
                sampler.tick()
                now += MILLIS_PER_5_MINUTES
            }
            assertEquals(2, calls.get(), "attempted once per 2-hour interval (hour 0 and hour 2), never on the ticks in between")
            val claims = rawTs("telemetry.storage_ms")
            assertEquals(2, claims.size)
            assertTrue(claims.all { it.avg == 0.0 }, "a claim that did not complete stays at 0: $claims")
            assertEquals(37, rawTs("queue.pending").size, "the cheap collectors commit every tick")
            assertEquals(37, rawTs("telemetry.cluster_ms").size)
            val hourly = store.samples(assertNotNull(store.processId(identity)), HOURLY_RESOLUTION, "queue.pending")
            assertTrue(hourly.isNotEmpty(), "retention (the hourly rollup) still commits")
            assertEquals(1, capture.events.count { it.level == ch.qos.logback.classic.Level.WARN }, "one WARN, then DEBUG for the repeat")
            assertTrue(
                capture.events.single { it.level == ch.qos.logback.classic.Level.WARN }.formattedMessage
                    .startsWith("Telemetry collector boom failed: java.lang.IllegalStateException: kaboom"),
            )

            // Recovered: the next interval's attempt succeeds, writes its readings, and completes the marker.
            failing = false
            now = hourFloor(now) + 2 * MILLIS_PER_HOUR
            sampler.tick()
            assertEquals(3, calls.get())
            assertEquals(42.0, rawTs("db.size").single().avg)
            val completed = rawTs("telemetry.storage_ms").maxBy { it.ts }
            assertTrue(completed.avg >= 1.0, "the marker now holds the run's duration: $completed")
            assertTrue(capture.events.any { it.formattedMessage == "Telemetry collector boom recovered" })
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a heavy phase that outruns its budget is cancelled, warned about once and not retried before the next interval`() = runBlocking {
        val calls = AtomicInteger()
        val stuck = HeavyCollector("stuck", TelemetryMetrics.ESTIMATE_TICK, intervalMillis = MILLIS_PER_HOUR) {
            calls.incrementAndGet()
            awaitCancellation()
        }
        var now = hourFloor(1_800_000_000_000L) + 5 * MILLIS_PER_HOUR + 1_000
        val sampler = sampler(heavy = listOf(stuck)) { now }.also { it.heavyBudgetMillis = 300 }
        val capture = LogCapture(samplerLog)
        try {
            val started = System.nanoTime()
            sampler.tickWithinBudget()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "the heavy phase is bounded by its own budget")
            now += MILLIS_PER_5_MINUTES
            sampler.tickWithinBudget()
            assertEquals(1, calls.get(), "the claim holds for the interval")
            assertEquals(listOf(0.0), rawTs("telemetry.estimate_ms").map { it.avg })
            assertEquals(2, rawTs("queue.pending").size, "the light phase committed both ticks")
            val warns = capture.events.filter { it.level == ch.qos.logback.classic.Level.WARN }.map { it.formattedMessage }
            assertEquals(1, warns.size)
            assertTrue(warns.single().startsWith("Telemetry collector heavy failed: java.util.concurrent.TimeoutException"), warns.single())
        } finally {
            capture.detach()
        }
    }
}
