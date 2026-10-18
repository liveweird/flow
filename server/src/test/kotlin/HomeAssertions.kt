package ch.nokillswit

import ch.nokillswit.reports.HomeFigure
import ch.nokillswit.reports.HomePredLine
import ch.nokillswit.reports.HomeTeam
import ch.nokillswit.reports.HomeVerdict
import ch.nokillswit.reports.HomeWip
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Grades a `HomeTeam` against `HomeOracle.kt`'s independent re-derivation (JDBC over the fact rows, its own arithmetic): every
 * figure's value, n, hidden flag and numerator, every previous-window twin and its verdict, the weekly series, the findings and the
 * WIP percentiles. Used on the derived fixture and on the hand-built world alike.
 */

/** A figure as the oracle expects it: [value] is null while [hidden]. */
internal data class Core(val value: Double?, val n: Int, val hidden: Boolean, val numerator: Int? = null)

internal fun statCore(values: List<Double>, q: Double, min: Int): Core =
    Core(if (values.size < min) null else pct(values, q), values.size, values.size < min)

internal fun countCore(count: Int) = Core(count.toDouble(), count, false)

/** PRED(x): the items with a relative error of at most x over the sample of relative errors [mres]. */
internal fun predCore(mres: List<Double>, x: Double, min: Int): Core {
    val within = mres.count { it <= x + HOME_PRED_EPS }
    return Core(if (mres.size < min) null else within.toDouble() / mres.size, mres.size, mres.size < min, within)
}

private fun near(label: String, expected: Double?, actual: Double?) {
    if (expected == null) {
        assertNull(actual, "$label value must be null")
    } else {
        assertNotNull(actual, "$label value")
        assertTrue(abs(expected - actual) < 1e-9, "$label: expected $expected but was $actual")
    }
}

/** One figure against its expected [now] core and, when it carries a trend, its [before] core and the direction of the measure. */
internal fun checkFigure(
    label: String,
    figure: HomeFigure,
    now: Core,
    before: Core?,
    lowerIsBetter: Boolean,
    tolerance: Double,
    itemsUnit: Boolean,
    min: Int,
) {
    near("$label value", now.value, figure.value)
    assertEquals(now.n, figure.n, "$label n")
    assertEquals(now.hidden, figure.hidden, "$label hidden")
    assertEquals(now.numerator, figure.numerator, "$label numerator")
    if (before == null) {
        assertNull(figure.previous, "$label has no trend")
        return
    }
    val previous = assertNotNull(figure.previous, "$label previous")
    near("$label previous value", before.value, previous.value)
    assertEquals(before.n, previous.n, "$label previous n")
    assertEquals(before.hidden, previous.hidden, "$label previous hidden")
    val better = oracleBetter(lowerIsBetter, tolerance, itemsUnit, before.value, now.value, before.n, now.n, min)
    assertEquals(
        when (better) {
            true -> HomeVerdict.BETTER
            false -> HomeVerdict.WORSE
            null -> null
        },
        previous.verdict,
        "$label verdict",
    )
    near("$label difference", if (before.value != null && now.value != null) now.value - before.value else null, previous.difference)
}

private fun checkWip(label: String, wip: HomeWip, ages: List<Double>, min: Int) {
    assertEquals(ages.size, wip.count, "$label count")
    assertEquals(ages.size < min, wip.hidden, "$label hidden")
    near("$label p50", if (wip.hidden) null else pct(ages, 0.5), wip.p50)
    near("$label p85", if (wip.hidden) null else pct(ages, 0.85), wip.p85)
    near("$label p95", if (wip.hidden) null else pct(ages, 0.95), wip.p95)
}

private const val MD_TOL = 0.005
private const val DAYS_TOL = 0.05
private const val SHARE_TOL = 0.005

/** Everything but the sprint block of [team], graded against the oracle over [connIds] at [nowMs] under [min] and [tolerance]. */
internal fun assertHomeTeamEqualsOracle(
    team: HomeTeam,
    connIds: List<UInt>,
    nowMs: Long,
    zone: ZoneId,
    min: Int,
    tolerance: BigDecimal,
) {
    val id = team.teamId
    val (cur, prev) = oracleWindows(zone, nowMs)
    val now = oracleDoneTasks(connIds, id, cur)
    val before = oracleDoneTasks(connIds, id, prev)
    val epics = oracleEpics(connIds, id)
    fun stat(rows: List<OTask>, q: Double, pick: (OTask) -> Double?) = statCore(rows.mapNotNull(pick), q, min)
    val g = team.granularity
    checkFigure("estimate", g.estimateMd, stat(now, .5) { it.estimate }, stat(before, .5) { it.estimate }, true, MD_TOL, false, min)
    checkFigure("cycle", g.cycleWorkingDays, stat(now, .5) { it.cycle }, stat(before, .5) { it.cycle }, true, DAYS_TOL, false, min)
    checkFigure("cost", g.costMd, stat(now, .5) { it.cost }, stat(before, .5) { it.cost }, true, MD_TOL, false, min)

    val curEpics = epics.filter { it.doneAt != null && cur.contains(it.doneAt) }.mapNotNull { it.mre }
    val prevEpics = epics.filter { it.doneAt != null && prev.contains(it.doneAt) }.mapNotNull { it.mre }
    for ((label, x, pick) in listOf(
        Triple("10", .10, HomePredLine::pred10), Triple("25", .25, HomePredLine::pred25), Triple("50", .50, HomePredLine::pred50),
    )) {
        checkFigure(
            "task pred$label", pick(team.accuracy.tasks), predCore(now.mapNotNull { it.mre }, x, min),
            predCore(before.mapNotNull { it.mre }, x, min), false, SHARE_TOL, false, min,
        )
        checkFigure(
            "epic pred$label", pick(team.accuracy.epics), predCore(curEpics, x, min), predCore(prevEpics, x, min),
            false, SHARE_TOL, false, min,
        )
    }

    val t = team.throughput
    fun md(rows: List<OTask>) = rows.fold(BigDecimal.ZERO) { acc, r -> acc + (r.estDone ?: BigDecimal.ZERO) }.toDouble()
    val deliveredNow = Core(md(now), now.size, false)
    checkFigure("delivered MD", t.deliveredMd, deliveredNow, Core(md(before), before.size, false), false, MD_TOL, false, min)
    checkFigure("delivered items", t.deliveredItems, countCore(now.size), countCore(before.size), false, 0.0, true, min)
    checkFigure("cycle p50", t.cycleP50, stat(now, .5) { it.cycle }, stat(before, .5) { it.cycle }, true, DAYS_TOL, false, min)
    checkFigure("cycle p85", t.cycleP85, stat(now, .85) { it.cycle }, stat(before, .85) { it.cycle }, true, DAYS_TOL, false, min)
    val monday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
    val firstWeek = cur.from.with(monday)
    val weeks = generateSequence(firstWeek) { it.plusWeeks(1) }.takeWhile { !it.isAfter(cur.to.with(monday)) }.toList()
    assertEquals(weeks.map { it.toString() }, t.weeks.map { it.bucketStart }, "weekly series: every Monday of the window, zero-filled")
    for ((index, week) in weeks.withIndex()) {
        val inWeek = now.filter { Instant.ofEpochMilli(it.doneAt).atZone(zone).toLocalDate().with(monday) == week }
        assertEquals(inWeek.size, t.weeks[index].deliveredItems, "week $week items")
        near("week $week MD", md(inWeek), t.weeks[index].deliveredMd)
    }

    val q = team.quality
    fun finding(label: String, figure: HomeFigure, pick: (OTask) -> Boolean) =
        checkFigure(label, figure, countCore(now.count(pick)), countCore(before.count(pick)), true, 0.0, true, min)
    finding("no estimate", q.tasksNoEstimate) { it.source == "NONE" }
    finding("unassigned", q.tasksUnassigned) { it.assignee == null }
    finding("estimated late", q.tasksEstimatedLate) { it.late }
    val population = epics.filter { it.doneAt == null || cur.contains(it.doneAt) }
    fun epicFinding(label: String, figure: HomeFigure, pick: (OEpic) -> Boolean) =
        checkFigure(label, figure, countCore(population.count(pick)), null, true, 0.0, true, min)
    epicFinding("epics no estimate", q.epicsNoEstimate) { it.budgetSource == "CHILDREN" }
    epicFinding("epics no dates", q.epicsNoDates) { it.dimStart == null || it.dimDue == null }
    epicFinding("epics gap", q.epicsEstimateGap) { it.gap(tolerance) }

    fun age(open: OOpen) = oracleWorkingDays(zone, open.startedAt, nowMs)
    checkWip("wip tasks", team.wipTasks, oracleOpenTasks(connIds, id).map(::age), min)
    checkWip("wip epics", team.wipEpics, oracleOpenEpics(epics).map(::age), min)
}
