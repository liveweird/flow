package ch.nokillswit.ingest

import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.ingest.DataSourceService.Connections
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/*
 * The worker's writes of job outcomes onto the connection row's status columns (`.claude/docs/ingestion.md` "Scheduling").
 * `DataSourceService` delegates here, keeping the public names stable; the back-off arithmetic itself is `ScheduleBackoff.kt`.
 */

internal suspend fun recordSyncOutcomeRow(database: R2dbcDatabase, id: UInt, succeeded: Boolean, errorCode: String?, now: Long) {
    suspendTransaction(database) {
        if (succeeded) recordSyncSuccess(id, now) else recordFailureBackoff(id, SyncJobKind.SYNC, errorCode, now)
    }
}

internal suspend fun recordReconcileSucceededRow(database: R2dbcDatabase, id: UInt, now: Long) {
    suspendTransaction(database) {
        Connections.update({ Connections.id eq id }) {
            it[lastReconcileAt] = now
            it[reconcileFailures] = 0
            it[nextReconcileAt] = null
        }
    }
}

internal suspend fun recordSyncStartedRow(database: R2dbcDatabase, id: UInt, now: Long) {
    suspendTransaction(database) {
        Connections.update({ Connections.id eq id }) { it[lastSyncStartedAt] = now }
    }
}

internal suspend fun recordPurgeSucceededRow(database: R2dbcDatabase, id: UInt, now: Long) {
    suspendTransaction(database) {
        Connections.update({ Connections.id eq id }) { it[purgedAt] = now }
    }
}

internal suspend fun updateProfileRow(database: R2dbcDatabase, id: UInt, profileJson: String, now: Long) {
    suspendTransaction(database) {
        Connections.update({ Connections.id eq id }) {
            it[profile] = canonicalJson(profileJson)
            it[profileAt] = now
        }
    }
}
