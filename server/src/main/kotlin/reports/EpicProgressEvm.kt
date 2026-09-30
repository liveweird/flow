package ch.nokillswit.reports

import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.EpicPlanBaseline
import ch.nokillswit.metrics.MD_SCALE
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.TreeMap
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/** A running (PV, EV, AC) triple in man-days; every derived figure is computed from it. */
internal data class Evm(val pv: BigDecimal, val ev: BigDecimal, val ac: BigDecimal) {
    operator fun plus(other: Evm) = Evm(pv + other.pv, ev + other.ev, ac + other.ac)

    val sv: BigDecimal get() = ev - pv
    val cv: BigDecimal get() = ev - ac
    val spi: Double? get() = if (pv.signum() == 0) null else ev.toDouble() / pv.toDouble()
    val cpi: Double? get() = if (ac.signum() == 0) null else ev.toDouble() / ac.toDouble()
    val isZero: Boolean get() = pv.signum() == 0 && ev.signum() == 0 && ac.signum() == 0
}

internal val NO_EVM = Evm(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)

/** One `agg_daily_flow` day's summed INCREMENT (A23). */
internal class DayIncrement(val day: String, val evm: Evm)

internal fun Evm.toAsOf(day: LocalDate?) = EpicProgressAsOf(day?.toString(), pv.md(), ev.md(), ac.md(), sv.md(), spi, cv.md(), cpi)

internal fun Evm.toRow(kind: EpicProgressKind, id: UInt?, key: String?, name: String, active: Boolean? = null) =
    EpicProgressRow(kind, id, key, name, pv.md(), ev.md(), ac.md(), sv.md(), spi, cv.md(), cpi, active)

/**
 * The `agg_daily_flow` rows of a target: the unit sums every DOMAIN scope (the epic basis, consistent with the EPIC domain view —
 * a sprint sum would count an item in overlapping sprints twice), a team its own TEAM scope (`0` = UNASSIGNED), a domain/epic theirs.
 */
internal fun aggPredicate(resolved: ResolvedTarget, connectionIds: List<UInt>): Op<Boolean> {
    val f = MetricsTables.AggDailyFlow
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
internal suspend fun fetchIncrements(predicate: Op<Boolean>, lastDay: String): List<DayIncrement> {
    val f = MetricsTables.AggDailyFlow
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
internal fun totalsThrough(increments: List<DayIncrement>, days: List<LocalDate>): List<Evm> {
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
internal class EpicDetail(val epic: EpicProgressEpic, private val originalCurve: TreeMap<LocalDate, BigDecimal>?) {
    /** The first baseline's cumulative PV at the end of [day] (`0` before its curve starts); `null` when it has no curve. */
    fun pvOriginalAt(day: LocalDate): BigDecimal? =
        originalCurve?.let { curve -> curve.floorEntry(day)?.value ?: BigDecimal.ZERO }
}

internal suspend fun epicDetail(epic: ResolvedEpic, ctx: ProgressContext): EpicDetail {
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
    val p = MetricsTables.FactEpicPlan
    return p.select(p.baselinedAt, p.startAt, p.dueAt, p.budgetMd, p.budgetSource, p.supersededAt)
        .where { (p.connectionId eq epic.connectionId) and (p.issueId eq epic.issueId) }
        .orderBy(p.baselineSeq to SortOrder.ASC)
        .toList()
        .map { PlanRow(it[p.baselinedAt], it[p.startAt], it[p.dueAt], it[p.budgetMd], it[p.budgetSource], it[p.supersededAt]) }
}

/** With no current baseline the epic's budget is what its delivery fact says: the own estimate, else the child sum (D4). */
private suspend fun deliveredBudget(epic: ResolvedEpic): Pair<BigDecimal?, String?> {
    val e = MetricsTables.FactEpicDelivery
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
        cumulative[point.day] = budget.multiply(BigDecimal(index + 1)).divide(count, MD_SCALE, RoundingMode.HALF_UP)
    }
    return cumulative
}

/**
 * The working days DERIVE spread [plan]'s window over are `metrics.dim_date.is_working_day` (the table is global; a DERIVE at the
 * current settings revision rewrites every row that differs, so it carries the current calendar once any DERIVE of ANY connection
 * at that revision has run — a calendar edited since moves the curve only then). When `dim_date` covers every day of the window
 * that IS the calendar (fed to the kernel as a holiday set over a weekend-less calendar); when it does not — a superseded
 * baseline's window outside the range DERIVE keeps stamped — the current settings calendar stands in.
 */
private suspend fun deriveTimeCalendar(plan: PlanRow, ctx: ProgressContext): WorkingCalendar {
    val first = Instant.ofEpochMilli(plan.startAt!!).atZone(ZoneOffset.UTC).toLocalDate()
    val last = Instant.ofEpochMilli(plan.dueAt!!).atZone(ZoneOffset.UTC).toLocalDate()
    val d = MetricsTables.DimDate
    val stamped = d.select(d.day, d.isWorkingDay).where { (d.day greaterEq first.toString()) and (d.day lessEq last.toString()) }
        .toList().associate { it[d.day] to it[d.isWorkingDay] }
    val windowDays = ChronoUnit.DAYS.between(first, last) + 1
    if (stamped.size.toLong() != windowDays) return ctx.calendar
    val nonWorking = stamped.filterValues { !it }.keys.map { LocalDate.parse(it) }.toSet()
    return WorkingCalendar(WorkingCalendar.zoneOf(ctx.scope.settings.timeZone), emptySet(), nonWorking)
}

/**
 * A20: the share of the MD the team's authors logged from the beginning of time up to the end of `asOf.day` — the same cumulative
 * window as CPI, which it qualifies — that was foreign work (`fact_worklog.foreign_work`). `null` when they logged nothing.
 * `teamId=0` is the authors in no team, whose work is never foreign.
 */
internal suspend fun foreignWorkShare(connectionIds: List<UInt>, teamId: UInt, throughMs: Long): Double? {
    if (connectionIds.isEmpty()) return null
    val w = MetricsTables.FactWorklog
    val author: Op<Boolean> = if (teamId == UNASSIGNED_TEAM_ID) w.authorTeamId.isNull() else w.authorTeamId eq teamId
    val logged = (w.connectionId inList connectionIds) and author and (w.startedAt less throughMs)
    val md = w.md.sum()
    val total = w.select(md).where { logged }.toList().single()[md] ?: BigDecimal.ZERO
    if (total.signum() == 0) return null
    val foreign = w.select(md).where { logged and (w.foreignWork eq true) }.toList().single()[md] ?: BigDecimal.ZERO
    return foreign.toDouble() / total.toDouble()
}
