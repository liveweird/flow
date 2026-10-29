package ch.nokillswit.reports

import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/*
 * `GET /api/v1/reports/compare-people/items` (Report 18's drill, `.claude/docs/reports.md` "Report 18"): the items behind ONE person's
 * cell of one row. Each view runs the report's OWN fetch and per-item value function (the cells are built from the same ones, see
 * ComparePeopleCells.kt), so a list is exactly what the cell counted -- `bar.count` is the cell's `n` for a distribution or a count, the
 * numerator's items for a share (the late, foreign, blocked, changed, estimate-less worklogs or tasks; the gap for the worklog coverage)
 * and the distinct tasks for the average WIP (whose `n` is the working days). A cell hidden under `minSampleSize` still lists its items.
 */

/** The views whose figure is a distribution the report draws a histogram of: only these take a `bucketIndex`. */
private val HISTOGRAM_VIEWS = setOf(CompareDrillView.CYCLE_WORKING_DAYS, CompareDrillView.CYCLE_ELAPSED_DAYS)

/** What every view of one request shares: the resolved [scope], the [filter], the [segment] (the person) and the period [window]. */
private class DrillContext(val tx: R2dbcTransaction, val filter: ReportFilter, val scope: ReportScope, val segment: String) {
    val window: Pair<Long, Long> = requireNotNull(scope.window) { "compare-people always has a from/to window" }
    val meta: ReportMeta get() = scope.meta
    val connections: List<UInt> get() = scope.connectionIds

    fun task(connectionId: UInt, issueId: Long, team: UInt?, at: Long?, figure: Double?) =
        DrillRow(connectionId, issueId, DrillItemKind.TASK, segment, team, at, figure)

    suspend fun listed(rows: List<DrillRow>, sumMd: Double? = null): ReportDrillItems =
        drillItems(meta, ReportDrillBar(rows.size, sumMd, null, null), rows)
}

private fun List<BigDecimal>.sumMd(): Double = fold(BigDecimal.ZERO, BigDecimal::add).md()

/**
 * The items behind [segmentAccountId]'s cell of [view] -- REQUIRED (every compare-people figure is one person's) and one of the two
 * picks. With a [bucketIndex] (the cycle-time views only) the list is that bar of the report's union histogram narrowed to the person,
 * so it equals the `byUser` count of `cycleTime.workingDays` / `elapsedDays`; without one, the cell's whole measured set.
 */
internal suspend fun ReportService.comparePeopleItems(
    filter: ReportFilter,
    view: CompareDrillView,
    bucketIndex: Int?,
    segmentAccountId: String?,
    nowMs: Long,
): ReportDrillItems {
    val missing = BadRequestException("segmentAccountId is required: every compare-people figure is one person's")
    val segment = filter.requireSegment(segmentAccountId ?: throw missing)!!
    if (bucketIndex != null && view !in HISTOGRAM_VIEWS) {
        throw BadRequestException("bucketIndex applies to the cycle-time views only (${HISTOGRAM_VIEWS.joinToString { it.name }})")
    }
    return reportTransaction {
        val ctx = DrillContext(this, filter, resolveReportScope(filter, nowMs), segment)
        when (view) {
            CompareDrillView.DELIVERED, CompareDrillView.NO_ESTIMATE -> ctx.deliveredItems(view == CompareDrillView.DELIVERED)
            CompareDrillView.CYCLE_WORKING_DAYS, CompareDrillView.CYCLE_ELAPSED_DAYS ->
                ctx.cycleItems(if (view == CompareDrillView.CYCLE_WORKING_DAYS) ::workingDaysOf else ::elapsedDaysOf, bucketIndex)
            CompareDrillView.REPORTED_TIME_RATIO, CompareDrillView.FLOW_EFFICIENCY, CompareDrillView.NO_WORKLOGS ->
                ctx.doneCycleItems(view)
            CompareDrillView.ACCURACY_AT_START, CompareDrillView.ACCURACY_AT_DONE ->
                ctx.accuracyItems(view == CompareDrillView.ACCURACY_AT_START)
            CompareDrillView.TASK_SIZE -> ctx.taskSizeItems()
            CompareDrillView.REOPENED -> ctx.reopenedItems()
            CompareDrillView.ADJUSTED, CompareDrillView.ADJUSTMENT_CHANGE -> ctx.adjustmentItems(view == CompareDrillView.ADJUSTED)
            CompareDrillView.BLOCKED -> ctx.blockedItems()
            CompareDrillView.LOGGED, CompareDrillView.FOREIGN, CompareDrillView.LATE -> ctx.worklogItems(view)
            CompareDrillView.WIP_TASK_DAYS, CompareDrillView.WIP_AT_END -> ctx.wipItems(view == CompareDrillView.WIP_TASK_DAYS)
        }
    }
}

/** `DELIVERED` (the figure is the estimate at done, null = unestimated) or `NO_ESTIMATE` (the done tasks with no estimate at all). */
private suspend fun DrillContext.deliveredItems(all: Boolean): ReportDrillItems {
    val own = fetchCompareDoneTasks(filter, connections, window).filter { it.accountId == segment }
        .filter { all || it.estimateSource == NO_ESTIMATE_SOURCE }
    val rows = own.map { task(it.connectionId, it.issueId, it.creditTeamId, it.doneAt, it.estimateMd?.toDouble()) }
    return listed(rows, if (all) own.map { it.md }.sumMd() else null)
}

/** A cycle-time bar ([bucketIndex] set: the union histogram's bucket narrowed to the person) or the person's whole measured set. */
private suspend fun DrillContext.cycleItems(valueOf: (DoneCycleTask) -> Double?, bucketIndex: Int?): ReportDrillItems {
    val tasks = fetchDoneCycleTasks(filter, connections, window)
    fun row(t: DoneCycleTask, value: Double) =
        DrillRow(t.connectionId, t.issueId, DrillItemKind.TASK, t.accountId, t.creditTeamId, t.doneAt, value)
    if (bucketIndex != null) {
        val minSample = scope.settings.minSampleSize
        val tail = filter.tailPercent
        return distributionDrillItems(meta, tasks, minSample, tail, bucketIndex, segment, valueOf) { t, value -> row(t, value) }
    }
    return listed(tasks.filter { it.accountId == segment }.mapNotNull { t -> valueOf(t)?.let { row(t, it) } })
}

/** The reported-time ratio, the flow efficiency (their measured tasks) and the worklog-coverage gap (the done tasks with no worklog). */
private suspend fun DrillContext.doneCycleItems(view: CompareDrillView): ReportDrillItems {
    val own = fetchDoneCycleTasks(filter, connections, window).filter { it.accountId == segment }
    fun row(t: DoneCycleTask, figure: Double?) = task(t.connectionId, t.issueId, t.creditTeamId, t.doneAt, figure)
    val rows = when (view) {
        CompareDrillView.REPORTED_TIME_RATIO -> own.mapNotNull { t -> ratioValueOf(t)?.let { row(t, it) } }
        CompareDrillView.FLOW_EFFICIENCY -> own.mapNotNull { t -> flowEfficiencyValueOf(t)?.let { row(t, it) } }
        else -> own.filter { !it.hasWorklogs }.map { row(it, null) }
    }
    return listed(rows)
}

private suspend fun DrillContext.accuracyItems(atStart: Boolean): ReportDrillItems {
    val ratioOf = if (atStart) ::ratioAtStartOf else ::ratioAtDoneOf
    val rows = fetchAccuracyTasks(filter, connections, window).filter { it.accountId == segment }
        .mapNotNull { t -> ratioOf(t)?.let { task(t.connectionId, t.issueId, t.creditTeamId, t.doneAt, it) } }
    return listed(rows)
}

/** `TASK_SIZE`: the person's done tasks that have an estimate at start (Report 19's rule), figure = that estimate in MD. */
private suspend fun DrillContext.taskSizeItems(): ReportDrillItems {
    val rows = fetchAccuracyTasks(filter, connections, window).filter { it.accountId == segment }
        .mapNotNull { t -> estimateAtStartOf(t)?.let { task(t.connectionId, t.issueId, t.creditTeamId, t.doneAt, it) } }
    return listed(rows)
}

/** `REOPENED`: the person's done tasks reopened at least once (the share's numerator), figure = the reopen count. */
private suspend fun DrillContext.reopenedItems(): ReportDrillItems {
    val rows = fetchCompareDoneTasks(filter, connections, window)
        .filter { it.accountId == segment && it.reopenCount >= 1 }
        .map { task(it.connectionId, it.issueId, it.creditTeamId, it.doneAt, it.reopenCount.toDouble()) }
    return listed(rows)
}

/** `ADJUSTED`: the started tasks changed after start (the share's numerator, figure = the changes); else the changed done items. */
private suspend fun DrillContext.adjustmentItems(share: Boolean): ReportDrillItems {
    val own = fetchAdjustmentTasks(filter, connections, window).filter { it.account == segment }
    val rows = if (share) {
        own.filter { it.startedAt != null && it.startedAt >= window.first && it.startedAt < window.second && it.changes > 0 }
            .map { task(it.connectionId, it.issueId, it.team, it.startedAt, it.changes.toDouble()) }
    } else {
        own.mapNotNull { t -> t.adjustedChange(window)?.let { task(t.connectionId, t.issueId, t.team, t.doneAt, it) } }
    }
    return listed(rows)
}

private suspend fun DrillContext.blockedItems(): ReportDrillItems {
    val rows = fetchBlockedItems(filter, BlockedItemKind.TASK, connections, window)
        .filter { it.account == segment && it.blockedWd > 0.0 }
        .map { task(it.connectionId, it.issueId, it.teamId, it.doneAt, it.blockedWd) }
    return listed(rows)
}

/**
 * `LOGGED` (every worklog), `FOREIGN` or `LATE` (logged over a day after it started): WORKLOG rows, figure = MD, `bar.sumMd` their sum.
 * The person's worklogs are ordered, counted, summed and cut to the listed page in Postgres ([fetchCompareWorklogPage]).
 */
private suspend fun DrillContext.worklogItems(view: CompareDrillView): ReportDrillItems {
    val page = fetchCompareWorklogPage(filter, connections, window, segment, view)
    return listedDrillItems(meta, ReportDrillBar(page.total, page.exactMd.md(), null, null), page.rows, page.total)
}

/**
 * `WIP_TASK_DAYS`: each distinct task held on a listed working day, with the number of those days (the average's numerator);
 * `WIP_AT_END`: the tasks held at the last listed day, figure = their age in working days.
 */
private suspend fun DrillContext.wipItems(taskDays: Boolean): ReportDrillItems {
    val wip = tx.fetchCompareWip(scope, filter)
    if (taskDays) {
        val first = wip.days.firstOrNull() ?: return listed(emptyList())
        val request = WipTaskDaysRequest(wip.connectionIds, listOf(segment), first.toString(), wip.days.last().toString())
        val counts = tx.readWipUserTaskCounts(request, wip.workingDays)
        return listed(counts.map { (key, days) -> task(key.first, key.second, null, null, days.toDouble()) })
    }
    val dayEnd = wip.days.lastOrNull()?.let { scope.calendar.dayBoundsMs(it).second }
    val rows = wip.held[segment].orEmpty().map { held ->
        val startedAt = held.startedAt
        val age = if (startedAt != null && dayEnd != null) ageOf(scope.calendar, startedAt, dayEnd) else null
        task(held.connectionId, held.issueId, null, startedAt, age)
    }
    return listed(rows)
}
