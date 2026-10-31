package ch.nokillswit.norm

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.ingest.DataSourceService
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * Published by `gitlab/GitLab.kt`'s `configureGitLab` BEFORE its enabled check (a database handle only), so PR 11's `identities/`
 * routes can read the code layer whether or not GitLab is on in this process.
 */
val CodeActivityStoreKey = AttributeKey<CodeActivityStore>("CodeActivityStore")

/**
 * The `norm` schema's CODE layer (V44, `.claude/docs/persistence.md` "The normalized code layer (V44)"): the five Exposed tables, the row
 * shapes and the ONE entry point every other package uses (`attributes[CodeActivityStoreKey]`) - the [WorkItemStore] shape. The behaviour
 * lives in one small collaborator per concern, each in its own file; every method below is a one-line delegation:
 *
 * - [CodeActivityWriter] - the PROCESS write path (the wholesale reference rebuilds, the commit upsert, the per-merge-request REPLACE);
 * - [CodeActivityReads] - the data profile's aggregates and the row readers the tests use;
 * - [CodeActivityPurge] - the PURGE step's batched deletes (`purgeAll`, in that file).
 *
 * Every write opens its own `suspendTransaction`, which joins an enclosing one: the PROCESS stream wraps a page's write and the raw rows'
 * marks in ONE transaction. A `_counted` null means UNKNOWN, never zero (V44's header).
 */
class CodeActivityStore(database: R2dbcDatabase) {
    private val writer = CodeActivityWriter(database)
    private val reads = CodeActivityReads(database)
    private val purge = CodeActivityPurge(database)

    object CodeProjects : Table("norm.code_projects") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val projectId = long("project_id")
        val pathWithNamespace = text("path_with_namespace")
        val groupPath = text("group_path")
        val defaultBranch = text("default_branch").nullable()
        val archived = bool("archived")
        val movedOutAt = long("moved_out_at").nullable()
        val deletedAt = long("deleted_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, projectId)
    }

    object CodeIdentities : Table("norm.code_identities") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val identityKey = varchar("identity_key", MAX_KEY)
        val kind = varchar("kind", MAX_KIND)
        val userId = long("user_id").nullable()
        val username = text("username").nullable()
        val displayName = text("display_name").nullable()
        val state = varchar("state", MAX_STATE).nullable()
        val bot = bool("bot").default(false)
        val emails = jsonb("emails")
        val deletedAt = long("deleted_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, identityKey)
    }

    object CodeCommits : Table("norm.code_commits") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val projectId = long("project_id")
        val sha = varchar("sha", MAX_SHA)
        val identityKey = varchar("identity_key", MAX_KEY)
        val title = text("title")
        val authoredAt = long("authored_at")
        val committedAt = long("committed_at")
        val isMerge = bool("is_merge")
        val additions = integer("additions").nullable()
        val deletions = integer("deletions").nullable()
        val files = integer("files").nullable()
        val additionsCounted = integer("additions_counted").nullable()
        val deletionsCounted = integer("deletions_counted").nullable()
        val filesCounted = integer("files_counted").nullable()
        val filesCapped = bool("files_capped").default(false)
        val deletedAt = long("deleted_at").nullable()
        val processedAt = long("processed_at")
        val processingVersion = integer("processing_version")
        override val primaryKey = PrimaryKey(connectionId, projectId, sha)
    }

    object CodeMergeRequests : Table("norm.code_merge_requests") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val mrId = long("mr_id")
        val projectId = long("project_id")
        val iid = long("iid")
        val state = varchar("state", MAX_STATE)
        val draft = bool("draft")
        val authorKey = varchar("author_key", MAX_KEY).nullable()
        val mergeUserKey = varchar("merge_user_key", MAX_KEY).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val mergedAt = long("merged_at").nullable()
        val closedAt = long("closed_at").nullable()
        val sourceBranch = text("source_branch")
        val targetBranch = text("target_branch")
        val title = text("title")
        val headSha = varchar("head_sha", MAX_SHA).nullable()
        val mergeCommitSha = varchar("merge_commit_sha", MAX_SHA).nullable()
        val squashCommitSha = varchar("squash_commit_sha", MAX_SHA).nullable()
        val reviewerKeys = jsonb("reviewer_keys")
        val assigneeKeys = jsonb("assignee_keys")
        val additions = integer("additions").nullable()
        val deletions = integer("deletions").nullable()
        val fileCount = integer("file_count").nullable()
        val notesCapped = bool("notes_capped").default(false)
        val detailsState = varchar("details_state", MAX_DETAILS_STATE)
        val deletedAt = long("deleted_at").nullable()
        val processedAt = long("processed_at")
        val processingVersion = integer("processing_version")
        override val primaryKey = PrimaryKey(connectionId, mrId)
    }

    object CodeMrEvents : Table("norm.code_mr_events") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val mrId = long("mr_id")
        val seq = integer("seq")
        val kind = varchar("kind", MAX_EVENT_KIND)
        val actorKey = varchar("actor_key", MAX_KEY).nullable()
        val eventAt = long("event_at").nullable()
        val targetKeys = jsonb("target_keys").nullable()
        override val primaryKey = PrimaryKey(connectionId, mrId, seq)
    }

    suspend fun replaceProjects(connectionId: UInt, rows: List<NormCodeProject>) = writer.replaceProjects(connectionId, rows)

    /** Rebuilds the connection's identities wholesale; the rows written. */
    suspend fun replaceIdentities(connectionId: UInt, rows: List<NormCodeIdentity>): Int = writer.replaceIdentities(connectionId, rows)

    /** Upserts a page of commit facts on the raw primary key, stamping each with [now] and [version]. */
    suspend fun replaceCommits(connectionId: UInt, rows: List<NormCodeCommit>, now: Long, version: Int = CODE_PROCESSING_VERSION) =
        writer.replaceCommits(connectionId, rows, now, version)

    /** REPLACES a page of merge requests: their events are deleted and re-inserted, the rows upserted. */
    suspend fun replaceMergeRequests(
        connectionId: UInt,
        rows: List<NormCodeMergeRequest>,
        now: Long,
        version: Int = CODE_PROCESSING_VERSION,
    ) = writer.replaceMergeRequests(connectionId, rows, now, version)

    suspend fun projectCounts(connectionId: UInt): CodeProjectCounts = reads.projectCounts(connectionId)

    suspend fun commitFigures(connectionId: UInt): CodeCommitFigures = reads.commitFigures(connectionId)

    suspend fun mergeRequestFigures(connectionId: UInt): CodeMergeRequestFigures = reads.mergeRequestFigures(connectionId)

    suspend fun eventCounts(connectionId: UInt): Map<MrEventKind, Long> = reads.eventCounts(connectionId)

    suspend fun identityFigures(connectionId: UInt): CodeIdentityFigures = reads.identityFigures(connectionId)

    internal suspend fun projectRows(connectionId: UInt): List<NormCodeProject> = reads.projectRows(connectionId)

    internal suspend fun identityRows(connectionId: UInt): List<NormCodeIdentity> = reads.identityRows(connectionId)

    internal suspend fun commitRows(connectionId: UInt, projectId: Long? = null): List<NormCodeCommit> =
        reads.commitRows(connectionId, projectId)

    /** The merge requests of the connection, each WITHOUT its events (read them with [mrEventRows]). */
    internal suspend fun mergeRequestRows(connectionId: UInt): List<NormCodeMergeRequest> = reads.mergeRequestRows(connectionId)

    internal suspend fun mrEventRows(connectionId: UInt, mrId: Long? = null): Map<Long, List<NormCodeMrEvent>> =
        reads.mrEventRows(connectionId, mrId)

    /** One batch of the connection's events (PURGE); the rows deleted. */
    suspend fun purgeMrEventsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int =
        purge.purgeMrEventsBatch(connectionId, batchSize)

    suspend fun purgeMergeRequestsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int =
        purge.purgeMergeRequestsBatch(connectionId, batchSize)

    suspend fun purgeCommitsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int =
        purge.purgeCommitsBatch(connectionId, batchSize)

    /** The two reference tables are rebuilt wholesale already: PURGE clears them outright. */
    suspend fun purgeReferenceRows(connectionId: UInt) = purge.purgeReferenceRows(connectionId)

    private companion object {
        const val MAX_KEY = 40
        const val MAX_KIND = 10
        const val MAX_STATE = 30
        const val MAX_SHA = 64
        const val MAX_DETAILS_STATE = 12
        const val MAX_EVENT_KIND = 20
    }
}

/** One `norm.code_projects` row (every raw project, the moved-out and deleted markers mirrored). */
data class NormCodeProject(
    val projectId: Long,
    val pathWithNamespace: String,
    val groupPath: String,
    val defaultBranch: String?,
    val archived: Boolean,
    val movedOutAt: Long?,
    val deletedAt: Long?,
)

/**
 * One `norm.code_identities` row. [key] is `u<id>` or `e<hash>` (never an email); [emails] are the canonical addresses the identity
 * page may show an admin. A USER with a null [username] is a ghost: an id a fact names that has no users row.
 */
data class NormCodeIdentity(
    val key: String,
    val kind: IdentityKind,
    val userId: Long?,
    val username: String?,
    val displayName: String?,
    val state: String?,
    val bot: Boolean,
    val emails: List<String>,
    val deletedAt: Long?,
)

/**
 * One `norm.code_commits` row. The `*Counted` figures follow the path exclusions; null = UNKNOWN (files pending or unavailable, or a
 * merge), never zero. [additions]/[deletions]/[files] are GitLab's own, beside them.
 */
data class NormCodeCommit(
    val projectId: Long,
    val sha: String,
    val identityKey: String,
    val title: String,
    val authoredAt: Long,
    val committedAt: Long,
    val isMerge: Boolean,
    val additions: Int?,
    val deletions: Int?,
    val files: Int?,
    val additionsCounted: Int?,
    val deletionsCounted: Int?,
    val filesCounted: Int?,
    val filesCapped: Boolean,
    val deletedAt: Long?,
)

/** One `norm.code_merge_requests` row with its [events] (empty when the row came from the store's reader: see `mergeRequestRows`). */
data class NormCodeMergeRequest(
    val mrId: Long,
    val projectId: Long,
    val iid: Long,
    val state: String,
    val draft: Boolean,
    val authorKey: String?,
    val mergeUserKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val mergedAt: Long?,
    val closedAt: Long?,
    val sourceBranch: String,
    val targetBranch: String,
    val title: String,
    val headSha: String?,
    val mergeCommitSha: String?,
    val squashCommitSha: String?,
    val reviewerKeys: List<String>,
    val assigneeKeys: List<String>,
    val additions: Int?,
    val deletions: Int?,
    val fileCount: Int?,
    val notesCapped: Boolean,
    val detailsState: DetailsState,
    val deletedAt: Long?,
    val events: List<NormCodeMrEvent>,
)

/** One review event; [seq] is 1..n per merge request in the deterministic order, [targetKeys] only for REVIEW_REQUESTED. */
data class NormCodeMrEvent(
    val seq: Int,
    val kind: MrEventKind,
    val actorKey: String?,
    val eventAt: Long?,
    val targetKeys: List<String>,
)

/** The projects by marker: [live] neither moved out nor deleted; [movedOut] moved out, not deleted; [deleted]; [archived] of the live. */
data class CodeProjectCounts(val live: Long, val movedOut: Long, val deleted: Long, val archived: Long)

/**
 * The live commits: [count] non-merge, [merges], the commit-time range of ALL live commits, [filesCapped] (live non-merge, more files than
 * stored) and [commitsWithCounts] (live non-merge with BOTH counted lines figures known: `additions_counted` and `deletions_counted` set).
 */
data class CodeCommitFigures(
    val count: Long,
    val merges: Long,
    val earliestCommittedAt: Long?,
    val latestCommittedAt: Long?,
    val filesCapped: Long,
    val commitsWithCounts: Long,
)

/** The live merge requests: [byState] (GitLab's state names), [withDiffStats] (not a gap) and the two non-READ [DetailsState]s. */
data class CodeMergeRequestFigures(
    val byState: Map<String, Long>,
    val withDiffStats: Long,
    val detailsPending: Long,
    val detailsUnreadable: Long,
)

/**
 * The identities: [users] listed and live (bots included), [bots] among them, [usersDeleted], [usersWithEmail] (live, at least one
 * address), [ghosts] (USER rows no users row backs), [emailOnly] (EMAIL rows) and [commitsUnmatched] (live non-merge commits under an
 * `e…` key, read from the commit's own key).
 */
data class CodeIdentityFigures(
    val users: Long,
    val bots: Long,
    val usersDeleted: Long,
    val usersWithEmail: Long,
    val ghosts: Long,
    val emailOnly: Long,
    val commitsUnmatched: Long,
)
