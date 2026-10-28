package ch.nokillswit

import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.DataSourceTestRequest
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.flowusers.FlowUserRole
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /api/v1/data-sources/test` and `.../{id}/test` (v0.2.0 plan §9) against the shared
 * [JiraStubServer] — the exact `sample-data/jira-stub` contract, not a hand-rolled fixture.
 */
class DataSourceTestConnectionTest {

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun jiraRequest(
        projectKeys: List<String> = listOf("FLO", "PLT", "GTM", "OPS"),
        apiToken: String = "token-${UUID.randomUUID()}",
    ) = JiraConnectionRequest(
        siteUrl = TEST_JIRA_SITE_URL,
        email = "svc-${unique("acct")}@example.com",
        apiToken = apiToken,
        projectKeys = projectKeys,
        authScheme = JiraAuthScheme.BASIC,
    )

    @Test
    fun `ad-hoc test connection - every required row is ok against the sample stub`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstest", FlowUserRole.ADMIN)

        val response = admin.postJson("/api/v1/data-sources/test", DataSourceTestRequest(jiraRequest()))
        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.body<ConnectionTestResult>()
        assertEquals(JiraStubServer.CLOUD_ID, result.cloudId)
        val requiredFailures = result.rows.filter { it.required && !it.ok }
        assertTrue(requiredFailures.isEmpty(), "required probes failed: $requiredFailures")
        assertTrue(result.rows.any { it.name == "tenant_info" && it.ok })
        assertTrue(result.rows.any { it.name.startsWith("project_statuses:") })
        val fieldsRow = result.rows.single { it.name == "project_fields" }
        assertTrue(fieldsRow.ok && !fieldsRow.required, "the field-scheme probe is optional and the stub serves it: $fieldsRow")
        assertEquals("read:field-configuration:jira", fieldsRow.scopeHint)
        val searchRow = result.rows.single { it.name == "field_search" }
        assertTrue(searchRow.ok && !searchRow.required, "the field-search probe is optional and the stub serves it: $searchRow")
        assertEquals("read:field:jira, read:field-configuration:jira", searchRow.scopeHint)
    }

    @Test
    fun `a denied field-search probe is a failed optional row - it never fails the required set`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstestsearch", FlowUserRole.ADMIN)
        val forbidden = JiraStubServer.addOverride(
            get(urlPathMatching(".*/rest/api/3/field/search")).atPriority(1).willReturn(aResponse().withStatus(403)),
        )
        val capture = LogCapture("ch.nokillswit.audit")
        try {
            val result = admin.postJson("/api/v1/data-sources/test", DataSourceTestRequest(jiraRequest())).body<ConnectionTestResult>()
            val row = result.rows.single { it.name == "field_search" }
            assertEquals(false, row.ok)
            assertEquals("FORBIDDEN_SCOPE", row.code)
            assertEquals(false, row.required)
            assertTrue(result.rows.single { it.name == "field" }.ok, "GET /field is unaffected")
            val requiredFailures = result.rows.filter { it.required && !it.ok }
            assertTrue(requiredFailures.isEmpty(), "required probes failed: $requiredFailures")
            // The audit event is judged on the REQUIRED rows (what the UI shows as a pass); the failed optional row is still named.
            val event = capture.awaitEvent { e ->
                e.message == "data_source.tested" &&
                    e.keyValuePairs.any { it.key == "failedEndpoints" && it.value.toString().contains("field_search") }
            }
            assertTrue(event != null, "data_source.tested was not audited")
            assertTrue(event.hasKeyValue("ok", true), "an optional row failing must not clear ok: ${event.keyValuePairs}")
        } finally {
            capture.detach()
            JiraStubServer.removeOverride(forbidden)
        }
    }

    @Test
    fun `a scope-denied projects-fields probe is a failed optional row - it never fails the required set`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstestfields", FlowUserRole.ADMIN)
        val forbidden = JiraStubServer.addOverride(
            get(urlPathMatching(".*/rest/api/3/projects/fields")).atPriority(1).willReturn(aResponse().withStatus(403)),
        )
        try {
            val result = admin.postJson("/api/v1/data-sources/test", DataSourceTestRequest(jiraRequest())).body<ConnectionTestResult>()
            val row = result.rows.single { it.name == "project_fields" }
            assertEquals(false, row.ok)
            assertEquals("FORBIDDEN_SCOPE", row.code)
            assertEquals(false, row.required)
            val requiredFailures = result.rows.filter { it.required && !it.ok }
            assertTrue(requiredFailures.isEmpty(), "required probes failed: $requiredFailures")
        } finally {
            JiraStubServer.removeOverride(forbidden)
        }
    }

    @Test
    fun `non-admin gets 403 before the body decodes, on both endpoints`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val user = seededClient("dstestuser")

        val malformed = user.post("/api/v1/data-sources/test") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, malformed.status, "the guard runs before the body decodes")
        assertEquals(HttpStatusCode.Forbidden, user.post("/api/v1/data-sources/999999/test") {}.status)
    }

    @Test
    fun `stored connection variant is 404 for an unknown id`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstestmissing", FlowUserRole.ADMIN)
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/data-sources/999999/test") {}.status)
    }

    @Test
    fun `stored connection variant uses the stored token and persists the resolved cloudId`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstestpersist", FlowUserRole.ADMIN)
        val created = admin.postJson(
            "/api/v1/data-sources",
            DataSourceRequest(unique("Jira"), true, 60, null, 3, jiraRequest(uniqueProjectKeys(1))),
        ).body<DataSourceResponse>()
        assertNull(created.jira.cloudId)

        val response = admin.post("/api/v1/data-sources/${created.id}/test") {}
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JiraStubServer.CLOUD_ID, response.body<ConnectionTestResult>().cloudId)

        val reread = admin.get("/api/v1/data-sources/${created.id}").body<DataSourceResponse>()
        assertEquals(JiraStubServer.CLOUD_ID, reread.jira.cloudId)
    }

    @Test
    fun `a scope-denied endpoint reports FORBIDDEN_SCOPE, a broken one reports AUTHENTICATION_FAILED`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstestscope", FlowUserRole.ADMIN)

        val forbiddenBoards = JiraStubServer.addOverride(
            get(urlPathMatching(".*/rest/agile/1\\.0/board")).atPriority(1).willReturn(aResponse().withStatus(403)),
        )
        val brokenMyself = JiraStubServer.addOverride(
            get(urlPathMatching(".*/rest/api/3/myself")).atPriority(1).willReturn(aResponse().withStatus(401)),
        )
        try {
            val response = admin.postJson("/api/v1/data-sources/test", DataSourceTestRequest(jiraRequest()))
            assertEquals(HttpStatusCode.OK, response.status, "a probe failure is a row, never a failing HTTP status")
            val result = response.body<ConnectionTestResult>()

            val boardsRow = result.rows.single { it.name == "boards" }
            assertEquals(false, boardsRow.ok)
            assertEquals("FORBIDDEN_SCOPE", boardsRow.code)
            assertEquals(403, boardsRow.status)
            assertEquals(true, boardsRow.required)

            val myselfRow = result.rows.single { it.name == "myself" }
            assertEquals(false, myselfRow.ok)
            assertEquals("AUTHENTICATION_FAILED", myselfRow.code)
            assertEquals(401, myselfRow.status)
        } finally {
            JiraStubServer.removeOverride(forbiddenBoards)
            JiraStubServer.removeOverride(brokenMyself)
        }
    }

    @Test
    fun `the per-IP DATA_SOURCE_TEST bucket throttles a chatty host after 10 calls per minute`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstestrate", FlowUserRole.ADMIN)

        val statuses = (1..11).map {
            admin.postJson("/api/v1/data-sources/test", DataSourceTestRequest(jiraRequest())).status
        }
        assertTrue(statuses.take(10).all { it == HttpStatusCode.OK }, "expected 200s first: $statuses")
        assertEquals(HttpStatusCode.TooManyRequests, statuses.last())
    }

    // [MED-4] A raw `.jsonObject`/`.jsonPrimitive` cast on a shape-mismatched response used to
    // throw an uncaught `IllegalArgumentException` — Test connection 500'd instead of reporting
    // the probe as a row like every other failure mode.

    @Test
    fun `a malformed (non-object) tenant_info response is INVALID_RESPONSE, never a 500`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstestbadtenant", FlowUserRole.ADMIN)
        val override = JiraStubServer.addOverride(
            get(urlPathMatching(".*/_edge/tenant_info")).atPriority(1).willReturn(aResponse().withStatus(200).withBody("[]")),
        )
        try {
            val response = admin.postJson("/api/v1/data-sources/test", DataSourceTestRequest(jiraRequest()))
            assertEquals(HttpStatusCode.OK, response.status, "a malformed probe response is a row, never a 500")
            val result = response.body<ConnectionTestResult>()
            val tenantRow = result.rows.single { it.name == "tenant_info" }
            assertEquals(false, tenantRow.ok)
            assertEquals("INVALID_RESPONSE", tenantRow.code)
            assertNull(result.cloudId)
        } finally {
            JiraStubServer.removeOverride(override)
        }
    }

    @Test
    fun `a malformed (non-object) myself response is INVALID_RESPONSE, never a 500`() = testApplication {
        configureApp("jira.stubBaseUrl" to JiraStubServer.start())
        startApplication()
        val admin = seededClient("dstestbadmyself", FlowUserRole.ADMIN)
        val override = JiraStubServer.addOverride(
            get(urlPathMatching(".*/rest/api/3/myself")).atPriority(1).willReturn(aResponse().withStatus(200).withBody("[]")),
        )
        try {
            val response = admin.postJson("/api/v1/data-sources/test", DataSourceTestRequest(jiraRequest()))
            assertEquals(HttpStatusCode.OK, response.status, "a malformed probe response is a row, never a 500")
            val result = response.body<ConnectionTestResult>()
            val myselfRow = result.rows.single { it.name == "myself" }
            assertEquals(false, myselfRow.ok)
            assertEquals("INVALID_RESPONSE", myselfRow.code)
            assertEquals(JiraStubServer.CLOUD_ID, result.cloudId, "tenant_info still succeeded normally")
        } finally {
            JiraStubServer.removeOverride(override)
        }
    }
}
