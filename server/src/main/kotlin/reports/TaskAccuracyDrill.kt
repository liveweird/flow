package ch.nokillswit.reports

/** Which of the two histograms of Task accuracy (and, with the same `view` values, of Task estimates) a drill reads. */
enum class AccuracyDrillView { AT_START, AT_DONE }

/**
 * `GET /api/v1/reports/task-estimation-accuracy/items`: the tasks behind ONE bar of the `atStart` or `atDone` ratio histogram.
 * The report's own fetch ([fetchAccuracyTasks]) and its own per-task ratio ([ratioAtStartOf] / [ratioAtDoneOf], `null` = one of
 * the report's exclusions, so in no bar) go through the one bucket rule ([distributionDrillItems]). At USER level the layout is
 * the picked people's union and [segmentAccountId] narrows the list to one person's share (the histogram's `byUser` count).
 */
internal suspend fun ReportService.taskAccuracyItems(
    filter: ReportFilter,
    view: AccuracyDrillView,
    bucketIndex: Int,
    segmentAccountId: String?,
    nowMs: Long,
): ReportDrillItems {
    val segment = filter.requireSegment(segmentAccountId)
    return reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val tasks = scope.window?.let { fetchAccuracyTasks(filter, scope.connectionIds, it) }.orEmpty()
        val valueOf = if (view == AccuracyDrillView.AT_START) ::ratioAtStartOf else ::ratioAtDoneOf
        distributionDrillItems(
            scope.meta, tasks, scope.settings.minSampleSize, filter.tailPercent, bucketIndex, segment, valueOf,
        ) { task, value ->
            DrillRow(task.connectionId, task.issueId, DrillItemKind.TASK, task.accountId, task.creditTeamId, task.doneAt, value)
        }
    }
}
