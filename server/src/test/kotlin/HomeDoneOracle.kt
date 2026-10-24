package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.AccuracyTask
import ch.nokillswit.reports.DoneCycleTask
import ch.nokillswit.reports.HomeContext
import ch.nokillswit.reports.HomeDoneTask
import ch.nokillswit.reports.HomeFigure
import ch.nokillswit.reports.HomeGranularity
import ch.nokillswit.reports.HomeMeasure
import ch.nokillswit.reports.HomePredLine
import ch.nokillswit.reports.HomeSpan
import ch.nokillswit.reports.HomeThroughput
import ch.nokillswit.reports.HomeWeek
import ch.nokillswit.reports.Measured
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ThroughputBucket
import ch.nokillswit.reports.bucketStart
import ch.nokillswit.reports.bucketStarts
import ch.nokillswit.reports.costMdOf
import ch.nokillswit.reports.countFigure
import ch.nokillswit.reports.cycleDaysOf
import ch.nokillswit.reports.estimateMdOf
import ch.nokillswit.reports.hasNoAssignee
import ch.nokillswit.reports.hasNoEstimate
import ch.nokillswit.reports.isEstimate
import ch.nokillswit.reports.isWorked
import ch.nokillswit.reports.md
import ch.nokillswit.reports.percentileContinuous
import ch.nokillswit.reports.predLine
import ch.nokillswit.reports.ratioAtStartOf
import ch.nokillswit.reports.sumFigure
import ch.nokillswit.reports.sumMdAtDone
import ch.nokillswit.reports.taskFactSlice
import ch.nokillswit.reports.taskRatioOf
import ch.nokillswit.reports.toFigure
import ch.nokillswit.reports.trendOf
import ch.nokillswit.reports.workingDaysOf
import java.math.BigDecimal
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The Home overview's done-task read as it was BEFORE the `credit_team_id` pushdown (master b9e87084) and before the row was trimmed
 * (WHY 36): the level-0 tasks done in both windows of the WHOLE unit with all fifteen columns ([LegacyDoneTask], the old row with its
 * old per-item values built on `AccuracyTask`/`DoneCycleTask`), filtered to the listed teams in Kotlin (`teamOf`, the drills'
 * `doneTasks`) and counted outside the active teams the same way (`unassigned.doneTasks`). Kept as the golden reference
 * `HomeDonePushdownGoldenTest` grades the SQL-filtered, column-trimmed read, the per-item values and the count against.
 */

/** The old row: every column the overview and the drills used to read, with the old per-item values. */
internal data class LegacyDoneTask(
    val connectionId: UInt,
    val issueId: Long,
    val doneAt: Long,
    val creditTeamId: UInt?,
    val accountId: String?,
    val startedAt: Long?,
    val estimateAtStartMd: BigDecimal?,
    val estimateAtDoneMd: BigDecimal?,
    val actualMd: BigDecimal,
    val hasWorklogs: Boolean,
    val cycleMs: Long?,
    val cycleWorkingDays: BigDecimal?,
    val activeMs: Long,
    val estimateSource: String,
    val estimatedLate: Boolean,
) {
    fun toAccuracy() = AccuracyTask(
        connectionId, issueId, doneAt, creditTeamId, accountId, startedAt, hasWorklogs, actualMd, estimateAtStartMd, estimateAtDoneMd,
    )

    fun toCycle() = DoneCycleTask(
        connectionId, issueId, doneAt, creditTeamId, accountId, cycleMs, cycleWorkingDays, activeMs, hasWorklogs, actualMd,
    )

    /** The trimmed row the new read must return for this fact row (the folded columns derived in Kotlin from the full ones). */
    fun expectedHomeRow() = HomeDoneTask(
        connectionId, issueId, doneAt, creditTeamId, accountId,
        worklogActualMd = actualMd.takeIf { hasWorklogs },
        estimateAtStartMd = estimateAtStartMd.takeIf { startedAt != null },
        estimateAtDoneMd = estimateAtDoneMd,
        cycleWorkingDays = cycleWorkingDays.takeIf { cycleMs != null },
        noEstimate = estimateSource == "NONE",
        estimatedLate = estimatedLate,
    )
}

internal fun legacyEstimateMdOf(task: LegacyDoneTask): Double? = task.estimateAtDoneMd.takeIf { it.isEstimate() }?.toDouble()

internal fun legacyCycleDaysOf(task: LegacyDoneTask): Double? = workingDaysOf(task.toCycle())

internal fun legacyCostMdOf(task: LegacyDoneTask): Double? = task.actualMd.toDouble().takeIf { task.toAccuracy().isWorked() }

internal fun legacyTaskRatioOf(task: LegacyDoneTask): Double? = ratioAtStartOf(task.toAccuracy())

internal fun LegacyDoneTask.hasNoEstimate(): Boolean = estimateSource == "NONE"

internal fun LegacyDoneTask.hasNoAssignee(): Boolean = accountId == null

/** Every done task of the unit in [window] (the old `fetchHomeDoneTasks`). */
internal suspend fun legacyHomeDoneTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<LegacyDoneTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(
        t.connectionId, t.issueId, t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, t.startedAt, t.estimateAtStartMd,
        t.estimateAtDoneMd, t.actualMd, t.hasWorklogs, t.cycleMs, t.cycleWorkingDays, t.activeMs, t.estimateSource, t.estimatedLate,
    ).where { predicate }.toList().map {
        LegacyDoneTask(
            connectionId = it[t.connectionId].value,
            issueId = it[t.issueId],
            doneAt = it[t.doneAt]!!,
            creditTeamId = it[t.creditTeamId]?.value,
            accountId = it[t.assigneeAccountIdAtDone],
            startedAt = it[t.startedAt],
            estimateAtStartMd = it[t.estimateAtStartMd],
            estimateAtDoneMd = it[t.estimateAtDoneMd],
            actualMd = it[t.actualMd],
            hasWorklogs = it[t.hasWorklogs],
            cycleMs = it[t.cycleMs],
            cycleWorkingDays = it[t.cycleWorkingDays],
            activeMs = it[t.activeMs],
            estimateSource = it[t.estimateSource],
            estimatedLate = it[t.estimatedLate],
        )
    }
}

/** The old `unassigned.doneTasks`: the current window's done tasks credited to no active team (none, or an inactive one). */
internal fun legacyDoneOutside(ctx: HomeContext, all: List<LegacyDoneTask>, activeTeams: Set<UInt>): Int =
    all.count { ctx.windows.current.contains(it.doneAt) && it.creditTeamId !in activeTeams }

private const val CYCLE_P85 = 0.85

/** What the old `teamOf` derived from a team's done tasks: the sections 2, 3 (tasks), 6 and the task findings of section 7. */
internal data class LegacyDoneFigures(
    val granularity: HomeGranularity,
    val taskAccuracy: HomePredLine,
    val throughput: HomeThroughput,
    val tasksNoEstimate: HomeFigure,
    val tasksUnassigned: HomeFigure,
    val tasksEstimatedLate: HomeFigure,
)

/** The old per-team figures of [teamTasks] (the unit's rows already filtered to one team in Kotlin, as `teamOf` did). */
internal fun legacyDoneFigures(ctx: HomeContext, teamTasks: List<LegacyDoneTask>): LegacyDoneFigures {
    val min = ctx.minSample
    val current = teamTasks.filter { ctx.windows.current.contains(it.doneAt) }
    val previous = teamTasks.filter { ctx.windows.previous.contains(it.doneAt) }
    fun cycle(rows: List<LegacyDoneTask>) = rows.mapNotNull(::legacyCycleDaysOf)
    fun finding(test: (LegacyDoneTask) -> Boolean) =
        trended(HomeMeasure.FINDING_COUNT, current, previous, min) { countFigure(it.count(test)) }
    return LegacyDoneFigures(
        granularity = HomeGranularity(
            estimateMd = trended(HomeMeasure.ESTIMATE_MD, current, previous, min) {
                medianFigure(it.mapNotNull(::legacyEstimateMdOf), min)
            },
            cycleWorkingDays = trended(HomeMeasure.CYCLE_WORKING_DAYS, current, previous, min) { medianFigure(cycle(it), min) },
            costMd = trended(HomeMeasure.COST_MD, current, previous, min) { medianFigure(it.mapNotNull(::legacyCostMdOf), min) },
        ),
        taskAccuracy = predLine(current, previous, min, ::legacyTaskRatioOf),
        throughput = HomeThroughput(
            weeks = weeklyDelivery(current.map { it.expectedHomeRow() }, ctx.windows.current, ctx.zone),
            deliveredMd = trended(HomeMeasure.DELIVERED_MD, current, previous, min) {
                sumFigure(it.map { row -> row.expectedHomeRow() }.sumMdAtDone().md(), it.size)
            },
            deliveredItems = trended(HomeMeasure.DELIVERED_ITEMS, current, previous, min) { countFigure(it.size) },
            cycleP50 = trended(HomeMeasure.CYCLE_PERCENTILE, current, previous, min) { medianFigure(cycle(it), min) },
            cycleP85 = trended(HomeMeasure.CYCLE_PERCENTILE, current, previous, min) { percentileFigure(cycle(it), CYCLE_P85, min) },
        ),
        tasksNoEstimate = finding(LegacyDoneTask::hasNoEstimate),
        tasksUnassigned = finding(LegacyDoneTask::hasNoAssignee),
        tasksEstimatedLate = finding { it.estimatedLate },
    )
}

// ---- The kernels the overview's done-task figures used before WHY 38 (per row, in Kotlin): the oracle of the Postgres aggregates ----

/** The [fraction] percentile of [values] (PostgreSQL's `percentile_cont`), hidden under [minSample] values like a `Distribution`. */
internal fun percentileFigure(values: List<Double>, fraction: Double, minSample: Int): Measured {
    val hidden = values.size < minSample
    return Measured(if (hidden) null else percentileContinuous(values.sorted(), fraction), values.size, hidden)
}

private const val MEDIAN = 0.5

internal fun medianFigure(values: List<Double>, minSample: Int): Measured = percentileFigure(values, MEDIAN, minSample)

/** A figure with its trend: [measured] over each window's rows. */
internal fun <T> trended(
    measure: HomeMeasure,
    current: List<T>,
    previous: List<T>,
    minSample: Int,
    measured: (List<T>) -> Measured,
): HomeFigure {
    val now = measured(current)
    return now.toFigure(trendOf(measure, measured(previous), now, minSample))
}

/** Every Monday of [window] (zero-filled), each with the MD at done and the count of the [doneTasks] that fall in its week. */
internal fun weeklyDelivery(doneTasks: List<HomeDoneTask>, window: HomeSpan, zone: ZoneId): List<HomeWeek> {
    val byWeek = doneTasks.groupBy { bucketStart(it.doneAt, zone, ThroughputBucket.WEEK) }
    return bucketStarts(window.fromMs, window.toMs, zone, ThroughputBucket.WEEK).map { start ->
        val rows = byWeek[start].orEmpty()
        HomeWeek(start.toString(), rows.sumMdAtDone().md(), rows.size)
    }
}

/** What the old `teamOf` derived from a team's trimmed rows ([HomeDoneTask], `fetchHomeDoneTasks`): the figures, in Kotlin, per row. */
internal fun rowDoneFigures(ctx: HomeContext, teamTasks: List<HomeDoneTask>): LegacyDoneFigures {
    val min = ctx.minSample
    val current = teamTasks.filter { ctx.windows.current.contains(it.doneAt) }
    val previous = teamTasks.filter { ctx.windows.previous.contains(it.doneAt) }
    fun cycle(rows: List<HomeDoneTask>) = rows.mapNotNull(::cycleDaysOf)
    fun finding(test: (HomeDoneTask) -> Boolean) =
        trended(HomeMeasure.FINDING_COUNT, current, previous, min) { countFigure(it.count(test)) }
    return LegacyDoneFigures(
        granularity = HomeGranularity(
            estimateMd = trended(HomeMeasure.ESTIMATE_MD, current, previous, min) { medianFigure(it.mapNotNull(::estimateMdOf), min) },
            cycleWorkingDays = trended(HomeMeasure.CYCLE_WORKING_DAYS, current, previous, min) { medianFigure(cycle(it), min) },
            costMd = trended(HomeMeasure.COST_MD, current, previous, min) { medianFigure(it.mapNotNull(::costMdOf), min) },
        ),
        taskAccuracy = predLine(current, previous, min, ::taskRatioOf),
        throughput = HomeThroughput(
            weeks = weeklyDelivery(current, ctx.windows.current, ctx.zone),
            deliveredMd = trended(HomeMeasure.DELIVERED_MD, current, previous, min) { sumFigure(it.sumMdAtDone().md(), it.size) },
            deliveredItems = trended(HomeMeasure.DELIVERED_ITEMS, current, previous, min) { countFigure(it.size) },
            cycleP50 = trended(HomeMeasure.CYCLE_PERCENTILE, current, previous, min) { medianFigure(cycle(it), min) },
            cycleP85 = trended(HomeMeasure.CYCLE_PERCENTILE, current, previous, min) { percentileFigure(cycle(it), CYCLE_P85, min) },
        ),
        tasksNoEstimate = finding(HomeDoneTask::hasNoEstimate),
        tasksUnassigned = finding(HomeDoneTask::hasNoAssignee),
        tasksEstimatedLate = finding { it.estimatedLate },
    )
}
