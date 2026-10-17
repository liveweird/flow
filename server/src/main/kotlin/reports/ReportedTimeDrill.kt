package ch.nokillswit.reports

/** Which of the reported-time report's two histograms a drill reads (`view`). */
enum class ReportedTimeDrillView { RATIO, FLOW_EFFICIENCY }

/**
 * `GET /api/v1/reports/reported-time-ratio/items`: the tasks behind ONE bar of the ratio or the flow-efficiency histogram. The
 * report's own fetch ([fetchDoneCycleTasks]) and its own per-task value ([ratioValueOf] / [flowEfficiencyValueOf], `null` = one
 * of the report's exclusions, so in no bar) go through the one bucket rule ([distributionDrillItems]). [segmentAccountId] narrows
 * the list to one picked person's share (`byUser`).
 */
internal suspend fun ReportService.reportedTimeItems(
    filter: ReportFilter,
    view: ReportedTimeDrillView,
    bucketIndex: Int,
    segmentAccountId: String?,
    nowMs: Long,
): ReportDrillItems {
    val segment = filter.requireSegment(segmentAccountId)
    return reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val tasks = scope.window?.let { fetchDoneCycleTasks(filter, scope.connectionIds, it) }.orEmpty()
        val valueOf = if (view == ReportedTimeDrillView.RATIO) ::ratioValueOf else ::flowEfficiencyValueOf
        distributionDrillItems(
            scope.meta, tasks, scope.settings.minSampleSize, filter.tailPercent, bucketIndex, segment, valueOf,
        ) { task, value ->
            DrillRow(task.connectionId, task.issueId, DrillItemKind.TASK, task.accountId, task.creditTeamId, task.doneAt, value)
        }
    }
}
