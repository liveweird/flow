package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import java.time.LocalDate

/**
 * `GET /api/v1/reports/throughput/items`: the tasks behind ONE bar of the Throughput PERIOD chart. It runs the report's own
 * fetch ([fetchThroughputTasks]) over the same filter and window and keeps the tasks the report's own bucket rule
 * ([bucketStart], the function `byBucket` groups by) puts in [bucketFirstDay] — so the list and the bar are one computation, and
 * the bar block is recomputed over exactly the listed set. [segmentAccountId] (USER level, one of the picked accounts) narrows
 * the list, and with it the bar, to that person's share (`byUser`). The figure is the task's MD at done (`null` = unestimated,
 * an item worth 0 MD). A missing [bucketFirstDay] is a `400` (the sprint-delivered mode arrives with the sprint-scope drill),
 * and so is one that is not the first day of a bucket the report draws (`bucketStarts` over the window).
 */
internal suspend fun ReportService.throughputItems(
    filter: ReportFilter,
    bucket: ThroughputBucket,
    bucketFirstDay: LocalDate?,
    segmentAccountId: String?,
    nowMs: Long,
): ReportDrillItems {
    val start = bucketFirstDay ?: throw BadRequestException("bucketStart or a sprintId period is required")
    val segment = filter.requireSegment(segmentAccountId)
    return reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
        val window = scope.window
        // Only a bucket the report itself draws (its first day, inside the window) is a bar; anything else is a client error.
        val drawn = if (window == null) emptyList() else bucketStarts(window.first, window.second, zone, bucket)
        if (start !in drawn) throw BadRequestException("bucketStart must be the first day of a $bucket bucket inside the period")
        val tasks = if (window == null) emptyList() else fetchThroughputTasks(filter, scope.connectionIds, window)
        val rows = tasks
            .filter { bucketStart(it.doneAt, zone, bucket) == start }
            .filter { segment == null || it.accountId == segment }
        val bar = ReportDrillBar(
            count = rows.size,
            sumMd = rows.fold(BigDecimal.ZERO) { acc, task -> acc + task.md }.toDouble(),
            from = null,
            to = null,
        )
        drillItems(
            scope.meta,
            bar,
            rows.map {
                DrillRow(
                    connectionId = it.connectionId,
                    issueId = it.issueId,
                    itemKind = DrillItemKind.TASK,
                    accountId = it.accountId,
                    teamId = it.creditTeamId,
                    anchorAt = it.doneAt,
                    figure = it.estimateMd?.toDouble(),
                )
            },
        )
    }
}
