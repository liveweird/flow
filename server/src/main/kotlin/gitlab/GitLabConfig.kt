package ch.nokillswit.gitlab

import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.outbound.SelfManagedHosts
import ch.nokillswit.infra.outbound.TlsTrust
import ch.nokillswit.infra.outbound.loadCaBundleTrust
import ch.nokillswit.infra.outbound.parseSelfManagedHosts
import ch.nokillswit.infra.time.MILLIS_PER_HOUR
import io.ktor.server.config.ApplicationConfig
import java.net.URI
import java.net.URISyntaxException

/**
 * The development-only GitLab stub origin (`gitlab.stubBaseUrl`): a local compose/in-JVM fixture on a loopback or docker-network
 * address, possibly plain `http`. It exists only when [readGitLabSettings] was told it is development mode; production refuses a
 * non-blank value at boot.
 */
class GitLabDevStub(val scheme: String, val host: String, val port: Int) {
    fun matchesHost(candidate: String): Boolean = candidate.equals(host, ignoreCase = true)

    /**
     * The stub's origin as the base-URL validator canonicalises it (`scheme://host`, the port only when it is not the scheme's
     * default).
     */
    fun origin(): String {
        val defaultPort = if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
        return if (port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
    }

    fun matchesOrigin(candidateScheme: String, candidateHost: String, candidatePort: Int): Boolean =
        candidateScheme == scheme && matchesHost(candidateHost) && candidatePort == port
}

/** The validated `gitlab.*` configuration. Everything here is operator-set at boot; nothing is editable at runtime. */
class GitLabSettings(
    val hosts: SelfManagedHosts,
    val devStub: GitLabDevStub?,
    /** The CA bundle's trust (JVM defaults + the bundle), or null when `gitlab.caBundle` is blank. */
    val tls: TlsTrust?,
    val requestTimeoutSeconds: Long,
    val maxResponseBytes: Long,
    /** Retries on 429/5xx/IOException before a call fails terminally (`SourceHttp`). */
    val maxRetries: Int,
    /** The total budget of one logical call across every attempt and backoff, in seconds. */
    val requestDeadlineSeconds: Long,
    /** How old (hours) the stored GitLab users may get before a SYNC fetches them again; 0 = every SYNC. */
    val usersRefreshHours: Int,
) {
    val requestDeadlineMillis: Long get() = requestDeadlineSeconds * MILLIS_PER_SECOND
    val usersRefreshMillis: Long get() = usersRefreshHours * MILLIS_PER_HOUR

    /** False means "GitLab disabled": no client is built and no data source of the GitLab kind can be created. */
    val enabled: Boolean get() = hosts.enabled || devStub != null
}

private fun ApplicationConfig.trimmed(key: String): String = propertyOrNull(key)?.getString()?.trim().orEmpty()

/**
 * Reads and validates `gitlab.*` (every refusal is a startup failure naming the key; no value is ever echoed that could be a
 * secret, and a stub URL is never echoed at all):
 *  - `allowedHosts`/`allowedCidrs`: [parseSelfManagedHosts];
 *  - `caBundle`: [loadCaBundleTrust], checked whenever set, and refused without `allowedHosts` (like the CIDRs: it is only ever
 *    for a listed host);
 *  - `stubBaseUrl`: development mode only, an origin with a host and nothing else;
 *  - `requestTimeoutSeconds`, `maxRetries`, `requestDeadlineSeconds`, `usersRefreshHours`: bounds-checked like their `jira.*` twins;
 *  - `maxResponseBytes`: blank = `min(8 MiB, jira.maxResponseBytes)`; an explicit value is bounds-checked and may not exceed
 *    `jira.maxResponseBytes` (refused even while GitLab is disabled). `jira/JiraHeapFit.kt` sizes the per-slot response tree from the
 *    Jira ceiling, so a GitLab ceiling at or below it keeps a GitLab response tree inside that figure. GitLab's calls draw on the
 *    SAME permit pool as Jira's (`fetch/OutboundPermits.kt`, published by `configureJira`), so the fit's in-flight term
 *    (`jira.maxConcurrentRequests x jira.maxResponseBytes`) already covers them and no GitLab term is added.
 */
fun readGitLabSettings(config: ApplicationConfig, developmentMode: Boolean): GitLabSettings {
    val stubRaw = config.trimmed("gitlab.stubBaseUrl")
    if (!developmentMode && stubRaw.isNotEmpty()) {
        error("Config \"gitlab.stubBaseUrl\" must be blank outside development mode (a stub must never be reachable in production)")
    }
    val hosts = parseSelfManagedHosts(
        config.trimmed("gitlab.allowedHosts"), config.trimmed("gitlab.allowedCidrs"),
        hostsKey = "gitlab.allowedHosts", cidrsKey = "gitlab.allowedCidrs",
    )
    val caBundle = config.trimmed("gitlab.caBundle")
    if (caBundle.isNotEmpty() && !hosts.enabled) {
        error("Config \"gitlab.caBundle\" is set but \"gitlab.allowedHosts\" is empty (a CA is only ever for a listed host)")
    }
    val tls = if (caBundle.isEmpty()) null else loadCaBundleTrust(caBundle, "gitlab.caBundle")
    val timeout = requireConfigLong(config, "gitlab.requestTimeoutSeconds", MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    val maxResponseBytes = readMaxResponseBytes(config)
    val maxRetries = requireConfigInt(config, "gitlab.maxRetries", MIN_RETRIES, MAX_RETRIES)
    val deadline = requireConfigLong(config, "gitlab.requestDeadlineSeconds", MIN_DEADLINE_SECONDS, MAX_DEADLINE_SECONDS)
    val usersRefreshHours = requireConfigInt(config, "gitlab.usersRefreshHours", MIN_USERS_REFRESH_HOURS, MAX_USERS_REFRESH_HOURS)
    return GitLabSettings(
        hosts, stubRaw.takeIf { it.isNotEmpty() }?.let(::parseDevStub), tls, timeout, maxResponseBytes, maxRetries, deadline,
            usersRefreshHours,
    )
}

private fun readMaxResponseBytes(config: ApplicationConfig): Long {
    val jiraMax = requireConfigLong(config, "jira.maxResponseBytes", MIN_RESPONSE_BYTES)
    if (config.trimmed("gitlab.maxResponseBytes").isEmpty()) return minOf(DEFAULT_RESPONSE_BYTES, jiraMax)
    val explicit = requireConfigLong(config, "gitlab.maxResponseBytes", MIN_RESPONSE_BYTES, MAX_RESPONSE_BYTES)
    if (explicit > jiraMax) {
        error("Config \"gitlab.maxResponseBytes\" ($explicit) must not exceed \"jira.maxResponseBytes\" ($jiraMax)")
    }
    return explicit
}

private fun parseDevStub(raw: String): GitLabDevStub {
    fun refuse(): Nothing =
        error("Config \"gitlab.stubBaseUrl\" must be an http(s) origin with a host (no path, query, fragment or credentials)")
    val uri = try {
        URI(raw)
    } catch (_: URISyntaxException) {
        refuse()
    }
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: refuse()
    val host = uri.host?.lowercase() ?: refuse()
    val extras = uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
    if (extras || uri.rawPath.orEmpty() !in setOf("", "/")) refuse()
    val port = if (uri.port != -1) uri.port else if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
    return GitLabDevStub(scheme, host, port)
}

private const val DEFAULT_HTTPS_PORT = 443
private const val DEFAULT_HTTP_PORT = 80
private const val MIN_TIMEOUT_SECONDS = 1L
private const val MAX_TIMEOUT_SECONDS = 120L
private const val MIN_RETRIES = 0
private const val MAX_RETRIES = 10
private const val MIN_DEADLINE_SECONDS = 1L
private const val MAX_DEADLINE_SECONDS = 1_800L
private const val MIN_USERS_REFRESH_HOURS = 0
private const val MAX_USERS_REFRESH_HOURS = 168
private const val MILLIS_PER_SECOND = 1_000L
private const val MIN_RESPONSE_BYTES = 1_024L
private const val MAX_RESPONSE_BYTES = 128L * 1024 * 1024
private const val DEFAULT_RESPONSE_BYTES = 8L * 1024 * 1024
