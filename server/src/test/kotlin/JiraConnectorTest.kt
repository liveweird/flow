package ch.nokillswit

import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.jira.JiraClient
import ch.nokillswit.jira.JiraChangelogPage
import ch.nokillswit.jira.JiraConnector
import ch.nokillswit.jira.JiraSearchPage
import ch.nokillswit.jira.JiraStartAtPage
import ch.nokillswit.jira.JiraWorklogIdsPage
import ch.nokillswit.jira.JiraWorklogStartAtPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        override suspend fun projectFields(projectId: Long, workTypeIds: List<Long>, startAt: Int, maxResults: Int) = fail()
        override suspend fun issueTypes() = fail()
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

    @Test
    fun `every probe row carries its API-token scope hint`() = runBlocking {
        // Succeeds everywhere; one issue and one board so the issue- and board-scoped child probes run too.
        val allOk: JiraClient = object : JiraClient by alwaysThrowsRawCast {
            override suspend fun resolveCloudId() = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            override suspend fun myself() = buildJsonObject {}
            override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int) =
                JiraSearchPage(JsonArray(listOf(buildJsonObject { put("id", "10001") })))
            override suspend fun fields() = JsonArray(emptyList())
            override suspend fun statusesSearch(startAt: Int) = JiraStartAtPage()
            override suspend fun projectsSearch(startAt: Int) =
                JiraStartAtPage(values = JsonArray(listOf(buildJsonObject { put("id", "10050"); put("key", "COOK") })))
            override suspend fun projectStatuses(projectKey: String) = JsonArray(listOf(buildJsonObject { put("id", "10001") }))
            override suspend fun projectFields(projectId: Long, workTypeIds: List<Long>, startAt: Int, maxResults: Int): JiraStartAtPage {
                assertEquals(10050L, projectId)
                assertEquals(listOf(10001L), workTypeIds)
                return JiraStartAtPage()
            }
            override suspend fun changelogBulk(issueIds: List<String>, nextPageToken: String?, maxResults: Int) = buildJsonObject {}
            override suspend fun issueChangelogPage(issueId: String, startAt: Int) = JiraChangelogPage()
            override suspend fun issueWorklogPage(issueId: String, startAt: Int) = JiraWorklogStartAtPage()
            override suspend fun worklogUpdated(sinceEpochMillis: Long) = JiraWorklogIdsPage()
            override suspend fun usersSearch(startAt: Int) = JsonArray(emptyList())
            override suspend fun boards(startAt: Int) = JiraStartAtPage(values = JsonArray(listOf(board(7L, "COOK", "scrum"))))
            override suspend fun boardConfiguration(boardId: Long) = buildJsonObject {}
            override suspend fun boardSprints(boardId: Long, startAt: Int) = JiraStartAtPage()
        }
        val connector = JiraConnector(newClient = { _, _, _, _ -> allOk })
        val result = connector.testConnection("https://acme.atlassian.net", "svc@example.com", "tok", listOf("COOK"), JiraAuthScheme.BASIC)
        assertTrue(result.rows.all { it.ok }, "every probe must succeed: ${result.rows}")
        assertEquals(
            mapOf(
                "tenant_info" to null,
                "myself" to "read:jira-user",
                "search" to "read:jql:jira",
                "field" to "read:field:jira",
                "statuses" to "read:workflow:jira",
                "projects" to "read:project:jira",
                "project_statuses:COOK" to "read:status:jira, read:issue-status:jira, read:issue-type:jira",
                "project_fields" to "read:field-configuration:jira",
                "bulkfetch" to "read:issue.changelog:jira",
                "issue_changelog" to "read:issue-details:jira, read:issue.changelog:jira",
                "issue_worklog" to "read:issue:jira, read:issue-worklog:jira",
                "worklog_updated" to "read:issue-worklog:jira",
                "users" to "read:jira-user",
                "boards" to "read:board-scope:jira-software, read:project:jira",
                "board_configuration" to "read:board-scope.admin:jira-software, read:project:jira",
                "board_sprints" to "read:sprint:jira-software",
            ),
            result.rows.associate { it.name to it.scopeHint },
        )
        // bulkfetch and project_fields are the only optional rows: CHANGELOGS falls back from the first, the metrics-config
        // editor from the second; a SYNC needs the board endpoints.
        assertEquals(listOf("project_fields", "bulkfetch"), result.rows.filter { !it.required }.map { it.name })
    }

    private fun board(id: Long, projectKey: String, type: String) = buildJsonObject {
        put("id", id)
        put("type", type)
        put("location", buildJsonObject { put("projectKey", projectKey) })
    }

    /** Succeeds everywhere; [boardList] is what `boards` answers, and the board-child calls are recorded in [calls]. */
    private fun boardsClient(boardList: List<JsonObject>, calls: MutableList<String>): JiraClient =
        object : JiraClient by alwaysThrowsRawCast {
            override suspend fun resolveCloudId() = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            override suspend fun myself() = buildJsonObject {}
            override suspend fun searchJql(jql: String, fields: String?, nextPageToken: String?, maxResults: Int) = JiraSearchPage()
            override suspend fun fields() = JsonArray(emptyList())
            override suspend fun statusesSearch(startAt: Int) = JiraStartAtPage()
            override suspend fun projectsSearch(startAt: Int) = JiraStartAtPage()
            override suspend fun projectStatuses(projectKey: String) = JsonArray(emptyList())
            override suspend fun worklogUpdated(sinceEpochMillis: Long) = JiraWorklogIdsPage()
            override suspend fun usersSearch(startAt: Int) = JsonArray(emptyList())
            override suspend fun boards(startAt: Int) = JiraStartAtPage(values = JsonArray(boardList))
            override suspend fun boardConfiguration(boardId: Long): JsonObject {
                calls += "configuration:$boardId"
                return buildJsonObject {}
            }
            override suspend fun boardSprints(boardId: Long, startAt: Int): JiraStartAtPage {
                calls += "sprints:$boardId"
                return JiraStartAtPage()
            }
        }

    private suspend fun boardProbes(boardList: List<JsonObject>): Pair<List<String>, List<String>> {
        val calls = mutableListOf<String>()
        val connector = JiraConnector(newClient = { _, _, _, _ -> boardsClient(boardList, calls) })
        val result = connector.testConnection("https://acme.atlassian.net", "svc@example.com", "tok", listOf("COOK"), JiraAuthScheme.BASIC)
        assertTrue(result.rows.all { it.ok }, "every probe must succeed: ${result.rows}")
        return calls to result.rows.map { it.name }.filter { it.startsWith("board_") }
    }

    @Test
    fun `the board child probes target the first in-scope board and the first in-scope scrum board`() = runBlocking {
        val (calls, rows) = boardProbes(
            listOf(
                board(1L, "OTHER", "scrum"),
                board(2L, "COOK", "kanban"),
                board(3L, "COOK", "scrum"),
            ),
        )
        assertEquals(listOf("configuration:2", "sprints:3"), calls, "configuration: first in-scope; sprints: first in-scope scrum")
        assertEquals(listOf("board_configuration", "board_sprints"), rows)
    }

    @Test
    fun `a Kanban-only in-scope tenant probes the configuration but skips the sprints probe`() = runBlocking {
        val (calls, rows) = boardProbes(listOf(board(1L, "COOK", "kanban")))
        assertEquals(listOf("configuration:1"), calls)
        assertEquals(listOf("board_configuration"), rows)
    }

    @Test
    fun `no in-scope board skips both board child probes`() = runBlocking {
        val (calls, rows) = boardProbes(listOf(board(1L, "OTHER", "scrum")))
        assertEquals(emptyList(), calls)
        assertEquals(emptyList(), rows)
    }
}
