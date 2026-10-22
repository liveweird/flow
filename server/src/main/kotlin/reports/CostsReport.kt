package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * Report 16, "Costs" (`.claude/docs/measures.md` "Report 16"): the man-days logged in the period (`fact_worklog.started_at`) with
 * the foreign-work share, split by activity type and by work category. [totalMd] is the rounded EXACT sum of every worklog in scope
 * (never a sum of rounded parts), [foreignMd] the exact sum of the foreign-work ones and [foreignShare] their ratio, `null` when
 * nothing was logged. [byActivityType] and [byWorkCategory] split the SAME worklogs (`null` category = [UNCATEGORIZED]): the
 * parts-of-whole charts, each ordered by MD descending then key, their exact sums equal to the total's, so the rounded slices add up
 * to [totalMd] within the 0.005-per-addend rule. Empty for an empty slice; always present.
 */
@Serializable
data class CostsReport(
    val meta: ReportMeta,
    val totalMd: Double,
    val foreignMd: Double,
    val foreignShare: Double?,
    val byActivityType: List<CostBreakdownSlice>,
    val byWorkCategory: List<CostBreakdownSlice>,
)

/** The exact MD of the report's worklogs: [total] of all of them, [foreign] of the foreign-work ones. */
private class CostTotals(val total: BigDecimal, val foreign: BigDecimal)

private suspend fun fetchCostTotals(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): CostTotals {
    val w = MetricsTables.FactWorklog
    val md = w.md.sum()
    val byForeign = w.select(w.foreignWork, md)
        .where { worklogSlice(filter, connectionIds, window) }
        .groupBy(w.foreignWork)
        .toList()
        .associate { it[w.foreignWork] to (it[md] ?: BigDecimal.ZERO) }
    val foreign = byForeign[true] ?: BigDecimal.ZERO
    return CostTotals(foreign + (byForeign[false] ?: BigDecimal.ZERO), foreign)
}

private fun emptyCosts(meta: ReportMeta) = CostsReport(meta, 0.0, 0.0, null, emptyList(), emptyList())

/**
 * `GET /api/v1/reports/costs` (Report 16, `.claude/docs/measures.md` "Report 16"): the man-days logged in the period, with the
 * foreign-work share and the activity-type / work-category splits, all over the one [worklogSlice] predicate in one transaction.
 * Sprint-relative periods use the resolved sprints' envelope. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.costs(filter: ReportFilter, nowMs: Long): CostsReport = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val window = scope.window
    if (window == null || scope.connectionIds.isEmpty()) return@reportTransaction emptyCosts(scope.meta)
    val totals = fetchCostTotals(filter, scope.connectionIds, window)
    CostsReport(
        meta = scope.meta,
        totalMd = totals.total.md(),
        foreignMd = totals.foreign.md(),
        foreignShare = if (totals.total.signum() == 0) null else totals.foreign.toDouble() / totals.total.toDouble(),
        byActivityType = fetchCostBreakdown(filter, scope.connectionIds, window, CostBreakdown.ACTIVITY_TYPE),
        byWorkCategory = fetchCostBreakdown(filter, scope.connectionIds, window, CostBreakdown.WORK_CATEGORY),
    )
}
