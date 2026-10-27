package ch.nokillswit.reports

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * The wire shapes of `GET /api/v1/reports/person-trend` (`.claude/docs/reports.md` "Report 20"): one person's Compare-people cells over
 * rolling 90-day windows, one window every 14 days. No behaviour here; `PersonTrendKernels.kt` is the pure half.
 */

/** How much of a window the data covers: all of it, only part (before the backfill start or past the last derived day) or none. */
@Serializable
enum class TrendCoverage { FULL, PARTIAL, NONE }

/** Which end of a row's range is better: the last point against the first. */
@Serializable
enum class TrendVerdict { BETTER, WORSE }

/** One window of the grid: it covers [from]..[end] (inclusive ISO dates, the configured zone); [coverage] says how much data backs it. */
@Serializable
data class PersonTrendWindow(val end: String, val from: String, val coverage: TrendCoverage)

/** One person's cell of one row over the window ending [end]: Compare people's `{value, n, hidden}` (no [value] when hidden). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PersonTrendPoint(
    val end: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val value: Double? = null,
    val n: Int,
    val hidden: Boolean,
)

/**
 * A row's cue: the first against the last point with a FULL window and a shown value ([fromEnd], [toEnd]). [difference] = last minus
 * first; [verdict] is Compare people's rule for that pair (sample-gated, a tie within the unit's tolerance has none).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PersonTrendCue(
    val fromEnd: String,
    val toEnd: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val verdict: TrendVerdict? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val difference: Double? = null,
)

/** One row: how to read it (Compare people's own fields), its [points] in window order and, when two points qualify, its cue. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PersonTrendRow(
    val key: String,
    val group: CompareGroup,
    val unit: CompareUnit,
    val direction: CompareDirection,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val target: Double? = null,
    val tolerance: Double,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val drill: CompareDrillView? = null,
    val points: List<PersonTrendPoint>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trend: PersonTrendCue? = null,
)

/** One work-mix key in one window: the man-days delivered under it and its share of all the window's delivered man-days. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PersonTrendMixPoint(val end: String, val md: Double, @EncodeDefault(EncodeDefault.Mode.NEVER) val share: Double? = null)

/** One work-mix key over the windows (a point for EVERY window; man-days 0 where the person delivered nothing under it). */
@Serializable
data class PersonTrendMixSeries(val key: String, val points: List<PersonTrendMixPoint>)

/** Context, not a measure: the delivered work by activity type and by work category (`UNCATEGORIZED` = none), as shares per window. */
@Serializable
data class PersonTrendWorkMix(val activityType: List<PersonTrendMixSeries>, val workCategory: List<PersonTrendMixSeries>)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PersonTrendReport(
    /** `from`/`to` are the requested range (the days the points sit on); the first window reaches [windowDays] - 1 days before `from`. */
    val meta: ReportMeta,
    val person: ComparePerson,
    val windowDays: Int,
    val stepDays: Int,
    val windows: List<PersonTrendWindow>,
    val rows: List<PersonTrendRow>,
    val workMix: PersonTrendWorkMix,
    /** Why the WIP rows are empty or partial (not derived yet, a range before the backfill window...); omitted otherwise. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: String? = null,
)
