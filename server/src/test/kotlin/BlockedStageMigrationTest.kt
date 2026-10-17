package ch.nokillswit

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V29's tick conversion (domain-model A36), run as the migration's own statements (read from the file, so the test can never drift from
 * it) inside a transaction that is rolled back: the dropped `metrics.blocked_statuses` is created again (DDL is transactional in
 * PostgreSQL), seeded with ticks, and the conversion, the revision bump and the DERIVE fan-out are executed and read back. The widened
 * CHECKs are already in the migrated database, so a BLOCKED row is accepted. Never touches a committed row except the connections it
 * creates (deleted afterwards).
 */
class BlockedStageMigrationTest {
    private val sql: String = Files.readString(Path.of("src/main/resources/db/migration/V29__blocked_stage.sql"))

    private fun statement(start: String): String = start + sql.substringAfter(start).substringBefore(";") + ";"

    private val convert = statement("UPDATE metrics.status_stage_map m SET stage = 'BLOCKED'")
    private val insertGlobal = statement("INSERT INTO metrics.status_stage_map (connection_id, status_id, domain_key, stage)")
    private val bump = statement("UPDATE metrics.settings SET config_revision")
    private val fanOut = statement("INSERT INTO public.sync_jobs")

    private val connectionsToClean = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        connectionsToClean.forEach { SyncedStubFixture.dataSources().delete(it) }
        connectionsToClean.clear()
    }

    private fun <T> rolledBack(body: (Connection) -> T): T =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use { c ->
            c.autoCommit = false
            try {
                c.run(
                    "CREATE TABLE metrics.blocked_statuses (connection_id INTEGER NOT NULL REFERENCES public.source_connections(id), " +
                        "status_id VARCHAR(50) NOT NULL, PRIMARY KEY (connection_id, status_id))",
                )
                body(c)
            } finally {
                c.rollback()
            }
        }

    private fun Connection.run(statement: String) = createStatement().use { it.execute(statement) }

    private fun <T> Connection.query(statement: String, read: (java.sql.ResultSet) -> T): List<T> =
        createStatement().use { st -> st.executeQuery(statement).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }

    private fun Connection.stage(connectionId: UInt, status: String, domain: String, stage: String) =
        run(
            "INSERT INTO metrics.status_stage_map (connection_id, status_id, domain_key, stage) " +
                "VALUES ($connectionId, '$status', '$domain', '$stage')",
        )

    private fun Connection.category(connectionId: UInt, status: String, category: String) =
        run(
            "INSERT INTO norm.statuses (connection_id, status_id, name, category) " +
                "VALUES ($connectionId, '$status', 'Name $status', '$category')",
        )

    private fun Connection.tick(connectionId: UInt, status: String) =
        run("INSERT INTO metrics.blocked_statuses (connection_id, status_id) VALUES ($connectionId, '$status')")

    /** Every `status_stage_map` row of [connectionId] as `status|domain -> stage`. */
    private fun Connection.stages(connectionId: UInt): Map<String, String> =
        query("SELECT status_id, domain_key, stage FROM metrics.status_stage_map WHERE connection_id = $connectionId") {
            "${it.getString(1)}|${it.getString(2)}" to it.getString(3)
        }.toMap()

    private suspend fun connection(prefix: String, enabled: Boolean): UInt =
        SyncedStubFixture.createConnection(namePrefix = prefix, enabled = enabled).also { connectionsToClean += it }

    @Test
    fun `every tick case converts or loses its tick as documented, and nothing leaks across connections`() = runBlocking {
        SyncedStubFixture.ensureMigrated()
        val ticked = connection("v29-ticked", enabled = true)
        val untouched = connection("v29-untouched", enabled = true)
        rolledBack { c ->
            // (a) a global IN_PROGRESS row; (b) a global WAITING row with a WAITING and a DONE per-domain override; (c) no row and an
            // IN_PROGRESS category; (c2) no row and a TODO category; (c3) only a WAITING override and an IN_PROGRESS category;
            // (d) NOT_STARTED and DONE rows; (u) a status that was never ticked.
            c.stage(ticked, "a", "", "IN_PROGRESS")
            c.stage(ticked, "b", "", "WAITING"); c.stage(ticked, "b", "D1", "WAITING"); c.stage(ticked, "b", "D2", "DONE")
            c.stage(ticked, "c3", "D1", "WAITING")
            c.stage(ticked, "d1", "", "NOT_STARTED"); c.stage(ticked, "d2", "", "DONE"); c.stage(ticked, "d2", "D1", "IN_PROGRESS")
            c.stage(ticked, "u", "", "IN_PROGRESS")
            c.category(ticked, "c", "IN_PROGRESS"); c.category(ticked, "c2", "TODO"); c.category(ticked, "c3", "IN_PROGRESS")
            listOf("a", "b", "c", "c2", "c3", "d1", "d2").forEach { c.tick(ticked, it) }
            // The same status id on a connection with NO tick is never converted.
            c.stage(untouched, "a", "", "IN_PROGRESS")
            c.category(untouched, "a", "IN_PROGRESS")

            c.run(convert)
            c.run(insertGlobal)

            assertEquals(
                mapOf(
                    "a|" to "BLOCKED",
                    "b|" to "BLOCKED", "b|D1" to "BLOCKED", "b|D2" to "DONE",
                    "c|" to "BLOCKED",
                    "c3|" to "BLOCKED", "c3|D1" to "BLOCKED",
                    "d1|" to "NOT_STARTED", "d2|" to "DONE", "d2|D1" to "BLOCKED",
                    "u|" to "IN_PROGRESS",
                ),
                c.stages(ticked),
                "(c2) inserts nothing; a never-ticked status and a DONE override stay; a ticked status' IN_PROGRESS override converts",
            )
            assertEquals(mapOf("a|" to "IN_PROGRESS"), c.stages(untouched), "another connection's rows are not touched")
        }
    }

    @Test
    fun `a tick bumps the shared revision and enqueues one DERIVE for each enabled ticked connection only`() = runBlocking {
        SyncedStubFixture.ensureMigrated()
        val enabledTicked = connection("v29-enabled", enabled = true)
        val disabledTicked = connection("v29-disabled", enabled = false)
        val enabledPlain = connection("v29-plain", enabled = true)
        rolledBack { c ->
            c.tick(enabledTicked, "x")
            c.tick(disabledTicked, "x")
            val revision = "SELECT config_revision FROM metrics.settings"
            val before = c.query(revision) { it.getLong(1) }.single()
            c.run(bump)
            assertEquals(before + 1, c.query(revision) { it.getLong(1) }.single(), "the shared revision moves when a tick existed")

            c.run(fanOut)
            val jobsSql = "SELECT connection_id, status, priority, config_revision, max_attempts, not_before FROM public.sync_jobs " +
                "WHERE kind = 'DERIVE' AND connection_id IN ($enabledTicked, $disabledTicked, $enabledPlain)"
            val jobs = c.query(jobsSql) {
                listOf(it.getInt(1), it.getString(2), it.getInt(3), it.getLong(4), it.getInt(5), it.getObject(6))
            }
            assertEquals(1, jobs.size, "no DERIVE for the disabled ticked connection or the enabled one with no tick")
            val own = c.query("SELECT config_revision FROM public.source_connections WHERE id = $enabledTicked") { it.getLong(1) }.single()
            val job = jobs.single()
            assertEquals(listOf(enabledTicked.toInt(), "PENDING", 10, own, 3), job.take(5))
            assertNull(job[5], "not_before stays NULL: the DERIVE is due at once")
        }
    }

    @Test
    fun `without a single tick the migration bumps nothing and enqueues nothing`() = runBlocking {
        SyncedStubFixture.ensureMigrated()
        val enabled = connection("v29-none", enabled = true)
        rolledBack { c ->
            val revision = "SELECT config_revision FROM metrics.settings"
            val before = c.query(revision) { it.getLong(1) }.single()
            c.run(convert)
            c.run(insertGlobal)
            c.run(bump)
            c.run(fanOut)
            assertEquals(before, c.query(revision) { it.getLong(1) }.single())
            val jobs = c.query("SELECT 1 FROM public.sync_jobs WHERE kind = 'DERIVE' AND connection_id = $enabled") { 1 }
            assertTrue(jobs.isEmpty(), "no tick, no DERIVE")
        }
    }
}
