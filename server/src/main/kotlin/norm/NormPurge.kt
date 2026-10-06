package ch.nokillswit.norm

import ch.nokillswit.norm.WorkItemStore.BoardColumns
import ch.nokillswit.norm.WorkItemStore.Boards
import ch.nokillswit.norm.WorkItemStore.FieldChanges
import ch.nokillswit.norm.WorkItemStore.FieldIntervals
import ch.nokillswit.norm.WorkItemStore.People
import ch.nokillswit.norm.WorkItemStore.Sprints
import ch.nokillswit.norm.WorkItemStore.StatusIntervals
import ch.nokillswit.norm.WorkItemStore.Statuses
import ch.nokillswit.norm.WorkItemStore.WorkItems
import ch.nokillswit.norm.WorkItemStore.Worklogs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** Batch size for the PURGE step's cleanup over the bigger `norm.*` tables — mirrors `jira/JiraRawStore.kt`'s `JIRA_PURGE_BATCH_SIZE`. */
internal const val NORM_PURGE_BATCH_SIZE = 500

/**
 * The PURGE step's deletes over `norm.*` (plan §0 A2): one batch per big table per call, so a connection with a large
 * history drains in bounded transactions, and the small reference tables cleared outright. The drain loop is
 * [purgeAll] below.
 */
internal class NormPurge(private val database: R2dbcDatabase) {

    /** One batch of a connection's `norm.work_items` rows (the PURGE step, plan §0 A2). */
    suspend fun purgeWorkItemsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = WorkItems.select(WorkItems.issueId).where { WorkItems.connectionId eq connectionId }.limit(batchSize)
            .map { it[WorkItems.issueId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        WorkItems.deleteWhere { (WorkItems.connectionId eq connectionId) and (WorkItems.issueId inList ids) }
    }

    suspend fun purgeStatusIntervalsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = StatusIntervals.select(StatusIntervals.id).where { StatusIntervals.connectionId eq connectionId }.limit(batchSize)
            .map { it[StatusIntervals.id] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        StatusIntervals.deleteWhere { (StatusIntervals.connectionId eq connectionId) and (StatusIntervals.id inList ids) }
    }

    suspend fun purgeFieldIntervalsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = FieldIntervals.select(FieldIntervals.id).where { FieldIntervals.connectionId eq connectionId }.limit(batchSize)
            .map { it[FieldIntervals.id] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        FieldIntervals.deleteWhere { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.id inList ids) }
    }

    suspend fun purgeFieldChangesBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = FieldChanges.select(FieldChanges.id).where { FieldChanges.connectionId eq connectionId }.limit(batchSize)
            .map { it[FieldChanges.id] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        FieldChanges.deleteWhere { (FieldChanges.connectionId eq connectionId) and (FieldChanges.id inList ids) }
    }

    suspend fun purgeWorklogsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = Worklogs.select(Worklogs.worklogId).where { Worklogs.connectionId eq connectionId }.limit(batchSize)
            .map { it[Worklogs.worklogId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Worklogs.deleteWhere { (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId inList ids) }
    }

    /** The small reference tables are rebuilt wholesale already — PURGE just clears them outright, no batching needed. */
    suspend fun purgeReferenceRows(connectionId: UInt) = suspendTransaction(database) {
        Statuses.deleteWhere { Statuses.connectionId eq connectionId }
        People.deleteWhere { People.connectionId eq connectionId }
        BoardColumns.deleteWhere { BoardColumns.connectionId eq connectionId }
        Boards.deleteWhere { Boards.connectionId eq connectionId }
        Sprints.deleteWhere { Sprints.connectionId eq connectionId }
    }
}

/** Drains a connection's `norm.*` rows in batches — the PURGE step (plan §0 A2), mirroring `jira/JiraRawStore.kt`'s `purgeAll`. */
suspend fun WorkItemStore.purgeAll(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE) {
    while (purgeFieldChangesBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeFieldIntervalsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeStatusIntervalsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeWorklogsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeWorkItemsBatch(connectionId, batchSize) > 0) { /* drain */ }
    purgeReferenceRows(connectionId)
}
