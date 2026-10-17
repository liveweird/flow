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

/** Registers every drill route next to the reports they belong to (`multipleAccounts` mirrors the parent report). */
internal fun Route.drillRoutes(reportService: ReportService, metricsSettings: MetricsSettingsService) {
    reportGet<ReportThroughputItemsRoute>(metricsSettings, DomainView.TASK, multipleAccounts = true) { _, filter, params, nowMs ->
        reportService.throughputItems(
            filter = filter,
            bucket = params.optionalEnum<ThroughputBucket>("bucket") ?: ThroughputBucket.WEEK,
            bucketFirstDay = params.optionalString("bucketStart")?.let { parseIsoDate(it, "bucketStart") },
            segmentAccountId = params.optionalString("segmentAccountId"),
            nowMs = nowMs,
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
        )
    }
}
