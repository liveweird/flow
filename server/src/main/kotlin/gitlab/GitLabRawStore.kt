package ch.nokillswit.gitlab

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.GitLabSyncCounts
import ch.nokillswit.ingest.RawUpsertOutcome
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

val GitLabRawStoreKey = AttributeKey<GitLabRawStore>("GitLabRawStore")

/**
 * The GitLab raw store (V41-V43): `raw.gitlab_projects`/`raw.gitlab_users` (V41), `raw.gitlab_commits`/`raw.gitlab_commit_files`
 * (V42) and `raw.gitlab_merge_requests`/`raw.gitlab_mr_events` (V43) in the `raw` schema. Projects, users and merge requests hold slim
 * payloads canonicalized and sha256-hashed before storage like `raw.jira_*` - the hash decides "changed"
 * (`ingest/RawUpsertOutcome.kt`'s `classifyUpsert`, the
 * ONE diff rule). Every method opens its OWN `suspendTransaction`, joining an enclosing one, so a stream's `StreamContext.transaction`
 * can land a page and its cursor together. One small collaborator per concern, as `JiraRawStore`:
 *
 *  - [GitLabRawWrites] - the project/user upserts and the end-of-pass sweeps;
 *  - [GitLabCommitWrites] / [GitLabCommitFileWrites] - the commit upserts, the head and the tombstone sweep / the files flush;
 *  - [GitLabMrWrites] - the merge-request upserts, the events replacement and the details stamp (V43);
 *  - [GitLabRawReads] / [GitLabCommitReads] / [GitLabMrReads] - the status counts and freshness / the commit-stream reads / the
 *    merge-request backlog, counts and group lookups;
 *  - [GitLabRawPurge] - the PURGE step's batched deletes.
 *
 * The commit tables carry no payload and no hash: a commit is immutable by SHA (`.claude/docs/persistence.md` "The GitLab commits raw
 * store (V42)"); a merge request is mutable and hashed, its events are a replaced set ("The GitLab merge-request raw store (V43)").
 */
class GitLabRawStore(database: R2dbcDatabase) {
    private val writes = GitLabRawWrites(database)
    private val reads = GitLabRawReads(database)
    private val purge = GitLabRawPurge(database)
    private val commitWrites = GitLabCommitWrites(database)
    private val commitFileWrites = GitLabCommitFileWrites(database)
    private val commitReads = GitLabCommitReads(database)
    private val mrWrites = GitLabMrWrites(database)
    private val mrReads = GitLabMrReads(database)

    object Projects : Table("raw.gitlab_projects") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val projectId = long("project_id")
        val pathWithNamespace = varchar("path_with_namespace", MAX_PROJECT_PATH)
        val groupPath = varchar("group_path", MAX_GROUP_PATH)
        val groupId = long("group_id")
        val defaultBranch = varchar("default_branch", MAX_BRANCH).nullable()
        val archived = bool("archived")
        val payload = jsonb("payload")
        val sha256 = char("sha256", SHA256_LENGTH)
        val firstSeenAt = long("first_seen_at")
        val lastSeenAt = long("last_seen_at")
        val changedAt = long("changed_at")
        val movedOutAt = long("moved_out_at").nullable()
        val deletedAt = long("deleted_at").nullable()
        val commitsHeadSha = varchar("commits_head_sha", MAX_SHA).nullable()
        val relistRequested = bool("relist_requested").default(false)
        val relistStartedAt = long("relist_started_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, projectId)
    }

    object Users : Table("raw.gitlab_users") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val userId = long("user_id")
        val username = varchar("username", MAX_NAME)
        val name = varchar("name", MAX_NAME)
        val state = varchar("state", MAX_STATE)
        val bot = bool("bot")
        val emails = jsonb("emails")
        val payload = jsonb("payload")
        val sha256 = char("sha256", SHA256_LENGTH)
        val firstSeenAt = long("first_seen_at")
        val lastSeenAt = long("last_seen_at")
        val changedAt = long("changed_at")
        val deletedAt = long("deleted_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, userId)
    }

    object Commits : Table("raw.gitlab_commits") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val projectId = long("project_id")
        val sha = varchar("sha", MAX_SHA)
        val title = varchar("title", MAX_NAME)
        val authorName = varchar("author_name", MAX_NAME)
        val authorEmail = varchar("author_email", MAX_NAME)
        val authoredAt = long("authored_at")
        val committedAt = long("committed_at")
        val parentCount = short("parent_count")
        val additions = integer("additions").nullable()
        val deletions = integer("deletions").nullable()
        val filesChanged = integer("files_changed").nullable()
        val filesCapped = bool("files_capped").default(false)
        val filesFetchedAt = long("files_fetched_at").nullable()
        val filesError = varchar("files_error", MAX_FILES_ERROR).nullable()
        val firstSeenAt = long("first_seen_at")
        val lastSeenAt = long("last_seen_at")
        val deletedAt = long("deleted_at").nullable()
        val needsProcessing = bool("needs_processing").default(true)
        val processedAt = long("processed_at").nullable()
        val processingVersion = integer("processing_version").nullable()
        override val primaryKey = PrimaryKey(connectionId, projectId, sha)
    }

    /** `connection_id` is typed through the reference only; the table's FK is the composite one to [Commits] (V42, `ON DELETE CASCADE`). */
    object CommitFiles : Table("raw.gitlab_commit_files") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val projectId = long("project_id")
        val sha = varchar("sha", MAX_SHA)
        val path = varchar("path", MAX_PATH)
        val oldPath = varchar("old_path", MAX_PATH).nullable()
        val additions = integer("additions").nullable()
        val deletions = integer("deletions").nullable()
        val newFile = bool("new_file")
        val deletedFile = bool("deleted_file")
        val renamedFile = bool("renamed_file")
        val generatedFile = bool("generated_file").default(false)
        override val primaryKey = PrimaryKey(connectionId, projectId, sha, path)
    }

    object MergeRequests : Table("raw.gitlab_merge_requests") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val mrId = long("mr_id")
        val projectId = long("project_id")
        val iid = long("iid")
        val state = varchar("state", MAX_STATE)
        val draft = bool("draft")
        val authorId = long("author_id").nullable()
        val mergeUserId = long("merge_user_id").nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val mergedAt = long("merged_at").nullable()
        val closedAt = long("closed_at").nullable()
        val sourceBranch = varchar("source_branch", MAX_BRANCH)
        val targetBranch = varchar("target_branch", MAX_BRANCH)
        val title = varchar("title", MAX_NAME)
        val headSha = varchar("head_sha", MAX_SHA).nullable()
        val mergeCommitSha = varchar("merge_commit_sha", MAX_SHA).nullable()
        val squashCommitSha = varchar("squash_commit_sha", MAX_SHA).nullable()
        val reviewerIds = jsonb("reviewer_ids")
        val assigneeIds = jsonb("assignee_ids")
        val userNotesCount = integer("user_notes_count").nullable()
        val additions = integer("additions").nullable()
        val deletions = integer("deletions").nullable()
        val fileCount = integer("file_count").nullable()
        val notesCapped = bool("notes_capped").default(false)
        val detailsSyncedAt = long("details_synced_at").nullable()
        val detailsError = varchar("details_error", MAX_DETAILS_ERROR).nullable()
        val payload = jsonb("payload")
        val sha256 = char("sha256", SHA256_LENGTH)
        val firstSeenAt = long("first_seen_at")
        val lastSeenAt = long("last_seen_at")
        val changedAt = long("changed_at")
        val deletedAt = long("deleted_at").nullable()
        val needsProcessing = bool("needs_processing").default(true)
        val processedAt = long("processed_at").nullable()
        val processingVersion = integer("processing_version").nullable()
        override val primaryKey = PrimaryKey(connectionId, mrId)
    }

    /** `connection_id` is typed through the reference only; the FK is the composite one to [MergeRequests] (V43, `ON DELETE CASCADE`). */
    object MrEvents : Table("raw.gitlab_mr_events") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val mrId = long("mr_id")
        val eventSource = varchar("source", MAX_SOURCE)
        val sourceId = long("source_id")
        val projectId = long("project_id")
        val actorId = long("actor_id").nullable()
        val eventAt = long("event_at").nullable()
        val system = bool("system")
        val noteType = varchar("note_type", MAX_NOTE_TYPE).nullable()
        val action = varchar("action", MAX_ACTION)
        val targets = jsonb("targets").nullable()
        override val primaryKey = PrimaryKey(connectionId, mrId, eventSource, sourceId)
    }

    suspend fun upsertProjects(connectionId: UInt, projects: List<RawGitLabProject>, now: Long): List<RawUpsertOutcome> =
        writes.upsertProjects(connectionId, projects, now)

    suspend fun upsertUsers(connectionId: UInt, users: List<RawGitLabUser>, now: Long): List<RawUpsertOutcome> =
        writes.upsertUsers(connectionId, users, now)

    /**
     * Marks the live projects not seen since [passStartedAt] moved out (a completed pass over every configured group); returns
     * how many.
     */
    suspend fun markProjectsMovedOutNotSeenSince(connectionId: UInt, passStartedAt: Long, now: Long): Int =
        writes.markProjectsMovedOutNotSeenSince(connectionId, passStartedAt, now)

    /** Marks the live users not seen since [passStartedAt] deleted (a completed pass over `/users`); returns how many. */
    suspend fun markUsersDeletedNotSeenSince(connectionId: UInt, passStartedAt: Long, now: Long): Int =
        writes.markUsersDeletedNotSeenSince(connectionId, passStartedAt, now)

    /**
     * RECONCILE's writes are guarded on the marker they set, so a repeated pass is a no-op. Marks a moved-out project deleted (a 404 of
     * `GET /projects/:id`); true when the row changed.
     */
    suspend fun markProjectDeleted(connectionId: UInt, projectId: Long, now: Long): Boolean =
        writes.markProjectDeleted(connectionId, projectId, now)

    /** Sets `archived = true` on a project RECONCILE found archived; touches no marker and no payload (see [GitLabRawWrites]). */
    suspend fun markProjectArchived(connectionId: UInt, projectId: Long): Boolean = writes.markProjectArchived(connectionId, projectId)

    /** The ids of up to [limit] moved-out, not deleted projects, oldest move first: the RECONCILE project probes. */
    suspend fun movedOutProjects(connectionId: UInt, limit: Int): List<Long> = reads.movedOutProjects(connectionId, limit)

    suspend fun counts(connectionId: UInt): GitLabSyncCounts = reads.counts(connectionId, mrReads.mrCounts(connectionId))

    /** The `last_seen_at` of the OLDEST live user row, or null when there is none: the users-freshness read. */
    suspend fun oldestLiveUserSeenAt(connectionId: UInt): Long? = reads.oldestLiveUserSeenAt(connectionId)

    internal suspend fun projectRows(connectionId: UInt) = reads.projectRows(connectionId)

    internal suspend fun userRows(connectionId: UInt) = reads.userRows(connectionId)

    /** Stores a page of one project's commits (a repeated SHA is judged by its first occurrence); outcomes in input order. */
    suspend fun upsertCommits(connectionId: UInt, projectId: Long, commits: List<RawGitLabCommit>, now: Long): List<RawUpsertOutcome> =
        commitWrites.upsertCommits(connectionId, projectId, commits, now)

    /** Records the project's walked head and sets or clears the re-list request in one update. */
    suspend fun recordCommitsHead(connectionId: UInt, projectId: Long, headSha: String, relistRequested: Boolean = false) =
        commitWrites.recordCommitsHead(connectionId, projectId, headSha, relistRequested)

    /**
     * Begins (or continues) the project's re-list: sets `relist_requested` and the re-list epoch (`relist_started_at`, kept if already set)
     * and returns the epoch; [restart] replaces a set epoch with [now]. The sweep's candidates are the live commits not seen since it.
     */
    suspend fun beginRelist(connectionId: UInt, projectId: Long, now: Long, restart: Boolean = false): Long =
        commitWrites.beginRelist(connectionId, projectId, now, restart)

    /**
     * Ends a re-list's sweep: records [headSha] when there is one (an empty listing keeps the stored head), writes `relist_requested =
     * [moreToDo]` and clears the epoch once it is false.
     */
    suspend fun finishRelist(connectionId: UInt, projectId: Long, headSha: String?, moreToDo: Boolean) =
        commitWrites.finishRelist(connectionId, projectId, headSha, moreToDo)

    /** Marks the live [shas] seen now: the re-list sweep confirmed they are still on the branch. */
    suspend fun touchCommits(connectionId: UInt, projectId: Long, shas: List<String>, now: Long) =
        commitWrites.touchCommits(connectionId, projectId, shas, now)

    /** Tombstones the live [shas] (the re-list sweep confirmed they left the branch); returns how many. */
    suspend fun tombstoneCommits(connectionId: UInt, projectId: Long, shas: List<String>, now: Long): Int =
        commitWrites.tombstoneCommits(connectionId, projectId, shas, now)

    /**
     * The re-list sweep's candidates: up to [limit] live SHAs of the project committed at or after [sinceCommittedAt] that nothing has
     * seen since the re-list epoch [relistStartedAt] (the walk's pages and the sweep's touches both count as seeing). "Not listed" is only
     * a candidate - GitLab's `since` can stop a listing early on a commit with a skewed date - so the caller confirms each against the
     * branch before tombstoning it.
     */
    suspend fun staleCommitShas(
        connectionId: UInt,
        projectId: Long,
        sinceCommittedAt: Long,
        relistStartedAt: Long,
        limit: Int,
    ): List<String> =
        commitReads.staleCommitShas(connectionId, projectId, sinceCommittedAt, relistStartedAt, limit)

    /** Lands finished diffs in ONE transaction: the file rows, then each commit's stamp (`files_fetched_at`, `files_changed`, ...). */
    suspend fun flushCommitFiles(connectionId: UInt, outcomes: List<CommitFilesOutcome>, now: Long) =
        commitFileWrites.flushCommitFiles(connectionId, outcomes, now)

    suspend fun liveProjectsForCommits(connectionId: UInt): List<CommitsProjectRow> = commitReads.liveProjectsForCommits(connectionId)

    /** The `committed_at` of the project's oldest live commit: the re-list window's start. */
    suspend fun oldestLiveCommittedAt(connectionId: UInt, projectId: Long): Long? =
        commitReads.oldestLiveCommittedAt(connectionId, projectId)

    /** The COMMIT FILES backlog, newest first: up to [limit] live non-merge commits whose diff has not been read. */
    suspend fun pendingCommitFiles(connectionId: UInt, limit: Int): List<PendingCommit> =
        commitReads.pendingCommitFiles(connectionId, limit)

    suspend fun countPendingCommitFiles(connectionId: UInt): Long = commitReads.countPendingCommitFiles(connectionId)

    internal suspend fun commitRows(connectionId: UInt, projectId: Long? = null) = commitReads.commitRows(connectionId, projectId)

    internal suspend fun commitFileRows(connectionId: UInt, projectId: Long, sha: String) =
        commitReads.commitFileRows(connectionId, projectId, sha)

    /**
     * Stores a page of merge requests by the hashed page rule (a repeated id is judged by its first occurrence); outcomes in input order.
     * Nothing here tombstones a merge request: a listing that omits a row is no evidence (`deleted_at` is RECONCILE's).
     */
    suspend fun upsertMergeRequests(connectionId: UInt, mrs: List<RawGitLabMergeRequest>, now: Long): List<RawUpsertOutcome> =
        mrWrites.upsertMergeRequests(connectionId, mrs, now)

    /**
     * Settles one merge request's details read in one transaction: a complete read replaces its events and writes the stats and cap, a
     * terminal error writes no events ([events] must be empty); the stamp never trails `changed_at`. False when the row is gone.
     */
    suspend fun settleMrDetails(
        connectionId: UInt,
        mrId: Long,
        events: List<RawGitLabMrEvent>,
        now: Long,
        outcome: MrDetailsOutcome,
    ): Boolean =
        mrWrites.settleMrDetails(connectionId, mrId, events, now, outcome)

    /** The MR DETAILS backlog, newest `updated_at` first: up to [limit] live merge requests of live projects whose details are stale. */
    suspend fun staleMergeRequests(connectionId: UInt, limit: Int): List<StaleMergeRequest> =
        mrReads.staleMergeRequests(connectionId, limit)

    suspend fun countStaleMergeRequests(connectionId: UInt): Long = mrReads.countStaleMergeRequests(connectionId)

    /** The merge requests RECONCILE's re-list did not show and that are old enough to ask about, stalest-checked first. */
    suspend fun mrReconcileCandidates(
        connectionId: UInt,
        floorMillis: Long,
        passStartedAt: Long,
        groupPaths: Collection<String>,
        limit: Int,
    ): List<StaleMergeRequest> =
        mrReads.mrReconcileCandidates(connectionId, floorMillis, passStartedAt, groupPaths, limit)

    /** Live merge requests of live projects whose settled details lack diff stats, newest first: RECONCILE's stats re-ask. */
    suspend fun gappedMergeRequests(connectionId: UInt, limit: Int): List<StaleMergeRequest> =
        mrReads.gappedMergeRequests(connectionId, limit)

    /** Tombstones the live [mrIds] (RECONCILE confirmed they are gone) and flags PROCESS; returns how many. */
    suspend fun tombstoneMergeRequests(connectionId: UInt, mrIds: Collection<Long>, now: Long): Int =
        mrWrites.tombstoneMergeRequests(connectionId, mrIds, now)

    /** Marks the live [mrIds] seen now (RECONCILE could not disprove them). */
    suspend fun touchMergeRequests(connectionId: UInt, mrIds: Collection<Long>, now: Long) =
        mrWrites.touchMergeRequests(connectionId, mrIds, now)

    /** Fills the diff stats of the project's live merge requests by iid and flags PROCESS; the details stamp is untouched. */
    suspend fun writeMrDiffStats(connectionId: UInt, projectId: Long, statsByIid: Map<Long, GitLabDiffStats>): Int =
        mrWrites.writeMrDiffStats(connectionId, projectId, statsByIid)

    /** Un-stamps up to [limit] live merge requests whose `details_error` is in [errors], stalest first (MR DETAILS rereads them). */
    suspend fun retryMrDetails(connectionId: UInt, errors: Collection<String>, limit: Int): Int =
        mrWrites.retryMrDetails(connectionId, errors, limit)

    /** The seven merge-request figures of the status view. */
    suspend fun mrCounts(connectionId: UInt): MrCounts = mrReads.mrCounts(connectionId)

    /** The stored projects' configured group: `group_path` to `group_id` (the MERGE REQUESTS stream lists per group id). */
    suspend fun configuredGroupIds(connectionId: UInt): Map<String, Long> = mrReads.configuredGroupIds(connectionId)

    /** The live stored projects' ids per `group_path` (the MERGE REQUESTS stream notices a project that newly entered a group). */
    suspend fun liveProjectIdsByGroup(connectionId: UInt): Map<String, List<Long>> = mrReads.liveProjectIdsByGroup(connectionId)

    /** The ids of every stored project, whatever its state: a merge request of any other project is not kept. */
    suspend fun storedProjectIds(connectionId: UInt): Set<Long> = mrReads.storedProjectIds(connectionId)

    /** `path_with_namespace` of the given stored projects (the GraphQL query names a project by it). */
    suspend fun projectPaths(connectionId: UInt, projectIds: Collection<Long>): Map<Long, String> =
        mrReads.projectPaths(connectionId, projectIds)

    internal suspend fun mergeRequestRows(connectionId: UInt, projectId: Long? = null) = mrReads.mergeRequestRows(connectionId, projectId)

    internal suspend fun mrEventRows(connectionId: UInt, mrId: Long? = null) = mrReads.mrEventRows(connectionId, mrId)

    /** Deletes up to [batchSize] commits of the connection (their files cascade, up to 1,000 rows each); returns the commits deleted. */
    suspend fun purgeCommitsBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_COMMITS_BATCH_SIZE): Int =
        purge.purgeCommitsBatch(connectionId, batchSize)

    /** Deletes up to [batchSize] merge requests of the connection (their events cascade, ~1,100 rows each); returns how many. */
    suspend fun purgeMergeRequestsBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_MRS_BATCH_SIZE): Int =
        purge.purgeMergeRequestsBatch(connectionId, batchSize)

    suspend fun purgeProjectsBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_BATCH_SIZE): Int =
        purge.purgeProjectsBatch(connectionId, batchSize)

    suspend fun purgeUsersBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_BATCH_SIZE): Int =
        purge.purgeUsersBatch(connectionId, batchSize)

    /** The column widths of V41-V43; the commit writes cut to them ([MAX_NAME] is `title`, `author_name` and `author_email`). */
    internal companion object {
        const val MAX_PROJECT_PATH = 1024
        const val MAX_GROUP_PATH = 255
        const val MAX_BRANCH = 255
        const val MAX_NAME = 255
        const val MAX_STATE = 30
        const val SHA256_LENGTH = 64
        const val MAX_SHA = 64
        const val MAX_PATH = 1024
        const val MAX_FILES_ERROR = 40
        const val MAX_SOURCE = 10
        const val MAX_ACTION = 40
        const val MAX_NOTE_TYPE = 30
        const val MAX_DETAILS_ERROR = 40
    }
}
