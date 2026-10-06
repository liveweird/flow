package ch.nokillswit

import ch.nokillswit.metrics.ANALYZED_TABLES
import ch.nokillswit.metrics.MetricsTables
import ch.qos.logback.classic.Level
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression pins for the `ANALYZE` of DERIVE (`MetricsStore.analyzeDerivedTables`,
 * `.claude/docs/metrics.md` "The DERIVE run algorithm"). DERIVE rebuilds `metrics.*` in ONE transaction;
 * a connection's FIRST derive ANALYZEs inside it (no statistics at all would mean `rows=1` plans and
 * every re-derive slower, build-times WHY 1), every LATER derive ANALYZEs after the commit in its own
 * short transaction, so concurrent derives overlap (the lock is held for milliseconds, not to commit).
 * Deliberately not a timing test of the plans — it pins the statistics a derive leaves behind
 * (`pg_stat_user_tables.last_analyze` advanced by THIS derive for every table in `ANALYZED_TABLES`),
 * which path each kind of derive takes (a foreign transaction holding `ANALYZE metrics.item_stage`
 * open blocks a first derive's COMMIT but never a re-derive's, whose post-commit ANALYZE gives up after its lock
 * timeout with a WARN and a still-SUCCEEDED run), that `ANALYZED_TABLES` covers every
 * table the WIP/flow SQL sources mention (a future join to an un-analyzed table fails here rather than
 * silently planning on stale statistics), and logs the measured two-connection overlap. Every test
 * derives its own DISABLED clone (`.claude/docs/testing.md` fixture rules).
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
    fun `a first DERIVE leaves fresh planner statistics on every table the WIP and flow steps read`() = runBlocking {
        SyncedStubFixture.ensureMigrated()
        val connId = preparedClone("analyze-team")
        val startedAt = dbNow()
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId, jobId = 1u) }

        val stale = staleTablesAfter(startedAt)
        assertTrue(stale.isEmpty(), "a DERIVE must ANALYZE every table its WIP/flow SQL reads; no fresh last_analyze for: $stale")
    }

    private suspend fun preparedClone(teamPrefix: String): UInt {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-analyze-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), teamPrefix)
        return connId
    }

    private fun dbNow(): Timestamp = jdbc { conn ->
        conn.createStatement().use { st -> st.executeQuery("SELECT clock_timestamp()").use { rs -> rs.next(); rs.getTimestamp(1) } }
    }

    /** Polls (the stats collector reports a second or so after commit) until every [ANALYZED_TABLES] entry was analyzed after [since]. */
    private suspend fun staleTablesAfter(since: Timestamp): List<String> {
        var polls = 0
        var stale: List<String>
        do {
            val analyzed = lastAnalyze()
            stale = ANALYZED_TABLES.filter { (analyzed[it]?.after(since)) != true }
            if (stale.isNotEmpty()) delay(ANALYZE_POLL_MS)
        } while (stale.isNotEmpty() && ++polls < ANALYZE_POLLS)
        return stale
    }

    private suspend fun succeededRuns(connId: UInt): Int = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.DeriveRuns.selectAll()
            .where { (MetricsTables.DeriveRuns.connectionId eq connId.toInt()) and (MetricsTables.DeriveRuns.status eq "SUCCEEDED") }
            .toList().size
    }

    private suspend fun latestRunStatus(connId: UInt): String? = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.DeriveRuns.selectAll().where { MetricsTables.DeriveRuns.connectionId eq connId.toInt() }
            .orderBy(MetricsTables.DeriveRuns.id to SortOrder.DESC).limit(1).toList().singleOrNull()
            ?.get(MetricsTables.DeriveRuns.status)
    }

    /** A foreign JDBC session holding `ANALYZE <table>` open (its `SHARE UPDATE EXCLUSIVE` lock, held to the rollback). */
    private class AnalyzeHolder(val pid: Int, val release: CompletableDeferred<Unit>)

    private fun connect(): Connection =
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)

    /**
     * Runs [block] while ANOTHER session holds `ANALYZE metrics.item_stage` open in a transaction — the lock a concurrent
     * derive's ANALYZE would hold. The lock is always released in `finally` (a statement blocked on it cannot be cancelled).
     */
    private suspend fun <T> withItemStageAnalyzeHeld(block: suspend (AnalyzeHolder) -> T): T = coroutineScope {
        val held = CompletableDeferred<AnalyzeHolder>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            connect().use { conn ->
                try {
                    conn.autoCommit = false
                    conn.createStatement().use { it.execute("ANALYZE metrics.item_stage") }
                    val pid = conn.createStatement().use { st ->
                        st.executeQuery("SELECT pg_backend_pid()").use { rs -> rs.next(); rs.getInt(1) }
                    }
                    held.complete(AnalyzeHolder(pid, release))
                    release.await()
                    conn.rollback()
                } catch (failure: Exception) {
                    held.completeExceptionally(failure)
                    throw failure
                }
            }
        }
        try {
            block(held.await())
        } finally {
            release.complete(Unit)
            holder.join()
        }
    }

    /** Waits until some backend is blocked BY the [holder] (a derive's ANALYZE queued on its lock); fails after [BACKSTOP_MS]. */
    private suspend fun awaitWaiter(holder: AnalyzeHolder) {
        val deadline = System.nanoTime() + BACKSTOP_MS * NANOS_PER_MS
        connect().use { conn ->
            conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))").use { stmt ->
                stmt.setInt(1, holder.pid)
                while (stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) } == 0) {
                    assertTrue(System.nanoTime() < deadline, "no ANALYZE ever queued behind the held item_stage lock")
                    delay(WAITER_POLL_MS)
                }
            }
        }
    }

    @Test
    fun `a second DERIVE of a connection advances last_analyze on every analyzed table (the post-commit path)`() = runBlocking {
        val connId = preparedClone("analyze-second-team")
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            DerivedStubFixture.derivePinned(connId, jobId = 1u)
            val beforeSecond = dbNow()
            DerivedStubFixture.derivePinned(connId, jobId = 2u)
            // derive() returns only after its post-commit ANALYZE, so everything is stamped after `beforeSecond`.
            val stale = staleTablesAfter(beforeSecond)
            assertTrue(stale.isEmpty(), "a re-DERIVE must ANALYZE every table its WIP/flow SQL reads; no fresh last_analyze for: $stale")
        }
        assertEquals(2, succeededRuns(connId))
    }

    @Test
    fun `a re-DERIVE commits and succeeds while a foreign ANALYZE holds a table lock, and ANALYZEs once it is released`() = runBlocking {
        val connId = preparedClone("analyze-held-re-team")
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            DerivedStubFixture.derivePinned(connId, jobId = 1u)
            val beforeRederive = dbNow()
            withItemStageAnalyzeHeld { holder ->
                val rederive = async(Dispatchers.Default) { DerivedStubFixture.derivePinned(connId, jobId = 2u) }
                awaitWaiter(holder) // the post-commit ANALYZE has queued on item_stage …
                assertEquals(2, succeededRuns(connId), "… so the derive's data is already committed and its run marked SUCCEEDED")
                assertFalse(rederive.isCompleted, "the post-commit ANALYZE waits for the foreign lock rather than skipping the table")
                holder.release.complete(Unit)
                rederive.await()
            }
            val stale = staleTablesAfter(beforeRederive)
            assertTrue(stale.isEmpty(), "once the lock is free the post-commit ANALYZE must cover every table; stale: $stale")
        }
    }

    @Test
    fun `a re-DERIVE whose post-commit ANALYZE cannot get its lock in time WARNs and still succeeds`() = runBlocking {
        val connId = preparedClone("analyze-timeout-team")
        val capture = LogCapture("ch.nokillswit.metrics.MetricsDeriver")
        try {
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
                DerivedStubFixture.derivePinned(connId, jobId = 1u)
                withItemStageAnalyzeHeld { holder ->
                    val rederive = async(Dispatchers.Default) {
                        DerivedStubFixture.derivePinned(connId, jobId = 2u, analyzeLockTimeoutMs = SHORT_LOCK_TIMEOUT_MS)
                    }
                    awaitWaiter(holder) // the post-commit ANALYZE has queued; the lock stays held past its timeout
                    val waitStart = System.nanoTime()
                    withTimeout(SHORT_LOCK_TIMEOUT_MS + TIMEOUT_MARGIN_MS) { rederive.await() }
                    val waitedMs = (System.nanoTime() - waitStart) / NANOS_PER_MS
                    assertTrue(waitedMs < SHORT_LOCK_TIMEOUT_MS + TIMEOUT_MARGIN_MS, "the derive call returned after $waitedMs ms")
                    assertFalse(holder.release.isCompleted, "the derive finished while the foreign lock was still held")
                }
            }
            assertEquals(2, succeededRuns(connId), "a timed-out post-commit ANALYZE never fails the run")
            assertEquals("SUCCEEDED", latestRunStatus(connId))
            val warned = capture.events.any { it.level == Level.WARN && it.formattedMessage.contains("post-commit ANALYZE") }
            assertTrue(warned, "the lock timeout must be logged as a WARN")
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `a first DERIVE ANALYZEs inside its transaction, so a foreign ANALYZE lock holds back its commit`() = runBlocking {
        val connId = preparedClone("analyze-held-first-team")
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            withItemStageAnalyzeHeld { holder ->
                // A lock timeout far below the hold must NOT apply to the in-transaction ANALYZE: it has to wait for fresh stats.
                val first = async(Dispatchers.Default) {
                    DerivedStubFixture.derivePinned(connId, jobId = 1u, analyzeLockTimeoutMs = SHORT_LOCK_TIMEOUT_MS)
                }
                awaitWaiter(holder) // the in-transaction ANALYZE has queued on item_stage
                delay(HOLD_WAIT_MS)
                assertFalse(first.isCompleted, "a first DERIVE must not finish while its in-transaction ANALYZE waits on the lock")
                assertEquals("RUNNING", latestRunStatus(connId), "the first derive's data is not committed until its ANALYZE ran")
                holder.release.complete(Unit)
                first.await()
            }
        }
        assertEquals("SUCCEEDED", latestRunStatus(connId))
    }

    /**
     * Two connections' re-derives running at once vs one after the other: the ANALYZE no longer holds shared table
     * locks to the commit, so only the (shared-CPU-bound) fact building is left to contend. The wall times are LOGGED,
     * not asserted — a timing assertion would flake on a loaded CI box — and `build-times.md` records the measurement.
     */
    @Test
    fun `two connections re-derive concurrently and the overlap is measured`() = runBlocking {
        val first = preparedClone("analyze-conc-a-team")
        val second = preparedClone("analyze-conc-b-team")
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            DerivedStubFixture.derivePinned(first, jobId = 1u)
            DerivedStubFixture.derivePinned(second, jobId = 1u)

            val sequentialStart = System.nanoTime()
            DerivedStubFixture.derivePinned(first, jobId = 2u)
            DerivedStubFixture.derivePinned(second, jobId = 2u)
            val sequentialMs = (System.nanoTime() - sequentialStart) / NANOS_PER_MS

            val concurrentStart = System.nanoTime()
            coroutineScope {
                listOf(first, second)
                    .map { connId -> async(Dispatchers.Default) { DerivedStubFixture.derivePinned(connId, jobId = 3u) } }
                    .awaitAll()
            }
            val concurrentMs = (System.nanoTime() - concurrentStart) / NANOS_PER_MS
            println("MetricsAnalyzeTest two re-derives: sequential $sequentialMs ms, concurrent $concurrentMs ms")
        }
        assertEquals(3, succeededRuns(first))
        assertEquals(3, succeededRuns(second))
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
        const val WAITER_POLL_MS = 50L
        const val HOLD_WAIT_MS = 1_500L
        const val SHORT_LOCK_TIMEOUT_MS = 500L
        const val TIMEOUT_MARGIN_MS = 10_000L
        const val BACKSTOP_MS = 120_000L
        const val NANOS_PER_MS = 1_000_000L

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
