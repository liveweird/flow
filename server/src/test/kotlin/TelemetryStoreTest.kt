package ch.nokillswit

import ch.nokillswit.telemetry.HOURLY_RESOLUTION
import ch.nokillswit.telemetry.SampleRow
import ch.nokillswit.telemetry.TelemetryStore
import ch.nokillswit.telemetry.hourFloor
import ch.nokillswit.telemetry.insertSampleRows
import ch.nokillswit.telemetry.pruneTelemetry
import ch.nokillswit.telemetry.rollupHourly
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlinx.coroutines.flow.toList
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `telemetry/TelemetryStore.kt` over the shared container (V36): the process registry, the idempotent multi-row insert, the hourly
 * rollup (sum of `n`, n-weighted mean, max of max, the complete-hour rule, idempotence), the prune boundaries, the cascade and the
 * cluster lock. `ops` is the telemetry tests' own schema; each test starts from empty and scopes its assertions by its own process ids.
 */
class TelemetryStoreTest {
    private val store = telemetryStore()
    private val hour = 3_600_000L

    // A fixed, hour-aligned base far from "now": the tests pass `now` explicitly.
    private val base = hourFloor(1_800_000_000_000L)

    @Before
    fun emptyOps() = runBlocking { wipeTelemetry(store) }

    private fun raw(pid: Int, ts: Long, metric: String = "mem.heap_used", label: String = "", n: Int = 1, avg: Double, max: Double = avg) =
        SampleRow(RAW_RESOLUTION, ts, pid, metric, label, n, avg, max)

    private suspend fun insert(vararg rows: SampleRow) = store.transaction { insertSampleRows(rows.toList()) }

    private suspend fun rollup(now: Long, settleMillis: Long = 600_000L) = store.transaction { rollupHourly(now, settleMillis) }

    private suspend fun prune(now: Long) = store.transaction { pruneTelemetry(now) }

    @Test
    fun `a process registers once per identity and is touched thereafter`() = runBlocking {
        val identity = testIdentity()
        val id = store.registerProcess(identity, now = 1_000, containerLimitBytes = null)
        assertEquals(id, store.registerProcess(identity, now = 2_000, containerLimitBytes = 536_870_912), "the same run is the same row")
        val other = store.registerProcess(testIdentity(), now = 3_000, containerLimitBytes = null)
        assertNotEquals(id, other)
        assertTrue(store.touchProcess(id, now = 4_000, containerLimitBytes = 1))
        assertFalse(store.touchProcess(Int.MAX_VALUE, now = 4_000, containerLimitBytes = null), "a vanished row asks for re-registration")
        val p = TelemetryStore.Processes
        val row = store.transaction { p.selectAll().where { p.id eq id }.toList().single() }
        assertEquals(4_000L, row[p.lastSeen])
        assertEquals(1L, row[p.containerLimitBytes])
        assertEquals(identity.role, row[p.role])
        assertEquals(identity.instance, row[p.instance])
        assertEquals(identity.startedAt, row[p.startedAt])
        assertEquals("9.9.9", row[p.buildVersion])
        assertEquals("abc1234", row[p.buildCommit])
        assertEquals(4, row[p.processors])
    }

    @Test
    fun `inserting a sample twice keeps the first and writes every column`() = runBlocking {
        val pid = store.registerProcess(testIdentity(), 0, null)
        insert(raw(pid, base, label = "Copy", n = 2, avg = 5.5, max = 7.5))
        insert(raw(pid, base, label = "Copy", avg = 99.0), raw(pid, base + 300_000, avg = 1.0))
        val rows = store.samples(pid)
        assertEquals(2, rows.size)
        assertEquals(raw(pid, base, label = "Copy", n = 2, avg = 5.5, max = 7.5), rows.single { it.label == "Copy" })
        assertEquals("", rows.single { it.label != "Copy" }.label, "the default label is empty")
    }

    @Test
    fun `an hour rolls up with summed n, an n-weighted mean and the largest max, per label and process`() = runBlocking {
        val a = store.registerProcess(testIdentity(), 0, null)
        val b = store.registerProcess(testIdentity(), 0, null)
        insert(
            raw(a, base, avg = 10.0, max = 10.0),
            raw(a, base + 300_000, n = 3, avg = 30.0, max = 50.0),
            raw(a, base + 600_000, label = "x", avg = 4.0),
            raw(b, base + 900_000, avg = 7.0, max = 9.0),
            raw(a, base, metric = "pool.idle", avg = 1.0),
        )
        assertTrue(rollup(base + hour + 10 * 60_000))
        val hourly = store.samples(a, HOURLY_RESOLUTION)
        val heap = hourly.single { it.metric == "mem.heap_used" && it.label == "" }
        assertEquals(base, heap.ts)
        assertEquals(4, heap.n, "1 + 3 raw samples")
        assertEquals((10.0 + 30.0 * 3) / 4, heap.avg, 1e-9)
        assertEquals(50.0, heap.max, "a 5-minute peak survives the rollup")
        assertEquals(1, hourly.single { it.label == "x" }.n)
        assertEquals(1, hourly.single { it.metric == "pool.idle" }.n)
        assertEquals(7.0, store.samples(b, HOURLY_RESOLUTION).single().avg)
        assertEquals(4, store.samples(a, RAW_RESOLUTION).size, "the raw rows stay (7 days)")
        assertEquals(1, store.samples(b, RAW_RESOLUTION).size)
    }

    @Test
    fun `only a complete hour at least ten minutes old is rolled up`() = runBlocking {
        val pid = store.registerProcess(testIdentity(), 0, null)
        insert(raw(pid, base + 3_300_000, avg = 1.0)) // the hour's last sample
        val settled = base + hour + 600_000
        assertFalse(rollup(settled - 1), "one millisecond early")
        assertTrue(store.samples(pid, HOURLY_RESOLUTION).isEmpty())
        assertTrue(rollup(settled))
        assertEquals(listOf(base), store.samples(pid, HOURLY_RESOLUTION).map { it.ts })
    }

    @Test
    fun `the rollup is idempotent and only moves forward`() = runBlocking {
        val pid = store.registerProcess(testIdentity(), 0, null)
        insert(raw(pid, base, avg = 2.0), raw(pid, base + hour, avg = 4.0))
        val now = base + 3 * hour
        assertTrue(rollup(now))
        val first = store.samples(pid, HOURLY_RESOLUTION)
        assertEquals(listOf(base, base + hour), first.map { it.ts })
        assertFalse(rollup(now), "nothing left to fold")
        assertEquals(first, store.samples(pid, HOURLY_RESOLUTION))
        // A raw sample for an hour that is already rolled up is never folded in (the hourly row stands).
        insert(raw(pid, base + 60_000, label = "late", avg = 100.0))
        rollup(now + hour)
        assertTrue(store.samples(pid, HOURLY_RESOLUTION).none { it.label == "late" })
        // A new hour is picked up.
        insert(raw(pid, base + 2 * hour, avg = 8.0))
        assertTrue(rollup(now + hour))
        assertEquals(base + 2 * hour, store.samples(pid, HOURLY_RESOLUTION).maxOf { it.ts })
    }

    @Test
    fun `the prune boundaries are strict and a process delete cascades`() = runBlocking {
        val now = base + 200 * 24 * hour
        val keep = store.registerProcess(testIdentity(), now, null)
        val old = store.registerProcess(testIdentity(), now - 90 * 24 * hour - 1, null)
        val edge = store.registerProcess(testIdentity(), now - 90 * 24 * hour, null)
        insert(
            SampleRow(RAW_RESOLUTION, now - 7 * 24 * hour, keep, "m", "at7d", 1, 1.0, 1.0),
            SampleRow(RAW_RESOLUTION, now - 7 * 24 * hour - 1, keep, "m", "past7d", 1, 1.0, 1.0),
            SampleRow(HOURLY_RESOLUTION, now - 90 * 24 * hour, keep, "m", "at90d", 1, 1.0, 1.0),
            SampleRow(HOURLY_RESOLUTION, now - 90 * 24 * hour - 1, keep, "m", "past90d", 1, 1.0, 1.0),
            // a 3-day-old raw row and a 30-day-old hourly row are well inside
            SampleRow(RAW_RESOLUTION, now - 3 * 24 * hour, keep, "m", "recent", 1, 1.0, 1.0),
            SampleRow(HOURLY_RESOLUTION, now - 30 * 24 * hour, keep, "m", "month", 1, 1.0, 1.0),
            SampleRow(HOURLY_RESOLUTION, now - 30 * 24 * hour, old, "m", "of-old-process", 1, 1.0, 1.0),
            SampleRow(HOURLY_RESOLUTION, now - 30 * 24 * hour, edge, "m", "of-edge-process", 1, 1.0, 1.0),
        )
        prune(now)
        assertEquals(setOf("at7d", "recent", "at90d", "month"), store.samples(keep).map { it.label }.toSet())
        assertTrue(store.samples(old).isEmpty(), "the silent process's samples went with it")
        assertEquals(1, store.samples(edge).size, "a process seen exactly 90 days ago stays")
        // The old process row is gone: re-touching reports it so.
        assertFalse(store.touchProcess(old, now, null))
        assertTrue(store.touchProcess(edge, now, null))
    }

    @Test
    fun `the cluster lock is exclusive while held and free after`() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val holder = async {
                store.clusterSection {
                    held.complete(Unit)
                    release.await()
                    "holder"
                }
            }
            held.await()
            assertNull(store.clusterSection { "contender" }, "the lock is taken: the section does not run")
            release.complete(Unit)
            assertEquals("holder", holder.await())
        }
        assertEquals("after", store.clusterSection { "after" })
    }

    @Test
    fun `a sample of an unknown process is refused by the foreign key`() = runBlocking {
        val failure = runCatching { insert(raw(Int.MAX_VALUE, base, avg = 1.0)) }.exceptionOrNull()
        assertTrue(failure != null, "ops.telemetry_samples.process_id references ops.telemetry_processes")
    }

    @Test
    fun `the settle time is a parameter and a rollup reports whether it inserted`() = runBlocking {
        val pid = store.registerProcess(testIdentity(), 0, null)
        insert(raw(pid, base + 3_000_000, avg = 1.0))
        val hourEnd = base + hour
        assertFalse(rollup(hourEnd + 899_999, settleMillis = 900_000), "a 15-minute settle (interval 1800 s) is not over yet")
        assertTrue(rollup(hourEnd + 900_000, settleMillis = 900_000))
        assertFalse(rollup(hourEnd + 5 * hour), "a due range with nothing in it inserts nothing, and the caller does not prune")
    }

    @Test
    fun `raw rows of any interval roll up and the resolution check refuses a width that does not tile an hour`() = runBlocking {
        val pid = store.registerProcess(testIdentity(), 0, null)
        insert(
            SampleRow(600, base, pid, "m", "", 1, 2.0, 2.0),
            SampleRow(600, base + 600_000, pid, "m", "", 1, 4.0, 4.0),
            SampleRow(60, base + 60_000, pid, "m", "x", 1, 5.0, 5.0),
        )
        assertTrue(rollup(base + 2 * hour))
        assertEquals(listOf(2, 1), store.samples(pid, HOURLY_RESOLUTION).sortedBy { it.label }.map { it.n })
        for (bad in listOf(0, 7, 59, 50, 7200, 3601)) {
            val failure = runCatching { insert(SampleRow(bad, base, pid, "bad", "", 1, 1.0, 1.0)) }.exceptionOrNull()
            assertTrue(failure != null, "resolution $bad must be refused")
        }
        insert(SampleRow(1800, base, pid, "ok", "", 1, 1.0, 1.0), SampleRow(3600, base, pid, "ok", "", 1, 1.0, 1.0))
    }

    private companion object {
        // A raw sample at the default 5-minute interval (any `resolution < 3600` is a raw row).
        const val RAW_RESOLUTION = 300
    }
}
