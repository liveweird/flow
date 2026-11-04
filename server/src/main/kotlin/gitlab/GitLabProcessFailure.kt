package ch.nokillswit.gitlab

import io.r2dbc.spi.R2dbcException
import kotlinx.serialization.SerializationException

/** A failed page names at most this many raw keys (project ids, shas, merge request ids: never a title, email, path or name). */
internal const val MAX_LOGGED_KEYS = 20

/**
 * A PROCESS failure as it leaves the stream: the [stage], at most [MAX_LOGGED_KEYS] raw keys and the CLASS of the cause - never the cause's
 * message, because a database error quotes the bound values of its statement (an address, a title) and `sync_jobs.error_detail` stores the
 * top message. The original is the `cause`: the job's log reduces every cause to its class name (`ingest/LoggedFailure.kt`) and keeps the
 * stack.
 */
internal class ProcessFailure(val stage: String, keys: List<String>, cause: Throwable) : RuntimeException(
    "PROCESS $stage failed" + keysText(keys) + ": " + cause.describe(),
    cause,
)

private fun keysText(keys: List<String>) = if (keys.isEmpty()) "" else " (${keys.size} key(s), first ${keys.take(MAX_LOGGED_KEYS)})"

/** The normalizer threw for a row of the page: a data fault of that page, not an outage. Its message is only the cause's class. */
internal class NormalizationFault(cause: Throwable) : RuntimeException(cause.javaClass.simpleName, cause)

/**
 * A row-level fault of one page: the normalizer threw, the database refused a row's value (SQLSTATE class 22 or 23), or a raw reader's
 * decoder could not read a stored value (a malformed JSON or id list: [SerializationException], [NumberFormatException]).
 */
internal fun Throwable.isRowFault(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is NormalizationFault || current is SerializationException || current is NumberFormatException) return true
        val state = (current as? R2dbcException)?.sqlState
        if (state != null && (state.startsWith("22") || state.startsWith("23"))) return true
        current = current.cause
    }
    return false
}

/** One safe line about [this]: the class (the normalizer fault unwrapped) and the first SQLSTATE along the chain, never a message. */
internal fun Throwable.describe(): String {
    val root = if (this is NormalizationFault) cause ?: this else this
    var current: Throwable? = this
    while (current != null) {
        (current as? R2dbcException)?.sqlState?.let { return "${root.javaClass.simpleName}, SQLSTATE $it" }
        current = current.cause
    }
    return root.javaClass.simpleName
}
