package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/** The default period when no `from`/`to`/`lastSprints`/`sprintId` is given: the trailing 90 days, `to` = today (v0.3.0 plan §7). */
private const val DEFAULT_PERIOD_DAYS = 90

/** `to` (inclusive) minus `from` (inclusive) must not exceed this many calendar days (plan §7). */
private const val MAX_PERIOD_SPAN_DAYS = 1100

private const val MIN_LAST_SPRINTS = 1
private const val MIN_SPRINT_ID = 1L
private const val MAX_LAST_SPRINTS = 52

/**
 * `teamId`/`accountId` set the level (plan §7): `UNIT` groups by team (the default, no `teamId`),
 * `TEAM` groups by user (a `teamId` alone), `USER` is the one user (`teamId` AND `accountId`
 * together — `assignee_at_commitment`/`current_assignee_account_id`-style reads need to know both
 * "whose number" and "within which team's roster" it is being read against).
 */
@Serializable
enum class ReportLevel { UNIT, TEAM, USER }

/** D3's two domain views — per-report default, passed in by the caller (`.claude/docs/domain-model.md` "Amendments"). */
@Serializable
enum class DomainView { TASK, EPIC }

/**
 * The three MUTUALLY EXCLUSIVE ways a report's period is selected (plan §7): an explicit calendar
 * range, each team's last N closed sprints (union at unit level), or one specific sprint. Only
 * [DateRange] resolves to UTC-millis bounds up front — [LastSprints]/[BySprintId] name sprints a
 * report's own service still has to look up (`.claude/docs/domain-model.md` "sprint-relative
 * periods are per team"), so their own bounds are report-specific and computed downstream.
 */
sealed interface ReportPeriod {
    /** [fromMs]/[toMs] are the half-open `[fromMs, toMs)` UTC-millis bounds — `toMs` is the day AFTER [toDate] starts (inclusive `to`). */
    data class DateRange(val fromDate: LocalDate, val toDate: LocalDate, val fromMs: Long, val toMs: Long) : ReportPeriod
    data class LastSprints(val count: Int) : ReportPeriod
    data class BySprintId(val sprintId: Long) : ReportPeriod
}

/**
 * The shared, parsed-and-structurally-validated filter every report endpoint reads (v0.3.0 M4
 * commit 10a, plan §7 "Reports API"). [parseReportFilter] does ONLY structural validation (ranges,
 * mutual exclusion, ISO date syntax) — id existence (`teamId`, `sprintId`, `connectionId` against
 * the database) is validated by each report's OWN service once it reads this filter, never here
 * (`400`, the client-supplied-FK idiom already used throughout `metrics/`) — this file has no DB
 * dependency of its own, which is what keeps [ReportFilterTest] a pure unit test.
 *
 * [teamId] `0` is the UNASSIGNED sentinel bucket (a real, meaningful value — never "absent"), so it
 * is never rejected here; a positive [teamId] still needs a DB check downstream. [workCategory]
 * accepts the literal `UNCATEGORIZED` value as well as a real category name — again, structural
 * only, a real service decides which strings are valid categories for the connection(s) in scope.
 */
data class ReportFilter(
    val period: ReportPeriod,
    val level: ReportLevel,
    val teamId: UInt?,
    val accountId: String?,
    val domainView: DomainView,
    val domain: String?,
    val activityType: String?,
    val workCategory: String?,
    val connectionId: UInt?,
)

/**
 * Parses and structurally validates the query string of a report request into a [ReportFilter].
 * [calendar] supplies the configured zone's day-boundary math (`metrics.settings.time_zone` — this
 * function never reimplements it, per the plan's own instruction) purely to turn `from`/`to` ISO
 * dates into UTC-millis bounds; it needs no working-day/holiday data for that, so a scratch
 * [WorkingCalendar] with empty weekend/holiday sets is a perfectly valid caller for a route that
 * only has the zone at hand. [nowMs] anchors the default 90-day window and is always caller-supplied
 * (never `System.currentTimeMillis()`) so both routes and tests can pin it. [defaultDomainView] is
 * per-report (plan §7: "EPIC for PV/EV/AC-shaped measures … TASK elsewhere") — the caller's own
 * default, never guessed here.
 */
fun Parameters.parseReportFilter(calendar: WorkingCalendar, nowMs: Long, defaultDomainView: DomainView): ReportFilter {
    val period = parsePeriod(calendar, nowMs)

    val teamId = optionalUInt("teamId")
    val accountId = optionalString("accountId")
    if (accountId != null && teamId == null) {
        throw BadRequestException("accountId requires teamId")
    }
    val level = when {
        accountId != null -> ReportLevel.USER
        teamId != null -> ReportLevel.TEAM
        else -> ReportLevel.UNIT
    }

    return ReportFilter(
        period = period,
        level = level,
        teamId = teamId,
        accountId = accountId,
        domainView = optionalEnum<DomainView>("domainView") ?: defaultDomainView,
        domain = optionalString("domain"),
        activityType = optionalString("activityType"),
        workCategory = optionalString("workCategory"),
        connectionId = optionalUInt("connectionId"),
    )
}

private fun Parameters.parsePeriod(calendar: WorkingCalendar, nowMs: Long): ReportPeriod {
    val fromRaw = optionalString("from")
    val toRaw = optionalString("to")
    val lastSprintsRaw = optionalString("lastSprints")
    val sprintIdRaw = optionalString("sprintId")

    val modesRequested = listOf(fromRaw != null || toRaw != null, lastSprintsRaw != null, sprintIdRaw != null).count { it }
    if (modesRequested > 1) {
        throw BadRequestException("from/to, lastSprints and sprintId are mutually exclusive")
    }

    return when {
        lastSprintsRaw != null -> ReportPeriod.LastSprints(parseLastSprints(lastSprintsRaw))
        sprintIdRaw != null -> ReportPeriod.BySprintId(parseSprintId(sprintIdRaw))
        else -> parseDateRange(fromRaw, toRaw, calendar, nowMs)
    }
}

private fun parseLastSprints(raw: String): Int {
    val count = raw.toIntOrNull() ?: throw BadRequestException("lastSprints must be an integer")
    if (count !in MIN_LAST_SPRINTS..MAX_LAST_SPRINTS) {
        throw BadRequestException("lastSprints must be between $MIN_LAST_SPRINTS and $MAX_LAST_SPRINTS")
    }
    return count
}

private fun parseSprintId(raw: String): Long {
    val id = raw.toLongOrNull() ?: throw BadRequestException("sprintId must be an integer")
    // The spec declares `minimum: 1` (Jira sprint ids are positive); a smaller value can name no sprint anyway.
    if (id < MIN_SPRINT_ID) throw BadRequestException("sprintId must be at least $MIN_SPRINT_ID")
    return id
}

private fun parseDateRange(fromRaw: String?, toRaw: String?, calendar: WorkingCalendar, nowMs: Long): ReportPeriod.DateRange {
    val toDate = toRaw?.let { parseIsoDate(it, "to") } ?: calendar.dayOf(nowMs)
    val fromDate = fromRaw?.let { parseIsoDate(it, "from") } ?: toDate.minusDays((DEFAULT_PERIOD_DAYS - 1).toLong())
    if (toDate.isBefore(fromDate)) {
        throw BadRequestException("to must not be before from")
    }
    val spanDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1
    if (spanDays > MAX_PERIOD_SPAN_DAYS) {
        throw BadRequestException("from/to span must not exceed $MAX_PERIOD_SPAN_DAYS days")
    }
    val fromMs = calendar.dayBoundsMs(fromDate).first
    val toMs = calendar.dayBoundsMs(toDate).second
    return ReportPeriod.DateRange(fromDate, toDate, fromMs, toMs)
}

internal fun parseIsoDate(raw: String, paramName: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (failure: DateTimeParseException) {
    throw BadRequestException("$paramName must be an ISO date (YYYY-MM-DD)")
}
