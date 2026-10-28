package ch.nokillswit.ingest

import ch.nokillswit.audit.audit
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.ingest.SyncPause")

/**
 * The loud half of a pause (`.claude/docs/ingestion.md` "Ceiling trips and the pause"), run by the worker once the failing job's
 * transaction - which started the pause - has committed: one ERROR line and the `data_source.paused` audit event. Both carry ids,
 * numbers and the constant trip code only, never exception text or host text (the code is one of [LIMIT_EXCEEDED_TRIP] /
 * [TIME_EXCEEDED_TRIP], the figures are the counter and its threshold).
 */
internal fun announcePause(claim: SyncJobClaim, paused: PauseSnapshot, pauseAfterTrips: Int) {
    log.error(
        "Data source {} paused: its SYNC stopped at a ceiling ({}) without storing a page {} time(s) in a row (pause after {}); " +
            "scheduled SYNC stays off until an ADMIN runs Sync now or saves the connection",
        claim.connectionId, paused.reason, paused.limitTrips, pauseAfterTrips,
    )
    audit(
        "data_source.paused",
        "dataSourceId" to claim.connectionId.toLong(),
        "jobId" to claim.id.toLong(),
        "reason" to paused.reason,
        "limitTrips" to paused.limitTrips,
    )
}

/**
 * `data_source.resumed`: an ADMIN lifted a pause - [via] is `SYNC_NOW` (the manual SYNC request) or `UPDATE` (the config PUT) -
 * naming what it held (the trip code and the count it had reached).
 */
internal fun auditResumed(byUserId: UInt, dataSourceId: UInt, via: String, lifted: PauseSnapshot) {
    audit(
        "data_source.resumed",
        "byUserId" to byUserId.toLong(),
        "dataSourceId" to dataSourceId.toLong(),
        "via" to via,
        "reason" to lifted.reason,
        "limitTrips" to lifted.limitTrips,
    )
}
