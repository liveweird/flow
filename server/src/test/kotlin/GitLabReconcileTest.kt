package ch.nokillswit

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.gitlab.DETAILS_ERROR_FETCH_FAILED
import ch.nokillswit.gitlab.DETAILS_ERROR_LIMIT_EXCEEDED
import ch.nokillswit.gitlab.DETAILS_ERROR_NOT_FOUND
import ch.nokillswit.gitlab.GitLabDiffStats
import ch.nokillswit.gitlab.GitLabDiffStatsAnswer
import ch.nokillswit.gitlab.GitLabMergeRequestPage
import ch.nokillswit.gitlab.GitLabMergeRequestsStream
import ch.nokillswit.gitlab.GitLabMrReconcileRefresh
import ch.nokillswit.gitlab.GitLabMrReconcileSweep
import ch.nokillswit.gitlab.GitLabProjectDetail
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabReconcileProjectsStream
import ch.nokillswit.gitlab.MAX_MR_CONFIRMATIONS
import ch.nokillswit.gitlab.MR_DETAILS_BATCH
import ch.nokillswit.gitlab.MergeRequestsCursor
import ch.nokillswit.gitlab.MergeRequestsPass
import ch.nokillswit.gitlab.MrDetailsOutcome
import ch.nokillswit.gitlab.MrEventSource
import ch.nokillswit.gitlab.MrReconcileState
import ch.nokillswit.gitlab.RECONCILE_DETAILS_RETRIES
import ch.nokillswit.gitlab.RawGitLabMergeRequest
import ch.nokillswit.gitlab.RawGitLabMrEvent
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PLATFORM_GROUP = 7L
private const val APPS_GROUP = 9L
private const val BASE = 1_700_000_000_000L
private const val FLOOR = 1_600_000_000_000L
private const val SEEDED_AT = 1_000L

/**
 * RECONCILE's three server-side pieces against a scripted [FakeClient] (no HTTP): the project probe, the merge-request re-list mode with
 * its candidate rule and positive-confirmation sweep, and the stats / details refresh. The stub-driven day-2 journey is
 * [GitLabReconcileStreamsTest]. Every test that tombstones pins one item of the "wrong tombstone" checklist of `gitlab-integration.md`.
 */
class GitLabReconcileTest {
    private val store: GitLabRawStore = GitLabFixtures.rawStore()
    private val cursors = SyncCursorsService(sharedDatabaseForTests())
    private val connections = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { store.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private fun project(id: Long, group: String = "platform", groupId: Long = PLATFORM_GROUP, archived: Boolean = false) = RawGitLabProject(
        id, "$group/p$id", group, groupId, "main", archived,
        Json.parseToJsonElement("""{"id":$id,"path":"p$id","path_with_namespace":"$group/p$id","name":"p$id","archived":$archived}"""),
    )

    /** Projects 1 and 2 under `platform`, 3 under `apps`; project 2 is moved out when [moved2]. */
    private suspend fun connection(moved2: Boolean = false): UInt {
        val connId = GitLabFixtures.createConnection().also { connections += it }
        store.upsertProjects(connId, listOf(project(1), project(2), project(3, "apps", APPS_GROUP)), now = SEEDED_AT)
        if (moved2) markMovedOut(connId, 2)
        return connId
    }

    private suspend fun markMovedOut(connId: UInt, projectId: Long) {
        suspendTransaction(sharedDatabaseForTests()) {
            GitLabRawStore.Projects.update({ (GitLabRawStore.Projects.connectionId eq connId) and
                (GitLabRawStore.Projects.projectId eq projectId) }) { it[movedOutAt] = 2_000L }
        }
    }

    private fun mr(n: Long, projectId: Long = 1, updatedAt: Long = BASE + n * 1_000, title: String = "Merge request $n") =
        RawGitLabMergeRequest(
            mrId = projectId * 1_000 + n, projectId = projectId, iid = n, state = "opened", draft = false, authorId = 5,
            mergeUserId = null, createdAt = 100, updatedAt = updatedAt, mergedAt = null, closedAt = null, sourceBranch = "feature/$n",
            targetBranch = "main", title = title, headSha = null, mergeCommitSha = null, squashCommitSha = null, reviewerIds = emptyList(),
            assigneeIds = emptyList(), userNotesCount = 0,
            payload = buildJsonObject {
                put("id", projectId * 1_000 + n)
                put("iid", n)
                put("title", title)
                put("updated_at", updatedAt)
            },
        )

    private suspend fun seed(connId: UInt, vararg mrs: RawGitLabMergeRequest) {
        store.upsertMergeRequests(connId, mrs.toList(), now = SEEDED_AT)
    }

    private fun notFound() = FetchException(fakeSource, "NOT_FOUND", null, "/x")

    private fun failure(code: String) = FetchException(fakeSource, code, null, "/x")

    private fun detail(id: Long, archived: Boolean = false) = GitLabProjectDetail(id, "platform/p$id", "platform", archived, "main")

    /** The listing: per group id, the merge requests of its single page; a group absent from [listing] answers NOT_FOUND. */
    private fun FakeClient.listing(listing: Map<Long, List<RawGitLabMergeRequest>>) {
        groupMergeRequestsOf = { group, _, _ ->
            GitLabMergeRequestPage(listing[group] ?: throw notFound(), null, null)
        }
    }

    private fun reconcileStream(
        client: FakeClient,
        groups: List<String> = listOf("platform", "apps"),
        onDemand: Boolean = false,
    ) = GitLabMergeRequestsStream(
        client, store, groups, FLOOR, reconcile = true,
        sweep = GitLabMrReconcileSweep(client, store, FLOOR), refresh = GitLabMrReconcileRefresh(client, store, onDemand),
    )

    private suspend fun reconcile(
        client: FakeClient,
        connId: UInt,
        context: StreamContext = GitLabFixtures.freshContext(connId),
        groups: List<String> = listOf("platform", "apps"),
    ) = GitLabFixtures.runStream(reconcileStream(client, groups), connId, context)

    private suspend fun row(connId: UInt, mrId: Long) = store.mergeRequestRows(connId).single { it[MergeRequests.mrId] == mrId }

    private suspend fun cursorOf(connId: UInt) = cursors.get(connId, "reconcile_merge_requests")?.let {
        decodeCursor<MergeRequestsCursor>(it.cursor)
    }

    private fun note(id: Long) = RawGitLabMrEvent(MrEventSource.NOTE, id, 5, id, false, "DiscussionNote", "COMMENT")

    // --- 1. the project probe ----------------------------------------------------------------------------------------------

    @Test
    fun `a moved-out project that answers 404 is deleted, an archived one is marked archived, a live one is never probed`() =
        runBlocking<Unit> {
        val connId = GitLabFixtures.createConnection().also { connections += it }
        store.upsertProjects(connId, listOf(project(1), project(2), project(3)), now = SEEDED_AT)
        store.upsertProjects(connId, listOf(project(3)), now = 5_000)
        store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 5_000, now = 6_000)
        val client = FakeClient().apply {
            projectOf = { id -> if (id == 1L) throw notFound() else detail(id, archived = true) }
        }
        val first = GitLabFixtures.runStream(GitLabReconcileProjectsStream(client, store), connId)
        assertEquals(listOf(1L, 2L), client.projectProbes, "the live project 3 is never probed")
        assertEquals(1L, first["projectsDeleted"])
        assertEquals(1L, first["projectsArchived"])
        val rows = store.projectRows(connId).associateBy { it[GitLabRawStore.Projects.projectId] }
        assertNotNull(rows.getValue(1)[GitLabRawStore.Projects.deletedAt])
        assertNull(rows.getValue(2)[GitLabRawStore.Projects.deletedAt])
        assertTrue(rows.getValue(2)[GitLabRawStore.Projects.archived])
        assertEquals(6_000L, rows.getValue(2)[GitLabRawStore.Projects.movedOutAt], "archiving keeps the moved-out marker")

        client.projectProbes.clear()
        val second = GitLabFixtures.runStream(GitLabReconcileProjectsStream(client, store), connId)
        assertEquals(listOf(2L), client.projectProbes, "the deleted project is not probed again")
        assertNull(second["projectsDeleted"], "a repeat writes nothing")
        assertNull(second["projectsArchived"])
        assertEquals(1L, second["projectsProbed"])
        val failing = FakeClient().apply { projectOf = { throw failure("UPSTREAM_UNAVAILABLE") } }
        assertFailsWith<FetchException> { GitLabFixtures.runStream(GitLabReconcileProjectsStream(failing, store), connId) }
    }

    // --- 2. the re-list mode and its cursor ----------------------------------------------------------------------------------

    @Test
    fun `the re-list asks every group from the floor, writes its own cursor and leaves SYNC's byte-identical`() = runBlocking<Unit> {
        val connId = connection()
        seed(connId, mr(1))
        val syncCursor = MergeRequestsCursor(watermarks = mapOf("platform" to BASE, "apps" to BASE))
        cursors.put(connId, "merge_requests", encodeCursor(syncCursor), BASE, 5, 5)
        val client = FakeClient().apply { listing(mapOf(PLATFORM_GROUP to listOf(mr(1)), APPS_GROUP to emptyList())) }
        reconcile(client, connId)
        assertEquals(listOf(PLATFORM_GROUP, APPS_GROUP), client.listedMrs.map { it.first })
        val fromFloor = client.listedMrs.all { it.second == isoInstant(FLOOR) && it.third == 1 }
        assertTrue(fromFloor, "every group from the floor: ${client.listedMrs}")
        val sync = assertNotNull(cursors.get(connId, "merge_requests"))
        assertEquals(syncCursor, decodeCursor<MergeRequestsCursor>(sync.cursor), "SYNC's watermarks are never read or written")
        assertEquals(BASE, sync.watermarkAt)
        assertNull(cursors.get(connId, "reconcile_merge_requests"), "the pass ended")
    }

    @Test
    fun `a lease lost after page 1 resumes at page 2 of the same pass, and a pass saved for another group list restarts`() =
        runBlocking<Unit> {
        val connId = connection()
        val pages = listOf(listOf(mr(9), mr(8)), listOf(mr(7), mr(6)), listOf(mr(5)))
        val client = FakeClient().apply {
            groupMergeRequestsOf = { group, _, page ->
                if (group != PLATFORM_GROUP) GitLabMergeRequestPage(emptyList(), null, null)
                else GitLabMergeRequestPage(pages[page - 1], if (page < pages.size) page + 1 else null, null)
            }
        }
        var beats = 0
        val failing = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> ++beats > 1 })
        assertFailsWith<LeaseLostException> { reconcile(client, connId, failing) }
        val midway = assertNotNull(cursorOf(connId))
        val state = assertNotNull(midway.reconcile)
        assertFalse(state.listingDone)
        assertEquals(2, midway.pass?.page)
        assertTrue(midway.watermarks.isEmpty() && midway.projectSets.isEmpty())

        Thread.sleep(5)
        client.listedMrs.clear()
        reconcile(client, connId)
        assertEquals(Triple(PLATFORM_GROUP, midway.pass?.updatedAfter, 2), client.listedMrs.first(), "resumed at page 2, same listing")
        // Had the resumed run started a NEW pass, page 1's rows (seen before it) would be candidates and probed: the fake would throw.
        assertTrue(client.mrProbes.isEmpty())

        // A pass saved for another group list is dropped together with its state.
        val other = connection()
        seed(other, mr(1, updatedAt = BASE))
        val stale = MergeRequestsCursor(
            pass = MergeRequestsPass(listOf("elsewhere"), 0, 2, "2020-01-01T00:00:00Z"),
            reconcile = MrReconcileState(passStartedAt = 1, completedGroups = listOf("elsewhere")),
        )
        cursors.put(other, "reconcile_merge_requests", encodeCursor(stale), null, null, 5)
        val fresh = FakeClient().apply {
            listing(mapOf(PLATFORM_GROUP to emptyList(), APPS_GROUP to emptyList()))
            projectOf = { detail(it) }
            mergeRequestOf = { _, iid -> mr(iid) }
        }
        reconcile(fresh, other)
        assertEquals(Triple(PLATFORM_GROUP, isoInstant(FLOOR), 1), fresh.listedMrs.first(), "restarted at page 1 from the floor")
        assertEquals(listOf(1L to 1L), fresh.mrProbes, "the old state (passStartedAt = 1) was dropped: the unseen row is a candidate")
    }

    // --- 3. candidates and the confirmation (the "wrong tombstone" checklist) -------------------------------------------------

    @Test
    fun `only a 404 confirmed by the project answering before and after is tombstoned, the rest is kept, touched or never probed`() =
        runBlocking<Unit> {
        val connId = connection(moved2 = true)
        val a = mr(1)
        val b = mr(2)
        val c = mr(3)
        val d = mr(4)
        val e = mr(5, updatedAt = FLOOR - 1_000)
        val f = mr(6, projectId = 2)
        val g = mr(7, projectId = 3)
        val h = mr(8, updatedAt = FLOOR) // `updated_after` may be exclusive upstream: an MR at the floor is a candidate, and kept
        seed(connId, a, b, c, d, e, f, g, h)
        store.settleMrDetails(connId, c.mrId, listOf(note(1), note(2)), now = 1_500, MrDetailsOutcome())
        store.settleMrDetails(connId, b.mrId, listOf(note(3)), now = 1_500, MrDetailsOutcome())
        val client = FakeClient().apply {
            // `apps` (group 9) is NOT_FOUND for the token: its project's merge requests are not candidates this pass.
            listing(mapOf(PLATFORM_GROUP to listOf(a)))
            projectOf = { detail(it) }
            mergeRequestOf = { _, iid ->
                when (iid) {
                    2L -> mr(2, title = "Renamed upstream")
                    3L -> throw notFound()
                    4L -> throw failure("UPSTREAM_UNAVAILABLE")
                    8L -> h
                    else -> error("merge request $iid must never be probed")
                }
            }
        }
        val progress = reconcile(client, connId)
        assertEquals(setOf(1L to 2L, 1L to 3L, 1L to 4L, 1L to 8L), client.mrProbes.toSet(), "E, F, G are never probed")
        assertEquals(listOf(1L, 1L), client.projectProbes, "the pre-probe, and the post-probe because one verdict was a 404")

        val kept = row(connId, b.mrId)
        assertNull(kept[MergeRequests.deletedAt])
        assertEquals("Renamed upstream", kept[MergeRequests.title])
        assertTrue(kept[MergeRequests.changedAt] > kept[MergeRequests.detailsSyncedAt]!!, "a changed body makes the details stale")
        assertEquals(2L, progress["mrReconcileKept"], "B (refreshed) and H (at the floor)")

        val gone = row(connId, c.mrId)
        assertNotNull(gone[MergeRequests.deletedAt])
        assertTrue(gone[MergeRequests.needsProcessing])
        assertEquals(2, store.mrEventRows(connId, c.mrId).size, "events stay")
        assertEquals(1L, progress["mergeRequestsDeleted"])

        val unconfirmed = row(connId, d.mrId)
        assertNull(unconfirmed[MergeRequests.deletedAt])
        assertTrue(unconfirmed[MergeRequests.lastSeenAt] > SEEDED_AT, "touched")
        assertEquals(1L, progress["mrReconcileUnconfirmed"])
        for (untouched in listOf(e, f, g)) {
            val r = row(connId, untouched.mrId)
            assertNull(r[MergeRequests.deletedAt], "mr ${untouched.iid}")
            assertEquals(SEEDED_AT, r[MergeRequests.lastSeenAt], "mr ${untouched.iid}")
        }
        assertNull(cursorOf(connId), "the pass ended")
        assertEquals(1L, store.counts(connId).mergeRequestsDeleted)
    }

    @Test
    fun `a listing that fails otherwise propagates before any sweep`() = runBlocking<Unit> {
        val connId = connection()
        seed(connId, mr(1))
        val client = FakeClient().apply {
            groupMergeRequestsOf = { _, _, _ -> throw failure("UPSTREAM_UNAVAILABLE") }
            projectOf = { detail(it) }
            mergeRequestOf = { _, _ -> throw notFound() }
        }
        assertFailsWith<FetchException> { reconcile(client, connId) }
        assertTrue(client.mrProbes.isEmpty() && client.projectProbes.isEmpty(), "no candidate list without a complete listing")
        assertNull(row(connId, mr(1).mrId)[MergeRequests.deletedAt])
        assertNull(cursorOf(connId)?.reconcile?.takeIf { it.listingDone })
    }

    @Test
    fun `a group the token cannot see makes no candidates, and a moved-out project's merge requests are left alone`() = runBlocking<Unit> {
        val connId = connection(moved2 = true)
        seed(connId, mr(1, projectId = 3), mr(2, projectId = 2))
        val client = FakeClient().apply { listing(mapOf(PLATFORM_GROUP to emptyList())) } // group 9 is NOT_FOUND
        val progress = reconcile(client, connId)
        assertEquals(1L, progress["mrGroupsNotFound"])
        assertTrue(client.mrProbes.isEmpty() && client.projectProbes.isEmpty(), "nothing is probed, so nothing can be tombstoned")
        assertNull(progress["mergeRequestsDeleted"])
        assertTrue(store.mergeRequestRows(connId).all { it[MergeRequests.deletedAt] == null && it[MergeRequests.lastSeenAt] == SEEDED_AT })
    }

    // --- 4. the project probes -----------------------------------------------------------------------------------------------

    @Test
    fun `a project that does not answer makes every candidate unconfirmed - before the batch, after it, or by failing`() =
        runBlocking<Unit> {
        // The pre-probe is a 404: no merge-request probe at all.
        val pre = connection()
        seed(pre, mr(1), mr(2))
        val preClient = FakeClient().apply {
            listing(mapOf(PLATFORM_GROUP to emptyList(), APPS_GROUP to emptyList()))
            projectOf = { throw notFound() }
        }
        val preProgress = reconcile(preClient, pre)
        assertTrue(preClient.mrProbes.isEmpty())
        assertEquals(2L, preProgress["mrReconcileUnconfirmed"])
        assertNull(preProgress["mergeRequestsDeleted"])
        assertTrue(store.mergeRequestRows(pre).all { it[MergeRequests.deletedAt] == null && it[MergeRequests.lastSeenAt] > SEEDED_AT })

        // The pre-probe is a 200, the merge request 404s, the post-probe is a 404 (the project was lost mid-batch): unconfirmed.
        val mid = connection()
        seed(mid, mr(1))
        var asked = 0
        val midClient = FakeClient().apply {
            listing(mapOf(PLATFORM_GROUP to emptyList(), APPS_GROUP to emptyList()))
            projectOf = { if (++asked == 1) detail(it) else throw notFound() }
            mergeRequestOf = { _, _ -> throw notFound() }
        }
        val midProgress = reconcile(midClient, mid)
        assertEquals(2, midClient.projectProbes.size)
        assertEquals(1L, midProgress["mrReconcileUnconfirmed"])
        assertNull(midProgress["mergeRequestsDeleted"])
        assertNull(row(mid, mr(1).mrId)[MergeRequests.deletedAt])
    }

    @Test
    fun `a project probe that fails otherwise propagates with the listing done, and the next run only finishes the sweep`() =
        runBlocking<Unit> {
        val connId = connection()
        seed(connId, mr(1))
        val client = FakeClient().apply {
            listing(mapOf(PLATFORM_GROUP to emptyList(), APPS_GROUP to emptyList()))
            projectOf = { throw failure("RATE_LIMITED") }
        }
        assertFailsWith<FetchException> { reconcile(client, connId) }
        val saved = assertNotNull(cursorOf(connId))
        assertTrue(assertNotNull(saved.reconcile).listingDone)
        assertNull(saved.pass)
        assertEquals(listOf(PLATFORM_GROUP to 1, APPS_GROUP to 1), client.listedMrs.map { it.first to it.third })

        client.listedMrs.clear()
        client.projectOf = { detail(it) }
        client.mergeRequestOf = { _, iid -> mr(iid) }
        val progress = reconcile(client, connId)
        assertTrue(client.listedMrs.isEmpty(), "the listing is skipped")
        assertEquals(1L, progress["mrReconcileKept"])
        assertNull(cursorOf(connId))
    }

    // --- 5. the cap ------------------------------------------------------------------------------------------------------------

    @Test
    fun `at most the cap of candidates is probed per run and the next pass takes the rest`() = runBlocking<Unit> {
        val connId = connection()
        val total = MAX_MR_CONFIRMATIONS + 5
        store.upsertMergeRequests(connId, (1..total).map { mr(it.toLong()) }, now = SEEDED_AT)
        val client = FakeClient().apply {
            listing(mapOf(PLATFORM_GROUP to emptyList(), APPS_GROUP to emptyList()))
            projectOf = { detail(it) }
            mergeRequestOf = { _, _ -> throw notFound() }
        }
        val first = reconcile(client, connId)
        assertEquals(MAX_MR_CONFIRMATIONS, client.mrProbes.size)
        assertNotNull(first["mrReconcileDeferred"])
        assertEquals(MAX_MR_CONFIRMATIONS.toLong(), first["mergeRequestsDeleted"])

        client.mrProbes.clear()
        val second = reconcile(client, connId)
        assertEquals(5, client.mrProbes.size, "the five that were deferred (the others are tombstoned)")
        assertNull(second["mrReconcileDeferred"])
        assertEquals(total.toLong(), store.counts(connId).mergeRequestsDeleted)
    }

    // --- 6. the stats re-ask -------------------------------------------------------------------------------------------------

    private suspend fun settledGaps(connId: UInt, vararg mrs: RawGitLabMergeRequest) {
        seed(connId, *mrs)
        mrs.forEach { store.settleMrDetails(connId, it.mrId, emptyList(), now = 2_000, MrDetailsOutcome()) }
    }

    private fun answered(vararg iids: Long) = GitLabDiffStatsAnswer(iids.associateWith { GitLabDiffStats(5, 2, 3) }, 0, false)

    @Test
    fun `the stats re-ask fills what GitLab answers per project, leaves the rest a gap and skips pending and errored rows`() =
        runBlocking<Unit> {
        val connId = connection()
        settledGaps(connId, mr(1), mr(2), mr(3, projectId = 2))
        seed(connId, mr(4))
        settledGaps(connId, mr(5))
        store.settleMrDetails(connId, mr(5).mrId, emptyList(), now = 3_000, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND))
        val asked = mutableListOf<Pair<String, List<Long>>>()
        val client = FakeClient().apply {
            diffStatsOf = { path, iids ->
                asked += path to iids
                if (path == "platform/p1") GitLabDiffStatsAnswer(answered(1).stats, 1, false, "INTERNAL_SERVER_ERROR") else answered(3)
            }
        }
        val context = GitLabFixtures.freshContext(connId)
        GitLabMrReconcileRefresh(client, store, onDemand = false).run(context)
        assertEquals(setOf("platform/p1", "platform/p2"), asked.map { it.first }.toSet(), "one call per project with gaps")
        assertEquals(2, asked.size)
        val firstProject = asked.first { it.first == "platform/p1" }.second.toSet()
        assertEquals(setOf(1L, 2L), firstProject, "the pending and the errored rows are not asked")
        assertEquals(2L, context.progressSnapshot()["mrStatsRefilled"])
        assertEquals(5, row(connId, mr(1).mrId)[MergeRequests.additions])
        assertNull(row(connId, mr(2).mrId)[MergeRequests.additions], "the still-null one stays a gap")
        assertEquals(2_000L, row(connId, mr(1).mrId)[MergeRequests.detailsSyncedAt], "the stamp is untouched")
        assertTrue(row(connId, mr(1).mrId)[MergeRequests.needsProcessing])
    }

    @Test
    fun `an outage ends the stats pass after one call, a gap code moves to the next project`() = runBlocking<Unit> {
        val connId = connection()
        settledGaps(connId, mr(1), mr(3, projectId = 2))
        val outage = FakeClient().apply { diffStatsOf = { _, _ -> throw failure("TIMEOUT") } }
        val outageContext = GitLabFixtures.freshContext(connId)
        GitLabMrReconcileRefresh(outage, store, onDemand = false).run(outageContext)
        assertEquals(1, outage.graphqlCalls.size)
        assertEquals(1L, outageContext.progressSnapshot()["mrGraphqlBreaker"])
        assertNull(outageContext.progressSnapshot()["mrStatsRefilled"])

        val gap = FakeClient().apply {
            diffStatsOf = { path, _ -> if (path == "platform/p1") throw failure("INVALID_RESPONSE") else answered(3) }
        }
        val gapContext = GitLabFixtures.freshContext(connId)
        GitLabMrReconcileRefresh(gap, store, onDemand = false).run(gapContext)
        assertEquals(2, gap.graphqlCalls.size)
        assertEquals(1L, gapContext.progressSnapshot()["mrStatsRefilled"])

        val rejected = FakeClient().apply { diffStatsOf = { _, _ -> throw failure("RATE_LIMITED") } }
        val rejectedContext = GitLabFixtures.freshContext(connId)
        assertFailsWith<FetchException> { GitLabMrReconcileRefresh(rejected, store, onDemand = false).run(rejectedContext) }
    }

    @Test
    fun `a project with more gaps than one GraphQL batch is asked in batches`() = runBlocking<Unit> {
        val connId = connection()
        val many = (1..MR_DETAILS_BATCH + 3).map { mr(it.toLong()) }.toTypedArray()
        settledGaps(connId, *many)
        val client = FakeClient().apply { diffStatsOf = { _, iids -> answered(*iids.toLongArray()) } }
        val context = GitLabFixtures.freshContext(connId)
        GitLabMrReconcileRefresh(client, store, onDemand = false).run(context)
        assertEquals(listOf(MR_DETAILS_BATCH, 3), client.graphqlCalls.map { it.second.size })
        assertEquals((MR_DETAILS_BATCH + 3).toLong(), context.progressSnapshot()["mrStatsRefilled"])
    }

    // --- 7. the details retries (U2) -----------------------------------------------------------------------------------------

    @Test
    fun `the retries un-stamp the ten stalest failed reads, and the oversized one only on demand`() = runBlocking<Unit> {
        val connId = connection()
        seed(connId, *(1..14).map { mr(it.toLong()) }.toTypedArray())
        for (n in 1L..12L) {
            store.settleMrDetails(connId, mr(n).mrId, emptyList(), now = 2_000 + n, MrDetailsOutcome(error = DETAILS_ERROR_FETCH_FAILED))
        }
        store.settleMrDetails(connId, mr(13).mrId, emptyList(), now = 2_013, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND))
        store.settleMrDetails(connId, mr(14).mrId, emptyList(), now = 2_001, MrDetailsOutcome(error = DETAILS_ERROR_LIMIT_EXCEEDED))
        val client = FakeClient()

        val scheduled = GitLabFixtures.freshContext(connId)
        GitLabMrReconcileRefresh(client, store, onDemand = false).run(scheduled)
        assertEquals(RECONCILE_DETAILS_RETRIES.toLong(), scheduled.progressSnapshot()["mrDetailsRetried"])
        assertEquals(RECONCILE_DETAILS_RETRIES, store.staleMergeRequests(connId, 100).size, "they re-enter the details backlog")
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, row(connId, mr(14).mrId)[MergeRequests.detailsError], "not on a scheduled run")
        val stillStamped = store.mergeRequestRows(connId).filter { it[MergeRequests.detailsError] != null }.map { it[MergeRequests.iid] }
        assertEquals(setOf(11L, 12L, 13L, 14L), stillStamped.toSet(), "the three freshest stamps and the oversized one")
        assertEquals(0, client.graphqlCalls.size + client.restCalls, "RECONCILE never reads details itself")

        val onDemand = GitLabFixtures.freshContext(connId)
        GitLabMrReconcileRefresh(client, store, onDemand = true).run(onDemand)
        assertEquals(4L, onDemand.progressSnapshot()["mrDetailsRetried"], "the three left and the oversized one")
        assertEquals(14, store.staleMergeRequests(connId, 100).size)
    }

    // --- 8. durable-change accounting ------------------------------------------------------------------------------------------

    private fun progressed(context: StreamContext) = context.ceilingTrip("LIMIT_EXCEEDED", null).madeProgress

    @Test
    fun `pages of unchanged rows and probes that keep nothing are no progress, a kept batch is`() = runBlocking<Unit> {
        val idle = connection()
        seed(idle, mr(1))
        val idleClient = FakeClient().apply { listing(mapOf(PLATFORM_GROUP to listOf(mr(1)), APPS_GROUP to emptyList())) }
        val idleContext = GitLabFixtures.freshContext(idle)
        reconcile(idleClient, idle, idleContext)
        assertFalse(progressed(idleContext), "unchanged pages, no candidate: a daily no-op never reads as progress")

        val unconfirmed = connection()
        seed(unconfirmed, mr(1))
        val unconfirmedClient = FakeClient().apply {
            listing(mapOf(PLATFORM_GROUP to emptyList(), APPS_GROUP to emptyList()))
            projectOf = { detail(it) }
            mergeRequestOf = { _, _ -> throw failure("UPSTREAM_UNAVAILABLE") }
        }
        val unconfirmedContext = GitLabFixtures.freshContext(unconfirmed)
        reconcile(unconfirmedClient, unconfirmed, unconfirmedContext)
        assertFalse(progressed(unconfirmedContext), "probes that wrote nothing durable are no progress")

        val kept = connection()
        seed(kept, mr(1))
        val keptClient = FakeClient().apply {
            listing(mapOf(PLATFORM_GROUP to emptyList(), APPS_GROUP to emptyList()))
            projectOf = { detail(it) }
            mergeRequestOf = { _, iid -> mr(iid) }
        }
        val keptContext = GitLabFixtures.freshContext(kept)
        reconcile(keptClient, kept, keptContext)
        assertTrue(progressed(keptContext), "the kept batch shrank the candidate set: progress")
    }
}
