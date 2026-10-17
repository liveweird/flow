package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException

/** Exactly this many distinct `accountId`s make a comparison: the two people. */
internal const val COMPARE_PEOPLE_COUNT = 2

/**
 * Parses a compare-people request (report 18, and its drill) into a [ReportFilter]: exactly [COMPARE_PEOPLE_COUNT] distinct
 * `accountId`s (a repeat that collapses to one is a `400`, like one or three), a `from`/`to` period only (no `teamId`, `lastSprints`
 * or `sprintId`: the two people are read across every team they belonged to), then the shared parser with `teamless = true` -- the
 * ONE place that lifts the `accountId requires teamId` rule. Every `400` here needs no data.
 */
internal fun Parameters.parseComparePeople(calendar: WorkingCalendar, nowMs: Long): ReportFilter {
    val accounts = repeatedStrings("accountId", COMPARE_PEOPLE_COUNT)
    if (accounts.size != COMPARE_PEOPLE_COUNT) {
        throw BadRequestException("accountId must name exactly $COMPARE_PEOPLE_COUNT different people (got ${accounts.size})")
    }
    if (optionalString("teamId") != null || optionalString("lastSprints") != null || optionalString("sprintId") != null) {
        throw BadRequestException("compare-people takes a from/to period only: teamId, lastSprints and sprintId are not accepted")
    }
    return parseReportFilter(calendar, nowMs, DomainView.TASK, multipleAccounts = true, teamless = true)
}
