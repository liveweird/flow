package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.metrics.SprintBucket
import io.ktor.resources.Resource
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/**
 * The drill routes: `GET /api/v1/reports/<report>/items`, the items behind one bar of a report's chart. Same posture as every
 * report — any signed-in user, read-only, no audit, `call.caller()` first — and each takes its PARENT report's full parameter list
 * plus the bar's identity ([reportGet] parses the filter, so the parent's `400`s come first). `.claude/docs/reports.md`
 * "Drill to items".
 */
@Serializable
@Resource("/api/v1/reports/throughput/items")
class ReportThroughputItemsRoute

@Serializable
@Resource("/api/v1/reports/sprint-consistency/items")
class ReportSprintConsistencyItemsRoute

@Serializable
@Resource("/api/v1/reports/cycle-time/items")
class ReportCycleTimeItemsRoute

@Serializable
@Resource("/api/v1/reports/reported-time-ratio/items")
class ReportReportedTimeItemsRoute

@Serializable
@Resource("/api/v1/reports/task-estimation-accuracy/items")
class ReportTaskAccuracyItemsRoute

@Serializable
@Resource("/api/v1/reports/task-estimates/items")
class ReportTaskEstimatesItemsRoute

@Serializable
@Resource("/api/v1/reports/epic-estimation-accuracy/items")
class ReportEpicAccuracyItemsRoute

@Serializable
@Resource("/api/v1/reports/estimate-adjustments/items")
class ReportEstimateAdjustmentsItemsRoute

@Serializable
@Resource("/api/v1/reports/blocked-time/items")
class ReportBlockedTimeItemsRoute

@Serializable
@Resource("/api/v1/reports/costs/items")
class ReportCostsItemsRoute

/**
 * Registers every drill route next to the reports they belong to (`multipleAccounts` mirrors the parent report). Each answers through
 * the cache validators like its parent (`ReportValidators.kt`): the bar's identity parses in the lambda (every `400` that needs no
 * data), the [answer] runs only when `If-None-Match` does not match, and the clock is the parent's.
 */
internal fun Route.drillRoutes(reportService: ReportService) {
    // An OPEN sprint's window ends at `now` (Throughput, Costs, and so their drills): the five-minute clock, as for the parent.
    reportGet<ReportThroughputItemsRoute>(
        reportService, DomainView.TASK, multipleAccounts = true, clock = StampClock.FIVE_MINUTES,
    ) { filter, params, nowMs ->
        val bucket = params.optionalEnum<ThroughputBucket>("bucket") ?: ThroughputBucket.WEEK
        val bucketFirstDay = params.optionalString("bucketStart")?.let { parseIsoDate(it, "bucketStart") }
        val segmentAccountId = params.optionalString("segmentAccountId")
        val epicSegment = params.optionalEpicSegment()
        val measure = params.optionalEnum<ThroughputDrillMeasure>("measure") ?: ThroughputDrillMeasure.DELIVERED
        // The sprint mode (a sprintId period, no bucketStart) reads one sprint, and a sprint id is shared by one site's teams:
        // the team picks it.
        if (bucketFirstDay == null && filter.period is ReportPeriod.BySprintId && filter.teamId == null) {
            throw BadRequestException("the sprint mode needs teamId: a sprint's items are read for the sprint's own team")
        }
        answer {
            reportService.throughputItems(
                filter = filter, bucket = bucket, bucketFirstDay = bucketFirstDay, segmentAccountId = segmentAccountId,
                nowMs = nowMs, epicSegment = epicSegment, measure = measure,
            )
        }
    }
    // The ONE sprint-scope drill (Commitments, Throughput's sprint chart, Sprint consistency, Home): the parent report takes a single
    // `accountId`, but its stacked segments (Commitments' and Throughput's USER level) need up to eight, so this route opts in.
    // It carries no domain slice, so the team is its only scope (`TEAM_ONLY`).
    reportGet<ReportSprintConsistencyItemsRoute>(
        reportService, DomainView.TASK, multipleAccounts = true, scope = ReportScopeRule.TEAM_ONLY,
    ) { filter, params, nowMs ->
        val bucket = params.requireEnum<SprintBucket>("bucket")
        val segmentAccountId = params.optionalString("segmentAccountId")
        val epicSegment = params.optionalEpicSegment()
        answer {
            reportService.sprintScopeItems(
                filter = filter, bucket = bucket, segmentAccountId = segmentAccountId, nowMs = nowMs, epicSegment = epicSegment,
            )
        }
    }
    // The histogram drills: `view` picks one of the parent's distributions, `bucketIndex` its bar (0-based, the open tail last).
    // `bucket` is the parent's trend resolution — validated like the parent, but it plays no part in a histogram bar.
    reportGet<ReportCycleTimeItemsRoute>(reportService, DomainView.TASK, multipleAccounts = true) { filter, params, nowMs ->
        params.optionalEnum<ThroughputBucket>("bucket") // validated like the parent's (a bad value is its 400); no bar depends on it
        val view = params.requireEnum<CycleTimeDrillView>("view")
        val bucketIndex = params.requireBucketIndex()
        val segmentAccountId = params.optionalString("segmentAccountId")
        answer { reportService.cycleTimeItems(filter, view, bucketIndex, segmentAccountId, nowMs) }
    }
    reportGet<ReportReportedTimeItemsRoute>(reportService, DomainView.TASK, multipleAccounts = true) { filter, params, nowMs ->
        val view = params.requireEnum<ReportedTimeDrillView>("view")
        val bucketIndex = params.requireBucketIndex()
        val segmentAccountId = params.optionalString("segmentAccountId")
        answer { reportService.reportedTimeItems(filter, view, bucketIndex, segmentAccountId, nowMs) }
    }
    reportGet<ReportTaskAccuracyItemsRoute>(reportService, DomainView.TASK, multipleAccounts = true) { filter, params, nowMs ->
        val view = params.requireEnum<AccuracyDrillView>("view")
        val bucketIndex = params.requireBucketIndex()
        val segmentAccountId = params.optionalString("segmentAccountId")
        answer { reportService.taskAccuracyItems(filter, view, bucketIndex, segmentAccountId, nowMs) }
    }
    reportGet<ReportTaskEstimatesItemsRoute>(reportService, DomainView.TASK, multipleAccounts = true) { filter, params, nowMs ->
        val view = params.requireEnum<AccuracyDrillView>("view")
        val bucketIndex = params.requireBucketIndex()
        val segmentAccountId = params.optionalString("segmentAccountId")
        answer { reportService.taskEstimatesItems(filter, view, bucketIndex, segmentAccountId, nowMs) }
    }
    // Adjustments: the tasks' change histogram splits per picked person (`segmentAccountId`), the epics' does not.
    reportGet<ReportEstimateAdjustmentsItemsRoute>(reportService, DomainView.TASK, multipleAccounts = true) { filter, params, nowMs ->
        val view = params.requireEnum<AdjustmentDrillView>("view")
        val bucketIndex = params.requireBucketIndex()
        val segmentAccountId = params.optionalString("segmentAccountId")
        answer { reportService.estimateAdjustmentItems(filter, view, bucketIndex, segmentAccountId, nowMs) }
    }
    // Epics carry no user (the parent takes one `accountId`, USER level is empty) and the blocked-time histograms carry no
    // per-person split, so neither drill has a `segmentAccountId`.
    reportGet<ReportEpicAccuracyItemsRoute>(reportService, DomainView.EPIC) { filter, params, nowMs ->
        val view = params.requireEnum<AccuracyDrillView>("view")
        val bucketIndex = params.requireBucketIndex()
        answer { reportService.epicAccuracyItems(filter, view, bucketIndex, nowMs) }
    }
    reportGet<ReportBlockedTimeItemsRoute>(reportService, DomainView.TASK) { filter, params, nowMs ->
        val itemKind = params.optionalEnum<BlockedItemKind>("itemKind") ?: BlockedItemKind.TASK
        val view = params.requireEnum<BlockedDrillView>("view")
        val bucketIndex = params.requireBucketIndex()
        answer { reportService.blockedTimeItems(filter, itemKind, view, bucketIndex, nowMs) }
    }
    // Costs' charts: `by` picks a donut's breakdown with the repeated `key` of the slice (several keys = the chart's folded "Other"), or
    // `by=EPIC` + `epicSegment` a segment of one sprint's AC bar; no `by` is that sprint's whole bar (both need a `sprintId` period and
    // a team). The parent takes one `accountId`, so there is no per-person segment.
    reportGet<ReportCostsItemsRoute>(reportService, DomainView.EPIC, clock = StampClock.FIVE_MINUTES) { filter, params, nowMs ->
        val selection = params.costsDrillSelection()
        answer { reportService.costsItems(filter, selection, nowMs) }
    }
}
