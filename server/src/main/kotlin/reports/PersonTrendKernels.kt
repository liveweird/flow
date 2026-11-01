package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import java.time.LocalDate

/*
 * Report 20, Person trend (`.claude/docs/reports.md` "Report 20"): the PURE half -- the grid of windows, the slice of a wide fetch to one
 * window, the coverage label of a window and the first-against-last cue of a row. No database here, so `PersonTrendKernelsTest` pins
 * every branch with hand-computed numbers. The figures themselves are NOT here: every point is Report 18's `cellsOf` over a window.
 */

/** Every window covers this many calendar days, ending on its point's day (the Home overview's 90). */
internal const val TREND_WINDOW_DAYS = 90

/** A point every this many days, counting back from the request's `to`. */
internal const val TREND_STEP_DAYS = 14

/** The most points one request can ask for: the parser's span cap divided by the step (the span's first day is a point too). */
internal const val MAX_TREND_POINTS = MAX_PERIOD_SPAN_DAYS / TREND_STEP_DAYS + 1

/** The rows of the report, in display order -- each one of Compare people's rows (so each is that report's figure over a window). */
internal val TREND_ROW_KEYS: List<String> = listOf(
    "cycleWorkingDaysP50", "cycleWorkingDaysP90", "flowEfficiencyP50", "deliveredItems", "deliveredMd", "accuracyAtStartPred25",
    "avgWip", "wipAtEndMedianAgeWd", "blockedItemsShare", "worklogCoverage", "lateLoggingShare", "noEstimateShare", "reopenedShare",
    "taskSizeP50", "mrsMerged", "mrCycleWorkingDaysP50", "reviewsGiven",
)

/** [TREND_ROW_KEYS] resolved against Compare people's table; a capacity row is refused (this report shows none, A39). */
internal val TREND_ROWS: List<CompareRowSpec> = TREND_ROW_KEYS.map { key ->
    COMPARE_ROWS.single { it.key == key }.also { require(!it.requiresCapacity) { "$key needs capacity, which Person trend never shows" } }
}

/** One point's window: the [from]..[end] days (inclusive) and their half-open UTC-millis [bounds], as Compare people would read them. */
internal class TrendWindow(val end: LocalDate, val from: LocalDate, val bounds: Pair<Long, Long>)

/** The point days of the range [from]..[to] (inclusive), oldest first: `to`, then every [TREND_STEP_DAYS] back while not before [from]. */
internal fun pointEnds(from: LocalDate, to: LocalDate): List<LocalDate> =
    generateSequence(to) { it.minusDays(TREND_STEP_DAYS.toLong()) }.takeWhile { !it.isBefore(from) }.toList().reversed()

/** The [TREND_WINDOW_DAYS]-day window ending on [end], in [calendar]'s zone. */
internal fun trendWindowOf(end: LocalDate, calendar: WorkingCalendar): TrendWindow {
    val from = end.minusDays((TREND_WINDOW_DAYS - 1).toLong())
    return TrendWindow(end, from, calendar.dayBoundsMs(from).first to calendar.dayBoundsMs(end).second)
}

/**
 * The items of this list (SORTED by [anchor], ascending) anchored inside [bounds] -- the half-open `[first, second)` every report fetch
 * uses -- by two binary searches. This is what lets one fetch over the whole range serve every window: each report's own SQL window is
 * the same half-open test on the same anchor (`done_at`, a worklog's `started_at`).
 */
internal fun <T> List<T>.sliceByAnchor(bounds: Pair<Long, Long>, anchor: (T) -> Long): List<T> {
    fun firstAtOrAfter(limit: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (anchor(this[mid]) < limit) low = mid + 1 else high = mid
        }
        return low
    }
    return subList(firstAtOrAfter(bounds.first), firstAtOrAfter(bounds.second))
}

/**
 * How much of [window] the data covers. [coveredThrough] is the last day the daily aggregates reach ([derivedCoverage]: `null` = nothing
 * derived at all) and [backfillFrom] the day before which a connection's history is incomplete ([backfillStart]: `null` = no limit).
 * NONE: nothing derived, the window ends before [backfillFrom] or starts after [coveredThrough]. PARTIAL: it straddles either edge. FULL
 * otherwise. Every row of a window shares the label (the figures are read from facts the same DERIVE wrote).
 */
internal fun coverageOf(window: TrendWindow, coveredThrough: LocalDate?, backfillFrom: LocalDate?): TrendCoverage = when {
    coveredThrough == null -> TrendCoverage.NONE
    window.from.isAfter(coveredThrough) -> TrendCoverage.NONE
    backfillFrom != null && window.end.isBefore(backfillFrom) -> TrendCoverage.NONE
    window.end.isAfter(coveredThrough) -> TrendCoverage.PARTIAL
    backfillFrom != null && window.from.isBefore(backfillFrom) -> TrendCoverage.PARTIAL
    else -> TrendCoverage.FULL
}

/** One point as the cue reads it: its day, window coverage and cell. */
internal class CuePoint(val end: String, val coverage: TrendCoverage, val cell: CompareCell)

/**
 * The cue of a row: the FIRST against the LAST point whose window is [TrendCoverage.FULL] and whose cell shows a value (not hidden), read
 * with Compare people's own rule ([compareValues]: A = first, B = last, so B better is [TrendVerdict.BETTER]; a sample-gated row needs
 * both samples at `minSampleSize`, a tie within the unit's tolerance reads as no verdict). `null` with fewer than two such points;
 * otherwise the cue always carries the `difference` (last minus first) and the verdict when there is one.
 */
internal fun cueOf(spec: CompareRowSpec, points: List<CuePoint>, minSampleSize: Int): PersonTrendCue? {
    val usable = points.filter { it.coverage == TrendCoverage.FULL && !it.cell.hidden && it.cell.value != null }
    if (usable.size < 2) return null
    val first = usable.first()
    val last = usable.last()
    val verdict = compareValues(
        spec.direction, spec.unit, spec.target, first.cell.value, last.cell.value, first.cell.n, last.cell.n, minSampleSize,
    )
    val side = when (verdict.better) {
        CompareSide.B -> TrendVerdict.BETTER
        CompareSide.A -> TrendVerdict.WORSE
        null -> null
    }
    return PersonTrendCue(first.end, last.end, side, verdict.difference)
}
