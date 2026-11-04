package ch.nokillswit

import ch.nokillswit.gitlab.DETAILS_ERROR_LIMIT_EXCEEDED
import ch.nokillswit.gitlab.GitLabClient
import ch.nokillswit.gitlab.GitLabCommitsStream
import ch.nokillswit.gitlab.GitLabMergeRequestsStream
import ch.nokillswit.gitlab.GitLabMrDetailsStream
import ch.nokillswit.gitlab.GitLabProjectsStream
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.backfillFromEpochMillis
import ch.nokillswit.ingest.defaultBackfillFrom
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val GROUPS = listOf("platform", "apps")

/**
 * RECONCILE through the connector against the GitLab stub, graded against `sample-data/gitlab/expected.json`: day two (the deleted merge
 * request, the moved project, the force-push, the renamed default branch), a deleted project, and the day-zero no-op. Own connections
 * only, no shared fixture: the Started state is PROJECTS + COMMITS + MERGE REQUESTS through `runStream` (the two backlogs run only
 * where a test needs a settled merge request).
 */
class GitLabReconcileStreamsTest {
    private val store: GitLabRawStore = GitLabFixtures.rawStore()
    private val cursors = SyncCursorsService(sharedDatabaseForTests())
    private val connections = mutableListOf<UInt>()
    private val overrides = mutableListOf<StubMapping>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        overrides.forEach(GitLabStubServer::removeOverride)
        overrides.clear()
        GitLabStubServer.resetScenarios()
        connections.forEach { GitLabFixtures.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private val expected: JsonObject by lazy {
        val file = listOf(File("sample-data/gitlab/expected.json"), File("../sample-data/gitlab/expected.json")).first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun JsonElement.at(vararg path: String): JsonElement = path.fold(this) { node, key -> node.jsonObject.getValue(key) }

    private fun JsonElement.str() = jsonPrimitive.content

    private fun client(): GitLabClient = GitLabFixtures.stubClient()(GitLabStubServer.start(), "glpat-test-token")

    private val floor get() = backfillFromEpochMillis(defaultBackfillFrom())

    private suspend fun fresh(): UInt = GitLabFixtures.createConnection().also { connections += it }

    /** The Started state: PROJECTS, COMMITS and MERGE REQUESTS, never the two backlogs (~0.3 s). */
    private suspend fun started(connId: UInt) {
        val client = client()
        GitLabFixtures.runStream(GitLabProjectsStream(client, store, GROUPS), connId)
        GitLabFixtures.runStream(GitLabCommitsStream(client, store, floor), connId)
        GitLabFixtures.runStream(GitLabMergeRequestsStream(client, store, GROUPS, floor), connId)
    }

    private suspend fun reconcile(connId: UInt, onDemand: Boolean = false): Map<String, Long> {
        val progress = mutableListOf<String>()
        GitLabFixtures.run(GitLabFixtures.connector(), connId, SyncJobKind.RECONCILE, onDemand, progress)
        return Json.parseToJsonElement(progress.last()).jsonObject.mapValues { it.value.jsonPrimitive.long }
    }

    private suspend fun mrs(connId: UInt) =
        store.mergeRequestRows(connId).associateBy { it[MergeRequests.projectId] to it[MergeRequests.iid] }

    private fun List<ResultRow>.commitsOf(projectId: Long) = filter { it[Commits.projectId] == projectId }

    private fun leftoverCursors(connId: UInt) =
        runBlocking { cursors.getAll(connId).map { it.stream }.filter { it.startsWith("reconcile_") } }

    private fun JsonObject.key() = getValue("projectId").jsonPrimitive.long to getValue("iid").jsonPrimitive.long

    private suspend fun exec(update: suspend () -> Unit) = suspendTransaction(sharedDatabaseForTests()) { update() }

    @Test
    fun `day two through the connector - a deleted merge request, a moved project, a force-push and a renamed branch are reconciled`() =
        runBlocking<Unit> {
        val connId = fresh()
        started(connId)
        val mrsBefore = mrs(connId)
        val commitsBefore = store.commitRows(connId)
        val day2 = expected.at("day2")
        val gone = day2.at("deletedMergeRequest")
        val goneKey = gone.at("projectId").jsonPrimitive.long to gone.at("iid").jsonPrimitive.long

        GitLabStubServer.setScenarioState(GitLabStubServer.SCENARIO, GitLabStubServer.DAY2)
        val progress = reconcile(connId)

        // Merge requests: the deleted one is tombstoned for PROCESS, the moved project's are kept (served by project id), 5 are re-listed.
        val after = mrs(connId)
        val tombstone = after.getValue(goneKey)
        assertNotNull(tombstone[MergeRequests.deletedAt])
        // Flagged for PROCESS by the tombstone, and RECONCILE ends with PROCESS: the norm row mirrors it in the same job.
        assertFalse(tombstone[MergeRequests.needsProcessing])
        val mirrored = GitLabFixtures.codeStore().mergeRequestRows(connId).single { it.mrId == tombstone[MergeRequests.mrId] }
        assertNotNull(mirrored.deletedAt)
        assertEquals(1L, progress["mergeRequestsDeleted"])
        assertEquals(1, after.values.count { it[MergeRequests.deletedAt] != null })
        val movedProject = day2.at("movedProject").at("projectId").jsonPrimitive.long
        val moved = expected.at("mergeRequests", "perProject", movedProject.toString()).jsonPrimitive.int
        assertEquals(moved.toLong(), progress["mrReconcileKept"], "unlisted under group 7, served by project id: all kept")
        val updates = day2.at("mergeRequestUpdates").jsonArray.map { it.jsonObject }
        val storedUpdates = updates.filter { it.key() in mrsBefore }
        assertEquals(6, storedUpdates.size, "the new project's merge request is not a stored row")
        for (update in storedUpdates) {
            val key = update.key()
            assertTrue(after.getValue(key)[MergeRequests.changedAt] > mrsBefore.getValue(key)[MergeRequests.changedAt], "update $key")
        }
        val listed = storedUpdates.count { it.getValue("projectId").jsonPrimitive.long != movedProject }
        assertEquals(listed.toLong(), progress["mergeRequestsChanged"], "the moved project's change comes through the probe")
        assertEquals(1L, progress["mrSkipped"], "the new project is not a stored project: PROJECTS does not run in RECONCILE")

        // Commits: the force-pushed project's removed SHAs are tombstoned and its head is the new tip; the renamed branch is skipped.
        val push = day2.at("forcePush")
        val removed = push.at("removedShas").jsonArray.map { it.str() }.toSet()
        val rows = store.commitRows(connId)
        assertEquals(removed, rows.commitsOf(1).filter { it[Commits.deletedAt] != null }.map { it[Commits.sha] }.toSet())
        assertEquals(removed.size.toLong(), progress["commitsTombstoned"])
        val projects = store.liveProjectsForCommits(connId).associateBy { it.projectId }
        assertEquals(push.at("newTip").str(), projects.getValue(1).headSha)
        assertFalse(projects.getValue(1).relistRequested)
        val delta3 = day2.at("commitDeltaByProject", "3", "newInListing").str().toInt()
        assertEquals(commitsBefore.commitsOf(3).size + delta3, rows.commitsOf(3).size, "SYNC never stored them; the since-less walk does")
        assertEquals(day2.at("commitListingByProject", "3", "tip").str(), projects.getValue(3).headSha)
        // The moved project still serves its commits by id at day two: re-listed, everything kept.
        assertEquals(commitsBefore.commitsOf(movedProject).size, rows.commitsOf(movedProject).size)
        assertTrue(rows.commitsOf(movedProject).all { it[Commits.deletedAt] == null })
        // The renamed default branch: the stored `master` no longer exists, so the project is skipped and its row is left as it was.
        assertEquals(1L, progress["commitsProjectsSkipped"])
        assertFalse(projects.getValue(4).relistRequested, "a skipped reconcile walk raises no re-list request")
        assertTrue((progress["commitsRelisted"] ?: 0) >= 3)

        assertEquals(emptyList(), leftoverCursors(connId))
        assertEquals(1L, store.counts(connId).mergeRequestsDeleted)
        assertEquals(0L, store.counts(connId).projectsDeleted)

        // A SYNC afterwards converges: the moved project is marked moved out, the renamed project is followed, the backlog drains.
        GitLabFixtures.run(GitLabFixtures.connector(), connId)
        val counts = store.counts(connId)
        assertEquals(1L, counts.projectsMovedOut)
        val project4 = store.liveProjectsForCommits(connId).single { it.projectId == 4L }
        assertFalse(project4.relistRequested)
        assertEquals(day2.at("commitListingByProject", "4", "tip").str(), project4.headSha)
        assertEquals(0L, store.countStaleMergeRequests(connId))
        assertEquals(day2.at("mergeRequestsTotal").jsonPrimitive.long, counts.mergeRequests)
        assertNotNull(mrs(connId).getValue(goneKey)[MergeRequests.deletedAt], "a SYNC does not resurrect a merge request it cannot list")
    }

    @Test
    fun `a deleted merge request keeps its events - the details were read before the reconcile`() = runBlocking<Unit> {
        val connId = fresh()
        started(connId)
        GitLabFixtures.runStream(GitLabMrDetailsStream(client(), store, 100_000), connId)
        val gone = expected.at("day2", "deletedMergeRequest")
        val key = gone.at("projectId").jsonPrimitive.long to gone.at("iid").jsonPrimitive.long
        val mrId = mrs(connId).getValue(key)[MergeRequests.mrId]
        val events = store.mrEventRows(connId, mrId).size
        assertTrue(events > 0)

        GitLabStubServer.setScenarioState(GitLabStubServer.SCENARIO, GitLabStubServer.DAY2)
        val progress = reconcile(connId)
        assertEquals(1L, progress["mergeRequestsDeleted"])
        assertEquals(events, store.mrEventRows(connId, mrId).size, "a tombstone deletes nothing")
        assertNotNull(mrs(connId).getValue(key)[MergeRequests.deletedAt])
    }

    @Test
    fun `a moved-out project that answers 404 is deleted with its commits and merge requests untouched, one that answers 200 is not`() =
        runBlocking<Unit> {
        val connId = fresh()
        started(connId)
        val mrCount = store.mergeRequestRows(connId).size
        val commitCount = store.commitRows(connId).size
        exec {
            for (projectId in listOf(3L, 4L)) {
                Projects.update({ (Projects.connectionId eq connId) and (Projects.projectId eq projectId) }) { it[movedOutAt] = 5_000L }
            }
        }
        overrides += GitLabStubServer.addOverride(
            get(urlPathEqualTo("/api/v4/projects/3")).atPriority(1)
                .willReturn(aResponse().withStatus(404).withBody("""{"message":"404 Project Not Found"}""")),
        )
        val progress = reconcile(connId)
        assertEquals(1L, progress["projectsDeleted"])
        assertEquals(1L, progress["projectsProbed"], "project 4 still exists: it merely left the groups")
        val rows = store.projectRows(connId).associateBy { it[Projects.projectId] }
        assertNotNull(rows.getValue(3)[Projects.deletedAt])
        assertNull(rows.getValue(4)[Projects.deletedAt])
        assertEquals(1L, store.counts(connId).projectsDeleted)
        assertEquals(mrCount, store.mergeRequestRows(connId).size)
        assertTrue(store.mergeRequestRows(connId).all { it[MergeRequests.deletedAt] == null }, "nothing cascades to the merge requests")
        assertEquals(commitCount, store.commitRows(connId).size)
        assertTrue(store.commitRows(connId).all { it[Commits.deletedAt] == null }, "nor to the commits")
    }

    @Test
    fun `at day zero a reconcile changes nothing, twice`() = runBlocking<Unit> {
        val connId = fresh()
        started(connId)
        fun snapshot() = runBlocking {
            Triple(
                store.projectRows(connId).map { Triple(it[Projects.projectId], it[Projects.changedAt], it[Projects.deletedAt]) },
                store.mergeRequestRows(connId).map {
                    Triple(it[MergeRequests.mrId], it[MergeRequests.changedAt], it[MergeRequests.deletedAt])
                },
                store.commitRows(connId).map { it[Commits.sha] to it[Commits.deletedAt] },
            )
        }
        val before = snapshot()
        repeat(2) {
            val progress = reconcile(connId)
            assertEquals(before, snapshot())
            assertNotNull(progress["pages"])
            assertEquals(4L, progress["commitsRelisted"])
            assertNull(progress["mrReconcileKept"])
            assertNull(progress["mergeRequestsDeleted"])
            assertEquals(emptyList(), leftoverCursors(connId))
        }
    }

    @Test
    fun `the stats re-ask refills a gap GitLab can answer and leaves the permanent one`() = runBlocking<Unit> {
        val connId = fresh()
        started(connId)
        GitLabFixtures.runStream(GitLabMrDetailsStream(client(), store, 100_000), connId)
        val gap = expected.at("mergeRequests", "nullDiffStatsGap")
        val gapKey = gap.at("projectId").jsonPrimitive.long to gap.at("iid").jsonPrimitive.long
        assertEquals(1L, store.counts(connId).mrDiffStatsMissing)
        val victim = mrs(connId).entries.first { it.key != gapKey && it.value[MergeRequests.additions] != null }
        val original = victim.value[MergeRequests.additions]
        exec {
            MergeRequests.update({ (MergeRequests.connectionId eq connId) and (MergeRequests.mrId eq victim.value[MergeRequests.mrId]) }) {
                it[additions] = null
                it[deletions] = null
                it[fileCount] = null
            }
        }
        assertEquals(2L, store.counts(connId).mrDiffStatsMissing)

        val progress = reconcile(connId)
        assertEquals(1L, progress["mrStatsRefilled"], "the permanent gap is asked again and stays a gap")
        assertEquals(1L, store.counts(connId).mrDiffStatsMissing)
        assertEquals(original, mrs(connId).getValue(victim.key)[MergeRequests.additions])
        assertNull(mrs(connId).getValue(gapKey)[MergeRequests.additions])
    }

    @Test
    fun `a details read that was too big is retried only by Reconcile now`() = runBlocking<Unit> {
        val connId = fresh()
        started(connId)
        val target = mrs(connId).values.first()[MergeRequests.mrId]
        exec {
            MergeRequests.update({ (MergeRequests.connectionId eq connId) and (MergeRequests.mrId eq target) }) {
                it[detailsSyncedAt] = 9_000_000_000_000L
                it[detailsError] = DETAILS_ERROR_LIMIT_EXCEEDED
            }
        }
        assertNull(reconcile(connId)["mrDetailsRetried"])
        fun stamp() = runBlocking { store.mergeRequestRows(connId).first { it[MergeRequests.mrId] == target }[MergeRequests.detailsError] }
        assertEquals(DETAILS_ERROR_LIMIT_EXCEEDED, stamp())
        assertEquals(1L, reconcile(connId, onDemand = true)["mrDetailsRetried"])
        assertNull(stamp())
    }
}
