package ch.nokillswit

import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.norm.CodeActivityStore
import org.jetbrains.exposed.v1.core.Table
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.assertEquals

/**
 * A suite-wide, lazily-synced GitLab connection over the shared [GitLabStubServer] (`.claude/docs/testing.md` "Shared synced fixture",
 * the GitLab twin of [SyncedStubFixture]): ONE full GitLab SYNC of the stub per JVM fork - PROJECTS, USERS, COMMITS, MERGE REQUESTS, COMMIT
 * FILES and MR DETAILS - and every READ-ONLY test of the commit and merge-request streams reads THIS connection. A test that mutates state
 * (a second SYNC, the day-2 scenario, a head or budget change, PURGE) creates its OWN connection with [GitLabFixtures.createConnection] and
 * never touches this one.
 *
 * The SYNC ends with the code layer's PROCESS, so the connection also carries `norm.code_*` rows (and the GitLab section of the data
 * profile once PROFILE lands). A test that needs a MUTABLE copy clones: [cloneRawData] (the six raw tables) or [cloneProcessedData]
 * (plus the five `norm.code_*` tables and the profile columns), with `GitLabFixtures.createConnection` for the target.
 *
 * A class that reads it warms it in a `@BeforeClass` through [warmGitLabFixture] (`StubFixtureWarmupGuardTest` enforces it), so the
 * one-time Postgres + SYNC cost lands outside `testApplication`'s hard 60 s limit. It has its OWN warm-up call, not
 * [warmStubFixtures]: the Jira-fixture classes would otherwise all pay the GitLab SYNC too.
 */
object SyncedGitLabFixture {
    /** `sample-data/gitlab/expected.json` `commits.inScopeTotals.total`: the tripwire figure. */
    private const val EXPECTED_COMMITS = 300L

    /** `sample-data/gitlab/expected.json` `mergeRequests.total`: the second tripwire figure. */
    private const val EXPECTED_MERGE_REQUESTS = 60L

    private val initLock = Mutex()

    @Volatile
    private var syncedConnectionId: UInt? = null

    fun rawStore(): GitLabRawStore = GitLabFixtures.rawStore()

    /** The raw tables a clone copies, parents before children (the files reference the commits, the events the merge requests). */
    internal val RAW_CLONE_TABLES: List<Table> = listOf(
        GitLabRawStore.Projects, GitLabRawStore.Users, GitLabRawStore.Commits, GitLabRawStore.CommitFiles,
        GitLabRawStore.MergeRequests, GitLabRawStore.MrEvents,
    )

    internal val PROCESSED_CLONE_TABLES: List<Table> = listOf(
        CodeActivityStore.CodeProjects, CodeActivityStore.CodeIdentities, CodeActivityStore.CodeCommits,
        CodeActivityStore.CodeMergeRequests, CodeActivityStore.CodeMrEvents,
    )

    /** Copies the six `raw.gitlab_*` tables of [fromConnectionId] verbatim (flags and tombstones as they are) into [toConnectionId]. */
    fun cloneRawData(fromConnectionId: UInt, toConnectionId: UInt) {
        CloneSupport.cloneInTransaction(RAW_CLONE_TABLES, fromConnectionId, toConnectionId)
    }

    /**
     * [cloneRawData] plus the five `norm.code_*` tables, `source_connections.profile`/`profile_at` and the `code_process` cursor row (the
     * fingerprints the norm rows were computed under: without it the clone's first PROCESS would re-flag everything), in ONE transaction.
     */
    fun cloneProcessedData(fromConnectionId: UInt, toConnectionId: UInt) {
        CloneSupport.cloneInTransaction(RAW_CLONE_TABLES + PROCESSED_CLONE_TABLES, fromConnectionId, toConnectionId) { st ->
            val connections = DataSourceService.Connections.tableName
            val updated = st.executeUpdate(
                "UPDATE $connections SET profile = src.profile, profile_at = src.profile_at " +
                    "FROM $connections src WHERE src.id = $fromConnectionId AND $connections.id = $toConnectionId",
            )
            assertEquals(1, updated, "the clone's source_connections row must receive the source's profile")
            val cursor = st.executeUpdate(
                "INSERT INTO sync_cursors (connection_id, stream, cursor, watermark_at, last_completed_at, updated_at) " +
                    "SELECT $toConnectionId, stream, cursor, watermark_at, last_completed_at, updated_at FROM sync_cursors " +
                    "WHERE connection_id = $fromConnectionId AND stream = 'code_process'",
            )
            assertEquals(1, cursor, "the fixture's SYNC ended with PROCESS, which stores its fingerprints")
        }
    }

    /** Runs the SYNC exactly once per JVM fork and returns the connection id; every later call re-checks the tripwire figures. */
    suspend fun connectionId(): UInt {
        val connId = syncedConnectionId ?: initLock.withLock { syncedConnectionId ?: sync().also { syncedConnectionId = it } }
        assertUnchanged(connId)
        return connId
    }

    /** The tripwire: a reader that mutated the connection fails the next test that asks (counts only; a snapshot is milliseconds). */
    suspend fun assertUnchanged() {
        assertUnchanged(connectionId())
    }

    private suspend fun assertUnchanged(connId: UInt) {
        val store = rawStore()
        val live = store.commitRows(connId).count { it[GitLabRawStore.Commits.deletedAt] == null }.toLong()
        assertEquals(EXPECTED_COMMITS, live, "the GitLab fixture's commit count changed: a test mutated it")
        assertEquals(0L, store.countPendingCommitFiles(connId), "the GitLab fixture has pending commit files: a test mutated it")
        val mrs = store.mergeRequestRows(connId).count { it[GitLabRawStore.MergeRequests.deletedAt] == null }.toLong()
        assertEquals(EXPECTED_MERGE_REQUESTS, mrs, "the GitLab fixture's merge-request count changed: a test mutated it")
        assertEquals(0L, store.countStaleMergeRequests(connId), "the GitLab fixture has stale merge-request details: a test mutated it")
        val code = GitLabFixtures.codeStore()
        val normCommits = code.commitRows(connId).count { it.deletedAt == null }.toLong()
        assertEquals(EXPECTED_COMMITS, normCommits, "the fixture's norm commits changed")
        val normMrs = code.mergeRequestRows(connId).count { it.deletedAt == null }.toLong()
        assertEquals(EXPECTED_MERGE_REQUESTS, normMrs, "the fixture's norm merge requests changed")
        val flagged = GitLabFixtures.processStore().countNeedsProcessing(connId)
        assertEquals(0L, flagged, "the fixture has rows flagged for PROCESS: a test mutated it")
    }

    private suspend fun sync(): UInt {
        PostgresTestSupport.ensureMigrated()
        GitLabStubServer.resetScenarios() // a previous test may have left day2
        val connId = GitLabFixtures.createConnection(namePrefix = "gitlab-fixture")
        val startedAt = System.nanoTime()
        GitLabFixtures.run(GitLabFixtures.connector(), connId, SyncJobKind.SYNC, onDemand = true)
        // Fixture-cost marker (read by `scripts/timings/local-times.mjs`, like `SyncedStubFixture`'s):
        // `fixture-cost<TAB><name><TAB><millis>`.
        println("fixture-cost\tgitlab-sync\t${(System.nanoTime() - startedAt) / 1_000_000L}")
        assertUnchanged(connId)
        // Soft-deleted once synced: every ACTIVE GitLab source must read ONE instance (`ingest/GitLabScope.kt`), so a permanent source
        // on the stub would turn every other-instance create in `GitLabScopeTest` into a 409 for the rest of the fork. The raw rows stay
        // (nothing purges them in a test run) and every reader goes through the raw store by connection id, not the data source row.
        val dataSources = GitLabFixtures.dataSources()
        dataSources.delete(connId)
        // Stamped purged so the scheduler's purge scan (`dueForPurge`) never selects it (a PURGE would drain the readers' rows).
        dataSources.recordPurgeSucceeded(connId)
        return connId
    }
}
