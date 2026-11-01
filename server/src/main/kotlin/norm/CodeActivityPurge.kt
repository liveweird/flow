package ch.nokillswit.norm

import ch.nokillswit.norm.CodeActivityStore.CodeCommits
import ch.nokillswit.norm.CodeActivityStore.CodeIdentities
import ch.nokillswit.norm.CodeActivityStore.CodeMergeRequests
import ch.nokillswit.norm.CodeActivityStore.CodeMrEvents
import ch.nokillswit.norm.CodeActivityStore.CodeMrIssueKeys
import ch.nokillswit.norm.CodeActivityStore.CodeProjects
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The PURGE step's deletes over `norm.code_*` ([CodeActivityStore] delegates here): one bounded batch per big table per call (the
 * [NormPurge] shape: select a batch of keys, delete them), so a connection with a large history drains in bounded transactions. Events are
 * batched by their own key `(mr_id, seq)` and issue keys by theirs `(mr_id, issue_key)`, never by merge request: nothing cascades between
 * these FK-free tables, so a statement deletes exactly its batch. The two small reference tables are cleared outright. The drain loop is
 * [purgeAll] below.
 */
internal class CodeActivityPurge(private val database: R2dbcDatabase) {

    suspend fun purgeMrIssueKeysBatch(connectionId: UInt, batchSize: Int): Int = suspendTransaction(database) {
        val keys = CodeMrIssueKeys.select(CodeMrIssueKeys.mrId, CodeMrIssueKeys.issueKey)
            .where { CodeMrIssueKeys.connectionId eq connectionId }
            .limit(batchSize).map { it[CodeMrIssueKeys.mrId] to it[CodeMrIssueKeys.issueKey] }.toList()
        if (keys.isEmpty()) return@suspendTransaction 0
        CodeMrIssueKeys.deleteWhere {
            (CodeMrIssueKeys.connectionId eq connectionId) and ((CodeMrIssueKeys.mrId to CodeMrIssueKeys.issueKey) inList keys)
        }
    }

    suspend fun purgeMrEventsBatch(connectionId: UInt, batchSize: Int): Int = suspendTransaction(database) {
        val keys = CodeMrEvents.select(CodeMrEvents.mrId, CodeMrEvents.seq).where { CodeMrEvents.connectionId eq connectionId }
            .limit(batchSize).map { it[CodeMrEvents.mrId] to it[CodeMrEvents.seq] }.toList()
        if (keys.isEmpty()) return@suspendTransaction 0
        CodeMrEvents.deleteWhere {
            (CodeMrEvents.connectionId eq connectionId) and ((CodeMrEvents.mrId to CodeMrEvents.seq) inList keys)
        }
    }

    suspend fun purgeMergeRequestsBatch(connectionId: UInt, batchSize: Int): Int = suspendTransaction(database) {
        val ids = CodeMergeRequests.select(CodeMergeRequests.mrId).where { CodeMergeRequests.connectionId eq connectionId }
            .limit(batchSize).map { it[CodeMergeRequests.mrId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        CodeMergeRequests.deleteWhere { (CodeMergeRequests.connectionId eq connectionId) and (CodeMergeRequests.mrId inList ids) }
    }

    suspend fun purgeCommitsBatch(connectionId: UInt, batchSize: Int): Int = suspendTransaction(database) {
        val keys = CodeCommits.select(CodeCommits.projectId, CodeCommits.sha).where { CodeCommits.connectionId eq connectionId }
            .limit(batchSize).map { it[CodeCommits.projectId] to it[CodeCommits.sha] }.toList()
        if (keys.isEmpty()) return@suspendTransaction 0
        CodeCommits.deleteWhere {
            (CodeCommits.connectionId eq connectionId) and ((CodeCommits.projectId to CodeCommits.sha) inList keys)
        }
    }

    /** The identities and projects are rebuilt wholesale already: PURGE clears them outright, no batching needed. */
    suspend fun purgeReferenceRows(connectionId: UInt) {
        suspendTransaction(database) {
            CodeIdentities.deleteWhere { CodeIdentities.connectionId eq connectionId }
            CodeProjects.deleteWhere { CodeProjects.connectionId eq connectionId }
        }
    }
}

/**
 * Drains a connection's `norm.code_*` rows in batches - the PURGE step, the [WorkItemStore.purgeAll] twin: issue keys, events, merge
 * requests, commits, then the reference tables. The batch size defaults to [NORM_PURGE_BATCH_SIZE].
 */
suspend fun CodeActivityStore.purgeAll(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE) {
    while (purgeMrIssueKeysBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeMrEventsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeMergeRequestsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeCommitsBatch(connectionId, batchSize) > 0) { /* drain */ }
    purgeReferenceRows(connectionId)
}
