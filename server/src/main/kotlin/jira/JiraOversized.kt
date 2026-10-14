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
