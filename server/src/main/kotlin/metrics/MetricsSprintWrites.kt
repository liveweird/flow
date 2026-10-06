package ch.nokillswit.metrics

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.metrics.MetricsTables.DimSprint
import ch.nokillswit.metrics.MetricsTables.FactSprint
import ch.nokillswit.metrics.MetricsTables.FactSprintScope
import ch.nokillswit.metrics.MetricsTables.FactSprintSnapshot
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * `metrics.fact_sprint_snapshot.scope` — the sprint's own [SprintScopeItem] rows, frozen as JSON (D13). The per-item MD
 * values are the raw (unrounded) estimates; the snapshot's frozen totals are [sumMd] of these (each item rounded to two
 * decimals before summing), exactly as `fact_sprint`'s are.
 */
private fun sprintScopeItemsJson(items: List<SprintScopeItem>): String = buildJsonArray {
    items.forEach { item ->
        add(
            buildJsonObject {
                put("issueId", item.issueId)
                put("addedAtMs", item.addedAtMs)
                put("removedAtMs", item.removedAtMs)
                put("committed", item.committed)
                put("inScopeAtClose", item.inScopeAtClose)
                put("estimateAtCommitmentMd", item.estimateAtCommitmentMd)
                put("estimateAtCloseMd", item.estimateAtCloseMd)
                put("estimateAtDoneMd", item.estimateAtDoneMd)
                put("assigneeAtCommitment", item.assigneeAtCommitment)
                put("doneInSprint", item.doneInSprint)
                put("carriedOver", item.carriedOver)
                put("dropped", item.dropped)
            },
        )
    }
}.toString()

/**
 * The sprint step's writes: `dim_sprint`, `fact_sprint_scope`, `fact_sprint` (wholesale per run, in the CALLER's
 * transaction) and the append-only `fact_sprint_snapshot` (own transaction, INSERT only; [MetricsStore]'s delegations).
 */
internal class MetricsSprintWrites(private val database: R2dbcDatabase) {

    /** Deletes `dim_sprint`/`fact_sprint_scope`/`fact_sprint` for one connection — `fact_sprint_snapshot`
     * is append-only and never deleted here. */
    suspend fun deleteSprintFacts(connectionId: UInt) {
        FactSprintScope.deleteWhere { FactSprintScope.connectionId eq connectionId }
        FactSprint.deleteWhere { FactSprint.connectionId eq connectionId }
        DimSprint.deleteWhere { DimSprint.connectionId eq connectionId }
    }

    suspend fun insertDimSprints(connectionId: UInt, rows: List<DimSprintRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        DimSprint.insertRows(rows) {
            this[DimSprint.connectionId] = connectionId
            this[DimSprint.sprintId] = it.sprintId
            this[DimSprint.boardId] = it.boardId
            this[DimSprint.teamId] = it.teamId
            this[DimSprint.name] = it.name
            this[DimSprint.state] = it.state
            this[DimSprint.startAt] = it.startAt
            this[DimSprint.endAt] = it.endAt
            this[DimSprint.completeAt] = it.completeAt
            this[DimSprint.capacityMd] = it.capacityMd?.toBigDecimal()
            this[DimSprint.capacitySource] = it.capacitySource
            this[DimSprint.configRevision] = configRevision
        }
    }

    /** One batch's worth of `fact_sprint_scope` rows — call per batch, AFTER [deleteSprintFacts] ran once for the connection. */
    suspend fun insertFactSprintScope(connectionId: UInt, rows: List<FactSprintScopeRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactSprintScope.insertRows(rows) { (sprintId, item) ->
            this[FactSprintScope.connectionId] = connectionId
            this[FactSprintScope.sprintId] = sprintId
            this[FactSprintScope.issueId] = item.issueId
            this[FactSprintScope.addedAt] = item.addedAtMs
            this[FactSprintScope.removedAt] = item.removedAtMs
            this[FactSprintScope.committed] = item.committed
            this[FactSprintScope.inScopeAtClose] = item.inScopeAtClose
            this[FactSprintScope.estimateAtCommitmentMd] = item.estimateAtCommitmentMd?.toBigDecimal()
            this[FactSprintScope.estimateAtCloseMd] = item.estimateAtCloseMd?.toBigDecimal()
            this[FactSprintScope.estimateAtDoneMd] = item.estimateAtDoneMd?.toBigDecimal()
            this[FactSprintScope.assigneeAtCommitment] = item.assigneeAtCommitment
            this[FactSprintScope.doneInSprint] = item.doneInSprint
            this[FactSprintScope.carriedOver] = item.carriedOver
            this[FactSprintScope.dropped] = item.dropped
            this[FactSprintScope.configRevision] = configRevision
        }
    }

    suspend fun insertFactSprint(connectionId: UInt, rows: List<FactSprintRow>, configRevision: Long) {
        if (rows.isEmpty()) return
        FactSprint.insertRows(rows) {
            this[FactSprint.connectionId] = connectionId
            this[FactSprint.sprintId] = it.sprintId
            this[FactSprint.teamId] = it.teamId
            this[FactSprint.completeAt] = it.completeAt
            this[FactSprint.committedMd] = it.totals.committedMd.toBigDecimal()
            this[FactSprint.committedItems] = it.totals.committedItems
            this[FactSprint.addedMd] = it.totals.addedMd.toBigDecimal()
            this[FactSprint.addedItems] = it.totals.addedItems
            this[FactSprint.removedMd] = it.totals.removedMd.toBigDecimal()
            this[FactSprint.removedItems] = it.totals.removedItems
            this[FactSprint.finalMd] = it.totals.finalMd.toBigDecimal()
            this[FactSprint.finalItems] = it.totals.finalItems
            this[FactSprint.deliveredMd] = it.totals.deliveredMd.toBigDecimal()
            this[FactSprint.deliveredItems] = it.totals.deliveredItems
            this[FactSprint.carriedOverMd] = it.totals.carriedOverMd.toBigDecimal()
            this[FactSprint.carriedOverItems] = it.totals.carriedOverItems
            this[FactSprint.droppedMd] = it.totals.droppedMd.toBigDecimal()
            this[FactSprint.droppedItems] = it.totals.droppedItems
            this[FactSprint.capacityMd] = it.capacityMd?.toBigDecimal()
            this[FactSprint.load] = it.load?.toBigDecimal()
            this[FactSprint.configRevision] = configRevision
        }
    }

    /** Every `metrics.fact_sprint_snapshot` sprint id already frozen for this connection — a DERIVE
     * run never re-inserts one (D13, invariant 11). */
    suspend fun existingSnapshotSprintIds(connectionId: UInt): Set<Long> = suspendTransaction(database) {
        FactSprintSnapshot.select(FactSprintSnapshot.sprintId).where { FactSprintSnapshot.connectionId eq connectionId }
            .toList().map { it[FactSprintSnapshot.sprintId] }.toSet()
    }

    /**
     * ONE `metrics.fact_sprint_snapshot` row (D13, invariant 11: append-only, immutable once
     * written) — INSERT only, never called for a sprint id [existingSnapshotSprintIds] already
     * names; the DB trigger (`.claude/docs/persistence.md`) is the actual enforcement against a
     * hand-rolled `UPDATE`/`DELETE`, this method simply never attempts one. [scopeRows] freezes the
     * sprint's own `fact_sprint_scope` rows as JSON so a per-user velocity read from the snapshot
     * needs no child table.
     */
    suspend fun insertFactSprintSnapshot(
        connectionId: UInt,
        row: FactSprintRow,
        scopeRows: List<SprintScopeItem>,
        configRevision: Long,
        processingVersion: Int,
        reconstructed: Boolean,
        snapshotAt: Long,
    ) = suspendTransaction(database) {
        FactSprintSnapshot.insert {
            it[FactSprintSnapshot.connectionId] = connectionId
            it[sprintId] = row.sprintId
            it[teamId] = row.teamId
            it[completeAt] = row.completeAt
            it[committedMd] = row.totals.committedMd.toBigDecimal()
            it[committedItems] = row.totals.committedItems
            it[addedMd] = row.totals.addedMd.toBigDecimal()
            it[addedItems] = row.totals.addedItems
            it[removedMd] = row.totals.removedMd.toBigDecimal()
            it[removedItems] = row.totals.removedItems
            it[finalMd] = row.totals.finalMd.toBigDecimal()
            it[finalItems] = row.totals.finalItems
            it[deliveredMd] = row.totals.deliveredMd.toBigDecimal()
            it[deliveredItems] = row.totals.deliveredItems
            it[carriedOverMd] = row.totals.carriedOverMd.toBigDecimal()
            it[carriedOverItems] = row.totals.carriedOverItems
            it[droppedMd] = row.totals.droppedMd.toBigDecimal()
            it[droppedItems] = row.totals.droppedItems
            it[capacityMd] = row.capacityMd?.toBigDecimal()
            it[load] = row.load?.toBigDecimal()
            it[scope] = sprintScopeItemsJson(scopeRows)
            it[FactSprintSnapshot.configRevision] = configRevision
            it[FactSprintSnapshot.processingVersion] = processingVersion
            it[FactSprintSnapshot.reconstructed] = reconstructed
            it[FactSprintSnapshot.snapshotAt] = snapshotAt
        }
        Unit
    }
}
