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
import ch.nokillswit.jira.ChangelogsCursor
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.IssuesCursor
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReferenceStream
import ch.nokillswit.jira.JiraSyncDependencies
import ch.nokillswit.jira.JiraWorklogStream
import ch.nokillswit.norm.WorkItemStore
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val IN_SCOPE_PROJECT_KEYS = listOf("FLO", "PLT", "GTM", "OPS")
private val ISSUES_TEST_JSON = Json { ignoreUnknownKeys = true }

/**
 * `sample-data/jira/expected.json`'s in-scope-reachable/day2 facts (v0.2.0 plan §12 item 7): read
 * from the generated fixture rather than hand-copied, so a generator change can never drift
 * silently from what this suite asserts.
 */
@Serializable
private data class ExpectedChangelog(val inScopeHistories: Long)

@Serializable
private data class ExpectedWorklogs(val inScopeCount: Long)

@Serializable
private data class ExpectedDay2(val deletedIssueId: String, val movedIssueId: String, val movedToProjectKey: String)

@Serializable
private data class ExpectedIssues(val totalInScope: Long, val perProject: Map<String, Long>)

@Serializable
private data class ExpectedFixture(
    val issues: ExpectedIssues,
    val changelog: ExpectedChangelog,
    val worklogs: ExpectedWorklogs,
    val day2: ExpectedDay2,
)

private val EXPECTED_FIXTURE_JSON = Json { ignoreUnknownKeys = true }

private val expectedFixture: ExpectedFixture by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    EXPECTED_FIXTURE_JSON.decodeFromString(file.readText())
}

/**
 * `jira/JiraConnector.kt`'s `run` (v0.2.0 plan §7/§11/§12 item 6): REFERENCE then ISSUES, driven
 * against the SAME in-JVM WireMock (`JiraStubServer`) fixture the compose stack serves
 * (`sample-data/README.md`), asserting exact counts against `sample-data/jira/expected.json` where
 * that file has them, and hand-derived-from-the-stub counts for reference entities (the fixture's
 * reference data is scenario-independent and fixed, so these counts are exact, not approximate).
 *
 * The backfill assertion reads the suite-wide `SyncedStubFixture` (`.claude/docs/testing.md`
 * "Shared synced fixture") rather than driving its own full [JiraConnector] SYNC; the
 * second-sync/day2/RECONCILE assertions clone that fixture's raw rows
 * (`SyncedStubFixture.cloneRawData`) into a connection of their own and drive a full or partial
 * [JiraConnector] run for real against it. The fault-injection/CURSOR_EXPIRED/lease-loss assertions
 * are about the ISSUES cursor's OWN resume mechanics specifically, so they drive `JiraIssuesStream`
 * directly — same production code, same real HTTP against the stub, but without re-running the
 * (unrelated) REFERENCE pass on every call, which would make the fault-injection timing noisier
 * without adding coverage.
 */
class JiraSyncPipelineTest {
    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun dataSources() = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY))
    private fun rawStore() = JiraRawStore(sharedDatabaseForTests())
    private fun cursors() = SyncCursorsService(sharedDatabaseForTests())
    private fun workItems() = WorkItemStore(sharedDatabaseForTests())

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
            workItems = workItems(),
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
        connector.run(SyncJobRunContext(claimFor(connId)) { _, _ -> true })
    }

    private suspend fun runReconcileOnce(connector: JiraConnector, connId: UInt) {
        connector.run(SyncJobRunContext(claimFor(connId).copy(kind = SyncJobKind.RECONCILE)) { _, _ -> true })
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
        // Read-only over the shared synced fixture (`.claude/docs/testing.md` "Shared synced
        // fixture") — this test's own subject (the backfill's raw counts) is exactly what the
        // fixture's own one-time SYNC already produced.
        val connId = SyncedStubFixture.connectionId()

        val rows = issueRows(connId)
        assertEquals(expectedFixture.issues.totalInScope.toInt(), rows.size, "sample-data/jira/expected.json issues.totalInScope")
        val perProject = rows.groupingBy { it[JiraRawStore.Issues.projectKey] }.eachCount()
        assertEquals(expectedFixture.issues.perProject.filterKeys { it != "SEC" }.mapValues { it.value.toInt() }, perProject)
        assertFalse(perProject.containsKey("SEC"), "the out-of-scope project's issues must never be stored")

        val entities = entityCounts(connId)
        assertEquals(
            mapOf(
                "FIELD" to 19,
                "STATUS" to 7, // the stub's six workflow statuses plus the epic-only On Hold (no issue ever sits in it)
                "STATUS_CATEGORY" to 4,
                "PROJECT" to 5,
                "PROJECT_STATUSES" to 4,
                "PROJECT_FIELDS" to 4,
                "ISSUE_TYPE" to 6,
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

        val store = rawStore()
        // expected.json's changelog.allProjectsHistories/worklogs.allProjectsTotalCount are
        // WHOLE-DATASET stats — the generator's own counters loop over every project INCLUDING the
        // out-of-scope SEC project (`sample-data/jira/generate.mjs`'s "issues" array), so they also
        // count history/worklog rows on issues A1 deliberately never fetches. changelog.inScopeHistories/
        // worklogs.inScopeCount are the in-scope-reachable totals this backfill actually stores: the
        // 23 real bulkfetch chunks' histories plus the omitted chunk's 50-issue fallback, and every
        // in-scope issue's own worklog page total.
        assertEquals(
            expectedFixture.changelog.inScopeHistories, store.countChangelogs(connId),
            "sample-data/jira/expected.json changelog.inScopeHistories (23 bulk chunks + the omitted chunk's fallback)",
        )
        assertEquals(
            expectedFixture.worklogs.inScopeCount, store.countWorklogs(connId),
            "sample-data/jira/expected.json worklogs.inScopeCount (sample-data/jira-stub's per-issue worklog pages)",
        )
        assertEquals(
            expectedFixture.issues.totalInScope.toInt(), rows.count { it[JiraRawStore.Issues.worklogsSyncedAt] != null },
            "worklogs_synced_at must be set on every in-scope issue",
        )

        // changelog.omittedBulkfetchChunkIndex (5): that chunk's 50 issues must have gone through the
        // per-issue fallback, which is the only thing that persists a "changelogs" cursor.
        val changelogsCursor = assertNotNull(
            cursors().get(connId, "changelogs"), "the omitted bulkfetch chunk must trigger the per-issue fallback",
        )
        val decodedChangelogs = ISSUES_TEST_JSON.decodeFromString<ChangelogsCursor>(changelogsCursor.cursor)
        assertNotNull(decodedChangelogs.bulkUnavailableUntil, "a bulkfetch 404 must set the fallback cursor flag")
        Unit
    }

    @Test
    fun `a second SYNC changes no payload or hash - only fetched_at moves`() = runBlocking {
        // The "before" state comes from cloning the shared fixture's already-synced raw rows
        // (`.claude/docs/testing.md` "Shared synced fixture") rather than running a first real
        // sync here too — the SYNC this test drives still runs for real over HTTP, and the
        // idempotence proof (unchanged hash, only `fetched_at` moves) is identical either way,
        // since `upsertIssue`'s diff is keyed on payload content, never on how a row got there.
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-second-sync")
        SyncedStubFixture.cloneRawData(sharedConnId, connId)
        val connector = buildConnector()

        val store = rawStore()
        val before = issueRows(connId).associate { it[JiraRawStore.Issues.issueId] to it.shaChangedFetched() }
        val changelogCountBefore = store.countChangelogs(connId)
        val worklogCountBefore = store.countWorklogs(connId)

        runConnectorOnce(connector, connId)
        val after = issueRows(connId).associate { it[JiraRawStore.Issues.issueId] to it.shaChangedFetched() }

        assertEquals(before.keys, after.keys, "no issue may appear or disappear on an unchanged re-sync")
        before.forEach { (issueId, beforeTriple) ->
            val afterTriple = after.getValue(issueId)
            assertEquals(beforeTriple.first, afterTriple.first, "sha256 must not change")
            assertEquals(beforeTriple.second, afterTriple.second, "changed_at must not move on an unchanged re-fetch")
            assertTrue(afterTriple.third >= beforeTriple.third, "fetched_at must not go backward")
        }
        assertEquals(changelogCountBefore, store.countChangelogs(connId), "a second SYNC must add no new changelog rows")
        assertEquals(worklogCountBefore, store.countWorklogs(connId), "a second SYNC must add no new worklog rows")
    }

    @Test
    fun `a PURGE step removes every raw row for the connection`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds)
        val store = rawStore()
        val connector = JiraConnector(
            newClient = { _, _, _, _ -> error("PURGE never calls the client") },
            sync = JiraSyncDependencies(
                ds, store, cursors(), sharedDatabaseForTests(), workItems(),
                incrementalOverlapMinutes = 10, issuesPageSize = 100,
            ),
        )
        val samplePayload = """{"id":"1","key":"FLO-1","fields":{"project":{"id":"1","key":"FLO"}}}"""
        store.upsertIssue(connId, ch.nokillswit.jira.RawIssueInput(1L, "FLO-1", 1L, "FLO", 1_000, samplePayload), 1_000)
        store.upsertEntity(connId, "FIELD", "summary", """{"id":"summary"}""", 1_000)
        store.insertChangelog(connId, 500_900L, 1L, 1_000, "acc-1", """{"id":"500900"}""", 1_000)
        store.upsertWorklog(connId, 1L, 700_900L, 1_000, """{"id":"700900","issueId":"1"}""", 1_000)

        val purgeSteps: List<PurgeStep> = connector.purgeSteps
        assertTrue(purgeSteps.isNotEmpty())
        purgeSteps.forEach { it.purge(connId) }

        assertEquals(0L, store.countIssues(connId))
        assertEquals(emptyMap(), entityCounts(connId))
        assertEquals(0L, store.countChangelogs(connId))
        assertEquals(0L, store.countWorklogs(connId))
    }

    @Test
    fun `day2 - new-in-scope worklogs stored, out-of-scope worklogs dropped and counted, deleted worklog tombstoned`() = runBlocking {
        // A clone of the shared fixture's raw rows stands in for the full backfill
        // (`.claude/docs/testing.md` "Shared synced fixture") — this test's SUBJECT is the WORKLOGS
        // stream alone, driven directly below, so `raw.jira_issues` merely needs to already know
        // every in-scope issue before the day2 scenario flips.
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-day2-worklogs")
        SyncedStubFixture.cloneRawData(sharedConnId, connId)
        val store = rawStore()
        val worklogCountBefore = store.countWorklogs(connId)

        JiraStubServer.setScenarioState("jira-day2", "day2")
        try {
            val client = buildClient(maxRetries = 0)
            val worklogStream = JiraWorklogStream(client, store)
            val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors(), jobHeartbeat = { _, _ -> true })
            worklogStream.run(context)

            assertEquals(
                2L, context.progressSnapshot()["worklogsOutOfScope"] ?: 0L,
                "sample-data/jira/expected.json worklogs.outOfScopeDay2FeedCount — SEC worklogs dropped",
            )

            val rows = suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Worklogs.selectAll().where { JiraRawStore.Worklogs.connectionId eq connId }.toList()
            }
            // +2 new in-scope worklogs, -1 for the one ALSO tombstoned in this same run — net +1 live.
            val liveCount = rows.count { it[JiraRawStore.Worklogs.deletedAt] == null }
            assertEquals(worklogCountBefore + 1, liveCount.toLong(), "day2NewInScopeCount net the deleted worklog")
            assertEquals(worklogCountBefore + 2, rows.size.toLong(), "day2NewInScopeCount (total rows incl. the tombstoned one)")

            val deletedWorklog = rows.single { it[JiraRawStore.Worklogs.worklogId] == 700_000L }
            assertEquals(
                30_003L, deletedWorklog[JiraRawStore.Worklogs.issueId],
                "sample-data/jira/expected.json worklogs.day2DeletedWorklogIssueId",
            )
            assertNotNull(
                deletedWorklog[JiraRawStore.Worklogs.deletedAt],
                "sample-data/jira/expected.json worklogs.day2DeletedWorklogId must be tombstoned",
            )
            Unit
        } finally {
            JiraStubServer.resetScenarios()
        }
    }

    @Test
    fun `a fault-injected page fails the ISSUES run, and the next run resumes with identical totals`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds)
        val store = rawStore()
        val cursorService = cursors()
        val client = buildClient(maxRetries = 0)
        val stream = JiraIssuesStream(client, store, IN_SCOPE_PROJECT_KEYS, 0L, "1970-01-01", 10L, 100)
        val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = { _, _ -> true })

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
        assertEquals(
            expectedFixture.issues.totalInScope, store.countIssues(connId),
            "the resumed run must reach the same final total, with no duplicates",
        )
        val finalCursor = assertNotNull(cursorService.get(connId, "issues"))
        assertNull(ISSUES_TEST_JSON.decodeFromString<IssuesCursor>(finalCursor.cursor).nextPageToken)
    }

    @Test
    fun `CURSOR_EXPIRED on a page drops the token and restarts from the watermark, completing successfully`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds)
        val store = rawStore()
        val cursorService = cursors()
        val client = buildClient(maxRetries = 0)
        val stream = JiraIssuesStream(client, store, IN_SCOPE_PROJECT_KEYS, 0L, "1970-01-01", 10L, 100)
        val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = { _, _ -> true })

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

        assertEquals(expectedFixture.issues.totalInScope, store.countIssues(connId))
        val finalCursor = assertNotNull(cursorService.get(connId, "issues"))
        val decoded = ISSUES_TEST_JSON.decodeFromString<IssuesCursor>(finalCursor.cursor)
        assertNull(decoded.nextPageToken)
        assertEquals(decoded.watermarkAt, decoded.runStartedAt)
    }

    @Test
    fun `a lease loss mid-run stops the ISSUES stream without ever fetching the next page`() = runBlocking {
        val ds = dataSources()
        val connId = createConnection(ds)
        val store = rawStore()
        val cursorService = cursors()
        val client = buildClient()
        val stream = JiraIssuesStream(client, store, IN_SCOPE_PROJECT_KEYS, 0L, "1970-01-01", 10L, 100)
        var heartbeats = 0
        val context = StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = { _, _ ->
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

    @Test
    fun `RECONCILE tombstones the day2 deleted and moved issues, processes them in the same job, and is idempotent`() = runBlocking {
        // A clone of the shared fixture's raw rows stands in for the full backfill
        // (`.claude/docs/testing.md` "Shared synced fixture") — this test's SUBJECT is RECONCILE
        // (plus the PROCESS step it ends with), driven for real below.
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-reconcile")
        SyncedStubFixture.cloneRawData(sharedConnId, connId)
        val connector = buildConnector()
        val store = rawStore()

        JiraStubServer.setScenarioState("jira-day2", "day2")
        try {
            runReconcileOnce(connector, connId)

            val deletedId = expectedFixture.day2.deletedIssueId.toLong()
            val movedId = expectedFixture.day2.movedIssueId.toLong()
            val rows = issueRows(connId).associateBy { it[JiraRawStore.Issues.issueId] }

            val deletedRow = rows.getValue(deletedId)
            assertNotNull(
                deletedRow[JiraRawStore.Issues.deletedAt],
                "sample-data/jira/expected.json day2.deletedIssueId must be tombstoned",
            )
            // A tombstone flags the issue for processing (needs_processing = true), but this job
            // runs RECONCILE then PROCESS itself (jira/JiraConnector.kt's runReconcile, plan §7/§8)
            // — by the time this reads the row back, PROCESS has already cleared the flag and
            // stamped processed_at, mirroring the tombstone into norm.work_items in the SAME job
            // (NormalizationPipelineTest's own dedicated assertion for that mirror).
            assertFalse(deletedRow[JiraRawStore.Issues.needsProcessing], "the same job's PROCESS step must have already cleared this")
            assertNotNull(deletedRow[JiraRawStore.Issues.processedAt], "the same job's PROCESS step must have processed this tombstone")

            val movedRow = rows.getValue(movedId)
            assertNotNull(
                movedRow[JiraRawStore.Issues.movedOutAt],
                "sample-data/jira/expected.json day2.movedIssueId must be tombstoned as moved-out",
            )
            assertEquals(expectedFixture.day2.movedToProjectKey, movedRow[JiraRawStore.Issues.projectKey])
            assertFalse(movedRow[JiraRawStore.Issues.needsProcessing], "the same job's PROCESS step must have already cleared this")
            assertNotNull(movedRow[JiraRawStore.Issues.processedAt], "the same job's PROCESS step must have processed this tombstone")

            assertEquals(0L, store.countReconcileSeen(connId), "the scratch table must be empty after a completed pass")

            // Idempotence: a second RECONCILE against the SAME day2 state changes nothing further.
            val deletedAtBefore = deletedRow[JiraRawStore.Issues.deletedAt]
            val movedAtBefore = movedRow[JiraRawStore.Issues.movedOutAt]
            runReconcileOnce(connector, connId)
            val rowsAfter = issueRows(connId).associateBy { it[JiraRawStore.Issues.issueId] }
            assertEquals(
                deletedAtBefore, rowsAfter.getValue(deletedId)[JiraRawStore.Issues.deletedAt], "a second RECONCILE must be idempotent",
            )
            assertEquals(
                movedAtBefore, rowsAfter.getValue(movedId)[JiraRawStore.Issues.movedOutAt], "a second RECONCILE must be idempotent",
            )
            assertEquals(0L, store.countReconcileSeen(connId), "a second pass must also leave the scratch table empty")
            Unit
        } finally {
            JiraStubServer.resetScenarios()
        }
    }

    @Test
    fun `RECONCILE fetches and re-stores an issue the id-sweep saw but raw storage never had (index gap)`() = runBlocking {
        // A clone of the shared fixture's raw rows stands in for the full backfill
        // (`.claude/docs/testing.md` "Shared synced fixture") — this test's SUBJECT is RECONCILE's
        // index-gap fetch, driven for real below.
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-reconcile-gap")
        SyncedStubFixture.cloneRawData(sharedConnId, connId)
        // The index-gap phase only runs once an ISSUES run has covered the scope: carry the shared
        // backfill's completed cursor over (cloneRawData copies rows, not cursors).
        val completedIssuesCursor = assertNotNull(cursors().get(sharedConnId, "issues"))
        with(completedIssuesCursor) { cursors().put(connId, "issues", cursor, watermarkAt, lastCompletedAt) }
        val connector = buildConnector()
        val store = rawStore()

        // `sample-data/README.md`'s "issue-get-probe" mapping is the ONE arbitrary in-scope issue
        // id the stub maps `GET /issue/{id}` for unconditionally (no requiredState) — it is always
        // the lowest issue id among the in-scope set (ids are assigned in one global chronological
        // pass, `sample-data/README.md`), which is exactly what's stored here.
        val probeIssueId = issueRows(connId).minOf { it[JiraRawStore.Issues.issueId] }
        suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Issues.deleteWhere {
                (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq probeIssueId)
            }
        }
        assertEquals(
            expectedFixture.issues.totalInScope - 1, store.countIssues(connId),
            "the simulated index gap must have removed exactly one row",
        )

        runReconcileOnce(connector, connId)

        assertEquals(expectedFixture.issues.totalInScope, store.countIssues(connId), "the index gap must be fetched and re-stored")
        val restored = issueRows(connId).single { it[JiraRawStore.Issues.issueId] == probeIssueId }
        assertNull(restored[JiraRawStore.Issues.deletedAt])
        assertNull(restored[JiraRawStore.Issues.movedOutAt])
        assertEquals(0L, store.countReconcileSeen(connId), "the scratch table must be empty after a completed pass")
    }

    @Test
    fun `a pass resumed from an old PRIORITY cursor completes, clears the cursor and tombstones the no-longer-fetched PRIORITY rows`() =
        runBlocking {
            val connId = createConnection(dataSources())
            val store = rawStore()
            val cursorService = cursors()
            // What a pre-change version left behind: a REFERENCE cursor between ISSUE_TYPE and PRIORITY, plus a raw PRIORITY row.
            val oldPassStartedAt = 5_000L
            cursorService.put(connId, "reference", """{"passStartedAt":$oldPassStartedAt,"step":"PRIORITY"}""")
            store.upsertEntity(connId, JiraEntityKind.PRIORITY.name, "3", """{"id":"3","name":"Medium"}""", now = 1_000L)

            val stream = JiraReferenceStream(buildClient(maxRetries = 0), store, IN_SCOPE_PROJECT_KEYS)
            stream.run(StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = { _, _ -> true }))

            assertNull(cursorService.get(connId, "reference"), "the pass completed, so its cursor is cleared")
            val priority = suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Entities.selectAll().where {
                    (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq JiraEntityKind.PRIORITY.name)
                }.toList()
            }.single()
            assertNotNull(priority[JiraRawStore.Entities.deletedAt], "PRIORITY is no longer fetched; the sweep tombstones it")
            val fields = suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Entities.selectAll().where {
                    (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq JiraEntityKind.FIELD.name)
                }.toList()
            }
            assertTrue(
                fields.isNotEmpty() && fields.all { it[JiraRawStore.Entities.deletedAt] == null },
                "the restarted pass fetched FIELD live",
            )
        }

    @Test
    fun `a pass resumed from an old PROJECT_FIELDS cursor restarts at step 0, so ISSUE_TYPE still runs and is not tombstoned`() =
        runBlocking {
            val connId = createConnection(dataSources())
            val store = rawStore()
            val cursorService = cursors()
            // What the previous step order (PROJECT_FIELDS before ISSUE_TYPE) left behind: a PROJECT_FIELDS cursor, plus an issue type.
            val oldPassStartedAt = 5_000L
            cursorService.put(connId, "reference", """{"passStartedAt":$oldPassStartedAt,"step":"PROJECT_FIELDS","startAt":1}""")
            val staleEpicType = """{"id":"10000","name":"Epic","hierarchyLevel":1}"""
            store.upsertEntity(connId, JiraEntityKind.ISSUE_TYPE.name, "10000", staleEpicType, now = 1_000L)

            val stream = JiraReferenceStream(buildClient(maxRetries = 0), store, IN_SCOPE_PROJECT_KEYS)
            stream.run(StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = { _, _ -> true }))

            assertNull(cursorService.get(connId, "reference"), "the pass completed, so its cursor is cleared")
            val issueTypes = suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Entities.selectAll().where {
                    (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq JiraEntityKind.ISSUE_TYPE.name)
                }.toList()
            }
            assertEquals(6, issueTypes.size, "the ISSUE_TYPE step ran and stored the stub's six issue types (incl. the A31 Program)")
            assertTrue(
                issueTypes.all { it[JiraRawStore.Entities.deletedAt] == null && it[JiraRawStore.Entities.lastSeenAt] >= oldPassStartedAt },
                "every issue type was seen in this pass, so the sweep leaves it live",
            )
            val projectFields = suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Entities.selectAll().where {
                    (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq JiraEntityKind.PROJECT_FIELDS.name)
                }.toList()
            }
            assertTrue(
                projectFields.size == IN_SCOPE_PROJECT_KEYS.size && projectFields.all { it[JiraRawStore.Entities.deletedAt] == null },
                "PROJECT_FIELDS ran for every project, after ISSUE_TYPE",
            )
        }

    @Test
    fun `a 401 on the board list fails the REFERENCE stream with AUTHENTICATION_FAILED (the sync is strict about the board scopes)`() =
        runBlocking {
            val connId = createConnection(dataSources())
            val cursorService = cursors()
            val stream = JiraReferenceStream(buildClient(maxRetries = 0), rawStore(), IN_SCOPE_PROJECT_KEYS)
            val unauthorized = JiraStubServer.addOverride(
                get(urlPathMatching(".*/rest/agile/1.0/board"))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(401)),
            )
            try {
                val failure = assertFailsWith<JiraFetchException> {
                    stream.run(StreamContext(connId, 1u, sharedDatabaseForTests(), cursorService, jobHeartbeat = { _, _ -> true }))
                }
                assertEquals("AUTHENTICATION_FAILED", failure.code)
                assertEquals(401, failure.status)
            } finally {
                JiraStubServer.removeOverride(unauthorized)
            }
        }
}
