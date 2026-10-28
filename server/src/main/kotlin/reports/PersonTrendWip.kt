package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import java.time.LocalDate
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/*
 * Person trend's WIP (report 20): the person's WIP over EVERY window from the few statements a SINGLE Compare-people WIP read would run,
 * not one read per window. Compare people's `fetchCompareWip` plans the days of its period ([planSnapshotDays]: the DERIVE cut-off and
 * the backfill start apply), counts the tasks held per day, lists the tasks held at the LAST listed day and reads their `started_at`.
 * Each of those is a per-day (or per-task) fact that does not depend on the period, so reading it once over the whole range and cutting
 * it to a window is the same answer: a window's days are the plan's days inside it, its per-day counts the same map, its last-day tasks
 * the rows of its last listed day.
 */

/**
 * The WIP of the person and their reference peers over the range of the whole report; [forWindow] cuts it to one window, as
 * `fetchCompareWip` would have read it.
 */
internal class TrendWip(
    private val plan: SnapshotPlan,
    private val calendar: WorkingCalendar,
    private val connectionIds: List<UInt>,
    private val dayCounts: Map<String, Map<LocalDate, Int>>,
    private val atEndByDay: Map<String, List<WipTaskDay>>,
    private val startedAt: Map<Pair<UInt, Long>, Long?>,
) {
    /** Why the series is empty or partial over the whole range (a window-level note is the same text or less). */
    val note: String? get() = plan.note

    /** The [CompareWip] of [window]: exactly what `fetchCompareWip` returns for a request whose period is that window. */
    fun forWindow(window: TrendWindow): CompareWip {
        val days = plan.days.filter { !it.isBefore(window.from) && !it.isAfter(window.end) }
        if (days.isEmpty()) return CompareWip(emptyList(), emptyList(), emptyList(), emptyMap(), emptyList(), emptyMap(), plan.note)
        return CompareWip(
            days = days,
            workingDays = days.filter { calendar.isWorkingDay(it) },
            connectionIds = connectionIds,
            dayCounts = dayCounts,
            atEnd = atEndByDay[days.last().toString()].orEmpty(),
            startedAt = startedAt,
            note = plan.note,
        )
    }
}

/**
 * Reads the WIP of [accounts] (the person and their reference peers) for [windows] inside the caller's report transaction. [scope] carries
 * the WHOLE range's window (the first window's start to the last window's end); [derived] are the scope's connections with a successful
 * DERIVE (the only ones read, like the WIP report's USER level). Three statements however many windows: the per-day counts over the
 * plan's days, the tasks held on the last listed day of each window (`WipTaskDaysRequest.days`), and the `started_at` of those tasks.
 */
internal suspend fun R2dbcTransaction.readTrendWip(
    scope: ReportScope,
    filter: ReportFilter,
    windows: List<TrendWindow>,
    derived: List<UInt>,
    accounts: List<String>,
): TrendWip {
    val plan = planSnapshotDays(scope, filter, scope.calendar, userNote = null, startAtBackfill = true)
    val empty = TrendWip(plan, scope.calendar, emptyList(), emptyMap(), emptyMap(), emptyMap())
    if (plan.days.isEmpty()) return empty
    val request = WipTaskDaysRequest(derived, accounts, plan.days.first().toString(), plan.days.last().toString())
    val lastDays = windows.mapNotNull { window ->
        plan.days.lastOrNull { !it.isBefore(window.from) && !it.isAfter(window.end) }?.toString()
    }.distinct()
    val atEnd = if (lastDays.isEmpty()) emptyList() else readWipUserTaskDays(request.copy(days = lastDays))
    return TrendWip(
        plan = plan,
        calendar = scope.calendar,
        connectionIds = derived,
        dayCounts = readWipUserDayCounts(request),
        atEndByDay = atEnd.groupBy { it.day },
        startedAt = startedAtOf(atEnd.map { it.connectionId to it.issueId }.distinct()),
    )
}
