package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.IssuesCursor
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.backfillFromEpochMillis
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val BACKFILL = "2026-01-01"
private const val PAGE_SIZE = 8
private const val RESPONSE_CAP_BYTES = 3_000L
private const val PADDING_BYTES = 5_000
private val CURSOR_JSON = Json { ignoreUnknownKeys = true }

/** A real `search/jql` request recorder in front of the real [HttpJiraClient]: the `maxResults` each page was asked with. */
private class RecordingClient(private val real: JiraClient) : JiraClient by real {
    val sizes = mutableListOf<Int>()

    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
        sizes += maxResults
        return real.searchJql(jql, fields, nextPageToken, maxResults)
    }
}

/** Answers `search/jql` from a queue of scripted outcomes (a page, or the failure to throw) and records each `maxResults`. */
private class ScriptedClient(private val script: ArrayDeque<(Int) -> JiraSearchPage>) : UnsupportedJiraClient() {
    val sizes = mutableListOf<Int>()

    override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage {
        sizes += maxResults
        return script.removeFirst()(maxResults)
    }
}

/**
 * The ISSUES stream's answer to a `search/jql` page over `jira.maxResponseBytes`: the SAME page is asked again at half
 * the `maxResults`, down to 1 (`.claude/docs/jira-integration.md` "Oversized issue page (`LIMIT_EXCEEDED`)"). Real HTTP over the
 * shared WireMock stub with a tiny byte cap and per-`maxResults`/per-token override pages — the stub's own fixed
 * pages ignore `maxResults`, so every page here is scripted; nothing touches the shared synced fixture.
 */
class JiraIssuesPageSizeTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()

    private fun issue(id: Long, padding: Int = 0): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("key", "FLO-$id")
        putJsonObject("fields") {
            put("updated", "2026-05-20T10:00:00.000+0000")
            putJsonObject("project") { put("id", "10000"); put("key", "FLO") }
            if (padding > 0) put("description", "x".repeat(padding))
        }
    }

    private fun page(nextToken: String?, vararg issues: JsonObject): String = buildJsonObject {
        putJsonArray("issues") { issues.forEach { add(it) } }
        nextToken?.let { put("nextPageToken", it) }
    }.toString()

    /** Registers the page Jira would answer for [maxResults] on the first page (`token == null`) or on [token]. */
    private fun stubPage(token: String?, maxResults: Int, body: String): StubMapping = JiraStubServer.addOverride(
        get(urlPathMatching(".*/rest/api/3/search/jql"))
            .withQueryParam("maxResults", equalTo(maxResults.toString()))
            .withQueryParam("nextPageToken", token?.let { equalTo(it) } ?: absent())
            .atPriority(1)
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
    )

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(
            namePrefix = "jira-pagesize", enabled = false, projectKeys = listOf("FLO"), backfillFrom = BACKFILL,
        )
    }

    private suspend fun recordingClient(): RecordingClient {
        val base = JiraStubServer.start()
        val http = SyncedStubFixture.buildJiraHttp(maxRetries = 0, maxResponseBytes = RESPONSE_CAP_BYTES)
        val real = HttpJiraClient(http, base, base, "svc@example.com", "token", JiraAuthScheme.BASIC)
        real.resolveCloudId()
        return RecordingClient(real)
    }

    private fun stream(client: JiraClient) =
        JiraIssuesStream(client, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL), BACKFILL, 10L, PAGE_SIZE)

    private fun context(connId: UInt) = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true })

    private val oversized get() = page(null, issue(99, padding = PADDING_BYTES))

    @Test
    fun `an oversized page is re-asked at half the size for the rest of the run, the next run starts normal`() = runBlocking {
        val connId = connection()
        val client = recordingClient()
        val mappings = listOf(
            stubPage(null, PAGE_SIZE, page("t1", issue(1))),
            stubPage("t1", PAGE_SIZE, oversized),
            stubPage("t1", PAGE_SIZE / 2, oversized),
            stubPage("t1", PAGE_SIZE / 4, page("t2", issue(2))),
            stubPage("t2", PAGE_SIZE / 4, page(null, issue(3))),
        )
        try {
            stream(client).run(context(connId))
            assertEquals(listOf(8, 8, 4, 2, 2), client.sizes, "page 2 halved 8 -> 4 -> 2; page 3 stays at 2")
            assertEquals(3L, store.countIssues(connId), "every issue of the shrunk pages is stored")
            assertNull(CURSOR_JSON.decodeFromString<IssuesCursor>(assertNotNull(cursors.get(connId, "issues")).cursor).nextPageToken)

            client.sizes.clear()
            stream(client).run(context(connId))
            assertEquals(listOf(8, 8, 4, 2, 2), client.sizes, "a new run asks for the configured size again")
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a single issue over the cap still fails with LIMIT_EXCEEDED, the cursor stays at the last good page`() = runBlocking {
        val connId = connection()
        val client = recordingClient()
        val mappings = listOf(
            stubPage(null, PAGE_SIZE, page("t1", issue(1))),
            stubPage("t1", PAGE_SIZE, oversized),
            stubPage("t1", PAGE_SIZE / 2, oversized),
            stubPage("t1", PAGE_SIZE / 4, oversized),
            stubPage("t1", 1, oversized),
        )
        try {
            val failure = assertFailsWith<JiraFetchException> { stream(client).run(context(connId)) }
            assertEquals("LIMIT_EXCEEDED", failure.code)
            assertEquals(listOf(8, 8, 4, 2, 1), client.sizes, "halved down to 1, then given up")
            assertEquals(1L, store.countIssues(connId))
            val cursor = CURSOR_JSON.decodeFromString<IssuesCursor>(assertNotNull(cursors.get(connId, "issues")).cursor)
            assertEquals("t1", cursor.nextPageToken)
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a first page over the cap is halved too, and the persisted cursor never carries a size`() = runBlocking {
        val connId = connection()
        val client = recordingClient()
        val mappings = listOf(
            stubPage(null, PAGE_SIZE, oversized),
            stubPage(null, PAGE_SIZE / 2, page(null, issue(1))),
        )
        try {
            stream(client).run(context(connId))
            assertEquals(listOf(8, 4), client.sizes)
            assertEquals(1L, store.countIssues(connId))
            val raw = assertNotNull(cursors.get(connId, "issues")).cursor
            assertFalse(raw.contains("size", ignoreCase = true) || raw.contains("maxResults"), "the halved size is run-local: $raw")
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a re-asked token rejected after a halving restarts from the first page at the smaller size`() = runBlocking {
        val connId = connection()
        val client = recordingClient()
        val mappings = listOf(
            stubPage(null, PAGE_SIZE, page("t1", issue(1))),
            stubPage("t1", PAGE_SIZE, oversized),
            JiraStubServer.addOverride(
                get(urlPathMatching(".*/rest/api/3/search/jql"))
                    .withQueryParam("maxResults", equalTo((PAGE_SIZE / 2).toString()))
                    .withQueryParam("nextPageToken", equalTo("t1"))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(400)),
            ),
            stubPage(null, PAGE_SIZE / 2, page(null, issue(2))),
        )
        try {
            stream(client).run(context(connId))
            assertEquals(listOf(8, 8, 4, 4), client.sizes, "CURSOR_EXPIRED restarts at page one, still at the halved size")
            assertEquals(2L, store.countIssues(connId))
            assertNull(CURSOR_JSON.decodeFromString<IssuesCursor>(assertNotNull(cursors.get(connId, "issues")).cursor).nextPageToken)
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a rejection right after a halving does not use up the CURSOR_EXPIRED restart budget`() = runBlocking {
        val connId = connection()
        val ok = { next: String?, id: Long -> { _: Int -> JiraSearchPage(JsonArray(listOf(issue(id))), next) } }
        val expired = { _: Int -> throw JiraFetchException("CURSOR_EXPIRED", 410, null) }
        val oversizedFetch = { _: Int -> throw JiraFetchException("LIMIT_EXCEEDED", 200, null) }
        val script = ArrayDeque<(Int) -> JiraSearchPage>()
        // MAX_CURSOR_RESTARTS (5) ordinary restarts: page one answers, the token is rejected.
        repeat(5) { script += ok("t1", 1); script += expired }
        // Then a halving followed by a rejection of the re-asked token (not counted), and a restart that completes.
        script += listOf<(Int) -> JiraSearchPage>(ok("t1", 1), oversizedFetch, expired, ok(null, 2))
        val client = ScriptedClient(script)

        stream(client).run(context(connId))

        assertEquals(listOf(8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 4, 4), client.sizes)
        assertEquals(2L, store.countIssues(connId))
    }
}
