package ch.nokillswit.infra.outbound

import ch.nokillswit.audit.audit
import ch.nokillswit.ingest.ATLASSIAN_RESERVED_SITE_LABELS
import ch.nokillswit.ingest.JIRA_SITE_URL_PATTERN
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
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

/**
 * Safe audit fields only — never the full URL, which may carry a query string.
 *
 * [MED-1] Extends [java.net.UnknownHostException] — the checked exception [Dns.lookup]'s contract
 * actually declares — rather than a bare [RuntimeException]. OkHttp 5.5's async call path
 * (`RealCall$AsyncCall.run`, decompiled: `okhttp-jvm-5.5.0.jar`) only delivers an exception thrown
 * out of the interceptor chain to `Callback.onFailure` UNCHANGED when it IS-A [java.io.IOException];
 * anything else is caught by the generic `Throwable` branch, cancels the call and is RE-WRAPPED as
 * `IOException("canceled due to $e").initCause(e)` — the original exception survives only as a
 * suppressed cause. Ktor's `OkHttpCallback.onFailure` → `mapOkHttpException` never unwraps a cause
 * chain either. A plain-`RuntimeException` [BlockedHostException] therefore arrived at
 * `JiraHttp.request` as that wrapper `IOException`, missing `JiraHttp`'s
 * `catch (cause: BlockedHostException)` entirely and falling into the generic `catch (cause:
 * IOException)` retry branch instead — `BLOCKED_HOST` was retried [jira.maxRetries] times (~21s of
 * backoff, 5 `outbound.blocked` audits) before surfacing as `UPSTREAM_UNAVAILABLE`. As an
 * `IOException` subtype, [BlockedHostException] now reaches `Callback.onFailure` as itself (the
 * `try`'s FIRST, `IOException`-only catch clause, not the `Throwable` one), so it reaches
 * `JiraHttp`'s specific catch unchanged and is never retried. Pinned by
 * `OutboundGuardTest`'s production-wiring integration test.
 */
class BlockedHostException(val scheme: String?, val host: String?) : UnknownHostException("Blocked outbound host: $host")

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
    /**
     * [LOW-5] The scheme actually used to reach [hostname] — every genuine Jira Cloud host is
     * `https`, but the development `jira.stubBaseUrl` may be plain `http://` (the compose demo).
     * `Dns.lookup` only receives a hostname, never a scheme, so the caller (`jira/Jira.kt`) wires
     * this from the ONE place that knows both: `jira.stubBaseUrl`'s own scheme for the stub host,
     * `https` for everything else. Audited on every rejection so `outbound.blocked` never claims a
     * scheme that wasn't actually used.
     */
    private val schemeFor: (String) -> String = { "https" },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!allowedHost(hostname)) auditBlockedOutbound(schemeFor(hostname), hostname)
        val addresses = try {
            resolver(hostname)
        } catch (_: UnknownHostException) {
            auditBlockedOutbound(schemeFor(hostname), hostname)
        }
        if (addresses.isEmpty()) auditBlockedOutbound(schemeFor(hostname), hostname)
        if (!skipAddressCheck(hostname) && addresses.any { it.isBlockedAddress() }) {
            auditBlockedOutbound(schemeFor(hostname), hostname)
        }
        return addresses
    }
}

/**
 * [LOW-4] Ported from Toadie's `infra/fetch/UrlFetch.kt`: `Socket()` itself still consults the
 * JVM's SOCKS `ProxySelector` regardless of OkHttp's own `Proxy.NO_PROXY`, so a direct physical
 * socket factory closes that gap too. `createSocket(host, port)` overloads (which resolve their
 * OWN address, bypassing [GuardedDns] entirely) are refused outright — OkHttp only ever calls the
 * no-arg [createSocket] and connects the returned socket itself with an address [GuardedDns]
 * already approved.
 */
internal object DirectSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket(Proxy.NO_PROXY)
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()

    private fun unsupported(): Socket = throw SocketException("connected socket creation is disabled")
}

/**
 * The guarded OkHttp transport (v0.2.0 plan §6): [GuardedDns] above, `Proxy.NO_PROXY` (backstopped
 * by [DirectSocketFactory] — LOW-4), no redirects, no connection-failure retries (`JiraHttp` owns
 * the retry/backoff policy explicitly — a silent OkHttp-level retry would double-count against
 * it), `fastFallback(false)` (Toadie precedent: no parallel Happy-Eyeballs connection attempts
 * outside the single already-checked address list), no cookies/proxy-authenticator. [eventListener]
 * is a test seam (`OutboundGuardTest`'s connection-pool-leak test — LOW-3); production never sets
 * one.
 */
fun guardedOkHttpClient(
    allowedHost: (String) -> Boolean,
    resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    timeoutSeconds: Long,
    skipAddressCheck: (String) -> Boolean = { false },
    schemeFor: (String) -> String = { "https" },
    eventListener: EventListener = EventListener.NONE,
): OkHttpClient = OkHttpClient.Builder()
    .dns(GuardedDns(allowedHost, resolver, skipAddressCheck, schemeFor))
    .proxy(Proxy.NO_PROXY)
    .socketFactory(DirectSocketFactory)
    .fastFallback(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .cookieJar(CookieJar.NO_COOKIES)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .eventListener(eventListener)
    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
    .build()
