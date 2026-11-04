package ch.nokillswit

import ch.nokillswit.gitlab.GitLabCommitsStream
import ch.nokillswit.gitlab.GitLabProjectsStream
import ch.nokillswit.gitlab.GitLabRawStore
import ch.nokillswit.gitlab.GitLabRawStore.CommitFiles
import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.ingest.backfillFromEpochMillis
import ch.nokillswit.ingest.defaultBackfillFrom
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val IN_SCOPE = listOf(1L, 2L, 3L, 4L)

/**
 * The COMMITS and COMMIT FILES streams against the GitLab stub, graded against `sample-data/gitlab/expected.json` (the generator's ground
 * truth, never numbers copied here): the shared [SyncedGitLabFixture] read-only, and own connections for everything that mutates (the
 * RANGE walk, the day-2 force-push and branch rename, the call budget, the terminal outcomes).
 */
class GitLabCommitsStreamTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmGitLabFixture()
    }

    private val store: GitLabRawStore = GitLabFixtures.rawStore()
    private val connections = mutableListOf<UInt>()
    private val overrides = mutableListOf<StubMapping>()

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        overrides.forEach(GitLabStubServer::removeOverride)
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

    private suspend fun fresh(): UInt = GitLabFixtures.createConnection().also { connections += it }

    private suspend fun sync(connId: UInt, commitFilesPerRun: Int = 100_000): Map<String, Long> {
        val progress = mutableListOf<String>()
        GitLabFixtures.run(GitLabFixtures.connector(commitFilesPerRun = commitFilesPerRun), connId, onDemand = true, progress = progress)
        return Json.parseToJsonElement(progress.last()).jsonObject.mapValues { it.value.jsonPrimitive.long }
    }

    private suspend fun fileRows(connId: UInt): List<ResultRow> = suspendTransaction(sharedDatabaseForTests()) {
        CommitFiles.selectAll().where { CommitFiles.connectionId eq connId }.toList()
    }

    private fun List<ResultRow>.commitsOf(projectId: Long) = filter { it[Commits.projectId] == projectId }

    private fun live(rows: List<ResultRow>) = rows.filter { it[Commits.deletedAt] == null }

    // --- the shared SYNC, read-only ------------------------------------------------------------------------------------------

    @Test
    fun `the synced fixture holds every in-scope commit, its merges, heads and authors`() = runBlocking<Unit> {
        val connId = SyncedGitLabFixture.connectionId()
        val rows = store.commitRows(connId)
        val perProject = expected.at("commits", "perProject")
        for (projectId in IN_SCOPE) {
            val truth = perProject.at(projectId.toString())
            val mine = rows.commitsOf(projectId)
            assertEquals(truth.at("total").str().toInt(), mine.size, "project $projectId commits")
            assertEquals(truth.at("merges").str().toInt(), mine.count { it[Commits.parentCount] > 1 }, "project $projectId merges")
        }
        assertEquals(expected.at("commits", "inScopeTotals", "total").str().toInt(), rows.size)
        store.liveProjectsForCommits(connId).forEach { project ->
            assertEquals(perProject.at(project.projectId.toString(), "tip").str(), project.headSha, "project ${project.projectId} head")
            assertFalse(project.relistRequested)
        }
        assertEquals(0L, store.countPendingCommitFiles(connId))
        assertTrue(rows.none { it[Commits.deletedAt] != null })

        val identities = expected.getValue("identities").jsonArray.map { it.jsonObject }
        for (identity in identities) {
            val emails = identity.getValue("emails").jsonArray.map { it.str() }.toSet()
            val mine = rows.count { it[Commits.parentCount] <= 1 && it[Commits.authorEmail] in emails }
            assertEquals(identity.at("commitsNonMerge").str().toInt(), mine, "identity ${identity.at("key").str()} non-merge commits")
        }
    }

    @Test
    fun `the stored file rows equal the ground truth for every non-merge commit`() = runBlocking<Unit> {
        val connId = SyncedGitLabFixture.connectionId()
        val files = fileRows(connId).groupBy { it[CommitFiles.projectId] to it[CommitFiles.sha] }
        val commits = store.commitRows(connId).associateBy { it[Commits.projectId] to it[Commits.sha] }
        val manyFiles = expected.at("special", "manyFiles").at("sha").str()
        var checked = 0
        for (projectId in IN_SCOPE) {
            for ((sha, truth) in expected.at("commitFiles", projectId.toString()).jsonObject) {
                val stored = commits.getValue(projectId to sha)
                val statsAdd = truth.at("stats", "additions").jsonPrimitive.int
                assertEquals(statsAdd, stored[Commits.additions], "$projectId/$sha stats")
                if (truth.at("merge").jsonPrimitive.boolean) {
                    assertTrue(files[projectId to sha].isNullOrEmpty(), "a merge commit's diff is never read")
                    assertNull(stored[Commits.filesFetchedAt])
                    continue
                }
                if (sha == manyFiles) continue
                val byPath = files[projectId to sha].orEmpty().associateBy { it[CommitFiles.path] }
                // A path listed twice (delete + add at the same path) is ONE stored row with the lines summed.
                val expectedFiles = truth.at("files").jsonArray.map { it.jsonObject }.groupBy { it.at("path").str() }
                assertEquals(expectedFiles.size, byPath.size, "$projectId/$sha file count")
                assertEquals(expectedFiles.size, stored[Commits.filesChanged])
                assertFalse(stored[Commits.filesCapped])
                for ((path, entries) in expectedFiles) assertFileMatches("$projectId/$sha", entries, byPath.getValue(path))
                checked++
            }
        }
        val nonMerge = expected.at("commits", "inScopeTotals", "nonMerge").str().toInt()
        assertEquals(nonMerge - 1, checked, "every non-merge commit but the capped one")
    }

    private fun assertFileMatches(where: String, entries: List<JsonObject>, row: ResultRow) {
        val path = entries.first().at("path").str()
        val uncounted = entries.any { it["tooLarge"] != null || it["collapsed"] != null }
        fun total(key: String) = if (uncounted) null else entries.sumOf { it.at(key).jsonPrimitive.int }
        assertEquals(total("additions"), row[CommitFiles.additions], "$where $path additions")
        assertEquals(total("deletions"), row[CommitFiles.deletions], "$where $path deletions")
        assertEquals(entries.all { it["new"] != null }, row[CommitFiles.newFile], "$where $path new")
        assertEquals(entries.all { it["deleted"] != null }, row[CommitFiles.deletedFile], "$where $path deleted")
        assertEquals(entries.any { it["renamed"] != null }, row[CommitFiles.renamedFile], "$where $path renamed")
        assertEquals(entries.any { it["generated"] != null }, row[CommitFiles.generatedFile], "$where $path generated")
        assertEquals(entries.firstNotNullOfOrNull { it["oldPath"]?.str() }, row[CommitFiles.oldPath], "$where $path old path")
    }

    @Test
    fun `the 1040-file commit is capped, and too-large or collapsed files keep their paths with null lines`() = runBlocking<Unit> {
        val connId = SyncedGitLabFixture.connectionId()
        val many = expected.at("special", "manyFiles")
        val projectId = many.at("projectId").str().toLong()
        val rows = store.commitFileRows(connId, projectId, many.at("sha").str())
        assertEquals(many.at("cap").str().toInt(), rows.size)
        val commit = store.commitRows(connId, projectId).single { it[Commits.sha] == many.at("sha").str() }
        assertTrue(commit[Commits.filesCapped])
        assertEquals(1_000, commit[Commits.filesChanged])
        val capped = many.at("firstCappedFilesTotals")
        assertEquals(capped.at("additions").str().toInt(), rows.sumOf { it[CommitFiles.additions] ?: 0 })
        assertEquals(capped.at("deletions").str().toInt(), rows.sumOf { it[CommitFiles.deletions] ?: 0 })
        assertEquals(many.at("stats", "additions").str().toInt(), commit[Commits.additions], "the stats still carry every file")

        for (name in listOf("tooLarge", "collapsed")) {
            val special = expected.at("special", name)
            val file = store.commitFileRows(connId, special.at("projectId").str().toLong(), special.at("sha").str())
                .single { it[CommitFiles.path] == special.at("path").str() }
            assertNull(file[CommitFiles.additions], "$name keeps its path with unknown lines")
            assertNull(file[CommitFiles.deletions])
        }
    }

    @Test
    fun `the special example files are stored as the generator describes them`() = runBlocking<Unit> {
        val connId = SyncedGitLabFixture.connectionId()
        val examples = expected.at("special", "examples").jsonObject
        for ((label, element) in examples) {
            val example = element.jsonObject
            val row = store.commitFileRows(connId, example.at("projectId").str().toLong(), example.at("sha").str())
                .single { it[CommitFiles.path] == example.at("path").str() }
            assertEquals(example.at("additions").str().toInt(), row[CommitFiles.additions], "$label additions")
            assertEquals(example.at("deletions").str().toInt(), row[CommitFiles.deletions], "$label deletions")
            assertEquals(example["generated"] != null, row[CommitFiles.generatedFile], "$label generated")
            assertEquals(example["new"] != null, row[CommitFiles.newFile], "$label new")
            assertEquals(example["deleted"] != null, row[CommitFiles.deletedFile], "$label deleted")
            assertEquals(example["oldPath"]?.str(), row[CommitFiles.oldPath], "$label old path")
        }
        assertNotNull(examples["lockFile"])
    }

    // --- own connections -----------------------------------------------------------------------------------------------------

    @Test
    fun `a RANGE walk from each stored head lists exactly the commits after it, and a second run is CHECK-only`() = runBlocking<Unit> {
        val connId = fresh()
        val client = GitLabFixtures.stubClient()(GitLabStubServer.start(), "glpat-test-token")
        GitLabFixtures.runStream(GitLabProjectsStream(client, store, listOf("platform", "apps")), connId)
        val heads = expected.getValue("storedHeads").jsonObject
        for (project in store.liveProjectsForCommits(connId)) {
            store.recordCommitsHead(connId, project.projectId, heads.at(project.projectId.toString(), "storedSha").str())
        }
        val floor = backfillFromEpochMillis(defaultBackfillFrom())
        val first = GitLabFixtures.runStream(GitLabCommitsStream(client, store, floor), connId)
        var total = 0L
        for (project in IN_SCOPE) {
            val after = heads.at(project.toString(), "commitsAfterStoredHead").str().toInt()
            assertEquals(after, store.commitRows(connId, project).size, "project $project")
            total += after
            val head = store.liveProjectsForCommits(connId).single { it.projectId == project }.headSha
            assertEquals(heads.at(project.toString(), "startedTipSha").str(), head)
        }
        assertEquals(total, first["commits"])
        assertNull(first["commitsTombstoned"])

        val second = GitLabFixtures.runStream(GitLabCommitsStream(client, store, floor), connId)
        assertNull(second["commits"])
        assertNull(second["pages"], "unchanged heads: the tip page is the only call, nothing is walked or written")
        assertEquals(total, store.commitRows(connId).size.toLong())
    }

    @Test
    fun `day two - a force-push is re-listed and tombstoned, a renamed branch and new commits are followed`() = runBlocking<Unit> {
        val connId = fresh()
        sync(connId)
        val before = store.commitRows(connId)
        GitLabStubServer.setScenarioState(GitLabStubServer.SCENARIO, GitLabStubServer.DAY2)
        val progress = sync(connId)
        val day2 = expected.at("day2")
        val rows = store.commitRows(connId)

        // Project 1: five commits removed, five rewritten or new, the head moved, the request cleared.
        val push = day2.at("forcePush")
        val removed = push.at("removedShas").jsonArray.map { it.str() }.toSet()
        val mine = rows.commitsOf(1)
        assertEquals(removed, mine.filter { it[Commits.deletedAt] != null }.map { it[Commits.sha] }.toSet())
        // The tombstones were flagged for PROCESS, and the same SYNC job ended with it: the norm rows mirror them; nothing stays flagged.
        val mirrored = GitLabFixtures.codeStore().commitRows(connId, 1).filter { it.sha in removed }
        assertEquals(removed, mirrored.filter { it.deletedAt != null }.map { it.sha }.toSet())
        assertTrue(mine.filter { it[Commits.sha] in removed }.none { it[Commits.needsProcessing] })
        val added = (push.at("newShas").jsonArray + push.at("rewrittenShas").jsonArray).map { it.str() }.toSet()
        val known = before.commitsOf(1).map { it[Commits.sha] }.toSet()
        assertEquals(added, mine.map { it[Commits.sha] }.toSet() - known)
        assertEquals(day2.at("commitListingByProject", "1", "total").str().toInt(), live(mine).size)
        val project1 = store.liveProjectsForCommits(connId).single { it.projectId == 1L }
        assertEquals(push.at("newTip").str(), project1.headSha)
        assertFalse(project1.relistRequested)
        assertEquals(1L, progress["commitsRelisted"])
        assertEquals(removed.size.toLong(), progress["commitsTombstoned"])

        // Project 3: new commits (one a merge); project 4: the renamed default branch; project 7: new; project 2: moved out, untouched.
        for (projectId in listOf(3L, 4L, 7L)) {
            val delta = day2.at("commitDeltaByProject", projectId.toString(), "newInListing").str().toInt()
            assertEquals(before.commitsOf(projectId).size + delta, rows.commitsOf(projectId).size, "project $projectId")
            val listed = day2.at("commitListingByProject", projectId.toString(), "total").str().toInt()
            assertEquals(listed, live(rows.commitsOf(projectId)).size)
        }
        val project7 = store.liveProjectsForCommits(connId).single { it.projectId == 7L }
        assertEquals(day2.at("commitListingByProject", "7", "tip").str(), project7.headSha)
        val project4 = store.projectRows(connId).single { it[Projects.projectId] == 4L }
        assertEquals("main", project4[Projects.defaultBranch])
        assertEquals(before.commitsOf(2).size, rows.commitsOf(2).size)
        assertTrue(store.liveProjectsForCommits(connId).none { it.projectId == 2L }, "project 2 moved out of scope")
        assertEquals(0L, store.countPendingCommitFiles(connId), "the new commits' diffs were read in the same run")
    }

    @Test
    fun `a small call budget stops the files stream with commits pending and the next run drains them`() = runBlocking<Unit> {
        val connId = fresh()
        val first = sync(connId, commitFilesPerRun = 20)
        val nonMerge = expected.at("commits", "inScopeTotals", "nonMerge").str().toLong()
        val pending = first.getValue("commitFilesPending")
        assertTrue(pending > 0)
        assertEquals(nonMerge, (first["commitFiles"] ?: 0L) + pending, "every non-merge commit is read or pending")
        assertEquals(pending, store.countPendingCommitFiles(connId))
        store.commitRows(connId).filter { it[Commits.filesFetchedAt] != null }.forEach {
            val stored = store.commitFileRows(connId, it[Commits.projectId], it[Commits.sha]).size
            assertEquals(it[Commits.filesChanged], stored)
        }

        val second = sync(connId)
        assertNull(second["commitFilesPending"])
        assertNull(second["commits"], "the commit streams found nothing new")
        assertEquals(0L, store.countPendingCommitFiles(connId))
    }

    @Test
    fun `a commit whose diff is gone or over the cap is stamped with its terminal error and the run still succeeds`() = runBlocking<Unit> {
        val connId = fresh()
        val shas = expected.at("commitFiles", "1").jsonObject.filter { !it.value.at("merge").jsonPrimitive.boolean }.keys.toList().take(2)
        val (missing, oversized) = shas
        overrides += GitLabStubServer.addOverride(
            get(urlPathEqualTo("/api/v4/projects/1/repository/commits/$missing/diff")).atPriority(1)
                .willReturn(aResponse().withStatus(404).withBody("""{"message":"404 Commit Not Found"}""")),
        )
        // Over gitlab.maxResponseBytes (8 MiB in the test settings): refused before it is parsed.
        val hugeBody = "[" + " ".repeat(9 * 1024 * 1024) + "]"
        overrides += GitLabStubServer.addOverride(
            get(urlPathEqualTo("/api/v4/projects/1/repository/commits/$oversized/diff")).atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(hugeBody)),
        )
        val progress = sync(connId)
        val rows = store.commitRows(connId, 1).associateBy { it[Commits.sha] }
        assertEquals("NOT_FOUND", rows.getValue(missing)[Commits.filesError])
        assertEquals("LIMIT_EXCEEDED", rows.getValue(oversized)[Commits.filesError])
        assertNull(rows.getValue(oversized)[Commits.filesChanged])
        assertEquals(1L, progress["commitFilesMissing"])
        assertEquals(1L, progress["commitFilesOversized"])
        assertEquals(0L, store.countPendingCommitFiles(connId))
    }
}
