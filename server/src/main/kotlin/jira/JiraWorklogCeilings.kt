package ch.nokillswit.jira

import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.format.DateTimeParseException

/**
 * The WORKLOGS stream's answer ceilings (`.claude/docs/jira-integration.md` "Worklog answer ceilings"): what a compromised or
 * buggy host behind the allow-list can make the stream hold or follow, bounded the way CHANGELOGS' answer is ([BoundedAnswer]).
 * [backfill] bounds ONE issue's `GET /issue/{id}/worklog` chain; [feedAnswer] one `worklog/updated` page's `worklog/list` details,
 * whose [AnswerCeiling.maxItems] is also the most entries one `updated`/`deleted` page may carry; [maxFeedPages] the pages one
 * run follows through either feed. A constructor parameter of [JiraWorklogStream], so a test can use small figures.
 */
internal data class WorklogCeilings(
    val backfill: AnswerCeiling = BACKFILL_ANSWER_CEILING,
    val feedAnswer: AnswerCeiling = FEED_ANSWER_CEILING,
    val maxFeedPages: Int = MAX_FEED_PAGES_PER_RUN,
)

/**
 * One issue's whole worklog answer: 8 Mi characters of TRIMMED worklog text (~500 B each, so ~16k worklogs; an untrimmed Jira
 * worklog is ~2 KB), 25,000 worklogs (~700 B of row objects each) and 200 pages (an endless `total`). Sized with the backfill's
 * other bounds against the worker heap: 4 issues in flight (`fetchAheadInOrder`'s window) x 8 Mi chars + the flush bound
 * ([BACKFILL_FLUSH_CHARS]) + one issue = ~48 Mi chars, up to ~96 MiB as UTF-16, the CHANGELOGS order of magnitude.
 */
internal val BACKFILL_ANSWER_CEILING = AnswerCeiling(maxChars = 8L * 1024 * 1024, maxItems = 25_000, maxPages = 200)

/**
 * One feed page's `worklog/list` details: Jira documents at most 1,000 entries per page; 2,000 leaves headroom, and the details
 * cannot legitimately outnumber the ids asked. A page is read once, sequentially, so 8 Mi characters of trimmed text is ample
 * (2,000 worklogs x ~500 B). The `worklog/list` calls are not counted against [AnswerCeiling.maxPages]: each asks at least one
 * of the page's ids (at most [MAX_FEED_PAGE_ENTRIES]) plus the few halvings of the byte-cap path, so the entries bound them.
 */
internal const val MAX_FEED_PAGE_ENTRIES = 2_000
internal val FEED_ANSWER_CEILING =
    AnswerCeiling(maxChars = 8L * 1024 * 1024, maxItems = MAX_FEED_PAGE_ENTRIES, maxPages = Int.MAX_VALUE)

/**
 * The pages one run follows through `worklog/updated`, and again through `worklog/deleted`: 300 x at most [MAX_FEED_PAGE_ENTRIES]
 * entries (~1,000 in practice, so ~300k worklogs) per run, far past a real catch-up. Every page commits its own cursor, so a run that
 * stops here (`LIMIT_EXCEEDED`) loses nothing and the next run resumes where it stopped.
 */
internal const val MAX_FEED_PAGES_PER_RUN = 300

/** How far ahead of Flow's own clock a feed page's `until` may be (Jira's clock running a little fast); anything later is refused. */
internal const val FEED_CLOCK_SKEW_MILLIS = 5L * MILLIS_PER_MINUTE

/** The backfill batch flushes early at this many characters of stored text, beside the 5,000-worklog count. */
internal const val BACKFILL_FLUSH_CHARS = 8 * 1024 * 1024

/** `GET /rest/api/3/worklog/updated` and `/deleted`, as named in a failure (never a URL). */
internal const val WORKLOG_UPDATED_ENDPOINT = "/rest/api/3/worklog/updated"
internal const val WORKLOG_DELETED_ENDPOINT = "/rest/api/3/worklog/deleted"
internal const val WORKLOG_LIST_ENDPOINT = "/rest/api/3/worklog/list"
internal const val ISSUE_WORKLOG_ENDPOINT = "/rest/api/3/issue/{id}/worklog"

/**
 * Refuses a feed page that cannot be true, BEFORE anything of it is stored or its cursor persisted, so a lying host fails the job
 * visibly instead of silencing or looping the feed: more than [maxEntries] entries (`LIMIT_EXCEEDED`); an `until` later than
 * [now] plus [FEED_CLOCK_SKEW_MILLIS] (`INVALID_RESPONSE`: persisted, it would make every later run ask a `since` in the future and
 * read nothing); a page that says another follows but whose `until` does not move past [since] (`INVALID_RESPONSE`: the same call
 * would be re-asked forever).
 */
internal fun JiraWorklogIdsPage.requirePlausible(endpoint: String, since: Long, now: Long, maxEntries: Int) {
    if (values.size > maxEntries) throw answerCeilingExceeded(endpoint, "a feed page of ${values.size} entries, more than $maxEntries")
    if (until > now + FEED_CLOCK_SKEW_MILLIS) {
        val detail = "the feed's until ($until) is later than now + ${FEED_CLOCK_SKEW_MILLIS}ms"
        throw JiraFetchException("INVALID_RESPONSE", null, endpoint, detail)
    }
    if (!lastPage && until <= since) {
        val detail = "the feed has a next page but its until ($until) did not pass since ($since)"
        throw JiraFetchException("INVALID_RESPONSE", null, endpoint, detail)
    }
}

/**
 * The cursor a feed page leaves: its `until`, but NEVER behind [since] - a last page that answers an `until` of 0 (a missing field
 * decodes to 0), a negative one or one before [since] would otherwise rewind the cursor and make the next run re-read the
 * instance-wide feed from there. A non-last page already moved on ([requirePlausible]).
 */
internal fun JiraWorklogIdsPage.advancedSince(since: Long): Long = maxOf(since, until)

/** A worklog field the host sent that cannot be used. The text is constant: no host-supplied value reaches `error_detail` or a log. */
private fun invalidWorklog(endpoint: String, field: String) =
    JiraFetchException("INVALID_RESPONSE", null, endpoint, "a worklog without a usable $field")

/** A worklog's numeric string/number field ([key]: `id`, `issueId`), else `INVALID_RESPONSE`. */
internal fun JsonObject.worklogLong(key: String, endpoint: String): Long =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: throw invalidWorklog(endpoint, key)

/** A worklog's `updated` as epoch millis, else `INVALID_RESPONSE`. */
internal fun JsonObject.worklogUpdatedMillis(endpoint: String): Long {
    val text = (this["updated"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: throw invalidWorklog(endpoint, "updated")
    return try {
        parseJiraInstantEpochMillis(text)
    } catch (_: DateTimeParseException) {
        throw invalidWorklog(endpoint, "updated")
    }
}
