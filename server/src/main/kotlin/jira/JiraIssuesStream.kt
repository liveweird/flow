package ch.nokillswit.jira

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.ingest.Stream
import ch.nokillswit.ingest.StreamContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(JiraIssuesStream::class.java)

private val ISSUES_CURSOR_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** `sync_cursors.cursor` shape for the ISSUES stream (v0.2.0 plan §7). */
@Serializable
internal data class IssuesCursor(
    val watermarkAt: Long? = null,
    /**
     * The exact JQL string driving THIS run — computed once at the run's start, then reused for
     * every page (`sample-data/README.md`: the stub ignores JQL text, but a real tenant's paging
     * must reuse the same query).
     */
    val jql: String,
    val nextPageToken: String? = null,
    val runStartedAt: Long,
    /**
     * The scope the WATERMARK covers: every project in [coveredProjectKeys] has been fetched back to
     * [coveredBackfillFrom] (`yyyy-MM-dd`) and kept current since. Null in a cursor written before the
     * scope was tracked — such a cursor with a watermark is read as covering the CURRENT scope
     * ([normalizedFor]), so deploying this does not re-download anything. Moves to [runProjectKeys]/
     * [runBackfillFrom] when a run's LAST page lands, together with the watermark.
     */
    val coveredProjectKeys: List<String>? = null,
    val coveredBackfillFrom: String? = null,
    /**
     * The scope the CURRENT run's [jql] was built for. An interrupted run resumes its stored query/token
     * only while this still equals the configured scope; otherwise the token is discarded and a fresh run
     * starts (Jira ties a page token to its query text, and the query no longer matches the scope).
     */
    val runProjectKeys: List<String>? = null,
    val runBackfillFrom: String? = null,
)

/** The `sync_cursors.stream` key of the ISSUES stream — shared with RECONCILE's "has the scope been downloaded" guard. */
internal const val ISSUES_STREAM_NAME = "issues"

internal fun decodeIssuesCursor(text: String): IssuesCursor = ISSUES_CURSOR_JSON.decodeFromString(text)

/**
 * A cursor read from storage with the fields an older build never wrote filled in: a legacy cursor
 * (no scope tracking) covers — and, if interrupted, was running — the CURRENT scope.
 */
internal fun IssuesCursor.normalizedFor(projectKeys: List<String>, backfillFrom: String): IssuesCursor = copy(
    coveredProjectKeys = coveredProjectKeys ?: projectKeys.takeIf { watermarkAt != null },
    coveredBackfillFrom = coveredBackfillFrom ?: backfillFrom.takeIf { watermarkAt != null },
    runProjectKeys = runProjectKeys ?: projectKeys,
    runBackfillFrom = runBackfillFrom ?: backfillFrom,
)

/** The configured keys the watermark does not cover yet — added to the scope, or re-added after a run shrank the covered set. */
internal fun IssuesCursor.uncoveredKeys(projectKeys: List<String>): List<String> =
    coveredProjectKeys?.let { covered -> projectKeys.filter { it !in covered } } ?: emptyList()

/** True when the configured `backfillFrom` reaches EARLIER than the history the watermark covers. */
internal fun IssuesCursor.backfillMovedEarlier(backfillFromMillis: Long): Boolean =
    coveredBackfillFrom?.let { backfillFromMillis < backfillFromEpochMillis(it) } ?: false

/**
 * The ONE "has a completed ISSUES run downloaded the current scope" decision, built from the same two
 * helpers [JiraIssuesStream.freshRun] splits its clauses with (so what the stream would still catch up
 * and what RECONCILE's index-gap guard waits for cannot drift apart): a completed run exists (a
 * watermark), no configured project is uncovered, and the covered history reaches back at least as far
 * as the configured `backfillFrom`. A cursor without scope fields (legacy) covers whatever is configured.
 */
internal fun IssuesCursor?.coversScope(projectKeys: List<String>, backfillFromMillis: Long): Boolean =
    this != null && watermarkAt != null && uncoveredKeys(projectKeys).isEmpty() && !backfillMovedEarlier(backfillFromMillis)

private fun List<String>?.sameKeysAs(other: List<String>?): Boolean = this.orEmpty().toSet() == other.orEmpty().toSet()

/**
 * A restart bound for [JiraFetchException]'s `CURSOR_EXPIRED` — a real Jira token never expires
 * again immediately after a fresh query; this only guards against a pathological/misbehaving
 * upstream.
 */
internal const val MAX_CURSOR_RESTARTS = 5

/**
 * The ISSUES stream's `search/jql` `fields`: every field, the same document `GET /issue/{id}` returns (the index-gap
 * path stores that one through the same write). `search/jql` returns only `id` when `fields` is omitted, unlike the
 * retired `/search`; the first real tenant's SYNC failed on exactly that.
 */
internal const val ISSUE_SEARCH_FIELDS = "*all"

/**
 * The page size and restart budget of ONE stream invocation (run-local, never persisted): [size] starts at the
 * configured page size and only shrinks; [halvedJustNow] is true between a halving and the next answer.
 */
private class PageAsk(var size: Int, var restarts: Int = 0, var halvedJustNow: Boolean = false)

/**
 * The ISSUES stream (v0.2.0 plan §7): pages `search/jql` with an opaque `nextPageToken`, JQL scoped
 * by [projectKeys] with a relative `updated` bound (`JiraJql.incremental`) — TZ-free by design
 * (`jira/JiraJql.kt`). Fields are requested as [ISSUE_SEARCH_FIELDS] (`*all`, EVERY field — `search/jql` returns only
 * `id` without it), the only `fields` value the stub's full-document pages match
 * (`.claude/docs/jira-integration.md` "ISSUES stream fields"); this also serves the normalization
 * layer landing in a later commit, since Jira does not let a caller ask for "every SYSTEM field
 * plus every discovered custom field" any more cheaply than asking for all of them.
 *
 * Every run starts by tombstoning (`moved_out_at`, no HTTP) the stored issues of any project no
 * longer in [projectKeys] (`JiraRawStore.markOutOfScopeProjects`) and, in the same transaction, shrinking the
 * stored cursor's covered project keys to the configured ones ([shrinkCoveredScope]).
 *
 * `N` (the relative `-Nm` window) is minutes since `backfillFromEpochMillis` on the very first run,
 * or minutes since the last completed run's watermark plus [incrementalOverlapMinutes] afterward —
 * per project: a project the watermark does not cover yet (added to the scope, or re-added), and every
 * project after `backfillFrom` moved earlier, is searched back to `backfillFrom` in the same query
 * ([freshRun], [IssuesCursor.coveredProjectKeys]).
 * The cursor advances in the SAME transaction as each page's raw-issue upserts
 * (`ingest/Stream.kt`'s `StreamContext.transaction`); the watermark itself only moves to this run's
 * `runStartedAt` once the LAST page of that run is written — a lease loss or thrown fault mid-run
 * leaves it exactly where the last completed page left it.
 *
 * Oversized issue page (`.claude/docs/jira-integration.md` "Oversized issue page (`LIMIT_EXCEEDED`)"): a page over
 * `jira.maxResponseBytes` (`LIMIT_EXCEEDED`, a heavy `*all` page) is re-requested with the SAME token and
 * JQL at half the `maxResults`, down to 1; the smaller size lasts for the rest of this invocation only (the next run
 * starts at [pageSize] again), and a single issue still over the cap fails the run as `LIMIT_EXCEEDED`.
 *
 * A bad issue does not pin the cursor (`.claude/docs/ingestion.md` "ISSUES: a bad issue is skipped, loudly"): a page whose
 * transaction fails with a DATA error is redone issue by issue ([writePage]), an issue the database rejects as a bad VALUE is
 * skipped with a WARN and counted (`issuesSkipped`), and the cursor advances as usual; any other failure - and a page of
 * several issues that are ALL skipped - still fails the run.
 */
class JiraIssuesStream(
    private val client: JiraClient,
    private val rawStore: JiraRawStore,
    private val projectKeys: List<String>,
    private val backfillFromEpochMillis: Long,
    /** The configured `backfillFrom` text (`yyyy-MM-dd`); [backfillFromEpochMillis] is its UTC-midnight instant. */
    private val backfillFrom: String,
    private val incrementalOverlapMinutes: Long,
    private val pageSize: Int,
) : Stream {
    override val name: String = ISSUES_STREAM_NAME

    override suspend fun run(context: StreamContext) {
        // A project removed from the connection's scope: tombstone its stored issues locally, before
        // any page — RECONCILE never has to probe them one by one (`JiraRawStore.markOutOfScopeProjects`).
        context.transaction {
            val movedOut = rawStore.markOutOfScopeProjects(context.connectionId, projectKeys, context.clock())
            if (movedOut > 0) context.incrementProgress("movedOutOfScope", movedOut.toLong())
            // The covered scope shrinks in the SAME transaction as the tombstones: a run that fails before its last
            // page must not leave a removed project "covered" — re-adding it would then be a plain retained project
            // (searched only since the watermark) and its tombstoned rows would mostly never come back.
            shrinkCoveredScope(context)
        }
        var state = loadOrStart(context)
        val ask = PageAsk(pageSize)
        while (true) {
            val (current, page) = nextPage(context, ask, state)
            state = current
            val isLast = page.nextPageToken == null
            // The watermark and the scope it covers move together, on the run's LAST page only.
            val written = if (isLast) {
                state.copy(
                    watermarkAt = state.runStartedAt, nextPageToken = null,
                    coveredProjectKeys = state.runProjectKeys, coveredBackfillFrom = state.runBackfillFrom,
                )
            } else {
                state.copy(nextPageToken = page.nextPageToken)
            }
            writePage(context, page.issues.map(::rawIssueInput), written, isLast)
            context.heartbeat()
            if (isLast) break
            state = written
        }
    }

    /**
     * Writes one page: every issue's upsert (one batch, [JiraRawStore.upsertIssues]) and the cursor advance in ONE transaction
     * (the common path). A DATA error
     * ([isDataError]: a value PostgreSQL or Exposed rejects, retrying it cannot succeed) aborts that transaction for
     * every issue of the page, so the page is then redone issue by issue ([writeIssuesOneByOne]) and a bad issue is
     * skipped instead of pinning the cursor on this page forever. Any other failure (an outage, a parse error) is
     * rethrown untouched, as before.
     */
    private suspend fun writePage(context: StreamContext, inputs: List<RawIssueInput>, written: IssuesCursor, isLast: Boolean) {
        val pageFailure = catchingFailures<Exception?>(
            block = {
                context.transaction {
                    rawStore.upsertIssues(context.connectionId, inputs, context.clock())
                    advanceCursor(context, written, isLast)
                }
                null
            },
            onFailure = { it },
        )
        if (pageFailure == null) {
            context.incrementProgress("pages")
            context.incrementProgress("issuesUpserted", inputs.size.toLong())
            return
        }
        if (!pageFailure.isDataError()) throw pageFailure
        log.warn(
            "ISSUES page of {} issue(s) for data source {} hit a data error ({}), retrying one by one",
            inputs.size, context.connectionId, pageFailure.describe(),
        )
        val skipped = writeIssuesOneByOne(context, inputs)
        // Every issue of a multi-issue page rejected is systemic (a wrong column, a migration gap), not one bad row.
        check(inputs.size < 2 || skipped < inputs.size) {
            "ISSUES page of ${inputs.size} issues: every issue was rejected as a bad value - systemic, not skipping the page"
        }
        // The cursor advances exactly as the page transaction would have written it, now in its own transaction.
        context.transaction { advanceCursor(context, written, isLast) }
        context.incrementProgress("pages")
        context.incrementProgress("issuesUpserted", (inputs.size - skipped).toLong())
        if (skipped > 0) context.incrementProgress("issuesSkipped", skipped.toLong())
    }

    /**
     * The fallback of [writePage]: each issue in its OWN transaction. An issue that fails with a data error is SKIPPED
     * ("skip, loudly": one WARN naming its key and id — never its payload — and the `issuesSkipped` counter); the next
     * ISSUES run that sees it updated, or a full resync, fetches it again. Only a bad VALUE ([isBadValueError]) is skipped: any
     * other failure that survived the isolated retry - an outage, or an integrity violation (class 23: a PK race heals on the
     * retry, a NOT NULL/CHECK failure is a writer bug that must fail loudly) - propagates. Returns the number skipped.
     */
    private suspend fun writeIssuesOneByOne(context: StreamContext, inputs: List<RawIssueInput>): Int {
        var skipped = 0
        inputs.forEach { input ->
            val failure = catchingFailures<Exception?>(
                block = {
                    context.transaction { rawStore.upsertIssue(context.connectionId, input, context.clock()) }
                    null
                },
                onFailure = { it },
            )
            if (failure != null) {
                if (!failure.isBadValueError()) throw failure
                skipped++
                log.warnSkippedIssue("ISSUES", context.connectionId, input.issueId, input.issueKey, failure)
            }
        }
        return skipped
    }

    /** The cursor write and `last_completed_at` stamp of one page, inside the caller's transaction. */
    private suspend fun advanceCursor(context: StreamContext, written: IssuesCursor, isLast: Boolean) {
        context.putCursor(
            name,
            ISSUES_CURSOR_JSON.encodeToString(written),
            watermarkAt = written.watermarkAt,
            lastCompletedAt = if (isLast) context.clock() else null,
        )
    }

    private fun rawIssueInput(issueElement: JsonElement): RawIssueInput {
        val issue = issueElement.jsonObject
        val fields = issue.getValue("fields").jsonObject
        val project = fields.getValue("project").jsonObject
        return RawIssueInput(
            issueId = issue.getValue("id").jsonPrimitive.content.toLong(),
            issueKey = issue.getValue("key").jsonPrimitive.content,
            projectId = project.getValue("id").jsonPrimitive.content.toLong(),
            projectKey = project.getValue("key").jsonPrimitive.content,
            issueUpdatedAt = parseJiraInstantEpochMillis(fields.getValue("updated").jsonPrimitive.content),
            payloadJson = issue.toString(),
        )
    }

    /** Fetches the page [from] points at, recovering via [recover]; returns the state that was actually answered. */
    private suspend fun nextPage(context: StreamContext, ask: PageAsk, from: IssuesCursor): Pair<IssuesCursor, JiraSearchPage> {
        var state = from
        while (true) {
            try {
                val page = client.searchJql(state.jql, ISSUE_SEARCH_FIELDS, state.nextPageToken, ask.size)
                ask.halvedJustNow = false
                return state to page
            } catch (failure: JiraFetchException) {
                state = recover(context, ask, state, failure)
            }
        }
    }

    /**
     * The state to ask next after [failure], or the failure rethrown. `LIMIT_EXCEEDED` re-asks the SAME page (same
     * token/JQL) at half the size; a single issue over the cap falls through and fails. `CURSOR_EXPIRED` restarts from
     * the last completed watermark ([freshRun]); one right after a halving is not an upstream fault (halvings are
     * bounded on their own, log2(pageSize) <= 9), so it does not use up the [MAX_CURSOR_RESTARTS] budget.
     */
    private fun recover(context: StreamContext, ask: PageAsk, state: IssuesCursor, failure: JiraFetchException): IssuesCursor = when {
        failure.code == "LIMIT_EXCEEDED" && ask.size > 1 -> {
            log.warn("ISSUES page over the response cap, data source {}: size {} -> {}", context.connectionId, ask.size, ask.size / 2)
            ask.size /= 2
            ask.halvedJustNow = true
            state
        }
        failure.code == "CURSOR_EXPIRED" && (ask.halvedJustNow || ask.restarts < MAX_CURSOR_RESTARTS) -> {
            if (!ask.halvedJustNow) ask.restarts++
            ask.halvedJustNow = false
            freshRun(context, state)
        }
        else -> throw failure
    }

    /**
     * A persisted `nextPageToken` means a prior run was interrupted mid-page — resume it AS-IS (same
     * JQL/runStartedAt/token) while the scope it was built for is still the configured one; a run
     * interrupted under a different scope is discarded (covered scope and watermark stay as they were)
     * and a fresh run starts. Without a token: a fresh run from the watermark/covered scope.
     */
    private suspend fun loadOrStart(context: StreamContext): IssuesCursor {
        val existing = context.cursor(name)?.cursor?.let { decodeIssuesCursor(it).normalizedFor(projectKeys, backfillFrom) }
        if (existing?.nextPageToken != null && runsCurrentScope(existing)) return existing
        return freshRun(context, existing)
    }

    /**
     * Rewrites the stored cursor's covered keys to covered ∩ configured (nothing else in the cursor moves,
     * and `last_completed_at` is kept). Always safe: a smaller covered set can only cost a redundant catch-up.
     */
    private suspend fun shrinkCoveredScope(context: StreamContext) {
        val row = context.cursor(name) ?: return
        val stored = decodeIssuesCursor(row.cursor)
        if (stored.watermarkAt == null) return
        val normalized = stored.normalizedFor(projectKeys, backfillFrom)
        val covered = normalized.coveredProjectKeys.orEmpty().filter { it in projectKeys }
        if (covered == stored.coveredProjectKeys) return
        context.putCursor(
            name,
            ISSUES_CURSOR_JSON.encodeToString(normalized.copy(coveredProjectKeys = covered)),
            watermarkAt = row.watermarkAt,
            lastCompletedAt = row.lastCompletedAt,
        )
    }

    private fun runsCurrentScope(cursor: IssuesCursor): Boolean =
        cursor.runProjectKeys.sameKeysAs(projectKeys) && cursor.runBackfillFrom == backfillFrom

    /**
     * A new run over the configured scope. With no watermark (the very first run) one clause reaches
     * back to `backfillFrom`. Otherwise the projects the watermark already covers ("retained") are
     * searched since the watermark plus the overlap — or since the new `backfillFrom` when it moved
     * EARLIER than the covered one — while projects it does not cover ("added", incl. a re-added one,
     * whose tombstoned rows `upsertIssue` resurrects) are searched since `backfillFrom`. Narrowing
     * (fewer projects, a later `backfillFrom`) needs no search of its own: the watermark stays and the
     * covered scope shrinks to the configured one when the run completes. [prior] carries the watermark
     * and the covered scope through unchanged.
     */
    private fun freshRun(context: StreamContext, prior: IssuesCursor?): IssuesCursor {
        val now = context.clock()
        val watermarkAt = prior?.watermarkAt
        val sinceBackfill = minutesBetween(backfillFromEpochMillis, now)
        val clauses = if (prior == null || watermarkAt == null) {
            listOf(JiraJql.Clause(projectKeys, sinceBackfill))
        } else {
            val added = prior.uncoveredKeys(projectKeys)
            val retainedMinutes = if (prior.backfillMovedEarlier(backfillFromEpochMillis)) {
                sinceBackfill
            } else {
                minutesBetween(watermarkAt, now) + incrementalOverlapMinutes
            }
            listOf(
                JiraJql.Clause(projectKeys.filter { it !in added }, retainedMinutes),
                JiraJql.Clause(added, sinceBackfill),
            ).filter { it.projectKeys.isNotEmpty() }
        }
        return IssuesCursor(
            watermarkAt = watermarkAt,
            jql = JiraJql.incremental(clauses),
            nextPageToken = null,
            runStartedAt = now,
            coveredProjectKeys = prior?.coveredProjectKeys,
            coveredBackfillFrom = prior?.coveredBackfillFrom,
            runProjectKeys = projectKeys,
            runBackfillFrom = backfillFrom,
        )
    }
}
