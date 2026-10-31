package ch.nokillswit.gitlab

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.GitLabSyncCounts
import ch.nokillswit.ingest.RawUpsertOutcome
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

val GitLabRawStoreKey = AttributeKey<GitLabRawStore>("GitLabRawStore")

/** One project as the PROJECTS stream hands it over: the group it was LISTED under and GitLab's document (slimmed on write). */
class RawGitLabProject(
    val projectId: Long,
    val pathWithNamespace: String,
    /** The configured group path (lowercase) the project was listed under, and that group's numeric id. */
    val groupPath: String,
    val groupId: Long,
    val defaultBranch: String?,
    val archived: Boolean,
    val payload: JsonElement,
)

/** One user as the USERS stream hands it over; [emails] is the sorted, distinct, lowercase union (`gitLabUserEmails`). */
class RawGitLabUser(
    val userId: Long,
    val username: String,
    val name: String,
    val state: String,
    val bot: Boolean,
    val emails: List<String>,
    val payload: JsonElement,
)

/** One default-branch commit as the COMMITS stream hands it over (no body, committer or diff); [additions]/[deletions] are `stats`. */
class RawGitLabCommit(
    val sha: String,
    val title: String,
    val authorName: String,
    val authorEmail: String,
    val authoredAt: Long,
    val committedAt: Long,
    val parentCount: Int,
    val additions: Int?,
    val deletions: Int?,
)

/** One file of a commit's `/diff`: [additions]/[deletions] null = GitLab sent it too large or collapsed; a binary file is 0/0. */
class RawGitLabCommitFile(
    val path: String,
    val oldPath: String?,
    val additions: Int?,
    val deletions: Int?,
    val newFile: Boolean,
    val deletedFile: Boolean,
    val renamedFile: Boolean,
    val generatedFile: Boolean,
)

/** A commit's complete file list; [capped] = the 1,000-file cap cut it. */
class CommitFilesResult(val files: List<RawGitLabCommitFile>, val capped: Boolean)

/** The terminal `files_error` values (V42): a diff page over the response ceiling, and a commit GitLab no longer has. */
internal const val FILES_ERROR_LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
internal const val FILES_ERROR_NOT_FOUND = "NOT_FOUND"

/** What the COMMIT FILES stream settled for one commit: its [result], or a terminal [error] (`LIMIT_EXCEEDED`, `NOT_FOUND`) with none. */
class CommitFilesOutcome(val projectId: Long, val sha: String, val result: CommitFilesResult?, val error: String?) {
    init {
        require((result == null) != (error == null)) { "a commit-files outcome carries exactly one of a result and an error" }
    }
}

/** A non-merge live commit whose `/diff` has not been read. */
class PendingCommit(val projectId: Long, val sha: String)

/** A live project as the COMMITS stream reads it: its default branch, the stored head and whether a re-list was requested. */
class CommitsProjectRow(val projectId: Long, val defaultBranch: String?, val headSha: String?, val relistRequested: Boolean)

/**
 * The GitLab raw store (V41, V42): `raw.gitlab_projects`/`raw.gitlab_users` (V41) and `raw.gitlab_commits`/`raw.gitlab_commit_files`
 * (V42) in the `raw` schema. Projects and users hold slim payloads canonicalized and
 * sha256-hashed before storage like `raw.jira_*` - the hash decides "changed" (`ingest/RawUpsertOutcome.kt`'s `classifyUpsert`, the
 * ONE diff rule). Every method opens its OWN `suspendTransaction`, joining an enclosing one, so a stream's `StreamContext.transaction`
 * can land a page and its cursor together. One small collaborator per concern, as `JiraRawStore`:
 *
 *  - [GitLabRawWrites] - the project/user upserts and the end-of-pass sweeps;
 *  - [GitLabCommitWrites] / [GitLabCommitFileWrites] - the commit upserts, the head and the tombstone sweep / the files flush;
 *  - [GitLabRawReads] / [GitLabCommitReads] - the status counts and freshness / the commit-stream reads;
 *  - [GitLabRawPurge] - the PURGE step's batched deletes.
 *
 * The commit tables carry no payload and no hash: a commit is immutable by SHA (`.claude/docs/persistence.md` "The GitLab commits raw
 * store (V42)").
 */
class GitLabRawStore(database: R2dbcDatabase) {
    private val writes = GitLabRawWrites(database)
    private val reads = GitLabRawReads(database)
    private val purge = GitLabRawPurge(database)
    private val commitWrites = GitLabCommitWrites(database)
    private val commitFileWrites = GitLabCommitFileWrites(database)
    private val commitReads = GitLabCommitReads(database)

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

    suspend fun counts(connectionId: UInt): GitLabSyncCounts = reads.counts(connectionId)

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
     * and returns the epoch. The sweep's candidates are the live commits not seen since it.
     */
    suspend fun beginRelist(connectionId: UInt, projectId: Long, now: Long): Long = commitWrites.beginRelist(connectionId, projectId, now)

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

    /** Deletes up to [batchSize] commits of the connection (their files cascade, up to 1,000 rows each); returns the commits deleted. */
    suspend fun purgeCommitsBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_COMMITS_BATCH_SIZE): Int =
        purge.purgeCommitsBatch(connectionId, batchSize)

    suspend fun purgeProjectsBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_BATCH_SIZE): Int =
        purge.purgeProjectsBatch(connectionId, batchSize)

    suspend fun purgeUsersBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_BATCH_SIZE): Int =
        purge.purgeUsersBatch(connectionId, batchSize)

    /** The column widths of V41/V42; the commit writes cut to them ([MAX_NAME] is `title`, `author_name` and `author_email`). */
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
    }
}
