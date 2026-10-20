package ch.nokillswit.reports

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
 * The slice of the matrix's worklogs the picked [keys] of [by] select: `column IN keys`, plus `column IS NULL` when [UNCATEGORIZED]
 * is among them (the key a null column value is listed under, [costBreakdownKey]).
 */
private fun costKeysSlice(by: CostBreakdown, keys: List<String>): Op<Boolean> {
    val column = by.column()
    val inKeys: Op<Boolean> = column inList keys
    return if (UNCATEGORIZED in keys) inKeys or column.isNull() else inKeys
}

/** One (worklog's issue, author) group of the slice: the exact MD and the latest `started_at`, plus the parent of a sub-task. */
private class CostDrillGroup(
    val connectionId: UInt,
    val issueId: Long,
    val authorAccountId: String?,
    val epicId: Long?,
    val parentTaskId: Long?,
    val md: BigDecimal,
    val lastStartedAt: Long?,
)

/** One listed item before it is labelled: the task (or epic, or above-epic issue) every group of it rolls up to. */
private class CostDrillItem(val connectionId: UInt, val issueId: Long, val isEpic: Boolean, val groups: List<CostDrillGroup>)

private suspend fun fetchCostDrillGroups(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    by: CostBreakdown,
    keys: List<String>,
): List<CostDrillGroup> {
    val w = MetricsTables.FactWorklog
    val task = MetricsTables.DimTask
    val keySlice = if (by == CostBreakdown.EPIC) epicKeysSlice(filter, connectionIds, window, keys) else costKeysSlice(by, keys)
    val md = w.md.sum()
    val last = w.startedAt.max()
    // A sub-task's worklogs roll up to its parent task (D2): dim_task names the parent; an epic or above-epic issue has no row.
    val source = w.join(task, JoinType.LEFT, onColumn = w.issueId, otherColumn = task.issueId) { w.connectionId eq task.connectionId }
    return source.select(w.connectionId, w.issueId, w.authorAccountId, w.epicId, task.isSubtask, task.parentTaskId, md, last)
        .where { worklogSlice(filter, connectionIds, window) and keySlice }
        .groupBy(w.connectionId, w.issueId, w.authorAccountId, w.epicId, task.isSubtask, task.parentTaskId)
        .toList()
        .map {
            CostDrillGroup(
                connectionId = it[w.connectionId].value,
                issueId = it[w.issueId],
                authorAccountId = it[w.authorAccountId],
                epicId = it[w.epicId],
                parentTaskId = if (it.getOrNull(task.isSubtask) == true) it[task.parentTaskId] else null,
                md = it[md] ?: BigDecimal.ZERO,
                lastStartedAt = it[last],
            )
        }
}

/** Groups the slice's rows by the item they roll up to (sub-task → parent task) — and tells an epic-logged item from a task. */
private fun rollUp(groups: List<CostDrillGroup>): List<CostDrillItem> =
    groups.groupBy { it.connectionId to (it.parentTaskId ?: it.issueId) }.map { (owner, rows) ->
        // A worklog logged ON an epic carries the epic's own id as `epic_id` (A21); every other kind has a different or no epic.
        CostDrillItem(owner.first, owner.second, isEpic = rows.any { it.epicId == it.issueId }, groups = rows)
    }

/**
 * `GET /api/v1/reports/cost-matrix/items`: the tasks behind one slice of the cost matrix's activity-type or work-category chart, or
 * one row or cell of its epic table (`by=EPIC`, keys per `CostMatrixEpics.kt`). It
 * reads the matrix's own worklogs ([worklogSlice] over the same filter and window) narrowed to the picked [keys] of [by] and rolls
 * each sub-task's worklogs up to its parent task (D2, invariant 7) — a worklog logged ON an epic lists the epic (`itemKind` EPIC),
 * one logged on an issue above the epic level (A31) lists that issue as a TASK. `figure` is the item's MD in the slice (the rounded
 * exact sum of its worklogs), `anchorAt` the latest `started_at` among them, `assignee` the item's authors' display names (the
 * largest contributor first, `, `-joined; `accountId` is set only when there is exactly one author), and `bar` the slice recomputed
 * over exactly the listed set: `bar.sumMd` is the rounded EXACT sum of its worklogs (the chart's slice figure for one key), `count`
 * the items. Authors with no account on a worklog are not named. An empty slice is a valid empty list (an unknown key is no `404`).
 */
internal suspend fun ReportService.costMatrixItems(
    filter: ReportFilter,
    by: CostBreakdown,
    keys: List<String>,
    nowMs: Long,
): ReportDrillItems = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val window = scope.window
    val groups = if (window == null || scope.connectionIds.isEmpty()) {
        emptyList()
    } else {
        fetchCostDrillGroups(filter, scope.connectionIds, window, by, keys)
    }
    val items = rollUp(groups)
    val names = accountDisplayNames(groups.mapNotNull { it.authorAccountId }.distinct())
    val bar = ReportDrillBar(
        count = items.size,
        sumMd = groups.fold(BigDecimal.ZERO) { sum, group -> sum + group.md }.md(),
        from = null,
        to = null,
    )
    drillItems(
        scope.meta,
        bar,
        items.map { item ->
            val authors = item.groups.filter { it.authorAccountId != null }
                .groupBy({ it.authorAccountId!! }, { it.md })
                .map { (account, parts) -> account to parts.fold(BigDecimal.ZERO, BigDecimal::add) }
                .sortedWith(compareByDescending<Pair<String, BigDecimal>> { it.second }.thenBy { names[it.first] ?: it.first })
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
