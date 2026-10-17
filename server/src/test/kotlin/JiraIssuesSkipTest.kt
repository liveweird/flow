package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.IssuesCursor
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraReconcileStream
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.backfillFromEpochMillis
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.r2dbc.spi.R2dbcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BACKFILL = "2026-01-01"
private const val PAGE_SIZE = 7
private val CURSOR_JSON = Json { ignoreUnknownKeys = true }

/** `raw.jira_issues.issue_key` is `VARCHAR(20)`: an issue key this long is rejected as a bad VALUE before any SQL runs. */
private val OVER_LONG_KEY = "FLO-" + "9".repeat(30)

/**
 * The ISSUES stream's "skip, loudly" rule (`.claude/docs/ingestion.md` "A bad issue is skipped, loudly"): a page whose
 * transaction fails with a DATA error is redone issue by issue, the bad issue is skipped (WARN + `issuesSkipped`) and the
 * cursor advances; an issue with a NUL in its text is no longer a bad issue at all (`infra/json/CanonicalJson.kt`). Real
 * HTTP over `JiraStubServer` overrides on a connection of its own, nothing touches the shared synced fixture; the stream
 * is driven alone, so no other stream's output is a prerequisite.
 */
class JiraIssuesSkipTest {
    private val store = SyncedStubFixture.rawStore()
    private val cursors = SyncedStubFixture.cursors()

    private fun issue(
        id: Long,
        key: String = "FLO-$id",
        description: String? = null,
        updated: String = "2026-05-20T10:00:00.000+0000",
    ): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("key", key)
        putJsonObject("fields") {
            put("updated", updated)
            putJsonObject("project") { put("id", "10000"); put("key", "FLO") }
            if (description != null) put("description", description)
        }
    }

    private fun page(nextToken: String?, vararg issues: JsonObject): String = buildJsonObject {
        putJsonArray("issues") { issues.forEach { add(it) } }
        nextToken?.let { put("nextPageToken", it) }
    }.toString()

    private fun stubPage(token: String?, body: String): StubMapping = JiraStubServer.addOverride(
        get(urlPathMatching(".*/rest/api/3/search/jql"))
            .withQueryParam("maxResults", equalTo(PAGE_SIZE.toString()))
            .withQueryParam("nextPageToken", token?.let { equalTo(it) } ?: absent())
            .atPriority(1)
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)),
    )

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(
            namePrefix = "jira-issues-skip", enabled = false, projectKeys = listOf("FLO"), backfillFrom = BACKFILL,
        )
    }

    private suspend fun stream(): JiraIssuesStream {
        val base = JiraStubServer.start()
        val http = SyncedStubFixture.buildJiraHttp(maxRetries = 0)
        val client = HttpJiraClient(http, base, base, "svc@example.com", "token", JiraAuthScheme.BASIC)
        client.resolveCloudId()
        return JiraIssuesStream(client, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL), BACKFILL, 10L, PAGE_SIZE)
    }

    private fun context(connId: UInt) = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true })

    private suspend fun storedIssueIds(connId: UInt): List<Long> = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }
            .toList().map { it[JiraRawStore.Issues.issueId] }.sorted()
    }

    private suspend fun payloadOf(connId: UInt, issueId: Long): String = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.selectAll()
            .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }
            .toList().single()[JiraRawStore.Issues.payload]
    }

    private suspend fun cursorOf(connId: UInt): IssuesCursor =
        CURSOR_JSON.decodeFromString(assertNotNull(cursors.get(connId, "issues")).cursor)

    @Test
    fun `a bad issue is skipped and counted, the rest of its page and the next page land, the cursor advances`() = runBlocking {
        val connId = connection()
        val logs = LogCapture("ch.nokillswit.jira.JiraIssuesStream")
        val ctx = context(connId)
        val mappings = listOf(
            stubPage(null, page("t1", issue(1), issue(2, key = OVER_LONG_KEY, description = "SECRET-PAYLOAD-TEXT"), issue(3))),
            stubPage("t1", page(null, issue(4))),
        )
        try {
            stream().run(ctx)

            assertEquals(listOf(1L, 3L, 4L), storedIssueIds(connId), "the bad issue is the only one left out")
            assertEquals(1L, ctx.progressSnapshot()["issuesSkipped"])
            assertEquals(3L, ctx.progressSnapshot()["issuesUpserted"])
            assertEquals(2L, ctx.progressSnapshot()["pages"])
            val cursor = cursorOf(connId)
            assertNull(cursor.nextPageToken, "the run completed: the cursor is not pinned on the bad page")
            assertNotNull(cursor.watermarkAt)

            val skippedWarn = logs.events.single { it.formattedMessage.startsWith("ISSUES skipped issue") }
            assertContains(skippedWarn.formattedMessage, "(id 2)")
            assertContains(skippedWarn.formattedMessage, OVER_LONG_KEY.take(40))
            assertFalse(logs.events.any { it.formattedMessage.contains("SECRET-PAYLOAD-TEXT") }, "the payload is never logged")
            assertTrue(logs.events.none { it.throwableProxy?.message?.contains("SECRET-PAYLOAD-TEXT") == true })
        } finally {
            logs.detach()
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a skipped issue is stored by a later run once Jira serves it fine`() = runBlocking {
        val connId = connection()
        val firstMappings = listOf(stubPage(null, page(null, issue(1), issue(2, key = OVER_LONG_KEY))))
        try {
            stream().run(context(connId))
            assertEquals(listOf(1L), storedIssueIds(connId))
        } finally {
            firstMappings.forEach(JiraStubServer::removeOverride)
        }
        val secondMappings = listOf(stubPage(null, page(null, issue(1), issue(2))))
        try {
            val ctx = context(connId)
            stream().run(ctx)
            assertEquals(listOf(1L, 2L), storedIssueIds(connId))
            assertNull(ctx.progressSnapshot()["issuesSkipped"], "nothing skipped the second time")
        } finally {
            secondMappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a NUL in an issue description is stored with U+FFFD instead of failing or skipping the issue`() = runBlocking {
        val connId = connection()
        val ctx = context(connId)
        val mappings = listOf(stubPage(null, page(null, issue(1), issue(2, description = "before\u0000after"))))
        try {
            stream().run(ctx)

            assertEquals(listOf(1L, 2L), storedIssueIds(connId))
            assertNull(ctx.progressSnapshot()["issuesSkipped"])
            assertEquals(2L, ctx.progressSnapshot()["issuesUpserted"])
            assertContains(payloadOf(connId, 2), "before�after")
            assertFalse(payloadOf(connId, 2).contains("\\u0000"))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a failure that is not a data error still fails the run and leaves the cursor where it was`() = runBlocking {
        val connId = connection()
        val ctx = context(connId)
        // An unparseable `updated` is a connector/shape problem, not a bad row: it must not be skipped.
        val mappings = listOf(stubPage(null, page(null, issue(1), issue(2, updated = "not-a-date"))))
        try {
            assertFailsWith<RuntimeException> { stream().run(ctx) }

            assertEquals(emptyList(), storedIssueIds(connId))
            assertNull(ctx.progressSnapshot()["issuesSkipped"])
            assertNull(cursors.get(connId, "issues"), "no page was committed, so no cursor was written")
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    // --- what must NOT be skipped, and the RECONCILE counterpart ------------------------------------------------------

    private fun execute(sql: String) {
        DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
            .use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    /** A BEFORE INSERT trigger on `raw.jira_issues` that fails the insert of ONE issue with [sqlState]; returns its remover. */
    private fun failInsertOf(connId: UInt, issueId: Long, sqlState: String): () -> Unit {
        val name = "skip_${sqlState}_${connId}_$issueId"
        execute(
            "CREATE OR REPLACE FUNCTION public.fn_$name() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN IF NEW.connection_id = $connId AND NEW.issue_id = $issueId THEN " +
                "RAISE EXCEPTION 'simulated' USING ERRCODE = '$sqlState'; END IF; RETURN NEW; END \$\$",
        )
        execute("CREATE TRIGGER trg_$name BEFORE INSERT ON raw.jira_issues FOR EACH ROW EXECUTE FUNCTION public.fn_$name()")
        return {
            execute("DROP TRIGGER IF EXISTS trg_$name ON raw.jira_issues")
            execute("DROP FUNCTION IF EXISTS public.fn_$name()")
        }
    }

    private fun Throwable.sqlStates(): List<String?> =
        generateSequence(this) { it.cause }.filterIsInstance<R2dbcException>().map { it.sqlState }.toList()

    @Test
    fun `an outage-class database error is rethrown at once, nothing is skipped and no cursor is written`() = runBlocking {
        val connId = connection()
        val ctx = context(connId)
        val mappings = listOf(stubPage(null, page(null, issue(1), issue(2))))
        val dropTrigger = failInsertOf(connId, 2, "08006")
        try {
            val failure = assertFailsWith<Exception> { stream().run(ctx) }

            assertTrue("08006" in failure.sqlStates(), "the outage's own error surfaces: $failure")
            assertEquals(emptyList(), storedIssueIds(connId), "the page transaction rolled back and was not redone")
            assertNull(ctx.progressSnapshot()["issuesSkipped"])
            assertNull(cursors.get(connId, "issues"))
        } finally {
            dropTrigger()
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `an integrity violation that survives the isolated retry is rethrown, never skipped`() = runBlocking {
        val connId = connection()
        val ctx = context(connId)
        val mappings = listOf(stubPage(null, page(null, issue(1), issue(2))))
        val dropTrigger = failInsertOf(connId, 2, "23514")
        try {
            val failure = assertFailsWith<Exception> { stream().run(ctx) }

            assertTrue("23514" in failure.sqlStates(), "the CHECK violation surfaces: $failure")
            assertEquals(listOf(1L), storedIssueIds(connId), "the healthy issue was stored by its own transaction")
            assertNull(ctx.progressSnapshot()["issuesSkipped"])
            assertNull(cursors.get(connId, "issues"), "the cursor never advanced past the failing page")
        } finally {
            dropTrigger()
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `a page whose every issue is rejected fails the run, a lone bad issue is skipped`() = runBlocking {
        val connId = connection()
        val ctx = context(connId)
        val allBad = listOf(stubPage(null, page(null, issue(1, key = OVER_LONG_KEY), issue(2, key = OVER_LONG_KEY))))
        try {
            val failure = assertFailsWith<IllegalStateException> { stream().run(ctx) }

            assertContains(failure.message.orEmpty(), "every issue was rejected")
            assertEquals(emptyList(), storedIssueIds(connId))
            assertNull(cursors.get(connId, "issues"), "a systemic failure must not advance the cursor")
        } finally {
            allBad.forEach(JiraStubServer::removeOverride)
        }
        val loneBad = listOf(stubPage(null, page(null, issue(3, key = OVER_LONG_KEY))))
        try {
            val loneCtx = context(connId)
            stream().run(loneCtx)

            assertEquals(1L, loneCtx.progressSnapshot()["issuesSkipped"])
            assertNull(cursorOf(connId).nextPageToken)
        } finally {
            loneBad.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `control characters in a skipped issue's key never reach the log`() = runBlocking {
        val connId = connection()
        val logs = LogCapture("ch.nokillswit.jira.JiraIssuesStream")
        val mappings = listOf(stubPage(null, page(null, issue(1), issue(2, key = "FLO-\nFORGED " + "9".repeat(30)))))
        try {
            stream().run(context(connId))

            val warn = logs.events.single { it.formattedMessage.startsWith("ISSUES skipped issue") }
            assertFalse(warn.formattedMessage.contains('\n'), warn.formattedMessage)
            assertContains(warn.formattedMessage, "FLO-?FORGED")
            assertContains(warn.formattedMessage, "client-side length/value check")
        } finally {
            logs.detach()
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    /** `search/jql` answers one page of bare ids (the RECONCILE sweep); `GET /issue/{id}` answers the scripted full document. */
    private class SweepClient(private val documents: Map<Long, JsonObject>) : UnsupportedJiraClient() {
        override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage =
            JiraSearchPage(JsonArray(documents.keys.map { id -> buildJsonObject { put("id", id.toString()) } }), null)

        override suspend fun issue(idOrKey: String, fields: String?): JsonObject = documents.getValue(idOrKey.toLong())
    }

    @Test
    fun `RECONCILE skips the issue ISSUES skipped, counts it as indexGapSkipped and completes`() = runBlocking {
        val connId = connection()
        val mappings = listOf(stubPage(null, page(null, issue(1), issue(2, key = OVER_LONG_KEY))))
        try {
            stream().run(context(connId))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
        assertEquals(listOf(1L), storedIssueIds(connId))

        val ctx = context(connId)
        val logs = LogCapture("ch.nokillswit.jira.JiraReconcileStream")
        try {
            val client = SweepClient(mapOf(1L to issue(1), 2L to issue(2, key = OVER_LONG_KEY)))
            JiraReconcileStream(client, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL)).run(ctx)

            assertEquals(1L, ctx.progressSnapshot()["indexGapSkipped"], "the still-bad issue is counted, not fatal")
            assertNull(ctx.progressSnapshot()["issuesUpserted"])
            assertNull(cursors.get(connId, "reconcile"), "the pass completed: its cursor is cleared")
            assertEquals(listOf(1L), storedIssueIds(connId))
            assertTrue(logs.events.any { it.formattedMessage.startsWith("RECONCILE index-gap fetch skipped issue") })
        } finally {
            logs.detach()
        }
    }
}
