package ch.nokillswit.infra.outbound

import java.net.InetAddress
import java.net.UnknownHostException

/**
 * One operator-configured CIDR range (`gitlab.allowedCidrs`). Parsing is strict and never resolves a name: the address part
 * is matched against a literal-only grammar BEFORE it reaches the JDK, because `InetAddress.getByName` on anything else is a
 * DNS lookup. [network] holds the address bytes (4 or 16) with no host bits set ([parseCidr] refuses an entry that has any).
 */
internal class Cidr(private val network: ByteArray, private val prefix: Int) {
    /** True when [address] (already unwrapped from an IPv4-mapped form) is in this range; a different family never matches. */
    fun contains(address: InetAddress): Boolean = address.address.let { it.size == network.size && sharesPrefix(it, network, prefix) }

    /** True when the two ranges share any address (CIDR blocks nest or are disjoint, so comparing the shorter prefix decides). */
    fun overlaps(other: Cidr): Boolean =
        network.size == other.network.size && sharesPrefix(network, other.network, minOf(prefix, other.prefix))

    /** The first forbidden range ([FORBIDDEN_RANGES]) this range touches, described for an error message. */
    fun forbiddenRangeTouched(): String? = FORBIDDEN_RANGES.firstOrNull { (_, range) -> range.overlaps(this) }?.first

    companion object {
        /** Widest range allowed: IPv4 /8 (16,777,216 addresses), IPv6 /32. */
        const val WIDEST_IPV4_PREFIX = 8
        const val WIDEST_IPV6_PREFIX = 32

        /** The loopback, link-local (incl. cloud metadata), any-local and multicast ranges no operator CIDR may touch. */
        val FORBIDDEN_RANGES: List<Pair<String, Cidr>> = listOf(
            "loopback 127.0.0.0/8" to Cidr(ipv4(127, 0, 0, 0), 8),
            "link-local 169.254.0.0/16 (includes the cloud metadata address)" to Cidr(ipv4(169, 254, 0, 0), 16),
            "any-local 0.0.0.0/8" to Cidr(ipv4(0, 0, 0, 0), 8),
            "multicast 224.0.0.0/4" to Cidr(ipv4(224, 0, 0, 0), 4),
            "IPv6 loopback and any-local ::/96" to Cidr(ipv6(), 96),
            "IPv6 link-local fe80::/10" to Cidr(ipv6(0xfe80), 10),
            "IPv6 multicast ff00::/8" to Cidr(ipv6(0xff00), 8),
            "IPv4-mapped IPv6 ::ffff:0:0/96 (list the IPv4 range instead)" to Cidr(ipv6(0, 0, 0, 0, 0, 0xffff), 96),
            "NAT64 64:ff9b::/96 (NAT64 addresses are never admitted)" to Cidr(ipv6(0x64, 0xff9b), 96),
            "6to4 2002::/16 (tunnelling addresses are never admitted)" to Cidr(ipv6(0x2002), 16),
            "Teredo 2001:0::/32 (tunnelling addresses are never admitted)" to Cidr(ipv6(0x2001, 0), 32),
        )
    }
}

private const val IPV4_BYTES = 4
private const val IPV6_BYTES = 16
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF
private val IPV4_LITERAL = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")
private val IPV6_LITERAL_CHARS = Regex("""[0-9a-fA-F:.]+""")
private val PREFIX_LENGTH = Regex("""\d{1,3}""")

private fun ipv4(a: Int, b: Int, c: Int, d: Int): ByteArray = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

/** The leading hextets given, zero-filled to 16 bytes. */
private fun ipv6(vararg hextets: Int): ByteArray = ByteArray(IPV6_BYTES).also { bytes ->
    hextets.forEachIndexed { i, h ->
        bytes[i * 2] = (h shr BITS_PER_BYTE).toByte()
        bytes[i * 2 + 1] = h.toByte()
    }
}

private fun sharesPrefix(a: ByteArray, b: ByteArray, bits: Int): Boolean {
    val whole = bits / BITS_PER_BYTE
    for (i in 0 until whole) if (a[i] != b[i]) return false
    val rest = bits % BITS_PER_BYTE
    if (rest == 0) return true
    val mask = (BYTE_MASK shl (BITS_PER_BYTE - rest)) and BYTE_MASK
    return (a[whole].toInt() and mask) == (b[whole].toInt() and mask)
}

private fun hasHostBits(bytes: ByteArray, prefix: Int): Boolean = bytes.indices.any { i ->
    val networkBits = (prefix - i * BITS_PER_BYTE).coerceIn(0, BITS_PER_BYTE)
    (bytes[i].toInt() and (BYTE_MASK shr networkBits)) != 0
}

/** A literal IPv4 or IPv6 address as bytes, or null. Never performs a DNS lookup (see [Cidr]). */
private fun parseLiteral(text: String): ByteArray? {
    IPV4_LITERAL.matchEntire(text)?.let { match ->
        val octets = match.groupValues.drop(1).map { it.toInt() }
        return if (octets.all { it in 0..BYTE_MASK }) ByteArray(IPV4_BYTES) { octets[it].toByte() } else null
    }
    if (':' !in text || !IPV6_LITERAL_CHARS.matches(text)) return null
    return try {
        // A ':' in the text makes the JDK parse an IPv6 literal or throw; it does not consult DNS for such a string.
        InetAddress.getByName(text).address
    } catch (_: UnknownHostException) {
        null
    }
}

/**
 * Parses `address/length` into a [Cidr]. Refuses (IllegalArgumentException, the reason as its message; the caller adds the
 * entry): a malformed entry, host bits set, a range wider than IPv4 /8 or IPv6 /32 (so `/0` always), an IPv4-mapped IPv6
 * range, and any range overlapping [Cidr.FORBIDDEN_RANGES].
 */
internal fun parseCidr(entry: String): Cidr {
    val parts = entry.split('/')
    require(parts.size == 2 && PREFIX_LENGTH.matches(parts[1])) { "is not an address/length CIDR" }
    val bytes = requireNotNull(parseLiteral(parts[0])) { "does not start with an IPv4 or IPv6 address literal" }
    val written = ':' in parts[0]
    // The JDK folds an IPv4-mapped IPv6 literal into 4 bytes; a CIDR must say which family it means.
    require(!(written && bytes.size == IPV4_BYTES)) { "is an IPv4-mapped IPv6 range (write the IPv4 range instead)" }
    val prefix = parts[1].toInt()
    require(prefix <= bytes.size * BITS_PER_BYTE) { "has a prefix length longer than the address" }
    val widest = if (bytes.size == IPV4_BYTES) Cidr.WIDEST_IPV4_PREFIX else Cidr.WIDEST_IPV6_PREFIX
    require(prefix >= widest) { "is wider than /$widest, the widest range allowed for ${if (bytes.size == IPV4_BYTES) "IPv4" else "IPv6"}" }
    require(!hasHostBits(bytes, prefix)) { "has host bits set (write the network address of the range)" }
    val cidr = Cidr(bytes, prefix)
    cidr.forbiddenRangeTouched()?.let { throw IllegalArgumentException("overlaps the forbidden range $it") }
    return cidr
}
