package ch.nokillswit

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V28 (domain-model A35): the three new fact columns exist, are NOT NULL and default to false (so a row written before the first
 * DERIVE reads "not late / not after done"), and the re-derive fan-out — read from the migration file, run in a rolled-back
 * transaction, like `PublicHolidayMigrationTest` — bumps the shared revision and enqueues one DERIVE per enabled connection.
 */
class EstimateAfterDoneMigrationTest {
    private val sql: String = Files.readString(Path.of("src/main/resources/db/migration/V28__estimate_after_done.sql"))

    private fun statement(start: String): String = start + sql.substringAfter(start).substringBefore(";") + ";"

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
                body(c)
            } finally {
                c.rollback()
            }
        }

    private fun Connection.run(statement: String) = createStatement().use { it.execute(statement) }

    private fun <T> Connection.query(statement: String, read: (java.sql.ResultSet) -> T): List<T> =
        createStatement().use { st -> st.executeQuery(statement).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }

    @Test
    fun `the new flags are NOT NULL booleans defaulting to false on both facts`() {
        SyncedStubFixture.ensureMigrated()
        rolledBack { c ->
            val columns = c.query(
                "SELECT table_name, column_name, data_type, is_nullable, column_default FROM information_schema.columns " +
                    "WHERE table_schema = 'metrics' AND table_name IN ('fact_task_delivery', 'fact_epic_delivery') " +
                    "AND column_name IN ('estimated_late', 'estimated_after_done') ORDER BY 1, 2",
            ) { listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5)) }
            val expected = listOf(
                listOf("fact_epic_delivery", "estimated_after_done", "boolean", "NO", "false"),
                listOf("fact_epic_delivery", "estimated_late", "boolean", "NO", "false"),
                listOf("fact_task_delivery", "estimated_after_done", "boolean", "NO", "false"),
                listOf("fact_task_delivery", "estimated_late", "boolean", "NO", "false"),
            )
            assertEquals(expected, columns)
        }
    }

    @Test
    fun `the fan-out bumps the revision and enqueues one DERIVE per enabled connection, coalescing into an open one`() = runBlocking {
        SyncedStubFixture.ensureMigrated()
        val enabled = SyncedStubFixture.createConnection(namePrefix = "v28-enabled", enabled = true).also { connectionsToClean += it }
        val disabled = SyncedStubFixture.createConnection(namePrefix = "v28-disabled", enabled = false).also { connectionsToClean += it }
        val open = SyncedStubFixture.createConnection(namePrefix = "v28-open", enabled = true).also { connectionsToClean += it }
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
}
