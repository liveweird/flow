package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import java.time.ZoneId

/*
 * The Home overview's done-task figures (`HomeReport.kt`), aggregated in Postgres (build-times WHY 38): no done-task row is loaded for
 * them. `HomeDoneSql.kt` holds the two statements; this file turns their rows into the per-team, per-window figures the section
 * builders read.
 * What the Kotlin kernels computed per row, the statements compute per group with the same comparisons (`estimateMdOf`, `cycleDaysOf`,
 * `costMdOf`, `taskRatioOf` and the findings keep their per-item form for the drills, `HomeDrill.kt`).
 */

/** One window's done-task figures of a team: the counts and sums of statement 1 and the three samples of statement 2. */
internal class HomeDoneWindowFigures(
    val agg: HomeDoneWindowAgg,
    val estimate: HomeDoneSample,
    val cycle: HomeDoneSample,
    val cost: HomeDoneSample,
)

/** A team's done-task figures of both windows, and the current window's weekly delivery (zero-filled). */
internal class HomeDoneTeamFigures(
    val current: HomeDoneWindowFigures,
    val previous: HomeDoneWindowFigures,
    val weeks: List<HomeWeek>,
)

/** The first instant of every Monday of [window] (the weeks `bucketStarts` lists), each in [calendar]'s zone: ascending. */
internal fun weekStartsMs(window: HomeSpan, calendar: WorkingCalendar, zone: ZoneId): List<Long> =
    bucketStarts(window.fromMs, window.toMs, zone, ThroughputBucket.WEEK).map { calendar.dayBoundsMs(it).first }

/**
 * The figures of every one of [teams] (a team without rows gets zero counts and empty samples, hidden under any minimum) over both
 * windows, read by two statements; none runs for an empty [teams] or [connectionIds].
 */
internal suspend fun readHomeDoneFigures(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    windows: HomeWindows,
    teams: Collection<UInt>,
    calendar: WorkingCalendar,
    zone: ZoneId,
): Map<UInt, HomeDoneTeamFigures> {
    val mondays = bucketStarts(windows.current.fromMs, windows.current.toMs, zone, ThroughputBucket.WEEK)
    val read = connectionIds.isNotEmpty() && teams.isNotEmpty()
    val groups = if (read) {
        readHomeDoneGroups(
            filter, connectionIds, windows.both, teams, windows.current.fromMs, weekStartsMs(windows.current, calendar, zone),
        )
    } else {
        emptyList()
    }
    val samples = if (read) readHomeDoneSamples(filter, connectionIds, windows.both, teams, windows.current.fromMs) else emptyMap()
    return teams.associateWith { team ->
        val own = groups.filter { it.team == team }
        fun window(current: Boolean) = HomeDoneWindowFigures(
            agg = own.filter { it.current == current }.fold(HomeDoneWindowAgg.ZERO) { acc, g -> acc + g.agg },
            estimate = samples[HomeDoneKey(team, current, SAMPLE_ESTIMATE)] ?: HomeDoneSample.EMPTY,
            cycle = samples[HomeDoneKey(team, current, SAMPLE_CYCLE)] ?: HomeDoneSample.EMPTY,
            cost = samples[HomeDoneKey(team, current, SAMPLE_COST)] ?: HomeDoneSample.EMPTY,
        )
        val byWeek = own.filter { it.current }.groupBy({ it.week }) { it.agg }
        val weeks = mondays.mapIndexed { i, monday ->
            val week = byWeek[i + 1].orEmpty().fold(HomeDoneWindowAgg.ZERO) { acc, agg -> acc + agg }
            HomeWeek(monday.toString(), week.sumEstimateAtDone.md(), week.nAll)
        }
        HomeDoneTeamFigures(window(true), window(false), weeks)
    }
}

private const val MEDIAN = 0.5
private const val CYCLE_P85 = 0.85

internal fun granularityOf(current: HomeDoneWindowFigures, previous: HomeDoneWindowFigures, min: Int) = HomeGranularity(
    estimateMd = trendedOf(
        HomeMeasure.ESTIMATE_MD, previous.estimate.figure(MEDIAN, min), current.estimate.figure(MEDIAN, min), min,
    ),
    cycleWorkingDays = trendedOf(
        HomeMeasure.CYCLE_WORKING_DAYS, previous.cycle.figure(MEDIAN, min), current.cycle.figure(MEDIAN, min), min,
    ),
    costMd = trendedOf(HomeMeasure.COST_MD, previous.cost.figure(MEDIAN, min), current.cost.figure(MEDIAN, min), min),
)

internal fun throughputOf(team: HomeDoneTeamFigures, min: Int): HomeThroughput {
    val (current, previous) = team.current to team.previous
    return HomeThroughput(
        weeks = team.weeks,
        deliveredMd = trendedOf(
            HomeMeasure.DELIVERED_MD,
            sumFigure(previous.agg.sumEstimateAtDone.md(), previous.agg.nAll),
            sumFigure(current.agg.sumEstimateAtDone.md(), current.agg.nAll),
            min,
        ),
        deliveredItems = trendedOf(HomeMeasure.DELIVERED_ITEMS, countFigure(previous.agg.nAll), countFigure(current.agg.nAll), min),
        cycleP50 = trendedOf(
            HomeMeasure.CYCLE_PERCENTILE, previous.cycle.figure(MEDIAN, min), current.cycle.figure(MEDIAN, min), min,
        ),
        cycleP85 = trendedOf(
            HomeMeasure.CYCLE_PERCENTILE, previous.cycle.figure(CYCLE_P85, min), current.cycle.figure(CYCLE_P85, min), min,
        ),
    )
}

/** `accuracyMeasures`' rule over counts: the share of [n] ratios, hidden when there are none or fewer than [min]; the numerator always. */
private fun predOf(within: Int, n: Int, min: Int): Measured {
    val share = if (n == 0 || n < min) null else predShareOf(within, n)
    return Measured(share, n, share == null, within)
}

internal fun taskPredOf(current: HomeDoneWindowAgg, previous: HomeDoneWindowAgg, min: Int): HomePredLine {
    fun line(within: (HomeDoneWindowAgg) -> Int) = trendedOf(
        HomeMeasure.PRED, predOf(within(previous), previous.nRatio, min), predOf(within(current), current.nRatio, min), min,
    )
    return HomePredLine(line { it.within10 }, line { it.within25 }, line { it.within50 })
}

/** The three task findings of section 7 (counts, never hidden). */
internal class HomeTaskFindings(val noEstimate: HomeFigure, val unassigned: HomeFigure, val estimatedLate: HomeFigure)

internal fun taskFindingsOf(current: HomeDoneWindowAgg, previous: HomeDoneWindowAgg, min: Int): HomeTaskFindings {
    fun finding(count: (HomeDoneWindowAgg) -> Int) =
        trendedOf(HomeMeasure.FINDING_COUNT, countFigure(count(previous)), countFigure(count(current)), min)
    return HomeTaskFindings(finding { it.noEstimate }, finding { it.unassigned }, finding { it.late })
}
