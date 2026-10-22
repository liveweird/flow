package ch.nokillswit.teams

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.existsActive

/**
 * The ONE "is this team there and active" check every other package runs (metrics' config/calendar/membership services, the reports'
 * `teamId` filter): it throws what [orElse] builds when the team is missing or soft-deleted, so the CALLER picks the status (a
 * path/resource team is a `404`, a client-supplied `teamId` filter value a `400`). Read-only; a write that must also exclude a
 * concurrent delete uses `lockActiveForWrite` instead.
 */
suspend fun TeamService.Teams.requireActive(teamId: UInt, orElse: () -> Throwable) {
    if (!existsActive(teamId)) throw orElse()
}

/** [requireActive] for a team named by the request path: `404 Team not found`. */
suspend fun TeamService.Teams.requireActiveOrNotFound(teamId: UInt) = requireActive(teamId) { NotFoundException("Team not found") }
