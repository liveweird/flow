package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.WorkItemStore
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** What a WIP report's `counts` are keyed by (`by`): the Jira status, the configured stage, or the mapped board's column. */
@Serializable
enum class WipBy { STATUS, STAGE, COLUMN }

/** Which items a WIP report counts (`itemKind`): level-0 tasks, epics, or both added together. */
@Serializable
enum class WipItemKind { TASK, EPIC, BOTH }

/** One legend entry of [WipReport.keys]: a [key] used in every point's `counts`, and its display [label]. */
@Serializable
data class WipKey(val key: String, val label: String)

/** One calendar day of the series: [counts] carries EVERY key of [WipReport.keys] (zero-filled). */
@Serializable
data class WipPoint(val day: String, val isWorkingDay: Boolean, val counts: Map<String, Int>)

@Serializable
data class WipReport(
    val meta: ReportMeta,
    val by: WipBy,
    val itemKind: WipItemKind,
    /** The legend: every key a point's `counts` uses, in display order. */
    val keys: List<WipKey>,
    /** One point per calendar day of the period (cut off after the last derived day), oldest first. */
    val series: List<WipPoint>,
    /**
     * Why the series is empty or partial (USER level, not derived yet, no sprint resolved, connections left out of the
     * cut-off); `null` otherwise.
     */
    val note: String?,
)

/** The stages `item_stage`/`agg_daily_wip` carry, in flow order — also the fixed key set of `by=STAGE`. */
private val WIP_STAGES = listOf("NOT_STARTED", "IN_PROGRESS", "DONE", "UNMAPPED")

/** The `by=COLUMN` key of a status no column of the board holds. */
private const val NO_COLUMN_KEY = "(no column)"

private const val USER_LEVEL_NOTE = "WIP is not stored per user: the daily aggregate has only team and domain scopes"

/** One summed `agg_daily_wip` cell: how many items sat in [statusId] (stage [stage]) at the end of [day]. */
private data class WipCell(val day: String, val statusId: String, val stage: String, val count: Int)

/** A team's board resolved for `by=COLUMN`: the connections it lives on and its columns' status membership. */
private class ColumnMapping(val connectionIds: List<UInt>, val columns: List<String>, val columnOfStatus: Map<String, String>)

private fun WipItemKind.storedKinds(): List<String> = when (this) {
    WipItemKind.TASK -> listOf(WIP_KIND_TASK)
    WipItemKind.EPIC -> listOf(WIP_KIND_EPIC)
    WipItemKind.BOTH -> listOf(WIP_KIND_TASK, WIP_KIND_EPIC)
}

/**
 * `GET /api/v1/reports/wip` (v0.3.0 M5 commit 15, Report 9, `.claude/docs/measures.md` "Report 9 — WIP"): items in
 * parallel per status / stage / board column over time, straight off `metrics.agg_daily_wip` (each day's END-of-day
 * snapshot, one row per non-zero cell — a missing cell is zero). See `.claude/docs/reports.md` "Reports 9, 10, 13".
 */
suspend fun ReportService.wip(filter: ReportFilter, by: WipBy, itemKind: WipItemKind, nowMs: Long): WipReport =
    suspendTransaction(database) {
        val scope = resolveReportScope(filter, nowMs)
        val snapshotScope = snapshotScopeOf(filter)
        val mapping = if (by == WipBy.COLUMN) columnMappingFor(snapshotScope, scope.connectionIds) else null
        val calendar = WorkingCalendar.of(scope.settings)
        val plan = planSnapshotDays(scope, filter, calendar, USER_LEVEL_NOTE)
        val days = plan.days
        val cells = if (days.isEmpty()) {
            emptyList()
        } else {
            val connectionIds = mapping?.connectionIds ?: scope.connectionIds
            fetchWipCells(connectionIds, snapshotScope, itemKind, days.first().toString(), days.last().toString())
        }
        val keys = wipKeys(by, cells, mapping, scope.connectionIds)
        val countsByDay = countsByDay(by, cells, mapping)
        val series = days.map { day ->
            val perKey = countsByDay[day.toString()].orEmpty()
            WipPoint(day.toString(), calendar.isWorkingDay(day), keys.associate { it.key to (perKey[it.key] ?: 0) })
        }
        WipReport(scope.meta.forTaskDomain(), by, itemKind, keys, series, plan.note)
    }

private suspend fun fetchWipCells(
    connectionIds: List<UInt>,
    scope: SnapshotScope,
    itemKind: WipItemKind,
    firstDay: String,
    lastDay: String,
): List<WipCell> {
    if (connectionIds.isEmpty()) return emptyList()
    val w = MetricsTables.AggDailyWip
    val total = w.itemCount.sum()
    return w.select(w.day, w.statusId, w.stage, total)
        .where {
            (w.connectionId inList connectionIds) and wipScopePredicate(scope) and (w.itemKind inList itemKind.storedKinds()) and
                (w.day greaterEq firstDay) and (w.day lessEq lastDay)
        }
        .groupBy(w.day, w.statusId, w.stage)
        .toList().map { WipCell(it[w.day], it[w.statusId], it[w.stage], it[total] ?: 0) }
}

/**
 * `by=COLUMN`: the team's mapped board's columns (`norm.board_columns`, read at query time — a board edit shows up
 * without a re-derive), `400` when there is no single team to read a board for or the team has no board in scope.
 * A team has at most ONE board (`uq_metrics_board_team_map_team_id`, D10).
 */
private suspend fun columnMappingFor(scope: SnapshotScope, connectionIds: List<UInt>): ColumnMapping {
    val teamId = (scope as? SnapshotScope.Team)?.teamId
        ?: throw BadRequestException("by=COLUMN needs a teamId naming a real team: a board column belongs to one team's board")
    val map = MetricsConfigService.BoardTeamMap
    val boards = map.selectAll().where { (map.teamId eq teamId) and (map.connectionId inList connectionIds) }.toList()
        .map { it[map.connectionId].value to it[map.boardId] }
    if (boards.isEmpty()) throw BadRequestException("Team $teamId has no board mapped in the connections in scope; by=COLUMN needs one")
    val columns = WorkItemStore.BoardColumns
    val rows = boards.flatMap { (connectionId, boardId) ->
        columns.selectAll().where { (columns.connectionId eq connectionId) and (columns.boardId eq boardId) }
            .orderBy(columns.seq to SortOrder.ASC).toList()
    }
    val columnOfStatus = LinkedHashMap<String, String>()
    for (row in rows) {
        val name = row[columns.name]
        Json.parseToJsonElement(row[columns.statusIds]).jsonArray.forEach { columnOfStatus.putIfAbsent(it.jsonPrimitive.content, name) }
    }
    return ColumnMapping(boards.map { it.first }.distinct(), rows.map { it[columns.name] }.distinct(), columnOfStatus)
}

private fun keyOf(by: WipBy, cell: WipCell, mapping: ColumnMapping?): String = when (by) {
    WipBy.STAGE -> cell.stage
    WipBy.STATUS -> cell.statusId
    WipBy.COLUMN -> mapping!!.columnOfStatus[cell.statusId] ?: NO_COLUMN_KEY
}

private fun countsByDay(by: WipBy, cells: List<WipCell>, mapping: ColumnMapping?): Map<String, Map<String, Int>> =
    cells.groupBy { it.day }.mapValues { (_, dayCells) ->
        dayCells.groupBy { keyOf(by, it, mapping) }.mapValues { (_, group) -> group.sumOf { it.count } }
    }

/** The legend: STAGE is the fixed four stages, COLUMN the board's columns (plus "(no column)" when used), STATUS every status seen. */
private suspend fun wipKeys(by: WipBy, cells: List<WipCell>, mapping: ColumnMapping?, connectionIds: List<UInt>): List<WipKey> = when (by) {
    WipBy.STAGE -> WIP_STAGES.map { WipKey(it, it) }
    WipBy.COLUMN -> {
        val columnKeys = mapping!!.columns.map { WipKey(it, it) }
        val usesNoColumn = cells.any { keyOf(by, it, mapping) == NO_COLUMN_KEY }
        if (usesNoColumn) columnKeys + WipKey(NO_COLUMN_KEY, NO_COLUMN_KEY) else columnKeys
    }
    WipBy.STATUS -> {
        val stageOfStatus = LinkedHashMap<String, String>()
        cells.forEach { stageOfStatus.putIfAbsent(it.statusId, it.stage) }
        val names = statusNames(stageOfStatus.keys, connectionIds)
        stageOfStatus.keys
            .sortedWith(
                compareBy<String> { WIP_STAGES.indexOf(stageOfStatus.getValue(it)) }
                    .thenBy { names[it] ?: it }
                    .thenBy { it },
            )
            .map { WipKey(it, names[it] ?: it) }
    }
}

/** `norm.statuses` names by status id — the lowest connection id's name wins when a status id repeats across connections. */
private suspend fun statusNames(statusIds: Collection<String>, connectionIds: List<UInt>): Map<String, String> {
    if (statusIds.isEmpty() || connectionIds.isEmpty()) return emptyMap()
    val s = WorkItemStore.Statuses
    return s.select(s.statusId, s.name)
        .where { (s.connectionId inList connectionIds) and (s.statusId inList statusIds) }
        .orderBy(s.connectionId to SortOrder.DESC)
        .toList().associate { it[s.statusId] to it[s.name] }
}
