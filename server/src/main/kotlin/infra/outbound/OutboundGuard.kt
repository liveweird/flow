package ch.nokillswit.infra.outbound

import ch.nokillswit.audit.audit
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ch.nokillswit.outbound")

/**
 * The outbound HTTP boundary for the Jira client (v0.2.0 plan §3/§6, `.claude/docs/security.md`
 * "Outbound HTTP calls"): a host allow-list as the PRIMARY boundary — exactly `api.atlassian.com`,
 * a genuine `*.atlassian.net` tenant per the SAME shape `ingest/DataSource.kt` enforces at
 * create/update time (one source of truth — [JIRA_SITE_URL_PATTERN] /
 * [ATLASSIAN_RESERVED_SITE_LABELS]), or, in development mode only, the configured
 * `jira.stubBaseUrl` host — plus [isBlockedAddress], ported from Toadie's
 * `infra/fetch/UrlFetch.kt` (`isBlockedAddress` and its private helpers, with the additions noted on it), as defense in depth once
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
 * `SourceHttp.request` as that wrapper `IOException`, missing `SourceHttp`'s
 * `catch (cause: BlockedHostException)` entirely and falling into the generic `catch (cause:
 * IOException)` retry branch instead — `BLOCKED_HOST` was retried [jira.maxRetries] times (~30s of
 * backoff, 5 `outbound.blocked` audits) before surfacing as `UPSTREAM_UNAVAILABLE`. As an
 * `IOException` subtype, [BlockedHostException] now reaches `Callback.onFailure` as itself (the
 * `try`'s FIRST, `IOException`-only catch clause, not the `Throwable` one), so it reaches
 * `SourceHttp`'s specific catch unchanged and is never retried. Pinned by
 * `OutboundGuardTest`'s production-wiring integration test.
 *
 * Only GUARD rejections are a [BlockedHostException] (host not allow-listed, resolved address in a blocked range). A DNS
 * failure is a plain [java.net.UnknownHostException] (see [GuardedDns.lookup]) and IS retried.
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

/**
 * Ported from Toadie's `infra/fetch/UrlFetch.kt` (`isBlockedAddress`, lines 129-154), with two additions: an IPv4-mapped IPv6
 * address (`::ffff:a.b.c.d`) is unwrapped to its IPv4 form first ([withoutIpv4Mapping]) - an `Inet6Address` built from the raw 16
 * bytes is not folded by the JDK, and its `isLoopbackAddress`/`isSiteLocalAddress` would answer for the IPv6 form only - and the
 * IPv6 tunnelling prefixes 6to4 `2002::/16` and Teredo `2001:0::/32` are blocked ([isSixToFourOrTeredo]): both embed an arbitrary
 * IPv4 address a translator may deliver to, so the embedded address cannot be vouched for here.
 */
internal fun InetAddress.isBlockedAddress(): Boolean = withoutIpv4Mapping().isBlockedForm()

private fun InetAddress.isBlockedForm(): Boolean =
    isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress ||
        isMulticastAddress || isUniqueLocalIpv6() || isSpecialIpv4() || isNat64() || isSixToFourOrTeredo()

/** 6to4 `2002::/16` or Teredo `2001:0000::/32`. */
internal fun InetAddress.isSixToFourOrTeredo(): Boolean {
    val bytes = address
    if (bytes.size != IPV6_BYTES) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    return (b0 == 0x20 && b1 == 0x02) || (b0 == 0x20 && b1 == 0x01 && bytes[2] == 0.toByte() && bytes[3] == 0.toByte())
}

/** The IPv4 address inside an IPv4-mapped IPv6 address (`::ffff:0:0/96`), or this address unchanged. */
internal fun InetAddress.withoutIpv4Mapping(): InetAddress {
    val bytes = address
    if (bytes.size != IPV6_BYTES) return this
    val mapped = (0 until IPV4_MAPPED_ZERO_BYTES).all { bytes[it] == 0.toByte() } &&
        bytes[IPV4_MAPPED_ZERO_BYTES] == 0xFF.toByte() && bytes[IPV4_MAPPED_ZERO_BYTES + 1] == 0xFF.toByte()
    return if (mapped) InetAddress.getByAddress(bytes.copyOfRange(IPV6_BYTES - IPV4_BYTES, IPV6_BYTES)) else this
}

private const val IPV4_BYTES = 4
private const val IPV6_BYTES = 16
private const val IPV4_MAPPED_ZERO_BYTES = 10

/** The address rule of every client that is not a self-managed host: public addresses only ([isBlockedAddress]). */
val publicAddressesOnly: (String, InetAddress) -> Boolean = { _, address -> !address.isBlockedAddress() }

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

/** A scheme, host and port: what a request's URL is allowed to name. */
data class Origin(val scheme: String, val host: String, val port: Int)

private const val HTTPS_PORT = 443

/**
 * The per-REQUEST origin rule of the Jira client (the transport-level twin of [isAllowedJiraHost], which only sees a hostname in
 * DNS and never sees an IP-literal URL at all): `https` on port 443 to an allowed Jira host, or - [developmentMode] only - exactly
 * the configured [stub] origin (its own scheme and port).
 */
fun isAllowedJiraOrigin(scheme: String, host: String, port: Int, stub: Origin?, developmentMode: Boolean): Boolean {
    if (scheme == "https" && port == HTTPS_PORT && isAllowedJiraHost(host, null, developmentMode = false)) return true
    return developmentMode && stub != null && scheme == stub.scheme && port == stub.port && host.equals(stub.host, ignoreCase = true)
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
     * The address rule, asked of EVERY address a lookup resolved to (all-or-nothing: one refused address fails the whole
     * lookup, audited). The default is [publicAddressesOnly]. The development stub host (`jira.stubBaseUrl`,
     * `gitlab.stubBaseUrl`) answers `true` for its own host only: it is, by construction, a local compose/in-JVM fixture
     * (loopback or a docker-network private address). A self-managed GitLab host answers through
     * `SelfManagedHosts.addressAllowed` (`.claude/docs/security.md` "Outbound HTTP calls"). The allow-list check above
     * still applies unconditionally — this never widens which HOSTS are reachable.
     */
    private val addressAllowed: (String, InetAddress) -> Boolean = publicAddressesOnly,
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
        // A resolver failure (an UnknownHostException or an empty answer) is a DNS fault, not a guard rejection: the host
        // is allow-listed and no blocked address was involved, so it is neither audited `outbound.blocked` nor a
        // [BlockedHostException]. A plain UnknownHostException is an IOException, which `SourceHttp` retries with backoff
        // (and every retry comes back through this method, re-checked).
        val addresses = try {
            resolver(hostname)
        } catch (_: UnknownHostException) {
            throw dnsFailure(hostname)
        }
        if (addresses.isEmpty()) throw dnsFailure(hostname)
        if (addresses.any { !addressAllowed(hostname, it) }) auditBlockedOutbound(schemeFor(hostname), hostname)
        return addresses
    }

    /** Host ONLY in the message and the log, never a URL; the stack trace is deliberately not logged. */
    private fun dnsFailure(hostname: String): UnknownHostException {
        log.warn("DNS resolution failed for allow-listed outbound host {}", hostname)
        return UnknownHostException(hostname)
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
 * by [DirectSocketFactory] — LOW-4), no redirects, no connection-failure retries (`SourceHttp` owns
 * the retry/backoff policy explicitly — a silent OkHttp-level retry would double-count against
 * it), `fastFallback(false)` (Toadie precedent: no parallel Happy-Eyeballs connection attempts
 * outside the single already-checked address list), no cookies/proxy-authenticator. [eventListener]
 * is a test seam (`OutboundGuardTest`'s connection-pool-leak test — LOW-3); production never sets
 * one.
 *
 * [sslSocketFactory] and [trustManager] (both or neither) replace the JVM default trust: the GitLab client adds an operator
 * CA bundle ALONGSIDE the default CAs this way. There is no option to skip certificate or hostname verification.
 * [originAllowed] (scheme, host, port) is checked on every request before it is sent, so a `host:port` allow-list entry
 * means that port only (DNS sees a hostname, never a port) and an IP-literal URL, which OkHttp never hands to DNS, is judged too; a
 * refusal is audited `outbound.blocked` like a DNS one. With it set, a request that carries a caller-set `Host` header is refused
 * as well (it would name one host to the guard and another to the server).
 */
fun guardedOkHttpClient(
    allowedHost: (String) -> Boolean,
    resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    timeoutSeconds: Long,
    addressAllowed: (String, InetAddress) -> Boolean = publicAddressesOnly,
    schemeFor: (String) -> String = { "https" },
    eventListener: EventListener = EventListener.NONE,
    sslSocketFactory: SSLSocketFactory? = null,
    trustManager: X509TrustManager? = null,
    originAllowed: ((scheme: String, host: String, port: Int) -> Boolean)? = null,
): OkHttpClient = OkHttpClient.Builder()
    .dns(GuardedDns(allowedHost, resolver, addressAllowed, schemeFor))
    .apply {
        require((sslSocketFactory == null) == (trustManager == null)) { "sslSocketFactory and trustManager go together" }
        if (sslSocketFactory != null && trustManager != null) sslSocketFactory(sslSocketFactory, trustManager)
        if (originAllowed != null) {
            addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val url = request.url
                    if (request.header("Host") != null || !originAllowed(url.scheme, url.host, url.port)) {
                        auditBlockedOutbound(url.scheme, url.host)
                    }
                    chain.proceed(request)
                },
            )
        }
    }
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
