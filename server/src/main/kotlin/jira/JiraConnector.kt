package ch.nokillswit.jira

import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.ConnectionTestRow
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.JiraAuthScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val PROBE_TIMEOUT_MS = 10_000L
private const val TOTAL_BUDGET_MS = 30_000L

/**
 * `testConnection` (v0.2.0 plan §6): probes each endpoint in SEQUENCE, at most 10s each and 30s
 * total, never throwing — every outcome becomes a [ConnectionTestRow]. Order: tenant_info →
 * myself → search → field → statuses → projects (+ each key) → bulkfetch (optional) + per-issue
 * changelog → per-issue worklog (A1) → worklog/updated → users → boards + configuration
 * (optional) + sprints. The first in-scope issue id found by the search probe seeds the
 * issue-scoped probes; the first board id found by the boards probe seeds its two children.
 *
 * `bulkfetch` is deliberately probed with a SINGLE issue id (not a real 50-id chunk — that shape
 * belongs to the CHANGELOGS stream arriving in plan commit 6): against the sample stub, whose
 * `changelog/bulkfetch` mappings only match its own generator-fixed 50-id chunks, this legitimately
 * answers 404 (`NOT_FOUND`), which is fine — the row is `required = false` precisely because a
 * real tenant's bulkfetch GA/scope status is one of the spike's open unknowns.
 */
typealias JiraClientFactory = (siteUrl: String, email: String, apiToken: String, authScheme: JiraAuthScheme) -> JiraClient

class JiraConnector(
    private val newClient: JiraClientFactory,
    /** [LOW-1] Test seam: an injected clock lets a test simulate the 30s TOTAL budget eroding
     * across probes without a real 30-second sleep. */
    private val now: () -> Long = System::currentTimeMillis,
) : Connector {
    override val kind = DataSourceKind.JIRA_CLOUD

    override suspend fun testConnection(
        siteUrl: String,
        email: String,
        apiToken: String,
        projectKeys: List<String>,
        authScheme: JiraAuthScheme,
    ): ConnectionTestResult {
        val client = newClient(siteUrl, email, apiToken, authScheme)
        val rows = mutableListOf<ConnectionTestRow>()
        var cloudId: String? = null
        var firstIssueId: String? = null
        var firstBoardId: Long? = null
        val deadline = now() + TOTAL_BUDGET_MS

        suspend fun probe(name: String, path: String, required: Boolean, scopeHint: String? = null, block: suspend () -> Unit) {
            // [LOW-1] The remaining TOTAL budget, not just [PROBE_TIMEOUT_MS] — a budget checked
            // only ONCE per probe (before it starts) never shrinks the probe's OWN timeout, so a
            // run of several near-the-cap probes could together run well past the documented 30s
            // (each individually within its own 10s allowance). `withTimeout` below is always the
            // SMALLER of the two.
            val remaining = deadline - now()
            if (remaining <= 0) {
                rows += ConnectionTestRow(name, path, required, ok = false, code = "TIMEOUT", scopeHint = scopeHint)
                return
            }
            try {
                withTimeout(minOf(PROBE_TIMEOUT_MS, remaining)) { block() }
                rows += ConnectionTestRow(name, path, required, ok = true, scopeHint = scopeHint)
            } catch (_: TimeoutCancellationException) {
                rows += ConnectionTestRow(name, path, required, ok = false, code = "TIMEOUT", scopeHint = scopeHint)
            } catch (cause: CancellationException) {
                // [MED-4] A GENUINE coroutine cancellation (the caller's scope was cancelled) is
                // not a probe outcome — it must propagate, never get folded into a row. Distinct
                // from TimeoutCancellationException above, which IS this function's own bounded
                // wait expiring and is deliberately reported as a TIMEOUT row.
                throw cause
            } catch (cause: JiraFetchException) {
                rows += ConnectionTestRow(name, path, required, ok = false, status = cause.status, code = cause.code, scopeHint = scopeHint)
            } catch (_: IllegalStateException) {
                // A prior REQUIRED probe (tenant_info) never resolved the gateway (e.g. it
                // itself timed out or failed) — every probe downstream of it is unreachable,
                // not a distinct failure of ITS OWN endpoint.
                rows += ConnectionTestRow(name, path, required, ok = false, code = "NOT_FOUND", scopeHint = scopeHint)
            } catch (_: RuntimeException) {
                // [MED-4] A raw `.jsonObject`/`.jsonArray`/`.jsonPrimitive` cast (here, or one a
                // future client implementation forgets to route through its own decode helper)
                // throws `IllegalArgumentException` on a shape mismatch — without this catch-all
                // that crashed the WHOLE `testConnection` call (and 500'd the route), instead of
                // reporting the one malformed probe as a row like every other failure mode.
                rows += ConnectionTestRow(name, path, required, ok = false, code = "INVALID_RESPONSE", scopeHint = scopeHint)
            }
        }

        probe("tenant_info", "/_edge/tenant_info", required = true) { cloudId = client.resolveCloudId() }
        probe("myself", "/rest/api/3/myself", required = true, scopeHint = "read:jira-user") { client.myself() }
        probe("search", "/rest/api/3/search/jql", required = true, scopeHint = "read:jql:jira") {
            val page = client.searchJql(JiraJql.scope(projectKeys), maxResults = SEARCH_PROBE_MAX_RESULTS)
            firstIssueId = page.issues.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        }
        probe("field", "/rest/api/3/field", required = true, scopeHint = "read:field:jira") { client.fields() }
        probe("statuses", "/rest/api/3/statuses/search", required = true, scopeHint = "read:status:jira") { client.statusesSearch() }
        probe("projects", "/rest/api/3/project/search", required = true, scopeHint = "read:project:jira") { client.projectsSearch() }
        projectKeys.forEach { key ->
            probe("project_statuses:$key", "/rest/api/3/project/$key/statuses", required = true) { client.projectStatuses(key) }
        }
        firstIssueId?.let { issueId ->
            probe("bulkfetch", "/rest/api/3/changelog/bulkfetch", required = false) {
                client.changelogBulk(listOf(issueId), maxResults = 1)
            }
            probe("issue_changelog", "/rest/api/3/issue/$issueId/changelog", required = true, scopeHint = "read:issue-details:jira") {
                client.issueChangelogPage(issueId)
            }
            probe("issue_worklog", "/rest/api/3/issue/$issueId/worklog", required = true, scopeHint = "read:issue:jira") {
                client.issueWorklogPage(issueId)
            }
        }
        probe("worklog_updated", "/rest/api/3/worklog/updated", required = true) { client.worklogUpdated(0) }
        probe("users", "/rest/api/3/users/search", required = true, scopeHint = "read:jira-user") { client.usersSearch() }
        probe("boards", "/rest/agile/1.0/board", required = false, scopeHint = "read:board-scope:jira-software") {
            val page = client.boards()
            firstBoardId = page.values.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
        }
        firstBoardId?.let { boardId ->
            probe(
                "board_configuration",
                "/rest/agile/1.0/board/$boardId/configuration",
                required = false,
                scopeHint = "read:board-scope.admin:jira-software",
            ) { client.boardConfiguration(boardId) }
            probe("board_sprints", "/rest/agile/1.0/board/$boardId/sprint", required = false, scopeHint = "read:sprint:jira-software") {
                client.boardSprints(boardId)
            }
        }
        return ConnectionTestResult(rows, cloudId)
    }

    private companion object {
        const val SEARCH_PROBE_MAX_RESULTS = 5
    }
}
