package ch.nokillswit

import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraProjectFields
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReferenceStream
import ch.nokillswit.jira.JiraStartAtPage
import ch.nokillswit.jira.ReferenceCursor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val KEYS = SyncedStubFixture.IN_SCOPE_PROJECT_KEYS

private fun fieldRowOf(fieldId: String): JsonObject = buildJsonObject { put("fieldId", fieldId) }

private fun fieldsPage(values: List<JsonObject>, total: Int = 0, isLast: Boolean? = null) =
    JiraStartAtPage(startAt = 0, maxResults = 100, total = total, isLast = isLast, values = JsonArray(values))

/**
 * The OPTIONAL `PROJECT_FIELDS` step over SCRIPTED `projects/fields` answers (a [JiraClient] that delegates to the real
 * stub client for every other call): work-type chunking, `isLast` over `total`, bad rows, missing inputs, a bad page 2,
 * resuming from the step's cursor, and which failures skip (all but `BLOCKED_HOST`) or propagate.
 */
class JiraProjectFieldsScriptedTest {

    private fun context(connId: UInt) =
        StreamContext(connId, 1u, sharedDatabaseForTests(), SyncedStubFixture.cursors(), jobHeartbeat = { _, _ -> true })

    /** The stub client with `projectFields` (and optionally `projectStatuses`) scripted. */
    private suspend fun scripted(
        statuses: ((String) -> JsonArray?)? = null,
        fields: suspend (Long, List<Long>, Int) -> JiraStartAtPage,
    ): JiraClient {
        val real = SyncedStubFixture.buildClient(maxRetries = 0)
        return object : JiraClient by real {
            override suspend fun projectFields(projectId: Long, workTypeIds: List<Long>, startAt: Int, maxResults: Int) =
                fields(projectId, workTypeIds, startAt)

            override suspend fun projectStatuses(projectKey: String): JsonArray =
                statuses?.invoke(projectKey) ?: real.projectStatuses(projectKey)
        }
    }

    private suspend fun run(connId: UInt, client: JiraClient, keys: List<String> = KEYS): Map<String, Long> {
        val context = context(connId)
        JiraReferenceStream(client, SyncedStubFixture.rawStore(), keys).run(context)
        return context.progressSnapshot()
    }

    private suspend fun fieldRows(connId: UInt): Map<String, ResultRow> = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Entities.selectAll().where {
            (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq JiraEntityKind.PROJECT_FIELDS.name)
        }.toList()
    }.associateBy { it[JiraRawStore.Entities.entityId] }

    private fun ResultRow.payloadFieldIds(): List<String> =
        Json.parseToJsonElement(this[JiraRawStore.Entities.payload]).jsonObject.getValue("fieldIds").jsonArray
            .map { it.jsonPrimitive.content }

    @Test
    fun `thirty issue types go out as two chunks of 25 and 5, and the stored set is the union of both answers`() = runBlocking {
        val calls = mutableListOf<List<Long>>()
        val client = scripted { _, types, _ ->
            calls += types
            fieldsPage(listOf(fieldRowOf("summary"), fieldRowOf("type-${types.first()}")), total = 2)
        }
        val ids = (1L..30L).toList()
        val fieldIds = JiraProjectFields.fetchScheme(client, 7L, ids).fieldIds

        assertEquals(listOf(ids.take(25), ids.drop(25)), calls, "two calls, the work types in groups of 25")
        assertEquals(listOf("summary", "type-1", "type-26"), fieldIds, "the union of both answers, sorted and distinct")
    }

    @Test
    fun `a non-null isLast is trusted over total - absent total still pages on, a true isLast stops`() = runBlocking {
        val starts = mutableListOf<Int>()
        val onToTwoPages = scripted { _, _, startAt ->
            starts += startAt
            fieldsPage(listOf(fieldRowOf(if (startAt == 0) "a" else "b")), total = 0, isLast = startAt != 0)
        }
        assertEquals(listOf("a", "b"), JiraProjectFields.fetchScheme(onToTwoPages, 7L, listOf(1L)).fieldIds)
        assertEquals(listOf(0, 1), starts)

        starts.clear()
        val stopsEarly = scripted { _, _, startAt ->
            starts += startAt
            fieldsPage(listOf(fieldRowOf("a")), total = 999, isLast = true)
        }
        assertEquals(listOf("a"), JiraProjectFields.fetchScheme(stopsEarly, 7L, listOf(1L)).fieldIds)
        assertEquals(listOf(0), starts, "isLast=true ends the walk although total says there is more")
    }

    @Test
    fun `a row whose fieldId is null, absent or not a string is INVALID_RESPONSE`() = runBlocking {
        val bad = listOf(
            buildJsonObject { put("fieldId", JsonNull) },
            buildJsonObject { put("projectId", 1) },
            buildJsonObject { put("fieldId", 42) },
            buildJsonObject { put("fieldId", "") },
        )
        bad.forEach { badRow ->
            val client = scripted { _, _, _ -> fieldsPage(listOf(fieldRowOf("summary"), badRow), total = 2, isLast = true) }
            val failure = assertFailsWith<JiraFetchException>("$badRow") { JiraProjectFields.fetchScheme(client, 7L, listOf(1L)) }
            assertEquals("INVALID_RESPONSE", failure.code, "$badRow")
        }
    }

    @Test
    fun `a project missing from PROJECT or without issue types is skipped, counted and keeps its previous row`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-inputs")
        val store = SyncedStubFixture.rawStore()
        val old = """{"projectKey":"OPS","projectId":1,"fieldIds":["old"]}"""
        store.upsertEntity(connId, JiraEntityKind.PROJECT_FIELDS.name, "OPS", old, now = 1_000L)
        val requested = mutableListOf<Long>()
        val client = scripted(
            // ZZZ has an (empty) statuses document but no PROJECT entity; OPS is a real project with no issue types at all.
            statuses = { key -> if (key == "ZZZ" || key == "OPS") JsonArray(emptyList()) else null },
        ) { projectId, _, _ ->
            requested += projectId
            fieldsPage(listOf(fieldRowOf("summary")), total = 1)
        }

        val progress = run(connId, client, listOf("FLO", "ZZZ", "OPS"))

        val rows = fieldRows(connId)
        assertEquals(setOf("FLO", "OPS"), rows.keys, "no entity appears for ZZZ")
        assertEquals(1, requested.size, "only FLO reached the endpoint")
        assertEquals(2L, progress["projectFieldsSkipped"])
        assertEquals(listOf("old"), rows.getValue("OPS").payloadFieldIds(), "OPS keeps its previous scheme")
        assertNull(rows.getValue("OPS")[JiraRawStore.Entities.deletedAt])
        assertTrue(rows.getValue("OPS")[JiraRawStore.Entities.lastSeenAt] > 1_000L, "and was touched, out of the tombstone sweep")
    }

    @Test
    fun `a bad page 2 after a good page 1 skips the project and leaves the previous row unchanged`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-page2")
        val old = """{"projectKey":"FLO","projectId":10000,"fieldIds":["old"]}"""
        SyncedStubFixture.rawStore().upsertEntity(connId, JiraEntityKind.PROJECT_FIELDS.name, "FLO", old, now = 1_000L)
        val client = scripted { _, _, startAt ->
            if (startAt == 0) {
                fieldsPage(listOf(fieldRowOf("summary")), total = 2, isLast = false)
            } else {
                fieldsPage(listOf(buildJsonObject { put("projectId", 1) }), total = 2)
            }
        }

        val progress = run(connId, client, listOf("FLO"))

        val kept = fieldRows(connId).getValue("FLO")
        assertEquals(listOf("old"), kept.payloadFieldIds(), "page 1's field is NOT merged into the previous row")
        assertEquals(Json.parseToJsonElement(old), Json.parseToJsonElement(kept[JiraRawStore.Entities.payload]), "jsonb re-spells the text")
        assertEquals(1L, progress["projectFieldsSkipped"])
    }

    @Test
    fun `a pass resumed from a PROJECT_FIELDS cursor at startAt 2 fetches only the remaining projects`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-resume")
        run(connId, SyncedStubFixture.buildClient(maxRetries = 0))
        val projectIds = fieldRows(connId).mapValues {
            Json.parseToJsonElement(it.value[JiraRawStore.Entities.payload]).jsonObject.getValue("projectId").jsonPrimitive.content.toLong()
        }
        assertEquals(KEYS.toSet(), projectIds.keys)

        // passStartedAt = 1 keeps the resumed pass's end sweep from tombstoning anything (a fresh pass would re-run the earlier steps).
        val cursor = Json.encodeToString(ReferenceCursor(1L, JiraEntityKind.PROJECT_FIELDS, startAt = 2))
        SyncedStubFixture.cursors().put(connId, "reference", cursor)
        val requested = mutableSetOf<Long>()
        val client = scripted { projectId, _, _ ->
            requested += projectId
            fieldsPage(listOf(fieldRowOf("summary")), total = 1, isLast = true)
        }
        run(connId, client)

        assertEquals(setOf(projectIds.getValue(KEYS[2]), projectIds.getValue(KEYS[3])), requested, "projects 0 and 1 are not fetched again")
        assertNull(SyncedStubFixture.cursors().get(connId, "reference"), "the resumed pass completed")
    }

    @Test
    fun `every Jira-side failure but BLOCKED_HOST skips the project - the pass completes`() = runBlocking {
        val codes = listOf("UPSTREAM_UNAVAILABLE", "TIMEOUT", "REDIRECT", "LIMIT_EXCEEDED", "RATE_LIMITED", "CURSOR_EXPIRED", "NOT_FOUND")
        codes.forEach { code ->
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-code")
            val progress = run(connId, scripted { _, _, _ -> throw JiraFetchException(code, null, "/rest/api/3/projects/fields") })

            assertEquals(KEYS.size.toLong(), progress["projectFieldsSkipped"], code)
            assertNull(SyncedStubFixture.cursors().get(connId, "reference"), "$code: the pass completed")
        }
    }

    @Test
    fun `a blocked host, a cancellation and a non-Jira failure still stop the pass`(): Unit = runBlocking {
        val blocked = scripted { _, _, _ -> throw JiraFetchException("BLOCKED_HOST", null, "https://x/rest/api/3/projects/fields") }
        val blockedConn = SyncedStubFixture.createConnection(namePrefix = "jira-fields-blocked")
        val failure = assertFailsWith<JiraFetchException> { run(blockedConn, blocked) }
        assertEquals("BLOCKED_HOST", failure.code)

        val cancelled = scripted { _, _, _ -> throw CancellationException("stop") }
        assertFailsWith<CancellationException> { run(SyncedStubFixture.createConnection(namePrefix = "jira-fields-cancel"), cancelled) }

        val broken = scripted { _, _, _ -> error("database down") }
        assertFailsWith<IllegalStateException> { run(SyncedStubFixture.createConnection(namePrefix = "jira-fields-error"), broken) }
    }
}
