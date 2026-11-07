package ch.nokillswit.integration

import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.metrics.MetricsSettingsServiceKey
import ch.nokillswit.metrics.SprintCalendarServiceKey
import ch.nokillswit.metrics.TeamMembershipServiceKey
import ch.nokillswit.norm.WorkItemStoreKey
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.plugins.servesApi
import ch.nokillswit.teams.TeamServiceKey
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/integration")
class IntegrationRoute {
    @Serializable
    @Resource("me")
    class Me(val parent: IntegrationRoute = IntegrationRoute())

    /** `?sprintsSince=YYYY-MM-DD` is read from the raw query: a repeated key is a 400 (`singleValue`) and the date's 400 text is ours. */
    @Serializable
    @Resource("teams")
    class Teams(val parent: IntegrationRoute = IntegrationRoute())
}

/**
 * The facts of the presenting service token as they were BEFORE this call's `last_used_at` stamp (`null` = never used / never expires):
 * the "Test connection" probe of a consumer. Nothing secret, nothing about any user.
 */
@Serializable
data class IntegrationMe(
    val tokenId: UInt,
    val name: String,
    val createdAt: Long,
    val expiresAt: Long? = null,
    val lastUsedAt: Long? = null,
)

/**
 * The read-only `/api/v1/integration` namespace (`.claude/docs/security.md` "Service tokens"): TOKEN-ONLY, GET only (`me`, and the
 * `teams` bulk read composed by `IntegrationReadService`). The one and only
 * `authenticate(SERVICE_TOKEN)` block of the codebase, so a service token can reach nothing else and a JWT reaches nothing here; no
 * write route may be added to this file (`ServiceTokenSurfaceTest` greps it for `post<`, `put<`, `delete<` and `receive(`). Two
 * buckets, both named in `plugins/RateLimits.kt`: the per-host `integration` budget is spent by the provider itself BEFORE the lookup
 * (`HostWindowLimiter`: Ktor's rate limiter runs after authentication and never sees a refused call), the per-token `integration-token`
 * is the plugin's bucket inside `authenticate`, where its key (the verified token's id) exists. The worker role serves no API
 * (`servesApi()`).
 */
fun Application.configureIntegrationRoutes() {
    if (!servesApi()) return

    val readService = IntegrationReadService(
        attributes[TeamServiceKey],
        attributes[TeamMembershipServiceKey],
        attributes[SprintCalendarServiceKey],
        attributes[MetricsSettingsServiceKey],
        attributes[WorkItemStoreKey],
    )

    routing {
        authenticate(SERVICE_TOKEN) {
            rateLimit(RateLimitName(RateLimits.INTEGRATION_TOKEN)) {
                get<IntegrationRoute.Me> {
                    val token = call.attributes[ServiceTokenFactsKey]
                    call.respond(
                        HttpStatusCode.OK,
                        IntegrationMe(token.tokenId, token.name, token.createdAt, token.expiresAt, token.lastUsedAtBefore),
                    )
                }
                get<IntegrationRoute.Teams> {
                    val since = parseSprintsSince(call.request.queryParameters.optionalString("sprintsSince"))
                    call.respond(HttpStatusCode.OK, readService.teams(since))
                }
            }
        }
    }
}
