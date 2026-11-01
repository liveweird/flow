package ch.nokillswit.reports

import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.ISSUE_KEY_FIELD_ID
import ch.nokillswit.norm.WorkItemStore.FieldChanges
import ch.nokillswit.norm.projectKeyFromIssueKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The READ-TIME scope of the issue-key candidates a merge request names (`.claude/docs/gitlab-integration.md` "Issue keys", A51/D3).
 * PROCESS stores every candidate (`UTF-8` and `SHA-256` included) with no Jira knowledge; a report shows only the ones whose project
 * part is a KNOWN Jira project. Whether the key resolves to an existing issue is deliberately NOT asked here: a key of a known project
 * is shown even when Jira has no such issue (it is a link, and Jira answers 404).
 */

/**
 * The known project keys: the `projectKeys` of every ACTIVE Jira data source (enabled or not, soft delete respected: the same set as
 * [DataSourceService.activeJiraProjectKeys]) plus the project prefixes of every `issuekey` change of those sources -- the OLD projects
 * of issues that were moved (a merge request still names `OLD-12`). A GitLab source does not pick Jira sources: every active one counts.
 * Call it inside the report transaction (the change read is one small statement on `idx_norm_work_item_field_changes_field`).
 */
internal suspend fun knownProjectKeys(dataSources: DataSourceService): Set<String> {
    val configured = dataSources.activeJiraProjectKeys()
    if (configured.isEmpty()) return emptySet()
    val f = FieldChanges
    val moved = f.select(f.fromValue, f.fromText, f.toValue, f.toText).withDistinct()
        .where { (f.connectionId inList configured.keys) and (f.fieldId eq ISSUE_KEY_FIELD_ID) }
        .toList().flatMap { listOf(it[f.fromValue], it[f.fromText], it[f.toValue], it[f.toText]) }
        .mapNotNullTo(HashSet(), ::projectKeyFromIssueKey)
    return configured.values.flatten().toSet() + moved
}

/**
 * `keys:<connection id>:<sha256 of the sorted configured project keys>` per active Jira source, in connection order: the validator
 * lines of the known set's configured half (a project-list edit bumps no revision a report stamps, `connection:` does not carry
 * `projectKeys`). The issue-key changes the other half reads are the Jira star's own, covered by its `ingest:` and `run:` lines.
 */
internal suspend fun issueKeyScopeStamp(dataSources: DataSourceService): List<String> =
    dataSources.activeJiraProjectKeys().toSortedMap().map { (id, keys) -> "keys:$id:${sha256Hex(keys.sorted().joinToString(","))}" }

/** [keys] of [candidates] whose project is in [known], sorted and distinct. */
internal fun scopedIssueKeys(candidates: Collection<String>, known: Set<String>): List<String> =
    candidates.filter { projectKeyFromIssueKey(it) in known }.distinct().sorted()
