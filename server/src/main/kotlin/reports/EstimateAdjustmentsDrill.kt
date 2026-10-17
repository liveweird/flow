package ch.nokillswit.reports

import io.ktor.server.plugins.BadRequestException

/** Which of the adjustments report's two change histograms a drill reads (`view`): the `tasks` block's or the `epics` block's. */
enum class AdjustmentDrillView { TASKS, EPICS }

/**
 * `GET /api/v1/reports/estimate-adjustments/items`: the DONE items behind ONE bar of the `tasks.changeDistribution` or the
 * `epics.changeDistribution` histogram. The report's own fetches ([fetchAdjustmentTasks] / [fetchAdjustmentEpics]) and its own
 * per-item change ([adjustedChange], `null` = not charted: estimated late, unestimated, or an unchanged estimate, so in no bar)
 * go through the one bucket rule ([distributionDrillItems]). At USER level the tasks' layout is the picked people's union and
 * [segmentAccountId] narrows the list to one person's share (the histogram's `byUser` count); epics carry no user, so USER
 * level reads none and the `EPICS` view answers the "epics are not read per person" `400`.
 */
internal suspend fun ReportService.estimateAdjustmentItems(
    filter: ReportFilter,
    view: AdjustmentDrillView,
    bucketIndex: Int,
    segmentAccountId: String?,
    nowMs: Long,
): ReportDrillItems {
    val segment = filter.requireSegment(segmentAccountId)
    if (view == AdjustmentDrillView.EPICS && filter.level == ReportLevel.USER) throw BadRequestException(EPICS_NOT_PER_PERSON)
    return reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val window = scope.window ?: (0L to 0L)
        val tasks = view == AdjustmentDrillView.TASKS
        val items = when {
            scope.window == null -> emptyList()
            tasks -> fetchAdjustmentTasks(filter, scope.connectionIds, window)
            else -> fetchAdjustmentEpics(filter, scope.connectionIds, window)
        }
        val kind = if (tasks) DrillItemKind.TASK else DrillItemKind.EPIC
        distributionDrillItems(
            scope.meta, items, scope.settings.minSampleSize, filter.tailPercent, bucketIndex, segment,
            { item -> item.adjustedChange(window) },
        ) { item, value ->
            DrillRow(item.connectionId, item.issueId, kind, item.account, item.team, item.doneAt, value)
        }
    }
}
