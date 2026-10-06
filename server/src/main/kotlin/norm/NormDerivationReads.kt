package ch.nokillswit.norm

import ch.nokillswit.norm.WorkItemStore.DerivationWorkItemRow
import ch.nokillswit.norm.WorkItemStore.DerivationWorklogRow
import ch.nokillswit.norm.WorkItemStore.FieldChanges
import ch.nokillswit.norm.WorkItemStore.FieldIntervals
import ch.nokillswit.norm.WorkItemStore.StatusIntervals
import ch.nokillswit.norm.WorkItemStore.WorkItems
import ch.nokillswit.norm.WorkItemStore.Worklogs
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** One `norm.work_item_field_changes` row, connection-wide (v0.3.0 M1 commit 2) — [fieldChangesByFieldIds]'s own read shape. */
data class FieldChangeRow(
    val issueId: Long,
    val fieldId: String?,
    val field: String,
    val changedAt: Long,
    val fromValue: String?,
    val fromText: String?,
    val toValue: String?,
    val toText: String?,
)

/**
 * The DERIVE job's reads over `norm.*` (`metrics/MetricsDeriver.kt` and its steps, v0.3.0 M3): the LIVE work-item base
 * rows, worklogs, and the status/field intervals and field changes — the connection-wide reads take an optional
 * `issueIds` scope for the deriver's per-batch memory bound. Nothing here writes.
 */
internal class NormDerivationReads(private val database: R2dbcDatabase) {

    /**
     * Every `norm.work_item_worklogs` row for a connection, grouped by issue (v0.3.0 M3 commit 7)
     * — `metrics/MetricsDeriver.kt`'s own per-issue read.
     */
    suspend fun worklogsByIssue(connectionId: UInt): Map<Long, List<DerivationWorklogRow>> = suspendTransaction(database) {
        Worklogs.selectAll().where { Worklogs.connectionId eq connectionId }
            .toList()
            .map {
                DerivationWorklogRow(
                    worklogId = it[Worklogs.worklogId],
                    issueId = it[Worklogs.issueId],
                    authorAccountId = it[Worklogs.authorAccountId],
                    startedAt = it[Worklogs.startedAt],
                    timeSpentSeconds = it[Worklogs.timeSpentSeconds],
                    createdAt = it[Worklogs.createdAt],
                    updatedAt = it[Worklogs.updatedAt],
                )
            }
            .groupBy { it.issueId }
    }

    /** Every LIVE work item for a connection, as [DerivationWorkItemRow] — `metrics/MetricsDeriver.kt`'s per-issue base row set. */
    suspend fun workItemsForDerivation(connectionId: UInt): List<DerivationWorkItemRow> = suspendTransaction(database) {
        WorkItems.selectAll().where {
            (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull()
        }.toList().map { row ->
            DerivationWorkItemRow(
                issueId = row[WorkItems.issueId],
                issueKey = row[WorkItems.issueKey],
                projectKey = row[WorkItems.projectKey],
                issueType = row[WorkItems.issueType],
                isSubtask = row[WorkItems.isSubtask],
                parentIssueId = row[WorkItems.parentIssueId],
                hierarchyLevel = row[WorkItems.hierarchyLevel],
                summary = row[WorkItems.summary],
                createdAt = row[WorkItems.createdAt],
                assigneeAccountId = row[WorkItems.assigneeAccountId],
                dueAt = row[WorkItems.dueAt],
                customFields = Json.parseToJsonElement(row[WorkItems.customFields]).jsonObject,
                currentSprintIds = parseLongArray(row[WorkItems.currentSprintIds]),
            )
        }
    }

    /**
     * Every `norm.work_item_field_intervals` row of [field], connection-wide, grouped by issue and
     * ordered by `seq` (v0.3.0 M1 commit 2) — the metrics layer's per-issue replay read (e.g. PARENT
     * for `task_epic`, SPRINT for `task_sprint`), the connection-wide sibling of
     * [NormInspectorReads.fieldIntervalsForIssue]. [issueIds], when non-null (v0.3.0 M3 review round 2b — the batched
     * DERIVE read), scopes the read to only those issues — `MetricsDeriver.kt`'s per-batch-of-200
     * memory bound; `null` (every other caller) keeps the whole-connection scan.
     */
    suspend fun fieldIntervalsByIssue(
        connectionId: UInt,
        field: TrackedField,
        issueIds: Collection<Long>? = null,
    ): Map<Long, List<NormalizedFieldInterval>> =
        suspendTransaction(database) {
            if (issueIds != null && issueIds.isEmpty()) return@suspendTransaction emptyMap()
            var predicate = (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.field eq field.name)
            if (issueIds != null) predicate = predicate and (FieldIntervals.issueId inList issueIds)
            FieldIntervals.selectAll().where { predicate }
                .toList()
                .groupBy({ it[FieldIntervals.issueId] }) {
                    NormalizedFieldInterval(
                        field = field,
                        seq = it[FieldIntervals.seq],
                        valueId = it[FieldIntervals.valueId],
                        valueText = it[FieldIntervals.valueText],
                        fromAtMs = it[FieldIntervals.fromAt],
                        toAtMs = it[FieldIntervals.toAt],
                    )
                }
                .mapValues { (_, intervals) -> intervals.sortedBy { it.seq } }
        }

    /**
     * Every `norm.work_item_field_changes` row whose `field_id` is one of [fieldIds], connection-wide
     * (v0.3.0 M1 commit 2) — the metrics layer's per-field estimate/epic-date replay read (e.g. the
     * configured estimate field's changes, to build `item_estimate` timelines). [issueIds], when
     * non-null (v0.3.0 M3 review round 2b), scopes the read to only those issues —
     * `MetricsDeriver.kt`'s per-batch-of-200 memory bound; `null` keeps the whole-connection scan.
     */
    suspend fun fieldChangesByFieldIds(
        connectionId: UInt,
        fieldIds: Collection<String>,
        issueIds: Collection<Long>? = null,
    ): List<FieldChangeRow> =
        suspendTransaction(database) {
            if (fieldIds.isEmpty() || issueIds?.isEmpty() == true) return@suspendTransaction emptyList()
            var predicate = (FieldChanges.connectionId eq connectionId) and (FieldChanges.fieldId inList fieldIds)
            if (issueIds != null) predicate = predicate and (FieldChanges.issueId inList issueIds)
            FieldChanges.selectAll().where { predicate }
                .orderBy(FieldChanges.issueId to SortOrder.ASC, FieldChanges.changedAt to SortOrder.ASC, FieldChanges.seq to SortOrder.ASC)
                .toList()
                .map { row ->
                    FieldChangeRow(
                        issueId = row[FieldChanges.issueId],
                        fieldId = row[FieldChanges.fieldId],
                        field = row[FieldChanges.field],
                        changedAt = row[FieldChanges.changedAt],
                        fromValue = row[FieldChanges.fromValue],
                        fromText = row[FieldChanges.fromText],
                        toValue = row[FieldChanges.toValue],
                        toText = row[FieldChanges.toText],
                    )
                }
        }

    /**
     * Every work item's tiled status intervals, ordered — the pipeline test's SQL-invariant-sweep/
     * reopen-count source. [issueIds], when non-null (v0.3.0 M3 review round 2b), scopes the read to
     * only those issues — `MetricsDeriver.kt`'s per-batch-of-200 memory bound; `null` (every other
     * caller) keeps the whole-connection scan.
     */
    suspend fun statusIntervalsByIssue(
        connectionId: UInt,
        issueIds: Collection<Long>? = null,
    ): Map<Long, List<NormalizedStatusInterval>> = suspendTransaction(database) {
        if (issueIds != null && issueIds.isEmpty()) return@suspendTransaction emptyMap()
        var predicate: Op<Boolean> = StatusIntervals.connectionId eq connectionId
        if (issueIds != null) predicate = predicate and (StatusIntervals.issueId inList issueIds)
        StatusIntervals.selectAll().where { predicate }
            .toList()
            .groupBy({ it[StatusIntervals.issueId] }) {
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
            .mapValues { (_, intervals) -> intervals.sortedBy { it.seq } }
    }

    /**
     * Every issue's summed `time_spent_seconds` for a connection (v0.3.0 M3 review round 2b) —
     * `MetricsDeriver.kt`'s own memory-light worklog read: only the ONE number `ownWorklogSeconds`/
     * `actualMdFor` ever sum, never the full [DerivationWorklogRow] shape (author/timestamps),
     * which this commit's algorithm does not read at all.
     */
    suspend fun worklogSecondsByIssue(connectionId: UInt): Map<Long, Long> = suspendTransaction(database) {
        Worklogs.select(Worklogs.issueId, Worklogs.timeSpentSeconds).where { Worklogs.connectionId eq connectionId }
            .toList()
            .groupBy({ it[Worklogs.issueId] }) { it[Worklogs.timeSpentSeconds] }
            .mapValues { (_, seconds) -> seconds.sum() }
    }
}
