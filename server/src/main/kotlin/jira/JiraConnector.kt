package ch.nokillswit.jira

import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.ConnectionTestRow
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.PurgeStep
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncCursorsService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.purgeAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

private const val PROBE_TIMEOUT_MS = 10_000L
private const val TOTAL_BUDGET_MS = 30_000L

/**
 * `testConnection` (v0.2.0 plan §6): probes each endpoint in SEQUENCE, at most 10s each and 30s
 * total, never throwing — every outcome becomes a [ConnectionTestRow]. Order: tenant_info →
 * myself → search → field → statuses → projects (+ each key) → bulkfetch (optional) + per-issue
 * changelog → per-issue worklog (A1) → worklog/updated → users → boards + configuration
 * + sprints (required: a SYNC's REFERENCE stream fetches all three). The OPTIONAL `project_fields` row follows the per-key
 * `project_statuses` rows, seeded with the first configured key's project id and first issue-type id (skipped when either is
 * unavailable). The first in-scope issue id found by the search probe seeds the
 * issue-scoped probes. The boards probe seeds its two children by the REFERENCE stream's own rules (`isInScopeBoard`,
 * `isScrumBoard`): `board_configuration` uses the first board of the first page whose project is in `projectKeys`,
 * `board_sprints` the first such SCRUM board (a Kanban board's `/sprint` answers 400); a child with no such board is skipped.
 *
 * `bulkfetch` is deliberately probed with a SINGLE issue id (not a real 50-id chunk — that shape
 * belongs to the CHANGELOGS stream arriving in plan commit 6): against the sample stub, whose
 * `changelog/bulkfetch` mappings only match its own generator-fixed 50-id chunks, this legitimately
 * answers 404 (`NOT_FOUND`), which is fine — the row is `required = false` precisely because a
 * real tenant's bulkfetch GA/scope status is one of the spike's open unknowns.
 */
typealias JiraClientFactory = (siteUrl: String, email: String, apiToken: String, authScheme: JiraAuthScheme) -> JiraClient

/**
 * What [JiraConnector.run] needs beyond [JiraClientFactory] — bundled so `JiraConnectorTest`'s
 * `testConnection`-only fixtures (which never call `run`) keep constructing a bare `JiraConnector`
 * without threading these through. `jira/Jira.kt`'s `configureJira` is the one production wiring
 * site.
 */
class JiraSyncDependencies(
    val dataSources: DataSourceService,
    val rawStore: JiraRawStore,
    val cursors: SyncCursorsService,
    val database: R2dbcDatabase,
    /** The normalized layer's write target (v0.2.0 plan §0 A3/§8, `norm/WorkItemStore.kt`) — PROCESS's and PURGE's one consumer. */
    val workItems: WorkItemStore,
    /** `jira.incrementalOverlapMinutes` (default 10, plan §5/§7) — the ISSUES stream's re-fetch window past the last watermark. */
    val incrementalOverlapMinutes: Long,
    val issuesPageSize: Int,
    /**
     * `jira.changelogBulkSize` (default 50, plan §5/§7) — the CHANGELOGS stream's `changelog/bulkfetch`
     * chunk size; MUST match the stub's chunking (`sample-data/README.md`).
     */
    val changelogBulkSize: Int = DEFAULT_CHANGELOG_BULK_SIZE,
)

private const val DEFAULT_CHANGELOG_BULK_SIZE = 50

class JiraConnector(
    private val newClient: JiraClientFactory,
    /** [LOW-1] Test seam: an injected clock lets a test simulate the 30s TOTAL budget eroding
     * across probes without a real 30-second sleep. */
    private val now: () -> Long = System::currentTimeMillis,
    private val sync: JiraSyncDependencies? = null,
) : Connector {
    override val kind = DataSourceKind.JIRA_CLOUD

    /**
     * PURGE's connector-owned cleanup step (v0.2.0 plan §0 A2): drains this connection's
     * `raw.jira_*` rows AND its `norm.*` rows (plan §8) in batches.
     */
    override val purgeSteps: List<PurgeStep>
        get() = sync?.let { deps ->
            listOf(
                PurgeStep { connectionId -> deps.rawStore.purgeAll(connectionId) },
                PurgeStep { connectionId -> deps.workItems.purgeAll(connectionId) },
            )
        } ?: emptyList()

    /**
     * The sync-job stream runner (v0.2.0 plan §7/§8/§12 item 7-9): SYNC runs REFERENCE → ISSUES →
     * CHANGELOGS → WORKLOGS → PROCESS → PROFILE; RECONCILE runs its own stream then PROCESS (never
     * PROFILE — a daily drift check is not itself a reason to recompute the whole data profile);
     * REPROCESS flags every raw issue for the connection, then runs PROCESS → PROFILE. PURGE drains
     * [purgeSteps].
     */
    override suspend fun run(context: SyncJobRunContext) {
        when (context.claim.kind) {
            SyncJobKind.SYNC -> runSync(context)
            SyncJobKind.RECONCILE -> runReconcile(context)
            SyncJobKind.PURGE -> purgeSteps.forEach { it.purge(context.claim.connectionId) }
            SyncJobKind.REPROCESS -> runReprocess(context)
            // DERIVE is connector-agnostic (v0.3.0 M3 commit 7) — `ingest/IngestWorker.kt`
            // dispatches it through `ingest/JobHandlers.kt`'s registry BEFORE ever reaching a connector's own
            // `run()`, so this branch is unreachable in practice; it exists only for exhaustiveness.
            SyncJobKind.DERIVE -> Unit
        }
    }

    private suspend fun runSync(context: SyncJobRunContext) {
        val deps = checkNotNull(sync) { "JiraConnector.run(SYNC) requires JiraSyncDependencies (jira/Jira.kt's configureJira)" }
        val claim = context.claim
        val stored = deps.dataSources.readForSync(claim.connectionId) ?: return
        val client = newClient(stored.siteUrl, stored.email, stored.apiToken, stored.authScheme)
        // [resolveCloudId] must run before any other gateway call (jira/JiraClient.kt's contract).
        deps.dataSources.persistCloudId(claim.connectionId, client.resolveCloudId())
        val backfillFromMillis = backfillFromEpochMillis(stored.backfillFrom)
        val streamContext = StreamContext(claim.connectionId, claim.id, deps.database, deps.cursors, context.heartbeat, now)
        val streams: List<Stream> = listOf(
            JiraReferenceStream(client, deps.rawStore, stored.projectKeys),
            JiraIssuesStream(
                client, deps.rawStore, stored.projectKeys, backfillFromMillis, stored.backfillFrom,
                deps.incrementalOverlapMinutes, deps.issuesPageSize,
            ),
            JiraChangelogStream(client, deps.rawStore, deps.changelogBulkSize),
            JiraWorklogStream(client, deps.rawStore),
            JiraProcessStream(deps.rawStore, deps.workItems),
            JiraProfileStream(deps.rawStore, deps.workItems, deps.dataSources),
        )
        streams.forEach { stream ->
            streamContext.currentStreamName = stream.name
            stream.run(streamContext)
        }
    }

    /**
     * RECONCILE runs `reconcile` then `process` (v0.2.0 plan §7/§8/§12 item 7-8) — a RECONCILE
     * tombstone flags its issue `needs_processing` (`jira/JiraRawStore.kt`'s `markIssueDeleted`/
     * `markIssueMovedOut`), so the SAME job mirrors it into `norm.work_items` immediately, not on
     * the next scheduled SYNC. `IngestWorker.onSucceeded` stamps `last_reconcile_at`.
     */
    private suspend fun runReconcile(context: SyncJobRunContext) {
        val deps = checkNotNull(sync) { "JiraConnector.run(RECONCILE) requires JiraSyncDependencies (jira/Jira.kt's configureJira)" }
        val claim = context.claim
        val stored = deps.dataSources.readForSync(claim.connectionId) ?: return
        val client = newClient(stored.siteUrl, stored.email, stored.apiToken, stored.authScheme)
        deps.dataSources.persistCloudId(claim.connectionId, client.resolveCloudId())
        val streamContext = StreamContext(claim.connectionId, claim.id, deps.database, deps.cursors, context.heartbeat, now)
        val streams: List<Stream> = listOf(
            JiraReconcileStream(client, deps.rawStore, stored.projectKeys, backfillFromEpochMillis(stored.backfillFrom)),
            JiraProcessStream(deps.rawStore, deps.workItems),
        )
        streams.forEach { stream ->
            streamContext.currentStreamName = stream.name
            stream.run(streamContext)
        }
    }

    /**
     * REPROCESS (v0.2.0 plan §7/§8/§12 item 8-9): flags EVERY raw issue for the connection, then
     * runs `process` → `profile` — a version bump or a manually requested full rebuild, never
     * touching Jira.
     */
    private suspend fun runReprocess(context: SyncJobRunContext) {
        val deps = checkNotNull(sync) { "JiraConnector.run(REPROCESS) requires JiraSyncDependencies (jira/Jira.kt's configureJira)" }
        val claim = context.claim
        deps.rawStore.markAllNeedsProcessing(claim.connectionId)
        val streamContext = StreamContext(claim.connectionId, claim.id, deps.database, deps.cursors, context.heartbeat, now)
        val streams: List<Stream> = listOf(
            JiraProcessStream(deps.rawStore, deps.workItems),
            JiraProfileStream(deps.rawStore, deps.workItems, deps.dataSources),
        )
        streams.forEach { stream ->
            streamContext.currentStreamName = stream.name
            stream.run(streamContext)
        }
    }

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
        var firstProjectId: Long? = null
        var firstProjectTypeId: Long? = null
        var firstBoardId: Long? = null
        var firstScrumBoardId: Long? = null
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
            val page = client.searchJql(JiraJql.scope(projectKeys), fields = "id", maxResults = SEARCH_PROBE_MAX_RESULTS)
            firstIssueId = page.issues.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        }
        probe("field", "/rest/api/3/field", required = true, scopeHint = "read:field:jira") { client.fields() }
        probe("statuses", "/rest/api/3/statuses/search", required = true, scopeHint = "read:workflow:jira") { client.statusesSearch() }
        probe("projects", "/rest/api/3/project/search", required = true, scopeHint = "read:project:jira") {
            // The first configured key's id seeds the optional `project_fields` probe (only when the key is on this first page).
            val projects = client.projectsSearch().values
            // `projectIdsByKey` is total (skips malformed entries), so seeding the optional probe can never fail this REQUIRED one.
            firstProjectId = projectKeys.firstOrNull()?.let { JiraProjectFields.projectIdsByKey(projects)[it] }
        }
        projectKeys.forEach { key ->
            probe(
                "project_statuses:$key",
                "/rest/api/3/project/$key/statuses",
                required = true,
                scopeHint = "read:status:jira, read:issue-status:jira, read:issue-type:jira",
            ) {
                val statuses = client.projectStatuses(key)
                if (key == projectKeys.first()) firstProjectTypeId = JiraProjectFields.issueTypeIds(statuses).firstOrNull()
            }
        }
        firstIssueId?.let { issueId ->
            probe("bulkfetch", "/rest/api/3/changelog/bulkfetch", required = false, scopeHint = "read:issue.changelog:jira") {
                client.changelogBulk(listOf(issueId), maxResults = 1)
            }
            probe(
                "issue_changelog",
                "/rest/api/3/issue/$issueId/changelog",
                required = true,
                scopeHint = "read:issue-details:jira, read:issue.changelog:jira",
            ) { client.issueChangelogPage(issueId) }
            probe(
                "issue_worklog",
                "/rest/api/3/issue/$issueId/worklog",
                required = true,
                scopeHint = "read:issue:jira, read:issue-worklog:jira",
            ) { client.issueWorklogPage(issueId) }
        }
        probe("worklog_updated", "/rest/api/3/worklog/updated", required = true, scopeHint = "read:issue-worklog:jira") {
            client.worklogUpdated(0)
        }
        probe("users", "/rest/api/3/users/search", required = true, scopeHint = "read:jira-user") { client.usersSearch() }
        // Required: a SYNC's REFERENCE stream fetches boards, configurations and sprints for every
        // connection, and a 401/403 there fails the job (`.claude/docs/jira-integration.md`).
        probe("boards", "/rest/agile/1.0/board", required = true, scopeHint = "read:board-scope:jira-software, read:project:jira") {
            val inScope = client.boards().values.map { it.jsonObject }.filter { isInScopeBoard(it, projectKeys) }
            firstBoardId = inScope.firstOrNull()?.boardId()
            firstScrumBoardId = inScope.firstOrNull(::isScrumBoard)?.boardId()
        }
        firstBoardId?.let { boardId ->
            probe(
                "board_configuration",
                "/rest/agile/1.0/board/$boardId/configuration",
                required = true,
                scopeHint = "read:board-scope.admin:jira-software, read:project:jira",
            ) { client.boardConfiguration(boardId) }
        }
        firstScrumBoardId?.let { boardId ->
            probe("board_sprints", "/rest/agile/1.0/board/$boardId/sprint", required = true, scopeHint = "read:sprint:jira-software") {
                client.boardSprints(boardId)
            }
        }
        // Optional and last: the field scheme only decides which custom fields the metrics-config editor lists by default,
        // so it must not eat the shared time budget of the required probes above.
        val fieldsProjectId = firstProjectId
        val fieldsTypeId = firstProjectTypeId
        if (fieldsProjectId != null && fieldsTypeId != null) {
            probe("project_fields", "/rest/api/3/projects/fields", required = false, scopeHint = "read:field-configuration:jira") {
                client.projectFields(fieldsProjectId, listOf(fieldsTypeId), maxResults = 1)
            }
        }
        return ConnectionTestResult(rows, cloudId)
    }

    private fun JsonObject.boardId(): Long? = get("id")?.jsonPrimitive?.longOrNull

    private companion object {
        const val SEARCH_PROBE_MAX_RESULTS = 5
    }
}
