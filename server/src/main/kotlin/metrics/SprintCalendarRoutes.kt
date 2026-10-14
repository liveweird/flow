package ch.nokillswit.metrics

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.authz.requireTeamManagerOrAdmin
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.servesApi
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamServiceKey
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/teams/{id}/capacity")
class TeamCapacityRoute(val id: UInt)

@Serializable
@Resource("/api/v1/teams/{id}/sprint-calendars")
class TeamSprintCalendarsRoute(val id: UInt) {
    @Serializable
    @Resource("{sprintId}")
    class Sprint(val parent: TeamSprintCalendarsRoute, val sprintId: Long)
}

/**
 * Capacity management's API (domain-model A32; `.claude/docs/authorization.md`): every read is any-authenticated (a missing or soft-deleted
 * team or a sprint of another team is a plain 404); every write is ADMIN or the team's own ACTIVE manager. The writes run
 * `requireTeamManagerOrAdmin` FIRST — before the team lookup, the body and any validation — so a non-admin gets a uniform 403 whether the
 * team exists or not, and 403 wins over 404 wins over 400 (then 409 for a sprint without a start date). The path's `teamId` is bound once
 * and handed to both the guard and the service, which re-checks the grant inside its locked write transaction.
 */
fun Application.configureSprintCalendarRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val service = attributes[SprintCalendarServiceKey]
    val teams = attributes[TeamServiceKey]
    val metricsSettings = attributes[MetricsSettingsServiceKey]

    routing {
        authenticate {
            teamCapacityRoutes(service, teams)
            sprintCalendarRoutes(service, teams, metricsSettings)
        }
    }
}

private suspend fun canEdit(caller: CallerPrincipal, teamId: UInt, teams: TeamService): Boolean =
    caller.isAdmin() || teams.isActiveManager(teamId, caller.userId)

/** The audit field naming which grant authorized the write. */
private fun CallerPrincipal.authorizedBy(): String = if (isAdmin()) "ADMIN" else "MANAGER"

private suspend fun ApplicationCall.requireCapacityEditor(teams: TeamService, teamId: UInt): CallerPrincipal {
    val caller = caller()
    requireTeamManagerOrAdmin(caller, teamId) { t, u -> teams.isActiveManager(t, u) }
    return caller
}

private fun Route.teamCapacityRoutes(service: SprintCalendarService, teams: TeamService) {
    get<TeamCapacityRoute> { route ->
        val caller = call.caller()
        call.respond(HttpStatusCode.OK, service.readTeamCapacity(route.id, canEdit(caller, route.id, teams)))
    }
    put<TeamCapacityRoute> { route ->
        val caller = call.requireCapacityEditor(teams, route.id)
        service.requireTeam(route.id)
        val request = call.receive<TeamCapacityRequest>()
        validateTeamCapacity(request)
        if (service.replaceTeamCapacity(route.id, request, caller.userId, caller.isAdmin())) {
            audit(
                "team.capacity_default_updated",
                "byUserId" to caller.userId.toLong(),
                "teamId" to route.id.toLong(),
                "defaultCapacityMd" to request.defaultCapacityMd,
                "authorizedBy" to caller.authorizedBy(),
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.sprintCalendarRoutes(service: SprintCalendarService, teams: TeamService, metricsSettings: MetricsSettingsService) {
    get<TeamSprintCalendarsRoute> { route ->
        call.caller()
        val paging = call.parsePaging(sortable = SPRINT_CALENDAR_SORT_FIELDS)
        val result = service.listSprints(route.id, paging)
        call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
    }
    get<TeamSprintCalendarsRoute.Sprint> { route ->
        val caller = call.caller()
        val teamId = route.parent.id
        val calendar = WorkingCalendar.of(metricsSettings.read())
        call.respond(HttpStatusCode.OK, service.read(teamId, route.sprintId, calendar, canEdit(caller, teamId, teams)))
    }
    put<TeamSprintCalendarsRoute.Sprint> { route ->
        val teamId = route.parent.id
        val caller = call.requireCapacityEditor(teams, teamId)
        service.requireSprint(teamId, route.sprintId)
        val request = sanitizedSprintCalendar(call.receive())
        validateSprintCalendarShape(request)
        val calendar = WorkingCalendar.of(metricsSettings.read())
        val outcome = service.replace(teamId, route.sprintId, request, caller.userId, caller.isAdmin(), calendar)
        if (outcome.changed) {
            audit(
                "team.sprint_calendar_updated",
                "byUserId" to caller.userId.toLong(),
                "teamId" to teamId.toLong(),
                "sprintId" to route.sprintId,
                "connectionId" to outcome.connectionId.toLong(),
                "capacityMd" to outcome.capacityMd,
                "authorizedBy" to caller.authorizedBy(),
            )
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
