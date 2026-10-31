package ch.nokillswit.gitlab

import ch.nokillswit.gitlab.GitLabRawStore.CommitFiles
import ch.nokillswit.gitlab.GitLabRawStore.Commits
import ch.nokillswit.gitlab.GitLabRawStore.MergeRequests
import ch.nokillswit.gitlab.GitLabRawStore.MrEvents
import ch.nokillswit.gitlab.GitLabRawStore.Projects
import ch.nokillswit.gitlab.GitLabRawStore.Users
import ch.nokillswit.infra.json.parseStringArray
import ch.nokillswit.norm.NormCodeProject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The PROCESS side of the GitLab raw tables (V44's reader and marker; `GitLabProcessStream` is its caller): the keyset claim scans, the
 * page reads, the marks and the reference reads. A sibling of [GitLabRawStore] over the SAME `raw.gitlab_*` tables, not more delegations
 * on it (that file is at its size cap). Every method opens its own `suspendTransaction`, joining an enclosing one, so the stream's page
 * transaction holds the page's locks until its marks commit.
 *
 * **Claim:** a raw row is claimable when `needs_processing OR processing_version IS NULL OR processing_version <> version`, so a version
 * bump reprocesses everything with no migration. Scans are keyset (`> after`, ordered by the primary key), 50 rows a page.
 *
 * **Lock:** the page reads take `FOR NO KEY UPDATE`, never `FOR UPDATE` - `raw.gitlab_commit_files` and `raw.gitlab_mr_events` reference
 * their parents ("Row locks vs. FK locks"). A concurrent write that re-flags a row (a files flush, a tombstone, a changed merge request)
 * WAITS and lands after the mark, so its `needs_processing = true` is never overwritten by this page's mark, and what was read is exactly
 * what the mark stamps. A row a concurrent PURGE removed is absent from the read and simply not marked.
 *
 * No `processed_hash` here (unlike Jira): a commit has no hash, and a merge request's sha256 is the listing's, not PROCESS's input.
 */
class GitLabRawProcessStore(private val database: R2dbcDatabase) {

    private fun commitClaim(version: Int): Op<Boolean> =
        (Commits.needsProcessing eq true) or Commits.processingVersion.isNull() or (Commits.processingVersion neq version)

    private fun mergeRequestClaim(version: Int): Op<Boolean> =
        (MergeRequests.needsProcessing eq true) or MergeRequests.processingVersion.isNull() or (MergeRequests.processingVersion neq version)

    /** Up to [limit] claimable commit keys strictly after [after], ordered by `(project_id, sha)`. */
    suspend fun commitsToProcess(connectionId: UInt, version: Int, limit: Int, after: CommitKey? = null): List<CommitKey> =
        suspendTransaction(database) {
            val beyond: Op<Boolean> = after?.let {
                (Commits.projectId greater it.projectId) or ((Commits.projectId eq it.projectId) and (Commits.sha greater it.sha))
            } ?: Op.TRUE
            Commits.select(Commits.projectId, Commits.sha)
                .where { (Commits.connectionId eq connectionId) and commitClaim(version) and beyond }
                .orderBy(Commits.projectId).orderBy(Commits.sha).limit(limit).toList()
                .map { CommitKey(it[Commits.projectId], it[Commits.sha]) }
        }

    /** Up to [limit] claimable merge request ids strictly after [afterMrId], ascending. */
    suspend fun mergeRequestsToProcess(connectionId: UInt, version: Int, limit: Int, afterMrId: Long? = null): List<Long> =
        suspendTransaction(database) {
            MergeRequests.select(MergeRequests.mrId)
                .where {
                    (MergeRequests.connectionId eq connectionId) and mergeRequestClaim(version) and
                        (afterMrId?.let { MergeRequests.mrId greater it } ?: Op.TRUE)
                }
                .orderBy(MergeRequests.mrId).limit(limit).toList().map { it[MergeRequests.mrId] }
        }

    /** The page's commit rows, `FOR NO KEY UPDATE`, in key order (a fixed lock order); a key no longer present is absent. */
    suspend fun commitsForProcessing(connectionId: UInt, keys: List<CommitKey>): List<RawCommitForProcessing> {
        if (keys.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            Commits.selectAll()
                .where { (Commits.connectionId eq connectionId) and ((Commits.projectId to Commits.sha) inList keys.pairs()) }
                .orderBy(Commits.projectId).orderBy(Commits.sha)
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)
                .toList().map(::commitOf)
        }
    }

    /** The stored files of the [keys]' commits, by commit, ordered by path (a commit without files is absent). */
    suspend fun commitFilesForProcessing(connectionId: UInt, keys: List<CommitKey>): Map<CommitKey, List<RawCommitFileForProcessing>> {
        if (keys.isEmpty()) return emptyMap()
        return suspendTransaction(database) {
            CommitFiles.selectAll()
                .where { (CommitFiles.connectionId eq connectionId) and ((CommitFiles.projectId to CommitFiles.sha) inList keys.pairs()) }
                .orderBy(CommitFiles.path)
                .toList()
                .groupBy({ CommitKey(it[CommitFiles.projectId], it[CommitFiles.sha]) }) {
                    RawCommitFileForProcessing(it[CommitFiles.path], it[CommitFiles.additions], it[CommitFiles.deletions])
                }
        }
    }

    /** The page's merge request rows, `FOR NO KEY UPDATE`, in id order. */
    suspend fun mergeRequestsForProcessing(connectionId: UInt, ids: List<Long>): List<RawMergeRequestForProcessing> {
        if (ids.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            MergeRequests.selectAll().where { (MergeRequests.connectionId eq connectionId) and (MergeRequests.mrId inList ids) }
                .orderBy(MergeRequests.mrId)
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)
                .toList().map(::mergeRequestOf)
        }
    }

    /** The events of the [ids]' merge requests by merge request, each in primary-key order (a merge request without events is absent). */
    suspend fun mrEventsForProcessing(connectionId: UInt, ids: List<Long>): Map<Long, List<RawMrEventForProcessing>> {
        if (ids.isEmpty()) return emptyMap()
        return suspendTransaction(database) {
            MrEvents.selectAll().where { (MrEvents.connectionId eq connectionId) and (MrEvents.mrId inList ids) }
                .orderBy(MrEvents.mrId).orderBy(MrEvents.eventSource).orderBy(MrEvents.sourceId)
                .toList()
                .groupBy({ it[MrEvents.mrId] }) {
                    RawMrEventForProcessing(
                        source = it[MrEvents.eventSource],
                        sourceId = it[MrEvents.sourceId],
                        actorId = it[MrEvents.actorId],
                        eventAt = it[MrEvents.eventAt],
                        system = it[MrEvents.system],
                        action = it[MrEvents.action],
                        targets = it[MrEvents.targets]?.let(::parseStringArray).orEmpty(),
                    )
                }
        }
    }

    /** ONE `UPDATE … IN` for the page; called in the SAME transaction as the norm write. Clears the flag the read held the lock for. */
    suspend fun markCommitsProcessed(connectionId: UInt, keys: List<CommitKey>, now: Long, version: Int) {
        if (keys.isEmpty()) return
        suspendTransaction(database) {
            Commits.update({ (Commits.connectionId eq connectionId) and ((Commits.projectId to Commits.sha) inList keys.pairs()) }) {
                it[processedAt] = now
                it[processingVersion] = version
                it[needsProcessing] = false
            }
        }
    }

    suspend fun markMergeRequestsProcessed(connectionId: UInt, ids: List<Long>, now: Long, version: Int) {
        if (ids.isEmpty()) return
        suspendTransaction(database) {
            MergeRequests.update({ (MergeRequests.connectionId eq connectionId) and (MergeRequests.mrId inList ids) }) {
                it[processedAt] = now
                it[processingVersion] = version
                it[needsProcessing] = false
            }
        }
    }

    /**
     * Flags the connection's raw rows for the next PROCESS pass and returns how many: both tables for REPROCESS, [commitsOnly] for the
     * path-exclusions change (a merge request's line counts are not exclusion-aware).
     */
    suspend fun markAllNeedsProcessing(connectionId: UInt, commitsOnly: Boolean): Int = suspendTransaction(database) {
        val commits = Commits.update({ Commits.connectionId eq connectionId }) { it[needsProcessing] = true }
        val mergeRequests =
            if (commitsOnly) 0 else MergeRequests.update({ MergeRequests.connectionId eq connectionId }) { it[needsProcessing] = true }
        commits + mergeRequests
    }

    /** The rows flagged `needs_processing` in both tables: the fixture tripwire and tests (a version mismatch alone is not counted). */
    suspend fun countNeedsProcessing(connectionId: UInt): Long = suspendTransaction(database) {
        Commits.selectAll().where { (Commits.connectionId eq connectionId) and (Commits.needsProcessing eq true) }.count() +
            MergeRequests.selectAll()
                .where { (MergeRequests.connectionId eq connectionId) and (MergeRequests.needsProcessing eq true) }.count()
    }

    /** Live commits whose files read ended in a terminal error (`files_error` set): the profile's `filesUnavailable`. */
    suspend fun countFilesUnavailable(connectionId: UInt): Long = suspendTransaction(database) {
        Commits.selectAll()
            .where { (Commits.connectionId eq connectionId) and Commits.deletedAt.isNull() and Commits.filesError.isNotNull() }.count()
    }

    /** Every project row, the moved-out and deleted markers included, as the norm shape. */
    suspend fun projectRowsForNorm(connectionId: UInt): List<NormCodeProject> = suspendTransaction(database) {
        Projects.selectAll().where { Projects.connectionId eq connectionId }.orderBy(Projects.projectId).toList().map {
            NormCodeProject(
                projectId = it[Projects.projectId],
                pathWithNamespace = it[Projects.pathWithNamespace],
                groupPath = it[Projects.groupPath],
                defaultBranch = it[Projects.defaultBranch],
                archived = it[Projects.archived],
                movedOutAt = it[Projects.movedOutAt],
                deletedAt = it[Projects.deletedAt],
            )
        }
    }

    /** Every user row, live and deleted, by id. */
    suspend fun userRowsForNorm(connectionId: UInt): List<RawUserForNorm> = suspendTransaction(database) {
        Users.selectAll().where { Users.connectionId eq connectionId }.orderBy(Users.userId).toList().map {
            RawUserForNorm(
                userId = it[Users.userId],
                username = it[Users.username],
                name = it[Users.name],
                state = it[Users.state],
                bot = it[Users.bot],
                emails = parseStringArray(it[Users.emails]),
                deletedAt = it[Users.deletedAt],
            )
        }
    }

    /**
     * The distinct author addresses of the LIVE commits, each with the name, time and sha of its newest commit (one statement,
     * `DISTINCT ON`; only the connection id is in the text, the `JiraRawProcessReads` precedent). The caller folds spellings to the
     * canonical form and picks the newest name across them.
     */
    suspend fun liveCommitAuthors(connectionId: UInt): List<CommitAuthor> = suspendTransaction(database) {
        val rows: Flow<CommitAuthor?>? = exec(
            """
            SELECT DISTINCT ON (author_email) author_email, author_name, committed_at, sha FROM raw.gitlab_commits
            WHERE connection_id = $connectionId AND deleted_at IS NULL
            ORDER BY author_email, committed_at DESC, sha
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row ->
            CommitAuthor(
                row.get(0, String::class.java)!!,
                row.get(1, String::class.java)!!,
                row.get(2, Long::class.javaObjectType)!!,
                row.get(3, String::class.java)!!,
            )
        }
        rows?.toList()?.filterNotNull().orEmpty()
    }

    /**
     * The distinct GitLab user ids the LIVE merge requests and their events name (author, merge user, reviewers, assignees, event actors),
     * so the identity rebuild can add a ghost row for an id no users row backs. Ids only, one statement.
     */
    suspend fun referencedUserIds(connectionId: UInt): Set<Long> = suspendTransaction(database) {
        val live = "connection_id = $connectionId AND deleted_at IS NULL"
        val rows: Flow<Long?>? = exec(
            """
            SELECT DISTINCT id FROM (
              SELECT author_id AS id FROM raw.gitlab_merge_requests WHERE $live
              UNION SELECT merge_user_id FROM raw.gitlab_merge_requests WHERE $live
              UNION SELECT jsonb_array_elements_text(reviewer_ids)::bigint FROM raw.gitlab_merge_requests WHERE $live
              UNION SELECT jsonb_array_elements_text(assignee_ids)::bigint FROM raw.gitlab_merge_requests WHERE $live
              UNION SELECT e.actor_id FROM raw.gitlab_mr_events e
                JOIN raw.gitlab_merge_requests m ON m.connection_id = e.connection_id AND m.mr_id = e.mr_id
                WHERE e.connection_id = $connectionId AND m.deleted_at IS NULL
            ) ids WHERE id IS NOT NULL
            """.trimIndent(),
            explicitStatementType = StatementType.SELECT,
        ) { row -> row.get(0, Long::class.javaObjectType) }
        rows?.toList()?.filterNotNull().orEmpty().toSet()
    }

    private fun commitOf(row: ResultRow) = RawCommitForProcessing(
        projectId = row[Commits.projectId],
        sha = row[Commits.sha],
        title = row[Commits.title],
        authorName = row[Commits.authorName],
        authorEmail = row[Commits.authorEmail],
        authoredAt = row[Commits.authoredAt],
        committedAt = row[Commits.committedAt],
        parentCount = row[Commits.parentCount].toInt(),
        additions = row[Commits.additions],
        deletions = row[Commits.deletions],
        filesChanged = row[Commits.filesChanged],
        filesCapped = row[Commits.filesCapped],
        filesFetchedAt = row[Commits.filesFetchedAt],
        filesError = row[Commits.filesError],
        deletedAt = row[Commits.deletedAt],
    )

    private fun mergeRequestOf(row: ResultRow) = RawMergeRequestForProcessing(
        mrId = row[MergeRequests.mrId],
        projectId = row[MergeRequests.projectId],
        iid = row[MergeRequests.iid],
        state = row[MergeRequests.state],
        draft = row[MergeRequests.draft],
        authorId = row[MergeRequests.authorId],
        mergeUserId = row[MergeRequests.mergeUserId],
        createdAt = row[MergeRequests.createdAt],
        updatedAt = row[MergeRequests.updatedAt],
        mergedAt = row[MergeRequests.mergedAt],
        closedAt = row[MergeRequests.closedAt],
        sourceBranch = row[MergeRequests.sourceBranch],
        targetBranch = row[MergeRequests.targetBranch],
        title = row[MergeRequests.title],
        headSha = row[MergeRequests.headSha],
        mergeCommitSha = row[MergeRequests.mergeCommitSha],
        squashCommitSha = row[MergeRequests.squashCommitSha],
        reviewerIds = parseLongArray(row[MergeRequests.reviewerIds]),
        assigneeIds = parseLongArray(row[MergeRequests.assigneeIds]),
        additions = row[MergeRequests.additions],
        deletions = row[MergeRequests.deletions],
        fileCount = row[MergeRequests.fileCount],
        notesCapped = row[MergeRequests.notesCapped],
        changedAt = row[MergeRequests.changedAt],
        detailsSyncedAt = row[MergeRequests.detailsSyncedAt],
        detailsError = row[MergeRequests.detailsError],
        deletedAt = row[MergeRequests.deletedAt],
    )

    private fun List<CommitKey>.pairs() = map { it.projectId to it.sha }

    private fun parseLongArray(json: String): List<Long> = parseStringArray(json).map { it.toLong() }
}
