package ch.nokillswit.fetch

// The shared pieces of an answer to a response over the byte cap (`.claude/docs/jira-integration.md` "Oversized changelog/worklog
// batch": re-ask the SAME batch at half the size, give up on a single id that still does not fit) and the answer/run ceilings of
// the loops that follow an upstream's paging. Every failure is a `FetchException` of the caller's [FetchSource].

/** The `FetchException` code of a response, an answer or a run past a ceiling; never retried by `SourceHttp`. */
internal const val LIMIT_EXCEEDED_CODE = "LIMIT_EXCEEDED"

/** True when this fetch failed because the response body (or an answer, or a run) was over a ceiling: `LIMIT_EXCEEDED`. */
internal fun FetchException.isOversized(): Boolean = code == LIMIT_EXCEEDED_CODE

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
internal fun answerCeilingExceeded(source: FetchSource, endpoint: String, what: String) =
    FetchException(source, LIMIT_EXCEEDED_CODE, null, endpoint, "answer ceiling: $what")

/**
 * The page ceiling alone, for a loop that follows a host's paging and holds nothing (or something else bounds what it holds):
 * call [next] before fetching each page and the [maxPages]+1th throws `LIMIT_EXCEEDED`. One object per answer (or per run, for a
 * feed), never shared across loops.
 */
internal class PageCeiling(
    private val source: FetchSource,
    private val endpoint: String,
    private val maxPages: Int,
    private val what: String = "pages",
) {
    private var pages = 0

    fun next() {
        if (++pages > maxPages) throw answerCeilingExceeded(source, endpoint, "more than $maxPages $what")
    }
}

/**
 * One answer's items as they will be stored, within an [AnswerCeiling]: [nextPage] before each page, [add] with each item and the
 * characters of the payload it will store. Anything past a ceiling throws `LIMIT_EXCEEDED` (never a silent truncation), so a loop
 * adopts the lever with one construction, one [nextPage] and one [add]. [unit] names the items in the failure ("histories").
 */
internal class BoundedAnswer<T>(
    private val source: FetchSource,
    private val endpoint: String,
    private val ceiling: AnswerCeiling,
    private val unit: String,
) {
    val items = mutableListOf<T>()
    private val pages = PageCeiling(source, endpoint, ceiling.maxPages)
    private var chars = 0L

    /** Call before fetching each page. */
    fun nextPage() = pages.next()

    fun add(item: T, itemChars: Int) {
        chars += itemChars
        if (chars > ceiling.maxChars) throw answerCeilingExceeded(source, endpoint, "more than ${ceiling.maxChars} characters")
        if (items.size >= ceiling.maxItems) throw answerCeilingExceeded(source, endpoint, "more than ${ceiling.maxItems} $unit")
        items += item
    }
}

/**
 * The ceilings on ONE run of a token-paged stream that holds nothing between pages but writes every page (ISSUES, RECONCILE):
 * the pages followed ([maxPages], for an endless or cycling `nextPageToken`), the items written ([maxItems], rows of
 * a stream's raw tables) and the bytes the pages carried on the wire in all ([maxBytes]: what `SourceHttp` read,
 * whitespace and unused members included, so no padding or hidden bulk is free; it bounds the volume read and parsed whatever the
 * items' count). Sized against the scope a real tenant has, with wide headroom (`.claude/docs/jira-integration.md` "Paging
 * ceilings"), and the same for every run: a configured page size does not move them. The small REFERENCE steps leave [maxBytes] at its
 * default, [UNBOUNDED_BYTES] (their pages are ~0.1 MB and the page and row ceilings carry them).
 */
internal data class RunCeiling(val maxPages: Int, val maxItems: Int, val maxBytes: Long = UNBOUNDED_BYTES)

/** A [RunCeiling] with no byte ceiling. */
internal const val UNBOUNDED_BYTES = Long.MAX_VALUE

/**
 * One run's work against a [RunCeiling]: [nextPage] before fetching each page, [add] with the items the fetched page carries (and
 * its wire bytes, where the ceiling has a byte figure), both BEFORE the page's transaction, so a trip leaves the cursor at the last
 * page that committed and the next run resumes from it. `LIMIT_EXCEEDED` past any; [unit] names the items in the failure ("issues").
 */
internal class RunBudget(
    private val source: FetchSource,
    private val endpoint: String,
    private val ceiling: RunCeiling,
    private val unit: String,
) {
    private val pages = PageCeiling(source, endpoint, ceiling.maxPages)
    private var items = 0L
    private var bytes = 0L

    fun nextPage() = pages.next()

    fun add(count: Int, wireBytes: Long = 0L) {
        items += count
        bytes += wireBytes
        if (items > ceiling.maxItems) throw answerCeilingExceeded(source, endpoint, "more than ${ceiling.maxItems} $unit")
        if (bytes > ceiling.maxBytes) throw answerCeilingExceeded(source, endpoint, "more than ${ceiling.maxBytes} bytes")
    }
}
