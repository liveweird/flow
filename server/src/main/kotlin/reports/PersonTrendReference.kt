package ch.nokillswit.reports

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Report 20's team reference line (A46 amended 2026-10-28, `.claude/docs/reports.md` "Report 20"): beside each person point, the median of
 * the same cell over the OTHER ACTIVE members of the team the person belonged to on the window's end day, so a move the whole team made is
 * not read as the person's. The figures are NOT computed here: a peer's cell is Report 18's `cellsOf` over the very window slice the
 * person's is (one truth); this file picks the team of each point, its peers and takes the median.
 */

/** A reference needs at least this many ACTIVE peers with a shown value in the window; fewer and the point has the count, no `value`. */
internal const val MIN_REFERENCE_PEERS = 3

/** The reference team of one point and its peers' account ids (the team's OTHER members on the window's end day, sorted, distinct). */
internal class TrendReferenceTeam(val team: PersonTrendTeam, val peers: List<String>)

/**
 * The team a point compares with: among the teams a membership of the person covers on the window's END day, the one the person
 * belonged to for the most days inside the window (a day counts when any membership of that team covers it, [PersonMembership.coversDay]);
 * a tie goes to the LOWEST team id (an id is stable, a name can be renamed). Several teams cover the end day only on a transfer day, as
 * the exclusion constraint allows one team at an instant. `null` = no membership covers the end day: no reference for that point.
 */
internal fun referenceTeamOf(memberships: List<PersonMembership>, window: TrendWindow, calendar: WorkingCalendar): UInt? {
    val (endStart, endEnd) = calendar.dayBoundsMs(window.end)
    val covering = memberships.filter { it.coversDay(endStart, endEnd) }.map { it.teamId }.toSet()
    if (covering.isEmpty()) return null
    val days = (0 until TREND_WINDOW_DAYS).map { calendar.dayBoundsMs(window.from.plusDays(it.toLong())) }
    fun daysIn(teamId: UInt): Int =
        days.count { (start, end) -> memberships.any { it.teamId == teamId && it.coversDay(start, end) } }
    return covering.sorted().maxByOrNull { daysIn(it) }
}

/** The members of [teamId] other than [person] whose membership covers the window's end day (the person's own rule), by account id. */
internal fun peersOf(
    roster: List<PersonMembership>,
    teamId: UInt,
    person: String,
    window: TrendWindow,
    calendar: WorkingCalendar,
): List<String> {
    val (endStart, endEnd) = calendar.dayBoundsMs(window.end)
    return roster.filter { it.teamId == teamId && it.accountId != person && it.coversDay(endStart, endEnd) }
        .map { it.accountId }.distinct().sorted()
}

/**
 * The reference of one row at one point from the [peerCells]: the median (`percentile_cont(0.5)`, the report's one p50) of the peers
 * whose cell is shown (not hidden, with a value) -- `null` value under [MIN_REFERENCE_PEERS] of them. `peers` is how many contributed.
 */
internal fun referenceOf(peerCells: List<CompareCell>): PersonTrendReference {
    val values = peerCells.filter { !it.hidden }.mapNotNull { it.value }
    if (values.size < MIN_REFERENCE_PEERS) return PersonTrendReference(null, values.size)
    return PersonTrendReference(percentileContinuous(values.sorted(), 0.5), values.size)
}

/**
 * Whether [account] (whose rows alone [CompareData] holds) was active in the window: it delivered a task, logged a worklog (both under the
 * request's slices) or held work in progress on any listed day of the window. A member with none of these (a manager, someone on leave)
 * is not a peer to compare with: they would pull a count or WIP median to 0 and count toward [MIN_REFERENCE_PEERS].
 */
internal fun CompareData.hadActivity(account: String): Boolean =
    done.isNotEmpty() || worklogs.isNotEmpty() || wip.days.any { (wip.dayCounts[account]?.get(it) ?: 0) > 0 }

/**
 * Every trend row's reference at one point, `null` with no team: the cells ([cellsOf] over the point's window, as [PeerCellEntry], cached
 * or fresh alike) of the team's peers that were active in the window ([PeerCellEntry.active]) -- an inactive peer contributes to NO Jira
 * row -- so `peers` counts only the active ones. The `CODE` rows (A50) are the exception: no Jira slice or Jira activity applies to them,
 * so their pool is the peers with a LINKED GitLab identity ([PeerCellEntry.linked]), whatever they did in Jira: a product owner with
 * no link
 * does not pull the team's median of merge requests to 0, and a linked peer with no merge request in the window counts as the 0 it is.
 * [entries] holds every one of the team's peers by account id.
 */
internal fun referencesOf(team: TrendReferenceTeam?, entries: Map<String, PeerCellEntry>): Map<String, PersonTrendReference>? {
    team ?: return null
    val peers = team.peers.map { entries.getValue(it) }
    return TREND_ROWS.mapIndexed { row, spec ->
        val pool = peers.filter { if (spec.group == CompareGroup.CODE) it.linked else it.active }
        spec.key to referenceOf(pool.map { it.cell(row) })
    }.toMap()
}

/** The memberships of [teamIds] overlapping `[fromMs, toMs)`: those teams' dated rosters. */
internal suspend fun readTeamMemberships(teamIds: Collection<UInt>, fromMs: Long, toMs: Long): List<PersonMembership> {
    val m = TeamMembershipService.TeamMembership
    val overlapping = (m.validFrom less toMs) and (m.validTo.isNull() or (m.validTo greater fromMs))
    return m.select(m.accountId, m.teamId, m.validFrom, m.validTo).where { (m.teamId inList teamIds) and overlapping }.toList()
        .map { PersonMembership(it[m.accountId], it[m.teamId].value, it[m.validFrom], it[m.validTo]) }
}

/**
 * The reference team and peers of every window, in the caller's report transaction: the person's memberships over the whole range, the
 * team picked per window ([referenceTeamOf]), then the picked teams' rosters and names -- at most three statements however many windows
 * (none beyond the first when the person has no membership covering any end day).
 */
internal suspend fun readReferenceTeams(person: String, windows: List<TrendWindow>, calendar: WorkingCalendar): List<TrendReferenceTeam?> {
    val fromMs = windows.first().bounds.first
    val toMs = windows.last().bounds.second
    val own = readPeopleMemberships(listOf(person), fromMs, toMs)
    val teamIds = windows.map { referenceTeamOf(own, it, calendar) }
    val picked = teamIds.filterNotNull().distinct()
    if (picked.isEmpty()) return windows.map { null }
    val roster = readTeamMemberships(picked, fromMs, toMs)
    val names = teamNames(picked)
    return windows.mapIndexed { i, window ->
        teamIds[i]?.let { id ->
            TrendReferenceTeam(PersonTrendTeam(id, names[id] ?: "#$id"), peersOf(roster, id, person, window, calendar))
        }
    }
}
