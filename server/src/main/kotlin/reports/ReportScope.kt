package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.WorkingCalendar

/** What an ITEM-anchored report (one that reads task/epic facts by a timestamp) needs before its own query. */
internal data class ReportScope(
    val settings: MetricsSettingsResponse,
    /** The working calendar built with [settings] in the same read (`MetricsSettingsService.readWithCalendar`). */
    val calendar: WorkingCalendar,
    val connectionIds: List<UInt>,
    val meta: ReportMeta,
    /** The resolved sprint rows (empty for a `from`/`to` period narrower than sprints, and for `teamId=0`). */
    val sprintRows: List<SprintRow>,
    /** [periodWindow]; `null` = nothing to read. */
    val window: Pair<Long, Long>?,
)

/**
 * The shared preamble of the item-anchored reports: settings, connection scope, the `400` existence
 * checks, sprint resolution (only to describe/resolve a sprint-relative period; `teamId=0` resolves no
 * sprint at all), `meta` and the window. Runs inside the caller's transaction.
 */
internal suspend fun ReportService.resolveReportScope(filter: ReportFilter, nowMs: Long): ReportScope {
    val (settings, calendar) = metricsSettings.readWithCalendar()
    val connectionIds = resolveConnectionScope(filter.connectionId)
    val stamp = deriveStamp(connectionIds)
    val unassigned = filter.teamId == UNASSIGNED_TEAM_ID
    if (!unassigned) filter.teamId?.let { requireActiveTeam(it) }
    val narrowTeamId = filter.teamId.takeIf { filter.level != ReportLevel.UNIT && !unassigned }
    val sprintRows = if (unassigned) emptyList() else resolveSprintRows(filter.period, connectionIds, narrowTeamId)
    val meta = filter.toMeta(stamp.derivedAt, stamp.configRevision, settings.minSampleSize, resolvedSprintGroups(filter.period, sprintRows))
    return ReportScope(settings, calendar, connectionIds, meta, sprintRows, periodWindow(filter.period, sprintRows, nowMs))
}
