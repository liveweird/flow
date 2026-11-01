package ch.nokillswit.reports

import ch.nokillswit.norm.CodeActivityStore.CodeCommits
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.norm.CodeActivityStore.CodeMergeRequests
import ch.nokillswit.norm.CodeActivityStore.CodeMrIssueKeys
import ch.nokillswit.norm.CodeActivityStore.CodeProjects
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Report 21's drill labels: the project path, the merge request's iid and title and the commit's first line, read for the LISTED rows only
 * (at most DRILL_MAX_ITEMS of them, so every list below is short and one statement each is enough). The rows themselves identify a merge
 * request by (connection, mr_id) and a commit by (connection, project, sha); this is where they become `<path>!<iid>` / `<path>@<sha12>`.
 * A merge request's row also gets the Jira keys it names (`linkedKeys`, A51): its stored candidates (`norm.code_mr_issue_keys`) scoped to
 * the known Jira projects ([knownProjectKeys]). Like the other labels they load no budgeted row: at most 500 merge requests, a few
 * candidates each.
 */

/** How many leading characters of a commit's sha an issue key carries (GitLab's own short form is 8; 12 keeps a key unambiguous). */
internal const val CODE_SHA_KEY_LENGTH = 12

/** The labels of a drill's rows. A row whose merge request or commit is gone (not expected: the rows were just read) gets no title. */
internal class CodeDrillLabels(
    private val mergeRequests: Map<CodeMrRef, MrLabel>,
    private val commits: Map<Triple<UInt, Long, String>, String>,
    private val paths: Map<Pair<UInt, Long>, String>,
    private val linkedKeys: Map<CodeMrRef, List<String>> = emptyMap(),
    /** Whether the Jira scope was requested ([fetchCodeDrillLabels] given a data source); unscoped, no row has a `linkedKeys` list. */
    private val scoped: Boolean = false,
) {
    internal class MrLabel(val projectId: Long, val iid: Long, val title: String)

    private fun pathOf(connectionId: UInt, projectId: Long): String = paths[connectionId to projectId] ?: "#$projectId"

    /** `<project path>!<iid>` or `<project path>@<sha12>`. */
    fun issueKey(row: CodeDrillRow): String = when (row.kind) {
        DrillItemKind.COMMIT -> "${pathOf(row.connectionId, row.projectId)}@${row.sha!!.take(CODE_SHA_KEY_LENGTH)}"
        else -> mergeRequests[row.mrRef]?.let { "${pathOf(row.connectionId, it.projectId)}!${it.iid}" } ?: "#mr-${row.mrId}"
    }

    /**
     * The Jira keys a merge request names within the known projects, sorted (empty when it names none); null for a commit, which is linked
     * through its merge request only (A51/D5), and for every row when the scope was not requested (Compare people's lists).
     */
    fun linkedKeys(row: CodeDrillRow): List<String>? =
        if (scoped && row.kind == DrillItemKind.MERGE_REQUEST) linkedKeys[row.mrRef].orEmpty() else null

    /** The merge request's title, or a commit's first line; null when blank or unknown. */
    fun summary(row: CodeDrillRow): String? {
        val raw = when (row.kind) {
            DrillItemKind.COMMIT -> commits[Triple(row.connectionId, row.projectId, row.sha!!)]
            else -> mergeRequests[row.mrRef]?.title
        }
        return raw?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The labels of [rows] (inside the caller's report transaction). [dataSources] scopes the issue keys of the merge requests; null (no
 * code layer wired for the caller) links nothing.
 */
internal suspend fun fetchCodeDrillLabels(rows: List<CodeDrillRow>, dataSources: DataSourceService?): CodeDrillLabels {
    val mrRows = rows.filter { it.kind == DrillItemKind.MERGE_REQUEST }
    val commitRows = rows.filter { it.kind == DrillItemKind.COMMIT }
    val mrs = fetchMrLabels(mrRows.map { it.mrRef })
    val commits = fetchCommitTitles(commitRows)
    val projects = (mrRows.mapNotNull { row -> mrs[row.mrRef]?.let { row.connectionId to it.projectId } } +
        commitRows.map { it.connectionId to it.projectId }).distinct()
    val linked = fetchLinkedKeys(mrRows.map { it.mrRef }, dataSources)
    return CodeDrillLabels(mrs, commits, fetchProjectPaths(projects), linked, scoped = dataSources != null)
}

/**
 * The Jira keys of [refs]: the stored candidates scoped to [knownProjectKeys]. The scope is read only when some listed merge request
 * names a candidate, so a drill of commits (or of merge requests naming nothing) reads no data source.
 */
private suspend fun fetchLinkedKeys(refs: List<CodeMrRef>, dataSources: DataSourceService?): Map<CodeMrRef, List<String>> {
    if (refs.isEmpty() || dataSources == null) return emptyMap()
    val k = CodeMrIssueKeys
    val wanted = refs.toSet()
    val candidates = k.select(k.connectionId, k.mrId, k.issueKey)
        .where { (k.connectionId inList wanted.map { it.connectionId }.distinct()) and (k.mrId inList wanted.map { it.mrId }.distinct()) }
        .toList().mapNotNull { row ->
            val ref = CodeMrRef(row[k.connectionId].value, row[k.mrId])
            if (ref in wanted) ref to row[k.issueKey] else null
        }.groupBy({ it.first }) { it.second }
    if (candidates.isEmpty()) return emptyMap()
    val known = knownProjectKeys(dataSources)
    return candidates.mapValues { (_, keys) -> scopedIssueKeys(keys, known) }
}

private suspend fun fetchMrLabels(refs: List<CodeMrRef>): Map<CodeMrRef, CodeDrillLabels.MrLabel> {
    if (refs.isEmpty()) return emptyMap()
    val m = CodeMergeRequests
    val wanted = refs.toSet()
    return m.select(m.connectionId, m.mrId, m.projectId, m.iid, m.title)
        .where { (m.connectionId inList wanted.map { it.connectionId }.distinct()) and (m.mrId inList wanted.map { it.mrId }.distinct()) }
        .toList().mapNotNull { row ->
            val ref = CodeMrRef(row[m.connectionId].value, row[m.mrId])
            if (ref in wanted) ref to CodeDrillLabels.MrLabel(row[m.projectId], row[m.iid], row[m.title]) else null
        }.toMap()
}

private suspend fun fetchCommitTitles(rows: List<CodeDrillRow>): Map<Triple<UInt, Long, String>, String> {
    if (rows.isEmpty()) return emptyMap()
    val c = CodeCommits
    val wanted = rows.map { Triple(it.connectionId, it.projectId, it.sha!!) }.toSet()
    return c.select(c.connectionId, c.projectId, c.sha, c.title)
        .where {
            (c.connectionId inList wanted.map { it.first }.distinct()) and (c.projectId inList wanted.map { it.second }.distinct()) and
                (c.sha inList wanted.map { it.third }.distinct())
        }
        .toList().mapNotNull { row ->
            val key = Triple(row[c.connectionId].value, row[c.projectId], row[c.sha])
            if (key in wanted) key to row[c.title] else null
        }.toMap()
}

private suspend fun fetchProjectPaths(projects: List<Pair<UInt, Long>>): Map<Pair<UInt, Long>, String> {
    if (projects.isEmpty()) return emptyMap()
    val p = CodeProjects
    val wanted = projects.toSet()
    return p.select(p.connectionId, p.projectId, p.pathWithNamespace)
        .where { (p.connectionId inList wanted.map { it.first }.distinct()) and (p.projectId inList wanted.map { it.second }.distinct()) }
        .toList().mapNotNull { row ->
            val key = row[p.connectionId].value to row[p.projectId]
            if (key in wanted) key to row[p.pathWithNamespace] else null
        }.toMap()
}
