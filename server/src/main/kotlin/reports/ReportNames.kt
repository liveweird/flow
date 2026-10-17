package ch.nokillswit.reports

import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

/* The label lookups every report's org drill shares (team names, Jira display names). Each runs inside the CALLER's transaction. */

/** Team names by id — an empty [ids] costs no query. */
internal suspend fun teamNames(ids: Collection<UInt>): Map<UInt, String> {
    if (ids.isEmpty()) return emptyMap()
    return TeamService.Teams.select(TeamService.Teams.id, TeamService.Teams.name)
        .where { TeamService.Teams.id inList ids }
        .toList().associate { it[TeamService.Teams.id].value to it[TeamService.Teams.name] }
}

/** Jira display names by account id (the lowest connection id's name wins for an account seen on several). */
internal suspend fun accountDisplayNames(accountIds: Collection<String>): Map<String, String> {
    if (accountIds.isEmpty()) return emptyMap()
    return WorkItemStore.People.select(WorkItemStore.People.accountId, WorkItemStore.People.displayName)
        .where { WorkItemStore.People.accountId inList accountIds }
        .orderBy(WorkItemStore.People.connectionId to SortOrder.DESC)
        .toList().associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
}
