package ch.nokillswit.reports

import ch.nokillswit.metrics.SprintBucket
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import java.time.LocalDate

/** The `measure` of `/throughput/items`: the delivered tasks of a bar (the default) or the worklogs behind its actual cost. */
enum class ThroughputDrillMeasure { DELIVERED, ACTUAL_COST }

/**
 * `GET /api/v1/reports/throughput/items`: the tasks behind ONE bar of the Throughput PERIOD chart. It runs the report's own
 * fetch ([fetchThroughputTasks]) over the same filter and window and keeps the tasks the report's own bucket rule
 * ([bucketStart], the function `byBucket` groups by) puts in [bucketFirstDay] — so the list and the bar are one computation, and
 * the bar block is recomputed over exactly the listed set. [segmentAccountId] (USER level, one of the picked accounts) narrows
 * the list, and with it the bar, to that person's share (`byUser`). The figure is the task's MD at done (`null` = unestimated,
 * an item worth 0 MD). Without [bucketFirstDay] a `sprintId` period selects the SPRINT mode instead — the sprint view's bar: the items
 * the sprint delivered, via [sprintScopeItems] (the kernel's DELIVERED bucket, the rows `fact_sprint.delivered_*` sums) — and
 * anything else is a `400`. The sprint view carries no activity/category slice (like `bySprint`), so the sprint mode drops it; it alone
 * takes [epicSegment] (the by-epic segment of the sprint's bar), a `400` on a period bar.
 * A [bucketFirstDay] that is not the first day of a bucket the report draws (`bucketStarts` over the window) is a `400` too.
 * [measure] `ACTUAL_COST` lists instead the tasks the bar's actual-cost figure is logged on (`ThroughputActualCostDrill.kt`), with the
 * same identity parameters.
 */
internal suspend fun ReportService.throughputItems(
    filter: ReportFilter,
    bucket: ThroughputBucket,
    bucketFirstDay: LocalDate?,
    segmentAccountId: String?,
    nowMs: Long,
    epicSegment: EpicSegmentSelector? = null,
    measure: ThroughputDrillMeasure = ThroughputDrillMeasure.DELIVERED,
): ReportDrillItems {
    if (measure == ThroughputDrillMeasure.ACTUAL_COST) {
        return throughputActualCostItems(filter, bucket, bucketFirstDay, segmentAccountId, nowMs, epicSegment)
    }
    if (bucketFirstDay == null) {
        if (filter.period !is ReportPeriod.BySprintId) throw BadRequestException("bucketStart or a sprintId period is required")
        return sprintScopeItems(
            filter.copy(activityType = null, workCategory = null), SprintBucket.DELIVERED, segmentAccountId, nowMs, epicSegment,
        )
    }
    if (epicSegment != null) {
        throw BadRequestException("epicSegment narrows the sprint view's bars only (a sprintId period, no bucketStart)")
    }
    val start = bucketFirstDay
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
