package ch.nokillswit.ingest

import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.ingest.DataSourceService.Connections
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/*
 * The ceiling-trip pause (`.claude/docs/ingestion.md` "Ceiling trips and the pause", V37): a SYNC that stops at a bound on purpose (a
 * paging/run ceiling `LIMIT_EXCEEDED`, or the per-job wall clock `TIME_EXCEEDED`) and stored no CHANGE (the stream that tripped for a
 * limit, the whole run for the clock; [StreamContext.ceilingTrip]) counts one in `source_connections.limit_trips`;
 * `ingest.pauseAfterLimitTrips` of them in a row PAUSE the connection (`paused_at`, `pause_reason`), and the scheduler's SYNC scan skips
 * a paused connection (RECONCILE has its own pause, `ReconcilePause.kt`, and its own back-off). A succeeded SYNC resets the counter;
 * an ADMIN's "Sync now" or config PUT lifts the pause. A wall-clock stop AFTER stored changes is no failure at all
 * ([recordResumableStop]). All writes are extensions on the
 * caller's transaction, exactly like `ScheduleBackoff.kt`'s.
 */

/** The trip codes: `sync_jobs.error_code`, `last_sync_error_code` and `pause_reason` hold ONLY these (constants, never exception text). */
const val LIMIT_EXCEEDED_TRIP = "LIMIT_EXCEEDED"
const val TIME_EXCEEDED_TRIP = "TIME_EXCEEDED"

private const val TIME_EXCEEDED_MESSAGE =
    "job wall-clock ceiling (ingest.maxJobMinutes) reached between pages; the next run resumes from the cursors"
private const val HARD_STOP_MESSAGE =
    "job wall-clock ceiling (ingest.maxJobMinutes) plus its grace (ingest.hardStopGraceSeconds) elapsed mid-page; " +
        "the in-flight call was cancelled and the next run resumes from the cursors"

/**
 * A run that stopped at a ceiling instead of breaking (`jira/JiraConnector.kt` wraps a `LIMIT_EXCEEDED` failure in one;
 * [StreamContext.heartbeat] throws one when the job's wall clock is up). [madeProgress]: the run stored at least one CHANGE (a
 * [StreamContext.heartbeat] with `changed = true`, always AFTER a page's transaction, or [StreamContext.markChanged]) - in the
 * tripping stream for a `LIMIT_EXCEEDED`, in any stream of the run for `TIME_EXCEEDED` - so the next run resumes further on; a trip
 * without it would repeat identically (a page re-stored unchanged is not a change). [underlying] is the failure being reported (null
 * for the clock). [hardStop]: the `TIME_EXCEEDED` came from the HARD stop ([StreamContext.withHardStop]: the in-flight call or page was
 * cancelled once the budget plus its grace had elapsed), not from the graceful check between pages; the code stays the one
 * `TIME_EXCEEDED`, the message and the worker's log/audit say which.
 */
class CeilingTripException(
    val code: String,
    val madeProgress: Boolean,
    val underlying: Exception? = null,
    val hardStop: Boolean = false,
) : Exception(underlying?.message ?: if (hardStop) HARD_STOP_MESSAGE else TIME_EXCEEDED_MESSAGE, underlying)

/**
 * What a pause (or a counter) held: [pausedAt] and [reason] are null for a counter that never reached the threshold. [job] is the job
 * kind the pause stops: SYNC (`limit_trips`, this file) or RECONCILE (`reconcile_limit_trips`, `ReconcilePause.kt`).
 */
data class PauseSnapshot(val pausedAt: Long?, val reason: String?, val limitTrips: Int, val job: SyncJobKind)

/** [pausedNow]: the pause this trip started (null when it did not). [counted]: whether the trip raised its job's counter. */
class TripRecord(val limitTrips: Int, val counted: Boolean, val pausedNow: PauseSnapshot?)

/**
 * A SYNC stopped by its wall clock AFTER committing pages is a normal "continue next run", not a failure: the connection is rescheduled at
 * its ordinary interval (`next_sync_at = now + interval`) and `consecutive_failures` and `last_sync_error_code` stay as they were and the
 * trip counter restarts. Same fences as [recordFailureBackoff]: a job of an older `config_revision` writes nothing.
 */
internal suspend fun R2dbcTransaction.recordResumableStop(connectionId: UInt, now: Long, jobConfigRevision: Long) {
    val row = lockConnection(connectionId) ?: return
    if (row[Connections.configRevision] != jobConfigRevision) return
    val intervalMillis = row[Connections.syncIntervalMinutes].toLong() * MILLIS_PER_MINUTE
    Connections.update({ Connections.id eq connectionId }) {
        it[nextSyncAt] = now + intervalMillis
        it[limitTrips] = 0 // a trip with progress breaks the "in a row"
    }
}

/** A fenced [SyncJobsService.failTripped] that matched: the trip bookkeeping it did (null inside when the job was not a current SYNC). */
class FailedWrite(val trip: TripRecord?, val resumable: Boolean = false)

/** The row's pause state, or null when it is not paused. */
internal fun ResultRow.pauseSnapshot(): PauseSnapshot? =
    this[Connections.pausedAt]?.let { PauseSnapshot(it, this[Connections.pauseReason], this[Connections.limitTrips], SyncJobKind.SYNC) }

/**
 * A SYNC job ended in [trip]: counts it when it made no progress and pauses the connection when the count reaches
 * [pauseAfterTrips]. Runs in the transaction that closes the job (so no tick sees the job closed but the pause missing) and, like
 * [recordFailureBackoff], writes nothing for a job of an older `config_revision` ([jobConfigRevision]) - a run that started
 * before a config PUT must not re-impose the pause that PUT lifted. A trip that DID make progress is the normal
 * "continue next run": not counted, and it resets the count (the trips must be in a row).
 * Null = nothing recorded (the connection is gone or the job is stale).
 */
internal suspend fun R2dbcTransaction.recordCeilingTrip(
    connectionId: UInt,
    trip: CeilingTripException,
    pauseAfterTrips: Int,
    now: Long,
    jobConfigRevision: Long,
): TripRecord? {
    val row = lockConnection(connectionId) ?: return null
    if (row[Connections.configRevision] != jobConfigRevision) return null
    val before = row[Connections.limitTrips]
    if (trip.madeProgress) {
        // Progress breaks the "in a row": a still-advancing backfill cannot reach the pause through scattered no-progress trips.
        if (before > 0) Connections.update({ Connections.id eq connectionId }) { it[limitTrips] = 0 }
        return TripRecord(0, counted = false, pausedNow = null)
    }
    val after = before + 1
    val pauseNow = row[Connections.pausedAt] == null && after >= pauseAfterTrips
    Connections.update({ Connections.id eq connectionId }) {
        it[limitTrips] = after
        if (pauseNow) {
            it[pausedAt] = now
            it[pauseReason] = trip.code
        }
    }
    return TripRecord(after, counted = true, pausedNow = if (pauseNow) PauseSnapshot(now, trip.code, after, SyncJobKind.SYNC) else null)
}

/** Clears the pause and the counter inside an UPDATE of the connection row [current] (a config PUT); a healthy row is left alone. */
internal fun UpdateStatement.clearPause(current: ResultRow) {
    if (current[Connections.limitTrips] > 0 || current[Connections.pausedAt] != null) {
        this[Connections.limitTrips] = 0
        this[Connections.pausedAt] = null
        this[Connections.pauseReason] = null
    }
}

/** "Sync now": clears the counter and the pause of [id]; returns what a PAUSE held, null when it was not paused. */
internal suspend fun resumeFromPauseRow(database: R2dbcDatabase, id: UInt): PauseSnapshot? = suspendTransaction(database) {
    val row = lockConnection(id) ?: return@suspendTransaction null
    val snapshot = row.pauseSnapshot()
    if (snapshot != null || row[Connections.limitTrips] > 0) {
        Connections.update({ Connections.id eq id }) {
            it[limitTrips] = 0
            it[pausedAt] = null
            it[pauseReason] = null
        }
    }
    snapshot
}
