package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.ChangelogsCursor
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraChangelogPage
import ch.nokillswit.jira.JiraChangelogStream
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraWorklogStream
import ch.nokillswit.jira.MAX_ANSWER_CHARS
import ch.nokillswit.jira.MAX_ANSWER_HISTORIES
import ch.nokillswit.jira.MAX_ANSWER_PAGES
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.SkipBudget
import ch.nokillswit.jira.halvedBatchSize
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
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
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESPONSE_CAP_BYTES = 3_000L
private const val PADDING_BYTES = 5_000
private const val CREATED_MILLIS = 1_790_330_188_061L
private val CURSOR_JSON = Json { ignoreUnknownKeys = true }

/** A real request recorder in front of the real [HttpJiraClient]: the id-batch size of each bulk/list call and each per-issue call. */
private class BatchRecordingClient(private val real: JiraClient) : JiraClient by real {
    val bulkSizes = mutableListOf<Int>()
    val bulkPageSizes = mutableListOf<Int>()
    val listSizes = mutableListOf<Int>()
    val perIssueChangelogs = mutableListOf<String>()

    override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int): JsonObject {
        bulkSizes += issueIds.size
        bulkPageSizes += maxResults
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
 * touches the shared synced fixture, and each test owns its connection. The CHANGELOGS answer ceiling's tests (a host that
 * never stops paging, text adding up across pages) script the client in-process instead.
 */
class JiraOversizedBatchTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()

    private val connections = mutableListOf<UInt>()

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = "jira-oversized", enabled = false, projectKeys = listOf("FLO"))
            .also { connections += it }
    }

    /** Each test owns its connection (testing.md: a test removes its own rows): its raw rows go, the connection is soft-deleted. */
    @AfterTest
    fun dropConnections() = runBlocking {
        connections.forEach { connId ->
            suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Changelogs.deleteWhere { JiraRawStore.Changelogs.connectionId eq connId }
                JiraRawStore.Worklogs.deleteWhere { JiraRawStore.Worklogs.connectionId eq connId }
                JiraRawStore.Issues.deleteWhere { JiraRawStore.Issues.connectionId eq connId }
            }
            SyncedStubFixture.dataSources().delete(connId)
        }
        connections.clear()
    }

    private suspend fun seedIssues(connId: UInt, vararg ids: Long) = ids.forEach { id ->
        store.upsertIssue(
            connId,
            RawIssueInput(
                issueId = id, issueKey = "FLO-$id", projectId = 1L, projectKey = "FLO", issueUpdatedAt = 1_000,
                payload = Json.parseToJsonElement("""{"id":"$id","key":"FLO-$id","fields":{"project":{"id":"1","key":"FLO"}}}"""),
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

    private suspend fun runChangelogs(
        connId: UInt,
        client: JiraClient,
        bulkSize: Int,
        pageSize: Int = 1_000,
        maxAnswerChars: Long = MAX_ANSWER_CHARS,
        maxAnswerHistories: Int = MAX_ANSWER_HISTORIES,
        maxAnswerPages: Int = MAX_ANSWER_PAGES,
    ): StreamContext {
        val context = context(connId)
        JiraChangelogStream(client, store, bulkSize, pageSize, maxAnswerChars, maxAnswerHistories, maxAnswerPages).run(context)
        return context
    }

    /**
     * A host that never stops paging: every bulk page is empty but carries a `nextPageToken`, and (with [endlessPerIssue]) every
     * per-issue page has one new history and a `total` it never reaches; otherwise the per-issue answer is one history.
     */
    private inner class EndlessClient(real: JiraClient, private val endlessPerIssue: Boolean) : JiraClient by real {
        var bulkCalls = 0
        var perIssueCalls = 0

        override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int): JsonObject {
            bulkCalls++
            return Json.parseToJsonElement("""{"issueChangeLogs":[],"nextPageToken":"again"}""").jsonObject
        }

        override suspend fun issueChangelogPage(issueId: String, startAt: Int): JiraChangelogPage {
            perIssueCalls++
            val history = Json.parseToJsonElement(history(issueId.toLong() * 1_000 + startAt))
            return if (endlessPerIssue) {
                JiraChangelogPage(startAt, 1, Int.MAX_VALUE, false, JsonArray(listOf(history)))
            } else {
                JiraChangelogPage(0, 1, 1, true, JsonArray(listOf(history)))
            }
        }
    }

    @Test
    fun `normal-size batches are untouched, one bulk call per batch`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2, 3)
        val client = recordingClient()
        val mappings = listOf(bulkOk(1, 2, 3))
        try {
            val context = runChangelogs(connId, client, bulkSize = 3, pageSize = 7)
            assertEquals(listOf(3), client.bulkSizes)
            assertEquals(listOf(7), client.bulkPageSizes, "a page asks jira.changelogPageSize histories, not the batch size")
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
    fun `a bulk answer that pages past the page ceiling is oversized - halved, then fetched per issue`(): Unit = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2)
        val client = EndlessClient(recordingClient(), endlessPerIssue = false)
        val context = runChangelogs(connId, client, bulkSize = 2, maxAnswerPages = 5)
        assertEquals(15, client.bulkCalls, "5 pages for [1, 2], then 5 for [1] and 5 for [2]: never more than the ceiling")
        assertEquals(2, client.perIssueCalls)
        assertEquals(2L, store.countChangelogs(connId))
        assertNull(context.progressSnapshot()["changelogsSkipped"])
        assertNotNull(changelogSyncedAt(connId, 1))
        assertNotNull(changelogSyncedAt(connId, 2))
    }

    @Test
    fun `an issue whose per-issue answer never ends is skipped at the page ceiling and stays stale`(): Unit = runBlocking {
        val connId = connection()
        seedIssues(connId, 1)
        val client = EndlessClient(recordingClient(), endlessPerIssue = true)
        val context = runChangelogs(connId, client, bulkSize = 1, maxAnswerPages = 5)
        assertEquals(5, client.perIssueCalls, "the per-issue paging stops at the ceiling too")
        assertEquals(1L, context.progressSnapshot()["changelogsSkipped"])
        assertEquals(0L, store.countChangelogs(connId), "nothing of the partial answer is stored")
        assertNull(changelogSyncedAt(connId, 1))
    }

    @Test
    fun `an endless per-issue answer of tiny histories stops at the row ceiling`(): Unit = runBlocking {
        val connId = connection()
        seedIssues(connId, 1)
        val client = EndlessClient(recordingClient(), endlessPerIssue = true)
        val context = runChangelogs(connId, client, bulkSize = 1, maxAnswerHistories = 3, maxAnswerPages = 50)
        assertEquals(4, client.perIssueCalls, "the 4th history passes the row ceiling of 3, long before the page ceiling")
        assertEquals(1L, context.progressSnapshot()["changelogsSkipped"])
        assertEquals(0L, store.countChangelogs(connId))
    }

    @Test
    fun `the text ceiling holds on the per-issue path too`(): Unit = runBlocking {
        val connId = connection()
        seedIssues(connId, 1)
        val client = EndlessClient(recordingClient(), endlessPerIssue = false)
        // Bulk pages forever (3 pages, then per issue); the per-issue answer is one 47-character history, over 40.
        val context = runChangelogs(connId, client, bulkSize = 1, maxAnswerChars = 40, maxAnswerPages = 3)
        assertEquals(1, client.perIssueCalls)
        assertEquals(1L, context.progressSnapshot()["changelogsSkipped"])
        assertNull(changelogSyncedAt(connId, 1))
    }

    @Test
    fun `an endless host costs a bounded number of calls before the skip budget fails the run`() = runBlocking {
        val connId = connection()
        seedIssues(connId, *(1L..12L).toList().toLongArray())
        val client = EndlessClient(recordingClient(), endlessPerIssue = true)
        val failure = assertFailsWith<JiraFetchException> { runChangelogs(connId, client, bulkSize = 4, maxAnswerPages = 2) }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue(failure.message.orEmpty().contains("answer ceiling"), failure.message)
        // [1..4] and [1, 2] take 2 pages each and halve; then each of 11 issues takes 2 bulk and 2 per-issue pages and is skipped.
        assertEquals(2 + 2 + 11 * 2, client.bulkCalls)
        assertEquals(11 * 2, client.perIssueCalls)
    }

    @Test
    fun `the text ceiling adds up across pages - two pages each under it are oversized together`(): Unit = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2)
        val real = recordingClient()
        val sizes = mutableListOf<Int>()
        // Two ids answer in two pages of one 47-character history each; one id in one page.
        val client = object : JiraClient by real {
            override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int): JsonObject {
                sizes += issueIds.size
                val id = if (nextPageToken == null) issueIds.first() else issueIds.last()
                val token = if (issueIds.size > 1 && nextPageToken == null) ",\"nextPageToken\":\"p2\"" else ""
                return Json.parseToJsonElement(
                    """{"issueChangeLogs":[{"issueId":"$id","changeHistories":[${history(id.toLong())}]}]$token}""",
                ).jsonObject
            }
        }
        runChangelogs(connId, client, bulkSize = 2, maxAnswerChars = 80)
        assertEquals(listOf(2, 2, 1, 1), sizes, "page 2 of [1, 2] passes the ceiling (47 + 47 > 80): halved, then one page each")
        assertEquals(2L, store.countChangelogs(connId))
        assertNotNull(changelogSyncedAt(connId, 1))
    }

    @Test
    fun `a bulk answer over the text ceiling is halved like a response over the byte cap`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2)
        val client = recordingClient()
        val mappings = listOf(bulkOk(1, 2), bulkOk(1), bulkOk(2))
        try {
            // One history is 47 characters: one fits in 80, the pair does not.
            runChangelogs(connId, client, bulkSize = 2, maxAnswerChars = 80)
            assertEquals(listOf(2, 1, 1), client.bulkSizes)
            assertEquals(2L, store.countChangelogs(connId))
            assertTrue(client.perIssueChangelogs.isEmpty())
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

    @Test
    fun `a bulk failure that is not an oversized response propagates unchanged and skips nothing`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2)
        val client = recordingClient()
        val mappings = listOf(override(bulkFor(listOf(1, 2)).willReturn(aResponse().withStatus(500))))
        try {
            val failure = assertFailsWith<JiraFetchException> { runChangelogs(connId, client, bulkSize = 2) }
            assertEquals("UPSTREAM_UNAVAILABLE", failure.code)
            assertEquals(500, failure.status)
            assertEquals(listOf(2), client.bulkSizes, "no halving, no retry within the stream")
            assertTrue(client.perIssueChangelogs.isEmpty(), "and no per-issue fallback")
            assertNull(changelogSyncedAt(connId, 1))
            assertEquals(0L, store.countChangelogs(connId))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a per-issue changelog failure that is not an oversized response propagates unchanged`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1)
        val client = recordingClient()
        val mappings = listOf(
            override(bulkFor(listOf(1)).willReturn(aResponse().withStatus(404))),
            perIssueChangelog(1, aResponse().withStatus(401)),
        )
        try {
            val failure = assertFailsWith<JiraFetchException> { runChangelogs(connId, client, bulkSize = 1) }
            assertEquals("AUTHENTICATION_FAILED", failure.code)
            assertNull(changelogSyncedAt(connId, 1))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `an oversized second bulk page discards the first page, the halved re-ask stores each history once`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2)
        val client = recordingClient()
        val firstPage = override(
            bulkFor(listOf(1, 2)).willReturn(
                json("""{"issueChangeLogs":[{"issueId":"1","changeHistories":[${history(99)}]}],"nextPageToken":"t2"}"""),
            ),
        )
        // Registered after the page-1 stub, so it wins for the request that carries the token.
        val secondPage = override(
            bulkFor(listOf(1, 2)).withRequestBody(matchingJsonPath("$.nextPageToken", equalTo("t2"))).willReturn(oversized),
        )
        val mappings = listOf(firstPage, secondPage, bulkOk(1), bulkOk(2))
        try {
            val context = runChangelogs(connId, client, bulkSize = 2)
            assertEquals(listOf(2, 2, 1, 1), client.bulkSizes, "page 1, the oversized page 2, then one issue per ask")
            assertEquals(2L, store.countChangelogs(connId), "the discarded page-1 history (99) was never stored: one history per issue")
            assertEquals(2L, context.progressSnapshot()["changelogs"])
            assertNull(context.progressSnapshot()["changelogsSkipped"])
            (1L..2L).forEach { assertNotNull(changelogSyncedAt(connId, it)) }
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a run that skips more than max of 10 and a tenth of its issues fails LIMIT_EXCEEDED instead of succeeding`() = runBlocking {
        val connId = connection()
        seedIssues(connId, *LongArray(12) { it + 1L })
        val client = recordingClient()
        val mappings = listOf(
            override(post(urlPathMatching(".*/rest/api/3/changelog/bulkfetch")).willReturn(oversized)),
            override(get(urlPathMatching(".*/rest/api/3/issue/\\d+/changelog")).willReturn(oversized)),
        )
        try {
            val context = context(connId)
            val failure = assertFailsWith<JiraFetchException> { JiraChangelogStream(client, store, 12).run(context) }
            assertEquals("LIMIT_EXCEEDED", failure.code)
            assertTrue("CHANGELOGS skipped 11 of 11 issues over jira.maxResponseBytes" in failure.message.orEmpty(), failure.message)
            assertEquals(11L, context.progressSnapshot()["changelogsSkipped"], "the 11th skip is the one past the allowance of 10")
            assertEquals(0L, store.countChangelogs(connId))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `halvedBatchSize halves the smaller of the run size and the attempted batch, never below one`() {
        assertEquals(25, halvedBatchSize(current = 50, attempted = 50))
        assertEquals(12, halvedBatchSize(current = 25, attempted = 25))
        assertEquals(2, halvedBatchSize(current = 50, attempted = 5), "a short tail batch halves its own size, not the run's")
        assertEquals(1, halvedBatchSize(current = 50, attempted = 3))
        assertEquals(1, halvedBatchSize(current = 2, attempted = 2))
        assertEquals(1, halvedBatchSize(current = 1, attempted = 1), "the floor is one id")
        assertEquals(1, halvedBatchSize(current = 3, attempted = 1))
    }

    @Test
    fun `the skip budget allows ten skips, then a tenth of the ids attempted`() {
        val small = SkipBudget("CHANGELOGS", "issues")
        repeat(10) { small.skip() }
        val failure = assertFailsWith<JiraFetchException> { small.skip() }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue("CHANGELOGS skipped 11 of 11 issues" in failure.message.orEmpty())

        val large = SkipBudget("WORKLOGS", "issues")
        large.settled(200)
        repeat(22) { large.skip() } // 22 of 222 is under a tenth (22.2): still fine
        val over = assertFailsWith<JiraFetchException> { large.skip() } // 23 of 223 is over the 22 allowed
        assertTrue("WORKLOGS skipped 23 of 223 issues" in over.message.orEmpty(), over.message)
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

    @Test
    fun `a worklog list failure that is not an oversized response propagates unchanged and moves no cursor`() = runBlocking {
        val connId = connection()
        seedWorklogSyncedIssue(connId, 7)
        val client = recordingClient()
        val mappings = feeds(11, 12) + listOf(override(listFor(listOf(11, 12)).willReturn(aResponse().withStatus(401))))
        try {
            val failure = assertFailsWith<JiraFetchException> { runWorklogs(connId, client) }
            assertEquals("AUTHENTICATION_FAILED", failure.code)
            assertEquals(listOf(2), client.listSizes, "no halving")
            assertNull(cursors.get(connId, "worklogs"))
            assertEquals(0L, store.countWorklogs(connId))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a per-issue worklog failure that is not an oversized response propagates unchanged`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1)
        val client = recordingClient()
        val mappings = feeds() + override(get(urlPathMatching(".*/rest/api/3/issue/1/worklog")).willReturn(aResponse().withStatus(500)))
        try {
            val failure = assertFailsWith<JiraFetchException> { runWorklogs(connId, client) }
            assertEquals("UPSTREAM_UNAVAILABLE", failure.code)
            assertNull(worklogsSyncedAt(connId, 1))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a per-issue worklog failure keeps the issues fetched before it in the batch, and a re-run stores the failed one`() = runBlocking {
        val connId = connection()
        seedIssues(connId, 1, 2)
        val client = recordingClient()
        val page = { issueId: Long ->
            override(
                get(urlPathMatching(".*/rest/api/3/issue/$issueId/worklog")).willReturn(
                    json("""{"startAt":0,"maxResults":100,"total":1,"worklogs":[${worklog(issueId * 10, issueId)}]}"""),
                ),
            )
        }
        val failing = override(get(urlPathMatching(".*/rest/api/3/issue/2/worklog")).willReturn(aResponse().withStatus(500)))
        val mappings = (feeds() + page(1) + failing).toMutableList()
        try {
            val failure = assertFailsWith<JiraFetchException> { runWorklogs(connId, client) }
            assertEquals("UPSTREAM_UNAVAILABLE", failure.code)
            assertEquals(1L, store.countWorklogs(connId), "issue 1 was fetched before the failure and is kept")
            assertNotNull(worklogsSyncedAt(connId, 1))
            assertNull(worklogsSyncedAt(connId, 2), "the failed issue is not stamped")

            JiraStubServer.removeOverride(failing)
            mappings -= failing
            mappings += page(2)
            runWorklogs(connId, client)
            assertEquals(2L, store.countWorklogs(connId))
            assertNotNull(worklogsSyncedAt(connId, 2), "the re-run stores the issue that failed")
            Unit
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `backfill skipping more than max of 10 and a tenth of its issues fails LIMIT_EXCEEDED`() = runBlocking {
        val connId = connection()
        seedIssues(connId, *LongArray(12) { it + 1L })
        val client = recordingClient()
        val mappings = feeds() + override(get(urlPathMatching(".*/rest/api/3/issue/\\d+/worklog")).willReturn(oversized))
        try {
            val context = context(connId)
            val failure = assertFailsWith<JiraFetchException> { JiraWorklogStream(client, store).run(context) }
            assertEquals("LIMIT_EXCEEDED", failure.code)
            assertTrue("WORKLOGS skipped 11 of 11 issues over jira.maxResponseBytes" in failure.message.orEmpty(), failure.message)
            assertEquals(11L, context.progressSnapshot()["worklogsIssuesSkipped"])
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a feed whose listed worklogs are mostly over the cap fails LIMIT_EXCEEDED instead of advancing the cursor`() = runBlocking {
        val connId = connection()
        seedWorklogSyncedIssue(connId, 7)
        val client = recordingClient()
        val mappings = feeds(*LongArray(12) { it + 11L }) +
            override(post(urlPathMatching(".*/rest/api/3/worklog/list")).willReturn(oversized))
        try {
            val context = context(connId)
            val failure = assertFailsWith<JiraFetchException> { JiraWorklogStream(client, store).run(context) }
            assertEquals("LIMIT_EXCEEDED", failure.code)
            assertTrue("WORKLOGS skipped 11 of 11 listed worklogs over jira.maxResponseBytes" in failure.message.orEmpty(), failure.message)
            assertEquals(11L, context.progressSnapshot()["worklogsSkipped"])
            assertNull(cursors.get(connId, "worklogs"), "the page did not commit, so the feed will be re-read")
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }
}
