package ch.nokillswit.jira

import io.r2dbc.spi.R2dbcException
import org.slf4j.Logger

/**
 * Exposed's own text for a value longer than its `varchar(n)` column. Exposed enforces the length
 * CLIENT-side (`VarCharColumnType.validateValueBeforeUpdate` throws an [IllegalArgumentException]
 * before any SQL is sent), so such a row never produces a PostgreSQL SQLSTATE. The message is
 * Exposed's, not ours: `JiraProcessFailureClassTest` (against real Exposed) and `NormalizationPipelineTest`'s
 * over-long-issue-key test pin it, so an Exposed upgrade that rewords it goes red instead of silently
 * reclassifying bad rows as outages.
 */
private const val EXPOSED_VARCHAR_TOO_LONG = "Value can't be stored to database column because exceeds length"

/**
 * True when [this] (or any cause) is a bad VALUE: a PostgreSQL data exception (SQLSTATE class 22 — value too
 * long, NUL, out of range …) or Exposed's client-side `varchar(n)` length rejection (an [IllegalArgumentException]
 * whose message starts with [EXPOSED_VARCHAR_TOO_LONG] — deliberately NOT every [IllegalArgumentException], which
 * would mask programming errors as bad rows). Retrying the same value cannot succeed. This is the narrow class
 * the wholesale reference rebuild tolerates: an integrity violation there is the writer's own bug.
 */
internal fun Throwable.isBadValueError(): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if ((cur as? R2dbcException)?.sqlState?.startsWith("22") == true) return true
        if (cur is IllegalArgumentException && cur.message?.startsWith(EXPOSED_VARCHAR_TOO_LONG) == true) return true
        cur = cur.cause
    }
    return false
}

/**
 * True when [this] (or any cause) is a bad ROW: a [bad value][isBadValueError] or an integrity violation
 * (SQLSTATE class 23 — PK/unique/FK/NOT NULL/CHECK) on a PER-ISSUE write, where another issue's row or stale
 * `norm` state can legitimately collide. Retrying the same row cannot succeed, so it must not be mistaken for
 * an outage.
 */
internal fun Throwable.isDataError(): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if ((cur as? R2dbcException)?.sqlState?.startsWith("23") == true) return true
        cur = cur.cause
    }
    return isBadValueError()
}

/**
 * A one-line description of [this] that is safe to log: the exception class and the first SQLSTATE found along the cause
 * chain, never a message (a database exception's message can quote the offending value, i.e. a piece of a Jira payload).
 */
internal fun Throwable.describe(): String {
    var cur: Throwable? = this
    while (cur != null) {
        (cur as? R2dbcException)?.sqlState?.let { return "${javaClass.simpleName}, SQLSTATE $it" }
        if (cur is IllegalArgumentException && cur.message?.startsWith(EXPOSED_VARCHAR_TOO_LONG) == true) {
            return "${javaClass.simpleName}, client-side length/value check"
        }
        cur = cur.cause
    }
    return javaClass.simpleName
}

/** How much of a skipped issue's key a WARN carries. */
private const val MAX_LOGGED_KEY_LENGTH = 40

/**
 * The ONE "skip, loudly" log line of an issue a stream left out because the database rejected its value
 * (`.claude/docs/ingestion.md` "ISSUES: a bad issue is skipped, loudly"): [stream] names who skipped, the key is Jira text so it is
 * cut and stripped of control characters (no log injection), and the payload and the exception message are never logged.
 */
internal fun Logger.warnSkippedIssue(stream: String, connectionId: UInt, issueId: Long, issueKey: String, failure: Throwable) {
    val safeKey = issueKey.take(MAX_LOGGED_KEY_LENGTH).map { if (it.isISOControl()) '?' else it }.joinToString("")
    warn("{} skipped issue {} (id {}) of data source {}: {}", stream, safeKey, issueId, connectionId, failure.describe())
}
