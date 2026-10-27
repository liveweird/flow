package ch.nokillswit.jira

/**
 * The shared pieces of the CHANGELOGS/WORKLOGS answer to a response over `jira.maxResponseBytes`
 * (`.claude/docs/jira-integration.md` "Oversized changelog/worklog batch"): re-ask the SAME batch at half the size, and
 * give up on a single id that still does not fit.
 */
internal const val LIMIT_EXCEEDED_CODE = "LIMIT_EXCEEDED"

/** True when this fetch failed because the response body was over `jira.maxResponseBytes` (never retried by `JiraHttp`). */
internal fun JiraFetchException.isOversized(): Boolean = code == LIMIT_EXCEEDED_CODE

/**
 * The batch size to ask next after a [attempted]-id batch was over the cap while the run's size was [current]:
 * half of whichever is smaller (a short final batch must not waste halvings on a size it never reached), floor 1.
 */
internal fun halvedBatchSize(current: Int, attempted: Int): Int = maxOf(1, minOf(current, attempted) / 2)

/**
 * The ceilings on ONE answer a stream holds until its transaction (`jira.maxResponseBytes` bounds each PAGE only): the stored
 * text kept ([maxChars], as the characters of the payloads it will store), the rows ([maxItems]; each costs ~700 B of objects on
 * the way into Postgres, whatever its text) and the pages followed ([maxPages], for an endless `nextPageToken`, `total` or
 * `lastPage`). Sized against the worker heap, never against `jira.maxResponseBytes`: a configured cap does not move them.
 */
internal data class AnswerCeiling(val maxChars: Long, val maxItems: Int, val maxPages: Int)

/** The typed failure of every answer ceiling: `LIMIT_EXCEEDED`, so it takes the oversized path (halve, skip, or end the stream). */
internal fun answerCeilingExceeded(endpoint: String, what: String) =
    JiraFetchException(LIMIT_EXCEEDED_CODE, null, endpoint, "answer ceiling: $what")

/**
 * The page ceiling alone, for a loop that follows a host's paging and holds nothing (or something else bounds what it holds):
 * call [next] before fetching each page and the [maxPages]+1th throws `LIMIT_EXCEEDED`. One object per answer (or per run, for a
 * feed), never shared across loops.
 */
internal class PageCeiling(private val endpoint: String, private val maxPages: Int, private val what: String = "pages") {
    private var pages = 0

    fun next() {
        if (++pages > maxPages) throw answerCeilingExceeded(endpoint, "more than $maxPages $what")
    }
}

/**
 * One answer's items as they will be stored, within an [AnswerCeiling]: [nextPage] before each page, [add] with each item and the
 * characters of the payload it will store. Anything past a ceiling throws `LIMIT_EXCEEDED` (never a silent truncation), so a loop
 * adopts the lever with one construction, one [nextPage] and one [add]. [unit] names the items in the failure ("histories").
 */
internal class BoundedAnswer<T>(
    private val endpoint: String,
    private val ceiling: AnswerCeiling,
    private val unit: String,
) {
    val items = mutableListOf<T>()
    private val pages = PageCeiling(endpoint, ceiling.maxPages)
    private var chars = 0L

    /** Call before fetching each page. */
    fun nextPage() = pages.next()

    fun add(item: T, itemChars: Int) {
        chars += itemChars
        if (chars > ceiling.maxChars) throw answerCeilingExceeded(endpoint, "more than ${ceiling.maxChars} characters")
        if (items.size >= ceiling.maxItems) throw answerCeilingExceeded(endpoint, "more than ${ceiling.maxItems} $unit")
        items += item
    }
}

/** The floor of the skip threshold: a stream may always skip this many ids before the ratio matters. */
private const val SKIP_ALLOWANCE_FLOOR = 10L

/** The ratio of the ids a stream attempted this run that it may skip, once past [SKIP_ALLOWANCE_FLOOR]: one tenth. */
private const val SKIP_ALLOWANCE_DIVISOR = 10L

/**
 * The per-run fail-safe on the single-id skips (`.claude/docs/jira-integration.md` "Oversized changelog/worklog batch"):
 * a stream that skips MORE than `max(10, 10% of the ids it attempted this run)` ids is not meeting isolated outliers but
 * a cap that is too small (every response oversized), so it stops with `LIMIT_EXCEEDED` instead of letting the job
 * SUCCEED having skipped everything. One budget per counted unit ([unit], e.g. "issues"), run-local like the batch size.
 * "Attempted" = [settled] (ids that finished, stored) + skipped, so a halving's repeated asks never count twice, and the
 * skip set a stream keeps (the claim-scan exclusion) can never grow past this threshold.
 */
internal class SkipBudget(private val stream: String, private val unit: String) {
    private var settled = 0L
    private var skipped = 0L

    /** [count] ids of this unit finished normally. */
    fun settled(count: Int = 1) {
        settled += count
    }

    /** One id skipped; throws the `LIMIT_EXCEEDED` failure of the stream once the threshold is passed. */
    fun skip() {
        skipped++
        val attempted = settled + skipped
        if (skipped > maxOf(SKIP_ALLOWANCE_FLOOR, attempted / SKIP_ALLOWANCE_DIVISOR)) {
            throw JiraFetchException(
                LIMIT_EXCEEDED_CODE,
                null,
                null,
                "$stream skipped $skipped of $attempted $unit over jira.maxResponseBytes or an answer ceiling - the WARNs name which",
            )
        }
    }
}
