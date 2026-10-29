package ch.nokillswit.jira

import ch.nokillswit.infra.time.MILLIS_PER_MINUTE

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
 * One issue's whole worklog answer: 4 Mi characters of TRIMMED worklog text (~500 B each, so ~8k worklogs; an untrimmed Jira
 * worklog is ~2 KB; the largest real issue measured is ~1,200 worklogs / ~514 KiB stored, so this is 8x that), 25,000 worklogs (20x;
 * their flush rows are counted by [batchBytes]) and 200 pages (an endless `total`). Sized with the backfill's other bounds against the
 * worker heap
 * ([retentionChars], `JiraHeapFit.kt`'s `worklogTextBytes`): `fetchAheadInOrder` keeps up to `jira.maxConcurrentRequests` (4) issues in
 * flight plus the batch it is flushing, so 5 x 4 Mi + [BACKFILL_FLUSH_CHARS] = 24 Mi characters, 48 MiB as UTF-16; the page TREES are not
 * held beside it (each page is converted to trimmed text under the big-parse lock, `JiraClient.issueWorklogPageAs`), except the one being
 * converted. (It was 8 Mi characters and an 8 Mi flush bound: 48 Mi characters, 96 MiB as UTF-16, which with the 112 MiB tree no longer
 * fitted a 448 MiB old generation beside a PROCESS slot.)
 */
internal val BACKFILL_ANSWER_CEILING = AnswerCeiling(maxChars = 4L * 1024 * 1024, maxItems = 25_000, maxPages = 200)

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

/** The backfill batch flushes early at this many worklogs, so a batch of long-lived tickets stays bounded. */
internal const val BACKFILL_FLUSH_WORKLOGS = 5_000

/** The backfill batch flushes early at this many characters of stored text, beside the [BACKFILL_FLUSH_WORKLOGS] count. */
internal const val BACKFILL_FLUSH_CHARS = 4 * 1024 * 1024

/**
 * The most worklog rows one `INSERT` group carries (`JiraRawBatchWrites.upsertWorklogs`): a power of two, so a full group is ONE
 * statement of a size the statement cache already holds (`payloadChunkSizes`), well under the bind-parameter cap (3,555 rows of 9 columns).
 */
internal const val WORKLOG_INSERT_GROUP_ROWS = 1_024

/** The most characters of worklog text one `INSERT` group carries (a row alone over it is a group of its own): ~1,000 real worklogs. */
internal const val WORKLOG_INSERT_GROUP_CHARS = 512 * 1024

/**
 * Heap bytes ONE worklog row costs while its statement is assembled and sent, beyond the text: measured on the perf stack at 25,000
 * rows of 149 characters in `upsertWorklogs` (build-times WHY 51): 1,390 B beyond the held input while the rows were built, ~1,690 B
 * while their statement was being encoded (the `InsertRow` map ~560, boxed numbers ~180, a `Pair` and a `Prepared`, the canonical
 * text ~224, r2dbc's encoding ~150-250). Rounded up; it includes a short payload's own text.
 */
internal const val WORKLOG_STATEMENT_ROW_BYTES = 1_700L

/**
 * Heap bytes per CHARACTER of a group's text on top of the row figure: the canonical text (UTF-16 worst case, 2 B) and its encoding
 * onto the wire (UTF-8, up to 3 B a character).
 */
internal const val WORKLOG_STATEMENT_CHAR_BYTES = 5L

/**
 * Heap bytes ONE row of the flushing batch keeps for the whole flush, beyond its text: the `Prepared` (its hash `String` ~104 B,
 * the object ~32, the boxed key ~16) and the bookkeeping `diffBatch` keeps per key (a `HashSet` entry, a boxed index, the outcome
 * slot). ESTIMATED from the object layouts, not measured; the canonical text is not kept (rendered again per group).
 */
internal const val WORKLOG_BATCH_HELD_ROW_BYTES = 256L

/** This list cut, in order, into groups of at most [maxRows] rows and [maxChars] characters ([chars] each); a row over it is alone. */
internal fun <T> List<T>.insertGroups(maxRows: Int, maxChars: Int, chars: (T) -> Int): Sequence<List<T>> = sequence {
    var start = 0
    while (start < size) {
        var end = start
        var used = 0L
        while (end < size && end - start < maxRows && (end == start || used + chars(this@insertGroups[end]) <= maxChars)) {
            used += chars(this@insertGroups[end])
            end++
        }
        yield(subList(start, end))
        start = end
    }
}

/**
 * The most characters of worklog text the WORKLOGS stream can hold at once: the backfill's [window] issues being fetched ahead and
 * the one being settled into the batch (`window + 1` answers of [AnswerCeiling.maxChars]) plus the batch's flush bound, or one feed
 * page's `worklog/list` answer - whichever is larger. The boot-time heap fit counts it at 2 bytes a character, beside a response tree.
 */
internal fun WorklogCeilings.retentionChars(window: Int): Long =
    maxOf((window + 1L) * backfill.maxChars + BACKFILL_FLUSH_CHARS, feedAnswer.maxChars)

/**
 * What the WORKLOGS flush adds to a slot beyond the text its batch and fetched-ahead answers hold ([retentionChars]): the batch is the
 * unflushed worklogs (up to [BACKFILL_FLUSH_WORKLOGS] - 1) plus one whole answer ([AnswerCeiling.maxItems]), each keeping
 * [WORKLOG_BATCH_HELD_ROW_BYTES] for the flush, plus the ONE insert group in flight: [WORKLOG_INSERT_GROUP_ROWS] rows at
 * [WORKLOG_STATEMENT_ROW_BYTES] and [WORKLOG_INSERT_GROUP_CHARS] characters at [WORKLOG_STATEMENT_CHAR_BYTES]. (It was the whole
 * batch's rows at ~1.7 KB, ~48 MiB at the ceiling, not counted at all.) The feed's page (at most [MAX_FEED_PAGE_ENTRIES]) is smaller.
 */
internal fun WorklogCeilings.batchBytes(): Long =
    (BACKFILL_FLUSH_WORKLOGS - 1L + backfill.maxItems) * WORKLOG_BATCH_HELD_ROW_BYTES +
        WORKLOG_INSERT_GROUP_ROWS * WORKLOG_STATEMENT_ROW_BYTES + WORKLOG_INSERT_GROUP_CHARS * WORKLOG_STATEMENT_CHAR_BYTES

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
