package ch.nokillswit.norm

import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.ingest.DataSourceService
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/** Batch size for the PURGE step's cleanup over the bigger `norm.*` tables — mirrors `jira/JiraRawStore.kt`'s `JIRA_PURGE_BATCH_SIZE`. */
internal const val NORM_PURGE_BATCH_SIZE = 500

private fun stringArrayJson(values: List<String>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()
private fun longArrayJson(values: List<Long>): String = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()
private fun anomaliesJson(values: List<TilingAnomaly>): String =
    buildJsonArray { values.forEach { add(JsonPrimitive(it.name)) } }.toString()

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
)

/**
 * The `norm` schema's Exposed table set (v0.2.0 plan §0 A3/§4 V13) — the PROCESS step's write
 * target. Every per-issue write is a REPLACE, one transaction per issue (plan §8 step 5): delete
 * this issue's child rows, insert the freshly tiled ones, upsert `work_items`. Reference rows
 * (statuses/people/boards/board_columns/sprints) are rebuilt WHOLESALE per connection, once per
 * PROCESS run — never diffed row-by-row like `raw.jira_entities`.
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
        override val primaryKey = PrimaryKey(id)
    }

    object Worklogs : Table("norm.work_item_worklogs") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val worklogId = long("worklog_id")
        val issueId = long("issue_id")
        val authorAccountId = varchar("author_account_id", 100).nullable()
        val startedAt = long("started_at")
        val timeSpentSeconds = long("time_spent_seconds")
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
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    /**
     * The PROCESS step's per-issue REPLACE (plan §8 step 5) — delete this issue's child rows,
     * insert the freshly tiled ones, upsert `work_items`, ALL in one transaction (the caller,
     * `jira/JiraProcessStream.kt`, wraps this in the SAME `StreamContext.transaction { }` block as
     * the raw row's own `processed_at`/`needs_processing` update, so a crash mid-issue never leaves
     * a half-written normalized row).
     */
    suspend fun replaceWorkItem(connectionId: UInt, normalized: NormalizedIssue, now: Long, processingVersion: Int = PROCESSING_VERSION) {
        suspendTransaction(database) {
            val issueId = normalized.issueId
            StatusIntervals.deleteWhere { (StatusIntervals.connectionId eq connectionId) and (StatusIntervals.issueId eq issueId) }
            FieldIntervals.deleteWhere { (FieldIntervals.connectionId eq connectionId) and (FieldIntervals.issueId eq issueId) }
            FieldChanges.deleteWhere { (FieldChanges.connectionId eq connectionId) and (FieldChanges.issueId eq issueId) }
            Worklogs.deleteWhere { (Worklogs.connectionId eq connectionId) and (Worklogs.issueId eq issueId) }

            if (normalized.statusIntervals.isNotEmpty()) {
                StatusIntervals.batchInsert(normalized.statusIntervals) { interval ->
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
            if (normalized.fieldIntervals.isNotEmpty()) {
                FieldIntervals.batchInsert(normalized.fieldIntervals) { interval ->
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
            if (normalized.fieldChanges.isNotEmpty()) {
                FieldChanges.batchInsert(normalized.fieldChanges.withIndex().toList()) { (index, change) ->
                    this[FieldChanges.connectionId] = connectionId
                    this[FieldChanges.issueId] = issueId
                    this[FieldChanges.seq] = index + 1
                    this[FieldChanges.field] = change.field
                    this[FieldChanges.changedAt] = change.atMs
                    this[FieldChanges.fromValue] = change.fromValue
                    this[FieldChanges.fromText] = change.fromText
                    this[FieldChanges.toValue] = change.toValue
                    this[FieldChanges.toText] = change.toText
                }
            }
            if (normalized.worklogs.isNotEmpty()) {
                Worklogs.batchInsert(normalized.worklogs) { worklog ->
                    this[Worklogs.connectionId] = connectionId
                    this[Worklogs.worklogId] = worklog.worklogId
                    this[Worklogs.issueId] = issueId
                    this[Worklogs.authorAccountId] = worklog.authorAccountId
                    this[Worklogs.startedAt] = worklog.startedAtMs
                    this[Worklogs.timeSpentSeconds] = worklog.timeSpentSeconds
                }
            }

            val facts = normalized.facts
            val existing = WorkItems.selectAll()
                .where { (WorkItems.connectionId eq connectionId) and (WorkItems.issueId eq issueId) }
                .toList().singleOrNull()
            fun apply(builder: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>) {
                builder[WorkItems.connectionId] = connectionId
                builder[WorkItems.issueId] = issueId
                builder[WorkItems.issueKey] = facts.issueKey
                builder[WorkItems.projectKey] = facts.projectKey
                builder[WorkItems.issueType] = facts.issueType
                builder[WorkItems.isSubtask] = facts.isSubtask
                builder[WorkItems.parentIssueId] = facts.parentIssueId
                builder[WorkItems.summary] = facts.summary
                builder[WorkItems.statusId] = facts.currentStatusId
                builder[WorkItems.statusName] = normalized.currentStatusName
                builder[WorkItems.statusCategory] = normalized.currentStatusCategory.name
                builder[WorkItems.resolution] = facts.resolution
                builder[WorkItems.priority] = facts.priority
                builder[WorkItems.assigneeAccountId] = facts.assigneeAccountId
                builder[WorkItems.reporterAccountId] = facts.reporterAccountId
                builder[WorkItems.createdAt] = facts.createdAtMs
                builder[WorkItems.updatedAt] = facts.updatedAtMs
                builder[WorkItems.resolvedAt] = facts.resolvedAtMs
                builder[WorkItems.storyPoints] = facts.storyPoints
                builder[WorkItems.originalEstimateSeconds] = facts.originalEstimateSeconds
                builder[WorkItems.timeSpentSeconds] = facts.timeSpentSeconds
                builder[WorkItems.labels] = stringArrayJson(facts.labels)
                builder[WorkItems.components] = stringArrayJson(facts.components)
                builder[WorkItems.fixVersions] = stringArrayJson(facts.fixVersions)
                builder[WorkItems.currentSprintIds] = longArrayJson(normalized.currentSprintIds)
                builder[WorkItems.teamValue] = facts.teamValueJson
                builder[WorkItems.flagged] = normalized.flagged
                builder[WorkItems.rank] = facts.rank
                builder[WorkItems.anomalies] = anomaliesJson(normalized.anomalies)
                builder[WorkItems.deletedAt] = if (facts.tombstone == TombstoneKind.DELETED) now else null
                builder[WorkItems.movedOutAt] = if (facts.tombstone == TombstoneKind.MOVED_OUT) now else null
                builder[WorkItems.processedAt] = now
                builder[WorkItems.processingVersion] = processingVersion
            }
            if (existing == null) {
                WorkItems.insert { apply(it) }
            } else {
                WorkItems.update({ (WorkItems.connectionId eq connectionId) and (WorkItems.issueId eq issueId) }) { apply(it) }
            }
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

    /** Every work item's tiled status intervals, ordered — the pipeline test's SQL-invariant-sweep/reopen-count source. */
    suspend fun statusIntervalsByIssue(connectionId: UInt): Map<Long, List<NormalizedStatusInterval>> = suspendTransaction(database) {
        StatusIntervals.selectAll().where { StatusIntervals.connectionId eq connectionId }
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

    suspend fun workItemRow(connectionId: UInt, issueId: Long): org.jetbrains.exposed.v1.core.ResultRow? = suspendTransaction(database) {
        WorkItems.selectAll().where { (WorkItems.connectionId eq connectionId) and (WorkItems.issueId eq issueId) }.toList().singleOrNull()
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
