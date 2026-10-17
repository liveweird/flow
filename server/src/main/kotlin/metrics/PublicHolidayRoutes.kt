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
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/public-holidays")
class PublicHolidaysRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: PublicHolidaysRoute = PublicHolidaysRoute(), val id: UInt)
}

/**
 * The public-holiday dictionary API (ported from Lettuce, without its feature flag): GET is any authenticated caller (the capacity
 * page and the settings form show the names), POST/DELETE are ADMIN-only with the guard BEFORE the body decodes — a non-admin probe
 * gets a uniform 403 whether or not the row exists. Both mutations bump the shared revision (DERIVE for every enabled connection).
 */
fun Application.configurePublicHolidayRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val service = attributes[PublicHolidayServiceKey]

    routing {
        authenticate {
            get<PublicHolidaysRoute> {
                call.caller()
                call.respond(HttpStatusCode.OK, PublicHolidayList(service.list()))
            }
            post<PublicHolidaysRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedPublicHoliday(call.receive())
                validatePublicHoliday(request)
                // A duplicate date is the database's unique index -> 23505 -> the central 409 mapping.
                val created = service.create(request)
                call.response.header(HttpHeaders.Location, call.application.href(PublicHolidaysRoute.Id(id = created.id)))
                audit(
                    "public_holiday.created",
                    "byUserId" to caller.userId.toLong(),
                    "holidayId" to created.id.toLong(),
                    "date" to created.date,
                    "name" to created.name,
                )
                call.respond(HttpStatusCode.Created, created)
            }
            delete<PublicHolidaysRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val removed = service.delete(route.id).orNotFound("Public holiday")
                audit(
                    "public_holiday.deleted",
                    "byUserId" to caller.userId.toLong(),
                    "holidayId" to removed.id.toLong(),
                    "date" to removed.date,
                    "name" to removed.name,
                )
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
