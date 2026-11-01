package ch.nokillswit.reports

import ch.nokillswit.metrics.ISSUE_KEY_FIELD_ID
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.CodeActivityStore.CodeMergeRequests
import ch.nokillswit.norm.CodeActivityStore.CodeMrIssueKeys
import ch.nokillswit.norm.WorkItemStore.FieldChanges
import ch.nokillswit.norm.WorkItemStore.WorkItems
import ch.nokillswit.norm.projectKeyFromIssueKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Report 22's SQL reads (`.claude/docs/reports.md` "Report 22", A51/D3-D4), read-only inside the caller's report transaction. Everything
 * here is bounded by the done tasks (the population): their keys, old and current, are resolved to the live issues that name them
 * (across EVERY active Jira source, `connectionId` narrows only the population) and looked up on the V46 index
 * `(connection_id, issue_key)`; the merge requests are never scanned, and only the link rows are budgeted rows ([CodeRowBudget]).
 * Tombstoned merge requests (`deleted_at`) and tombstoned Jira issues (`deleted_at` / `moved_out_at`, the rows DERIVE skips) are
 * never read.
 */

/** `IN` lists are cut into statements of at most this many values (well under the driver's bind limit). */
private const val IN_CHUNK = 5_000

/** The historic-key read repeats the chunk in four columns, so its chunks are a quarter as long. */
private const val HISTORIC_CHUNK = 1_000

/** The DONE level-0 tasks of the period credited to the request's team / domain slice (the cycle-time population, with key and start). */
internal suspend fun fetchTaskCodePopulation(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
): List<TaskCodeTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(t.connectionId, t.issueId, t.issueKey, t.startedAt, t.doneAt, t.assigneeAccountIdAtDone, t.creditTeamId)
        .where { predicate }
        .map {
            val ref = IssueRef(it[t.connectionId].value, it[t.issueId])
            TaskCodeTask(ref, it[t.issueKey], it[t.startedAt], it[t.doneAt]!!, it[t.assigneeAccountIdAtDone], it[t.creditTeamId]?.value)
        }
        .toList()
}

/**
 * Every key the [issues] had or have by their `issuekey` changes (a move to another project; real Jira sends the keys in the `*_text`
 * columns, the stub in the value columns too): the OLD keys a merge request may still name. One statement per connection and chunk,
 * on `idx_norm_work_item_field_changes_field`; bounded by the population.
 */
internal suspend fun fetchKeysOfIssues(issues: Collection<IssueRef>): Map<IssueRef, Set<String>> {
    val f = FieldChanges
    val result = HashMap<IssueRef, MutableSet<String>>()
    issues.groupBy({ it.connectionId }) { it.issueId }.forEach { (connectionId, ids) ->
        ids.chunked(IN_CHUNK).forEach { chunk ->
            f.select(f.issueId, f.fromValue, f.fromText, f.toValue, f.toText)
                .where { (f.connectionId eq connectionId) and (f.fieldId eq ISSUE_KEY_FIELD_ID) and (f.issueId inList chunk) }
                .toList().forEach { row ->
                    val keys = listOf(row[f.fromValue], row[f.fromText], row[f.toValue], row[f.toText]).filterNotNull()
                    result.getOrPut(IssueRef(connectionId, row[f.issueId])) { HashSet() }
                        .addAll(keys.filter { projectKeyFromIssueKey(it) != null })
                }
        }
    }
    return result
}

/**
 * The live issues of [jiraIds] that had or have one of [keys] by an `issuekey` change (the OLD keys of moved issues), by key. A moved
 * issue's old key is not its `issue_key` any more, so the current-key read does not see it. The `issuekey` range is small by nature.
 */
internal suspend fun fetchIssuesByHistoricKey(jiraIds: Collection<UInt>, keys: Collection<String>): Map<String, Set<IssueRef>> {
    if (jiraIds.isEmpty() || keys.isEmpty()) return emptyMap()
    val f = FieldChanges
    val w = WorkItems
    val source = f.join(w, JoinType.INNER, f.connectionId, w.connectionId, additionalConstraint = { f.issueId eq w.issueId })
    val found = keys.toSet()
    return keys.distinct().chunked(HISTORIC_CHUNK).flatMap { chunk ->
        source.select(f.connectionId, f.issueId, f.fromValue, f.fromText, f.toValue, f.toText)
            .where {
                (f.connectionId inList jiraIds) and (f.fieldId eq ISSUE_KEY_FIELD_ID) and w.deletedAt.isNull() and w.movedOutAt.isNull() and
                    ((f.fromValue inList chunk) or (f.fromText inList chunk) or (f.toValue inList chunk) or (f.toText inList chunk))
            }
            .toList().flatMap { row ->
                val ref = IssueRef(row[f.connectionId].value, row[f.issueId])
                val named = listOf(row[f.fromValue], row[f.fromText], row[f.toValue], row[f.toText]).filterNotNull()
                named.filter { it in found }.map { it to ref }
            }
    }.groupBy({ it.first }) { it.second }.mapValues { it.value.toSet() }
}

/** The live issues of [jiraIds] whose CURRENT key is one of [keys] (the `(connection_id, issue_key)` index), by key. */
internal suspend fun fetchLiveIssuesByKey(jiraIds: Collection<UInt>, keys: Collection<String>): Map<String, Set<IssueRef>> {
    if (jiraIds.isEmpty() || keys.isEmpty()) return emptyMap()
    val w = WorkItems
    return keys.distinct().chunked(IN_CHUNK).flatMap { chunk ->
        w.select(w.connectionId, w.issueId, w.issueKey)
            .where { (w.connectionId inList jiraIds) and (w.issueKey inList chunk) and w.deletedAt.isNull() and w.movedOutAt.isNull() }
            .map { it[w.issueKey] to IssueRef(it[w.connectionId].value, it[w.issueId]) }.toList()
    }.groupBy({ it.first }) { it.second }.mapValues { it.value.toSet() }
}

/**
 * The live merge requests of [gitlabIds] that name one of [keys], one row per (merge request, key) - the task -> merge request read, on
 * `idx_norm_code_mr_issue_keys_key`. Charged to [budget]: a read over the budget is the 400 of Report 21.
 */
internal suspend fun fetchTaskLinks(gitlabIds: Collection<UInt>, keys: Collection<String>, budget: CodeRowBudget): List<TaskCodeLink> {
    if (gitlabIds.isEmpty() || keys.isEmpty()) return emptyList()
    val k = CodeMrIssueKeys
    val m = CodeMergeRequests
    val source = k.join(m, JoinType.INNER, k.connectionId, m.connectionId, additionalConstraint = { k.mrId eq m.mrId })
    return keys.distinct().chunked(IN_CHUNK).flatMap { chunk ->
        source.select(k.issueKey, k.connectionId, k.mrId, m.state, m.mergedAt)
            .where { (k.connectionId inList gitlabIds) and (k.issueKey inList chunk) and m.deletedAt.isNull() }
            .toBudgetedList(budget) {
                val merged = it[m.state] == MR_MERGED
                TaskCodeLink(it[k.issueKey], CodeMrRef(it[k.connectionId].value, it[k.mrId]), if (merged) it[m.mergedAt] else null)
            }
    }
}
