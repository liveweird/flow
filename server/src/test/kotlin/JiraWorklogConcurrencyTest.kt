package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraWorklogStream
import ch.nokillswit.jira.RawIssueInput
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

private const val CLOUD_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
private val ISSUE_WORKLOG_PATH = Regex(".*/rest/api/3/issue/([0-9]+)/worklog")
private const val EMPTY_FEED = """{"values":[],"since":0,"until":5,"lastPage":true}"""
private const val CALL_MILLIS = 25L

/**
 * The WORKLOGS per-issue backfill fetches a claimed batch CONCURRENTLY but stores it in claim order (`jira/JiraWorklogStream.kt`
 * `fetchAndFlush`, `build-times.md` WHY 33). The real [HttpJiraClient] and [JiraHttp] (so the real semaphore) run over a Ktor
 * `MockEngine` that counts the calls in flight at the "wire"; the raw store is the real one on the shared test database, one
 * connection per test. What must hold: never more calls in flight than `maxConcurrentRequests`, the same rows and stamps as the
 * sequential loop, a failure leaving exactly what it left before, and cancellation reaching the in-flight calls.
 */
class JiraWorklogConcurrencyTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()
    private val connections = mutableListOf<UInt>()

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = "jira-worklog-concurrency", enabled = false, projectKeys = listOf("FLO"))
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

    private suspend fun seedIssues(connId: UInt, ids: IntRange) = ids.forEach { id ->
        store.upsertIssue(
            connId,
            RawIssueInput(
                issueId = id.toLong(), issueKey = "FLO-$id", projectId = 1L, projectKey = "FLO", issueUpdatedAt = 1_000,
                payloadJson = """{"id":"$id","key":"FLO-$id","fields":{"project":{"id":"1","key":"FLO"}}}""",
            ),
            now = 1_000,
        )
    }

    private suspend fun worklogsSyncedAt(connId: UInt, issueId: Int): Long? = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.select(JiraRawStore.Issues.worklogsSyncedAt)
            .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId.toLong()) }
            .map { it[JiraRawStore.Issues.worklogsSyncedAt] }.toList().single()
    }

    private fun worklogBody(issueId: Long) =
        """{"startAt":0,"maxResults":100,"total":1,"worklogs":[""" +
            """{"id":"${issueId * 10}","issueId":"$issueId","updated":"2026-05-20T10:00:00.000+0000"}]}"""

    /** Answers each per-issue worklog GET with [perIssue] (counted in [inFlight] while it runs); the tenant and the feeds are quiet. */
    private fun client(
        limit: Int,
        inFlight: InFlight,
        perIssue: suspend MockRequestHandleScope.(Long) -> HttpResponseData = { id ->
            delay(CALL_MILLIS)
            respond(worklogBody(id), HttpStatusCode.OK, JSON_HEADERS)
        },
    ): HttpJiraClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/_edge/tenant_info" -> respond("""{"cloudId":"$CLOUD_ID"}""", HttpStatusCode.OK, JSON_HEADERS)
                path.endsWith("/worklog/updated") || path.endsWith("/worklog/deleted") ->
                    respond(EMPTY_FEED, HttpStatusCode.OK, JSON_HEADERS)
                else -> {
                    val issueId = ISSUE_WORKLOG_PATH.matchEntire(path)!!.groupValues[1].toLong()
                    inFlight.track { perIssue(issueId) }
                }
            }
        }
        val http = JiraHttp(
            HttpClient(engine) { expectSuccess = false; followRedirects = false },
            maxRetries = 0, maxResponseBytes = 1_000_000, maxConcurrentRequests = limit, sleeper = {},
        )
        return HttpJiraClient(http, "https://stub.test", "https://stub.test", "svc@example.com", "token", JiraAuthScheme.BASIC)
    }

    private fun context(connId: UInt, heartbeat: suspend (String?, String?) -> Boolean = { _, _ -> true }) =
        StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = heartbeat)

    private suspend fun run(connId: UInt, client: HttpJiraClient, context: StreamContext = context(connId)): StreamContext {
        client.resolveCloudId()
        JiraWorklogStream(client, store).run(context)
        return context
    }

    @Test
    fun `the backfill never has more calls in flight than maxConcurrentRequests, and stores what the loop did`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1..60)
        val inFlight = InFlight()
        val context = run(connId, client(limit = 3, inFlight))
        assertEquals(3, inFlight.peak.get(), "concurrent, and capped at the client's own limit")
        assertEquals(60L, store.countWorklogs(connId))
        assertEquals(60L, context.progressSnapshot()["worklogs"])
        (1..60).forEach { assertNotNull(worklogsSyncedAt(connId, it), "issue $it stamped") }
        assertNotNull(cursors.get(connId, "worklogs"), "the incremental cursor is written after the backfill")
    }

    @Test
    fun `a client limit of 1 keeps the backfill strictly sequential`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1..12)
        val inFlight = InFlight()
        run(connId, client(limit = 1, inFlight))
        assertEquals(1, inFlight.peak.get())
        assertEquals(12L, store.countWorklogs(connId))
    }

    @Test
    fun `a failure leaves what the sequential loop left - the issues before the first failure in claim order are kept, nothing after it`() =
        runBlocking<Unit> {
            val connId = connection()
            seedIssues(connId, 1..8)
            val inFlight = InFlight()
            // Issue 3 fails SLOWLY with a 500; issue 5 fails at once with a 401 and issues 4 and 6..8 succeed: claim order decides.
            val client = client(limit = 4, inFlight) { id ->
                when (id) {
                    3L -> { delay(4 * CALL_MILLIS); respond("{}", HttpStatusCode.InternalServerError, JSON_HEADERS) }
                    5L -> respond("{}", HttpStatusCode.Unauthorized, JSON_HEADERS)
                    else -> { delay(CALL_MILLIS); respond(worklogBody(id), HttpStatusCode.OK, JSON_HEADERS) }
                }
            }
            val failure = assertFailsWith<JiraFetchException> { run(connId, client) }
            assertEquals("UPSTREAM_UNAVAILABLE", failure.code, "issue 3's failure, not issue 5's that arrived first")
            assertEquals(2L, store.countWorklogs(connId), "issues 1 and 2 were before the failure")
            assertNotNull(worklogsSyncedAt(connId, 1))
            assertNotNull(worklogsSyncedAt(connId, 2))
            (3..8).forEach { assertNull(worklogsSyncedAt(connId, it), "issue $it is not stamped: the next run re-fetches it") }
            assertNull(cursors.get(connId, "worklogs"), "no incremental cursor: the stream ended in the backfill")
            inFlight.awaitDrained("nothing is left running after the stream failed")

            run(connId, client(limit = 4, InFlight()))
            assertEquals(8L, store.countWorklogs(connId), "a re-run stores the rest")
        }

    @Test
    fun `cancelling the job cancels the worklog calls in flight and stores nothing`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1..20)
        val inFlight = InFlight()
        val saturated = CompletableDeferred<Unit>()
        val client = client(limit = 4, inFlight) {
            if (inFlight.now.get() == 4) saturated.complete(Unit)
            awaitCancellation()
        }
        client.resolveCloudId()
        val job = launch(Dispatchers.Default) { JiraWorklogStream(client, store).run(context(connId)) }
        withTimeout(10_000) { saturated.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        inFlight.awaitDrained("the in-flight calls were cancelled with the job")
        assertEquals(0L, store.countWorklogs(connId))
        (1..20).forEach { assertNull(worklogsSyncedAt(connId, it)) }
    }

    @Test
    fun `a lost lease ends the backfill at the first heartbeat, cancels the calls ahead and stores nothing`() = runBlocking<Unit> {
        val connId = connection()
        seedIssues(connId, 1..20)
        val inFlight = InFlight()
        val client = client(limit = 4, inFlight)
        assertFailsWith<ch.nokillswit.ingest.LeaseLostException> { run(connId, client, context(connId) { _, _ -> false }) }
        inFlight.awaitDrained("the calls ahead were cancelled with the backfill")
        assertEquals(0L, store.countWorklogs(connId))
        assertNull(worklogsSyncedAt(connId, 1), "the first issue was fetched but its batch never flushed")
    }
}
