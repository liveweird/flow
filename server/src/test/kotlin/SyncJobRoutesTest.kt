package ch.nokillswit

import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.SyncJobActionResult
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobPageResponse
import ch.nokillswit.ingest.SyncJobResponse
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The sync-job API (v0.2.0 plan §9, `ingest/SyncJobRoutes.kt`): ADMIN-only, `requireAdmin` before
 * `call.receive()`, PURGE rejected as requester-initiated, coalescing surfaced on the wire, and
 * every cancel path (PENDING now / RUNNING requested / terminal 409).
 */
class SyncJobRoutesTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun jira() = JiraConnectionRequest(
        siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
        email = "svc-${unique("acct")}@example.com",
        apiToken = "token-${UUID.randomUUID()}",
        projectKeys = listOf("ENG"),
        authScheme = JiraAuthScheme.BASIC,
    )

    private fun dataSourceRequest(enabled: Boolean = true) = ch.nokillswit.ingest.DataSourceRequest(
        name = unique("conn"),
        enabled = enabled,
        syncIntervalMinutes = 60,
        backfillFrom = null,
        reconcileHourUtc = 3,
        jira = jira(),
    )

    @Test
    fun `non-admin gets 403 before the body decodes`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjadmin", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val client = seededClient("sjuser")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/data-sources/${created.id}/sync-jobs").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/data-sources/${created.id}/sync-jobs/1").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/data-sources/${created.id}/sync-jobs/1/cancel").status)
        val malformed = client.post("/api/v1/data-sources/${created.id}/sync-jobs") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, malformed.status, "the guard runs before the body decodes")
    }

    @Test
    fun `requesting a SYNC job enqueues it, 202`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjrequest", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val response = admin.postJson(
            "/api/v1/data-sources/${created.id}/sync-jobs",
            ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC),
        )
        assertEquals(HttpStatusCode.Accepted, response.status)
        val result = response.body<SyncJobActionResult>()
        assertEquals(false, result.coalesced)
        assertEquals(SyncJobKind.SYNC, result.job.kind)
        assertEquals(SyncJobStatus.PENDING, result.job.status)
        assertEquals(created.id, result.job.connectionId)
    }

    @Test
    fun `a second request while one is open is coalesced`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjcoalesce", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val path = "/api/v1/data-sources/${created.id}/sync-jobs"

        val first = admin.postJson(path, ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC)).body<SyncJobActionResult>()
        val second = admin.postJson(path, ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC)).body<SyncJobActionResult>()

        assertEquals(false, first.coalesced)
        assertEquals(true, second.coalesced)
        assertEquals(first.job.id, second.job.id)
    }

    @Test
    fun `PURGE is rejected as requester-initiated, 400`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjpurge", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()

        val response = admin.postJson(
            "/api/v1/data-sources/${created.id}/sync-jobs",
            ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.PURGE),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a disabled connection rejects a sync-job request, 400`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjdisabled", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest(enabled = false)).body<DataSourceResponse>()

        val response = admin.postJson(
            "/api/v1/data-sources/${created.id}/sync-jobs",
            ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC),
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `requesting against a missing data source is 404`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjmissing", UserRole.ADMIN)
        val response = admin.postJson(
            "/api/v1/data-sources/999999/sync-jobs",
            ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC),
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/999999/sync-jobs").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/999999/sync-jobs/1").status)
    }

    @Test
    fun `list supports paging, the kind and status filters, and 404s a missing job`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjlist", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val base = "/api/v1/data-sources/${created.id}/sync-jobs"

        val sync = admin.postJson(base, ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC)).body<SyncJobActionResult>().job
        val reconcile = admin.postJson(base, ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.RECONCILE)).body<SyncJobActionResult>().job

        val all = admin.get(base).body<SyncJobPageResponse>()
        assertEquals(2, all.total)

        val onlySync = admin.get("$base?kind=SYNC").body<SyncJobPageResponse>()
        assertEquals(listOf(sync.id), onlySync.items.map { it.id })

        val onlyPending = admin.get("$base?status=PENDING").body<SyncJobPageResponse>()
        assertEquals(2, onlyPending.total)

        val fetched = admin.get("$base/${reconcile.id}").body<SyncJobResponse>()
        assertEquals(reconcile.id, fetched.id)

        assertEquals(HttpStatusCode.NotFound, admin.get("$base/999999").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("$base?sort=unknownField").status)
    }

    @Test
    fun `cancelling a PENDING job cancels it immediately`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjcancelpending", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val job = admin.postJson(
            "/api/v1/data-sources/${created.id}/sync-jobs",
            ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC),
        ).body<SyncJobActionResult>().job

        val cancel = admin.post("/api/v1/data-sources/${created.id}/sync-jobs/${job.id}/cancel")
        assertEquals(HttpStatusCode.Accepted, cancel.status)
        assertEquals(SyncJobStatus.CANCELLED, cancel.body<SyncJobResponse>().status)

        // Already terminal now — a second cancel is 409.
        assertEquals(HttpStatusCode.Conflict, admin.post("/api/v1/data-sources/${created.id}/sync-jobs/${job.id}/cancel").status)
    }

    @Test
    fun `cancelling a RUNNING job sets cancel_requested_at rather than cancelling it outright`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjcancelrunning", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val job = admin.postJson(
            "/api/v1/data-sources/${created.id}/sync-jobs",
            ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC),
        ).body<SyncJobActionResult>().job

        // Simulate the worker claiming it directly (no worker runs in this HTTP-only test, and
        // SyncJobsService.claim() picks the GLOBAL front of the queue across the whole shared
        // test database — not necessarily THIS job — so flip THIS row's status directly instead).
        markJobRunning(job.id)

        val cancel = admin.post("/api/v1/data-sources/${created.id}/sync-jobs/${job.id}/cancel")
        assertEquals(HttpStatusCode.Accepted, cancel.status)
        val fetched = admin.get("/api/v1/data-sources/${created.id}/sync-jobs/${job.id}").body<SyncJobResponse>()
        assertEquals(SyncJobStatus.RUNNING, fetched.status, "a RUNNING job is not force-stopped by the request")
        assertNotNull(fetched.cancelRequestedAt)
    }

    @Test
    fun `cancelling a missing job is 404`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjcancelmissing", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/data-sources/${created.id}/sync-jobs/999999/cancel").status)
    }

    @Test
    fun `mutations audit sync_job requested and cancel_requested`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
            val job = admin.postJson(
                "/api/v1/data-sources/${created.id}/sync-jobs",
                ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC),
            ).body<SyncJobActionResult>().job
            assertNotNull(capture.awaitEvent { it.message == "sync_job.requested" && it.hasKeyValue("jobId", job.id.toLong()) })

            admin.post("/api/v1/data-sources/${created.id}/sync-jobs/${job.id}/cancel")
            assertNotNull(capture.awaitEvent { it.message == "sync_job.cancel_requested" && it.hasKeyValue("jobId", job.id.toLong()) })
        }
    }

    @Test
    fun `deleting a data source cancels its open jobs`() = testApplication {
        configureApp("app.role" to "web")
        startApplication()
        val admin = seededClient("sjdelete", UserRole.ADMIN)
        val created = admin.postJson("/api/v1/data-sources", dataSourceRequest()).body<DataSourceResponse>()
        val job = admin.postJson(
            "/api/v1/data-sources/${created.id}/sync-jobs",
            ch.nokillswit.ingest.SyncJobRequest(SyncJobKind.SYNC),
        ).body<SyncJobActionResult>().job

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/data-sources/${created.id}").status)

        // The connection is now soft-deleted (its own GET routes 404 it) — read the job directly.
        val directJobs = SyncJobsService(sharedDatabaseForTests(), defaultMaxAttempts = 3)
        val cancelled = directJobs.read(created.id, job.id)
        assertNotNull(cancelled)
        assertEquals(SyncJobStatus.CANCELLED, cancelled.status)
    }

    /**
     * Flips a specific job row straight to RUNNING — a stand-in for the worker's claim, without
     * going through [SyncJobsService.claim]'s GLOBAL queue-front semantics (this shared test
     * database accumulates PENDING rows from other tests, so claim() may not pick THIS job).
     */
    private suspend fun markJobRunning(jobId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            val jobs = SyncJobsService.Jobs
            jobs.update({ jobs.id eq jobId }) {
                it[status] = SyncJobStatus.RUNNING.name
                it[leaseOwner] = "test-worker"
                it[leaseUntil] = System.currentTimeMillis() + 300_000
            }
        }
    }
}
