package ch.nokillswit

import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.jira.purgeAll
import ch.nokillswit.norm.NORM_ANALYZE_LOCK_TIMEOUT_MS
import ch.nokillswit.norm.NORM_ANALYZE_STATEMENT_TIMEOUT_MS
import ch.nokillswit.norm.NORM_WORK_ITEM_ANALYZED_TABLES
import ch.nokillswit.norm.NormAnalyzeSettings
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.analyzeIfMaterial
import ch.nokillswit.norm.normStatisticsStale
import ch.nokillswit.norm.purgeAll
import ch.qos.logback.classic.Level
import kotlinx.coroutines.runBlocking
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
private val SYNC_TABLES = NORM_WORK_ITEM_ANALYZED_TABLES + "norm.work_item_status_intervals"

/**
 * The planner statistics of the normalized tables a Jira PROCESS has just filled (`norm/NormAnalyze.kt`, build-times WHY 60): the rule
 * on a real catalog (a scratch table), and the Jira PROCESS wiring on a clone of the shared synced fixture's RAW rows (PROCESS is the
 * subject, so running it is allowed; the shared connection is never touched). The GitLab twin is [NormAnalyzeGitLabTest].
 */
class NormAnalyzeTest {
    private val connections = mutableListOf<UInt>()
    private val progressKeys = listOf("normAnalyzed", "normAnalyzeMs")

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

    /** Lowers the share (see [NormAnalyzeSettings]) where the ANALYZE must be attempted whatever the fork's other classes left. */
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
    private fun settleStatistics() = NormAnalyzeSupport.execute("ANALYZE ${SYNC_TABLES.joinToString(", ")}")

    @Test
    fun `the rule - a never analyzed table or a tenth of the described rows is stale, nothing written never is`() {
        assertTrue(normStatisticsStale(rowsWritten = 1, reltuplesSum = 1_000_000, neverAnalyzed = true))
        assertTrue(normStatisticsStale(rowsWritten = 0, reltuplesSum = 1_000_000, neverAnalyzed = true), "never analyzed, even unwritten")
        assertFalse(normStatisticsStale(rowsWritten = 99, reltuplesSum = 1_000, neverAnalyzed = false))
        assertTrue(normStatisticsStale(rowsWritten = 100, reltuplesSum = 1_000, neverAnalyzed = false), "exactly a tenth")
        assertTrue(normStatisticsStale(rowsWritten = 5, reltuplesSum = 0, neverAnalyzed = false), "analyzed empty, now filled")
        assertFalse(normStatisticsStale(rowsWritten = 0, reltuplesSum = 0, neverAnalyzed = false), "analyzed empty, nothing written")
    }

    @Test
    fun `on a real catalog the ANALYZE runs for a never analyzed table and at a tenth of the rows, not below`() = runBlocking {
        val database = sharedDatabaseForTests()
        NormAnalyzeSupport.execute("DROP TABLE IF EXISTS $PROBE")
        NormAnalyzeSupport.execute("CREATE TABLE $PROBE (id INT)")
        val probe = listOf(PROBE)

        assertTrue(analyzeIfMaterial(database, probe, rowsWritten = 0).ran, "reltuples is -1 on a fresh table: never analyzed")
        assertFalse(analyzeIfMaterial(database, probe, rowsWritten = 0).ran, "analyzed (empty) and nothing written")
        assertTrue(analyzeIfMaterial(database, probe, rowsWritten = 5).ran, "analyzed empty, rows written since")

        NormAnalyzeSupport.execute("INSERT INTO $PROBE SELECT g FROM generate_series(1, 1000) g")
        assertTrue(analyzeIfMaterial(database, probe, rowsWritten = 1_000).ran)
        assertFalse(analyzeIfMaterial(database, probe, rowsWritten = 99).ran, "99 of 1,000 is under a tenth")
        val atTenth = analyzeIfMaterial(database, probe, rowsWritten = 100)
        assertTrue(atTenth.ran, "100 of 1,000 is a tenth")
        assertTrue(atTenth.millis >= 0)
    }

    @Test
    fun `the tables are fixed constants - a name that is not schema dot table is refused before any SQL`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { analyzeIfMaterial(sharedDatabaseForTests(), listOf("norm.x; DROP TABLE y"), 1) }
        assertFailsWith<IllegalArgumentException> { analyzeIfMaterial(sharedDatabaseForTests(), emptyList(), 1) }
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

        val progress = process(connId, storeWith(NormAnalyzeSettings(materialPercent = 1)))

        assertTrue(progress.getValue("issuesProcessed") > 100, "the clone's issues were processed: $progress")
        assertEquals(1L, progress["normAnalyzed"])
        assertTrue(progress.getValue("normAnalyzeMs") >= 0)
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
        settleStatistics()
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
        val shortWait = storeWith(NormAnalyzeSettings(lockMs = 300, statementMs = 10_000, materialPercent = 1))
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
