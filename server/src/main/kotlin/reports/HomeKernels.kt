package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.http.Parameters
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/*
 * The Home overview's PURE half (`.claude/docs/reports.md` "Home overview"): the two 90-day windows, the figure builders, the trend
 * table and the verdict. No database here, so `HomeKernelsTest` pins every branch with hand-computed numbers.
 */

/** Both windows are this many calendar days long (the shared parser's default period). */
internal const val HOME_WINDOW_DAYS = 90L

/** The most teams one Home request lists (`teamId` repeated 1..this; also the size of the caller-agnostic default). */
internal const val HOME_MAX_TEAMS = 3

/** One window: inclusive dates in the configured zone and the half-open `[fromMs, toMs)` bounds of those whole days. */
internal data class HomeSpan(val fromDate: LocalDate, val toDate: LocalDate, val fromMs: Long, val toMs: Long) {
    fun contains(atMs: Long): Boolean = atMs >= fromMs && atMs < toMs

    fun toWire(): HomeWindow = HomeWindow(fromDate.toString(), toDate.toString())
}

/** The window the figures describe and the equally long one before it. */
internal data class HomeWindows(val current: HomeSpan, val previous: HomeSpan) {
    /** Both windows as one read `[previous.from, current.to)`. */
    val both: Pair<Long, Long> get() = previous.fromMs to current.toMs
}

/**
 * The filter the overview reads under: the shared parser's default (no parameters) — the trailing 90 days ending today in the
 * configured zone, UNIT level, TASK domain view. A Home link with `from`/`to` of this window reproduces exactly the page's figures.
 */
internal fun homeFilter(calendar: WorkingCalendar, nowMs: Long): ReportFilter =
    Parameters.Empty.parseReportFilter(calendar, nowMs, DomainView.TASK)

/**
 * The windows of [filter]'s default period: the previous one is `from − 90 … from − 1` by date arithmetic (never ms arithmetic
 * across a DST change).
 */
internal fun homeWindowsOf(calendar: WorkingCalendar, filter: ReportFilter): HomeWindows {
    val range = filter.period as ReportPeriod.DateRange
    val current = HomeSpan(range.fromDate, range.toDate, range.fromMs, range.toMs)
    val previousFrom = range.fromDate.minusDays(HOME_WINDOW_DAYS)
    val previousTo = range.fromDate.minusDays(1)
    val previous = HomeSpan(previousFrom, previousTo, calendar.dayBoundsMs(previousFrom).first, calendar.dayBoundsMs(previousTo).second)
    return HomeWindows(current, previous)
}

/** A figure before it is wrapped for the wire: [value] is `null` while [hidden] (n under the minimum) or undefined. */
internal data class Measured(val value: Double?, val n: Int, val hidden: Boolean, val numerator: Int? = null)

/** The [fraction] percentile of [values] (PostgreSQL's `percentile_cont`), hidden under [minSample] values like a `Distribution`. */
internal fun percentileFigure(values: List<Double>, fraction: Double, minSample: Int): Measured =
    percentileFigureSorted(if (values.size < minSample) values else values.sorted(), fraction, minSample)

/**
 * [percentileFigure] over values already in ascending order ([sorted] may be a list that only knows the ranks `percentileContinuous`
 * reads, `SparseValues`): hidden under [minSample] values, and then never indexed.
 */
internal fun percentileFigureSorted(sorted: List<Double>, fraction: Double, minSample: Int): Measured {
    val hidden = sorted.size < minSample
    return Measured(if (hidden) null else percentileContinuous(sorted, fraction), sorted.size, hidden)
}

private const val MEDIAN = 0.5

internal fun medianFigure(values: List<Double>, minSample: Int): Measured = percentileFigure(values, MEDIAN, minSample)

/** A plain count: never hidden (a count is a fact, not an estimate from a sample). */
internal fun countFigure(count: Int): Measured = Measured(count.toDouble(), count, hidden = false)

/** A sum over [items] items (never hidden). */
internal fun sumFigure(sum: Double, items: Int): Measured = Measured(sum, items, hidden = false)

/** PRED(10/25/50) of one sample of `actual ÷ estimate` ratios. */
internal data class PredMeasured(val pred10: Measured, val pred25: Measured, val pred50: Measured)

/**
 * The PRED shares of [ratios] ([accuracyMeasures], reused as-is: hidden under [minSample] like the reports) with, as [Measured.numerator],
 * how many ratios are within each share (what the drill lists, `predWithin` — the same epsilon).
 */
internal fun predFigures(ratios: List<Double>, minSample: Int): PredMeasured {
    val measures = accuracyMeasures(ratios, minSample)
    fun one(share: Double?, threshold: Double) = Measured(share, ratios.size, share == null, predWithin(ratios, threshold))
    return PredMeasured(
        one(measures?.pred10, PRED_10), one(measures?.pred25, PRED_25), one(measures?.pred50, PRED_50),
    )
}

/**
 * Which way each trended figure reads and in what unit (the unit sets the tie tolerance and whether `minSampleSize` gates the cue).
 * The granularity medians are LOWER-is-better (smaller batches), like cycle time in Compare people.
 */
internal enum class HomeMeasure(val unit: CompareUnit, val direction: CompareDirection) {
    ESTIMATE_MD(CompareUnit.MD, CompareDirection.LOWER),
    CYCLE_WORKING_DAYS(CompareUnit.DAYS, CompareDirection.LOWER),
    COST_MD(CompareUnit.MD, CompareDirection.LOWER),
    PRED(CompareUnit.SHARE, CompareDirection.HIGHER),
    DELIVERED_MD(CompareUnit.MD, CompareDirection.HIGHER),
    DELIVERED_ITEMS(CompareUnit.ITEMS, CompareDirection.HIGHER),
    CYCLE_PERCENTILE(CompareUnit.DAYS, CompareDirection.LOWER),
    FINDING_COUNT(CompareUnit.ITEMS, CompareDirection.LOWER),
}

/** [current] against the same figure of the previous window ([previous]), by [measure]'s direction: previous is side A, current side B. */
internal fun trendOf(measure: HomeMeasure, previous: Measured, current: Measured, minSample: Int): HomePrevious {
    val verdict = compareValues(
        measure.direction, measure.unit, null,
        previous.value?.takeUnless { previous.hidden }, current.value?.takeUnless { current.hidden },
        previous.n, current.n, minSample,
    )
    return HomePrevious(
        value = previous.value,
        n = previous.n,
        hidden = previous.hidden,
        verdict = when (verdict.better) {
            CompareSide.B -> HomeVerdict.BETTER
            CompareSide.A -> HomeVerdict.WORSE
            null -> null
        },
        difference = verdict.difference,
    )
}

internal fun Measured.toFigure(previous: HomePrevious? = null): HomeFigure = HomeFigure(value, n, hidden, numerator, previous)

/** A figure with its trend: [measured] over each window's rows. */
internal fun <T> trended(
    measure: HomeMeasure,
    current: List<T>,
    previous: List<T>,
    minSample: Int,
    measured: (List<T>) -> Measured,
): HomeFigure {
    return trendedOf(measure, measured(previous), measured(current), minSample)
}

/** [current] as a figure beside its trend against [previous] (the figures already measured, whatever read them). */
internal fun trendedOf(measure: HomeMeasure, previous: Measured, current: Measured, minSample: Int): HomeFigure =
    current.toFigure(trendOf(measure, previous, current, minSample))

/** The PRED line of one sample per window: three trended figures. */
internal fun <T> predLine(current: List<T>, previous: List<T>, minSample: Int, ratioOf: (T) -> Double?): HomePredLine {
    val now = predFigures(current.mapNotNull(ratioOf), minSample)
    val before = predFigures(previous.mapNotNull(ratioOf), minSample)
    return HomePredLine(
        now.pred10.toFigure(trendOf(HomeMeasure.PRED, before.pred10, now.pred10, minSample)),
        now.pred25.toFigure(trendOf(HomeMeasure.PRED, before.pred25, now.pred25, minSample)),
        now.pred50.toFigure(trendOf(HomeMeasure.PRED, before.pred50, now.pred50, minSample)),
    )
}

/** Every Monday of [window] (zero-filled), each with the MD at done and the count of the [doneTasks] that fall in its week. */
internal fun weeklyDelivery(doneTasks: List<HomeDoneTask>, window: HomeSpan, zone: ZoneId): List<HomeWeek> {
    val byWeek = doneTasks.groupBy { bucketStart(it.doneAt, zone, ThroughputBucket.WEEK) }
    return bucketStarts(window.fromMs, window.toMs, zone, ThroughputBucket.WEEK).map { start ->
        val rows = byWeek[start].orEmpty()
        HomeWeek(start.toString(), rows.sumMdAtDone().md(), rows.size)
    }
}

/** The exact sum of the tasks' estimates at done (an unestimated task adds 0). */
internal fun List<HomeDoneTask>.sumMdAtDone(): BigDecimal =
    fold(BigDecimal.ZERO) { acc, t -> acc + (t.estimateAtDoneMd ?: BigDecimal.ZERO) }
