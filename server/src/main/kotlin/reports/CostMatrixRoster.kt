package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar

/*
 * The cost matrix's zero rows (report 16, domain-model A39, amended 2026-10-17): at TEAM and USER level of a real team the rows are
 * the authors who logged PLUS the people the roster says belong there, each with all-zero cells. The set must be the same for every
 * viewer, so it reads the roster (`team_membership`) and the working calendar only -- never a capacity figure, which is withheld from
 * non-editors.
 */

/**
 * The accounts that get a row even if they logged nothing: at TEAM level (`teamId` >= 1) every member of the team whose membership
 * covers at least one WORKING day of the period `window` (the configured calendar, public holidays off; the whole period, not only
 * the days up to today), at USER level the picked person; none at UNIT level and for `teamId=0` (UNASSIGNED has no roster).
 */
internal suspend fun costZeroRowAccounts(filter: ReportFilter, window: Pair<Long, Long>, calendar: WorkingCalendar): List<String> {
    val teamId = filter.teamId ?: return emptyList()
    if (teamId == UNASSIGNED_TEAM_ID) return emptyList()
    return when (filter.level) {
        ReportLevel.UNIT -> emptyList()
        ReportLevel.USER -> listOfNotNull(filter.accountId)
        ReportLevel.TEAM -> {
            val workingDayBounds = periodDaysOf(calendar, window.first, window.second)
                .filter(calendar::isWorkingDay)
                .map(calendar::dayBoundsMs)
            readCostMemberships(teamId, window.first, window.second)
                .filter { member -> workingDayBounds.any { (from, to) -> member.overlaps(from, to) } }
                .map { it.accountId }.distinct()
        }
    }
}

/**
 * The org-drill [groups] plus an empty group for every [zeroRowAccounts] entry that has none, re-sorted by the ONE row order (display
 * name, null last, then team id, then account id) so a zero row sorts in among the authors rather than after them.
 */
internal suspend fun withZeroRows(
    groups: List<Pair<OrgGroupKey, List<CostAggregate>>>,
    zeroRowAccounts: List<String>,
): List<Pair<OrgGroupKey, List<CostAggregate>>> {
    val present = groups.mapNotNull { it.first.accountId }.toSet()
    val missing = zeroRowAccounts.filter { it !in present }
    if (missing.isEmpty()) return groups
    val names = accountDisplayNames(missing)
    val zero = missing.map { OrgGroupKey(null, it, names[it] ?: it) to emptyList<CostAggregate>() }
    return (groups + zero).sortedWith(byLabelThenId({ it.first.label }, { it.first.teamId }, { it.first.accountId }))
}
