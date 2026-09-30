package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsConfigServiceKey
import ch.nokillswit.metrics.TeamMembershipServiceKey
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
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
@Resource("/api/v1/reports/sprint-consistency")
class ReportSprintConsistencyRoute

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
@Resource("/api/v1/reports/epic-progress")
class ReportEpicProgressRoute

@Serializable
@Resource("/api/v1/reports/data-quality")
class ReportDataQualityRoute

@Serializable
@Resource("/api/v1/reports/cost-matrix")
class ReportCostMatrixRoute

val ReportServiceKey = AttributeKey<ReportService>("ReportService")

/**
 * `GET /api/v1/reports/data-quality` (report 14). Findings about tasks follow the task's own domain (D3), so TASK is the default
 * view; the period is the tasks' `done_at` and the worklogs' `started_at`, and open started tasks and open epics are listed
 * whatever the period.
 */
private fun Route.reportDataQualityRoute(reportService: ReportService, metricsConfig: MetricsConfigService) {
    get<ReportDataQualityRoute> {
        call.caller()
        val calendar = reportsWorkingCalendar(metricsConfig)
        val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
        call.respond(HttpStatusCode.OK, reportService.dataQuality(filter, nowMillis()))
    }
}

/**
 * The `reports/` package's composition root, mirroring `metrics/Metrics.kt`'s OWN shape: this
 * package composes services `configureDatabase`/`configureJira`/`configureMetrics` already
 * published, so it constructs and publishes [ReportService] here — inside its own
 * `configureReportRoutes()` — rather than in `infra/db/Database.kt` itself, which runs BEFORE
 * `configureMetrics` and so cannot see [MetricsConfigServiceKey]/[TeamMembershipServiceKey] yet.
 * Registered in `application.yaml` after `configureMetrics`/the ingest route modules (the
 * "— features" group), alongside `MetricsConfigRoutes.kt`/`TeamMembershipRoutes.kt`/
 * `JiraUsersRoutes.kt`.
 *
 * v0.3.0 M4 commit 10a built `/reports/filters`; commit 10b adds `/reports/velocity` (Report 1), 10c
 * `/reports/throughput` (Report 2), 10d `/reports/sprint-consistency` (Reports 6.1-6.3), 12 the estimation
 * batch (`/reports/task-estimation-accuracy`, `/reports/epic-estimation-accuracy`,
 * `/reports/estimate-adjustments`), 12b `/reports/cycle-time` and `/reports/reported-time-ratio`, 15
 * `/reports/wip` and `/reports/backlog`, 15c `/reports/epic-progress` — every later report lands as its own commit
 * (plan §10) and registers its own `get<...>` block in this SAME `routing { authenticate { … } }` block, the
 * `MetricsConfigRoutes.kt` shape (one registrar per resource, several routes inside).
 */
fun Application.configureReportRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val database = attributes[R2dbcDatabaseKey]
    val metricsConfig = attributes[MetricsConfigServiceKey]
    val teamMembership = attributes[TeamMembershipServiceKey]
    val reportService = ReportService(database, metricsConfig, teamMembership)
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
            // Velocity carries no domain slice (measures.md's Report 1 rows: domain "—"), so the
            // TASK/EPIC default is a harmless placeholder — every response still echoes it in `meta`.
            get<ReportVelocityRoute> {
                call.caller()
                val calendar = reportsWorkingCalendar(metricsConfig)
                val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
                call.respond(HttpStatusCode.OK, reportService.velocity(filter))
            }
            // Throughput's period view honours domain/activityType/workCategory and defaults to the TASK
            // domain view (D3: delivery/flow measures stay with the task's own domain).
            get<ReportThroughputRoute> {
                call.caller()
                val calendar = reportsWorkingCalendar(metricsConfig)
                val params = call.request.queryParameters
                val filter = params.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
                val bucket = params.optionalEnum<ThroughputBucket>("bucket") ?: ThroughputBucket.WEEK
                call.respond(HttpStatusCode.OK, reportService.throughput(filter, bucket, nowMillis()))
            }
            // Sprint consistency (reports 6.1-6.3) is sprint-scoped like velocity: no domain slice, so the
            // TASK/EPIC default is a harmless placeholder echoed in `meta`.
            get<ReportSprintConsistencyRoute> {
                call.caller()
                val calendar = reportsWorkingCalendar(metricsConfig)
                val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
                call.respond(HttpStatusCode.OK, reportService.sprintConsistency(filter))
            }
            // Estimation accuracy (reports 3, 4) and adjustments (report 5). Tasks default to the TASK domain view
            // (D3); epic accuracy is PV/EV/AC-shaped (plan §7) so it defaults to EPIC — which for an epic is always
            // its own space either way, the view is only echoed in `meta`.
            get<ReportTaskEstimationAccuracyRoute> {
                call.caller()
                val calendar = reportsWorkingCalendar(metricsConfig)
                val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
                call.respond(HttpStatusCode.OK, reportService.taskEstimationAccuracy(filter, nowMillis()))
            }
            get<ReportEpicEstimationAccuracyRoute> {
                call.caller()
                val calendar = reportsWorkingCalendar(metricsConfig)
                val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.EPIC)
                call.respond(HttpStatusCode.OK, reportService.epicEstimationAccuracy(filter, nowMillis()))
            }
            // Cycle time (report 7) and reported ÷ cycle (report 8): delivery/flow measures, so the task's own domain (D3).
            get<ReportCycleTimeRoute> {
                call.caller()
                val calendar = reportsWorkingCalendar(metricsConfig)
                val params = call.request.queryParameters
                val filter = params.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
                val bucket = params.optionalEnum<ThroughputBucket>("bucket") ?: ThroughputBucket.WEEK
                call.respond(HttpStatusCode.OK, reportService.cycleTime(filter, bucket, nowMillis()))
            }
            get<ReportReportedTimeRatioRoute> {
                call.caller()
                val calendar = reportsWorkingCalendar(metricsConfig)
                val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
                call.respond(HttpStatusCode.OK, reportService.reportedTimeRatio(filter, nowMillis()))
            }
            flowRoutes(reportService, metricsConfig)
            epicProgressRoute(reportService, metricsConfig)
            reportDataQualityRoute(reportService, metricsConfig)
            costMatrixRoute(reportService, metricsConfig)
            get<ReportEstimateAdjustmentsRoute> {
                call.caller()
                val calendar = reportsWorkingCalendar(metricsConfig)
                val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
                call.respond(HttpStatusCode.OK, reportService.estimateAdjustments(filter, nowMillis()))
            }
        }
    }
}

/**
 * Epic progress / EVM (report 15) is PV/EV/AC-shaped, so it defaults to (and only accepts) the EPIC domain view (D3); `epicId` —
 * an epic's issue key — is its own parameter, outside the shared filter. A private `Route.xxx` registrar, since
 * [configureReportRoutes] itself has outgrown detekt's `LongMethod` threshold (the documented feature-template idiom).
 */
private fun Route.epicProgressRoute(reportService: ReportService, metricsConfig: MetricsConfigService) {
    get<ReportEpicProgressRoute> {
        call.caller()
        val calendar = reportsWorkingCalendar(metricsConfig)
        val params = call.request.queryParameters
        val filter = params.parseReportFilter(calendar, nowMillis(), DomainView.EPIC)
        val epicId = params.optionalString("epicId")
        // A present-but-blank epicId is a mistake, not "no epic": it must not silently answer for the whole unit.
        if (epicId == null && params.contains("epicId")) throw BadRequestException("epicId must not be blank")
        call.respond(HttpStatusCode.OK, reportService.epicProgress(filter, epicId, nowMillis()))
    }
}

/**
 * The cost matrix (report 16) is a worklog-cost measure — PV/EV/AC-shaped, so it defaults to the EPIC domain view (D3); an explicit
 * `domainView=TASK` switches the columns to the task's own domain.
 */
private fun Route.costMatrixRoute(reportService: ReportService, metricsConfig: MetricsConfigService) {
    get<ReportCostMatrixRoute> {
        call.caller()
        val calendar = reportsWorkingCalendar(metricsConfig)
        val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.EPIC)
        call.respond(HttpStatusCode.OK, reportService.costMatrix(filter, nowMillis()))
    }
}

/**
 * The SAME zone/weekend/holiday triple `metrics/MetricsDeriver.kt` reads off `metrics.settings` to
 * build its own [WorkingCalendar] — every report route needs one purely to turn `from`/`to` ISO
 * dates into UTC-millis bounds ([parseReportFilter]'s own doc comment), so this is the ONE place
 * every later report's route reuses rather than re-deriving the calendar itself.
 */
private suspend fun reportsWorkingCalendar(metricsConfig: MetricsConfigService): WorkingCalendar =
    workingCalendarOf(metricsConfig.read())

/**
 * The flow batch (reports 9–12), grouped out of [configureReportRoutes] per the `*Routes.kt` idiom (detekt `LongMethod`).
 */
private fun Route.flowRoutes(reportService: ReportService, metricsConfig: MetricsConfigService) {
    // WIP (report 9) and the estimated backlog (reports 10 + 13) read the daily aggregates: delivery/flow
    // measures, so the task's own domain (D3) — the aggregates carry no activity-type/work-category slice.
    get<ReportWipRoute> {
        call.caller()
        val calendar = reportsWorkingCalendar(metricsConfig)
        val params = call.request.queryParameters
        val filter = params.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
        val by = params.optionalEnum<WipBy>("by") ?: WipBy.STAGE
        val itemKind = params.optionalEnum<WipItemKind>("itemKind") ?: WipItemKind.TASK
        call.respond(HttpStatusCode.OK, reportService.wip(filter, by, itemKind, nowMillis()))
    }
    get<ReportBacklogRoute> {
        call.caller()
        val calendar = reportsWorkingCalendar(metricsConfig)
        val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
        call.respond(HttpStatusCode.OK, reportService.backlog(filter, nowMillis()))
    }
    // Aging WIP (report 11) is "as of now" (the period is ignored); blocked time (report 12) is a delivery/flow
    // measure over DONE items, so the task's own domain (D3). Tasks and epics are different grains: the blocked-time
    // `itemKind` defaults to TASK.
    get<ReportAgingWipRoute> {
        call.caller()
        val calendar = reportsWorkingCalendar(metricsConfig)
        val filter = call.request.queryParameters.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
        call.respond(HttpStatusCode.OK, reportService.agingWip(filter, nowMillis()))
    }
    get<ReportBlockedTimeRoute> {
        call.caller()
        val calendar = reportsWorkingCalendar(metricsConfig)
        val params = call.request.queryParameters
        val filter = params.parseReportFilter(calendar, nowMillis(), DomainView.TASK)
        val itemKind = params.optionalEnum<BlockedItemKind>("itemKind") ?: BlockedItemKind.TASK
        call.respond(HttpStatusCode.OK, reportService.blockedTime(filter, itemKind, nowMillis()))
    }
}
