package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.gitlab.GitLabRawStore.Users
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** Batch size for the PURGE step's connector cleanup - bounds one DELETE's row count (the Jira batch size). */
const val GITLAB_PURGE_BATCH_SIZE = 500

/**
 * The commits batch counts COMMITS, not rows, and each cascades up to 1,000 file rows (the files cap): 25 commits bound one statement to
 * 25,000 cascaded file rows (plus their FK trigger work), where 500 would have been up to 500,000.
 */
const val GITLAB_PURGE_COMMITS_BATCH_SIZE = 25

/**
 * The PURGE step's batched deletes over the `raw.gitlab_*` tables (the commit files go with their commits, V42's `ON DELETE CASCADE`);
 * the drain loop is [purgeAll] below.
 */
internal class GitLabRawPurge(private val database: R2dbcDatabase) {

    /**
     * Deletes up to [batchSize] commits; their files cascade (up to 1,000 rows each, hence [GITLAB_PURGE_COMMITS_BATCH_SIZE]). Per project,
     * so a SHA shared by two projects (a fork) stays exact.
     */
    suspend fun purgeCommitsBatch(connectionId: UInt, batchSize: Int): Int = suspendTransaction(database) {
        val keys = Commits.select(Commits.projectId, Commits.sha).where { Commits.connectionId eq connectionId }.limit(batchSize)
            .map { it[Commits.projectId] to it[Commits.sha] }.toList()
        keys.groupBy({ it.first }, { it.second }).entries.sumOf { (projectId, shas) ->
            Commits.deleteWhere {
                (Commits.connectionId eq connectionId) and (Commits.projectId eq projectId) and (Commits.sha inList shas)
            }
        }
    }

    suspend fun purgeProjectsBatch(connectionId: UInt, batchSize: Int): Int = suspendTransaction(database) {
        val ids = Projects.select(Projects.projectId).where { Projects.connectionId eq connectionId }.limit(batchSize)
            .map { it[Projects.projectId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Projects.deleteWhere { (Projects.connectionId eq connectionId) and (Projects.projectId inList ids) }
    }

    suspend fun purgeUsersBatch(connectionId: UInt, batchSize: Int): Int = suspendTransaction(database) {
        val ids = Users.select(Users.userId).where { Users.connectionId eq connectionId }.limit(batchSize)
            .map { it[Users.userId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Users.deleteWhere { (Users.connectionId eq connectionId) and (Users.userId inList ids) }
    }
}

/** Drains a connection's GitLab raw rows in batches: the PURGE step (`ingest/Connector.kt`'s `PurgeStep`). */
suspend fun GitLabRawStore.purgeAll(connectionId: UInt, batchSize: Int = GITLAB_PURGE_BATCH_SIZE) {
    while (purgeCommitsBatch(connectionId, minOf(batchSize, GITLAB_PURGE_COMMITS_BATCH_SIZE)) > 0) { /* drain */ }
    while (purgeUsersBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeProjectsBatch(connectionId, batchSize) > 0) { /* drain */ }
}
