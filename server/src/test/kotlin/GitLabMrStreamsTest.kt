package ch.nokillswit

import ch.nokillswit.gitlab.GitLabClient
import ch.nokillswit.gitlab.GitLabMergeRequestsStream
import ch.nokillswit.gitlab.GitLabMrDetailsStream
import ch.nokillswit.gitlab.GitLabProjectsStream
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabRawStore.MrEvents
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.gitlab.GitLabRawStore.Users
import ch.nokillswit.gitlab.MergeRequestsCursor
import ch.nokillswit.gitlab.decodeCursor
import ch.nokillswit.gitlab.parseGitLabInstantMillis
import ch.nokillswit.gitlab.purgeAll
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.backfillFromEpochMillis
import ch.nokillswit.ingest.defaultBackfillFrom
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.ResultRow
import org.junit.BeforeClass
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val GROUPS = listOf("platform", "apps")
private val OTHER_CODES = setOf("MERGED", "CLOSED", "REOPENED", "DRAFT", "READY", "UNAPPROVAL", "REVIEW_REQUEST_REMOVED", "OTHER_SYSTEM")
private const val PRIVACY_MIN_CHARS = 20

/**
 * The MERGE REQUESTS and MR DETAILS streams against the GitLab stub, graded against `sample-data/gitlab/expected.json` (the generator's
 * ground truth, never numbers copied here): the shared [SyncedGitLabFixture] read-only, and own connections - running ONLY the streams
 * they need, never a full SYNC - for the day-2 incremental, the call budget and the stub failures (a GraphQL outage, a 404, an oversized
 * notes page).
 */
class GitLabMrStreamsTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmGitLabFixture()
    }

    private val store: GitLabRawStore = GitLabFixtures.rawStore()
    private val cursors = SyncCursorsService(sharedDatabaseForTests())
    private val connections = mutableListOf<UInt>()
    private val overrides = mutableListOf<StubMapping>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        overrides.forEach(GitLabStubServer::removeOverride)
        overrides.clear()
        GitLabStubServer.resetScenarios()
        connections.forEach { store.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private val expected: JsonObject by lazy {
        val file = listOf(File("sample-data/gitlab/expected.json"), File("../sample-data/gitlab/expected.json")).first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private val stubFiles: File by lazy { listOf(File("sample-data/gitlab-stub/__files"),
        File("../sample-data/gitlab-stub/__files")).first { it.isDirectory } }

    private fun JsonElement.at(vararg path: String): JsonElement = path.fold(this) { node, key -> node.jsonObject.getValue(key) }

    private fun JsonElement.str() = jsonPrimitive.content

    private fun JsonElement.int() = jsonPrimitive.int

    private val started: List<JsonObject> by lazy { expected.at("mergeRequests", "list").jsonArray.map { it.jsonObject } }

    private val day2Updates: List<JsonObject> by lazy { expected.at("day2", "mergeRequestUpdates").jsonArray.map { it.jsonObject } }

    /** A member that is present and not JSON null. */
    private fun JsonObject.opt(key: String): JsonElement? = get(key)?.takeIf { it !is JsonNull }

    private fun JsonObject.key() = at("projectId").long() to at("iid").long()

    private fun JsonElement.long() = jsonPrimitive.long

    private fun client(): GitLabClient = GitLabFixtures.stubClient()(GitLabStubServer.start(), "glpat-test-token")

    private suspend fun fresh(): UInt = GitLabFixtures.createConnection().also { connections += it }

    private val floor get() = backfillFromEpochMillis(defaultBackfillFrom())

    private class Progress(val listing: Map<String, Long>, val details: Map<String, Long>)

    /** PROJECTS, then the listing, then the details - the three streams a merge-request test needs, never a full SYNC. */
    private suspend fun run(connId: UInt, client: GitLabClient = client(), callsPerRun: Int = 100_000): Progress {
        GitLabFixtures.runStream(GitLabProjectsStream(client, store, GROUPS), connId)
        val listing = GitLabFixtures.runStream(GitLabMergeRequestsStream(client, store, GROUPS, floor), connId)
        return Progress(listing, GitLabFixtures.runStream(GitLabMrDetailsStream(client, store, callsPerRun), connId))
    }

    private suspend fun rows(connId: UInt) =
        store.mergeRequestRows(connId).associateBy { it[MergeRequests.projectId] to it[MergeRequests.iid] }

    private suspend fun eventsOf(connId: UInt, row: ResultRow, source: String? = null) =
        store.mrEventRows(connId, row[MergeRequests.mrId]).filter { source == null || it[MrEvents.eventSource] == source }

    private fun ids(json: String): List<Long> = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.long }

    private fun millis(text: String) = parseGitLabInstantMillis(text)

    // --- the shared SYNC, read-only ---------------------------------------------------------------------------------------------

    @Test
    fun `the synced fixture holds every merge request as the generator describes it, with its events and diff stats`() = runBlocking<Unit> {
        val connId = SyncedGitLabFixture.connectionId()
        val stored = rows(connId)
        assertEquals(expected.at("mergeRequests", "total").int(), stored.size)
        val live = stored.values.filter { it[MergeRequests.deletedAt] == null }
        for ((state, count) in expected.at("mergeRequests", "byState").jsonObject) {
            assertEquals(count.int(), live.count { it[MergeRequests.state] == state }, "state $state")
        }
        assertEquals(expected.at("mergeRequests", "drafts").int(), live.count { it[MergeRequests.draft] })
        for ((project, count) in expected.at("mergeRequests", "perProject").jsonObject) {
            assertEquals(count.int(), live.count { it[MergeRequests.projectId] == project.toLong() }, "project $project")
        }
        val gap = expected.at("mergeRequests", "nullDiffStatsGap")
        for (truth in started) {
            val row = stored.getValue(truth.key())
            val where = "${truth.key()}"
            assertEquals(truth.at("id").long(), row[MergeRequests.mrId], where)
            assertEquals(truth.at("state").str(), row[MergeRequests.state], where)
            assertEquals(truth.at("draft").jsonPrimitive.content.toBoolean(), row[MergeRequests.draft], where)
            assertEquals(truth.at("title").str(), row[MergeRequests.title], where)
            assertEquals(truth.at("authorId").long(), row[MergeRequests.authorId], where)
            assertEquals(truth.at("reviewerIds").jsonArray.map { it.long() }.sorted(), ids(row[MergeRequests.reviewerIds]), where)
            assertEquals(truth.at("assigneeIds").jsonArray.map { it.long() }.sorted(), ids(row[MergeRequests.assigneeIds]), where)
            assertEquals(truth.opt("mergedById")?.long(), row[MergeRequests.mergeUserId], where)
            assertEquals(truth.at("sha").str(), row[MergeRequests.headSha], where)
            assertEquals(truth.opt("mergeCommitSha")?.str(), row[MergeRequests.mergeCommitSha], where)
            assertEquals(truth.opt("squashCommitSha")?.str(), row[MergeRequests.squashCommitSha], where)
            assertEquals(millis(truth.at("createdAt").str()), row[MergeRequests.createdAt], where)
            assertEquals(millis(truth.at("updatedAt").str()), row[MergeRequests.updatedAt], where)
            assertEquals(truth.opt("mergedAt")?.str()?.let(::millis), row[MergeRequests.mergedAt], where)
            assertEquals(truth.opt("closedAt")?.str()?.let(::millis), row[MergeRequests.closedAt], where)
            assertNotNull(row[MergeRequests.detailsSyncedAt], where)
            assertNull(row[MergeRequests.detailsError], where)
            assertFalse(row[MergeRequests.notesCapped], where)
            val stats = truth.opt("diffStats")
            if (stats == null) {
                assertNull(row[MergeRequests.additions], "$where is the GraphQL gap")
                assertEquals(gap.at("projectId").long() to gap.at("iid").long(), truth.key())
            } else {
                assertEquals(stats.at("additions").int(), row[MergeRequests.additions], where)
                assertEquals(stats.at("deletions").int(), row[MergeRequests.deletions], where)
                assertEquals(stats.at("fileCount").int(), row[MergeRequests.fileCount], where)
            }
            assertEquals(truth.at("notes").int(), eventsOf(connId, row, "NOTE").size, "$where notes")
            assertEquals(truth.at("approvals").int(), eventsOf(connId, row, "APPROVAL").size, "$where approvals")
            val reviewers = eventsOf(connId, row, "REVIEWER").map { it[MrEvents.sourceId] to it[MrEvents.action] }.toSet()
            assertEquals(truth.at("reviewerStates").jsonArray.map { it.at("userId").long() to it.at("state").str().uppercase() }.toSet(),
                reviewers, where)
        }
        assertEquals(1L, store.mrCounts(connId).mrDiffStatsMissing)
        assertEquals(0L, store.countStaleMergeRequests(connId))
    }

    @Test
    fun `the stored events carry the generator's action totals, real usernames as targets and known actors`() = runBlocking<Unit> {
        val connId = SyncedGitLabFixture.connectionId()
        val events = store.mrEventRows(connId)
        val notes = events.filter { it[MrEvents.eventSource] == "NOTE" }
        val byAction = notes.groupingBy { it[MrEvents.action] }.eachCount()
        val truth = expected.at("events", "notesByKind").jsonObject
        for ((kind, count) in truth) {
            if (kind == "OTHER_SYSTEM") continue
            assertEquals(count.int(), byAction[kind] ?: 0, kind)
        }
        assertEquals(truth.getValue("OTHER_SYSTEM").int(), byAction.filterKeys { it in OTHER_CODES }.values.sum(), "the other system notes")
        assertEquals(expected.at("events", "noteTotal").int(), notes.size)
        assertEquals(expected.at("events", "approvalsEndpointTotal").int(), events.count { it[MrEvents.eventSource] == "APPROVAL" })
        val reviewerRows = events.filter { it[MrEvents.eventSource] == "REVIEWER" }
        assertEquals(expected.at("events", "reviewersTotal").int(), reviewerRows.size)
        for ((state, count) in expected.at("events", "reviewerStates").jsonObject) {
            assertEquals(count.int(), reviewerRows.count { it[MrEvents.action] == state.uppercase() }, state)
        }
        val users = store.userRows(connId)
        val usernames = users.map { it[Users.username] }.toSet()
        val userIds = users.map { it[Users.userId] }.toSet()
        val requests = notes.filter { it[MrEvents.action] == "REVIEW_REQUESTED" }
        assertTrue(requests.isNotEmpty())
        for (request in requests) {
            val targets = Json.parseToJsonElement(assertNotNull(request[MrEvents.targets],
                "a review request names who")).jsonArray.map { it.str() }
            assertTrue(targets.isNotEmpty() && targets.all { it in usernames }, "targets $targets are stored usernames")
        }
        val others = notes.filter { it[MrEvents.action] != "REVIEW_REQUESTED" && it[MrEvents.action] != "REVIEW_REQUEST_REMOVED" }
        assertTrue(others.all { it[MrEvents.targets] == null })
        assertTrue(notes.all { it[MrEvents.actorId] == null || it[MrEvents.actorId] in userIds }, "every note actor is a stored user")
    }

    @Test
    fun `no merge-request description and no note body reaches any stored row`() = runBlocking<Unit> {
        val connId = SyncedGitLabFixture.connectionId()
        val descriptions = listOf("group-7-merge-requests-started-page-1.json",
            "group-9-merge-requests-started-page-1.json").flatMap { name ->
            Json.parseToJsonElement(File(stubFiles,
                name).readText()).jsonArray.mapNotNull { (it.jsonObject["description"] as? JsonPrimitive)?.content }
        }
        val bodies = listOf("p1-mr-1-notes-page-1.json", "p1-mr-1-notes-page-2.json").flatMap { name ->
            Json.parseToJsonElement(File(stubFiles,
                name).readText()).jsonArray.mapNotNull { (it.jsonObject["body"] as? JsonPrimitive)?.content }
        }
        val texts = (descriptions + bodies).filter { it.length >= PRIVACY_MIN_CHARS }.distinct()
        assertTrue(descriptions.size >= 30 && bodies.size >= 100 && texts.size >= 20, "the privacy check saw ${texts.size} texts")
        val mrText = store.mergeRequestRows(connId).joinToString("\n") { it[MergeRequests.payload] + "\n" + it[MergeRequests.title] }
        val eventText = store.mrEventRows(connId).joinToString("\n") {
            "${it[MrEvents.targets]}\n${it[MrEvents.action]}\n${it[MrEvents.noteType]}"
        }
        for (text in texts) {
            assertFalse(text in mrText, "a merge request row carries stub text")
            assertFalse(text in eventText, "an event row carries stub text")
        }
    }

    // --- own connections -----------------------------------------------------------------------------------------------------

    @Test
    fun `day two - the listing inserts the new merge request and changes the updated ones, only those are re-read, the deleted stays`() =
        runBlocking<Unit> {
            val connId = fresh()
            val client = client()
            val first = run(connId, client)
            assertEquals(expected.at("mergeRequests", "total").int().toLong(), first.listing["mergeRequests"])
            val firstWatermarks = decodeCursor<MergeRequestsCursor>(assertNotNull(cursors.get(connId, "merge_requests")).cursor).watermarks
            val deletedKey = expected.at("day2", "deletedMergeRequest").let { it.at("projectId").long() to it.at("iid").long() }
            val deletedBefore = rows(connId).getValue(deletedKey)

            GitLabStubServer.setScenarioState(GitLabStubServer.SCENARIO, GitLabStubServer.DAY2)
            val second = run(connId, client)
            // Project 2 left the groups at day 2 (its merge requests are no longer listed there); 7 is new.
            val relisted = day2Updates.filter { it.at("projectId").long() in setOf(1L, 3L, 4L) }
            assertEquals(1L, second.listing["mergeRequests"])
            assertEquals(relisted.size.toLong(), second.listing["mergeRequestsChanged"])
            assertEquals(relisted.size + 1L, second.details["mrDetails"])
            assertNull(second.details["mrDetailsPending"])

            val stored = rows(connId)
            assertEquals(expected.at("mergeRequests", "total").int() + 1, stored.size)
            assertNull(stored.getValue(deletedKey)[MergeRequests.deletedAt],
                "no tombstone: a listing that omits a merge request is no evidence")
            assertEquals(deletedBefore[MergeRequests.detailsSyncedAt], stored.getValue(deletedKey)[MergeRequests.detailsSyncedAt])
            assertEquals(8, eventsOf(connId, stored.getValue(deletedKey), "NOTE").size, "its notes are still there")

            val startedNotes = started.associate { it.key() to it.at("notes").int() }
            for (update in day2Updates) {
                val key = update.key()
                val row = stored.getValue(key)
                val reread = key.first in setOf(1L, 3L, 4L, 7L)
                if (!reread) { // project 2: out of the groups, untouched
                    assertEquals(startedNotes.getValue(key), eventsOf(connId, row, "NOTE").size)
                    continue
                }
                assertEquals(update.at("newState").str(), row[MergeRequests.state], "$key")
                assertEquals(update.at("newDraft").jsonPrimitive.content.toBoolean(), row[MergeRequests.draft], "$key")
                assertEquals(millis(update.at("updatedAt").str()), row[MergeRequests.updatedAt], "$key")
                update.opt("mergeCommitSha")?.let { assertEquals(it.str(), row[MergeRequests.mergeCommitSha], "$key") }
                assertEquals((startedNotes[key] ?: 0) + update.at("newNotes").int(), eventsOf(connId, row, "NOTE").size, "$key notes")
                assertNotNull(row[MergeRequests.detailsSyncedAt])
            }
            val changes = stored.getValue(1L to 14L)
            assertTrue(eventsOf(connId, changes, "REVIEWER").any { it[MrEvents.action] == "REQUESTED_CHANGES" },
                "p1 iid 14 asked for changes at day 2")
            assertEquals(0L, store.countStaleMergeRequests(connId))

            val cursor = decodeCursor<MergeRequestsCursor>(assertNotNull(cursors.get(connId, "merge_requests")).cursor)
            assertNull(cursor.pass)
            assertEquals(setOf("platform", "apps"), cursor.watermarks.keys)
            val groupOf = store.projectRows(connId).associate { it[Projects.projectId] to it[Projects.groupPath] }
            for ((group, mark) in cursor.watermarks) {
                assertEquals(stored.values.filter { groupOf[it[MergeRequests.projectId]] == group }.maxOf { it[MergeRequests.updatedAt] },
                    mark, group)
                assertTrue(mark > firstWatermarks.getValue(group), "$group moved forward")
            }
        }

    @Test
    fun `a small call budget stops the details with merge requests pending and the next run drains them`() = runBlocking<Unit> {
        val connId = fresh()
        val client = client()
        GitLabFixtures.runStream(GitLabProjectsStream(client, store, GROUPS), connId)
        GitLabFixtures.runStream(GitLabMergeRequestsStream(client, store, GROUPS, floor), connId)
        val total = expected.at("mergeRequests", "total").int().toLong()
        val first = GitLabFixtures.runStream(GitLabMrDetailsStream(client, store, 20), connId)
        val pending = first.getValue("mrDetailsPending")
        assertTrue(pending > 0)
        assertEquals(total, first.getValue("mrDetails") + pending, "every merge request is read or pending")
        assertEquals(pending, store.countStaleMergeRequests(connId))

        val second = GitLabFixtures.runStream(GitLabMrDetailsStream(client, store, 100_000), connId)
        assertNull(second["mrDetailsPending"])
        assertEquals(0L, store.countStaleMergeRequests(connId))
        assertEquals(total, store.mrCounts(connId).mergeRequests)
    }

    @Test
    fun `a GraphQL outage stops the details with everything pending and no gap, and the next run reads them all with their stats`() =
        runBlocking<Unit> {
            val connId = fresh()
            overrides += GitLabStubServer.addOverride(
                post(urlPathEqualTo("/api/graphql")).atPriority(1).willReturn(aResponse().withStatus(500)),
            )
            val total = expected.at("mergeRequests", "total").int().toLong()
            val outage = run(connId)
            assertEquals(1L, outage.details["mrGraphqlBreaker"])
            assertNull(outage.details["mrDetails"])
            assertEquals(total, outage.details["mrDetailsPending"])
            assertEquals(0L, store.mrCounts(connId).mrDiffStatsMissing, "an outage makes no permanent gap")
            assertEquals(total, store.countStaleMergeRequests(connId))

            overrides.forEach(GitLabStubServer::removeOverride)
            overrides.clear()
            val again = run(connId)
            assertEquals(total, again.details["mrDetails"])
            assertEquals(1L, store.mrCounts(connId).mrDiffStatsMissing, "only the stub's own null-stats merge request")
            assertEquals(0L, store.countStaleMergeRequests(connId))
        }

    @Test
    fun `a notes 404 stamps the merge request NOT_FOUND with no events - a changed listing brings them back`() = runBlocking<Unit> {
        val connId = fresh()
        overrides += GitLabStubServer.addOverride(
            get(urlPathEqualTo("/api/v4/projects/1/merge_requests/14/notes")).atPriority(1)
                .willReturn(aResponse().withStatus(404).withBody("""{"message":"404 Not found"}""")),
        )
        val missing = run(connId)
        assertEquals(1L, missing.details["mrDetailsMissing"])
        val row = rows(connId).getValue(1L to 14L)
        assertEquals("NOT_FOUND", row[MergeRequests.detailsError])
        assertNull(row[MergeRequests.deletedAt])
        assertTrue(eventsOf(connId, row).isEmpty())
        assertEquals(0L, store.countStaleMergeRequests(connId))

        overrides.forEach(GitLabStubServer::removeOverride)
        overrides.clear()
        GitLabStubServer.setScenarioState(GitLabStubServer.SCENARIO, GitLabStubServer.DAY2)
        run(connId)
        val back = rows(connId).getValue(1L to 14L)
        assertNull(back[MergeRequests.detailsError])
        val update = day2Updates.single { it.key() == 1L to 14L }
        val before = started.single { it.key() == 1L to 14L }.at("notes").int()
        assertEquals(before + update.at("newNotes").int(), eventsOf(connId, back, "NOTE").size)
    }

    @Test
    fun `a notes page over the response cap is stamped LIMIT_EXCEEDED and the run still succeeds`() = runBlocking<Unit> {
        val connId = fresh()
        val hugeBody = "[" + " ".repeat(9 * 1024 * 1024) + "]" // over gitlab.maxResponseBytes (8 MiB in the test settings)
        overrides += GitLabStubServer.addOverride(
            get(urlPathEqualTo("/api/v4/projects/1/merge_requests/14/notes")).atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(hugeBody)),
        )
        val progress = run(connId)
        assertEquals(1L, progress.details["mrDetailsOversized"])
        val row = rows(connId).getValue(1L to 14L)
        assertEquals("LIMIT_EXCEEDED", row[MergeRequests.detailsError])
        assertTrue(eventsOf(connId, row).isEmpty())
        assertEquals(expected.at("mergeRequests", "total").int() - 1L, progress.details["mrDetails"])
        assertEquals(0L, store.countStaleMergeRequests(connId))
    }
}
