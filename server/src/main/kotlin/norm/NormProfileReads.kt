package ch.nokillswit.norm

import ch.nokillswit.norm.WorkItemStore.FieldIntervals
import ch.nokillswit.norm.WorkItemStore.ProfileWorkItemRow
import ch.nokillswit.norm.WorkItemStore.WorkItems
import ch.nokillswit.norm.WorkItemStore.WorklogRow
import ch.nokillswit.norm.WorkItemStore.Worklogs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The data profile's bulk reads over `norm.*` (`jira/JiraProfile.kt`, v0.2.0 plan §8/§12 item 9): the LIVE work-item
 * base row set, the worklog rows and the sprint field-interval pairs. Every profile section derives from these plus
 * the reference rows ([NormReferenceStore]), never a second raw-store pass.
 */
internal class NormProfileReads(private val database: R2dbcDatabase) {

    /**
     * Every LIVE (non-tombstoned) work item for a connection (v0.2.0 plan §8/§12 item 9) — the data
     * profile's own base row set; every section is derived from this plus the interval/reference
     * reads below, never a second raw-store pass.
     */
    suspend fun profileWorkItems(connectionId: UInt): List<ProfileWorkItemRow> = suspendTransaction(database) {
        WorkItems.selectAll().where {
            (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull()
        }.toList().map { row ->
            ProfileWorkItemRow(
                issueId = row[WorkItems.issueId],
                projectKey = row[WorkItems.projectKey],
                issueType = row[WorkItems.issueType],
                assigneeAccountId = row[WorkItems.assigneeAccountId],
                storyPoints = row[WorkItems.storyPoints],
                originalEstimateSeconds = row[WorkItems.originalEstimateSeconds],
                createdAt = row[WorkItems.createdAt],
                updatedAt = row[WorkItems.updatedAt],
                anomalies = parseAnomalies(row[WorkItems.anomalies]),
            )
        }
    }

    /** Every `norm.work_item_worklogs` row for a connection — the caller (`jira/JiraProfile.kt`) filters to live issue ids itself. */
    suspend fun worklogRows(connectionId: UInt): List<WorklogRow> = suspendTransaction(database) {
        Worklogs.selectAll().where { Worklogs.connectionId eq connectionId }
            .map { WorklogRow(it[Worklogs.issueId], it[Worklogs.authorAccountId], it[Worklogs.timeSpentSeconds]) }.toList()
    }

    /**
     * Every `norm.work_item_field_intervals` row of [field] for a connection, as `(issueId,
     * valueId)` pairs — the sprint profile's own read.
     */
    suspend fun fieldIntervalValues(connectionId: UInt, field: TrackedField): List<Pair<Long, String?>> = suspendTransaction(database) {
        FieldIntervals.select(FieldIntervals.issueId, FieldIntervals.valueId)
            .where { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.field eq field.name) }
            .map { it[FieldIntervals.issueId] to it[FieldIntervals.valueId] }.toList()
    }
}
