package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.derivesMetrics
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select

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
    val sprintRows = if (unassigned) {
        emptyList()
    } else {
        resolveSprintRows(filter.period, connectionIds, narrowTeamId, filter.historyHorizon, calendar)
    }
    val meta = filter.toMeta(stamp.derivedAt, stamp.configRevision, settings.minSampleSize, resolvedSprintGroups(filter.period, sprintRows))
    return ReportScope(settings, calendar, connectionIds, meta, sprintRows, periodWindow(filter.period, sprintRows, nowMs))
}

/**
 * The connections a report reads: every non-deleted connection of a kind that derives metrics (`DataSourceKind.derivesMetrics`), or the one
 * named by [connectionId]. A connection of another kind is not in any report scope, and naming one is the same `400` as naming one that
 * does not exist (it discloses nothing about it).
 */
internal suspend fun resolveConnectionScope(connectionId: UInt?): List<UInt> {
    if (connectionId == null) {
        return DataSourceService.Connections.select(DataSourceService.Connections.id)
            // Every non-deleted connection, like /reports/filters: a disabled one only has syncing paused.
            .where { DataSourceService.Connections.active() and DataSourceService.Connections.derivesMetrics() }
            .toList().map { it[DataSourceService.Connections.id].value }
    }
    val exists = DataSourceService.Connections.select(DataSourceService.Connections.id)
        .where {
            (DataSourceService.Connections.id eq connectionId) and DataSourceService.Connections.active() and
                DataSourceService.Connections.derivesMetrics()
        }
        .toList().isNotEmpty()
    if (!exists) throw BadRequestException("Unknown or inactive connectionId: $connectionId")
    return listOf(connectionId)
}
