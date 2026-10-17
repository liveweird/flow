package ch.nokillswit.reports

import io.ktor.server.plugins.BadRequestException

/** The `400` of a drill into an epic histogram at USER level: epics carry no user, so the report reads none there. */
internal const val EPICS_NOT_PER_PERSON = "Epics are not read per person: USER level has no epic bar to list"

/**
 * `GET /api/v1/reports/epic-estimation-accuracy/items`: the epics behind ONE bar of the `atStart` or `atDone` ratio histogram
 * (the same [AccuracyDrillView] as the task report). The report's own fetch ([fetchAccuracyEpics]) and its own per-epic ratio
 * (`ratioAtStart` / `ratioAtDone`, `null` = excluded, so in no bar) go through the one bucket rule ([distributionDrillItems]).
 * Epics carry no user, so there is no segment; USER level reads no epics (the report's own all-zero answer), so it has no bar
 * to list and answers a `400` saying epics are not read per person.
 */
internal suspend fun ReportService.epicAccuracyItems(
    filter: ReportFilter,
    view: AccuracyDrillView,
    bucketIndex: Int,
    nowMs: Long,
): ReportDrillItems = reportTransaction {
    if (filter.level == ReportLevel.USER) throw BadRequestException(EPICS_NOT_PER_PERSON)
    val scope = resolveReportScope(filter, nowMs)
    val epics = scope.window?.let { fetchAccuracyEpics(filter, scope.connectionIds, it) }.orEmpty()
    val valueOf: (AccuracyEpic) -> Double? =
        if (view == AccuracyDrillView.AT_START) { epic -> epic.ratioAtStart } else { epic -> epic.ratioAtDone }
    distributionDrillItems(scope.meta, epics, scope.settings.minSampleSize, filter.tailPercent, bucketIndex, null, valueOf) { epic, value ->
        DrillRow(epic.connectionId, epic.issueId, DrillItemKind.EPIC, null, epic.ownerTeamId, epic.doneAt, value)
    }
}
