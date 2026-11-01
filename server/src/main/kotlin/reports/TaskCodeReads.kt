package ch.nokillswit.reports

import ch.nokillswit.ingest.DataSourceService
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
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Report 22's SQL reads (`.claude/docs/reports.md` "Report 22", A51/D3-D4), read-only inside the caller's report transaction. The merge
 * requests are read in the TASK -> merge request direction: the keys the done tasks have are looked up on the V46 index
 * `(connection_id, issue_key)`, never a scan of the merge requests; only those link rows are budgeted rows ([CodeRowBudget]). The one
 * connection-wide read is the DISTINCT candidate keys of the live merge requests (strings, not rows: the quality counts and the
 * ambiguity check need them). Tombstoned merge requests (`deleted_at`) and tombstoned Jira issues (`deleted_at` / `moved_out_at`, the
 * rows DERIVE skips) are never read.
 */

/** `IN` lists are cut into statements of at most this many values (well under the driver's bind limit). */
private const val IN_CHUNK = 5_000

/** What the `issuekey` changes of the live issues say: [issuesByKey] every key (old or new) to its issues, [keysByIssue] the inverse. */
internal class IssueKeyHistory(val issuesByKey: Map<String, Set<IssueRef>>, val keysByIssue: Map<IssueRef, Set<String>>)

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
    return t.select(t.connectionId, t.issueId, t.issueKey, t.startedAt, t.doneAt).where { predicate }
        .map { TaskCodeTask(IssueRef(it[t.connectionId].value, it[t.issueId]), it[t.issueKey], it[t.startedAt], it[t.doneAt]!!) }
        .toList()
}

/**
 * Every key a live issue of [jiraIds] had or has by its `issuekey` changes (a move to another project; real Jira sends the keys in the
 * `*_text` columns, the stub in the value columns too). Small by nature: moves are rare, and the read rides
 * `idx_norm_work_item_field_changes_field`.
 */
internal suspend fun fetchIssueKeyHistory(jiraIds: Collection<UInt>): IssueKeyHistory {
    if (jiraIds.isEmpty()) return IssueKeyHistory(emptyMap(), emptyMap())
    val f = FieldChanges
    val w = WorkItems
    val rows = f.join(w, JoinType.INNER, f.connectionId, w.connectionId, additionalConstraint = { f.issueId eq w.issueId })
        .select(f.connectionId, f.issueId, f.fromValue, f.fromText, f.toValue, f.toText)
        .where { (f.connectionId inList jiraIds) and (f.fieldId eq ISSUE_KEY_FIELD_ID) and w.deletedAt.isNull() and w.movedOutAt.isNull() }
        .toList()
    val pairs = rows.flatMap { row ->
        val ref = IssueRef(row[f.connectionId].value, row[f.issueId])
        listOf(row[f.fromValue], row[f.fromText], row[f.toValue], row[f.toText]).filter { projectKeyFromIssueKey(it) != null }
            .map { key -> key!! to ref }
    }
    return IssueKeyHistory(
        issuesByKey = pairs.groupBy({ it.first }) { it.second }.mapValues { it.value.toSet() },
        keysByIssue = pairs.groupBy({ it.second }) { it.first }.mapValues { it.value.toSet() },
    )
}

/**
 * The distinct candidate keys the LIVE merge requests of [gitlabIds] name (`norm.code_mr_issue_keys`): strings, not rows, so not charged
 * to the row budget. Every candidate the extractor admits is in it (`UTF-8` too); the caller scopes it to the known Jira projects.
 */
internal suspend fun fetchCandidateKeys(gitlabIds: Collection<UInt>): Set<String> {
    if (gitlabIds.isEmpty()) return emptySet()
    val k = CodeMrIssueKeys
    val m = CodeMergeRequests
    return k.join(m, JoinType.INNER, k.connectionId, m.connectionId, additionalConstraint = { k.mrId eq m.mrId })
        .select(k.issueKey).withDistinct()
        .where { (k.connectionId inList gitlabIds) and m.deletedAt.isNull() }
        .map { it[k.issueKey] }.toList().toSet()
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

/** The known Jira projects of the request's Jira sources: their configured `projectKeys` plus the prefixes of their `issuekey` moves. */
internal suspend fun knownProjectsOf(dataSources: DataSourceService, jiraIds: Collection<UInt>, history: IssueKeyHistory): Set<String> {
    val configured = dataSources.activeJiraProjectKeys().filterKeys { it in jiraIds }.values.flatten()
    return configured.toSet() + history.issuesByKey.keys.mapNotNull(::projectKeyFromIssueKey)
}
