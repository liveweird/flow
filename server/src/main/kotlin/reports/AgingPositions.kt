package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Where each listed aging item sits in the workflow (the x-axis of the aging chart): its OPEN `metrics.item_stage` row
 * (the same DERIVE as `fact_task_delivery.current_stage` / `dim_epic.current_stage`, so status and stage agree), and the
 * category it is plotted in — a board column for one team with a mapped board, the Jira status otherwise.
 */

/** What an [AgingPosition] is: a Jira status (`key` = its id) or a board column (`key` = its name). */
@Serializable
enum class AgingPositionKind { STATUS, COLUMN }

/** One x-axis category of the aging chart, in workflow order. */
@Serializable
data class AgingPosition(val key: String, val label: String, val kind: AgingPositionKind)

/** Where one item sits: its open stage row ([statusId]/[statusName] null when it has none) and the [position] key it is plotted at. */
internal data class AgingPlace(val statusId: String?, val statusName: String?, val stage: String, val position: String)

internal class AgingPlaces(val positions: List<AgingPosition>, val byItem: Map<Pair<UInt, Long>, AgingPlace>)

/** The position of an item with no open stage row in status mode (the DERIVE invariant says there is none such). */
private const val NO_STATUS_KEY = "(no status)"

private data class OpenStageRow(val statusId: String, val stage: String)

/**
 * The open `item_stage` row of every [items] entry. Each listed item has exactly ONE (Tiling leaves one open interval per
 * item; `ReportAgingWipTest` pins it on the fixture); should two ever exist, the newest wins.
 */
private suspend fun openStageRows(items: List<OpenItem>): Map<Pair<UInt, Long>, OpenStageRow> {
    if (items.isEmpty()) return emptyMap()
    val s = MetricsTables.ItemStage
    val wanted = items.map { it.connectionId to it.issueId }.toSet()
    return s.select(s.connectionId, s.issueId, s.stage, s.statusId, s.validFrom)
        .where {
            (s.connectionId inList items.map { it.connectionId }.distinct()) and (s.issueId inList items.map { it.issueId }.distinct()) and
                s.validTo.isNull()
        }
        .toList().filter { (it[s.connectionId].value to it[s.issueId]) in wanted }
        .groupBy { it[s.connectionId].value to it[s.issueId] }
        .mapValues { (_, rows) -> rows.maxBy { it[s.validFrom] }.let { OpenStageRow(it[s.statusId], it[s.stage]) } }
}

/**
 * The places and the x-axis of [items] (the LISTED items, after the 500 cap, so the chart and the table agree).
 * COLUMN mode: the filter names one real team with a board mapped in [connectionIds] — the board's columns in board
 * order, a status in no column under `(no column)` (last). Otherwise STATUS mode: the statuses ordered like WIP
 * `by=STATUS` (earliest stage, then name, then id). Only positions holding at least one item are listed.
 */
internal suspend fun agingPlaces(filter: ReportFilter, connectionIds: List<UInt>, items: List<OpenItem>): AgingPlaces {
    val open = openStageRows(items)
    val names = statusNames(open.values.map { it.statusId }.toSet(), connectionIds)
    val teamId = filter.teamId?.takeIf { it != UNASSIGNED_TEAM_ID }
    val mapping = teamId?.let { boardColumnMappingOrNull(it, connectionIds) }
    val places = items.associate { item ->
        val row = open[item.connectionId to item.issueId]
        val position = when {
            mapping != null -> row?.let { mapping.columnOfStatus[it.statusId] } ?: NO_COLUMN_KEY
            else -> row?.statusId ?: NO_STATUS_KEY
        }
        val stage = row?.stage ?: item.stage
        (item.connectionId to item.issueId) to AgingPlace(row?.statusId, row?.statusId?.let { names[it] ?: it }, stage, position)
    }
    val used = places.values.map { it.position }.toSet()
    val positions = if (mapping != null) {
        val columns = mapping.columns.filter { it in used }.map { AgingPosition(it, it, AgingPositionKind.COLUMN) }
        columns + listOfNotNull(AgingPosition(NO_COLUMN_KEY, NO_COLUMN_KEY, AgingPositionKind.COLUMN).takeIf { NO_COLUMN_KEY in used })
    } else {
        // A status can carry two stages (a per-domain override): rank it by its earliest, like WIP's legend.
        val rank = open.values.groupBy { it.statusId }.mapValues { (_, rows) -> rows.minOf { WIP_STAGES.indexOf(it.stage) } }
        val statuses = sortStatusIds(rank, names).map { AgingPosition(it, names[it] ?: it, AgingPositionKind.STATUS) }
        statuses + listOfNotNull(AgingPosition(NO_STATUS_KEY, NO_STATUS_KEY, AgingPositionKind.STATUS).takeIf { NO_STATUS_KEY in used })
    }
    return AgingPlaces(positions, places)
}
