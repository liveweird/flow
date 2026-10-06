package ch.nokillswit.norm

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.db.upsertRows
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.norm.WorkItemStore.FieldChanges
import ch.nokillswit.norm.WorkItemStore.FieldIntervals
import ch.nokillswit.norm.WorkItemStore.StatusIntervals
import ch.nokillswit.norm.WorkItemStore.WorkItems
import ch.nokillswit.norm.WorkItemStore.Worklogs
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The PROCESS step's write path (`WorkItemStore`'s facade delegates here) — plan §8 step 5. Every write is a
 * REPLACE of a PAGE of issues (one transaction per page; a page of one issue is the per-issue REPLACE): delete those
 * issues' child rows, insert the freshly tiled ones, upsert `work_items` — the scope of a replace stays the ISSUE
 * (a page never touches an issue outside its id list). Owns the child-row column mapping and nothing else.
 */
internal class WorkItemWriter(private val database: R2dbcDatabase) {

    /**
     * The PROCESS step's REPLACE (plan §8 step 5) for a whole page of issues — delete the page's child
     * rows (ONE `DELETE … issue_id IN (…)` per table), insert every freshly tiled row of the page (ONE
     * multi-row `insertRows` per table), upsert `work_items` (ONE `ON CONFLICT (connection_id, issue_id) DO UPDATE`
     * batch), ALL in one transaction. The rows written are byte-for-byte the ones N per-issue calls
     * write (same columns, same per-issue `seq`); only the number of round trips differs — measured
     * ~13 statements per ISSUE became ~13 per PAGE (`.claude/docs/build-times.md`). The caller
     * (`jira/JiraProcessStream.kt`) wraps this in the SAME `StreamContext.transaction { }` block as the
     * raw rows' own `processed_at`/`needs_processing` update, so a crash mid-page never leaves a
     * half-written normalized row or a raw row pointing at rows that were never written.
     */
    suspend fun replaceWorkItems(
        connectionId: UInt,
        normalized: List<NormalizedIssue>,
        now: Long,
        processingVersion: Int = PROCESSING_VERSION,
    ) {
        if (normalized.isEmpty()) return
        suspendTransaction(database) {
            val issueIds = normalized.map { it.issueId }
            StatusIntervals.deleteWhere { (StatusIntervals.connectionId eq connectionId) and (StatusIntervals.issueId inList issueIds) }
            FieldIntervals.deleteWhere { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.issueId inList issueIds) }
            FieldChanges.deleteWhere { (FieldChanges.connectionId eq connectionId) and (FieldChanges.issueId inList issueIds) }
            Worklogs.deleteWhere { (Worklogs.connectionId eq connectionId) and (Worklogs.issueId inList issueIds) }

            val statusRows = normalized.flatMap { n -> n.statusIntervals.map { n.issueId to it } }
            if (statusRows.isNotEmpty()) {
                StatusIntervals.insertRows(statusRows) { (issueId, interval) ->
                    this[StatusIntervals.connectionId] = connectionId
                    this[StatusIntervals.issueId] = issueId
                    this[StatusIntervals.seq] = interval.seq
                    this[StatusIntervals.statusId] = interval.statusId
                    this[StatusIntervals.statusName] = interval.statusName
                    this[StatusIntervals.statusCategory] = interval.category.name
                    this[StatusIntervals.fromAt] = interval.fromAtMs
                    this[StatusIntervals.toAt] = interval.toAtMs
                    this[StatusIntervals.intervalSource] = interval.source.name
                }
            }
            val fieldIntervalRows = normalized.flatMap { n -> n.fieldIntervals.map { n.issueId to it } }
            if (fieldIntervalRows.isNotEmpty()) {
                FieldIntervals.insertRows(fieldIntervalRows) { (issueId, interval) ->
                    this[FieldIntervals.connectionId] = connectionId
                    this[FieldIntervals.issueId] = issueId
                    this[FieldIntervals.field] = interval.field.name
                    this[FieldIntervals.seq] = interval.seq
                    this[FieldIntervals.valueId] = interval.valueId
                    this[FieldIntervals.valueText] = interval.valueText
                    this[FieldIntervals.fromAt] = interval.fromAtMs
                    this[FieldIntervals.toAt] = interval.toAtMs
                }
            }
            // `seq` is per ISSUE (1-based, in changelog order) — never a page-wide counter.
            val fieldChangeRows = normalized.flatMap { n ->
                n.fieldChanges.mapIndexed { index, change -> Triple(n.issueId, index + 1, change) }
            }
            if (fieldChangeRows.isNotEmpty()) {
                FieldChanges.insertRows(fieldChangeRows) { (issueId, seq, change) ->
                    this[FieldChanges.connectionId] = connectionId
                    this[FieldChanges.issueId] = issueId
                    this[FieldChanges.seq] = seq
                    this[FieldChanges.field] = change.field
                    this[FieldChanges.changedAt] = change.atMs
                    this[FieldChanges.fromValue] = change.fromValue
                    this[FieldChanges.fromText] = change.fromText
                    this[FieldChanges.toValue] = change.toValue
                    this[FieldChanges.toText] = change.toText
                    this[FieldChanges.fieldId] = change.fieldId
                }
            }
            val worklogRows = normalized.flatMap { n -> n.worklogs.map { n.issueId to it } }
            if (worklogRows.isNotEmpty()) {
                Worklogs.insertRows(worklogRows) { (issueId, worklog) ->
                    this[Worklogs.connectionId] = connectionId
                    this[Worklogs.worklogId] = worklog.worklogId
                    this[Worklogs.issueId] = issueId
                    this[Worklogs.authorAccountId] = worklog.authorAccountId
                    this[Worklogs.startedAt] = worklog.startedAtMs
                    this[Worklogs.timeSpentSeconds] = worklog.timeSpentSeconds
                    this[Worklogs.createdAt] = worklog.createdAtMs
                    this[Worklogs.updatedAt] = worklog.updatedAtMs
                }
            }

            upsertWorkItems(connectionId, normalized, now, processingVersion)
        }
    }

    /**
     * Insert-or-update `norm.work_items` on its PK — every non-key column is written either way, so an
     * existing row ends up exactly as the old select-then-insert/update produced it. Runs inside
     * [replaceWorkItems]' transaction.
     */
    private suspend fun upsertWorkItems(connectionId: UInt, normalized: List<NormalizedIssue>, now: Long, processingVersion: Int) {
        WorkItems.upsertRows(normalized, listOf(WorkItems.connectionId, WorkItems.issueId)) { item ->
            val facts = item.facts
            this[WorkItems.connectionId] = connectionId
            this[WorkItems.issueId] = item.issueId
            this[WorkItems.issueKey] = facts.issueKey
            this[WorkItems.projectKey] = facts.projectKey
            this[WorkItems.issueType] = facts.issueType
            this[WorkItems.isSubtask] = facts.isSubtask
            this[WorkItems.parentIssueId] = facts.parentIssueId
            this[WorkItems.summary] = facts.summary
            this[WorkItems.statusId] = facts.currentStatusId
            this[WorkItems.statusName] = item.currentStatusName
            this[WorkItems.statusCategory] = item.currentStatusCategory.name
            this[WorkItems.resolution] = facts.resolution
            this[WorkItems.priority] = facts.priority
            this[WorkItems.assigneeAccountId] = facts.assigneeAccountId
            this[WorkItems.reporterAccountId] = facts.reporterAccountId
            this[WorkItems.createdAt] = facts.createdAtMs
            this[WorkItems.updatedAt] = facts.updatedAtMs
            this[WorkItems.resolvedAt] = facts.resolvedAtMs
            this[WorkItems.storyPoints] = facts.storyPoints
            this[WorkItems.originalEstimateSeconds] = facts.originalEstimateSeconds
            this[WorkItems.timeSpentSeconds] = facts.timeSpentSeconds
            this[WorkItems.labels] = stringArrayJson(facts.labels)
            this[WorkItems.components] = stringArrayJson(facts.components)
            this[WorkItems.fixVersions] = stringArrayJson(facts.fixVersions)
            this[WorkItems.currentSprintIds] = longArrayJson(item.currentSprintIds)
            this[WorkItems.teamValue] = facts.teamValueJson
            this[WorkItems.flagged] = item.flagged
            this[WorkItems.rank] = facts.rank
            this[WorkItems.hierarchyLevel] = facts.hierarchyLevel
            this[WorkItems.dueAt] = facts.dueAtMs
            this[WorkItems.customFields] = facts.customFieldsJson
            this[WorkItems.anomalies] = anomaliesJson(item.anomalies)
            // The RAW tombstone time, never `now` — a PROCESSING_VERSION bump reprocesses every
            // already-tombstoned issue, and `now` would reset every one of them to the
            // reprocess/deploy time (v0.3.0 M1 commit 2 review fix). `?: now` is a defensive
            // fallback for a caller that (like a hand-built test fixture) omits `tombstoneAtMs`.
            this[WorkItems.deletedAt] = if (facts.tombstone == TombstoneKind.DELETED) (facts.tombstoneAtMs ?: now) else null
            this[WorkItems.movedOutAt] = if (facts.tombstone == TombstoneKind.MOVED_OUT) (facts.tombstoneAtMs ?: now) else null
            this[WorkItems.processedAt] = now
            this[WorkItems.processingVersion] = processingVersion
        }
    }
}
