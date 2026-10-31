package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.gitlab.CommitsCursor
import ch.nokillswit.gitlab.CommitsWalkCursor
import ch.nokillswit.gitlab.GITLAB_PAGE_SIZE
import ch.nokillswit.gitlab.GitLabCommitFilesStream
import ch.nokillswit.gitlab.GitLabCommitPage
import ch.nokillswit.gitlab.GitLabCommitSummary
import ch.nokillswit.gitlab.GitLabCommitsStream
import ch.nokillswit.gitlab.GitLabDiffPage
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.MAX_SWEEP_CONFIRMATIONS
import ch.nokillswit.gitlab.RawGitLabCommit
import ch.nokillswit.gitlab.RawGitLabCommitFile
import ch.nokillswit.gitlab.RawGitLabProject
import ch.nokillswit.gitlab.REF_NOT_FOUND
import ch.nokillswit.gitlab.WalkMode
import ch.nokillswit.gitlab.decodeCursor
import ch.nokillswit.gitlab.encodeCursor
import ch.nokillswit.gitlab.isoInstant
import ch.nokillswit.gitlab.purgeAll
import ch.nokillswit.ingest.LeaseLostException
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncCursorsService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROJECT = 1L
private const val BRANCH = "main"
private const val BACKFILL = 1_000L

/**
 * The COMMITS and COMMIT FILES streams against a scripted [FakeClient] (no HTTP): the walk modes and their decisions, the resumable
 * cursor, the re-list sweep with its `merge_base` confirmations and cap, and the files stream's cap, skip budget and call budget.
 */
class GitLabCommitsWalkTest {
    private val store: GitLabRawStore = GitLabFixtures.rawStore()
    private val cursors = SyncCursorsService(sharedDatabaseForTests())
    private val connections = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { store.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private fun sha(n: Int) = n.toString(16).padStart(40, '0')

    private fun summary(n: Int, parents: Int = 1, committedAt: Long = n * 10L) =
        GitLabCommitSummary(sha(n), "Commit $n", "Ada", "ada@x.test", committedAt - 1, committedAt, parents, n, 1)

    private fun raw(n: Int, committedAt: Long = n * 10L) =
        RawGitLabCommit(sha(n), "Commit $n", "Ada", "ada@x.test", committedAt - 1, committedAt, 1, n, 1)

    private fun commitPage(range: IntProgression, next: Int? = null) = GitLabCommitPage(range.map { summary(it) }, next, null)

    private fun fetchFailure(code: String) = FetchException(fakeSource, code, null, "/x")

    private suspend fun connection(branch: String? = BRANCH): UInt {
        val connId = GitLabFixtures.createConnection().also { connections += it }
        val payload = Json.parseToJsonElement("""{"id":$PROJECT,"path":"p","path_with_namespace":"platform/p","name":"p"}""")
        store.upsertProjects(connId, listOf(RawGitLabProject(PROJECT, "platform/p", "platform", 7, branch, false, payload)), now = 1)
        return connId
    }

    private fun stream(client: FakeClient) = GitLabCommitsStream(client, store, BACKFILL)

    private suspend fun run(client: FakeClient, connId: UInt, context: StreamContext = GitLabFixtures.freshContext(connId)) =
        GitLabFixtures.runStream(stream(client), connId, context)

    private suspend fun project(connId: UInt) = store.liveProjectsForCommits(connId).single()

    // --- the listing: modes, decisions, resume -------------------------------------------------------------------------------

    @Test
    fun `a project without a head is walked FULL from the backfill floor and its head is the first commit of page 1`() = runBlocking<Unit> {
        val connId = connection()
        val client = FakeClient().apply { listingOf = { _, _, _, _ -> commitPage(3 downTo 1) } }
        val progress = run(client, connId)
        assertEquals(listOf<Triple<String, String?, Int>>(Triple(BRANCH, isoInstant(BACKFILL), 1)), client.listed)
        assertEquals(3, store.commitRows(connId).size)
        assertEquals(sha(3), project(connId).headSha)
        assertEquals(3L, progress["commits"])
        assertNull(cursors.get(connId, "commits"), "the pass ended: no cursor")
    }

    @Test
    fun `an unchanged tip writes nothing and costs one page`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(3)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(3))
        val client = FakeClient().apply { listingOf = { _, _, _, _ -> commitPage(3 downTo 3) } }
        val progress = run(client, connId)
        assertEquals(1, client.listCalls)
        assertEquals(emptySet(), progress.keys.intersect(setOf("pages", "commits", "commitsRelisted")))
        assertEquals(1, store.commitRows(connId).size)
    }

    @Test
    fun `a head that is still an ancestor is walked as a RANGE`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(1)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(1))
        val client = FakeClient().apply {
            listingOf = { _, ref, _, _ -> if (ref == "${sha(1)}..$BRANCH") commitPage(3 downTo 2) else commitPage(3 downTo 3) }
            mergeBaseOf = { _, a, _ -> a }
        }
        val progress = run(client, connId)
        assertTrue(client.listed.any { it.first == "${sha(1)}..$BRANCH" && it.second == null }, "the range is the ref_name, no since")
        assertEquals(3, store.commitRows(connId).size)
        assertEquals(sha(3), project(connId).headSha)
        assertEquals(2L, progress["commits"])
        assertNull(progress["commitsRelisted"])
    }

    @Test
    fun `a merge base other than the head, or a head GitLab does not know, requests a RELIST`() = runBlocking<Unit> {
        for (unknown in listOf(true, false)) {
            val connId = connection()
            store.upsertCommits(connId, PROJECT, listOf(raw(1), raw(2)), now = 100)
            store.recordCommitsHead(connId, PROJECT, sha(2))
            val client = FakeClient().apply {
                listingOf = { _, _, _, _ -> commitPage(3 downTo 1) }
                mergeBaseOf = { _, _, _ -> if (unknown) throw fetchFailure(REF_NOT_FOUND) else sha(1) }
            }
            val progress = run(client, connId)
            assertEquals(1L, progress["commitsRelisted"], "unknown=$unknown")
            val relist = client.listed.last()
            assertEquals(isoInstant(raw(1).committedAt), relist.second, "a re-list starts at the oldest live commit, not the floor")
            assertEquals(sha(3), project(connId).headSha)
            assertFalse(project(connId).relistRequested, "the request is cleared by the sweep")
        }
    }

    @Test
    fun `a RANGE listing that answers NOT_FOUND turns into a RELIST in the same run`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(1)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(1))
        val client = FakeClient().apply {
            listingOf = { _, ref, _, _ -> if (ref.contains("..")) throw fetchFailure("NOT_FOUND") else commitPage(2 downTo 1) }
            mergeBaseOf = { _, a, _ -> a }
        }
        val progress = run(client, connId)
        assertEquals(1L, progress["commitsRelisted"])
        assertEquals(sha(2), project(connId).headSha)
        assertEquals(2, store.commitRows(connId).size)
    }

    @Test
    fun `a gone branch, a missing default branch and an empty branch are handled without a head`() = runBlocking<Unit> {
        val gone = connection()
        store.recordCommitsHead(gone, PROJECT, sha(1))
        val goneProgress = run(FakeClient().apply { listingOf = { _, _, _, _ -> throw fetchFailure("NOT_FOUND") } }, gone)
        assertEquals(1L, goneProgress["commitsProjectsSkipped"])

        val noBranch = connection(branch = null)
        val noBranchClient = FakeClient()
        assertEquals(1L, run(noBranchClient, noBranch)["commitsProjectsSkipped"])
        assertEquals(0, noBranchClient.listCalls, "no branch, no call")

        val empty = connection()
        val emptyProgress = run(FakeClient().apply { listingOf = { _, _, _, _ -> commitPage(IntRange.EMPTY) } }, empty)
        assertNull(project(empty).headSha, "an empty branch records no head")
        assertNull(emptyProgress["commitsProjectsSkipped"])
        assertTrue(store.commitRows(empty).isEmpty())
    }

    @Test
    fun `a lost lease after page 1 leaves the cursor at page 2 and the next run resumes there`() = runBlocking<Unit> {
        val connId = connection()
        val client = FakeClient().apply {
            listingOf = { _, _, _, page ->
                when (page) {
                    1 -> commitPage(250 downTo 151, next = 2)
                    2 -> commitPage(150 downTo 51, next = 3)
                    else -> commitPage(50 downTo 1)
                }
            }
        }
        var beats = 0
        val failing = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> ++beats > 1 })
        assertFailsWith<LeaseLostException> { run(client, connId, failing) }
        assertEquals(100, store.commitRows(connId).size, "page 1 was committed before the lease check")
        assertNull(project(connId).headSha, "the head moves only with the last page")
        val saved = decodeCursor<CommitsCursor>(assertNotNull(cursors.get(connId, "commits")).cursor)
        assertEquals(2, saved.walk?.page)
        assertEquals(sha(250), saved.walk?.tipSha)

        client.listed.clear()
        val progress = run(client, connId)
        assertEquals(listOf(2, 3), client.listed.map { it.third }, "the second run resumes at page 2, no CHECK, no page 1")
        assertEquals(250, store.commitRows(connId).size)
        assertEquals(sha(250), project(connId).headSha)
        assertEquals(2L, progress["pages"])
        assertNull(cursors.get(connId, "commits"))
    }

    @Test
    fun `a saved walk for another branch is dropped and the project is decided afresh`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(1)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(1))
        val stale = CommitsCursor(5, null, CommitsWalkCursor(PROJECT, WalkMode.RANGE, "${sha(1)}..old-branch", page = 2, tipSha = sha(9)))
        cursors.put(connId, "commits", encodeCursor(stale), null, null, 5)
        val client = FakeClient().apply {
            listingOf = { _, ref, _, _ -> if (ref.contains("..")) commitPage(3 downTo 2) else commitPage(3 downTo 3) }
            mergeBaseOf = { _, a, _ -> a }
        }
        run(client, connId)
        assertTrue(client.listed.none { it.first.contains("old-branch") })
        assertEquals(sha(3), project(connId).headSha)
        assertEquals(3, store.commitRows(connId).size)
    }

    // --- the re-list sweep ---------------------------------------------------------------------------------------------------

    /** A re-list over [stored] (all committed after `since`) whose listing shows only [listed]; [base] answers each merge_base. */
    private suspend fun relist(
        connId: UInt,
        listed: IntRange,
        base: (String) -> String,
        client: FakeClient = FakeClient(),
    ): Pair<FakeClient, Map<String, Long>> {
        client.listingOf = { _, _, _, _ -> commitPage(listed.reversed()) }
        client.mergeBaseOf = { _, candidate, _ -> base(candidate) }
        store.requestRelist(connId, PROJECT)
        return client to run(client, connId)
    }

    @Test
    fun `a commit the listing stopped short of is kept while it is an ancestor, and only one off the branch is tombstoned`() =
        runBlocking<Unit> {
            val connId = connection()
            // GitLab's `since` is git's --since: it stops once everything queued is older than the date, and a commit with a skewed
            // date hides its parents. Here the listing shows only commit 9; 5 and 6 are still ancestors, 7 left the branch (its merge
            // base is the fork point), 8's merge base is unknown to GitLab and 4's answer fails.
            store.upsertCommits(connId, PROJECT, (4..8).map { raw(it) }, now = 100)
            store.recordCommitsHead(connId, PROJECT, sha(8))
            val (_, progress) = relist(connId, 9..9, base = {
                when (it) {
                    sha(5), sha(6) -> it
                    sha(7) -> sha(1)
                    sha(8) -> throw fetchFailure(REF_NOT_FOUND)
                    else -> throw fetchFailure("INVALID_RESPONSE")
                }
            })
            val rows = store.commitRows(connId, PROJECT).associateBy { it[GitLabRawStore.Commits.sha] }
            assertNull(rows.getValue(sha(5))[GitLabRawStore.Commits.deletedAt], "an ancestor missed by the listing stays live")
            assertNull(rows.getValue(sha(6))[GitLabRawStore.Commits.deletedAt])
            assertNotNull(rows.getValue(sha(7))[GitLabRawStore.Commits.deletedAt], "off the branch")
            assertNotNull(rows.getValue(sha(8))[GitLabRawStore.Commits.deletedAt], "unknown to GitLab")
            assertNull(rows.getValue(sha(4))[GitLabRawStore.Commits.deletedAt], "an unconfirmed commit is never tombstoned")
            assertTrue(rows.getValue(sha(5))[GitLabRawStore.Commits.lastSeenAt] > 100, "a kept commit is touched")
            assertEquals(2L, progress["commitsTombstoned"])
            assertEquals(2L, progress["commitsSweepKept"])
            assertEquals(1L, progress["commitsSweepUnconfirmed"])
            assertTrue(project(connId).relistRequested, "an unconfirmed candidate leaves the request set for the next run")
            assertEquals(sha(9), project(connId).headSha)
        }

    @Test
    fun `at most the cap of candidates are confirmed per run and the next run finishes the rest`() = runBlocking<Unit> {
        val connId = connection()
        val total = MAX_SWEEP_CONFIRMATIONS + 1
        store.upsertCommits(connId, PROJECT, (1..total).map { raw(it) }, now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(total))
        val client = FakeClient()
        val (_, first) = relist(connId, (total + 1)..(total + 1), base = { sha(0) }, client = client)
        assertEquals(MAX_SWEEP_CONFIRMATIONS, client.mergeBaseCalls, "one call per confirmed candidate, no more than the cap")
        assertEquals(MAX_SWEEP_CONFIRMATIONS.toLong(), first["commitsTombstoned"])
        assertEquals(1L, first["commitsSweepDeferred"])
        assertTrue(project(connId).relistRequested)

        client.mergeBaseCalls = 0
        val second = run(client, connId)
        assertEquals(1, client.mergeBaseCalls)
        assertEquals(1L, second["commitsTombstoned"])
        assertFalse(project(connId).relistRequested, "everything confirmed: the request is cleared")
        val live = store.commitRows(connId, PROJECT).filter { it[GitLabRawStore.Commits.deletedAt] == null }
        assertEquals(listOf(sha(total + 1)), live.map { it[GitLabRawStore.Commits.sha] }, "only the listed commit is left")
    }

    // --- COMMIT FILES --------------------------------------------------------------------------------------------------------

    private suspend fun pendingProject(commits: Int): UInt {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, (1..commits).map { raw(it) }, now = 100)
        return connId
    }

    private fun files(count: Int, offset: Int = 0) =
        (1..count).map { RawGitLabCommitFile("f${offset + it}.go", null, 1, 0, false, false, false, false) }

    private fun file(path: String, additions: Int?, deletions: Int?, new: Boolean = false, deleted: Boolean = false) =
        RawGitLabCommitFile(path, null, additions, deletions, new, deleted, renamedFile = false, generatedFile = false)

    private suspend fun runFiles(client: FakeClient, connId: UInt, callsPerRun: Int = 100_000) =
        GitLabFixtures.runStream(GitLabCommitFilesStream(client, store, callsPerRun), connId)

    @Test
    fun `an 11th diff page makes the commit capped with exactly 1000 rows`() = runBlocking<Unit> {
        val connId = pendingProject(1)
        val client = FakeClient().apply {
            diffOf = { _, _, page ->
                GitLabDiffPage(files(GITLAB_PAGE_SIZE, (page - 1) * GITLAB_PAGE_SIZE), if (page < 12) page + 1 else null)
            }
        }
        val progress = runFiles(client, connId)
        assertEquals(10, client.diffCalls, "ten pages are read, the 11th is only known to exist")
        assertEquals(1_000, store.commitFileRows(connId, PROJECT, sha(1)).size)
        val row = store.commitRows(connId).single()
        assertTrue(row[GitLabRawStore.Commits.filesCapped])
        assertEquals(1_000, row[GitLabRawStore.Commits.filesChanged])
        assertEquals(1L, progress["commitFilesCapped"])
        assertNull(progress["commitFilesPending"])
    }

    @Test
    fun `a path GitLab lists twice is one row with the lines summed`() = runBlocking<Unit> {
        val connId = pendingProject(1)
        val client = FakeClient().apply {
            diffOf = { _, _, _ ->
                GitLabDiffPage(
                    listOf(
                        file("a.go", 0, 7, deleted = true),
                        file("a.go", 5, 0, new = true),
                        file("b.go", null, null, new = true),
                    ),
                    null,
                )
            }
        }
        runFiles(client, connId)
        val rows = store.commitFileRows(connId, PROJECT, sha(1)).associateBy { it[GitLabRawStore.CommitFiles.path] }
        val merged = rows.getValue("a.go")
        assertEquals(5, merged[GitLabRawStore.CommitFiles.additions])
        assertEquals(7, merged[GitLabRawStore.CommitFiles.deletions])
        assertFalse(merged[GitLabRawStore.CommitFiles.newFile], "delete + add at one path is a modified file")
        assertFalse(merged[GitLabRawStore.CommitFiles.deletedFile])
        assertNull(rows.getValue("b.go")[GitLabRawStore.CommitFiles.additions])
        assertEquals(2, store.commitRows(connId).single()[GitLabRawStore.Commits.filesChanged], "files_changed counts paths")
    }

    @Test
    fun `the call budget stops the run between commits and leaves the rest pending`() = runBlocking<Unit> {
        val connId = pendingProject(5)
        val client = FakeClient().apply { diffOf = { _, _, _ -> GitLabDiffPage(files(2), null) } }
        val progress = runFiles(client, connId, callsPerRun = 3)
        assertEquals(3, client.diffCalls)
        assertEquals(3L, progress["commitFiles"])
        assertEquals(2L, progress["commitFilesPending"])
        assertEquals(2L, store.countPendingCommitFiles(connId))
        assertEquals(listOf(sha(5), sha(4), sha(3)), store.commitRows(connId).filter { it[GitLabRawStore.Commits.filesFetchedAt] != null }
            .map { it[GitLabRawStore.Commits.sha] }.sorted().reversed(), "newest first")
        val rest = runFiles(client, connId)
        assertNull(rest["commitFilesPending"])
        assertEquals(0L, store.countPendingCommitFiles(connId))
    }

    @Test
    fun `more oversized commits than the skip budget allows fail the run, keep the complete ones and stamp no oversized commit`() =
        runBlocking<Unit> {
            val connId = pendingProject(14)
            // The newest 11 are oversized (a cap set too small), the 3 oldest are fine; the budget trips on the 11th.
            val client = FakeClient().apply {
                diffOf = { _, sha, _ -> if (sha >= sha(4)) throw fetchFailure("LIMIT_EXCEEDED") else GitLabDiffPage(files(1), null) }
            }
            val failure = assertFailsWith<FetchException> { runFiles(client, connId) }
            assertEquals("LIMIT_EXCEEDED", failure.code)
            val rows = store.commitRows(connId).associateBy { it[GitLabRawStore.Commits.sha] }
            assertTrue(rows.values.none { it[GitLabRawStore.Commits.filesError] != null }, "a mis-set cap strands no commit")
            assertEquals(14L, store.countPendingCommitFiles(connId))
        }

    @Test
    fun `isolated oversized and missing commits are stamped and the run succeeds`() = runBlocking<Unit> {
        val connId = pendingProject(4)
        val client = FakeClient().apply {
            diffOf = { _, sha, _ ->
                when (sha) {
                    sha(4) -> throw fetchFailure("LIMIT_EXCEEDED")
                    sha(3) -> throw fetchFailure("NOT_FOUND")
                    else -> GitLabDiffPage(files(2), null)
                }
            }
        }
        val progress = runFiles(client, connId)
        val rows = store.commitRows(connId).associateBy { it[GitLabRawStore.Commits.sha] }
        assertEquals("LIMIT_EXCEEDED", rows.getValue(sha(4))[GitLabRawStore.Commits.filesError])
        assertEquals("NOT_FOUND", rows.getValue(sha(3))[GitLabRawStore.Commits.filesError])
        assertNull(rows.getValue(sha(4))[GitLabRawStore.Commits.filesChanged])
        assertEquals(2, rows.getValue(sha(2))[GitLabRawStore.Commits.filesChanged])
        assertEquals(1L, progress["commitFilesOversized"])
        assertEquals(1L, progress["commitFilesMissing"])
        assertEquals(2L, progress["commitFiles"])
        assertEquals(0L, store.countPendingCommitFiles(connId))
    }

    @Test
    fun `a failure other than a terminal one ends the run after the complete commits are flushed`() = runBlocking<Unit> {
        val connId = pendingProject(3)
        val client = FakeClient().apply {
            diffOf = { _, sha, _ -> if (sha == sha(2)) throw fetchFailure("INVALID_RESPONSE") else GitLabDiffPage(files(1), null) }
        }
        assertEquals("INVALID_RESPONSE", assertFailsWith<FetchException> { runFiles(client, connId) }.code)
        val stamped = store.commitRows(connId).filter { it[GitLabRawStore.Commits.filesFetchedAt] != null }
            .map { it[GitLabRawStore.Commits.sha] }
        assertEquals(listOf(sha(3)), stamped, "the commit fetched before the failure is stored; the failing one and the rest stay pending")
        assertEquals(2L, store.countPendingCommitFiles(connId))
    }
}
