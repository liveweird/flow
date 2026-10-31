package ch.nokillswit.jira

import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.outbound.Origin
import ch.nokillswit.infra.outbound.guardedKtorClient
import ch.nokillswit.infra.outbound.guardedOkHttpClient
import ch.nokillswit.infra.outbound.isAllowedJiraHost
import ch.nokillswit.infra.outbound.isAllowedJiraOrigin
import ch.nokillswit.infra.outbound.publicAddressesOnly
import ch.nokillswit.infra.time.MILLIS_PER_HOUR
import ch.nokillswit.ingest.ConnectorRegistryKey
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.DataSourceServiceKey
import ch.nokillswit.ingest.SyncCursorsServiceKey
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.WorkItemStoreKey
import io.ktor.client.HttpClient
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import java.net.InetAddress
import java.net.URI
import okhttp3.EventListener

val JiraConnectorKey = AttributeKey<JiraConnector>("JiraConnector")

/** The shared guarded client — published so a test can assert it is closed on [ApplicationStopped]. */
val JiraHttpClientKey = AttributeKey<HttpClient>("JiraHttpClient")

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
    val requestDeadlineSeconds = requireConfigLong(config, "jira.requestDeadlineSeconds", MIN_DEADLINE_SECONDS, MAX_DEADLINE_SECONDS)
    val maxResponseBytes = requireConfigLong(config, "jira.maxResponseBytes", MIN_RESPONSE_BYTES, MAX_RESPONSE_BYTES)
    val maxConcurrentRequests = requireConfigInt(config, "jira.maxConcurrentRequests", MIN_CONCURRENCY, MAX_CONCURRENCY)
    val maxRetries = requireConfigInt(config, "jira.maxRetries", MIN_RETRIES, MAX_RETRIES)
    val incrementalOverlapMinutes = requireConfigLong(config, "jira.incrementalOverlapMinutes", MIN_OVERLAP_MINUTES, MAX_OVERLAP_MINUTES)
    val issuesPageSize = requireConfigInt(config, "jira.pageSize", MIN_PAGE_SIZE, MAX_PAGE_SIZE)
    val changelogBulkSize = requireConfigInt(config, "jira.changelogBulkSize", MIN_CHANGELOG_BULK_SIZE, MAX_CHANGELOG_BULK_SIZE)
    val changelogPageSize = requireConfigInt(config, "jira.changelogPageSize", MIN_CHANGELOG_PAGE_SIZE, MAX_CHANGELOG_PAGE_SIZE)
    val usersRefreshHours = requireConfigInt(config, "jira.usersRefreshHours", MIN_USERS_REFRESH_HOURS, MAX_USERS_REFRESH_HOURS)
    // The stored-size ceilings (JiraStoredCeilings.kt): what the raw writers refuse past, and what a PROCESS page may hold.
    val stored = JiraStoredCeilings(
        maxPayloadBytes = requireConfigLong(
            config, "jira.maxStoredPayloadBytes", MIN_MAX_STORED_PAYLOAD_BYTES, MAX_MAX_STORED_PAYLOAD_BYTES,
        ),
        processPageBytes = requireConfigLong(config, "jira.processPageBytes", MIN_PROCESS_PAGE_BYTES, MAX_PROCESS_PAGE_BYTES),
    )
    requireProcessPageHoldsAnIssue(stored)
    // Fail closed (worker roles) before anything is built: the ceilings x the slots must fit the heap (JiraHeapFit.kt).
    requireHeapFitsJiraCeilings(config, maxResponseBytes, maxConcurrentRequests, stored)
    // The web-side Jira consumers (the raw issue inspector) draw from ONE share equal to the single response tree the fit above counts
    // for the web role, so what they hold is inside that figure (JiraHeapShare.kt).
    attributes.put(JiraHeapShareKey, JiraHeapShare(jiraHeapShareCapacityBytes(maxResponseBytes)))
    val stubHost = stubBaseUrl?.let { URI(it).host }
    val stubScheme = stubBaseUrl?.let { URI(it).scheme }
    val stubOrigin = stubBaseUrl?.let { raw ->
        val uri = URI(raw)
        Origin(uri.scheme, uri.host, if (uri.port != -1) uri.port else if (uri.scheme == "https") HTTPS_PORT else HTTP_PORT)
    }

    val httpClient = buildGuardedJiraHttpClient(
        allowedHost = { host -> isAllowedJiraHost(host, stubHost, developmentMode) },
        // The stub is a local compose/in-JVM fixture BY DESIGN (see GuardedDns's kdoc) — its
        // address is expected to be loopback/private, unlike a genuine Jira Cloud host. Every other host: public addresses only.
        addressAllowed = { host, address ->
            (developmentMode && stubHost != null && host.equals(stubHost, ignoreCase = true)) || publicAddressesOnly(host, address)
        },
        // [LOW-5] The stub may be plain http:// (the compose demo) — audit its real scheme rather
        // than the https constant that's correct for every genuine Jira Cloud host.
        schemeFor = { host ->
            if (developmentMode && stubHost != null && host.equals(stubHost, ignoreCase = true)) stubScheme ?: "https" else "https"
        },
        // The transport-level rule (JiraOriginGuardTest pins this wiring): https:443 to an allowed Jira host, or the dev stub's exact
        // origin. It also refuses an IP-literal URL, which OkHttp never hands to the DNS guard.
        originAllowed = { scheme, host, port -> isAllowedJiraOrigin(scheme, host, port, stubOrigin, developmentMode) },
        requestTimeoutSeconds = requestTimeoutSeconds,
    )
    // ONE long-lived client for the app's whole life: closing it on ApplicationStopped releases the OkHttp
    // dispatcher/connection-pool threads (a stopped Application otherwise leaks them — the test suite
    // starts and stops hundreds), like the database pool's own ApplicationStopped disposal.
    attributes.put(JiraHttpClientKey, httpClient)
    monitor.subscribe(ApplicationStopped) { httpClient.close() }
    val jiraHttp = createJiraHttp(
        httpClient,
        maxRetries,
        maxResponseBytes,
        maxConcurrentRequests,
        requestDeadlineMillis = requestDeadlineSeconds * MILLIS_PER_SECOND,
    )
    // JiraRawStore (V10) lives next to the feature it serves rather than in infra/db/Database.kt's
    // generic composition root — it only needs the R2dbcDatabase configureDatabase already
    // published (module order: Database before this — application.yaml).
    val rawStore = JiraRawStore(attributes[R2dbcDatabaseKey], stored)
    attributes.put(JiraRawStoreKey, rawStore)
    // WorkItemStore (V13) — the normalized layer's write target (plan §0 A3/§8), the PROCESS
    // stream's/PURGE's one consumer; same composition-root shape as rawStore above.
    val workItemStore = WorkItemStore(attributes[R2dbcDatabaseKey])
    attributes.put(WorkItemStoreKey, workItemStore)
    val connector = JiraConnector(
        newClient = { siteUrl, email, apiToken, authScheme ->
            val tenantInfoBaseUrl = stubBaseUrl ?: siteUrl
            HttpJiraClient(jiraHttp, tenantInfoBaseUrl, stubBaseUrl, email, apiToken, authScheme)
        },
        sync = JiraSyncDependencies(
            dataSources = attributes[DataSourceServiceKey],
            rawStore = rawStore,
            cursors = attributes[SyncCursorsServiceKey],
            database = attributes[R2dbcDatabaseKey],
            workItems = workItemStore,
            incrementalOverlapMinutes = incrementalOverlapMinutes,
            issuesPageSize = issuesPageSize,
            changelogBulkSize = changelogBulkSize,
            changelogPageSize = changelogPageSize,
            usersRefreshMillis = usersRefreshHours * MILLIS_PER_HOUR,
        ),
    )
    attributes.put(JiraConnectorKey, connector)
    // The Connector registry (ingest/Connector.kt) — IngestWorker's claim loop dispatches a
    // claimed job to its connector by DataSourceKind; a GitLab connector adds its own entry later.
    attributes.put(ConnectorRegistryKey, mapOf(DataSourceKind.JIRA_CLOUD to connector))
}

/**
 * [MED-3] Builds EXACTLY the guarded Ktor `HttpClient(OkHttp)` [configureJira] wires into
 * production — factored out so a test can build the SAME client (not a hand-rolled approximation
 * that could silently drift if the guard were ever unwired) and drive it against a real loopback
 * WireMock fixture (`OutboundGuardTest`'s production-wiring integration test). [originAllowed] is null (no per-request origin
 * check) only for those loopback tests; [configureJira] always passes the real rule.
 */
fun buildGuardedJiraHttpClient(
    allowedHost: (String) -> Boolean,
    resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    addressAllowed: (String, InetAddress) -> Boolean = publicAddressesOnly,
    schemeFor: (String) -> String = { "https" },
    eventListener: EventListener = EventListener.NONE,
    originAllowed: ((scheme: String, host: String, port: Int) -> Boolean)? = null,
    requestTimeoutSeconds: Long,
): HttpClient {
    val okHttpClient = guardedOkHttpClient(
        allowedHost = allowedHost,
        resolver = resolver,
        timeoutSeconds = requestTimeoutSeconds,
        addressAllowed = addressAllowed,
        schemeFor = schemeFor,
        eventListener = eventListener,
        originAllowed = originAllowed,
    )
    return guardedKtorClient(okHttpClient, requestTimeoutSeconds)
}

private const val MIN_TIMEOUT_SECONDS = 1L
private const val MAX_TIMEOUT_SECONDS = 120L
private const val MIN_DEADLINE_SECONDS = 1L
private const val MAX_DEADLINE_SECONDS = 1_800L
private const val MIN_RESPONSE_BYTES = 1_024L
private const val MAX_RESPONSE_BYTES = 128L * 1024 * 1024
private const val MIN_OVERLAP_MINUTES = 0L
private const val MAX_OVERLAP_MINUTES = 1_440L
private const val MIN_PAGE_SIZE = 1
private const val MAX_PAGE_SIZE = 500
private const val MIN_CHANGELOG_BULK_SIZE = 1
private const val MAX_CHANGELOG_BULK_SIZE = 1_000
private const val MIN_CHANGELOG_PAGE_SIZE = 1
// Jira's documented `changelog/bulkfetch` `maxResults` ceiling (the v3 OpenAPI spec's BulkChangelogRequestBean).
private const val MAX_CHANGELOG_PAGE_SIZE = 10_000
private const val MIN_USERS_REFRESH_HOURS = 0
private const val MAX_USERS_REFRESH_HOURS = 168
private const val MIN_CONCURRENCY = 1
private const val MAX_CONCURRENCY = 64
private const val MIN_RETRIES = 0
private const val MAX_RETRIES = 10
private const val MILLIS_PER_SECOND = 1_000L
private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80
