package ch.nokillswit.fetch

import io.ktor.http.Headers

// What distinguishes one upstream from another for the shared transport (`SourceHttp`) and the failures and ceilings built around
// it: the name its messages use, the setting that caps its responses, and how it signals "slow down". Nothing here knows a
// connector; each connector package owns its one `FetchSource` constant.

/**
 * How an upstream asks for a breather on a response that SUCCEEDED (a 429 is the transport's own, retried with backoff). Given the
 * response [headers], [pauseMillisAfter] answers the pause the call takes before it reads the body, or null for none. The transport
 * sleeps it holding its permit and counts it in the sync job's tally ([CallTally]).
 */
fun interface RateLimitSignal {
    fun pauseMillisAfter(headers: Headers): Long?

    companion object {
        /** An upstream with no such signal. */
        val NONE = RateLimitSignal { null }
    }
}

/**
 * One upstream as the shared fetch machinery names it. [label] opens every failure message ("Jira fetch failed: ...") and the
 * operator log lines ("A Jira response was refused ..."); [maxResponseBytesSetting] is the configuration key the half-cap warning
 * tells the operator to raise; [rateLimitSignal] is the upstream's own pause rule.
 */
class FetchSource(
    val label: String,
    val maxResponseBytesSetting: String,
    val rateLimitSignal: RateLimitSignal = RateLimitSignal.NONE,
)

/**
 * A failed upstream call, classified into one of the plan's §6 codes. [status]/[endpoint] are for
 * logging/diagnostics only - never render [endpoint] with a query string (it may embed a token). [detail] is a short,
 * secret-free operator hint appended to the message (the oversized-skip threshold's "skipped N of M" line).
 */
class FetchException(source: FetchSource, val code: String, val status: Int? = null, val endpoint: String? = null, detail: String? = null) :
    RuntimeException(
        buildString {
            append(source.label).append(" fetch failed: ").append(code)
            status?.let { append(" (").append(it).append(')') }
            endpoint?.let { append(" @ ").append(it) }
            detail?.let { append(": ").append(it) }
        },
    )
