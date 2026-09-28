package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.db.nowMillis
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
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/reports/filters")
class ReportFiltersRoute

@Serializable
@Resource("/api/v1/reports/velocity")
class ReportVelocityRoute

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
 * v0.3.0 M4 commit 10a built `/reports/filters`; commit 10b adds `/reports/velocity` (Report 1) —
 * throughput/sprint-consistency and every later report land as their own commits (plan §10) and
 * register their own `get<...>` blocks in this SAME `routing { authenticate { … } }` block, the
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
    val zone = runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.of("UTC"))
    val holidays = settings.holidays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
    return WorkingCalendar(zone, settings.weekendDays.toSet(), holidays)
}
