package ch.nokillswit.metrics

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.db.R2dbcDatabaseKey
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
 * The `metrics` package's composition root (v0.3.0 M1 commit 3): constructs and publishes
 * [MetricsConfigService] (the `metrics.settings` singleton) and [TeamMembershipService] (D1's
 * dated Jira-user membership), then — since the settings resource is a tiny ADMIN singleton with
 * no id of its own — registers its GET/PUT routes right here (the `plugins/Health.kt` shape: an
 * infra module that is also its own small route surface). `TeamMembershipService`'s and
 * `JiraUsersRoutes.kt`'s richer per-team/list surfaces get their own `configureXRoutes()`
 * modules instead. Registered in `application.yaml` after `configureJira` (needs nothing from it
 * yet, but the metrics layer as a whole reads `norm`, which `configureJira` publishes) and before
 * `configureIngestWorker` (which gains a `DERIVE` dispatch to this package from commit 7 on).
 */
fun Application.configureMetrics() {
    val database = attributes[R2dbcDatabaseKey]
    val metricsConfig = MetricsConfigService(database)
    attributes.put(MetricsConfigServiceKey, metricsConfig)
    attributes.put(TeamMembershipServiceKey, TeamMembershipService(database, metricsConfig))

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
