package ch.nokillswit.norm

import ch.nokillswit.norm.WorkItemStore.FieldIntervals
import ch.nokillswit.norm.WorkItemStore.StatusIntervals
import ch.nokillswit.norm.WorkItemStore.WorkItemView
import ch.nokillswit.norm.WorkItemStore.WorkItems
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * One issue's normalized rows, as the raw issue inspector shows them (`ingest/RawIssueInspectorRoutes.kt`, v0.2.0 plan
 * §9/§12 item 8b): the `work_items` view plus its tiled status and field intervals. Route handlers never touch tables
 * (`.claude/docs/persistence.md`); this is the read behind them.
 */
internal class NormInspectorReads(private val database: R2dbcDatabase) {

    suspend fun workItemView(connectionId: UInt, issueId: Long): WorkItemView? = suspendTransaction(database) {
        WorkItems.selectAll().where { (WorkItems.connectionId eq connectionId) and (WorkItems.issueId eq issueId) }
            .toList().singleOrNull()?.let { row ->
                WorkItemView(
                    issueKey = row[WorkItems.issueKey],
                    projectKey = row[WorkItems.projectKey],
                    issueType = row[WorkItems.issueType],
                    statusId = row[WorkItems.statusId],
                    statusName = row[WorkItems.statusName],
                    statusCategory = StatusCategory.valueOf(row[WorkItems.statusCategory]),
                    assigneeAccountId = row[WorkItems.assigneeAccountId],
                    hierarchyLevel = row[WorkItems.hierarchyLevel],
                    dueAt = row[WorkItems.dueAt],
                    customFields = Json.parseToJsonElement(row[WorkItems.customFields]).jsonObject,
                    anomalies = parseAnomalies(row[WorkItems.anomalies]),
                    processedAt = row[WorkItems.processedAt],
                    processingVersion = row[WorkItems.processingVersion],
                    deletedAt = row[WorkItems.deletedAt],
                    movedOutAt = row[WorkItems.movedOutAt],
                )
            }
    }

    /** One issue's tiled status intervals, ordered by `seq` (v0.2.0 plan §9/§12 item 8b — the raw issue inspector). */
    suspend fun statusIntervalsForIssue(connectionId: UInt, issueId: Long): List<NormalizedStatusInterval> = suspendTransaction(database) {
        StatusIntervals.selectAll().where { (StatusIntervals.connectionId eq connectionId) and (StatusIntervals.issueId eq issueId) }
            .toList().sortedBy { it[StatusIntervals.seq] }
            .map {
                NormalizedStatusInterval(
                    seq = it[StatusIntervals.seq],
                    statusId = it[StatusIntervals.statusId],
                    statusName = it[StatusIntervals.statusName],
                    category = StatusCategory.valueOf(it[StatusIntervals.statusCategory]),
                    fromAtMs = it[StatusIntervals.fromAt],
                    toAtMs = it[StatusIntervals.toAt],
                    source = IntervalSource.valueOf(it[StatusIntervals.intervalSource]),
                )
            }
    }

    /** One issue's tiled field intervals (ASSIGNEE/SPRINT/FLAGGED), ordered — the raw issue inspector (v0.2.0 plan §9/§12 item 8b). */
    suspend fun fieldIntervalsForIssue(connectionId: UInt, issueId: Long): List<NormalizedFieldInterval> = suspendTransaction(database) {
        FieldIntervals.selectAll().where { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.issueId eq issueId) }
            .toList().sortedWith(compareBy({ it[FieldIntervals.field] }, { it[FieldIntervals.seq] }))
            .map {
                NormalizedFieldInterval(
                    field = TrackedField.valueOf(it[FieldIntervals.field]),
                    seq = it[FieldIntervals.seq],
                    valueId = it[FieldIntervals.valueId],
                    valueText = it[FieldIntervals.valueText],
                    fromAtMs = it[FieldIntervals.fromAt],
                    toAtMs = it[FieldIntervals.toAt],
                )
            }
    }
}
