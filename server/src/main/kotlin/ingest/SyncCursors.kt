package ch.nokillswit.ingest

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert

val SyncCursorsServiceKey = AttributeKey<SyncCursorsService>("SyncCursorsService")

/** One resumable cursor per (connection, stream) — V9 `sync_cursors`; the streams themselves land in plan commit 6+. */
data class SyncCursorRow(
    val connectionId: UInt,
    val stream: String,
    val cursor: String,
    val watermarkAt: Long?,
    val lastCompletedAt: Long?,
    val updatedAt: Long,
)

/**
 * Per-stream incremental cursors (v0.2.0 plan §4/§7 V9): `stream` names a sync phase (e.g.
 * `"issues"`, `"changelogs"`) within a connection. `cursor` is stored as jsonb text
 * (`infra/db/Jsonb.kt`) — its shape is per-stream and owned by that stream's implementation.
 */
class SyncCursorsService(private val database: R2dbcDatabase) {
    object Cursors : Table("sync_cursors") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val stream = varchar("stream", 30)
        val cursor = jsonb("cursor")
        val watermarkAt = long("watermark_at").nullable()
        val lastCompletedAt = long("last_completed_at").nullable()
        val updatedAt = long("updated_at")
        override val primaryKey = PrimaryKey(connectionId, stream)
    }

    suspend fun get(connectionId: UInt, stream: String): SyncCursorRow? = suspendTransaction(database) {
        Cursors.selectAll().where { (Cursors.connectionId eq connectionId) and (Cursors.stream eq stream) }
            .toList().singleOrNull()?.let {
                SyncCursorRow(
                    connectionId = it[Cursors.connectionId].value,
                    stream = it[Cursors.stream],
                    cursor = it[Cursors.cursor],
                    watermarkAt = it[Cursors.watermarkAt],
                    lastCompletedAt = it[Cursors.lastCompletedAt],
                    updatedAt = it[Cursors.updatedAt],
                )
            }
    }

    /**
     * Upserts the cursor for (connectionId, stream) — the write is expected to run in the SAME
     * transaction as the stream's page write once streams land (plan commit 6+).
     */
    suspend fun put(
        connectionId: UInt,
        stream: String,
        cursor: String,
        watermarkAt: Long? = null,
        lastCompletedAt: Long? = null,
        now: Long = nowMillis(),
    ) {
        suspendTransaction(database) {
            Cursors.upsert(Cursors.connectionId, Cursors.stream) {
                it[Cursors.connectionId] = connectionId
                it[Cursors.stream] = stream
                it[Cursors.cursor] = cursor
                it[Cursors.watermarkAt] = watermarkAt
                it[Cursors.lastCompletedAt] = lastCompletedAt
                it[Cursors.updatedAt] = now
            }
        }
    }

    /** Every persisted cursor row for a connection — `GET …/{id}/status`'s `cursors[]` (v0.2.0 plan §9). */
    suspend fun getAll(connectionId: UInt): List<SyncCursorRow> = suspendTransaction(database) {
        Cursors.selectAll().where { Cursors.connectionId eq connectionId }.toList().map {
            SyncCursorRow(
                connectionId = it[Cursors.connectionId].value,
                stream = it[Cursors.stream],
                cursor = it[Cursors.cursor],
                watermarkAt = it[Cursors.watermarkAt],
                lastCompletedAt = it[Cursors.lastCompletedAt],
                updatedAt = it[Cursors.updatedAt],
            )
        }
    }

    /** A stream that just completed a whole logical pass (e.g. REFERENCE, plan §7) clears its own resumption state. */
    suspend fun clear(connectionId: UInt, stream: String) {
        suspendTransaction(database) {
            Cursors.deleteWhere { (Cursors.connectionId eq connectionId) and (Cursors.stream eq stream) }
        }
    }
}
