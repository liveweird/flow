package ch.nokillswit

import ch.nokillswit.reports.HomeDrillView
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/*
 * The INDEPENDENT re-derivation the Home overview tests grade against (`ReportHomeTest`, `ReportHomeDrillTest`): plain JDBC over the
 * fact tables (`jdbcRows`), its own window, percentile (`quantile`), working-day and PRED arithmetic -- never `HomeKernels`, the
 * report fetches or `WorkingCalendar`. The shared `metrics.settings` defaults apply (Mon-Fri working week, the seeded holidays).
 */

internal const val HOME_PRED_EPS = 1e-9

internal class OWindow(val from: LocalDate, val to: LocalDate, zone: ZoneId) {
    val fromMs: Long = from.atStartOfDay(zone).toInstant().toEpochMilli()
    val toMs: Long = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun contains(atMs: Long) = atMs >= fromMs && atMs < toMs
}

/** The trailing 90 days ending on [nowMs]'s day in [zone], and the 90 days before them. */
internal fun oracleWindows(zone: ZoneId, nowMs: Long): Pair<OWindow, OWindow> {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val cur = OWindow(today.minusDays(89), today, zone)
    return cur to OWindow(cur.from.minusDays(90), cur.from.minusDays(1), zone)
}

/** Integer ids as an SQL list (ints only, never user text). */
private fun List<UInt>.sqlIds(): String = joinToString(",") { it.toInt().toString() }

private fun Any?.big(): BigDecimal? = this as BigDecimal?

private fun Any?.long(): Long? = (this as Number?)?.toLong()

internal class OTask(
    val issueId: Long,
    val doneAt: Long,
    val estDone: BigDecimal?,
    val estStart: BigDecimal?,
    val actual: BigDecimal,
    val hasWl: Boolean,
    val startedAt: Long?,
    val cycleMs: Long?,
    val cycleWd: BigDecimal?,
    val source: String,
    val assignee: String?,
    val late: Boolean,
) {
    val estimate: Double? get() = estDone?.takeIf { it.signum() > 0 }?.toDouble()
    val cycle: Double? get() = if (cycleMs != null && cycleWd != null) cycleWd.toDouble() else null
    val cost: Double? get() = if (hasWl && actual.signum() > 0) actual.toDouble() else null

    /** `|actual - estimate at start| / actual` (the relative error against the ACTUAL), null = in no PRED sample. */
    val mre: Double?
        get() = if (cost != null && startedAt != null && estStart != null && estStart.signum() > 0) {
            abs(actual.toDouble() - estStart.toDouble()) / actual.toDouble()
        } else {
            null
        }
}

internal class OEpic(
    val issueId: Long,
    val owner: UInt?,
    val doneAt: Long?,
    val startedAt: Long?,
    val ownStart: BigDecimal?,
    val ownCurrent: BigDecimal?,
    val childSum: BigDecimal,
    val actual: BigDecimal,
    val budgetSource: String,
    val stage: String?,
    val dimStart: Long?,
    val dimDue: Long?,
) {
    val mre: Double?
        get() = if (startedAt != null && actual.signum() > 0 && ownStart != null && ownStart.signum() > 0) {
            abs(actual.toDouble() - ownStart.toDouble()) / actual.toDouble()
        } else {
            null
        }

    fun gap(tolerance: BigDecimal): Boolean =
        ownCurrent != null && ownCurrent.signum() > 0 && childSum.signum() > 0 &&
            (childSum - ownCurrent).abs() > ownCurrent * tolerance
}

/** The one team the derived fixture's FLO board is mapped to. */
internal fun oracleFixtureTeam(connId: UInt): UInt {
    val rows = jdbcRows("SELECT DISTINCT team_id FROM metrics.fact_sprint WHERE connection_id = ? AND team_id IS NOT NULL", connId.toInt())
    return (rows.single()[0] as Number).toInt().toUInt()
}

internal class OOpen(val issueId: Long, val startedAt: Long)

internal fun oracleDoneTasks(connIds: List<UInt>, team: UInt, window: OWindow): List<OTask> = jdbcRows(
    """SELECT issue_id, done_at, estimate_at_done_md, estimate_at_start_md, actual_md, has_worklogs, started_at, cycle_ms,
              cycle_working_days, estimate_source, assignee_account_id_at_done, estimated_late
       FROM metrics.fact_task_delivery
       WHERE connection_id IN (${connIds.sqlIds()}) AND is_subtask = false AND credit_team_id = ? AND done_at >= ? AND done_at < ?""",
    team.toInt(), window.fromMs, window.toMs,
).map {
    OTask(
        it[0].long()!!, it[1].long()!!, it[2].big(), it[3].big(), it[4].big()!!, it[5] as Boolean, it[6].long(), it[7].long(), it[8].big(),
        it[9] as String, it[10] as String?, it[11] as Boolean,
    )
}

/** Every `fact_epic_delivery` row of [connIds] joined with its `dim_epic` twin (stage, dates), owner team [team]. */
internal fun oracleEpics(connIds: List<UInt>, team: UInt): List<OEpic> = jdbcRows(
    """SELECT e.issue_id, e.owner_team_id, e.done_at, e.started_at, e.own_estimate_at_start_md, e.own_estimate_current_md,
              e.child_sum_estimate_md, e.actual_md, e.budget_source, d.current_stage, d.start_at, d.due_at
       FROM metrics.fact_epic_delivery e
       LEFT JOIN metrics.dim_epic d ON d.connection_id = e.connection_id AND d.issue_id = e.issue_id
       WHERE e.connection_id IN (${connIds.sqlIds()}) AND e.owner_team_id = ?""",
    team.toInt(),
).map {
    OEpic(
        it[0].long()!!, (it[1] as Number?)?.toInt()?.toUInt(), it[2].long(), it[3].long(), it[4].big(), it[5].big(), it[6].big()!!,
        it[7].big()!!, it[8] as String, it[9] as String?, it[10].long(), it[11].long(),
    )
}

private val WIP_STAGES = listOf("IN_PROGRESS", "WAITING", "BLOCKED")

internal fun oracleOpenTasks(connIds: List<UInt>, team: UInt): List<OOpen> = jdbcRows(
    """SELECT issue_id, started_at FROM metrics.fact_task_delivery
       WHERE connection_id IN (${connIds.sqlIds()}) AND is_subtask = false AND done_at IS NULL AND started_at IS NOT NULL
         AND current_team_id = ? AND current_stage IN ('IN_PROGRESS', 'WAITING', 'BLOCKED')""",
    team.toInt(),
).map { OOpen(it[0].long()!!, it[1].long()!!) }

internal fun oracleOpenEpics(epics: List<OEpic>): List<OOpen> =
    epics.filter { it.doneAt == null && it.startedAt != null && it.stage in WIP_STAGES }.map { OOpen(it.issueId, it.startedAt!!) }

/** The seeded public holidays as ISO dates. */
private fun holidays(): Set<String> = jdbcRows("SELECT holiday_date FROM metrics.public_holidays").map { it[0] as String }.toSet()

/**
 * Working days in `[fromMs, toMs)`: every Mon-Fri non-holiday calendar day the span touches, weighted by the covered share of that
 * day's REAL length (a 23 h or 25 h day is its own length) -- written out here, not `WorkingCalendar`.
 */
internal fun oracleWorkingDays(zone: ZoneId, fromMs: Long, toMs: Long): Double {
    if (toMs <= fromMs) return 0.0
    val off = holidays()
    var day = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
    var total = 0.0
    while (true) {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (start >= toMs) break
        val working = day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY && day.toString() !in off
        if (working) total += (minOf(end, toMs) - maxOf(start, fromMs)).toDouble() / (end - start)
        day = day.plusDays(1)
    }
    return total
}

/** `percentile_cont` of [values] (unsorted) -- [quantile] over the sorted list. */
internal fun pct(values: List<Double>, q: Double): Double = quantile(values.sorted(), q)

/** The independent verdict of a figure against its previous window: `true` = BETTER, `false` = WORSE, null = no cue. */
internal fun oracleBetter(
    lowerIsBetter: Boolean,
    tolerance: Double,
    itemsUnit: Boolean,
    previous: Double?,
    current: Double?,
    previousN: Int,
    currentN: Int,
    minSample: Int,
): Boolean? {
    if (previous == null || current == null) return null
    if (!itemsUnit && (previousN < minSample || currentN < minSample)) return null
    val difference = current - previous
    if (abs(difference) <= tolerance + 1e-9) return null
    return if (lowerIsBetter) difference < 0 else difference > 0
}

/** Which view's items an independent predicate says belong behind one team's figure (issue id to its figure), current window only. */
internal fun oracleViewItems(
    view: HomeDrillView,
    tasks: List<OTask>,
    epics: List<OEpic>,
    openTasks: List<OOpen>,
    openEpics: List<OOpen>,
    cur: OWindow,
    tolerance: BigDecimal,
    ageOf: (OOpen) -> Double,
): Map<Long, Double?> {
    val x = when (view) {
        HomeDrillView.TASK_PRED10, HomeDrillView.EPIC_PRED10 -> 0.10
        HomeDrillView.TASK_PRED25, HomeDrillView.EPIC_PRED25 -> 0.25
        else -> 0.50
    }
    val population = epics.filter { it.doneAt == null || cur.contains(it.doneAt) }
    return when (view) {
        HomeDrillView.TASK_ESTIMATE -> tasks.filter { it.estimate != null }.associate { it.issueId to it.estimate }
        HomeDrillView.TASK_CYCLE -> tasks.filter { it.cycle != null }.associate { it.issueId to it.cycle }
        HomeDrillView.TASK_COST -> tasks.filter { it.cost != null }.associate { it.issueId to it.cost }
        HomeDrillView.TASK_PRED10, HomeDrillView.TASK_PRED25, HomeDrillView.TASK_PRED50 ->
            tasks.filter { (it.mre ?: Double.MAX_VALUE) <= x + HOME_PRED_EPS }
                .associate { it.issueId to it.actual.toDouble() / it.estStart!!.toDouble() }
        HomeDrillView.EPIC_PRED10, HomeDrillView.EPIC_PRED25, HomeDrillView.EPIC_PRED50 ->
            epics.filter { it.doneAt != null && cur.contains(it.doneAt) && (it.mre ?: Double.MAX_VALUE) <= x + HOME_PRED_EPS }
                .associate { it.issueId to it.actual.toDouble() / it.ownStart!!.toDouble() }
        HomeDrillView.WIP_TASKS -> openTasks.associate { it.issueId to ageOf(it) }
        HomeDrillView.WIP_EPICS -> openEpics.associate { it.issueId to ageOf(it) }
        HomeDrillView.QUALITY_TASKS_NO_ESTIMATE -> tasks.filter { it.source == "NONE" }.associate { it.issueId to it.estDone?.toDouble() }
        HomeDrillView.QUALITY_TASKS_UNASSIGNED -> tasks.filter { it.assignee == null }.associate { it.issueId to it.estDone?.toDouble() }
        HomeDrillView.QUALITY_TASKS_ESTIMATED_LATE -> tasks.filter { it.late }.associate { it.issueId to it.estDone?.toDouble() }
        HomeDrillView.QUALITY_EPICS_NO_ESTIMATE ->
            population.filter { it.budgetSource == "CHILDREN" }.associate { it.issueId to it.ownCurrent?.toDouble() }
        HomeDrillView.QUALITY_EPICS_NO_DATES ->
            population.filter { it.dimStart == null || it.dimDue == null }.associate { it.issueId to it.ownCurrent?.toDouble() }
        HomeDrillView.QUALITY_EPICS_ESTIMATE_GAP ->
            population.filter { it.gap(tolerance) }.associate { it.issueId to (it.childSum - it.ownCurrent!!).toDouble() }
    }
}
