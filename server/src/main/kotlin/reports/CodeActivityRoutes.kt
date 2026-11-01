package ch.nokillswit.reports

import io.ktor.resources.Resource
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/reports/code-activity")
class ReportCodeActivityRoute

/**
 * Report 21, Code activity (`.claude/docs/reports.md` "Report 21", A50): any signed-in user (D12), read-only, no audit, `call.caller()`
 * first (inside [reportGet]). Read per team (`TEAM_ONLY`: a request naming no `teamId` is a `400`), `accountId` repeats 1..8 (USER level),
 * and the validator moves every five minutes (the open merge requests' ages are at `now`). It is the one report whose tag also carries the
 * GitLab inputs ([codeStamp]: the sources, their sync activity and the identity links), because it reads `norm.code_*` and the links live.
 * The default view is a placeholder echoed in `meta` (a GitLab project is not a Jira domain).
 */
internal fun Route.codeActivityRoutes(reportService: ReportService) {
    reportGet<ReportCodeActivityRoute>(
        reportService, DomainView.TASK, multipleAccounts = true, clock = StampClock.FIVE_MINUTES,
        scope = ReportScopeRule.TEAM_ONLY, withCodeStamp = true,
    ) { filter, _, nowMs ->
        answer { reportService.codeActivity(filter, nowMs) }
    }
}
