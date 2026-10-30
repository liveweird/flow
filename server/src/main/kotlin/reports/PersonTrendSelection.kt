package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.metrics.HistoryHorizon
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException

/** The trailing span a request with no `from`/`to` gets: a year, which is 27 points of 14 days. */
internal const val DEFAULT_TREND_PERIOD_DAYS = 365

/**
 * Parses a person-trend request (report 20) into a [ReportFilter]: exactly one `accountId` (required; a repeated key is a `400` like on
 * every scalar parameter), a `from`/`to` period only (no `teamId`, `lastSprints` or `sprintId`: the person is read across every team
 * they belonged to, like Compare people), then the shared parser with `teamless = true` and a year as the default period. The shared
 * parser's span cap (1100 days) is the report's cap: [MAX_TREND_POINTS] points. Every `400` here needs no data.
 */
internal fun Parameters.parsePersonTrend(calendar: WorkingCalendar, nowMs: Long, historyHorizon: HistoryHorizon?): ReportFilter {
    if (optionalString("accountId") == null) throw BadRequestException("accountId is required: Person trend shows one person")
    if (optionalString("teamId") != null || optionalString("lastSprints") != null || optionalString("sprintId") != null) {
        throw BadRequestException("person-trend takes a from/to period only: teamId, lastSprints and sprintId are not accepted")
    }
    return parseReportFilter(
        calendar, nowMs, DomainView.TASK, historyHorizon, teamless = true, defaultPeriodDays = DEFAULT_TREND_PERIOD_DAYS,
    )
}
