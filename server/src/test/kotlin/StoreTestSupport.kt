package ch.nokillswit

import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.metrics.FactEpicDeliveryRow
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * Test-only store reads and seeding shapes. They lived on the production stores with zero main callers; as extensions here
 * they read through `sharedDatabaseForTests()` (the same container database every test store is built over) and use only the
 * stores' public surface, so production keeps no code that only a test calls.
 */

/** Wholesale-replace shape (one call) for a test that has every row in memory — runs in the caller's ambient transaction. */
internal suspend fun MetricsStore.replaceFactTaskDelivery(connectionId: UInt, rows: List<FactTaskDeliveryRow>, configRevision: Long) {
    deleteFactTaskDelivery(connectionId)
    insertFactTaskDelivery(connectionId, rows, configRevision)
}

/** Wholesale-replace shape (one call) for a test that has every row in memory — runs in the caller's ambient transaction. */
internal suspend fun MetricsStore.replaceFactEpicDelivery(connectionId: UInt, rows: List<FactEpicDeliveryRow>, configRevision: Long) {
    deleteFactEpicDelivery(connectionId)
    insertFactEpicDelivery(connectionId, rows, configRevision)
}

/** The `norm.work_items` row count of a connection. */
internal suspend fun WorkItemStore.countWorkItems(connectionId: UInt): Long = suspendTransaction(sharedDatabaseForTests()) {
    WorkItemStore.WorkItems.selectAll().where { WorkItemStore.WorkItems.connectionId eq connectionId }.count()
}

/** One issue's raw `norm.work_items` row, or null. */
internal suspend fun WorkItemStore.workItemRow(connectionId: UInt, issueId: Long): ResultRow? =
    suspendTransaction(sharedDatabaseForTests()) {
        WorkItemStore.WorkItems.selectAll()
            .where { (WorkItemStore.WorkItems.connectionId eq connectionId) and (WorkItemStore.WorkItems.issueId eq issueId) }
            .toList().singleOrNull()
    }

/** The RECONCILE scratch table's row count for a connection (the "empty after a completed pass" assertion). */
internal suspend fun JiraRawStore.countReconcileSeen(connectionId: UInt): Long = suspendTransaction(sharedDatabaseForTests()) {
    JiraRawStore.ReconcileSeen.selectAll().where { JiraRawStore.ReconcileSeen.connectionId eq connectionId }.count()
}
