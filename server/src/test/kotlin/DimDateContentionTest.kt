package ch.nokillswit

import ch.nokillswit.metrics.DIM_DATE_LOCK_KEY
import ch.nokillswit.metrics.DimDateRange
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The global `metrics.dim_date` is written by every connection's DERIVE, so its writes must never be
 * held to the end of a derive's one big transaction (`.claude/docs/metrics.md` "The DERIVE run
 * algorithm", `MetricsStore.ensureDimDate`): held row locks made two DERIVEs deadlock (40P01) or
 * serialize, and a calendar change left every row outside the run's range stale.
 *
 * Every test owns a private DISABLED processed clone (`.claude/docs/testing.md`, "The derived
 * fixture") and derives under the fixture's pinned clock.
 */
class DimDateContentionTest {
    private suspend fun preparedClone(teamPrefix: String): UInt {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-dimdate-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), teamPrefix)
        return connId
    }

    private suspend fun latestRunStatus(connId: UInt): String? = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.DeriveRuns.selectAll().where { MetricsTables.DeriveRuns.connectionId eq connId.toInt() }
            .orderBy(MetricsTables.DeriveRuns.id to SortOrder.DESC).limit(1).toList().singleOrNull()
            ?.get(MetricsTables.DeriveRuns.status)
    }

    private fun jdbc(): java.sql.Connection =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)

    /**
     * Runs [block] while ANOTHER plain-JDBC session holds a `FOR NO KEY UPDATE` row lock on EVERY `dim_date`
     * row in an open transaction — exactly what a concurrent DERIVE's in-transaction upsert used to hold
     * until its commit. A poller (one reused JDBC connection) asks `pg_stat_activity` whether any backend is
     * blocked BY the holder's pid (`? = ANY(pg_blocking_pids(pid))`, so an unrelated wait — a DERIVE's ANALYZE
     * behind autovacuum — never counts): the moment one is, `blocked` is set and the holder is released (a
     * statement blocked on a row lock cannot be cancelled — the rollback queues behind it — so freeing the
     * lock is the only way to fail a regression promptly). [BACKSTOP_MS] is a last resort so a regression can
     * never hang CI. The lock is released in `finally` too. Returns [block]'s result and `blocked`.
     */
    private suspend fun <T> holdingDimDateRowLocks(block: suspend () -> T): Pair<T, Boolean> = coroutineScope {
        val holderPid = CompletableDeferred<Int>()
        val release = CompletableDeferred<Unit>()
        val blocked = AtomicBoolean(false)
        val holder = launch(Dispatchers.IO) {
            jdbc().use { conn ->
                try {
                    conn.autoCommit = false
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT day FROM metrics.dim_date FOR NO KEY UPDATE").use { rs -> while (rs.next()) Unit }
                    }
                    val pid = conn.createStatement().use { st ->
                        st.executeQuery("SELECT pg_backend_pid()").use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                    holderPid.complete(pid)
                    release.await()
                    conn.rollback()
                } catch (failure: Exception) {
                    holderPid.completeExceptionally(failure)
                    throw failure
                }
            }
        }
        val poller = launch(Dispatchers.IO) {
            val pid = holderPid.await()
            val deadline = System.nanoTime() + BACKSTOP_MS * NANOS_PER_MS
            jdbc().use { conn ->
                conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))").use { stmt ->
                    stmt.setInt(1, pid)
                    while (!release.isCompleted) {
                        val waiters = stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                        if (waiters > 0 || System.nanoTime() > deadline) {
                            blocked.set(true)
                            release.complete(Unit)
                        }
                        delay(POLL_MS)
                    }
                }
            }
        }
        try {
            holderPid.await()
            block() to blocked.get()
        } finally {
            poller.cancel()
            release.complete(Unit)
            holder.join()
        }
    }

    @Test
    fun `DERIVE holds no dim_date row locks, so a foreign transaction holding them never blocks it`() = runBlocking {
        val connId = preparedClone("dimdate-locks-team")
        val config = DerivedStubFixture.metricsConfig()
        DerivedStubFixture.withPinnedSettings(config) {
            DerivedStubFixture.derivePinned(connId, config, jobId = 1u)

            val (_, blocked) = holdingDimDateRowLocks { DerivedStubFixture.derivePinned(connId, config, jobId = 2u) }
            assertFalse(blocked, "a re-DERIVE must finish while another transaction row-locks every dim_date row")
        }
        assertEquals("SUCCEEDED", latestRunStatus(connId))
    }

    private suspend fun dimDateDays(): List<WorkingCalendar.DimDateRow> = suspendTransaction(sharedDatabaseForTests()) {
        val dd = MetricsTables.DimDate
        dd.selectAll().orderBy(dd.day to SortOrder.ASC).toList()
            .map { WorkingCalendar.DimDateRow(it[dd.day], it[dd.dayStartMs], it[dd.dayEndMs], it[dd.isWorkingDay]) }
    }

    /** One stored `dim_date` row with the revision it was written under. */
    private data class Stamped(val row: WorkingCalendar.DimDateRow, val revision: Long)

    private suspend fun snapshotDimDate(): List<Stamped> = suspendTransaction(sharedDatabaseForTests()) {
        val dd = MetricsTables.DimDate
        dd.selectAll().orderBy(dd.day to SortOrder.ASC).toList().map {
            Stamped(WorkingCalendar.DimDateRow(it[dd.day], it[dd.dayStartMs], it[dd.dayEndMs], it[dd.isWorkingDay]), it[dd.configRevision])
        }
    }

    /**
     * Puts `dim_date` back EXACTLY as [snapshot] found it — every row with its own original revision (a
     * restore through `ensureDimDate` could not: it only rewrites at the current revision, and stamping a
     * high one would make later tests' calendar changes skip the rows) — by emptying the table and
     * re-inserting the snapshot, so every row the test added (inside or outside the span) is gone too.
     */
    private suspend fun restoreDimDate(snapshot: List<Stamped>) {
        val dd = MetricsTables.DimDate
        suspendTransaction(sharedDatabaseForTests()) {
            dd.deleteAll()
            if (snapshot.isNotEmpty()) {
                dd.batchInsert(snapshot) { stamped ->
                    this[dd.day] = stamped.row.day
                    this[dd.dayStartMs] = stamped.row.dayStartMs
                    this[dd.dayEndMs] = stamped.row.dayEndMs
                    this[dd.isWorkingDay] = stamped.row.isWorkingDay
                    this[dd.configRevision] = stamped.revision
                }
            }
        }
    }

    @Test
    fun `a calendar change rewrites dim_date rows outside the run's range, not only inside it`() = runBlocking {
        DerivedStubFixture.connectionId() // the shared derive has stamped the initial span under the settings calendar
        val config = DerivedStubFixture.metricsConfig()
        val snapshot = snapshotDimDate()
        val before = snapshot.map { it.row }
        val farDay = LocalDate.parse("2012-03-07") // far outside any run's range (clone created ~2020s - 1y), inside the 50-year floor
        val farRow = WorkingCalendar.of(config.read()).dimDateRows(farDay, farDay).single()
        DerivedStubFixture.stampDimDate(listOf(farRow), configRevision = 1L)
        val connId = preparedClone("dimdate-calendar-team")
        try {
            DerivedStubFixture.withPinnedSettings(config) {
                // A different zone (day bounds move) AND a different weekend (working flags move).
                withMetricsSettings(config, { it.copy(timeZone = "Asia/Tokyo", weekendDays = listOf(5, 6)) }) {
                    DerivedStubFixture.derivePinned(connId, config, jobId = 1u)
                    val calendar = WorkingCalendar.of(config.read())
                    val after = dimDateDays()
                    assertTrue(after.any { it.day == farRow.day }, "the far-past row must survive the derive")
                    assertNotEquals(farRow, calendar.dimDateRows(farDay, farDay).single(), "the two calendars must disagree on the far day")
                    val stale = after.filter { it != calendar.dimDateRows(LocalDate.parse(it.day), LocalDate.parse(it.day)).single() }
                    assertEquals(emptyList(), stale.map { it.day }.take(MAX_LISTED), "every dim_date row must equal the NEW calendar's")
                    assertTrue(after.size >= before.size, "no stored day may disappear")
                }
            }
        } finally {
            restoreDimDate(snapshot)
        }
    }

    @Test
    fun `ensureDimDate over a settled table writes nothing`() = runBlocking {
        DerivedStubFixture.connectionId()
        val config = DerivedStubFixture.metricsConfig()
        val store = MetricsStore(sharedDatabaseForTests())
        val settings = config.read()
        val calendar = WorkingCalendar.of(settings)
        val revision = settings.configRevision
        val snapshot = snapshotDimDate()
        val span = snapshot.first().row.day..snapshot.last().row.day
        // Ten days directly below the stored span: missing rows, no gap to fill.
        val firstStored = LocalDate.parse(span.start)
        val from = firstStored.minusDays(TEN_DAYS)
        val range = DimDateRange(calendar.dayBoundsMs(from).first, calendar.dayBoundsMs(firstStored.minusDays(1)).first)
        try {
            val first = store.ensureDimDate(calendar, range, revision)
            assertEquals(TEN_DAYS.toInt(), first, "the ten missing days are written")
            val second = store.ensureDimDate(calendar, range, revision)
            assertEquals(0, second, "a second call with the same calendar and range writes nothing")
            val pinnedDay = DimDateRange(DerivedStubFixture.PINNED_NOW, DerivedStubFixture.PINNED_NOW)
            assertEquals(0, store.ensureDimDate(calendar, pinnedDay, revision))
            val rows = dimDateDays().associateBy { it.day }
            assertEquals(calendar.dimDateRows(from, firstStored.minusDays(1)).toSet(), rows.filterKeys { it < span.start }.values.toSet())
        } finally {
            restoreDimDate(snapshot)
        }
    }

    @Test
    fun `a stale caller (A to B to A settings changes) only inserts missing days and never rewrites a row`() = runBlocking {
        DerivedStubFixture.connectionId()
        val config = DerivedStubFixture.metricsConfig()
        val store = MetricsStore(sharedDatabaseForTests())
        val calendarX = WorkingCalendar.of(config.read())
        val calendarY = WorkingCalendar(ZoneId.of("Asia/Tokyo"), setOf(5, 6), emptySet())
        val snapshot = snapshotDimDate()
        val rowsBefore = snapshot.map { it.row }
        val firstStored = LocalDate.parse(snapshot.first().row.day)
        val pinned = DimDateRange(DerivedStubFixture.PINNED_NOW, DerivedStubFixture.PINNED_NOW)
        try {
            // A (calendar X) -> B (Tokyo, revision r+1) -> A again (X, revision r+2): a DERIVE that started under B is stale.
            var staleRevision = 0L
            withMetricsSettings(config, { it.copy(timeZone = "Asia/Tokyo") }) { staleRevision = config.read().configRevision }
            val current = config.read().configRevision
            assertTrue(staleRevision < current, "the settings revision must have moved on since the stale run started")

            assertEquals(0, store.ensureDimDate(calendarY, pinned, staleRevision), "a stale caller rewrites no stored row")
            assertEquals(rowsBefore, dimDateDays(), "the table is unchanged (calendar X stays)")
            // Missing days are still inserted, rows beside them stay untouched.
            val below = DimDateRange(
                calendarY.dayBoundsMs(firstStored.minusDays(THREE_DAYS)).first,
                calendarY.dayBoundsMs(firstStored.minusDays(1)).first,
            )
            assertEquals(THREE_DAYS.toInt(), store.ensureDimDate(calendarY, below, staleRevision), "missing days are inserted")
            val firstDay = snapshot.first().row.day
            assertEquals(rowsBefore, dimDateDays().filter { it.day >= firstDay }, "existing rows stay on the current calendar")

            // A caller at the CURRENT revision does rewrite the differing rows.
            assertTrue(store.ensureDimDate(calendarY, pinned, current) > 0, "the current revision rewrites differing rows")
            assertNotEquals(rowsBefore, dimDateDays().filter { it.day >= firstDay })
            assertEquals(0, store.ensureDimDate(calendarX, pinned, staleRevision - 1), "and an even older caller still writes nothing")
        } finally {
            restoreDimDate(snapshot)
        }
    }

    @Test
    fun `ensureDimDate commits separately from the caller's still-open transaction`() = runBlocking {
        DerivedStubFixture.connectionId()
        val config = DerivedStubFixture.metricsConfig()
        val store = MetricsStore(sharedDatabaseForTests())
        val settings = config.read()
        val calendar = WorkingCalendar.of(settings)
        val snapshot = snapshotDimDate()
        val firstStored = LocalDate.parse(snapshot.first().row.day)
        val from = firstStored.minusDays(THREE_DAYS)
        val range = DimDateRange(calendar.dayBoundsMs(from).first, calendar.dayBoundsMs(firstStored.minusDays(1)).first)
        try {
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsTables.DimDate.selectAll().limit(1).toList() // the caller's transaction holds its own connection now
                assertEquals(THREE_DAYS.toInt(), store.ensureDimDate(calendar, range, settings.configRevision))
                // Still inside the caller's transaction: a separate session must already see the rows, and the
                // advisory lock must already be released.
                val (visible, lockFree) = withContext(Dispatchers.IO) {
                    jdbc().use { conn ->
                        conn.autoCommit = false
                        val countSql = "SELECT count(*) FROM metrics.dim_date WHERE day >= ? AND day < ?"
                        val visible = conn.prepareStatement(countSql).use { stmt ->
                            stmt.setString(1, from.toString())
                            stmt.setString(2, firstStored.toString())
                            stmt.executeQuery().use { rs ->
                                rs.next()
                                rs.getInt(1)
                            }
                        }
                        val lockFree = conn.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use { stmt ->
                            stmt.setLong(1, DIM_DATE_LOCK_KEY)
                            stmt.executeQuery().use { rs ->
                                rs.next()
                                rs.getBoolean(1)
                            }
                        }
                        conn.rollback()
                        visible to lockFree
                    }
                }
                assertEquals(THREE_DAYS.toInt(), visible, "the ensured rows are committed before the caller's transaction ends")
                assertTrue(lockFree, "the advisory lock is not held by the caller's transaction")
            }
        } finally {
            restoreDimDate(snapshot)
        }
    }

    @Test
    fun `concurrent ensureDimDate calls queue on one advisory lock`() = runBlocking {
        DerivedStubFixture.connectionId()
        val config = DerivedStubFixture.metricsConfig()
        val store = MetricsStore(sharedDatabaseForTests())
        val calendar = WorkingCalendar.of(config.read())
        val range = DimDateRange(DerivedStubFixture.PINNED_NOW, DerivedStubFixture.PINNED_NOW)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val holder = launch {
                suspendTransaction(sharedDatabaseForTests()) {
                    exec("SELECT pg_advisory_xact_lock($DIM_DATE_LOCK_KEY)")
                    locked.complete(Unit)
                    release.await()
                }
            }
            locked.await()
            val ensure = async { store.ensureDimDate(calendar, range, config.read().configRevision) }
            delay(ADVISORY_WAIT_MS)
            assertFalse(ensure.isCompleted, "ensureDimDate must wait while another transaction holds the dim_date advisory lock")
            release.complete(Unit)
            holder.join()
            assertEquals(0, ensure.await(), "once the lock is free the settled table needs no writes")
        }
    }

    private companion object {
        const val TEN_DAYS = 10L
        const val THREE_DAYS = 3L
        const val MAX_LISTED = 5
        const val ADVISORY_WAIT_MS = 1_500L
        const val BACKSTOP_MS = 120_000L
        const val POLL_MS = 100L
        const val NANOS_PER_MS = 1_000_000L
    }
}
