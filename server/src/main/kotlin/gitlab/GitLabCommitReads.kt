package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.CommitFiles
import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.LiteralOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.ShortColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * A merge commit has two or more parents; the files backlog takes the rest (L4). Rendered as a SQL LITERAL, never a bind parameter:
 * the partial index `idx_raw_gitlab_commits_files_backlog` is declared `WHERE ... parent_count <= 1`, and a cached generic plan with
 * a `?` there cannot prove the predicate implies the index's, so it would never use the index.
 */
internal val NON_MERGE_PARENTS: Op<Boolean> = Commits.parentCount lessEq LiteralOp(ShortColumnType(), 1.toShort())

/**
 * The COMMIT FILES backlog (the V42 partial index plus the project): diff unread, not a merge, not tombstoned, and of a LIVE project
 * (`moved_out_at`/`deleted_at` null) - a moved-out or deleted project's commits cost no diff call and are not counted as pending
 * (`commitFilesPending`). The project is an `EXISTS` on its primary key, so the commits predicate stays the index's.
 */
internal val FILES_BACKLOG: Op<Boolean> = Commits.filesFetchedAt.isNull() and NON_MERGE_PARENTS and Commits.deletedAt.isNull() and
    exists(
        Projects.selectAll().where {
            (Projects.connectionId eq Commits.connectionId) and (Projects.projectId eq Commits.projectId) and
                Projects.movedOutAt.isNull() and Projects.deletedAt.isNull()
        },
    )

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

    private fun pending(connectionId: UInt): Op<Boolean> = (Commits.connectionId eq connectionId) and FILES_BACKLOG

    suspend fun staleCommitShas(
        connectionId: UInt,
        projectId: Long,
        sinceCommittedAt: Long,
        relistStartedAt: Long,
        limit: Int,
    ): List<String> = suspendTransaction(database) {
        Commits.select(Commits.sha)
            .where {
                (Commits.connectionId eq connectionId) and (Commits.projectId eq projectId) and Commits.deletedAt.isNull() and
                    (Commits.committedAt greaterEq sinceCommittedAt) and (Commits.lastSeenAt less relistStartedAt)
            }
            .orderBy(Commits.committedAt to SortOrder.DESC, Commits.sha to SortOrder.ASC)
            .limit(limit)
            .toList()
            .map { it[Commits.sha] }
    }

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
