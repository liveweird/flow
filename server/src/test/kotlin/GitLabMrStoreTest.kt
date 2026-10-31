package ch.nokillswit

import ch.nokillswit.gitlab.DETAILS_ERROR_FETCH_FAILED
import ch.nokillswit.gitlab.DETAILS_ERROR_LIMIT_EXCEEDED
import ch.nokillswit.gitlab.DETAILS_ERROR_NOT_FOUND
import ch.nokillswit.gitlab.GITLAB_PURGE_MRS_BATCH_SIZE
import ch.nokillswit.gitlab.GitLabDiffStats
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabRawStore.MrEvents
import ch.nokillswit.gitlab.MR_DETAILS_BACKLOG
import ch.nokillswit.gitlab.MrDetailsOutcome
import ch.nokillswit.gitlab.MrEventSource
import ch.nokillswit.gitlab.RawGitLabMergeRequest
import ch.nokillswit.gitlab.RawGitLabMrEvent
import ch.nokillswit.gitlab.RawGitLabProject
import ch.nokillswit.gitlab.cutText
import ch.nokillswit.gitlab.purgeAll
import ch.nokillswit.ingest.RawUpsertOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `gitlab/GitLabMr*` (V43): the upserts, the events replacement, the details stamp, the backlog reads, PURGE and the privacy whitelist. */
class GitLabMrStoreTest {
    private val connections = mutableListOf<UInt>()
    private val store: GitLabRawStore = GitLabFixtures.rawStore()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { store.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private suspend fun connection(): UInt = GitLabFixtures.createConnection().also { connections += it }

    private fun project(id: Long, group: String = "platform", groupId: Long = 7) = RawGitLabProject(
        id, "$group/p$id", group, groupId, "main", false,
        Json.parseToJsonElement("""{"id":$id,"path":"p$id","path_with_namespace":"$group/p$id","name":"p$id"}"""),
    )

    private fun mr(
        n: Long,
        projectId: Long = 1,
        updatedAt: Long = n * 1_000,
        title: String = "Merge request $n",
        state: String = "opened",
        headSha: String? = null,
        reviewers: List<Long> = listOf(9, 3, 3),
    ) = RawGitLabMergeRequest(
        mrId = 1_000 + n, projectId = projectId, iid = n, state = state, draft = false, authorId = 5, mergeUserId = null,
        createdAt = 100, updatedAt = updatedAt, mergedAt = null, closedAt = null, sourceBranch = "feature/$n", targetBranch = "main",
        title = title, headSha = headSha, mergeCommitSha = null, squashCommitSha = null, reviewerIds = reviewers, assigneeIds = listOf(5),
        userNotesCount = 2,
        payload = buildJsonObject {
            put("id", 1_000 + n)
            put("iid", n)
            put("title", title)
            put("state", state)
            put("updated_at", updatedAt)
        },
    )

    private fun note(id: Long, action: String = "COMMENT", targets: List<String> = emptyList(), system: Boolean = false) = RawGitLabMrEvent(
        MrEventSource.NOTE, id, actorId = 5, eventAt = id * 10, system = system, noteType = if (system) null else "DiscussionNote",
        action = action, targets = targets,
    )

    private fun approval(userId: Long) = RawGitLabMrEvent(MrEventSource.APPROVAL, userId, userId, null, false, null, "APPROVAL")

    private suspend fun row(connId: UInt, n: Long) = store.mergeRequestRows(connId).single { it[MergeRequests.iid] == n }

    private suspend fun markProcessed(connId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            MergeRequests.update({ MergeRequests.connectionId eq connId }) { it[needsProcessing] = false }
        }
    }

    /** One settle at [now] (default after every upsert's clock): the events and the stamp of one merge request. */
    private suspend fun settle(
        connId: UInt,
        mrId: Long,
        outcome: MrDetailsOutcome = MrDetailsOutcome(),
        now: Long = 2_000,
        events: List<RawGitLabMrEvent> = emptyList(),
    ): Boolean = store.settleMrDetails(connId, mrId, events, now, outcome)

    private fun exec(sql: String) {
        PostgresTestSupport.connect().use { c -> c.createStatement().use { it.executeUpdate(sql) } }
    }

    /** Tombstones a merge request the way RECONCILE (PR 9) will: `deleted_at` set, nothing else. */
    private fun tombstone(connId: UInt, iid: Long) {
        PostgresTestSupport.connect().use { c ->
            val sql = "UPDATE raw.gitlab_merge_requests SET deleted_at = 1500 WHERE iid = $iid AND connection_id = $connId"
            c.createStatement().use { it.executeUpdate(sql) }
        }
    }

    @Test
    fun `a merge request is inserted, unchanged with only last_seen_at moved, then changed with changed_at moved`() = runBlocking<Unit> {
        val connId = connection()
        val inserted = store.upsertMergeRequests(connId, listOf(mr(1), mr(2)), now = 10_000)
        assertEquals(listOf(RawUpsertOutcome.INSERTED, RawUpsertOutcome.INSERTED), inserted)
        val first = row(connId, 1)
        assertEquals(10_000L, first[MergeRequests.changedAt])
        assertEquals(10_000L, first[MergeRequests.firstSeenAt])
        assertTrue(first[MergeRequests.needsProcessing])
        assertNull(first[MergeRequests.detailsSyncedAt])
        assertEquals("[3, 9]", first[MergeRequests.reviewerIds], "sorted, distinct, canonical")

        assertEquals(listOf(RawUpsertOutcome.UNCHANGED), store.upsertMergeRequests(connId, listOf(mr(1)), now = 20_000))
        val unchanged = row(connId, 1)
        assertEquals(20_000L, unchanged[MergeRequests.lastSeenAt])
        assertEquals(10_000L, unchanged[MergeRequests.changedAt])

        markProcessed(connId)
        assertEquals(
            listOf(RawUpsertOutcome.CHANGED),
            store.upsertMergeRequests(connId, listOf(mr(1, updatedAt = 30_000, state = "merged")), now = 30_000),
        )
        val changed = row(connId, 1)
        assertEquals(30_000L, changed[MergeRequests.changedAt])
        assertEquals(30_000L, changed[MergeRequests.updatedAt])
        assertEquals("merged", changed[MergeRequests.state])
        assertTrue(changed[MergeRequests.needsProcessing])
        assertEquals(10_000L, changed[MergeRequests.firstSeenAt])
    }

    @Test
    fun `a tombstoned merge request seen again is resurrected, and a NOT_FOUND stamp clears only on a change`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertMergeRequests(connId, listOf(mr(1), mr(2), mr(3)), now = 1_000)
        for (n in 1L..3L) settle(connId, 1_000 + n, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND))
        settle(connId, 1_003, MrDetailsOutcome(error = DETAILS_ERROR_LIMIT_EXCEEDED), now = 2_000)
        tombstone(connId, iid = 1)

        // Unchanged live row keeps its NOT_FOUND stamp: the listing says nothing new about it.
        assertEquals(listOf(RawUpsertOutcome.UNCHANGED), store.upsertMergeRequests(connId, listOf(mr(2)), now = 3_000))
        assertEquals(DETAILS_ERROR_NOT_FOUND, row(connId, 2)[MergeRequests.detailsError])

        assertEquals(listOf(RawUpsertOutcome.RESURRECTED), store.upsertMergeRequests(connId, listOf(mr(1)), now = 3_000))
        val back = row(connId, 1)
        assertNull(back[MergeRequests.deletedAt])
        assertNull(back[MergeRequests.detailsError], "seeing it again disproves gone")
        assertEquals(3_000L, back[MergeRequests.changedAt])

        store.upsertMergeRequests(connId, listOf(mr(3, updatedAt = 9_999)), now = 4_000)
        val stamp = row(connId, 3)[MergeRequests.detailsError]
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, stamp, "a LIMIT_EXCEEDED stamp stays until the next read")
    }

    @Test
    fun `a page repeating an id is judged by its first occurrence, and text is NUL-replaced and cut on code points`() = runBlocking<Unit> {
        val connId = connection()
        val emoji = "😀"
        val long = mr(2, title = emoji.repeat(300) + "\u0000")
        val outcomes = store.upsertMergeRequests(connId, listOf(mr(1), mr(1), long), now = 1_000)
        assertEquals(listOf(RawUpsertOutcome.INSERTED, RawUpsertOutcome.UNCHANGED, RawUpsertOutcome.INSERTED), outcomes)
        assertEquals(emoji.repeat(255), row(connId, 2)[MergeRequests.title], "255 code points, no half a surrogate pair")
        assertEquals("a\uFFFDb", cutText("a\u0000b", 10), "NUL is replaced, not dropped")
        assertFailsWith<IllegalArgumentException> { cutText("x", -1) }
        assertEquals(emoji.repeat(2), cutText(emoji.repeat(5), 2))
        assertEquals(emoji, cutText(emoji.repeat(5), 5, maxBytes = 7), "a byte cap stops on a code-point boundary")
    }

    @Test
    fun `only a full commit id is kept`() = runBlocking<Unit> {
        val connId = connection()
        val sha = "ab".repeat(20)
        store.upsertMergeRequests(connId, listOf(mr(1, headSha = sha), mr(2, headSha = "not-a-sha")), now = 1_000)
        assertEquals(sha, row(connId, 1)[MergeRequests.headSha])
        assertNull(row(connId, 2)[MergeRequests.headSha])
    }

    @Test
    fun `replacing the events removes the old rows, ignores a duplicate and keeps targets for review requests only`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertMergeRequests(connId, listOf(mr(1), mr(2)), now = 1_000)
        val review = note(7, "REVIEW_REQUESTED", targets = listOf("alice", "bob", "alice"), system = true)
        val reviewer = RawGitLabMrEvent(MrEventSource.REVIEWER, 3, 3, 40, false, null, "REVIEWED")
        settle(connId, 1_001, events = listOf(note(5), note(5), review, approval(3), reviewer))
        settle(connId, 1_002, events = listOf(note(5)))
        val rows = store.mrEventRows(connId, 1_001)
        assertEquals(4, rows.size, "a note listed twice is one row; APPROVAL and REVIEWER rows of the same user id coexist")
        assertEquals("[\"alice\", \"bob\"]", rows.single { it[MrEvents.sourceId] == 7L }[MrEvents.targets])
        assertNull(rows.single { it[MrEvents.sourceId] == 5L }[MrEvents.targets])
        assertEquals(1, rows.count { it[MrEvents.eventSource] == "APPROVAL" })
        assertNull(rows.single { it[MrEvents.eventSource] == "APPROVAL" }[MrEvents.eventAt])

        settle(connId, 1_001, events = listOf(note(6, "MERGED", system = true)))
        assertEquals(listOf(6L), store.mrEventRows(connId, 1_001).map { it[MrEvents.sourceId] }, "the set is replaced, not merged")
        assertEquals(1, store.mrEventRows(connId, 1_002).size, "another merge request is untouched")
        assertEquals(2L, store.mrCounts(connId).mrEvents, "every merge request's events are counted")
    }

    @Test
    fun `targets are capped and the event types refuse impossible combinations`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertMergeRequests(connId, listOf(mr(1)), now = 1_000)
        val many = (1..30).map { "user$it" }
        settle(connId, 1_001, events = listOf(note(1, "REVIEW_REQUEST_REMOVED", targets = many + "x".repeat(300), system = true)))
        val stored = Json.parseToJsonElement(store.mrEventRows(connId, 1_001).single()[MrEvents.targets]!!).toString()
        assertEquals(20, Regex("user").findAll(stored).count())
        assertFailsWith<IllegalArgumentException> { note(2, "COMMENT", targets = listOf("alice")) }
        assertFailsWith<IllegalArgumentException> {
            RawGitLabMrEvent(MrEventSource.REVIEWER, 1, 1, 1, false, null, "REVIEW_REQUESTED", listOf("a"))
        }
        assertFailsWith<IllegalArgumentException> { MrDetailsOutcome(error = "BOOM") }
        assertFailsWith<IllegalArgumentException> { MrDetailsOutcome(stats = GitLabDiffStats(1, 1, 1), error = DETAILS_ERROR_NOT_FOUND) }
        assertFailsWith<IllegalArgumentException> { MrDetailsOutcome(notesCapped = true, error = DETAILS_ERROR_LIMIT_EXCEEDED) }
        assertFailsWith<IllegalArgumentException> { GitLabDiffStats(-1, 0, 0) }
    }

    @Test
    fun `settling writes stats, records a gap, and leaves stats and events alone on a terminal error`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        store.upsertMergeRequests(connId, listOf(mr(1), mr(2), mr(3)), now = 1_000)
        assertEquals(3, store.countStaleMergeRequests(connId))

        settle(connId, 1_001, MrDetailsOutcome(notesCapped = true, stats = GitLabDiffStats(10, 2, 3)), now = 2_000)
        settle(connId, 1_002, MrDetailsOutcome(stats = null), now = 2_000)
        settle(connId, 1_003, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND), now = 2_000)
        val withStats = row(connId, 1)
        val stats = listOf(withStats[MergeRequests.additions], withStats[MergeRequests.deletions], withStats[MergeRequests.fileCount])
        assertEquals(listOf(10, 2, 3), stats)
        assertTrue(withStats[MergeRequests.notesCapped])
        assertNull(withStats[MergeRequests.detailsError])
        assertEquals(2_000L, withStats[MergeRequests.detailsSyncedAt])
        assertNull(row(connId, 2)[MergeRequests.additions])
        assertEquals(DETAILS_ERROR_NOT_FOUND, row(connId, 3)[MergeRequests.detailsError])
        assertEquals(0, store.countStaleMergeRequests(connId), "every stamp leaves the backlog, an error included")
        val counts = store.mrCounts(connId)
        assertEquals(1L, counts.mrDiffStatsMissing, "the gap only: the NOT_FOUND one is a terminal state of its own")
        assertEquals(0L, counts.mrDetailsPending)

        // A later terminal error keeps the stats a complete read wrote.
        settle(connId, 1_001, MrDetailsOutcome(error = DETAILS_ERROR_LIMIT_EXCEEDED), now = 3_000)
        val kept = row(connId, 1)
        assertEquals(10, kept[MergeRequests.additions])
        assertTrue(kept[MergeRequests.notesCapped])
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, kept[MergeRequests.detailsError])
        // A change makes the details stale again.
        store.upsertMergeRequests(connId, listOf(mr(1, updatedAt = 99_000)), now = 4_000)
        assertEquals(1, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `a terminal error writes no events and keeps the old ones, and a missing row settles nothing`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertMergeRequests(connId, listOf(mr(1)), now = 1_000)
        assertTrue(settle(connId, 1_001, events = listOf(note(1), note(2))))
        assertTrue(settle(connId, 1_001, MrDetailsOutcome(error = DETAILS_ERROR_LIMIT_EXCEEDED), now = 3_000))
        assertEquals(listOf(1L, 2L), store.mrEventRows(connId, 1_001).map { it[MrEvents.sourceId] }, "the old events stay")
        assertFailsWith<IllegalArgumentException> {
            settle(connId, 1_001, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND), events = listOf(note(3)))
        }
        assertEquals(false, settle(connId, 9_999, events = listOf(note(1))), "a row that is gone (purged) settles nothing")
        assertTrue(store.mrEventRows(connId, 9_999).isEmpty())
    }

    @Test
    fun `a failing stamp rolls the replaced events back`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertMergeRequests(connId, listOf(mr(1)), now = 1_000)
        settle(connId, 1_001, MrDetailsOutcome(stats = GitLabDiffStats(1, 1, 1)), events = listOf(note(1), note(2)))
        exec("ALTER TABLE raw.gitlab_merge_requests ADD CONSTRAINT chk_test_file_count CHECK (file_count IS NULL OR file_count < 100)")
        try {
            val tooMany = MrDetailsOutcome(stats = GitLabDiffStats(1, 1, 500))
            assertFailsWith<Exception> { settle(connId, 1_001, tooMany, now = 3_000, events = listOf(note(7))) }
        } finally {
            exec("ALTER TABLE raw.gitlab_merge_requests DROP CONSTRAINT chk_test_file_count")
        }
        assertEquals(listOf(1L, 2L), store.mrEventRows(connId, 1_001).map { it[MrEvents.sourceId] }, "the events were not replaced")
        assertEquals(1, row(connId, 1)[MergeRequests.fileCount], "nor the stamp")
        assertEquals(2_000L, row(connId, 1)[MergeRequests.detailsSyncedAt])
    }

    @Test
    fun `the stamp never trails changed_at, so a constant or lagging clock leaves the backlog`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        store.upsertMergeRequests(connId, listOf(mr(1), mr(2)), now = 5_000)
        settle(connId, 1_001, now = 5_000)
        assertEquals(5_000L, row(connId, 1)[MergeRequests.detailsSyncedAt], "the same millisecond as the change")
        settle(connId, 1_002, now = 1_000)
        assertEquals(5_000L, row(connId, 2)[MergeRequests.detailsSyncedAt], "a clock behind the change")
        assertEquals(0, store.countStaleMergeRequests(connId))
        // A real later change is stale again, and the same constant clock settles that too.
        store.upsertMergeRequests(connId, listOf(mr(1, updatedAt = 99_000)), now = 5_000)
        assertEquals(0, store.countStaleMergeRequests(connId), "a change in the same millisecond as the stamp is not told apart: accepted")
        store.upsertMergeRequests(connId, listOf(mr(1, updatedAt = 99_001)), now = 6_000)
        assertEquals(1, store.countStaleMergeRequests(connId))
        settle(connId, 1_001, now = 6_000)
        assertEquals(0, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `a changed merge request is pending, not a missing-stats gap, and loses its NOT_FOUND stamp`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        store.upsertMergeRequests(connId, listOf(mr(1), mr(2)), now = 1_000)
        settle(connId, 1_001, MrDetailsOutcome(stats = null))
        settle(connId, 1_002, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND))
        assertEquals(1L, store.mrCounts(connId).mrDiffStatsMissing)
        store.upsertMergeRequests(connId, listOf(mr(1, updatedAt = 50_000), mr(2, updatedAt = 50_000)), now = 3_000)
        val counts = store.mrCounts(connId)
        assertEquals(2L, counts.mrDetailsPending)
        assertEquals(0L, counts.mrDiffStatsMissing, "pending rows are not counted as gaps")
        assertNull(row(connId, 2)[MergeRequests.detailsError], "a plain CHANGED clears NOT_FOUND")
        assertEquals(2_000L, row(connId, 2)[MergeRequests.detailsSyncedAt], "the old stamp stays, older than changed_at")
        settle(connId, 1_001, MrDetailsOutcome(stats = null), now = 4_000)
        assertEquals(1L, store.mrCounts(connId).mrDiffStatsMissing)
    }

    @Test
    fun `usernames are cut first, then empties dropped and the rest made distinct`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertMergeRequests(connId, listOf(mr(1)), now = 1_000)
        val targets = listOf("", "x".repeat(300), "x".repeat(301), "bob")
        settle(connId, 1_001, events = listOf(note(1, "REVIEW_REQUESTED", targets = targets, system = true)))
        val stored = Json.parseToJsonElement(store.mrEventRows(connId, 1_001).single()[MrEvents.targets]!!).toString()
        assertEquals("""["${"x".repeat(255)}","bob"]""", stored)
        settle(connId, 1_001, events = listOf(note(1, "REVIEW_REQUESTED", targets = listOf("", ""), system = true)))
        assertNull(store.mrEventRows(connId, 1_001).single()[MrEvents.targets])
    }

    @Test
    fun `the backlog is newest first, excludes stamped rows and merge requests of moved-out or missing projects`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1), project(2), project(3)), now = 1_000)
        store.upsertMergeRequests(
            connId,
            listOf(mr(1, updatedAt = 5_000), mr(2, updatedAt = 9_000), mr(3, updatedAt = 7_000), mr(4, projectId = 2, updatedAt = 8_000),
                mr(5, projectId = 3, updatedAt = 6_000), mr(6, projectId = 99, updatedAt = 10_000)),
            now = 1_000,
        )
        store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 2_000, now = 2_500)
        store.upsertProjects(connId, listOf(project(1), project(3)), now = 3_000)
        store.markProjectsMovedOutNotSeenSince(connId, passStartedAt = 3_000, now = 3_500)
        val stale = store.staleMergeRequests(connId, 10).map { it.iid }
        assertEquals(listOf(2L, 3L, 5L, 1L), stale, "project 2 moved out, project 99 is unknown; newest first")
        assertEquals(listOf(2L, 3L), store.staleMergeRequests(connId, 2).map { it.iid })
        val first = store.staleMergeRequests(connId, 1).single()
        assertEquals(listOf(1_002L, 1L, 2L), listOf(first.mrId, first.projectId, first.iid))
        settle(connId, 1_002, MrDetailsOutcome(), now = 4_000)
        assertEquals(listOf(3L, 5L, 1L), store.staleMergeRequests(connId, 10).map { it.iid })
        assertEquals(3, store.countStaleMergeRequests(connId))
        assertTrue(store.staleMergeRequests(GitLabFixtures.createConnection().also { connections += it }, 10).isEmpty(), "per connection")
    }

    @Test
    fun `the group, project and path lookups read the stored projects`() = runBlocking<Unit> {
        val connId = connection()
        assertTrue(store.configuredGroupIds(connId).isEmpty())
        store.upsertProjects(connId, listOf(project(1), project(2), project(3, group = "tools", groupId = 8)), now = 1_000)
        assertEquals(mapOf("platform" to 7L, "tools" to 8L), store.configuredGroupIds(connId))
        assertEquals(setOf(1L, 2L, 3L), store.storedProjectIds(connId))
        assertEquals(mapOf(1L to "platform/p1", 3L to "tools/p3"), store.projectPaths(connId, listOf(3, 1, 1, 77)))
        assertTrue(store.projectPaths(connId, emptyList()).isEmpty())
    }

    @Test
    fun `a path's group id comes from its live projects, the latest seen first, never from a moved-out one`() = runBlocking<Unit> {
        val connId = connection()
        // Project 1 sits under an older group id and was seen LAST, but it moved out; project 2 is live under the current id.
        store.upsertProjects(connId, listOf(project(2, groupId = 7)), now = 1_000)
        store.upsertProjects(connId, listOf(project(1, groupId = 5)), now = 5_000)
        exec("UPDATE raw.gitlab_projects SET moved_out_at = 6000 WHERE project_id = 1 AND connection_id = $connId")
        assertEquals(mapOf("platform" to 7L), store.configuredGroupIds(connId))
        // Two live rows: the most recently seen one wins, a tie goes to the greater id.
        store.upsertProjects(connId, listOf(project(3, groupId = 6)), now = 9_000)
        assertEquals(mapOf("platform" to 6L), store.configuredGroupIds(connId))
        store.upsertProjects(connId, listOf(project(4, groupId = 8)), now = 9_000)
        assertEquals(mapOf("platform" to 8L), store.configuredGroupIds(connId))
        // A path whose projects all left is absent: nothing to list for it.
        exec("UPDATE raw.gitlab_projects SET deleted_at = 1 WHERE connection_id = $connId")
        assertTrue(store.configuredGroupIds(connId).isEmpty())
    }

    @Test
    fun `the status counts are the live, merged, event, pending and missing-stats figures`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        store.upsertMergeRequests(connId, listOf(mr(1, state = "merged"), mr(2), mr(3)), now = 1_000)
        tombstone(connId, iid = 3)
        settle(connId, 1_001, MrDetailsOutcome(stats = GitLabDiffStats(1, 1, 1)), events = listOf(note(1), note(2)))
        settle(connId, 1_002)
        val counts = store.mrCounts(connId)
        val figures = listOf(
            counts.mergeRequests, counts.mergeRequestsMerged, counts.mrEvents, counts.mrDetailsPending, counts.mrDiffStatsMissing,
        )
        assertEquals(listOf(2L, 1L, 2L, 0L, 1L), figures)
    }

    @Test
    fun `tombstoning flags PROCESS on live rows only, keeps the events and is a no-op the second time`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        store.upsertMergeRequests(connId, listOf(mr(1), mr(2), mr(3)), now = 1_000)
        settle(connId, 1_001, events = listOf(note(1), approval(5)))
        markProcessed(connId)
        tombstone(connId, iid = 3)

        assertEquals(0, store.tombstoneMergeRequests(connId, emptyList(), now = 5_000))
        val changed = store.tombstoneMergeRequests(connId, listOf(1_001, 1_003, 4_242, 1_001), now = 5_000)
        assertEquals(1, changed, "the tombstoned and unknown ids change nothing")
        assertEquals(0, store.tombstoneMergeRequests(connId, listOf(1_001), now = 6_000))
        val gone = row(connId, 1)
        assertEquals(5_000L, gone[MergeRequests.deletedAt])
        assertTrue(gone[MergeRequests.needsProcessing])
        assertEquals(2, store.mrEventRows(connId, 1_001).size, "events stay until PURGE")
        assertEquals(1_500L, row(connId, 3)[MergeRequests.deletedAt], "an earlier tombstone is not rewritten")
        assertFalse(row(connId, 2)[MergeRequests.needsProcessing])
        val counts = store.mrCounts(connId)
        assertEquals(1L, counts.mergeRequests)
        assertEquals(2L, counts.mergeRequestsDeleted)
    }

    @Test
    fun `touching moves last_seen_at of live rows only`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        store.upsertMergeRequests(connId, listOf(mr(1), mr(2), mr(3)), now = 1_000)
        tombstone(connId, iid = 2)
        store.touchMergeRequests(connId, listOf(1_001, 1_002), now = 5_000)
        store.touchMergeRequests(connId, emptyList(), now = 6_000)
        assertEquals(5_000L, row(connId, 1)[MergeRequests.lastSeenAt])
        assertEquals(1_000L, row(connId, 2)[MergeRequests.lastSeenAt], "a tombstoned row is not touched")
        assertEquals(1_000L, row(connId, 3)[MergeRequests.lastSeenAt])
        assertEquals(1_000L, row(connId, 1)[MergeRequests.changedAt], "a touch is not a change")
    }

    @Test
    fun `reconcile candidates are the unseen live rows of live projects in the listed groups at or above the floor, newest first`() =
        runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1), project(2), project(3, group = "tools", groupId = 8)), now = 1_000)
        store.upsertMergeRequests(
            connId,
            listOf(
                mr(1, updatedAt = 9_000), mr(2, updatedAt = 8_000), mr(3, updatedAt = 100), mr(4, projectId = 2, updatedAt = 7_000),
                mr(5, projectId = 3, updatedAt = 6_000), mr(6, updatedAt = 9_500), mr(7, updatedAt = 7_500), mr(8, updatedAt = 5_000),
            ),
            now = 1_000,
        )
        exec("UPDATE raw.gitlab_projects SET moved_out_at = 1500 WHERE project_id = 2 AND connection_id = $connId")
        tombstone(connId, iid = 6)
        store.touchMergeRequests(connId, listOf(1_007), now = 3_000)

        suspend fun candidates(groups: Collection<String>, limit: Int = 10, passStartedAt: Long = 2_000) =
            store.mrReconcileCandidates(connId, floorMillis = 5_000, passStartedAt, groups, limit).map { it.iid }
        assertEquals(listOf(1L, 2L, 8L), candidates(listOf("platform")),
            "iid 3 is below the floor, 4 is of a moved-out project, 5 of another group, 6 is tombstoned, 7 was seen this pass")
        assertEquals(listOf(1L, 2L, 5L, 8L), candidates(listOf("platform", "tools", "platform")))
        assertEquals(listOf(1L, 2L), candidates(listOf("platform", "tools"), limit = 2))
        assertEquals(listOf(1L, 2L, 7L, 8L), candidates(listOf("platform"), passStartedAt = 3_001), "the touch is before this pass")
        assertTrue(candidates(emptyList()).isEmpty())
        assertTrue(candidates(listOf("elsewhere")).isEmpty())
        val first = store.mrReconcileCandidates(connId, 5_000, 2_000, listOf("platform"), 1).single()
        assertEquals(listOf(1_001L, 1L, 1L), listOf(first.mrId, first.projectId, first.iid))
    }

    @Test
    fun `the gapped set is the settled missing-stats rows of live projects, newest first`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1), project(2)), now = 1_000)
        store.upsertMergeRequests(
            connId, listOf(mr(1), mr(2), mr(3), mr(4, projectId = 2), mr(5), mr(6), mr(7)), now = 1_000,
        )
        exec("UPDATE raw.gitlab_projects SET moved_out_at = 1500 WHERE project_id = 2 AND connection_id = $connId")
        settle(connId, 1_001)
        settle(connId, 1_003, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND))
        settle(connId, 1_004)
        settle(connId, 1_005, MrDetailsOutcome(stats = GitLabDiffStats(1, 1, 1)))
        settle(connId, 1_006)
        settle(connId, 1_007)
        tombstone(connId, iid = 6)

        assertEquals(listOf(7L, 1L), store.gappedMergeRequests(connId, 10).map { it.iid },
            "2 is pending, 3 has an error, 4 is of a moved-out project, 5 has stats, 6 is tombstoned")
        assertEquals(listOf(7L), store.gappedMergeRequests(connId, 1).map { it.iid })
        assertEquals(3L, store.mrCounts(connId).mrDiffStatsMissing, "the status figure also counts the moved-out project's gap")
    }

    @Test
    fun `writing diff stats fills exactly the given live rows, flags PROCESS and leaves the stamp and events`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1), project(2)), now = 1_000)
        store.upsertMergeRequests(connId, listOf(mr(1), mr(2), mr(3), mr(4), mr(5, projectId = 2)), now = 1_000)
        for (n in 1L..5L) settle(connId, 1_000 + n, events = listOf(note(n)))
        tombstone(connId, iid = 4)
        markProcessed(connId)
        assertEquals(4L, store.mrCounts(connId).mrDiffStatsMissing, "the four live settled rows are gaps")

        val filled = store.writeMrDiffStats(
            connId, projectId = 1,
            statsByIid = mapOf(1L to GitLabDiffStats(5, 2, 3), 3L to GitLabDiffStats(0, 0, 0), 4L to GitLabDiffStats(1, 1, 1),
                5L to GitLabDiffStats(9, 9, 9), 99L to GitLabDiffStats(1, 1, 1)),
        )
        assertEquals(2, filled, "the tombstoned row, the other project's iid and the unknown iid are not written")
        val stats = row(connId, 1).let { listOf(it[MergeRequests.additions], it[MergeRequests.deletions], it[MergeRequests.fileCount]) }
        assertEquals(listOf<Int?>(5, 2, 3), stats)
        assertEquals(0, row(connId, 3)[MergeRequests.additions])
        assertNull(row(connId, 2)[MergeRequests.additions])
        assertNull(row(connId, 5)[MergeRequests.additions])
        assertNull(row(connId, 4)[MergeRequests.additions])
        assertTrue(row(connId, 1)[MergeRequests.needsProcessing])
        assertFalse(row(connId, 2)[MergeRequests.needsProcessing])
        assertEquals(2_000L, row(connId, 1)[MergeRequests.detailsSyncedAt], "the stamp is untouched")
        assertNull(row(connId, 1)[MergeRequests.detailsError])
        assertEquals(5, store.mrEventRows(connId).size)
        assertEquals(0, store.writeMrDiffStats(connId, 1, emptyMap()))
        assertEquals(listOf(5L, 2L), store.gappedMergeRequests(connId, 10).map { it.iid }, "what is still a gap, newest first")
    }

    @Test
    fun `retrying un-stamps the stalest rows of the given error set, bounded, and they re-enter the backlog`() = runBlocking<Unit> {
        val connId = connection()
        store.upsertProjects(connId, listOf(project(1), project(2)), now = 1_000)
        store.upsertMergeRequests(
            connId, listOf(mr(1), mr(2), mr(3), mr(4), mr(5, projectId = 2), mr(6), mr(7)), now = 1_000,
        )
        exec("UPDATE raw.gitlab_projects SET moved_out_at = 1500 WHERE project_id = 2 AND connection_id = $connId")
        settle(connId, 1_001, MrDetailsOutcome(error = DETAILS_ERROR_FETCH_FAILED), now = 2_000)
        settle(connId, 1_002, MrDetailsOutcome(error = DETAILS_ERROR_NOT_FOUND), now = 3_000)
        settle(connId, 1_003, MrDetailsOutcome(error = DETAILS_ERROR_LIMIT_EXCEEDED), now = 2_500)
        settle(connId, 1_004, MrDetailsOutcome(error = DETAILS_ERROR_FETCH_FAILED), now = 4_000)
        settle(connId, 1_005, MrDetailsOutcome(error = DETAILS_ERROR_FETCH_FAILED), now = 1_500)
        settle(connId, 1_006)
        settle(connId, 1_007, MrDetailsOutcome(error = DETAILS_ERROR_FETCH_FAILED), now = 1_200)
        tombstone(connId, iid = 7)
        assertEquals(5L, store.mrCounts(connId).mrDetailsUnread, "stamped live rows: 1-5 (a tombstoned or clean row is not counted)")
        assertTrue(store.staleMergeRequests(connId, 10).isEmpty())

        val scheduled = listOf(DETAILS_ERROR_FETCH_FAILED, DETAILS_ERROR_NOT_FOUND)
        assertEquals(2, store.retryMrDetails(connId, scheduled, limit = 2), "stalest first: 1 (2000), 2 (3000); 5 is moved out")
        assertEquals(setOf(1L, 2L), store.staleMergeRequests(connId, 10).map { it.iid }.toSet())
        for (n in 1L..2L) {
            assertNull(row(connId, n)[MergeRequests.detailsSyncedAt])
            assertNull(row(connId, n)[MergeRequests.detailsError])
        }
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, row(connId, 3)[MergeRequests.detailsError], "outside the scheduled set")
        assertEquals(3L, store.mrCounts(connId).mrDetailsUnread)
        assertEquals(1, store.retryMrDetails(connId, scheduled, limit = 10), "only 4 is left in the set")
        assertEquals(0, store.retryMrDetails(connId, scheduled, limit = 10))
        assertEquals(0, store.retryMrDetails(connId, emptyList(), limit = 10))
        assertEquals(0, store.retryMrDetails(connId, scheduled, limit = 0))
        assertEquals(1, store.retryMrDetails(connId, scheduled + DETAILS_ERROR_LIMIT_EXCEEDED, limit = 10), "on demand: oversized too")
        assertEquals(1L, store.mrCounts(connId).mrDetailsUnread, "only the moved-out project's stamped row is still unread")
    }

    @Test
    fun `PURGE drains the merge requests in batches, the events cascade and other connections are untouched`() = runBlocking<Unit> {
        val connId = connection()
        val other = connection()
        store.upsertProjects(connId, listOf(project(1)), now = 1_000)
        store.upsertMergeRequests(connId, (1L..7L).map { mr(it) }, now = 1_000)
        for (n in 1L..7L) settle(connId, 1_000 + n, events = listOf(note(n), approval(n)))
        store.upsertMergeRequests(other, listOf(mr(1)), now = 1_000)
        settle(other, 1_001, events = listOf(note(1)))

        assertEquals(3, store.purgeMergeRequestsBatch(connId, batchSize = 3))
        assertEquals(4, store.mrEventRows(connId).map { it[MrEvents.mrId] }.distinct().size, "the events of the deleted ones cascaded")
        store.purgeAll(connId, batchSize = 3)

        assertTrue(store.mergeRequestRows(connId).isEmpty())
        assertTrue(store.mrEventRows(connId).isEmpty())
        assertTrue(store.projectRows(connId).isEmpty())
        assertEquals(1, store.mergeRequestRows(other).size)
        assertEquals(1, store.mrEventRows(other).size, "another connection's events are untouched")
    }

    @Test
    fun `the default merge-request purge batch is sized by cascaded rows`() = runBlocking<Unit> {
        assertEquals(20, GITLAB_PURGE_MRS_BATCH_SIZE, "20 x ~1,100 events stays under ~25,000 cascaded rows per statement")
        val connId = connection()
        store.upsertMergeRequests(connId, (1L..25L).map { mr(it) }, now = 1_000)
        assertEquals(GITLAB_PURGE_MRS_BATCH_SIZE, store.purgeMergeRequestsBatch(connId))
        assertEquals(5, store.mergeRequestRows(connId).size)
    }

    @Test
    fun `the backlog predicate carries no bind parameter the partial index could not match`() = runBlocking<Unit> {
        val text = suspendTransaction(sharedDatabaseForTests()) { QueryBuilder(true).also { it.append(MR_DETAILS_BACKLOG) }.toString() }
        assertTrue("details_synced_at IS NULL" in text && "details_synced_at < " in text && "deleted_at IS NULL" in text, text)
        assertFalse("?" in text, "a bind parameter would defeat the partial index under a cached generic plan: $text")
    }

    @Test
    fun `the stored columns are exactly the privacy whitelist`() {
        fun columns(table: String): Set<String> = PostgresTestSupport.connect().use { c ->
            val sql = "SELECT column_name FROM information_schema.columns WHERE table_schema = 'raw' AND table_name = ?"
            c.prepareStatement(sql).use { s ->
                s.setString(1, table)
                s.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        assertEquals(
            setOf(
                "connection_id", "mr_id", "project_id", "iid", "state", "draft", "author_id", "merge_user_id", "created_at", "updated_at",
                "merged_at", "closed_at", "source_branch", "target_branch", "title", "head_sha", "merge_commit_sha", "squash_commit_sha",
                "reviewer_ids", "assignee_ids", "user_notes_count", "additions", "deletions", "file_count", "notes_capped",
                "details_synced_at", "details_error", "payload", "sha256", "first_seen_at", "last_seen_at", "changed_at", "deleted_at",
                "needs_processing", "processed_at", "processing_version",
            ),
            columns("gitlab_merge_requests"),
            "a new merge-request column needs a privacy decision: no description, labels, URLs, names or note bodies",
        )
        assertEquals(
            setOf(
                "connection_id", "mr_id", "source", "source_id", "project_id", "actor_id", "event_at", "system", "note_type", "action",
                "targets",
            ),
            columns("gitlab_mr_events"),
            "an event row never holds a note body",
        )
    }
}
