package ch.nokillswit.gitlab

import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.outbound.SelfManagedHosts
import ch.nokillswit.infra.outbound.TlsTrust
import ch.nokillswit.infra.outbound.loadCaBundleTrust
import ch.nokillswit.infra.outbound.parseSelfManagedHosts
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
) {
    /** False means "GitLab disabled": no client is built and no data source of the GitLab kind can be created. */
    val enabled: Boolean get() = hosts.enabled || devStub != null
}

private fun ApplicationConfig.trimmed(key: String): String = propertyOrNull(key)?.getString()?.trim().orEmpty()

/**
 * Reads and validates `gitlab.*` (every refusal is a startup failure naming the key; no value is ever echoed that could be a
 * secret, and a stub URL is never echoed at all):
 *  - `allowedHosts`/`allowedCidrs`: [parseSelfManagedHosts];
 *  - `caBundle`: [loadCaBundleTrust], checked whenever set;
 *  - `stubBaseUrl`: development mode only, an origin with a host;
 *  - `requestTimeoutSeconds`, and `maxResponseBytes` — which may not exceed `jira.maxResponseBytes`, so the worker's boot-time
 *    heap fit (`jira/JiraHeapFit.kt`, which counts the Jira ceiling) already covers a GitLab response too.
 */
fun readGitLabSettings(config: ApplicationConfig, developmentMode: Boolean): GitLabSettings {
    val stubRaw = config.trimmed("gitlab.stubBaseUrl")
    if (!developmentMode && stubRaw.isNotEmpty()) {
        error("Config \"gitlab.stubBaseUrl\" must be blank outside development mode (a stub must never be reachable in production)")
    }
    val hosts = parseSelfManagedHosts(config.trimmed("gitlab.allowedHosts"), config.trimmed("gitlab.allowedCidrs"))
    val caBundle = config.trimmed("gitlab.caBundle")
    val tls = if (caBundle.isEmpty()) null else loadCaBundleTrust(caBundle)
    val timeout = requireConfigLong(config, "gitlab.requestTimeoutSeconds", MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
    val maxResponseBytes = requireConfigLong(config, "gitlab.maxResponseBytes", MIN_RESPONSE_BYTES, MAX_RESPONSE_BYTES)
    val jiraMaxResponseBytes = requireConfigLong(config, "jira.maxResponseBytes", MIN_RESPONSE_BYTES)
    if (maxResponseBytes > jiraMaxResponseBytes) {
        error("Config \"gitlab.maxResponseBytes\" must not exceed \"jira.maxResponseBytes\" (the worker's heap fit counts that ceiling)")
    }
    return GitLabSettings(hosts, stubRaw.takeIf { it.isNotEmpty() }?.let(::parseDevStub), tls, timeout, maxResponseBytes)
}

private fun parseDevStub(raw: String): GitLabDevStub {
    val uri = try {
        URI(raw)
    } catch (_: URISyntaxException) {
        null
    }
    val scheme = uri?.scheme?.lowercase()
    val host = uri?.host?.lowercase()
    val wellFormed = scheme in setOf("http", "https") && uri?.rawPath.orEmpty() in setOf("", "/")
    if (uri == null || scheme == null || host == null || !wellFormed) {
        error("Config \"gitlab.stubBaseUrl\" must be an http(s) origin with a host (no path)")
    }
    val port = if (uri.port != -1) uri.port else if (scheme == "https") DEFAULT_HTTPS_PORT else DEFAULT_HTTP_PORT
    return GitLabDevStub(scheme, host, port)
}

private const val DEFAULT_HTTPS_PORT = 443
private const val DEFAULT_HTTP_PORT = 80
private const val MIN_TIMEOUT_SECONDS = 1L
private const val MAX_TIMEOUT_SECONDS = 120L
private const val MIN_RESPONSE_BYTES = 1_024L
private const val MAX_RESPONSE_BYTES = 128L * 1024 * 1024
