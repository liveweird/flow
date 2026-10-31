package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.gitlab.GitLabRawStore.Users
import ch.nokillswit.ingest.GitLabSyncCounts
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The GitLab raw store's reads: the status counts (`ingest/SyncStatusRoutes.kt`), the users freshness, and the rows tests inspect. */
internal class GitLabRawReads(private val database: R2dbcDatabase) {

    /** The nine commit-side figures as database-side `count(*)`s (no row is loaded), joined with the five merge-request [mr] ones. */
    suspend fun counts(connectionId: UInt, mr: MrCounts): GitLabSyncCounts = suspendTransaction(database) {
        suspend fun projects(extra: Op<Boolean>) = Projects.selectAll().where { (Projects.connectionId eq connectionId) and extra }.count()
        suspend fun users(extra: Op<Boolean>) = Users.selectAll().where { (Users.connectionId eq connectionId) and extra }.count()
        suspend fun commits(extra: Op<Boolean>) = Commits.selectAll().where { (Commits.connectionId eq connectionId) and extra }.count()
        GitLabSyncCounts(
            projects = projects(Projects.deletedAt.isNull() and Projects.movedOutAt.isNull()),
            projectsMovedOut = projects(Projects.movedOutAt.isNotNull() and Projects.deletedAt.isNull()),
            projectsDeleted = projects(Projects.deletedAt.isNotNull()),
            users = users(Users.deletedAt.isNull()),
            usersDeleted = users(Users.deletedAt.isNotNull()),
            commits = commits(Commits.deletedAt.isNull()),
            commitsMerge = commits(Commits.deletedAt.isNull() and (Commits.parentCount greater 1.toShort())),
            commitsDeleted = commits(Commits.deletedAt.isNotNull()),
            commitFilesPending = commits(FILES_BACKLOG),
            mergeRequests = mr.mergeRequests,
            mergeRequestsMerged = mr.mergeRequestsMerged,
            mrEvents = mr.mrEvents,
            mrDetailsPending = mr.mrDetailsPending,
            mrDiffStatsMissing = mr.mrDiffStatsMissing,
        )
    }

    suspend fun oldestLiveUserSeenAt(connectionId: UInt): Long? = suspendTransaction(database) {
        val oldest = Users.lastSeenAt.min()
        Users.select(oldest).where { (Users.connectionId eq connectionId) and Users.deletedAt.isNull() }
            .toList().singleOrNull()?.get(oldest)
    }

    suspend fun projectRows(connectionId: UInt): List<ResultRow> = suspendTransaction(database) {
        Projects.selectAll().where { Projects.connectionId eq connectionId }.orderBy(Projects.projectId).toList()
    }

    suspend fun userRows(connectionId: UInt): List<ResultRow> = suspendTransaction(database) {
        Users.selectAll().where { Users.connectionId eq connectionId }.orderBy(Users.userId).toList()
    }
}
