package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.teams.TeamService
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/reports/compare-people")
class ReportComparePeopleRoute

@Serializable
@Resource("/api/v1/reports/compare-people/items")
class ReportComparePeopleItemsRoute

/**
 * Report 18, Compare people: any signed-in user (D12), read-only, no audit, `call.caller()` FIRST so the guard wins over every `400`.
 * The report has its own parser ([parseComparePeople]: exactly two people, a `from`/`to` period only, no team) instead of
 * [reportGet]'s shared one.
 *
 * Per-person capacity is shaped by the caller, not guarded (no 403, no audit; the idiom of A39): an ADMIN, or the ACTIVE
 * manager of EVERY team either person belonged to during the period, gets the four capacity rows and the `capacity` block; everyone
 * else gets the report without those keys. All or nothing for the pair, checked per request (never cached in a claim), and decided
 * BEFORE the report read so the service computes the capacity only for a caller who may see it. A pair with no team in the period
 * has no manager to ask: only an ADMIN sees capacity then. The verdict is part of the ETag (`CapacityGrant.etagShape`).
 */
internal fun Route.comparePeopleRoutes(reportService: ReportService, teams: TeamService) {
    get<ReportComparePeopleRoute> {
        val caller = call.caller()
        val scope = reportService.stampScope(StampClock.DAY)
        val filter = call.request.queryParameters.parseComparePeople(scope.calendar, scope.nowMs)
        val grant = if (caller.isAdmin()) {
            CapacityGrant.Admin
        } else {
            val teamIds = reportService.comparePeopleTeamIds(filter)
            val manages = teamIds.isNotEmpty() && teamIds.all { teams.isActiveManager(it, caller.userId) }
            if (manages) CapacityGrant.Teams(teamIds) else CapacityGrant.None
        }
        // The one caller-shaped answer: the verdict is part of the validator, so a 304 can never hand capacity rows to a caller who
        // no longer (or never) holds the grant, nor withhold them from one who now does.
        val etag = reportService.etagFor(call, scope, grant.etagShape())
        call.respondRevalidated(etag) { reportService.comparePeople(filter, scope.nowMs, grant) }
    }
    // The drill: the items behind one person's cell. The parent's parameters (its own parser, so the same 400s first), then the cell's
    // identity: `view` and `segmentAccountId` (both required) and, for the cycle-time views, an optional `bucketIndex`.
    get<ReportComparePeopleItemsRoute> {
        call.caller()
        val params = call.request.queryParameters
        val scope = reportService.stampScope(StampClock.DAY)
        val filter = params.parseComparePeople(scope.calendar, scope.nowMs)
        val view = params.requireEnum<CompareDrillView>("view")
        val bucketIndex = params.optionalString("bucketIndex")?.let { params.requireBucketIndex() }
        val segmentAccountId = params.optionalString("segmentAccountId")
        call.respondRevalidated(reportService.etagFor(call, scope)) {
            reportService.comparePeopleItems(filter, view, bucketIndex, segmentAccountId, scope.nowMs)
        }
    }
}
