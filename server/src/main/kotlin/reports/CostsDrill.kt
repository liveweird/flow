package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.metrics.MetricsTables
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Join
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
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
internal fun costKeysSlice(by: CostBreakdown, keys: List<String>): Op<Boolean> {
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

// A sub-task's worklogs roll up to its parent task (D2): dim_task names the parent; an epic or above-epic issue has no row.
internal fun costWorklogSource(): Join {
    val w = MetricsTables.FactWorklog
    val task = MetricsTables.DimTask
    return w.join(task, JoinType.LEFT, onColumn = w.issueId, otherColumn = task.issueId) { w.connectionId eq task.connectionId }
}

/** What identifies a worklog's group: the stored columns, plus the `dim_task` ones that name its parent task and current epic. */
private val costWorklogColumns: List<Expression<*>> = listOf(
    MetricsTables.FactWorklog.connectionId, MetricsTables.FactWorklog.issueId, MetricsTables.FactWorklog.authorAccountId,
    MetricsTables.FactWorklog.epicId, MetricsTables.DimTask.isSubtask, MetricsTables.DimTask.parentTaskId, MetricsTables.DimTask.epicId,
)

/** The epic a worklog row counts toward NOW: its `dim_task` row's, else (no row) its own epic when it is logged on it (A21), else none. */
private fun ResultRow.costCurrentEpicId(): Long? {
    val w = MetricsTables.FactWorklog
    val task = MetricsTables.DimTask
    return if (getOrNull(task.isSubtask) != null) getOrNull(task.epicId) else this[w.epicId]?.takeIf { epic -> epic == this[w.issueId] }
}

private fun ResultRow.costParentTaskId(): Long? {
    val task = MetricsTables.DimTask
    return if (getOrNull(task.isSubtask) == true) this[task.parentTaskId] else null
}

/** One stored worklog with the epic it counts toward now (a [CostDrillGroup] before grouping): the sprint widgets split these by date. */
internal class CostWorklog(
    val connectionId: UInt,
    val issueId: Long,
    val authorAccountId: String?,
    val epicId: Long?,
    val currentEpicId: Long?,
    val parentTaskId: Long?,
    val md: BigDecimal,
    val startedAt: Long,
)

/** The worklogs [predicate] selects, one row each, shaped like [fetchCostDrillGroups]'s groups (the same join, epic and parent rules). */
internal suspend fun fetchCostWorklogs(predicate: Op<Boolean>): List<CostWorklog> {
    val w = MetricsTables.FactWorklog
    return costWorklogSource()
        .select(costWorklogColumns + w.md + w.startedAt)
        .where { predicate }
        .toList()
        .map {
            CostWorklog(
                connectionId = it[w.connectionId].value,
                issueId = it[w.issueId],
                authorAccountId = it[w.authorAccountId],
                epicId = it[w.epicId],
                currentEpicId = it.costCurrentEpicId(),
                parentTaskId = it.costParentTaskId(),
                md = it[w.md],
                startedAt = it[w.startedAt],
            )
        }
}

/** [worklogs] grouped per (connection, issue, author, stored epic), exact MD and latest `started_at`: [fetchCostDrillGroups] in the JVM. */
internal fun groupCostWorklogs(worklogs: List<CostWorklog>): List<CostDrillGroup> =
    worklogs.groupBy { listOf(it.connectionId, it.issueId, it.authorAccountId, it.epicId) }.values.map { rows ->
        val first = rows.first()
        CostDrillGroup(
            connectionId = first.connectionId,
            issueId = first.issueId,
            authorAccountId = first.authorAccountId,
            epicId = first.epicId,
            currentEpicId = first.currentEpicId,
            parentTaskId = first.parentTaskId,
            md = rows.fold(BigDecimal.ZERO) { sum, row -> sum + row.md },
            lastStartedAt = rows.maxOf { it.startedAt },
        )
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
    if (by != CostDrillBy.EPIC && contains("topEpicId")) {
        throw BadRequestException("topEpicId belongs to by=EPIC with epicSegment=$OTHER_EPICS_KEY")
    }
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
 * reads the worklogs of that sprint's AC ([sprintBarSlice]: the chart's rule, `readCostSprints`), the whole bar or one epic segment.
 * Each sub-task's worklogs roll up to its parent task (D2, invariant 7) — a worklog logged ON an epic lists the epic (`itemKind` EPIC),
 * one logged on an issue above the epic level (A31) lists that issue as a TASK. `figure` is the item's MD in the bar (the rounded
 * exact sum of its worklogs), `anchorAt` the latest `started_at` among them, `assignee` the item's authors' display names (the
 * largest contributor first, `, `-joined; `accountId` is set only when there is exactly one author), and `bar` the bar recomputed
 * over exactly the listed set: `bar.sumMd` is the rounded EXACT sum of its worklogs (the chart's figure), `count` the items. Authors
 * with no account on a worklog are not named. An empty bar is a valid empty list (an unknown key is no `404`). The items are formed,
 * ordered and cut to the listed page in Postgres ([fetchCostDrillPage]); only that page reaches the JVM.
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
        val slice = when {
            window == null || scope.connectionIds.isEmpty() -> null
            selection is CostsDrillSelection.Slice ->
                worklogSlice(filter, scope.connectionIds, window) and costKeysSlice(selection.by, selection.keys)
            selection is CostsDrillSelection.SprintBar ->
                sprintBarSlice(filter, scope.connectionIds, scope.sprintRows, nowMs, selection.epicSegment)
            else -> null
        }
        costsDrillResult(scope.meta, slice?.let { fetchCostDrillPage(it) } ?: CostDrillPage.EMPTY)
    }
}

private suspend fun costsDrillResult(meta: ReportMeta, page: CostDrillPage): ReportDrillItems {
    val names = accountDisplayNames(page.items.flatMap { item -> item.shares.mapNotNull { it.first } }.distinct())
    val bar = ReportDrillBar(count = page.total, sumMd = page.exactMd.md(), from = null, to = null)
    return listedDrillItems(
        meta,
        bar,
        page.items.map { item ->
            val authors = rankedAuthors(item.shares, names)
            DrillRow(
                connectionId = item.connectionId,
                issueId = item.issueId,
                itemKind = if (item.isEpic) DrillItemKind.EPIC else DrillItemKind.TASK,
                accountId = authors.singleOrNull()?.first,
                teamId = null,
                anchorAt = item.lastStartedAt,
                figure = item.md.md(),
                assignee = authors.takeIf { it.isNotEmpty() }?.joinToString(", ") { (account, _) -> names[account] ?: account },
            )
        },
        total = page.total,
    )
}
