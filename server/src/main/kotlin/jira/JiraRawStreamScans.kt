package ch.nokillswit.jira

import ch.nokillswit.jira.JiraRawStore.Issues
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The CHANGELOGS and WORKLOGS streams' claim scans over `raw.jira_issues` (v0.2.0 plan §7) and the stamps that close
 * them: which issues still need their history/worklogs fetched, which worklog issue ids are in scope.
 */
internal class JiraRawStreamScans(private val database: R2dbcDatabase) {

    /**
     * The CHANGELOGS stream's claim scan (v0.2.0 plan §7): in-scope (never tombstoned) issues whose
     * history has never been synced, OR changed since it last was — ascending issue id (matching
     * `sample-data`'s global chronological id assignment, so a batch of [limit] ascending ids lines
     * up with the stub's own fixed 50-id bulkfetch chunks), batches of [limit]
     * (`jira.changelogBulkSize`).
     */
    suspend fun staleChangelogIssueIds(connectionId: UInt, limit: Int): List<Long> = suspendTransaction(database) {
        Issues.select(Issues.issueId)
            .where {
                (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and
                    (Issues.changelogSyncedAt.isNull() or (Issues.changelogSyncedAt less Issues.changedAt))
            }
            .orderBy(Issues.issueId)
            .limit(limit)
            .map { it[Issues.issueId] }.toList()
    }

    /** Marks every id in [issueIds] as changelog-synced — called in the SAME transaction as their history inserts. */
    suspend fun markChangelogSynced(connectionId: UInt, issueIds: List<Long>, now: Long) {
        if (issueIds.isEmpty()) return
        suspendTransaction(database) {
            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId inList issueIds) }) {
                it[changelogSyncedAt] = now
                it[needsProcessing] = true
            }
        }
    }

    /** The WORKLOGS stream's per-issue backfill scan (v0.2.0 plan §7 "WORKLOGS", A1): in-scope issues never worklog-synced. */
    suspend fun staleWorklogIssueIds(connectionId: UInt, limit: Int): List<Long> = suspendTransaction(database) {
        Issues.select(Issues.issueId)
            .where { (Issues.connectionId eq connectionId) and Issues.deletedAt.isNull() and Issues.worklogsSyncedAt.isNull() }
            .orderBy(Issues.issueId)
            .limit(limit)
            .map { it[Issues.issueId] }.toList()
    }

    /** Marks one issue as worklog-synced — called in the SAME transaction as its worklog-page upserts. */
    suspend fun markWorklogsSynced(connectionId: UInt, issueId: Long, now: Long) {
        suspendTransaction(database) {
            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) }) {
                it[worklogsSyncedAt] = now
                it[needsProcessing] = true
            }
        }
    }

    /**
     * A1's scope filter (v0.2.0 plan §0/§7 "WORKLOGS"): which of [issueIds] are a KNOWN,
     * non-tombstoned `raw.jira_issues` row for [connectionId] — the WORKLOGS stream's incremental
     * step calls this AFTER fetching `worklog/list` details (only that response carries `issueId`)
     * and BEFORE writing anything, so an out-of-scope worklog is dropped without ever reaching
     * `raw.jira_worklogs`.
     */
    suspend fun knownInScopeIssueIds(connectionId: UInt, issueIds: List<Long>): Set<Long> {
        if (issueIds.isEmpty()) return emptySet()
        return suspendTransaction(database) {
            Issues.select(Issues.issueId)
                .where {
                    (Issues.connectionId eq connectionId) and (Issues.issueId inList issueIds) and
                        Issues.deletedAt.isNull() and Issues.movedOutAt.isNull()
                }
                .map { it[Issues.issueId] }.toList().toSet()
        }
    }
}
