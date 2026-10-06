package ch.nokillswit.reports

import ch.nokillswit.metrics.MD_SCALE
import ch.nokillswit.metrics.WorkingCalendar
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import java.util.TreeSet

// The pure (no DB) kernels of report 17, the Deep dive (A29, `.claude/docs/domain-model.md`; per-measure rows in
// `.claude/docs/measures.md` "Report 17"): a task's plan spread, its execution task-days and its cost per author, each as a SPARSE daily
// series. Every day is an OFFSET in days from `origin` (the report's `range.from`, a date in the configured zone): it may be negative or
// run past the range's end, because clipping to the range is the caller's job (clipped data still counts in a task's totals). Rounding
// beyond the MD scale each kernel documents is the caller's job as well. `spreadCumulative`/`cumulativeSplit` live here, not in
// `EpicProgressEvm.kt`, because both reports use them and this is the pure-kernels file; report 15 reaches them through the package.

// ---- cumulative-rounding split (shared with report 15) -----------------------------------------------

/**
 * The running total at the end of each of [count] equal slots of [total]: `ROUND(total * i / count, 2)` half-up for `i = 1..count` — the
 * rule DERIVE's stored `pv_md` increments follow, so the last value is [total] rounded to [MD_SCALE] and the split never drifts. Empty
 * when [count] is not positive (nowhere to place [total]).
 */
internal fun cumulativeSplit(total: BigDecimal, count: Int): List<BigDecimal> {
    if (count <= 0) return emptyList()
    val slots = BigDecimal(count)
    return (1..count).map { total.multiply(BigDecimal(it)).divide(slots, MD_SCALE, RoundingMode.HALF_UP) }
}

/**
 * [total] spread over [days] by cumulative rounding: each day's value is the DIFFERENCE of two consecutive [cumulativeSplit] values, so
 * the values sum to [total] (rounded to [MD_SCALE]) exactly. 1 MD over 3 days is `0.33, 0.34, 0.33` (running `0.33, 0.67, 1.00`).
 */
internal fun <D> spreadCumulative(total: BigDecimal, days: List<D>): List<Pair<D, BigDecimal>> {
    var previous = BigDecimal.ZERO.setScale(MD_SCALE)
    return cumulativeSplit(total, days.size).mapIndexed { index, running ->
        (days[index] to running - previous).also { previous = running }
    }
}

// ---- shapes -------------------------------------------------------------------------------------------

/** One entry of a sparse series: the day (offset from the origin) and the man-days on it. */
internal data class DayAmount(val offset: Int, val md: BigDecimal)

/** One entry of the execution series: the day (offset from the origin) and the fraction of a working day spent in progress on it. */
internal data class DayFraction(val offset: Int, val taskDays: Double)

/** One cost entry: the day, the author (the worklog's account id; `null` when Jira gave none) and their summed man-days on it. */
internal data class AuthorDayCost(val offset: Int, val author: String?, val md: BigDecimal)

/**
 * One sprint the task was `in_scope_at_close` in (A17: committed or added; removed scope never reaches here). Its window is
 * `dayOf(start_at ?: close)`..`dayOf(close)` with `close = complete_at ?: end_at` — a sprint with no start is a one-day window on its
 * close (as `periodWindow` treats it), one with no close at all contributes no day; its [commitmentMd] still takes part in the
 * estimate choice either way.
 */
internal class SprintWindow(
    val startAtMs: Long?,
    val completeAtMs: Long?,
    val endAtMs: Long?,
    /** `fact_sprint_scope.estimate_at_commitment_md`; `null` = no estimate then. */
    val commitmentMd: BigDecimal?,
)

/** Where a task's plan estimate came from. */
internal enum class PlanSource { EARLIEST, LATER_FALLBACK, NONE }

/** Why a task has no PV. */
internal enum class NoPlanReason { NEVER_IN_SPRINT, NO_ESTIMATE, NO_WORKING_DAY }

/**
 * A task's plan: [basisMd] and [source] are the estimate chosen, whenever there is one — also when [noPlanReason] is
 * [NoPlanReason.NO_WORKING_DAY] (an estimate with nowhere to go, so [pv] is empty). Only a task without an estimate has source NONE and
 * no basis. Whenever [noPlanReason] is null, [pv] sums to [basisMd] exactly.
 */
internal class TaskPlan(
    val basisMd: BigDecimal?,
    val source: PlanSource,
    val noPlanReason: NoPlanReason?,
    val pv: List<DayAmount>,
) {
    companion object {
        fun none(reason: NoPlanReason) = TaskPlan(null, PlanSource.NONE, reason, emptyList())
    }
}

/** One `item_stage` interval of stage IN_PROGRESS: `[fromMs, toMs)`, `toMs` null while still open. The caller filters by stage. */
internal class StageSpan(val fromMs: Long, val toMs: Long?)

/** One `fact_worklog` row reduced to what the cost kernel reads. */
internal class CostEntry(val startedAtMs: Long, val author: String?, val md: BigDecimal)

// ---- A29 kernels --------------------------------------------------------------------------------------

/** [day]'s offset from [origin] in whole calendar days (negative before it). */
internal fun dayOffset(origin: LocalDate, day: LocalDate): Int = ChronoUnit.DAYS.between(origin, day).toInt()

/** A window longer than this many days (the report's range cap) is clamped to its first that many days. */
internal const val MAX_WINDOW_DAYS = 1100

/**
 * A sprint's plan window in [calendar]'s zone, inclusive: `dayOf(start ?: close)`..`dayOf(close)` with `close = complete ?: end`, clamped
 * to its first [MAX_WINDOW_DAYS] days. `null` when the sprint has no close or the window is empty (a close before its start): it
 * contributes no day. The ONE statement of the rule: [planDays] walks it, the report's range envelope and sprint marks read it.
 */
internal fun sprintWindow(
    startMs: Long?,
    completeMs: Long?,
    endMs: Long?,
    calendar: WorkingCalendar,
): Pair<LocalDate, LocalDate>? {
    val close = completeMs ?: endMs ?: return null
    val first = calendar.dayOf(startMs ?: close)
    val last = minOf(calendar.dayOf(close), first.plusDays(MAX_WINDOW_DAYS - 1L))
    return if (first.isAfter(last)) null else first to last
}

/**
 * The working days of the UNION of [windows] (A29), ascending: each window's days are [sprintWindow]'s (the one owner of that rule), and a
 * day in two windows is counted once. Depends only on the windows and the calendar, so the report layer can memoize it per distinct
 * window set.
 */
internal fun planDays(windows: List<SprintWindow>, calendar: WorkingCalendar): List<LocalDate> {
    val days = TreeSet<LocalDate>()
    for (window in windows) {
        val (first, last) = sprintWindow(window.startAtMs, window.completeAtMs, window.endAtMs, calendar) ?: continue
        var day = first
        while (!day.isAfter(last)) {
            if (calendar.isWorkingDay(day)) days += day
            day = day.plusDays(1)
        }
    }
    return days.toList()
}

/**
 * A29 "Plan (PV), per task". [windows] are the sprints the task was in scope at close in, EARLIEST FIRST (the caller orders them).
 * The estimate is the earliest sprint's `estimate_at_commitment_md`; when that is null, the first LATER sprint that has one
 * ([PlanSource.LATER_FALLBACK]). It is spread by [spreadCumulative] over [planDays], so the entries sum to the estimate exactly. No
 * window is [NoPlanReason.NEVER_IN_SPRINT]; none with an estimate is [NoPlanReason.NO_ESTIMATE] (source NONE); an estimate with no
 * working day in the union keeps its basis and source with an empty series and [NoPlanReason.NO_WORKING_DAY]. A zero estimate is an
 * estimate: it has a basis and an empty series with no reason (sparse series drop zero entries, so a split's zero days are absent too).
 * [daysOf] defaults to [planDays]; the report layer passes a memoizing one, since it depends only on the window set.
 */
internal fun taskPlan(
    windows: List<SprintWindow>,
    calendar: WorkingCalendar,
    origin: LocalDate,
    daysOf: (List<SprintWindow>) -> List<LocalDate> = { planDays(it, calendar) },
): TaskPlan {
    if (windows.isEmpty()) return TaskPlan.none(NoPlanReason.NEVER_IN_SPRINT)
    val earliest = windows.first().commitmentMd
    val (basis, source) = if (earliest != null) {
        earliest to PlanSource.EARLIEST
    } else {
        val later = windows.drop(1).firstNotNullOfOrNull { it.commitmentMd }
        if (later == null) return TaskPlan.none(NoPlanReason.NO_ESTIMATE)
        later to PlanSource.LATER_FALLBACK
    }
    val days = daysOf(windows)
    if (days.isEmpty()) return TaskPlan(basis, source, NoPlanReason.NO_WORKING_DAY, emptyList())
    val pv = spreadCumulative(basis, days)
        .filter { (_, md) -> md.signum() != 0 }
        .map { (day, md) -> DayAmount(dayOffset(origin, day), md) }
    return TaskPlan(basis, source, null, pv)
}

/**
 * A29 "Execution, per task": per day, the fraction of a working day the task spent in [spans] — the unit of cycle time. An OPEN span is
 * cut at [deriveClockMs] (the connection's last DERIVE clock); a span starting at or after it contributes nothing. Each
 * day is [WorkingCalendar.workingDaysBetween] over the span's slice of that day, so a DST day's real length is honoured, a non-working
 * day is absent, and the values over a span sum to `workingDaysBetween(from, to)` (spans sharing a day add up on it). Ascending by offset.
 */
internal fun executionDays(spans: List<StageSpan>, deriveClockMs: Long, calendar: WorkingCalendar, origin: LocalDate): List<DayFraction> {
    val perDay = TreeMap<LocalDate, Double>()
    for (span in spans) {
        val toMs = span.toMs ?: deriveClockMs
        if (toMs <= span.fromMs) continue
        var day = calendar.dayOf(span.fromMs)
        while (true) {
            val (dayStartMs, dayEndMs) = calendar.dayBoundsMs(day)
            if (dayStartMs >= toMs) break
            val fraction = calendar.workingDaysBetween(maxOf(dayStartMs, span.fromMs), minOf(dayEndMs, toMs))
            if (fraction > 0.0) perDay.merge(day, fraction, Double::plus)
            day = day.plusDays(1)
        }
    }
    return perDay.map { (day, taskDays) -> DayFraction(dayOffset(origin, day), taskDays) }
}

/**
 * A29 "Cost (AC)": each worklog's MD on `dayOf(started_at)` in [calendar]'s zone (the zone every period filter uses — a worklog at
 * 23:30 UTC is the NEXT day in Warsaw), summed per day and author, exactly (no rounding here). A multi-day worklog still lands on its
 * start day. A worklog on a non-working day is kept: cost is what was logged, not what was planned. Ascending by offset, then author
 * (none first).
 */
internal fun costDays(worklogs: List<CostEntry>, calendar: WorkingCalendar, origin: LocalDate): List<AuthorDayCost> {
    val sums = HashMap<Pair<Int, String?>, BigDecimal>()
    for (worklog in worklogs) {
        sums.merge(dayOffset(origin, calendar.dayOf(worklog.startedAtMs)) to worklog.author, worklog.md, BigDecimal::add)
    }
    return sums.entries
        .sortedWith(
            compareBy<Map.Entry<Pair<Int, String?>, BigDecimal>> { it.key.first }.thenBy(nullsFirst()) { it.key.second },
        )
        .map { (key, md) -> AuthorDayCost(key.first, key.second, md) }
}
