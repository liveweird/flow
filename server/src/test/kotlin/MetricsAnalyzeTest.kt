package ch.nokillswit

import ch.nokillswit.metrics.ANALYZED_TABLES
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression pin for the `ANALYZE` inside DERIVE (`MetricsStore.analyzeDerivedTables`,
 * `.claude/docs/metrics.md` "The DERIVE run algorithm"): DERIVE rebuilds `metrics.*` in ONE
 * transaction, so without an in-transaction `ANALYZE` the WIP/flow `INSERT ... SELECT`s plan against
 * `rows=1` estimates and every re-derive gets slower. Deliberately not a timing test — it pins the
 * statistics the derive leaves behind (`pg_stat_user_tables.last_analyze` advanced by THIS derive
 * for every table in `ANALYZED_TABLES`), and that `ANALYZED_TABLES` covers every table the WIP/flow
 * SQL sources mention, so a future join to an un-analyzed table fails here rather than silently
 * planning on stale statistics. Derives its own DISABLED clone (`.claude/docs/testing.md` fixture rules).
 */
class MetricsAnalyzeTest {
    private fun <T> jdbc(block: (Connection) -> T): T =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use(block)

    /** `last_analyze` per analyzed table (null = never), read on a fresh autocommit connection. */
    private fun lastAnalyze(): Map<String, Timestamp?> = jdbc { conn ->
        conn.createStatement().use { st ->
            val sql = "SELECT schemaname || '.' || relname, last_analyze FROM pg_stat_user_tables WHERE schemaname = 'metrics'"
            st.executeQuery(sql).use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getTimestamp(2)) }
            }
        }
    }

    @Test
    fun `a DERIVE leaves fresh planner statistics on every table the WIP and flow steps read`() = runBlocking {
        SyncedStubFixture.ensureMigrated()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-analyze-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        val config = DerivedStubFixture.metricsConfig()
        DerivedStubFixture.mapFloBoardToNewTeam(connId, config, "analyze-team")

        val startedAt = jdbc { conn ->
            conn.createStatement().use { st -> st.executeQuery("SELECT clock_timestamp()").use { rs -> rs.next(); rs.getTimestamp(1) } }
        }
        DerivedStubFixture.withPinnedSettings(config) { DerivedStubFixture.derivePinned(connId, config, jobId = 1u) }

        // The stats collector reports asynchronously (a second or so after commit): poll, never sleep blind.
        var polls = 0
        var stale: List<String>
        do {
            val analyzed = lastAnalyze()
            stale = ANALYZED_TABLES.filter { (analyzed[it]?.after(startedAt)) != true }
            if (stale.isNotEmpty()) delay(ANALYZE_POLL_MS)
        } while (stale.isNotEmpty() && ++polls < ANALYZE_POLLS)
        assertTrue(stale.isEmpty(), "a DERIVE must ANALYZE every table its WIP/flow SQL reads; no fresh last_analyze for: $stale")
    }

    @Test
    fun `ANALYZED_TABLES covers every table the WIP and flow SQL reads`() {
        val mentioned = STEP_SOURCES.flatMap { file ->
            val source = File("src/main/kotlin/metrics/$file").readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "") // KDoc/block comments name docs like `metrics.md`
                .replace(Regex("//[^\\n]*"), "")
            TABLE_REFERENCE.findAll(source).map { it.value }.toList()
        }.toSet()
        assertTrue(mentioned.size > ANALYZED_TABLES.size / 2, "the source scan must find the steps' tables; found $mentioned")
        val uncovered = mentioned - ANALYZED_TABLES.toSet() - NOT_ANALYZED
        assertTrue(uncovered.isEmpty(), "the WIP/flow SQL reads tables that DERIVE does not ANALYZE (add to ANALYZED_TABLES): $uncovered")
        assertTrue(NOT_ANALYZED.none { it in ANALYZED_TABLES }, "an excluded table must not also be analyzed")
    }

    private companion object {
        const val ANALYZE_POLLS = 40
        const val ANALYZE_POLL_MS = 500L

        val STEP_SOURCES = listOf("DeriveWipStep.kt", "DeriveFlowStep.kt")
        val TABLE_REFERENCE = Regex("\\b(metrics|norm)\\.[a-z_]+")

        /** Tables the steps mention but DERIVE deliberately does not ANALYZE, each with its reason. */
        val NOT_ANALYZED = setOf(
            "metrics.agg_daily_wip", // written by the WIP step, never read by it
            "metrics.agg_daily_flow", // the flow step's write target (its ON CONFLICT merge reads only the conflicting row)
            "metrics.team_membership", // configuration, not rebuilt by DERIVE; autovacuum keeps it fresh
            "norm.work_item_field_intervals", // PROCESS's table, already committed before DERIVE; autovacuum analyzes it
        )
    }
}
