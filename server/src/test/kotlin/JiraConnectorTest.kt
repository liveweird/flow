package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `JiraConnector.testConnection`'s probe loop in isolation (a hand-written [JiraClient] fake, no
 * HTTP/WireMock needed) — [MED-4]'s `catch (RuntimeException)` safety net and [LOW-1]'s
 * remaining-budget `withTimeout`. `DataSourceTestConnectionTest` covers the same MED-4 fix over the
 * real Test-connection endpoints/stub.
 */
class JiraConnectorTest {

    /** Every method throws a raw [IllegalArgumentException] — the shape a `.jsonObject`/`.jsonPrimitive` cast fails with. */
    private val alwaysThrowsRawCast: JiraClient = object : JiraClient {
        private fun fail(): Nothing = throw IllegalArgumentException("simulated raw-cast shape mismatch")
        override suspend fun resolveCloudId() = fail()
        override suspend fun myself() = fail()
        override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int) = fail()
        override suspend fun approximateCount(jql: String) = fail()
        override suspend fun issue(idOrKey: String, fields: String?) = fail()
        override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int) = fail()
        override suspend fun issueChangelogPage(issueId: String, startAt: Int) = fail()
        override suspend fun issueWorklogPage(issueId: String, startAt: Int) = fail()
        override suspend fun worklogUpdated(sinceEpochMillis: Long) = fail()
        override suspend fun worklogDeleted(sinceEpochMillis: Long) = fail()
        override suspend fun worklogList(ids: List<Long>) = fail()
        override suspend fun fields() = fail()
        override suspend fun statusesSearch(startAt: Int) = fail()
        override suspend fun statusCategories() = fail()
        override suspend fun projectsSearch(startAt: Int) = fail()
        override suspend fun projectStatuses(projectKey: String) = fail()
        override suspend fun issueTypes() = fail()
        override suspend fun priorities(startAt: Int) = fail()
        override suspend fun resolutions(startAt: Int) = fail()
        override suspend fun issueLinkTypes() = fail()
        override suspend fun usersSearch(startAt: Int) = fail()
        override suspend fun boards(startAt: Int) = fail()
        override suspend fun boardConfiguration(boardId: Long) = fail()
        override suspend fun boardSprints(boardId: Long, startAt: Int) = fail()
    }

    @Test
    fun `a raw-cast IllegalArgumentException anywhere in a probe becomes an INVALID_RESPONSE row, never an uncaught crash`() = runBlocking {
        val connector = JiraConnector(newClient = { _, _, _, _ -> alwaysThrowsRawCast })
        val result = connector.testConnection("https://acme.atlassian.net", "svc@example.com", "tok", listOf("ENG"), JiraAuthScheme.BASIC)
        assertTrue(result.rows.isNotEmpty())
        assertTrue(result.rows.all { !it.ok && it.code == "INVALID_RESPONSE" }, "every row: ${result.rows}")
    }

    @Test
    fun `a genuine coroutine cancellation during a probe is never swallowed as a row`() = runBlocking {
        val cancelling: JiraClient = object : JiraClient by alwaysThrowsRawCast {
            override suspend fun resolveCloudId(): String = throw CancellationException("scope cancelled")
        }
        val connector = JiraConnector(newClient = { _, _, _, _ -> cancelling })
        assertFailsWith<CancellationException> {
            connector.testConnection("https://acme.atlassian.net", "svc@example.com", "tok", listOf("ENG"), JiraAuthScheme.BASIC)
        }
        Unit
    }

    @Test
    fun `a probe honours the REMAINING total budget, not just the fixed per-probe timeout`() = runBlocking {
        var clock = 0L
        // tenant_info "consumes" almost the whole 30s budget instantly (no real sleep — the clock
        // is advanced as a side effect), leaving myself only ~20ms of the total budget even though
        // its own per-probe cap is 10s.
        val client: JiraClient = object : JiraClient by alwaysThrowsRawCast {
            override suspend fun resolveCloudId(): String {
                clock = 29_980L
                return "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            }
            override suspend fun myself(): JsonObject {
                delay(200) // real wait: under the 10s per-probe cap, over the ~20ms REMAINING budget
                return buildJsonObject {}
            }
        }
        val connector = JiraConnector(newClient = { _, _, _, _ -> client }, now = { clock })
        val result = connector.testConnection("https://acme.atlassian.net", "svc@example.com", "tok", listOf("ENG"), JiraAuthScheme.BASIC)
        val myselfRow = result.rows.single { it.name == "myself" }
        assertEquals("TIMEOUT", myselfRow.code, "myself must be bounded by the ~20ms REMAINING budget, not the full 10s per-probe cap")
    }
}
