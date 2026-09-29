package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsConfigServiceKey
import ch.nokillswit.metrics.TeamMembershipServiceKey
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.time.LocalDate
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

val ReportServiceKey = AttributeKey<ReportService>("ReportService")

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
 * batch (`/reports/task-estimation-accuracy`, `/reports/epic-estimation-accuracy`, `/reports/estimate-adjustments`) — every later report
 * lands as its own commit (plan §10) and registers its own `get<...>` block in this SAME
 * `routing { authenticate { … } }` block, the `MetricsConfigRoutes.kt` shape (one registrar per resource, several routes inside).
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
 * The SAME zone/weekend/holiday triple `metrics/MetricsDeriver.kt` reads off `metrics.settings` to
 * build its own [WorkingCalendar] — every report route needs one purely to turn `from`/`to` ISO
 * dates into UTC-millis bounds ([parseReportFilter]'s own doc comment), so this is the ONE place
 * every later report's route reuses rather than re-deriving the calendar itself.
 */
private suspend fun reportsWorkingCalendar(metricsConfig: MetricsConfigService): WorkingCalendar {
    val settings = metricsConfig.read()
    val zone = zoneOf(settings.timeZone)
    val holidays = settings.holidays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
    return WorkingCalendar(zone, settings.weekendDays.toSet(), holidays)
}
