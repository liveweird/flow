package ch.nokillswit

import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.SyncCounts
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.ingest.SyncStatusResponse
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.RawIssueInput
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
 * `GET /api/v1/data-sources/{id}/status` (v0.2.0 plan §9/§12 item 7, `ingest/SyncStatusRoutes.kt`):
 * ADMIN only, read-only. Seeds the raw store/cursors/jobs directly against the shared test
 * database (the same `sharedDatabaseForTests()` a running `testApplication` connects to) rather
 * than driving a real Jira sync over HTTP — this route is a pure read over state every other
 * endpoint already owns.
 */
class SyncStatusRoutesTest {
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

    @Test
    fun `non-admin gets 403`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("statusadmin", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val user = seededClient("statususer")
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/data-sources/${created.id}/status").status)
    }

    @Test
    fun `an unknown connection is 404`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("statusmissing", UserRole.ADMIN)

        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/999999999/status").status)
    }

    @Test
    fun `every declared count, cursor and job shows up after seeding the raw store directly`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("statusfull", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val connId = created.id

        val rawStore = JiraRawStore(sharedDatabaseForTests())
        val cursors = SyncCursorsService(sharedDatabaseForTests())
        val jobs = SyncJobsService(sharedDatabaseForTests(), defaultMaxAttempts = 3)

        val payload1 = """{"id":"1","key":"ENG-1","fields":{"project":{"id":"1","key":"ENG"}}}"""
        val payload2 = """{"id":"2","key":"ENG-2","fields":{"project":{"id":"1","key":"ENG"}}}"""
        rawStore.upsertIssue(connId, RawIssueInput(1L, "ENG-1", 1L, "ENG", 1_000, payload1), 1_000)
        rawStore.upsertIssue(connId, RawIssueInput(2L, "ENG-2", 1L, "ENG", 1_000, payload2), 1_000)
        rawStore.markIssueDeleted(connId, 2L, 2_000)
        rawStore.upsertEntity(connId, "FIELD", "summary", """{"id":"summary"}""", 1_000)
        rawStore.insertChangelog(connId, 500_900L, 1L, 1_000, "acc-1", """{"id":"500900"}""", 1_000)
        rawStore.upsertWorklog(connId, 1L, 700_900L, 1_000, """{"id":"700900","issueId":"1"}""", 1_000)
        cursors.put(connId, "issues", """{"watermarkAt":1000}""", watermarkAt = 1_000L)
        val requested = jobs.requestJob(connId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = created.configRevision)

        val response = admin.get("/api/v1/data-sources/$connId/status")
        assertEquals(HttpStatusCode.OK, response.status)
        val status = response.body<SyncStatusResponse>()

        assertEquals(connId, status.connection.id)
        assertEquals(1, status.cursors.size)
        assertEquals("issues", status.cursors.single().stream)
        assertEquals(1_000L, status.cursors.single().watermarkAt)

        assertEquals(
            SyncCounts(
                rawIssues = 2L,
                tombstonedDeleted = 1L,
                tombstonedMovedOut = 0L,
                changelogs = 1L,
                worklogs = 1L,
                entitiesByKind = mapOf("FIELD" to 1L),
                needsProcessing = 2L,
            ),
            status.counts,
        )

        assertEquals(1, status.lastJobs.size)
        val lastSync = assertNotNull(status.lastJobs[SyncJobKind.SYNC.name])
        assertEquals(requested.jobId, lastSync.id)
        assertNull(status.currentJob, "the job is still PENDING, never claimed - no currentJob yet")
        assertTrue(status.lastJobs.keys.all { it in SyncJobKind.entries.map(SyncJobKind::name) })
    }
}
