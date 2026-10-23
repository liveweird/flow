package ch.nokillswit.reports

import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundOr

/*
 * Costs' sprint widgets (Report 16, domain-model A44): per listed sprint the ACTUAL COST (AC = the man-days the team's members logged,
 * `fact_worklog.started_at` inside the sprint's own dates), its split by the CURRENT epic, and the sprint's capacity; plus the team's
 * time logged in the period outside every listed sprint. ONE rule -- a sprint's AC is its connection's slice-matching worklogs started in
 * its half-open [costWindow] -- feeds the chart (`readCostSprints`: one query over the envelope of the period and the sprints' windows,
 * split in the JVM) and the drill behind a bar (`costsItems`, `CostsDrillSelection.SprintBar`: [sprintBarSlice], the same rule as a
 * predicate, so Postgres forms, orders and cuts the listed items), so a bar and its list cannot disagree.
 * `.claude/docs/reports.md` "Report 16".
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

/** What the sprint widgets' ONE read splits into: each listed sprint's AC groups (in the order of the rows asked) and the outside MD. */
internal class CostSprintSplit(val perSprint: List<List<CostDrillGroup>>, val outsideMd: BigDecimal)

/** Whether [at] falls in this half-open `[from, to)` window. */
private fun Pair<Long, Long>.holds(at: Long): Boolean = at >= first && at < second

/**
 * The worklogs of the sprint-bar drill, as a predicate: for each of [rows] (a sprint of a scoped connection with a non-empty
 * [costWindow]) the report's own slice ([worklogSlice]) over that sprint's window on its own connection -- exactly what [readCostSprints]
 * splits a sprint's AC out of its envelope fetch -- OR-ed together (the rows are distinct `(connection, sprint)` pairs, so a worklog
 * matches at most one) and, for one epic segment, narrowed to it ([costPredicate]). `null` = nothing to list.
 */
internal fun sprintBarSlice(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    rows: List<SprintRow>,
    nowMs: Long,
    epicSegment: EpicSegmentSelector?,
): Op<Boolean>? {
    val slices = rows.filter { it.connectionId in connectionIds }.mapNotNull { row ->
        val window = row.costWindow(nowMs)
        if (window.second > window.first) worklogSlice(filter, listOf(row.connectionId), window) else null
    }
    if (slices.isEmpty()) return null
    val ofSprints = slices.compoundOr()
    return if (epicSegment == null) ofSprints else ofSprints and epicSegment.costPredicate()
}

/**
 * The sprint widgets' ONE read for the chart ([costsSprintsBlock]); the sprint-bar drill ([costsItems]) lists the same rule through
 * [sprintBarSlice], so a bar and its list cannot disagree. A single query fetches the team's worklogs under the report's own slice
 * ([worklogSlice]: the team's members as the AUTHOR's team at the worklog, the account, domain per view, activity type, work category)
 * over the ENVELOPE -- the period [window] and every non-empty sprint window ([costWindow]) -- on [connectionIds]; the JVM then splits it.
 * A sprint's AC is the worklogs of ITS OWN connection with `started_at` in its half-open window (an open sprint runs to [nowMs]; a
 * sprint without a start has an empty window), grouped per (issue, author, stored epic) by [groupCostWorklogs]; one inside two
 * overlapping sprints is in BOTH. The outside figure is the exact MD started inside the period [window] and inside no listed sprint's
 * window on its connection.
 */
internal suspend fun readCostSprints(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    rows: List<SprintRow>,
    nowMs: Long,
): CostSprintSplit {
    val windows = rows.map { it.costWindow(nowMs) }
    val live = rows.indices.filter { windows[it].second > windows[it].first }.toSet()
    val envelope = (live.map { windows[it].first } + window.first).min() to (live.map { windows[it].second } + window.second).max()
    val byConnection = fetchCostWorklogs(worklogSlice(filter, connectionIds, envelope)).groupBy { it.connectionId }
    val perSprint = rows.indices.map { i ->
        val logs = byConnection[rows[i].connectionId].orEmpty()
        if (i in live) groupCostWorklogs(logs.filter { windows[i].holds(it.startedAt) }) else emptyList()
    }
    val liveByConnection = live.groupBy({ rows[it].connectionId }, { windows[it] })
    val outside = byConnection.flatMap { (connectionId, logs) ->
        val sprintWindows = liveByConnection[connectionId].orEmpty()
        logs.filter { log -> window.holds(log.startedAt) && sprintWindows.none { it.holds(log.startedAt) } }
    }
    return CostSprintSplit(perSprint, outside.fold(BigDecimal.ZERO) { sum, log -> sum + log.md })
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
    val split = readCostSprints(filter, scope.connectionIds, window, rows, nowMs)
    val perSprint = split.perSprint
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
    return CostsSprintsBlock(sprints, split.outsideMd.md())
}
