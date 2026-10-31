package ch.nokillswit.jira

import ch.nokillswit.fetch.FetchException
import ch.nokillswit.fetch.LIMIT_EXCEEDED_CODE

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
            throw FetchException(
                JIRA_SOURCE,
                LIMIT_EXCEEDED_CODE,
                null,
                null,
                "$stream skipped $skipped of $attempted $unit over jira.maxResponseBytes, its node or depth ceiling " +
                    "or an answer ceiling - the WARNs name which",
            )
        }
    }
}
