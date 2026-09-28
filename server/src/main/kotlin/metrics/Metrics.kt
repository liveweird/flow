package ch.nokillswit.metrics

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.ingest.SyncJobsServiceKey
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
 * and publishes [MetricsConfigService] (the `metrics.settings` singleton AND, as of commit 4, the
 * per-connection `metrics-config`/`options` reads `MetricsConfigRoutes.kt` serves — hence the
 * `WorkItemStoreKey`/`DataSourceServiceKey` dependencies below) and [TeamMembershipService] (D1's
 * dated Jira-user membership), then — since the settings resource is a tiny ADMIN singleton with
 * no id of its own — registers its GET/PUT routes right here (the `plugins/Health.kt` shape: an
 * infra module that is also its own small route surface). `TeamMembershipService`'s,
 * `JiraUsersRoutes.kt`'s and `MetricsConfigRoutes.kt`'s richer per-resource surfaces get their own
 * `configureXRoutes()` modules instead. Registered in `application.yaml` after `configureJira`
 * (publishes `WorkItemStoreKey`, and the metrics layer as a whole reads `norm`) and before
 * `configureIngestWorker` (which gains a `DERIVE` dispatch to this package from commit 7 on).
 */
fun Application.configureMetrics() {
    val database = attributes[R2dbcDatabaseKey]
    val workItemStore = attributes[WorkItemStoreKey]
    val dataSources = attributes[DataSourceServiceKey]
    val metricsConfig = MetricsConfigService(database, workItemStore, dataSources, attributes[SyncJobsServiceKey])
    attributes.put(MetricsConfigServiceKey, metricsConfig)
    val teamMembership = TeamMembershipService(database, metricsConfig)
    attributes.put(TeamMembershipServiceKey, teamMembership)
    val metricsStore = MetricsStore(database)
    attributes.put(MetricsStoreKey, metricsStore)
    // Published regardless of role — `ingest/IngestWorker.kt`'s DERIVE dispatch reads it under
    // `runsWorker()`, which is independent of `servesApi()` below (a WORKER-only instance never
    // reaches the route-registration early return, but still needs this attribute present).
    attributes.put(MetricsDeriverKey, MetricsDeriver(workItemStore, metricsConfig, teamMembership, metricsStore, database))

    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    routing {
        authenticate {
            get<MetricsSettingsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                call.respond(HttpStatusCode.OK, metricsConfig.read())
            }
            put<MetricsSettingsRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedMetricsSettings(call.receive())
                validateMetricsSettings(request)
                val outcome = metricsConfig.replace(request, caller.userId)
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
