package ch.nokillswit.metrics

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.ingest.DataSourceServiceKey
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
@Resource("/api/v1/data-sources/{id}/metrics-config")
class DataSourceMetricsConfigRoute(val id: UInt) {
    @Serializable
    @Resource("options")
    class Options(val parent: DataSourceMetricsConfigRoute)
}

/**
 * The per-connection metrics configuration API (v0.3.0 M1 commit 4, `.claude/docs/domain-model.md`
 * "Configuration"): `GET/PUT /api/v1/data-sources/{id}/metrics-config` (ONE composite resource — a
 * PUT is a full replace, the features-PUT idiom) plus `GET .../metrics-config/options` (the
 * editor's reference data). ADMIN only, `requireAdmin` runs BEFORE the existence check, which itself
 * runs BEFORE the body decodes (the data-sources idiom — 403 wins over 404 wins over 400).
 */
fun Application.configureMetricsConfigRoutes() {
    // The worker role serves only the health/ready probes (plugins/Health.kt) — see Role.kt.
    if (!servesApi()) return

    val dataSources = attributes[DataSourceServiceKey]
    val metricsConfig = attributes[MetricsConfigServiceKey]
    val metricsSettings = attributes[MetricsSettingsServiceKey]
    val metricsConfigOptions = attributes[MetricsConfigOptionsKey]

    routing {
        authenticate {
            get<DataSourceMetricsConfigRoute> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                dataSources.read(route.id).orNotFound("Data source")
                call.respond(HttpStatusCode.OK, metricsConfig.effectiveConfig(route.id))
            }
            put<DataSourceMetricsConfigRoute> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                dataSources.read(route.id).orNotFound("Data source")
                val request = sanitizedDataSourceMetricsConfig(call.receive())
                val outcome = metricsConfig.replaceConfig(route.id, request)
                if (outcome.changed) {
                    audit(
                        "metrics_config.updated",
                        "byUserId" to caller.userId.toLong(),
                        "dataSourceId" to route.id.toLong(),
                        "configRevision" to metricsSettings.currentRevision(),
                    )
                }
                call.respond(HttpStatusCode.NoContent)
            }
            get<DataSourceMetricsConfigRoute.Options> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val connectionId = route.parent.id
                dataSources.read(connectionId).orNotFound("Data source")
                val workCategoryField = call.request.queryParameters.optionalString("workCategoryField")
                call.respond(HttpStatusCode.OK, metricsConfigOptions.options(connectionId, workCategoryField))
            }
        }
    }
}
