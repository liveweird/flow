package ch.nokillswit

import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.norm.purgeAll
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PURGE of the GitLab code layer (`GitLabConnector.purgeSteps`: the raw tables, then `norm.code_*`). Every case works on its OWN clone of
 * the fixture (`SyncedGitLabFixture.cloneProcessedData`) and proves the fixture itself is untouched.
 */
class GitLabPurgeTest {
    private val connections = mutableListOf<UInt>()
    private val fixtureId: UInt by lazy { runBlocking { SyncedGitLabFixture.connectionId() } }

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { GitLabFixtures.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private suspend fun clone(): UInt = GitLabFixtures.createConnection().also {
        connections += it
        SyncedGitLabFixture.cloneProcessedData(fixtureId, it)
    }

    private fun profileOf(connId: UInt): Pair<String?, Long?> = PostgresTestSupport.connect().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT profile::text, profile_at FROM public.source_connections WHERE id = $connId").use { rs ->
                rs.next()
                rs.getString(1) to rs.getLong(2).takeUnless { rs.wasNull() }
            }
        }
    }

    @Test
    fun `a PURGE job empties the raw and code tables of its connection and leaves the profile and other connections alone`() =
        runBlocking<Unit> {
            val own = clone()
            val before = GitLabCodeTables.counts(own)
            assertEquals(GitLabCodeTables.counts(fixtureId), before, "the clone starts as the fixture")
            assertTrue(before.values.all { it > 0 }, "every one of the twelve tables has rows to purge: $before")
            val profile = profileOf(own)
            assertTrue(profile.first != null && profile.second != null, "the clone carries the fixture's profile")

            GitLabFixtures.run(GitLabFixtures.connector(), own, SyncJobKind.PURGE)

            assertEquals(GitLabCodeTables.ALL.associateWith { 0L }, GitLabCodeTables.counts(own))
            assertEquals(profile, profileOf(own), "source_connections.profile is left alone (the Jira rule)")
            assertEquals(before, GitLabCodeTables.counts(fixtureId), "the fixture's rows are intact")
            SyncedGitLabFixture.assertUnchanged()
        }

    @Test
    fun `the code layer drains in bounded batches, keys and events first, and a neighbouring clone is left alone`() = runBlocking<Unit> {
        val own = clone()
        val other = clone()
        val code = GitLabFixtures.codeStore()
        val events = GitLabCodeTables.counts(own, listOf("norm.code_mr_events")).getValue("norm.code_mr_events")

        val keysTable = "norm.code_mr_issue_keys"
        val keys = GitLabCodeTables.counts(own, listOf(keysTable)).getValue(keysTable)
        assertTrue(keys > 3, "the fixture has issue keys to drain in more than one batch: $keys")
        var drainedKeys = 0L
        while (true) {
            val batch = code.purgeMrIssueKeysBatch(own, batchSize = 3)
            assertTrue(batch <= 3, "a key batch never exceeds its size: $batch")
            if (batch == 0) break
            drainedKeys += batch
        }
        assertEquals(keys, drainedKeys, "the batches add up to every issue key")
        assertEquals(keys, GitLabCodeTables.counts(other, listOf(keysTable)).getValue(keysTable), "the neighbour's keys stay")

        var drained = 0L
        while (true) {
            val batch = code.purgeMrEventsBatch(own, batchSize = 3)
            assertTrue(batch <= 3, "a batch never exceeds its size: $batch")
            if (batch == 0) break
            drained += batch
        }
        assertEquals(events, drained, "the batches add up to every event")
        assertEquals(events, GitLabCodeTables.counts(other, listOf("norm.code_mr_events")).getValue("norm.code_mr_events"))

        code.purgeAll(own, batchSize = 3)
        assertEquals(GitLabCodeTables.NORM.associateWith { 0L }, GitLabCodeTables.counts(own, GitLabCodeTables.NORM))
        assertEquals(GitLabCodeTables.counts(fixtureId, GitLabCodeTables.NORM), GitLabCodeTables.counts(other, GitLabCodeTables.NORM))
        val raw = GitLabCodeTables.RAW
        assertEquals(GitLabCodeTables.counts(fixtureId, raw), GitLabCodeTables.counts(own, raw), "the raw rows stay")
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmGitLabFixture()
    }
}
