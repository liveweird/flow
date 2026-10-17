package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.metrics.MetricsSettingsService
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
}
