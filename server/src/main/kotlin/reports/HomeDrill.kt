package ch.nokillswit.reports

import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal

/*
 * `GET /api/v1/reports/home/items` (the Home overview's drill, `.claude/docs/reports.md` "Home overview"): the items behind ONE
 * figure of ONE team. Each view reads the same rows with the same per-item value function the figure was built from
 * (`HomeReport.kt`), so a list is exactly what the figure counted. `bar.count` is the figure's `n` for a median, a percentile and a
 * count, the items within the share for a PRED (a PRED figure is a share, so its list is its numerator); `bar.sumMd` is set for the
 * two MD views. A figure hidden under `minSampleSize` still lists its items. Only the current window is read (no trend here).
 */

/** What every view of one request shares: the [ctx] and the [teamId] the figure belongs to. */
private class HomeDrillContext(val ctx: HomeContext, val teamId: UInt) {
    private val current get() = ctx.windows.current.let { it.fromMs to it.toMs }

    suspend fun doneTasks(): List<HomeDoneTask> =
        fetchHomeDoneTasks(ctx.filter, ctx.connectionIds, current, listOf(teamId))

    suspend fun listed(rows: List<DrillRow>, sumMd: BigDecimal? = null): ReportDrillItems =
        drillItems(ctx.meta(), ReportDrillBar(rows.size, sumMd?.md(), null, null), rows)

    fun task(t: HomeDoneTask, figure: Double?) =
        DrillRow(t.connectionId, t.issueId, DrillItemKind.TASK, t.accountId, t.creditTeamId, t.doneAt, figure)
}

/** The PRED threshold (a fraction) of a PRED view. */
private fun HomeDrillView.predThreshold(): Double = when (this) {
    HomeDrillView.TASK_PRED10, HomeDrillView.EPIC_PRED10 -> PRED_10
    HomeDrillView.TASK_PRED25, HomeDrillView.EPIC_PRED25 -> PRED_25
    else -> PRED_50
}

/**
 * The items behind [view]'s figure of [teamId] (a REAL active team: `0` and an unknown or inactive id are a `400`). The caller
 * validated [view] and [teamId] as parameters; the team's existence is checked here, in the report's transaction.
 */
internal suspend fun ReportService.homeItems(view: HomeDrillView, teamId: UInt, nowMs: Long): ReportDrillItems {
    if (teamId == UNASSIGNED_TEAM_ID) throw BadRequestException("Home lists teams only: teamId must be a real team")
    return reportTransaction {
        requireActiveTeam(teamId)
        val drill = HomeDrillContext(homeContext(nowMs), teamId)
        when (view) {
            HomeDrillView.TASK_ESTIMATE, HomeDrillView.TASK_CYCLE, HomeDrillView.TASK_COST -> drill.granularityItems(view)
            HomeDrillView.TASK_PRED10, HomeDrillView.TASK_PRED25, HomeDrillView.TASK_PRED50 -> drill.taskPredItems(view.predThreshold())
            HomeDrillView.EPIC_PRED10, HomeDrillView.EPIC_PRED25, HomeDrillView.EPIC_PRED50 -> drill.epicPredItems(view.predThreshold())
            HomeDrillView.WIP_TASKS, HomeDrillView.WIP_EPICS -> drill.wipItems(view == HomeDrillView.WIP_EPICS)
            HomeDrillView.QUALITY_TASKS_NO_ESTIMATE, HomeDrillView.QUALITY_TASKS_UNASSIGNED, HomeDrillView.QUALITY_TASKS_ESTIMATED_LATE ->
                drill.taskFindingItems(view)
            HomeDrillView.QUALITY_EPICS_NO_ESTIMATE, HomeDrillView.QUALITY_EPICS_NO_DATES, HomeDrillView.QUALITY_EPICS_ESTIMATE_GAP ->
                drill.epicFindingItems(view, nowMs)
        }
    }
}

/** The estimate-at-done, cycle and logged-cost views: the tasks the median was taken over, the figure being the per-item value. */
private suspend fun HomeDrillContext.granularityItems(view: HomeDrillView): ReportDrillItems {
    val tasks = doneTasks()
    return when (view) {
        HomeDrillView.TASK_ESTIMATE -> {
            val own = tasks.filter { estimateMdOf(it) != null }
            listed(own.map { task(it, estimateMdOf(it)) }, own.sumMdAtDone())
        }
        HomeDrillView.TASK_CYCLE -> listed(tasks.mapNotNull { t -> cycleDaysOf(t)?.let { task(t, it) } })
        else -> {
            val own = tasks.filter { costMdOf(it) != null }
            listed(own.map { task(it, costMdOf(it)) }, own.fold(BigDecimal.ZERO) { acc, t -> acc + t.actualMd })
        }
    }
}

/** The tasks within [threshold] of their actual at start: the PRED figure's numerator, the figure being `actual ÷ estimate`. */
private suspend fun HomeDrillContext.taskPredItems(threshold: Double): ReportDrillItems {
    val rows = doneTasks().mapNotNull { t -> taskRatioOf(t)?.takeIf { predWithin(listOf(it), threshold) == 1 }?.let { task(t, it) } }
    return listed(rows)
}

/** The epics (owner team) within [threshold] of their actual at start. */
private suspend fun HomeDrillContext.epicPredItems(threshold: Double): ReportDrillItems {
    val epics = fetchAccuracyEpics(ctx.filter, ctx.connectionIds, ctx.windows.current.let { it.fromMs to it.toMs })
    val rows = epics.filter { it.ownerTeamId == teamId }.mapNotNull { e ->
        e.ratioAtStart?.takeIf { predWithin(listOf(it), threshold) == 1 }
            ?.let { DrillRow(e.connectionId, e.issueId, DrillItemKind.EPIC, null, e.ownerTeamId, e.doneAt, it) }
    }
    return listed(rows)
}

/**
 * The open items of the team in progress now, the figure being their age in working days and the anchor the day they started. Postgres
 * picks the oldest 500 (`candidatesOf`, as Aging WIP does: the first 500 by `started_at` plus every later item whose age still equals
 * the 500th's) and counts the rest; [DRILL_ORDER] (figure, then the newest start, then connection and issue id) is applied here, so the
 * within-tie order is the old drill's, and `bar`/`total` are the whole set's size.
 */
private suspend fun HomeDrillContext.wipItems(epics: Boolean): ReportDrillItems {
    val conns = ctx.connectionIds
    val source = if (epics) EpicOpenSource(ctx.filter, conns, teamId) else TaskOpenSource(ctx.filter, conns, teamId)
    val (candidates, total) = if (conns.isEmpty()) emptyList<Pair<OpenItem, Double>>() to 0L else candidatesOf(source, ctx::ageOfItem)
    val kind = if (epics) DrillItemKind.EPIC else DrillItemKind.TASK
    val listed = candidates.map { (item, age) ->
        DrillRow(item.connectionId, item.issueId, kind, item.account, item.teamId, item.startedAt, age)
    }.sortedWith(DRILL_ORDER).take(DRILL_MAX_ITEMS)
    return listedDrillItems(ctx.meta(), ReportDrillBar(total.toInt(), null, null, null), listed, total.toInt())
}

/** The done tasks of one finding, the figure being their estimate at done (`null` = none). */
private suspend fun HomeDrillContext.taskFindingItems(view: HomeDrillView): ReportDrillItems {
    val finding: (HomeDoneTask) -> Boolean = when (view) {
        HomeDrillView.QUALITY_TASKS_NO_ESTIMATE -> HomeDoneTask::hasNoEstimate
        HomeDrillView.QUALITY_TASKS_UNASSIGNED -> HomeDoneTask::hasNoAssignee
        else -> { t -> t.estimatedLate }
    }
    return listed(doneTasks().filter(finding).map { task(it, it.estimateAtDoneMd?.toDouble()) })
}

/**
 * The epics of one finding among the Data quality page's population (open now or done in the window) that this team owns: the figure
 * is the epic's own estimate, or for the estimate gap its signed gap in MD (tasks' sum − own, `EpicEstimateGap.gapMd`).
 */
private suspend fun HomeDrillContext.epicFindingItems(view: HomeDrillView, nowMs: Long): ReportDrillItems {
    // No DERIVE clocks: they only feed `outsideHorizon`, which no Home figure reads.
    val epics = fetchDqEpics(ctx.filter, ctx.connectionIds, ctx.windows.current.let { it.fromMs to it.toMs }, emptyMap(), nowMs)
        .filter { it.owner == teamId }
    val tolerance = ctx.epicGapTolerance()
    val rows = when (view) {
        HomeDrillView.QUALITY_EPICS_NO_ESTIMATE -> epics.filter { it.withoutEstimate }
        HomeDrillView.QUALITY_EPICS_NO_DATES -> epics.filter { it.withoutDates }
        else -> epics.filter { it.estimateGap(tolerance) }
    }
    return listed(
        rows.map {
            val gap = view == HomeDrillView.QUALITY_EPICS_ESTIMATE_GAP
            val figure = if (gap) (it.childSum - it.ownEstimate!!).toDouble() else it.ownEstimate?.toDouble()
            DrillRow(it.connectionId, it.issueId, DrillItemKind.EPIC, null, it.owner, it.doneAt, figure)
        },
    )
}
