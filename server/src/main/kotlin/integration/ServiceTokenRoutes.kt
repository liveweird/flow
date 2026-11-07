package ch.nokillswit.integration

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/service-tokens")
class ServiceTokensRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: ServiceTokensRoute = ServiceTokensRoute(), val id: UInt) {
        @Serializable
        @Resource("revoke")
        class Revoke(val parent: Id)
    }
}

/**
 * Admin management of the service tokens (V48; `.claude/docs/authorization.md`): ADMIN only, reads INCLUDED (the registry holds
 * credentials), with `requireAdmin` FIRST on every operation, before the id lookup and before the body decodes, so a non-admin gets
 * a uniform 403 whether or not the id exists and whatever the body. Tokens are immutable: no PUT/DELETE; `POST {id}/revoke` is the
 * terminal disable. The secret appears in the create response only and in no audit field.
 */
fun Application.configureServiceTokenRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) - see Role.kt.
    if (!servesApi()) return

    val tokens = attributes[ServiceTokenServiceKey]

    routing {
        authenticate {
            get<ServiceTokensRoute> {
                requireAdmin(call.caller())
                call.respond(HttpStatusCode.OK, ServiceTokenListResponse(tokens.list()))
            }
            post<ServiceTokensRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = call.receive<ServiceTokenCreateRequest>()
                val name = validateServiceTokenCreate(request.name, request.expiresAt, System.currentTimeMillis())
                val (token, secret) = tokens.create(name, request.expiresAt, caller.userId)
                audit(
                    "service_token.created",
                    "byUserId" to caller.userId.toLong(),
                    "tokenId" to token.id.toLong(),
                    "name" to token.name,
                    "expiresAt" to token.expiresAt,
                )
                call.response.header(HttpHeaders.Location, call.application.href(ServiceTokensRoute.Id(id = token.id)))
                call.respond(HttpStatusCode.Created, ServiceTokenCreateResponse(token = token, secret = secret))
            }
            get<ServiceTokensRoute.Id> { route ->
                requireAdmin(call.caller())
                call.respond(HttpStatusCode.OK, tokens.read(route.id).orNotFound("Service token"))
            }
            post<ServiceTokensRoute.Id.Revoke> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val id = route.parent.id
                when (tokens.revoke(id, caller.userId)) {
                    ServiceTokenRevokeOutcome.NOT_FOUND -> throw NotFoundException("Service token not found")
                    ServiceTokenRevokeOutcome.ALREADY_REVOKED -> throw ConflictException("Service token is already revoked")
                    ServiceTokenRevokeOutcome.REVOKED -> {
                        audit("service_token.revoked", "byUserId" to caller.userId.toLong(), "tokenId" to id.toLong())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
