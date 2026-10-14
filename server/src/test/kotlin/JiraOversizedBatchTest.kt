package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.ChangelogsCursor
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraChangelogPage
import ch.nokillswit.jira.JiraChangelogStream
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraWorklogStream
import ch.nokillswit.jira.RawIssueInput
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val RESPONSE_CAP_BYTES = 3_000L
private const val PADDING_BYTES = 5_000
private const val CREATED_MILLIS = 1_790_330_188_061L
private val CURSOR_JSON = Json { ignoreUnknownKeys = true }

/** A real request recorder in front of the real [HttpJiraClient]: the id-batch size of each bulk/list call and each per-issue call. */
private class BatchRecordingClient(private val real: JiraClient) : JiraClient by real {
    val bulkSizes = mutableListOf<Int>()
    val listSizes = mutableListOf<Int>()
    val perIssueChangelogs = mutableListOf<String>()

    override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int): JsonObject {
        bulkSizes += issueIds.size
        return real.changelogBulk(issueIds, nextPageToken, maxResults)
    }

    override suspend fun issueChangelogPage(issueId: String, startAt: Int): JiraChangelogPage {
        perIssueChangelogs += issueId
        return real.issueChangelogPage(issueId, startAt)
    }

    override suspend fun worklogList(ids: List<Long>): JsonArray {
        listSizes += ids.size
        return real.worklogList(ids)
    }
}

/**
 * The CHANGELOGS and WORKLOGS streams' answer to a response over `jira.maxResponseBytes`: the SAME batch is asked again
 * at half the size (floor 1) for the rest of the run, and one id that still does not fit is isolated instead of stalling
 * the stream (`.claude/docs/jira-integration.md` "Oversized changelog/worklog batch"). Real HTTP over the shared
 * WireMock stub with a tiny byte cap; every response here is scripted by an override on the exact id list, so nothing
 * touches the shared synced fixture, and each test owns its connection.
 */
class JiraOversizedBatchTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = "jira-oversized", enabled = false, projectKeys = listOf("FLO"))
    }

    private suspend fun seedIssues(connId: UInt, vararg ids: Long) = ids.forEach { id ->
        store.upsertIssue(
            connId,
            RawIssueInput(
                issueId = id, issueKey = "FLO-$id", projectId = 1L, projectKey = "FLO", issueUpdatedAt = 1_000,
                payloadJson = """{"id":"$id","key":"FLO-$id","fields":{"project":{"id":"1","key":"FLO"}}}""",
            ),
            now = 1_000,
        )
    }

    private suspend fun recordingClient(): BatchRecordingClient {
        val base = JiraStubServer.start()
        val http = SyncedStubFixture.buildJiraHttp(maxRetries = 0, maxResponseBytes = RESPONSE_CAP_BYTES)
        val real = HttpJiraClient(http, base, base, "svc@example.com", "token", JiraAuthScheme.BASIC)
        real.resolveCloudId()
        return BatchRecordingClient(real)
    }

    private fun context(connId: UInt) = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true })

    private fun json(body: String) = aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

    private val oversized get() = json("""{"padding":"${"x".repeat(PADDING_BYTES)}"}""")

    private fun override(stub: MappingBuilder): StubMapping = JiraStubServer.addOverride(stub.atPriority(1))

    private fun bulkFor(ids: List<Long>) = post(urlPathMatching(".*/rest/api/3/changelog/bulkfetch"))
        .withRequestBody(matchingJsonPath("$.issueIdsOrKeys", equalToJson(ids.map { "\"$it\"" }.toString(), false, false)))

    private fun bulkOk(vararg ids: Long) = override(
        bulkFor(ids.toList()).willReturn(
            json(
                """{"issueChangeLogs":[${ids.joinToString(",") { """{"issueId":"$it","changeHistories":[${history(it)}]}""" }}]}""",
            ),
        ),
    )

    private fun bulkOversized(vararg ids: Long) = override(bulkFor(ids.toList()).willReturn(oversized))

    private fun history(issueId: Long) = """{"id":"${issueId}01","created":$CREATED_MILLIS,"items":[]}"""

    private fun perIssueChangelog(issueId: Long, response: ResponseDefinitionBuilder) =
        override(get(urlPathMatching(".*/rest/api/3/issue/$issueId/changelog")).willReturn(response))

    private fun perIssueChangelogOk(issueId: Long) =
        perIssueChangelog(issueId, json("""{"startAt":0,"maxResults":100,"total":1,"isLast":true,"histories":[${history(issueId)}]}"""))

    private suspend fun changelogSyncedAt(connId: UInt, issueId: Long): Long? = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.select(JiraRawStore.Issues.changelogSyncedAt)
            .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }
            .map { it[JiraRawStore.Issues.changelogSyncedAt] }.toList().single()
    }

    private suspend fun worklogsSyncedAt(connId: UInt, issueId: Long): Long? = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.select(JiraRawStore.Issues.worklogsSyncedAt)
            .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }
            .map { it[JiraRawStore.Issues.worklogsSyncedAt] }.toList().single()
    }

    private suspend fun runChangelogs(connId: UInt, client: JiraClient, bulkSize: Int): StreamContext {
        val context = context(connId)
        JiraChangelogStream(client, store, bulkSize).run(context)
        return context
    }

    @Test
    fun `normal-size batches are untouched, one bulk call per batch`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2, 3)
        val client = recordingClient()
        val mappings = listOf(bulkOk(1, 2, 3))
        try {
            val context = runChangelogs(connId, client, bulkSize = 3)
            assertEquals(listOf(3), client.bulkSizes)
            assertEquals(3L, store.countChangelogs(connId))
            assertEquals(3L, context.progressSnapshot()["changelogs"])
            assertNull(context.progressSnapshot()["changelogsSkipped"])
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `an oversized changelog batch is halved, and the smaller size lasts for the rest of the run`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2, 3, 4, 5, 6)
        val client = recordingClient()
        val mappings = listOf(bulkOversized(1, 2, 3, 4), bulkOk(1, 2), bulkOk(3, 4), bulkOk(5, 6))
        try {
            runChangelogs(connId, client, bulkSize = 4)
            assertEquals(listOf(4, 2, 2, 2), client.bulkSizes, "4 over the cap -> 2; the next batch is claimed at 2 too")
            assertEquals(6L, store.countChangelogs(connId), "every issue's history is stored")
            (1L..6L).forEach { assertNotNull(changelogSyncedAt(connId, it), "issue $it is marked synced") }
            assertNull(cursors.get(connId, "changelogs"), "a halving is not a bulk-unavailable verdict and persists no size")

            // A new run starts at the configured size again (nothing stale now, so seed one more and watch the ask).
            seedIssues(connId, 7, 8, 9, 10)
            val more = listOf(bulkOk(7, 8, 9, 10))
            try {
                client.bulkSizes.clear()
                runChangelogs(connId, client, bulkSize = 4)
                assertEquals(listOf(4), client.bulkSizes, "the halved size was run-local")
            } finally {
                more.forEach(JiraStubServer::removeOverride)
            }
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a single issue over the cap on bulk is fetched through the per-issue endpoint`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2, 3)
        val client = recordingClient()
        val mappings = listOf(
            bulkOversized(1, 2, 3), bulkOk(1), bulkOversized(2), bulkOk(3), perIssueChangelogOk(2),
        )
        try {
            val context = runChangelogs(connId, client, bulkSize = 3)
            assertEquals(listOf(3, 1, 1, 1), client.bulkSizes)
            assertEquals(listOf("2"), client.perIssueChangelogs)
            assertEquals(3L, store.countChangelogs(connId))
            assertNull(context.progressSnapshot()["changelogsSkipped"])
            assertNull(cursors.get(connId, "changelogs"), "the endpoint works; only one issue's answer was too big")
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `an issue over the cap on both endpoints is skipped for the run, left stale, and the stream completes`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2, 3)
        val client = recordingClient()
        val mappings = listOf(
            bulkOversized(1, 2, 3), bulkOk(1), bulkOversized(2), bulkOk(3), perIssueChangelog(2, oversized),
        )
        try {
            val context = runChangelogs(connId, client, bulkSize = 3)
            assertEquals(listOf(3, 1, 1, 1), client.bulkSizes, "issue 2 is not claimed again within the run")
            assertEquals(listOf("2"), client.perIssueChangelogs)
            assertEquals(2L, store.countChangelogs(connId))
            assertEquals(1L, context.progressSnapshot()["changelogsSkipped"])
            assertNotNull(changelogSyncedAt(connId, 1))
            assertNull(changelogSyncedAt(connId, 2), "still stale: the next run retries it")
            assertNotNull(changelogSyncedAt(connId, 3))
            assertEquals(listOf(2L), store.staleChangelogIssueIds(connId, 10))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `when bulk is unavailable an issue over the cap on the per-issue endpoint is isolated too`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2)
        val client = recordingClient()
        val mappings = listOf(
            override(bulkFor(listOf(1, 2)).willReturn(aResponse().withStatus(404))),
            perIssueChangelogOk(1),
            perIssueChangelog(2, oversized),
        )
        try {
            val context = runChangelogs(connId, client, bulkSize = 2)
            assertEquals(1L, context.progressSnapshot()["changelogsSkipped"])
            assertNotNull(changelogSyncedAt(connId, 1))
            assertNull(changelogSyncedAt(connId, 2))
            val cursor = CURSOR_JSON.decodeFromString<ChangelogsCursor>(assertNotNull(cursors.get(connId, "changelogs")).cursor)
            assertNotNull(cursor.bulkUnavailableUntil, "the 404 still records the bulk-unavailable window")
            Unit
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    // --- WORKLOGS -----------------------------------------------------------------------------------------------

    private fun worklog(id: Long, issueId: Long) = """{"id":"$id","issueId":"$issueId","updated":"2026-05-20T10:00:00.000+0000"}"""

    private fun listFor(ids: List<Long>) = post(urlPathMatching(".*/rest/api/3/worklog/list"))
        .withRequestBody(equalToJson("""{"ids":$ids}""", false, false))

    /** `worklog/list` answers [ids] with one worklog each, on issue [issueId]. */
    private fun listOk(issueId: Long, vararg ids: Long) =
        override(listFor(ids.toList()).willReturn(json("[${ids.joinToString(",") { worklog(it, issueId) }}]")))

    private fun listOversized(vararg ids: Long) = override(listFor(ids.toList()).willReturn(oversized))

    private fun entry(worklogId: Long) = """{"worklogId":$worklogId,"updatedTime":1}"""

    /** The incremental feeds: one `updated` page naming [ids], an empty `deleted` page; both last pages. */
    private fun feeds(vararg ids: Long) = listOf(
        override(
            get(urlPathMatching(".*/rest/api/3/worklog/updated")).willReturn(
                json("""{"values":[${ids.joinToString(",") { entry(it) }}],"since":0,"until":5,"lastPage":true}"""),
            ),
        ),
        override(
            get(urlPathMatching(".*/rest/api/3/worklog/deleted")).willReturn(json("""{"values":[],"since":0,"until":5,"lastPage":true}""")),
        ),
    )

    private suspend fun seedWorklogSyncedIssue(connId: UInt, issueId: Long) {
        seedIssues(connId, issueId)
        store.markWorklogsSynced(connId, issueId, now = 2_000)
    }

    private suspend fun runWorklogs(connId: UInt, client: JiraClient): StreamContext {
        val context = context(connId)
        JiraWorklogStream(client, store).run(context)
        return context
    }

    @Test
    fun `an oversized worklog list is halved, and the smaller size lasts for the rest of the run`() = runBlocking {
        val connId = connection()
        seedWorklogSyncedIssue(connId, 7)
        val client = recordingClient()
        val mappings = feeds(11, 12, 13, 14) + listOf(listOversized(11, 12, 13, 14), listOk(7, 11, 12), listOk(7, 13, 14))
        try {
            val context = runWorklogs(connId, client)
            assertEquals(listOf(4, 2, 2), client.listSizes)
            assertEquals(4L, store.countWorklogs(connId))
            assertEquals(4L, context.progressSnapshot()["worklogs"])
            assertNull(context.progressSnapshot()["worklogsSkipped"])
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a single worklog over the cap is skipped and counted, the rest of the page is stored`() = runBlocking {
        val connId = connection()
        seedWorklogSyncedIssue(connId, 7)
        val client = recordingClient()
        val mappings = feeds(11, 12, 13) +
            listOf(listOversized(11, 12, 13), listOk(7, 11), listOversized(12), listOk(7, 13))
        try {
            val context = runWorklogs(connId, client)
            assertEquals(listOf(3, 1, 1, 1), client.listSizes)
            assertEquals(2L, store.countWorklogs(connId))
            assertEquals(1L, context.progressSnapshot()["worklogsSkipped"])
            assertNotNull(cursors.get(connId, "worklogs"), "the cursor advanced past the page")
            Unit
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `an issue whose per-issue worklog page is over the cap is skipped for the run and left unsynced`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2, 3)
        val client = recordingClient()
        val worklogPage = { issueId: Long ->
            override(
                get(urlPathMatching(".*/rest/api/3/issue/$issueId/worklog")).willReturn(
                    json("""{"startAt":0,"maxResults":100,"total":1,"worklogs":[${worklog(issueId * 10, issueId)}]}"""),
                ),
            )
        }
        val mappings = feeds() + listOf(
            worklogPage(1), worklogPage(3),
            override(get(urlPathMatching(".*/rest/api/3/issue/2/worklog")).willReturn(oversized)),
        )
        try {
            val context = runWorklogs(connId, client)
            assertEquals(1L, context.progressSnapshot()["worklogsIssuesSkipped"])
            assertEquals(2L, store.countWorklogs(connId))
            assertNotNull(worklogsSyncedAt(connId, 1))
            assertNull(worklogsSyncedAt(connId, 2), "still unsynced: the next run retries it")
            assertNotNull(worklogsSyncedAt(connId, 3))
            Unit
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }
}
