package ch.nokillswit

import ch.nokillswit.gitlab.GitLabProcessStream
import ch.nokillswit.infra.validation.PathGlob
import ch.nokillswit.ingest.DEFAULT_PATH_EXCLUSIONS
import ch.nokillswit.norm.CodeActivityStore
import ch.nokillswit.norm.NORM_CODE_ANALYZED_TABLES
import ch.nokillswit.norm.NormAnalyzeSettings
import ch.qos.logback.classic.Level
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val LOGGER = "ch.nokillswit.norm.NormAnalyze"
private val CODE_TABLES = NORM_CODE_ANALYZED_TABLES + listOf("norm.code_projects", "norm.code_identities")

/**
 * The GitLab PROCESS wiring of the post-PROCESS `ANALYZE` (`norm/NormAnalyze.kt`, build-times WHY 60): on a clone of the shared synced
 * GitLab fixture's RAW rows (PROCESS is the subject, so running it is allowed; the shared connection is never touched). The rule itself
 * and the Jira wiring are [NormAnalyzeTest].
 */
class NormAnalyzeGitLabTest {
    private val processStore = GitLabFixtures.processStore()
    private val connections = mutableListOf<UInt>()
    private val globs = DEFAULT_PATH_EXCLUSIONS.map(PathGlob::compile)

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { GitLabFixtures.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
        connections.clear()
    }

    /** A clone of the fixture's RAW tables, every row flagged for PROCESS (a fresh sync's state, the norm layer empty). */
    private suspend fun rawClone(): UInt = GitLabFixtures.createConnection().also {
        connections += it
        SyncedGitLabFixture.cloneRawData(SyncedGitLabFixture.connectionId(), it)
        processStore.markAllNeedsProcessing(it, commitsOnly = false)
    }

    /** Lowers the share (see [NormAnalyzeSettings]) where the ANALYZE must be attempted whatever the fork's other classes left. */
    private fun storeWith(settings: NormAnalyzeSettings) = CodeActivityStore(sharedDatabaseForTests(), settings)

    private suspend fun process(connId: UInt, code: CodeActivityStore = GitLabFixtures.codeStore()): Map<String, Long> =
        GitLabFixtures.runStream(GitLabProcessStream(processStore, code, globs), connId)

    /** The brought-up-to-date statistics a quiet autovacuum would leave. */
    private fun settleStatistics() = NormAnalyzeSupport.execute("ANALYZE ${CODE_TABLES.joinToString(", ")}")

    @Test
    fun `a GitLab PROCESS that writes the connection ANALYZEs the three code tables once and records it in the progress`() = runBlocking {
        val connId = rawClone()
        settleStatistics()
        val before = NormAnalyzeSupport.analyzeCounts(CODE_TABLES)

        val progress = process(connId, storeWith(NormAnalyzeSettings(materialPercent = 1)))

        assertTrue(progress.getValue("commitsProcessed") > 100, "the clone's commits were processed: $progress")
        assertEquals(1L, progress["normAnalyzed"])
        assertTrue(progress.getValue("normAnalyzeMs") >= 0)
        val after = NormAnalyzeSupport.analyzeCounts(CODE_TABLES)
        NORM_CODE_ANALYZED_TABLES.forEach { table -> assertEquals(before.getValue(table) + 1, after[table], "$table analyzed once") }
        listOf("norm.code_projects", "norm.code_identities").forEach { table ->
            assertEquals(before[table], after[table], "$table is small and rebuilt wholesale: left to autovacuum")
        }
    }

    @Test
    fun `a GitLab PROCESS that writes a few rows or none does not ANALYZE`() = runBlocking {
        val connId = rawClone()
        process(connId)
        settleStatistics()
        val before = NormAnalyzeSupport.analyzeCounts(CODE_TABLES)

        NormAnalyzeSupport.execute("UPDATE raw.gitlab_commits SET needs_processing = false WHERE connection_id = $connId")
        NormAnalyzeSupport.execute("UPDATE raw.gitlab_merge_requests SET needs_processing = false WHERE connection_id = $connId")
        NormAnalyzeSupport.execute(
            "UPDATE raw.gitlab_commits SET needs_processing = true WHERE ctid IN " +
                "(SELECT ctid FROM raw.gitlab_commits WHERE connection_id = $connId ORDER BY project_id, sha LIMIT 3)",
        )
        val few = process(connId)
        val none = process(connId)

        assertEquals(3L, few["commitsProcessed"])
        assertNull(none["commitsProcessed"], "nothing was stale the second time")
        listOf("normAnalyzed", "normAnalyzeMs").forEach { key ->
            assertNull(few[key], "a few rows: no $key")
            assertNull(none[key], "no rows: no $key")
        }
        assertEquals(before, NormAnalyzeSupport.analyzeCounts(CODE_TABLES))
    }

    @Test
    fun `a lock another session holds makes the ANALYZE one WARN and the GitLab PROCESS still succeeds`() = runBlocking {
        val connId = rawClone()
        settleStatistics()
        val before = NormAnalyzeSupport.analyzeCounts(CODE_TABLES)
        val shortWait = storeWith(NormAnalyzeSettings(lockMs = 300, statementMs = 10_000, materialPercent = 1))
        val logs = LogCapture(LOGGER)
        try {
            val progress = NormAnalyzeSupport.holdingAnalyzeLock("norm.code_mr_events") { process(connId, shortWait) }

            assertTrue(progress.getValue("commitsProcessed") > 100, "the PROCESS itself finished: $progress")
            assertNull(progress["commitPagesFailed"])
            listOf("normAnalyzed", "normAnalyzeMs").forEach { assertNull(progress[it], "the ANALYZE did not run: no $it") }
            val warn = logs.events.single { it.level == Level.WARN }
            assertTrue(NORM_CODE_ANALYZED_TABLES.all { it in warn.formattedMessage }, "the table names: ${warn.formattedMessage}")
            assertNull(warn.throwableProxy, "the class name in the text, no stack and no driver message")
            // The counter is not transactional: a table the statement reached before the blocked one counts, whatever the rollback did.
            assertEquals(
                before["norm.code_mr_events"], NormAnalyzeSupport.analyzeCounts(CODE_TABLES)["norm.code_mr_events"],
                "the table another session locked was never analyzed",
            )
            assertTrue(GitLabFixtures.codeStore().commitFigures(connId).count > 100, "the norm rows are committed")
        } finally {
            logs.detach()
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmGitLabFixture()
    }
}
