package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/** One calendar day of [BacklogReport.trend]: the estimated backlog at the END of that day. */
@Serializable
data class BacklogTrendPoint(val day: String, val items: Int, val md: Double)

/**
 * The backlog on the period's last listed day ([asOfDay]) and how far ahead it reaches (report 13):
 * [backlogInSprints] = [md] ÷ [meanDeliveredMd], the mean `delivered_md` of the team's last [windowSprints] closed
 * sprints as of that day ([sprintsUsed] of them, when fewer exist — at UNIT level the MINIMUM across the teams that have
 * closed a sprint, so "some team has fewer than N" is detectable). `null` = not defined (no closed sprint, a mean
 * of 0, or a scope with no velocity of its own: a domain slice, or the UNOWNED backlog).
 */
@Serializable
data class BacklogCurrent(
    val asOfDay: String?,
    val items: Int,
    val md: Double,
    val meanDeliveredMd: Double?,
    val windowSprints: Int,
    val sprintsUsed: Int,
    val backlogInSprints: Double?,
)

@Serializable
data class BacklogReport(
    val meta: ReportMeta,
    val current: BacklogCurrent,
    /** One point per calendar day of the period (cut off after the last derived day), zero-filled, oldest first. */
    val trend: List<BacklogTrendPoint>,
    /**
     * Why the report is empty or partial (USER level, not derived yet, no sprint resolved, connections left out of the
     * cut-off); `null` otherwise.
     */
    val note: String?,
)

private const val USER_LEVEL_NOTE = "The estimated backlog is not stored per user: the daily aggregate has only team and domain scopes"

/** A day's summed backlog snapshot. */
private data class BacklogDay(val items: Int, val md: BigDecimal)

/**
 * `GET /api/v1/reports/backlog` (v0.3.0 M5 commit 15, Reports 10 and 13, `.claude/docs/measures.md` "Reports 10, 13"):
 * the estimated backlog (D9) as a daily trend off `agg_daily_flow.backlog_items/_md` (end-of-day snapshots, a missing
 * row is zero), `current` its last day, and the backlog in sprints against the owner team's recent delivery. See
 * `.claude/docs/reports.md` "Reports 9, 10, 13".
 */
suspend fun ReportService.backlog(filter: ReportFilter, nowMs: Long): BacklogReport = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val snapshotScope = snapshotScopeOf(filter)
    val calendar = WorkingCalendar.of(scope.settings)
    val plan = planSnapshotDays(scope, filter, calendar, USER_LEVEL_NOTE)
    val days = plan.days
    val perDay = if (days.isEmpty()) {
        emptyMap()
    } else {
        fetchBacklog(scope.connectionIds, snapshotScope, days.first().toString(), days.last().toString())
    }
    val trend = days.map { day ->
        val cell = perDay[day.toString()]
        BacklogTrendPoint(day.toString(), cell?.items ?: 0, cell?.md?.toDouble() ?: 0.0)
    }
    val last = trend.lastOrNull()
    val asOfEndMs = days.lastOrNull()?.let { calendar.dayBoundsMs(it).second }
    val (mean, used) = if (asOfEndMs == null) {
        null to 0
    } else {
        meanDelivered(snapshotScope, scope.connectionIds, scope.settings.backlogWindowSprints, asOfEndMs)
    }
    val current = BacklogCurrent(
        asOfDay = last?.day,
        items = last?.items ?: 0,
        md = last?.md ?: 0.0,
        meanDeliveredMd = mean,
        windowSprints = scope.settings.backlogWindowSprints,
        sprintsUsed = used,
        backlogInSprints = if (last != null && mean != null && mean > 0.0) last.md / mean else null,
    )
    BacklogReport(scope.meta.forTaskDomain(), current, trend, plan.note)
}

private suspend fun fetchBacklog(
    connectionIds: List<UInt>,
    scope: SnapshotScope,
    firstDay: String,
    lastDay: String,
): Map<String, BacklogDay> {
    if (connectionIds.isEmpty()) return emptyMap()
    val f = MetricsTables.AggDailyFlow
    val items = f.backlogItems.sum()
    val md = f.backlogMd.sum()
    return f.select(f.day, items, md)
        .where {
            (f.connectionId inList connectionIds) and flowScopePredicate(scope) and (f.day greaterEq firstDay) and (f.day lessEq lastDay)
        }
        .groupBy(f.day)
        .toList().associate { it[f.day] to BacklogDay(it[items] ?: 0, it[md] ?: BigDecimal.ZERO) }
}

/**
 * The mean `delivered_md` of the last [window] closed sprints of each team the [scope] covers, as of [asOfEndMs]
 * (a sprint counts once its `complete_at` is before it): one team's own mean at TEAM level, the SUM of every team's
 * mean at UNIT level (the unit's delivery per sprint). Also the number of sprints averaged — one team's own count, at UNIT
 * level the MINIMUM across the teams that have closed a sprint. `(null, 0)` when no closed sprint exists — and always
 * for a scope with no velocity of its own (a domain slice, the UNOWNED backlog).
 */
private suspend fun meanDelivered(scope: SnapshotScope, connectionIds: List<UInt>, window: Int, asOfEndMs: Long): Pair<Double?, Int> {
    if (scope is SnapshotScope.Domain || scope is SnapshotScope.Unassigned || connectionIds.isEmpty()) return null to 0
    val s = MetricsTables.FactSprint
    var predicate = (s.connectionId inList connectionIds) and s.teamId.isNotNull() and
        s.completeAt.isNotNull() and (s.completeAt less asOfEndMs)
    if (scope is SnapshotScope.Team) predicate = predicate and (s.teamId eq scope.teamId)
    val byTeam = s.select(s.teamId, s.deliveredMd)
        .where { predicate }
        .orderBy(s.teamId to SortOrder.ASC, s.completeAt to SortOrder.DESC)
        .toList()
        .groupBy { it[s.teamId]!!.value }
        .values.map { rows -> rows.take(window).map { it[s.deliveredMd] } }
    if (byTeam.isEmpty()) return null to 0
    return byTeam.sumOf { delivered -> delivered.sumOf { it.toDouble() } / delivered.size } to byTeam.minOf { it.size }
}
