package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate

/**
 * `GET /api/v1/reports/throughput/items?measure=ACTUAL_COST`: the tasks behind ONE actual-cost figure of the Throughput charts -- a bar of
 * the PERIOD chart (`bucketStart`, the first day of a bucket the report draws) or of the SPRINT chart (a `sprintId` period, no
 * `bucketStart`). It reads the very worklogs the figure sums ([periodActualCostLogs] / [sprintActualCostLogs]) and rolls them up to the
 * task each counts toward (a sub-task's to its parent, A31), in the SHAPE of the Costs drill (`CostsDrill.kt`): one row per
 * task, `figure` the MD logged on it inside the bar (the rounded exact sum), `anchorAt` the latest `started_at` among those worklogs,
 * `assignee` its authors' display names (the largest contributor first; `accountId` set only for exactly one author) and `bar` recomputed
 * over exactly the listed set -- `bar.sumMd` is the rounded EXACT sum of the worklogs, i.e. the chart's `actualCostMd`, `count` the tasks.
 * At USER level only the picked authors' worklogs are listed, as in the figure; [segmentAccountId] (one of the picks) narrows to that
 * person's own logged time (the `byUser` entry). `epicSegment` belongs to the delivered bars (`400`).
 */
internal suspend fun ReportService.throughputActualCostItems(
    filter: ReportFilter,
    bucket: ThroughputBucket,
    bucketFirstDay: LocalDate?,
    segmentAccountId: String?,
    nowMs: Long,
    epicSegment: EpicSegmentSelector?,
): ReportDrillItems {
    if (epicSegment != null) throw BadRequestException("epicSegment narrows the delivered bars only (measure=DELIVERED)")
    if (bucketFirstDay == null && filter.period !is ReportPeriod.BySprintId) {
        throw BadRequestException("bucketStart or a sprintId period is required")
    }
    val segment = filter.requireSegment(segmentAccountId)
    return reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val logs = if (bucketFirstDay == null) {
            val authors = filter.accountIds.takeIf { it.isNotEmpty() }
            // A sprint id is shared by one site's teams: without a teamId the bar is the union of their sprints.
            sprintActualCostLogs(scope.sprintRows, authors, nowMs).values.flatten()
        } else {
            val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
            val window = scope.window
            val drawn = if (window == null) emptyList() else bucketStarts(window.first, window.second, zone, bucket)
            if (window == null || bucketFirstDay !in drawn) {
                throw BadRequestException("bucketStart must be the first day of a $bucket bucket inside the period")
            }
            periodActualCostLogs(filter, scope.connectionIds, window, zone, bucket)[bucketFirstDay].orEmpty()
        }.filter { segment == null || it.authorAccountId == segment }
        val items = logs.groupBy { it.connectionId to it.ownerId }
        val names = accountDisplayNames(logs.mapNotNull { it.authorAccountId }.distinct())
        val bar = ReportDrillBar(count = items.size, sumMd = logs.costMd(), from = null, to = null)
        drillItems(
            scope.meta,
            bar,
            items.map { (owner, rows) ->
                val authors = rankedAuthors(rows.map { it.authorAccountId to it.md }, names)
                DrillRow(
                    connectionId = owner.first,
                    issueId = owner.second,
                    itemKind = DrillItemKind.TASK,
                    accountId = authors.singleOrNull()?.first,
                    teamId = null,
                    anchorAt = rows.maxOf { it.startedAt },
                    figure = rows.costMd(),
                    assignee = authors.takeIf { it.isNotEmpty() }?.joinToString(", ") { (account, _) -> names[account] ?: account },
                )
            },
        )
    }
}
