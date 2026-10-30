package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.repeatedStrings
import ch.nokillswit.infra.paging.singleValue
import ch.nokillswit.metrics.HistoryHorizon
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
internal const val MAX_PERIOD_SPAN_DAYS = 1100

private const val MIN_LAST_SPRINTS = 1
private const val MIN_SPRINT_ID = 1L
private const val MAX_LAST_SPRINTS = 52

/** How many `accountId` values a multi-account report's repeated key takes (every route passing `multipleAccounts = true`). */
private const val MAX_PICKED_ACCOUNTS = 8

/**
 * `teamId`/`accountId` set the level (plan §7): `UNIT` groups by team (a `domain` with no `teamId`: the guard refuses a read with neither),
 * `TEAM` groups by user (a `teamId` alone), `USER` is the one user (`teamId` AND `accountId`
 * together — `assignee_at_commitment`/`current_assignee_account_id`-style reads need to know both
 * "whose number" and "within which team's roster" it is being read against).
 */
@Serializable
enum class ReportLevel { UNIT, TEAM, USER }

/**
 * The scope a report accepts; a request naming none is a `400` (`CLAUDE.md`: a report is read per team or per domain, never for the
 * whole unit). [TEAM_OR_DOMAIN] is almost every report; [TEAM_ONLY] the three with no domain slice (Commitments, Sprint consistency
 * and its drill); [TEAM_DOMAIN_OR_EPIC] Epic progress, which also reads one epic. [UNSCOPED] is Home's own default-period filter only
 * ([homeFilter], which parses no request): Home is team-scoped by its three-team cap (D1), so no route passes it.
 */
enum class ReportScopeRule(internal val refusal: String?) {
    TEAM_OR_DOMAIN("teamId or domain is required: a report is read per team or per domain, never for the whole unit"),
    TEAM_ONLY("teamId is required: this report carries no domain slice and is read per team, never for the whole unit"),
    TEAM_DOMAIN_OR_EPIC(
        "epicId, domain or teamId is required: epic progress is read per epic, per domain or per team, never for the whole unit",
    ),
    UNSCOPED(null),
}

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
    /** `null` = no team narrowing. Below USER level it also means UNIT; at USER level it is `null` only for compare-people (report 18). */
    val teamId: UInt?,
    /**
     * Every picked account, in request order, distinct: empty unless [level] is USER. A report that does not opt in
     * ([parseReportFilter]'s `multipleAccounts`) takes one `accountId` (a repeat is a `400`), so for it this is one element;
     * the opted-in reports take 1..[MAX_PICKED_ACCOUNTS]. This is the ONLY stored form: [accountId] is
     * derived from it, so a `copy(...)` can never leave the two disagreeing (narrowing to no account is
     * `copy(accountIds = emptyList())`).
     */
    val accountIds: List<String> = emptyList(),
    val domainView: DomainView,
    val domain: String?,
    val activityType: String?,
    val workCategory: String?,
    val connectionId: UInt?,
    /**
     * The share (percent, `0..`[MAX_TAIL_PERCENT]) of the largest values every [Distribution] this report builds folds into
     * its histogram's final open bucket (`buildDistribution`); `0` keeps the plain equal-width histogram. A report with no
     * distribution ignores it.
     */
    val tailPercent: Int = DEFAULT_TAIL_PERCENT,
    /**
     * The history horizon this request was parsed under (domain-model A48): `null` = off. A `from`/`to` period was already clamped to it
     * and a period wholly before it refused; the sprint-relative periods and the day-anchored series read it from here, and
     * `meta.historyHorizon` echoes it.
     */
    val historyHorizon: HistoryHorizon? = null,
) {
    /** The first picked account — the single `accountId` every single-account report reads; `null` when none is picked. */
    val accountId: String? get() = accountIds.firstOrNull()
}

/**
 * Parses and structurally validates the query string of a report request into a [ReportFilter].
 * [calendar] supplies the configured zone's day-boundary math (`metrics.settings.time_zone` — this
 * function never reimplements it, per the plan's own instruction) purely to turn `from`/`to` ISO
 * dates into UTC-millis bounds; it needs no working-day/holiday data for that, so a scratch
 * [WorkingCalendar] with empty weekend/holiday sets is a perfectly valid caller for a route that
 * only has the zone at hand. [nowMs] anchors the default 90-day window and is always caller-supplied
 * (never `System.currentTimeMillis()`) so both routes and tests can pin it. [defaultDomainView] is
 * per-report (plan §7: "EPIC for PV/EV/AC-shaped measures … TASK elsewhere") — the caller's own
 * default, never guessed here. [historyHorizon] is the request's A48 horizon (`null` = off), with NO default so a new caller cannot
 * forget it: it clamps a `from`/`to` period (the default one too) and refuses one lying wholly before it. [multipleAccounts] is the
 * per-route opt-in:
 * `accountId` repeats (`?accountId=a&accountId=b`, at most [MAX_PICKED_ACCOUNTS] distinct values, a `400` above) instead of being strictly
 * single-valued like on every other report. [teamless] lifts the `accountId requires teamId` rule so a USER-level filter may carry NO team:
 * ONLY `parseComparePeople` (report 18) and `parsePersonTrend` (report 20) set it, and the filter it yields must never reach a function
 * that needs a team at USER level (`snapshotScopeOf` and `readWipUserCells` refuse it). [defaultPeriodDays] is the trailing span a
 * request with no `from`/`to` gets (default [DEFAULT_PERIOD_DAYS]; only Person trend, report 20, asks for a longer one). [scope] is the
 * report's [ReportScopeRule]: the request must name a `teamId` (`0` included), a `domain` (unless [ReportScopeRule.TEAM_ONLY]), an `epicId`
 * ([ReportScopeRule.TEAM_DOMAIN_OR_EPIC], raw presence so a blank one keeps its own message) or, for a [teamless] report, an `accountId`; a
 * request with none would read the whole unit and is a `400`.
 */
fun Parameters.parseReportFilter(
    calendar: WorkingCalendar,
    nowMs: Long,
    defaultDomainView: DomainView,
    historyHorizon: HistoryHorizon?,
    multipleAccounts: Boolean = false,
    teamless: Boolean = false,
    defaultPeriodDays: Int = DEFAULT_PERIOD_DAYS,
    scope: ReportScopeRule = ReportScopeRule.TEAM_OR_DOMAIN,
): ReportFilter {
    val period = parsePeriod(calendar, nowMs, defaultPeriodDays, historyHorizon)

    val teamId = optionalUInt("teamId")
    val accountIds =
        if (multipleAccounts) repeatedStrings("accountId", MAX_PICKED_ACCOUNTS) else listOfNotNull(optionalString("accountId"))
    val accountId = accountIds.firstOrNull()
    if (accountId != null && teamId == null && !teamless) {
        throw BadRequestException("accountId requires teamId")
    }
    val domain = optionalString("domain")
    requireScope(scope, teamId, domain, teamlessPerson = teamless && accountId != null)
    val level = when {
        accountId != null -> ReportLevel.USER
        teamId != null -> ReportLevel.TEAM
        else -> ReportLevel.UNIT
    }

    return ReportFilter(
        period = period,
        level = level,
        teamId = teamId,
        accountIds = accountIds,
        domainView = optionalEnum<DomainView>("domainView") ?: defaultDomainView,
        domain = domain,
        activityType = optionalString("activityType"),
        workCategory = optionalString("workCategory"),
        connectionId = optionalUInt("connectionId"),
        tailPercent = parseTailPercent(optionalString("tailPercent")),
        historyHorizon = historyHorizon,
    )
}

/** The scope guard of [parseReportFilter]: a request naming no team, domain, epic or (teamless) person is a `400`. */
private fun Parameters.requireScope(scope: ReportScopeRule, teamId: UInt?, domain: String?, teamlessPerson: Boolean) {
    val refusal = scope.refusal ?: return
    val named = teamId != null || teamlessPerson ||
        (scope != ReportScopeRule.TEAM_ONLY && domain != null) ||
        (scope == ReportScopeRule.TEAM_DOMAIN_OR_EPIC && singleValue("epicId") != null)
    if (!named) throw BadRequestException(refusal)
}

private fun parseTailPercent(raw: String?): Int {
    if (raw == null) return DEFAULT_TAIL_PERCENT
    val percent = raw.toIntOrNull() ?: throw BadRequestException("tailPercent must be an integer")
    if (percent !in 0..MAX_TAIL_PERCENT) throw BadRequestException("tailPercent must be between 0 and $MAX_TAIL_PERCENT")
    return percent
}

private fun Parameters.parsePeriod(
    calendar: WorkingCalendar,
    nowMs: Long,
    defaultPeriodDays: Int,
    horizon: HistoryHorizon?,
): ReportPeriod {
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
        else -> parseDateRange(fromRaw, toRaw, calendar, nowMs, defaultPeriodDays, horizon)
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

private fun parseDateRange(
    fromRaw: String?,
    toRaw: String?,
    calendar: WorkingCalendar,
    nowMs: Long,
    defaultPeriodDays: Int,
    horizon: HistoryHorizon?,
): ReportPeriod.DateRange {
    val toDate = toRaw?.let { parseIsoDate(it, "to") } ?: calendar.dayOf(nowMs)
    val fromDate = fromRaw?.let { parseIsoDate(it, "from") } ?: toDate.minusDays((defaultPeriodDays - 1).toLong())
    if (toDate.isBefore(fromDate)) {
        throw BadRequestException("to must not be before from")
    }
    val spanDays = ChronoUnit.DAYS.between(fromDate, toDate) + 1
    if (spanDays > MAX_PERIOD_SPAN_DAYS) {
        throw BadRequestException("from/to span must not exceed $MAX_PERIOD_SPAN_DAYS days")
    }
    // The structural checks above judge the REQUESTED dates; only then does the horizon refuse a period wholly before it or clamp `from`.
    if (horizon != null && toDate.isBefore(horizon.from)) {
        throw BadRequestException("from/to lies entirely before the history horizon (${horizon.from})")
    }
    val clampedFrom = if (horizon != null && fromDate.isBefore(horizon.from)) horizon.from else fromDate
    val fromMs = calendar.dayBoundsMs(clampedFrom).first
    val toMs = calendar.dayBoundsMs(toDate).second
    return ReportPeriod.DateRange(clampedFrom, toDate, fromMs, toMs)
}

internal fun parseIsoDate(raw: String, paramName: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (failure: DateTimeParseException) {
    throw BadRequestException("$paramName must be an ISO date (YYYY-MM-DD)")
}
