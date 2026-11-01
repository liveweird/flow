package ch.nokillswit.reports

import io.ktor.resources.Resource
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/reports/task-code")
class ReportTaskCodeRoute

/**
 * Report 22, Tasks and code (`.claude/docs/reports.md` "Report 22", A51): any signed-in user (D12), read-only, no audit, `call.caller()`
 * first (inside [reportGet]). Read per team or per domain (`TEAM_OR_DOMAIN`, the cycle-time scope) over the done tasks of the Jira star;
 * `connectionId` names a JIRA source, like every Jira report. No user level in this slice: an `accountId` is a `400`, raised with the
 * other structural refusals BEFORE the cache validators. It reads `norm.code_*` live, so its tag also carries the GitLab inputs
 * ([codeStamp]: the live merge requests ride its `code:` / `code-ingest:` lines). The clock is the DAY, like Cycle time's: the done tasks
 * come from the derived facts (`run:`), so an open sprint's window ending at `now` cannot change the population between two DERIVEs. No
 * permit: the merge requests are read by the keys of the done tasks and bounded by the row budget, like Compare people's code reads.
 */
internal fun Route.taskCodeRoutes(reportService: ReportService) {
    reportGet<ReportTaskCodeRoute>(reportService, DomainView.TASK, withCodeStamp = true) { filter, _, nowMs ->
        if (filter.accountId != null) throw BadRequestException("accountId is not available: Tasks and code has no user level")
        answer { reportService.taskCode(filter, nowMs) }
    }
}
