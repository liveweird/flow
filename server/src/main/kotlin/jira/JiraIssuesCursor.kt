package ch.nokillswit.jira

import ch.nokillswit.fetch.CALL_TALLY_KEYS
import kotlinx.serialization.Serializable

// The ISSUES stream's persisted cursor and the scope decisions read off it (split out of `JiraIssuesStream.kt`, which keeps the
// paging loop). RECONCILE's "has the scope been downloaded" guard uses `coversScope` too.

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

/** The progress counter (`sync_jobs.progress`, value 1) a SYNC carries when its ISSUES run is a first download or widens the scope. */
internal const val BACKFILL_PROGRESS_KEY = "backfill"

/**
 * The progress keys a retried attempt carries over from the previous one (`JiraConnector`): the call tally's four counters and the
 * ISSUES stream's `backfill` marker, none of which the retry would necessarily set again.
 */
internal val STICKY_PROGRESS_KEYS = CALL_TALLY_KEYS + BACKFILL_PROGRESS_KEY

internal fun decodeIssuesCursor(text: String): IssuesCursor = decodeCursor(text)

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

/** Whether two project-key lists name the same set (order and absence-vs-empty ignored). */
internal fun List<String>?.sameKeysAs(other: List<String>?): Boolean = this.orEmpty().toSet() == other.orEmpty().toSet()
