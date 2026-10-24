package ch.nokillswit

import ch.nokillswit.telemetry.ConnectionTable
import ch.nokillswit.telemetry.HeavyReader
import ch.nokillswit.telemetry.Reading
import ch.nokillswit.telemetry.TelemetryMetrics
import ch.nokillswit.telemetry.apportion
import ch.nokillswit.telemetry.connectionEstimateReadings
import ch.nokillswit.telemetry.connectionTables
import ch.nokillswit.telemetry.storageReadings
import kotlinx.coroutines.runBlocking
import org.junit.After
import java.util.UUID
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PAD = "x"
private const val HEAVY_TIMEOUT_SECONDS = 30

/**
 * The storage collectors (`telemetry/StorageCollectors.kt`) over the shared container: the per-schema sizes, and the per-connection
 * estimate - for every table of `raw`/`norm`/`metrics` with a `connection_id` column, each connection's row share times the table's
 * size. The estimate is graded against an INDEPENDENT derivation (`information_schema` for the tables, plain JDBC for the counts and
 * sizes), including a table the test itself adds with a `connection_id` column (nothing is hand-listed) and a soft-deleted but
 * unpurged connection (its rows are reclaimable storage, so they count).
 */
class StorageEstimateTest {
    private val store = telemetryStore()
    private val dataSources = SyncedStubFixture.dataSources()

    private suspend fun connection(prefix: String): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(dataSources, namePrefix = prefix, enabled = false)
    }

    @After
    fun removeSeededIssues() {
        PostgresTestSupport.connect().use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM raw.jira_issues WHERE project_key = 'EST'") }
        }
    }

    /** [count] live issues for [connId], plus [tombstoned] ones with `deleted_at` set (stored, but not live). */
    private fun seedIssues(connId: UInt, count: Int, tombstoned: Int = 0) = PostgresTestSupport.connect().use { conn ->
        conn.prepareStatement(
            "INSERT INTO raw.jira_issues (connection_id, issue_id, issue_key, project_id, project_key, issue_updated_at, payload, " +
                "sha256, first_seen_at, fetched_at, changed_at, deleted_at) " +
                "VALUES (?, ?, ?, 1, 'EST', 0, ?::jsonb, repeat('a', 64), 0, 0, 0, ?)",
        ).use { st ->
            repeat(count + tombstoned) { n ->
                st.setInt(1, connId.toInt())
                st.setLong(2, 9_000_000L + n)
                st.setString(3, "EST-$n")
                st.setString(4, """{"pad":"${PAD.repeat(200)}"}""")
                if (n < count) st.setNull(5, java.sql.Types.BIGINT) else st.setLong(5, 1L)
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    private val reader = HeavyReader(store, HEAVY_TIMEOUT_SECONDS)

    private fun List<Reading>.of(metric: ch.nokillswit.telemetry.MetricDef, id: UInt): Double? =
        firstOrNull { it.metric == metric && it.label == id.toString() }?.value

    /** Per `schema.table` with a `connection_id` column (found through `information_schema`): rows of the given ids, and all rows. */
    private class Independent(val rows: Map<UInt, Long>, val total: Long)

    private fun independent(ids: Set<UInt>): Map<String, Independent> = PostgresTestSupport.connect().use { conn ->
        val tables = conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT c.table_schema, c.table_name FROM information_schema.columns c " +
                    "JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name " +
                    "WHERE c.column_name = 'connection_id' AND t.table_type = 'BASE TABLE' " +
                    "AND c.table_schema IN ('raw', 'norm', 'metrics')",
            ).use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
        tables.associate { (schema, table) ->
            val name = "$schema.$table"
            conn.createStatement().use { st ->
                val rows = ids.associateWith { id ->
                    st.executeQuery("SELECT count(*) FROM $name WHERE connection_id = $id").use { rs -> rs.next(); rs.getLong(1) }
                }
                val total = st.executeQuery("SELECT count(*) FROM $name").use { rs -> rs.next(); rs.getLong(1) }
                name to Independent(rows, total)
            }
        }
    }

    @Test
    fun `issues and estimated bytes per connection match an independent derivation`() = runBlocking {
        val a = connection("est-a")
        val b = connection("est-b")
        seedIssues(a, 3, tombstoned = 2)
        seedIssues(b, 9)

        val readings = reader.connectionEstimateReadings()
        assertEquals(3.0, readings.of(TelemetryMetrics.CONN_ISSUES, a), "live issues only: the two tombstoned ones are stored, not counted")
        assertEquals(9.0, readings.of(TelemetryMetrics.CONN_ISSUES, b))
        assertTrue(assertNotNull(readings.of(TelemetryMetrics.CONN_BYTES_EST, a)) > 0)

        // The bytes are graded against the SAME size read (relation sizes move under autovacuum: fork growth), with the row counts
        // and the table set derived independently through plain JDBC and information_schema.
        val tables = reader.connectionTables()
        val expected = independent(setOf(a, b))
        assertEquals(expected.keys, tables.map { it.name }.toSet(), "the same tables are discovered")
        val bytes = apportion(tables)
        for (id in listOf(a, b)) {
            val independentBytes = tables.sumOf { t ->
                val x = expected.getValue(t.name)
                if (x.total == 0L) 0.0 else t.sizeBytes.toDouble() * x.rows.getValue(id) / x.total
            }
            val reported = bytes.getValue(id.toLong())
            assertTrue(abs(reported - independentBytes) <= 1e-6 * independentBytes + 1e-6, "connection $id: $reported vs $independentBytes")
        }
        val issuesTable = expected.getValue("raw.jira_issues")
        assertEquals(5L, issuesTable.rows.getValue(a), "stored rows, tombstones included, weigh into the bytes")
        assertEquals(9L, issuesTable.rows.getValue(b))
    }

    @Test
    fun `the shares of every table add up to its size together with the rows no connection owns`() = runBlocking {
        val a = connection("est-sum-a")
        val b = connection("est-sum-b")
        seedIssues(a, 5)
        seedIssues(b, 2)

        val tables = reader.connectionTables()
        assertTrue(tables.size >= 10, "raw/norm/metrics have many connection tables: ${tables.map { it.name }}")
        for (table in tables) {
            val rows = table.rowsByConnection.values.sum()
            if (rows == 0L) continue
            val owned = apportion(listOf(table)).values.sum()
            val unowned = table.sizeBytes.toDouble() * (table.rowsByConnection[null] ?: 0L) / rows
            val gap = abs(owned + unowned - table.sizeBytes)
            assertTrue(gap <= 1e-6 * table.sizeBytes + 1e-6, "${table.name}: $owned + $unowned vs ${table.sizeBytes}")
        }
        // Two connections that own all the rows of a table split its size exactly in their row ratio.
        val split = apportion(listOf(ConnectionTable("t", 1_000, mapOf(1L to 1, 2L to 3))))
        assertEquals(250.0, split.getValue(1L), 1e-9)
        assertEquals(750.0, split.getValue(2L), 1e-9)
        assertEquals(emptyMap(), apportion(listOf(ConnectionTable("empty", 8_192, emptyMap()))), "an empty table apportions nothing")
        assertEquals(mapOf(7L to 100.0), apportion(listOf(ConnectionTable("one", 100, mapOf(7L to 4)))), "exact for one connection")
    }

    @Test
    fun `a table added with a connection_id column is discovered, one without is not`() = runBlocking {
        val a = connection("est-new-a")
        val b = connection("est-new-b")
        val table = "metrics.zz_est_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        PostgresTestSupport.connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE $table (connection_id integer, pad text)")
                st.execute("INSERT INTO $table SELECT $a, repeat('p', 500) FROM generate_series(1, 4)")
                st.execute("INSERT INTO $table SELECT $b, repeat('p', 500) FROM generate_series(1, 1)")
            }
        }
        try {
            val tables = reader.connectionTables()
            val added = assertNotNull(tables.firstOrNull { it.name == table }, "discovered with no code change: ${tables.map { it.name }}")
            assertEquals(mapOf<Long?, Long>(a.toLong() to 4L, b.toLong() to 1L), added.rowsByConnection)
            val share = apportion(listOf(added))
            assertEquals(added.sizeBytes * 0.8, share.getValue(a.toLong()), 1e-6)
            assertEquals(added.sizeBytes * 0.2, share.getValue(b.toLong()), 1e-6)
            val outside = tables.firstOrNull { it.name == "public.users" || it.name == "ops.telemetry_samples" }
            assertNull(outside, "only raw/norm/metrics are scanned")
        } finally {
            PostgresTestSupport.connect().use { conn -> conn.createStatement().use { it.execute("DROP TABLE IF EXISTS $table") } }
        }
    }

    @Test
    fun `a soft-deleted but unpurged connection is still estimated`() = runBlocking {
        val live = connection("est-live")
        val gone = connection("est-gone")
        seedIssues(live, 2)
        seedIssues(gone, 6)
        assertEquals(1, dataSources.delete(gone))

        val readings = reader.connectionEstimateReadings()
        assertEquals(6.0, readings.of(TelemetryMetrics.CONN_ISSUES, gone), "its rows are reclaimable storage until the purge")
        assertTrue(assertNotNull(readings.of(TelemetryMetrics.CONN_BYTES_EST, gone)) > 0)
        assertEquals(2.0, readings.of(TelemetryMetrics.CONN_ISSUES, live))
    }

    @Test
    fun `the storage reading reports the database and each schema`() = runBlocking {
        val readings = reader.storageReadings()
        val total = readings.single { it.metric == TelemetryMetrics.DB_SIZE }.value
        val bySchema = readings.filter { it.metric == TelemetryMetrics.DB_SCHEMA_SIZE }.associate { it.label to it.value }
        assertEquals(setOf("raw", "norm", "metrics", "public", "ops"), bySchema.keys)
        assertTrue(bySchema.values.all { it > 0 }, "$bySchema: every schema holds tables here")
        assertTrue(bySchema.values.sum() < total, "the schemas are part of the database ($bySchema of $total)")
        val independent = PostgresTestSupport.connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT COALESCE(SUM(pg_total_relation_size(c.oid)), 0) FROM pg_class c " +
                        "JOIN pg_namespace n ON n.oid = c.relnamespace " +
                        "WHERE n.nspname = 'raw' AND c.relkind IN ('r', 'p', 'm')",
                ).use { rs -> rs.next(); rs.getLong(1) }
            }
        }
        val raw = bySchema.getValue("raw")
        assertTrue(abs(independent - raw) <= 0.05 * independent, "raw: $independent vs $raw")
    }
}
