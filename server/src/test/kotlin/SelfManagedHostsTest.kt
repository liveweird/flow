package ch.nokillswit

import ch.nokillswit.infra.outbound.SelfManagedHosts
import ch.nokillswit.infra.outbound.parseSelfManagedHosts
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The production key names, spelled once for the tests. */
internal fun parseGitLabHosts(hostsRaw: String, cidrsRaw: String): SelfManagedHosts =
    parseSelfManagedHosts(hostsRaw, cidrsRaw, hostsKey = "gitlab.allowedHosts", cidrsKey = "gitlab.allowedCidrs")

/**
 * The self-managed host allowance (`infra/outbound/SelfManagedHosts.kt`, `Cidr.kt`; `.claude/docs/security.md` "Outbound HTTP
 * calls"): parsing and every boot refusal, then the address rule across its matrix. Pure and offline: literals only, no DNS.
 */
class SelfManagedHostsTest {

    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    /** A real [Inet6Address] holding the IPv4-mapped bytes: `getByName` folds the literal into an Inet4Address, this does not. */
    private fun rawMapped(a: Int, b: Int, c: Int, d: Int): InetAddress {
        val bytes = ByteArray(16).also {
            it[10] = 0xFF.toByte()
            it[11] = 0xFF.toByte()
            it[12] = a.toByte(); it[13] = b.toByte(); it[14] = c.toByte(); it[15] = d.toByte()
        }
        return Inet6Address.getByAddress(null, bytes, 0)
    }

    private fun refusal(hosts: String = "", cidrs: String = ""): String =
        assertFailsWith<IllegalStateException> { parseGitLabHosts(hosts, cidrs) }.message.orEmpty()

    // --- allowedHosts ---

    @Test
    fun `an empty configuration is off`() {
        val none = parseGitLabHosts("", "")
        assertFalse(none.enabled)
        assertFalse(parseGitLabHosts("   ", "  ").enabled)
        assertFalse(none.hostListed("gitlab.example.com"))
    }

    @Test
    fun `hosts are lowercased, trimmed, deduplicated and a missing port means 443`() {
        val hosts = parseGitLabHosts(" GitLab.Example.COM , git.example.com:8443,gitlab.example.com:443 ", "")
        assertTrue(hosts.enabled)
        assertTrue(hosts.hostListed("gitlab.example.com"))
        assertTrue(hosts.hostListed("GITLAB.EXAMPLE.COM"))
        assertTrue(hosts.hostListed("git.example.com"))
        assertFalse(hosts.hostListed("example.com"))
        assertFalse(hosts.hostListed("evil.gitlab.example.com"))
        assertTrue(hosts.originListed("https", "gitlab.example.com", 443), "no port and :443 are the same entry")
        assertTrue(hosts.originListed("https", "git.example.com", 8443))
        assertFalse(hosts.originListed("https", "git.example.com", 443), "an entry with a port means that port only")
        assertFalse(hosts.originListed("https", "gitlab.example.com", 8443))
        assertFalse(hosts.originListed("http", "gitlab.example.com", 443), "https only")
    }

    @Test
    fun `a single-label intranet name is a valid host`() {
        assertTrue(parseGitLabHosts("gitlab", "").hostListed("gitlab"))
    }

    @Test
    fun `malformed host entries refuse startup, naming the key and the entry`() {
        val bad = mapOf(
            "*.example.com" to "wildcard",
            "git*.example.com" to "wildcard",
            "https://gitlab.example.com" to "scheme",
            "gitlab.example.com/path" to "path",
            "gitlab.example.com?x=1" to "query",
            "gitlab.example.com#frag" to "fragment",
            "10.0.0.5" to "IPv4 literal",
            "10.0.0.5:8443" to "IPv4 literal with a port",
            "2130706433" to "decimal IPv4",
            "0x7f.0x1" to "hex IPv4",
            "127.1" to "short IPv4",
            "[::1]" to "IPv6 literal",
            "[::1]:8443" to "IPv6 literal with a port",
            "::1" to "bare IPv6",
            "fe80::1" to "bare IPv6",
            "gitlab.example.com:0" to "port 0",
            "gitlab.example.com:65536" to "port too large",
            "gitlab.example.com:-1" to "negative port",
            "gitlab.example.com:" to "empty port",
            "gitlab.example.com:80a" to "non-numeric port",
            "gitlab.example.com:123456" to "too many digits",
            ":443" to "no host",
            "gitlab..example.com" to "empty label",
            "-gitlab.example.com" to "leading hyphen",
            "gitlab-.example.com" to "trailing hyphen",
            "gitlab.example.com." to "trailing dot",
            "gitlab_one.example.com" to "underscore",
            "gütlab.example.com" to "non-ASCII",
            "${"a".repeat(64)}.example.com" to "label too long",
            "gitlab example.com" to "space",
        )
        for ((entry, why) in bad) {
            val message = refusal(hosts = entry)
            assertTrue("gitlab.allowedHosts" in message, "$why: the message names the key: $message")
            assertTrue(entry in message || "entry 1" in message, "$why: the message points at the entry: $message")
        }
    }

    @Test
    fun `credentials in a host entry refuse startup WITHOUT echoing them`() {
        val message = refusal(hosts = "deploy:hunter2secret@gitlab.example.com")
        assertTrue("gitlab.allowedHosts" in message && "credentials" in message, message)
        assertFalse("hunter2secret" in message, "a secret in a config entry never reaches the startup error: $message")
        val withQuery = refusal(hosts = "gitlab.example.com?token=glpat-abcdef")
        assertFalse("glpat-abcdef" in withQuery, withQuery)
    }

    @Test
    fun `the config key names are parameters, not hard-coded`() {
        val hostsFailure = assertFailsWith<IllegalStateException> { parseSelfManagedHosts("*.example.com", "", "my.hosts", "my.cidrs") }
        assertTrue("my.hosts" in hostsFailure.message.orEmpty() && "gitlab" !in hostsFailure.message.orEmpty(), hostsFailure.message)
        val cidrFailure = assertFailsWith<IllegalStateException> {
            parseSelfManagedHosts("a.example.com", "0.0.0.0/0", "my.hosts", "my.cidrs")
        }
        assertTrue("my.cidrs" in cidrFailure.message.orEmpty() && "gitlab" !in cidrFailure.message.orEmpty(), cidrFailure.message)
        val orphan = assertFailsWith<IllegalStateException> { parseSelfManagedHosts("", "10.20.0.0/16", "my.hosts", "my.cidrs") }
        assertTrue("my.cidrs" in orphan.message.orEmpty() && "my.hosts" in orphan.message.orEmpty(), orphan.message)
    }

    @Test
    fun `only strictly host- or CIDR-shaped entries are echoed in an error`() {
        assertTrue("\"gitlab.example.com/path\"" !in refusal(hosts = "gitlab.example.com/path"), "a path-bearing entry: by position")
        assertTrue("\"*.example.com\"" !in refusal(hosts = "*.example.com"))
        assertTrue("\"10.0.0.5:8443\"" in refusal(hosts = "10.0.0.5:8443"), "a plain host:port is echoed")
        assertTrue("\"0.0.0.0/0\"" in refusal(hosts = "gitlab.example.com", cidrs = "0.0.0.0/0"), "a plain CIDR is echoed")
        assertTrue("\"gitlab.example.com/24\"" !in refusal(hosts = "gitlab.example.com", cidrs = "gitlab.example.com/24"))
    }

    @Test
    fun `an empty entry in a list refuses startup`() {
        assertTrue("entry 2 is empty" in refusal(hosts = "gitlab.example.com,,git.example.com"))
        assertTrue("entry 2 is empty" in refusal(hosts = "gitlab.example.com,"))
        assertTrue("entry 2 is empty" in refusal(hosts = "gitlab.example.com", cidrs = "10.0.0.0/16, "))
    }

    // --- allowedCidrs ---

    @Test
    fun `valid CIDRs parse`() {
        val cidrs = "10.20.0.0/16, 172.16.0.0/12,192.168.5.0/24,10.0.0.0/8,fd12:3456::/32,fd12:3456:789a::/48,10.1.2.3/32"
        val hosts = parseGitLabHosts("gitlab.example.com", cidrs)
        assertTrue(hosts.enabled)
    }

    @Test
    fun `CIDRs without hosts refuse startup`() {
        assertTrue("allowedHosts" in refusal(hosts = "", cidrs = "10.20.0.0/16"))
    }

    @Test
    fun `a CIDR that is too wide or malformed refuses startup naming the entry`() {
        val bad = mapOf(
            "0.0.0.0/0" to "/0",
            "::/0" to "IPv6 /0",
            "10.0.0.0/7" to "IPv4 wider than /8",
            "8.0.0.0/4" to "IPv4 /4",
            "fd00::/8" to "IPv6 wider than /32",
            "fc00::/7" to "IPv6 /7",
            "2001:db8::/31" to "IPv6 /31",
            "10.0.0.0/33" to "prefix too long",
            "fd12:3456::/129" to "prefix too long",
            "10.0.0.0" to "no length",
            "10.0.0.0/" to "empty length",
            "10.0.0.0/-8" to "negative length",
            "10.0.0.0/8/8" to "two slashes",
            "10.0.0/16" to "short address",
            "10.0.0.256/24" to "bad octet",
            "gitlab.example.com/24" to "a name",
            "fe80::1%eth0/64" to "zone id",
            "10.20.0.5/16" to "host bits set",
            "fd12:3456::1/32" to "IPv6 host bits set",
            "::ffff:10.0.0.0/104" to "IPv4-mapped range",
            "::ffff:0:0/96" to "IPv4-mapped /96",
            "64:ff9b::/96" to "NAT64",
        )
        for ((entry, why) in bad) {
            val message = refusal(hosts = "gitlab.example.com", cidrs = entry)
            assertTrue("gitlab.allowedCidrs" in message, "$why: $message")
            // An entry with a character outside the plain host/CIDR alphabet (a '%' zone id) is identified by position instead.
            assertTrue(entry in message || "entry 1" in message, "$why: the offending entry is named: $message")
        }
    }

    @Test
    fun `a CIDR overlapping loopback, link-local, any-local or multicast refuses startup`() {
        val bad = listOf(
            "127.0.0.0/8", "127.0.0.1/32", "127.1.0.0/16",
            "169.254.0.0/16", "169.254.169.254/32", "169.254.1.0/24",
            "0.0.0.0/8", "0.0.0.0/32", "0.1.0.0/16",
            "224.0.0.0/4", "239.1.0.0/16", "224.0.0.1/32",
            "::1/128", "::/128", "::/32", "2002::/32", "2002:c000::/32", "2001::/32", "2001:0:4136::/48",
            "fe80::/10", "fe80::1/128", "fe80:1::/32",
            "ff00::/8", "ff02::/32", "ff02::1/128",
        )
        for (entry in bad) {
            val message = refusal(hosts = "gitlab.example.com", cidrs = entry)
            assertTrue(entry in message, "$entry must be refused by name: $message")
        }
        // The forbidden-range check is on OVERLAP, not on equality: a broad range that merely CONTAINS one is refused as well
        // (the /8 floor makes the widest such range 0.0.0.0/8 .. 255/8, e.g. 127.0.0.0/8 itself and 169.0.0.0/8 here).
        assertTrue("169.0.0.0/8" in refusal(hosts = "gitlab.example.com", cidrs = "169.0.0.0/8"))
        assertTrue("224.0.0.0/8" in refusal(hosts = "gitlab.example.com", cidrs = "224.0.0.0/8"))
    }

    @Test
    fun `one bad CIDR among good ones refuses startup`() {
        assertTrue("127.0.0.0/8" in refusal(hosts = "gitlab.example.com", cidrs = "10.20.0.0/16,127.0.0.0/8"))
    }

    // --- addressAllowed: the matrix ---

    private val listed = parseGitLabHosts("gitlab.example.com", "10.20.0.0/16,172.16.0.0/12,fd12:3456::/32")
    private val listedHost = "gitlab.example.com"
    private val unlistedHost = "other.example.com"

    @Test
    fun `a listed host in an allowed CIDR is allowed`() {
        assertTrue(listed.addressAllowed(listedHost, ip("10.20.1.5")))
        assertTrue(listed.addressAllowed(listedHost, ip("172.20.0.9")))
        assertTrue(listed.addressAllowed(listedHost, ip("fd12:3456::7")))
        assertTrue(listed.addressAllowed("GITLAB.EXAMPLE.COM", ip("10.20.1.5")), "the host match is case-insensitive")
    }

    @Test
    fun `a listed host on a private address OUTSIDE the CIDRs is refused`() {
        assertFalse(listed.addressAllowed(listedHost, ip("10.21.0.1")))
        assertFalse(listed.addressAllowed(listedHost, ip("192.168.1.1")))
        assertFalse(listed.addressAllowed(listedHost, ip("100.64.0.1")), "CGNAT outside the CIDRs")
        assertFalse(listed.addressAllowed(listedHost, ip("fd12:3457::1")))
        assertFalse(listed.addressAllowed(listedHost, ip("fc00::1")))
    }

    @Test
    fun `an unlisted host on a private address INSIDE the CIDRs is refused`() {
        assertFalse(listed.addressAllowed(unlistedHost, ip("10.20.1.5")))
        assertFalse(listed.addressAllowed("evil.gitlab.example.com", ip("10.20.1.5")))
        assertFalse(listed.addressAllowed("gitlab.example.com.evil.net", ip("10.20.1.5")))
        assertFalse(SelfManagedHosts.NONE.addressAllowed(listedHost, ip("10.20.1.5")), "with nothing configured nothing private is allowed")
    }

    @Test
    fun `a public address is allowed for every host, as today`() {
        assertTrue(listed.addressAllowed(listedHost, ip("203.0.113.10")))
        assertTrue(listed.addressAllowed(unlistedHost, ip("203.0.113.10")))
        assertTrue(SelfManagedHosts.NONE.addressAllowed(unlistedHost, ip("8.8.8.8")))
        assertTrue(listed.addressAllowed(listedHost, ip("2606:4700:4700::1111")))
    }

    @Test
    fun `loopback, link-local, any-local and multicast are refused for a listed host whatever the CIDRs say`() {
        // The broadest CIDRs the boot check admits that are NEAR these ranges, plus an unrestricted-looking /8 beside them.
        val broad = parseGitLabHosts(listedHost, "10.0.0.0/8,126.0.0.0/8,128.0.0.0/8,169.0.0.0/9,170.0.0.0/8,fd00::/32")
        val never = listOf(
            "127.0.0.1", "127.255.255.254", "169.254.169.254", "169.254.0.1", "0.0.0.0", "0.1.2.3", "224.0.0.1", "239.255.255.255",
            "::1", "::", "fe80::1", "febf::1", "ff02::1", "ff00::", "::10.0.0.1",
            "2002:c000:204::1", "2002:0a14:0105::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "2001:0:0:0:0:0:0:1",
        )
        for (literal in never) {
            assertFalse(broad.addressAllowed(listedHost, ip(literal)), "$literal must be refused for a listed host")
            assertFalse(listed.addressAllowed(listedHost, ip(literal)), "$literal must be refused for a listed host")
            assertFalse(listed.addressAllowed(unlistedHost, ip(literal)), "$literal must be refused for an unlisted host")
        }
    }

    @Test
    fun `IPv4-mapped IPv6 forms are judged as their IPv4 address`() {
        // As the JDK folds a mapped literal (an Inet4Address)...
        assertTrue(listed.addressAllowed(listedHost, ip("::ffff:10.20.1.5")))
        assertFalse(listed.addressAllowed(unlistedHost, ip("::ffff:10.20.1.5")))
        assertFalse(listed.addressAllowed(listedHost, ip("::ffff:10.21.0.1")))
        assertFalse(listed.addressAllowed(listedHost, ip("::ffff:127.0.0.1")))
        assertFalse(listed.addressAllowed(listedHost, ip("::ffff:169.254.169.254")))
        assertFalse(listed.addressAllowed(listedHost, ip("::ffff:0.0.0.0")))
        assertFalse(listed.addressAllowed(listedHost, ip("::ffff:224.0.0.1")))
        assertTrue(listed.addressAllowed(unlistedHost, ip("::ffff:203.0.113.10")))
        // ...and as a raw Inet6Address the JDK did not fold.
        assertTrue(listed.addressAllowed(listedHost, rawMapped(10, 20, 1, 5)))
        assertFalse(listed.addressAllowed(unlistedHost, rawMapped(10, 20, 1, 5)))
        assertFalse(listed.addressAllowed(listedHost, rawMapped(127, 0, 0, 1)))
        assertFalse(listed.addressAllowed(listedHost, rawMapped(169, 254, 169, 254)))
        assertFalse(listed.addressAllowed(unlistedHost, rawMapped(10, 0, 0, 1)))
        assertTrue(listed.addressAllowed(unlistedHost, rawMapped(203, 0, 113, 10)))
    }

    @Test
    fun `a NAT64 address embedding a private IPv4 is refused even with broad CIDRs`() {
        val broad = parseGitLabHosts(listedHost, "10.0.0.0/8,fd00::/32")
        assertFalse(broad.addressAllowed(listedHost, ip("64:ff9b::a14:105")), "64:ff9b::10.20.1.5")
        assertFalse(broad.addressAllowed(listedHost, ip("64:ff9b::7f00:1")), "64:ff9b::127.0.0.1")
    }

    @Test
    fun `an IPv4 CIDR never matches an IPv6 address and the reverse`() {
        val v4only = parseGitLabHosts(listedHost, "10.20.0.0/16")
        assertFalse(v4only.addressAllowed(listedHost, ip("fd12:3456::1")))
        val v6only = parseGitLabHosts(listedHost, "fd12:3456::/32")
        assertFalse(v6only.addressAllowed(listedHost, ip("10.20.1.5")))
    }

    @Test
    fun `the address rule answers per address, so one good and one bad answer differ`() {
        // GuardedDns refuses the whole lookup when ANY answer is refused (all-or-nothing, GitLabOutboundGuardTest).
        val answers = listOf(ip("10.20.1.5"), ip("127.0.0.1"))
        assertEquals(listOf(true, false), answers.map { listed.addressAllowed(listedHost, it) })
    }
}
