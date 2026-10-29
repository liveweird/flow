package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * What an epic-progress read is about: one domain, one epic or one team (`level` of the response). [UNIT], the whole unit, is no
 * longer answered (the scope guard refuses a request naming no epic, domain or team); the value stays on the wire until the web drops
 * its UNIT rows (BACKLOG).
 */
@Serializable
enum class EpicProgressLevel { UNIT, DOMAIN, EPIC, TEAM }

/** What a [EpicProgressScope] or a drill [EpicProgressRow] stands for. */
@Serializable
enum class EpicProgressKind { EPIC, DOMAIN, TEAM }

/**
 * The one thing the curve is about. [key] is the epic's issue key or the domain key,
 * [id] the team id (`0` = UNASSIGNED); [name] is the epic's summary (its key when it has none), the domain or the team name.
 */
@Serializable
data class EpicProgressScope(val kind: EpicProgressKind, val id: UInt?, val key: String?, val name: String)

/**
 * One calendar day of the series, in the configured zone: [pv]/[ev]/[ac] at the END of that day — running sums from the beginning
 * of time under `basis=CUMULATIVE`, or minus the sum before the period's first day under `basis=PERIOD` (A37: the curves start
 * near 0). [pvOriginal] is the EPIC level's first baseline redrawn on the same days, rebased the same way (`null` at every other
 * level and when that baseline has no curve).
 */
@Serializable
data class EpicProgressPoint(val date: String, val pv: Double, val ev: Double, val ac: Double, val pvOriginal: Double?)

/**
 * The figures at [day] (the response's `asOf` day; `null` when there is nothing to read): [sv] = EV − PV, [spi] = EV ÷ PV
 * (`null` when PV is 0), [cv] = EV − AC, [cpi] = EV ÷ AC (`null` when AC is 0). MD at two decimals, the ratios unrounded.
 */
@Serializable
data class EpicProgressAsOf(
    val day: String?,
    val pv: Double,
    val ev: Double,
    val ac: Double,
    val sv: Double,
    val spi: Double?,
    val cv: Double,
    val cpi: Double?,
)

/**
 * One drill row (a domain's epic; a domain or a team of the unit), its CUMULATIVE figures as of the response's `asOf` day and
 * its [period] figures beside them (A37). [active] is set on TEAM rows only: `false` marks a soft-deleted team that still has
 * figures, whose own drill (`teamId`) answers 400.
 */
@Serializable
data class EpicProgressRow(
    val kind: EpicProgressKind,
    val id: UInt?,
    val key: String?,
    val name: String,
    val pv: Double,
    val ev: Double,
    val ac: Double,
    val sv: Double,
    val spi: Double?,
    val cv: Double,
    val cpi: Double?,
    val period: EpicProgressRowPeriod,
    val active: Boolean? = null,
)

/**
 * One `fact_epic_plan` baseline: in effect from [effectiveFrom] until [supersededAt] (`null` = the current one). [startAt]/[dueAt]
 * are the planned calendar dates as UTC-midnight millis.
 */
@Serializable
data class EpicProgressBaseline(
    val effectiveFrom: Long,
    val supersededAt: Long?,
    val startAt: Long?,
    val dueAt: Long?,
    val budgetMd: Double?,
)

/** Whether the CURRENT baseline differs from the FIRST one: in its start/due [dates] and/or its [budget]. */
@Serializable
data class EpicProgressDrift(val dates: Boolean, val budget: Boolean)

/**
 * The EPIC level's plan facts: the current [budgetMd] and its [budgetSource] (`OWN` or `CHILDREN`, D4), the epic's own
 * [startAt]/[dueAt] (UTC-midnight millis of the calendar dates, `null` when unset), [inPvHorizon] (the current baseline is
 * complete and both its dates lie within ±10 years of the DERIVE clock, A23), [hasPvCurve] (in the horizon AND its window holds a
 * working day, so PV is actually spread) and every [baselines] entry, oldest first.
 */
@Serializable
data class EpicProgressEpic(
    val budgetMd: Double?,
    val budgetSource: String?,
    val startAt: Long?,
    val dueAt: Long?,
    val inPvHorizon: Boolean,
    val hasPvCurve: Boolean,
    val baselines: List<EpicProgressBaseline>,
    val drift: EpicProgressDrift,
)

@Serializable
data class EpicProgressReport(
    val meta: ReportMeta,
    val level: EpicProgressLevel,
    val scope: EpicProgressScope?,
    /** The EFFECTIVE basis of `series`: what `basis` asked for, else the level's default (PERIOD, but CUMULATIVE for EPIC). */
    val basis: EpicProgressBasis,
    /** One point per calendar day of the period up to `asOf.day`, on [basis]. */
    val series: List<EpicProgressPoint>,
    /** The CUMULATIVE figures, whatever the basis (A37). */
    val asOf: EpicProgressAsOf,
    /** The figures inside the period, whatever the basis (A37). */
    val period: EpicProgressPeriod,
    /** SPI and CPI per week end over the trailing `trendWindowDays` (empty when the period lies after `asOf.day`). */
    val trend: List<EpicProgressTrendPoint>,
    /** EPIC level only. */
    val epic: EpicProgressEpic?,
    /** TEAM level only: the share (0..1) of the authors' logged MD up to `asOf.day` (CPI's window) that was foreign (A20). */
    val foreignWorkShare: Double?,
    /** TEAM level only: the same share over `fact_worklog` rows started inside the period (CPI's window under `basis=PERIOD`). */
    val foreignWorkSharePeriod: Double?,
    /** The drill table: a domain's epics (DOMAIN level); empty at EPIC and TEAM level. */
    val rows: List<EpicProgressRow>,
    /** Why the answer is empty or partial (not derived yet, no sprint resolved, a period past `asOf`, ...); `null` otherwise. */
    val note: String?,
)

/** The request's clock and period, resolved: the connections, the calendar, the days it can list and the day it is read as of. */
internal class ProgressContext(
    val scope: ReportScope,
    val calendar: WorkingCalendar,
    val asOfDay: LocalDate,
    val nowMs: Long,
) {
    /** Every calendar day of the period up to [asOfDay] — empty when the period lies after it. */
    val days: List<LocalDate> by lazy { snapshotDays(scope.window, calendar, asOfDay) }

    /** The period's first listed day (A37), `null` for an empty period. */
    val firstDay: LocalDate? get() = days.firstOrNull()
}

/**
 * `GET /api/v1/reports/epic-progress` (v0.3.0 M5 commit 15c, Report 15, `.claude/docs/measures.md` "Report 15 — EVM"):
 * planned value, earned value and actual cost as cumulative curves for one epic, one domain, one team or the whole unit,
 * with SV/SPI/CV/CPI as of `asOf`. Reads `agg_daily_flow`'s per-day INCREMENTS (A23) and sums them at query time from the
 * beginning of time; see `.claude/docs/reports.md` "Report 15".
 */
internal suspend fun ReportService.epicProgress(
    filter: ReportFilter,
    epicKey: String?,
    nowMs: Long,
    options: EpicProgressOptions = EpicProgressOptions(),
): EpicProgressReport =
    reportTransaction {
        val target = progressTargetOf(filter, epicKey)
        val scope = resolveReportScope(filter, nowMs)
        val calendar = scope.calendar
        val coverage = derivedCoverage(scope.connectionIds, calendar)
        val notes = snapshotNotes(scope, filter, coverage).toMutableList()
        val coveredThrough = coverage.day
        val window = scope.window
        // Nothing derived: an epic/domain cannot be checked against dimensions that do not exist yet, so the answer is empty + the note.
        val resolved = resolveTarget(target, scope.connectionIds, validate = coveredThrough != null)
        if (coveredThrough == null || window == null) {
            return@reportTransaction emptyProgress(scope.meta, resolved, options, notes)
        }
        // asOf = min(end of period, now), and never beyond the last derived day (EV and AC are unknown past it, PV alone is not a triple).
        val asOfDay = minOf(coveredThrough, calendar.dayOf(nowMs), calendar.dayOf(window.second - 1))
        val ctx = ProgressContext(scope, calendar, asOfDay, nowMs)
        progressOf(ctx, resolved, options, notes)
    }

private fun emptyProgress(
    meta: ReportMeta,
    resolved: ResolvedTarget,
    options: EpicProgressOptions,
    notes: List<String>,
) = EpicProgressReport(
    meta = meta,
    level = resolved.target.level,
    scope = resolved.scope,
    basis = options.basis ?: resolved.target.level.defaultBasis(),
    series = emptyList(),
    asOf = NO_EVM.toAsOf(null),
    period = NO_PERIOD,
    trend = emptyList(),
    epic = null,
    foreignWorkShare = null,
    foreignWorkSharePeriod = null,
    rows = emptyList(),
    note = notes.joinToString(". ").ifEmpty { null },
)

private suspend fun progressOf(
    ctx: ProgressContext,
    resolved: ResolvedTarget,
    options: EpicProgressOptions,
    notes: MutableList<String>,
): EpicProgressReport {
    val target = resolved.target
    val basis = options.basis ?: target.level.defaultBasis()
    val increments = fetchIncrements(aggPredicate(resolved, ctx.scope.connectionIds), ctx.asOfDay.toString())
    val asOf = totalsThrough(increments, listOf(ctx.asOfDay)).single()
    val firstDay = ctx.firstDay
    if (firstDay == null) {
        notes += "The period lies after ${ctx.asOfDay} (the last derived or current day): there is no series to list, asOf is that day"
    }
    val (before, delta) = periodDelta(increments, firstDay, ctx.asOfDay)
    val rebased = basis == EpicProgressBasis.PERIOD && firstDay != null
    val detail = resolved.epic?.let { epicDetail(it, ctx) }
    val series = progressSeries(
        ctx.days,
        totalsThrough(increments, ctx.days),
        base = if (rebased) before else NO_EVM,
        rebaseAfter = firstDay?.minusDays(1)?.takeIf { rebased },
        detail = detail,
    )
    val rows = when (target) {
        is ProgressTarget.Domain -> domainRows(ctx, target.key)
        is ProgressTarget.Epic, is ProgressTarget.Team -> emptyList()
    }
    val team = target as? ProgressTarget.Team
    val throughMs = ctx.calendar.dayBoundsMs(ctx.asOfDay).second
    val foreign = team?.let { foreignWorkShare(ctx.scope.connectionIds, it.teamId, throughMs) }
    val foreignPeriod = if (team == null || firstDay == null) null else {
        foreignWorkShare(ctx.scope.connectionIds, team.teamId, throughMs, sinceMs = ctx.calendar.dayBoundsMs(firstDay).first)
    }
    return EpicProgressReport(
        meta = ctx.scope.meta,
        level = target.level,
        scope = resolved.scope,
        basis = basis,
        series = series,
        asOf = asOf.toAsOf(ctx.asOfDay),
        period = delta.toPeriod(firstDay),
        trend = if (firstDay == null) emptyList() else trendOf(increments, trendDays(firstDay, ctx.asOfDay), options.trendWindowDays),
        epic = detail?.epic,
        foreignWorkShare = foreign,
        foreignWorkSharePeriod = foreignPeriod,
        rows = rows,
        note = notes.joinToString(". ").ifEmpty { null },
    )
}
