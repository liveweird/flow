package ch.nokillswit.ingest

import ch.nokillswit.infra.json.replaceNul
import org.slf4j.LoggerFactory

/**
 * A failed job's exception as logged: [message] (the bounded `<Class>: <message>` text `sync_jobs.error_detail` stores)
 * and every stack frame, but each CAUSE reduced to its class name. A transport cause's message can embed the full
 * request URL with its query string (Ktor's connect-timeout `IOException`, wrapped by `FetchException`), which is
 * never logged (`.claude/docs/jira-integration.md`); the top message is bounded because a database exception's message
 * inlines every bound value of its statement (a page of whole issue documents). At most [MAX_LOGGED_CAUSES] causes.
 */
internal class LoggedFailure(failure: Throwable, message: String, depth: Int = 0) : RuntimeException(
    message,
    failure.cause?.takeIf { depth < MAX_LOGGED_CAUSES }?.let { LoggedFailure(it, it.javaClass.name, depth + 1) },
    false,
    true,
) {
    init {
        stackTrace = failure.stackTrace
    }
}

private const val MAX_LOGGED_CAUSES = 10

private const val MAX_ERROR_DETAIL_LENGTH = 1000

/**
 * `sync_jobs.error_detail` for [failure]: `<ExceptionClass>: <message>`, bounded. A NUL in the message (a database exception inlines the
 * offending value) cannot be stored in `text`: the failure write would fail with 22021 and the job hang RUNNING until its lease expired.
 * Control characters go too (no forged log/status line).
 */
internal fun failureDetail(failure: Exception): String =
    "${failure.javaClass.simpleName}: ${failure.message}".replaceNul().map { if (it.isISOControl()) '?' else it }
        .joinToString("").take(MAX_ERROR_DETAIL_LENGTH)

private val jobLog = LoggerFactory.getLogger(IngestWorker::class.java)

/**
 * The log line of a closed-failed job: INFO for a resumable clock stop (it is no failure; it says whether the stop was graceful, between
 * pages, or the hard stop that cancelled the run in flight), a WARN WITHOUT a stack trace for a trip with no failure behind it (the
 * clock; its [detail] says which stop too), otherwise the WARN with the [LoggedFailure] of [reported].
 */
internal fun logJobFailure(claim: SyncJobClaim, written: FailedWrite, reported: Exception, trip: CeilingTripException?, detail: String) {
    when {
        written.resumable ->
            jobLog.info("Sync job {} ({}) of data source {} hit its wall clock after storing pages ({})", claim.id, claim.kind,
                claim.connectionId, if (trip?.hardStop == true) "hard stop: cancelled in flight" else "stopped between pages")
        trip != null && trip.underlying == null ->
            jobLog.warn("Sync job {} ({}) for data source {} failed: {}", claim.id, claim.kind, claim.connectionId, detail)
        else -> {
            val failure = LoggedFailure(reported, detail)
            jobLog.warn("Sync job {} ({}) for data source {} failed", claim.id, claim.kind, claim.connectionId, failure)
        }
    }
}
