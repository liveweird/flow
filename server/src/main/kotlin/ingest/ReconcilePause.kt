package ch.nokillswit.ingest

import ch.nokillswit.ingest.DataSourceService.Connections
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/*
 * The RECONCILE ceiling-trip pause (`.claude/docs/ingestion.md` "Ceiling trips and the pause", V38): `SyncPause.kt`'s twin with its OWN
 * counter and pause (`reconcile_limit_trips`, `reconcile_paused_at`, `reconcile_pause_reason`), so a RECONCILE that trips every run is
 * stopped instead of repeating at the back-off ceiling (15 min to 6 h, `ScheduleBackoff.kt`) forever, and neither pause ever touches the
 * other job. Same fences and same shape as the SYNC bookkeeping: extensions on the caller's transaction, row locked
 * `FOR NO KEY UPDATE`, nothing written for a job of an older `config_revision`.
 */

/**
 * A RECONCILE job ended in [trip]: counts it and pauses the connection's RECONCILE when the count reaches [pauseAfterTrips]. Runs in
 * the transaction that closes the job (like [recordCeilingTrip]) and writes nothing for a job of an older `config_revision`
 * ([jobConfigRevision]). Same rule as a SYNC trip, read through [CeilingTripException.madeProgress]: a trip that stored NO durable change
 * counts; one that did (a tombstone or an index-gap upsert after the sweep, `jira/JiraReconcileStream.kt`; the id sweep's scratch
 * seen-set is keyed by job id, so its pages are no change and a stop inside it leaves the next run exactly where this one began) is not
 * counted and RESETS the count to 0 (the trips must be in a row), because the next run's candidate set has shrunk. It is still NOT a
 * "resumable" stop (`SyncJobLeases.failTripped` resumes SYNC only): the failure back-off applies. The count is also reset by a succeeded
 * RECONCILE ([resetReconcilePause] through `recordReconcileSucceededRow`), "Reconcile now" and a config PUT.
 * Null = nothing recorded (the connection is gone or the job is stale).
 */
internal suspend fun R2dbcTransaction.recordReconcileTrip(
    connectionId: UInt,
    trip: CeilingTripException,
    pauseAfterTrips: Int,
    now: Long,
    jobConfigRevision: Long,
): TripRecord? {
    val row = lockConnection(connectionId) ?: return null
    if (row[Connections.configRevision] != jobConfigRevision) return null
    val before = row[Connections.reconcileLimitTrips]
    if (trip.madeProgress) {
        if (before > 0) Connections.update({ Connections.id eq connectionId }) { it[reconcileLimitTrips] = 0 }
        return TripRecord(0, counted = false, pausedNow = null)
    }
    val after = before + 1
    val pauseNow = row[Connections.reconcilePausedAt] == null && after >= pauseAfterTrips
    Connections.update({ Connections.id eq connectionId }) {
        it[reconcileLimitTrips] = after
        if (pauseNow) {
            it[reconcilePausedAt] = now
            it[reconcilePauseReason] = trip.code
        }
    }
    return TripRecord(
        after, counted = true,
        pausedNow = if (pauseNow) PauseSnapshot(now, trip.code, after, SyncJobKind.RECONCILE) else null,
    )
}

/** The row's RECONCILE pause state, or null when it is not paused. */
internal fun ResultRow.reconcilePauseSnapshot(): PauseSnapshot? = this[Connections.reconcilePausedAt]?.let {
    PauseSnapshot(it, this[Connections.reconcilePauseReason], this[Connections.reconcileLimitTrips], SyncJobKind.RECONCILE)
}

/** Clears the RECONCILE pause and counter inside an UPDATE of the connection row [current] (a config PUT); a healthy row is left alone. */
internal fun UpdateStatement.clearReconcilePause(current: ResultRow) {
    if (current[Connections.reconcileLimitTrips] > 0 || current[Connections.reconcilePausedAt] != null) {
        this[Connections.reconcileLimitTrips] = 0
        this[Connections.reconcilePausedAt] = null
        this[Connections.reconcilePauseReason] = null
    }
}

/** A RECONCILE ran to the end: the ceilings are not the problem, so the counter restarts and any pause lifts. */
internal fun UpdateStatement.resetReconcilePause() {
    this[Connections.reconcileLimitTrips] = 0
    this[Connections.reconcilePausedAt] = null
    this[Connections.reconcilePauseReason] = null
}

/** "Reconcile now": clears the RECONCILE counter and pause of [id]; returns what a PAUSE held, null when it was not paused. */
internal suspend fun resumeFromReconcilePauseRow(database: R2dbcDatabase, id: UInt): PauseSnapshot? = suspendTransaction(database) {
    val row = lockConnection(id) ?: return@suspendTransaction null
    val snapshot = row.reconcilePauseSnapshot()
    if (snapshot != null || row[Connections.reconcileLimitTrips] > 0) {
        Connections.update({ Connections.id eq id }) { it.resetReconcilePause() }
    }
    snapshot
}
