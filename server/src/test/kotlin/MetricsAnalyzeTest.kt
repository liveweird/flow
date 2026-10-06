package ch.nokillswit

import ch.nokillswit.metrics.ANALYZED_TABLES
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.asRequest
import ch.nokillswit.metrics.statisticsDescribeRows
import ch.qos.logback.classic.Level
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression pins for the `ANALYZE` of DERIVE (`MetricsStore.analyzeDerivedTables`,
 * `.claude/docs/metrics.md` "The DERIVE run algorithm"). DERIVE rebuilds `metrics.*` in ONE transaction;
 * a derive whose predecessor left no statistics that describe the connection's rows (the first one, or one where any
 * per-table count — tasks, epics, sprints, worklogs, epic plans, estimates — went from 0 to non-zero or more than
 * doubled since the previous SUCCEEDED run's `row_counts`, `statisticsDescribeRows`)
 * ANALYZEs inside it (no statistics would mean `rows=1` plans and every re-derive slower, build-times WHY 1); every
 * other derive ANALYZEs after the commit in its own short, time-bounded transaction, so concurrent derives overlap.
 * Deliberately not a timing test of the plans — it pins the statistics a derive leaves behind
 * (`pg_stat_user_tables.last_analyze` advanced by THIS derive for every table in `ANALYZED_TABLES`), which path each
 * kind of derive takes (a foreign transaction holding `ANALYZE metrics.item_stage` open holds back an
 * in-transaction derive's COMMIT but never a re-derive's, whose post-commit ANALYZE gives up after its lock timeout
 * with a WARN and a still-SUCCEEDED run), and that `ANALYZED_TABLES` covers every table the WIP/flow SQL sources
 * mention (a future join to an un-analyzed table fails here rather than silently planning on stale statistics).
 * The re-derive tests share ONE derived clone (`SharedClone`); the in-transaction ones need a derive that is
 * first (or over an empty connection), so each makes its own DISABLED clone (`.claude/docs/testing.md` fixture
 * rules). The two-connection overlap measurement is opt-in (`FLOW_MEASURE_DERIVE_OVERLAP=1`).
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

    /**
     * Waits until some backend is blocked BY the [holder] (a derive's ANALYZE queued on its lock); fails at once if the
     * [derive] already completed (no ANALYZE ever waited) and after [BACKSTOP_MS] otherwise.
     */
    private suspend fun awaitWaiter(holder: AnalyzeHolder, derive: Deferred<*>) {
        val deadline = System.nanoTime() + BACKSTOP_MS * NANOS_PER_MS
        connect().use { conn ->
            conn.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))").use { stmt ->
                stmt.setInt(1, holder.pid)
                while (stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) } == 0) {
                    assertFalse(derive.isCompleted, "the derive completed without any ANALYZE queuing behind the held item_stage lock")
                    assertTrue(System.nanoTime() < deadline, "no ANALYZE ever queued behind the held item_stage lock")
                    delay(WAITER_POLL_MS)
                }
            }
        }
    }

    @Test
    fun `a second DERIVE of a connection advances last_analyze on every analyzed table (the post-commit path)`() = runBlocking {
        val connId = SharedClone.connectionId()
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            val before = dbNow()
            DerivedStubFixture.derivePinned(connId, SharedClone.nextJobId())
            // derive() returns only after its post-commit ANALYZE, so everything is stamped after `before`.
            val stale = staleTablesAfter(before)
            assertTrue(stale.isEmpty(), "a re-DERIVE must ANALYZE every table its WIP/flow SQL reads; no fresh last_analyze for: $stale")
        }
    }

    @Test
    fun `a re-DERIVE commits and succeeds while a foreign ANALYZE holds a table lock, and ANALYZEs once it is released`() = runBlocking {
        val connId = SharedClone.connectionId()
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            val runsBefore = succeededRuns(connId)
            val before = dbNow()
            withItemStageAnalyzeHeld { holder ->
                val rederive = async(Dispatchers.Default) {
                    DerivedStubFixture.derivePinned(connId, SharedClone.nextJobId(), analyzeLockTimeoutMs = LONG_LOCK_TIMEOUT_MS)
                }
                awaitWaiter(holder, rederive) // the post-commit ANALYZE has queued on item_stage …
                assertEquals(runsBefore + 1, succeededRuns(connId), "… so the data is already committed and the run marked SUCCEEDED")
                assertFalse(rederive.isCompleted, "the post-commit ANALYZE waits for the foreign lock rather than skipping the table")
                holder.release.complete(Unit)
                rederive.await()
            }
            val stale = staleTablesAfter(before)
            assertTrue(stale.isEmpty(), "once the lock is free the post-commit ANALYZE must cover every table; stale: $stale")
        }
    }

    @Test
    fun `a re-DERIVE whose post-commit ANALYZE cannot get its lock in time WARNs and still succeeds`() = runBlocking {
        val connId = SharedClone.connectionId()
        val capture = LogCapture("ch.nokillswit.metrics.MetricsDeriver")
        try {
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
                val runsBefore = succeededRuns(connId)
                withItemStageAnalyzeHeld { holder ->
                    val rederive = async(Dispatchers.Default) {
                        DerivedStubFixture.derivePinned(
                            connId,
                            SharedClone.nextJobId(),
                            analyzeLockTimeoutMs = TIMEOUT_TEST_LOCK_TIMEOUT_MS,
                        )
                    }
                    awaitWaiter(holder, rederive) // the post-commit ANALYZE has queued; the lock stays held past its timeout
                    val waitStart = System.nanoTime()
                    withTimeout(TIMEOUT_TEST_LOCK_TIMEOUT_MS + TIMEOUT_MARGIN_MS) { rederive.await() }
                    val waitedMs = (System.nanoTime() - waitStart) / NANOS_PER_MS
                    assertTrue(
                        waitedMs < TIMEOUT_TEST_LOCK_TIMEOUT_MS + TIMEOUT_MARGIN_MS,
                        "the derive returned after $waitedMs ms (lock timeout $TIMEOUT_TEST_LOCK_TIMEOUT_MS ms), the lock still held",
                    )
                }
                assertEquals(runsBefore + 1, succeededRuns(connId), "a timed-out post-commit ANALYZE never fails the run")
            }
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
            assertHeldBackInTransaction(connId)
        }
        assertEquals("SUCCEEDED", latestRunStatus(connId))
    }

    @Test
    fun `a derive after a SUCCEEDED run over zero tasks still ANALYZEs inside its transaction`() = runBlocking {
        // A new connection derived before its first SYNC: SUCCEEDED, but its statistics describe nothing.
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-analyze-empty", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            DerivedStubFixture.derivePinned(connId, jobId = 1u)
            assertEquals(1, succeededRuns(connId))
            assertEquals(0, store.newestSucceededRunRowCounts(connId)?.get("tasks"), "the empty derive wrote no tasks")

            SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
            DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "analyze-empty-team")
            assertHeldBackInTransaction(connId, jobId = 2u)
        }
        assertEquals(2, succeededRuns(connId))
        assertTrue((store.newestSucceededRunRowCounts(connId)?.get("tasks") ?: 0) > 0, "the real derive wrote tasks")
    }

    @Test
    fun `a config that fills item_estimate while the tasks stay flat makes the next derive ANALYZE inside its transaction`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-analyze-estimate", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        val config = DerivedStubFixture.metricsConfig()
        val store = MetricsStore(sharedDatabaseForTests())
        DerivedStubFixture.mapFloBoardToNewTeam(connId, config, "analyze-estimate-team")
        val withEstimates = config.effectiveConfig(connId)
        // The admin has not mapped the estimate fields yet: every task is derived, no item_estimate row is written.
        config.replaceConfig(
            connId,
            withEstimates.asRequest().copy(fields = withEstimates.fields.copy(estimateTask = null, estimateEpic = null)),
        )
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            DerivedStubFixture.derivePinned(connId, jobId = 1u)
            val before = requireNotNull(store.newestSucceededRunRowCounts(connId))
            assertEquals(0, before["estimates"], "no estimate field configured, no item_estimate rows")
            assertTrue((before["tasks"] ?: 0) > 0)

            // The first config PUT that maps them: same tasks, a table that was empty is now full.
            config.replaceConfig(connId, withEstimates.asRequest())
            assertHeldBackInTransaction(connId, jobId = 2u)
            val after = requireNotNull(store.newestSucceededRunRowCounts(connId))
            assertEquals(before["tasks"], after["tasks"], "the task count stayed flat")
            assertTrue((after["estimates"] ?: 0) > 0, "item_estimate was filled")
        }
        assertEquals(2, succeededRuns(connId))
    }

    @Test
    fun `statisticsDescribeRows decides per table, like with like`() {
        val previous = mapOf("tasks" to 1_000, "estimates" to 900, "sprints" to 0, "worklogs" to 50)
        fun described(current: Map<String, Int>, before: Map<String, Int>? = previous) = statisticsDescribeRows(before, current)

        assertFalse(described(mapOf("tasks" to 1_200), before = null), "no previous SUCCEEDED run")
        assertTrue(described(mapOf("tasks" to 1_200, "estimates" to 1_000, "sprints" to 0, "worklogs" to 60)), "everything similar")
        assertTrue(described(mapOf("tasks" to 2_000)), "exactly twice is still described")
        assertFalse(described(mapOf("tasks" to 2_001)), "more than twice the previous count is a different table")
        assertTrue(described(mapOf("tasks" to 300, "estimates" to 0)), "a shrunken or emptied table has nothing to plan")
        assertFalse(described(mapOf("tasks" to 1_000, "sprints" to 5)), "a table that was empty last time and is not now")
        assertFalse(described(mapOf("tasks" to 1_000), before = mapOf("tasks" to 0)), "a zero-task previous run left no statistics")
        assertFalse(described(mapOf("tasks" to 1_000, "epicPlans" to 3)), "a key the previous run did not record fails, once")
        assertTrue(described(mapOf("tasks" to 1_000, "epicPlans" to 0)), "… unless the table is empty now")
        assertTrue(described(emptyMap()), "nothing to compare")
        assertTrue(described(mapOf("tasks" to 0), before = emptyMap()), "an empty derive after any run plans nothing")
    }

    /**
     * Asserts [connId]'s next derive (job [jobId]) ANALYZEs INSIDE its transaction: with a foreign `ANALYZE
     * metrics.item_stage` held it queues, stays RUNNING (a short lock timeout, which applies only to the post-commit
     * path, does not rescue it) and finishes only after the release.
     */
    private suspend fun assertHeldBackInTransaction(connId: UInt, jobId: UInt = 1u) = coroutineScope {
        withItemStageAnalyzeHeld { holder ->
            val derive = async(Dispatchers.Default) {
                DerivedStubFixture.derivePinned(connId, jobId, analyzeLockTimeoutMs = TIMEOUT_TEST_LOCK_TIMEOUT_MS)
            }
            awaitWaiter(holder, derive) // the in-transaction ANALYZE has queued on item_stage
            delay(HOLD_WAIT_MS)
            assertFalse(derive.isCompleted, "the derive must not finish while its in-transaction ANALYZE waits on the lock")
            assertEquals("RUNNING", latestRunStatus(connId), "its data is not committed until its ANALYZE ran")
            holder.release.complete(Unit)
            derive.await()
        }
    }

    /**
     * Two connections' re-derives running at once vs one after the other: the ANALYZE no longer holds shared table
     * locks to the commit, so only the (shared-CPU-bound) fact building is left to contend. The wall times are PRINTED,
     * not asserted — a timing assertion would flake on a loaded CI box — and `build-times.md` records the measurement,
     * so the test only runs when asked (`FLOW_MEASURE_DERIVE_OVERLAP=1`) and costs the suite nothing.
     */
    @Test
    fun `two connections re-derive concurrently and the overlap is measured (opt-in)`() = runBlocking {
        if (System.getenv("FLOW_MEASURE_DERIVE_OVERLAP") == null) return@runBlocking
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

    /** ONE processed clone, derived once (its FIRST derive), that every re-derive test then re-derives under fresh job ids. */
    private object SharedClone {
        private val lock = Mutex()
        private var id: UInt? = null
        private val jobIds = AtomicInteger(1)

        fun nextJobId(): UInt = jobIds.incrementAndGet().toUInt()

        suspend fun connectionId(): UInt = lock.withLock {
            id ?: run {
                SyncedStubFixture.ensureMigrated()
                val connId = SyncedStubFixture.createConnection(namePrefix = "jira-analyze-shared", enabled = false)
                SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
                DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "analyze-shared-team")
                DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
                    DerivedStubFixture.derivePinned(connId, jobId = 1u)
                }
                connId.also { id = it }
            }
        }
    }

    private companion object {
        const val ANALYZE_POLLS = 40
        const val ANALYZE_POLL_MS = 500L
        const val WAITER_POLL_MS = 50L
        const val HOLD_WAIT_MS = 1_500L
        const val TIMEOUT_TEST_LOCK_TIMEOUT_MS = 2_000L
        const val LONG_LOCK_TIMEOUT_MS = 120_000L
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
