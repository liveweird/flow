package ch.nokillswit.reports

import ch.nokillswit.metrics.ClaimSprint
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.StoredSprintCalendar
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.metrics.claimedDaysBySprint
import ch.nokillswit.metrics.personDayCapacity
import ch.nokillswit.metrics.projectCalendar
import ch.nokillswit.metrics.sprintWindow
import ch.nokillswit.metrics.storedCalendarsForTeam
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import java.math.BigDecimal
import java.time.LocalDate

/*
 * The per-person capacity of a team over a period (domain-model A39; its Report 16 matrix consumer was removed with the Costs
 * revamp, Compare people (A41, `ComparePeopleCapacity.kt`) is its reader): the man-days each person had available in the period,
 * summed per calendar day over ONE day map that decides which sprint owns a day. A day owned by a sprint with a STORED calendar reads
 * that calendar (`projectCalendar` + `personDayCapacity`, DERIVE's exact arguments); every other day is a default day (one man-day
 * per team member on a working day). The caller computes it only for a viewer who may see it, never computed-then-stripped.
 */

/** One sprint of the filter team (`metrics.dim_sprint`, with a start): enough for the day map and the stored-calendar window. */
internal data class CostSprint(
    val connectionId: UInt,
    val sprintId: Long,
    val name: String,
    val startAtMs: Long,
    val endAtMs: Long?,
    val completeAtMs: Long?,
)

/** One dated membership of the filter team (`metrics.team_membership`). */
internal data class CostMembership(val accountId: String, val validFrom: Long, val validTo: Long?) {
    /** Whether the membership overlaps `[fromMs, toMs)` (the A3 member predicate). */
    fun overlaps(fromMs: Long, toMs: Long): Boolean = validFrom < toMs && (validTo == null || validTo > fromMs)
}

/**
 * The capacity of the period: [perPersonByDay] is each person's exact man-days per day, [days] the period days up to today (the
 * capacity days) and [calendarDays] how many of them read a saved calendar. A person with no counted day is absent.
 */
internal data class CostCapacityDays(
    val perPersonByDay: Map<String, Map<LocalDate, BigDecimal>>,
    val days: List<LocalDate>,
    val calendarDays: Int,
) {
    /** The exact capacity of [accountId] over the period (0 for a person with none). */
    fun exactFor(accountId: String): BigDecimal =
        perPersonByDay[accountId]?.values?.fold(BigDecimal.ZERO, BigDecimal::add) ?: BigDecimal.ZERO
}

/** The team's sprints with a start, one per sprint id (the lowest connection id's row), ordered by start then sprint id. */
internal suspend fun readCostSprints(teamId: UInt, connectionIds: List<UInt>): List<CostSprint> {
    val d = MetricsTables.DimSprint
    val rows = d.select(d.connectionId, d.sprintId, d.name, d.startAt, d.endAt, d.completeAt)
        .where { (d.teamId eq teamId) and (d.connectionId inList connectionIds) and d.startAt.isNotNull() }
        .toList()
        .map { CostSprint(it[d.connectionId].value, it[d.sprintId], it[d.name], it[d.startAt]!!, it[d.endAt], it[d.completeAt]) }
    return rows.groupBy { it.sprintId }.values.map { sameSprint -> sameSprint.minBy { it.connectionId } }
        .sortedWith(compareBy({ it.startAtMs }, { it.sprintId }))
}

/** The team's memberships overlapping `[fromMs, toMs)` — one read, the `fetchMemberDays` predicate. */
internal suspend fun readCostMemberships(teamId: UInt, fromMs: Long, toMs: Long): List<CostMembership> {
    val m = TeamMembershipService.TeamMembership
    val overlapping: Op<Boolean> = (m.validFrom less toMs) and (m.validTo.isNull() or (m.validTo greater fromMs))
    return m.select(m.accountId, m.validFrom, m.validTo).where { (m.teamId eq teamId) and overlapping }.toList()
        .map { CostMembership(it[m.accountId], it[m.validFrom], it[m.validTo]) }
}

/** Every calendar day of `[firstMs, lastMs)` in the configured zone, first to last (empty for an empty interval). */
internal fun periodDaysOf(calendar: WorkingCalendar, firstMs: Long, lastMs: Long): List<LocalDate> {
    if (lastMs <= firstMs) return emptyList()
    val first = calendar.dayOf(firstMs)
    val last = calendar.dayOf(lastMs - 1)
    return generateSequence(first) { day -> day.plusDays(1).takeIf { !it.isAfter(last) } }.toList()
}

/**
 * Which sprint owns each of [periodDays]: a sprint's days are `dayOf(start) .. dayOf(max(start, end - 1))` (the A32 window rule,
 * whatever its length), the FIRST sprint in [sprints] order owns a day — two overlapping sprints never count a day twice.
 */
internal fun sprintDayOwners(
    sprints: List<CostSprint>,
    periodDays: List<LocalDate>,
    calendar: WorkingCalendar,
): Map<LocalDate, CostSprint> {
    if (periodDays.isEmpty()) return emptyMap()
    val first = periodDays.first()
    val last = periodDays.last()
    val owners = HashMap<LocalDate, CostSprint>()
    for (sprint in sprints) {
        val end = sprint.endAtMs ?: sprint.completeAtMs ?: sprint.startAtMs
        val from = maxOf(calendar.dayOf(sprint.startAtMs), first)
        val to = minOf(calendar.dayOf(maxOf(sprint.startAtMs, end - 1)), last)
        generateSequence(from) { day -> day.plusDays(1).takeIf { !it.isAfter(to) } }.takeWhile { !it.isAfter(to) }
            .forEach { owners.putIfAbsent(it, sprint) }
    }
    return owners
}

/**
 * The re-projected calendar capacity of every sprint that owns a period day, has a stored calendar and an accepted window. The claims
 * (A42) come from the owning sprints, the page's rule: a day a sprint OWNS is never claimed (its owner is the FIRST sprint holding it),
 * so the days read here are the same whatever the claims, and the per-sprint figures match the page.
 */
private fun calendarCapacityBySprint(
    owned: Collection<CostSprint>,
    stored: Map<Pair<UInt, Long>, StoredSprintCalendar>,
    memberships: List<CostMembership>,
    calendar: WorkingCalendar,
): Map<Long, Map<String, Map<LocalDate, BigDecimal>>> {
    val sprints = owned.distinct()
    val claims = claimedDaysBySprint(sprints.map { ClaimSprint(it.sprintId, it.name, it.startAtMs, it.endAtMs, it.completeAtMs) }, calendar)
    return sprints.mapNotNull { sprint ->
        val saved = stored[sprint.connectionId to sprint.sprintId] ?: return@mapNotNull null
        val window = sprintWindow(sprint.startAtMs, sprint.endAtMs, sprint.completeAtMs, calendar) ?: return@mapNotNull null
        val roster = memberships.filter { it.overlaps(window.startMs, window.endMs) }.map { it.accountId }.distinct()
        val claimed = claims[sprint.sprintId].orEmpty()
        sprint.sprintId to personDayCapacity(projectCalendar(saved.document, window, roster, calendar, claimed, carry = null))
    }.toMap()
}

/** A default day: one man-day for each member of [memberships] on a working day, nothing on a weekend or a public holiday. */
private fun defaultDayCapacity(day: LocalDate, memberships: List<CostMembership>, calendar: WorkingCalendar): Map<String, BigDecimal> {
    if (!calendar.isWorkingDay(day)) return emptyMap()
    val (dayStart, dayEnd) = calendar.dayBoundsMs(day)
    return memberships.filter { it.overlaps(dayStart, dayEnd) }.associate { it.accountId to BigDecimal.ONE }
}

/**
 * The per-person per-day capacity over [capacityDays] (the period up to today). [owners] is the day map; a day whose owner has a
 * stored calendar in [stored] and an accepted window (at most 60 days) reads the re-projected calendar for the sprint's roster
 * (A3: the memberships overlapping the sprint's instants); any other day counts 1 for each member of [memberships] on a working day.
 */
internal fun computeCostCapacity(
    capacityDays: List<LocalDate>,
    owners: Map<LocalDate, CostSprint>,
    stored: Map<Pair<UInt, Long>, StoredSprintCalendar>,
    memberships: List<CostMembership>,
    calendar: WorkingCalendar,
): CostCapacityDays {
    val calendarCapacity = calendarCapacityBySprint(capacityDays.mapNotNull { owners[it] }, stored, memberships, calendar)
    val perPerson = HashMap<String, MutableMap<LocalDate, BigDecimal>>()
    var calendarDays = 0
    for (day in capacityDays) {
        val fromCalendar = owners[day]?.let { calendarCapacity[it.sprintId] }
        if (fromCalendar != null) calendarDays++
        val onDay = if (fromCalendar != null) {
            fromCalendar.mapNotNull { (account, perDay) -> perDay[day]?.let { account to it } }.toMap()
        } else {
            defaultDayCapacity(day, memberships, calendar)
        }
        onDay.forEach { (account, md) -> perPerson.getOrPut(account) { HashMap() }[day] = md }
    }
    return CostCapacityDays(perPerson, capacityDays, calendarDays)
}

/**
 * The capacity of the period `window` for the real team [teamId] on the scoped connections: the period days clipped to today, the
 * day map of the team's sprints, the stored calendars and the memberships. Runs in the report's transaction.
 */
internal suspend fun loadCostCapacity(
    teamId: UInt,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    calendar: WorkingCalendar,
    nowMs: Long,
): CostCapacityDays {
    val today = calendar.dayOf(nowMs)
    val periodDays = periodDaysOf(calendar, window.first, window.second)
    val capacityDays = periodDays.takeWhile { !it.isAfter(today) }
    if (capacityDays.isEmpty()) return CostCapacityDays(emptyMap(), emptyList(), 0)
    val owners = sprintDayOwners(readCostSprints(teamId, connectionIds), periodDays, calendar)
    val stored = storedCalendarsForTeam(teamId, connectionIds)
    // Sprint rosters reach outside the period (a sprint starts before it), so the membership read covers the widest sprint window too.
    val sprintStarts = owners.values.distinct().map { it.startAtMs }
    val sprintEnds = owners.values.distinct().map { it.endAtMs ?: it.completeAtMs ?: it.startAtMs }
    val fromMs = minOf(calendar.dayBoundsMs(capacityDays.first()).first, sprintStarts.minOrNull() ?: Long.MAX_VALUE)
    val toMs = maxOf(calendar.dayBoundsMs(capacityDays.last()).second, sprintEnds.maxOrNull() ?: Long.MIN_VALUE)
    return computeCostCapacity(capacityDays, owners, stored, readCostMemberships(teamId, fromMs, toMs), calendar)
}
