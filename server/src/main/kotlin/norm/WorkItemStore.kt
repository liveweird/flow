package ch.nokillswit.norm

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.DataSourceService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.batchUpsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** Batch size for the PURGE step's cleanup over the bigger `norm.*` tables — mirrors `jira/JiraRawStore.kt`'s `JIRA_PURGE_BATCH_SIZE`. */
internal const val NORM_PURGE_BATCH_SIZE = 500

/** `WorkItemStore.distinctCustomFieldValues`' response-size cap (v0.3.0 M1 commit 4 review fix). */
internal const val MAX_DISTINCT_FIELD_VALUES = 200

/** Published by `jira/Jira.kt`'s `configureJira` — the raw issue inspector and the data profile step both read it back. */
val WorkItemStoreKey = AttributeKey<WorkItemStore>("WorkItemStore")

private fun stringArrayJson(values: List<String>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()
private fun longArrayJson(values: List<Long>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()
private fun anomaliesJson(values: List<TilingAnomaly>): String =
    buildJsonArray { values.forEach { add(JsonPrimitive(it.name)) } }.toString()

/** The inverse of [anomaliesJson] — the raw issue inspector's/data profile's own read path. */
private fun parseAnomalies(json: String): List<TilingAnomaly> =
    Json.parseToJsonElement(json).jsonArray.map { TilingAnomaly.valueOf(it.jsonPrimitive.content) }

/** The inverse of [stringArrayJson]. */
private fun parseStringArray(json: String): List<String> = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }

/** The inverse of `longArrayJson` (`norm.work_items.current_sprint_ids`) — `WorkItemStore.workItemsForDerivation`'s own reader. */
private fun parseLongArray(json: String): List<Long> = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.long }

/**
 * One custom-field value, as `(valueId, valueName)` pairs — `WorkItemStore.distinctCustomFieldValues`'
 * own reader, handling every shape a Jira field value can take (an object, a bare primitive, or an
 * array of either) without ever casting blindly.
 */
internal fun fieldValueOptions(element: JsonElement?): List<Pair<String, String?>> = when {
    element == null || element == JsonNull -> emptyList()
    element is JsonArray -> element.flatMap { fieldValueOptions(it) }
    element is JsonObject -> {
        val id = element["id"]?.jsonPrimitive?.contentOrNull ?: element["value"]?.jsonPrimitive?.contentOrNull
        val name = element["value"]?.jsonPrimitive?.contentOrNull ?: element["name"]?.jsonPrimitive?.contentOrNull
        id?.let { listOf(it to name) } ?: emptyList()
    }
    else -> element.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { listOf(it to it) } ?: emptyList()
}

/** A rebuilt reference row (plan §8: "reference rows ... rebuilt per connection each PROCESS") — one per `norm.statuses` row. */
data class StatusRef(val statusId: String, val name: String, val category: StatusCategory)
data class PersonRef(val accountId: String, val displayName: String, val email: String?, val active: Boolean)
data class BoardColumnRef(val name: String, val statusIds: List<String>)
data class BoardRef(val boardId: Long, val name: String, val boardType: String, val projectKey: String?, val columns: List<BoardColumnRef>)
data class SprintRef(
    val sprintId: Long,
    val boardId: Long?,
    val name: String,
    val state: String,
    val startAtMs: Long?,
    val endAtMs: Long?,
    val goal: String?,
    /** `completeDate` (v0.3.0 M1 commit 2) — the metrics layer keys sprint periods on completion, not `endAt`. */
    val completeAtMs: Long? = null,
)

/** One `norm.work_item_field_changes` row, connection-wide (v0.3.0 M1 commit 2) — [fieldChangesByFieldIds]'s own read shape. */
data class FieldChangeRow(
    val issueId: Long,
    val fieldId: String?,
    val field: String,
    val changedAt: Long,
    val fromValue: String?,
    val fromText: String?,
    val toValue: String?,
    val toText: String?,
)

/**
 * The `norm` schema's Exposed table set (v0.2.0 plan §0 A3/§4 V13) — the PROCESS step's write
 * target. Every write is a REPLACE of a PAGE of issues (one transaction per page, plan §8 step 5;
 * a page of one issue is the per-issue REPLACE): delete those issues' child rows, insert the freshly
 * tiled ones, upsert `work_items` — the scope of a replace stays the ISSUE (a page never touches an
 * issue outside its id list). Reference rows (statuses/people/boards/board_columns/sprints) are
 * rebuilt WHOLESALE per connection, once per PROCESS run — never diffed row-by-row like
 * `raw.jira_entities`.
 */
class WorkItemStore(private val database: R2dbcDatabase) {

    object WorkItems : Table("norm.work_items") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val issueKey = varchar("issue_key", 20)
        val projectKey = varchar("project_key", 20)
        val issueType = varchar("issue_type", 50)
        val isSubtask = bool("is_subtask").default(false)
        val parentIssueId = long("parent_issue_id").nullable()
        val summary = text("summary").nullable()
        val statusId = varchar("status_id", 50)
        val statusName = varchar("status_name", 100)
        val statusCategory = varchar("status_category", 20)
        val resolution = varchar("resolution", 100).nullable()
        val priority = varchar("priority", 50).nullable()
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
        val statusName = varchar("status_name", 100)
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
        val valueText = varchar("value_text", 500).nullable()
        val fromAt = long("from_at")
        val toAt = long("to_at").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    object FieldChanges : Table("norm.work_item_field_changes") {
        val id = integer("id").autoIncrement()
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val issueId = long("issue_id")
        val seq = integer("seq")
        val field = varchar("field", 50)
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
        val name = varchar("name", 100)
        val category = varchar("category", 20)
        override val primaryKey = PrimaryKey(connectionId, statusId)
    }

    object People : Table("norm.people") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val accountId = varchar("account_id", 100)
        val displayName = varchar("display_name", 200)
        val email = varchar("email", 254).nullable()
        val active = bool("active").default(true)
        override val primaryKey = PrimaryKey(connectionId, accountId)
    }

    object Boards : Table("norm.boards") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val boardId = long("board_id")
        val name = varchar("name", 200)
        val boardType = varchar("board_type", 20)
        val projectKey = varchar("project_key", 20).nullable()
        override val primaryKey = PrimaryKey(connectionId, boardId)
    }

    object BoardColumns : Table("norm.board_columns") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val boardId = long("board_id")
        val seq = integer("seq")
        val name = varchar("name", 200)
        val statusIds = jsonb("status_ids")
        override val primaryKey = PrimaryKey(connectionId, boardId, seq)
    }

    object Sprints : Table("norm.sprints") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val boardId = long("board_id").nullable()
        val name = varchar("name", 200)
        val state = varchar("state", 20)
        val startAt = long("start_at").nullable()
        val endAt = long("end_at").nullable()
        val goal = text("goal").nullable()
        val completeAt = long("complete_at").nullable()
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    /**
     * The PROCESS step's per-issue REPLACE (plan §8 step 5) — [replaceWorkItems] for one issue (the
     * fallback path of `jira/JiraProcessStream.kt` and the many test fixtures that write one item).
     */
    suspend fun replaceWorkItem(connectionId: UInt, normalized: NormalizedIssue, now: Long, processingVersion: Int = PROCESSING_VERSION) =
        replaceWorkItems(connectionId, listOf(normalized), now, processingVersion)

    /**
     * The PROCESS step's REPLACE (plan §8 step 5) for a whole page of issues — delete the page's child
     * rows (ONE `DELETE … issue_id IN (…)` per table), insert every freshly tiled row of the page (ONE
     * batch per table), upsert `work_items` (ONE `ON CONFLICT (connection_id, issue_id) DO UPDATE`
     * batch), ALL in one transaction. The rows written are byte-for-byte the ones N per-issue calls
     * write (same columns, same per-issue `seq`); only the number of round trips differs — measured
     * ~13 statements per ISSUE became ~13 per PAGE (`.claude/docs/build-times.md`). The caller
     * (`jira/JiraProcessStream.kt`) wraps this in the SAME `StreamContext.transaction { }` block as the
     * raw rows' own `processed_at`/`needs_processing` update, so a crash mid-page never leaves a
     * half-written normalized row or a raw row pointing at rows that were never written.
     */
    suspend fun replaceWorkItems(
        connectionId: UInt,
        normalized: List<NormalizedIssue>,
        now: Long,
        processingVersion: Int = PROCESSING_VERSION,
    ) {
        if (normalized.isEmpty()) return
        suspendTransaction(database) {
            val issueIds = normalized.map { it.issueId }
            StatusIntervals.deleteWhere { (StatusIntervals.connectionId eq connectionId) and (StatusIntervals.issueId inList issueIds) }
            FieldIntervals.deleteWhere { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.issueId inList issueIds) }
            FieldChanges.deleteWhere { (FieldChanges.connectionId eq connectionId) and (FieldChanges.issueId inList issueIds) }
            Worklogs.deleteWhere { (Worklogs.connectionId eq connectionId) and (Worklogs.issueId inList issueIds) }

            val statusRows = normalized.flatMap { n -> n.statusIntervals.map { n.issueId to it } }
            if (statusRows.isNotEmpty()) {
                StatusIntervals.batchInsert(statusRows, shouldReturnGeneratedValues = false) { (issueId, interval) ->
                    this[StatusIntervals.connectionId] = connectionId
                    this[StatusIntervals.issueId] = issueId
                    this[StatusIntervals.seq] = interval.seq
                    this[StatusIntervals.statusId] = interval.statusId
                    this[StatusIntervals.statusName] = interval.statusName
                    this[StatusIntervals.statusCategory] = interval.category.name
                    this[StatusIntervals.fromAt] = interval.fromAtMs
                    this[StatusIntervals.toAt] = interval.toAtMs
                    this[StatusIntervals.intervalSource] = interval.source.name
                }
            }
            val fieldIntervalRows = normalized.flatMap { n -> n.fieldIntervals.map { n.issueId to it } }
            if (fieldIntervalRows.isNotEmpty()) {
                FieldIntervals.batchInsert(fieldIntervalRows, shouldReturnGeneratedValues = false) { (issueId, interval) ->
                    this[FieldIntervals.connectionId] = connectionId
                    this[FieldIntervals.issueId] = issueId
                    this[FieldIntervals.field] = interval.field.name
                    this[FieldIntervals.seq] = interval.seq
                    this[FieldIntervals.valueId] = interval.valueId
                    this[FieldIntervals.valueText] = interval.valueText
                    this[FieldIntervals.fromAt] = interval.fromAtMs
                    this[FieldIntervals.toAt] = interval.toAtMs
                }
            }
            // `seq` is per ISSUE (1-based, in changelog order) — never a page-wide counter.
            val fieldChangeRows = normalized.flatMap { n ->
                n.fieldChanges.mapIndexed { index, change -> Triple(n.issueId, index + 1, change) }
            }
            if (fieldChangeRows.isNotEmpty()) {
                FieldChanges.batchInsert(fieldChangeRows, shouldReturnGeneratedValues = false) { (issueId, seq, change) ->
                    this[FieldChanges.connectionId] = connectionId
                    this[FieldChanges.issueId] = issueId
                    this[FieldChanges.seq] = seq
                    this[FieldChanges.field] = change.field
                    this[FieldChanges.changedAt] = change.atMs
                    this[FieldChanges.fromValue] = change.fromValue
                    this[FieldChanges.fromText] = change.fromText
                    this[FieldChanges.toValue] = change.toValue
                    this[FieldChanges.toText] = change.toText
                    this[FieldChanges.fieldId] = change.fieldId
                }
            }
            val worklogRows = normalized.flatMap { n -> n.worklogs.map { n.issueId to it } }
            if (worklogRows.isNotEmpty()) {
                Worklogs.batchInsert(worklogRows, shouldReturnGeneratedValues = false) { (issueId, worklog) ->
                    this[Worklogs.connectionId] = connectionId
                    this[Worklogs.worklogId] = worklog.worklogId
                    this[Worklogs.issueId] = issueId
                    this[Worklogs.authorAccountId] = worklog.authorAccountId
                    this[Worklogs.startedAt] = worklog.startedAtMs
                    this[Worklogs.timeSpentSeconds] = worklog.timeSpentSeconds
                    this[Worklogs.createdAt] = worklog.createdAtMs
                    this[Worklogs.updatedAt] = worklog.updatedAtMs
                }
            }

            upsertWorkItems(connectionId, normalized, now, processingVersion)
        }
    }

    /**
     * Insert-or-update `norm.work_items` on its PK — every non-key column is written either way, so an
     * existing row ends up exactly as the old select-then-insert/update produced it. Runs inside
     * [replaceWorkItems]' transaction.
     */
    private suspend fun upsertWorkItems(connectionId: UInt, normalized: List<NormalizedIssue>, now: Long, processingVersion: Int) {
        WorkItems.batchUpsert(normalized, WorkItems.connectionId, WorkItems.issueId, shouldReturnGeneratedValues = false) { item ->
            val facts = item.facts
            this[WorkItems.connectionId] = connectionId
            this[WorkItems.issueId] = item.issueId
            this[WorkItems.issueKey] = facts.issueKey
            this[WorkItems.projectKey] = facts.projectKey
            this[WorkItems.issueType] = facts.issueType
            this[WorkItems.isSubtask] = facts.isSubtask
            this[WorkItems.parentIssueId] = facts.parentIssueId
            this[WorkItems.summary] = facts.summary
            this[WorkItems.statusId] = facts.currentStatusId
            this[WorkItems.statusName] = item.currentStatusName
            this[WorkItems.statusCategory] = item.currentStatusCategory.name
            this[WorkItems.resolution] = facts.resolution
            this[WorkItems.priority] = facts.priority
            this[WorkItems.assigneeAccountId] = facts.assigneeAccountId
            this[WorkItems.reporterAccountId] = facts.reporterAccountId
            this[WorkItems.createdAt] = facts.createdAtMs
            this[WorkItems.updatedAt] = facts.updatedAtMs
            this[WorkItems.resolvedAt] = facts.resolvedAtMs
            this[WorkItems.storyPoints] = facts.storyPoints
            this[WorkItems.originalEstimateSeconds] = facts.originalEstimateSeconds
            this[WorkItems.timeSpentSeconds] = facts.timeSpentSeconds
            this[WorkItems.labels] = stringArrayJson(facts.labels)
            this[WorkItems.components] = stringArrayJson(facts.components)
            this[WorkItems.fixVersions] = stringArrayJson(facts.fixVersions)
            this[WorkItems.currentSprintIds] = longArrayJson(item.currentSprintIds)
            this[WorkItems.teamValue] = facts.teamValueJson
            this[WorkItems.flagged] = item.flagged
            this[WorkItems.rank] = facts.rank
            this[WorkItems.hierarchyLevel] = facts.hierarchyLevel
            this[WorkItems.dueAt] = facts.dueAtMs
            this[WorkItems.customFields] = facts.customFieldsJson
            this[WorkItems.anomalies] = anomaliesJson(item.anomalies)
            // The RAW tombstone time, never `now` — a PROCESSING_VERSION bump reprocesses every
            // already-tombstoned issue, and `now` would reset every one of them to the
            // reprocess/deploy time (v0.3.0 M1 commit 2 review fix). `?: now` is a defensive
            // fallback for a caller that (like a hand-built test fixture) omits `tombstoneAtMs`.
            this[WorkItems.deletedAt] = if (facts.tombstone == TombstoneKind.DELETED) (facts.tombstoneAtMs ?: now) else null
            this[WorkItems.movedOutAt] = if (facts.tombstone == TombstoneKind.MOVED_OUT) (facts.tombstoneAtMs ?: now) else null
            this[WorkItems.processedAt] = now
            this[WorkItems.processingVersion] = processingVersion
        }
    }

    /** Reference rows are rebuilt WHOLESALE per connection, once per PROCESS run (plan §8) — never diffed. */
    suspend fun replaceStatuses(connectionId: UInt, statuses: List<StatusRef>) = suspendTransaction(database) {
        Statuses.deleteWhere { Statuses.connectionId eq connectionId }
        if (statuses.isNotEmpty()) {
            Statuses.batchInsert(statuses) {
                this[Statuses.connectionId] = connectionId
                this[Statuses.statusId] = it.statusId
                this[Statuses.name] = it.name
                this[Statuses.category] = it.category.name
            }
        }
    }

    /** One `norm.people` row collapsed to its account id (v0.3.0 M1 commit 3's `/api/v1/jira-users`). */
    data class PersonRow(val accountId: String, val displayName: String)

    data class PersonListResult(val items: List<PersonRow>, val total: Long)

    /**
     * Every Jira account known to an ACTIVE connection, DISTINCT by account id (v0.3.0 M1 commit
     * 3): two connections to the same site share account ids (plan §12 risk note), so this
     * collapses via `GROUP BY account_id` (picking the alphabetically-first display name via
     * `MIN`) rather than a connection-scoped read. Excludes soft-deleted connections and inactive
     * `norm.people` rows (a departed user is marked `active = false` on the NEXT REFERENCE pass'
     * wholesale rebuild, never removed outright). [q] filters by [containsNormalized] on the
     * display name; [accountIds] (when non-null) restricts to that exact set (e.g. a team's
     * current membership, or the unit-relevant set `JiraUsersRoutes.kt` computes) — an empty set
     * short-circuits to no rows. `count(DISTINCT)` and the grouped page read run in the SAME
     * transaction (list-endpoints.md), ordered by the aggregated display name then account id (a
     * deterministic tiebreaker — never left to whatever order `GROUP BY` happens to return).
     */
    suspend fun listPeople(paging: PageRequest, q: String? = null, accountIds: Set<String>? = null): PersonListResult =
        suspendTransaction(database) {
            if (accountIds != null && accountIds.isEmpty()) return@suspendTransaction PersonListResult(emptyList(), 0)
            var predicate: Op<Boolean> = DataSourceService.Connections.active() and (People.active eq true)
            q?.let { predicate = predicate and People.displayName.containsNormalized(it) }
            accountIds?.let { ids -> predicate = predicate and (People.accountId inList ids) }

            val joined = People.innerJoin(DataSourceService.Connections)
            val countExpr = People.accountId.countDistinct()
            val total = joined.select(countExpr).where { predicate }.single()[countExpr]

            val nameAgg = People.displayName.min()
            val descending = paging.sort.firstOrNull { it.name == "displayName" }?.descending == true
            val rows = joined.select(People.accountId, nameAgg).where { predicate }
                .groupBy(People.accountId)
                .orderBy(nameAgg to (if (descending) SortOrder.DESC else SortOrder.ASC), People.accountId to SortOrder.ASC)
                .limit(paging.pageSize)
                .offset((paging.page - 1).toLong() * paging.pageSize)
                .toList()
                // MIN() over a non-null column is itself typed nullable by Exposed (an empty group
                // yields SQL NULL in general) — never actually null here, since GROUP BY only ever
                // emits a row for an account id that has at least one matching people row.
                .map { PersonRow(it[People.accountId], it[nameAgg] ?: "") }
            PersonListResult(rows, total)
        }

    /** Distinct assignee account ids across ACTIVE connections' LIVE work items — one input to `/jira-users`' UNIT scope. */
    suspend fun distinctAssigneeAccountIds(): Set<String> = suspendTransaction(database) {
        WorkItems.innerJoin(DataSourceService.Connections)
            .select(WorkItems.assigneeAccountId).withDistinct()
            .where { DataSourceService.Connections.active() and WorkItems.assigneeAccountId.isNotNull() and WorkItems.deletedAt.isNull() }
            .toList()
            .mapNotNull { it[WorkItems.assigneeAccountId] }
            .toSet()
    }

    /** Distinct worklog author account ids across ACTIVE connections — the other input to `/jira-users`' UNIT scope. */
    suspend fun distinctWorklogAuthorAccountIds(): Set<String> = suspendTransaction(database) {
        Worklogs.innerJoin(DataSourceService.Connections)
            .select(Worklogs.authorAccountId).withDistinct()
            .where { DataSourceService.Connections.active() and Worklogs.authorAccountId.isNotNull() }
            .toList()
            .mapNotNull { it[Worklogs.authorAccountId] }
            .toSet()
    }

    suspend fun replacePeople(connectionId: UInt, people: List<PersonRef>) = suspendTransaction(database) {
        People.deleteWhere { People.connectionId eq connectionId }
        if (people.isNotEmpty()) {
            People.batchInsert(people) {
                this[People.connectionId] = connectionId
                this[People.accountId] = it.accountId
                this[People.displayName] = it.displayName
                this[People.email] = it.email
                this[People.active] = it.active
            }
        }
    }

    suspend fun replaceBoards(connectionId: UInt, boards: List<BoardRef>) = suspendTransaction(database) {
        Boards.deleteWhere { Boards.connectionId eq connectionId }
        BoardColumns.deleteWhere { BoardColumns.connectionId eq connectionId }
        if (boards.isNotEmpty()) {
            Boards.batchInsert(boards) {
                this[Boards.connectionId] = connectionId
                this[Boards.boardId] = it.boardId
                this[Boards.name] = it.name
                this[Boards.boardType] = it.boardType
                this[Boards.projectKey] = it.projectKey
            }
        }
        boards.forEach { board ->
            if (board.columns.isNotEmpty()) {
                BoardColumns.batchInsert(board.columns.withIndex().toList()) { (index, column) ->
                    this[BoardColumns.connectionId] = connectionId
                    this[BoardColumns.boardId] = board.boardId
                    this[BoardColumns.seq] = index + 1
                    this[BoardColumns.name] = column.name
                    this[BoardColumns.statusIds] = stringArrayJson(column.statusIds)
                }
            }
        }
    }

    suspend fun replaceSprints(connectionId: UInt, sprints: List<SprintRef>) = suspendTransaction(database) {
        Sprints.deleteWhere { Sprints.connectionId eq connectionId }
        if (sprints.isNotEmpty()) {
            Sprints.batchInsert(sprints) {
                this[Sprints.connectionId] = connectionId
                this[Sprints.sprintId] = it.sprintId
                this[Sprints.boardId] = it.boardId
                this[Sprints.name] = it.name
                this[Sprints.state] = it.state
                this[Sprints.startAt] = it.startAtMs
                this[Sprints.endAt] = it.endAtMs
                this[Sprints.goal] = it.goal
                this[Sprints.completeAt] = it.completeAtMs
            }
        }
    }

    // RECONCILE/PROCESS tombstone mirror (plan §8): a raw issue tombstoned since the last PROCESS
    // gets the SAME tombstone here, on its next normal replace — no separate code path.

    suspend fun countWorkItems(connectionId: UInt): Long = suspendTransaction(database) {
        WorkItems.selectAll().where { WorkItems.connectionId eq connectionId }.count()
    }

    suspend fun countStatusIntervals(connectionId: UInt): Long = suspendTransaction(database) {
        StatusIntervals.selectAll().where { StatusIntervals.connectionId eq connectionId }.count()
    }

    suspend fun countFieldIntervals(connectionId: UInt, field: TrackedField? = null): Long = suspendTransaction(database) {
        val predicate = if (field != null) {
            (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.field eq field.name)
        } else {
            FieldIntervals.connectionId eq connectionId
        }
        FieldIntervals.selectAll().where { predicate }.count()
    }

    suspend fun countWorklogs(connectionId: UInt): Long = suspendTransaction(database) {
        Worklogs.selectAll().where { Worklogs.connectionId eq connectionId }.count()
    }

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

    /**
     * Every LIVE (non-tombstoned) work item for a connection (v0.2.0 plan §8/§12 item 9) — the data
     * profile's own base row set; every section is derived from this plus the interval/reference
     * reads below, never a second raw-store pass.
     */
    suspend fun profileWorkItems(connectionId: UInt): List<ProfileWorkItemRow> = suspendTransaction(database) {
        WorkItems.selectAll().where {
            (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull()
        }.toList().map { row ->
            ProfileWorkItemRow(
                issueId = row[WorkItems.issueId],
                projectKey = row[WorkItems.projectKey],
                issueType = row[WorkItems.issueType],
                assigneeAccountId = row[WorkItems.assigneeAccountId],
                storyPoints = row[WorkItems.storyPoints],
                originalEstimateSeconds = row[WorkItems.originalEstimateSeconds],
                createdAt = row[WorkItems.createdAt],
                updatedAt = row[WorkItems.updatedAt],
                anomalies = parseAnomalies(row[WorkItems.anomalies]),
            )
        }
    }

    data class WorklogRow(val issueId: Long, val authorAccountId: String?, val timeSpentSeconds: Long)

    /** Every `norm.work_item_worklogs` row for a connection — the caller (`jira/JiraProfile.kt`) filters to live issue ids itself. */
    suspend fun worklogRows(connectionId: UInt): List<WorklogRow> = suspendTransaction(database) {
        Worklogs.selectAll().where { Worklogs.connectionId eq connectionId }
            .map { WorklogRow(it[Worklogs.issueId], it[Worklogs.authorAccountId], it[Worklogs.timeSpentSeconds]) }.toList()
    }

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
     * Every `norm.work_item_worklogs` row for a connection, grouped by issue (v0.3.0 M3 commit 7)
     * — `metrics/MetricsDeriver.kt`'s own per-issue read.
     */
    suspend fun worklogsByIssue(connectionId: UInt): Map<Long, List<DerivationWorklogRow>> = suspendTransaction(database) {
        Worklogs.selectAll().where { Worklogs.connectionId eq connectionId }
            .toList()
            .map {
                DerivationWorklogRow(
                    worklogId = it[Worklogs.worklogId],
                    issueId = it[Worklogs.issueId],
                    authorAccountId = it[Worklogs.authorAccountId],
                    startedAt = it[Worklogs.startedAt],
                    timeSpentSeconds = it[Worklogs.timeSpentSeconds],
                    createdAt = it[Worklogs.createdAt],
                    updatedAt = it[Worklogs.updatedAt],
                )
            }
            .groupBy { it.issueId }
    }

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

    /** Every LIVE work item for a connection, as [DerivationWorkItemRow] — `metrics/MetricsDeriver.kt`'s per-issue base row set. */
    suspend fun workItemsForDerivation(connectionId: UInt): List<DerivationWorkItemRow> = suspendTransaction(database) {
        WorkItems.selectAll().where {
            (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull()
        }.toList().map { row ->
            DerivationWorkItemRow(
                issueId = row[WorkItems.issueId],
                issueKey = row[WorkItems.issueKey],
                projectKey = row[WorkItems.projectKey],
                issueType = row[WorkItems.issueType],
                isSubtask = row[WorkItems.isSubtask],
                parentIssueId = row[WorkItems.parentIssueId],
                hierarchyLevel = row[WorkItems.hierarchyLevel],
                summary = row[WorkItems.summary],
                createdAt = row[WorkItems.createdAt],
                assigneeAccountId = row[WorkItems.assigneeAccountId],
                dueAt = row[WorkItems.dueAt],
                customFields = Json.parseToJsonElement(row[WorkItems.customFields]).jsonObject,
                currentSprintIds = parseLongArray(row[WorkItems.currentSprintIds]),
            )
        }
    }

    /**
     * Every `norm.work_item_field_intervals` row of [field] for a connection, as `(issueId,
     * valueId)` pairs — the sprint profile's own read.
     */
    suspend fun fieldIntervalValues(connectionId: UInt, field: TrackedField): List<Pair<Long, String?>> = suspendTransaction(database) {
        FieldIntervals.select(FieldIntervals.issueId, FieldIntervals.valueId)
            .where { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.field eq field.name) }
            .map { it[FieldIntervals.issueId] to it[FieldIntervals.valueId] }.toList()
    }

    /**
     * Every `norm.work_item_field_intervals` row of [field], connection-wide, grouped by issue and
     * ordered by `seq` (v0.3.0 M1 commit 2) — the metrics layer's per-issue replay read (e.g. PARENT
     * for `task_epic`, SPRINT for `task_sprint`), the connection-wide sibling of
     * [fieldIntervalsForIssue]. [issueIds], when non-null (v0.3.0 M3 review round 2b — the batched
     * DERIVE read), scopes the read to only those issues — `MetricsDeriver.kt`'s per-batch-of-200
     * memory bound; `null` (every other caller) keeps the whole-connection scan.
     */
    suspend fun fieldIntervalsByIssue(
        connectionId: UInt,
        field: TrackedField,
        issueIds: Collection<Long>? = null,
    ): Map<Long, List<NormalizedFieldInterval>> =
        suspendTransaction(database) {
            if (issueIds != null && issueIds.isEmpty()) return@suspendTransaction emptyMap()
            var predicate = (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.field eq field.name)
            if (issueIds != null) predicate = predicate and (FieldIntervals.issueId inList issueIds)
            FieldIntervals.selectAll().where { predicate }
                .toList()
                .groupBy({ it[FieldIntervals.issueId] }) {
                    NormalizedFieldInterval(
                        field = field,
                        seq = it[FieldIntervals.seq],
                        valueId = it[FieldIntervals.valueId],
                        valueText = it[FieldIntervals.valueText],
                        fromAtMs = it[FieldIntervals.fromAt],
                        toAtMs = it[FieldIntervals.toAt],
                    )
                }
                .mapValues { (_, intervals) -> intervals.sortedBy { it.seq } }
        }

    /**
     * Every `norm.work_item_field_changes` row whose `field_id` is one of [fieldIds], connection-wide
     * (v0.3.0 M1 commit 2) — the metrics layer's per-field estimate/epic-date replay read (e.g. the
     * configured estimate field's changes, to build `item_estimate` timelines). [issueIds], when
     * non-null (v0.3.0 M3 review round 2b), scopes the read to only those issues —
     * `MetricsDeriver.kt`'s per-batch-of-200 memory bound; `null` keeps the whole-connection scan.
     */
    suspend fun fieldChangesByFieldIds(
        connectionId: UInt,
        fieldIds: Collection<String>,
        issueIds: Collection<Long>? = null,
    ): List<FieldChangeRow> =
        suspendTransaction(database) {
            if (fieldIds.isEmpty() || issueIds?.isEmpty() == true) return@suspendTransaction emptyList()
            var predicate = (FieldChanges.connectionId eq connectionId) and (FieldChanges.fieldId inList fieldIds)
            if (issueIds != null) predicate = predicate and (FieldChanges.issueId inList issueIds)
            FieldChanges.selectAll().where { predicate }
                .orderBy(FieldChanges.issueId to SortOrder.ASC, FieldChanges.changedAt to SortOrder.ASC, FieldChanges.seq to SortOrder.ASC)
                .toList()
                .map { row ->
                    FieldChangeRow(
                        issueId = row[FieldChanges.issueId],
                        fieldId = row[FieldChanges.fieldId],
                        field = row[FieldChanges.field],
                        changedAt = row[FieldChanges.changedAt],
                        fromValue = row[FieldChanges.fromValue],
                        fromText = row[FieldChanges.fromText],
                        toValue = row[FieldChanges.toValue],
                        toText = row[FieldChanges.toText],
                    )
                }
        }

    /**
     * Every `norm.sprints` reference row for a connection (v0.2.0 plan §8/§12 item 9) — rebuilt
     * wholesale per PROCESS run, read back as-is.
     */
    suspend fun allSprintRefs(connectionId: UInt): List<SprintRef> = suspendTransaction(database) {
        Sprints.selectAll().where { Sprints.connectionId eq connectionId }.toList().map { row ->
            SprintRef(
                sprintId = row[Sprints.sprintId],
                boardId = row[Sprints.boardId],
                name = row[Sprints.name],
                state = row[Sprints.state],
                startAtMs = row[Sprints.startAt],
                endAtMs = row[Sprints.endAt],
                goal = row[Sprints.goal],
                completeAtMs = row[Sprints.completeAt],
            )
        }
    }

    /** Every `norm.boards`/`norm.board_columns` reference row for a connection, joined back into [BoardRef] shape. */
    suspend fun allBoardRefs(connectionId: UInt): List<BoardRef> = suspendTransaction(database) {
        val columnsByBoard = BoardColumns.selectAll().where { BoardColumns.connectionId eq connectionId }
            .toList().sortedBy { it[BoardColumns.seq] }
            .groupBy({ it[BoardColumns.boardId] }) { BoardColumnRef(it[BoardColumns.name], parseStringArray(it[BoardColumns.statusIds])) }
        Boards.selectAll().where { Boards.connectionId eq connectionId }.toList().map { row ->
            val boardId = row[Boards.boardId]
            BoardRef(
                boardId = boardId,
                name = row[Boards.name],
                boardType = row[Boards.boardType],
                projectKey = row[Boards.projectKey],
                columns = columnsByBoard[boardId] ?: emptyList(),
            )
        }
    }

    /** Every `norm.statuses` reference row for a connection — the data profile's status-id-to-name lookup for board columns. */
    suspend fun allStatusRefs(connectionId: UInt): List<StatusRef> = suspendTransaction(database) {
        Statuses.selectAll().where { Statuses.connectionId eq connectionId }.toList().map { row ->
            StatusRef(row[Statuses.statusId], row[Statuses.name], StatusCategory.valueOf(row[Statuses.category]))
        }
    }

    /** Distinct project keys among a connection's LIVE work items (v0.3.0 M1 commit 4) — the metrics-config defaults' 1:1 domain map. */
    suspend fun distinctProjectKeys(connectionId: UInt): Set<String> = suspendTransaction(database) {
        WorkItems.select(WorkItems.projectKey).withDistinct()
            .where { (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull() }
            .map { it[WorkItems.projectKey] }.toList().toSet()
    }

    /** Distinct issue types among a connection's LIVE work items (v0.3.0 M1 commit 4) — the metrics-config defaults' activity-type map. */
    suspend fun distinctIssueTypes(connectionId: UInt): Set<String> = suspendTransaction(database) {
        WorkItems.select(WorkItems.issueType).withDistinct()
            .where { (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull() }
            .map { it[WorkItems.issueType] }.toList().toSet()
    }

    /**
     * Every distinct `(valueId, valueName)` pair a `custom_fields[fieldId]` value carries across a
     * connection's LIVE work items (v0.3.0 M1 commit 4) — the metrics-config options endpoint's
     * `?workCategoryField=` read and `work_category_map`'s own id validation. Handles every shape
     * Jira uses for a select-field value: an object (`{id, value}`), a bare string/number, or an
     * array of either (a multi-select) — never a cast failure on an unexpected shape, since the
     * field could be ANY custom field the admin picks, not necessarily a `select`. Returns the FULL
     * set, uncapped: `work_category_map` id validation must never reject a legitimate value just
     * because it fell outside the OPTIONS endpoint's own display cap
     * (`MetricsConfigService.options`, [MAX_DISTINCT_FIELD_VALUES]) — the config table itself has
     * no such limit.
     */
    suspend fun distinctCustomFieldValues(connectionId: UInt, fieldId: String): List<Pair<String, String?>> =
        suspendTransaction(database) {
            WorkItems.select(WorkItems.customFields)
                .where { (WorkItems.connectionId eq connectionId) and WorkItems.deletedAt.isNull() and WorkItems.movedOutAt.isNull() }
                .toList()
                .flatMap { row ->
                    val element = Json.parseToJsonElement(row[WorkItems.customFields]).jsonObject[fieldId]
                    fieldValueOptions(element)
                }
                .distinctBy { it.first }
                .sortedBy { it.first }
        }

    /**
     * Every work item's tiled status intervals, ordered — the pipeline test's SQL-invariant-sweep/
     * reopen-count source. [issueIds], when non-null (v0.3.0 M3 review round 2b), scopes the read to
     * only those issues — `MetricsDeriver.kt`'s per-batch-of-200 memory bound; `null` (every other
     * caller) keeps the whole-connection scan.
     */
    suspend fun statusIntervalsByIssue(
        connectionId: UInt,
        issueIds: Collection<Long>? = null,
    ): Map<Long, List<NormalizedStatusInterval>> = suspendTransaction(database) {
        if (issueIds != null && issueIds.isEmpty()) return@suspendTransaction emptyMap()
        var predicate: Op<Boolean> = StatusIntervals.connectionId eq connectionId
        if (issueIds != null) predicate = predicate and (StatusIntervals.issueId inList issueIds)
        StatusIntervals.selectAll().where { predicate }
            .toList()
            .groupBy({ it[StatusIntervals.issueId] }) {
                NormalizedStatusInterval(
                    seq = it[StatusIntervals.seq],
                    statusId = it[StatusIntervals.statusId],
                    statusName = it[StatusIntervals.statusName],
                    category = StatusCategory.valueOf(it[StatusIntervals.statusCategory]),
                    fromAtMs = it[StatusIntervals.fromAt],
                    toAtMs = it[StatusIntervals.toAt],
                    source = IntervalSource.valueOf(it[StatusIntervals.intervalSource]),
                )
            }
            .mapValues { (_, intervals) -> intervals.sortedBy { it.seq } }
    }

    /**
     * Every issue's summed `time_spent_seconds` for a connection (v0.3.0 M3 review round 2b) —
     * `MetricsDeriver.kt`'s own memory-light worklog read: only the ONE number `ownWorklogSeconds`/
     * `actualMdFor` ever sum, never the full [DerivationWorklogRow] shape (author/timestamps),
     * which this commit's algorithm does not read at all.
     */
    suspend fun worklogSecondsByIssue(connectionId: UInt): Map<Long, Long> = suspendTransaction(database) {
        Worklogs.select(Worklogs.issueId, Worklogs.timeSpentSeconds).where { Worklogs.connectionId eq connectionId }
            .toList()
            .groupBy({ it[Worklogs.issueId] }) { it[Worklogs.timeSpentSeconds] }
            .mapValues { (_, seconds) -> seconds.sum() }
    }

    suspend fun workItemRow(connectionId: UInt, issueId: Long): org.jetbrains.exposed.v1.core.ResultRow? = suspendTransaction(database) {
        WorkItems.selectAll().where { (WorkItems.connectionId eq connectionId) and (WorkItems.issueId eq issueId) }.toList().singleOrNull()
    }

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

    suspend fun workItemView(connectionId: UInt, issueId: Long): WorkItemView? = suspendTransaction(database) {
        WorkItems.selectAll().where { (WorkItems.connectionId eq connectionId) and (WorkItems.issueId eq issueId) }
            .toList().singleOrNull()?.let { row ->
                WorkItemView(
                    issueKey = row[WorkItems.issueKey],
                    projectKey = row[WorkItems.projectKey],
                    issueType = row[WorkItems.issueType],
                    statusId = row[WorkItems.statusId],
                    statusName = row[WorkItems.statusName],
                    statusCategory = StatusCategory.valueOf(row[WorkItems.statusCategory]),
                    assigneeAccountId = row[WorkItems.assigneeAccountId],
                    hierarchyLevel = row[WorkItems.hierarchyLevel],
                    dueAt = row[WorkItems.dueAt],
                    customFields = Json.parseToJsonElement(row[WorkItems.customFields]).jsonObject,
                    anomalies = parseAnomalies(row[WorkItems.anomalies]),
                    processedAt = row[WorkItems.processedAt],
                    processingVersion = row[WorkItems.processingVersion],
                    deletedAt = row[WorkItems.deletedAt],
                    movedOutAt = row[WorkItems.movedOutAt],
                )
            }
    }

    /** One issue's tiled status intervals, ordered by `seq` (v0.2.0 plan §9/§12 item 8b — the raw issue inspector). */
    suspend fun statusIntervalsForIssue(connectionId: UInt, issueId: Long): List<NormalizedStatusInterval> = suspendTransaction(database) {
        StatusIntervals.selectAll().where { (StatusIntervals.connectionId eq connectionId) and (StatusIntervals.issueId eq issueId) }
            .toList().sortedBy { it[StatusIntervals.seq] }
            .map {
                NormalizedStatusInterval(
                    seq = it[StatusIntervals.seq],
                    statusId = it[StatusIntervals.statusId],
                    statusName = it[StatusIntervals.statusName],
                    category = StatusCategory.valueOf(it[StatusIntervals.statusCategory]),
                    fromAtMs = it[StatusIntervals.fromAt],
                    toAtMs = it[StatusIntervals.toAt],
                    source = IntervalSource.valueOf(it[StatusIntervals.intervalSource]),
                )
            }
    }

    /** One issue's tiled field intervals (ASSIGNEE/SPRINT/FLAGGED), ordered — the raw issue inspector (v0.2.0 plan §9/§12 item 8b). */
    suspend fun fieldIntervalsForIssue(connectionId: UInt, issueId: Long): List<NormalizedFieldInterval> = suspendTransaction(database) {
        FieldIntervals.selectAll().where { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.issueId eq issueId) }
            .toList().sortedWith(compareBy({ it[FieldIntervals.field] }, { it[FieldIntervals.seq] }))
            .map {
                NormalizedFieldInterval(
                    field = TrackedField.valueOf(it[FieldIntervals.field]),
                    seq = it[FieldIntervals.seq],
                    valueId = it[FieldIntervals.valueId],
                    valueText = it[FieldIntervals.valueText],
                    fromAtMs = it[FieldIntervals.fromAt],
                    toAtMs = it[FieldIntervals.toAt],
                )
            }
    }

    /** One batch of a connection's `norm.work_items` rows (the PURGE step, plan §0 A2). */
    suspend fun purgeWorkItemsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = WorkItems.select(WorkItems.issueId).where { WorkItems.connectionId eq connectionId }.limit(batchSize)
            .map { it[WorkItems.issueId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        WorkItems.deleteWhere { (WorkItems.connectionId eq connectionId) and (WorkItems.issueId inList ids) }
    }

    suspend fun purgeStatusIntervalsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = StatusIntervals.select(StatusIntervals.id).where { StatusIntervals.connectionId eq connectionId }.limit(batchSize)
            .map { it[StatusIntervals.id] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        StatusIntervals.deleteWhere { (StatusIntervals.connectionId eq connectionId) and (StatusIntervals.id inList ids) }
    }

    suspend fun purgeFieldIntervalsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = FieldIntervals.select(FieldIntervals.id).where { FieldIntervals.connectionId eq connectionId }.limit(batchSize)
            .map { it[FieldIntervals.id] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        FieldIntervals.deleteWhere { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.id inList ids) }
    }

    suspend fun purgeFieldChangesBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = FieldChanges.select(FieldChanges.id).where { FieldChanges.connectionId eq connectionId }.limit(batchSize)
            .map { it[FieldChanges.id] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        FieldChanges.deleteWhere { (FieldChanges.connectionId eq connectionId) and (FieldChanges.id inList ids) }
    }

    suspend fun purgeWorklogsBatch(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE): Int = suspendTransaction(database) {
        val ids = Worklogs.select(Worklogs.worklogId).where { Worklogs.connectionId eq connectionId }.limit(batchSize)
            .map { it[Worklogs.worklogId] }.toList()
        if (ids.isEmpty()) return@suspendTransaction 0
        Worklogs.deleteWhere { (Worklogs.connectionId eq connectionId) and (Worklogs.worklogId inList ids) }
    }

    /** The small reference tables are rebuilt wholesale already — PURGE just clears them outright, no batching needed. */
    suspend fun purgeReferenceRows(connectionId: UInt) = suspendTransaction(database) {
        Statuses.deleteWhere { Statuses.connectionId eq connectionId }
        People.deleteWhere { People.connectionId eq connectionId }
        BoardColumns.deleteWhere { BoardColumns.connectionId eq connectionId }
        Boards.deleteWhere { Boards.connectionId eq connectionId }
        Sprints.deleteWhere { Sprints.connectionId eq connectionId }
    }
}

/** Drains a connection's `norm.*` rows in batches — the PURGE step (plan §0 A2), mirroring `jira/JiraRawStore.kt`'s `purgeAll`. */
suspend fun WorkItemStore.purgeAll(connectionId: UInt, batchSize: Int = NORM_PURGE_BATCH_SIZE) {
    while (purgeFieldChangesBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeFieldIntervalsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeStatusIntervalsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeWorklogsBatch(connectionId, batchSize) > 0) { /* drain */ }
    while (purgeWorkItemsBatch(connectionId, batchSize) > 0) { /* drain */ }
    purgeReferenceRows(connectionId)
}
