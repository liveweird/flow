package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.HomeContext
import ch.nokillswit.reports.HomeDoneTask
import ch.nokillswit.reports.HomeFigure
import ch.nokillswit.reports.HomeGranularity
import ch.nokillswit.reports.HomeMeasure
import ch.nokillswit.reports.HomePredLine
import ch.nokillswit.reports.HomeThroughput
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.costMdOf
import ch.nokillswit.reports.countFigure
import ch.nokillswit.reports.cycleDaysOf
import ch.nokillswit.reports.estimateMdOf
import ch.nokillswit.reports.hasNoAssignee
import ch.nokillswit.reports.hasNoEstimate
import ch.nokillswit.reports.md
import ch.nokillswit.reports.medianFigure
import ch.nokillswit.reports.percentileFigure
import ch.nokillswit.reports.predLine
import ch.nokillswit.reports.sumFigure
import ch.nokillswit.reports.sumMdAtDone
import ch.nokillswit.reports.taskFactSlice
import ch.nokillswit.reports.taskRatioOf
import ch.nokillswit.reports.trended
import ch.nokillswit.reports.weeklyDelivery
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The Home overview's done-task read as it was BEFORE the `credit_team_id` pushdown (master b9e87084): the level-0 tasks done in both
 * windows of the WHOLE unit, filtered to the listed teams in Kotlin (`teamOf`, the drills' `doneTasks`) and counted outside the active
 * teams the same way (`unassigned.doneTasks`). Kept as the golden reference `HomeDonePushdownGoldenTest` grades the SQL-filtered read
 * and the count against.
 */

/** Every done task of the unit in [window] (the old `fetchHomeDoneTasks`). */
internal suspend fun legacyHomeDoneTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<HomeDoneTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(
        t.connectionId, t.issueId, t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, t.startedAt, t.estimateAtStartMd,
        t.estimateAtDoneMd, t.actualMd, t.hasWorklogs, t.cycleMs, t.cycleWorkingDays, t.activeMs, t.estimateSource, t.estimatedLate,
    ).where { predicate }.toList().map {
        HomeDoneTask(
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
internal fun legacyDoneOutside(ctx: HomeContext, all: List<HomeDoneTask>, activeTeams: Set<UInt>): Int =
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
internal fun legacyDoneFigures(ctx: HomeContext, teamTasks: List<HomeDoneTask>): LegacyDoneFigures {
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
