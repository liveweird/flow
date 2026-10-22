package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Costs' sprint widgets (Report 16, domain-model A44): per listed sprint the ACTUAL COST (AC = the man-days the team's members logged,
 * `fact_worklog.started_at` inside the sprint's own dates), its split by the CURRENT epic, and the sprint's capacity; plus the team's
 * time logged in the period outside every listed sprint. ONE fetch (`fetchCostSprintGroups`) feeds the chart's figures and the drill
 * (`costsItems`, `CostsDrillSelection.SprintBar`), so a bar and its list cannot disagree. `.claude/docs/reports.md` "Report 16".
 */

/**
 * One epic segment of a Costs sprint ([CostsSprint.byEpic]): the AC of the worklogs whose current epic this is (`EpicSegments.kt` for the
 * rule, the top-[EPIC_SEGMENT_TOP] fold and the order; ranked by Σ AC over the response's sprints). [key] is the epic id, `OTHER_EPICS`
 * or `NO_EPIC`; [epicId]/[epicKey] only on an epic's own segment. [acMd] is the rounded EXACT sum of the segment's worklogs, so the
 * segments add up to the sprint's `acMd` within 0.005 per segment.
 */
@Serializable
data class CostsEpicSegment(val key: String, val epicId: Long?, val epicKey: String?, val label: String, val acMd: Double)

/**
 * One listed sprint of the Costs report: the team's sprint, [acMd] the rounded EXACT sum of the MD its members logged in the sprint's
 * dates `[startAt, completedAt)` (an open sprint: to now) under the report's slices, [byEpic] that AC cut by current epic (empty when
 * nothing was logged). [capacityMd]/[capacitySource] are the sprint's stored capacity (`CALENDAR`/`TEAM_DEFAULT`/`DEFAULT`, A3/A32; as
 * Commitments' line) shown to EVERY viewer (A44), `null` when it has none and always at USER level (capacity is the team's).
 */
@Serializable
data class CostsSprint(
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val connectionId: UInt,
    val startAt: Long?,
    val completedAt: Long?,
    val acMd: Double,
    val capacityMd: Double?,
    val capacitySource: String?,
    val byEpic: List<CostsEpicSegment>,
)

/** The sprint widgets of one response: the listed [sprints] and the period's team time logged in none of them. */
internal class CostsSprintsBlock(val sprints: List<CostsSprint>, val outsideSprintsMd: Double)

/** Whether the filter names a real team (`teamId` present and not the UNASSIGNED `0`) -- the only level the sprint widgets exist at. */
internal fun ReportFilter.hasRealTeam(): Boolean = teamId != null && teamId != UNASSIGNED_TEAM_ID

/** The sprint-bar drill's scope: one sprint (`sprintId` period) of a team (`400` otherwise). */
internal fun ReportFilter.requireSprintBarScope() {
    if (period !is ReportPeriod.BySprintId) throw BadRequestException("sprintId is required (the worklogs of one sprint's AC bar)")
    if (!hasRealTeam()) throw BadRequestException("teamId is required (a sprint's AC is its team's)")
}

private fun List<CostDrillGroup>.exactMd(): BigDecimal = fold(BigDecimal.ZERO) { sum, group -> sum + group.md }

/**
 * The worklogs of [row]'s AC: the report's own slice ([worklogSlice]: the team's members as the AUTHOR's team at the worklog, the
 * account, domain per view, activity type, work category) over the sprint's dates ([costWindow], half-open, an open sprint to [nowMs])
 * on the sprint's own connection, grouped like the drill ([fetchCostDrillGroups]). A sprint without a start has an empty window.
 */
internal suspend fun fetchCostSprintGroups(filter: ReportFilter, row: SprintRow, nowMs: Long): List<CostDrillGroup> {
    val window = row.costWindow(nowMs)
    if (window.second <= window.first) return emptyList()
    return fetchCostDrillGroups(worklogSlice(filter, listOf(row.connectionId), window))
}

/** The team's logged MD in [window] under the slice that falls in none of [rows]' dates (each on its own connection). */
private suspend fun outsideSprintsMd(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    rows: List<SprintRow>,
    nowMs: Long,
): BigDecimal {
    val w = MetricsTables.FactWorklog
    val inSprint: List<Op<Boolean>> = rows.mapNotNull { row ->
        val (from, to) = row.costWindow(nowMs)
        if (to <= from) null else (w.connectionId eq row.connectionId) and (w.startedAt greaterEq from) and (w.startedAt less to)
    }
    var predicate: Op<Boolean> = worklogSlice(filter, connectionIds, window)
    if (inSprint.isNotEmpty()) predicate = predicate and not(inSprint.reduce { a, b -> a or b })
    val md = w.md.sum()
    return w.select(md).where { predicate }.toList().single()[md] ?: BigDecimal.ZERO
}

/**
 * The sprint widgets of a TEAM-level (or one person's) read over the period [window]: the sprints the shared selector resolved
 * (`scope.sprintRows` -- the team's CLOSED sprints completed in the period, an open sprint only by an explicit `sprintId`, exactly
 * Commitments' rule), oldest first, each with its AC, its by-epic split (epics ranked over ALL the listed sprints by Σ AC) and its
 * capacity, and the period's other team time. A worklog inside two overlapping listed sprints counts in BOTH (and so in both bars).
 */
internal suspend fun costsSprintsBlock(
    filter: ReportFilter,
    scope: ReportScope,
    window: Pair<Long, Long>,
    nowMs: Long,
): CostsSprintsBlock {
    val rows = scope.sprintRows.sortedWith(
        compareBy<SprintRow, Long?>(nullsLast()) { it.completedAt }.thenBy { it.startAt ?: Long.MIN_VALUE }.thenBy { it.sprintId }
            .thenBy { it.connectionId },
    )
    val perSprint = rows.map { fetchCostSprintGroups(filter, it, nowMs) }
    val epics = rankEpicsOf(perSprint.flatten(), { it.currentEpicId }, { it.connectionId }) { listOf(it.exactMd()) }
    val sprints = rows.zip(perSprint).map { (row, groups) ->
        CostsSprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, connectionId = row.connectionId,
            startAt = row.startAt, completedAt = row.completedAt, acMd = groups.exactMd().md(),
            capacityMd = row.capacityMd.takeIf { filter.level != ReportLevel.USER },
            capacitySource = row.capacitySource.takeIf { filter.level != ReportLevel.USER },
            byEpic = epics.split(groups, { it.currentEpicId }) { id, group ->
                CostsEpicSegment(id.key, id.epicId, id.epicKey, id.label, group.exactMd().md())
            },
        )
    }
    return CostsSprintsBlock(sprints, outsideSprintsMd(filter, scope.connectionIds, window, rows, nowMs).md())
}
