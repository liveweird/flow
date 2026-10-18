package ch.nokillswit

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.ReportFilters
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * `GET /api/v1/reports/filters` (v0.3.0 M4 commit 10a) — any authenticated user (D12), no query
 * params. Reads against [DerivedStubFixture]'s connection (one FLO-mapped team, one completed
 * DERIVE) so every section of the response has something real to assert over.
 * `AnonymousAccessTest` covers the 401 sweep automatically (no `security: []` in the spec).
 */
class ReportRoutesTest {

    @Test
    fun `a non-admin caller gets 200 with the derived reference data`() = testApplication {
        usePostgresTestcontainer()
        val connectionId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-filters")

        val response = client.get("/api/v1/reports/filters")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ReportFilters>()

        assertTrue(body.teams.isNotEmpty(), "expected at least the derived fixture's own team")
        assertTrue(body.teams.any { it.sprints.isNotEmpty() }, "the FLO-mapped team should carry sprints from dim_sprint")
        // A sprint picker labels a sprint `name · from → to` with the end `endAt ?? completeAt`, so the planned end travels with the start.
        val started = body.teams.flatMap { it.sprints }.filter { it.startAt != null }
        assertTrue(started.isNotEmpty(), "the fixture holds dated sprints")
        assertTrue(started.all { it.endAt != null }, "a sprint with a start carries its planned end")
        assertTrue(body.domains.isNotEmpty(), "expected at least the derived fixture's own domain(s)")
        assertTrue(body.activityTypes.isNotEmpty(), "expected at least the derived fixture's own activity type(s)")
        assertTrue(
            body.connections.any { it.id == connectionId },
            "the derived fixture's own connection should be listed (active, even if disabled)",
        )
        assertTrue(body.derivedAt != null, "a SUCCEEDED derive_runs row exists — derivedAt must not be null")
        val revision = assertNotNull(body.configRevision, "a derived connection stamps the filters with its run's revision")
        assertTrue(revision >= 1L, "a derived connection stamps the filters with its run's revision")
        assertTrue(body.minSampleSize >= 1)
        // The configured zone the SPA renders dates in — must be a real IANA id (ZoneId.of throws otherwise).
        assertTrue(body.timeZone.isNotBlank())
        assertEquals(body.timeZone, java.time.ZoneId.of(body.timeZone).id)
    }

    /**
     * An activity type carried ONLY by a worklog (an epic- or above-epic-logged one, A31) is offered next to the `dim_task` ones; the
     * rest of the list is unchanged, still sorted and distinct. The worklogs live in a fresh disabled connection and are removed after.
     */
    @Test
    fun `an activity type that only a worklog carries is offered next to the task ones`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-filters-worklog-types")
        val before = client.get("/api/v1/reports/filters").body<ReportFilters>().activityTypes
        assertTrue(before.isNotEmpty())
        val connId = SyncedStubFixture.createConnection(namePrefix = "filters-worklog-types", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val worklogOnly = SyncedStubFixture.unique("Program")
        val alsoOnTasks = before.first()
        fun wl(id: Long, activity: String) = FactWorklogRow(
            worklogId = id, issueId = id, authorAccountId = null, authorTeamId = null, startedAt = 1_768_000_000_000L,
            createdAt = null, lateMs = null, md = 1.0, taskDomainKey = null, epicId = id, epicDomainKey = null,
            activityType = activity, workCategory = null, sprintIdAtStarted = null, sprintTeamIdAtStarted = null,
            foreignWork = false, assigneeAccountIdAtStarted = null, assigneeTeamIdAtStarted = null,
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertFactWorklog(connId, listOf(wl(1, worklogOnly), wl(2, alsoOnTasks), wl(3, worklogOnly)), configRevision = 1L)
        }
        try {
            val after = client.get("/api/v1/reports/filters").body<ReportFilters>().activityTypes
            assertTrue(worklogOnly in after, "the worklog-only type is offered")
            assertEquals(after.sorted(), after, "sorted")
            assertEquals(after.distinct(), after, "distinct: a type on both a task and a worklog, and a repeated worklog type, appear once")
            assertEquals((before + worklogOnly).sorted(), after, "everything that was offered still is, plus the one new type")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactWorklog(connId) }
        }
        val gone = client.get("/api/v1/reports/filters").body<ReportFilters>().activityTypes
        assertFalse(worklogOnly in gone, "the type disappears with its worklogs")
        assertEquals(before, gone)
    }

    /**
     * Each connection carries its Jira site (`siteUrl`, the organisation's own host the SPA links issue keys to) for ANY signed-in
     * caller -- and nothing else of the connection's `settings` (the service-account email, project keys, auth scheme) nor its token.
     */
    @Test
    fun `a non-admin caller sees each connection's siteUrl and no other settings key`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-filters-site")
        val dataSources = SyncedStubFixture.dataSources()
        val connId = SyncedStubFixture.createConnection(dataSources, namePrefix = "filters-site", enabled = false)
        try {
            val stored = assertNotNull(dataSources.read(connId))
            val text = client.get("/api/v1/reports/filters").bodyAsText()
            val connections = Json.parseToJsonElement(text).jsonObject.getValue("connections").jsonArray.map { it.jsonObject }
            val mine = assertNotNull(connections.singleOrNull { it.getValue("id").jsonPrimitive.int == connId.toInt() })
            assertEquals(stored.jira.siteUrl, mine.getValue("siteUrl").jsonPrimitive.content)
            assertTrue(stored.jira.siteUrl.startsWith("https://") && !stored.jira.siteUrl.endsWith("/"))
            connections.forEach { assertEquals(setOf("id", "name", "siteUrl"), it.keys, "a connection is id, name and siteUrl only") }
            assertFalse(stored.jira.email in text, "the service-account email never travels")
        } finally {
            dataSources.delete(connId)
        }
    }

    /** A `settings` payload without a usable `siteUrl` must not fail the filters read: that connection answers "". */
    @Test
    fun `a connection with malformed settings answers an empty siteUrl instead of failing the read`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-filters-bad-site")
        val dataSources = SyncedStubFixture.dataSources()
        val connId = SyncedStubFixture.createConnection(dataSources, namePrefix = "filters-bad-site", enabled = false)
        try {
            for (payload in listOf("[]", """{"email":"x"}""", """{"siteUrl":{"nested":true}}""")) {
                suspendTransaction(sharedDatabaseForTests()) {
                    val connections = DataSourceService.Connections
                    connections.update({ connections.id eq connId }) { it[connections.settings] = payload }
                }
                val response = client.get("/api/v1/reports/filters")
                assertEquals(HttpStatusCode.OK, response.status, "settings $payload")
                val mine = response.body<ReportFilters>().connections.single { it.id == connId }
                assertEquals("", mine.siteUrl, "settings $payload")
            }
        } finally {
            dataSources.delete(connId)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
