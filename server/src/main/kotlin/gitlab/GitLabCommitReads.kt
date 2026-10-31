package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.CommitFiles
import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** A merge commit has two or more parents; the files backlog takes the rest (L4). */
private const val MAX_NON_MERGE_PARENTS: Short = 1

/** The commit-stream reads (V42): the projects to walk, the re-list window, the files backlog and the rows tests inspect. */
internal class GitLabCommitReads(private val database: R2dbcDatabase) {

    /** The projects the COMMITS stream walks: live (not moved out, not deleted), in project-id order. */
    suspend fun liveProjectsForCommits(connectionId: UInt): List<CommitsProjectRow> = suspendTransaction(database) {
        Projects.select(Projects.projectId, Projects.defaultBranch, Projects.commitsHeadSha, Projects.relistRequested)
            .where { (Projects.connectionId eq connectionId) and Projects.movedOutAt.isNull() and Projects.deletedAt.isNull() }
            .orderBy(Projects.projectId)
            .toList()
            .map {
                CommitsProjectRow(
                    it[Projects.projectId], it[Projects.defaultBranch], it[Projects.commitsHeadSha], it[Projects.relistRequested],
                )
            }
    }

    suspend fun oldestLiveCommittedAt(connectionId: UInt, projectId: Long): Long? = suspendTransaction(database) {
        val oldest = Commits.committedAt.min()
        Commits.select(oldest)
            .where { (Commits.connectionId eq connectionId) and (Commits.projectId eq projectId) and Commits.deletedAt.isNull() }
            .toList().singleOrNull()?.get(oldest)
    }

    /** The files-backlog predicate (the V42 partial index): diff unread, not a merge, not tombstoned. */
    private fun pending(connectionId: UInt): Op<Boolean> =
        (Commits.connectionId eq connectionId) and Commits.filesFetchedAt.isNull() and
            (Commits.parentCount lessEq MAX_NON_MERGE_PARENTS) and Commits.deletedAt.isNull()

    suspend fun pendingCommitFiles(connectionId: UInt, limit: Int): List<PendingCommit> = suspendTransaction(database) {
        Commits.select(Commits.projectId, Commits.sha).where { pending(connectionId) }
            .orderBy(Commits.committedAt to SortOrder.DESC, Commits.sha to SortOrder.ASC)
            .limit(limit)
            .toList()
            .map { PendingCommit(it[Commits.projectId], it[Commits.sha]) }
    }

    suspend fun countPendingCommitFiles(connectionId: UInt): Long = suspendTransaction(database) {
        Commits.selectAll().where { pending(connectionId) }.count()
    }

    suspend fun commitRows(connectionId: UInt, projectId: Long?): List<ResultRow> = suspendTransaction(database) {
        Commits.selectAll()
            .where { (Commits.connectionId eq connectionId) and (projectId?.let { Commits.projectId eq it } ?: Op.TRUE) }
            .orderBy(Commits.projectId to SortOrder.ASC, Commits.committedAt to SortOrder.DESC, Commits.sha to SortOrder.ASC)
            .toList()
    }

    suspend fun commitFileRows(connectionId: UInt, projectId: Long, sha: String): List<ResultRow> = suspendTransaction(database) {
        CommitFiles.selectAll()
            .where { (CommitFiles.connectionId eq connectionId) and (CommitFiles.projectId eq projectId) and (CommitFiles.sha eq sha) }
            .orderBy(CommitFiles.path)
            .toList()
    }
}
