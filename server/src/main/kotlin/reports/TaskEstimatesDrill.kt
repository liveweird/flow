package ch.nokillswit.reports

/**
 * `GET /api/v1/reports/task-estimates/items`: the tasks behind ONE bar of the `atDone` or `atStart` estimate histogram (`view` is
 * Task accuracy's [AccuracyDrillView]). The report's own fetch ([fetchAccuracyTasks]) and its own per-task estimate
 * ([estimateAtDoneOf] / [estimateAtStartOf], `null` = one of the report's exclusions, so in no bar) go through the one bucket rule
 * ([distributionDrillItems], with the report's fixed 0.5-MD layout). At USER level the layout is the picked people's union and
 * [segmentAccountId] narrows the list to one person's share (the histogram's `byUser` count). The `figure` is the estimate in MD.
 */
internal suspend fun ReportService.taskEstimatesItems(
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
        val valueOf = if (view == AccuracyDrillView.AT_DONE) ::estimateAtDoneOf else ::estimateAtStartOf
        distributionDrillItems(
            scope.meta, tasks, scope.settings.minSampleSize, filter.tailPercent, bucketIndex, segment, valueOf, FIXED_BUCKET_STEP,
        ) { task, value ->
            DrillRow(task.connectionId, task.issueId, DrillItemKind.TASK, task.accountId, task.creditTeamId, task.doneAt, value)
        }
    }
}
