package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.metrics.MetricsSettingsService
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/reports/person-trend")
class ReportPersonTrendRoute

/**
 * Report 20, Person trend: any signed-in user (D12), read-only, no audit, `call.caller()` FIRST so the guard wins over every `400`.
 * One person, no capacity rows, so unlike Compare people nothing in the response depends on WHO asks. The report has its own parser
 * ([parsePersonTrend]: one required `accountId`, a `from`/`to` period only, a year by default) instead of [reportGet]'s shared one.
 */
internal fun Route.personTrendRoutes(reportService: ReportService, metricsSettings: MetricsSettingsService) {
    get<ReportPersonTrendRoute> {
        call.caller()
        val nowMs = nowMillis()
        val filter = call.request.queryParameters.parsePersonTrend(reportsWorkingCalendar(metricsSettings), nowMs)
        call.respond(HttpStatusCode.OK, reportService.personTrend(filter, nowMs))
    }
}
