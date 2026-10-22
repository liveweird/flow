package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.metrics.availableWorkCategories
import ch.nokillswit.metrics.storedWorkCategoryTargets
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Costs' work-category widgets (Report 16, domain-model A45): the team's TARGET share of effort per work category beside what its members
 * ACTUALLY logged, and the cumulative share of each category week by week. Both cut the very worklogs `byWorkCategory` splits (the
 * `worklogSlice` predicate, the "No category" worklog as `UNCATEGORIZED`), so they reconcile with `totalMd`. The targets are read at query
 * time (`metrics.team_work_category_targets`, A43): no DERIVE, and an earlier period is compared against the CURRENT target.
 * `.claude/docs/reports.md` "Report 16".
 */

/** The longest period (in Monday-start weeks touched) the weekly trend is drawn for; a longer one answers `categoryShareTrend: null`. */
internal const val COSTS_TREND_MAX_WEEKS = 160

private const val PERCENT = 100
private const val SHARE_SCALE = 12
private const val DELTA_SCALE = 1

/**
 * One work category of the target-vs-actual table. [targetPercent] is the team's whole-percent target (a category with none, in a
 * configured set, counts as 0; `null` only when [CostsCategoryTargets.configured] is false). [actualMd] is the rounded EXACT sum of the
 * category's worklogs and [actualShare] its fraction (0..1) of the report's exact total (`null` when nothing was logged).
 * [deltaPoints] = actual percent - [targetPercent], percentage points rounded half-up to one decimal from the exact figures (`null`
 * without a target or without time). [mapped] = the category is choosable now (mapped on an active data source, or `UNCATEGORIZED`);
 * false flags a stored target or a logged label nobody can pick any more.
 */
@Serializable
data class CostsCategoryTargetRow(
    val key: String,
    val targetPercent: Int?,
    val actualMd: Double,
    val actualShare: Double?,
    val deltaPoints: Double?,
    val mapped: Boolean,
)

/**
 * The team's targets against its actual time. [configured] = the team has a stored target set. [totalMd] is the report's `totalMd`
 * (the rounded exact sum of the same worklogs). [rows] = every stored target plus every category with a worklog in the period, ordered by
 * target descending (no target = 0), then the exact actual MD descending, then key.
 */
@Serializable
data class CostsCategoryTargets(val configured: Boolean, val totalMd: Double, val rows: List<CostsCategoryTargetRow>)

/**
 * One category at one week's end: its cumulative MD (rounded exact) and its [share] (fraction of that week's cumulative total; `null`
 * while that is 0).
 */
@Serializable
data class CostsTrendCategory(val key: String, val cumulativeMd: Double, val share: Double?)

/**
 * One Monday-start week (its [weekStart], an ISO date in the configured zone): everything logged from the period's start through the end
 * of this week ([cumulativeMd], rounded exact), cut by category. [byCategory] lists every category of the period in the same order in
 * every week.
 */
@Serializable
data class CostsTrendWeek(val weekStart: String, val cumulativeMd: Double, val byCategory: List<CostsTrendCategory>)

/** The weekly cumulative share chart: one [CostsTrendWeek] per Monday from the period's first to its last, zero weeks included. */
@Serializable
data class CostsCategoryTrend(val weeks: List<CostsTrendWeek>)

/** The two widgets of one response (either `null` where it does not exist, see [costsCategoryWidgets]). */
internal class CostsCategoryWidgets(val targets: CostsCategoryTargets?, val trend: CostsCategoryTrend?)

private fun BigDecimal.percentOf(total: BigDecimal): BigDecimal =
    multiply(BigDecimal(PERCENT)).divide(total, SHARE_SCALE, RoundingMode.HALF_UP)

/** A table row with the exact figure its order needs. */
private class TargetRowDraft(val exact: BigDecimal, val row: CostsCategoryTargetRow)

/**
 * The target-vs-actual table (pure): [actual] the exact MD per work-category key (`UNCATEGORIZED` for none), [stored] the team's targets,
 * [available] the keys choosable now. See [CostsCategoryTargets] / [CostsCategoryTargetRow] for the rules.
 */
internal fun categoryTargetRows(actual: Map<String, BigDecimal>, stored: Map<String, Int>, available: Set<String>): CostsCategoryTargets {
    val total = actual.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val configured = stored.isNotEmpty()
    val drafts = (stored.keys + actual.keys).map { key ->
        val exact = actual[key] ?: BigDecimal.ZERO
        val target = if (configured) stored[key] ?: 0 else null
        val percent = if (total.signum() == 0) null else exact.percentOf(total)
        TargetRowDraft(
            exact,
            CostsCategoryTargetRow(
                key = key,
                targetPercent = target,
                actualMd = exact.md(),
                actualShare = if (total.signum() == 0) null else exact.toDouble() / total.toDouble(),
                deltaPoints = if (percent == null || target == null) {
                    null
                } else {
                    (percent - BigDecimal(target)).setScale(DELTA_SCALE, RoundingMode.HALF_UP).toDouble()
                },
                mapped = key in available,
            ),
        )
    }
    val ordered = drafts.sortedWith(
        compareByDescending<TargetRowDraft> { it.row.targetPercent ?: 0 }.thenByDescending { it.exact }.thenBy { it.row.key },
    )
    return CostsCategoryTargets(configured, total.md(), ordered.map { it.row })
}

/** Whether a `domain` / `activityType` / `workCategory` slice narrows the worklogs (the team's whole mix is then not what is shown). */
private fun ReportFilter.isSliced(): Boolean = domain != null || activityType != null || workCategory != null

/** The Monday-start weeks a period [window] touches, or `null` when they exceed [COSTS_TREND_MAX_WEEKS]. An empty window has none. */
internal fun trendWeekStarts(window: Pair<Long, Long>?, zone: ZoneId): List<LocalDate>? {
    if (window == null || window.second <= window.first) return emptyList()
    val first = bucketStart(window.first, zone, ThroughputBucket.WEEK)
    val last = bucketStart(window.second - 1, zone, ThroughputBucket.WEEK)
    if (ChronoUnit.WEEKS.between(first, last) + 1 > COSTS_TREND_MAX_WEEKS) return null
    return bucketStarts(window.first, window.second, zone, ThroughputBucket.WEEK)
}

/**
 * The weekly cumulative share (pure): [weekStarts] zero-filled, [perWeek] the exact MD logged IN each week per category. The categories
 * are those of [perWeek] itself, in the `byWorkCategory` order (MD descending, then key), so a week's figures always add up to its total.
 * Every week lists every key; the running sums are exact and rounded once at the figure.
 */
internal fun categoryTrendWeeks(weekStarts: List<LocalDate>, perWeek: Map<LocalDate, Map<String, BigDecimal>>): CostsCategoryTrend {
    val keys = perWeek.values.flatMap { it.entries }.groupBy({ it.key }, { it.value })
        .mapValues { (_, parts) -> parts.fold(BigDecimal.ZERO, BigDecimal::add) }.toCostSlices().map { it.key }
    val running = keys.associateWith { BigDecimal.ZERO }.toMutableMap()
    return CostsCategoryTrend(
        weekStarts.map { week ->
            perWeek[week].orEmpty().forEach { (key, md) -> running.merge(key, md, BigDecimal::add) }
            val total = running.values.fold(BigDecimal.ZERO, BigDecimal::add)
            CostsTrendWeek(
                weekStart = week.toString(),
                cumulativeMd = total.md(),
                byCategory = keys.map { key ->
                    val cumulative = running.getValue(key)
                    CostsTrendCategory(key, cumulative.md(), if (total.signum() == 0) null else cumulative.toDouble() / total.toDouble())
                },
            )
        },
    )
}

/**
 * The period's worklogs ([worklogSlice], one query) as the exact MD per Monday-start week of [zone] and category. A week outside the
 * period cannot occur: every row is inside the window, so its week is one of [trendWeekStarts]'.
 */
internal suspend fun fetchCostWeeklyByCategory(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    zone: ZoneId,
): Map<LocalDate, Map<String, BigDecimal>> {
    val w = MetricsTables.FactWorklog
    return w.select(w.startedAt, w.workCategory, w.md)
        .where { worklogSlice(filter, connectionIds, window) }
        .toList()
        .groupBy { bucketStart(it[w.startedAt], zone, ThroughputBucket.WEEK) }
        .mapValues { (_, rows) ->
            rows.groupBy({ costBreakdownKey(it[w.workCategory]) }, { it[w.md] })
                .mapValues { (_, parts) -> parts.fold(BigDecimal.ZERO, BigDecimal::add) }
        }
}

/**
 * The two category widgets of a Costs response, [actual] being `fetchCostBreakdownExact(WORK_CATEGORY)` of the SAME filter and window
 * (a `null` window or no connection: nothing to read, [actual] empty). Targets exist only at TEAM level of a real team, and only while
 * no slice (`domain`, `activityType`, `workCategory`) narrows the actuals: they compare the team's WHOLE mix with its targets (`null` at
 * UNIT, for `teamId=0`, at USER level and under a slice). The trend exists at TEAM and USER level of a real team, up to
 * [COSTS_TREND_MAX_WEEKS] weeks, and shows the mix of whatever the slices select; with nothing logged its weeks are zero-filled and no
 * query runs. The targets and the available categories are read here, in the caller's transaction.
 */
internal suspend fun costsCategoryWidgets(
    filter: ReportFilter,
    scope: ReportScope,
    actual: Map<String, BigDecimal>,
): CostsCategoryWidgets {
    if (!filter.hasRealTeam()) return CostsCategoryWidgets(null, null)
    val teamId = checkNotNull(filter.teamId)
    val targets = if (filter.level == ReportLevel.USER || filter.isSliced()) {
        null
    } else {
        categoryTargetRows(actual, storedWorkCategoryTargets(teamId), availableWorkCategories())
    }
    val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
    val weekStarts = trendWeekStarts(scope.window, zone)
    val window = scope.window
    val trend = weekStarts?.let { weeks ->
        val perWeek = if (window == null || actual.isEmpty()) {
            emptyMap()
        } else {
            fetchCostWeeklyByCategory(filter, scope.connectionIds, window, zone)
        }
        categoryTrendWeeks(weeks, perWeek)
    }
    return CostsCategoryWidgets(targets, trend)
}
