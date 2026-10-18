package ch.nokillswit.reports

import java.time.LocalDate
import kotlin.math.abs
import kotlinx.serialization.Serializable

/*
 * Report 18, Compare people (`.claude/docs/reports.md` "Report 18"): the PURE half -- the one table of rows (what each figure is,
 * which side is better, how close counts as a tie) and the rule that turns two people's cells into a verdict. No database here, so
 * `ComparePeopleKernelsTest` pins every branch with hand-computed numbers.
 */

/** Which way a row reads: the higher / the lower value is better, the value closer to the row's target is better, or no side is. */
@Serializable
enum class CompareDirection { HIGHER, LOWER, TARGET, NEUTRAL }

/** What a row's figure is measured in; each unit carries the [tolerance] below which two values are a tie. */
@Serializable
enum class CompareUnit(val tolerance: Double) {
    /** Man-days. */
    MD(0.005),

    /** A man-day figure per available man-day (a part-timer's capacity is already in the denominator). */
    MD_PER_MD(0.005),

    /** A whole number of items: every difference counts. */
    ITEMS(0.0),

    /** An average count of items per working day. */
    ITEMS_PER_DAY(0.05),

    /** Items per available man-day. */
    ITEMS_PER_MD(0.05),

    /** Days (working or elapsed). */
    DAYS(0.05),

    /** A plain ratio (logged ÷ cycle, active ÷ elapsed, an estimate change). */
    RATIO(0.01),

    /** A share, as a fraction `0..1`. */
    SHARE(0.005),
}

/** The row groups of the table, in display order. */
@Serializable
enum class CompareGroup { DELIVERY, CYCLE, ESTIMATION, WIP, TIME, QUALITY }

/** The drill views of `GET /api/v1/reports/compare-people/items` -- which set of items one cell lists. */
enum class CompareDrillView {
    DELIVERED, CYCLE_WORKING_DAYS, CYCLE_ELAPSED_DAYS, REPORTED_TIME_RATIO, FLOW_EFFICIENCY, ACCURACY_AT_START, ACCURACY_AT_DONE,
    ADJUSTED, ADJUSTMENT_CHANGE, WIP_TASK_DAYS, WIP_AT_END, BLOCKED, LOGGED, FOREIGN, LATE, NO_WORKLOGS, NO_ESTIMATE,
}

/**
 * One row of the table. [requiresCapacity] rows are the A39-gated ones (`availableMd` and the three figures divided by it): present
 * only when the route says the caller may see per-person capacity. [target] is the value a [CompareDirection.TARGET] row aims at.
 */
internal data class CompareRowSpec(
    val key: String,
    val group: CompareGroup,
    val unit: CompareUnit,
    val direction: CompareDirection,
    val drill: CompareDrillView?,
    val target: Double? = null,
    val requiresCapacity: Boolean = false,
) {
    /**
     * Whether a side under `minSampleSize` takes the cue away. Every statistic (a distribution's percentile, a ratio, a share, an
     * average) does; a pure count of items ([CompareUnit.ITEMS]: delivered items, the items in progress at the end) is a fact, not an
     * estimate from a sample, so one item against eight is a real difference and the cue stays (its `n` is still reported).
     */
    val sampleGated: Boolean get() = unit != CompareUnit.ITEMS

    init {
        require((direction == CompareDirection.TARGET) == (target != null)) {
            "$key: a target goes with, and only with, the TARGET direction"
        }
    }
}

private const val RATIO_TARGET = 1.0

/** The ONE table of rows, in display order. */
internal val COMPARE_ROWS: List<CompareRowSpec> = listOf(
    CompareRowSpec("deliveredMd", CompareGroup.DELIVERY, CompareUnit.MD, CompareDirection.HIGHER, CompareDrillView.DELIVERED),
    CompareRowSpec("deliveredItems", CompareGroup.DELIVERY, CompareUnit.ITEMS, CompareDirection.HIGHER, CompareDrillView.DELIVERED),
    CompareRowSpec(
        "deliveredMdPerAvailableMd", CompareGroup.DELIVERY, CompareUnit.MD_PER_MD, CompareDirection.HIGHER, CompareDrillView.DELIVERED,
        requiresCapacity = true,
    ),
    CompareRowSpec(
        "deliveredItemsPerAvailableMd", CompareGroup.DELIVERY, CompareUnit.ITEMS_PER_MD, CompareDirection.HIGHER,
        CompareDrillView.DELIVERED, requiresCapacity = true,
    ),
    CompareRowSpec(
        "cycleWorkingDaysP50", CompareGroup.CYCLE, CompareUnit.DAYS, CompareDirection.LOWER, CompareDrillView.CYCLE_WORKING_DAYS,
    ),
    CompareRowSpec(
        "cycleWorkingDaysP90", CompareGroup.CYCLE, CompareUnit.DAYS, CompareDirection.LOWER, CompareDrillView.CYCLE_WORKING_DAYS,
    ),
    CompareRowSpec(
        "cycleElapsedDaysP50", CompareGroup.CYCLE, CompareUnit.DAYS, CompareDirection.LOWER, CompareDrillView.CYCLE_ELAPSED_DAYS,
    ),
    CompareRowSpec(
        "cycleElapsedDaysP90", CompareGroup.CYCLE, CompareUnit.DAYS, CompareDirection.LOWER, CompareDrillView.CYCLE_ELAPSED_DAYS,
    ),
    CompareRowSpec(
        "flowEfficiencyP50", CompareGroup.CYCLE, CompareUnit.SHARE, CompareDirection.HIGHER, CompareDrillView.FLOW_EFFICIENCY,
    ),
    CompareRowSpec(
        "reportedTimeRatioP50", CompareGroup.CYCLE, CompareUnit.RATIO, CompareDirection.TARGET, CompareDrillView.REPORTED_TIME_RATIO,
        target = RATIO_TARGET,
    ),
    CompareRowSpec(
        "accuracyAtStartPred25", CompareGroup.ESTIMATION, CompareUnit.SHARE, CompareDirection.HIGHER, CompareDrillView.ACCURACY_AT_START,
    ),
    CompareRowSpec(
        "accuracyAtDonePred25", CompareGroup.ESTIMATION, CompareUnit.SHARE, CompareDirection.HIGHER, CompareDrillView.ACCURACY_AT_DONE,
    ),
    CompareRowSpec("adjustedShare", CompareGroup.ESTIMATION, CompareUnit.SHARE, CompareDirection.NEUTRAL, CompareDrillView.ADJUSTED),
    CompareRowSpec(
        "adjustmentMedianChange", CompareGroup.ESTIMATION, CompareUnit.RATIO, CompareDirection.NEUTRAL, CompareDrillView.ADJUSTMENT_CHANGE,
    ),
    CompareRowSpec("avgWip", CompareGroup.WIP, CompareUnit.ITEMS_PER_DAY, CompareDirection.LOWER, CompareDrillView.WIP_TASK_DAYS),
    CompareRowSpec("wipAtEndItems", CompareGroup.WIP, CompareUnit.ITEMS, CompareDirection.LOWER, CompareDrillView.WIP_AT_END),
    CompareRowSpec("wipAtEndMedianAgeWd", CompareGroup.WIP, CompareUnit.DAYS, CompareDirection.LOWER, CompareDrillView.WIP_AT_END),
    CompareRowSpec("blockedItemsShare", CompareGroup.WIP, CompareUnit.SHARE, CompareDirection.LOWER, CompareDrillView.BLOCKED),
    CompareRowSpec("blockedShareOfCycle", CompareGroup.WIP, CompareUnit.SHARE, CompareDirection.LOWER, CompareDrillView.BLOCKED),
    CompareRowSpec("loggedMd", CompareGroup.TIME, CompareUnit.MD, CompareDirection.NEUTRAL, CompareDrillView.LOGGED),
    CompareRowSpec(
        "availableMd", CompareGroup.TIME, CompareUnit.MD, CompareDirection.NEUTRAL, drill = null, requiresCapacity = true,
    ),
    CompareRowSpec(
        "loggedPerAvailableMd", CompareGroup.TIME, CompareUnit.MD_PER_MD, CompareDirection.TARGET, CompareDrillView.LOGGED,
        target = RATIO_TARGET, requiresCapacity = true,
    ),
    CompareRowSpec("foreignShare", CompareGroup.TIME, CompareUnit.SHARE, CompareDirection.NEUTRAL, CompareDrillView.FOREIGN),
    CompareRowSpec("worklogCoverage", CompareGroup.QUALITY, CompareUnit.SHARE, CompareDirection.HIGHER, CompareDrillView.NO_WORKLOGS),
    CompareRowSpec("lateLoggingShare", CompareGroup.QUALITY, CompareUnit.SHARE, CompareDirection.LOWER, CompareDrillView.LATE),
    CompareRowSpec("noEstimateShare", CompareGroup.QUALITY, CompareUnit.SHARE, CompareDirection.LOWER, CompareDrillView.NO_ESTIMATE),
)

/** The spec of the row [key], or an error naming it (a typo in a figure builder is a programming error, not a request error). */
internal fun compareRow(key: String): CompareRowSpec = COMPARE_ROWS.firstOrNull { it.key == key } ?: error("no compare-people row '$key'")

/**
 * One person's cell of one row. [n] is the cell's sample size (done tasks, measured items, worklogs, working days...); [hidden] means
 * the figure is withheld under `minSampleSize` (distribution-shaped rows only, as the reports do), so [value] is `null` then; a
 * [value] is also `null` when the figure has no denominator (nothing logged, no available man-days).
 */
@Serializable
data class CompareCell(val value: Double?, val n: Int, val hidden: Boolean)

/** The two people of a comparison, in request order. */
@Serializable
enum class CompareSide { A, B }

/**
 * What two cells say about each other. [difference] = B's value minus A's (a positive number means B is higher), present whenever
 * both values are. [better] names the better side, `null` when the row is neutral, a side is under `minSampleSize` or has no value,
 * or the two are a tie within the row's tolerance; [margin] (never negative) is by how much, in the row's unit -- the value gap for
 * HIGHER/LOWER, the gap between the two distances to the target for TARGET -- and is present exactly when [better] is.
 */
internal data class CompareVerdict(val better: CompareSide?, val difference: Double?, val margin: Double?)

private const val EPSILON = 1e-9

/**
 * The verdict of [a] against [b] for [spec]; [minSampleSize] is the unit-wide `metrics.settings.min_sample_size`, which takes the cue
 * away from a side under it on every [CompareRowSpec.sampleGated] row (not on the pure item counts).
 */
internal fun compareCells(spec: CompareRowSpec, a: CompareCell, b: CompareCell, minSampleSize: Int): CompareVerdict =
    compareValues(
        spec.direction, spec.unit, spec.target, a.value?.takeUnless { a.hidden }, b.value?.takeUnless { b.hidden },
        a.n, b.n, minSampleSize,
    )

/**
 * The verdict of two bare values (`null` = hidden or undefined): [compareCells] without the cell wrapper, and the rule the Home
 * overview applies to a figure against the same figure over the previous window (A = previous, B = current). [target] goes with
 * [CompareDirection.TARGET] only; the sample gate is [CompareUnit.ITEMS]-exempt exactly as [CompareRowSpec.sampleGated] says.
 */
internal fun compareValues(
    direction: CompareDirection,
    unit: CompareUnit,
    target: Double?,
    aValue: Double?,
    bValue: Double?,
    aN: Int,
    bN: Int,
    minSampleSize: Int,
): CompareVerdict {
    if (aValue == null || bValue == null) return CompareVerdict(null, null, null)
    val difference = bValue - aValue
    val sampleOk = unit == CompareUnit.ITEMS || (aN >= minSampleSize && bN >= minSampleSize)
    val comparable = direction != CompareDirection.NEUTRAL && sampleOk
    if (!comparable) return CompareVerdict(null, difference, null)
    val (score, gap) = when (direction) {
        // A positive score means B is better: the gap in the better direction.
        CompareDirection.HIGHER -> difference to abs(difference)
        CompareDirection.LOWER -> -difference to abs(difference)
        CompareDirection.TARGET -> (abs(aValue - target!!) - abs(bValue - target)).let { it to abs(it) }
        CompareDirection.NEUTRAL -> error("unreachable: NEUTRAL rows are not comparable")
    }
    if (gap <= unit.tolerance + EPSILON) return CompareVerdict(null, difference, null)
    return CompareVerdict(if (score > 0) CompareSide.B else CompareSide.A, difference, gap)
}

/** The mean of [countsByDay] over [workingDays] (a day with no entry counts 0), `null` for no working day. */
internal fun meanOverWorkingDays(countsByDay: Map<LocalDate, Int>, workingDays: List<LocalDate>): Double? =
    if (workingDays.isEmpty()) null else workingDays.sumOf { countsByDay[it] ?: 0 }.toDouble() / workingDays.size
