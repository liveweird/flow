package ch.nokillswit.telemetry

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.ingest.SyncJobsServiceKey
import ch.nokillswit.metrics.MetricsStoreKey
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/telemetry/current")
class TelemetryCurrentRoute

@Serializable
@Resource("/api/v1/telemetry/history")
class TelemetryHistoryRoute

/**
 * The telemetry read API (`.claude/docs/observability.md` "Telemetry"): `GET /api/v1/telemetry/current` and
 * `GET /api/v1/telemetry/history?group=&range=`, ADMIN only (operational data: process sizes, container limits, connection names and
 * their sync health), read-only, no audit event of their own (a denial is the generic `authz.denied`). `requireAdmin` runs before
 * the query is read, so a non-admin learns nothing about the parameters (403 outranks 400). The web and `all` roles serve them; a
 * worker answers 404 like every API route (`servesApi()`). The samples are written by the sampler of EVERY role
 * (`configureTelemetry`); this module only reads them, whether or not this process samples (`telemetry.enabled`).
 * Registered with the feature routes in `application.yaml`.
 */
fun Application.configureTelemetryRoutes() {
    if (!servesApi()) return

    val config = readTelemetryConfig(environment.config)
    val jobRetentionDays = requireConfigLong(environment.config, "ingest.jobRetentionDays", min = 1, max = MAX_RETENTION_DAYS)
    val jobRetentionMillis = jobRetentionDays * MILLIS_PER_DAY
    val store = TelemetryStore(attributes[R2dbcDatabaseKey], config.statementTimeoutSeconds)
    val reads = TelemetryReads(store, config.sampleIntervalSeconds)
    val dataSources = attributes[DataSourceServiceKey]
    val syncJobs = attributes[SyncJobsServiceKey]
    val metricsStore = attributes[MetricsStoreKey]
    val current = TelemetryCurrentReport(store, reads, dataSources, syncJobs, metricsStore, config.sampleIntervalSeconds)
    val history = TelemetryHistoryReport(store, reads, syncJobs, metricsStore, config.sampleIntervalSeconds, jobRetentionMillis)
    // Read per request: a test replaces the clock on `application {}` after this module has run (the IngestClockKey idiom).
    val clock: () -> Long = { attributes.getOrNull(TelemetryOverridesKey)?.clock?.invoke() ?: System.currentTimeMillis() }

    routing {
        authenticate {
            get<TelemetryCurrentRoute> {
                requireAdmin(call.caller())
                call.respond(HttpStatusCode.OK, current.build(clock()))
            }
            get<TelemetryHistoryRoute> {
                requireAdmin(call.caller())
                val group = call.request.queryParameters.optionalString("group")?.let(HistoryGroup::parse)
                    ?: throw BadRequestException("group is required: one of ${HistoryGroup.entries.joinToString("|") { it.param }}")
                val range = call.request.queryParameters.optionalString("range")?.let(HistoryRange::parse)
                    ?: throw BadRequestException("range is required: one of ${HistoryRange.entries.joinToString("|") { it.param }}")
                call.respond(HttpStatusCode.OK, history.build(group, range, clock()))
            }
        }
    }
}

private const val MAX_RETENTION_DAYS = 3650L
