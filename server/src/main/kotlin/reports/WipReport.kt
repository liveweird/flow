package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsTables
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
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** What a WIP report's `counts` are keyed by (`by`): the Jira status, the configured stage, or the mapped board's column. */
@Serializable
enum class WipBy { STATUS, STAGE, COLUMN }

/** Which items a WIP report counts (`itemKind`): level-0 tasks or epics — never both summed (different grains, D2). */
@Serializable
enum class WipItemKind { TASK, EPIC }

/** One legend entry of [WipReport.keys]: a [key] used in every point's `counts`, and its display [label]. */
@Serializable
data class WipKey(val key: String, val label: String)

/**
 * One calendar day of the series: [counts] carries EVERY key of [WipReport.keys] (zero-filled). At USER level [byUser] splits
 * [counts] per picked person, one entry per pick in request order, every key zero-filled (a pick with no task is all zeros); it is
 * empty below USER level and then OMITTED on the wire (`encodeDefaults = false`), like `Distribution.byUser`.
 */
@Serializable
data class WipPoint(val day: String, val isWorkingDay: Boolean, val counts: Map<String, Int>, val byUser: List<WipUserShare> = emptyList())

/** One picked person's own [counts] on one day of a USER-level WIP series (the tasks they hold, by the report's `by` key). */
@Serializable
data class WipUserShare(val accountId: String, val label: String, val counts: Map<String, Int>)

@Serializable
data class WipReport(
    val meta: ReportMeta,
    val by: WipBy,
    val itemKind: WipItemKind,
    /** The legend: every key a point's `counts` uses, in display order. */
    val keys: List<WipKey>,
    /**
     * One point per calendar day of the period, oldest first — cut off after the last derived day and starting no earlier
     * than the latest `backfill_from` of the connections in scope (decision C).
     */
    val series: List<WipPoint>,
    /**
     * Why the series is empty or partial (not derived yet, no sprint resolved, connections left out of the cut-off, a period
     * that begins before the backfill window, USER level leaving the epics out); `null` otherwise.
     */
    val note: String?,
)

internal const val STAGE_DONE = "DONE"

/** The stages `item_stage` carries, in flow order — also the fixed key set of `by=STAGE`. */
internal val WIP_STAGES = listOf("NOT_STARTED", "IN_PROGRESS", "WAITING", "BLOCKED", "DONE", "UNMAPPED")

/** The `by=COLUMN` key of a status no column of the board holds. */
internal const val NO_COLUMN_KEY = "(no column)"

/** USER level counts the picked people's tasks only: an epic has no assignee (D2), so `itemKind=EPIC` is empty. */
private const val USER_EPICS_NOTE = "USER level counts the picked people's tasks only: an epic has no assignee, so epics are left out"

/**
 * One summed cell: how many items sat in [statusId] (stage [stage]) at the end of [day] — an `agg_daily_wip` group, a DONE-band
 * group, or at USER level one picked person's ([accountId]) tasks, read at query time (`WipUserReport.kt`).
 */
internal data class WipCell(val day: String, val statusId: String, val stage: String, val count: Int, val accountId: String? = null)

/** A team's board resolved for `by=COLUMN`: the connections it lives on and its columns' status membership. */
internal class ColumnMapping(val connectionIds: List<UInt>, val columns: List<String>, val columnOfStatus: Map<String, String>)

private fun WipItemKind.storedKinds(): List<String> = when (this) {
    WipItemKind.TASK -> listOf(WIP_KIND_TASK)
    WipItemKind.EPIC -> listOf(WIP_KIND_EPIC)
}

/**
 * `GET /api/v1/reports/wip` (v0.3.0 M5 commit 15, Report 9, `.claude/docs/measures.md` "Report 9 — WIP"): items in
 * parallel per status / stage / board column over time (each day's END-of-day snapshot, a missing cell is zero). The five
 * work-in-progress stages (the DONE band apart) come off `metrics.agg_daily_wip`, one row per non-zero cell; the DONE band
 * is computed AT QUERY TIME from `item_stage` (decision B: DERIVE stores no DONE row), attributed as at the item's done time
 * ([readWipDoneBand]). USER level (`teamId` + 1..8 `accountId`s) is not stored either: it is read at query time too, from the
 * same `item_stage` intervals joined to the assignee ones ([readWipUserCells]), and each point carries a `byUser` split. See
 * `.claude/docs/reports.md` "Reports 9, 10, 13".
 */
suspend fun ReportService.wip(filter: ReportFilter, by: WipBy, itemKind: WipItemKind, nowMs: Long): WipReport =
    reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val snapshotScope = snapshotScopeOf(filter)
        val mapping = if (by == WipBy.COLUMN) columnMappingFor(snapshotScope, scope.connectionIds) else null
        val calendar = scope.calendar
        val plan = planSnapshotDays(scope, filter, calendar, userNote = null, startAtBackfill = true)
        val picked = pickedAccounts(filter)
        val user = filter.level == ReportLevel.USER
        // An epic has no assignee: USER level + EPIC has nothing to read (the note says so).
        val days = if (user && itemKind == WipItemKind.EPIC) emptyList() else plan.days
        val cells = if (days.isEmpty()) {
            emptyList()
        } else {
            val connectionIds = mapping?.connectionIds ?: scope.connectionIds
            val first = days.first().toString()
            val last = days.last().toString()
            if (user) {
                // Like the aggregate (no rows for a connection that never derived): only connections with a successful DERIVE.
                val derived = deriveClocks(connectionIds).keys
                readWipUserCells(WipUserRequest(connectionIds.filter { it in derived }, filter.teamId!!, picked.ids, first, last))
            } else {
                fetchWipCells(connectionIds, snapshotScope, itemKind, first, last) +
                    fetchDoneCells(connectionIds, snapshotScope, itemKind, first, last)
            }
        }
        val keys = wipKeys(by, cells, mapping, scope.connectionIds)
        val countsByDay = countsByDay(by, cells, mapping)
        val countsByDayAndAccount = if (user) countsByDayAndAccount(by, cells, mapping) else emptyMap()
        val series = days.map { day ->
            val iso = day.toString()
            val byUser = picked.ids.map { id ->
                val own = countsByDayAndAccount[iso]?.get(id).orEmpty()
                WipUserShare(id, picked.label(id), keys.associate { it.key to (own[it.key] ?: 0) })
            }
            val perKey = countsByDay[iso].orEmpty()
            WipPoint(iso, calendar.isWorkingDay(day), keys.associate { it.key to (perKey[it.key] ?: 0) }, byUser)
        }
        val epicsNote = USER_EPICS_NOTE.takeIf { user && itemKind == WipItemKind.EPIC }
        val note = listOfNotNull(plan.note, epicsNote).joinToString(". ").ifEmpty { null }
        WipReport(scope.meta.forTaskDomain(), by, itemKind, keys, series, note)
    }

private suspend fun R2dbcTransaction.fetchDoneCells(
    connectionIds: List<UInt>,
    scope: SnapshotScope,
    itemKind: WipItemKind,
    firstDay: String,
    lastDay: String,
): List<WipCell> {
    val request = WipDoneRequest(
        connectionIds = connectionIds,
        scope = scope,
        includeTasks = itemKind == WipItemKind.TASK,
        includeEpics = itemKind == WipItemKind.EPIC,
        firstDay = firstDay,
        lastDay = lastDay,
    )
    return readWipDoneBand(request).map { WipCell(it.day, it.statusId, STAGE_DONE, it.count) }
}

/** The five stages `agg_daily_wip` stores (never DONE — [fetchDoneCells] computes that band). */
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
                (w.day greaterEq firstDay) and (w.day lessEq lastDay) and (w.stage neq STAGE_DONE)
        }
        .groupBy(w.day, w.statusId, w.stage)
        .orderBy(w.day to SortOrder.ASC, w.statusId to SortOrder.ASC, w.stage to SortOrder.ASC)
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
    return boardColumnMappingOrNull(teamId, connectionIds)
        ?: throw BadRequestException("Team $teamId has no board mapped in the connections in scope; by=COLUMN needs one")
}

/** [columnMappingFor]'s read, `null` instead of a `400` when the team has no board in [connectionIds] (the aging x-axis). */
internal suspend fun boardColumnMappingOrNull(teamId: UInt, connectionIds: List<UInt>): ColumnMapping? {
    val map = MetricsConfigService.BoardTeamMap
    val boards = map.selectAll().where { (map.teamId eq teamId) and (map.connectionId inList connectionIds) }.toList()
        .map { it[map.connectionId].value to it[map.boardId] }
    if (boards.isEmpty()) return null
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

/** [countsByDay]'s USER-level twin: day -> account -> key -> count (cells with no account, i.e. every non-USER read, are skipped). */
private fun countsByDayAndAccount(by: WipBy, cells: List<WipCell>, mapping: ColumnMapping?): Map<String, Map<String, Map<String, Int>>> =
    cells.filter { it.accountId != null }.groupBy { it.day }.mapValues { (_, dayCells) ->
        dayCells.groupBy { it.accountId!! }.mapValues { (_, own) ->
            own.groupBy { keyOf(by, it, mapping) }.mapValues { (_, group) -> group.sumOf { it.count } }
        }
    }

/** The legend: STAGE is the fixed six stages, COLUMN the board's columns (plus "(no column)" when used), STATUS every status seen. */
private suspend fun wipKeys(by: WipBy, cells: List<WipCell>, mapping: ColumnMapping?, connectionIds: List<UInt>): List<WipKey> = when (by) {
    WipBy.STAGE -> WIP_STAGES.map { WipKey(it, it) }
    WipBy.COLUMN -> {
        val columnKeys = mapping!!.columns.map { WipKey(it, it) }
        val usesNoColumn = cells.any { keyOf(by, it, mapping) == NO_COLUMN_KEY }
        if (usesNoColumn) columnKeys + WipKey(NO_COLUMN_KEY, NO_COLUMN_KEY) else columnKeys
    }
    WipBy.STATUS -> {
        // A status can carry two stages in one scope (a per-domain stage override), so rank it by a FIXED rule: its
        // earliest stage in WIP_STAGES order, whatever order the cells arrive in.
        val rankOfStatus = cells.groupBy { it.statusId }
            .mapValues { (_, group) -> group.minOf { WIP_STAGES.indexOf(it.stage) } }
        val names = statusNames(rankOfStatus.keys, connectionIds)
        sortStatusIds(rankOfStatus, names).map { WipKey(it, names[it] ?: it) }
    }
}

/** The `by=STATUS` legend order, shared with the aging chart's x-axis: earliest-stage rank, then name, then id. */
internal fun sortStatusIds(rankOfStatus: Map<String, Int>, names: Map<String, String>): List<String> =
    rankOfStatus.keys.sortedWith(compareBy<String> { rankOfStatus.getValue(it) }.thenBy { names[it] ?: it }.thenBy { it })

/** `norm.statuses` names by status id — the lowest connection id's name wins when a status id repeats across connections. */
internal suspend fun statusNames(statusIds: Collection<String>, connectionIds: List<UInt>): Map<String, String> {
    if (statusIds.isEmpty() || connectionIds.isEmpty()) return emptyMap()
    val s = WorkItemStore.Statuses
    return s.select(s.statusId, s.name)
        .where { (s.connectionId inList connectionIds) and (s.statusId inList statusIds) }
        .orderBy(s.connectionId to SortOrder.DESC)
        .toList().associate { it[s.statusId] to it[s.name] }
}
