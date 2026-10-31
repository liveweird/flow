package ch.nokillswit

import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.jira.purgeAll
import ch.nokillswit.norm.NORM_ANALYZE_LOCK_TIMEOUT_MS
import ch.nokillswit.norm.NORM_ANALYZE_REASON_ACCUMULATED
import ch.nokillswit.norm.NORM_ANALYZE_REASON_CONNECTION_ABSENT
import ch.nokillswit.norm.NORM_ANALYZE_REASON_NEVER_ANALYZED
import ch.nokillswit.norm.NORM_ANALYZE_STATEMENT_TIMEOUT_MS
import ch.nokillswit.norm.NORM_WORK_ITEM_ANALYZED_TABLES
import ch.nokillswit.norm.NormAnalyzeSettings
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.analyzeIfMaterial
import ch.nokillswit.norm.normAnalyzeReason
import ch.nokillswit.norm.purgeAll
import ch.qos.logback.classic.Level
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOGGER = "ch.nokillswit.norm.NormAnalyze"
private const val PROBE = "public.norm_analyze_probe"
private const val MILLIS_STATEMENT_BUDGET = 1_000L
private val SYNC_TABLES = NORM_WORK_ITEM_ANALYZED_TABLES + "norm.work_item_status_intervals"

/**
 * The planner statistics of the normalized tables a Jira PROCESS has just filled (`norm/NormAnalyze.kt`, build-times WHY 60): the rule
 * on a real catalog (a scratch table with a `connection_id` column, so `pg_stats` and `n_mod_since_analyze` are exercised for chosen
 * connection ids without depending on the fork's other classes), and the Jira PROCESS wiring on a clone of the shared synced
 * fixture's RAW rows (PROCESS is the subject, so running it is allowed; the shared connection is never touched). The GitLab twin is
 * [NormAnalyzeGitLabTest].
 */
class NormAnalyzeTest {
    private val connections = mutableListOf<UInt>()
    private val progressKeys = listOf("normAnalyzed", "normAnalyzeMs", "normAnalyzeReason")

    @AfterTest
    fun cleanUp() = runBlocking {
        NormAnalyzeSupport.execute("DROP TABLE IF EXISTS $PROBE")
        connections.forEach { connId ->
            SyncedStubFixture.workItems().purgeAll(connId)
            SyncedStubFixture.rawStore().purgeAll(connId)
            SyncedStubFixture.dataSources().delete(connId)
        }
        connections.clear()
    }

    /** A clone of the fixture's raw rows with every issue flagged for PROCESS (a fresh sync's state, the norm layer empty). */
    private suspend fun rawClone(): UInt {
        val clone = SyncedStubFixture.createConnection(namePrefix = "norm-analyze", enabled = false).also { connections += it }
        SyncedStubFixture.cloneRawData(SyncedStubFixture.connectionId(), clone)
        SyncedStubFixture.rawStore().markAllNeedsProcessing(clone)
        return clone
    }

    /** Shortens the waits (see [NormAnalyzeSettings]) where the test hits a lock timeout. */
    private fun storeWith(settings: NormAnalyzeSettings) = WorkItemStore(sharedDatabaseForTests(), settings)

    private suspend fun process(connId: UInt, workItems: WorkItemStore = SyncedStubFixture.workItems()): Map<String, Long> {
        val context = SyncedStubFixture.freshContext(connId)
        context.currentStreamName = "process"
        JiraProcessStream(SyncedStubFixture.rawStore(), workItems).run(context)
        return context.progressSnapshot()
    }

    /** Leaves only [issueCount] issues of the connection flagged for PROCESS. */
    private fun flagOnly(connId: UInt, issueCount: Int) {
        NormAnalyzeSupport.execute("UPDATE raw.jira_issues SET needs_processing = false WHERE connection_id = $connId")
        NormAnalyzeSupport.execute(
            "UPDATE raw.jira_issues SET needs_processing = true WHERE connection_id = $connId AND issue_id IN " +
                "(SELECT issue_id FROM raw.jira_issues WHERE connection_id = $connId ORDER BY issue_id LIMIT $issueCount)",
        )
    }

    /** The statistics as the catalog holds them now: every norm table brought up to its true size, as an autovacuum pass would. */
    private suspend fun settleStatistics(quiet: Boolean = false) = NormAnalyzeSupport.settle(SYNC_TABLES, quiet)

    /** A probe table (a `connection_id` column, like the norm tables) holding [rows] rows per connection id, analyzed when [analyzed]. */
    private fun seedProbe(vararg rows: Pair<Int, Int>, analyzed: Boolean = true) {
        NormAnalyzeSupport.execute("DROP TABLE IF EXISTS $PROBE")
        NormAnalyzeSupport.execute("CREATE TABLE $PROBE (connection_id INT)")
        rows.forEach { (connId, count) -> addProbeRows(connId, count) }
        if (analyzed) NormAnalyzeSupport.execute("ANALYZE $PROBE")
    }

    private fun addProbeRows(connId: Int, count: Int) =
        NormAnalyzeSupport.execute("INSERT INTO $PROBE SELECT $connId FROM generate_series(1, $count)")

    private suspend fun analyzeProbe(connId: UInt, rowsWritten: Long, settings: NormAnalyzeSettings = NormAnalyzeSettings()) =
        analyzeIfMaterial(sharedDatabaseForTests(), listOf(PROBE), connId, rowsWritten, settings)

    @Test
    fun `the rule - nothing written never, then never analyzed, accumulated changes, an absent connection, in that order`() {
        fun reason(never: Boolean = false, mods: Long = 0, reltuples: Long = 1_000, inMcv: Boolean = true, rows: Long = 5) =
            normAnalyzeReason(never, mods, reltuples, inMcv, rows)

        assertEquals(0L, reason(never = true, mods = 1_000, inMcv = false, rows = 0), "nothing written changes nothing")
        assertEquals(NORM_ANALYZE_REASON_NEVER_ANALYZED, reason(never = true, mods = 1_000, inMcv = false))
        assertEquals(0L, reason(mods = 99), "99 of 1,000 is under a tenth")
        assertEquals(NORM_ANALYZE_REASON_ACCUMULATED, reason(mods = 100, inMcv = false), "exactly a tenth, and it wins over the connection")
        assertEquals(NORM_ANALYZE_REASON_ACCUMULATED, reason(mods = 20, reltuples = 100), "a small table")
        assertEquals(0L, reason(mods = 0, reltuples = 0), "analyzed empty, nothing modified, the connection known")
        assertEquals(NORM_ANALYZE_REASON_CONNECTION_ABSENT, reason(inMcv = false), "a small run of a connection the statistics do not know")
        assertEquals(NORM_ANALYZE_REASON_CONNECTION_ABSENT, reason(mods = 0, reltuples = 0, inMcv = false), "analyzed empty, now filled")
        assertEquals(0L, normAnalyzeReason(false, 4, 100, true, 5, materialPercent = 5), "4 of 100 is under 5 percent")
        assertEquals(NORM_ANALYZE_REASON_ACCUMULATED, normAnalyzeReason(false, 5, 100, true, 5, materialPercent = 5))
    }

    @Test
    fun `a never analyzed table ANALYZEs once rows were written, and not before`() = runBlocking {
        seedProbe(1 to 10, analyzed = false)

        val unwritten = analyzeProbe(1u, rowsWritten = 0)
        assertFalse(unwritten.ran, "reltuples is -1 on a fresh table, but the run wrote nothing")
        assertEquals(0L, unwritten.reason)
        val written = analyzeProbe(1u, rowsWritten = 5)
        assertTrue(written.ran)
        assertEquals(NORM_ANALYZE_REASON_NEVER_ANALYZED, written.reason)
        assertTrue(written.millis >= 0)
    }

    @Test
    fun `a second connection under a tenth ANALYZEs because the statistics do not know it, once`() = runBlocking {
        seedProbe(1 to 1_000)
        addProbeRows(2, 50)

        val first = analyzeProbe(2u, rowsWritten = 50)
        assertTrue(first.ran, "5 percent of the rows, below the accumulated line, but connection 2 was never sampled")
        assertEquals(NORM_ANALYZE_REASON_CONNECTION_ABSENT, first.reason)

        val again = analyzeProbe(2u, rowsWritten = 5)
        assertFalse(again.ran, "the ANALYZE took connection 2 into the most common values")
        assertEquals(0L, again.reason)
    }

    @Test
    fun `a connection already in the most common values writing a small share does not ANALYZE`() = runBlocking {
        seedProbe(1 to 1_000, 2 to 50)

        assertFalse(analyzeProbe(2u, rowsWritten = 5).ran)
        assertFalse(analyzeProbe(1u, rowsWritten = 99).ran)
        assertFalse(analyzeProbe(1u, rowsWritten = 0).ran)
    }

    @Test
    fun `changes accumulated over two small runs cross the tenth that neither run reaches alone`() = runBlocking {
        seedProbe(1 to 1_000)
        addProbeRows(1, 60)
        assertFalse(analyzeProbe(1u, rowsWritten = 60).ran, "60 of 1,000 is under a tenth")

        addProbeRows(1, 60)
        val second = analyzeProbe(1u, rowsWritten = 60)
        assertTrue(second.ran, "120 modifications since the last ANALYZE; the second run alone wrote only 60")
        assertEquals(NORM_ANALYZE_REASON_ACCUMULATED, second.reason)

        assertFalse(analyzeProbe(1u, rowsWritten = 60).ran, "the ANALYZE reset the count")
    }

    @Test
    fun `the helper refuses to run inside an enclosing transaction`() = runBlocking<Unit> {
        seedProbe(1 to 10)
        val database = sharedDatabaseForTests()

        assertFailsWith<IllegalStateException> {
            suspendTransaction(database) { analyzeIfMaterial(database, listOf(PROBE), 1u, 5) }
        }
        assertFalse(analyzeProbe(1u, rowsWritten = 5).ran, "outside a transaction it runs; connection 1 is known and nothing changed")
    }

    @Test
    fun `a lock held past the statement budget is one WARN carrying the statement timeout SQLSTATE`() = runBlocking {
        seedProbe(1 to 1_000)
        addProbeRows(2, 50)
        val budget = NormAnalyzeSettings(lockMs = 60_000, statementMs = MILLIS_STATEMENT_BUDGET)
        val logs = LogCapture(LOGGER)
        try {
            val result = NormAnalyzeSupport.holdingAnalyzeLock(PROBE) { analyzeProbe(2u, rowsWritten = 50, settings = budget) }

            assertFalse(result.ran)
            assertEquals(0L, result.reason, "a failed ANALYZE records no reason")
            val warn = logs.events.single { it.level == Level.WARN }
            assertTrue("SQLSTATE 57014" in warn.formattedMessage, warn.formattedMessage)
            assertNull(warn.throwableProxy)
        } finally {
            logs.detach()
        }
    }

    @Test
    fun `the settings refuse a non-positive wait or a share outside 1 to 100`() {
        assertFailsWith<IllegalArgumentException> { NormAnalyzeSettings(lockMs = 0) }
        assertFailsWith<IllegalArgumentException> { NormAnalyzeSettings(statementMs = -1) }
        assertFailsWith<IllegalArgumentException> { NormAnalyzeSettings(materialPercent = 0) }
        assertFailsWith<IllegalArgumentException> { NormAnalyzeSettings(materialPercent = 101) }
        assertEquals(100L, NormAnalyzeSettings(materialPercent = 100).materialPercent)
    }

    @Test
    fun `the tables are fixed constants - a name that is not schema dot table is refused before any SQL`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { analyzeIfMaterial(sharedDatabaseForTests(), listOf("norm.x; DROP TABLE y"), 1u, 1) }
        assertFailsWith<IllegalArgumentException> { analyzeIfMaterial(sharedDatabaseForTests(), emptyList(), 1u, 1) }
    }

    @Test
    fun `the lock wait is bounded like the DERIVE post-commit ANALYZE`() {
        assertEquals(5_000L, NORM_ANALYZE_LOCK_TIMEOUT_MS)
        assertEquals(30_000L, NORM_ANALYZE_STATEMENT_TIMEOUT_MS)
        assertEquals(NormAnalyzeSettings(5_000L, 30_000L, 10L), NormAnalyzeSettings())
    }

    @Test
    fun `a Jira PROCESS that rewrites the connection ANALYZEs the work item tables once and records it in the progress`() = runBlocking {
        val connId = rawClone()
        settleStatistics()
        val before = NormAnalyzeSupport.analyzeCounts(SYNC_TABLES)

        val progress = process(connId)

        assertTrue(progress.getValue("issuesProcessed") > 100, "the clone's issues were processed: $progress")
        assertEquals(1L, progress["normAnalyzed"])
        assertTrue(progress.getValue("normAnalyzeMs") >= 0)
        assertTrue(
            progress["normAnalyzeReason"] in setOf(NORM_ANALYZE_REASON_ACCUMULATED, NORM_ANALYZE_REASON_CONNECTION_ABSENT),
            "settled, then the clone's rows arrived: $progress",
        )
        val after = NormAnalyzeSupport.analyzeCounts(SYNC_TABLES)
        NORM_WORK_ITEM_ANALYZED_TABLES.forEach { table -> assertEquals(before.getValue(table) + 1, after[table], "$table analyzed once") }
        assertEquals(
            before["norm.work_item_status_intervals"], after["norm.work_item_status_intervals"],
            "the tables DERIVE's statement does not join are left alone",
        )
    }

    @Test
    fun `a Jira PROCESS that writes a few rows or none does not ANALYZE`() = runBlocking {
        val connId = rawClone()
        process(connId)
        settleStatistics(quiet = true)
        val before = NormAnalyzeSupport.analyzeCounts(SYNC_TABLES)

        flagOnly(connId, issueCount = 3)
        val few = process(connId)
        val none = process(connId)

        assertEquals(3L, few["issuesProcessed"])
        assertNull(none["issuesProcessed"], "nothing was stale the second time")
        progressKeys.forEach { key ->
            assertNull(few[key], "a few rows: no $key")
            assertNull(none[key], "no rows: no $key")
        }
        assertEquals(before, NormAnalyzeSupport.analyzeCounts(SYNC_TABLES))
    }

    @Test
    fun `a lock another session holds makes the ANALYZE one WARN and the PROCESS still succeeds`() = runBlocking {
        val connId = rawClone()
        settleStatistics()
        val before = NormAnalyzeSupport.analyzeCounts(SYNC_TABLES)
        val shortWait = storeWith(NormAnalyzeSettings(lockMs = 300, statementMs = 10_000))
        val logs = LogCapture(LOGGER)
        try {
            val progress = NormAnalyzeSupport.holdingAnalyzeLock("norm.work_item_worklogs") { process(connId, shortWait) }

            assertTrue(progress.getValue("issuesProcessed") > 100, "the PROCESS itself finished: $progress")
            assertNull(progress["issuesFailed"])
            progressKeys.forEach { assertNull(progress[it], "the ANALYZE did not run: no $it") }
            val warn = logs.events.single { it.level == Level.WARN }
            assertTrue(NORM_WORK_ITEM_ANALYZED_TABLES.all { it in warn.formattedMessage }, "the table names: ${warn.formattedMessage}")
            assertNull(warn.throwableProxy, "the class name in the text, no stack and no driver message")
            assertTrue("Exception" in warn.formattedMessage || "Error" in warn.formattedMessage, warn.formattedMessage)
            assertTrue("SQLSTATE 55P03" in warn.formattedMessage, "the lock timeout's SQLSTATE: ${warn.formattedMessage}")
            // The counter is not transactional: a table the statement reached before the blocked one counts, whatever the rollback did.
            assertEquals(
                before["norm.work_item_worklogs"], NormAnalyzeSupport.analyzeCounts(SYNC_TABLES)["norm.work_item_worklogs"],
                "the table another session locked was never analyzed",
            )
            assertTrue(SyncedStubFixture.workItems().profileWorkItems(connId).size > 100, "the norm rows are committed")
        } finally {
            logs.detach()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
