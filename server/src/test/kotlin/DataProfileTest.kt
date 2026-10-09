package ch.nokillswit

import ch.nokillswit.ingest.DataProfile
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val DATA_PROFILE_IN_SCOPE_PROJECT_KEYS = listOf("FLO", "PLT", "GTM", "OPS")

@Serializable
private data class ProfileExpectedReopens(val count: Long, val inScopeCount: Long)

@Serializable
private data class ProfileExpectedWorklogs(val inScopeCount: Long, val inScopeIssueCount: Long)

@Serializable
private data class ProfileExpectedSprints(val carryOverCount: Long)

@Serializable
private data class ProfileExpectedIssues(val perProject: Map<String, Long>)

@Serializable
private data class ProfileExpectedBoard(val id: Long, val projectKey: String, val unmappedStatuses: List<String>)

@Serializable
private data class ProfileExpectedFixture(
    val workflows: Map<String, List<String>>,
    val issues: ProfileExpectedIssues,
    val reopens: ProfileExpectedReopens,
    val worklogs: ProfileExpectedWorklogs,
    val sprints: ProfileExpectedSprints,
    val boards: List<ProfileExpectedBoard>,
)

private val DATA_PROFILE_FIXTURE_JSON = Json { ignoreUnknownKeys = true }

private val dataProfileExpectedFixture: ProfileExpectedFixture by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    DATA_PROFILE_FIXTURE_JSON.decodeFromString(file.readText())
}

/**
 * `GET /api/v1/data-sources/{id}/profile` (v0.2.0 plan §8/§9/§12 item 9, `jira/JiraProfile.kt` +
 * `ingest/DataProfileRoutes.kt`): ADMIN only, read-only. `computedAt` is null before the
 * connection's first PROCESS pass; a full SYNC against the shared `JiraStubServer` fixture
 * (`sample-data/jira-stub`) then makes the profile match `sample-data/jira/expected.json`'s own
 * in-scope-reachable figures — reopens, worklog coverage, sprint carry-over and the deliberately
 * unmapped board status (`.claude/docs/ingestion.md` "Jira stub").
 */
class DataProfileTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun jira() = JiraConnectionRequest(
        siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
        email = "svc-${unique("acct")}@example.com",
        apiToken = "token-${UUID.randomUUID()}",
        projectKeys = DATA_PROFILE_IN_SCOPE_PROJECT_KEYS,
        authScheme = JiraAuthScheme.BASIC,
    )

    private fun dataSourceRequest() = DataSourceRequest(
        name = unique("profile-conn"),
        enabled = true,
        syncIntervalMinutes = 60,
        backfillFrom = "2025-09-01",
        reconcileHourUtc = 3,
        jira = jira(),
    )

    @Test
    fun `non-admin gets 403`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("profileadmin403", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val user = seededClient("profileuser403")
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/data-sources/${created.id}/profile").status)
    }

    @Test
    fun `an unknown connection is 404`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("profilemissing404", UserRole.ADMIN)

        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/999999999/profile").status)
    }

    @Test
    fun `the profile is null before any PROCESS pass`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("profilenull", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val profile = admin.get("/api/v1/data-sources/${created.id}/profile").body<DataProfile>()
        assertNull(profile.computedAt)
        assertEquals(0, profile.projects.size)
        assertEquals(0L, profile.reopens.count)
        assertNull(profile.schemeFieldIds, "no profile yet means the field scheme is unknown")
        assertNull(profile.schemeEpicFieldIds)
        assertNull(profile.schemeTaskFieldIds)
        assertEquals(emptyList(), profile.epicWorkflowStatusIds)
        assertEquals(emptyList(), profile.taskWorkflowStatusIds)
    }

    @Test
    fun `a full SYNC's profile matches expected-json's in-scope reopens, worklog coverage, carry-over and the unmapped board status`() {
        // Reads the SUITE-WIDE shared synced fixture (`.claude/docs/testing.md` "Shared synced
        // fixture") instead of running its own full SYNC — this test only ever READS the profile a
        // completed SYNC produces, and `SyncedStubFixture` runs that SYNC (REFERENCE → … → PROCESS →
        // PROFILE over ~1,200 stub issues) at most once per JVM fork. `connectionId()` is called
        // OUTSIDE `testApplication` for the same reason the old direct sync call was: its first-ever
        // invocation runs a real SYNC, whose body would otherwise run under kotlinx-coroutines-test's
        // `runTest`, whose timeout a slow CI runner exceeds (`UncompletedCoroutinesError`).
        val connId = runBlocking { SyncedStubFixture.connectionId() }

        testApplication {
            configureApp("app.role" to "web")
            startApplication()
            val admin = seededClient("profilefull-read", UserRole.ADMIN)
            val profile = admin.get("/api/v1/data-sources/$connId/profile").body<DataProfile>()
            assertTrue(profile.computedAt != null, "PROFILE must run after PROCESS in a SYNC job")
            assertTrue(profile.workflowStatusIds.isNotEmpty(), "the in-scope projects' reference workflows list statuses")
            assertEquals(profile.workflowStatusIds.distinct().sorted(), profile.workflowStatusIds, "sorted and distinct")
            val schemeFieldIds = assertNotNull(profile.schemeFieldIds, "the stub serves projects/fields, so the scheme is known")
            assertTrue(schemeFieldIds.isNotEmpty() && "customfield_10016" in schemeFieldIds)
            assertEquals(schemeFieldIds.distinct().sorted(), schemeFieldIds, "sorted and distinct")
            // The epic/task split: the stub's epic start date is epic-only, Sprint is task-only, On Hold (10005) is an epic-only status.
            val epicFieldIds = assertNotNull(profile.schemeEpicFieldIds)
            val taskFieldIds = assertNotNull(profile.schemeTaskFieldIds)
            assertEquals(schemeFieldIds, (epicFieldIds + taskFieldIds).distinct().sorted())
            assertTrue("customfield_10015" in epicFieldIds && "customfield_10015" !in taskFieldIds)
            assertTrue("customfield_10020" in taskFieldIds && "customfield_10020" !in epicFieldIds)
            assertTrue("10005" in profile.epicWorkflowStatusIds && "10005" !in profile.taskWorkflowStatusIds)
            // A31: the stub's one FLO Program (level 2) is a workflow row of its own — never an epic or a task type.
            val program = profile.workflows.single { it.projectKey == "FLO" && it.issueType == "Program" }
            assertEquals(listOf("To Do"), program.observedStatuses.map { it.name }, "the Program never left To Do")
            assertEquals(listOf(0L), program.observedStatuses.map { it.transitionCount })
            assertEquals(
                dataProfileExpectedFixture.workflows.getValue("FLO"), program.referenceStatusNames,
                "FLO's workflow, as project/FLO/statuses lists it for the Program type",
            )
            assertTrue(profile.taskWorkflowStatusIds.isNotEmpty() && profile.taskWorkflowStatusIds.all { it in profile.workflowStatusIds })

            assertEquals(
                dataProfileExpectedFixture.reopens.inScopeCount, profile.reopens.count,
                "sample-data/jira/expected.json reopens.inScopeCount — the profile only ever sees in-scope work items",
            )
            assertEquals(
                dataProfileExpectedFixture.worklogs.inScopeIssueCount, profile.worklogs.itemsWithWorklog,
                "sample-data/jira/expected.json worklogs.inScopeIssueCount",
            )
            assertEquals(
                dataProfileExpectedFixture.worklogs.inScopeCount, profile.worklogs.count,
                "sample-data/jira/expected.json worklogs.inScopeCount",
            )
            assertEquals(
                dataProfileExpectedFixture.sprints.carryOverCount, profile.sprints.carryOverCount,
                "sample-data/jira/expected.json sprints.carryOverCount — carry-over only happens on scrum projects, all in-scope",
            )

            val perProject = profile.projects.associate { it.projectKey to it.issueCounts.values.sum() }
            DATA_PROFILE_IN_SCOPE_PROJECT_KEYS.forEach { projectKey ->
                assertEquals(
                    dataProfileExpectedFixture.issues.perProject.getValue(projectKey), perProject[projectKey],
                    "sample-data/jira/expected.json issues.perProject.$projectKey",
                )
            }
            assertTrue("SEC" !in perProject, "the out-of-scope project must never appear in the profile")

            val expectedGtmBoard = dataProfileExpectedFixture.boards.single { it.projectKey == "GTM" }
            val gtmBoard = profile.boards.single { it.boardId == expectedGtmBoard.id }
            assertEquals(
                expectedGtmBoard.unmappedStatuses.toSet(), gtmBoard.unmappedStatusNames.toSet(),
                "sample-data/jira/expected.json boards[projectKey=GTM].unmappedStatuses — the deliberately unmapped 'Waiting' status",
            )
        }
    }
}
