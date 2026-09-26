package ch.nokillswit.teams

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/teams")
class TeamsRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: TeamsRoute = TeamsRoute(), val id: UInt) {
        @Serializable
        @Resource("members")
        class Members(val parent: Id) {
            @Serializable
            @Resource("{userId}")
            class User(val parent: Members, val userId: UInt)
        }
    }
}

fun Application.configureTeamRoutes() {
    val teamService = attributes[TeamServiceKey]

    routing {
        authenticate {
            // Reads are any-authenticated (the owner pickers and the Teams page); every mutation
            // is ADMIN-only with the guard BEFORE the body decodes and the id lookup — a
            // non-admin probe gets a uniform 403 whether or not the id exists.
            get<TeamsRoute> {
                call.caller()
                val paging = call.parsePaging(sortable = TEAM_SORT_FIELDS)
                val params = call.request.queryParameters
                val filter = TeamListFilter(
                    name = params.optionalString("name"),
                    memberId = params.optionalUInt("memberId"),
                )
                val result = teamService.list(filter, paging)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            post<TeamsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedTeamCreate(call.receive())
                validateTeamCreate(request)
                val id = teamService.create(request)
                audit(
                    "team.created",
                    "byUserId" to caller.userId.toLong(),
                    "teamId" to id.toLong(),
                    "name" to request.name,
                    "members" to (request.memberIds?.size ?: 0),
                )
                call.response.header(HttpHeaders.Location, call.application.href(TeamsRoute.Id(id = id)))
                call.respond(HttpStatusCode.Created, teamService.read(id).orVanished("Team", id))
            }
            get<TeamsRoute.Id> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, teamService.read(route.id).orNotFound("Team"))
            }
            put<TeamsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedTeamUpdate(call.receive())
                validateTeamUpdate(request)
                teamService.update(route.id, request).orNotFound("Team")
                audit(
                    "team.updated",
                    "byUserId" to caller.userId.toLong(),
                    "teamId" to route.id.toLong(),
                    "name" to request.name,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<TeamsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                teamService.delete(route.id).orNotFound("Team")
                audit("team.deleted", "byUserId" to caller.userId.toLong(), "teamId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
            post<TeamsRoute.Id.Members.User> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val teamId = route.parent.parent.id
                teamService.addMember(teamId, route.userId)
                audit(
                    "team.member_added",
                    "byUserId" to caller.userId.toLong(),
                    "teamId" to teamId.toLong(),
                    "targetUserId" to route.userId.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<TeamsRoute.Id.Members.User> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val teamId = route.parent.parent.id
                teamService.removeMember(teamId, route.userId).orNotFound("Team member")
                audit(
                    "team.member_removed",
                    "byUserId" to caller.userId.toLong(),
                    "teamId" to teamId.toLong(),
                    "targetUserId" to route.userId.toLong(),
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
