package ch.nokillswit.ingest

import ch.nokillswit.audit.audit
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.ingest.SyncPause")

/**
 * The loud half of a pause (`.claude/docs/ingestion.md` "Ceiling trips and the pause"), run by the worker once the failing job's
 * transaction - which started the pause - has committed: one ERROR line and the `data_source.paused` audit event. Both carry ids,
 * numbers and the constant trip code only, never exception text or host text (the code is one of [LIMIT_EXCEEDED_TRIP] /
 * [TIME_EXCEEDED_TRIP], the figures are the counter and its threshold). [PauseSnapshot.job] says which job's pause it is (SYNC or
 * RECONCILE: one event name for both, told apart by the `job` field, so a pause alert needs one rule).
 */
internal fun announcePause(claim: SyncJobClaim, paused: PauseSnapshot, pauseAfterTrips: Int) {
    val job = paused.job
    if (job == SyncJobKind.RECONCILE) {
        log.error(
            "Data source {} paused: its RECONCILE stopped at a ceiling ({}) {} time(s) in a row (pause after {}); " +
                "scheduled RECONCILE stays off until an ADMIN runs Reconcile now or saves the connection",
            claim.connectionId, paused.reason, paused.limitTrips, pauseAfterTrips,
        )
    } else {
        log.error(
            "Data source {} paused: its SYNC stopped at a ceiling ({}) without storing a page {} time(s) in a row (pause after {}); " +
                "scheduled SYNC stays off until an ADMIN runs Sync now or saves the connection",
            claim.connectionId, paused.reason, paused.limitTrips, pauseAfterTrips,
        )
    }
    audit(
        "data_source.paused",
        "dataSourceId" to claim.connectionId.toLong(),
        "jobId" to claim.id.toLong(),
        "job" to job.name,
        "reason" to paused.reason,
        "limitTrips" to paused.limitTrips,
    )
}

/**
 * `data_source.resumed`: an ADMIN lifted a pause - [via] is `SYNC_NOW` (the manual SYNC request), `RECONCILE_NOW` (the manual RECONCILE
 * request) or `UPDATE` (the config PUT, one event per pause it lifted) - naming which job's pause ([PauseSnapshot.job]) and what it
 * held (the trip code and the count it had reached).
 */
internal fun auditResumed(byUserId: UInt, dataSourceId: UInt, via: String, lifted: PauseSnapshot) {
    audit(
        "data_source.resumed",
        "byUserId" to byUserId.toLong(),
        "dataSourceId" to dataSourceId.toLong(),
        "via" to via,
        "job" to lifted.job.name,
        "reason" to lifted.reason,
        "limitTrips" to lifted.limitTrips,
    )
}
