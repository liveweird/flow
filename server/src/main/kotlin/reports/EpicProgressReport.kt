package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.EpicPlanBaseline
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.teams.TeamService
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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

/** The scope a request selects — at most one of `epicId` (an issue key), `domain`, `teamId`; none is the unit. */
private sealed interface ProgressTarget {
    val level: EpicProgressLevel

    data object WholeUnit : ProgressTarget {
        override val level = EpicProgressLevel.UNIT
    }

    data class Domain(val key: String) : ProgressTarget {
        override val level = EpicProgressLevel.DOMAIN
    }

    data class Epic(val key: String) : ProgressTarget {
        override val level = EpicProgressLevel.EPIC
    }

    data class Team(val teamId: UInt) : ProgressTarget {
        override val level = EpicProgressLevel.TEAM
    }
}

/** A `dim_epic` row an `epicId` resolved to. */
private data class ResolvedEpic(
    val connectionId: UInt,
    val issueId: Long,
    val issueKey: String,
    val summary: String?,
    val startAt: Long?,
    val dueAt: Long?,
)

/** A [ProgressTarget] checked against the data: its response [scope] and, for an epic, its [epic] row. */
private class ResolvedTarget(val target: ProgressTarget, val scope: EpicProgressScope?, val epic: ResolvedEpic?)

/** The request's clock and period, resolved: the connections, the calendar, the days it can list and the day it is read as of. */
private class ProgressContext(
    val scope: ReportScope,
    val calendar: WorkingCalendar,
    val asOfDay: LocalDate,
    val nowMs: Long,
)

/** A running (PV, EV, AC) triple in man-days; every derived figure is computed from it. */
private data class Evm(val pv: BigDecimal, val ev: BigDecimal, val ac: BigDecimal) {
    operator fun plus(other: Evm) = Evm(pv + other.pv, ev + other.ev, ac + other.ac)

    val sv: BigDecimal get() = ev - pv
    val cv: BigDecimal get() = ev - ac
    val spi: Double? get() = if (pv.signum() == 0) null else ev.toDouble() / pv.toDouble()
    val cpi: Double? get() = if (ac.signum() == 0) null else ev.toDouble() / ac.toDouble()
    val isZero: Boolean get() = pv.signum() == 0 && ev.signum() == 0 && ac.signum() == 0
}

private val NO_EVM = Evm(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)

/** One `agg_daily_flow` day's summed INCREMENT (A23). */
private class DayIncrement(val day: String, val evm: Evm)

/** The display name of the `teamId=0` bucket. */
private const val TEAM_UNASSIGNED_NAME = "Unassigned"

private fun BigDecimal.md(): Double = setScale(2, RoundingMode.HALF_UP).toDouble()

private fun Evm.toAsOf(day: LocalDate?) = EpicProgressAsOf(day?.toString(), pv.md(), ev.md(), ac.md(), sv.md(), spi, cv.md(), cpi)

private fun Evm.toRow(kind: EpicProgressKind, id: UInt?, key: String?, name: String, active: Boolean? = null) =
    EpicProgressRow(kind, id, key, name, pv.md(), ev.md(), ac.md(), sv.md(), spi, cv.md(), cpi, active)

/**
 * `GET /api/v1/reports/epic-progress` (v0.3.0 M5 commit 15c, Report 15, `.claude/docs/measures.md` "Report 15 — EVM"):
 * planned value, earned value and actual cost as cumulative curves for one epic, one domain, one team or the whole unit,
 * with SV/SPI/CV/CPI as of `asOf`. Reads `agg_daily_flow`'s per-day INCREMENTS (A23) and sums them at query time from the
 * beginning of time; see `.claude/docs/reports.md` "Report 15".
 */
suspend fun ReportService.epicProgress(filter: ReportFilter, epicKey: String?, nowMs: Long): EpicProgressReport =
    suspendTransaction(database) {
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
            return@suspendTransaction emptyProgress(scope.meta, resolved, notes)
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

/** The scope selected, or `400` for a combination EVM cannot answer (see `.claude/docs/reports.md` "Report 15"). */
private fun progressTargetOf(filter: ReportFilter, epicKey: String?): ProgressTarget {
    if (filter.accountId != null) throw BadRequestException("accountId is not available: EVM has no user level")
    if (filter.domainView == DomainView.TASK) {
        throw BadRequestException("domainView=TASK is not available: EVM is always the EPIC view (D3)")
    }
    if (filter.activityType != null || filter.workCategory != null) {
        throw BadRequestException("activityType and workCategory are not available for the daily EVM aggregates")
    }
    val chosen = listOfNotNull(
        "epicId".takeIf { epicKey != null },
        "domain".takeIf { filter.domain != null },
        "teamId".takeIf { filter.teamId != null },
    )
    if (chosen.size > 1) throw BadRequestException("epicId, domain and teamId are mutually exclusive, got ${chosen.joinToString()}")
    val teamId = filter.teamId
    return when {
        epicKey != null -> ProgressTarget.Epic(epicKey)
        filter.domain != null -> ProgressTarget.Domain(filter.domain)
        teamId != null -> ProgressTarget.Team(teamId)
        else -> ProgressTarget.WholeUnit
    }
}

/**
 * Names the scope and (with [validate]) checks it exists in the connections in scope — `400` for an unknown domain, an unknown
 * epic key or one shared by several connections (narrow with `connectionId`); the team was already checked by
 * [resolveReportScope]. Without [validate] an epic/domain is named by its key alone.
 */
private suspend fun resolveTarget(target: ProgressTarget, connectionIds: List<UInt>, validate: Boolean): ResolvedTarget = when (target) {
    ProgressTarget.WholeUnit -> ResolvedTarget(target, null, null)
    is ProgressTarget.Team -> {
        val name = if (target.teamId == UNASSIGNED_TEAM_ID) TEAM_UNASSIGNED_NAME else teamNames(listOf(target.teamId))[target.teamId]
        ResolvedTarget(target, EpicProgressScope(EpicProgressKind.TEAM, target.teamId, null, name ?: target.teamId.toString()), null)
    }
    is ProgressTarget.Domain -> {
        val name = if (validate) domainName(target.key, connectionIds) else target.key
        ResolvedTarget(target, EpicProgressScope(EpicProgressKind.DOMAIN, null, target.key, name), null)
    }
    is ProgressTarget.Epic -> {
        val epic = if (validate) epicRefOf(target.key, connectionIds) else null
        val name = epic?.let { it.summary ?: it.issueKey } ?: target.key
        ResolvedTarget(target, EpicProgressScope(EpicProgressKind.EPIC, null, target.key, name), epic)
    }
}

private suspend fun domainName(domainKey: String, connectionIds: List<UInt>): String {
    val d = MetricsStore.DimDomain
    val names = d.select(d.name)
        .where { (d.connectionId inList connectionIds) and (d.domainKey eq domainKey) }
        .orderBy(d.connectionId to SortOrder.ASC)
        .toList().map { it[d.name] }
    return names.firstOrNull() ?: throw BadRequestException("Unknown domain: $domainKey")
}

private suspend fun epicRefOf(issueKey: String, connectionIds: List<UInt>): ResolvedEpic {
    val e = MetricsStore.DimEpic
    val rows = e.select(e.connectionId, e.issueId, e.summary, e.startAt, e.dueAt)
        .where { (e.connectionId inList connectionIds) and (e.issueKey eq issueKey) }
        .orderBy(e.connectionId to SortOrder.ASC)
        .toList()
        .map { ResolvedEpic(it[e.connectionId].value, it[e.issueId], issueKey, it[e.summary], it[e.startAt], it[e.dueAt]) }
    if (rows.isEmpty()) throw BadRequestException("Unknown epicId: $issueKey")
    if (rows.map { it.connectionId }.distinct().size > 1) {
        throw BadRequestException("epicId $issueKey exists in several connections; narrow with connectionId")
    }
    return rows.first()
}

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

/**
 * The `agg_daily_flow` rows of a target: the unit sums every DOMAIN scope (the epic basis, consistent with the EPIC domain view —
 * a sprint sum would count an item in overlapping sprints twice), a team its own TEAM scope (`0` = UNASSIGNED), a domain/epic theirs.
 */
private fun aggPredicate(resolved: ResolvedTarget, connectionIds: List<UInt>): Op<Boolean> {
    val f = MetricsStore.AggDailyFlow
    return when (val target = resolved.target) {
        ProgressTarget.WholeUnit -> (f.connectionId inList connectionIds) and (f.scopeKind eq SCOPE_KIND_DOMAIN)
        is ProgressTarget.Team -> {
            val scopeId = if (target.teamId == UNASSIGNED_TEAM_ID) SCOPE_UNASSIGNED else target.teamId.toString()
            (f.connectionId inList connectionIds) and (f.scopeKind eq SCOPE_KIND_TEAM) and (f.scopeId eq scopeId)
        }
        is ProgressTarget.Domain ->
            (f.connectionId inList connectionIds) and (f.scopeKind eq SCOPE_KIND_DOMAIN) and (f.scopeId eq target.key)
        is ProgressTarget.Epic -> {
            // An unresolved epic (nothing derived) never reaches here; an id no row uses reads as zeros.
            val epic = resolved.epic
            val connectionPredicate: Op<Boolean> =
                if (epic == null) f.connectionId inList connectionIds else f.connectionId eq epic.connectionId
            connectionPredicate and (f.scopeKind eq SCOPE_KIND_EPIC) and (f.scopeId eq (epic?.issueId?.toString() ?: target.key))
        }
    }
}

/** Every day's summed INCREMENT of [predicate]'s rows up to [lastDay] (inclusive, ISO), oldest first — from the beginning of time. */
private suspend fun fetchIncrements(predicate: Op<Boolean>, lastDay: String): List<DayIncrement> {
    val f = MetricsStore.AggDailyFlow
    val pv = f.pvMd.sum()
    val ev = f.evMd.sum()
    val ac = f.acMd.sum()
    return f.select(f.day, pv, ev, ac)
        .where { predicate and (f.day lessEq lastDay) }
        .groupBy(f.day)
        .orderBy(f.day to SortOrder.ASC)
        .toList()
        .map { DayIncrement(it[f.day], Evm(it[pv] ?: BigDecimal.ZERO, it[ev] ?: BigDecimal.ZERO, it[ac] ?: BigDecimal.ZERO)) }
}

/** The running sum of [increments] at the END of each of [days] (ascending): everything on or before the day, whenever it happened. */
private fun totalsThrough(increments: List<DayIncrement>, days: List<LocalDate>): List<Evm> {
    var next = 0
    var running = NO_EVM
    return days.map { day ->
        val key = day.toString()
        while (next < increments.size && increments[next].day <= key) running += increments[next++].evm
        running
    }
}

// ---- the EPIC level ----------------------------------------------------------------------------

/** One `fact_epic_plan` row. */
private data class PlanRow(
    val baselinedAt: Long,
    val startAt: Long?,
    val dueAt: Long?,
    val budgetMd: BigDecimal?,
    val budgetSource: String,
    val supersededAt: Long?,
) {
    val isComplete: Boolean get() = startAt != null && dueAt != null && budgetMd != null
}

/** The EPIC block plus its FIRST baseline redrawn: the cumulative PV per working day, rounded like the stored increments. */
private class EpicDetail(val epic: EpicProgressEpic, private val originalCurve: TreeMap<LocalDate, BigDecimal>?) {
    /** The first baseline's cumulative PV at the end of [day] (`0` before its curve starts); `null` when it has no curve. */
    fun pvOriginalAt(day: LocalDate): BigDecimal? =
        originalCurve?.let { curve -> curve.floorEntry(day)?.value ?: BigDecimal.ZERO }
}

private suspend fun epicDetail(epic: ResolvedEpic, ctx: ProgressContext): EpicDetail {
    val plans = planRowsOf(epic)
    val current = plans.lastOrNull { it.supersededAt == null }
    val first = plans.firstOrNull()
    // The stored PV was spread under the connection's DERIVE clock, so the horizon is judged against it too.
    val horizonNowMs = deriveClocks(listOf(epic.connectionId))[epic.connectionId] ?: ctx.nowMs
    val (budget, source) = if (current != null) current.budgetMd to current.budgetSource else deliveredBudget(epic)
    val drift = if (current != null && first != null && current !== first) {
        EpicProgressDrift(
            dates = current.startAt != first.startAt || current.dueAt != first.dueAt,
            budget = differs(current.budgetMd, first.budgetMd),
        )
    } else {
        EpicProgressDrift(dates = false, budget = false)
    }
    val currentInHorizon = current != null && current.isComplete && current.inHorizon(horizonNowMs)
    val block = EpicProgressEpic(
        budgetMd = budget?.md(),
        budgetSource = source,
        startAt = epic.startAt,
        dueAt = epic.dueAt,
        inPvHorizon = currentInHorizon,
        hasPvCurve = currentInHorizon && curveOf(current, ctx) != null,
        baselines = plans.map { EpicProgressBaseline(it.baselinedAt, it.supersededAt, it.startAt, it.dueAt, it.budgetMd?.md()) },
        drift = drift,
    )
    val originalCurve = first?.takeIf { it.isComplete && it.inHorizon(horizonNowMs) }?.let { curveOf(it, ctx) }
    return EpicDetail(block, originalCurve)
}

private fun PlanRow.inHorizon(nowMs: Long): Boolean = DeriveKernels.inPvHorizon(startAt!!, dueAt!!, nowMs)

private fun differs(a: BigDecimal?, b: BigDecimal?): Boolean = if (a == null || b == null) a !== b else a.compareTo(b) != 0

private suspend fun planRowsOf(epic: ResolvedEpic): List<PlanRow> {
    val p = MetricsStore.FactEpicPlan
    return p.select(p.baselinedAt, p.startAt, p.dueAt, p.budgetMd, p.budgetSource, p.supersededAt)
        .where { (p.connectionId eq epic.connectionId) and (p.issueId eq epic.issueId) }
        .orderBy(p.baselineSeq to SortOrder.ASC)
        .toList()
        .map { PlanRow(it[p.baselinedAt], it[p.startAt], it[p.dueAt], it[p.budgetMd], it[p.budgetSource], it[p.supersededAt]) }
}

/** With no current baseline the epic's budget is what its delivery fact says: the own estimate, else the child sum (D4). */
private suspend fun deliveredBudget(epic: ResolvedEpic): Pair<BigDecimal?, String?> {
    val e = MetricsStore.FactEpicDelivery
    val row = e.select(e.budgetSource, e.ownEstimateCurrentMd, e.childSumEstimateMd)
        .where { (e.connectionId eq epic.connectionId) and (e.issueId eq epic.issueId) }
        .toList().firstOrNull() ?: return null to null
    val source = row[e.budgetSource]
    return (if (source == "OWN") row[e.ownEstimateCurrentMd] else row[e.childSumEstimateMd]) to source
}

/**
 * [plan]'s PV curve on the working days [DeriveKernels.pvCurve] picks — under the calendar DERIVE spread the stored PV with (see
 * [deriveTimeCalendar]) — each day's cumulative value rounded exactly as the stored `pv_md` increments are (`ROUND(budget * i / n,
 * 2)`, the last day = the budget), so an unchanged baseline redraws to precisely the stored curve. `null` when the window holds no
 * working day (nowhere to place the budget).
 */
private suspend fun curveOf(plan: PlanRow, ctx: ProgressContext): TreeMap<LocalDate, BigDecimal>? {
    val budget = plan.budgetMd!!
    val baseline = EpicPlanBaseline(plan.baselinedAt, plan.startAt!!, plan.dueAt!!, budget.toDouble(), plan.budgetSource, plan.supersededAt)
    val curve = DeriveKernels.pvCurve(baseline, deriveTimeCalendar(plan, ctx))
    if (curve.isEmpty()) return null
    val count = BigDecimal(curve.size)
    val cumulative = TreeMap<LocalDate, BigDecimal>()
    curve.forEachIndexed { index, point ->
        cumulative[point.day] = budget.multiply(BigDecimal(index + 1)).divide(count, 2, RoundingMode.HALF_UP)
    }
    return cumulative
}

/**
 * The working days DERIVE spread [plan]'s window over are `metrics.dim_date.is_working_day` (stamped at the last derive, so a
 * calendar edited since — or a holiday added — does not move a curve that was never re-derived). When `dim_date` covers every day of
 * the window that IS the calendar (fed to the kernel as a holiday set over a weekend-less calendar); when it does not — a superseded
 * baseline's window outside the range DERIVE keeps stamped — the current settings calendar stands in.
 */
private suspend fun deriveTimeCalendar(plan: PlanRow, ctx: ProgressContext): WorkingCalendar {
    val first = Instant.ofEpochMilli(plan.startAt!!).atZone(ZoneOffset.UTC).toLocalDate()
    val last = Instant.ofEpochMilli(plan.dueAt!!).atZone(ZoneOffset.UTC).toLocalDate()
    val d = MetricsStore.DimDate
    val stamped = d.select(d.day, d.isWorkingDay).where { (d.day greaterEq first.toString()) and (d.day lessEq last.toString()) }
        .toList().associate { it[d.day] to it[d.isWorkingDay] }
    val windowDays = ChronoUnit.DAYS.between(first, last) + 1
    if (stamped.size.toLong() != windowDays) return ctx.calendar
    val nonWorking = stamped.filterValues { !it }.keys.map { LocalDate.parse(it) }.toSet()
    return WorkingCalendar(WorkingCalendar.zoneOf(ctx.scope.settings.timeZone), emptySet(), nonWorking)
}

// ---- the drill tables --------------------------------------------------------------------------

/**
 * A domain's epics: those of `dim_epic.domain_key = domain` with a CURRENT baseline or any EV/AC up to asOf, each with its own
 * EPIC-scope figures. An epic is listed under its current domain, so a domain the epic left keeps that epic's earlier EV/AC in
 * its DOMAIN total (as-was attribution) but not in these rows. Two joins on `dim_epic` — no list of epic ids leaves the database.
 */
private suspend fun domainRows(ctx: ProgressContext, domain: String): List<EpicProgressRow> {
    val connectionIds = ctx.scope.connectionIds
    val d = MetricsStore.DimEpic
    val f = MetricsStore.AggDailyFlow
    val pv = f.pvMd.sum()
    val ev = f.evMd.sum()
    val ac = f.acMd.sum()
    // Every epic of the domain, its EPIC-scope increments up to asOf LEFT-joined (an epic with none still lists its baseline).
    val epics = d.join(
        f,
        JoinType.LEFT,
        onColumn = d.connectionId,
        otherColumn = f.connectionId,
        additionalConstraint = {
            (f.scopeKind eq SCOPE_KIND_EPIC) and (f.scopeId eq d.issueId.castTo(VarCharColumnType(SCOPE_ID_LENGTH))) and
                (f.day lessEq ctx.asOfDay.toString())
        },
    ).select(d.connectionId, d.issueId, d.issueKey, d.summary, pv, ev, ac)
        .where { (d.connectionId inList connectionIds) and (d.domainKey eq domain) }
        .groupBy(d.connectionId, d.issueId, d.issueKey, d.summary)
        .toList()
    if (epics.isEmpty()) return emptyList()
    val p = MetricsStore.FactEpicPlan
    val withBaseline = p.join(d, JoinType.INNER, onColumn = p.connectionId, otherColumn = d.connectionId, additionalConstraint = {
        p.issueId eq d.issueId
    }).select(p.connectionId, p.issueId)
        .where { (d.connectionId inList connectionIds) and (d.domainKey eq domain) and p.supersededAt.isNull() }
        .toList().map { it[p.connectionId].value to it[p.issueId] }.toSet()
    return epics.mapNotNull { row ->
        val evm = Evm(row[pv] ?: BigDecimal.ZERO, row[ev] ?: BigDecimal.ZERO, row[ac] ?: BigDecimal.ZERO)
        val listed = (row[d.connectionId].value to row[d.issueId]) in withBaseline || evm.ev.signum() != 0 || evm.ac.signum() != 0
        if (!listed) return@mapNotNull null
        evm.toRow(EpicProgressKind.EPIC, null, row[d.issueKey], row[d.summary] ?: row[d.issueKey])
    }.sortedWith(compareBy({ it.key }, { it.name }))
}

/** `agg_daily_flow.scope_id`'s width (`VARCHAR(60)`) — what an epic's issue id is cast to for the join. */
private const val SCOPE_ID_LENGTH = 60

/**
 * The unit's drill: one row per domain (every domain the connections know, plus any DOMAIN scope with figures) on the EPIC
 * basis, then one per team (every active team, plus any TEAM scope with figures — UNASSIGNED, a soft-deleted team, marked
 * `active = false`) on the sprint/author basis. The DOMAIN rows sum to the unit's `asOf` (which reads the DOMAIN scopes); the team
 * rows are a different basis (sprint scope, author-team cost) and do not sum to that headline.
 */
private suspend fun unitRows(ctx: ProgressContext): List<EpicProgressRow> {
    val connectionIds = ctx.scope.connectionIds
    val f = MetricsStore.AggDailyFlow
    val pv = f.pvMd.sum()
    val ev = f.evMd.sum()
    val ac = f.acMd.sum()
    val sums = f.select(f.scopeKind, f.scopeId, pv, ev, ac)
        .where {
            (f.connectionId inList connectionIds) and (f.scopeKind inList listOf(SCOPE_KIND_TEAM, SCOPE_KIND_DOMAIN)) and
                (f.day lessEq ctx.asOfDay.toString())
        }
        .groupBy(f.scopeKind, f.scopeId)
        .toList()
        .associate {
            (it[f.scopeKind] to it[f.scopeId]) to Evm(it[pv] ?: BigDecimal.ZERO, it[ev] ?: BigDecimal.ZERO, it[ac] ?: BigDecimal.ZERO)
        }
    return domainUnitRows(connectionIds, sums) + teamUnitRows(sums)
}

private suspend fun domainUnitRows(connectionIds: List<UInt>, sums: Map<Pair<String, String>, Evm>): List<EpicProgressRow> {
    val d = MetricsStore.DimDomain
    val names = d.select(d.domainKey, d.name)
        .where { d.connectionId inList connectionIds }
        .orderBy(d.connectionId to SortOrder.DESC) // the lowest connection id's name wins, as everywhere
        .toList().associate { it[d.domainKey] to it[d.name] }
    val withFigures = sums.filter { (key, evm) -> key.first == SCOPE_KIND_DOMAIN && !evm.isZero }.keys.map { it.second }
    return (names.keys + withFigures).distinct().sorted().map { key ->
        (sums[SCOPE_KIND_DOMAIN to key] ?: NO_EVM).toRow(EpicProgressKind.DOMAIN, null, key, names[key] ?: key)
    }
}

private suspend fun teamUnitRows(sums: Map<Pair<String, String>, Evm>): List<EpicProgressRow> {
    val t = TeamService.Teams
    val active = t.select(t.id, t.name).where { t.active() }.toList().associate { it[t.id].value to it[t.name] }
    fun teamIdOf(scopeId: String): UInt? = if (scopeId == SCOPE_UNASSIGNED) UNASSIGNED_TEAM_ID else scopeId.toUIntOrNull()
    val withFigures = sums.filter { (key, evm) -> key.first == SCOPE_KIND_TEAM && !evm.isZero }.keys.mapNotNull { teamIdOf(it.second) }
    val others = withFigures.filter { it != UNASSIGNED_TEAM_ID && it !in active }
    val names = active + teamNames(others) + (UNASSIGNED_TEAM_ID to TEAM_UNASSIGNED_NAME)
    val ids = (active.keys + withFigures).distinct()
    return ids.sortedWith(compareBy<UInt>({ it == UNASSIGNED_TEAM_ID }, { names[it] ?: it.toString() }, { it })).map { id ->
        val scopeId = if (id == UNASSIGNED_TEAM_ID) SCOPE_UNASSIGNED else id.toString()
        val isActive = id == UNASSIGNED_TEAM_ID || id in active
        (sums[SCOPE_KIND_TEAM to scopeId] ?: NO_EVM).toRow(EpicProgressKind.TEAM, id, null, names[id] ?: id.toString(), isActive)
    }
}

/**
 * A20: the share of the MD the team's authors logged from the beginning of time up to the end of `asOf.day` — the same cumulative
 * window as CPI, which it qualifies — that was foreign work (`fact_worklog.foreign_work`). `null` when they logged nothing.
 * `teamId=0` is the authors in no team, whose work is never foreign.
 */
private suspend fun foreignWorkShare(connectionIds: List<UInt>, teamId: UInt, throughMs: Long): Double? {
    if (connectionIds.isEmpty()) return null
    val w = MetricsStore.FactWorklog
    val author: Op<Boolean> = if (teamId == UNASSIGNED_TEAM_ID) w.authorTeamId.isNull() else w.authorTeamId eq teamId
    val logged = (w.connectionId inList connectionIds) and author and (w.startedAt less throughMs)
    val md = w.md.sum()
    val total = w.select(md).where { logged }.toList().single()[md] ?: BigDecimal.ZERO
    if (total.signum() == 0) return null
    val foreign = w.select(md).where { logged and (w.foreignWork eq true) }.toList().single()[md] ?: BigDecimal.ZERO
    return foreign.toDouble() / total.toDouble()
}
