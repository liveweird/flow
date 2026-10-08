package ch.nokillswit.jira

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.ingest.DataSourceService
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

val JiraRawStoreKey = AttributeKey<JiraRawStore>("JiraRawStore")

/**
 * The kinds `raw.jira_entities` partitions by (v0.2.0 plan §4 V10) — this exact order is also the
 * REFERENCE stream's fixed step sequence (`jira/JiraReferenceStream.kt`), so a kind name doubles as
 * a cursor `step` value. PRIORITY is retained for persisted rows (`raw.jira_entities.kind`) and in-flight cursors
 * (`ReferenceCursor.step`); it is no longer fetched. ISSUE_TYPE precedes PROJECT_FIELDS on purpose: the field-scheme split
 * reads the stored issue types' hierarchy levels. A cursor naming PROJECT_FIELDS persisted under the previous order (no
 * `stepOrder`; it ran before ISSUE_TYPE) restarts the pass at step 0, like one naming PRIORITY, so ISSUE_TYPE is never
 * skipped; every other old cursor still names a step that resume re-runs from (every step is idempotent).
 */
enum class JiraEntityKind {
    FIELD, STATUS, STATUS_CATEGORY, PROJECT, PROJECT_STATUSES, ISSUE_TYPE, PROJECT_FIELDS, PRIORITY, RESOLUTION,
    ISSUE_LINK_TYPE, USER, BOARD, BOARD_CONFIGURATION, SPRINT,
}

/** What the ISSUES stream hands [JiraRawStore.upsertIssue] for one page's issue document. */
data class RawIssueInput(
    val issueId: Long,
    val issueKey: String,
    val projectId: Long,
    val projectKey: String,
    val issueUpdatedAt: Long,
    /** The issue document exactly as `search/jql` returned it (not yet canonicalized). */
    val payloadJson: String,
)

enum class RawUpsertOutcome { INSERTED, UNCHANGED, CHANGED, RESURRECTED }

/**
 * The Jira raw store (v0.2.0 plan §4/§7, V10): `raw.jira_issues`/`raw.jira_entities`/`raw.jira_changelogs`/
 * `raw.jira_worklogs`/`raw.jira_reconcile_seen`, all in the `raw` PostgreSQL schema (plan §0 A3) via
 * schema-qualified Exposed `Table`s (`RawSchemaRoundtripTest` proves this works over Exposed R2DBC). Payloads are
 * canonicalized (`infra/json/CanonicalJson.kt`) and sha256-hashed before storage; the hash — not a byte comparison —
 * is what decides "changed" (jsonb storage itself reformats regardless).
 *
 * Every write method opens its OWN `suspendTransaction` — like `ingest/SyncCursors.kt`'s service —
 * relying on Exposed reusing an ENCLOSING transaction for nested calls against the same
 * database, so a stream's `StreamContext.transaction { }` block (`ingest/Stream.kt`) can call
 * several of these plus a cursor write and have them all land in one transaction.
 *
 * This class is the table set, the row shapes the reads return, and the ONE entry point every other package uses
 * (`attributes[JiraRawStoreKey]`). The behaviour lives in one small collaborator per concern, each in its own file —
 * every method below is a one-line delegation, so a caller never learns which concern serves it and the
 * SQL/transactions are exactly the collaborators':
 *
 * - [JiraRawWrites] — the sha256 diff-rule upserts, the changelog insert, the worklog/entity tombstones;
 * - [JiraRawStreamScans] — the CHANGELOGS/WORKLOGS claim scans and their synced stamps;
 * - [JiraRawReconcile] — the RECONCILE id sweep, the anti-join, scope resolution and the issue tombstones;
 * - [JiraRawProcessReads] — the PROCESS claim scan, input reads and processed marks;
 * - [JiraRawViewReads] — the inspector, data profile and status-count reads;
 * - [JiraRawPurge] — the PURGE step's batched deletes.
 *
 * A new raw read or write goes in the collaborator that owns its concern, plus its one-line delegation here.
 */
class JiraRawStore(database: R2dbcDatabase) {
    private val writes = JiraRawWrites(database)
    private val scans = JiraRawStreamScans(database)
    private val viewReads = JiraRawViewReads(database)
    private val reconcile = JiraRawReconcile(database, viewReads)
    private val processReads = JiraRawProcessReads(database)
    private val purge = JiraRawPurge(database)

    object Issues : Table("raw.jira_issues") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val issueKey = varchar("issue_key", 20)
        val projectId = long("project_id")
        val projectKey = varchar("project_key", 20)
        val issueUpdatedAt = long("issue_updated_at")
        val payload = jsonb("payload")
        val sha256 = char("sha256", 64)
        val firstSeenAt = long("first_seen_at")
        val fetchedAt = long("fetched_at")
        val changedAt = long("changed_at")
        val changelogSyncedAt = long("changelog_synced_at").nullable()
        val worklogsSyncedAt = long("worklogs_synced_at").nullable()
        val needsProcessing = bool("needs_processing").default(true)
        val processedAt = long("processed_at").nullable()
        val processedHash = char("processed_hash", 64).nullable()
        val processingVersion = integer("processing_version").nullable()
        val deletedAt = long("deleted_at").nullable()
        val movedOutAt = long("moved_out_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, issueId)
    }

    object Entities : Table("raw.jira_entities") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val kind = varchar("kind", 30)
        val entityId = varchar("entity_id", 255)
        val payload = jsonb("payload")
        val sha256 = char("sha256", 64)
        val firstSeenAt = long("first_seen_at")
        val lastSeenAt = long("last_seen_at")
        val changedAt = long("changed_at")
        val deletedAt = long("deleted_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, kind, entityId)
    }

    object Changelogs : Table("raw.jira_changelogs") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val historyId = long("history_id")
        val issueId = long("issue_id")
        val createdAt = long("created_at")
        val authorAccountId = varchar("author_account_id", 100).nullable()
        val payload = jsonb("payload")
        val fetchedAt = long("fetched_at")
        override val primaryKey = PrimaryKey(connectionId, historyId)
    }

    object Worklogs : Table("raw.jira_worklogs") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val worklogId = long("worklog_id")
        val issueId = long("issue_id")
        val worklogUpdatedAt = long("worklog_updated_at")
        val payload = jsonb("payload")
        val sha256 = char("sha256", 64)
        val firstSeenAt = long("first_seen_at")
        val fetchedAt = long("fetched_at")
        val changedAt = long("changed_at")
        val deletedAt = long("deleted_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, worklogId)
    }

    object ReconcileSeen : Table("raw.jira_reconcile_seen") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val jobId = integer("job_id")
        val issueId = long("issue_id")
        override val primaryKey = PrimaryKey(connectionId, jobId, issueId)
    }

    /** One raw issue's PROCESS input (v0.2.0 plan §8): the canonicalized payload plus its current tombstone/process bookkeeping. */
    data class RawIssueForProcessing(
        val issueId: Long,
        val payloadJson: String,
        val sha256: String,
        val deletedAt: Long?,
        val movedOutAt: Long?,
    )

    /** One raw issue's full detail (v0.2.0 plan §9/§12 item 8b) — the raw issue inspector's own read shape. */
    data class RawIssueDetail(
        val issueId: Long,
        val issueKey: String,
        val payloadJson: String,
        val sha256: String,
        val fetchedAt: Long,
        val changedAt: Long,
        val deletedAt: Long?,
        val movedOutAt: Long?,
        val needsProcessing: Boolean,
    )

    // --- Diff-rule writes ([JiraRawWrites]) ---

    suspend fun upsertIssue(connectionId: UInt, issue: RawIssueInput, now: Long): RawUpsertOutcome =
        writes.upsertIssue(connectionId, issue, now)
    suspend fun upsertEntity(connectionId: UInt, kind: String, entityId: String, payloadJson: String, now: Long): RawUpsertOutcome =
        writes.upsertEntity(connectionId, kind, entityId, payloadJson, now)
    suspend fun touchEntity(connectionId: UInt, kind: String, entityId: String, now: Long): Int =
        writes.touchEntity(connectionId, kind, entityId, now)
    suspend fun insertChangelog(
        connectionId: UInt,
        historyId: Long,
        issueId: Long,
        createdAt: Long,
        authorAccountId: String?,
        payloadJson: String,
        now: Long,
    ) = writes.insertChangelog(connectionId, historyId, issueId, createdAt, authorAccountId, payloadJson, now)
    suspend fun upsertWorklog(
        connectionId: UInt,
        issueId: Long,
        worklogId: Long,
        worklogUpdatedAt: Long,
        payloadJson: String,
        now: Long,
    ): RawUpsertOutcome = writes.upsertWorklog(connectionId, issueId, worklogId, worklogUpdatedAt, payloadJson, now)
    suspend fun tombstoneWorklog(connectionId: UInt, worklogId: Long, now: Long): Int =
        writes.tombstoneWorklog(connectionId, worklogId, now)
    suspend fun markEntitiesDeletedNotSeenSince(connectionId: UInt, kind: String, passStartedAt: Long, now: Long = nowMillis()): Int =
        writes.markEntitiesDeletedNotSeenSince(connectionId, kind, passStartedAt, now)

    // --- CHANGELOGS / WORKLOGS claim scans ([JiraRawStreamScans]) ---

    suspend fun staleChangelogIssueIds(connectionId: UInt, limit: Int): List<Long> = scans.staleChangelogIssueIds(connectionId, limit)
    suspend fun markChangelogSynced(connectionId: UInt, issueIds: List<Long>, now: Long) =
        scans.markChangelogSynced(connectionId, issueIds, now)
    suspend fun staleWorklogIssueIds(connectionId: UInt, limit: Int): List<Long> = scans.staleWorklogIssueIds(connectionId, limit)
    suspend fun markWorklogsSynced(connectionId: UInt, issueId: Long, now: Long) = scans.markWorklogsSynced(connectionId, issueId, now)
    suspend fun knownInScopeIssueIds(connectionId: UInt, issueIds: List<Long>): Set<Long> =
        scans.knownInScopeIssueIds(connectionId, issueIds)

    // --- RECONCILE and scope ([JiraRawReconcile]) ---

    suspend fun insertReconcileSeen(connectionId: UInt, jobId: UInt, issueId: Long) =
        reconcile.insertReconcileSeen(connectionId, jobId, issueId)
    suspend fun resolveProjectIds(connectionId: UInt, projectKeys: List<String>): Set<Long>? =
        reconcile.resolveProjectIds(connectionId, projectKeys)
    suspend fun issuesMissingFromSeen(
        connectionId: UInt,
        jobId: UInt,
        projectIds: Set<Long>?,
        windowStartMillis: Long,
    ): List<ReconcileCandidate> = reconcile.issuesMissingFromSeen(connectionId, jobId, projectIds, windowStartMillis)
    suspend fun markOutOfScopeProjects(connectionId: UInt, projectKeys: List<String>, now: Long): Int =
        reconcile.markOutOfScopeProjects(connectionId, projectKeys, now)
    suspend fun seenButUnknownIds(connectionId: UInt, jobId: UInt): List<Long> = reconcile.seenButUnknownIds(connectionId, jobId)
    suspend fun clearReconcileSeen(connectionId: UInt): Int = reconcile.clearReconcileSeen(connectionId)
    suspend fun markIssueDeleted(connectionId: UInt, issueId: Long, now: Long): Int = reconcile.markIssueDeleted(connectionId, issueId, now)
    suspend fun markIssueMovedOut(
        connectionId: UInt,
        issueId: Long,
        newKey: String,
        newProjectId: Long,
        newProjectKey: String,
        now: Long,
    ): Int = reconcile.markIssueMovedOut(connectionId, issueId, newKey, newProjectId, newProjectKey, now)

    // --- PROCESS reads and marks ([JiraRawProcessReads]) ---

    suspend fun issuesToProcess(connectionId: UInt, currentProcessingVersion: Int, limit: Int, afterIssueId: Long? = null): List<Long> =
        processReads.issuesToProcess(connectionId, currentProcessingVersion, limit, afterIssueId)
    suspend fun issueForProcessing(connectionId: UInt, issueId: Long): RawIssueForProcessing? =
        processReads.issueForProcessing(connectionId, issueId)
    suspend fun issuesForProcessing(connectionId: UInt, issueIds: List<Long>): List<RawIssueForProcessing> =
        processReads.issuesForProcessing(connectionId, issueIds)
    suspend fun changelogPayloadsForIssues(connectionId: UInt, issueIds: List<Long>): Map<Long, List<String>> =
        processReads.changelogPayloadsForIssues(connectionId, issueIds)
    suspend fun worklogPayloadsForIssues(connectionId: UInt, issueIds: List<Long>): Map<Long, List<String>> =
        processReads.worklogPayloadsForIssues(connectionId, issueIds)
    suspend fun markProcessedBatch(connectionId: UInt, shaByIssueId: Map<Long, String>, now: Long, processingVersion: Int) =
        processReads.markProcessedBatch(connectionId, shaByIssueId, now, processingVersion)
    suspend fun markProcessed(connectionId: UInt, issueId: Long, processedHash: String, now: Long, processingVersion: Int) =
        processReads.markProcessed(connectionId, issueId, processedHash, now, processingVersion)
    suspend fun markAllNeedsProcessing(connectionId: UInt): Int = processReads.markAllNeedsProcessing(connectionId)
    suspend fun entityPayloadsByKind(connectionId: UInt, kind: String): List<String> =
        processReads.entityPayloadsByKind(connectionId, kind)
    suspend fun changelogPayloadsForIssue(connectionId: UInt, issueId: Long): List<String> =
        processReads.changelogPayloadsForIssue(connectionId, issueId)
    suspend fun worklogPayloadsForIssue(connectionId: UInt, issueId: Long): List<String> =
        processReads.worklogPayloadsForIssue(connectionId, issueId)

    // --- Inspector, profile and status reads ([JiraRawViewReads]) ---

    suspend fun issueById(connectionId: UInt, issueId: Long): RawIssueDetail? = viewReads.issueById(connectionId, issueId)
    suspend fun issueByKey(connectionId: UInt, issueKey: String): RawIssueDetail? = viewReads.issueByKey(connectionId, issueKey)
    suspend fun entityRowsByKind(connectionId: UInt, kind: String): List<Pair<String, String>> =
        viewReads.entityRowsByKind(connectionId, kind)
    suspend fun issuePayloads(connectionId: UInt): List<String> = viewReads.issuePayloads(connectionId)
    suspend fun entityCountsByKind(connectionId: UInt): Map<String, Long> = viewReads.entityCountsByKind(connectionId)
    suspend fun countIssuesDeleted(connectionId: UInt): Long = viewReads.countIssuesDeleted(connectionId)
    suspend fun countIssuesMovedOut(connectionId: UInt): Long = viewReads.countIssuesMovedOut(connectionId)
    suspend fun countNeedsProcessing(connectionId: UInt): Long = viewReads.countNeedsProcessing(connectionId)
    internal suspend fun countIssues(connectionId: UInt): Long = viewReads.countIssues(connectionId)
    internal suspend fun countChangelogs(connectionId: UInt): Long = viewReads.countChangelogs(connectionId)
    internal suspend fun countWorklogs(connectionId: UInt, excludeDeleted: Boolean = false): Long =
        viewReads.countWorklogs(connectionId, excludeDeleted)

    // --- PURGE ([JiraRawPurge]; the drain loop is the `purgeAll` extension in that file) ---

    suspend fun purgeIssuesBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int =
        purge.purgeIssuesBatch(connectionId, batchSize)
    suspend fun purgeEntitiesBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int =
        purge.purgeEntitiesBatch(connectionId, batchSize)
    suspend fun purgeChangelogsBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int =
        purge.purgeChangelogsBatch(connectionId, batchSize)
    suspend fun purgeWorklogsBatch(connectionId: UInt, batchSize: Int = JIRA_PURGE_BATCH_SIZE): Int =
        purge.purgeWorklogsBatch(connectionId, batchSize)
}
