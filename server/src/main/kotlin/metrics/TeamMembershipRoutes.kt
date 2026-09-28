package ch.nokillswit.metrics

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
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
@Resource("/api/v1/teams/{id}/jira-memberships")
class TeamJiraMembershipsRoute(val id: UInt) {
    @Serializable
    @Resource("{membershipId}")
    class Id(val parent: TeamJiraMembershipsRoute, val membershipId: UInt)
}

/**
 * D1's dated Jira-user team membership API (v0.3.0 M1 commit 3). Reads are any-authenticated (the
 * team reads' own posture, `.claude/docs/authorization.md`); every mutation is ADMIN-only, guard
 * BEFORE the body decodes — a non-admin probe gets a uniform 403 whether or not the team/row
 * exists.
 */
fun Application.configureTeamMembershipRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val service = attributes[TeamMembershipServiceKey]

    routing {
        authenticate {
            get<TeamJiraMembershipsRoute> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, TeamMembershipListResponse(service.list(route.id)))
            }
            post<TeamJiraMembershipsRoute> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedTeamMembershipCreate(call.receive())
                validateTeamMembershipCreate(request)
                val created = service.create(route.id, request)
                audit(
                    "team.jira_membership_added",
                    "byUserId" to caller.userId.toLong(),
                    "teamId" to route.id.toLong(),
                    "accountId" to created.accountId,
                    "validFrom" to created.validFrom,
                    "validTo" to created.validTo,
                )
                call.response.header(
                    HttpHeaders.Location,
                    call.application.href(TeamJiraMembershipsRoute.Id(parent = route, membershipId = created.id)),
                )
                call.respond(HttpStatusCode.Created, created)
            }
            put<TeamJiraMembershipsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = call.receive<TeamMembershipUpdateRequest>()
                validateTeamMembershipUpdate(request)
                val teamId = route.parent.id
                val outcome = service.update(teamId, route.membershipId, request).orNotFound("Team membership")
                if (outcome.changed) {
                    audit(
                        "team.jira_membership_updated",
                        "byUserId" to caller.userId.toLong(),
                        "teamId" to teamId.toLong(),
                        "accountId" to outcome.response.accountId,
                        "validFrom" to outcome.response.validFrom,
                        "validTo" to outcome.response.validTo,
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }
            delete<TeamJiraMembershipsRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val teamId = route.parent.id
                // The service captures the row inside the SAME transaction as the delete, so the
                // audit line names what was removed without a second, racy round trip.
                val removed = service.delete(teamId, route.membershipId).orNotFound("Team membership")
                audit(
                    "team.jira_membership_removed",
                    "byUserId" to caller.userId.toLong(),
                    "teamId" to teamId.toLong(),
                    "accountId" to removed.accountId,
                    "validFrom" to removed.validFrom,
                    "validTo" to removed.validTo,
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
