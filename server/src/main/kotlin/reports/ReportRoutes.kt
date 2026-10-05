package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.MetricsSettingsServiceKey
import ch.nokillswit.metrics.TeamMembershipServiceKey
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/reports/filters")
class ReportFiltersRoute

@Serializable
@Resource("/api/v1/reports/velocity")
class ReportVelocityRoute

@Serializable
@Resource("/api/v1/reports/throughput")
class ReportThroughputRoute

@Serializable
@Resource("/api/v1/reports/task-estimation-accuracy")
class ReportTaskEstimationAccuracyRoute

@Serializable
@Resource("/api/v1/reports/epic-estimation-accuracy")
class ReportEpicEstimationAccuracyRoute

@Serializable
@Resource("/api/v1/reports/estimate-adjustments")
class ReportEstimateAdjustmentsRoute

@Serializable
@Resource("/api/v1/reports/sprint-consistency")
class ReportSprintConsistencyRoute

@Serializable
@Resource("/api/v1/reports/cycle-time")
class ReportCycleTimeRoute

@Serializable
@Resource("/api/v1/reports/reported-time-ratio")
class ReportReportedTimeRatioRoute

@Serializable
@Resource("/api/v1/reports/wip")
class ReportWipRoute

@Serializable
@Resource("/api/v1/reports/backlog")
class ReportBacklogRoute

@Serializable
@Resource("/api/v1/reports/aging-wip")
class ReportAgingWipRoute

@Serializable
@Resource("/api/v1/reports/blocked-time")
class ReportBlockedTimeRoute

@Serializable
@Resource("/api/v1/reports/data-quality")
class ReportDataQualityRoute

@Serializable
@Resource("/api/v1/reports/epic-progress")
class ReportEpicProgressRoute

@Serializable
@Resource("/api/v1/reports/cost-matrix")
class ReportCostMatrixRoute

@Serializable
@Resource("/api/v1/reports/deep-dive")
class ReportDeepDiveRoute

@Serializable
@Resource("/api/v1/reports/deep-dive/sprints")
class ReportDeepDiveSprintsRoute

@Serializable
@Resource("/api/v1/reports/deep-dive/epics")
class ReportDeepDiveEpicsRoute

@Serializable
@Resource("/api/v1/reports/deep-dive/epics/{epicKey}/tasks")
class ReportDeepDiveEpicTasksRoute(val epicKey: String)

val ReportServiceKey = AttributeKey<ReportService>("ReportService")

/**
 * The `reports/` package's composition root, mirroring `metrics/Metrics.kt`'s OWN shape: this
 * package composes services `configureDatabase`/`configureJira`/`configureMetrics` already
 * published, so it constructs and publishes [ReportService] here — inside its own
 * `configureReportRoutes()` — rather than in `infra/db/Database.kt` itself, which runs BEFORE
 * `configureMetrics` and so cannot see [MetricsSettingsServiceKey]/[TeamMembershipServiceKey] yet.
 * Registered in `application.yaml` after `configureMetrics`/the ingest route modules (the
 * "— features" group), alongside `MetricsConfigRoutes.kt`/`TeamMembershipRoutes.kt`/
 * `JiraUsersRoutes.kt`.
 *
 * Every report is one [reportGet] registration inside the shared `routing { authenticate { … } }` block, grouped into private
 * `Route.xxxRoutes` registrars by report number (detekt's `LongMethod`, the documented feature-template idiom).
 */
fun Application.configureReportRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val database = attributes[R2dbcDatabaseKey]
    val metricsSettings = attributes[MetricsSettingsServiceKey]
    val teamMembership = attributes[TeamMembershipServiceKey]
    val reportService = ReportService(database, metricsSettings, teamMembership)
    attributes.put(ReportServiceKey, reportService)

    routing {
        authenticate {
            // Any signed-in user — D12 ("everyone sees every level"; reports are read-only, so no
            // audit event of its own — `.claude/docs/authorization.md`/`observability.md`). No
            // query params, so `call.caller()` is the ENTIRE guard (parses/validates the JWT claims,
            // the `teams/TeamRoutes.kt` list-route idiom of calling it even with nothing further to
            // check).
            get<ReportFiltersRoute> {
                call.caller()
                call.respond(HttpStatusCode.OK, reportService.filters(nowMillis()))
            }
            deliveryRoutes(reportService, metricsSettings)
            flowRoutes(reportService, metricsSettings)
            qualityEpicAndCostRoutes(reportService, metricsSettings)
            deepDiveRoutes(reportService)
        }
    }
}

/**
 * ONE report endpoint: the `call.caller()` guard FIRST (403/401 before any parameter is read, so 403 wins over 400), the shared
 * filter parse against the configured working calendar ([defaultView] is the report's own `domainView` default), then
 * [respond] with the filter, the raw query parameters (for a report's own extras — parsed AFTER the filter, so the shared
 * `400`s keep their order) and the request's `now`.
 */
private inline fun <reified R : Any> Route.reportGet(
    metricsSettings: MetricsSettingsService,
    defaultView: DomainView,
    crossinline respond: suspend (filter: ReportFilter, params: Parameters, nowMs: Long) -> Any,
) {
    get<R> {
        call.caller()
        val calendar = reportsWorkingCalendar(metricsSettings)
        val nowMs = nowMillis()
        val params = call.request.queryParameters
        val filter = params.parseReportFilter(calendar, nowMs, defaultView)
        call.respond(HttpStatusCode.OK, respond(filter, params, nowMs))
    }
}

/** The sprint, estimation, cycle-time and reported-time reports (1–8), in report order. */
private fun Route.deliveryRoutes(reportService: ReportService, metricsSettings: MetricsSettingsService) {
    // Velocity (report 1) carries no domain slice (measures.md's Report 1 rows: domain "—"), so the TASK/EPIC default is a
    // harmless placeholder — every response still echoes it in `meta`.
    reportGet<ReportVelocityRoute>(metricsSettings, DomainView.TASK) { filter, _, _ -> reportService.velocity(filter) }
    // Throughput's (report 2) period view honours domain/activityType/workCategory and defaults to the TASK domain view
    // (D3: delivery/flow measures stay with the task's own domain).
    reportGet<ReportThroughputRoute>(metricsSettings, DomainView.TASK) { filter, params, nowMs ->
        reportService.throughput(filter, params.optionalEnum<ThroughputBucket>("bucket") ?: ThroughputBucket.WEEK, nowMs)
    }
    // Estimation accuracy (reports 3, 4) and adjustments (report 5). Tasks default to the TASK domain view (D3); epic
    // accuracy is PV/EV/AC-shaped (plan §7) so it defaults to EPIC — which for an epic is always its own space either way,
    // the view is only echoed in `meta`.
    reportGet<ReportTaskEstimationAccuracyRoute>(metricsSettings, DomainView.TASK) { filter, _, nowMs ->
        reportService.taskEstimationAccuracy(filter, nowMs)
    }
    reportGet<ReportEpicEstimationAccuracyRoute>(metricsSettings, DomainView.EPIC) { filter, _, nowMs ->
        reportService.epicEstimationAccuracy(filter, nowMs)
    }
    reportGet<ReportEstimateAdjustmentsRoute>(metricsSettings, DomainView.TASK) { filter, _, nowMs ->
        reportService.estimateAdjustments(filter, nowMs)
    }
    // Sprint consistency (reports 6.1-6.3) is sprint-scoped like velocity: no domain slice, so the TASK/EPIC default is a
    // harmless placeholder echoed in `meta`.
    reportGet<ReportSprintConsistencyRoute>(metricsSettings, DomainView.TASK) { filter, _, _ -> reportService.sprintConsistency(filter) }
    // Cycle time (report 7) and reported ÷ cycle (report 8): delivery/flow measures, so the task's own domain (D3).
    reportGet<ReportCycleTimeRoute>(metricsSettings, DomainView.TASK) { filter, params, nowMs ->
        reportService.cycleTime(filter, params.optionalEnum<ThroughputBucket>("bucket") ?: ThroughputBucket.WEEK, nowMs)
    }
    reportGet<ReportReportedTimeRatioRoute>(metricsSettings, DomainView.TASK) { filter, _, nowMs ->
        reportService.reportedTimeRatio(filter, nowMs)
    }
}

/** The flow batch (reports 9–12). */
private fun Route.flowRoutes(reportService: ReportService, metricsSettings: MetricsSettingsService) {
    // WIP (report 9) and the estimated backlog (reports 10 + 13) read the daily aggregates: delivery/flow
    // measures, so the task's own domain (D3) — the aggregates carry no activity-type/work-category slice.
    reportGet<ReportWipRoute>(metricsSettings, DomainView.TASK) { filter, params, nowMs ->
        val by = params.optionalEnum<WipBy>("by") ?: WipBy.STAGE
        val itemKind = params.optionalEnum<WipItemKind>("itemKind") ?: WipItemKind.TASK
        reportService.wip(filter, by, itemKind, nowMs)
    }
    reportGet<ReportBacklogRoute>(metricsSettings, DomainView.TASK) { filter, _, nowMs -> reportService.backlog(filter, nowMs) }
    // Aging WIP (report 11) is "as of now" (the period is ignored); blocked time (report 12) is a delivery/flow
    // measure over DONE items, so the task's own domain (D3). Tasks and epics are different grains: the blocked-time
    // `itemKind` defaults to TASK.
    reportGet<ReportAgingWipRoute>(metricsSettings, DomainView.TASK) { filter, _, nowMs -> reportService.agingWip(filter, nowMs) }
    reportGet<ReportBlockedTimeRoute>(metricsSettings, DomainView.TASK) { filter, params, nowMs ->
        reportService.blockedTime(filter, params.optionalEnum<BlockedItemKind>("itemKind") ?: BlockedItemKind.TASK, nowMs)
    }
}

/** Data quality, epic progress and the cost matrix (reports 14–16). */
private fun Route.qualityEpicAndCostRoutes(reportService: ReportService, metricsSettings: MetricsSettingsService) {
    // Data quality (report 14): findings about tasks follow the task's own domain (D3), so TASK is the default view; the period
    // is the tasks' `done_at` and the worklogs' `started_at`, and open started tasks and open epics are listed whatever the period.
    reportGet<ReportDataQualityRoute>(metricsSettings, DomainView.TASK) { filter, _, nowMs -> reportService.dataQuality(filter, nowMs) }
    // Epic progress / EVM (report 15) is PV/EV/AC-shaped, so it defaults to (and only accepts) the EPIC domain view (D3);
    // `epicId` — an epic's issue key — is its own parameter, outside the shared filter.
    reportGet<ReportEpicProgressRoute>(metricsSettings, DomainView.EPIC) { filter, params, nowMs ->
        val epicId = params.optionalString("epicId")
        // A present-but-blank epicId is a mistake, not "no epic": it must not silently answer for the whole unit.
        if (epicId == null && params.contains("epicId")) throw BadRequestException("epicId must not be blank")
        reportService.epicProgress(filter, epicId, nowMs)
    }
    // The cost matrix (report 16) is a worklog-cost measure — PV/EV/AC-shaped, so it defaults to the EPIC domain view (D3); an
    // explicit `domainView=TASK` switches the columns to the task's own domain.
    reportGet<ReportCostMatrixRoute>(metricsSettings, DomainView.EPIC) { filter, _, nowMs -> reportService.costMatrix(filter, nowMs) }
}

/**
 * An optional free-text/identifier parameter trimmed and checked for control characters (`400`, never a Postgres NUL error): the
 * deep dive's `domain` and `q`.
 */
internal fun Parameters.optionalSingleLine(name: String): String? =
    optionalString(name)?.let { sanitizeSingleLine(it, name) }?.takeIf { it.isNotEmpty() }

/**
 * The deep dive (report 17): the report itself and its three picker option lists. Any signed-in user (D12), read-only (no audit),
 * `call.caller()` first so the guard wins over every `400`. The report parses its own repeated-key selection
 * ([parseDeepDive], no shared filter); the lists are ordinary paged list endpoints, the paging and sort whitelist parsed BEFORE the
 * list's own parameters.
 */
private fun Route.deepDiveRoutes(reportService: ReportService) {
    get<ReportDeepDiveRoute> {
        call.caller()
        val request = call.request.queryParameters.parseDeepDive()
        call.respond(HttpStatusCode.OK, reportService.deepDive(request, nowMillis()))
    }
    get<ReportDeepDiveSprintsRoute> {
        call.caller()
        val paging = call.parsePaging(DEEP_DIVE_SPRINT_SORT_FIELDS, listOf(SortField("id", descending = true)))
        val params = call.request.queryParameters
        val domain = params.optionalSingleLine("domain") ?: throw BadRequestException("domain is required")
        val page = reportService.deepDiveSprints(domain, params.optionalUInt("connectionId"), params.optionalSingleLine("q"), paging)
        call.respond(HttpStatusCode.OK, page)
    }
    get<ReportDeepDiveEpicsRoute> {
        call.caller()
        val paging = call.parsePaging(DEEP_DIVE_EPIC_SORT_FIELDS, listOf(SortField("key", descending = false)))
        val params = call.request.queryParameters
        val page = reportService.deepDiveEpics(
            params.optionalSingleLine("domain"), params.optionalUInt("connectionId"), params.optionalSingleLine("q"), paging,
        )
        call.respond(HttpStatusCode.OK, page)
    }
    get<ReportDeepDiveEpicTasksRoute> { route ->
        call.caller()
        val paging = call.parsePaging(DEEP_DIVE_TASK_SORT_FIELDS, listOf(SortField("key", descending = false)))
        val params = call.request.queryParameters
        val page = reportService.deepDiveEpicTasks(
            route.epicKey, params.optionalUInt("connectionId"), params.optionalSingleLine("q"), paging,
        )
        call.respond(HttpStatusCode.OK, page)
    }
}

/**
 * The SAME zone/weekend/holiday triple `metrics/MetricsDeriver.kt` reads off `metrics.settings` to
 * build its own [WorkingCalendar] — every report route needs one purely to turn `from`/`to` ISO
 * dates into UTC-millis bounds ([parseReportFilter]'s own doc comment), so this is the ONE place
 * every later report's route reuses rather than re-deriving the calendar itself.
 */
private suspend fun reportsWorkingCalendar(metricsSettings: MetricsSettingsService): WorkingCalendar =
    WorkingCalendar.of(metricsSettings.read())
