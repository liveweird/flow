package ch.nokillswit.jira

import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.outbound.guardedOkHttpClient
import ch.nokillswit.infra.outbound.isAllowedJiraHost
import ch.nokillswit.ingest.ConnectorRegistryKey
import ch.nokillswit.ingest.DataSourceKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import java.net.URI

val JiraConnectorKey = AttributeKey<JiraConnector>("JiraConnector")

/**
 * Wires the Jira HTTP stack (v0.2.0 plan §3/§6): ONE guarded `HttpClient(OkHttp)` shared by every
 * connection — the allow-list ([isAllowedJiraHost]) checks the GENERAL tenant-host shape
 * (`ingest/DataSource.kt`'s validator, one source of truth), not a single stored site, so a single
 * long-lived client safely serves every connection row. `jira.stubBaseUrl` reroutes BOTH the
 * tenant_info host and the gateway host to the Jira stub — development mode ONLY; production
 * refuses a non-blank value at boot (fail-closed, the same shape as `infra/crypto/Crypto.kt`'s and
 * `infra/mail/Mail.kt`'s checks). Registered in `application.yaml` before the data-sources routes.
 */
fun Application.configureJira() {
    val config = environment.config
    val stubBaseUrl = config.propertyOrNull("jira.stubBaseUrl")?.getString()?.trim().orEmpty().ifBlank { null }
    if (!developmentMode && stubBaseUrl != null) {
        error("Config \"jira.stubBaseUrl\" must be blank outside development mode (a stub must never be reachable in production)")
    }
    val requestTimeoutSeconds = requireConfigLong(config, "jira.requestTimeoutSeconds", MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    val maxResponseBytes = requireConfigLong(config, "jira.maxResponseBytes", MIN_RESPONSE_BYTES, MAX_RESPONSE_BYTES)
    val maxConcurrentRequests = requireConfigInt(config, "jira.maxConcurrentRequests", MIN_CONCURRENCY, MAX_CONCURRENCY)
    val maxRetries = requireConfigInt(config, "jira.maxRetries", MIN_RETRIES, MAX_RETRIES)
    val stubHost = stubBaseUrl?.let { URI(it).host }

    val okHttpClient = guardedOkHttpClient(
        allowedHost = { host -> isAllowedJiraHost(host, stubHost, developmentMode) },
        timeoutSeconds = requestTimeoutSeconds,
        // The stub is a local compose/in-JVM fixture BY DESIGN (see GuardedDns's kdoc) — its
        // address is expected to be loopback/private, unlike a genuine Jira Cloud host.
        skipAddressCheck = { host -> developmentMode && stubHost != null && host.equals(stubHost, ignoreCase = true) },
    )
    val httpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttpClient }
        expectSuccess = false
        // Ktor's client-side HttpRedirect plugin is separate from the OkHttp engine's own
        // followRedirects (already false in guardedOkHttpClient) — both must refuse a 3xx, or
        // checking-then-following would defeat the resolve-check-connect guard.
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = requestTimeoutSeconds * MILLIS_PER_SECOND
            connectTimeoutMillis = requestTimeoutSeconds * MILLIS_PER_SECOND
        }
    }
    val jiraHttp = JiraHttp(httpClient, maxRetries, maxResponseBytes, maxConcurrentRequests)
    val connector = JiraConnector { siteUrl, email, apiToken, authScheme ->
        val tenantInfoBaseUrl = stubBaseUrl ?: siteUrl
        HttpJiraClient(jiraHttp, tenantInfoBaseUrl, stubBaseUrl, email, apiToken, authScheme)
    }
    attributes.put(JiraConnectorKey, connector)
    // The Connector registry (ingest/Connector.kt) — IngestWorker's claim loop dispatches a
    // claimed job to its connector by DataSourceKind; a GitLab connector adds its own entry later.
    attributes.put(ConnectorRegistryKey, mapOf(DataSourceKind.JIRA_CLOUD to connector))
}

private const val MIN_TIMEOUT_SECONDS = 1L
private const val MAX_TIMEOUT_SECONDS = 120L
private const val MIN_RESPONSE_BYTES = 1_024L
private const val MAX_RESPONSE_BYTES = 128L * 1024 * 1024
private const val MIN_CONCURRENCY = 1
private const val MAX_CONCURRENCY = 64
private const val MIN_RETRIES = 0
private const val MAX_RETRIES = 10
private const val MILLIS_PER_SECOND = 1_000L
