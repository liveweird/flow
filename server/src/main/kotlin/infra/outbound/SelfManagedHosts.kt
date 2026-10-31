package ch.nokillswit.infra.outbound

import java.net.Inet4Address
import java.net.InetAddress

/**
 * The operator-only allowance for a self-managed host that lives on a private address (`.claude/docs/security.md` "Outbound HTTP
 * calls", `.claude/docs/gitlab-integration.md`): `gitlab.allowedHosts` (exact DNS names, optionally `host:port`) and
 * `gitlab.allowedCidrs` (the private ranges those hosts may resolve to). Nothing a user or an admin types can widen either list.
 *
 * What the rule is, in one place ([addressAllowed]):
 *  - an address that is NOT blocked today ([isBlockedAddress]) is allowed for every host, listed or not;
 *  - loopback, link-local (incl. the cloud metadata address), any-local, multicast and `::/96` are refused for every host,
 *    whatever the CIDRs say (and the CIDRs may not overlap them: [parseSelfManagedHosts] refuses at boot);
 *  - any other blocked address (private, unique-local, CGNAT, ...) is allowed only for a LISTED host and only inside a CIDR.
 *
 * An IPv4-mapped IPv6 address is unwrapped to IPv4 before any matching, so `::ffff:10.0.0.5` is judged as `10.0.0.5`.
 */
class SelfManagedHosts private constructor(
    private val entries: Set<HostEntry>,
    private val cidrs: List<Cidr>,
) {
    /** False when no host is listed: the feature is off (a development stub aside). */
    val enabled: Boolean get() = entries.isNotEmpty()

    /** DNS-level check: Dns.lookup sees a hostname, never a port, so any entry for [host] admits the name. */
    fun hostListed(host: String): Boolean = entries.any { it.host == host.lowercase() }

    /** Request-level check: [host] AND [port] as an entry means them (no port in the entry = 443), over `https` only. */
    fun originListed(scheme: String, host: String, port: Int): Boolean =
        scheme == "https" && HostEntry(host.lowercase(), port) in entries

    fun addressAllowed(host: String, address: InetAddress): Boolean {
        val unwrapped = address.withoutIpv4Mapping()
        if (unwrapped.isNeverAllowed()) return false
        if (!unwrapped.isBlockedAddress()) return true
        return hostListed(host) && cidrs.any { it.contains(unwrapped) }
    }

    internal data class HostEntry(val host: String, val port: Int)

    companion object {
        /** No host listed, no CIDR: the self-managed allowance is off. */
        val NONE = SelfManagedHosts(emptySet(), emptyList())

        internal fun of(entries: Set<HostEntry>, cidrs: List<Cidr>) = SelfManagedHosts(entries, cidrs)
    }
}

private const val DEFAULT_HTTPS_PORT = 443
private const val MAX_PORT = 65_535
private const val MAX_HOST_LENGTH = 253
private const val MAX_LABEL_LENGTH = 63
private const val MAX_ECHOED_ENTRY = 100
private val DNS_LABEL = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")
private val HEX_OR_DECIMAL_PART = Regex("(0x[0-9a-f]+|[0-9]+)")
private val SAFE_TO_ECHO = Regex("[A-Za-z0-9.:*/_\\[\\]-]+")
private val PORT_DIGITS = Regex("[0-9]{1,5}")

/**
 * Always refused, for every host: loopback, link-local, any-local, multicast, the whole of `0.0.0.0/8` ("this network", of which
 * the JDK flags only `0.0.0.0`) and the deprecated IPv6 `::/96` family (`::`, `::1`, IPv4-compatible `::a.b.c.d`).
 */
private fun InetAddress.isNeverAllowed(): Boolean {
    if (isLoopbackAddress || isLinkLocalAddress || isAnyLocalAddress || isMulticastAddress) return true
    return if (this is Inet4Address) address[0] == 0.toByte() else address.take(IPV6_ZERO_PREFIX_BYTES).all { it == 0.toByte() }
}

private const val IPV6_ZERO_PREFIX_BYTES = 12

/**
 * An entry for an error message: echoed when it is plainly a host or CIDR, otherwise only its position. An entry that carries
 * `user:secret@host` or a `?token=` must never reach a log through a startup error.
 */
private fun describe(index: Int, entry: String): String =
    if (entry.length <= MAX_ECHOED_ENTRY && SAFE_TO_ECHO.matches(entry)) "entry ${index + 1} \"$entry\"" else "entry ${index + 1}"

private fun splitList(raw: String, key: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return raw.split(',').mapIndexed { index, part ->
        part.trim().also { if (it.isEmpty()) error("Config \"$key\": entry ${index + 1} is empty (a stray comma?)") }
    }
}

private fun parseHostEntry(entry: String, index: Int): SelfManagedHosts.HostEntry {
    fun refuse(why: String): Nothing = error("Config \"gitlab.allowedHosts\": ${describe(index, entry)} $why")
    if (entry.contains("://")) refuse("must be a host name, not a URL (no scheme)")
    if ('@' in entry) refuse("must not carry credentials")
    if ('/' in entry || '?' in entry || '#' in entry) refuse("must be host or host:port only (no path, query or fragment)")
    if ('*' in entry) refuse("must not use a wildcard")
    if ('[' in entry || ']' in entry || entry.count { it == ':' } > 1) refuse("must not be an IP literal (list the DNS name)")
    val host = entry.substringBefore(':').lowercase()
    val port = if (':' in entry) {
        val digits = entry.substringAfter(':')
        if (!PORT_DIGITS.matches(digits) || digits.toInt() !in 1..MAX_PORT) refuse("has a port outside 1..65535")
        digits.toInt()
    } else {
        DEFAULT_HTTPS_PORT
    }
    if (!isDnsName(host)) refuse("must be a DNS host name (letters, digits and hyphens in dot-separated labels, never an IP literal)")
    return SelfManagedHosts.HostEntry(host, port)
}

/**
 * A syntactically valid DNS name that cannot be read as an IP literal: labels of 1..63 letters/digits/hyphens (no leading or
 * trailing hyphen), 253 characters at most, no trailing dot, and not made only of decimal or `0x` hex parts (the JDK reads
 * `2130706433` and similar as an IPv4 address).
 */
private fun isDnsName(host: String): Boolean {
    if (host.isEmpty() || host.length > MAX_HOST_LENGTH) return false
    val labels = host.split('.')
    if (labels.any { it.length > MAX_LABEL_LENGTH || !DNS_LABEL.matches(it) }) return false
    return !labels.all { HEX_OR_DECIMAL_PART.matches(it) }
}

/**
 * Parses and validates `gitlab.allowedHosts` and `gitlab.allowedCidrs` (the raw comma-separated strings). Throws
 * [IllegalStateException] (startup refuses) naming the key and the offending entry when anything is malformed.
 */
fun parseSelfManagedHosts(hostsRaw: String, cidrsRaw: String): SelfManagedHosts {
    val hostEntries = splitList(hostsRaw, "gitlab.allowedHosts").mapIndexed { i, e -> parseHostEntry(e, i) }.toSet()
    val cidrs = splitList(cidrsRaw, "gitlab.allowedCidrs").mapIndexed { index, entry ->
        try {
            parseCidr(entry)
        } catch (e: IllegalArgumentException) {
            error("Config \"gitlab.allowedCidrs\": ${describe(index, entry)} ${e.message}")
        }
    }
    check(cidrs.isEmpty() || hostEntries.isNotEmpty()) {
        "Config \"gitlab.allowedCidrs\" is set but \"gitlab.allowedHosts\" is empty (a range is only ever for a listed host)"
    }
    return if (hostEntries.isEmpty()) SelfManagedHosts.NONE else SelfManagedHosts.of(hostEntries, cidrs)
}
