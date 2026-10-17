package ch.nokillswit.metrics

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.norm.WorkItemStoreKey
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/jira-teams")
class JiraTeamsRoute

// The `atlassianTeamId` query parameter is read in the handler, AFTER the guard: a typed resource property would fail its decode (a 400)
// before requireAdmin ran, and 403 must win over 400.
@Serializable
@Resource("/api/v1/teams/{id}/jira-memberships/seed-candidates")
class TeamSeedCandidatesRoute(val id: UInt)

@Serializable
@Resource("/api/v1/teams/{id}/jira-memberships/seed")
class TeamSeedRoute(val id: UInt)

/**
 * Seeding a team's dated Jira memberships from the Atlassian Team field (`TeamMembershipSeedService`): the team picker, the
 * per-assignee preview and the apply. ADMIN only, the whole surface — it prepares and performs `/jira-memberships` writes (ADMIN only),
 * and the preview pairs a person with per-team issue counts; `requireAdmin` runs before the id lookup, the query and the body decode
 * (a non-admin gets a uniform 403 whether or not the team exists, malformed input included).
 */
fun Application.configureTeamMembershipSeedRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val service = TeamMembershipSeedService(
        attributes[R2dbcDatabaseKey],
        attributes[TeamMembershipServiceKey],
        attributes[WorkItemStoreKey],
        attributes[MetricsSettingsServiceKey],
    )

    routing {
        authenticate {
            get<JiraTeamsRoute> {
                requireAdmin(call.caller())
                call.respond(HttpStatusCode.OK, JiraTeamListResponse(service.jiraTeams()))
            }
            get<TeamSeedCandidatesRoute> { route ->
                requireAdmin(call.caller())
                val atlassianTeamId = sanitizedAtlassianTeamId(call.request.queryParameters.optionalString("atlassianTeamId"))
                call.respond(HttpStatusCode.OK, SeedCandidateListResponse(service.candidatesFor(route.id, atlassianTeamId)))
            }
            post<TeamSeedRoute> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedTeamMembershipSeed(call.receive())
                validateTeamMembershipSeed(request)
                val outcome = service.seed(route.id, request)
                // Only an actual change is a trail line (the features-PUT precedent); counts and opaque ids, never a name.
                if (outcome.created.isNotEmpty()) {
                    audit(
                        "team.jira_memberships_seeded",
                        "byUserId" to caller.userId.toLong(),
                        "teamId" to route.id.toLong(),
                        "atlassianTeamId" to request.atlassianTeamId,
                        "requested" to request.accountIds.size,
                        "created" to outcome.created.size,
                        "skipped" to outcome.skipped.size,
                    )
                }
                call.respond(HttpStatusCode.OK, outcome)
            }
        }
    }
}
