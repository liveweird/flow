package ch.nokillswit.norm

import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.db.upsertRows
import ch.nokillswit.infra.json.stringArrayJson
import ch.nokillswit.norm.CodeActivityStore.CodeCommits
import ch.nokillswit.norm.CodeActivityStore.CodeIdentities
import ch.nokillswit.norm.CodeActivityStore.CodeMergeRequests
import ch.nokillswit.norm.CodeActivityStore.CodeMrEvents
import ch.nokillswit.norm.CodeActivityStore.CodeMrIssueKeys
import ch.nokillswit.norm.CodeActivityStore.CodeProjects
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The code layer's PROCESS write path ([CodeActivityStore] delegates here). Two shapes, as V44's header says: the REFERENCE rows
 * (projects, identities) are rebuilt wholesale - the connection's rows deleted and the current set inserted - and the FACTS (commits, merge
 * requests) are upserted on the raw primary key, a merge request's events and issue keys replaced with it. Each method is ONE transaction
 * that joins the caller's enclosing one (the PROCESS stream's page transaction also holds the raw rows' marks). The fact writers skip empty
 * input; the wholesale rebuilds (`replaceProjects`, `replaceIdentities`) always delete first, so an empty list empties them.
 * No row is ever deleted outside a wholesale rebuild or a PURGE: a tombstone is MIRRORED in `deleted_at`.
 */
internal class CodeActivityWriter(private val database: R2dbcDatabase) {

    suspend fun replaceProjects(connectionId: UInt, rows: List<NormCodeProject>) {
        suspendTransaction(database) {
            CodeProjects.deleteWhere { CodeProjects.connectionId eq connectionId }
            CodeProjects.insertRows(rows) { project ->
                this[CodeProjects.connectionId] = connectionId
                this[CodeProjects.projectId] = project.projectId
                this[CodeProjects.pathWithNamespace] = project.pathWithNamespace
                this[CodeProjects.groupPath] = project.groupPath
                this[CodeProjects.defaultBranch] = project.defaultBranch
                this[CodeProjects.archived] = project.archived
                this[CodeProjects.movedOutAt] = project.movedOutAt
                this[CodeProjects.deletedAt] = project.deletedAt
            }
        }
    }

    suspend fun replaceIdentities(connectionId: UInt, rows: List<NormCodeIdentity>): Int = suspendTransaction(database) {
        CodeIdentities.deleteWhere { CodeIdentities.connectionId eq connectionId }
        CodeIdentities.insertRows(rows) { identity ->
            this[CodeIdentities.connectionId] = connectionId
            this[CodeIdentities.identityKey] = identity.key
            this[CodeIdentities.kind] = identity.kind.name
            this[CodeIdentities.userId] = identity.userId
            this[CodeIdentities.username] = identity.username
            this[CodeIdentities.displayName] = identity.displayName
            this[CodeIdentities.state] = identity.state
            this[CodeIdentities.bot] = identity.bot
            this[CodeIdentities.emails] = stringArrayJson(identity.emails)
            this[CodeIdentities.deletedAt] = identity.deletedAt
        }
        rows.size
    }

    suspend fun replaceCommits(connectionId: UInt, rows: List<NormCodeCommit>, now: Long, version: Int) {
        if (rows.isEmpty()) return
        suspendTransaction(database) {
            CodeCommits.upsertRows(rows, keys = listOf(CodeCommits.connectionId, CodeCommits.projectId, CodeCommits.sha)) { commit ->
                this[CodeCommits.connectionId] = connectionId
                this[CodeCommits.projectId] = commit.projectId
                this[CodeCommits.sha] = commit.sha
                this[CodeCommits.identityKey] = commit.identityKey
                this[CodeCommits.title] = commit.title
                this[CodeCommits.authoredAt] = commit.authoredAt
                this[CodeCommits.committedAt] = commit.committedAt
                this[CodeCommits.isMerge] = commit.isMerge
                this[CodeCommits.additions] = commit.additions
                this[CodeCommits.deletions] = commit.deletions
                this[CodeCommits.files] = commit.files
                this[CodeCommits.additionsCounted] = commit.additionsCounted
                this[CodeCommits.deletionsCounted] = commit.deletionsCounted
                this[CodeCommits.filesCounted] = commit.filesCounted
                this[CodeCommits.filesCapped] = commit.filesCapped
                this[CodeCommits.deletedAt] = commit.deletedAt
                this[CodeCommits.processedAt] = now
                this[CodeCommits.processingVersion] = version
            }
        }
    }

    /**
     * REPLACE a page: the events and issue keys of the page's merge requests deleted, the fresh ones inserted, the merge request rows
     * upserted. A tombstoned merge request is replaced like any other (its keys follow its title and branch; the reports read live rows).
     */
    suspend fun replaceMergeRequests(connectionId: UInt, rows: List<NormCodeMergeRequest>, now: Long, version: Int) {
        if (rows.isEmpty()) return
        suspendTransaction(database) {
            val ids = rows.map { it.mrId }
            CodeMrEvents.deleteWhere { (CodeMrEvents.connectionId eq connectionId) and (CodeMrEvents.mrId inList ids) }
            val events = rows.flatMap { mr -> mr.events.map { mr.mrId to it } }
            CodeMrEvents.insertRows(events) { (mrId, event) ->
                this[CodeMrEvents.connectionId] = connectionId
                this[CodeMrEvents.mrId] = mrId
                this[CodeMrEvents.seq] = event.seq
                this[CodeMrEvents.kind] = event.kind.name
                this[CodeMrEvents.actorKey] = event.actorKey
                this[CodeMrEvents.eventAt] = event.eventAt
                this[CodeMrEvents.targetKeys] = event.targetKeys.takeIf { it.isNotEmpty() }?.let(::stringArrayJson)
            }
            CodeMrIssueKeys.deleteWhere { (CodeMrIssueKeys.connectionId eq connectionId) and (CodeMrIssueKeys.mrId inList ids) }
            val keys = rows.flatMap { mr -> mr.issueKeys.map { mr.mrId to it } }
            CodeMrIssueKeys.insertRows(keys) { (mrId, key) ->
                this[CodeMrIssueKeys.connectionId] = connectionId
                this[CodeMrIssueKeys.mrId] = mrId
                this[CodeMrIssueKeys.issueKey] = key.issueKey
                this[CodeMrIssueKeys.inTitle] = key.inTitle
                this[CodeMrIssueKeys.inBranch] = key.inBranch
            }
            CodeMergeRequests.upsertRows(rows, keys = listOf(CodeMergeRequests.connectionId, CodeMergeRequests.mrId)) { mr ->
                this[CodeMergeRequests.connectionId] = connectionId
                this[CodeMergeRequests.mrId] = mr.mrId
                this[CodeMergeRequests.projectId] = mr.projectId
                this[CodeMergeRequests.iid] = mr.iid
                this[CodeMergeRequests.state] = mr.state
                this[CodeMergeRequests.draft] = mr.draft
                this[CodeMergeRequests.authorKey] = mr.authorKey
                this[CodeMergeRequests.mergeUserKey] = mr.mergeUserKey
                this[CodeMergeRequests.createdAt] = mr.createdAt
                this[CodeMergeRequests.updatedAt] = mr.updatedAt
                this[CodeMergeRequests.mergedAt] = mr.mergedAt
                this[CodeMergeRequests.closedAt] = mr.closedAt
                this[CodeMergeRequests.sourceBranch] = mr.sourceBranch
                this[CodeMergeRequests.targetBranch] = mr.targetBranch
                this[CodeMergeRequests.title] = mr.title
                this[CodeMergeRequests.headSha] = mr.headSha
                this[CodeMergeRequests.mergeCommitSha] = mr.mergeCommitSha
                this[CodeMergeRequests.squashCommitSha] = mr.squashCommitSha
                this[CodeMergeRequests.reviewerKeys] = stringArrayJson(mr.reviewerKeys)
                this[CodeMergeRequests.assigneeKeys] = stringArrayJson(mr.assigneeKeys)
                this[CodeMergeRequests.additions] = mr.additions
                this[CodeMergeRequests.deletions] = mr.deletions
                this[CodeMergeRequests.fileCount] = mr.fileCount
                this[CodeMergeRequests.notesCapped] = mr.notesCapped
                this[CodeMergeRequests.detailsState] = mr.detailsState.name
                this[CodeMergeRequests.deletedAt] = mr.deletedAt
                this[CodeMergeRequests.processedAt] = now
                this[CodeMergeRequests.processingVersion] = version
            }
        }
    }
}
