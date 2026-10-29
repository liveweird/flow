package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.AnswerCeiling
import ch.nokillswit.jira.BoundedAnswer
import ch.nokillswit.jira.FEED_CLOCK_SKEW_MILLIS
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraStoredCeilings
import ch.nokillswit.jira.JiraWorklogIdsPage
import ch.nokillswit.jira.JiraWorklogStream
import ch.nokillswit.jira.PageCeiling
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.WorklogCeilings
import ch.nokillswit.jira.WorklogsCursor
import ch.nokillswit.jira.requirePlausible
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLOUD_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
private val ISSUE_WORKLOG_PATH = Regex(".*/rest/api/3/issue/([0-9]+)/worklog")
private const val NOW = 1_800_000_000_000L
private const val FAR_FUTURE = 32_503_680_000_000L
private const val WINDOW = 4
private const val PAGES = 5
private const val CALL_MILLIS = 5L
private const val MIB = 1024 * 1024
private const val NO_SKIP_BUDGET_ISSUES = 12

/**
 * The WORKLOGS stream's answer ceilings (`.claude/docs/jira-integration.md` "Worklog answer ceilings"): a compromised or buggy
 * Jira host that never stops paging, packs a giant answer, or sends a `until` that cannot be true must end in a typed failure
 * (or a skipped issue), never in unbounded growth, a silenced feed, or a cursor past what was stored. The real [HttpJiraClient],
 * [JiraHttp] and raw store run over a Ktor `MockEngine` that plays the host; the figures of [WorklogCeilings] are small so each
 * attack is cheap. Every test owns its connection.
 */
class JiraWorklogCeilingTest {
    // The WORKLOGS answer ceilings are the subject here, so the per-worklog stored-size ceiling (16 KiB, JiraStoredCeilingTest) is lifted.
    private val store = JiraRawStore(sharedDatabaseForTests(), JiraStoredCeilings(maxWorklogBytes = 8L * MIB))
    private val cursors = SyncedStubFixture.cursors()
    private val connections = mutableListOf<UInt>()

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = "jira-worklog-ceiling", enabled = false, projectKeys = listOf("FLO"))
            .also { connections += it }
    }

    @AfterTest
    fun dropConnections() = runBlocking<Unit> {
        connections.forEach { connId ->
            suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Worklogs.deleteWhere { JiraRawStore.Worklogs.connectionId eq connId }
                JiraRawStore.Issues.deleteWhere { JiraRawStore.Issues.connectionId eq connId }
            }
            SyncedStubFixture.dataSources().delete(connId)
        }
        connections.clear()
    }

    private suspend fun seedIssues(connId: UInt, vararg ids: Long, synced: Boolean = false) = ids.forEach { id ->
        store.upsertIssue(
            connId,
            RawIssueInput(
                issueId = id, issueKey = "FLO-$id", projectId = 1L, projectKey = "FLO", issueUpdatedAt = 1_000,
                payload = Json.parseToJsonElement("""{"id":"$id","key":"FLO-$id","fields":{"project":{"id":"1","key":"FLO"}}}"""),
            ),
            now = 1_000,
        )
        if (synced) store.markWorklogsSynced(connId, id, now = 2_000)
    }

    private suspend fun worklogsSyncedAt(connId: UInt, issueId: Long): Long? = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.select(JiraRawStore.Issues.worklogsSyncedAt)
            .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }
            .map { it[JiraRawStore.Issues.worklogsSyncedAt] }.toList().single()
    }

    private suspend fun cursor(connId: UInt): WorklogsCursor? =
        cursors.get(connId, "worklogs")?.let { Json.decodeFromString<WorklogsCursor>(it.cursor) }

    /** [padding] goes into a key the trim KEEPS (`properties`), [commentPadding] into one it DROPS (`comment`): only the first counts. */
    private fun worklog(id: Long, issueId: Long, padding: Int = 0, commentPadding: Int = 0): String {
        val kept = if (padding > 0) ""","properties":[{"key":"k","value":"${"x".repeat(padding)}"}]""" else ""
        val dropped = if (commentPadding > 0) ""","comment":"${"c".repeat(commentPadding)}"""" else ""
        val pad = kept + dropped
        return """{"id":"$id","issueId":"$issueId","updated":"2026-05-20T10:00:00.000+0000"$pad}"""
    }

    private fun issuePage(issueId: Long, startAt: Int, total: Int, padding: Int = 0, commentPadding: Int = 0) =
        """{"startAt":$startAt,"maxResults":1,"total":$total,""" +
            """"worklogs":[${worklog(issueId * 1_000_000 + startAt, issueId, padding, commentPadding)}]}"""

    /** A host whose pages for [issueId] never end: one worklog a page and a `total` it never reaches. */
    private fun endless(issueId: Long, startAt: Int, padding: Int = 0) = issuePage(issueId, startAt, Int.MAX_VALUE, padding)

    private fun entries(ids: Iterable<Long>) = ids.joinToString(",") { """{"worklogId":$it,"updatedTime":1}""" }

    private fun feed(vararg ids: Long, since: Long = 0, until: Long = NOW, lastPage: Boolean = true) =
        """{"values":[${entries(ids.toList())}],"since":$since,"until":$until,"lastPage":$lastPage}"""

    /** The host: every answer is a scripted function of the request, and every call is recorded. */
    private class Host {
        val perIssueCalls = ConcurrentHashMap<Long, AtomicInteger>()
        val updatedSinces = CopyOnWriteArrayList<Long>()
        val deletedSinces = CopyOnWriteArrayList<Long>()
        val listAsks = CopyOnWriteArrayList<List<Long>>()
        val inFlight = InFlight()

        var perIssue: (issueId: Long, startAt: Int) -> String = { _, _ -> """{"startAt":0,"maxResults":1,"total":0,"worklogs":[]}""" }
        var updated: (since: Long) -> String = { """{"values":[],"since":0,"until":$NOW,"lastPage":true}""" }
        var deleted: (since: Long) -> String = { """{"values":[],"since":0,"until":$NOW,"lastPage":true}""" }
        var list: (ids: List<Long>) -> String = { "[]" }

        private fun sinceOf(parameters: Parameters) = parameters["since"]!!.toLong()

        fun calls(issueId: Long): Int = perIssueCalls[issueId]?.get() ?: 0
        fun totalIssueCalls(): Int = perIssueCalls.values.sumOf { it.get() }

        fun client(): HttpJiraClient {
            val engine = MockEngine { request ->
                val path = request.url.encodedPath
                val body = when {
                    path == "/_edge/tenant_info" -> """{"cloudId":"$CLOUD_ID"}"""
                    path.endsWith("/worklog/updated") -> updated(sinceOf(request.url.parameters).also { updatedSinces += it })
                    path.endsWith("/worklog/deleted") -> deleted(sinceOf(request.url.parameters).also { deletedSinces += it })
                    path.endsWith("/worklog/list") -> {
                        val text = (request.body as TextContent).text
                        val ids = Json.parseToJsonElement(text).jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.long }
                        listAsks += ids
                        list(ids)
                    }
                    else -> {
                        val issueId = ISSUE_WORKLOG_PATH.matchEntire(path)!!.groupValues[1].toLong()
                        val startAt = request.url.parameters["startAt"]!!.toInt()
                        perIssueCalls.computeIfAbsent(issueId) { AtomicInteger() }.incrementAndGet()
                        inFlight.track { delay(CALL_MILLIS); perIssue(issueId, startAt) }
                    }
                }
                respond(body, HttpStatusCode.OK, JSON_HEADERS)
            }
            val http = JiraHttp(
                HttpClient(engine) { expectSuccess = false; followRedirects = false },
                maxRetries = 0, maxResponseBytes = 32L * MIB, maxConcurrentRequests = WINDOW, sleeper = {},
            )
            return HttpJiraClient(http, "https://stub.test", "https://stub.test", "svc@example.com", "token", JiraAuthScheme.BASIC)
        }
    }

    private fun context(connId: UInt, heartbeat: suspend (String?, String?) -> Boolean = { _, _ -> true }) =
        StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = heartbeat, clock = { NOW })

    private suspend fun run(
        connId: UInt,
        host: Host,
        ceilings: WorklogCeilings,
        context: StreamContext = context(connId),
    ): StreamContext {
        val client = host.client()
        client.resolveCloudId()
        JiraWorklogStream(client, store, ceilings).run(context)
        return context
    }

    private fun small(
        backfill: AnswerCeiling = AnswerCeiling(maxChars = 8L * MIB, maxItems = 1_000, maxPages = PAGES),
        feedAnswer: AnswerCeiling = AnswerCeiling(maxChars = 8L * MIB, maxItems = 1_000, maxPages = 1_000),
        maxFeedPages: Int = PAGES,
    ) = WorklogCeilings(backfill, feedAnswer, maxFeedPages)

    // --- The per-issue backfill ---------------------------------------------------------------------------------

    @Test
    fun `an issue whose total never ends is skipped at the page ceiling, left unsynced and stores nothing, its neighbours are stored`() =
        runBlocking<Unit> {
            val connId = connection()
            seedIssues(connId, 1, 2, 3)
            val host = Host().apply {
                perIssue = { id, startAt -> if (id == 2L) endless(id, startAt) else issuePage(id, startAt, total = 1) }
            }
            val context = run(connId, host, small())
            assertEquals(PAGES, host.calls(2), "the chain stops at the page ceiling, however large the host's total")
            assertEquals(1L, context.progressSnapshot()["worklogsIssuesSkipped"])
            assertEquals(2L, store.countWorklogs(connId), "only the neighbours' worklogs")
            assertNotNull(worklogsSyncedAt(connId, 1))
            assertNull(worklogsSyncedAt(connId, 2), "unsynced: the next run retries it")
            assertNotNull(worklogsSyncedAt(connId, 3))
        }

    @Test
    fun `an endless chain of tiny worklogs stops at the worklog ceiling before the page ceiling`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1)
        val host = Host().apply { perIssue = { id, startAt -> endless(id, startAt) } }
        val context = run(connId, host, small(backfill = AnswerCeiling(8L * MIB, maxItems = 3, maxPages = 50)))
        assertEquals(4, host.calls(1), "the 4th worklog passes the ceiling of 3")
        assertEquals(1L, context.progressSnapshot()["worklogsIssuesSkipped"])
        assertEquals(0L, store.countWorklogs(connId))
    }

    @Test
    fun `a giant worklog answer stops at the text ceiling, counted over the trimmed text across pages`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1)
        // Each page is ~300 characters of kept text; the ceiling is 700: the third page crosses it, no page alone does.
        val host = Host().apply { perIssue = { id, startAt -> endless(id, startAt, padding = 200) } }
        val context = run(connId, host, small(backfill = AnswerCeiling(maxChars = 700, maxItems = 1_000, maxPages = 50)))
        assertEquals(3, host.calls(1))
        assertEquals(1L, context.progressSnapshot()["worklogsIssuesSkipped"])
        assertEquals(0L, store.countWorklogs(connId))
    }

    @Test
    fun `an endless host for every issue costs bounded calls, never more than the window in flight, then fails the run`() =
        runBlocking<Unit> {
            val connId = connection()
            seedIssues(connId, *LongArray(NO_SKIP_BUDGET_ISSUES) { it + 1L })
            val host = Host().apply { perIssue = { id, startAt -> endless(id, startAt) } }
            val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
            assertEquals("LIMIT_EXCEEDED", failure.code)
            assertTrue("answer ceiling" in failure.message.orEmpty(), failure.message)
            assertTrue("WORKLOGS skipped 11 of 11 issues" in failure.message.orEmpty(), failure.message)
            host.perIssueCalls.forEach { (issue, calls) -> assertTrue(calls.get() <= PAGES, "issue $issue was asked ${calls.get()} pages") }
            assertTrue(host.totalIssueCalls() <= NO_SKIP_BUDGET_ISSUES * PAGES, "calls: ${host.totalIssueCalls()}")
            assertTrue(host.inFlight.peak.get() in 2..WINDOW, "concurrent, within the window: ${host.inFlight.peak.get()}")
            assertEquals(0L, store.countWorklogs(connId))
            (1L..NO_SKIP_BUDGET_ISSUES).forEach { assertNull(worklogsSyncedAt(connId, it)) }
            assertNull(cursor(connId), "the stream ended in the backfill")
        }

    @Test
    fun `a batch holding more than the flush bound of stored text is written before the next issue is settled`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1, 2, 3, 4)
        // Three issues of ~1.5 MiB of kept text each: the batch crosses the 4 Mi-character flush bound at the third and is flushed there.
        val host = Host().apply { perIssue = { id, startAt -> issuePage(id, startAt, total = 1, padding = 3 * MIB / 2) } }
        val storedAtHeartbeat = CopyOnWriteArrayList<Long>()
        val context = context(connId) { _, _ -> true.also { storedAtHeartbeat += store.countWorklogs(connId) } }
        run(connId, host, small(backfill = AnswerCeiling(8L * MIB, 1_000, PAGES)), context)
        assertEquals(4L, store.countWorklogs(connId))
        assertEquals(listOf(0L, 0L, 0L, 3L), storedAtHeartbeat.take(4), "the first three issues were flushed together")
    }

    // --- The incremental feed -----------------------------------------------------------------------------------

    @Test
    fun `a feed that always says another page follows stops at the page ceiling with the cursor at the last committed page`() =
        runBlocking<Unit> {
            val connId = connection()
            seedIssues(connId, 7, synced = true)
            val host = Host().apply { updated = { since -> feed(since = since, until = since + 1_000, lastPage = false) } }
            val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
            assertEquals("LIMIT_EXCEEDED", failure.code)
            assertTrue("more than $PAGES feed pages in one run" in failure.message.orEmpty(), failure.message)
            assertEquals(PAGES, host.updatedSinces.size, "never a call past the ceiling")
            assertEquals(WorklogsCursor(updatedSince = NOW + PAGES * 1_000, deletedSince = NOW), cursor(connId), "the committed pages stay")
            assertTrue(host.deletedSinces.isEmpty(), "the deleted feed never ran")
        }

    @Test
    fun `a feed page that says another follows but does not move until is refused at once`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply { updated = { since -> feed(since = since, until = since, lastPage = false) } }
        val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertEquals(1, host.updatedSinces.size)
        assertNull(cursor(connId), "nothing was persisted")
    }

    @Test
    fun `a far-future until is refused before it is persisted, so the feed is not silenced`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply {
            updated = { feed(11, until = FAR_FUTURE) }
            list = { ids -> "[${ids.joinToString(",") { worklog(it, 7) }}]" }
        }
        val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertTrue("until" in failure.message.orEmpty(), failure.message)
        assertNull(cursor(connId), "no cursor at all, so the next run asks since = its own start again")
        assertTrue(host.listAsks.isEmpty(), "the page was refused before its details were fetched")
        assertEquals(0L, store.countWorklogs(connId))
    }

    @Test
    fun `a far-future until on the deleted feed is refused too, and the updated cursor stays`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply { deleted = { feed(until = FAR_FUTURE) } }
        val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertEquals(WorklogsCursor(updatedSince = NOW, deletedSince = NOW), cursor(connId), "deletedSince did not take the host's value")
    }

    @Test
    fun `an until a little ahead of the clock (within the skew) is accepted`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val ahead = NOW + FEED_CLOCK_SKEW_MILLIS - 1
        val host = Host().apply { updated = { feed(until = ahead) } }
        run(connId, host, small())
        assertEquals(ahead, cursor(connId)?.updatedSince)
    }

    @Test
    fun `a feed page with more entries than the ceiling is refused before any detail is fetched`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply { updated = { feed(11, 12, 13, 14) } }
        val failure = assertFailsWith<JiraFetchException> {
            run(connId, host, small(feedAnswer = AnswerCeiling(8L * MIB, maxItems = 3, maxPages = 10)))
        }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue("a feed page of 4 entries, more than 3" in failure.message.orEmpty(), failure.message)
        assertTrue(host.listAsks.isEmpty())
        assertNull(cursor(connId))
    }

    @Test
    fun `details over the text ceiling fail the page whole, are not halved, store nothing and leave the cursor`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply {
            updated = { feed(11, 12) }
            list = { ids -> "[${ids.joinToString(",") { worklog(it, 7, padding = 400) }}]" }
        }
        val failure = assertFailsWith<JiraFetchException> {
            run(connId, host, small(feedAnswer = AnswerCeiling(maxChars = 600, maxItems = 1_000, maxPages = 10)))
        }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue("characters" in failure.message.orEmpty(), failure.message)
        assertEquals(listOf(listOf(11L, 12L)), host.listAsks.toList(), "one ask: the answer was too big, not the response")
        assertEquals(0L, store.countWorklogs(connId))
        assertNull(cursor(connId))
    }

    @Test
    fun `a list answer with more worklogs than the ceiling fails the page whole`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply {
            updated = { feed(11, 12) }
            list = { "[${(1L..3L).joinToString(",") { worklog(it, 7) }}]" }
        }
        val failure = assertFailsWith<JiraFetchException> {
            run(connId, host, small(feedAnswer = AnswerCeiling(8L * MIB, maxItems = 2, maxPages = 10)))
        }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue("more than 2 worklogs" in failure.message.orEmpty(), failure.message)
        assertNull(cursor(connId))
    }

    @Test
    fun `a feed truncated by a ceiling on its second page keeps the first page and its cursor, and no more`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply {
            updated = { since ->
                if (since == NOW) feed(11, since = since, until = NOW + 1_000, lastPage = false) else feed(21, 22, 23, 24, since = since)
            }
            list = { ids -> "[${ids.joinToString(",") { worklog(it, 7) }}]" }
        }
        assertFailsWith<JiraFetchException> {
            run(connId, host, small(feedAnswer = AnswerCeiling(8L * MIB, maxItems = 3, maxPages = 10)))
        }
        assertEquals(1L, store.countWorklogs(connId), "page one is stored")
        assertEquals(WorklogsCursor(updatedSince = NOW + 1_000, deletedSince = NOW), cursor(connId), "the cursor stops at page one's until")
    }

    @Test
    fun `an ordinary multi-page feed is unchanged by the ceilings`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply {
            updated = { since ->
                if (since == NOW) {
                    feed(11, since = since, until = NOW + 1_000, lastPage = false)
                } else {
                    feed(12, since = since, until = NOW + 2_000)
                }
            }
            list = { ids -> "[${ids.joinToString(",") { worklog(it, 7) }}]" }
        }
        val context = run(connId, host, small())
        assertEquals(2L, store.countWorklogs(connId))
        assertEquals(2L, context.progressSnapshot()["worklogs"])
        assertEquals(WorklogsCursor(updatedSince = NOW + 2_000, deletedSince = NOW), cursor(connId))
    }

    @Test
    fun `an out-of-scope worklog of the instance-wide feed is dropped unread even when its own fields are malformed`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply {
            updated = { feed(11, 12) }
            list = { """[${worklog(11, 7)},{"issueId":"99999","note":"no id, no updated"}]""" }
        }
        val context = run(connId, host, small())
        assertEquals(1L, store.countWorklogs(connId))
        assertEquals(1L, context.progressSnapshot()["worklogsOutOfScope"])
    }

    @Test
    fun `text the trim drops does not count toward the backfill's text ceiling`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1)
        // 5,000 characters of comment per worklog, 3 worklogs: far over the 700-character ceiling untrimmed, ~100 each trimmed.
        val host = Host().apply { perIssue = { id, startAt -> issuePage(id, startAt, total = 3, commentPadding = 5_000) } }
        val context = run(connId, host, small(backfill = AnswerCeiling(maxChars = 700, maxItems = 1_000, maxPages = 50)))
        assertNull(context.progressSnapshot()["worklogsIssuesSkipped"])
        assertEquals(3L, store.countWorklogs(connId))
        assertNotNull(worklogsSyncedAt(connId, 1))
    }

    @Test
    fun `text the trim drops does not count toward the feed details' text ceiling either`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply {
            updated = { feed(11, 12) }
            list = { ids -> "[${ids.joinToString(",") { worklog(it, 7, commentPadding = 5_000) }}]" }
        }
        run(connId, host, small(feedAnswer = AnswerCeiling(maxChars = 600, maxItems = 1_000, maxPages = 10)))
        assertEquals(2L, store.countWorklogs(connId))
    }

    @Test
    fun `the deleted feed stops at the page ceiling too, with its cursor at the last committed page`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply { deleted = { since -> feed(since = since, until = since + 1_000, lastPage = false) } }
        val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue("more than $PAGES feed pages in one run" in failure.message.orEmpty(), failure.message)
        assertEquals(PAGES, host.deletedSinces.size)
        assertEquals(WorklogsCursor(updatedSince = NOW, deletedSince = NOW + PAGES * 1_000), cursor(connId))
    }

    @Test
    fun `a last page whose until is missing, negative or before since never rewinds either cursor`() = runBlocking<Unit> {
        listOf(0L, -5L, NOW - 1).forEach { until ->
            val connId = connection()
            seedIssues(connId, 7, synced = true)
            val host = Host().apply {
                updated = { feed(until = until) }
                deleted = { feed(until = until) }
            }
            run(connId, host, small())
            assertEquals(WorklogsCursor(updatedSince = NOW, deletedSince = NOW), cursor(connId), "until $until")
        }
    }

    @Test
    fun `an in-scope worklog with an unusable id or updated fails INVALID_RESPONSE without the host's text`() = runBlocking<Unit> {
        listOf(
            """{"id":"11","issueId":"7","updated":"secret-host-text"}""",
            """{"id":"secret-host-text","issueId":"7","updated":"2026-05-20T10:00:00.000+0000"}""",
            """{"issueId":"7","updated":"2026-05-20T10:00:00.000+0000"}""",
        ).forEach { listed ->
            val connId = connection()
            seedIssues(connId, 7, synced = true)
            val host = Host().apply { updated = { feed(11) }; list = { "[$listed]" } }
            val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
            assertEquals("INVALID_RESPONSE", failure.code, listed)
            assertTrue("secret-host-text" !in failure.message.orEmpty(), failure.message)
            assertNull(cursor(connId), "the page did not commit")
        }
    }

    @Test
    fun `a listed worklog without a usable issueId fails INVALID_RESPONSE before the scope filter`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 7, synced = true)
        val host = Host().apply { updated = { feed(11) }; list = { """[{"id":"11","issueId":"secret-host-text"}]""" } }
        val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertTrue("secret-host-text" !in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a backfilled worklog with an unusable id fails INVALID_RESPONSE and leaves the issue unsynced`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1)
        val host = Host().apply {
            perIssue = { _, _ ->
                """{"startAt":0,"maxResults":1,"total":1,"worklogs":[{"id":"x","issueId":"1","updated":"2026-05-20T10:00:00.000+0000"}]}"""
            }
        }
        val failure = assertFailsWith<JiraFetchException> { run(connId, host, small()) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertNull(worklogsSyncedAt(connId, 1))
    }

    // --- The lever ----------------------------------------------------------------------------------------------

    @Test
    fun `the answer holds up to each ceiling and throws LIMIT_EXCEEDED past it`() {
        val answer = BoundedAnswer<String>("/e", AnswerCeiling(maxChars = 10, maxItems = 3, maxPages = 2), "things")
        answer.nextPage()
        answer.nextPage()
        val pageFailure = assertFailsWith<JiraFetchException> { answer.nextPage() }
        assertEquals("more than 2 pages", pageFailure.message.orEmpty().substringAfter("ceiling: "))
        answer.add("a", 5)
        answer.add("b", 5)
        val text = assertFailsWith<JiraFetchException> { answer.add("c", 1) }
        assertEquals("LIMIT_EXCEEDED", text.code)
        assertTrue("more than 10 characters" in text.message.orEmpty(), text.message)
        assertEquals(listOf("a", "b"), answer.items, "the item past the ceiling is not kept")
        val count = BoundedAnswer<String>("/e", AnswerCeiling(maxChars = 100, maxItems = 1, maxPages = 2), "things")
        count.add("a", 1)
        assertTrue("more than 1 things" in assertFailsWith<JiraFetchException> { count.add("b", 1) }.message.orEmpty())
    }

    @Test
    fun `a page ceiling alone counts calls and names what it counts`() {
        val pages = PageCeiling("/e", 2, "boards")
        pages.next()
        pages.next()
        val failure = assertFailsWith<JiraFetchException> { pages.next() }
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue("more than 2 boards" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a feed page is plausible when it is within the entries ceiling, the skew and, if not last, moves on`() {
        fun page(until: Long, lastPage: Boolean = true, entries: Int = 0) = Json.decodeFromString<JiraWorklogIdsPage>(
            """{"values":[${entries((1L..entries).toList())}],"until":$until,"lastPage":$lastPage}""",
        )
        page(until = NOW, entries = 2).requirePlausible("/e", since = 0, now = NOW, maxEntries = 2)
        page(until = NOW + FEED_CLOCK_SKEW_MILLIS).requirePlausible("/e", since = 0, now = NOW, maxEntries = 2)
        page(until = 5, lastPage = true).requirePlausible("/e", since = NOW, now = NOW, maxEntries = 2) // a last page may be old
        assertEquals("LIMIT_EXCEEDED", assertFailsWith<JiraFetchException> {
            page(until = NOW, entries = 3).requirePlausible("/e", 0, NOW, 2)
        }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<JiraFetchException> {
            page(until = NOW + FEED_CLOCK_SKEW_MILLIS + 1).requirePlausible("/e", 0, NOW, 2)
        }.code)
        assertEquals("INVALID_RESPONSE", assertFailsWith<JiraFetchException> {
            page(until = NOW, lastPage = false).requirePlausible("/e", NOW, NOW, 2)
        }.code)
    }
}
