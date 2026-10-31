package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.gitlab.GitLabRawStore.Users
import ch.nokillswit.ingest.GitLabSyncCounts
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The GitLab raw store's reads: the status counts (`ingest/SyncStatusRoutes.kt`), the users freshness, and the rows tests inspect. */
internal class GitLabRawReads(private val database: R2dbcDatabase) {

    suspend fun counts(connectionId: UInt): GitLabSyncCounts = suspendTransaction(database) {
        val projects = Projects.select(Projects.movedOutAt, Projects.deletedAt).where { Projects.connectionId eq connectionId }.toList()
        val users = Users.select(Users.deletedAt).where { Users.connectionId eq connectionId }.toList()
        GitLabSyncCounts(
            projects = projects.count { it[Projects.deletedAt] == null && it[Projects.movedOutAt] == null }.toLong(),
            projectsMovedOut = projects.count { it[Projects.movedOutAt] != null && it[Projects.deletedAt] == null }.toLong(),
            projectsDeleted = projects.count { it[Projects.deletedAt] != null }.toLong(),
            users = users.count { it[Users.deletedAt] == null }.toLong(),
            usersDeleted = users.count { it[Users.deletedAt] != null }.toLong(),
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
