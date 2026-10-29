package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.resources.Resource
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.path
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/reports/person-trend")
class ReportPersonTrendRoute

@Serializable
@Resource("/api/v1/reports/person-trend/items")
class ReportPersonTrendItemsRoute

/** The drill views a trend point can open: those of the report's own rows (so a view of a Compare-people-only row is a `400`). */
private val TREND_DRILL_VIEWS: Set<CompareDrillView> = TREND_ROWS.mapNotNull { it.drill }.toSet()

/**
 * Report 20, Person trend: any signed-in user (D12), read-only, no audit, `call.caller()` FIRST so the guard wins over every `400`.
 * One person, no capacity rows, so unlike Compare people nothing in the response depends on WHO asks. The report has its own parser
 * ([parsePersonTrend]: one required `accountId`, a `from`/`to` period only, a year by default) instead of [reportGet]'s shared one.
 */
internal fun Route.personTrendRoutes(reportService: ReportService) {
    get<ReportPersonTrendRoute> {
        call.caller()
        val scope = reportService.stampScope(StampClock.DAY)
        val filter = call.request.queryParameters.parsePersonTrend(scope.calendar, scope.nowMs)
        // The stamp is read ONCE, before the report transaction: it is both the ETag's and the peer-cell cache's generation.
        val ticket = reportService.peerCells.nextTicket()
        val stamp = reportService.dataStamp(scope.settings, scope.calendar, scope.clock, scope.nowMs)
        val tag = reportEtag(reportService.codeIdentity, call.request.path(), call.request.queryParameters, stamp)
        call.respondRevalidated(tag) { reportService.personTrend(filter, scope.nowMs, stamp, ticket) }
    }
    // The drill: the items behind one point of one row. The parent's parameters (its own parser, so the same 400s first), then the
    // point's identity: `view` (one of the rows') and the required `pointEnd` (a day of the request's grid). The list IS Report 18's drill
    // for the person over that point's window, so it reconciles with the point exactly as Compare people's does with its cell.
    get<ReportPersonTrendItemsRoute> {
        call.caller()
        val params = call.request.queryParameters
        val scope = reportService.stampScope(StampClock.DAY)
        val filter = params.parsePersonTrend(scope.calendar, scope.nowMs)
        val view = params.requireEnum<CompareDrillView>("view")
        if (view !in TREND_DRILL_VIEWS) {
            throw BadRequestException("view must be one of the trend rows' drills (${TREND_DRILL_VIEWS.joinToString { it.name }})")
        }
        if (params.optionalString("bucketIndex") != null) {
            throw BadRequestException("bucketIndex does not apply to a trend point: the list is the person's whole set in the window")
        }
        val pointFilter = filter.atPoint(params.optionalString("pointEnd"), scope.calendar)
        call.respondRevalidated(reportService.etagFor(call, scope)) {
            reportService.comparePeopleItems(pointFilter, view, null, filter.accountIds.single(), scope.nowMs)
        }
    }
}

/**
 * [this] with its period narrowed to the window of the grid point [pointEnd] (REQUIRED; an ISO date that is one of the request's
 * [pointEnds], else `400`), bounded by the very arithmetic the report's own points use ([trendWindowOf]).
 */
internal fun ReportFilter.atPoint(pointEnd: String?, calendar: WorkingCalendar): ReportFilter {
    val raw = pointEnd ?: throw BadRequestException("pointEnd is required: the day a point of the trend ends on")
    val end = parseIsoDate(raw, "pointEnd")
    val range = period as ReportPeriod.DateRange
    if (end !in pointEnds(range.fromDate, range.toDate)) {
        throw BadRequestException("pointEnd must be one of the trend's point days (every $TREND_STEP_DAYS days back from to)")
    }
    val window = trendWindowOf(end, calendar)
    return copy(period = ReportPeriod.DateRange(window.from, window.end, window.bounds.first, window.bounds.second))
}
