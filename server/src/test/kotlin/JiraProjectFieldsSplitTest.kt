package ch.nokillswit

import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraProfile
import ch.nokillswit.jira.JiraProjectFields
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReferenceStream
import ch.nokillswit.jira.JiraStartAtPage
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val KEYS = SyncedStubFixture.IN_SCOPE_PROJECT_KEYS

/** The stub's issue types (`sample-data/jira/generate.mjs` ISSUE_TYPE) — an INDEPENDENT copy: Epic is level 1, the rest level 0 / -1. */
private const val STUB_EPIC_TYPE_ID = 10000L
private const val EPIC_START_FIELD = "customfield_10015"
private const val SPRINT_FIELD = "customfield_10020"
private const val ON_HOLD_STATUS_ID = "10005"

private fun row(fieldId: String, workTypeId: Any?): JsonObject = buildJsonObject {
    put("fieldId", fieldId)
    when (workTypeId) {
        is Long -> put("workTypeId", workTypeId)
        is String -> put("workTypeId", workTypeId)
        else -> Unit
    }
}

private fun page(rows: List<JsonObject>, isLast: Boolean) =
    JiraStartAtPage(startAt = 0, maxResults = 100, total = 0, isLast = isLast, values = JsonArray(rows))

/**
 * The epic/task split of the `PROJECT_FIELDS` payload and of the data profile's workflow statuses
 * (`.claude/docs/jira-integration.md` "Project field schemes"): computed from (field, work type) rows across chunks and pages,
 * from the stored `ISSUE_TYPE` hierarchy levels, with the old-payload and unknown-hierarchy fallbacks.
 */
class JiraProjectFieldsSplitTest {

    private fun context(connId: UInt) =
        StreamContext(connId, 1u, sharedDatabaseForTests(), SyncedStubFixture.cursors(), jobHeartbeat = { _, _ -> true })

    private suspend fun runReference(connId: UInt) {
        JiraReferenceStream(SyncedStubFixture.buildClient(maxRetries = 0), SyncedStubFixture.rawStore(), KEYS).run(context(connId))
    }

    private suspend fun storedPayload(connId: UInt, kind: JiraEntityKind, key: String): JsonObject {
        val rows = suspendTransaction(sharedDatabaseForTests()) {
            JiraRawStore.Entities.selectAll().where {
                (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq kind.name) and
                    (JiraRawStore.Entities.entityId eq key)
            }.toList()
        }
        return Json.parseToJsonElement(rows.single()[JiraRawStore.Entities.payload]).jsonObject
    }

    private fun ids(payload: JsonObject, name: String): List<String> = payload.getValue(name).jsonArray.map { it.jsonPrimitive.content }

    /** (workTypeId, fieldId) pairs of a project's stub page files — an independent re-derivation, never the step's own reader. */
    private fun stubRows(projectKey: String): List<Pair<Long, String>> {
        val dir = listOf(File("sample-data/jira-stub/__files"), File("../sample-data/jira-stub/__files")).first { it.isDirectory }
        return dir.listFiles { f -> f.name.startsWith("projects-fields-$projectKey-page-") }.orEmpty()
            .flatMap { Json.parseToJsonElement(it.readText()).jsonObject.getValue("values").jsonArray }
            .map { it.jsonObject }
            .map { it.getValue("workTypeId").jsonPrimitive.content.toLong() to it.getValue("fieldId").jsonPrimitive.content }
    }

    private fun scripted(real: JiraClient, fields: suspend (List<Long>, Int) -> JiraStartAtPage): JiraClient = object : JiraClient by real {
        override suspend fun projectFields(projectId: Long, workTypeIds: List<Long>, startAt: Int, maxResults: Int) =
            fields(workTypeIds, startAt)
    }

    @Test
    fun `the split is built from rows across work-type chunks and pages, by the hierarchy level of each row's work type`() = runBlocking {
        val client = scripted(SyncedStubFixture.buildClient(maxRetries = 0)) { types, startAt ->
            val rows = types.flatMap { type ->
                listOf(row("summary", type), row("only-$type", type)) + if (startAt == 0) emptyList() else listOf(row("late-$type", type))
            }
            page(rows, isLast = startAt != 0)
        }
        val scheme = JiraProjectFields.fetchScheme(client, 7L, (1L..30L).toList())
        assertNotNull(scheme.byWorkType)
        assertEquals(30, scheme.byWorkType?.size, "both chunks and both pages of each reached the per-type sets")

        // 1 = epic, 2 = task, 3 = sub-task (-1), 4 = initiative (2: neither), 5 = no hierarchy known (counts as a task type).
        val hierarchy = mapOf("1" to 1, "2" to 0, "3" to -1, "4" to 2)
        val payload = Json.parseToJsonElement(JiraProjectFields.payload("PRJ", 7L, scheme, hierarchy)).jsonObject
        val epic = ids(payload, "epicFieldIds")
        val task = ids(payload, "taskFieldIds")
        assertEquals(listOf("late-1", "only-1", "summary"), epic)
        assertTrue("late-3" in task && "only-3" in task && "only-2" in task, "level 0 and the sub-task level -1 count with tasks")
        assertTrue("only-5" in task && "late-30" in task, "a work type missing from the hierarchy counts as a task type")
        assertTrue(task.none { it.endsWith("-4") } && epic.none { it.endsWith("-4") }, "a level above the epic's counts for neither")
        assertTrue("only-4" in ids(payload, "fieldIds"), "but it is still in the union")
        assertEquals(ids(payload, "fieldIds").sorted(), ids(payload, "fieldIds"), "the union stays sorted")
    }

    @Test
    fun `an unknown hierarchy or a row without a work type stores the union only, and reading it falls back to the union for both`() {
        val withType = JiraProjectFields.FetchedScheme(listOf("a", "b"), mapOf(1L to setOf("a"), 2L to setOf("b")))
        val noHierarchy = JiraProjectFields.payload("PRJ", 7L, withType, emptyMap())
        val noWorkType = JiraProjectFields.payload("PRJ", 7L, JiraProjectFields.FetchedScheme(listOf("a", "b"), null), mapOf("1" to 1))

        listOf(noHierarchy, noWorkType).forEach { payload ->
            val obj = Json.parseToJsonElement(payload).jsonObject
            assertEquals(setOf("projectKey", "projectId", "fieldIds"), obj.keys, "no split keys at all")
            val parsed = JiraProjectFields.parse(payload)
            assertEquals(listOf("a", "b"), parsed.fieldIds)
            assertEquals(parsed.fieldIds, parsed.epicFieldIds)
            assertEquals(parsed.fieldIds, parsed.taskFieldIds)
        }
        val known = JiraProjectFields.parse(JiraProjectFields.payload("PRJ", 7L, withType, mapOf("1" to 1, "2" to 0)))
        assertEquals(listOf("a") to listOf("b"), known.epicFieldIds to known.taskFieldIds)
        // An old payload (stored before the split existed) and a half-split one both read as "unknown".
        val old = JiraProjectFields.parse("""{"projectKey":"X","projectId":1,"fieldIds":["z","y"]}""")
        assertEquals(listOf("z", "y"), old.epicFieldIds)
        assertEquals(listOf("z", "y"), old.taskFieldIds)
        val half = JiraProjectFields.parse("""{"projectKey":"X","projectId":1,"fieldIds":["z"],"epicFieldIds":["z"]}""")
        assertEquals(listOf("z"), half.taskFieldIds)
    }

    @Test
    fun `a stub pass stores the split per project and it matches the stub rows by an independent re-derivation`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-split")
        runReference(connId)

        KEYS.forEach { key ->
            val payload = storedPayload(connId, JiraEntityKind.PROJECT_FIELDS, key)
            val rows = stubRows(key)
            val epicExpected = rows.filter { it.first == STUB_EPIC_TYPE_ID }.map { it.second }.distinct().sorted()
            val taskExpected = rows.filter { it.first != STUB_EPIC_TYPE_ID }.map { it.second }.distinct().sorted()
            assertEquals(epicExpected, ids(payload, "epicFieldIds"), "$key: epic scheme")
            assertEquals(taskExpected, ids(payload, "taskFieldIds"), "$key: task scheme")
            assertEquals(rows.map { it.second }.distinct().sorted(), ids(payload, "fieldIds"), "$key: the union is unchanged")
            assertTrue(EPIC_START_FIELD in epicExpected && EPIC_START_FIELD !in taskExpected, "$key: epic start is epic-only in the stub")
        }
        assertTrue(SPRINT_FIELD in ids(storedPayload(connId, JiraEntityKind.PROJECT_FIELDS, "FLO"), "taskFieldIds"))
        assertTrue(SPRINT_FIELD !in ids(storedPayload(connId, JiraEntityKind.PROJECT_FIELDS, "FLO"), "epicFieldIds"))
    }

    @Test
    fun `the profile splits the field schemes and the workflow statuses by level, null or empty when unknown`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-split-profile")
        runReference(connId)
        val store = SyncedStubFixture.rawStore()

        val profile = JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), KEYS)
        val epicFields = assertNotNull(profile.schemeEpicFieldIds)
        val taskFields = assertNotNull(profile.schemeTaskFieldIds)
        assertEquals(profile.schemeFieldIds, (epicFields + taskFields).distinct().sorted(), "the two scopes together are the union")
        assertTrue(EPIC_START_FIELD in epicFields && EPIC_START_FIELD !in taskFields)
        assertTrue(SPRINT_FIELD in taskFields && SPRINT_FIELD !in epicFields)

        // The stub's Epic workflow carries an epic-only On Hold; every other type's workflow does not.
        assertTrue(ON_HOLD_STATUS_ID in profile.epicWorkflowStatusIds)
        assertTrue(ON_HOLD_STATUS_ID !in profile.taskWorkflowStatusIds)
        assertTrue(ON_HOLD_STATUS_ID in profile.workflowStatusIds, "the union keeps meaning every workflow")
        assertEquals(profile.workflowStatusIds, (profile.epicWorkflowStatusIds + profile.taskWorkflowStatusIds).distinct().sorted())
        assertTrue(profile.taskWorkflowStatusIds.isNotEmpty() && profile.epicWorkflowStatusIds.size > 1)

        // Partial coverage is unknown for both scopes (the same rule as the union).
        val partial = JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), KEYS + "NOPE")
        assertNull(partial.schemeEpicFieldIds)
        assertNull(partial.schemeTaskFieldIds)
    }

    @Test
    fun `with no ISSUE_TYPE entity the workflow split is empty and an old PROJECT_FIELDS payload counts for both scopes`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-split-old")
        val store = SyncedStubFixture.rawStore()
        val oldPayload = """{"projectKey":"A","projectId":1,"fieldIds":["x","y"]}"""
        store.upsertEntity(connId, JiraEntityKind.PROJECT_FIELDS.name, "A", oldPayload, now = 1_000L)
        val newer = """{"projectKey":"B","projectId":2,"fieldIds":["y","z"],"epicFieldIds":["z"],"taskFieldIds":["y"]}"""
        store.upsertEntity(connId, JiraEntityKind.PROJECT_FIELDS.name, "B", newer, now = 1_000L)
        store.upsertEntity(
            connId, JiraEntityKind.PROJECT_STATUSES.name, "A",
            """[{"id":"10000","name":"Epic","statuses":[{"id":"7","name":"Seven"}]}]""", now = 1_000L,
        )

        val old = JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), listOf("A"))
        assertEquals(listOf("x", "y"), old.schemeEpicFieldIds)
        assertEquals(listOf("x", "y"), old.schemeTaskFieldIds)
        val mixed = JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), listOf("A", "B"))
        assertEquals(listOf("x", "y", "z"), mixed.schemeEpicFieldIds, "A contributes its whole scheme, B only its epic fields")
        assertEquals(listOf("x", "y"), mixed.schemeTaskFieldIds)
        assertEquals(listOf("7"), mixed.workflowStatusIds)
        assertEquals(emptyList(), mixed.epicWorkflowStatusIds, "no ISSUE_TYPE hierarchy: nothing is claimed for either level")
        assertEquals(emptyList(), mixed.taskWorkflowStatusIds)
    }
}
