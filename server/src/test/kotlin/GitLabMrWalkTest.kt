package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.RunCeiling
import ch.nokillswit.gitlab.GitLabCeilings
import ch.nokillswit.gitlab.GitLabMergeRequestPage
import ch.nokillswit.gitlab.GitLabMergeRequestsStream
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.MR_LIST_OVERLAP_MILLIS
import ch.nokillswit.gitlab.MrDetailsOutcome
import ch.nokillswit.gitlab.MergeRequestsCursor
import ch.nokillswit.gitlab.MergeRequestsPass
import ch.nokillswit.gitlab.RawGitLabMergeRequest
import ch.nokillswit.gitlab.RawGitLabProject
import ch.nokillswit.gitlab.decodeCursor
import ch.nokillswit.gitlab.encodeCursor
import ch.nokillswit.gitlab.isoInstant
import ch.nokillswit.gitlab.purgeAll
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

private const val PLATFORM_GROUP = 7L
private const val APPS_GROUP = 9L
private const val BASE = 1_700_000_000_000L
private const val BACKFILL = 1_600_000_000_000L

/**
 * The MERGE REQUESTS listing against a scripted [FakeClient] (no HTTP): the resumable per-group walk and its cursor, the per-group
 * watermarks that move only when a group's listing completes, the skipped groups and merge requests, the page ceiling and the
 * unchanged re-listing. The details backlog is [GitLabMrDetailsTest]; the stub's figures are [GitLabMrStreamsTest].
 */
class GitLabMrWalkTest {
    private val store: GitLabRawStore = GitLabFixtures.rawStore()
    private val cursors = SyncCursorsService(sharedDatabaseForTests())
    private val connections = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { store.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private fun project(id: Long, group: String, groupId: Long) = RawGitLabProject(
        id, "$group/p$id", group, groupId, "main", false,
        Json.parseToJsonElement("""{"id":$id,"path":"p$id","path_with_namespace":"$group/p$id","name":"p$id"}"""),
    )

    /** A connection whose stored projects are 1 (and, when [second], 2) under `platform` and 3 under `apps`. */
    private suspend fun connection(second: Boolean = false): UInt {
        val connId = GitLabFixtures.createConnection().also { connections += it }
        val projects = listOfNotNull(project(1, "platform", PLATFORM_GROUP), project(2, "platform", PLATFORM_GROUP).takeIf { second }) +
            project(3, "apps", APPS_GROUP)
        store.upsertProjects(connId, projects, now = 1)
        return connId
    }

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

    /** Scripts the listing: per group id, a list of pages (newest first within and across pages). */
    private fun FakeClient.script(vararg groups: Pair<Long, List<List<RawGitLabMergeRequest>>>) {
        val byGroup = groups.toMap()
        groupMergeRequestsOf = { group, _, page ->
            val pages = byGroup[group].orEmpty()
            GitLabMergeRequestPage(pages.getOrNull(page - 1).orEmpty(), if (page < pages.size) page + 1 else null, null)
        }
    }

    private suspend fun list(
        client: FakeClient,
        connId: UInt,
        context: StreamContext = GitLabFixtures.freshContext(connId),
        groups: List<String> = listOf("platform", "apps"),
        ceilings: GitLabCeilings = GitLabCeilings(),
    ) = GitLabFixtures.runStream(GitLabMergeRequestsStream(client, store, groups, BACKFILL, ceilings), connId, context)

    private suspend fun cursorOf(connId: UInt) = cursors.get(connId,
        "merge_requests")?.let { it to decodeCursor<MergeRequestsCursor>(it.cursor) }

    private fun notFound() = FetchException(fakeSource, "NOT_FOUND", null, "/x")

    /** The stored rows by merge-request id (`project * 1000 + iid` here). */
    private suspend fun rowsOf(connId: UInt) = store.mergeRequestRows(connId).associateBy { it[MergeRequests.mrId] }

    // --- resume ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a lost lease after page 1 leaves the pass at page 2 and the next run resumes there and sets the watermark only at the end`() =
        runBlocking<Unit> {
            val connId = connection()
            val client = FakeClient().apply {
                script(PLATFORM_GROUP to listOf(listOf(mr(9), mr(8), mr(7)), listOf(mr(6), mr(5), mr(4)), listOf(mr(3), mr(2), mr(1))))
            }
            var beats = 0
            val failing = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> ++beats > 1 })
            assertFailsWith<LeaseLostException> { list(client, connId, failing, groups = listOf("platform")) }
            val (_, midway) = assertNotNull(cursorOf(connId))
            val pass = assertNotNull(midway.pass)
            assertEquals(0, pass.groupIndex, "group 0 is still being listed")
            assertEquals(2, pass.page)
            assertEquals(mr(9).updatedAt, pass.seenMax, "the running maximum is page 1's first (newest) item")
            assertTrue(midway.watermarks.isEmpty(), "no watermark before the group's last page committed")
            assertEquals(3, rowsOf(connId).size)

            val progress = list(client, connId, groups = listOf("platform"))
            assertEquals(Triple(PLATFORM_GROUP, pass.updatedAfter, 2), client.listedMrs[1], "the resumed walk asks the SAME listing")
            assertEquals(Triple(PLATFORM_GROUP, pass.updatedAfter, 3), client.listedMrs.last())
            val (row, done) = assertNotNull(cursorOf(connId))
            assertNull(done.pass)
            assertEquals(mapOf("platform" to mr(9).updatedAt), done.watermarks)
            assertEquals(mr(9).updatedAt, row.watermarkAt)
            assertNotNull(row.lastCompletedAt)
            assertEquals(6L, progress["mergeRequests"], "this run inserted the six rows of pages 2 and 3")
            assertEquals(9, rowsOf(connId).size)
        }

    @Test
    fun `a lost lease between two groups resumes at the second one with the first group's watermark kept`() = runBlocking<Unit> {
        val connId = connection()
        val client = FakeClient().apply {
            script(PLATFORM_GROUP to listOf(listOf(mr(2), mr(1))), APPS_GROUP to listOf(listOf(mr(2, projectId = 3), mr(1, projectId = 3))))
        }
        var beats = 0
        val failing = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> ++beats > 1 })
        assertFailsWith<LeaseLostException> { list(client, connId, failing) }
        val (_, midway) = assertNotNull(cursorOf(connId))
        assertEquals(1, midway.pass?.groupIndex)
        assertEquals(mapOf("platform" to mr(2).updatedAt), midway.watermarks, "group 0 completed: its watermark moved with the cursor")

        list(client, connId)
        assertEquals(PLATFORM_GROUP, client.listedMrs[0].first)
        assertEquals(APPS_GROUP, client.listedMrs[1].first, "the second run starts at the second group")
        assertEquals(2, client.listedMrs.size, "the first group is not listed again")
        assertNull(cursorOf(connId)?.second?.pass)
    }

    @Test
    fun `a ceiling trip commits the pages before it, leaves the cursor at the next page and moves no watermark`() = runBlocking<Unit> {
        val connId = connection()
        val client = FakeClient().apply { script(PLATFORM_GROUP to listOf(listOf(mr(4), mr(3)), listOf(mr(2), mr(1)))) }
        val failure = assertFailsWith<FetchException> {
            list(client, connId, groups = listOf("platform"), ceilings = GitLabCeilings(mergeRequests = RunCeiling(maxPages = 1,
                maxItems = 100)))
        }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertEquals(1, client.listedMrs.size, "page 2 was never asked")
        assertEquals(2, rowsOf(connId).size)
        val (_, state) = assertNotNull(cursorOf(connId))
        assertEquals(2, state.pass?.page)
        assertTrue(state.watermarks.isEmpty())
    }

    // --- the watermark ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the next pass asks from the watermark minus the overlap, a group without one from the floor, and a watermark never moves back`() =
        runBlocking<Unit> {
            val connId = connection()
            val client = FakeClient().apply { script(PLATFORM_GROUP to listOf(listOf(mr(2), mr(1)))) } // apps lists nothing
            list(client, connId)
            assertEquals(isoInstant(BACKFILL), client.listedMrs[0].second)
            assertEquals(isoInstant(BACKFILL), client.listedMrs[1].second)
            val (first, state) = assertNotNull(cursorOf(connId))
            assertEquals(mapOf("platform" to mr(2).updatedAt), state.watermarks, "an empty listing sets no watermark")
            assertEquals(mr(2).updatedAt, first.watermarkAt)

            client.script(PLATFORM_GROUP to listOf(listOf(mr(1)))) // a listing whose newest item is OLDER than the stored watermark
            client.listedMrs.clear()
            list(client, connId)
            assertEquals(isoInstant(mr(2).updatedAt - MR_LIST_OVERLAP_MILLIS), client.listedMrs[0].second)
            assertEquals(isoInstant(BACKFILL), client.listedMrs[1].second, "the group without a watermark still asks the floor")
            assertEquals(mapOf("platform" to mr(2).updatedAt), cursorOf(connId)?.second?.watermarks)
        }

    @Test
    fun `a pass saved for another group list is dropped, and the watermark of a group that is no longer configured goes with it`() =
        runBlocking<Unit> {
            val connId = connection()
            val old = MergeRequestsCursor(
                watermarks = mapOf("platform" to BASE, "retired" to BASE + 5),
                pass = MergeRequestsPass(listOf("retired", "platform"), groupIndex = 1, page = 4, updatedAfter = "2020-01-01T00:00:00Z",
                    seenMax = BASE),
            )
            cursors.put(connId, "merge_requests", encodeCursor(old))
            val client = FakeClient().apply { script(PLATFORM_GROUP to listOf(listOf(mr(1)))) }
            list(client, connId, groups = listOf("platform"))
            assertEquals(Triple(PLATFORM_GROUP, isoInstant(BASE - MR_LIST_OVERLAP_MILLIS), 1), client.listedMrs.single(),
                "page 1 of group 0 again")
            assertEquals(setOf("platform"), cursorOf(connId)?.second?.watermarks?.keys)
        }

    // --- skipped groups and merge requests -------------------------------------------------------------------------------------

    @Test
    fun `a group answering NOT_FOUND is skipped and counted, even mid-listing - nothing else fails`() = runBlocking<Unit> {
        val connId = connection()
        val client = FakeClient().apply {
            groupMergeRequestsOf = { group, _, page ->
                when {
                    group == PLATFORM_GROUP && page == 1 -> GitLabMergeRequestPage(listOf(mr(2), mr(1)), 2, null)
                    group == PLATFORM_GROUP -> throw notFound() // the group vanished between two pages
                    else -> GitLabMergeRequestPage(listOf(mr(1, projectId = 3)), null, null)
                }
            }
        }
        val progress = list(client, connId)
        assertEquals(1L, progress["mrGroupsSkipped"])
        assertEquals(setOf(1_001L, 1_002L, 3_001L), rowsOf(connId).keys, "page 1 of platform and apps' item are stored")
        val (_, state) = assertNotNull(cursorOf(connId))
        assertNull(state.pass, "the pass ended")
        assertEquals(setOf("apps"), state.watermarks.keys, "the vanished group's watermark did not move")

        val again = list(FakeClient().apply { groupMergeRequestsOf = { _, _, _ -> throw notFound() } }, connId)
        assertEquals(2L, again["mrGroupsSkipped"], "every group answering NOT_FOUND is skipped, none fails the run")
    }

    @Test
    fun `a group without a live project lists nothing, an unknown project's merge request is skipped, a moved-out one's is stored`() =
        runBlocking<Unit> {
            val connId = connection(second = true)
            store.upsertProjects(connId, listOf(project(1, "platform", PLATFORM_GROUP), project(3, "apps", APPS_GROUP)), now = 20)
            store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 10, now = 30) // project 2 is moved out, 1 and 3 live
            val client = FakeClient().apply {
                script(PLATFORM_GROUP to listOf(listOf(mr(3, projectId = 99), mr(2, projectId = 2), mr(1))))
            }
            val none = list(client, connId, groups = listOf("platform", "elsewhere"))
            assertEquals(1, client.listedMrs.size, "`elsewhere` has no stored project: no call")
            assertEquals(1L, none["mrGroupsSkipped"])
            assertEquals(1L, none["mrSkipped"], "the unknown project's item")
            assertEquals(setOf(1L to 1L, 2L to 2L),
                rowsOf(connId).values.map { it[MergeRequests.projectId] to it[MergeRequests.iid] }.toSet())
        }

    @Test
    fun `a group whose projects were all moved out lists nothing`() = runBlocking<Unit> {
        val connId = connection()
        store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 10, now = 30) // every project is out
        val client = FakeClient().apply { script(PLATFORM_GROUP to listOf(listOf(mr(1)))) }
        val progress = list(client, connId)
        assertEquals(0, client.listedMrs.size)
        assertEquals(2L, progress["mrGroupsSkipped"])
        assertTrue(rowsOf(connId).isEmpty())
    }

    // --- unchanged ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `listing the same merge requests again changes no row, moves only last_seen_at and leaves the details alone`() = runBlocking<Unit> {
        val connId = connection()
        val client = FakeClient().apply { script(PLATFORM_GROUP to listOf(listOf(mr(2), mr(1)))) }
        var now = 10_000L
        val first = list(client, connId, StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true },
            clock = { now++ }))
        assertEquals(2L, first["mergeRequests"])
        rowsOf(connId).keys.forEach { store.settleMrDetails(connId, it, emptyList(), 50_000, MrDetailsOutcome()) }
        val before = rowsOf(connId).getValue(1_001L)
        now = 90_000L
        val second = list(client, connId, StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true },
            clock = { now++ }))
        assertNull(second["mergeRequests"])
        assertNull(second["mergeRequestsChanged"])
        val after = rowsOf(connId).getValue(1_001L)
        assertEquals(before[MergeRequests.changedAt], after[MergeRequests.changedAt])
        assertEquals(before[MergeRequests.detailsSyncedAt], after[MergeRequests.detailsSyncedAt])
        assertTrue(after[MergeRequests.lastSeenAt] >= 90_000L, "last_seen_at moved")
        assertEquals(0L, store.countStaleMergeRequests(connId), "an unchanged listing makes no details stale")
    }
}
