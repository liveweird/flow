package ch.nokillswit.jira

import ch.nokillswit.ingest.ConnectionTestResult
import ch.nokillswit.ingest.ConnectionTestRow
import ch.nokillswit.ingest.Connector
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.PurgeStep
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.norm.purgeAll
import ch.nokillswit.infra.catchingFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraConnector::class.java)

private const val PROBE_TIMEOUT_MS = 10_000L
private const val TOTAL_BUDGET_MS = 30_000L

/**
 * `testConnection` (v0.2.0 plan §6): probes each endpoint in SEQUENCE, at most 10s each and 30s
 * total, never throwing — every outcome becomes a [ConnectionTestRow]. Order: tenant_info →
 * myself → search → field → statuses → projects (+ each key) → bulkfetch (optional) + per-issue
 * changelog → per-issue worklog (A1) → worklog/updated → users → boards + configuration
 * + sprints (required: a SYNC's REFERENCE stream fetches all three), then the OPTIONAL `field_search` (informational: logs
 * its `total` next to `GET /field`'s count) and, LAST, the OPTIONAL `project_fields` row, seeded with the first configured
 * key's project id and first issue-type id (skipped when either is unavailable). The first in-scope issue id found by the search
 * probe seeds the
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
     * The sync-job stream runner (v0.2.0 plan §7/§8/§12 item 7-9): SYNC runs TRIM → REFERENCE → ISSUES →
     * CHANGELOGS → WORKLOGS → PROCESS → PROFILE; RECONCILE runs its own stream then PROCESS (never
     * PROFILE — a daily drift check is not itself a reason to recompute the whole data profile, and never TRIM:
     * its writes are trimmed on the way in); REPROCESS flags every raw issue for the connection, then runs
     * TRIM → PROCESS → PROFILE. PURGE drains [purgeSteps]. TRIM (`JiraTrimStream`) rewrites the raw rows stored before the
     * payload trim existed, once; it calls no Jira endpoint.
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
        withTally(deps, context) { streamContext ->
            // TRIM first, before the first Jira call: it needs no Jira (an unreachable site must not keep an install untrimmed).
            val trim = JiraTrimStream(deps.rawStore)
            streamContext.currentStreamName = trim.name
            trim.run(streamContext)
            // [resolveCloudId] must run before any other gateway call (jira/JiraClient.kt's contract).
            deps.dataSources.persistCloudId(claim.connectionId, client.resolveCloudId())
            val backfillFromMillis = backfillFromEpochMillis(stored.backfillFrom)
            // No Jira-calling stream may follow `process`/`profile`: the hard stop is disarmed for good there (`withHardStop`).
            val streams: List<Stream> = listOf(
                // "Sync now" refreshes the users whatever their age: a new hire's account is then pickable for team membership at once.
                JiraReferenceStream(client, deps.rawStore, stored.projectKeys, if (claim.requestedOnDemand) 0 else deps.usersRefreshMillis),
                JiraIssuesStream(
                    client, deps.rawStore, stored.projectKeys, backfillFromMillis, stored.backfillFrom,
                    deps.incrementalOverlapMinutes, deps.issuesPageSize,
                ),
                JiraChangelogStream(client, deps.rawStore, deps.changelogBulkSize, deps.changelogPageSize),
                JiraWorklogStream(client, deps.rawStore),
                JiraProcessStream(deps.rawStore, deps.workItems),
                JiraProfileStream(deps.rawStore, deps.workItems, deps.dataSources),
            )
            streams.forEach { stream ->
                streamContext.currentStreamName = stream.name
                stream.run(streamContext)
            }
        }
    }

    /**
     * Runs [block] with the job's [StreamContext] inside a fresh [JiraCallTally] (the job's Jira rate-limiting cost,
     * `jira/JiraCallTally.kt`): every [JiraHttp] call under it - the streams' fetch-ahead children included - counts into it, and the
     * context's heartbeats flush it onto `sync_jobs.progress`. REPROCESS never calls Jira, so it takes none.
     *
     * A RETRIED attempt (attempt > 1) starts from the sticky keys the previous attempt flushed ([STICKY_PROGRESS_KEYS]: the tally's
     * counters, which add up, and `backfill`), because its first heartbeat replaces the job's `progress`. An attempt that FAILS makes
     * one last best-effort heartbeat before the failure propagates (under [NonCancellable]; a lost lease or a database error is
     * swallowed; also for a `StackOverflowError`, the one `Error` handled), so the throttling that caused a `RATE_LIMITED` give-up is on
     * the job row; a cancellation propagates untouched. A `LIMIT_EXCEEDED` failure leaves as a
     * [ch.nokillswit.ingest.CeilingTripException] ([ceilingTripOf]); the job's wall clock already is one,
     * graceful (between pages) or hard ([StreamContext.withHardStop], which wraps the whole run here: a call or page still in flight at
     * the budget plus its grace is cancelled and the run leaves as the same `TIME_EXCEEDED` trip).
     */
    private suspend fun withTally(deps: JiraSyncDependencies, context: SyncJobRunContext, block: suspend (StreamContext) -> Unit) {
        val carried = carriedProgress(context.claim)
        val tally = JiraCallTally(carried)
        val backfill = carried.filterKeys { it == BACKFILL_PROGRESS_KEY }
        val streamContext = StreamContext(
            context.claim.connectionId, context.claim.id, deps.database, deps.cursors, context.heartbeat, now,
            extraProgress = { backfill + tally.snapshot() }, maxRunMillis = context.maxRunMillis,
            deadlineExemptStreams = LOCAL_STREAMS, hardStopMillis = context.hardStopMillis,
        )
        // A StackOverflowError (an Error, which `catchingFailures` leaves alone) is flushed and rethrown like any failure.
        val failure = try {
            catchingFailures<Throwable?>({ withContext(tally) { streamContext.withHardStop { block(streamContext) } }; null }) { it }
        } catch (overflow: StackOverflowError) {
            overflow
        }
        if (failure != null) {
            // Typed BEFORE the last heartbeat below, which is no change; the worker closes the job on the typed trip (`SyncPause.kt`).
            val thrown = (failure as? Exception)?.ceilingTripOf(streamContext) ?: failure
            withContext(NonCancellable) { catchingFailures({ streamContext.heartbeat(changed = false) }) { } }
            throw thrown
        }
    }

    /** The sticky counters the claim's previous attempt flushed (see [withTally]); empty on a first attempt. */
    private fun carriedProgress(claim: SyncJobClaim): Map<String, Long> {
        val prior = claim.priorProgress?.takeIf { claim.attempt > 1 } ?: return emptyMap()
        val parsed = catchingFailures({ Json.parseToJsonElement(prior) as? JsonObject }) { null } ?: return emptyMap()
        return parsed.filterKeys { it in STICKY_PROGRESS_KEYS }
            .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }.toMap()
    }

    private fun reprocessContext(deps: JiraSyncDependencies, context: SyncJobRunContext) = StreamContext(
        context.claim.connectionId, context.claim.id, deps.database, deps.cursors, context.heartbeat, now,
    )

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
        withTally(deps, context) { streamContext ->
            deps.dataSources.persistCloudId(claim.connectionId, client.resolveCloudId())
            // No Jira-calling stream may follow `process`/`profile`: the hard stop is disarmed for good there (`withHardStop`).
            val streams: List<Stream> = listOf(
                JiraReconcileStream(client, deps.rawStore, stored.projectKeys, backfillFromEpochMillis(stored.backfillFrom)),
                JiraProcessStream(deps.rawStore, deps.workItems),
            )
            streams.forEach { stream ->
                streamContext.currentStreamName = stream.name
                stream.run(streamContext)
            }
        }
    }

    /**
     * REPROCESS (v0.2.0 plan §7/§8/§12 item 8-9): flags EVERY raw issue for the connection, then
     * runs `trim` → `process` → `profile` — a version bump or a manually requested full rebuild, never
     * touching Jira (TRIM first, so an install that only ever REPROCESSes is trimmed too; it is a no-op once done).
     */
    private suspend fun runReprocess(context: SyncJobRunContext) {
        val deps = checkNotNull(sync) { "JiraConnector.run(REPROCESS) requires JiraSyncDependencies (jira/Jira.kt's configureJira)" }
        val claim = context.claim
        deps.rawStore.markAllNeedsProcessing(claim.connectionId)
        val streamContext = reprocessContext(deps, context)
        val streams: List<Stream> = listOf(
            JiraTrimStream(deps.rawStore),
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
        var fieldCount: Int? = null
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
        probe("field", "/rest/api/3/field", required = true, scopeHint = "read:field:jira") { fieldCount = client.fields().size }
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
        // Optional and informational: does a non-admin token read `field/search`, and does it count the same fields as `GET /field`?
        // The REFERENCE step stays on `GET /field` until the owner settles that (`.claude/docs/jira-integration.md`). Late on purpose,
        // like `project_fields`: an optional probe must not eat the shared time budget of the required ones above.
        probe("field_search", "/rest/api/3/field/search", required = false, scopeHint = "read:field:jira, read:field-configuration:jira") {
            val page = client.fieldsSearch(0, 1)
            log.info(
                "field/search total={}, values={}, isLast={}, field count={}",
                page.total, page.values.size, page.isLast, fieldCount,
            )
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
