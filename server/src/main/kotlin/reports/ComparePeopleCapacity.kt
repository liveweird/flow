package ch.nokillswit.reports

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Report 18's per-person capacity (`.claude/docs/reports.md` "Report 18", domain-model A39 / A41): the man-days each of the two people
 * had available in the period, ACROSS every team they belonged to. Each team's capacity is the one per-person kernel
 * ([loadCostCapacity], `CostCapacity.kt`: a stored sprint calendar where one owns the day, one man-day per member on a working day
 * otherwise); a person's days are then those of the team a membership of theirs covers, and a day two teams both cover (the
 * transfer day) counts ONCE, at the larger figure.
 * Computed only for a caller who may see it (the route's rule: an ADMIN, or the active manager of EVERY team either person belonged
 * to in the period) -- never computed-then-stripped.
 */

/**
 * Who may see per-person capacity (A41), decided by the route and enforced AGAIN inside the report's own transaction: [Admin] always,
 * [None] never, [Teams] only while every team either person belonged to in the period is still in [Teams.teamIds] -- the set the route
 * checked the caller manages. A membership added between the route's check and the report's read therefore drops the capacity, never
 * leaks it.
 */
internal sealed interface CapacityGrant {
    data object Admin : CapacityGrant

    data object None : CapacityGrant

    data class Teams(val teamIds: Set<UInt>) : CapacityGrant
}

/** The verdict as ETag text (`ReportValidators.kt`): Compare people is the one report whose body depends on who asks. */
internal fun CapacityGrant.etagShape(): List<String> = listOf(
    when (this) {
        CapacityGrant.Admin -> "capacity:admin"
        CapacityGrant.None -> "capacity:none"
        is CapacityGrant.Teams -> "capacity:teams:" + teamIds.sorted().joinToString(",")
    },
)

/** One dated membership of one picked person (`metrics.team_membership`). */
internal data class PersonMembership(val accountId: String, val teamId: UInt, val validFrom: Long, val validTo: Long?) {
    /** Whether the membership covers any instant of [dayStartMs, dayEndMs): the A3 member predicate. */
    fun coversDay(dayStartMs: Long, dayEndMs: Long): Boolean = validFrom < dayEndMs && (validTo == null || validTo > dayStartMs)
}

/** The memberships of [accountIds] overlapping `[fromMs, toMs)`, whatever the team. */
internal suspend fun readPeopleMemberships(accountIds: List<String>, fromMs: Long, toMs: Long): List<PersonMembership> {
    val m = TeamMembershipService.TeamMembership
    val overlapping = (m.validFrom less toMs) and (m.validTo.isNull() or (m.validTo greater fromMs))
    return m.select(m.accountId, m.teamId, m.validFrom, m.validTo).where { (m.accountId inList accountIds) and overlapping }.toList()
        .map { PersonMembership(it[m.accountId], it[m.teamId].value, it[m.validFrom], it[m.validTo]) }
}

/** The capacity days of the period: its calendar days up to and including today. */
internal fun comparePeopleCapacityDays(calendar: WorkingCalendar, window: Pair<Long, Long>, nowMs: Long): List<LocalDate> {
    val today = calendar.dayOf(nowMs)
    return periodDaysOf(calendar, window.first, window.second).takeWhile { !it.isAfter(today) }
}

/**
 * Each of [accountIds]'s exact available man-days over [window] and the number of days they had any (a person with none is absent):
 * per team of [memberships] the team's capacity ([loadCostCapacity]) restricted to the days a membership of THAT team covers,
 * merged per day by the larger figure so a transfer day is counted once.
 */
internal suspend fun loadPeopleCapacity(
    memberships: List<PersonMembership>,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    calendar: WorkingCalendar,
    nowMs: Long,
): Map<String, ComparePersonCapacity> {
    val byDay = HashMap<String, MutableMap<LocalDate, BigDecimal>>()
    for ((teamId, ofTeam) in memberships.groupBy { it.teamId }) {
        val capacity = loadCostCapacity(teamId, connectionIds, window, calendar, nowMs)
        for ((account, own) in ofTeam.groupBy { it.accountId }) {
            val perDay = capacity.perPersonByDay[account] ?: continue
            mergeCoveredDays(byDay.getOrPut(account) { HashMap() }, perDay, own, calendar)
        }
    }
    return byDay.mapValues { (_, days) -> ComparePersonCapacity(days.values.fold(BigDecimal.ZERO, BigDecimal::add), days.size) }
}

/** Adds to [into] the days of [perDay] that a membership of [own] covers, a day already there keeping the larger figure. */
private fun mergeCoveredDays(
    into: MutableMap<LocalDate, BigDecimal>,
    perDay: Map<LocalDate, BigDecimal>,
    own: List<PersonMembership>,
    calendar: WorkingCalendar,
) {
    for ((day, md) in perDay) {
        val (start, end) = calendar.dayBoundsMs(day)
        if (own.any { it.coversDay(start, end) }) into.merge(day, md, ::maxOf)
    }
}

/**
 * The teams either picked person belonged to during the filter's period (a `from`/`to` period; compare-people takes no other):
 * what the route checks the caller's management of. Opens its own transaction -- the route evaluates it BEFORE the report read, like
 * the capacity visibility rule, so the report only computes capacity for a caller who may see it.
 */
internal suspend fun ReportService.comparePeopleTeamIds(filter: ReportFilter): Set<UInt> {
    val period = filter.period as ReportPeriod.DateRange
    return reportTransaction { readPeopleMemberships(filter.accountIds, period.fromMs, period.toMs).map { it.teamId }.toSet() }
}
