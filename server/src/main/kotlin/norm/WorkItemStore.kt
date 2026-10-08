package ch.nokillswit.norm

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.DataSourceService
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/** Published by `jira/Jira.kt`'s `configureJira` — the raw issue inspector and the data profile step both read it back. */
val WorkItemStoreKey = AttributeKey<WorkItemStore>("WorkItemStore")

/**
 * The `norm` schema's store (v0.2.0 plan §0 A3/§4 V13): the Exposed table set, the row shapes the reads return, and the
 * ONE entry point every other package uses (`attributes[WorkItemStoreKey]`). The behaviour lives in one small
 * collaborator per concern, each in its own file — every method below is a one-line delegation, so a caller never
 * learns which concern serves it and the SQL/transactions are exactly the collaborators':
 *
 * - [WorkItemWriter] — the PROCESS write path (per-page REPLACE per issue scope, the `work_items` upsert);
 * - [NormReferenceStore] — the wholesale-rebuilt reference rows (`statuses`/`people`/`boards`/`sprints`) and their read-back;
 * - [NormDerivationReads] — the DERIVE job's reads (`metrics/`);
 * - [NormProfileReads] — the data profile's bulk reads (`jira/JiraProfile.kt`);
 * - [NormInspectorReads] — one issue's rows for the raw issue inspector;
 * - [NormPickerReads] — the `/jira-users` and metrics-config option reads;
 * - [NormPurge] — the PURGE step's batched deletes.
 *
 * A new `norm` read or write goes in the collaborator that owns its concern, plus its one-line delegation here.
 */
class WorkItemStore(database: R2dbcDatabase) {
    private val writer = WorkItemWriter(database)
    private val reference = NormReferenceStore(database)
    private val derivation = NormDerivationReads(database)
    private val profile = NormProfileReads(database)
    private val inspector = NormInspectorReads(database)
    private val pickers = NormPickerReads(database)
    private val purge = NormPurge(database)

    object WorkItems : Table("norm.work_items") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val issueKey = varchar("issue_key", 20)
        val projectKey = varchar("project_key", 20)
        val issueType = text("issue_type")
        val isSubtask = bool("is_subtask").default(false)
        val parentIssueId = long("parent_issue_id").nullable()
        val summary = text("summary").nullable()
        val statusId = varchar("status_id", 50)
        val statusName = text("status_name")
        val statusCategory = varchar("status_category", 20)
        val resolution = text("resolution").nullable()
        val priority = text("priority").nullable()
        val assigneeAccountId = varchar("assignee_account_id", 100).nullable()
        val reporterAccountId = varchar("reporter_account_id", 100).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        val resolvedAt = long("resolved_at").nullable()
        val storyPoints = double("story_points").nullable()
        val originalEstimateSeconds = long("original_estimate_seconds").nullable()
        val timeSpentSeconds = long("time_spent_seconds").default(0)
        val labels = jsonb("labels")
        val components = jsonb("components")
        val fixVersions = jsonb("fix_versions")
        val currentSprintIds = jsonb("current_sprint_ids")
        val teamValue = jsonb("team_value").nullable()
        val flagged = bool("flagged").default(false)
        val rank = varchar("rank", 100).nullable()
        val hierarchyLevel = integer("hierarchy_level").nullable()
        val dueAt = long("due_at").nullable()
        val customFields = jsonb("custom_fields")
        val anomalies = jsonb("anomalies")
        val deletedAt = long("deleted_at").nullable()
        val movedOutAt = long("moved_out_at").nullable()
        val processedAt = long("processed_at")
        val processingVersion = integer("processing_version")
        override val primaryKey = PrimaryKey(connectionId, issueId)
    }

    object StatusIntervals : Table("norm.work_item_status_intervals") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val seq = integer("seq")
        val statusId = varchar("status_id", 50)
        val statusName = text("status_name")
        val statusCategory = varchar("status_category", 20)
        val fromAt = long("from_at")
        val toAt = long("to_at").nullable()
        val intervalSource = varchar("source", 10)
        override val primaryKey = PrimaryKey(id)
    }

    object FieldIntervals : Table("norm.work_item_field_intervals") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val field = varchar("field", 20)
        val seq = integer("seq")
        val valueId = varchar("value_id", 200).nullable()
        val valueText = text("value_text").nullable()
        val fromAt = long("from_at")
        val toAt = long("to_at").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object FieldChanges : Table("norm.work_item_field_changes") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val seq = integer("seq")
        val field = text("field")
        val changedAt = long("changed_at")
        val fromValue = text("from_value").nullable()
        val fromText = text("from_text").nullable()
        val toValue = text("to_value").nullable()
        val toText = text("to_text").nullable()
        val fieldId = varchar("field_id", 100).nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object Worklogs : Table("norm.work_item_worklogs") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val worklogId = long("worklog_id")
        val issueId = long("issue_id")
        val authorAccountId = varchar("author_account_id", 100).nullable()
        val startedAt = long("started_at")
        val timeSpentSeconds = long("time_spent_seconds")
        val createdAt = long("created_at").nullable()
        val updatedAt = long("updated_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, worklogId)
    }

    object Statuses : Table("norm.statuses") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val statusId = varchar("status_id", 50)
        val name = text("name")
        val category = varchar("category", 20)
        override val primaryKey = PrimaryKey(connectionId, statusId)
    }

    object People : Table("norm.people") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val accountId = varchar("account_id", 100)
        val displayName = text("display_name")
        val email = text("email").nullable()
        val active = bool("active").default(true)
        override val primaryKey = PrimaryKey(connectionId, accountId)
    }

    object Boards : Table("norm.boards") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val boardId = long("board_id")
        val name = text("name")
        val boardType = varchar("board_type", 20)
        val projectKey = varchar("project_key", 20).nullable()
        override val primaryKey = PrimaryKey(connectionId, boardId)
    }

    object BoardColumns : Table("norm.board_columns") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val boardId = long("board_id")
        val seq = integer("seq")
        val name = text("name")
        val statusIds = jsonb("status_ids")
        override val primaryKey = PrimaryKey(connectionId, boardId, seq)
    }

    object Sprints : Table("norm.sprints") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val boardId = long("board_id").nullable()
        val name = text("name")
        val state = varchar("state", 20)
        val startAt = long("start_at").nullable()
        val endAt = long("end_at").nullable()
        val goal = text("goal").nullable()
        val completeAt = long("complete_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    /** One `norm.people` row collapsed to its account id (v0.3.0 M1 commit 3's `/api/v1/jira-users`). */
    data class PersonRow(val accountId: String, val displayName: String)

    data class PersonListResult(val items: List<PersonRow>, val total: Long)

    /** One issue's PROFILE-relevant `norm.work_items` columns (v0.2.0 plan §8/§12 item 9) — `jira/JiraProfile.kt`'s bulk read. */
    data class ProfileWorkItemRow(
        val issueId: Long,
        val projectKey: String,
        val issueType: String,
        val assigneeAccountId: String?,
        val storyPoints: Double?,
        val originalEstimateSeconds: Long?,
        val createdAt: Long,
        val updatedAt: Long,
        val anomalies: List<TilingAnomaly>,
    )

    data class WorklogRow(val issueId: Long, val authorAccountId: String?, val timeSpentSeconds: Long)

    /** One `norm.work_item_worklogs` row, keeping the timestamps `metrics/MetricsDeriver.kt`'s `fact_worklog` step (commit 9) needs. */
    data class DerivationWorklogRow(
        val worklogId: Long,
        val issueId: Long,
        val authorAccountId: String?,
        val startedAt: Long,
        val timeSpentSeconds: Long,
        val createdAt: Long?,
        val updatedAt: Long?,
    )

    /**
     * One `norm.work_items` row's derivation-relevant columns (v0.3.0 M3 commit 7) —
     * `metrics/MetricsDeriver.kt`'s per-issue input, LIVE (non-tombstoned) rows only.
     */
    data class DerivationWorkItemRow(
        val issueId: Long,
        val issueKey: String,
        val projectKey: String,
        val issueType: String,
        val isSubtask: Boolean,
        val parentIssueId: Long?,
        val hierarchyLevel: Int?,
        val summary: String?,
        val createdAt: Long,
        val assigneeAccountId: String?,
        val dueAt: Long?,
        val customFields: JsonObject,
        val currentSprintIds: List<Long>,
    )

    /**
     * One issue's `norm.work_items` row, as a DTO (v0.2.0 plan §9/§12 item 8b) — the raw issue
     * inspector's read shape ("route handlers never touch tables", `.claude/docs/persistence.md`).
     */
    data class WorkItemView(
        val issueKey: String,
        val projectKey: String,
        val issueType: String,
        val statusId: String,
        val statusName: String,
        val statusCategory: StatusCategory,
        val assigneeAccountId: String?,
        val hierarchyLevel: Int?,
        val dueAt: Long?,
        val customFields: JsonObject,
        val anomalies: List<TilingAnomaly>,
        val processedAt: Long,
        val processingVersion: Int,
        val deletedAt: Long?,
        val movedOutAt: Long?,
    )

    // --- PROCESS write path ([WorkItemWriter]) ---

    /**
     * The PROCESS step's per-issue REPLACE (plan §8 step 5) — [replaceWorkItems] for one issue (the
     * fallback path of `jira/JiraProcessStream.kt` and the many test fixtures that write one item).
     */
    suspend fun replaceWorkItem(connectionId: UInt, normalized: NormalizedIssue, now: Long, processingVersion: Int = PROCESSING_VERSION) =
        writer.replaceWorkItems(connectionId, listOf(normalized), now, processingVersion)

    /** The PROCESS step's REPLACE for a whole page of issues, in one transaction — see [WorkItemWriter.replaceWorkItems]. */
    suspend fun replaceWorkItems(
        connectionId: UInt,
        normalized: List<NormalizedIssue>,
        now: Long,
        processingVersion: Int = PROCESSING_VERSION,
    ) = writer.replaceWorkItems(connectionId, normalized, now, processingVersion)

    // --- Reference rows ([NormReferenceStore]) — each replace returns the number of rows it skipped ---

    suspend fun replaceStatuses(connectionId: UInt, statuses: List<StatusRef>): Int = reference.replaceStatuses(connectionId, statuses)
    suspend fun replacePeople(connectionId: UInt, people: List<PersonRef>): Int = reference.replacePeople(connectionId, people)
    suspend fun replaceBoards(connectionId: UInt, boards: List<BoardRef>): Int = reference.replaceBoards(connectionId, boards)
    suspend fun replaceSprints(connectionId: UInt, sprints: List<SprintRef>): Int = reference.replaceSprints(connectionId, sprints)
    suspend fun allSprintRefs(connectionId: UInt): List<SprintRef> = reference.allSprintRefs(connectionId)
    suspend fun allBoardRefs(connectionId: UInt): List<BoardRef> = reference.allBoardRefs(connectionId)
    suspend fun allStatusRefs(connectionId: UInt): List<StatusRef> = reference.allStatusRefs(connectionId)

    // --- DERIVE reads ([NormDerivationReads]) ---

    suspend fun workItemsForDerivation(connectionId: UInt): List<DerivationWorkItemRow> = derivation.workItemsForDerivation(connectionId)
    suspend fun worklogsByIssue(connectionId: UInt): Map<Long, List<DerivationWorklogRow>> = derivation.worklogsByIssue(connectionId)
    suspend fun worklogSecondsByIssue(connectionId: UInt): Map<Long, Long> = derivation.worklogSecondsByIssue(connectionId)
    suspend fun fieldIntervalsByIssue(
        connectionId: UInt,
        field: TrackedField,
        issueIds: Collection<Long>? = null,
    ): Map<Long, List<NormalizedFieldInterval>> = derivation.fieldIntervalsByIssue(connectionId, field, issueIds)
    suspend fun fieldChangesByFieldIds(
        connectionId: UInt,
        fieldIds: Collection<String>,
        issueIds: Collection<Long>? = null,
    ): List<FieldChangeRow> = derivation.fieldChangesByFieldIds(connectionId, fieldIds, issueIds)
    suspend fun statusIntervalsByIssue(
        connectionId: UInt,
        issueIds: Collection<Long>? = null,
    ): Map<Long, List<NormalizedStatusInterval>> = derivation.statusIntervalsByIssue(connectionId, issueIds)

    // --- Data profile reads ([NormProfileReads]) ---

    suspend fun profileWorkItems(connectionId: UInt): List<ProfileWorkItemRow> = profile.profileWorkItems(connectionId)
    suspend fun worklogRows(connectionId: UInt): List<WorklogRow> = profile.worklogRows(connectionId)
    suspend fun fieldIntervalValues(connectionId: UInt, field: TrackedField): List<Pair<Long, String?>> =
        profile.fieldIntervalValues(connectionId, field)

    // --- Raw issue inspector reads ([NormInspectorReads]) ---

    suspend fun workItemView(connectionId: UInt, issueId: Long): WorkItemView? = inspector.workItemView(connectionId, issueId)
    suspend fun statusIntervalsForIssue(connectionId: UInt, issueId: Long): List<NormalizedStatusInterval> =
        inspector.statusIntervalsForIssue(connectionId, issueId)
    suspend fun fieldIntervalsForIssue(connectionId: UInt, issueId: Long): List<NormalizedFieldInterval> =
        inspector.fieldIntervalsForIssue(connectionId, issueId)

    // --- Picker and option reads ([NormPickerReads]) ---

    suspend fun listPeople(paging: PageRequest, q: String? = null, accountIds: Set<String>? = null): PersonListResult =
        pickers.listPeople(paging, q, accountIds)
    suspend fun distinctAssigneeAccountIds(): Set<String> = pickers.distinctAssigneeAccountIds()
    suspend fun distinctWorklogAuthorAccountIds(): Set<String> = pickers.distinctWorklogAuthorAccountIds()
    suspend fun distinctProjectKeys(connectionId: UInt): Set<String> = pickers.distinctProjectKeys(connectionId)
    suspend fun distinctIssueTypes(connectionId: UInt): Set<String> = pickers.distinctIssueTypes(connectionId)
    suspend fun distinctIntervalStatusIds(connectionId: UInt): Set<String> = pickers.distinctIntervalStatusIds(connectionId)
    suspend fun distinctCustomFieldValues(connectionId: UInt, fieldId: String): List<Pair<String, String?>> =
        pickers.distinctCustomFieldValues(connectionId, fieldId)

    // --- PURGE ([NormPurge]; the drain loop is the `purgeAll` extension in that file) ---

    suspend fun purgeWorkItemsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int =
        purge.purgeWorkItemsBatch(connectionId, batchSize)
    suspend fun purgeStatusIntervalsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int =
        purge.purgeStatusIntervalsBatch(connectionId, batchSize)
    suspend fun purgeFieldIntervalsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int =
        purge.purgeFieldIntervalsBatch(connectionId, batchSize)
    suspend fun purgeFieldChangesBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int =
        purge.purgeFieldChangesBatch(connectionId, batchSize)
    suspend fun purgeWorklogsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int =
        purge.purgeWorklogsBatch(connectionId, batchSize)
    suspend fun purgeReferenceRows(connectionId: UInt) = purge.purgeReferenceRows(connectionId)
}
