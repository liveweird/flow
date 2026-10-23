package ch.nokillswit.ingest

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.deleted
import ch.nokillswit.ingest.DataSourceService.Connections
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The scheduler's scans over `source_connections` (IngestWorker's tick, `.claude/docs/ingestion.md` "Scheduling"): which
 * connections are due for which job kind. `DataSourceService` delegates here, keeping the public names stable.
 */

private fun ResultRow.toDueConnection() = DataSourceService.DueConnection(this[Connections.id].value, this[Connections.configRevision])

internal suspend fun enabledActiveConnectionRows(database: R2dbcDatabase): List<DataSourceService.DueConnection> =
    suspendTransaction(database) {
        Connections.selectAll().where { Connections.active() and (Connections.enabled eq true) }.toList().map { it.toDueConnection() }
    }

internal suspend fun dueForSyncRows(database: R2dbcDatabase, now: Long): List<DataSourceService.DueConnection> =
    suspendTransaction(database) {
        Connections.selectAll().where {
            Connections.active() and (Connections.enabled eq true) and
                ((Connections.nextSyncAt.isNull()) or (Connections.nextSyncAt lessEq now))
        }.toList().map { it.toDueConnection() }
    }

internal suspend fun dueForReconcileRows(database: R2dbcDatabase, now: Long): List<DataSourceService.DueConnection> =
    suspendTransaction(database) {
        Connections.selectAll().where { Connections.active() and (Connections.enabled eq true) }.toList()
            .filter {
                reconcileDue(it[Connections.lastReconcileAt], it[Connections.reconcileHourUtc], now, it[Connections.nextReconcileAt])
            }
            .map { it.toDueConnection() }
    }

internal suspend fun dueForPurgeRows(database: R2dbcDatabase, now: Long, graceMillis: Long): List<DataSourceService.DueConnection> =
    suspendTransaction(database) {
        Connections.selectAll().where {
            Connections.deleted() and (Connections.purgedAt.isNull()) and (Connections.updatedAt lessEq (now - graceMillis))
        }.toList().map { it.toDueConnection() }
    }
