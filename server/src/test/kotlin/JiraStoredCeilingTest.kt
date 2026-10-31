package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.RawUpsertOutcome
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.HttpJiraClient
import ch.nokillswit.jira.IssuesCursor
import ch.nokillswit.jira.JiraChangelogStream
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraIssuesStream
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReconcileStream
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.JiraStoredCeilings
import ch.nokillswit.jira.JiraWorklogStream
import ch.nokillswit.jira.RawChangelogInput
import ch.nokillswit.jira.RawEntityInput
import ch.nokillswit.jira.RawIssueInput
import ch.nokillswit.jira.RawWorklogInput
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.jira.backfillFromEpochMillis
import ch.nokillswit.jira.renderedBytes
import ch.nokillswit.jira.trimIssue
import ch.nokillswit.jira.storeEntities
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BACKFILL = "2026-01-01"
private const val PAGE_SIZE = 7
private const val PAD = 5_000
private val CURSOR_JSON = Json { ignoreUnknownKeys = true }

/** Small ceilings, so a padded item of [PAD] characters is over each and an ordinary stub-shaped item is far below. */
private val SMALL = JiraStoredCeilings(
    maxPayloadBytes = 3_000,
    processPageBytes = 2L * 1024 * 1024,
    maxHistoryBytes = 2_000,
    maxWorklogBytes = 600,
)

private const val LOGGER = "ch.nokillswit.jira"
private const val EMPTY_FEED = """{"values":[],"since":0,"until":5,"lastPage":true}"""

/**
 * The stored-size ceilings (`.claude/docs/ingestion.md` "A stored item over its ceiling is refused, loudly"): the raw writers
 * answer [RawUpsertOutcome.REFUSED] for an item over its ceiling and write NOTHING - an existing row is only touched
 * (`fetched_at`/`last_seen_at`), never changed - while the rest of the page/batch lands, the stream's counter ticks, one WARN names the
 * id and sizes (never the payload) and the cursor advances. One padded item per page via the stub server's overrides, for the issue,
 * entity, history and worklog writers; the shared full stub SYNC must never trip one (no refusal WARN fired, so every digest stands).
 */
class JiraStoredCeilingTest {
    private val store = JiraRawStore(sharedDatabaseForTests(), SMALL)
    private val cursors = SyncedStubFixture.cursors()
    private val connections = mutableListOf<UInt>()

    private suspend fun connection(): UInt {
        SyncedStubFixture.ensureMigrated()
        return SyncedStubFixture.createConnection(
            namePrefix = "jira-stored-ceiling", enabled = false, projectKeys = listOf("FLO"), backfillFrom = BACKFILL,
        ).also { connections += it }
    }

    @AfterTest
    fun dropConnections() = runBlocking {
        connections.forEach { connId ->
            suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Changelogs.deleteWhere { JiraRawStore.Changelogs.connectionId eq connId }
                JiraRawStore.Worklogs.deleteWhere { JiraRawStore.Worklogs.connectionId eq connId }
                JiraRawStore.Entities.deleteWhere { JiraRawStore.Entities.connectionId eq connId }
                JiraRawStore.Issues.deleteWhere { JiraRawStore.Issues.connectionId eq connId }
            }
            SyncedStubFixture.dataSources().delete(connId)
        }
        connections.clear()
    }

    private fun context(connId: UInt) = StreamContext(connId, 1u, sharedDatabaseForTests(), cursors, jobHeartbeat = { _, _ -> true })

    private suspend fun client(): JiraClient {
        val base = JiraStubServer.start()
        val http = SyncedStubFixture.buildJiraHttp(maxRetries = 0)
        return HttpJiraClient(http, base, base, "svc@example.com", "token", JiraAuthScheme.BASIC).also { it.resolveCloudId() }
    }

    private fun json(body: String) = aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)

    private fun override(stub: MappingBuilder): StubMapping = JiraStubServer.addOverride(stub.atPriority(1))

    // --- issues ------------------------------------------------------------------------------------------------------

    private fun issue(id: Long, pad: Int = 0): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("key", "FLO-$id")
        putJsonObject("fields") {
            put("updated", "2026-05-20T10:00:00.000+0000")
            putJsonObject("project") { put("id", "10000"); put("key", "FLO") }
            if (pad > 0) put("padding", "x".repeat(pad))
        }
    }

    private fun page(nextToken: String?, vararg issues: JsonObject): String = buildJsonObject {
        putJsonArray("issues") { issues.forEach { add(it) } }
        nextToken?.let { put("nextPageToken", it) }
    }.toString()

    private fun stubPage(token: String?, body: String) = override(
        get(urlPathMatching(".*/rest/api/3/search/jql"))
            .withQueryParam("maxResults", equalTo(PAGE_SIZE.toString()))
            .withQueryParam("nextPageToken", token?.let { equalTo(it) } ?: absent())
            .willReturn(json(body)),
    )

    private suspend fun issuesStream(): JiraIssuesStream =
        JiraIssuesStream(client(), store, listOf("FLO"), backfillFromEpochMillis(BACKFILL), BACKFILL, 10L, PAGE_SIZE)

    private suspend fun issueRow(connId: UInt, issueId: Long) = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.selectAll()
            .where { (JiraRawStore.Issues.connectionId eq connId) and (JiraRawStore.Issues.issueId eq issueId) }.toList().singleOrNull()
    }

    private suspend fun storedIssueIds(connId: UInt): List<Long> = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Issues.selectAll().where { JiraRawStore.Issues.connectionId eq connId }
            .toList().map { it[JiraRawStore.Issues.issueId] }.sorted()
    }

    private fun seedIssue(connId: UInt, id: Long) = runBlocking {
        store.upsertIssue(connId, RawIssueInput(id, "FLO-$id", 10000L, "FLO", 1_000, issue(id)), now = 1_000)
    }

    @Test
    fun `ISSUES refuses a padded issue, stores the rest of its page and the next, advances the cursor and warns without the payload`() =
        runBlocking {
            val connId = connection()
            val ctx = context(connId)
            val logs = LogCapture(LOGGER)
            val mappings = listOf(stubPage(null, page("t1", issue(1), issue(2, PAD), issue(3))), stubPage("t1", page(null, issue(4))))
            try {
                issuesStream().run(ctx)

                assertEquals(listOf(1L, 3L, 4L), storedIssueIds(connId), "the padded issue is the only one left out")
                assertEquals(1L, ctx.progressSnapshot()["issuesSkipped"])
                assertEquals(3L, ctx.progressSnapshot()["issuesUpserted"], "a refused issue is not counted as upserted")
                assertEquals(2L, ctx.progressSnapshot()["pages"])
                val cursor = CURSOR_JSON.decodeFromString<IssuesCursor>(assertNotNull(cursors.get(connId, "issues")).cursor)
                assertNull(cursor.nextPageToken, "the run completed: the cursor is not pinned on the refused page")
                assertNotNull(cursor.watermarkAt)

                val warn = logs.events.single { it.formattedMessage.startsWith("Stored-size ceiling") }
                assertContains(warn.formattedMessage, "refused issue FLO-2 of data source $connId")
                assertContains(warn.formattedMessage, "over the 3000-byte ceiling")
                assertFalse(logs.events.any { it.formattedMessage.contains("xxxxxxxx") }, "the payload is never logged")
                assertTrue(ctx.ceilingTrip("LIMIT_EXCEEDED", null).madeProgress, "the other issues were stored: progress")
            } finally {
                logs.detach()
                mappings.forEach(JiraStubServer::removeOverride)
            }
        }

    @Test
    fun `an existing issue is only touched when its new version is refused - payload, hash, changed_at and the flag stay, no progress`() =
        runBlocking {
            val connId = connection()
            seedIssue(connId, 2)
            val before = checkNotNull(issueRow(connId, 2))
            val ctx = context(connId)
            val mappings = listOf(stubPage(null, page(null, issue(2, PAD))))
            try {
                issuesStream().run(ctx)

                val after = checkNotNull(issueRow(connId, 2))
                assertEquals(before[JiraRawStore.Issues.payload], after[JiraRawStore.Issues.payload])
                assertEquals(before[JiraRawStore.Issues.sha256], after[JiraRawStore.Issues.sha256])
                assertEquals(before[JiraRawStore.Issues.changedAt], after[JiraRawStore.Issues.changedAt])
                assertEquals(before[JiraRawStore.Issues.needsProcessing], after[JiraRawStore.Issues.needsProcessing])
                assertTrue(after[JiraRawStore.Issues.fetchedAt] > before[JiraRawStore.Issues.fetchedAt], "touched: seen again")
                assertEquals(1L, ctx.progressSnapshot()["issuesSkipped"])
                assertEquals(0L, ctx.progressSnapshot()["issuesUpserted"])
                assertFalse(ctx.ceilingTrip("LIMIT_EXCEEDED", null).madeProgress, "a refusal is no change for the pause")
            } finally {
                mappings.forEach(JiraStubServer::removeOverride)
            }
        }

    @Test
    fun `the single-row and the batch issue writers refuse alike, outcomes in input order, and the ceiling is inclusive`() = runBlocking {
        val connId = connection()
        val inputs = listOf(
            RawIssueInput(6, "FLO-6", 10000L, "FLO", 1_000, issue(6)),
            RawIssueInput(7, "FLO-7", 10000L, "FLO", 1_000, issue(7, PAD)),
            RawIssueInput(8, "FLO-8", 10000L, "FLO", 1_000, issue(8)),
        )
        assertEquals(
            listOf(RawUpsertOutcome.INSERTED, RawUpsertOutcome.REFUSED, RawUpsertOutcome.INSERTED),
            store.upsertIssues(connId, inputs, now = 2_000),
        )
        assertEquals(RawUpsertOutcome.REFUSED, store.upsertIssue(connId, inputs[1], now = 3_000))
        assertNull(issueRow(connId, 7), "nothing of the refused issue was written")
        assertEquals(listOf(6L, 8L), storedIssueIds(connId))

        // The ceiling counts the RENDERED size of the canonical text: a ceiling AT it stores, one byte under refuses.
        val size = canonicalJson(trimIssue(inputs[0].payload)).renderedBytes()
        val exact = JiraRawStore(sharedDatabaseForTests(), SMALL.copy(maxPayloadBytes = size))
        val tight = JiraRawStore(sharedDatabaseForTests(), SMALL.copy(maxPayloadBytes = size - 1))
        assertEquals(RawUpsertOutcome.UNCHANGED, exact.upsertIssue(connId, inputs[0], now = 4_000))
        assertEquals(RawUpsertOutcome.REFUSED, tight.upsertIssue(connId, inputs[0], now = 4_000))
        assertEquals(listOf(RawUpsertOutcome.REFUSED), tight.upsertIssues(connId, listOf(inputs[0]), now = 4_000))
    }

    // --- the rendered size ---------------------------------------------------------------------------------------------

    @OptIn(ExperimentalSerializationApi::class)
    private fun issueWithNumbers(id: Long, literal: String, count: Int): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("key", "FLO-$id")
        putJsonObject("fields") {
            put("updated", "2026-05-20T10:00:00.000+0000")
            putJsonObject("project") { put("id", "10000"); put("key", "FLO") }
            putJsonArray("numbers") { repeat(count) { add(JsonUnquotedLiteral(literal)) } }
        }
    }

    @Test
    fun `an issue of 70 huge-exponent literals is refused though its text is tiny, while ordinary exponents, strings and numbers store`() =
        runBlocking {
            val connId = connection()
            val hostile = RawIssueInput(31, "FLO-31", 10000L, "FLO", 1_000, issueWithNumbers(31, "1e131071", 70))
            // ~700 bytes of canonical text, ~9 MB once PostgreSQL renders the numbers in plain digits.
            assertTrue(canonicalJson(trimIssue(hostile.payload)).length < 1_500)
            assertEquals(RawUpsertOutcome.REFUSED, store.upsertIssue(connId, hostile, now = 2_000))
            assertEquals(listOf(RawUpsertOutcome.REFUSED), store.upsertIssues(connId, listOf(hostile), now = 2_000))
            assertNull(issueRow(connId, 31))

            val ordinary = listOf(
                RawIssueInput(32, "FLO-32", 10000L, "FLO", 1_000, issueWithNumbers(32, "1.0E7", 10)),
                RawIssueInput(33, "FLO-33", 10000L, "FLO", 1_000, issueWithNumbers(33, "42", 10)),
                RawIssueInput(34, "FLO-34", 10000L, "FLO", 1_000, issue(34)),
            )
            val stored = store.upsertIssues(connId, ordinary, now = 3_000)
            assertEquals(List(3) { RawUpsertOutcome.INSERTED }, stored)
            assertEquals(listOf(32L, 33L, 34L), storedIssueIds(connId))
        }

    @Test
    fun `the rendered-size estimate counts exponent digits and separators outside strings only and never under-counts PostgreSQL`() {
        assertEquals("[1.0E7]".length + 7L, "[1.0E7]".renderedBytes())
        assertEquals("[2e-3]".length + 3L, "[2e-3]".renderedBytes())
        assertEquals("[1e131071]".length + 131_071L, "[1e131071]".renderedBytes())
        assertEquals("[1e999999999999]".length + 131_072L, "[1e999999999999]".renderedBytes(), "capped per literal")
        // Exponent-looking TEXT is free: it is a string, and a string renders as it is stored.
        val text = """{"a":"1e131071","b":"say \"2e9\" and 3e9","c":42,"d":-7.5}"""
        assertEquals(text.length + 7L, text.renderedBytes(), "only the 4 colons and 3 commas outside strings add a space each")
        val accents = """{"é":"zażółć, a:b"}"""
        assertEquals(accents.toByteArray().size + 1L, accents.renderedBytes(), "UTF-8 bytes, not characters; ':' ',' in a string are free")
        assertEquals("[0,0,0]".length + 2L, "[0,0,0]".renderedBytes())
        // Against PostgreSQL itself: the estimate is never below the rendered size.
        val samples = listOf(
            "[1e131071]", "[1.0E7,2e-3]", "[1e5,1e50]", "[0,0,0]", "[0,0,0,0,0]", """{"a":0,"b":[1,2]}""",
            """{"a":{"b":{"c":[1,{"d":"x,y:z"}]},"e":null},"f":[[],{}]}""", """[{"a":1e3,"b":"1e9"},{"é":"zażółć"}]""",
        )
        samples.forEach { literal ->
            val rendered = PostgresTestSupport.connect().use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT octet_length('$literal'::jsonb::text)").use { rs -> rs.next(); rs.getLong(1) }
                }
            }
            assertTrue(literal.renderedBytes() >= rendered, "$literal: estimated ${literal.renderedBytes()} < rendered $rendered")
        }
    }

    // --- RECONCILE ---------------------------------------------------------------------------------------------------

    private class SweepClient(private val documents: Map<Long, JsonObject>) : UnsupportedJiraClient() {
        override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int): JiraSearchPage =
            JiraSearchPage(JsonArray(documents.keys.map { id -> buildJsonObject { put("id", id.toString()) } }), null)

        override suspend fun issue(idOrKey: String, fields: String?): JsonObject = documents.getValue(idOrKey.toLong())
    }

    @Test
    fun `RECONCILE refuses a padded index-gap issue, counts it indexGapSkipped and completes`() = runBlocking {
        val connId = connection()
        // The index-gap phase runs only once ISSUES has completed a run covering the scope: issue 1 arrives that way.
        val mappings = listOf(stubPage(null, page(null, issue(1))))
        try {
            issuesStream().run(context(connId))
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
        val ctx = context(connId)
        val client = SweepClient(mapOf(1L to issue(1), 2L to issue(2, PAD)))
        JiraReconcileStream(client, store, listOf("FLO"), backfillFromEpochMillis(BACKFILL)).run(ctx)

        assertEquals(listOf(1L), storedIssueIds(connId), "the padded gap issue was not stored")
        assertEquals(1L, ctx.progressSnapshot()["indexGapSkipped"])
        assertNull(ctx.progressSnapshot()["issuesUpserted"])
        assertNull(cursors.get(connId, "reconcile"), "the pass completed: its cursor is cleared")
    }

    // --- REFERENCE entities ------------------------------------------------------------------------------------------

    private fun status(id: Int, pad: Int = 0): JsonObject = buildJsonObject {
        put("id", id.toString())
        put("name", "Status $id")
        if (pad > 0) put("padding", "x".repeat(pad))
    }

    private suspend fun entityRow(connId: UInt, id: String) = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Entities.selectAll()
            .where { (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.entityId eq id) }.toList().singleOrNull()
    }

    @Test
    fun `REFERENCE refuses a padded entity, touches its stored row without changing it, stores the rest and counts entitiesSkipped`() =
        runBlocking {
            val connId = connection()
            store.upsertEntity(connId, JiraEntityKind.STATUS.name, "2", status(2).toString(), now = 1_000)
            val before = checkNotNull(entityRow(connId, "2"))
            val ctx = context(connId)

            val changed = store.storeEntities(ctx, JiraEntityKind.STATUS, JsonArray(listOf(status(1), status(2, PAD), status(3))))

            assertTrue(changed, "entities 1 and 3 are new")
            assertEquals(2L, ctx.progressSnapshot()["entities"])
            assertEquals(1L, ctx.progressSnapshot()["entitiesSkipped"])
            val after = checkNotNull(entityRow(connId, "2"))
            assertEquals(before[JiraRawStore.Entities.payload], after[JiraRawStore.Entities.payload])
            assertEquals(before[JiraRawStore.Entities.sha256], after[JiraRawStore.Entities.sha256])
            assertEquals(before[JiraRawStore.Entities.changedAt], after[JiraRawStore.Entities.changedAt])
            val seenAgain = after[JiraRawStore.Entities.lastSeenAt] > before[JiraRawStore.Entities.lastSeenAt]
            assertTrue(seenAgain, "touched: seen again, so no sweep")
            assertNotNull(entityRow(connId, "1"))
            assertNotNull(entityRow(connId, "3"))

            val onlyRefused = context(connId)
            val refusedOnly = store.storeEntities(onlyRefused, JiraEntityKind.STATUS, JsonArray(listOf(status(2, PAD))))
            assertFalse(refusedOnly, "a refusal is no change")
            assertEquals(RawUpsertOutcome.REFUSED, store.upsertEntity(connId, "STATUS", "2", status(2, PAD), now = 9_000))
            assertEquals(
                listOf(RawUpsertOutcome.REFUSED, RawUpsertOutcome.INSERTED),
                store.upsertEntities(
                    connId, "STATUS", listOf(RawEntityInput("9", status(9, PAD)), RawEntityInput("10", status(10))), 9_000,
                ),
            )
        }

    // --- CHANGELOGS --------------------------------------------------------------------------------------------------

    private fun history(id: Long, pad: Int = 0) =
        """{"id":"$id","created":1790330188061,"items":[]${if (pad > 0) ""","padding":"${"x".repeat(pad)}"""" else ""}}"""

    @Test
    fun `CHANGELOGS refuses a padded history, stores the others, stamps every issue synced and counts historiesSkipped`() = runBlocking {
        val connId = connection()
        listOf(1L, 2L, 3L).forEach { seedIssue(connId, it) }
        val bulk = post(urlPathMatching(".*/rest/api/3/changelog/bulkfetch"))
            .withRequestBody(matchingJsonPath("$.issueIdsOrKeys", equalToJson("""["1","2","3"]""", false, false)))
            .willReturn(
                json(
                    """{"issueChangeLogs":[""" + listOf(1L to 0, 2L to PAD, 3L to 0).joinToString(",") { (issue, pad) ->
                        """{"issueId":"$issue","changeHistories":[${history(issue * 100, pad)}]}"""
                    } + "]}",
                ),
            )
        val mapping = override(bulk)
        val ctx = context(connId)
        try {
            JiraChangelogStream(client(), store, bulkSize = 3).run(ctx)

            assertEquals(2L, store.countChangelogs(connId))
            assertEquals(2L, ctx.progressSnapshot()["changelogs"])
            assertEquals(1L, ctx.progressSnapshot()["historiesSkipped"])
            listOf(1L, 2L, 3L).forEach {
                assertNotNull(issueRow(connId, it)?.get(JiraRawStore.Issues.changelogSyncedAt), "issue $it is stamped synced")
            }
            assertEquals(
                listOf(RawUpsertOutcome.REFUSED, RawUpsertOutcome.INSERTED),
                store.insertChangelogs(
                    connId,
                    listOf(
                        RawChangelogInput(900, 1, 1_000, null, history(900, PAD)),
                        RawChangelogInput(901, 1, 1_000, null, history(901)),
                    ),
                    now = 5_000,
                ),
            )
            assertEquals(RawUpsertOutcome.REFUSED, store.insertChangelog(connId, 902, 1, 1_000, null, history(902, PAD), now = 5_000))
        } finally {
            JiraStubServer.removeOverride(mapping)
        }
    }

    // --- WORKLOGS ----------------------------------------------------------------------------------------------------

    private fun worklog(id: Long, issueId: Long, pad: Int = 0) =
        """{"id":"$id","issueId":"$issueId","updated":"2026-05-20T10:00:00.000+0000"${padding(pad)}}"""

    private fun padding(pad: Int) = if (pad > 0) ""","padding":"${"x".repeat(pad)}"""" else ""

    private fun emptyFeeds() = listOf(
        override(get(urlPathMatching(".*/rest/api/3/worklog/updated")).willReturn(json(EMPTY_FEED))),
        override(get(urlPathMatching(".*/rest/api/3/worklog/deleted")).willReturn(json(EMPTY_FEED))),
    )

    @Test
    fun `WORKLOGS backfill refuses a padded worklog, stores the other, stamps both issues and counts worklogsSkipped`() = runBlocking {
        val connId = connection()
        listOf(1L, 2L).forEach { seedIssue(connId, it) }
        val perIssue = { issueId: Long, pad: Int ->
            override(
                get(urlPathMatching(".*/rest/api/3/issue/$issueId/worklog"))
                    .willReturn(json("""{"startAt":0,"maxResults":100,"total":1,"worklogs":[${worklog(issueId * 10, issueId, pad)}]}""")),
            )
        }
        val mappings = emptyFeeds() + listOf(perIssue(1, 0), perIssue(2, PAD))
        val ctx = context(connId)
        try {
            JiraWorklogStream(client(), store).run(ctx)

            assertEquals(1L, store.countWorklogs(connId))
            assertEquals(1L, ctx.progressSnapshot()["worklogs"])
            assertEquals(1L, ctx.progressSnapshot()["worklogsSkipped"])
            listOf(1L, 2L).forEach { assertNotNull(issueRow(connId, it)?.get(JiraRawStore.Issues.worklogsSyncedAt), "issue $it stamped") }
        } finally {
            mappings.forEach(JiraStubServer::removeOverride)
        }
    }

    @Test
    fun `WORKLOGS incremental feed refuses a padded worklog, stores the other and the cursor advances`() = runBlocking {
        val connId = connection()
        seedIssue(connId, 7)
        store.markWorklogsSynced(connId, 7L, now = 2_000)
        val feed = override(
            get(urlPathMatching(".*/rest/api/3/worklog/updated")).willReturn(
                json(
                    """{"values":[{"worklogId":11,"updatedTime":1},{"worklogId":12,"updatedTime":1}],"since":0,"until":5,""" +
                        """"lastPage":true}""",
                ),
            ),
        )
        val deleted = override(get(urlPathMatching(".*/rest/api/3/worklog/deleted")).willReturn(json(EMPTY_FEED)))
        val list = override(
            post(urlPathMatching(".*/rest/api/3/worklog/list"))
                .withRequestBody(equalToJson("""{"ids":[11,12]}""", false, false))
                .willReturn(json("[${worklog(11, 7)},${worklog(12, 7, PAD)}]")),
        )
        val ctx = context(connId)
        try {
            JiraWorklogStream(client(), store).run(ctx)

            assertEquals(1L, store.countWorklogs(connId))
            assertEquals(1L, ctx.progressSnapshot()["worklogs"])
            assertEquals(1L, ctx.progressSnapshot()["worklogsSkipped"])
            assertNotNull(cursors.get(connId, "worklogs"), "the cursor advanced past the page")
            assertEquals(
                listOf(RawUpsertOutcome.REFUSED),
                store.upsertWorklogs(connId, listOf(RawWorklogInput(7, 12, 1_000, worklog(12, 7, PAD))), now = 9_000),
            )
        } finally {
            listOf(feed, deleted, list).forEach(JiraStubServer::removeOverride)
        }
    }

    // --- the stub itself ---------------------------------------------------------------------------------------------

    @Test
    fun `the full stub SYNC under the shipped ceilings refused nothing`() = runBlocking {
        val connId = SyncedStubFixture.connectionId()
        assertEquals(0, SyncedStubFixture.storedCeilingRefusals, "no refusal WARN fired during the fixture's SYNC")
        assertTrue(store.countIssues(connId) > 0 && store.countChangelogs(connId) > 0 && store.countWorklogs(connId) > 0)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
