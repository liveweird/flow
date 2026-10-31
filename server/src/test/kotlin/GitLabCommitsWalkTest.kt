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
import ch.nokillswit.infra.time.MILLIS_PER_DAY
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
private const val BACKFILL = 1L

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

    private suspend fun run(
        client: FakeClient,
        connId: UInt,
        context: StreamContext = GitLabFixtures.freshContext(connId),
        floor: Long = BACKFILL,
    ) = GitLabFixtures.runStream(GitLabCommitsStream(client, store, floor), connId, context)

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
        val tip = sha(listed.last)
        val head = store.liveProjectsForCommits(connId).single().headSha
        client.listingOf = { _, _, _, _ -> commitPage(listed.reversed()) }
        // The CHECK finds the stored head gone (a force-push) and starts the re-list, whose epoch is the run's clock: the stored commits
        // (last seen at 100) were not seen since. The probe asks whether the listing's tip is still on the branch; the candidates are
        // asked against that tip.
        client.mergeBaseOf = { _, a, b ->
            when {
                a == tip -> a
                a == head && b == BRANCH -> throw fetchFailure(REF_NOT_FOUND)
                else -> base(a)
            }
        }
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
            assertTrue(rows.getValue(sha(4))[GitLabRawStore.Commits.lastSeenAt] > 100, "so is an unconfirmed one")
            assertEquals(2L, progress["commitsTombstoned"])
            assertEquals(2L, progress["commitsSweepKept"])
            assertEquals(1L, progress["commitsSweepUnconfirmed"])
            assertFalse(project(connId).relistRequested, "a persistently failing candidate does not pin the project in RELIST")
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
        assertEquals(MAX_SWEEP_CONFIRMATIONS + 2, client.mergeBaseCalls, "the head check, the tip probe, then the candidates up to the cap")
        assertEquals(MAX_SWEEP_CONFIRMATIONS.toLong(), first["commitsTombstoned"])
        assertEquals(1L, first["commitsSweepDeferred"])
        assertTrue(project(connId).relistRequested)

        client.mergeBaseCalls = 0
        val second = run(client, connId)
        assertEquals(2, client.mergeBaseCalls, "the probe and the one candidate left")
        assertEquals(1L, second["commitsTombstoned"])
        assertFalse(project(connId).relistRequested, "everything confirmed: the request is cleared")
        val live = store.commitRows(connId, PROJECT).filter { it[GitLabRawStore.Commits.deletedAt] == null }
        assertEquals(listOf(sha(total + 1)), live.map { it[GitLabRawStore.Commits.sha] }, "only the listed commit is left")
    }

    @Test
    fun `candidates are confirmed against the listing's tip, after one probe that the tip is still on the branch`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, (4..5).map { raw(it) }, now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(5))
        val (client, _) = relist(connId, 9..9, base = { it })
        assertTrue(sha(9) to BRANCH in client.mergeBases, "the probe: the tip against the branch")
        val against = client.mergeBases.filter { it.first == sha(4) || it.first == sha(5) }.map { it.second }.filter { it != BRANCH }
        assertEquals(listOf(sha(9), sha(9)), against, "each candidate against the tip, not the moving branch")
    }

    @Test
    fun `a branch renamed or a project deleted between the listing and the sweep tombstones nothing`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, (4..6).map { raw(it) }, now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(6))
        val client = FakeClient().apply {
            listingOf = { _, _, _, _ -> commitPage(9 downTo 9) }
            mergeBaseOf = { _, _, _ -> throw fetchFailure(REF_NOT_FOUND) } // even the tip no longer resolves
        }
        store.beginRelist(connId, PROJECT, now = 200)
        val progress = run(client, connId)
        assertEquals(2, client.mergeBaseCalls, "the head check and the failed probe: no candidate is asked")
        assertNull(progress["commitsTombstoned"])
        assertEquals(3L, progress["commitsSweepUnconfirmed"])
        assertTrue(store.commitRows(connId, PROJECT).none { it[GitLabRawStore.Commits.deletedAt] != null })
    }

    @Test
    fun `kept candidates leave the set - a deferred re-list's second run asks only about the rest`() = runBlocking<Unit> {
        val connId = connection()
        val hidden = MAX_SWEEP_CONFIRMATIONS + 50
        store.upsertCommits(connId, PROJECT, (1..hidden).map { raw(it) }, now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(hidden))
        val (client, first) = relist(connId, (hidden + 1)..(hidden + 1), base = { it })
        assertEquals(MAX_SWEEP_CONFIRMATIONS.toLong(), first["commitsSweepKept"])
        assertTrue(project(connId).relistRequested, "more candidates than the cap: the re-list goes on")

        client.mergeBaseCalls = 0
        client.listed.clear()
        val second = run(client, connId)
        assertEquals(50L, second["commitsSweepKept"])
        assertEquals(51, client.mergeBaseCalls, "the probe and only the 50 not yet touched: kept candidates did not come back")
        assertTrue(client.listed.all { it.second == null }, "the branch is still at the recorded head: nothing is listed again")
        assertEquals(1, client.listCalls)
        assertFalse(project(connId).relistRequested)
        assertTrue(store.commitRows(connId, PROJECT).none { it[GitLabRawStore.Commits.deletedAt] != null })
    }

    @Test
    fun `an empty re-list listing takes the branch's own tip as the head, so the next run is a plain CHECK`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(4)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(4))
        store.beginRelist(connId, PROJECT, now = 200)
        // `since` shows nothing (every commit is older than it); the branch itself (no `since`) is at 9, a descendant of the head.
        val client = FakeClient().apply {
            listingOf = { _, _, since, _ -> if (since == null) commitPage(9 downTo 9) else commitPage(IntRange.EMPTY) }
            mergeBaseOf = { _, a, _ -> a }
        }
        run(client, connId)
        assertEquals(sha(9), project(connId).headSha, "the stale head is replaced by the branch's tip")
        assertFalse(project(connId).relistRequested)
        assertNull(store.projectRows(connId).single()[GitLabRawStore.Projects.relistStartedAt])

        client.mergeBaseCalls = 0
        client.listed.clear()
        val next = run(client, connId)
        assertEquals(0, client.mergeBaseCalls, "tip == head: a plain CHECK")
        assertEquals(1, client.listCalls)
        assertNull(next["commitsRelisted"])
    }

    @Test
    fun `an empty re-list listing whose branch tip cannot be read keeps and touches every candidate`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(4)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(4))
        store.beginRelist(connId, PROJECT, now = 200)
        // A run that listed nothing and was cut before the sweep: the walk is saved with its listing done and no tip.
        val saved = CommitsWalkCursor(
            PROJECT, WalkMode.RELIST, BRANCH, since = isoInstant(1), sinceMillis = 1, listingDone = true, epochMillis = 200,
        )
        cursors.put(connId, "commits", encodeCursor(CommitsCursor(5, null, saved)), null, null, 5)
        val client = FakeClient().apply {
            listingOf = { _, _, _, _ -> throw fetchFailure("NOT_FOUND") }
            mergeBaseOf = { _, _, _ -> error("no tip, no merge_base") }
        }
        val progress = run(client, connId)
        assertEquals(0, client.mergeBaseCalls)
        assertEquals(1L, progress["commitsSweepUnconfirmed"])
        assertNull(progress["commitsTombstoned"])
        assertEquals(sha(4), project(connId).headSha)
        assertTrue(store.commitRows(connId, PROJECT).single()[GitLabRawStore.Commits.lastSeenAt] > 100, "touched")
    }

    @Test
    fun `a project lost part-way through the batch tombstones nothing - the probe is repeated after it`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, (4..6).map { raw(it) }, now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(6))
        var probes = 0
        val client = FakeClient().apply {
            listingOf = { _, _, _, _ -> commitPage(9 downTo 9) }
            mergeBaseOf = { _, a, b ->
                when {
                    // The project is lost after the first probe.
                    a == sha(9) && b == BRANCH -> if (++probes == 1) a else throw fetchFailure(REF_NOT_FOUND)
                    else -> throw fetchFailure(REF_NOT_FOUND)
                }
            }
        }
        val progress = run(client, connId)
        assertEquals(2, probes, "the probe before the batch and once more after it")
        assertNull(progress["commitsTombstoned"])
        assertEquals(3L, progress["commitsSweepUnconfirmed"])
        assertTrue(store.commitRows(connId, PROJECT).none { it[GitLabRawStore.Commits.deletedAt] != null })
    }

    @Test
    fun `a probe that fails for another reason fails the run, and the next run resumes at the sweep`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, (4..6).map { raw(it) }, now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(6))
        var limited = true
        val client = FakeClient().apply {
            listingOf = { _, _, _, _ -> commitPage(9 downTo 9) }
            mergeBaseOf = { _, a, b ->
                when {
                    a == sha(6) && b == BRANCH -> throw fetchFailure(REF_NOT_FOUND)
                    a == sha(9) && b == BRANCH -> if (limited) throw fetchFailure("RATE_LIMITED") else a
                    else -> a
                }
            }
        }
        assertEquals("RATE_LIMITED", assertFailsWith<FetchException> { run(client, connId) }.code)
        assertTrue(store.commitRows(connId, PROJECT).none { it[GitLabRawStore.Commits.deletedAt] != null })
        val saved = decodeCursor<CommitsCursor>(assertNotNull(cursors.get(connId, "commits")).cursor).walk
        assertTrue(saved?.listingDone == true, "the listing is done: the cursor waits at the sweep")

        limited = false
        client.listed.clear()
        val progress = run(client, connId)
        assertEquals(0, client.listCalls, "resumed straight at the sweep")
        assertEquals(3L, progress["commitsSweepKept"])
        assertFalse(project(connId).relistRequested)
    }

    @Test
    fun `a deferred re-list whose branch was force-pushed again starts a new epoch`() = runBlocking<Unit> {
        val connId = connection()
        val hidden = MAX_SWEEP_CONFIRMATIONS + 50
        store.upsertCommits(connId, PROJECT, (1..hidden).map { raw(it) }, now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(hidden))
        val (client, _) = relist(connId, (hidden + 1)..(hidden + 1), base = { it })
        val first = store.projectRows(connId).single()[GitLabRawStore.Projects.relistStartedAt]
        assertNotNull(first, "deferred: the epoch stays")

        // Pushed again: the recorded head (hidden + 1) is no longer an ancestor of the new tip.
        val newTip = sha(hidden + 2)
        client.listed.clear()
        client.listingOf = { _, _, _, _ -> commitPage((hidden + 2) downTo (hidden + 2)) }
        client.mergeBaseOf = { _, a, b -> if (a == sha(hidden + 1) && b == newTip) sha(1) else a }
        run(client, connId)
        val second = store.projectRows(connId).single()[GitLabRawStore.Projects.relistStartedAt]
        assertNotNull(second)
        assertTrue(second > first, "a second force-push restarts the epoch, so the commits the first sweep touched are candidates again")
        assertTrue(client.listed.any { it.second != null }, "and the branch is listed again")
    }

    @Test
    fun `a commit the RANGE listed before it fell back to a re-list is a candidate, one the re-list saw is not`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(1)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(1))
        var ticks = 10_000L
        val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true }, clock = { ticks++ })
        val client = FakeClient().apply {
            listingOf = { _, ref, _, page ->
                when {
                    !ref.contains("..") -> commitPage(5 downTo 5) // the CHECK, and the re-list
                    page == 1 -> commitPage(5 downTo 4, next = 2)
                    else -> throw fetchFailure("NOT_FOUND") // the head vanished mid-RANGE
                }
            }
            mergeBaseOf = { _, a, _ -> a }
        }
        run(client, connId, context)
        val asked = client.mergeBases.filter { it.second == sha(5) }.map { it.first }.toSet()
        assertEquals(setOf(sha(1), sha(4)), asked, "4 was last seen by the RANGE, before the epoch; 5 again by the re-list, after it")
    }

    @Test
    fun `a NOT_FOUND on a FULL or RELIST listing skips the project and leaves its request as it was`() = runBlocking<Unit> {
        val full = connection()
        val fullProgress = run(FakeClient().apply { listingOf = { _, _, _, _ -> throw fetchFailure("NOT_FOUND") } }, full)
        assertEquals(1L, fullProgress["commitsProjectsSkipped"])
        assertNull(project(full).headSha)
        assertTrue(store.commitRows(full).isEmpty())

        val relisting = connection()
        store.upsertCommits(relisting, PROJECT, listOf(raw(4)), now = 100)
        store.recordCommitsHead(relisting, PROJECT, sha(4))
        store.beginRelist(relisting, PROJECT, now = 200)
        val progress = run(FakeClient().apply { listingOf = { _, _, _, _ -> throw fetchFailure("NOT_FOUND") } }, relisting)
        assertEquals(1L, progress["commitsProjectsSkipped"])
        assertTrue(project(relisting).relistRequested, "the request survives: the next run tries again")
        assertEquals(sha(4), project(relisting).headSha)
        assertNull(progress["commitsRelisted"])
    }

    @Test
    fun `a re-list never starts before the backfill floor`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(3, committedAt = 5)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(3))
        store.beginRelist(connId, PROJECT, now = 200)
        val client = FakeClient().apply {
            listingOf = { _, _, _, _ -> commitPage(4 downTo 4) }
            mergeBaseOf = { _, a, _ -> a }
        }
        run(client, connId, floor = 7_000)
        assertEquals(isoInstant(7_000), client.listed.last().second, "an ancient commit does not widen the window past the floor")
    }

    // --- RECONCILE mode: a `since`-less re-list under a fresh epoch, stream `reconcile_commits` --------------------------------

    private suspend fun runReconcile(
        client: FakeClient,
        connId: UInt,
        context: StreamContext = GitLabFixtures.freshContext(connId),
        floor: Long = BACKFILL,
    ) = GitLabFixtures.runStream(GitLabCommitsStream(client, store, floor, reconcile = true), connId, context)

    private fun at(n: Int, days: Long) = summary(n, committedAt = days * MILLIS_PER_DAY)

    @Test
    fun `reconcile sends no since, lists from page 1, ends past the slack below the floor and stores only commits at or above it`() =
        runBlocking<Unit> {
        val connId = connection()
        val floor = 100 * MILLIS_PER_DAY
        store.upsertCommits(connId, PROJECT, listOf(raw(1, committedAt = 150 * MILLIS_PER_DAY)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(1))
        val client = FakeClient().apply {
            listingOf = { _, _, _, page ->
                when (page) {
                    1 -> GitLabCommitPage(listOf(at(1, 200), at(2, 150), at(3, 101)), 2, null)
                    2 -> GitLabCommitPage(listOf(at(4, 99), at(5, 90)), 3, null)
                    3 -> GitLabCommitPage(listOf(at(6, 85), at(7, 80)), 4, null)
                    else -> error("page 4 must never be asked")
                }
            }
            mergeBaseOf = { _, a, _ -> a }
        }
        val progress = runReconcile(client, connId, floor = floor)
        assertEquals(listOf(1, 2, 3), client.listed.map { it.third }, "page 2 is inside the slack; page 3 is entirely older and ends it")
        assertTrue(client.listed.all { it.first == BRANCH && it.second == null }, "no since on the wire")
        assertEquals(setOf(sha(1), sha(2), sha(3)), store.commitRows(connId).map { it[GitLabRawStore.Commits.sha] }.toSet(),
            "commits below the floor are passed through, never stored")
        assertEquals(2L, progress["commits"])
        assertEquals(3L, progress["pages"])
        assertNull(cursors.get(connId, "reconcile_commits"), "the pass ended")
        assertNull(cursors.get(connId, "commits"), "SYNC's cursor row is never touched")
        assertEquals(sha(1), project(connId).headSha)
    }

    @Test
    fun `reconcile lists a commit SYNC never stored, flags it for PROCESS, and a following SYNC is a plain CHECK`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(1), raw(2), raw(4)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(4))
        val client = FakeClient().apply {
            listingOf = { _, _, _, _ -> commitPage(4 downTo 1) }
            mergeBaseOf = { _, a, _ -> a }
        }
        val progress = runReconcile(client, connId)
        assertEquals(1L, progress["commits"], "the hidden parent is inserted")
        val rows = store.commitRows(connId).associateBy { it[GitLabRawStore.Commits.sha] }
        assertEquals(4, rows.size)
        assertTrue(rows.getValue(sha(3))[GitLabRawStore.Commits.needsProcessing])
        assertTrue(rows.values.all { it[GitLabRawStore.Commits.lastSeenAt] > 100 }, "the walk touched every listed row")
        assertEquals(0, client.mergeBaseCalls, "no candidate: no confirmation")
        assertNull(progress["commitsTombstoned"])
        assertEquals(sha(4), project(connId).headSha)
        assertFalse(project(connId).relistRequested)

        client.listed.clear()
        client.listingOf = { _, _, _, _ -> commitPage(4 downTo 4) }
        val sync = run(client, connId)
        assertEquals(1, client.listCalls, "tip == head: one page, nothing else")
        assertEquals(0, client.mergeBaseCalls)
        assertEquals(emptySet(), sync.keys.intersect(setOf("pages", "commits", "commitsRelisted")))
    }

    @Test
    fun `reconcile asks again about the commits an earlier sweep kept unconfirmed`() = runBlocking<Unit> {
        for (verdict in listOf("gone", "kept", "tip moved")) {
            val connId = connection()
            store.upsertCommits(connId, PROJECT, (1..3).map { raw(it) }, now = 100)
            store.recordCommitsHead(connId, PROJECT, sha(3))
            // An earlier re-list kept commit 2 UNCONFIRMED: touched inside its epoch, so the epoch alone would not ask again.
            store.beginRelist(connId, PROJECT, now = 120)
            store.touchCommits(connId, PROJECT, listOf(sha(2)), now = 150)
            val client = FakeClient().apply {
                listingOf = { _, _, _, _ -> GitLabCommitPage(listOf(summary(3), summary(1)), null, null) }
                mergeBaseOf = { _, a, b ->
                    when {
                        verdict == "tip moved" && b == BRANCH -> sha(1)
                        verdict == "gone" && a == sha(2) -> sha(1)
                        else -> a
                    }
                }
            }
            val progress = runReconcile(client, connId)
            val deleted = store.commitRows(connId).filter { it[GitLabRawStore.Commits.deletedAt] != null }
                .map { it[GitLabRawStore.Commits.sha] }
            when (verdict) {
                "gone" -> {
                    assertEquals(1L, progress["commitsTombstoned"])
                    assertEquals(listOf(sha(2)), deleted)
                }
                "kept" -> {
                    assertEquals(1L, progress["commitsSweepKept"])
                    assertTrue(deleted.isEmpty())
                }
                else -> {
                    assertEquals(1L, progress["commitsSweepUnconfirmed"], "the tip is no longer on the branch: nothing is believed")
                    assertNull(progress["commitsTombstoned"])
                    assertTrue(deleted.isEmpty())
                }
            }
        }
    }

    @Test
    fun `reconcile leaves a headless project to SYNC, skips a gone branch and resumes a failed listing under the same epoch`() =
        runBlocking<Unit> {
        val headless = connection()
        val untouched = FakeClient()
        val headlessProgress = runReconcile(untouched, headless)
        assertEquals(0, untouched.listCalls, "never synced: SYNC's FULL walk is pending")
        assertNull(headlessProgress["commitsProjectsSkipped"])
        assertNull(cursors.get(headless, "reconcile_commits"))
        assertNull(project(headless).headSha)

        val gone = connection()
        store.upsertCommits(gone, PROJECT, listOf(raw(1)), now = 100)
        store.recordCommitsHead(gone, PROJECT, sha(1))
        val skipped = runReconcile(FakeClient().apply { listingOf = { _, _, _, _ -> throw fetchFailure("NOT_FOUND") } }, gone)
        assertEquals(1L, skipped["commitsProjectsSkipped"])
        assertTrue(project(gone).relistRequested, "the request is left set for the next SYNC to continue")

        val connId = connection()
        store.upsertCommits(connId, PROJECT, listOf(raw(1)), now = 100)
        store.recordCommitsHead(connId, PROJECT, sha(1))
        val client = FakeClient().apply {
            listingOf = { _, _, _, page -> if (page == 1) commitPage(5 downTo 4, next = 2) else throw fetchFailure("UPSTREAM_UNAVAILABLE") }
            mergeBaseOf = { _, a, _ -> a }
        }
        assertFailsWith<FetchException> { runReconcile(client, connId) }
        val saved = decodeCursor<CommitsCursor>(assertNotNull(cursors.get(connId, "reconcile_commits")).cursor)
        assertEquals(2, saved.walk?.page)
        assertTrue(saved.walk?.reconcile == true)
        assertNull(saved.walk?.since)
        assertNull(cursors.get(connId, "commits"))
        val epoch = store.projectRows(connId).single()[GitLabRawStore.Projects.relistStartedAt]
        assertEquals(saved.walk?.epochMillis, epoch)

        client.listed.clear()
        client.listingOf = { _, _, _, _ -> commitPage(3 downTo 1) }
        runReconcile(client, connId)
        assertEquals(listOf(2), client.listed.map { it.third }, "resumed at page 2, no new epoch")
        assertNull(cursors.get(connId, "reconcile_commits"))
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
    fun `a moved-out project's pending commits cost no diff call and are not counted`() = runBlocking<Unit> {
        val connId = pendingProject(3)
        store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 10_000, now = 11_000)
        val client = FakeClient().apply { diffOf = { _, _, _ -> GitLabDiffPage(files(1), null) } }
        val progress = runFiles(client, connId)
        assertEquals(0, client.diffCalls)
        assertNull(progress["commitFilesPending"])
        assertEquals(0L, store.countPendingCommitFiles(connId))
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
