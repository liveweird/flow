package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.SprintBucket
import io.ktor.resources.Resource
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
@Resource("/api/v1/reports/cost-matrix/items")
class ReportCostMatrixItemsRoute

/** Registers every drill route next to the reports they belong to (`multipleAccounts` mirrors the parent report). */
internal fun Route.drillRoutes(reportService: ReportService, metricsSettings: MetricsSettingsService) {
    reportGet<ReportThroughputItemsRoute>(metricsSettings, DomainView.TASK, multipleAccounts = true) { _, filter, params, nowMs ->
        reportService.throughputItems(
            filter = filter,
            bucket = params.optionalEnum<ThroughputBucket>("bucket") ?: ThroughputBucket.WEEK,
            bucketFirstDay = params.optionalString("bucketStart")?.let { parseIsoDate(it, "bucketStart") },
            segmentAccountId = params.optionalString("segmentAccountId"),
            nowMs = nowMs,
            epicSegment = params.optionalEpicSegment(),
        )
    }
    // The ONE sprint-scope drill (Commitments, Throughput's sprint chart, Sprint consistency, Home): the parent report takes a single
    // `accountId`, but its stacked segments (Commitments' and Throughput's USER level) need up to eight, so this route opts in.
    reportGet<ReportSprintConsistencyItemsRoute>(metricsSettings, DomainView.TASK, multipleAccounts = true) { _, filter, params, nowMs ->
        reportService.sprintScopeItems(
            filter = filter,
            bucket = params.requireEnum<SprintBucket>("bucket"),
            segmentAccountId = params.optionalString("segmentAccountId"),
            nowMs = nowMs,
            epicSegment = params.optionalEpicSegment(),
        )
    }
    // The histogram drills: `view` picks one of the parent's distributions, `bucketIndex` its bar (0-based, the open tail last).
    // `bucket` is the parent's trend resolution — validated like the parent, but it plays no part in a histogram bar.
    reportGet<ReportCycleTimeItemsRoute>(metricsSettings, DomainView.TASK, multipleAccounts = true) { _, filter, params, nowMs ->
        params.optionalEnum<ThroughputBucket>("bucket") // validated like the parent's (a bad value is its 400); no bar depends on it
        reportService.cycleTimeItems(
            filter, params.requireEnum<CycleTimeDrillView>("view"), params.requireBucketIndex(),
            params.optionalString("segmentAccountId"), nowMs,
        )
    }
    reportGet<ReportReportedTimeItemsRoute>(metricsSettings, DomainView.TASK, multipleAccounts = true) { _, filter, params, nowMs ->
        reportService.reportedTimeItems(
            filter, params.requireEnum<ReportedTimeDrillView>("view"), params.requireBucketIndex(),
            params.optionalString("segmentAccountId"), nowMs,
        )
    }
    reportGet<ReportTaskAccuracyItemsRoute>(metricsSettings, DomainView.TASK, multipleAccounts = true) { _, filter, params, nowMs ->
        reportService.taskAccuracyItems(
            filter, params.requireEnum<AccuracyDrillView>("view"), params.requireBucketIndex(),
            params.optionalString("segmentAccountId"), nowMs,
        )
    }
    reportGet<ReportTaskEstimatesItemsRoute>(metricsSettings, DomainView.TASK, multipleAccounts = true) { _, filter, params, nowMs ->
        reportService.taskEstimatesItems(
            filter, params.requireEnum<AccuracyDrillView>("view"), params.requireBucketIndex(),
            params.optionalString("segmentAccountId"), nowMs,
        )
    }
    // Adjustments: the tasks' change histogram splits per picked person (`segmentAccountId`), the epics' does not.
    reportGet<ReportEstimateAdjustmentsItemsRoute>(metricsSettings, DomainView.TASK, multipleAccounts = true) { _, filter, params, nowMs ->
        reportService.estimateAdjustmentItems(
            filter, params.requireEnum<AdjustmentDrillView>("view"), params.requireBucketIndex(),
            params.optionalString("segmentAccountId"), nowMs,
        )
    }
    // Epics carry no user (the parent takes one `accountId`, USER level is empty) and the blocked-time histograms carry no
    // per-person split, so neither drill has a `segmentAccountId`.
    reportGet<ReportEpicAccuracyItemsRoute>(metricsSettings, DomainView.EPIC) { _, filter, params, nowMs ->
        reportService.epicAccuracyItems(filter, params.requireEnum<AccuracyDrillView>("view"), params.requireBucketIndex(), nowMs)
    }
    reportGet<ReportBlockedTimeItemsRoute>(metricsSettings, DomainView.TASK) { _, filter, params, nowMs ->
        reportService.blockedTimeItems(
            filter, params.optionalEnum<BlockedItemKind>("itemKind") ?: BlockedItemKind.TASK,
            params.requireEnum<BlockedDrillView>("view"), params.requireBucketIndex(), nowMs,
        )
    }
    // The cost matrix's activity-type / work-category charts: `by` picks the breakdown, the repeated `key` the slice (several keys =
    // the chart's folded "Other"); `by=EPIC` takes epic ids, NO_EPIC or OTHER_EPICS. The parent takes one `accountId`, so there is
    // no segment.
    reportGet<ReportCostMatrixItemsRoute>(metricsSettings, DomainView.EPIC) { _, filter, params, nowMs ->
        val by = params.requireEnum<CostBreakdown>("by")
        val keys = params.requireCostKeys()
        if (by == CostBreakdown.EPIC) requireEpicDrillKeys(keys)
        reportService.costMatrixItems(filter, by, keys, nowMs)
    }
}
