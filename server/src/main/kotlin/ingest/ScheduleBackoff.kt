package ch.nokillswit.ingest

import ch.nokillswit.infra.time.MILLIS_PER_MINUTE
import ch.nokillswit.ingest.DataSourceService.Connections
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The connection-row writes of the scheduler back-off (`.claude/docs/ingestion.md` "Scheduling"), each an extension on the
 * CALLER's transaction so a job's terminal write and its back-off commit together: `SyncJobLeases.fail` and the claim's
 * `RETRIES_EXHAUSTED` closes call [recordFailureBackoff] inside the transaction that closes the job, and
 * `DataSourceService`'s `record*` methods open a transaction of their own around the same bodies. Every write takes the
 * connection row `FOR NO KEY UPDATE` first (so two failures never read the same pre-increment count) — not `FOR UPDATE`, which
 * would conflict with the foreign-key `FOR KEY SHARE` every job insert takes on the connection; the writes change no key.
 */

/**
 * A job of [kind] failed (or ran out of attempts): SYNC bumps `consecutive_failures` and pushes `next_sync_at`, RECONCILE bumps
 * `reconcile_failures` and pushes `next_reconcile_at`; the other kinds have no schedule on the connection and write nothing.
 * With a [jobConfigRevision] (a job's own `config_revision`), nothing is written unless it equals the connection's CURRENT
 * revision, read under the lock: a job that started before a config PUT must not re-impose the back-off that PUT just cleared.
 */
internal suspend fun R2dbcTransaction.recordFailureBackoff(
    connectionId: UInt,
    kind: SyncJobKind,
    errorCode: String?,
    now: Long,
    jobConfigRevision: Long? = null,
) {
    if (kind != SyncJobKind.SYNC && kind != SyncJobKind.RECONCILE) return
    val row = lockConnection(connectionId) ?: return
    if (jobConfigRevision != null && row[Connections.configRevision] != jobConfigRevision) return
    if (kind == SyncJobKind.SYNC) {
        // `interval × 2^n`, n = the PRE-increment `consecutive_failures`, capped at MAX_BACKOFF_MILLIS.
        val failuresBefore = row[Connections.consecutiveFailures]
        val intervalMillis = row[Connections.syncIntervalMinutes].toLong() * MILLIS_PER_MINUTE
        Connections.update({ Connections.id eq connectionId }) {
            it[lastSyncErrorCode] = errorCode
            it[consecutiveFailures] = failuresBefore + 1
            it[nextSyncAt] = now + backoffMillis(intervalMillis, failuresBefore)
        }
    } else {
        // `RECONCILE_RETRY_BASE_MILLIS × 2^n`, n = the PRE-increment `reconcile_failures`.
        val failuresBefore = row[Connections.reconcileFailures]
        Connections.update({ Connections.id eq connectionId }) {
            it[reconcileFailures] = failuresBefore + 1
            it[nextReconcileAt] = now + backoffMillis(RECONCILE_RETRY_BASE_MILLIS, failuresBefore)
        }
    }
}

/** A SYNC succeeded: clears the failure streak and schedules `next_sync_at = now + syncInterval`. */
internal suspend fun R2dbcTransaction.recordSyncSuccess(connectionId: UInt, now: Long) {
    val row = lockConnection(connectionId) ?: return
    val intervalMillis = row[Connections.syncIntervalMinutes].toLong() * MILLIS_PER_MINUTE
    Connections.update({ Connections.id eq connectionId }) {
        it[lastSyncSucceededAt] = now
        it[lastSyncErrorCode] = null
        it[consecutiveFailures] = 0
        it[nextSyncAt] = now + intervalMillis
    }
}

/**
 * A config PUT (`DataSourceService.update`, which bumps `config_revision`) lifts the failure back-off of [current] — the
 * connection's row as read under the PUT's lock — so the operator's fix (a rotated token, a corrected scope or URL)
 * takes effect on the next scheduler tick instead of after up to [MAX_BACKOFF_MILLIS]: SYNC's `consecutive_failures` and
 * `next_sync_at`, RECONCILE's `reconcile_failures` and `next_reconcile_at`. Only a back-off that exists is lifted, so a
 * healthy connection keeps its schedule (a rename does not trigger an extra sync).
 */
internal fun UpdateStatement.clearBackoff(current: ResultRow) {
    if (current[Connections.consecutiveFailures] > 0) {
        this[Connections.consecutiveFailures] = 0
        this[Connections.nextSyncAt] = null
    }
    if (current[Connections.reconcileFailures] > 0) {
        this[Connections.reconcileFailures] = 0
        this[Connections.nextReconcileAt] = null
    }
}

private suspend fun R2dbcTransaction.lockConnection(connectionId: UInt) =
    Connections.selectAll().where { Connections.id eq connectionId }.forUpdate(NO_KEY_UPDATE).toList().singleOrNull()

private val NO_KEY_UPDATE = ForUpdateOption.PostgreSQL.ForNoKeyUpdate
