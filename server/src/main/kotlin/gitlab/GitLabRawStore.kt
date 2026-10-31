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

/**
 * The GitLab raw store (V41): `raw.gitlab_projects` and `raw.gitlab_users` in the `raw` schema, slim payloads canonicalized and
 * sha256-hashed before storage like `raw.jira_*` - the hash decides "changed" (`ingest/RawUpsertOutcome.kt`'s `classifyUpsert`, the
 * ONE diff rule). Every method opens its OWN `suspendTransaction`, joining an enclosing one, so a stream's `StreamContext.transaction`
 * can land a page and its cursor together. One small collaborator per concern, as `JiraRawStore`:
 *
 *  - [GitLabRawWrites] - the upserts and the end-of-pass sweeps;
 *  - [GitLabRawReads] - the status counts and the freshness read;
 *  - [GitLabRawPurge] - the PURGE step's batched deletes.
 */
class GitLabRawStore(database: R2dbcDatabase) {
    private val writes = GitLabRawWrites(database)
    private val reads = GitLabRawReads(database)
    private val purge = GitLabRawPurge(database)

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
        val commitsHeadSha = char("commits_head_sha", SHA1_LENGTH).nullable()
        val relistRequested = bool("relist_requested").default(false)
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

    suspend fun purgeProjectsBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_BATCH_SIZE): Int =
        purge.purgeProjectsBatch(connectionId, batchSize)

    suspend fun purgeUsersBatch(connectionId: UInt, batchSize: Int = GITLAB_PURGE_BATCH_SIZE): Int =
        purge.purgeUsersBatch(connectionId, batchSize)

    private companion object {
        const val MAX_PROJECT_PATH = 1024
        const val MAX_GROUP_PATH = 255
        const val MAX_BRANCH = 255
        const val MAX_NAME = 255
        const val MAX_STATE = 30
        const val SHA256_LENGTH = 64
        const val SHA1_LENGTH = 40
    }
}
