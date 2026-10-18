package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.optionalUInt
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/reports/home")
class ReportHomeRoute

@Serializable
@Resource("/api/v1/reports/home/items")
class ReportHomeItemsRoute

/**
 * The Home overview: any signed-in user (D12), read-only, no audit, `call.caller()` FIRST so the guard wins over every `400`. The
 * overview takes no parameters (like `/reports/filters`: the window is the trailing 90 days ending today). The drill takes `view` and
 * `teamId`, both required; `teamId` must be a real active team (checked by the service).
 */
internal fun Route.homeRoutes(reportService: ReportService) {
    get<ReportHomeRoute> {
        call.caller()
        call.respond(HttpStatusCode.OK, reportService.home(nowMillis()))
    }
    get<ReportHomeItemsRoute> {
        call.caller()
        val params = call.request.queryParameters
        val view = params.requireEnum<HomeDrillView>("view")
        val teamId = params.optionalUInt("teamId") ?: throw BadRequestException("teamId is required")
        call.respond(HttpStatusCode.OK, reportService.homeItems(view, teamId, nowMillis()))
    }
}
