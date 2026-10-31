package ch.nokillswit

import ch.nokillswit.fetch.AnswerCeiling
import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.MAX_PAGE_TOKEN_CHARS
import ch.nokillswit.fetch.RunCeiling
import ch.nokillswit.fetch.SourceHttp
import ch.nokillswit.ingest.CeilingTripException
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.LIMIT_EXCEEDED_TRIP
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.ISSUES_RUN_CEILING
import ch.nokillswit.jira.IssuesCursor
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReconcileStream
import ch.nokillswit.jira.JiraReferenceStream
import ch.nokillswit.jira.ProjectFieldsCeilings
import ch.nokillswit.jira.RECONCILE_RUN_CEILING
import ch.nokillswit.jira.ReconcileCursor
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.ReferenceCeilings
import ch.nokillswit.jira.ReferenceCursor
import ch.nokillswit.jira.backfillFromEpochMillis
import ch.nokillswit.jira.createJiraHttp
import ch.nokillswit.jira.decodeCursor
import ch.nokillswit.jira.decodeIssuesCursor
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLOUD_ID = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
private const val NOW = 1_800_000_000_000L
private const val BACKFILL_FROM = "2026-01-01"
private const val PAGES = 3
private const val FOREVER = Int.MAX_VALUE
private const val ROWS = 1_000_000

/** A handler answer that plays a rejected token: HTTP 400, which `searchJql` maps to `CURSOR_EXPIRED` for a request carrying one. */
private const val EXPIRED = "<expired>"

/** Host-supplied text that must never reach a failure message. */
private const val HOST_TEXT = "HOSTSECRET"

/**
 * The paging ceilings of the REFERENCE, ISSUES and RECONCILE loops (`.claude/docs/jira-integration.md` "Paging ceilings"): a Jira
 * host that never stops paging, cycles its `nextPageToken`, or packs a giant answer must end in a typed failure (or a skipped
 * project), with the cursor at the last page that COMMITTED - never unbounded work, growth or a cursor past stored data. The real
 * [HttpJiraClient], [SourceHttp] and raw store run over a Ktor `MockEngine` that plays the host; the ceilings are small so each attack
 * is cheap. Every test owns its connection.
 */
class JiraPagingCeilingTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()
    private val connections = mutableListOf<UInt>()

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(namePrefix = "jira-paging-ceiling", enabled = false, projectKeys = listOf("FLO"))
            .also { connections += it }
    }

    @AfterTest
    fun dropConnections() = runBlocking<Unit> {
        connections.forEach { connId ->
            suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.ReconcileSeen.deleteWhere { JiraRawStore.ReconcileSeen.connectionId eq connId }
                JiraRawStore.Entities.deleteWhere { JiraRawStore.Entities.connectionId eq connId }
                JiraRawStore.Issues.deleteWhere { JiraRawStore.Issues.connectionId eq connId }
            }
            SyncedStubFixture.dataSources().delete(connId)
        }
        connections.clear()
    }

    // --- The host ---------------------------------------------------------------------------------------------------

    private fun startAtPage(startAt: Int, values: List<String>, total: Int, isLast: Boolean? = null) =
        """{"startAt":$startAt,"maxResults":${values.size},"total":$total,""" +
            (isLast?.let { """"isLast":$it,""" } ?: "") + """"values":[${values.joinToString(",")}]}"""

    /** A page of one entity that never ends: `total` unreachable, `isLast` false. */
    private fun endlessEntity(startAt: Int, entity: String) = startAtPage(startAt, listOf(entity), total = FOREVER, isLast = false)

    private fun searchPage(issues: List<String>, nextPageToken: String?) =
        """{"issues":[${issues.joinToString(",")}]""" + (nextPageToken?.let { ""","nextPageToken":"$it"""" } ?: "") + "}"

    private fun issue(id: Long) = """{"id":"$id","key":"FLO-$id","fields":{"project":{"id":"1","key":"FLO"},""" +
        """"updated":"2026-05-20T10:00:00.000+0000"}}"""

    private fun idOnly(id: Long) = """{"id":"$id"}"""

    private fun inScopeScrumBoard(id: Int = 7) = """{"id":$id,"type":"scrum","location":{"projectKey":"FLO"}}"""

    /** The host: each route answers a function of its `startAt`/token, finite and empty unless a test says otherwise. */
    private class Host {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val handlers = ConcurrentHashMap<String, (startAt: Int, token: String?) -> String>()

        /** Every `startAt` asked of a route, in order. */
        val starts = ConcurrentHashMap<String, CopyOnWriteArrayList<Int>>()

        fun calls(label: String): Int = calls[label]?.get() ?: 0

        private fun label(path: String): String = when {
            path == "/_edge/tenant_info" -> "tenant"
            path.endsWith("/rest/api/3/field") -> "fields"
            path.endsWith("/statuscategory") -> "statusCategories"
            path.endsWith("/issuetype") -> "issueTypes"
            path.endsWith("/issueLinkType") -> "issueLinkTypes"
            path.endsWith("/statuses/search") -> "statuses"
            path.endsWith("/project/search") -> "projects"
            path.endsWith("/resolution/search") -> "resolutions"
            path.endsWith("/projects/fields") -> "projectFields"
            path.endsWith("/statuses") -> "projectStatuses"
            path.endsWith("/users/search") -> "users"
            path.endsWith("/configuration") -> "boardConfig"
            path.endsWith("/sprint") -> "sprints"
            path.endsWith("/rest/agile/1.0/board") -> "boards"
            path.endsWith("/search/jql") -> "search"
            else -> "issue"
        }

        private val empty = """{"startAt":0,"maxResults":0,"total":0,"isLast":true,"values":[]}"""

        private fun default(label: String): String = when (label) {
            "tenant" -> """{"cloudId":"$CLOUD_ID"}"""
            "issueLinkTypes" -> """{"issueLinkTypes":[]}"""
            "fields", "statusCategories", "issueTypes", "users", "projectStatuses" -> "[]"
            "boardConfig", "issue" -> "{}"
            "search" -> """{"issues":[]}"""
            else -> empty
        }

        fun client(): HttpJiraClient {
            val engine = MockEngine { request ->
                val label = label(request.url.encodedPath)
                calls.computeIfAbsent(label) { AtomicInteger() }.incrementAndGet()
                val startAt = request.url.parameters["startAt"]?.toInt() ?: 0
                starts.computeIfAbsent(label) { CopyOnWriteArrayList() } += startAt
                val token = request.url.parameters["nextPageToken"]
                val body = handlers[label]?.invoke(startAt, token) ?: default(label)
                if (body == EXPIRED) {
                    respond("{}", HttpStatusCode.BadRequest, JSON_HEADERS)
                } else {
                    respond(body, HttpStatusCode.OK, JSON_HEADERS)
                }
            }
            val http = createJiraHttp(
                HttpClient(engine) { expectSuccess = false; followRedirects = false },
                maxRetries = 0, maxResponseBytes = 32L * 1024 * 1024, maxConcurrentRequests = 1, sleeper = {},
            )
            return HttpJiraClient(http, "https://stub.test", "https://stub.test", "svc@example.com", "token", JiraAuthScheme.BASIC)
        }
    }

    private fun context(connId: UInt) =
        StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true }, clock = { NOW })

    private suspend fun readyClient(host: Host): HttpJiraClient = host.client().also { it.resolveCloudId() }

    private suspend fun entities(connId: UInt, kind: JiraEntityKind): Int = store.entityRowsByKind(connId, kind.name).size

    private suspend fun referenceCursor(connId: UInt): ReferenceCursor? =
        cursors.get(connId, "reference")?.let { decodeCursor<ReferenceCursor>(it.cursor) }

    private suspend fun issuesCursor(connId: UInt): IssuesCursor? = cursors.get(connId, "issues")?.let { decodeIssuesCursor(it.cursor) }

    private fun small(
        startAt: RunCeiling = RunCeiling(PAGES, ROWS),
        users: RunCeiling = RunCeiling(PAGES, ROWS),
        sprints: RunCeiling = RunCeiling(PAGES, ROWS),
        projectFields: ProjectFieldsCeilings = ProjectFieldsCeilings(maxPagesPerGroup = PAGES),
        boards: AnswerCeiling = AnswerCeiling(maxChars = 1_000_000, maxItems = 1_000, maxPages = 1_000),
    ) = ReferenceCeilings(startAt, users, sprints, projectFields, boards)

    private suspend fun runReference(connId: UInt, host: Host, ceilings: ReferenceCeilings, context: StreamContext = context(connId)) =
        JiraReferenceStream(readyClient(host), store, listOf("FLO"), 0, ceilings).run(context).let { context }

    private fun assertCeiling(failure: FetchException, text: String) {
        assertEquals("LIMIT_EXCEEDED", failure.code)
        assertTrue("answer ceiling" in failure.message.orEmpty() && text in failure.message.orEmpty(), failure.message)
    }

    // --- REFERENCE ----------------------------------------------------------------------------------------------------

    @Test
    fun `an endless startAt step fails at the page ceiling with its cursor on the last committed page`() = runBlocking<Unit> {
        listOf(
            Triple("statuses", JiraEntityKind.STATUS, """{"id":"%d","name":"n"}"""),
            Triple("projects", JiraEntityKind.PROJECT, """{"id":"%d","key":"P%d"}"""),
            Triple("resolutions", JiraEntityKind.RESOLUTION, """{"id":"%d","name":"n"}"""),
            Triple("boards", JiraEntityKind.BOARD, """{"id":%d,"type":"kanban","location":{"projectKey":"FLO"}}"""),
        ).forEach { (label, kind, template) ->
            val connId = connection()
            val host = Host().apply {
                handlers[label] = { startAt, _ -> endlessEntity(startAt, template.replace("%d", (startAt + 1).toString())) }
            }
            val failure = assertFailsWith<FetchException> { runReference(connId, host, small()) }
            assertCeiling(failure, "more than $PAGES pages")
            assertEquals(PAGES, host.calls(label), "$label: the chain stops at the page ceiling, however large the host's total")
            assertEquals(PAGES, entities(connId, kind), "$label: exactly the committed pages are stored")
            val cursor = assertNotNull(referenceCursor(connId))
            assertEquals(kind, cursor.step, label)
            assertEquals(PAGES, cursor.startAt, "$label: the cursor names the next page to ask, so the next run resumes there")
        }
    }

    @Test
    fun `an endless users step fails at the page ceiling and its cursor resumes after the stored pages`() = runBlocking<Unit> {
        val connId = connection()
        val host = Host().apply { handlers["users"] = { startAt, _ -> """[{"accountId":"a$startAt"}]""" } }
        val failure = assertFailsWith<FetchException> { runReference(connId, host, small()) }
        assertCeiling(failure, "more than $PAGES pages")
        assertEquals(PAGES, host.calls("users"))
        assertEquals(PAGES, entities(connId, JiraEntityKind.USER))
        assertEquals(PAGES, assertNotNull(referenceCursor(connId)).startAt)
    }

    @Test
    fun `a board whose sprints never end fails the pass at the page ceiling`() = runBlocking<Unit> {
        val connId = connection()
        val host = Host().apply {
            handlers["boards"] = { startAt, _ -> startAtPage(startAt, listOf(inScopeScrumBoard()), total = 1) }
            handlers["sprints"] = { startAt, _ -> endlessEntity(startAt, """{"id":${startAt + 1},"name":"s"}""") }
        }
        val first = context(connId)
        val failure = assertFailsWith<FetchException> { runReference(connId, host, small(), first) }
        assertCeiling(failure, "more than $PAGES pages")
        assertEquals(PAGES, host.calls("sprints"))
        assertEquals(PAGES, entities(connId, JiraEntityKind.SPRINT))
        assertEquals(JiraEntityKind.SPRINT, assertNotNull(referenceCursor(connId)).step)
        assertTrue(first.ceilingTrip(LIMIT_EXCEEDED_TRIP, failure).madeProgress, "the first run stored three new sprints")

        // The pass re-pages the board from 0 and stores the same sprints again: pages commit, nothing changes, so the trip is no
        // progress and N of them in a row pause the connection (`CeilingPauseTest`) instead of reading as a still-advancing backfill.
        val second = context(connId)
        val again = assertFailsWith<FetchException> { runReference(connId, host, small(), second) }
        assertCeiling(again, "more than $PAGES pages")
        assertEquals(PAGES, entities(connId, JiraEntityKind.SPRINT))
        assertFalse(second.ceilingTrip(LIMIT_EXCEEDED_TRIP, again).madeProgress, "identical sprints re-stored are not progress")
    }

    @Test
    fun `a single-shot step whose entities all come back unchanged reports no change`() = runBlocking<Unit> {
        val connId = connection()
        val host = Host().apply { handlers["fields"] = { _, _ -> """[{"id":"f1","name":"n"}]""" } }
        // Every heartbeat costs 3 minutes of a 5-minute budget: the second one (the step after FIELD) is the wall-clock stop.
        fun ticking(): StreamContext {
            val clock = AtomicLong(NOW)
            return StreamContext(
                connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> clock.addAndGet(180_000); true },
                clock = clock::get, maxRunMillis = 300_000,
            )
        }
        val first = ticking()
        val inserted = assertFailsWith<CeilingTripException> { runReference(connId, host, small(), first) }
        assertTrue(inserted.madeProgress, "the first run inserted the FIELD entity")

        cursors.clear(connId, "reference")
        val again = ticking()
        val unchanged = assertFailsWith<CeilingTripException> { runReference(connId, host, small(), again) }
        assertEquals(1, entities(connId, JiraEntityKind.FIELD))
        assertFalse(unchanged.madeProgress, "the same entity again is UNCHANGED: a committed page, not a change")
    }

    @Test
    fun `the in-scope boards a step holds are bounded in rows and in pages, before its first write`() = runBlocking<Unit> {
        val six = Host().apply {
            handlers["boards"] = { startAt, _ ->
                startAtPage(startAt, listOf(inScopeScrumBoard(startAt + 7)), total = 6, isLast = startAt >= 5)
            }
        }
        val rows = connection()
        val byRows = assertFailsWith<FetchException> {
            runReference(rows, six, small(startAt = RunCeiling(10, ROWS), boards = AnswerCeiling(1_000_000, maxItems = 3, maxPages = 100)))
        }
        assertCeiling(byRows, "more than 3 boards")
        assertEquals(6, entities(rows, JiraEntityKind.BOARD), "the BOARD step itself paged through all six within its own ceiling")
        assertEquals(0, entities(rows, JiraEntityKind.BOARD_CONFIGURATION), "no board configuration was fetched on a refused answer")
        assertEquals(0, six.calls("boardConfig"))
        assertEquals(JiraEntityKind.BOARD_CONFIGURATION, assertNotNull(referenceCursor(rows)).step)

        val pages = connection()
        val byPages = assertFailsWith<FetchException> {
            runReference(pages, six, small(startAt = RunCeiling(10, ROWS), boards = AnswerCeiling(1_000_000, maxItems = 100, maxPages = 2)))
        }
        assertCeiling(byPages, "more than 2 pages")

        val chars = connection()
        val byChars = assertFailsWith<FetchException> {
            val boards = AnswerCeiling(maxChars = 100, maxItems = 100, maxPages = 100)
            runReference(chars, six, small(startAt = RunCeiling(10, ROWS), boards = boards))
        }
        assertCeiling(byChars, "more than 100 characters")
    }

    @Test
    fun `a project whose field scheme never ends is skipped and the pass goes on`() = runBlocking<Unit> {
        val connId = connection()
        val host = Host().apply {
            handlers["projects"] = { startAt, _ -> startAtPage(startAt, listOf("""{"id":"10","key":"FLO"}"""), total = 1, isLast = true) }
            handlers["projectStatuses"] = { _, _ -> """[{"id":"3"}]""" }
            handlers["projectFields"] = { startAt, _ -> endlessEntity(startAt, """{"fieldId":"f$startAt","workTypeId":3}""") }
        }
        val context = runReference(connId, host, small())
        assertEquals(PAGES, host.calls("projectFields"), "one group of work types: the chain stops at the page ceiling")
        assertEquals(1L, context.progressSnapshot()["projectFieldsSkipped"])
        assertEquals(0, entities(connId, JiraEntityKind.PROJECT_FIELDS), "nothing is stored for the skipped project")
        assertNull(referenceCursor(connId), "the pass completed")
    }

    @Test
    fun `a step whose pages carry too many rows is refused before the page is written`() = runBlocking<Unit> {
        // Two entities a page, three allowed: the second page would pass, so only the first is stored.
        val statuses = connection()
        val host = Host().apply {
            handlers["statuses"] = { startAt, _ ->
                startAtPage(startAt, listOf("""{"id":"${startAt + 1}"}""", """{"id":"${startAt + 2}"}"""), total = FOREVER, isLast = false)
            }
        }
        val failure = assertFailsWith<FetchException> { runReference(statuses, host, small(startAt = RunCeiling(100, 3))) }
        assertCeiling(failure, "more than 3 entities")
        assertEquals(2, entities(statuses, JiraEntityKind.STATUS), "page 1 only: the refused page was not written")
        assertEquals(2, assertNotNull(referenceCursor(statuses)).startAt)

        val users = connection()
        val twoUsers = Host().apply { handlers["users"] = { startAt, _ -> """[{"accountId":"a$startAt"},{"accountId":"b$startAt"}]""" } }
        val byUsers = assertFailsWith<FetchException> { runReference(users, twoUsers, small(users = RunCeiling(100, 3))) }
        assertCeiling(byUsers, "more than 3 users")
        assertEquals(2, entities(users, JiraEntityKind.USER))
        assertEquals(2, assertNotNull(referenceCursor(users)).startAt)
    }

    @Test
    fun `the sprint ceiling is one budget for the whole pass, across boards`() = runBlocking<Unit> {
        val connId = connection()
        // Two boards of two sprint pages each: no board alone passes the 3-request ceiling, the pass does.
        val host = Host().apply {
            handlers["boards"] = { startAt, _ ->
                startAtPage(startAt, listOf(inScopeScrumBoard(7), inScopeScrumBoard(8)), total = 2)
            }
            handlers["sprints"] = { startAt, _ -> startAtPage(startAt, listOf("""{"id":${startAt + 1},"name":"s"}"""), total = 2) }
        }
        val failure = assertFailsWith<FetchException> { runReference(connId, host, small()) }
        assertCeiling(failure, "more than $PAGES pages")
        assertEquals(PAGES, host.calls("sprints"), "board 7 took two requests, board 8 one before the pass budget ran out")
        assertEquals(listOf(0, 1, 0), host.starts.getValue("sprints").toList())
        assertEquals(JiraEntityKind.SPRINT, assertNotNull(referenceCursor(connId)).step)

        // The row budget is shared the same way: two boards of two sprints each against a ceiling of three.
        val rows = connection()
        val byRows = assertFailsWith<FetchException> {
            runReference(rows, host, small(sprints = RunCeiling(100, 3)))
        }
        assertCeiling(byRows, "more than 3 sprints")
    }

    @Test
    fun `a step that failed at its ceiling resumes at its stored startAt on the next run and completes the pass`() = runBlocking<Unit> {
        val connId = connection()
        val endless = Host().apply { handlers["statuses"] = { startAt, _ -> endlessEntity(startAt, """{"id":"${startAt + 1}"}""") } }
        assertFailsWith<FetchException> { runReference(connId, endless, small()) }
        assertEquals(PAGES, assertNotNull(referenceCursor(connId)).startAt)

        // The host now ends after five entities; the next run asks from startAt 3 with a fresh budget and finishes the pass.
        val ends = Host().apply {
            handlers["statuses"] = { startAt, _ -> startAtPage(startAt, listOf("""{"id":"${startAt + 1}"}"""), total = 5) }
        }
        runReference(connId, ends, small())
        assertEquals(listOf(PAGES, PAGES + 1), ends.starts.getValue("statuses").toList(), "resumed, not restarted from 0")
        assertEquals(5, entities(connId, JiraEntityKind.STATUS))
        assertNull(referenceCursor(connId), "the pass completed")
    }

    private fun projectWithTypes(types: Int, fields: (startAt: Int) -> String): Host = Host().apply {
        handlers["projects"] = { startAt, _ -> startAtPage(startAt, listOf("""{"id":"10","key":"FLO"}"""), total = 1, isLast = true) }
        handlers["projectStatuses"] = { _, _ -> (1..types).joinToString(",", "[", "]") { """{"id":"$it"}""" } }
        handlers["projectFields"] = { startAt, _ -> fields(startAt) }
    }

    @Test
    fun `the field-scheme page ceiling is per group of work types, not per project`() = runBlocking<Unit> {
        val connId = connection()
        // 30 work types are two groups (25 + 5), each two pages: four requests in all against a ceiling of three a group.
        val host = projectWithTypes(30) { startAt -> startAtPage(startAt, listOf("""{"fieldId":"f$startAt","workTypeId":3}"""), total = 2) }
        val context = runReference(connId, host, small())
        assertEquals(4, host.calls("projectFields"))
        assertNull(context.progressSnapshot()["projectFieldsSkipped"], "the project was stored, not skipped")
        assertEquals(1, entities(connId, JiraEntityKind.PROJECT_FIELDS))
    }

    @Test
    fun `a field scheme past its work-type, row or field-id ceiling skips the project`() = runBlocking<Unit> {
        val rows = { startAt: Int ->
            val pair = listOf("""{"fieldId":"a$startAt","workTypeId":1}""", """{"fieldId":"b$startAt","workTypeId":1}""")
            startAtPage(startAt, pair, total = 4)
        }
        listOf(
            Triple("work types", projectWithTypes(3, rows), ProjectFieldsCeilings(maxWorkTypes = 2)),
            Triple("rows", projectWithTypes(1, rows), ProjectFieldsCeilings(maxRows = 3)),
            Triple("field ids", projectWithTypes(1, rows), ProjectFieldsCeilings(maxFieldIds = 3)),
        ).forEach { (what, host, ceilings) ->
            val connId = connection()
            val context = runReference(connId, host, small(projectFields = ceilings))
            assertEquals(1L, context.progressSnapshot()["projectFieldsSkipped"], what)
            assertEquals(0, entities(connId, JiraEntityKind.PROJECT_FIELDS), what)
            assertNull(referenceCursor(connId), "$what: the pass went on to its end")
            // The work-type cap is checked before any request.
            if (what == "work types") assertEquals(0, host.calls("projectFields"))
        }
    }

    // --- ISSUES -------------------------------------------------------------------------------------------------------

    private suspend fun runIssues(
        connId: UInt,
        host: Host,
        ceiling: RunCeiling,
        context: StreamContext = context(connId),
    ): StreamContext {
        val client = readyClient(host)
        JiraIssuesStream(client, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL_FROM), BACKFILL_FROM, 10, 100, ceiling).run(context)
        return context
    }

    /** One issue a page (ids 1, 2, ...), the token of page n being `t<n>`; endless unless [lastPage] says where it ends. */
    private fun pagedIssues(perPage: Int = 1, lastPage: Int = FOREVER): (Int, String?) -> String = { _, token ->
        val page = token?.removePrefix("t")?.toInt()?.plus(1) ?: 1
        val ids = ((page - 1) * perPage + 1L)..(page * perPage.toLong())
        searchPage(ids.map { issue(it) }, if (page >= lastPage) null else "t$page")
    }

    @Test
    fun `an endless issue token fails at the page ceiling before the extra fetch, and the next run resumes where it stopped`() =
        runBlocking<Unit> {
            val connId = connection()
            val host = Host().apply { handlers["search"] = pagedIssues() }
            val failure = assertFailsWith<FetchException> { runIssues(connId, host, RunCeiling(PAGES, 1_000)) }
            assertCeiling(failure, "more than $PAGES pages")
            assertEquals(PAGES, host.calls("search"), "the (n+1)th page is never asked")
            assertEquals(PAGES.toLong(), store.countIssues(connId))
            val cursor = assertNotNull(issuesCursor(connId))
            assertEquals("t$PAGES", cursor.nextPageToken, "the cursor names the next page, which was not fetched")
            assertNull(cursor.watermarkAt, "the watermark only moves on the run's last page")

            // The next run resumes from that token (a fresh budget) and, the host now ending, completes.
            host.handlers["search"] = pagedIssues(lastPage = PAGES + 2)
            runIssues(connId, host, RunCeiling(PAGES, 1_000))
            assertEquals(PAGES + 2L, store.countIssues(connId))
            assertNull(assertNotNull(issuesCursor(connId)).nextPageToken)
            assertNotNull(assertNotNull(issuesCursor(connId)).watermarkAt)
        }

    @Test
    fun `a page that would pass the issue ceiling is refused before it is written, the cursor staying on the token that asked it`() =
        runBlocking<Unit> {
            val connId = connection()
            val host = Host().apply { handlers["search"] = pagedIssues(perPage = 2) }
            val failure = assertFailsWith<FetchException> { runIssues(connId, host, RunCeiling(maxPages = 100, maxItems = 5)) }
            assertCeiling(failure, "more than 5 issues")
            assertEquals(3, host.calls("search"))
            assertEquals(4L, store.countIssues(connId), "pages 1 and 2 only: the refused page's issues are not stored")
            assertEquals("t2", assertNotNull(issuesCursor(connId)).nextPageToken, "the token that asks the refused page")
        }

    @Test
    fun `a run past the byte ceiling is refused before the page is written, and the next run resumes with a fresh budget`() =
        runBlocking<Unit> {
            val connId = connection()
            val host = Host().apply { handlers["search"] = pagedIssues() }
            // Pages of one issue each, ids 1-9 and tokens t1-t9 of one length: three pages' wire bytes fit exactly, the fourth passes it.
            val chars = 3L * searchPage(listOf(issue(1)), "t1").length
            val failure = assertFailsWith<FetchException> { runIssues(connId, host, RunCeiling(100, 1_000, chars)) }
            assertCeiling(failure, "more than $chars bytes")
            assertEquals(4, host.calls("search"), "the fourth page is fetched, then refused")
            assertEquals(3L, store.countIssues(connId), "the refused page's issue is not stored")
            assertEquals("t3", assertNotNull(issuesCursor(connId)).nextPageToken, "the token that asks the refused page")

            host.handlers["search"] = pagedIssues(lastPage = 5)
            runIssues(connId, host, RunCeiling(100, 1_000, chars))
            assertEquals(5L, store.countIssues(connId), "pages 4 and 5 fit the fresh budget")
            assertNull(assertNotNull(issuesCursor(connId)).nextPageToken)
        }

    @Test
    fun `a cycling page token is refused before its page is stored`() = runBlocking<Unit> {
        val connId = connection()
        val host = Host().apply {
            handlers["search"] = { _, token ->
                when (token) {
                    null -> searchPage(listOf(issue(1)), "t1")
                    "t1" -> searchPage(listOf(issue(2)), "t2")
                    else -> searchPage(listOf(issue(3)), "t1")
                }
            }
        }
        val failure = assertFailsWith<FetchException> { runIssues(connId, host, ISSUES_RUN_CEILING) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertTrue("page token the host already sent" in failure.message.orEmpty(), failure.message)
        assertEquals(3, host.calls("search"))
        assertEquals(2L, store.countIssues(connId))
        assertEquals("t2", assertNotNull(issuesCursor(connId)).nextPageToken)
    }

    @Test
    fun `a host that answers the same token it was asked with is refused, also when the run resumed from it`() = runBlocking<Unit> {
        val connId = connection()
        val resumed = IssuesCursor(
            jql = "project in (FLO)", nextPageToken = "resume", runStartedAt = NOW - 1_000, runProjectKeys = listOf("FLO"),
            runBackfillFrom = BACKFILL_FROM, coveredProjectKeys = listOf("FLO"), coveredBackfillFrom = BACKFILL_FROM,
        )
        cursors.put(connId, "issues", Json.encodeToString(resumed))
        val host = Host().apply { handlers["search"] = { _, _ -> searchPage(listOf(issue(1)), "resume") } }
        val failure = assertFailsWith<FetchException> { runIssues(connId, host, ISSUES_RUN_CEILING) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertEquals(1, host.calls("search"))
        assertEquals(0L, store.countIssues(connId))
        assertEquals("resume", assertNotNull(issuesCursor(connId)).nextPageToken, "unchanged: the next run asks the same page")
    }

    @Test
    fun `an over-long page token is refused before it is persisted`() = runBlocking<Unit> {
        val connId = connection()
        val long = "x".repeat(MAX_PAGE_TOKEN_CHARS + 1)
        val host = Host().apply { handlers["search"] = { _, _ -> searchPage(listOf(issue(1)), long) } }
        val failure = assertFailsWith<FetchException> { runIssues(connId, host, ISSUES_RUN_CEILING) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertTrue("longer than $MAX_PAGE_TOKEN_CHARS" in failure.message.orEmpty(), failure.message)
        assertEquals(0L, store.countIssues(connId))
        assertNull(issuesCursor(connId), "no cursor was written")
    }

    @Test
    fun `the shipped run ceilings leave wide headroom over the production target scope`() {
        // ~10 projects x ~6.4k issues at the default page size of 100 (a project alone is a tenth of this); RECONCILE pages 5,000 ids.
        val scope = 64_000
        assertTrue(ISSUES_RUN_CEILING.maxPages >= 10 * (scope / 100), "pages ${ISSUES_RUN_CEILING.maxPages}")
        assertTrue(ISSUES_RUN_CEILING.maxItems >= 7 * scope, "issues ${ISSUES_RUN_CEILING.maxItems}")
        // ~13 KB an issue on the wire on average, ~43 KB at the real tenant's worst density (the 4.3 MB worst 100-issue page).
        assertTrue(ISSUES_RUN_CEILING.maxBytes >= 4L * scope * 13_000, "average-density bytes ${ISSUES_RUN_CEILING.maxBytes}")
        assertTrue(ISSUES_RUN_CEILING.maxBytes >= scope * 43_000L, "worst-density bytes ${ISSUES_RUN_CEILING.maxBytes}")
        // The id ceiling must trip first for any realistic shape: ~200 bytes an id (`key`, `self`, `expand`), 2x headroom.
        assertTrue(RECONCILE_RUN_CEILING.maxBytes >= 2L * 200 * RECONCILE_RUN_CEILING.maxItems, "bytes ${RECONCILE_RUN_CEILING.maxBytes}")
        assertTrue(RECONCILE_RUN_CEILING.maxPages >= 7 * (scope / 1_000), "even at 1,000 ids a page")
        assertTrue(RECONCILE_RUN_CEILING.maxItems >= 7 * scope, "ids ${RECONCILE_RUN_CEILING.maxItems}")
    }

    // --- RECONCILE ----------------------------------------------------------------------------------------------------

    private suspend fun runReconcile(connId: UInt, host: Host, ceiling: RunCeiling): StreamContext {
        val context = context(connId)
        JiraReconcileStream(readyClient(host), store, listOf("FLO"), backfillFromEpochMillis(BACKFILL_FROM), ceiling).run(context)
        return context
    }

    /** A stored issue the anti-join would ask Jira about, were the sweep ever allowed to finish. */
    private suspend fun seedCandidate(connId: UInt) = store.upsertIssue(
        connId,
        RawIssueInput(
            issueId = 900, issueKey = "FLO-900", projectId = 1L, projectKey = "FLO", issueUpdatedAt = NOW - 60_000,
            payload = Json.parseToJsonElement(issue(900)),
        ),
        now = NOW - 60_000,
    )

    private fun pagedIds(perPage: Int = 1): (Int, String?) -> String = { _, token ->
        val page = token?.removePrefix("t")?.toInt()?.plus(1) ?: 1
        val ids = ((page - 1) * perPage + 1L)..(page * perPage.toLong())
        searchPage(ids.map { idOnly(it) }, "t$page")
    }

    private suspend fun seen(connId: UInt) = store.seenButUnknownIds(connId, 1u).size

    @Test
    fun `an endless sweep fails at the page ceiling and no anti-join runs on the incomplete set`() = runBlocking<Unit> {
        val connId = connection()
        seedCandidate(connId)
        val host = Host().apply { handlers["search"] = pagedIds() }
        val failure = assertFailsWith<FetchException> { runReconcile(connId, host, RunCeiling(PAGES, 1_000)) }
        assertCeiling(failure, "more than $PAGES pages")
        assertEquals(PAGES, host.calls("search"))
        assertEquals(PAGES, seen(connId), "the committed pages' ids are in the scratch table (the next pass drains it)")
        assertEquals(0, host.calls("issue"), "the stored issue was never checked: the sweep did not finish")
        assertEquals(0L, store.countIssuesDeleted(connId))
        assertEquals("t$PAGES", cursorToken(connId))
    }

    @Test
    fun `a sweep past the id ceiling is refused before the page's ids are written`() = runBlocking<Unit> {
        val connId = connection()
        val host = Host().apply { handlers["search"] = pagedIds(perPage = 2) }
        val failure = assertFailsWith<FetchException> { runReconcile(connId, host, RunCeiling(maxPages = 100, maxItems = 3)) }
        assertCeiling(failure, "more than 3 ids")
        assertEquals(2, seen(connId), "page 1 only")
        assertEquals("t1", cursorToken(connId))
    }

    @Test
    fun `a sweep past the byte ceiling is a hard stop before the page's ids are written`() = runBlocking<Unit> {
        val connId = connection()
        seedCandidate(connId)
        val host = Host().apply { handlers["search"] = pagedIds() }
        val chars = 3L * searchPage(listOf(idOnly(1)), "t1").length // the wire bytes of a page of one id
        val failure = assertFailsWith<FetchException> { runReconcile(connId, host, RunCeiling(100, 1_000, chars)) }
        assertCeiling(failure, "more than $chars bytes")
        assertEquals(4, host.calls("search"))
        assertEquals(3, seen(connId), "the committed pages only")
        assertEquals(0, host.calls("issue"), "no anti-join ran on the incomplete set")
        assertEquals("t3", cursorToken(connId))
    }

    @Test
    fun `a page padded with whitespace is charged its full wire size, in RECONCILE and in ISSUES`() = runBlocking<Unit> {
        // A hostile page that is mostly whitespace (or unused members) carries almost no text a parsed-tree count would see: the bytes
        // `SourceHttp` read are what is charged, so 10,000 bytes of padding trip a 5,000-byte ceiling that the ~60-byte document would not.
        val padding = " ".repeat(10_000)
        val sweep = connection()
        val ids = Host().apply { handlers["search"] = { _, _ -> padding + searchPage(listOf(idOnly(1)), null) } }
        val bySweep = assertFailsWith<FetchException> { runReconcile(sweep, ids, RunCeiling(100, 1_000, 5_000)) }
        assertCeiling(bySweep, "more than 5000 bytes")
        assertEquals(0, seen(sweep), "nothing of the refused page is stored")

        val issues = connection()
        val host = Host().apply { handlers["search"] = { _, _ -> padding + searchPage(listOf(issue(1)), null) } }
        val byIssues = assertFailsWith<FetchException> { runIssues(issues, host, RunCeiling(100, 1_000, 5_000)) }
        assertCeiling(byIssues, "more than 5000 bytes")
        assertEquals(0L, store.countIssues(issues))
        // The same page unpadded fits the same ceiling.
        host.handlers["search"] = { _, _ -> searchPage(listOf(issue(1)), null) }
        runIssues(issues, host, RunCeiling(100, 1_000, 5_000))
        assertEquals(1L, store.countIssues(issues))
    }

    @Test
    fun `a cycling sweep token is refused`() = runBlocking<Unit> {
        val connId = connection()
        val host = Host().apply {
            handlers["search"] = { _, token ->
                if (token == null) searchPage(listOf(idOnly(1)), "t1") else searchPage(listOf(idOnly(2)), "t1")
            }
        }
        val failure = assertFailsWith<FetchException> { runReconcile(connId, host, RECONCILE_RUN_CEILING) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertEquals(2, host.calls("search"))
        assertEquals(1, seen(connId), "only the first page committed")
        assertEquals("t1", cursorToken(connId))
    }

    @Test
    fun `a sweep restarted by CURSOR_EXPIRED may see its old tokens again in the new chain`() = runBlocking<Unit> {
        val connId = connection()
        var chain = 1
        val host = Host().apply {
            handlers["search"] = { _, token ->
                when {
                    chain == 1 && token == null -> searchPage(listOf(idOnly(1)), "t1")
                    chain == 1 -> { chain = 2; EXPIRED } // the stored token is rejected: a fresh pass starts
                    token == null -> searchPage(listOf(idOnly(2)), "t1") // the new chain repeats the OLD token
                    else -> searchPage(listOf(idOnly(3)), null)
                }
            }
        }
        runReconcile(connId, host, RECONCILE_RUN_CEILING) // must not be refused as a cycle
        assertEquals(4, host.calls("search"))
        assertNull(cursors.get(connId, "reconcile"), "the pass completed")
    }

    @Test
    fun `a sweep id the host sends without a usable number is refused with constant text`() = runBlocking<Unit> {
        val connId = connection()
        val host = Host().apply { handlers["search"] = { _, _ -> searchPage(listOf("""{"id":"$HOST_TEXT"}"""), null) } }
        val failure = assertFailsWith<FetchException> { runReconcile(connId, host, RECONCILE_RUN_CEILING) }
        assertEquals("INVALID_RESPONSE", failure.code)
        assertNoHostText(failure)
    }

    private fun assertNoHostText(failure: Throwable) {
        assertTrue(HOST_TEXT !in failure.message.orEmpty(), "host text leaked: ${failure.message}")
        assertTrue(failure.cause?.message?.contains(HOST_TEXT) != true)
    }

    // --- Typed reads ----------------------------------------------------------------------------------------------------

    @Test
    fun `an issue the host sends with an unusable id, key, project or date is INVALID_RESPONSE without the host's text`() =
        runBlocking<Unit> {
            val updated = "2026-05-20T10:00:00.000+0000"
            val bad = listOf(
                """{"id":"$HOST_TEXT","key":"FLO-1","fields":{"project":{"id":"1","key":"FLO"},"updated":"$updated"}}""",
                """{"id":"1","key":"","fields":{"project":{"id":"1","key":"FLO"},"updated":"$updated"}}""",
                """{"id":"1","key":"FLO-1","fields":{"project":{"id":"$HOST_TEXT","key":"FLO"},"updated":"$updated"}}""",
                """{"id":"1","key":"FLO-1","fields":{"project":{"id":"1","key":"FLO"},"updated":"$HOST_TEXT"}}""",
                """{"id":"1","key":"FLO-1","fields":{"project":"$HOST_TEXT","updated":"$updated"}}""",
                "\"$HOST_TEXT\"",
            )
            bad.forEach { document ->
                val connId = connection()
                val host = Host().apply { handlers["search"] = { _, _ -> searchPage(listOf(document), null) } }
                val failure = assertFailsWith<FetchException> { runIssues(connId, host, ISSUES_RUN_CEILING) }
                assertEquals("INVALID_RESPONSE", failure.code, document)
                assertNoHostText(failure)
                assertEquals(0L, store.countIssues(connId))
            }
        }

    @Test
    fun `a board id or an entity id the host sends unusable is INVALID_RESPONSE without the host's text`() = runBlocking<Unit> {
        val board = connection()
        val boards = Host().apply {
            handlers["boards"] = { startAt, _ ->
                startAtPage(startAt, listOf("""{"id":"$HOST_TEXT","type":"scrum","location":{"projectKey":"FLO"}}"""), total = 1)
            }
        }
        val byBoard = assertFailsWith<FetchException> { runReference(board, boards, small()) }
        assertEquals("INVALID_RESPONSE", byBoard.code)
        assertNoHostText(byBoard)

        val entity = connection()
        val statuses = Host().apply {
            handlers["statuses"] = { startAt, _ -> startAtPage(startAt, listOf("""{"name":"$HOST_TEXT"}"""), total = 1) }
        }
        val byEntity = assertFailsWith<FetchException> { runReference(entity, statuses, small()) }
        assertEquals("INVALID_RESPONSE", byEntity.code)
        assertNoHostText(byEntity)
    }

    private suspend fun cursorToken(connId: UInt): String? =
        cursors.get(connId, "reconcile")?.let { decodeCursor<ReconcileCursor>(it.cursor).nextPageToken }
}
