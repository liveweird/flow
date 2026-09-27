package ch.nokillswit

import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.RawIssueInspection
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.norm.IntervalSource
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.NormalizedIssue
import ch.nokillswit.norm.NormalizedStatusInterval
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.TombstoneKind
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemFacts
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/v1/data-sources/{id}/raw-issues/{issueKey}` (v0.2.0 plan §9/§12 item 8b,
 * `ingest/RawIssueInspectorRoutes.kt`): ADMIN only, read-only. Seeds `raw.jira_issues`/`norm.*`
 * directly against the shared test database (`SyncStatusRoutesTest`'s own pattern) rather than
 * driving a real Jira sync over HTTP — this route is a pure read over state every other stream
 * already owns.
 */
class RawIssueInspectorTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun jira() = JiraConnectionRequest(
        siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
        email = "svc-${unique("acct")}@example.com",
        apiToken = "token-${UUID.randomUUID()}",
        projectKeys = listOf("ENG"),
        authScheme = JiraAuthScheme.BASIC,
    )

    private fun dataSourceRequest() = DataSourceRequest(
        name = unique("conn"),
        enabled = true,
        syncIntervalMinutes = 60,
        backfillFrom = null,
        reconcileHourUtc = 3,
        jira = jira(),
    )

    private fun issuePayload(issueId: Long, issueKey: String) =
        """{"id":"$issueId","key":"$issueKey","fields":{"project":{"id":"1","key":"ENG"}}}"""

    @Test
    fun `non-admin gets 403 before any lookup, even for a malformed issueKey`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("rawissueadmin403", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val user = seededClient("rawissueuser403")
        val response = user.get("/api/v1/data-sources/${created.id}/raw-issues/not-a-valid-key!!!")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `an unknown connection is 404`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("rawissuemissingconn", UserRole.ADMIN)

        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/999999999/raw-issues/1").status)
    }

    @Test
    fun `an unknown issue id or key is 404`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("rawissuemissing404", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/${created.id}/raw-issues/999999").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/${created.id}/raw-issues/ENG-999").status)
    }

    @Test
    fun `a malformed issueKey is 400`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("rawissue400", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/data-sources/${created.id}/raw-issues/eng-1").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/data-sources/${created.id}/raw-issues/ENG").status)
    }

    @Test
    fun `looked up by id or by key, both return the same raw issue`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("rawissuebykeyid", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val connId = created.id

        val rawStore = JiraRawStore(sharedDatabaseForTests())
        rawStore.upsertIssue(connId, RawIssueInput(42L, "ENG-42", 1L, "ENG", 1_000, issuePayload(42L, "ENG-42")), 1_000)
        rawStore.insertChangelog(
            connId, 500_001L, 42L, 1_000, "acc-1",
            """{"id":"500001","created":"2024-01-01T00:00:00.000+0000"}""", 1_000,
        )
        rawStore.upsertWorklog(connId, 42L, 700_001L, 1_000, """{"id":"700001","issueId":"42"}""", 1_000)

        val byId = admin.get("/api/v1/data-sources/$connId/raw-issues/42").body<RawIssueInspection>()
        val byKey = admin.get("/api/v1/data-sources/$connId/raw-issues/ENG-42").body<RawIssueInspection>()

        assertEquals(byId, byKey)
        assertEquals(42L, byId.issueId)
        assertEquals("ENG-42", byId.issueKey)
        assertEquals(1, byId.changelogs.size)
        assertEquals(1, byId.worklogs.size)
        assertNull(byId.deletedAt)
        assertNull(byId.workItem, "never processed — no norm.work_items row yet")
        assertTrue(byId.statusIntervals.isEmpty())
        assertTrue(byId.anomalies.isEmpty())
    }

    @Test
    fun `a tombstoned issue shows deletedAt`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("rawissuetombstoned", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val connId = created.id

        val rawStore = JiraRawStore(sharedDatabaseForTests())
        rawStore.upsertIssue(connId, RawIssueInput(43L, "ENG-43", 1L, "ENG", 1_000, issuePayload(43L, "ENG-43")), 1_000)
        rawStore.markIssueDeleted(connId, 43L, 2_000)

        val inspection = admin.get("/api/v1/data-sources/$connId/raw-issues/ENG-43").body<RawIssueInspection>()
        assertEquals(2_000L, inspection.deletedAt)
        assertNull(inspection.movedOutAt)
    }

    @Test
    fun `a processed issue carries its normalized work item, status intervals and anomalies`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("rawissueprocessed", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val connId = created.id

        val rawStore = JiraRawStore(sharedDatabaseForTests())
        rawStore.upsertIssue(connId, RawIssueInput(44L, "ENG-44", 1L, "ENG", 1_000, issuePayload(44L, "ENG-44")), 1_000)

        val facts = WorkItemFacts(
            issueKey = "ENG-44",
            projectKey = "ENG",
            issueType = "Story",
            isSubtask = false,
            parentIssueId = null,
            summary = "A test issue",
            currentStatusId = "3",
            resolution = null,
            priority = null,
            assigneeAccountId = "acc-1",
            reporterAccountId = null,
            createdAtMs = 1_000L,
            updatedAtMs = 2_000L,
            resolvedAtMs = null,
            storyPoints = 3.0,
            originalEstimateSeconds = null,
            timeSpentSeconds = 0,
            labels = emptyList(),
            components = emptyList(),
            fixVersions = emptyList(),
            teamValueJson = null,
            rank = null,
            tombstone = TombstoneKind.NONE,
        )
        val statusInterval = NormalizedStatusInterval(
            seq = 1, statusId = "3", statusName = "In Progress", category = StatusCategory.IN_PROGRESS,
            fromAtMs = 1_000L, toAtMs = null, source = IntervalSource.CREATED,
        )
        val fieldInterval = NormalizedFieldInterval(
            field = TrackedField.ASSIGNEE, seq = 1, valueId = "acc-1", valueText = "Ada", fromAtMs = 1_000L, toAtMs = null,
        )
        val normalized = NormalizedIssue(
            issueId = 44L,
            facts = facts,
            currentStatusName = "In Progress",
            currentStatusCategory = StatusCategory.IN_PROGRESS,
            statusIntervals = listOf(statusInterval),
            fieldIntervals = listOf(fieldInterval),
            fieldChanges = emptyList(),
            worklogs = emptyList(),
            currentSprintIds = emptyList(),
            flagged = false,
            anomalies = emptyList(),
        )
        WorkItemStore(sharedDatabaseForTests()).replaceWorkItem(connId, normalized, now = 5_000L)

        val inspection = admin.get("/api/v1/data-sources/$connId/raw-issues/ENG-44").body<RawIssueInspection>()
        val workItem = assertNotNull(inspection.workItem)
        assertEquals("ENG-44", workItem.issueKey)
        assertEquals("In Progress", workItem.statusName)
        assertEquals(StatusCategory.IN_PROGRESS, workItem.statusCategory)
        assertEquals("acc-1", workItem.assigneeAccountId)
        assertEquals(1, inspection.statusIntervals.size)
        assertEquals(1, inspection.fieldIntervals.size)
        assertTrue(inspection.anomalies.isEmpty())
    }
}
