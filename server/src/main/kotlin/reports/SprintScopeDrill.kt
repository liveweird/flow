package ch.nokillswit.reports

import ch.nokillswit.metrics.SprintBucket
import ch.nokillswit.metrics.bucketMd
import ch.nokillswit.metrics.inBucket
import ch.nokillswit.metrics.sumMd
import io.ktor.server.plugins.BadRequestException

/**
 * `GET /api/v1/reports/sprint-consistency/items`: the tasks behind ONE sprint bucket of ONE sprint — the shared drill of every
 * sprint-scope bar (Commitments' committed/final, Throughput's delivered, Sprint consistency's seven, the Home chart). It runs
 * the report's own scope fetch ([fetchScopeEntries], with the activity/category slice so a sliced request is live-only like the
 * figures) over the sprint a `sprintId` period names and keeps the rows the kernel's own predicate
 * ([inBucket][ch.nokillswit.metrics.inBucket], the one [sprintTotals][ch.nokillswit.metrics.sprintTotals] sums by) puts in
 * [bucket]; each item's figure is that bucket's estimate column ([bucketMd][ch.nokillswit.metrics.bucketMd]) and the bar's MD
 * the kernel's own rounded sum ([sumMd]). Picked accounts (USER level) narrow the rows by `assignee_at_commitment`, and
 * [segmentAccountId] to one of them (so the bar and the list are that person's share). A period that is not a single sprint is a `400`.
 */
internal suspend fun ReportService.sprintScopeItems(
    filter: ReportFilter,
    bucket: SprintBucket,
    segmentAccountId: String?,
    nowMs: Long,
): ReportDrillItems {
    if (filter.period !is ReportPeriod.BySprintId) throw BadRequestException("sprintId is required (the items of one sprint's bucket)")
    val segment = filter.requireSegment(segmentAccountId)
    return reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val sprints = scope.sprintRows.associateBy { it.connectionId to it.sprintId }
        val rows = fetchScopeEntries(
            scope.sprintRows,
            filter.accountIds.takeIf { it.isNotEmpty() },
            ScopeSlice(filter.activityType, filter.workCategory),
        ).filter { it.item.inBucket(bucket) }
            .filter { segment == null || it.item.assigneeAtCommitment == segment }
        val bar = ReportDrillBar(count = rows.size, sumMd = sumMd(rows.map { it.item.bucketMd(bucket) }), from = null, to = null)
        drillItems(
            scope.meta,
            bar,
            rows.map { entry ->
                val sprint = sprints.getValue(entry.connectionId to entry.sprintId)
                DrillRow(
                    connectionId = entry.connectionId,
                    issueId = entry.item.issueId,
                    itemKind = DrillItemKind.TASK,
                    accountId = entry.item.assigneeAtCommitment,
                    teamId = sprint.teamId,
                    anchorAt = sprint.completedAt,
                    figure = entry.item.bucketMd(bucket),
                )
            },
        )
    }
}
