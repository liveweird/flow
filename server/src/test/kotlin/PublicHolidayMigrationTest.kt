package ch.nokillswit

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V25's data steps, run as the migration's own statements (read from the file, so the test can never drift from it) inside a
 * transaction that is rolled back: the carry-over of the old free-form `metrics.settings.holidays` list and the re-derive fan-out
 * that mirrors `MetricsSettingsService.bumpRevision`. The carry-over reads a temporary stand-in for the dropped column, never an
 * `ALTER TABLE metrics.settings`: that needs an exclusive lock on a table every DERIVE reads, so it queued behind any open reader and
 * every later reader queued behind it (a 180 s hang on CI, run 40052478417). Each transaction has a `lock_timeout` that names the
 * blockers instead of waiting out the JDBC socket timeout.
 */
class PublicHolidayMigrationTest {
    private val sql: String = Files.readString(Path.of("src/main/resources/db/migration/V25__public_holidays.sql"))

    private fun statement(start: String): String = start + sql.substringAfter(start).substringBefore(";") + ";"

    private val carryOver = statement("INSERT INTO metrics.public_holidays (holiday_date, name, created_at)\nSELECT DISTINCT")
    private val bump = statement("UPDATE metrics.settings SET config_revision")
    private val fanOut = statement("INSERT INTO public.sync_jobs")

    private val connectionsToClean = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        connectionsToClean.forEach { SyncedStubFixture.dataSources().delete(it) }
        connectionsToClean.clear()
    }

    private fun <T> rolledBack(body: (Connection) -> T): T =
        PostgresTestSupport.connect().use { c ->
            c.autoCommit = false
            try {
                c.run("SET LOCAL lock_timeout = '${LOCK_TIMEOUT_S}s'")
                body(c)
            } catch (e: SQLException) {
                if (e.sqlState != LOCK_NOT_AVAILABLE) throw e
                throw AssertionError("a lock wait passed ${LOCK_TIMEOUT_S}s; the other sessions: ${otherSessions()}", e)
            } finally {
                c.rollback()
            }
        }

    /** Every other backend on the database, its state, its blockers and its query - who held the lock this test waited for. */
    private fun otherSessions(): List<String> = PostgresTestSupport.connect().use { c ->
        c.query(
            "SELECT pid, state, pg_blocking_pids(pid), left(query, 200) FROM pg_stat_activity " +
                "WHERE datname = current_database() AND pid <> pg_backend_pid()",
        ) { "pid ${it.getInt(1)} ${it.getString(2)} blocked by ${it.getString(3)}: ${it.getString(4)}" }
    }

    private fun Connection.run(statement: String) = createStatement().use { it.execute(statement) }

    private fun <T> Connection.query(statement: String, read: (java.sql.ResultSet) -> T): List<T> =
        createStatement().use { st -> st.executeQuery(statement).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }

    @Test
    fun `the old free-form holidays survive as Holiday, the seed's name wins on a shared date and malformed entries are skipped`() {
        SyncedStubFixture.ensureMigrated()
        rolledBack { c ->
            // The pre-V25 column, as a one-row temporary table the migration's own statement is pointed at.
            val stored = "[\"2026-04-06\", \"2031-01-02\", \"2031-01-02\", \"not-a-date\", \"+12345-01-01\"]"
            c.run("CREATE TEMPORARY TABLE settings_v24 (holidays JSONB NOT NULL) ON COMMIT DROP")
            c.run("INSERT INTO settings_v24 (holidays) VALUES ('$stored')")
            val fromSettings = "FROM metrics.settings s,"
            assertEquals(true, fromSettings in carryOver, "the carry-over must still read metrics.settings")
            c.run(carryOver.replace(fromSettings, "FROM settings_v24 s,"))
            val carried = "SELECT holiday_date, name FROM metrics.public_holidays WHERE holiday_date IN ('2026-04-06', '2031-01-02')"
            val names = c.query(carried) { it.getString(1) to it.getString(2) }.toMap()
            assertEquals(mapOf("2026-04-06" to "Poniedziałek Wielkanocny", "2031-01-02" to "Holiday"), names)
            val malformed = "SELECT 1 FROM metrics.public_holidays WHERE holiday_date IN ('not-a-date', '+12345-01-01')"
            assertEquals(0, c.query(malformed) { 1 }.size, "only well-formed ISO dates are carried")
        }
    }

    @Test
    fun `the fan-out bumps the revision and enqueues one DERIVE per enabled connection, coalescing into an open one`() = runBlocking {
        SyncedStubFixture.ensureMigrated()
        val enabled = SyncedStubFixture.createConnection(namePrefix = "v25-enabled", enabled = true).also { connectionsToClean += it }
        val disabled = SyncedStubFixture.createConnection(namePrefix = "v25-disabled", enabled = false).also { connectionsToClean += it }
        val open = SyncedStubFixture.createConnection(namePrefix = "v25-open", enabled = true).also { connectionsToClean += it }
        // Only OUR connections: other tests' committed connections must not be touched, even inside the rolled-back transaction.
        val where = "WHERE c.enabled AND NOT c.marked_as_deleted"
        val scoped = fanOut.replace(where, "$where AND c.id IN ($enabled, $disabled, $open)")
        assertEquals(true, scoped != fanOut, "the scoping edit must hit the statement's WHERE clause")
        rolledBack { c ->
            val revision = "SELECT config_revision FROM metrics.settings"
            val revisionBefore = c.query(revision) { it.getLong(1) }.single()
            c.run(bump)
            assertEquals(revisionBefore + 1, c.query(revision) { it.getLong(1) }.single())

            c.run(
                "INSERT INTO public.sync_jobs " +
                    "(connection_id, kind, status, priority, config_revision, requested_at, attempt, max_attempts) " +
                    "VALUES ($open, 'DERIVE', 'PENDING', 0, 1, 1, 0, 3)",
            )
            c.run(scoped)
            val jobsSql = "SELECT connection_id, status, priority, config_revision, max_attempts FROM public.sync_jobs " +
                "WHERE kind = 'DERIVE' AND connection_id IN ($enabled, $disabled, $open)"
            val jobs = c.query(jobsSql) { listOf(it.getInt(1), it.getString(2), it.getInt(3), it.getLong(4), it.getInt(5)) }
            assertEquals(2, jobs.size, "the disabled connection gets nothing; the open one coalesces into its existing job")
            val own = "SELECT config_revision FROM public.source_connections WHERE id = $enabled"
            val ownRevision = c.query(own) { it.getLong(1) }.single()
            assertEquals(listOf(enabled.toInt(), "PENDING", 10, ownRevision, 3), jobs.single { it[0] == enabled.toInt() })
            assertEquals(listOf(open.toInt(), "PENDING", 0, 1L, 3), jobs.single { it[0] == open.toInt() }, "the existing job is untouched")
        }
    }

    private companion object {
        const val LOCK_TIMEOUT_S = 20
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
