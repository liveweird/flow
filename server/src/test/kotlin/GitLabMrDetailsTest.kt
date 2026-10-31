package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.gitlab.DETAILS_ERROR_FETCH_FAILED
import ch.nokillswit.gitlab.DETAILS_ERROR_LIMIT_EXCEEDED
import ch.nokillswit.gitlab.DETAILS_ERROR_NOT_FOUND
import ch.nokillswit.gitlab.GITLAB_PAGE_SIZE
import ch.nokillswit.gitlab.GitLabDiffStats
import ch.nokillswit.gitlab.GitLabDiffStatsAnswer
import ch.nokillswit.gitlab.GitLabMrDetailsStream
import ch.nokillswit.gitlab.GitLabNotePage
import ch.nokillswit.gitlab.GitLabNoteSummary
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabRawStore.MrEvents
import ch.nokillswit.gitlab.GitLabReviewerSummary
import ch.nokillswit.gitlab.MR_DETAILS_BATCH
import ch.nokillswit.gitlab.MR_REVIEWER_OTHER_STATE
import ch.nokillswit.gitlab.RawGitLabMergeRequest
import ch.nokillswit.gitlab.RawGitLabProject
import ch.nokillswit.gitlab.purgeAll
import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.LeaseLostException
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncCursorsService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = 1_700_000_000_000L

/**
 * The MR DETAILS stream against a scripted [FakeClient] (no HTTP): the per-merge-request transaction and its kill points, the GraphQL
 * gap rules, the notes cap, the terminal outcomes (NOT_FOUND, oversized), the skip and call budgets and the failures that must
 * propagate. The listing is [GitLabMrWalkTest]; the stub's figures are [GitLabMrStreamsTest].
 */
class GitLabMrDetailsTest {
    private val store: GitLabRawStore = GitLabFixtures.rawStore()
    private val cursors = SyncCursorsService(sharedDatabaseForTests())
    private val connections = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { store.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    /** A listing time after every stamp a test run made (stamps use the real clock): the re-listed row is stale again. */
    private fun later() = System.currentTimeMillis() + 3_600_000L

    private fun failure(code: String) = FetchException(fakeSource, code, null, "/x")

    private fun mr(n: Long, projectId: Long = 1, updatedAt: Long = BASE + n * 1_000) = RawGitLabMergeRequest(
        mrId = projectId * 1_000 + n, projectId = projectId, iid = n, state = "opened", draft = false, authorId = 5, mergeUserId = null,
        createdAt = 100, updatedAt = updatedAt, mergedAt = null, closedAt = null, sourceBranch = "feature/$n", targetBranch = "main",
        title = "Merge request $n", headSha = null, mergeCommitSha = null, squashCommitSha = null, reviewerIds = emptyList(),
        assigneeIds = emptyList(), userNotesCount = 0,
        payload = buildJsonObject {
            put("id", projectId * 1_000 + n)
            put("iid", n)
            put("updated_at", updatedAt)
        },
    )

    /** A connection with [count] stale merge requests of project 1 (iid 1..count, highest newest) and [otherProject] of project 3. */
    private suspend fun connection(count: Int, otherProject: Int = 0): UInt {
        val connId = GitLabFixtures.createConnection().also { connections += it }
        val projects = listOf(project(1, "platform", 7), project(3, "apps", 9))
        store.upsertProjects(connId, projects, now = 1)
        store.upsertMergeRequests(connId, (1..count).map { mr(it.toLong()) } + (1..otherProject).map { mr(it.toLong(), 3, BASE + it) },
            now = 100)
        return connId
    }

    private fun project(id: Long, group: String, groupId: Long) = RawGitLabProject(
        id, "$group/p$id", group, groupId, "main", false,
        Json.parseToJsonElement("""{"id":$id,"path":"p$id","path_with_namespace":"$group/p$id","name":"p$id"}"""),
    )

    private fun note(id: Long, action: String = "COMMENT") = GitLabNoteSummary(id, 5, id * 10, false, "DiscussionNote", action, emptyList())

    /** The happy defaults: one note per merge request (id = iid), no approvals or reviewers, stats for every iid asked. */
    private fun FakeClient.scriptAll() {
        notesOf = { _, iid, _ -> GitLabNotePage(listOf(note(iid)), null) }
        approvalsOf = { _, _ -> emptyList() }
        reviewersOf = { _, _ -> emptyList() }
        diffStatsOf = { _, iids -> GitLabDiffStatsAnswer(iids.associateWith { GitLabDiffStats(it.toInt(), 1, 1) }, 0, false) }
    }

    private suspend fun details(
        client: FakeClient,
        connId: UInt,
        callsPerRun: Int = 100_000,
        context: StreamContext = GitLabFixtures.freshContext(connId),
    ) = GitLabFixtures.runStream(GitLabMrDetailsStream(client, store, callsPerRun), connId, context)

    private suspend fun rowOf(connId: UInt, iid: Long, projectId: Long = 1) = store.mergeRequestRows(connId).single {
        it[MergeRequests.iid] == iid && it[MergeRequests.projectId] == projectId
    }

    private suspend fun eventIds(connId: UInt, iid: Long, source: String = "NOTE") = store.mrEventRows(connId, 1_000 + iid)
        .filter { it[MrEvents.eventSource] == source }.map { it[MrEvents.sourceId] }.sorted()

    private fun failingAfterFirstHeartbeat(connId: UInt): StreamContext {
        var beats = 0
        return StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> ++beats > 1 })
    }

    // --- resume ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a lost lease after the first merge request leaves it stamped with its events and the others stale, the next run finishes them`() =
        runBlocking<Unit> {
            val connId = connection(3)
            val client = FakeClient().apply { scriptAll() }
            assertFailsWith<LeaseLostException> { details(client, connId, context = failingAfterFirstHeartbeat(connId)) }
            assertNotNull(rowOf(connId, 3)[MergeRequests.detailsSyncedAt], "the newest was committed before the heartbeat")
            assertEquals(listOf(3L), eventIds(connId, 3))
            assertNull(rowOf(connId, 2)[MergeRequests.detailsSyncedAt])
            assertEquals(2L, store.countStaleMergeRequests(connId))

            val progress = details(client, connId)
            assertEquals(2L, progress["mrDetails"])
            assertEquals(0L, store.countStaleMergeRequests(connId))
            assertEquals(listOf(1L, 2L), client.graphqlCalls.last().second.sorted(),
                "the second claim asks only for the two that were left")
            assertEquals(listOf(2L), eventIds(connId, 2))
        }

    @Test
    fun `a failure between the notes and the stamp leaves the merge request stale with no events, and the rerun writes the set once`() =
        runBlocking<Unit> {
            val connId = connection(1)
            val client = FakeClient().apply {
                scriptAll()
                reviewersOf = { _, _ -> throw failure("RATE_LIMITED") }
            }
            assertFailsWith<FetchException> { details(client, connId) }
            assertTrue(store.mrEventRows(connId).isEmpty(), "events are written with the stamp, never before it")
            assertEquals(1L, store.countStaleMergeRequests(connId))
            client.scriptAll()
            details(client, connId)
            assertEquals(listOf(1L), eventIds(connId, 1))
            assertEquals(1, store.mrEventRows(connId).size)
        }

    // --- GraphQL gaps ----------------------------------------------------------------------------------------------------------

    @Test
    fun `a partial GraphQL answer is a gap for that iid only, a missing project is a gap for all and one call serves each project`() =
        runBlocking<Unit> {
            val connId = connection(3, otherProject = 2)
            val client = FakeClient().apply {
                scriptAll()
                diffStatsOf = { path, iids ->
                    when (path) {
                        "platform/p1" -> GitLabDiffStatsAnswer(iids.filter { it != 2L }.associateWith { GitLabDiffStats(7, 3, 2) }, 1,
                            false, "SOME")
                        else -> GitLabDiffStatsAnswer(emptyMap(), 0, projectMissing = true)
                    }
                }
            }
            val progress = details(client, connId)
            assertEquals(2, client.graphqlCalls.size, "ONE call per project per batch")
            assertEquals(setOf("platform/p1", "apps/p3"), client.graphqlCalls.map { it.first }.toSet())
            assertEquals(3L, progress["mrDiffStatsGaps"], "iid 2 of project 1 and both of project 3")
            assertNull(rowOf(connId, 2)[MergeRequests.additions])
            assertEquals(7, rowOf(connId, 3)[MergeRequests.additions])
            assertEquals(2, rowOf(connId, 3)[MergeRequests.fileCount])
            assertNull(rowOf(connId, 1, projectId = 3)[MergeRequests.additions])
            assertEquals(0L, store.countStaleMergeRequests(connId), "a gap is stamped complete")
        }

    @Test
    fun `every per-answer GraphQL failure is a gap and the run goes on, each code pinned`() = runBlocking<Unit> {
        for (code in listOf("INVALID_RESPONSE", "NOT_FOUND", "LIMIT_EXCEEDED", "REDIRECT", "FORBIDDEN_SCOPE")) {
            val connId = connection(2)
            val client = FakeClient().apply {
                scriptAll()
                diffStatsOf = { _, _ -> throw failure(code) }
            }
            val progress = details(client, connId)
            assertEquals(2L, progress["mrDiffStatsGaps"], code)
            assertNull(progress["mrGraphqlBreaker"], code)
            assertEquals(1, client.graphqlCalls.size, code)
            assertEquals(0L, store.countStaleMergeRequests(connId), "$code: both are stamped, gap or not")
            assertEquals(2L, store.mrCounts(connId).mrDiffStatsMissing, code)
        }
    }

    @Test
    fun `the first GraphQL outage stops the stream normally with nothing gapped, costs one call, and the next run completes`() =
        runBlocking<Unit> {
            for (code in listOf("TIMEOUT", "UPSTREAM_UNAVAILABLE")) {
                val connId = connection(3, otherProject = 2)
                val client = FakeClient().apply {
                    scriptAll()
                    diffStatsOf = { _, _ -> throw failure(code) }
                }
                val progress = details(client, connId)
                assertEquals(1L, progress["mrGraphqlBreaker"], code)
                assertEquals(1, client.graphqlCalls.size, "$code: one GraphQL call this run, not one per project")
                assertEquals(0, client.restCalls, "$code: the stats come first, so no REST read was wasted")
                assertNull(progress["mrDetails"], code)
                assertNull(progress["mrDiffStatsGaps"], code)
                assertEquals(5L, progress["mrDetailsPending"], code)
                assertEquals(5L, store.countStaleMergeRequests(connId), "$code: nothing was stamped, so no permanent gap")

                client.scriptAll()
                val recovered = details(client, connId)
                assertEquals(5L, recovered["mrDetails"], code)
                assertNull(recovered["mrDiffStatsGaps"], code)
                assertEquals(0L, store.mrCounts(connId).mrDiffStatsMissing, code)
            }
        }

    @Test
    fun `an outage on a later project keeps the earlier project settled with its stats and leaves the rest pending`() = runBlocking<Unit> {
        val connId = connection(3, otherProject = 2)
        val client = FakeClient().apply {
            scriptAll()
            val ok = diffStatsOf
            diffStatsOf = { path, iids -> if (path == "apps/p3") throw failure("UPSTREAM_UNAVAILABLE") else ok(path, iids) }
        }
        val progress = details(client, connId)
        assertEquals(3L, progress["mrDetails"], "project 1's three, newest first")
        assertEquals(1L, progress["mrGraphqlBreaker"])
        assertEquals(2, client.graphqlCalls.size)
        assertEquals(2L, progress["mrDetailsPending"])
        assertEquals(3, rowOf(connId, 3)[MergeRequests.additions])
    }

    @Test
    fun `a rate limit or a rejected token on the GraphQL call fails the run instead of becoming a gap`() = runBlocking<Unit> {
        for (code in listOf("RATE_LIMITED", "AUTHENTICATION_FAILED", "BLOCKED_HOST")) {
            val connId = connection(2)
            val client = FakeClient().apply {
                scriptAll()
                diffStatsOf = { _, _ -> throw failure(code) }
            }
            assertEquals(code, assertFailsWith<FetchException> { details(client, connId) }.code)
            assertEquals(2L, store.countStaleMergeRequests(connId), "$code: nothing was stamped")
            assertTrue(store.mrEventRows(connId).isEmpty())
        }
    }

    // --- notes, approvals, reviewers -------------------------------------------------------------------------------------------

    @Test
    fun `an 11th notes page caps the merge request at exactly 1000 note rows`() = runBlocking<Unit> {
        val connId = connection(1)
        val client = FakeClient().apply {
            scriptAll()
            notesOf = { _, _, page ->
                GitLabNotePage((1..GITLAB_PAGE_SIZE).map { note(((page - 1) * GITLAB_PAGE_SIZE + it).toLong()) },
                    if (page < 12) page + 1 else null)
            }
        }
        val progress = details(client, connId)
        assertEquals(10, client.restCalls - 2, "ten notes pages are read (plus approvals and reviewers); the 11th is only known to exist")
        assertEquals(1_000, store.mrEventRows(connId).count { it[MrEvents.eventSource] == "NOTE" })
        assertTrue(rowOf(connId, 1)[MergeRequests.notesCapped])
        assertEquals(1L, progress["mrNotesCapped"])
        assertEquals(1_000L, progress["mrEvents"])
    }

    @Test
    fun `a refresh replaces the events - one present before and absent after is gone, and approvals and reviewers become rows`() =
        runBlocking<Unit> {
            val connId = connection(1)
            val client = FakeClient().apply {
                scriptAll()
                notesOf = { _, _, _ -> GitLabNotePage(listOf(note(1), note(2), note(3)), null) }
                approvalsOf = { _, _ -> listOf(40L, 41L) }
                reviewersOf = { _, _ -> listOf(GitLabReviewerSummary(50, "requested_changes", 777), GitLabReviewerSummary(51, "weird",
                    null)) }
            }
            details(client, connId)
            assertEquals(listOf(1L, 2L, 3L), eventIds(connId, 1))
            assertEquals(listOf(40L, 41L), eventIds(connId, 1, "APPROVAL"))
            val reviewers = store.mrEventRows(connId).filter { it[MrEvents.eventSource] == "REVIEWER" }
                .associate { it[MrEvents.sourceId] to (it[MrEvents.action] to it[MrEvents.eventAt]) }
            assertEquals(mapOf(50L to ("REQUESTED_CHANGES" to 777L), 51L to (MR_REVIEWER_OTHER_STATE to null)), reviewers)

            store.upsertMergeRequests(connId, listOf(mr(1, updatedAt = BASE + 77_000)), now = later())
            client.notesOf = { _, _, _ -> GitLabNotePage(listOf(note(2), note(3), note(4), note(4)),
                null) } // 4 is listed twice by a page shift
            client.approvalsOf = { _, _ -> emptyList() }
            val progress = details(client, connId)
            assertEquals(listOf(2L, 3L, 4L), eventIds(connId, 1), "1 is gone, the doubly listed 4 is one row")
            assertEquals(emptyList(), eventIds(connId, 1, "APPROVAL"))
            assertEquals(5L, progress["mrEvents"], "3 notes + 2 reviewers, the repeated note counted once")
        }

    @Test
    fun `a NOT_FOUND on the notes stamps the merge request with no events or tombstone - a changed listing clears the stamp`() =
        runBlocking<Unit> {
            val connId = connection(2)
            val client = FakeClient().apply {
                scriptAll()
                notesOf = { _, iid, page -> if (iid == 2L && page == 1) throw failure("NOT_FOUND") else GitLabNotePage(listOf(note(iid)),
                    null) }
            }
            val progress = details(client, connId)
            assertEquals(1L, progress["mrDetailsMissing"])
            assertEquals(1L, progress["mrDetails"])
            val gone = rowOf(connId, 2)
            assertEquals(DETAILS_ERROR_NOT_FOUND, gone[MergeRequests.detailsError])
            assertNull(gone[MergeRequests.deletedAt], "no tombstone: RECONCILE decides")
            assertTrue(eventIds(connId, 2).isEmpty())
            assertEquals(0L, store.countStaleMergeRequests(connId), "it left the backlog - no repeated work")

            client.restCalls = 0
            details(client, connId)
            assertEquals(0, client.restCalls)

            store.upsertMergeRequests(connId, listOf(mr(2, updatedAt = BASE + 55_000)), now = later())
            assertNull(rowOf(connId, 2)[MergeRequests.detailsError], "seeing it again clears the stamp")
            client.scriptAll()
            details(client, connId)
            assertEquals(listOf(2L), eventIds(connId, 2))
            assertNull(rowOf(connId, 2)[MergeRequests.detailsError])
        }

    @Test
    fun `a NOT_FOUND on a later notes page is the same terminal outcome`() = runBlocking<Unit> {
        val connId = connection(1)
        val client = FakeClient().apply {
            scriptAll()
            notesOf = { _, _, page -> if (page == 1) GitLabNotePage(listOf(note(1)), 2) else throw failure("NOT_FOUND") }
        }
        details(client, connId)
        assertEquals(DETAILS_ERROR_NOT_FOUND, rowOf(connId, 1)[MergeRequests.detailsError])
        assertTrue(store.mrEventRows(connId).isEmpty())
    }

    @Test
    fun `a NOT_FOUND on approvals or reviewers alone is none for that merge request, counted, and the merge request is complete`() =
        runBlocking<Unit> {
            val connId = connection(1)
            val client = FakeClient().apply {
                scriptAll()
                approvalsOf = { _, _ -> throw failure("NOT_FOUND") }
                reviewersOf = { _, _ -> throw failure("NOT_FOUND") }
            }
            val progress = details(client, connId)
            assertEquals(1L, progress["mrApprovalsUnavailable"])
            assertEquals(1L, progress["mrReviewersUnavailable"])
            assertEquals(1L, progress["mrDetails"])
            assertNull(progress["mrDetailsMissing"])
            assertNull(rowOf(connId, 1)[MergeRequests.detailsError])
            assertEquals(listOf(1L), eventIds(connId, 1))
            assertEquals(0L, store.countStaleMergeRequests(connId))
        }

    // --- oversized -------------------------------------------------------------------------------------------------------------

    @Test
    fun `an oversized merge request is stamped at the END of the run, asked once, and the run succeeds`() = runBlocking<Unit> {
        val connId = connection(3)
        val askedAboutThree = mutableListOf<Int>()
        val client = FakeClient().apply {
            scriptAll()
            notesOf = { _, iid, page ->
                if (iid == 3L) {
                    askedAboutThree += page
                    throw failure("LIMIT_EXCEEDED")
                }
                GitLabNotePage(listOf(note(iid)), null)
            }
        }
        val progress = details(client, connId)
        assertEquals(1, askedAboutThree.size, "never re-claimed within the run, so never asked twice")
        assertEquals(1L, progress["mrDetailsOversized"])
        assertEquals(2L, progress["mrDetails"])
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, rowOf(connId, 3)[MergeRequests.detailsError])
        assertNotNull(rowOf(connId, 3)[MergeRequests.detailsSyncedAt])
        assertTrue(eventIds(connId, 3).isEmpty())
        assertEquals(0L, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `an oversized approvals answer is the same terminal outcome instead of failing every run`() = runBlocking<Unit> {
        val connId = connection(1)
        val client = FakeClient().apply {
            scriptAll()
            approvalsOf = { _, _ -> throw failure("LIMIT_EXCEEDED") }
        }
        details(client, connId)
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, rowOf(connId, 1)[MergeRequests.detailsError])
    }

    @Test
    fun `more oversized merge requests than the skip budget allows fail the run LIMIT_EXCEEDED, stamping none`() = runBlocking<Unit> {
        val connId = connection(12)
        val client = FakeClient().apply {
            scriptAll()
            notesOf = { _, _, _ -> throw failure("LIMIT_EXCEEDED") }
        }
        assertEquals("LIMIT_EXCEEDED", assertFailsWith<FetchException> { details(client, connId) }.code)
        assertTrue(store.mergeRequestRows(connId).all { it[MergeRequests.detailsError] == null }, "a mis-set cap strands no merge request")
        assertEquals(12L, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `the oversized stamps are written before a failure or the wall clock ends the run`() = runBlocking<Unit> {
        val failing = connection(3)
        val rateLimited = FakeClient().apply {
            scriptAll()
            notesOf = { _, iid, _ ->
                when (iid) {
                    3L -> throw failure("LIMIT_EXCEEDED")
                    2L -> throw failure("RATE_LIMITED")
                    else -> GitLabNotePage(emptyList(), null)
                }
            }
        }
        assertEquals("RATE_LIMITED", assertFailsWith<FetchException> { details(rateLimited, failing) }.code)
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, rowOf(failing, 3)[MergeRequests.detailsError])
        assertEquals(2L, store.countStaleMergeRequests(failing), "the merge requests the failure cut stay stale")

        val timed = connection(2)
        var now = 10_000_000L
        val clock = FakeClient().apply {
            scriptAll()
            notesOf = { _, iid, _ ->
                if (iid == 2L) throw failure("LIMIT_EXCEEDED")
                now += 1_000_000 // iid 1 takes "a long time": the deadline passes while it is read
                GitLabNotePage(listOf(note(iid)), null)
            }
        }
        val context = StreamContext(timed, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true }, clock = { now },
            maxRunMillis = 500_000)
        assertFailsWith<CeilingTripException> { details(clock, timed, context = context) }
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, rowOf(timed, 2)[MergeRequests.detailsError])
        assertNotNull(rowOf(timed, 1)[MergeRequests.detailsSyncedAt], "the merge request being read when the clock ran out was committed")
        assertEquals(0L, store.countStaleMergeRequests(timed))
    }

    @Test
    fun `a merge request whose read keeps failing is stamped FETCH_FAILED at the end, the older ones are read, and a change re-reads it`() =
        runBlocking<Unit> {
            for (where in listOf("notes", "approvals", "reviewers")) {
                val connId = connection(3)
                val asked = mutableListOf<Long>()
                val client = FakeClient().apply {
                    scriptAll()
                    diffStatsOf = { _, iids -> GitLabDiffStatsAnswer(emptyMap(), 0, false) } // every stat a gap
                    val broken = { iid: Long ->
                        if (iid == 2L) {
                            asked += iid
                            throw failure("UPSTREAM_UNAVAILABLE")
                        }
                    }
                    val ok = notesOf
                    notesOf = { project, iid, page ->
                        if (where == "notes") broken(iid)
                        ok(project, iid, page)
                    }
                    approvalsOf = { _, iid ->
                        if (where == "approvals") broken(iid)
                        emptyList()
                    }
                    reviewersOf = { _, iid ->
                        if (where == "reviewers") broken(iid)
                        emptyList()
                    }
                }
                val progress = details(client, connId)
                assertEquals(1L, progress["mrDetailsFailed"], where)
                assertEquals(2L, progress["mrDetails"], "$where: the older merge request is read behind the bad one")
                assertEquals(2L, progress["mrDiffStatsGaps"], "$where: gaps are counted for the stamped ones only")
                assertEquals(1, asked.size, "$where: the bad one is not re-claimed within the run")
                assertEquals(DETAILS_ERROR_FETCH_FAILED, rowOf(connId, 2)[MergeRequests.detailsError], where)
                assertTrue(eventIds(connId, 2).isEmpty(), where)
                assertEquals(0L, store.countStaleMergeRequests(connId), "$where: it left the backlog")

                store.upsertMergeRequests(connId, listOf(mr(2, updatedAt = BASE + 66_000)), now = later())
                assertNull(rowOf(connId, 2)[MergeRequests.detailsError], "$where: a change sheds the stamp")
                client.scriptAll()
                details(client, connId)
                assertEquals(listOf(2L), eventIds(connId, 2), where)
            }
        }

    @Test
    fun `an outage across many merge requests trips the skip budget and stamps none of them`() = runBlocking<Unit> {
        val connId = connection(12)
        val client = FakeClient().apply {
            scriptAll()
            notesOf = { _, _, _ -> throw failure("UPSTREAM_UNAVAILABLE") }
        }
        assertEquals("LIMIT_EXCEEDED", assertFailsWith<FetchException> { details(client, connId) }.code)
        assertTrue(store.mergeRequestRows(connId).all { it[MergeRequests.detailsError] == null })
        assertEquals(12L, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `a rejected token on a notes call propagates and stamps nothing`() = runBlocking<Unit> {
        val connId = connection(2)
        val client = FakeClient().apply {
            scriptAll()
            notesOf = { _, _, _ -> throw failure("AUTHENTICATION_FAILED") }
        }
        assertEquals("AUTHENTICATION_FAILED", assertFailsWith<FetchException> { details(client, connId) }.code)
        assertEquals(2L, store.countStaleMergeRequests(connId))
    }

    // --- budgets and failures --------------------------------------------------------------------------------------------------

    @Test
    fun `the call budget stops the run between merge requests as a normal end, and the next run continues`() = runBlocking<Unit> {
        val connId = connection(3)
        val client = FakeClient().apply { scriptAll() }
        val progress = details(client, connId, callsPerRun = 7) // notes + approvals + reviewers = 3 each, plus 1 GraphQL for the batch
        assertEquals(2L, progress["mrDetails"])
        assertEquals(1L, progress["mrDetailsPending"])
        assertEquals(7, client.restCalls + client.graphqlCalls.size)
        assertEquals(1L, store.countStaleMergeRequests(connId))
        assertNotNull(rowOf(connId, 3)[MergeRequests.detailsSyncedAt], "newest first")
        assertNull(rowOf(connId, 1)[MergeRequests.detailsSyncedAt])

        val rest = details(client, connId)
        assertNull(rest["mrDetailsPending"])
        assertEquals(0L, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `a claimed batch is at most the GraphQL batch size, newest first`() = runBlocking<Unit> {
        val connId = connection(MR_DETAILS_BATCH + 5)
        val client = FakeClient().apply { scriptAll() }
        details(client, connId)
        assertEquals(listOf(MR_DETAILS_BATCH, 5), client.graphqlCalls.map { it.second.size })
        assertEquals(MR_DETAILS_BATCH + 5L, store.mrCounts(connId).mrEvents)
        assertEquals(0L, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `a failure other than a terminal one ends the run after the finished merge requests are stored`() = runBlocking<Unit> {
        val connId = connection(3)
        val client = FakeClient().apply {
            scriptAll()
            notesOf = { _, iid, _ -> if (iid == 2L) throw failure("RATE_LIMITED") else GitLabNotePage(listOf(note(iid)), null) }
        }
        assertEquals("RATE_LIMITED", assertFailsWith<FetchException> { details(client, connId) }.code)
        assertNotNull(rowOf(connId, 3)[MergeRequests.detailsSyncedAt], "the one finished before the failure is stored")
        assertNull(rowOf(connId, 2)[MergeRequests.detailsSyncedAt])
        assertNull(rowOf(connId, 1)[MergeRequests.detailsSyncedAt])
        assertEquals(2L, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `a moved-out project's merge requests cost no call and are not pending`() = runBlocking<Unit> {
        val connId = connection(2)
        store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 10, now = 30)
        val client = FakeClient().apply { scriptAll() }
        val progress = details(client, connId)
        assertEquals(0, client.restCalls)
        assertTrue(client.graphqlCalls.isEmpty())
        assertNull(progress["mrDetailsPending"])
    }
}
