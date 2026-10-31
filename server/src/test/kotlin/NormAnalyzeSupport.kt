package ch.nokillswit

import ch.nokillswit.ingest.StreamContext

/**
 * The run's progress without the closing ANALYZE's two keys (`normAnalyzed`, `normAnalyzeMs`): whether a PROCESS ends with one depends
 * on what the shared test database's statistics held when it started (never analyzed, or a tenth of the rows), so a test that pins a
 * PROCESS's whole counter map compares through this and leaves the ANALYZE to [NormAnalyzeTest] / [NormAnalyzeGitLabTest].
 */
internal fun StreamContext.progressWithoutNormAnalyze(): Map<String, Long> = progressSnapshot() - setOf("normAnalyzed", "normAnalyzeMs")

/**
 * Raw-JDBC probes of the post-PROCESS `ANALYZE` (`norm/NormAnalyze.kt`) shared by [NormAnalyzeTest] and [NormAnalyzeGitLabTest]: the
 * manual-ANALYZE counter of a table set, a lock held from a second session. `pg_stat_user_tables.analyze_count` counts MANUAL analyzes
 * only (autovacuum has `autoanalyze_count`), so an autovacuum pass can never move what the tests compare.
 */
internal object NormAnalyzeSupport {
    fun execute(sql: String) {
        PostgresTestSupport.connect().use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    /** `table (schema.name) -> analyze_count` for [tables]. */
    fun analyzeCounts(tables: List<String>): Map<String, Long> = PostgresTestSupport.connect().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT schemaname || '.' || relname, analyze_count FROM pg_stat_user_tables " +
                    "WHERE schemaname || '.' || relname IN (${tables.joinToString(",") { "'$it'" }})",
            ).use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getLong(2)) }
            }
        }
    }

    /**
     * Runs [block] while a SECOND session holds `SHARE UPDATE EXCLUSIVE` on [table] (the lock an `ANALYZE` or a `VACUUM` takes: it
     * conflicts with itself and with DDL, not with the row writes of a PROCESS), then rolls it back.
     */
    inline fun <T> holdingAnalyzeLock(table: String, block: () -> T): T =
        PostgresTestSupport.connect().use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("LOCK TABLE $table IN SHARE UPDATE EXCLUSIVE MODE") }
                block()
            } finally {
                conn.rollback()
            }
        }
}
