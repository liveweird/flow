package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import java.time.LocalDate
import kotlinx.serialization.Serializable

/** What an epic-progress read is about: the whole unit, one domain, one epic or one team (`level` of the response). */
@Serializable
enum class EpicProgressLevel { UNIT, DOMAIN, EPIC, TEAM }

/** What a [EpicProgressScope] or a drill [EpicProgressRow] stands for. */
@Serializable
enum class EpicProgressKind { EPIC, DOMAIN, TEAM }

/**
 * The one thing the curve is about (`null` in the response at UNIT level). [key] is the epic's issue key or the domain key,
 * [id] the team id (`0` = UNASSIGNED); [name] is the epic's summary (its key when it has none), the domain or the team name.
 */
@Serializable
data class EpicProgressScope(val kind: EpicProgressKind, val id: UInt?, val key: String?, val name: String)

/**
 * One calendar day of the series, in the configured zone: the CUMULATIVE [pv]/[ev]/[ac] at the END of that day (running sums
 * that include everything before the period). [pvOriginal] is the EPIC level's first baseline redrawn on the same days
 * (`null` at every other level and when that baseline has no curve).
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
 * One drill row (a domain's epic; a domain or a team of the unit), its figures as of the response's `asOf` day. [active] is set on
 * TEAM rows only: `false` marks a soft-deleted team that still has figures, whose own drill (`teamId`) answers 400.
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
    /** One point per calendar day of the period up to `asOf.day`; empty at UNIT level. */
    val series: List<EpicProgressPoint>,
    val asOf: EpicProgressAsOf,
    /** EPIC level only. */
    val epic: EpicProgressEpic?,
    /** TEAM level only: the share (0..1) of the authors' logged MD up to `asOf.day` (CPI's window) that was foreign (A20). */
    val foreignWorkShare: Double?,
    /** The drill table: a domain's epics (DOMAIN level), or the domains and teams (UNIT level); empty at EPIC and TEAM level. */
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
)

/**
 * `GET /api/v1/reports/epic-progress` (v0.3.0 M5 commit 15c, Report 15, `.claude/docs/measures.md` "Report 15 — EVM"):
 * planned value, earned value and actual cost as cumulative curves for one epic, one domain, one team or the whole unit,
 * with SV/SPI/CV/CPI as of `asOf`. Reads `agg_daily_flow`'s per-day INCREMENTS (A23) and sums them at query time from the
 * beginning of time; see `.claude/docs/reports.md` "Report 15".
 */
suspend fun ReportService.epicProgress(filter: ReportFilter, epicKey: String?, nowMs: Long): EpicProgressReport =
    reportTransaction {
        val target = progressTargetOf(filter, epicKey)
        val scope = resolveReportScope(filter, nowMs)
        val calendar = WorkingCalendar.of(scope.settings)
        val coverage = derivedCoverage(scope.connectionIds, calendar)
        val notes = snapshotNotes(scope, filter, coverage).toMutableList()
        val coveredThrough = coverage.day
        val window = scope.window
        // Nothing derived: an epic/domain cannot be checked against dimensions that do not exist yet, so the answer is empty + the note.
        val resolved = resolveTarget(target, scope.connectionIds, validate = coveredThrough != null)
        if (coveredThrough == null || window == null) {
            return@reportTransaction emptyProgress(scope.meta, resolved, notes)
        }
        // asOf = min(end of period, now), and never beyond the last derived day (EV and AC are unknown past it, PV alone is not a triple).
        val asOfDay = minOf(coveredThrough, calendar.dayOf(nowMs), calendar.dayOf(window.second - 1))
        val ctx = ProgressContext(scope, calendar, asOfDay, nowMs)
        progressOf(ctx, resolved, notes)
    }

private fun emptyProgress(meta: ReportMeta, resolved: ResolvedTarget, notes: List<String>) = EpicProgressReport(
    meta = meta,
    level = resolved.target.level,
    scope = resolved.scope,
    series = emptyList(),
    asOf = NO_EVM.toAsOf(null),
    epic = null,
    foreignWorkShare = null,
    rows = emptyList(),
    note = notes.joinToString(". ").ifEmpty { null },
)

private suspend fun progressOf(ctx: ProgressContext, resolved: ResolvedTarget, notes: MutableList<String>): EpicProgressReport {
    val target = resolved.target
    val increments = fetchIncrements(aggPredicate(resolved, ctx.scope.connectionIds), ctx.asOfDay.toString())
    val asOf = totalsThrough(increments, listOf(ctx.asOfDay)).single()
    val days = if (target is ProgressTarget.WholeUnit) emptyList() else snapshotDays(ctx.scope.window, ctx.calendar, ctx.asOfDay)
    if (days.isEmpty() && target !is ProgressTarget.WholeUnit) {
        notes += "The period lies after ${ctx.asOfDay} (the last derived or current day): there is no series to list, asOf is that day"
    }
    val detail = resolved.epic?.let { epicDetail(it, ctx) }
    val totals = totalsThrough(increments, days)
    val series = days.mapIndexed { index, day ->
        val cumulative = totals[index]
        EpicProgressPoint(day.toString(), cumulative.pv.md(), cumulative.ev.md(), cumulative.ac.md(), detail?.pvOriginalAt(day)?.md())
    }
    val rows = when (target) {
        is ProgressTarget.Domain -> domainRows(ctx, target.key)
        ProgressTarget.WholeUnit -> unitRows(ctx)
        is ProgressTarget.Epic, is ProgressTarget.Team -> emptyList()
    }
    val throughMs = ctx.calendar.dayBoundsMs(ctx.asOfDay).second
    val foreign = (target as? ProgressTarget.Team)?.let { foreignWorkShare(ctx.scope.connectionIds, it.teamId, throughMs) }
    return EpicProgressReport(
        meta = ctx.scope.meta,
        level = target.level,
        scope = resolved.scope,
        series = series,
        asOf = asOf.toAsOf(ctx.asOfDay),
        epic = detail?.epic,
        foreignWorkShare = foreign,
        rows = rows,
        note = notes.joinToString(". ").ifEmpty { null },
    )
}
