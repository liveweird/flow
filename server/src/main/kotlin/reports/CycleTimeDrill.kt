package ch.nokillswit.reports

/** Which of the cycle-time report's four histograms a drill reads (`view`). */
enum class CycleTimeDrillView { WORKING_DAYS, ELAPSED_DAYS, EPIC_WORKING_DAYS, EPIC_ELAPSED_DAYS }

/**
 * `GET /api/v1/reports/cycle-time/items`: the tasks (or, for the `EPIC_*` views, the epics) behind ONE bar of a cycle-time
 * histogram. It runs the report's own fetch ([fetchDoneCycleTasks] / [fetchDoneEpicCycles]) over the same filter and window, the
 * report's own per-item value ([workingDaysOf] / [elapsedDaysOf], null = not measurable) and the one bucket rule
 * ([distributionDrillItems]), so the list is exactly the bar. [segmentAccountId] narrows a task view to one picked person; the
 * epic views carry no people, and at USER level the epics block is the all-zero hidden answer, so they answer the hidden `400`.
 */
internal suspend fun ReportService.cycleTimeItems(
    filter: ReportFilter,
    view: CycleTimeDrillView,
    bucketIndex: Int,
    segmentAccountId: String?,
    nowMs: Long,
): ReportDrillItems {
    val segment = filter.requireSegment(segmentAccountId)
    return reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val window = scope.window
        when (view) {
            CycleTimeDrillView.WORKING_DAYS, CycleTimeDrillView.ELAPSED_DAYS -> {
                val tasks = window?.let { fetchDoneCycleTasks(filter, scope.connectionIds, it) }.orEmpty()
                val valueOf = if (view == CycleTimeDrillView.WORKING_DAYS) ::workingDaysOf else ::elapsedDaysOf
                distributionDrillItems(scope.meta, tasks, minSample, filter.tailPercent, bucketIndex, segment, valueOf) { task, value ->
                    DrillRow(task.connectionId, task.issueId, DrillItemKind.TASK, task.accountId, task.creditTeamId, task.doneAt, value)
                }
            }
            CycleTimeDrillView.EPIC_WORKING_DAYS, CycleTimeDrillView.EPIC_ELAPSED_DAYS -> {
                // Epics carry no user: USER level reads nothing (the report's own rule), so its epic views have no bar.
                val epics = if (window == null || filter.level == ReportLevel.USER) {
                    emptyList()
                } else {
                    fetchDoneEpicCycles(filter, scope.connectionIds, window)
                }
                val valueOf: (DoneEpicCycle) -> Double? =
                    if (view == CycleTimeDrillView.EPIC_WORKING_DAYS) { epic -> epic.workingDays } else { epic -> epic.elapsedDays }
                distributionDrillItems(scope.meta, epics, minSample, filter.tailPercent, bucketIndex, segment, valueOf) { epic, value ->
                    DrillRow(epic.connectionId, epic.issueId, DrillItemKind.EPIC, null, epic.ownerTeamId, epic.doneAt, value)
                }
            }
        }
    }
}
