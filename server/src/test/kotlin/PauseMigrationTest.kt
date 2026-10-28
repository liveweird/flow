package ch.nokillswit

import org.flywaydb.core.Flyway
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * V37 (`source_connection_pause`) on a database that already holds a connection: the three new columns read as "no trips, not
 * paused" on a row that predates them, and the CHECKs keep `paused_at`/`pause_reason` together, the reason in its closed
 * vocabulary and the counter non-negative.
 * Migrates a scratch database to V36, inserts the legacy row, then to the latest version - the shared one is already past it.
 */
class PauseMigrationTest {
    @Test
    fun `existing connections read as not paused with no trips, and the checks hold`() {
        val scratch = "mig_v37_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        PostgresTestSupport.connect().use { admin -> admin.createStatement().use { it.execute("CREATE DATABASE $scratch") } }
        try {
            // Flyway's own connections carry the same bounds as every test-side connection (`PostgresTestSupport.connect`).
            val base = PostgresTestSupport.jdbcUrlFor(scratch)
            val url = base + (if ('?' in base) "&" else "?") + "connectTimeout=30&socketTimeout=180"
            fun flyway(target: String) = Flyway.configure()
                .dataSource(url, PostgresTestSupport.user, PostgresTestSupport.password)
                .locations("classpath:db/migration")
                .target(target)
                .load()
            flyway("36").migrate()
            PostgresTestSupport.connect(scratch).use { db ->
                db.createStatement().use {
                    it.execute(
                        "INSERT INTO source_connections " +
                            "(kind, name, secret, settings, sync_interval_minutes, backfill_from, created_at, updated_at) " +
                            "VALUES ('JIRA_CLOUD', 'legacy', 'enc', '{}'::jsonb, 60, '2024-01-01', 1, 1)",
                    )
                }
                flyway("37").migrate()
                db.createStatement().use { st ->
                    st.executeQuery("SELECT limit_trips, paused_at, pause_reason FROM source_connections").use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt("limit_trips"))
                        rs.getLong("paused_at")
                        assertEquals(true, rs.wasNull())
                        assertNull(rs.getString("pause_reason"))
                    }
                    fun assertCheckViolation(sql: String, what: String) {
                        assertEquals("23514", assertFailsWith<SQLException>(what) { st.execute(sql) }.sqlState, what)
                    }
                    assertCheckViolation("UPDATE source_connections SET paused_at = 5", "paused_at without a reason")
                    assertCheckViolation("UPDATE source_connections SET pause_reason = 'LIMIT_EXCEEDED'", "a reason without paused_at")
                    assertCheckViolation("UPDATE source_connections SET limit_trips = -1", "a negative count")
                    assertCheckViolation(
                        "UPDATE source_connections SET paused_at = 5, pause_reason = 'because'",
                        "a reason outside the closed vocabulary",
                    )
                    st.execute("UPDATE source_connections SET paused_at = 5, pause_reason = 'LIMIT_EXCEEDED', limit_trips = 5")
                }
            }
        } finally {
            PostgresTestSupport.connect().use { admin ->
                admin.createStatement().use { it.execute("DROP DATABASE IF EXISTS $scratch WITH (FORCE)") }
            }
        }
    }
}
