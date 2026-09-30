package ch.nokillswit.metrics

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.ingest.SyncJobsServiceKey
import ch.nokillswit.ingest.jobHandlerRegistry
import ch.nokillswit.norm.WorkItemStoreKey
import ch.nokillswit.plugins.servesApi
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/metrics-settings")
class MetricsSettingsRoute

/**
 * The `metrics` package's composition root (v0.3.0 M1 commit 3, extended in commit 4): constructs
 * and publishes [MetricsSettingsService] (the `metrics.settings` singleton), [MetricsConfigService]
 * (as of commit 4, the per-connection `metrics-config` reads/PUT), [MetricsConfigOptions] (the
 * editor's `options` read — those two serve `MetricsConfigRoutes.kt`, hence the
 * `WorkItemStoreKey`/`DataSourceServiceKey` dependencies below), [DomainOwnerResolver] (the A19/A22
 * owner-team resolution both `MetricsConfigService` and `MetricsDeriver` use; checkup D3 split the
 * former all-in-one config service into these four) and [TeamMembershipService] (D1's
 * dated Jira-user membership), then — since the settings resource is a tiny ADMIN singleton with
 * no id of its own — registers its GET/PUT routes right here (the `plugins/Health.kt` shape: an
 * infra module that is also its own small route surface). `TeamMembershipService`'s,
 * `JiraUsersRoutes.kt`'s and `MetricsConfigRoutes.kt`'s richer per-resource surfaces get their own
 * `configureXRoutes()` modules instead. Registered in `application.yaml` after `configureJira`
 * (publishes `WorkItemStoreKey`, and the metrics layer as a whole reads `norm`) and before
 * `configureIngestWorker` (which dispatches DERIVE and the metrics PURGE steps to this package through
 * `ingest/JobHandlers.kt`'s registry — `registerMetricsHandlers`, `MetricsJobHandlers.kt` — so `ingest/` never
 * imports `metrics/`).
 */
fun Application.configureMetrics() {
    val database = attributes[R2dbcDatabaseKey]
    val workItemStore = attributes[WorkItemStoreKey]
    val dataSources = attributes[DataSourceServiceKey]
    val metricsSettings = MetricsSettingsService(database, dataSources, attributes[SyncJobsServiceKey])
    attributes.put(MetricsSettingsServiceKey, metricsSettings)
    val domainOwners = DomainOwnerResolver(database, workItemStore)
    val metricsConfig = MetricsConfigService(database, workItemStore, dataSources, metricsSettings, domainOwners)
    attributes.put(MetricsConfigServiceKey, metricsConfig)
    attributes.put(MetricsConfigOptionsKey, MetricsConfigOptions(database, workItemStore, dataSources))
    val teamMembership = TeamMembershipService(database, metricsSettings)
    attributes.put(TeamMembershipServiceKey, teamMembership)
    val metricsStore = MetricsStore(database)
    // `ingest.jobRetentionDays` is validated on EVERY boot regardless of role (the `configureIngestWorker`
    // idiom — a bad ingest.* setting is a deploy-time config error) — `derive_runs` prunes on the SAME
    // retention window `sync_jobs` itself uses (`.claude/docs/persistence.md`, review round 2b).
    val jobRetentionDays = requireConfigLong(environment.config, "ingest.jobRetentionDays", min = 1, max = 3650)
    // Registered regardless of role — `ingest/IngestWorker.kt` dispatches DERIVE and the metrics half of
    // PURGE through `ingest/JobHandlers.kt`'s registry under `runsWorker()`, which is independent of
    // `servesApi()` below (a WORKER-only instance never reaches the route-registration early return, but
    // still needs the handlers present). Harmless under `web`: the registry is only read by the worker.
    jobHandlerRegistry().registerMetricsHandlers(
        deriver = MetricsDeriver(
            workItemStore, metricsSettings, metricsConfig, domainOwners, teamMembership, metricsStore, database, jobRetentionDays,
        ),
        metricsConfig = metricsConfig,
        metricsSettings = metricsSettings,
        metricsStore = metricsStore,
    )

    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    routing {
        authenticate {
            get<MetricsSettingsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                call.respond(HttpStatusCode.OK, metricsSettings.read())
            }
            put<MetricsSettingsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedMetricsSettings(call.receive())
                validateMetricsSettings(request)
                val outcome = metricsSettings.replace(request, caller.userId)
                if (outcome.changed) {
                    audit(
                        "metrics_settings.updated",
                        "byUserId" to caller.userId.toLong(),
                        "configRevision" to outcome.response.configRevision,
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
