package ch.nokillswit

import ch.nokillswit.ingest.StreamContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.atomic.AtomicInteger

/**
 * The run's progress without the closing ANALYZE's three keys (`normAnalyzed`, `normAnalyzeMs`, `normAnalyzeReason`): whether a
 * PROCESS ends with one depends on what the shared test database's statistics held when it started (never analyzed, a tenth of the
 * rows modified, the connection unknown), so a test that pins a PROCESS's whole counter map compares through this and leaves the
 * ANALYZE to [NormAnalyzeTest] / [NormAnalyzeGitLabTest].
 */
internal fun StreamContext.progressWithoutNormAnalyze(): Map<String, Long> =
    progressSnapshot() - setOf("normAnalyzed", "normAnalyzeMs", "normAnalyzeReason")

/**
 * Raw-JDBC probes of the post-PROCESS `ANALYZE` (`norm/NormAnalyze.kt`) shared by [NormAnalyzeTest] and [NormAnalyzeGitLabTest]: the
 * manual-ANALYZE counter of a table set, a lock held from a second session. `pg_stat_user_tables.analyze_count` counts MANUAL analyzes
 * only (autovacuum has `autoanalyze_count`), so an autovacuum pass can never move what the tests compare.
 */
private const val FLUSH_TIMEOUT_MS = 10_000L
/** The shared test pool's `maxSize` (`TestEnvironment.kt`). */
private const val TEST_POOL_SIZE = 10

internal object NormAnalyzeSupport {
    fun execute(sql: String) {
        PostgresTestSupport.connect().use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    /**
     * Brings the statistics of [tables] up to their true size, as a quiet autovacuum pass would. [quiet] first flushes the pooled
     * connections' pending statistics ([flushPooledStatistics]): a PROCESS that just ended leaves unflushed counters in the connections
     * it used, which would land after this ANALYZE reset `n_mod_since_analyze` and count as changes made later. Only a test that
     * asserts NO ANALYZE needs it.
     */
    suspend fun settle(tables: List<String>, quiet: Boolean = false) {
        if (quiet) flushPooledStatistics()
        execute("ANALYZE ${tables.joinToString(", ")}")
    }

    /**
     * PostgreSQL reports a session's table counters (`n_tup_*`, `n_mod_since_analyze`) at the end of a transaction only when a second has
     * passed since its last report, else at the idle timeout, up to 10 s later. `pg_stat_force_next_flush()` (PostgreSQL 15+) makes the
     * CALLING session report everything it holds at its next transaction end, whatever the interval, so no wait is needed: one
     * transaction on every connection of the shared test pool calls it. A barrier holds all [TEST_POOL_SIZE] transactions open until
     * each has started, which is what puts them on ten DISTINCT connections (a pool hands a free connection to the next acquirer, so
     * without it one connection could serve them all), then they commit together and each reports.
     */
    private suspend fun flushPooledStatistics() {
        val database = sharedDatabaseForTests()
        val started = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        withTimeout(FLUSH_TIMEOUT_MS) {
            coroutineScope {
                repeat(TEST_POOL_SIZE) {
                    launch {
                        suspendTransaction(database) {
                            exec("DO \$\$ BEGIN PERFORM pg_stat_force_next_flush(); END \$\$")
                            if (started.incrementAndGet() == TEST_POOL_SIZE) allStarted.complete(Unit)
                            allStarted.await()
                        }
                    }
                }
            }
        }
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
