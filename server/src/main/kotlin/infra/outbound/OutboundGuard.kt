package ch.nokillswit.infra.outbound

import ch.nokillswit.audit.audit
import ch.nokillswit.ingest.ATLASSIAN_RESERVED_SITE_LABELS
import ch.nokillswit.ingest.JIRA_SITE_URL_PATTERN
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * The outbound HTTP boundary for the Jira client (v0.2.0 plan §3/§6, `.claude/docs/security.md`
 * "Outbound HTTP calls"): a host allow-list as the PRIMARY boundary — exactly `api.atlassian.com`,
 * a genuine `*.atlassian.net` tenant per the SAME shape `ingest/DataSource.kt` enforces at
 * create/update time (one source of truth — [JIRA_SITE_URL_PATTERN] /
 * [ATLASSIAN_RESERVED_SITE_LABELS]), or, in development mode only, the configured
 * `jira.stubBaseUrl` host — plus [isBlockedAddress], ported VERBATIM from Toadie's
 * `infra/fetch/UrlFetch.kt` (`isBlockedAddress` and its private helpers), as defense in depth once
 * DNS actually resolves a host. A Jira Cloud API host is always public, so the address check is a
 * backstop; the allow-list is the boundary that actually matters. Every rejection is audited as
 * `outbound.blocked` with scheme + host ONLY — never a full URL, which may embed a query string.
 */
const val ATLASSIAN_GATEWAY_HOST = "api.atlassian.com"

/** Safe audit fields only — never the full URL, which may carry a query string. */
class BlockedHostException(val scheme: String?, val host: String?) : RuntimeException("Blocked outbound host: $host")

/** True for a syntactically valid, non-reserved `*.atlassian.net` tenant host (no scheme/path here — host only). */
fun isAllowedTenantHost(host: String): Boolean {
    val match = JIRA_SITE_URL_PATTERN.matchEntire("https://$host") ?: return false
    return match.groupValues[1] !in ATLASSIAN_RESERVED_SITE_LABELS
}

/**
 * The allow-list boundary. Checked against the GENERAL tenant-host shape, not one specific
 * connection's site — a single guarded client is shared across every connection (`jira/Jira.kt`).
 * [stubHost] is honoured ONLY when [developmentMode] is true; production must refuse a non-blank
 * `jira.stubBaseUrl` at boot (see `jira/Jira.kt`'s fail-closed check) — this function does not by
 * itself enforce that, it only decides whether a given resolved hostname may be dialed.
 */
fun isAllowedJiraHost(host: String, stubHost: String?, developmentMode: Boolean): Boolean {
    val normalized = host.lowercase()
    if (normalized == ATLASSIAN_GATEWAY_HOST) return true
    if (isAllowedTenantHost(normalized)) return true
    return developmentMode && !stubHost.isNullOrBlank() && normalized == stubHost.lowercase()
}

/** Ported VERBATIM from Toadie's `infra/fetch/UrlFetch.kt` (`isBlockedAddress`, lines 129-154). */
internal fun InetAddress.isBlockedAddress(): Boolean =
    isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress ||
        isMulticastAddress || isUniqueLocalIpv6() || isSpecialIpv4() || isNat64()

private fun InetAddress.isUniqueLocalIpv6(): Boolean {
    val bytes = address
    return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
}

private fun InetAddress.isSpecialIpv4(): Boolean {
    val bytes = address
    if (bytes.size != 4) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    return (b0 == 100 && b1 in 64..127) ||
        (b0 == 192 && b1 == 0 && (bytes[2].toInt() and 0xFF) == 0) ||
        (b0 == 198 && (b1 == 18 || b1 == 19))
}

private fun InetAddress.isNat64(): Boolean {
    val bytes = address
    if (bytes.size != 16) return false
    val prefix = byteArrayOf(0x00, 0x64, 0xFF.toByte(), 0x9B.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
    return bytes.copyOfRange(0, 12).contentEquals(prefix) &&
        InetAddress.getByAddress(bytes.copyOfRange(12, 16)).isBlockedAddress()
}

/** Audits `outbound.blocked` (scheme + host ONLY) and throws — never called with a full URL. */
fun auditBlockedOutbound(scheme: String, host: String?): Nothing {
    audit("outbound.blocked", "scheme" to scheme, "host" to host)
    throw BlockedHostException(scheme, host)
}

/**
 * The one approved DNS snapshot is also the only address set OkHttp may connect to. Ported from
 * Toadie's per-request pinned [Dns], adapted for a long-lived client shared across many calls:
 * every [lookup] re-checks the host allow-list and re-resolves + re-checks addresses fresh, so
 * each connection attempt (including a retry) independently closes the resolve-check-connect gap.
 */
class GuardedDns(
    private val allowedHost: (String) -> Boolean,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    /**
     * True ONLY for the development `jira.stubBaseUrl` host: it is, by construction, a local
     * compose/in-JVM fixture (loopback or a docker-network private address), so the address-range
     * check below would otherwise block the one host [allowedHost] deliberately admits in
     * development. The allow-list check above still applies unconditionally — this never widens
     * which HOSTS are reachable, only skips the address-shape check for that one already-approved
     * host.
     */
    private val skipAddressCheck: (String) -> Boolean = { false },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!allowedHost(hostname)) auditBlockedOutbound("https", hostname)
        val addresses = try {
            resolver(hostname)
        } catch (_: UnknownHostException) {
            auditBlockedOutbound("https", hostname)
        }
        if (addresses.isEmpty()) auditBlockedOutbound("https", hostname)
        if (!skipAddressCheck(hostname) && addresses.any { it.isBlockedAddress() }) auditBlockedOutbound("https", hostname)
        return addresses
    }
}

/**
 * The guarded OkHttp transport (v0.2.0 plan §6): [GuardedDns] above, `Proxy.NO_PROXY`, no
 * redirects, no connection-failure retries (`JiraHttp` owns the retry/backoff policy explicitly —
 * a silent OkHttp-level retry would double-count against it), no cookies/proxy-authenticator.
 */
fun guardedOkHttpClient(
    allowedHost: (String) -> Boolean,
    resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    timeoutSeconds: Long,
    skipAddressCheck: (String) -> Boolean = { false },
): OkHttpClient = OkHttpClient.Builder()
    .dns(GuardedDns(allowedHost, resolver, skipAddressCheck))
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .build()
