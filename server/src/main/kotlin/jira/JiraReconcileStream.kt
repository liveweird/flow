package ch.nokillswit.jira

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/** `sync_cursors.cursor` shape for the RECONCILE stream (v0.2.0 plan §7/§12 item 7). */
@Serializable
internal data class ReconcileCursor(
    val passStartedAt: Long,
    val nextPageToken: String? = null,
    /**
     * The exact id-sweep JQL of THIS pass, computed once at the pass start and reused by a resumed
     * pass (Jira ties a page token to its query text). Null only in a cursor written before the sweep
     * was windowed: its token belongs to the old, unbounded query, so [JiraReconcileStream] drops it
     * and starts a fresh pass instead of resuming.
     */
    val jql: String? = null,
    /**
     * The instant the sweep window starts — `passStartedAt` minus [jql]'s `N` whole minutes, fixed at
     * the pass start and never before `backfillFrom`. The anti-join's candidates start a few minutes
     * after it ([RECONCILE_CANDIDATE_SLACK_MILLIS]).
     */
    val windowStartMillis: Long? = null,
    /**
     * The sync job that started this pass. The scratch seen-set is keyed by job id, so only that job
     * can resume the pass; any other job starts a fresh one.
     */
    val jobId: Long? = null,
)

/** `search/jql fields=id` page size (v0.2.0 plan §7 "RECONCILE") — pages of up to 5000 ids. */
private const val RECONCILE_PAGE_SIZE = 5000

/** `GET /issue/{id}` requests only what a reconcile decision needs — never the full document. */
private const val RECONCILE_ISSUE_FIELDS = "project,key"

private const val NOT_FOUND_CODE = "NOT_FOUND"

private const val CURSOR_EXPIRED_CODE = "CURSOR_EXPIRED"

private val log = LoggerFactory.getLogger(JiraReconcileStream::class.java)

/**
 * The RECONCILE stream (v0.2.0 plan §7/§12 item 7): a daily id-only sweep (`search/jql fields=id`,
 * `JiraJql.reconcile`, bounded to `updated >= "-Nm"` back to the connection's `backfillFrom` — the
 * window the ISSUES stream's first run covered) into the `raw.jira_reconcile_seen` scratch table
 * (V12), then an anti-join against `raw.jira_issues` decides what changed while Flow wasn't looking:
 *
 * - A non-tombstoned raw issue of an in-scope project (by project id), last updated at least five
 *   minutes past the window start (`JiraRawStore.issuesMissingFromSeen`), ABSENT from the seen set is
 *   checked individually (`GET /issue/{id}?fields=project,key`): a 404 means Jira deleted it
 *   (`deletedAt`); a 200 whose project is now OUTSIDE the scope means it moved out (`movedOutAt`, plus
 *   a key/project refresh). Either way `needs_processing` is flagged — every tombstone flags the issue
 *   for processing (plan §7). Rows last updated before `backfillFrom` are kept but never re-checked.
 * - An id the sweep saw but `raw.jira_issues` never stored (an index gap — a missed ISSUES page,
 *   say) is fetched in full and upserted, the same write the ISSUES stream itself uses. Skipped (counted
 *   as `indexGapSkipped`) while the ISSUES cursor has no completed run covering the current scope — then
 *   the ids are simply the part of the scope the ISSUES stream has yet to bulk-download ([coversScope]).
 *
 * The scratch table is scoped per (connection, job) so a resumed pass (same job id, after a lease
 * loss/reclaim) re-inserts idempotently rather than duplicating (`JiraRawStore.insertReconcileSeen`'s
 * `ON CONFLICT DO NOTHING`), and is fully drained (`JiraRawStore.clearReconcileSeen`) once the
 * anti-join step completes — a crash before that point simply leaves it for the NEXT run's own
 * anti-join to work from once the sweep re-completes (the ids are re-collected, never lost).
 * `DataSourceService.recordReconcileSucceeded` (stamping `last_reconcile_at`) runs at the JOB level
 * once this stream returns successfully (`ingest/IngestWorker.kt`'s `onSucceeded`), not here.
 *
 * The sweep is bounded like ISSUES (`.claude/docs/jira-integration.md` "Paging ceilings"): at most [runCeiling] pages, ids and wire
 * bytes per pass (`LIMIT_EXCEEDED`), a repeated or very long `nextPageToken` refused (`INVALID_RESPONSE`), each before the
 * page's transaction.
 * A failed pass leaves its scratch ids for the NEXT job's fresh pass to drain ([freshPass]); no anti-join ran on an incomplete set.
 */
class JiraReconcileStream internal constructor(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val projectKeys: List<String>,
    private val backfillFromEpochMillis: Long,
    private val runCeiling: RunCeiling,
) : Stream {
    constructor(client: JiraClient, rawStore: JiraRawStore, projectKeys: List<String>, backfillFromEpochMillis: Long) :
        this(client, rawStore, projectKeys, backfillFromEpochMillis, RECONCILE_RUN_CEILING)

    override val name: String = "reconcile"

    override suspend fun run(context: StreamContext) {
        var state = loadOrStart(context)
        var restarts = 0
        val budget = RunBudget(SEARCH_JQL_ENDPOINT, runCeiling, "ids")
        val tokens = PageTokenGuard(SEARCH_JQL_ENDPOINT, state.nextPageToken)
        while (true) {
            budget.nextPage()
            val page = try {
                val jql = checkNotNull(state.jql)
                client.searchJql(jql, fields = "id", nextPageToken = state.nextPageToken, maxResults = RECONCILE_PAGE_SIZE)
            } catch (expired: JiraFetchException) {
                // Same bounded restart as the ISSUES stream: a token Jira no longer honours means a fresh pass.
                if (expired.code != CURSOR_EXPIRED_CODE || restarts >= MAX_CURSOR_RESTARTS) throw expired
                restarts++
                state = freshPass(context, persist = true)
                tokens.restart() // a new query: its tokens are a new chain
                continue
            }
            // Both refusals come BEFORE the page's transaction: the stored cursor still names the token that asked this page.
            tokens.accept(page.nextPageToken)
            budget.add(page.issues.size, page.wireBytes)
            val isLast = page.nextPageToken == null
            context.transaction {
                page.issues.forEach { issueElement ->
                    val issueId = issueElement.requireObjectElement(SEARCH_JQL_ENDPOINT, "an issue")
                        .requireLong("id", SEARCH_JQL_ENDPOINT, "an issue")
                    rawStore.insertReconcileSeen(context.connectionId, context.jobId, issueId)
                }
                context.putCursor(name, encodeCursor(state.copy(nextPageToken = page.nextPageToken)))
                context.incrementProgress("pages")
            }
            // Scratch only: the seen-set is keyed by this job's id, so a stop in the sweep leaves the next run exactly where this one
            // began - no change for the RECONCILE pause. The durable phases after the sweep (tombstones, index-gap upserts) say true.
            context.heartbeat(changed = false)
            if (isLast) break
            state = state.copy(nextPageToken = page.nextPageToken)
        }

        val projectIds = rawStore.resolveProjectIds(context.connectionId, projectKeys)
        if (projectIds == null) {
            if (rawStore.oldestLiveEntitySeenAt(context.connectionId, JiraEntityKind.PROJECT.name) == null) {
                log.info(
                    "reconcile for connection {}: no PROJECT reference entities yet (no SYNC has completed) — the scope is not " +
                        "resolved yet, candidates are not restricted by project",
                    context.connectionId,
                )
            } else {
                log.warn(
                    "reconcile for connection {}: a configured project key does not resolve to a live PROJECT entity " +
                        "(renamed, mistyped or deleted project) — candidates are not restricted by project",
                    context.connectionId,
                )
            }
        }
        rawStore.issuesMissingFromSeen(context.connectionId, context.jobId, projectIds, checkNotNull(state.windowStartMillis))
            .forEach { candidate -> reconcileMissing(context, candidate, projectIds) }
        reconcileIndexGaps(context)

        context.transaction {
            rawStore.clearReconcileSeen(context.connectionId)
            context.clearCursor(name)
        }
    }

    /**
     * The index-gap phase: fetch every id the sweep saw but `raw.jira_issues` never stored — unless the
     * ISSUES stream has not yet completed a run covering the current scope (a first backfill or a scope
     * catch-up is pending, [coversScope]). Then the "gap" is just the part of the scope ISSUES has yet to
     * download in bulk, and fetching it one `GET /issue/{id}` at a time would be the slow way to do the
     * ISSUES stream's job: the phase is skipped (counted as `indexGapSkipped`) and the next RECONCILE after
     * that run completes handles any real gap.
     */
    private suspend fun reconcileIndexGaps(context: StreamContext) {
        val issuesCursor = context.cursor(ISSUES_STREAM_NAME)?.cursor?.let(::decodeIssuesCursor)
        val unknownIds = rawStore.seenButUnknownIds(context.connectionId, context.jobId)
        if (!issuesCursor.coversScope(projectKeys, backfillFromEpochMillis)) {
            log.info(
                "reconcile for connection {}: index-gap phase skipped ({} unknown ids) — the ISSUES stream has not completed " +
                    "a run covering the current scope yet",
                context.connectionId, unknownIds.size,
            )
            if (unknownIds.isNotEmpty()) context.incrementProgress("indexGapSkipped", unknownIds.size.toLong())
            return
        }
        unknownIds.forEach { issueId -> reconcileIndexGap(context, issueId) }
    }

    /** A raw issue the sweep never saw: Jira either deleted it (404) or moved it out of scope (a different project). */
    private suspend fun reconcileMissing(context: StreamContext, candidate: ReconcileCandidate, projectIds: Set<Long>?) {
        val issueId = candidate.issueId
        val issue = try {
            client.issue(issueId.toString(), fields = RECONCILE_ISSUE_FIELDS)
        } catch (notFound: JiraFetchException) {
            if (notFound.code != NOT_FOUND_CODE) throw notFound
            context.transaction {
                rawStore.markIssueDeleted(context.connectionId, issueId, context.clock())
                context.incrementProgress("tombstoned")
            }
            context.heartbeat(changed = true)
            return
        }
        val fields = issue.requireObject("fields", ISSUE_ENDPOINT, "an issue")
        val project = fields.requireObject("project", ISSUE_ENDPOINT, "an issue")
        val newProjectKey = project.requireString("key", ISSUE_ENDPOINT, "an issue's project")
        val newProjectId = project.requireLong("id", ISSUE_ENDPOINT, "an issue's project")
        // Still in scope when the project id is unchanged (a rename keeps the id; only a move changes it) or is
        // an in-scope id; by key only when the scope could not be resolved.
        val inScope = newProjectId == candidate.projectId ||
            (if (projectIds != null) newProjectId in projectIds else newProjectKey in projectKeys)
        if (!inScope) {
            context.transaction {
                rawStore.markIssueMovedOut(
                    context.connectionId,
                    issueId,
                    newKey = issue.requireString("key", ISSUE_ENDPOINT, "an issue"),
                    newProjectId = newProjectId,
                    newProjectKey = newProjectKey,
                    now = context.clock(),
                )
                context.incrementProgress("tombstoned")
            }
            context.heartbeat(changed = true)
        }
        // Still in scope: the id sweep should have listed it already — nothing to reconcile here
        // (a transient sweep/anti-join mismatch, not expected in practice).
    }

    /** An id the sweep saw but `raw.jira_issues` never stored — fetched and upserted like a normal ISSUES-stream write. */
    private suspend fun reconcileIndexGap(context: StreamContext, issueId: Long) {
        // The id stays the one the sweep listed (and this fetch asked for).
        val input = rawIssueInputOf(client.issue(issueId.toString(), fields = ISSUE_SEARCH_FIELDS), ISSUE_ENDPOINT).copy(issueId = issueId)
        // The ISSUES stream's "skip, loudly" rule: an issue the database rejects as a bad VALUE (and so ISSUES left out) must not
        // fail every RECONCILE too - it is logged and counted as `indexGapSkipped`, not retried within this pass. An issue over the
        // stored-size ceiling is REFUSED by the writer (its WARN) and counted the same way; neither is a change for the pause.
        var outcome: RawUpsertOutcome? = null
        val failure = catchingFailures<Exception?>(
            block = {
                outcome = context.transaction { rawStore.upsertIssue(context.connectionId, input, context.clock()) }
                null
            },
            onFailure = { it },
        )
        if (failure == null && outcome != RawUpsertOutcome.REFUSED) {
            context.incrementProgress("issuesUpserted")
        } else if (failure == null) {
            context.incrementProgress("indexGapSkipped")
        } else {
            if (!failure.isBadValueError()) throw failure
            log.warnSkippedIssue("RECONCILE index-gap fetch", context.connectionId, input.issueId, input.issueKey, failure)
            context.incrementProgress("indexGapSkipped")
        }
        context.heartbeat(changed = outcome?.isChange == true)
    }

    /**
     * The persisted pass when it is this job's own, carries its JQL and window, and has a page token to
     * resume from; otherwise — no cursor, a legacy one written before the sweep was windowed (its token
     * belongs to the old unbounded query), another job's (the scratch seen-set is keyed by job id, so
     * resuming at page k would leave pages 1..k-1 unseen and make their rows false candidates), or a
     * finished sweep that never got cleared (a crash after the last page) — a fresh pass.
     */
    private suspend fun loadOrStart(context: StreamContext): ReconcileCursor {
        val existing = context.cursor(name)?.cursor?.let { decodeCursor<ReconcileCursor>(it) }
        val resumable = existing != null && existing.jql != null && existing.windowStartMillis != null &&
            existing.nextPageToken != null && existing.jobId == context.jobId.toLong()
        if (resumable) return checkNotNull(existing)
        return freshPass(context, persist = existing != null)
    }

    /**
     * A new pass: its start time, the window (`N` = WHOLE minutes from `backfillFrom` to the start,
     * rounded DOWN so the sweep never begins before `backfillFrom`; nothing older is ever listed, hence
     * no index-gap fetching of history the admin cut off) and the JQL derived from it, computed once.
     * [persist] replaces a stale cursor and drains the scratch ids an abandoned sweep left behind, in
     * one transaction.
     */
    private suspend fun freshPass(context: StreamContext, persist: Boolean): ReconcileCursor {
        val passStartedAt = context.clock()
        val sinceMinutes = wholeMinutesBetween(backfillFromEpochMillis, passStartedAt)
        val cursor = ReconcileCursor(
            passStartedAt = passStartedAt,
            jql = JiraJql.reconcile(projectKeys, sinceMinutes),
            windowStartMillis = passStartedAt - sinceMinutes * MILLIS_PER_MINUTE,
            jobId = context.jobId.toLong(),
        )
        if (persist) {
            context.transaction {
                rawStore.clearReconcileSeen(context.connectionId)
                context.putCursor(name, encodeCursor(cursor))
            }
        }
        return cursor
    }
}
