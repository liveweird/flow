package ch.nokillswit.reports

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.math.abs

/*
 * The CONFIGURATION and DERIVE-level findings of the data-quality report (report 14, `.claude/docs/measures.md` "Report 14"):
 * gaps in the mapping (a status with no stage, a board with no team, a domain with no owner), the sprint-snapshot drift and
 * the DERIVE warnings. They are properties of a connection, not of a person — the team filter does not narrow them (the
 * `domain` filter narrows the domains). Counts are SQL aggregates; every function runs inside the caller's `suspendTransaction`.
 */

private const val STAGE_UNMAPPED = "UNMAPPED"

/**
 * What the report needs of a connection's EFFECTIVE metrics configuration (`MetricsConfigService.effectiveConfig`, the one DERIVE
 * reads): the status ids that have a stage, the boards mapped to a team and whether a work-category field is configured.
 */
internal class ConnectionMappings(val stageStatusIds: Set<String>, val boardIds: Set<Long>, val workCategoryConfigured: Boolean)

/**
 * A light reader of exactly those three facts, so a request does not pay `effectiveConfig`'s defaults (DISTINCT scans of the work
 * items). Same rule as `effectiveConfig`: a connection with ANY stored configuration row (in the eight config tables) uses its
 * stored stage map, board map and work-category field; one with none uses the computed defaults — every status whose Jira
 * category has a default stage, no boards, no work-category field.
 */
internal suspend fun readConnectionMappings(connectionIds: List<UInt>): Map<UInt, ConnectionMappings> {
    if (connectionIds.isEmpty()) return emptyMap()
    val stages = MetricsConfigService.StatusStageMap.select(
        MetricsConfigService.StatusStageMap.connectionId, MetricsConfigService.StatusStageMap.statusId,
    ).where {
        (MetricsConfigService.StatusStageMap.connectionId inList connectionIds) and
            (MetricsConfigService.StatusStageMap.domainKey eq "")
    }.toList().groupBy({ it[MetricsConfigService.StatusStageMap.connectionId].value }) { it[MetricsConfigService.StatusStageMap.statusId] }
    val boards = MetricsConfigService.BoardTeamMap.select(
        MetricsConfigService.BoardTeamMap.connectionId, MetricsConfigService.BoardTeamMap.boardId,
    ).where { MetricsConfigService.BoardTeamMap.connectionId inList connectionIds }
        .toList().groupBy({ it[MetricsConfigService.BoardTeamMap.connectionId].value }) { it[MetricsConfigService.BoardTeamMap.boardId] }
    val fields = MetricsConfigService.FieldConfig.select(
        MetricsConfigService.FieldConfig.connectionId, MetricsConfigService.FieldConfig.role,
    ).where { MetricsConfigService.FieldConfig.connectionId inList connectionIds }
        .toList().groupBy({ it[MetricsConfigService.FieldConfig.connectionId].value }) { it[MetricsConfigService.FieldConfig.role] }
    val stored = stages.keys + boards.keys + fields.keys + storedElsewhere(connectionIds)
    val statuses = WorkItemStore.Statuses.select(
        WorkItemStore.Statuses.connectionId, WorkItemStore.Statuses.statusId, WorkItemStore.Statuses.category,
    ).where { WorkItemStore.Statuses.connectionId inList connectionIds }.toList().groupBy { it[WorkItemStore.Statuses.connectionId].value }
    return connectionIds.associateWith { id ->
        if (id in stored) {
            ConnectionMappings(
                stageStatusIds = stages[id].orEmpty().toSet(), boardIds = boards[id].orEmpty().toSet(),
                workCategoryConfigured = WORK_CATEGORY_ROLE in fields[id].orEmpty(),
            )
        } else {
            val defaults = statuses[id].orEmpty().filter { StatusCategory.valueOf(it[WorkItemStore.Statuses.category]).hasDefaultStage() }
            ConnectionMappings(defaults.map { it[WorkItemStore.Statuses.statusId] }.toSet(), emptySet(), workCategoryConfigured = false)
        }
    }
}

private const val WORK_CATEGORY_ROLE = "WORK_CATEGORY"

/** A Jira status category that maps to a stage by default (`MetricsConfigService.defaultConfig`'s `toDefaultStage`). */
private fun StatusCategory.hasDefaultStage() = this != StatusCategory.UNKNOWN

/** The connections that store a row in one of the five config tables [readConnectionMappings] does not otherwise read. */
private suspend fun storedElsewhere(connectionIds: List<UInt>): Set<UInt> = listOf(
    MetricsConfigService.DomainMap to MetricsConfigService.DomainMap.connectionId,
    MetricsConfigService.ActivityTypeMap to MetricsConfigService.ActivityTypeMap.connectionId,
    MetricsConfigService.WorkCategoryMap to MetricsConfigService.WorkCategoryMap.connectionId,
    MetricsConfigService.BlockedStatuses to MetricsConfigService.BlockedStatuses.connectionId,
    MetricsConfigService.TeamSprintCapacity to MetricsConfigService.TeamSprintCapacity.connectionId,
).flatMap { (table, column) ->
    table.select(column).where { column inList connectionIds }.withDistinct().toList().map { it[column].value }
}.toSet()

/** A domain no team owns (A19, A22): its epics and backlog land in `UNOWNED`. [epics] counts the domain's epics. */
@Serializable
data class UnownedDomain(
    val connectionId: UInt,
    val domainKey: String,
    val name: String,
    val projectKeys: List<String>,
    val epics: Int,
)

/**
 * A Jira status with no stage mapping — DERIVE tiles its time as UNMAPPED, never started, never done. [items] counts the
 * work items (sub-tasks included) that have ever sat in it, [openItems] those sitting in it now.
 */
@Serializable
data class UnmappedStatus(
    val connectionId: UInt,
    val statusId: String,
    val name: String,
    val category: String?,
    val items: Int,
    val openItems: Int,
)

/**
 * A Jira board no team is mapped to: its [sprints] have no team and no snapshot, and the [doneTasks] level-0 tasks of the
 * period that were done inside one of them fall back to the assignee's team (D5).
 */
@Serializable
data class UnmappedBoard(
    val connectionId: UInt,
    val boardId: Long,
    val name: String,
    val projectKey: String?,
    val sprints: Int,
    val doneTasks: Int,
)

/**
 * The unmapped boards, capped like every list, plus the residual [unattributedDoneTasks]: tasks of the period done inside a sprint
 * with no team that belongs to none of the listed boards (the sprint has no board, or its board IS mapped yet the sprint carries
 * no team), so no task done in a teamless sprint is counted nowhere.
 */
@Serializable
data class UnmappedBoardList(val total: Int, val items: List<UnmappedBoard>, val unattributedDoneTasks: Int)

/** A worklog author who belonged to no team when logging — a null [accountId] is a worklog with no known author. */
@Serializable
data class AuthorWithoutTeam(val accountId: String?, val name: String?, val worklogs: Int, val md: Double)

/**
 * One figure of a closed, team-mapped sprint whose live value has moved away from its frozen snapshot (D13). [reconstructed]
 * marks a snapshot written by a DERIVE that ran after the sprint closed — a baseline rebuilt from history, not a real freeze.
 */
@Serializable
data class SnapshotDrift(
    val connectionId: UInt,
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val completeAt: Long?,
    val field: String,
    val live: Double?,
    val frozen: Double?,
    val delta: Double?,
    val reconstructed: Boolean,
)

/** A connection whose latest SUCCEEDED DERIVE run reported [warnings] (A13: `sprintFieldUnresolved` — the sprint step was skipped). */
@Serializable
data class DeriveWarning(
    val connectionId: UInt,
    val connectionName: String,
    val runId: Int,
    val startedAt: Long,
    val warnings: List<String>,
)

private const val WARNING_SPRINT_FIELD_UNRESOLVED = "sprintFieldUnresolved"

/**
 * Domains with no owner team (`dim_domain.owner_team_id IS NULL`). The owner is a team, so a real `teamId` sees none;
 * UNIT and `teamId=0` (UNOWNED) list them, narrowed by `domain`. [UnownedDomain.epics] counts every epic of the domain (SQL).
 */
internal suspend fun fetchUnownedDomains(filter: ReportFilter, connectionIds: List<UInt>): List<UnownedDomain> {
    val teamScoped = filter.teamId != null && filter.teamId != UNASSIGNED_TEAM_ID
    if (connectionIds.isEmpty() || teamScoped) return emptyList()
    val d = MetricsTables.DimDomain
    val domains = d.select(d.connectionId, d.domainKey, d.name, d.projectKeys)
        .where {
            var predicate = (d.connectionId inList connectionIds) and d.ownerTeamId.isNull()
            filter.domain?.let { predicate = predicate and (d.domainKey eq it) }
            predicate
        }.toList()
    if (domains.isEmpty()) return emptyList()
    val e = MetricsTables.DimEpic
    val size = Count(e.issueId)
    val domainConnections = domains.map { it[d.connectionId].value }.distinct()
    val epics = e.select(e.connectionId, e.domainKey, size).where { e.connectionId inList domainConnections }
        .groupBy(e.connectionId, e.domainKey).toList().associate { (it[e.connectionId].value to it[e.domainKey]) to it[size].toInt() }
    return domains.map {
        val connectionId = it[d.connectionId].value
        UnownedDomain(
            connectionId = connectionId, domainKey = it[d.domainKey], name = it[d.name],
            projectKeys = Json.decodeFromString<List<String>>(it[d.projectKeys]),
            epics = epics[connectionId to it[d.domainKey]] ?: 0,
        )
    }.sortedWith(compareBy({ it.connectionId }, { it.domainKey }))
}

/**
 * Statuses with no stage in the connection's effective configuration (`norm.statuses` − the stage map DERIVE used), plus any
 * status DERIVE actually tiled as UNMAPPED (`item_stage`), with the items that sat in each — both counts are SQL
 * `count(DISTINCT issue_id)`.
 */
internal suspend fun fetchUnmappedStatuses(connectionIds: List<UInt>, mappings: Map<UInt, ConnectionMappings>): List<UnmappedStatus> {
    if (connectionIds.isEmpty()) return emptyList()
    val s = WorkItemStore.Statuses
    val statuses = s.select(s.connectionId, s.statusId, s.name, s.category).where { s.connectionId inList connectionIds }.toList()
        .associateBy { it[s.connectionId].value to it[s.statusId] }
    val i = MetricsTables.ItemStage
    val ever = Count(i.issueId, distinct = true)
    val tiled = i.select(i.connectionId, i.statusId, ever)
        .where { (i.connectionId inList connectionIds) and (i.stage eq STAGE_UNMAPPED) }.groupBy(i.connectionId, i.statusId).toList()
        .associate { (it[i.connectionId].value to it[i.statusId]) to it[ever].toInt() }
    val open = i.select(i.connectionId, i.statusId, ever)
        .where { (i.connectionId inList connectionIds) and (i.stage eq STAGE_UNMAPPED) and i.validTo.isNull() }
        .groupBy(i.connectionId, i.statusId).toList().associate { (it[i.connectionId].value to it[i.statusId]) to it[ever].toInt() }
    val declared = statuses.keys.filter { (connectionId, statusId) -> statusId !in mappings[connectionId]?.stageStatusIds.orEmpty() }
    return (declared + tiled.keys).toSet().sortedWith(compareBy({ it.first }, { it.second })).map { key ->
        val known = statuses[key]
        UnmappedStatus(
            connectionId = key.first, statusId = key.second, name = known?.get(s.name) ?: key.second,
            category = known?.get(s.category), items = tiled[key] ?: 0, openItems = open[key] ?: 0,
        )
    }
}

/**
 * Boards with no team in the effective board map (`norm.boards` − the board→team map) with their sprint count and the DONE tasks
 * of the period done inside one of their sprints ([doneInTeamlessSprints]: `(connection, sprint id)` → tasks), plus the residual
 * of such tasks that belong to no listed board.
 */
internal suspend fun fetchUnmappedBoards(
    connectionIds: List<UInt>,
    mappings: Map<UInt, ConnectionMappings>,
    doneInTeamlessSprints: Map<Pair<UInt, Long>, Int>,
): UnmappedBoardList {
    val residual = doneInTeamlessSprints.values.sum()
    if (connectionIds.isEmpty()) return UnmappedBoardList(0, emptyList(), residual)
    val b = WorkItemStore.Boards
    val boards = b.selectAll().where { b.connectionId inList connectionIds }.toList()
        .filter { it[b.boardId] !in mappings[it[b.connectionId].value]?.boardIds.orEmpty() }
    if (boards.isEmpty()) return UnmappedBoardList(0, emptyList(), residual)
    val sp = WorkItemStore.Sprints
    val sprintsByBoard = sp.select(sp.connectionId, sp.sprintId, sp.boardId)
        .where { (sp.connectionId inList connectionIds) and sp.boardId.isNotNull() }.toList()
        .groupBy({ it[sp.connectionId].value to it[sp.boardId]!! }) { it[sp.sprintId] }
    val items = boards.map {
        val connectionId = it[b.connectionId].value
        val sprintIds = sprintsByBoard[connectionId to it[b.boardId]].orEmpty()
        UnmappedBoard(
            connectionId = connectionId, boardId = it[b.boardId], name = it[b.name], projectKey = it[b.projectKey],
            sprints = sprintIds.size, doneTasks = sprintIds.sumOf { sprintId -> doneInTeamlessSprints[connectionId to sprintId] ?: 0 },
        )
    }.sortedWith(compareBy({ it.connectionId }, { it.boardId }))
    return UnmappedBoardList(items.size, items.take(DATA_QUALITY_MAX_ITEMS), residual - items.sumOf { it.doneTasks })
}

/** One compared figure: the live `fact_sprint` column, its frozen twin and how far apart they may be before it counts as drift. */
private class SprintFigure(val name: String, val tolerance: Double, val live: Column<out Number?>, val frozen: Column<out Number?>)

private const val MD_TOLERANCE = SPRINT_DRIFT_TOLERANCE_MD
private const val ITEMS_TOLERANCE = 0.0
private const val LOAD_TOLERANCE = 0.0005

private val LIVE = MetricsTables.FactSprint
private val FROZEN = MetricsTables.FactSprintSnapshot

private val SPRINT_FIGURES: List<SprintFigure> = listOf(
    SprintFigure("committedMd", MD_TOLERANCE, LIVE.committedMd, FROZEN.committedMd),
    SprintFigure("committedItems", ITEMS_TOLERANCE, LIVE.committedItems, FROZEN.committedItems),
    SprintFigure("addedMd", MD_TOLERANCE, LIVE.addedMd, FROZEN.addedMd),
    SprintFigure("addedItems", ITEMS_TOLERANCE, LIVE.addedItems, FROZEN.addedItems),
    SprintFigure("removedMd", MD_TOLERANCE, LIVE.removedMd, FROZEN.removedMd),
    SprintFigure("removedItems", ITEMS_TOLERANCE, LIVE.removedItems, FROZEN.removedItems),
    SprintFigure("finalMd", MD_TOLERANCE, LIVE.finalMd, FROZEN.finalMd),
    SprintFigure("finalItems", ITEMS_TOLERANCE, LIVE.finalItems, FROZEN.finalItems),
    SprintFigure("deliveredMd", MD_TOLERANCE, LIVE.deliveredMd, FROZEN.deliveredMd),
    SprintFigure("deliveredItems", ITEMS_TOLERANCE, LIVE.deliveredItems, FROZEN.deliveredItems),
    SprintFigure("carriedOverMd", MD_TOLERANCE, LIVE.carriedOverMd, FROZEN.carriedOverMd),
    SprintFigure("carriedOverItems", ITEMS_TOLERANCE, LIVE.carriedOverItems, FROZEN.carriedOverItems),
    SprintFigure("droppedMd", MD_TOLERANCE, LIVE.droppedMd, FROZEN.droppedMd),
    SprintFigure("droppedItems", ITEMS_TOLERANCE, LIVE.droppedItems, FROZEN.droppedItems),
    SprintFigure("capacityMd", MD_TOLERANCE, LIVE.capacityMd, FROZEN.capacityMd),
    SprintFigure("load", LOAD_TOLERANCE, LIVE.load, FROZEN.load),
)

/**
 * Every figure of the closed, team-mapped [sprints] whose live `fact_sprint` value differs from `fact_sprint_snapshot` (D13)
 * beyond the figure's tolerance (0.005 MD, items exact, load 0.0005; a figure null on one side only is drift). A sprint with
 * no snapshot has nothing to drift from. Sprints are few (a team closes ~26 a year), so both tables are read for the period's
 * sprint ids only.
 */
internal suspend fun fetchSnapshotDrift(sprints: List<SprintRow>): List<SnapshotDrift> {
    val closed = sprints.filter { it.completedAt != null }
    if (closed.isEmpty()) return emptyList()
    val connectionIds = closed.map { it.connectionId }.distinct()
    val sprintIds = closed.map { it.sprintId }.distinct()
    val live = MetricsTables.FactSprint
    val frozen = MetricsTables.FactSprintSnapshot
    val liveRows = live.selectAll().where { (live.connectionId inList connectionIds) and (live.sprintId inList sprintIds) }.toList()
        .associateBy { it[live.connectionId].value to it[live.sprintId] }
    val frozenRows = frozen.selectAll().where { (frozen.connectionId inList connectionIds) and (frozen.sprintId inList sprintIds) }
        .toList().associateBy { it[frozen.connectionId].value to it[frozen.sprintId] }
    return closed.sortedWith(compareBy({ it.completedAt }, { it.connectionId }, { it.sprintId })).flatMap { sprint ->
        val key = sprint.connectionId to sprint.sprintId
        val liveRow = liveRows[key]
        val frozenRow = frozenRows[key]
        if (liveRow == null || frozenRow == null) {
            emptyList()
        } else {
            SPRINT_FIGURES.mapNotNull { figure -> driftOf(sprint, figure, liveRow, frozenRow) }
        }
    }
}

private fun driftOf(sprint: SprintRow, figure: SprintFigure, liveRow: ResultRow, frozenRow: ResultRow): SnapshotDrift? {
    val live = liveRow[figure.live]?.toDouble()
    val frozen = frozenRow[figure.frozen]?.toDouble()
    val drifted = if (live == null || frozen == null) live != frozen else abs(live - frozen) > figure.tolerance
    if (!drifted) return null
    return SnapshotDrift(
        connectionId = sprint.connectionId, sprintId = sprint.sprintId, name = sprint.name, teamId = sprint.teamId,
        completeAt = sprint.completedAt, field = figure.name, live = live, frozen = frozen,
        delta = if (live != null && frozen != null) live - frozen else null,
        reconstructed = frozenRow[MetricsTables.FactSprintSnapshot.reconstructed],
    )
}

/**
 * The connections whose newest SUCCEEDED DERIVE run carries `row_counts.sprintFieldUnresolved` — the connection has sprints but no
 * detected Sprint field, so its sprint step was skipped (A13). [deriveClocks] is each connection's newest successful run start
 * (`deriveClocks`, one SQL `max()`), so only those runs' rows are read — never the run history or its `row_counts`. A connection
 * without a successful run has nothing to report ("not derived yet" is `meta.derivedAt`'s job).
 */
internal suspend fun fetchDeriveWarnings(deriveClocks: Map<UInt, Long>): List<DeriveWarning> {
    if (deriveClocks.isEmpty()) return emptyList()
    val r = MetricsTables.DeriveRuns
    val newest = deriveClocks.map { (connectionId, startedAt) -> (r.connectionId eq connectionId.toInt()) and (r.startedAt eq startedAt) }
        .reduce { a, b -> a or b }
    val flagged = r.select(r.id, r.connectionId, r.startedAt, r.rowCounts).where { (r.status eq DERIVE_RUN_SUCCEEDED) and newest }
        .toList().groupBy { it[r.connectionId] }.mapValues { (_, runs) -> runs.maxBy { it[r.id] } }
        .values.filter { sprintFieldUnresolved(it[r.rowCounts]) }
    if (flagged.isEmpty()) return emptyList()
    val names = DataSourceService.Connections.select(DataSourceService.Connections.id, DataSourceService.Connections.name)
        .where { DataSourceService.Connections.id inList flagged.map { it[r.connectionId].toUInt() } }
        .toList().associate { it[DataSourceService.Connections.id].value to it[DataSourceService.Connections.name] }
    return flagged.map {
        val connectionId = it[r.connectionId].toUInt()
        DeriveWarning(
            connectionId = connectionId, connectionName = names[connectionId] ?: connectionId.toString(), runId = it[r.id],
            startedAt = it[r.startedAt], warnings = listOf(WARNING_SPRINT_FIELD_UNRESOLVED),
        )
    }.sortedBy { it.connectionId }
}

private fun sprintFieldUnresolved(rowCounts: String?): Boolean =
    rowCounts != null &&
        (Json.parseToJsonElement(rowCounts) as? JsonObject)?.get(WARNING_SPRINT_FIELD_UNRESOLVED)?.jsonPrimitive?.booleanOrNull == true
