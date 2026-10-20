package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.repeatedLongs
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
 * overview takes an optional repeated `teamId` (1..[HOME_MAX_TEAMS] distinct active teams; absent = the first [HOME_MAX_TEAMS] by
 * name, whoever asks — a caller-dependent default would make a report's content depend on WHO asks, D12); the window is the trailing
 * 90 days ending today. The drill takes `view` and `teamId`, both required; `teamId` must be a real active team (checked by the
 * service).
 */
internal fun Route.homeRoutes(reportService: ReportService) {
    get<ReportHomeRoute> {
        call.caller()
        val teamIds = call.request.queryParameters.repeatedLongs("teamId", maxCount = HOME_MAX_TEAMS, minValue = 1).map {
            if (it > UInt.MAX_VALUE.toLong()) throw BadRequestException("Invalid teamId: $it")
            it.toUInt()
        }
        call.respond(HttpStatusCode.OK, reportService.home(nowMillis(), teamIds))
    }
    get<ReportHomeItemsRoute> {
        call.caller()
        val params = call.request.queryParameters
        val view = params.requireEnum<HomeDrillView>("view")
        val teamId = params.optionalUInt("teamId") ?: throw BadRequestException("teamId is required")
        call.respond(HttpStatusCode.OK, reportService.homeItems(view, teamId, nowMillis()))
    }
}
