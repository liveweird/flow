package ch.nokillswit

import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.jira.JiraEntityKind
import ch.nokillswit.jira.JiraProfile
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.jira.JiraReferenceStream
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val KEYS = listOf("FLO", "PLT", "GTM", "OPS")
private const val FIELDS_PATH = ".*/rest/api/3/projects/fields"

/**
 * The OPTIONAL `PROJECT_FIELDS` REFERENCE step (`GET /projects/fields`, experimental) against the sample stub: the stored
 * per-project field-id sets, the skip-never-fail behaviour for ANY Jira-side failure but a blocked host (keeping the
 * previous row, counting `projectFieldsSkipped`), and the data profile's `schemeFieldIds` (null = unknown or partial).
 * The scripted-client cases (chunking, paging, resume, missing inputs) are in `JiraProjectFieldsScriptedTest`.
 */
class JiraProjectFieldsTest {

    private fun context(connId: UInt) =
        StreamContext(connId, 1u, sharedDatabaseForTests(), SyncedStubFixture.cursors(), jobHeartbeat = { _, _ -> true })

    /** Runs one REFERENCE pass and returns the progress counters it accumulated. */
    private suspend fun runReference(connId: UInt, keys: List<String> = KEYS, maxRetries: Int = 0): Map<String, Long> {
        val context = context(connId)
        JiraReferenceStream(SyncedStubFixture.buildClient(maxRetries), SyncedStubFixture.rawStore(), keys).run(context)
        return context.progressSnapshot()
    }

    private suspend fun fieldRows(connId: UInt): List<ResultRow> = suspendTransaction(sharedDatabaseForTests()) {
        JiraRawStore.Entities.selectAll().where {
            (JiraRawStore.Entities.connectionId eq connId) and (JiraRawStore.Entities.kind eq JiraEntityKind.PROJECT_FIELDS.name)
        }.toList()
    }

    private fun ResultRow.fieldIds(): List<String> =
        Json.parseToJsonElement(this[JiraRawStore.Entities.payload]).jsonObject.getValue("fieldIds").jsonArray
            .map { it.jsonPrimitive.content }

    /** Distinct `fieldId`s over a project's stub page files — an independent re-derivation of what the step must store. */
    private fun stubFieldIds(projectKey: String): List<String> {
        val dir = listOf(File("sample-data/jira-stub/__files"), File("../sample-data/jira-stub/__files")).first { it.isDirectory }
        return dir.listFiles { f -> f.name.startsWith("projects-fields-$projectKey-page-") }.orEmpty()
            .flatMap { Json.parseToJsonElement(it.readText()).jsonObject.getValue("values").jsonArray }
            .map { it.jsonObject.getValue("fieldId").jsonPrimitive.content }.distinct().sorted()
    }

    private fun overrideFields(status: Int, body: String? = null): StubMapping = JiraStubServer.addOverride(
        get(urlPathMatching(FIELDS_PATH)).atPriority(1).willReturn(aResponse().withStatus(status).apply { body?.let { withBody(it) } }),
    )

    private suspend fun <T> withFieldsOverride(status: Int, body: String? = null, block: suspend () -> T): T {
        val override = overrideFields(status, body)
        return try {
            block()
        } finally {
            JiraStubServer.removeOverride(override)
        }
    }

    @Test
    fun `the step stores one entity per project key - the distinct field ids of its scheme, across every stub page`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-ok")
        runReference(connId)

        val rows = fieldRows(connId).associateBy { it[JiraRawStore.Entities.entityId] }
        assertEquals(KEYS.toSet(), rows.keys)
        KEYS.forEach { key ->
            val payload = Json.parseToJsonElement(rows.getValue(key)[JiraRawStore.Entities.payload]).jsonObject
            assertEquals(key, payload.getValue("projectKey").jsonPrimitive.content)
            assertEquals(stubFieldIds(key), rows.getValue(key).fieldIds(), "$key: sorted, distinct, every page")
        }
        // The stub's Scrum schemes carry Sprint/Story points, the Kanban project's does not (3 pages of rows each).
        assertTrue("customfield_10020" in rows.getValue("FLO").fieldIds())
        assertTrue("customfield_10020" !in rows.getValue("OPS").fieldIds())
        assertTrue(rows.values.all { it[JiraRawStore.Entities.deletedAt] == null })
    }

    @Test
    fun `a missing scope, a withdrawn endpoint or a bad shape skips the step - the pass completes, no entity is stored`() = runBlocking {
        val badShape = """{"startAt":0,"maxResults":5,"total":1,"isLast":true,"values":[{"projectId":1}]}"""
        listOf(Triple(401, null, "401"), Triple(403, null, "403"), Triple(404, null, "404"), Triple(200, badShape, "bad shape"))
            .forEach { (status, body, label) ->
                val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-skip")
                val progress = withFieldsOverride(status, body) { runReference(connId) }

                assertEquals(KEYS.size.toLong(), progress["projectFieldsSkipped"], "$label: one skip counted per project")
                assertEquals(emptyList(), fieldRows(connId), "$label: nothing stored for a skipped step")
                assertNull(SyncedStubFixture.cursors().get(connId, "reference"), "$label: the REFERENCE pass still completed")
                val entities = suspendTransaction(sharedDatabaseForTests()) {
                    JiraRawStore.Entities.selectAll().where { JiraRawStore.Entities.connectionId eq connId }.toList()
                }
                assertTrue(entities.any { it[JiraRawStore.Entities.kind] == JiraEntityKind.SPRINT.name }, "$label: later steps ran")
                val profile = JiraProfile.compute(connId, SyncedStubFixture.rawStore(), SyncedStubFixture.workItems(), KEYS)
                assertNull(profile.schemeFieldIds, "$label: no entity means the scheme is unknown, not empty")
            }
    }

    @Test
    fun `a skipped step keeps the previous entity live and untouched, and a removed project key still tombstones`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-keep")
        val store = SyncedStubFixture.rawStore()
        val old = """{"projectKey":"FLO","projectId":10000,"fieldIds":["customfield_10016","summary"]}"""
        store.upsertEntity(connId, JiraEntityKind.PROJECT_FIELDS.name, "FLO", old, now = 1_000L)
        store.upsertEntity(connId, JiraEntityKind.PROJECT_FIELDS.name, "GONE", old, now = 1_000L)

        withFieldsOverride(403) { runReference(connId) }

        val rows = fieldRows(connId).associateBy { it[JiraRawStore.Entities.entityId] }
        val kept = rows.getValue("FLO")
        assertNull(kept[JiraRawStore.Entities.deletedAt], "the skipped project's previous row stays live")
        assertEquals(listOf("customfield_10016", "summary"), kept.fieldIds(), "the payload is not rewritten")
        assertTrue(kept[JiraRawStore.Entities.lastSeenAt] > 1_000L, "it was touched by the pass")
        assertNotNull(rows.getValue("GONE")[JiraRawStore.Entities.deletedAt], "a key no longer configured is swept like any entity")
        assertEquals(setOf("FLO", "GONE"), rows.keys)

        // The profile reads only the CURRENT project keys' live entities: FLO's survives, PLT has none, a removed key is ignored.
        val flo = JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), listOf("FLO"))
        assertEquals(listOf("customfield_10016", "summary"), flo.schemeFieldIds)
        assertNull(JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), listOf("PLT")).schemeFieldIds)
    }

    @Test
    fun `the profile scheme is unknown unless EVERY current project has a stored scheme - partial coverage is null`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-partial")
        val store = SyncedStubFixture.rawStore()
        val stored = """{"projectKey":"A","projectId":1,"fieldIds":["summary"]}"""
        store.upsertEntity(connId, JiraEntityKind.PROJECT_FIELDS.name, "A", stored, now = 1_000L)

        assertEquals(listOf("summary"), JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), listOf("A")).schemeFieldIds)
        assertNull(
            JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), listOf("A", "B")).schemeFieldIds,
            "keys [A, B] with only A stored: a partial union would hide B's fields behind the default filter",
        )
    }

    @Test
    fun `the profile unions the current projects' schemes, sorted and distinct`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-profile")
        runReference(connId)
        val store = SyncedStubFixture.rawStore()

        val both = JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), listOf("FLO", "OPS"))
        assertEquals((stubFieldIds("FLO") + stubFieldIds("OPS")).distinct().sorted(), both.schemeFieldIds)
        val kanbanOnly = JiraProfile.compute(connId, store, SyncedStubFixture.workItems(), listOf("OPS"))
        assertEquals(stubFieldIds("OPS"), kanbanOnly.schemeFieldIds)
    }

    @Test
    fun `a server failure on the fields step skips the project like any Jira-side failure - the pass completes`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-500")
        val progress = withFieldsOverride(500) { runReference(connId, maxRetries = 0) }

        assertEquals(KEYS.size.toLong(), progress["projectFieldsSkipped"])
        assertEquals(emptyList(), fieldRows(connId))
        assertNull(SyncedStubFixture.cursors().get(connId, "reference"), "the REFERENCE pass completed")
    }

    @Test
    fun `an empty field list skips the project - nothing stored, previous rows kept`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-fields-empty")
        val empty = """{"startAt":0,"maxResults":100,"total":0,"isLast":true,"values":[]}"""
        val progress = withFieldsOverride(200, empty) { runReference(connId) }

        assertEquals(KEYS.size.toLong(), progress["projectFieldsSkipped"])
        assertEquals(emptyList(), fieldRows(connId))
    }
}
