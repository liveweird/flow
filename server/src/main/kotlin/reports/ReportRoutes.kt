package ch.nokillswit.reports

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalUInt
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.metrics.MetricsSettingsServiceKey
import ch.nokillswit.metrics.TeamMembershipServiceKey
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.plugins.servesApi
import io.ktor.http.Parameters
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
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
 * Every report is one [reportGet]/[reportGetWith] registration inside the shared `routing { authenticate { … } }` block,
 * grouped into private `Route.xxxRoutes` registrars by report number (detekt's `LongMethod`, the documented feature-template
 * idiom). All of them answer through the cache validators of `ReportValidators.kt`.
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
            // check). Like every report GET it answers through the cache validators (ReportValidators.kt);
            // the roster is "current as of now", hence the finer clock.
            get<ReportFiltersRoute> {
                call.caller()
                val nowMs = reportService.clock()
                val etag = reportService.etagFor(call, metricsSettings.read(), StampClock.FIVE_MINUTES, nowMs)
                call.respondRevalidated(etag) { reportService.filters(nowMs) }
            }
            deliveryRoutes(reportService)
            flowRoutes(reportService)
            qualityEpicAndCostRoutes(reportService)
            deepDiveRoutes(reportService)
        }
    }
}

/**
 * ONE report endpoint: the `call.caller()` guard FIRST (403/401 before any parameter is read, so 403 wins over 400), the shared
 * filter parse against the configured working calendar ([defaultView] is the report's own `domainView` default), the report's own
 * extra parameters ([parseExtras], parsed AFTER the filter so the shared `400`s keep their order), then — every `400` that needs
 * no data behind us — the ETag over the request and the data stamp (`ReportValidators.kt`, [clock] = how finely `now` enters it)
 * and [respond] with the filter, the extras and the request's `now`, which only runs when `If-None-Match` does not match.
 */
private inline fun <reified R : Any, X> Route.reportGetWith(
    reportService: ReportService,
    defaultView: DomainView,
    clock: StampClock = StampClock.DAY,
    crossinline parseExtras: (params: Parameters) -> X,
    crossinline respond: suspend (filter: ReportFilter, extras: X, nowMs: Long) -> Any,
) {
    get<R> {
        call.caller()
        val settings = reportService.metricsSettings.read()
        val nowMs = reportService.clock()
        val params = call.request.queryParameters
        val filter = params.parseReportFilter(WorkingCalendar.of(settings), nowMs, defaultView)
        val extras = parseExtras(params)
        val etag = reportService.etagFor(call, settings, clock, nowMs)
        call.respondRevalidated(etag) { respond(filter, extras, nowMs) }
    }
}

/** [reportGetWith] for a report with no parameters beyond the shared filter. */
private inline fun <reified R : Any> Route.reportGet(
    reportService: ReportService,
    defaultView: DomainView,
    clock: StampClock = StampClock.DAY,
    crossinline respond: suspend (filter: ReportFilter, nowMs: Long) -> Any,
) = reportGetWith<R, Unit>(reportService, defaultView, clock, parseExtras = {}) { filter, _, nowMs -> respond(filter, nowMs) }

/** The sprint, estimation, cycle-time and reported-time reports (1–8), in report order. */
private fun Route.deliveryRoutes(reportService: ReportService) {
    // Velocity (report 1) carries no domain slice (measures.md's Report 1 rows: domain "—"), so the TASK/EPIC default is a
    // harmless placeholder — every response still echoes it in `meta`.
    reportGet<ReportVelocityRoute>(reportService, DomainView.TASK) { filter, _ -> reportService.velocity(filter) }
    // Throughput's (report 2) period view honours domain/activityType/workCategory and defaults to the TASK domain view
    // (D3: delivery/flow measures stay with the task's own domain).
    reportGetWith<ReportThroughputRoute, _>(
        reportService, DomainView.TASK, parseExtras = { it.throughputBucket() },
    ) { filter, bucket, nowMs ->
        reportService.throughput(filter, bucket, nowMs)
    }
    // Estimation accuracy (reports 3, 4) and adjustments (report 5). Tasks default to the TASK domain view (D3); epic
    // accuracy is PV/EV/AC-shaped (plan §7) so it defaults to EPIC — which for an epic is always its own space either way,
    // the view is only echoed in `meta`.
    reportGet<ReportTaskEstimationAccuracyRoute>(reportService, DomainView.TASK) { filter, nowMs ->
        reportService.taskEstimationAccuracy(filter, nowMs)
    }
    reportGet<ReportEpicEstimationAccuracyRoute>(reportService, DomainView.EPIC) { filter, nowMs ->
        reportService.epicEstimationAccuracy(filter, nowMs)
    }
    reportGet<ReportEstimateAdjustmentsRoute>(reportService, DomainView.TASK) { filter, nowMs ->
        reportService.estimateAdjustments(filter, nowMs)
    }
    // Sprint consistency (reports 6.1-6.3) is sprint-scoped like velocity: no domain slice, so the TASK/EPIC default is a
    // harmless placeholder echoed in `meta`.
    reportGet<ReportSprintConsistencyRoute>(reportService, DomainView.TASK) { filter, _ -> reportService.sprintConsistency(filter) }
    // Cycle time (report 7) and reported ÷ cycle (report 8): delivery/flow measures, so the task's own domain (D3).
    reportGetWith<ReportCycleTimeRoute, _>(
        reportService, DomainView.TASK, parseExtras = { it.throughputBucket() },
    ) { filter, bucket, nowMs ->
        reportService.cycleTime(filter, bucket, nowMs)
    }
    reportGet<ReportReportedTimeRatioRoute>(reportService, DomainView.TASK) { filter, nowMs ->
        reportService.reportedTimeRatio(filter, nowMs)
    }
}

/** The flow batch (reports 9–12). */
private fun Route.flowRoutes(reportService: ReportService) {
    // WIP (report 9) and the estimated backlog (reports 10 + 13) read the daily aggregates: delivery/flow
    // measures, so the task's own domain (D3) — the aggregates carry no activity-type/work-category slice.
    reportGetWith<ReportWipRoute, _>(
        reportService, DomainView.TASK,
        parseExtras = { (it.optionalEnum<WipBy>("by") ?: WipBy.STAGE) to (it.optionalEnum<WipItemKind>("itemKind") ?: WipItemKind.TASK) },
    ) { filter, (by, itemKind), nowMs -> reportService.wip(filter, by, itemKind, nowMs) }
    reportGet<ReportBacklogRoute>(reportService, DomainView.TASK) { filter, nowMs -> reportService.backlog(filter, nowMs) }
    // Aging WIP (report 11) is "as of now" (the period is ignored); blocked time (report 12) is a delivery/flow
    // measure over DONE items, so the task's own domain (D3). Tasks and epics are different grains: the blocked-time
    // `itemKind` defaults to TASK.
    // Aging WIP's ages are fractional working days at `now`, so its validator moves every five minutes, not once a day.
    reportGet<ReportAgingWipRoute>(reportService, DomainView.TASK, StampClock.FIVE_MINUTES) { filter, nowMs ->
        reportService.agingWip(filter, nowMs)
    }
    reportGetWith<ReportBlockedTimeRoute, _>(
        reportService, DomainView.TASK,
        parseExtras = { it.optionalEnum<BlockedItemKind>("itemKind") ?: BlockedItemKind.TASK },
    ) { filter, itemKind, nowMs -> reportService.blockedTime(filter, itemKind, nowMs) }
}

/** Data quality, epic progress and the cost matrix (reports 14–16). */
private fun Route.qualityEpicAndCostRoutes(reportService: ReportService) {
    // Data quality (report 14): findings about tasks follow the task's own domain (D3), so TASK is the default view; the period
    // is the tasks' `done_at` and the worklogs' `started_at`, and open started tasks and open epics are listed whatever the period.
    // Its member-days are clipped at `now` (an open period), so — like Aging WIP — the validator moves every five minutes.
    reportGet<ReportDataQualityRoute>(reportService, DomainView.TASK, StampClock.FIVE_MINUTES) { filter, nowMs ->
        reportService.dataQuality(filter, nowMs)
    }
    // Epic progress / EVM (report 15) is PV/EV/AC-shaped, so it defaults to (and only accepts) the EPIC domain view (D3);
    // `epicId` — an epic's issue key — is its own parameter, outside the shared filter.
    reportGetWith<ReportEpicProgressRoute, _>(
        reportService, DomainView.EPIC, parseExtras = { it.epicIdOrNull() },
    ) { filter, epicId, nowMs ->
        reportService.epicProgress(filter, epicId, nowMs)
    }
    // The cost matrix (report 16) is a worklog-cost measure — PV/EV/AC-shaped, so it defaults to the EPIC domain view (D3); an
    // explicit `domainView=TASK` switches the columns to the task's own domain.
    reportGet<ReportCostMatrixRoute>(reportService, DomainView.EPIC) { filter, nowMs -> reportService.costMatrix(filter, nowMs) }
}

/** The `bucket` parameter of the period views (throughput, cycle time): `WEEK` unless given. */
private fun Parameters.throughputBucket(): ThroughputBucket = optionalEnum<ThroughputBucket>("bucket") ?: ThroughputBucket.WEEK

/** Epic progress' optional `epicId`; a present-but-blank one is a mistake, not "no epic" — it must not answer for the whole unit. */
private fun Parameters.epicIdOrNull(): String? {
    val epicId = optionalString("epicId")
    if (epicId == null && contains("epicId")) throw BadRequestException("epicId must not be blank")
    return epicId
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
        val nowMs = reportService.clock()
        val etag = reportService.etagFor(call, reportService.metricsSettings.read(), StampClock.DAY, nowMs)
        call.respondRevalidated(etag) { reportService.deepDive(request, nowMs) }
    }
    get<ReportDeepDiveSprintsRoute> {
        call.caller()
        val paging = call.parsePaging(DEEP_DIVE_SPRINT_SORT_FIELDS, listOf(SortField("id", descending = true)))
        val params = call.request.queryParameters
        val domain = params.optionalSingleLine("domain") ?: throw BadRequestException("domain is required")
        val connectionId = params.optionalUInt("connectionId")
        val q = params.optionalSingleLine("q")
        call.respondOptionList(reportService) { reportService.deepDiveSprints(domain, connectionId, q, paging) }
    }
    get<ReportDeepDiveEpicsRoute> {
        call.caller()
        val paging = call.parsePaging(DEEP_DIVE_EPIC_SORT_FIELDS, listOf(SortField("key", descending = false)))
        val params = call.request.queryParameters
        val domain = params.optionalSingleLine("domain")
        val connectionId = params.optionalUInt("connectionId")
        val q = params.optionalSingleLine("q")
        call.respondOptionList(reportService) { reportService.deepDiveEpics(domain, connectionId, q, paging) }
    }
    get<ReportDeepDiveEpicTasksRoute> { route ->
        call.caller()
        val paging = call.parsePaging(DEEP_DIVE_TASK_SORT_FIELDS, listOf(SortField("key", descending = false)))
        val params = call.request.queryParameters
        val connectionId = params.optionalUInt("connectionId")
        val q = params.optionalSingleLine("q")
        call.respondOptionList(reportService) { reportService.deepDiveEpicTasks(route.epicKey, connectionId, q, paging) }
    }
}

/** A deep-dive option list's answer through the cache validators — the parameters are all parsed already, the day clock is enough. */
private suspend inline fun <reified T : Any> ApplicationCall.respondOptionList(
    reportService: ReportService,
    produce: suspend () -> T,
) {
    val etag = reportService.etagFor(this, reportService.metricsSettings.read(), StampClock.DAY, reportService.clock())
    respondRevalidated(etag, produce)
}
