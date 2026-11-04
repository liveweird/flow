package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
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
 * The merge-requests batch counts MERGE REQUESTS, not rows, and each cascades its events: up to 1,000 notes (the notes cap) plus its
 * approvers and reviewers, ~1,100 rows. 20 bound one statement to ~22,000 cascaded rows (the commits batch's budget of ~25,000), where the
 * 500 of the other tables would be ~550,000.
 */
const val GITLAB_PURGE_MRS_BATCH_SIZE = 20

/**
 * The PURGE step's batched deletes over the `raw.gitlab_*` tables (the commit files go with their commits, V42's, and the merge-request
 * events with their merge requests, V43's, `ON DELETE CASCADE`);
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

    /** Deletes up to [batchSize] merge requests; their events cascade (~1,100 rows each, hence [GITLAB_PURGE_MRS_BATCH_SIZE]). */
    suspend fun purgeMergeRequestsBatch(connectionId: UInt, batchSize: Int): Int = suspendTransaction(database) {
        val ids = MergeRequests.select(MergeRequests.mrId).where { MergeRequests.connectionId eq connectionId }.limit(batchSize)
            .map { it[MergeRequests.mrId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        MergeRequests.deleteWhere { (MergeRequests.connectionId eq connectionId) and (MergeRequests.mrId inList ids) }
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
    while (purgeMergeRequestsBatch(connectionId, minOf(batchSize, GITLAB_PURGE_MRS_BATCH_SIZE)) > 0) { /* drain */ }
    while (purgeUsersBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeProjectsBatch(connectionId, batchSize) > 0) { /* drain */ }
}
