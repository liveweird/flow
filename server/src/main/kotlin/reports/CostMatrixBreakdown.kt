package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * What the cost matrix's parts-of-whole charts split the logged man-days by, and what the cost drill (`…/cost-matrix/items`)
 * slices by: the worklog's activity type or its work category (`fact_worklog.activity_type` / `work_category`), or — drill only —
 * its epic (`EPIC`: `fact_worklog.epic_id`, keys per `CostMatrixEpics.kt`; the epic table is `byEpic`, not a list of slices).
 */
@Serializable
enum class CostBreakdown { ACTIVITY_TYPE, WORK_CATEGORY, EPIC }

/** The `fact_worklog` column a breakdown reads. Both are nullable; a `null` is the [UNCATEGORIZED] key (see [costBreakdownKey]). */
internal fun CostBreakdown.column(): Column<String?> = when (this) {
    CostBreakdown.ACTIVITY_TYPE -> MetricsTables.FactWorklog.activityType
    CostBreakdown.WORK_CATEGORY -> MetricsTables.FactWorklog.workCategory
    CostBreakdown.EPIC -> error("EPIC slices by epic_id (epicKeysSlice), not by a text column")
}

/**
 * One slice of a breakdown: the [key] (an activity type or work category; a worklog with none is [UNCATEGORIZED] — the work-category
 * slice's own sentinel, reused for the activity type, whose derive always fills the column so only a corrupt row reaches it) and the
 * [md] logged under it, the rounded EXACT sum like every figure of the matrix. [exactMd] is that sum unrounded (4 decimals, as
 * stored), so a client that merges slices (the folded "Other") sums these and rounds once — the figure then equals the drill's
 * `bar.sumMd`.
 */
@Serializable
data class CostBreakdownSlice(val key: String, val md: Double, val exactMd: Double)

/** A worklog's slice key: its own value, [UNCATEGORIZED] when there is none. */
internal fun costBreakdownKey(value: String?): String = value ?: UNCATEGORIZED

/**
 * The matrix's worklogs ([worklogSlice], the very predicate [fetchCostAggregates] reads, in the same transaction) split by [by]:
 * one slice per key, each the rounded exact sum of its worklogs, ordered by that figure descending then key. A null column value
 * and a literal [UNCATEGORIZED] value are one key. The exact sums over all slices equal the matrix's exact total, so the rounded
 * slices add up to the grand total within 0.005 per slice (the matrix's own rounding rule).
 */
internal suspend fun fetchCostBreakdown(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    by: CostBreakdown,
): List<CostBreakdownSlice> {
    val column = by.column()
    val md = MetricsTables.FactWorklog.md.sum()
    val exactByKey = MetricsTables.FactWorklog.select(column, md)
        .where { worklogSlice(filter, connectionIds, window) }
        .groupBy(column)
        .toList()
        .groupBy({ costBreakdownKey(it[column]) }, { it[md] ?: BigDecimal.ZERO })
        .mapValues { (_, parts) -> parts.fold(BigDecimal.ZERO, BigDecimal::add) }
    return exactByKey.map { (key, exact) -> CostBreakdownSlice(key, exact.md(), exact.toDouble()) }
        .sortedWith(compareByDescending<CostBreakdownSlice> { it.md }.thenBy { it.key })
}
