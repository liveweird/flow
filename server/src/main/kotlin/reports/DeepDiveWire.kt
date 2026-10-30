package ch.nokillswit.reports

import ch.nokillswit.metrics.HistoryHorizon
import kotlinx.serialization.Serializable

// The wire shapes of report 17, the Deep dive (`.claude/docs/reports.md` "Report 17", A29, A40). Every series is SPARSE and every day an
// OFFSET in days from `range.from`; the server serializes every property, so an absent optional is a JSON null. Kept apart from
// `DeepDiveReport.kt` (the assembly) so that file stays the one place the figures are computed.

/** Which selection ran. */
@Serializable
enum class DeepDiveMode { SPRINTS, EPICS }

/**
 * The calendar window the day offsets count from ([from], an ISO date) to [to]; [asOfDay] = the day of the SELECTED connection's last
 * DERIVE clock (where an open in-progress interval stops; `null`: nothing derived).
 */
@Serializable
data class DeepDiveRange(val from: String, val to: String, val asOfDay: String?)

/** A selected sprint (mode SPRINTS): its plan window as day offsets from `range.from` (`null` when it contributes no day). */
@Serializable
data class DeepDiveSprint(val id: Long, val name: String, val startDay: Int?, val endDay: Int?)

/** A cost entry's author: [cost entries][DeepDiveCostDay] point at an index into `authors`. */
@Serializable
data class DeepDiveAuthor(val accountId: String, val displayName: String)

/** A sparse plan or Approximate EV entry: day offset [d] from `range.from` and the man-days [md] on it. */
@Serializable
data class DeepDiveMdDay(val d: Int, val md: Double)

/** A sparse cost entry: day offset [d], the author's index [a] into `authors` (`null` = Jira gave no author) and the man-days [md]. */
@Serializable
data class DeepDiveCostDay(val d: Int, val a: Int?, val md: Double)

/** The EV marker: day offset [d] of `done_at` and [evMd] = `estimate_at_done_md` (0 when unestimated). */
@Serializable
data class DeepDiveDone(val d: Int, val evMd: Double)

/** Where a task's plan estimate came from (A29). */
@Serializable
enum class DeepDivePlanSource { EARLIEST, LATER_FALLBACK, NONE }

/** Why a task has no PV (A29). */
@Serializable
enum class DeepDiveNoPlanReason { NEVER_IN_SPRINT, NO_ESTIMATE, NO_WORKING_DAY }

/**
 * Each layer's FULL sum over all days, in or out of `range`. Approximate EV has no figure of its own: it is a split of [evMd] (A40), so
 * its whole-life sum IS [evMd].
 */
@Serializable
data class DeepDiveTotals(val pvMd: Double, val evMd: Double, val costMd: Double)

/** A task's Approximate EV state (A40): [EARNED] (done with an estimate), [OPEN] (not done yet) or [UNESTIMATED] (done, no estimate). */
@Serializable
enum class DeepDiveEvState { EARNED, OPEN, UNESTIMATED }

/**
 * One task row: [key] is the issue key, [epicKey] null for the `(no epic)` row; `pv` sums to [planBasisMd] when [noPlanReason] is null.
 * [approxEv] is the task's EV spread over its in-progress working days (A40) and [inProgress] those days (range-clipped offsets, also for
 * a task that earns nothing); both cover the range only while [totals] stay whole-life.
 */
@Serializable
data class DeepDiveTask(
    val key: String,
    val summary: String?,
    val epicKey: String?,
    val planBasisMd: Double?,
    val planSource: DeepDivePlanSource,
    val noPlanReason: DeepDiveNoPlanReason?,
    val pv: List<DeepDiveMdDay>,
    val approxEv: List<DeepDiveMdDay>,
    val inProgress: List<Int>,
    val evState: DeepDiveEvState,
    val done: DeepDiveDone?,
    val cost: List<DeepDiveCostDay>,
    val totals: DeepDiveTotals,
)

/** The worklog MD logged on an epic itself (mode EPICS): the clipped [cost] series and the whole-life [totalMd]. */
@Serializable
data class DeepDiveOwnCost(val cost: List<DeepDiveCostDay>, val totalMd: Double)

/**
 * One epic row. [key] absent = the `(no epic)` row. [plannedStart]/[plannedDue] are the CURRENT `fact_epic_plan` baseline's window as day
 * offsets from `range.from` (an outline, never summed; absent without a complete window) and [budgetMd] its budget.
 */
@Serializable
data class DeepDiveEpic(
    val key: String?,
    val summary: String?,
    val plannedStart: Int?,
    val plannedDue: Int?,
    val budgetMd: Double?,
    val ownCost: DeepDiveOwnCost?,
)

/** What the page states openly as its limits (A29), counted over ALL selected tasks. */
@Serializable
data class DeepDiveQuality(
    val neverInSprint: Int,
    val noEstimate: Int,
    val noWorkingDay: Int,
    val epicsWithoutWindow: Int,
    val laterFallback: Int,
    /** OPEN tasks with at least one in-progress working day (whole-life): worked, nothing earned yet (A40). */
    val openInProgress: Int,
    /** Tasks done with no or a zero estimate: nothing to earn (A40). */
    val doneUnestimated: Int,
    val epicOwnCostMd: Double,
)

@Serializable
data class DeepDiveReport(
    val meta: ReportMeta,
    val mode: DeepDiveMode,
    val range: DeepDiveRange,
    /** Day offsets from `range.from` that are not working days. */
    val nonWorkingDays: List<Int>,
    val sprints: List<DeepDiveSprint>,
    val authors: List<DeepDiveAuthor>,
    val epics: List<DeepDiveEpic>,
    val tasks: List<DeepDiveTask>,
    val quality: DeepDiveQuality,
    val note: String?,
)

/**
 * The deep dive's `meta`: the DERIVE [stamp] over the connections in scope, the answer's [range] and the request's A48 [horizon]
 * (`null` = off). Level UNIT, task view, no resolved sprints: the report has its own selection, not the shared filter.
 */
internal fun deepDiveMeta(stamp: DeriveStamp, range: DeepDiveRange, minSampleSize: Int, horizon: HistoryHorizon?) = ReportMeta(
    derivedAt = stamp.derivedAt, configRevision = stamp.configRevision, from = range.from, to = range.to,
    level = ReportLevel.UNIT, domainView = DomainView.TASK, resolvedSprints = emptyList(), minSampleSize = minSampleSize,
    historyHorizon = horizon?.toWire(),
)
