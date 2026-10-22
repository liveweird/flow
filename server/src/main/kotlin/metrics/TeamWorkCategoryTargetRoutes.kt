package ch.nokillswit.metrics

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/teams/{id}/work-category-targets")
class TeamWorkCategoryTargetsRoute(val id: UInt)

/**
 * `GET`/`PUT /api/v1/teams/{id}/work-category-targets` (Costs part 3; `.claude/docs/authorization.md`): the read is any-authenticated (a
 * missing or soft-deleted team is a plain 404); the write is ADMIN only and runs `requireAdmin` FIRST — before the team lookup, the body
 * decode and any validation — so a non-admin gets a uniform 403 whether the team exists or not, then 404, then 400. A change audits
 * `team.work_category_targets_updated`; an identical re-PUT writes and audits nothing. No config revision bump: the targets are read at
 * query time.
 */
fun Application.configureTeamWorkCategoryTargetRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val service = attributes[TeamWorkCategoryTargetServiceKey]

    routing {
        authenticate {
            get<TeamWorkCategoryTargetsRoute> { route ->
                call.caller()
                call.respond(HttpStatusCode.OK, service.read(route.id))
            }
            put<TeamWorkCategoryTargetsRoute> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                service.requireTeam(route.id)
                val request = sanitizedTeamWorkCategoryTargets(call.receive())
                validateTeamWorkCategoryTargets(request)
                val outcome = service.replace(route.id, request, caller.userId)
                if (outcome.changed) {
                    audit(
                        "team.work_category_targets_updated",
                        "byUserId" to caller.userId.toLong(),
                        "teamId" to route.id.toLong(),
                        "targetsFrom" to outcome.from.asAuditValue(),
                        "targets" to outcome.to.asAuditValue(),
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** `key=percent` pairs in key order, `""` = none. Keys are admin-curated labels, never personal data. */
private fun List<TeamWorkCategoryTarget>.asAuditValue(): String = joinToString(";") { "${it.key}=${it.targetPercent}" }
