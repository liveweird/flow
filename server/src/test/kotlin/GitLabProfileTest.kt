package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.gitlab.GitLabProfile
import ch.nokillswit.ingest.DataProfile
import ch.nokillswit.norm.MrEventKind
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * The GitLab section of the data profile (`gitlab/GitLabProfile.kt`, `ingest/GitLabDataProfile.kt`) over the stub fixture's figures
 * (`sample-data/gitlab/expected.json`), computed directly and read back over HTTP from a clone's stored profile. Read-only on the fixture.
 */
class GitLabProfileTest {
    private val connections = mutableListOf<UInt>()
    private val fixtureId: UInt by lazy { runBlocking { SyncedGitLabFixture.connectionId() } }

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        connections.forEach { GitLabFixtures.purgeAll(it) }
        connections.forEach { GitLabFixtures.dataSources().delete(it) }
    }

    private val generator by lazy {
        val file = listOf(File("sample-data/gitlab/expected.json"), File("../sample-data/gitlab/expected.json")).first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("generator").jsonObject
    }

    private fun assertFixtureFigures(gitlab: ch.nokillswit.ingest.GitLabDataProfile) {
        assertEquals(listOf(4L, 0L, 0L, 0L), with(gitlab.projects) { listOf(live, movedOut, deleted, archived) })
        val commits = gitlab.commits
        assertEquals(
            listOf(281L, 19L, 0L, 0L, 1L, 281L),
            with(commits) { listOf(count, merges, filesPending, filesUnavailable, filesCapped, commitsWithCounts) },
        )
        val window = generator.getValue("commitWindow").jsonObject
        val from = Instant.parse(window.getValue("from").jsonPrimitive.content).toEpochMilli()
        val to = Instant.parse(window.getValue("to").jsonPrimitive.content).toEpochMilli()
        val earliest = assertNotNull(commits.earliestCommittedAt)
        val latest = assertNotNull(commits.latestCommittedAt)
        assertTrue(earliest >= from && latest <= to, "the commit range lies in the stub's window")
        val mrs = gitlab.mergeRequests
        assertEquals(mapOf("merged" to 34L, "opened" to 16L, "closed" to 8L, "locked" to 2L), mrs.byState)
        assertEquals(listOf(59L, 0L, 0L), listOf(mrs.withDiffStats, mrs.detailsPending, mrs.detailsUnreadable))
        assertEquals(98.3, mrs.withDiffStatsPercent)
        val events = gitlab.events.byKind
        assertEquals(MrEventKind.entries.map { it.name }.toSet(), events.keys)
        assertEquals(
            listOf(269L, 90L, 76L, 14L),
            listOf(events["COMMENT"], events["DIFF_COMMENT"], events["REVIEW_REQUESTED"], events["REVIEWED"]),
        )
        assertTrue(events.getValue("APPROVAL") >= 40 && events.getValue("CHANGES_REQUESTED") >= 10)
        val people = gitlab.identities
        val counted = with(people) { listOf(users, bots, usersDeleted, usersWithEmail, ghosts, emailOnly, commitsUnmatched) }
        assertEquals(listOf(8L, 1L, 0L, 8L, 0L, 1L, 3L), counted)
        assertEquals(100.0, people.usersWithEmailPercent)
    }

    @Test
    fun `the computed section matches the stub fixture's figures`() = runBlocking<Unit> {
        val section = GitLabProfile.compute(fixtureId, GitLabFixtures.rawStore(), GitLabFixtures.processStore(), GitLabFixtures.codeStore())
        assertFixtureFigures(section)
    }

    @Test
    fun `the stored profile is read over HTTP with every Jira section at its default, and a clone keeps the source's stamp`() {
        val own = runBlocking { GitLabFixtures.createConnection().also { connections += it } }
        SyncedGitLabFixture.cloneProcessedData(fixtureId, own)
        testApplication {
            configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
            startApplication()
            val admin = seededClient("glprofile", FlowUserRole.ADMIN)
            val profile = admin.get("/api/v1/data-sources/$own/profile").body<DataProfile>()
            assertNotNull(profile.computedAt, "the fixture's SYNC ended with PROFILE")
            assertFixtureFigures(assertNotNull(profile.gitlab))
            assertTrue(profile.projects.isEmpty() && profile.workflows.isEmpty())
            assertEquals(0L, profile.estimates.totalIssues)
            assertEquals(profileAt(fixtureId), profile.computedAt, "the clone received the source's profile_at")
        }
    }

    private fun profileAt(connId: UInt): Long = PostgresTestSupport.connect().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT profile_at FROM public.source_connections WHERE id = $connId").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    @Test
    fun `a never-synced GitLab source has no computed profile and no gitlab section`() {
        val own = runBlocking { GitLabFixtures.createConnection().also { connections += it } }
        testApplication {
            configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
            startApplication()
            val admin = seededClient("glprofile-empty", FlowUserRole.ADMIN)
            val profile = admin.get("/api/v1/data-sources/$own/profile").body<DataProfile>()
            assertNull(profile.computedAt)
            assertNull(profile.gitlab)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmGitLabFixture()
    }
}
