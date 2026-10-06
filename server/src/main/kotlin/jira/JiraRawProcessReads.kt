package ch.nokillswit.jira

import ch.nokillswit.jira.JiraRawStore.Changelogs
import ch.nokillswit.jira.JiraRawStore.Entities
import ch.nokillswit.jira.JiraRawStore.Issues
import ch.nokillswit.jira.JiraRawStore.RawIssueForProcessing
import ch.nokillswit.jira.JiraRawStore.Worklogs
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.CaseWhen
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The PROCESS step's raw-store half (v0.2.0 plan §7/§8): the claim scan, the per-issue and per-page input reads
 * (`FOR UPDATE`, fixed lock order), and the processed marks.
 */
internal class JiraRawProcessReads(private val database: R2dbcDatabase) {

    /**
     * The PROCESS step's claim scan (v0.2.0 plan §7/§8, plan commit 8a): every raw issue still
     * needing a rebuild — flagged `needs_processing`, OR whose `processing_version` disagrees with
     * [currentProcessingVersion] (a version bump reprocesses everything automatically, plan §8),
     * ascending issue id, batches of [limit] (`jira/JiraProcessStream.kt`'s batch size of 50). With
     * [afterIssueId] the scan resumes strictly AFTER that id (keyset paging): the stream passes the
     * last id of the previous page, so an issue that failed and stays flagged is retried by the NEXT
     * PROCESS pass, never re-claimed forever inside this one.
     */
    suspend fun issuesToProcess(
        connectionId: UInt,
        currentProcessingVersion: Int,
        limit: Int,
        afterIssueId: Long? = null,
    ): List<Long> = suspendTransaction(database) {
        Issues.select(Issues.issueId)
            .where {
                (Issues.connectionId eq connectionId) and
                    (
                        (Issues.needsProcessing eq true) or
                            Issues.processingVersion.isNull() or
                            (Issues.processingVersion neq currentProcessingVersion)
                        ) and
                    (if (afterIssueId == null) Op.TRUE else (Issues.issueId greater afterIssueId))
            }
            .orderBy(Issues.issueId)
            .limit(limit)
            .map { it[Issues.issueId] }.toList()
    }

    /** One issue for PROCESS's per-issue fallback — `FOR UPDATE` like [issuesForProcessing] (same lock-until-commit reasoning). */
    suspend fun issueForProcessing(connectionId: UInt, issueId: Long): RawIssueForProcessing? = suspendTransaction(database) {
        Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) }
            .forUpdate()
            .toList().singleOrNull()?.let {
                RawIssueForProcessing(issueId, it[Issues.payload], it[Issues.sha256], it[Issues.deletedAt], it[Issues.movedOutAt])
            }
    }

    /**
     * [issueForProcessing] for a whole PROCESS page in ONE query — a row per issue still present
     * (a concurrent PURGE may have removed some; those are simply absent). It takes `FOR UPDATE`,
     * ordered by `issue_id` (a fixed lock order): called inside `JiraProcessStream`'s page
     * transaction, it holds each issue's raw row until the page commits, so a concurrent write that
     * re-flags an issue (`upsertIssue` on a changed payload) WAITS and lands AFTER the commit —
     * its `needs_processing = true` is never overwritten by this page's [markProcessedBatch], and
     * the payload read here is exactly the one the mark stamps. Held for ~50 rows over ~30 ms.
     */
    suspend fun issuesForProcessing(connectionId: UInt, issueIds: List<Long>): List<RawIssueForProcessing> {
        if (issueIds.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            Issues.selectAll().where { (Issues.connectionId eq connectionId) and (Issues.issueId inList issueIds) }
                .orderBy(Issues.issueId)
                .forUpdate()
                .map {
                    RawIssueForProcessing(
                        it[Issues.issueId], it[Issues.payload], it[Issues.sha256], it[Issues.deletedAt], it[Issues.movedOutAt],
                    )
                }
                .toList()
        }
    }

    /**
     * [changelogPayloadsForIssue] for a whole PROCESS page in ONE query: issue id → its histories,
     * oldest first — the SAME `(created_at, history_id)` order per issue. An issue without
     * changelogs is absent from the map.
     */
    suspend fun changelogPayloadsForIssues(connectionId: UInt, issueIds: List<Long>): Map<Long, List<String>> {
        if (issueIds.isEmpty()) return emptyMap()
        return suspendTransaction(database) {
            Changelogs.selectAll().where { (Changelogs.connectionId eq connectionId) and (Changelogs.issueId inList issueIds) }
                .toList()
                .groupBy { it[Changelogs.issueId] }
                .mapValues { (_, rows) ->
                    rows.sortedWith(compareBy({ it[Changelogs.createdAt] }, { it[Changelogs.historyId] })).map { it[Changelogs.payload] }
                }
        }
    }

    /** [worklogPayloadsForIssue] for a whole PROCESS page in ONE query: issue id → its non-tombstoned worklogs (absent when none). */
    suspend fun worklogPayloadsForIssues(connectionId: UInt, issueIds: List<Long>): Map<Long, List<String>> {
        if (issueIds.isEmpty()) return emptyMap()
        return suspendTransaction(database) {
            Worklogs.selectAll().where {
                (Worklogs.connectionId eq connectionId) and (Worklogs.issueId inList issueIds) and Worklogs.deletedAt.isNull()
            }.toList().groupBy({ it[Worklogs.issueId] }, { it[Worklogs.payload] })
        }
    }

    /**
     * [markProcessed] for a whole PROCESS page in ONE statement: each row's `processed_hash` is the
     * `sha256` that was READ for it ([shaByIssueId], from [issuesForProcessing]) — a per-row `CASE`,
     * never the column's value at update time, so the mark can only ever claim the payload that was
     * actually normalized.
     */
    suspend fun markProcessedBatch(connectionId: UInt, shaByIssueId: Map<Long, String>, now: Long, processingVersion: Int) {
        if (shaByIssueId.isEmpty()) return
        suspendTransaction(database) {
            val hash = shaByIssueId.entries.fold<Map.Entry<Long, String>, CaseWhen<String>?>(null) { acc, (issueId, sha) ->
                val condition = Issues.issueId eq issueId
                acc?.When(condition, stringParam(sha)) ?: Case().When(condition, stringParam(sha))
            }!!.Else(Issues.sha256)
            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId inList shaByIssueId.keys.toList()) }) {
                it[Issues.processedAt] = now
                it[Issues.processedHash] = hash
                it[Issues.processingVersion] = processingVersion
                it[Issues.needsProcessing] = false
            }
        }
    }

    /** Marks one issue processed (v0.2.0 plan §8 step 5) — called in the SAME transaction as its `norm.*` rows' write. */
    suspend fun markProcessed(connectionId: UInt, issueId: Long, processedHash: String, now: Long, processingVersion: Int) {
        suspendTransaction(database) {
            Issues.update({ (Issues.connectionId eq connectionId) and (Issues.issueId eq issueId) }) {
                it[Issues.processedAt] = now
                it[Issues.processedHash] = processedHash
                it[Issues.processingVersion] = processingVersion
                it[Issues.needsProcessing] = false
            }
        }
    }

    /** REPROCESS (v0.2.0 plan §7/§12 item 8): flags EVERY raw issue for [connectionId] for the next PROCESS pass. */
    suspend fun markAllNeedsProcessing(connectionId: UInt): Int = suspendTransaction(database) {
        Issues.update({ Issues.connectionId eq connectionId }) { it[needsProcessing] = true }
    }

    /** Every non-deleted `raw.jira_entities` payload of [kind] for a connection (plan §8) — PROCESS's own reference-row source. */
    suspend fun entityPayloadsByKind(connectionId: UInt, kind: String): List<String> = suspendTransaction(database) {
        Entities.select(Entities.payload)
            .where { (Entities.connectionId eq connectionId) and (Entities.kind eq kind) and Entities.deletedAt.isNull() }
            .map { it[Entities.payload] }.toList()
    }

    /** One issue's changelog histories, oldest first — `(created_at, history_id)` (v0.2.0 plan §8's tiling input order). */
    suspend fun changelogPayloadsForIssue(connectionId: UInt, issueId: Long): List<String> = suspendTransaction(database) {
        Changelogs.selectAll().where { (Changelogs.connectionId eq connectionId) and (Changelogs.issueId eq issueId) }
            .toList()
            .sortedWith(compareBy({ it[Changelogs.createdAt] }, { it[Changelogs.historyId] }))
            .map { it[Changelogs.payload] }
    }

    /** One issue's non-tombstoned worklogs (v0.2.0 plan §8) — `norm.work_item_worklogs`' source. */
    suspend fun worklogPayloadsForIssue(connectionId: UInt, issueId: Long): List<String> = suspendTransaction(database) {
        Worklogs.selectAll().where {
            (Worklogs.connectionId eq connectionId) and (Worklogs.issueId eq issueId) and Worklogs.deletedAt.isNull()
        }.map { it[Worklogs.payload] }.toList()
    }
}
