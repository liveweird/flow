package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.metrics.MetricsTables
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/** The most `key`s one cost drill takes: the folded "Other" slice of a chart lists its keys, and no catalogue has more. */
internal const val COST_DRILL_MAX_KEYS = 100

/**
 * The required, repeated `key` of the cost drill: 1..[COST_DRILL_MAX_KEYS] distinct, trimmed, control-character-free values
 * (`400` otherwise).
 */
internal fun Parameters.requireCostKeys(): List<String> {
    val keys = repeatedStrings("key", COST_DRILL_MAX_KEYS).map { sanitizeSingleLine(it, "key") }.filter { it.isNotEmpty() }.distinct()
    if (keys.isEmpty()) throw BadRequestException("key is required (1..$COST_DRILL_MAX_KEYS values, repeated)")
    return keys
}

/**
 * The slice of the report's worklogs the picked [keys] of [by] select: `column IN keys`, plus `column IS NULL` when [UNCATEGORIZED]
 * is among them (the key a null column value is listed under, [costBreakdownKey]).
 */
private fun costKeysSlice(by: CostBreakdown, keys: List<String>): Op<Boolean> {
    val column = by.column()
    val inKeys: Op<Boolean> = column inList keys
    return if (UNCATEGORIZED in keys) inKeys or column.isNull() else inKeys
}

/**
 * One (worklog's issue, author) group of a drilled set: the exact MD and the latest `started_at`, plus the parent of a sub-task.
 * [epicId] is the worklog's stored as-of epic (the roll-up uses it to tell an epic-logged item, A21); [currentEpicId] is the CURRENT epic
 * the sprint widget's by-epic split counts it toward (`dim_task.epic_id`, or the epic's own id when it is the issue logged on, `null` = no
 * epic).
 */
internal class CostDrillGroup(
    val connectionId: UInt,
    val issueId: Long,
    val authorAccountId: String?,
    val epicId: Long?,
    val currentEpicId: Long?,
    val parentTaskId: Long?,
    val md: BigDecimal,
    val lastStartedAt: Long?,
)

/** One listed item before it is labelled: the task (or epic, or above-epic issue) every group of it rolls up to. */
private class CostDrillItem(val connectionId: UInt, val issueId: Long, val isEpic: Boolean, val groups: List<CostDrillGroup>)

/**
 * The worklogs [predicate] selects, grouped per (connection, issue, author) in SQL with their exact MD, each with the epic it counts toward
 * NOW: a task or sub-task row of `dim_task` names it (a sub-task row carries its parent task's epic, D2), a worklog logged ON an epic is
 * its own epic (the stored `epic_id` equals the issue id, A21) and one on an issue above the epic level has none (A31).
 */
internal suspend fun fetchCostDrillGroups(predicate: Op<Boolean>): List<CostDrillGroup> {
    val w = MetricsTables.FactWorklog
    val task = MetricsTables.DimTask
    val md = w.md.sum()
    val last = w.startedAt.max()
    // A sub-task's worklogs roll up to its parent task (D2): dim_task names the parent; an epic or above-epic issue has no row.
    val source = w.join(task, JoinType.LEFT, onColumn = w.issueId, otherColumn = task.issueId) { w.connectionId eq task.connectionId }
    return source.select(w.connectionId, w.issueId, w.authorAccountId, w.epicId, task.isSubtask, task.parentTaskId, task.epicId, md, last)
        .where { predicate }
        .groupBy(w.connectionId, w.issueId, w.authorAccountId, w.epicId, task.isSubtask, task.parentTaskId, task.epicId)
        .toList()
        .map {
            val hasTaskRow = it.getOrNull(task.isSubtask) != null
            CostDrillGroup(
                connectionId = it[w.connectionId].value,
                issueId = it[w.issueId],
                authorAccountId = it[w.authorAccountId],
                epicId = it[w.epicId],
                currentEpicId = if (hasTaskRow) it.getOrNull(task.epicId) else it[w.epicId]?.takeIf { epic -> epic == it[w.issueId] },
                parentTaskId = if (it.getOrNull(task.isSubtask) == true) it[task.parentTaskId] else null,
                md = it[md] ?: BigDecimal.ZERO,
                lastStartedAt = it[last],
            )
        }
}

/**
 * The named authors of one drilled item with the exact MD each logged, the largest contributor first (ties by display name); worklogs
 * with no known author are not named. Shared by the Costs drill and Throughput's actual-cost drill.
 */
internal fun rankedAuthors(shares: List<Pair<String?, BigDecimal>>, names: Map<String, String>): List<Pair<String, BigDecimal>> =
    shares.mapNotNull { (account, md) -> account?.let { it to md } }
        .groupBy({ it.first }, { it.second })
        .map { (account, parts) -> account to parts.fold(BigDecimal.ZERO, BigDecimal::add) }
        .sortedWith(compareByDescending<Pair<String, BigDecimal>> { it.second }.thenBy { names[it.first] ?: it.first })

/** Groups the slice's rows by the item they roll up to (sub-task → parent task) — and tells an epic-logged item from a task. */
private fun rollUp(groups: List<CostDrillGroup>): List<CostDrillItem> =
    groups.groupBy { it.connectionId to (it.parentTaskId ?: it.issueId) }.map { (owner, rows) ->
        // A worklog logged ON an epic carries the epic's own id as `epic_id` (A21); every other kind has a different or no epic.
        CostDrillItem(owner.first, owner.second, isEpic = rows.any { it.epicId == it.issueId }, groups = rows)
    }

/** What the drill's `by` can name: a donut's breakdown, or `EPIC` (a segment of a sprint's AC bar). Absent = a sprint's whole AC bar. */
internal enum class CostDrillBy { ACTIVITY_TYPE, WORK_CATEGORY, EPIC }

/** What a cost drill request asks for: a donut slice, or the worklogs of ONE sprint (the whole AC bar, or one epic segment of it). */
internal sealed interface CostsDrillSelection {
    class Slice(val by: CostBreakdown, val keys: List<String>) : CostsDrillSelection

    /** [epicSegment] `null` = the whole bar. */
    class SprintBar(val epicSegment: EpicSegmentSelector?) : CostsDrillSelection
}

/**
 * Reads `by` / `key` / `epicSegment` / `topEpicId` (`400` on any other combination): `by=ACTIVITY_TYPE|WORK_CATEGORY` takes the repeated
 * `key` (required) and no epic parameter; `by=EPIC` takes the sprint segment (`epicSegment`, with `topEpicId` for `OTHER_EPICS`) and no
 * `key`; no `by` is the sprint's whole bar and takes none of them.
 */
internal fun Parameters.costsDrillSelection(): CostsDrillSelection {
    val by = optionalEnum<CostDrillBy>("by")
    if (by == null && contains("by")) throw BadRequestException("by must not be blank (allowed: ${CostDrillBy.entries.joinToString()})")
    val epicSegment = optionalEpicSegment()
    val breakdown = when (by) {
        CostDrillBy.ACTIVITY_TYPE -> CostBreakdown.ACTIVITY_TYPE
        CostDrillBy.WORK_CATEGORY -> CostBreakdown.WORK_CATEGORY
        CostDrillBy.EPIC, null -> null
    }
    if (breakdown != null) {
        if (epicSegment != null) throw BadRequestException("epicSegment belongs to by=EPIC")
        return CostsDrillSelection.Slice(breakdown, requireCostKeys())
    }
    if (contains("key")) throw BadRequestException("key belongs to by=ACTIVITY_TYPE or by=WORK_CATEGORY")
    if (by == CostDrillBy.EPIC && epicSegment == null) throw BadRequestException("by=EPIC needs epicSegment")
    if (by == null && epicSegment != null) throw BadRequestException("epicSegment needs by=EPIC")
    return CostsDrillSelection.SprintBar(epicSegment)
}

/**
 * `GET /api/v1/reports/costs/items`: the tasks behind one bar of the Costs report's charts, rolled up and shaped the same way for every
 * kind. A donut slice ([CostsDrillSelection.Slice]) reads the report's own worklogs ([worklogSlice] over the same filter and window)
 * narrowed to the picked keys of `by`; a sprint bar ([CostsDrillSelection.SprintBar], needs a `sprintId` period and a team, else `400`)
 * reads the worklogs of that sprint's AC (`fetchCostSprintGroups`, the very groups the chart sums), the whole bar or one epic segment.
 * Each sub-task's worklogs roll up to its parent task (D2, invariant 7) — a worklog logged ON an epic lists the epic (`itemKind` EPIC),
 * one logged on an issue above the epic level (A31) lists that issue as a TASK. `figure` is the item's MD in the bar (the rounded
 * exact sum of its worklogs), `anchorAt` the latest `started_at` among them, `assignee` the item's authors' display names (the
 * largest contributor first, `, `-joined; `accountId` is set only when there is exactly one author), and `bar` the bar recomputed
 * over exactly the listed set: `bar.sumMd` is the rounded EXACT sum of its worklogs (the chart's figure), `count` the items. Authors
 * with no account on a worklog are not named. An empty bar is a valid empty list (an unknown key is no `404`).
 */
internal suspend fun ReportService.costsItems(
    filter: ReportFilter,
    selection: CostsDrillSelection,
    nowMs: Long,
): ReportDrillItems {
    if (selection is CostsDrillSelection.SprintBar) filter.requireSprintBarScope()
    return reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val window = scope.window
        val groups = when {
            window == null || scope.connectionIds.isEmpty() -> emptyList()
            selection is CostsDrillSelection.Slice ->
                fetchCostDrillGroups(worklogSlice(filter, scope.connectionIds, window) and costKeysSlice(selection.by, selection.keys))
            selection is CostsDrillSelection.SprintBar ->
                scope.sprintRows.flatMap { fetchCostSprintGroups(filter, it, nowMs) }
                    .filter { selection.epicSegment == null || selection.epicSegment.matches(it.currentEpicId) }
            else -> emptyList()
        }
        costsDrillResult(scope.meta, groups)
    }
}

private suspend fun costsDrillResult(meta: ReportMeta, groups: List<CostDrillGroup>): ReportDrillItems {
    val items = rollUp(groups)
    val names = accountDisplayNames(groups.mapNotNull { it.authorAccountId }.distinct())
    val bar = ReportDrillBar(
        count = items.size,
        sumMd = groups.fold(BigDecimal.ZERO) { sum, group -> sum + group.md }.md(),
        from = null,
        to = null,
    )
    return drillItems(
        meta,
        bar,
        items.map { item ->
            val authors = rankedAuthors(item.groups.map { it.authorAccountId to it.md }, names)
            DrillRow(
                connectionId = item.connectionId,
                issueId = item.issueId,
                itemKind = if (item.isEpic) DrillItemKind.EPIC else DrillItemKind.TASK,
                accountId = authors.singleOrNull()?.first,
                teamId = null,
                anchorAt = item.groups.mapNotNull { it.lastStartedAt }.maxOrNull(),
                figure = item.groups.fold(BigDecimal.ZERO) { sum, group -> sum + group.md }.md(),
                assignee = authors.takeIf { it.isNotEmpty() }?.joinToString(", ") { (account, _) -> names[account] ?: account },
            )
        },
    )
}
