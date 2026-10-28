package ch.nokillswit.ingest

import org.slf4j.LoggerFactory

/**
 * A failed job's exception as logged: [message] (the bounded `<Class>: <message>` text `sync_jobs.error_detail` stores)
 * and every stack frame, but each CAUSE reduced to its class name. A transport cause's message can embed the full
 * request URL with its query string (Ktor's connect-timeout `IOException`, wrapped by `JiraFetchException`), which is
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

private val jobLog = LoggerFactory.getLogger(IngestWorker::class.java)

/**
 * The log line of a closed-failed job: INFO for a resumable clock stop (it is no failure), a WARN WITHOUT a stack trace for a trip
 * with no failure behind it (the clock), otherwise the WARN with the [LoggedFailure] of [reported].
 */
internal fun logJobFailure(claim: SyncJobClaim, written: FailedWrite, reported: Exception, trip: CeilingTripException?, detail: String) {
    when {
        written.resumable ->
            jobLog.info("Sync job {} ({}) of data source {} hit its wall clock after storing pages", claim.id, claim.kind,
                claim.connectionId)
        trip != null && trip.underlying == null ->
            jobLog.warn("Sync job {} ({}) for data source {} failed: {}", claim.id, claim.kind, claim.connectionId, detail)
        else -> {
            val failure = LoggedFailure(reported, detail)
            jobLog.warn("Sync job {} ({}) for data source {} failed", claim.id, claim.kind, claim.connectionId, failure)
        }
    }
}
