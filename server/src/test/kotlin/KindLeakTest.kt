package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.ConnectionTarget
import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.DATA_SOURCE_KIND_UNSUPPORTED
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourcePageResponse
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.DataProfile
import ch.nokillswit.ingest.IngestConfig
import ch.nokillswit.ingest.IngestWorker
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncStatusResponse
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.reports.CodeActivityReport
import ch.nokillswit.reports.HomeOverview
import ch.nokillswit.reports.ReportFilters
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The clock the worker ticks with (2024-01-01T01:00:00Z), as in `IngestWorkerTest`. */
private val CLOCK_MILLIS = java.time.Instant.parse("2024-01-01T01:00:00Z").toEpochMilli()

/**
 * The kind-leak guards (`.claude/docs/gitlab-integration.md` "The connector seam", `.claude/docs/ingestion.md` "Connector kinds"): a
 * `GITLAB` row, inserted straight into `source_connections` (V40 allows it; no route creates one yet), must never reach a Jira- or
 * metrics-only path. One test per leak point: the Jira scope rules, DERIVE chaining and fan-out, the DERIVE backstop, the report and Home
 * scopes, the metrics-config routes, the raw-issue inspector, the profile route and the data-source API itself. Every case also pins a
 * JIRA control where the assertion would otherwise pass vacuously.
 */
class KindLeakTest {
    private val gitlabRows = mutableListOf<UInt>()
    private val jiraRows = mutableListOf<UInt>()
    private val workerId = "kind-leak-worker-${UUID.randomUUID().toString().take(8)}"

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        gitlabRows.forEach(GitLabRowFixture::remove)
        jiraRows.forEach { SyncedStubFixture.dataSources().delete(it) }
    }

    private suspend fun gitlab(enabled: Boolean = false, settings: String = GitLabRowFixture.MINIMAL_SETTINGS): UInt =
        GitLabRowFixture.insert("kindleak", enabled, settings).also { gitlabRows += it }

    private suspend fun jira(enabled: Boolean = false): UInt =
        SyncedStubFixture.createConnection(namePrefix = "kindleak-jira", enabled = enabled).also { jiraRows += it }

    private fun pagingAll() = PageRequest(page = 1, pageSize = 100, sort = emptyList())

    private class CountingConnector(override val kind: DataSourceKind) : Connector {
        val runs = AtomicInteger()
        override suspend fun testConnection(target: ConnectionTarget): ConnectionTestResult = error("not used")
        override suspend fun run(context: SyncJobRunContext) {
            runs.incrementAndGet()
        }
    }

    private fun config() = IngestConfig(
        schedulerTickSeconds = 15, workerSlots = 2, leaseSeconds = 300, jobRetentionDays = 90, purgeGraceDays = 7, workerId = workerId,
    )

    private val derivesRun = AtomicInteger()

    private fun worker(jobs: SyncJobsService, connectors: Map<DataSourceKind, Connector>): IngestWorker {
        val handlers = JobHandlerRegistry().apply { register(SyncJobKind.DERIVE) { derivesRun.incrementAndGet(); null } }
        return IngestWorker(jobs, SyncedStubFixture.dataSources(), handlers, connectors, config()) { CLOCK_MILLIS }
    }

    // ---------------------------------------------------------------- ingest/

    @Test
    fun `the Jira scope rules read Jira rows only - a GITLAB row neither names the site nor protects project keys`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("kindleak-scope", FlowUserRole.ADMIN)
        val key = uniqueProjectKey()
        // Settings that WOULD conflict if decoded as Jira: another site, and the very key the new source asks for. The kind decides.
        val lookalike = """{"siteUrl":"https://other-site.atlassian.net","email":"x@example.com","projectKeys":["$key"],""" +
            """"authScheme":"BASIC","baseUrl":"https://gitlab.example.com","groupPaths":["acme"]}"""
        runBlocking { gitlab(enabled = true, settings = lookalike) }

        val request = DataSourceRequest(
            name = "kindleak-scope-${UUID.randomUUID().toString().take(8)}",
            enabled = false,
            syncIntervalMinutes = 60,
            reconcileHourUtc = 3,
            jira = JiraConnectionRequest(
                TEST_JIRA_SITE_URL, "svc@example.com", "tok-${UUID.randomUUID()}", listOf(key), JiraAuthScheme.BASIC,
            ),
        )
        val created = admin.postJson("/api/v1/data-sources", request)
        // 201, not the OTHER_SITE / PROJECT_OVERLAP 409: the GITLAB row was skipped by kind.
        assertEquals(HttpStatusCode.Created, created.status)
        jiraRows += created.body<DataSourceResponse>().id
        // The control: the same key again IS a Jira overlap, so the rule itself still fires between Jira rows.
        val again = admin.postJson("/api/v1/data-sources", request.copy(name = request.name + "-b"))
        assertEquals(HttpStatusCode.Conflict, again.status)
    }

    @Test
    fun `a GITLAB SYNC chains no DERIVE, a Jira SYNC does`() = runBlocking<Unit> {
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3, { CLOCK_MILLIS })
        val gitlabConnector = CountingConnector(DataSourceKind.GITLAB)
        val jiraConnector = CountingConnector(DataSourceKind.JIRA_CLOUD)
        val worker = worker(jobs, mapOf(DataSourceKind.GITLAB to gitlabConnector, DataSourceKind.JIRA_CLOUD to jiraConnector))
        val gitlabId = gitlab(enabled = true)
        val jiraId = jira(enabled = true)
        jobs.cancelOpenForConnection(jiraId) // the create's own fan-out, if any

        val ids = mapOf(
            gitlabId to jobs.requestJob(gitlabId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId,
            jiraId to jobs.requestJob(jiraId, SyncJobKind.SYNC, requestedByUserId = 1u, configRevision = 1L).jobId,
        )
        withOnlyConnections(ids.keys, CLOCK_MILLIS) {
            repeat(2) {
                val claim = assertNotNull(jobs.claim(workerId, leaseSeconds = 300, now = CLOCK_MILLIS))
                worker.runJob(claim)
            }
        }

        assertEquals(1, gitlabConnector.runs.get())
        assertEquals(1, jiraConnector.runs.get())
        for ((connection, job) in ids) assertEquals(SyncJobStatus.SUCCEEDED, jobs.read(connection, job)?.status)
        suspend fun derives(id: UInt) = jobs.list(id, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
        assertEquals(emptyList(), derives(gitlabId), "a GITLAB SYNC must chain no DERIVE")
        assertEquals(1, derives(jiraId).size, "the control: the Jira SYNC chained its DERIVE")
    }

    @Test
    fun `a metrics-wide revision bump fans DERIVE out to Jira connections only`() = runBlocking<Unit> {
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3, { CLOCK_MILLIS })
        val dataSources = SyncedStubFixture.dataSources()
        val settings = MetricsSettingsService(sharedDatabaseForTests(), dataSources, jobs)
        val gitlabId = gitlab(enabled = true)
        val jiraId = jira(enabled = true)
        jobs.cancelOpenForConnection(jiraId)

        assertTrue(jiraId in dataSources.enabledActiveConnections().map { it.id }, "the control is in the fan-out set")
        assertFalse(gitlabId in dataSources.enabledActiveConnections().map { it.id })

        settings.bumpRevision()
        suspend fun derives(id: UInt) = jobs.list(id, SyncJobListFilter(kind = SyncJobKind.DERIVE), pagingAll()).items
        assertEquals(emptyList(), derives(gitlabId), "the fan-out skipped the GITLAB connection")
        assertEquals(1, derives(jiraId).size, "the control: the Jira connection got its DERIVE")
        jobs.cancelOpenForConnection(jiraId)
    }

    @Test
    fun `a DERIVE job that somehow exists for a GITLAB connection fails instead of deriving`() = runBlocking<Unit> {
        val jobs = SyncJobsService(sharedDatabaseForTests(), 3, { CLOCK_MILLIS })
        val worker = worker(jobs, emptyMap())
        val gitlabId = gitlab(enabled = true)
        val job = jobs.requestJob(gitlabId, SyncJobKind.DERIVE, requestedByUserId = 1u, configRevision = 1L).jobId
        val claim = withOnlyConnections(setOf(gitlabId), CLOCK_MILLIS) { jobs.claim(workerId, leaseSeconds = 300, now = CLOCK_MILLIS) }

        worker.runJob(assertNotNull(claim))

        val stored = assertNotNull(jobs.read(gitlabId, job))
        assertEquals(SyncJobStatus.FAILED, stored.status)
        assertEquals("RUN_FAILED", stored.errorCode)
        assertEquals(0, derivesRun.get(), "the DERIVE handler never ran")
    }

    // ---------------------------------------------------------------- the data-source API

    @Test
    fun `the data-source API serves a GITLAB row with its gitlab block and no jira block, never a 500`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("kindleak-ds", FlowUserRole.ADMIN)
        val (gitlabId, jiraId) = runBlocking { gitlab() to jira() }

        // By name, not by page: the shared table holds every other class's rows too.
        val jiraPage = admin.get("/api/v1/data-sources?name=${nameOf(jiraId)}").body<DataSourcePageResponse>()
        assertEquals(listOf(jiraId), jiraPage.items.map { it.id }, "the control: the Jira row is listed")
        assertNull(jiraPage.items.single().gitlab, "a Jira row has no gitlab block")
        val gitlabPage = admin.get("/api/v1/data-sources?name=${nameOf(gitlabId)}").body<DataSourcePageResponse>()
        val listed = gitlabPage.items.single()
        assertEquals(gitlabId, listed.id)
        assertEquals(DataSourceKind.GITLAB, listed.kind)
        assertNull(listed.jira, "a GITLAB row has no jira block")
        val gitlab = assertNotNull(listed.gitlab)
        assertEquals("https://gitlab.example.com", gitlab.baseUrl)
        assertEquals(listOf("acme/platform"), gitlab.groupPaths)
        assertFalse(gitlab.hasToken, "the fixture row stores no secret")
        assertEquals(ch.nokillswit.ingest.DEFAULT_PATH_EXCLUSIONS, gitlab.pathExclusions, "a row without the key follows the defaults")
        val read = admin.get("/api/v1/data-sources/$gitlabId")
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(listed.copy(status = listed.status.copy(runningJobId = null)), read.body<DataSourceResponse>())
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/data-sources/$jiraId").status)
    }

    private fun nameOf(id: UInt): String = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT name FROM source_connections WHERE id = ?").use { st ->
            st.setInt(1, id.toInt())
            st.executeQuery().use { rs -> check(rs.next()); rs.getString(1) }
        }
    }

    // ---------------------------------------------------------------- reports/

    @Test
    fun `report filters, an explicit connectionId and the Home overview never see a GITLAB connection`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("kindleak-reports", FlowUserRole.USER)
        val (gitlabId, jiraId) = runBlocking { gitlab(enabled = true) to jira() }

        val filters = user.get("/api/v1/reports/filters").body<ReportFilters>()
        assertTrue(filters.connections.any { it.id == jiraId }, "the control: the Jira connection is a filter choice")
        assertTrue(filters.connections.none { it.id == gitlabId })

        val home = user.get("/api/v1/reports/home").body<HomeOverview>()
        assertTrue(home.freshness.any { it.connectionId == jiraId }, "the control: the Jira connection is in the freshness list")
        assertTrue(home.freshness.none { it.connectionId == gitlabId })

        // An explicit connectionId of a GITLAB connection is exactly the answer for one that does not exist (nothing is disclosed).
        suspend fun answer(id: UInt): Pair<HttpStatusCode, String> {
            val response = user.get("/api/v1/reports/cycle-time?domain=kindleak&connectionId=$id")
            return response.status to response.body<ProblemDetail>().detail!!.replace(id.toString(), "<id>")
        }
        val missing = answer(987_654_321u)
        assertEquals(HttpStatusCode.BadRequest, missing.first)
        assertTrue(missing.second.contains("Unknown or inactive connectionId"), missing.second)
        assertEquals(missing, answer(gitlabId))
    }

    @Test
    fun `the code activity report is the mirror image - it reads GITLAB sources only, and a Jira connectionId is the unknown one`() =
        testApplication {
            usePostgresTestcontainer()
            val user = seededClient("kindleak-code", FlowUserRole.USER)
            val (gitlabId, jiraId) = runBlocking { gitlab(enabled = true) to jira() }
            val teamId = runBlocking { TestTeams.seed("kindleak-code-${System.nanoTime()}") }
            try {
                val base = "/api/v1/reports/code-activity?teamId=$teamId&from=2026-03-02&to=2026-03-15"
                val control = user.get("$base&connectionId=$gitlabId")
                assertEquals(HttpStatusCode.OK, control.status, "the control: a GITLAB source is the code report's scope")
                assertEquals(listOf(gitlabId), control.body<CodeActivityReport>().sources.map { it.connectionId })
                val all = user.get(base).body<CodeActivityReport>()
                assertTrue(all.sources.none { it.connectionId == jiraId }, "a Jira source is never in the code scope")
                assertTrue(all.sources.any { it.connectionId == gitlabId })

                suspend fun answer(id: UInt): Pair<HttpStatusCode, String> {
                    val response = user.get("$base&connectionId=$id")
                    return response.status to response.body<ProblemDetail>().detail!!.replace(id.toString(), "<id>")
                }
                val missing = answer(987_654_321u)
                assertEquals(HttpStatusCode.BadRequest, missing.first)
                assertTrue(missing.second.contains("Unknown or inactive connectionId"), missing.second)
                assertEquals(missing, answer(jiraId))
            } finally {
                runBlocking { TestTeams.service.delete(teamId) }
            }
        }

    // ---------------------------------------------------------------- ingest/ and metrics/ routes

    @Test
    fun `the metrics-config routes answer 409 data-source-kind-unsupported for a GITLAB connection, after the guard`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("kindleak-mc", FlowUserRole.ADMIN)
        val user = seededClient("kindleak-mc-user", FlowUserRole.USER)
        val gitlabId = runBlocking { gitlab() }
        val base = "/api/v1/data-sources/$gitlabId/metrics-config"

        for (response in listOf(admin.get(base), admin.putJson(base, DataSourceMetricsConfigRequest()), admin.get("$base/options"))) {
            assertEquals(HttpStatusCode.Conflict, response.status)
            val problem = response.body<ProblemDetail>()
            assertEquals(DATA_SOURCE_KIND_UNSUPPORTED, problem.type)
            assertTrue(problem.detail!!.contains("GITLAB"), problem.detail)
        }
        // 403 wins over 409 (the guard runs first), and 404 still means "no such data source".
        assertEquals(HttpStatusCode.Forbidden, user.get(base).status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/987654321/metrics-config").status)
    }

    @Test
    fun `the raw-issue inspector answers 409 for a GITLAB connection and the profile route never decodes it as Jira`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("kindleak-ri", FlowUserRole.ADMIN)
        val gitlabId = runBlocking { gitlab() }

        val inspected = admin.get("/api/v1/data-sources/$gitlabId/raw-issues/ENG-1")
        assertEquals(HttpStatusCode.Conflict, inspected.status)
        assertEquals(DATA_SOURCE_KIND_UNSUPPORTED, inspected.body<ProblemDetail>().type)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/data-sources/987654321/raw-issues/ENG-1").status)

        // The profile is per-kind (a GitLab source carries the `gitlab` section once PROFILE ran); a never-synced one is the
        // stored-and-never-computed answer for both kinds.
        val profile = admin.get("/api/v1/data-sources/$gitlabId/profile")
        assertEquals(HttpStatusCode.OK, profile.status)
        val body = profile.body<DataProfile>()
        assertNull(body.computedAt)
        assertNull(body.gitlab)
    }

    @Test
    fun `the sync-job and status routes serve a GITLAB row and the stored Test connection is 409 while GitLab is off`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("kindleak-jobs", FlowUserRole.ADMIN)
        val gitlabId = runBlocking { gitlab() }

        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/data-sources/$gitlabId/sync-jobs").status)
        val status = admin.get("/api/v1/data-sources/$gitlabId/status")
        assertEquals(HttpStatusCode.OK, status.status)
        val counts = status.body<SyncStatusResponse>().counts
        assertEquals(0L, counts.rawIssues, "a GITLAB row has no Jira raw rows")
        assertNull(counts.gitlab, "no GitLab store exists while GitLab is off in this process")
        // No connector is registered for the kind (the default test app has no GitLab host or stub): typed 409, not a Jira probe.
        val test = admin.post("/api/v1/data-sources/$gitlabId/test")
        assertEquals(HttpStatusCode.Conflict, test.status)
        assertEquals(DATA_SOURCE_KIND_UNSUPPORTED, test.body<ProblemDetail>().type)
    }
}
