package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.LeaseLostException
import ch.nokillswit.ingest.PurgeStep
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.IssuesCursor
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraSyncDependencies
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.Scenario
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val migrated = AtomicBoolean(false)

// See IngestWorkerTest.kt's identical note: this suite drives ch.nokillswit.jira.JiraConnector and
// its streams directly (deterministic, no worker/scheduler timing) against sharedDatabaseForTests(),
// so nothing else in this JVM fork is guaranteed to have run Flyway first.
private fun ensureMigrated() {
    if (migrated.compareAndSet(false, true)) {
        org.flywaydb.core.Flyway.configure()
            .dataSource(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}

private val IN_SCOPE_PROJECT_KEYS = listOf("FLO", "PLT", "GTM", "OPS")
private val ISSUES_TEST_JSON = Json { ignoreUnknownKeys = true }

/**
 * `jira/JiraConnector.kt`'s `run` (v0.2.0 plan §7/§11/§12 item 6): REFERENCE then ISSUES, driven
 * against the SAME in-JVM WireMock (`JiraStubServer`) fixture the compose stack serves
 * (`sample-data/README.md`), asserting exact counts against `sample-data/jira/expected.json` where
 * that file has them, and hand-derived-from-the-stub counts for reference entities (the fixture's
 * reference data is scenario-independent and fixed, so these counts are exact, not approximate).
 *
 * The backfill/second-sync assertions drive the full [JiraConnector] (REFERENCE + ISSUES, per
 * plan). The fault-injection/CURSOR_EXPIRED/lease-loss assertions are about the ISSUES cursor's
 * OWN resume mechanics specifically, so they drive `JiraIssuesStream` directly — same production
 * code, same real HTTP against the stub, but without re-running the (unrelated) REFERENCE pass on
 * every call, which would make the fault-injection timing noisier without adding coverage.
 */
class JiraSyncPipelineTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    private fun rawStore() = JiraRawStore(sharedDatabaseForTests())
    private fun cursors() = SyncCursorsService(sharedDatabaseForTests())

    private suspend fun createConnection(
        dataSources: DataSourceService,
        projectKeys: List<String> = IN_SCOPE_PROJECT_KEYS,
        backfillFrom: String = "2025-09-01",
    ): UInt = dataSources.create(
        DataSourceRequest(
            name = unique("jira-pipeline"),
            enabled = true,
            syncIntervalMinutes = 60,
            backfillFrom = backfillFrom,
            reconcileHourUtc = 3,
            jira = JiraConnectionRequest(
                siteUrl = "https://${unique("site").lowercase()}.atlassian.net",
                email = "svc-${unique("acct")}@example.com",
                apiToken = "token-${UUID.randomUUID()}",
                projectKeys = projectKeys,
                authScheme = JiraAuthScheme.BASIC,
            ),
        ),
    )

    private fun buildJiraHttp(maxRetries: Int = 4): JiraHttp {
        val httpClient = HttpClient(OkHttp) {
            engine { preconfigured = okhttp3.OkHttpClient() }
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 10_000
                connectTimeoutMillis = 10_000
            }
        }
        return JiraHttp(httpClient, maxRetries, maxResponseBytes = 33_554_432L, maxConcurrentRequests = 4)
    }

    /** A ready [JiraClient] (its ONE unauthenticated `resolveCloudId()` call already made) against the shared [JiraStubServer]. */
    private suspend fun buildClient(maxRetries: Int = 4): JiraClient {
        val stubBaseUrl = JiraStubServer.start()
        val client = HttpJiraClient(buildJiraHttp(maxRetries), stubBaseUrl, stubBaseUrl, "svc@example.com", "token", JiraAuthScheme.BASIC)
        client.resolveCloudId()
        return client
    }

    private fun buildConnector(maxRetries: Int = 4): JiraConnector = JiraConnector(
        newClient = { _, email, apiToken, authScheme ->
            val stubBaseUrl = JiraStubServer.start()
            HttpJiraClient(buildJiraHttp(maxRetries), stubBaseUrl, stubBaseUrl, email, apiToken, authScheme)
        },
        sync = JiraSyncDependencies(
            dataSources = dataSources(),
            rawStore = rawStore(),
            cursors = cursors(),
            database = sharedDatabaseForTests(),
            incrementalOverlapMinutes = 10,
            issuesPageSize = 100,
        ),
    )

    private fun claimFor(connId: UInt) = SyncJobClaim(
        id = 1u,
        connectionId = connId,
        connectorKind = DataSourceKind.JIRA_CLOUD,
        kind = SyncJobKind.SYNC,
        attempt = 1,
        maxAttempts = 3,
        syncIntervalMinutes = 60,
    )

    private suspend fun runConnectorOnce(connector: JiraConnector, connId: UInt) {
        connector.run(SyncJobRunContext(claimFor(connId)) { true })
    }

    private suspend fun issueRows(connId: UInt): List<org.jetbrains.exposed.v1.core.ResultRow> =
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }.toList()
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.shaChangedFetched(): Triple<String, Long, Long> = Triple(
        this[JiraRawStore.Issues.sha256],
        this[JiraRawStore.Issues.changedAt],
        this[JiraRawStore.Issues.fetchedAt],
    )

    private suspend fun entityCounts(connId: UInt): Map<String, Int> = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Entities.selectAll().where { JiraRawStore.Entities.connectionId eq connId }
            .toList().groupingBy { it[JiraRawStore.Entities.kind] }.eachCount()
    }

    @Test
    fun `backfill stores exactly the in-scope issues and reference entities, never the out-of-scope project`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val connector = buildConnector()

        runConnectorOnce(connector, connId)

        val rows = issueRows(connId)
        assertEquals(1200, rows.size, "sample-data/jira/expected.json issues.totalInScope")
        val perProject = rows.groupingBy { it[JiraRawStore.Issues.projectKey] }.eachCount()
        assertEquals(mapOf("FLO" to 400, "PLT" to 350, "GTM" to 300, "OPS" to 150), perProject)
        assertFalse(perProject.containsKey("SEC"), "the out-of-scope project's issues must never be stored")

        val entities = entityCounts(connId)
        assertEquals(
            mapOf(
                "FIELD" to 16,
                "STATUS" to 6,
                "STATUS_CATEGORY" to 4,
                "PROJECT" to 5,
                "PROJECT_STATUSES" to 4,
                "ISSUE_TYPE" to 5,
                "PRIORITY" to 5,
                "RESOLUTION" to 4,
                "ISSUE_LINK_TYPE" to 3,
                "USER" to 30,
                "BOARD" to 4,
                "BOARD_CONFIGURATION" to 4,
                "SPRINT" to 84,
            ),
            entities,
        )
        assertNull(cursors().get(connId, "reference"), "a completed REFERENCE pass clears its own cursor")
        val issuesCursor = assertNotNull(cursors().get(connId, "issues"))
        val decoded = ISSUES_TEST_JSON.decodeFromString<IssuesCursor>(issuesCursor.cursor)
        assertNull(decoded.nextPageToken, "a completed ISSUES run clears its page token")
    }

    @Test
    fun `a second SYNC changes no payload or hash - only fetched_at moves`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val connector = buildConnector()

        runConnectorOnce(connector, connId)
        val before = issueRows(connId).associate { it[JiraRawStore.Issues.issueId] to it.shaChangedFetched() }

        runConnectorOnce(connector, connId)
        val after = issueRows(connId).associate { it[JiraRawStore.Issues.issueId] to it.shaChangedFetched() }

        assertEquals(before.keys, after.keys, "no issue may appear or disappear on an unchanged re-sync")
        before.forEach { (issueId, beforeTriple) ->
            val afterTriple = after.getValue(issueId)
            assertEquals(beforeTriple.first, afterTriple.first, "sha256 must not change")
            assertEquals(beforeTriple.second, afterTriple.second, "changed_at must not move on an unchanged re-fetch")
            assertTrue(afterTriple.third >= beforeTriple.third, "fetched_at must not go backward")
        }
    }

    @Test
    fun `a PURGE step removes every raw row for the connection`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val store = rawStore()
        val connector = JiraConnector(
            newClient = { _, _, _, _ -> error("PURGE never calls the client") },
            sync = JiraSyncDependencies(
                ds, store, cursors(), sharedDatabaseForTests(),
                incrementalOverlapMinutes = 10, issuesPageSize = 100,
            ),
        )
        val samplePayload = """{"id":"1","key":"FLO-1","fields":{"project":{"id":"1","key":"FLO"}}}"""
        store.upsertIssue(connId, ch.nokillswit.jira.RawIssueInput(1L, "FLO-1", 1L, "FLO", 1_000, samplePayload), 1_000)
        store.upsertEntity(connId, "FIELD", "summary", """{"id":"summary"}""", 1_000)

        val purgeSteps: List<PurgeStep> = connector.purgeSteps
        assertTrue(purgeSteps.isNotEmpty())
        purgeSteps.forEach { it.purge(connId) }

        assertEquals(0L, store.countIssues(connId))
        assertEquals(emptyMap(), entityCounts(connId))
    }

    @Test
    fun `a fault-injected page fails the ISSUES run, and the next run resumes with identical totals`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val store = rawStore()
        val cursorService = cursors()
        val client = buildClient(maxRetries = 0)
        val stream = JiraIssuesStream(client, store, IN_SCOPE_PROJECT_KEYS, 0L, 10L, 100)
        val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = { true })

        val fault = JiraStubServer.addOverride(
            get(urlPathMatching(".*/rest/api/3/search/jql"))
                .withQueryParam("nextPageToken", equalTo("Started-page-3"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500)),
        )
        try {
            assertFailsWith<JiraFetchException> { stream.run(context) }
        } finally {
            JiraStubServer.removeOverride(fault)
        }

        assertEquals(200L, store.countIssues(connId), "only the two pages before the fault were written")
        val cursorAfterFailure = assertNotNull(cursorService.get(connId, "issues"))
        assertEquals("Started-page-3", ISSUES_TEST_JSON.decodeFromString<IssuesCursor>(cursorAfterFailure.cursor).nextPageToken)

        // "Upstream recovers": the fault is gone, the SAME stream instance resumes from the persisted cursor.
        stream.run(context)
        assertEquals(1200L, store.countIssues(connId), "the resumed run must reach the same final total, with no duplicates")
        val finalCursor = assertNotNull(cursorService.get(connId, "issues"))
        assertNull(ISSUES_TEST_JSON.decodeFromString<IssuesCursor>(finalCursor.cursor).nextPageToken)
    }

    @Test
    fun `CURSOR_EXPIRED on a page drops the token and restarts from the watermark, completing successfully`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val store = rawStore()
        val cursorService = cursors()
        val client = buildClient(maxRetries = 0)
        val stream = JiraIssuesStream(client, store, IN_SCOPE_PROJECT_KEYS, 0L, 10L, 100)
        val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = { true })

        // A ONE-SHOT 410 via a dedicated WireMock scenario: it fires exactly once (then flips its
        // own state away), so the retried page-2 request falls through to the real mapping and
        // succeeds — proving CURSOR_EXPIRED's restart is transparent within a single stream run.
        val scenarioName = unique("cursor-expiry")
        val expired = JiraStubServer.addOverride(
            get(urlPathMatching(".*/rest/api/3/search/jql"))
                .withQueryParam("nextPageToken", equalTo("Started-page-2"))
                .inScenario(scenarioName)
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered")
                .atPriority(1)
                .willReturn(aResponse().withStatus(410)),
        )
        try {
            stream.run(context)
        } finally {
            JiraStubServer.removeOverride(expired)
        }

        assertEquals(1200L, store.countIssues(connId))
        val finalCursor = assertNotNull(cursorService.get(connId, "issues"))
        val decoded = ISSUES_TEST_JSON.decodeFromString<IssuesCursor>(finalCursor.cursor)
        assertNull(decoded.nextPageToken)
        assertEquals(decoded.watermarkAt, decoded.runStartedAt)
    }

    @Test
    fun `a lease loss mid-run stops the ISSUES stream without ever fetching the next page`() = runBlocking {
        ensureMigrated()
        val ds = dataSources()
        val connId = createConnection(ds)
        val store = rawStore()
        val cursorService = cursors()
        val client = buildClient()
        val stream = JiraIssuesStream(client, store, IN_SCOPE_PROJECT_KEYS, 0L, 10L, 100)
        var heartbeats = 0
        val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = {
            heartbeats++
            heartbeats <= 2
        })

        assertFailsWith<LeaseLostException> { stream.run(context) }

        // heartbeat() runs AFTER each page's write commits, so the page whose OWN heartbeat call
        // reports the lease lost has already been written (a heartbeat can only report a status
        // that was already true when the LAST completed unit of work finished) — pages 1 and 2
        // report true, page 3's heartbeat (the 3rd call) reports false, so exactly 3 pages land
        // and the 4th is never even requested.
        assertEquals(300L, store.countIssues(connId), "pages 1-3 committed before the lease was reported lost")
        val cursor = assertNotNull(cursorService.get(connId, "issues"))
        assertEquals(
            "Started-page-4",
            ISSUES_TEST_JSON.decodeFromString<IssuesCursor>(cursor.cursor).nextPageToken,
            "the cursor must point at the NEXT page to fetch — that page must never have been requested",
        )
    }
}
