package ch.nokillswit.reports

import kotlinx.serialization.Serializable

/*
 * The wire shapes of `GET /api/v1/reports/home` (`.claude/docs/reports.md` "Home overview"): one response, every active team, every
 * 90-day figure beside the same figure over the 90 days before. No behaviour here; `HomeKernels.kt` is the pure half.
 */

/** Which side of a figure's previous-window comparison is better; null when it is a tie, a side is hidden or the figure is neutral. */
@Serializable
enum class HomeVerdict { BETTER, WORSE }

/** An inclusive ISO-date window in the configured zone. */
@Serializable
data class HomeWindow(val from: String, val to: String)

/** One active connection's last successful ingest (SYNC or RECONCILE) and last successful DERIVE; `null` = never. */
@Serializable
data class HomeFreshness(val connectionId: UInt, val name: String, val lastSyncAt: Long?, val lastDeriveAt: Long?)

/**
 * The same figure over the previous window. [verdict] is the current figure against it by the figure's direction
 * (`compareValues`, Compare people's rule: sample-gated, a tie within the unit's tolerance reads as none); [difference] = current −
 * previous, present when both values are.
 */
@Serializable
data class HomePrevious(val value: Double?, val n: Int, val hidden: Boolean, val verdict: HomeVerdict?, val difference: Double?)

/**
 * One 90-day figure. [n] is the sample behind it, [hidden] = under `minSampleSize` (then [value] is `null`, like a `Distribution`),
 * [numerator] the items within a PRED share (the drill's `bar.count`). [previous] is `null` on a figure that has no trend.
 */
@Serializable
data class HomeFigure(val value: Double?, val n: Int, val hidden: Boolean, val numerator: Int?, val previous: HomePrevious?)

/** A team's last closed sprint (section 1), its live figures beside the frozen snapshot. */
@Serializable
data class HomeSprint(
    val sprintId: Long,
    val name: String,
    val startAt: Long?,
    val completedAt: Long,
    val capacityMd: Double?,
    val capacitySource: String?,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val snapshot: SprintFigures?,
    val drift: Boolean,
)

/** Section 2: the median estimate at done (MD), cycle (working days) and logged cost (MD) of the tasks done in the window. */
@Serializable
data class HomeGranularity(val estimateMd: HomeFigure, val cycleWorkingDays: HomeFigure, val costMd: HomeFigure)

/** The share of items within 10 / 25 / 50 % of their actual (PRED, estimate at start). */
@Serializable
data class HomePredLine(val pred10: HomeFigure, val pred25: HomeFigure, val pred50: HomeFigure)

/** Section 3: estimate accuracy, the tasks (credit team) and the epics (owner team) done in the window. */
@Serializable
data class HomeAccuracy(val tasks: HomePredLine, val epics: HomePredLine)

/** Sections 4 and 5: the open items in progress now ([count] always; the age percentiles in working days are hidden under the minimum). */
@Serializable
data class HomeWip(val count: Int, val hidden: Boolean, val p50: Double?, val p85: Double?, val p95: Double?)

/** One week of section 6: `bucketStart` is the Monday, zero-filled. */
@Serializable
data class HomeWeek(val bucketStart: String, val deliveredMd: Double, val deliveredItems: Int)

/** Section 6: the weekly delivery over the window, its totals and the cycle p50/p85. */
@Serializable
data class HomeThroughput(
    val weeks: List<HomeWeek>,
    val deliveredMd: HomeFigure,
    val deliveredItems: HomeFigure,
    val cycleP50: HomeFigure,
    val cycleP85: HomeFigure,
)

/** Section 7: counts of findings. The task ones are done-in-window (with a trend); the epic ones are Data quality's population (none). */
@Serializable
data class HomeQuality(
    val tasksNoEstimate: HomeFigure,
    val tasksUnassigned: HomeFigure,
    val tasksEstimatedLate: HomeFigure,
    val epicsNoEstimate: HomeFigure,
    val epicsNoDates: HomeFigure,
    val epicsEstimateGap: HomeFigure,
)

/**
 * Section 8, one team's Code figures (Report 21's own, `HomeCode.kt`): the merge requests merged in the window, their cycle p50 (working
 * days), the open ones now ([openCount] waiting, [openDrafts] beside; no trend) with their age p50 and the merged-without-review share
 * ([HomeFigure.numerator] = the count, [HomeFigure.n] = the merged merge requests whose details were read; the share is hidden under
 * `minSampleSize`, the count never). [linkedMembers] = the team's people with at least one linked GitLab identity.
 */
@Serializable
data class HomeTeamCode(
    val linkedMembers: Int,
    val merged: HomeFigure,
    val cycleP50: HomeFigure,
    val openCount: HomeFigure,
    val openDrafts: Int,
    val openAgeP50: HomeFigure,
    val mergedWithoutReview: HomeFigure,
)

/**
 * The code layer's side of the overview: the active GitLab [sources] with their freshness (Report 21's `sources[]`; empty = no GitLab
 * source, the section is absent) and [overBudget] (the code read exceeded `reports.codeMaxRows`: every team's `code` is `null`).
 */
@Serializable
data class HomeCode(val sources: List<CodeActivitySource>, val overBudget: Boolean)

@Serializable
data class HomeTeam(
    val teamId: UInt,
    val name: String,
    /** `null` = the team has no closed sprint. */
    val lastSprint: HomeSprint?,
    val granularity: HomeGranularity,
    val accuracy: HomeAccuracy,
    val wipEpics: HomeWip,
    val wipTasks: HomeWip,
    val throughput: HomeThroughput,
    val quality: HomeQuality,
    /** `null` = no GitLab source is active, or the code read was over the row budget (see [HomeCode]). */
    val code: HomeTeamCode?,
)

/** Work of no ACTIVE team (whichever were picked): done tasks in the window, open tasks in progress, epics in the quality set. */
@Serializable
data class HomeUnassigned(val doneTasks: Int, val openTasks: Int, val epics: Int)

/**
 * `GET /api/v1/reports/home`: the overview of the picked (default: first three) active teams, in name order. [window] is the
 * trailing 90 days ending today in [timeZone] (the shared parser's default period, so a link with `from`/`to` reproduces the
 * figures), [previousWindow] the 90 days before it. [meta] has level UNIT, domain view TASK and `resolvedSprints` = each listed team's
 * last closed sprint.
 */
@Serializable
data class HomeOverview(
    val meta: ReportMeta,
    val window: HomeWindow,
    val previousWindow: HomeWindow,
    val timeZone: String,
    val epicEstimateGapTolerancePercent: Int,
    val freshness: List<HomeFreshness>,
    val teams: List<HomeTeam>,
    val unassigned: HomeUnassigned,
    val code: HomeCode,
)

/** The drill views of `GET /api/v1/reports/home/items` -- which set of items one figure of one team lists. */
enum class HomeDrillView {
    TASK_ESTIMATE, TASK_CYCLE, TASK_COST, TASK_PRED10, TASK_PRED25, TASK_PRED50, EPIC_PRED10, EPIC_PRED25, EPIC_PRED50,
    WIP_TASKS, WIP_EPICS, QUALITY_TASKS_NO_ESTIMATE, QUALITY_TASKS_UNASSIGNED, QUALITY_TASKS_ESTIMATED_LATE,
    QUALITY_EPICS_NO_ESTIMATE, QUALITY_EPICS_NO_DATES, QUALITY_EPICS_ESTIMATE_GAP,
    CODE_MERGED, CODE_OPEN, CODE_MERGED_WITHOUT_REVIEW,
}
