package ch.nokillswit

import ch.nokillswit.infra.outbound.BlockedHostException
import ch.nokillswit.infra.outbound.GuardedDns
import ch.nokillswit.infra.outbound.isAllowedJiraHost
import ch.nokillswit.jira.JiraFetchException
import ch.nokillswit.jira.JiraHttp
import ch.nokillswit.jira.buildGuardedJiraHttpClient
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import io.ktor.http.HttpMethod
import io.ktor.server.testing.testApplication
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The outbound HTTP boundary for the Jira client (v0.2.0 plan §6, `.claude/docs/security.md`
 * "Outbound HTTP calls"): the host allow-list ([isAllowedJiraHost]) as the primary boundary, and
 * [GuardedDns]'s address check (ported verbatim from Toadie's `isBlockedAddress`) as defense in
 * depth, with an injected resolver so every case is exact and offline.
 */
class OutboundGuardTest {

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    /** [hextets] are the 8 IPv6 groups (0..0xFFFF each) — safer than hand-counting a hex string. */
    private fun ipv6(vararg hextets: Int): InetAddress {
        require(hextets.size == 8)
        val bytes = ByteArray(16)
        hextets.forEachIndexed { i, h ->
            bytes[i * 2] = (h shr 8).toByte()
            bytes[i * 2 + 1] = (h and 0xFF).toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    @Test
    fun `allow-list accepts the gateway host and a genuine tenant, refuses reserved labels and other hosts`() {
        assertTrue(isAllowedJiraHost("api.atlassian.com", null, developmentMode = false))
        assertTrue(isAllowedJiraHost("acme-corp.atlassian.net", null, developmentMode = false))
        assertFalse(isAllowedJiraHost("api.atlassian.net", null, developmentMode = false), "a reserved label is not a genuine tenant")
        assertFalse(isAllowedJiraHost("evil.example.com", null, developmentMode = false))
        assertFalse(
            isAllowedJiraHost("169.254.169.254", null, developmentMode = false),
            "a raw metadata-service address is not a tenant host",
        )
    }

    @Test
    fun `the stub host is allowed ONLY in development, and only itself`() {
        assertFalse(isAllowedJiraHost("jira-stub", "jira-stub", developmentMode = false), "production never honours the stub host")
        assertTrue(isAllowedJiraHost("jira-stub", "jira-stub", developmentMode = true))
        assertFalse(isAllowedJiraHost("other-host", "jira-stub", developmentMode = true), "only the CONFIGURED stub host, not any host")
        assertFalse(isAllowedJiraHost("jira-stub", null, developmentMode = true), "a blank/unset stub host is never matched")
    }

    @Test
    fun `GuardedDns refuses a disallowed host WITHOUT ever resolving it`() {
        var resolved = false
        val dns = GuardedDns(allowedHost = { false }, resolver = { resolved = true; listOf(ipv4(8, 8, 8, 8)) })
        assertFailsWith<BlockedHostException> { dns.lookup("evil.example.com") }
        assertFalse(resolved, "an already-disallowed host must never reach the resolver")
    }

    @Test
    fun `GuardedDns refuses an unresolvable host`() {
        val dns = GuardedDns(allowedHost = { true }, resolver = { throw UnknownHostException() })
        assertFailsWith<BlockedHostException> { dns.lookup("api.atlassian.com") }
    }

    @Test
    fun `GuardedDns refuses every blocked address range, including IPv4-mapped IPv6`() {
        val blocked: List<Pair<String, InetAddress>> = listOf(
            "loopback" to ipv4(127, 0, 0, 1),
            "private 10-net" to ipv4(10, 0, 0, 1),
            "private 192.168-net" to ipv4(192, 168, 1, 1),
            "link-local" to ipv4(169, 254, 1, 1),
            "multicast" to ipv4(224, 0, 0, 1),
            "any-local (0.0.0.0)" to ipv4(0, 0, 0, 0),
            "CGNAT 100.64/10" to ipv4(100, 64, 0, 1),
            "IETF protocol assignments 192.0.0/24" to ipv4(192, 0, 0, 1),
            "benchmarking 198.18/15" to ipv4(198, 18, 0, 1),
            "benchmarking 198.19/15" to ipv4(198, 19, 255, 254),
            "IPv6 loopback ::1" to ipv6(0, 0, 0, 0, 0, 0, 0, 1),
            "IPv6 unique-local fc00::/7" to ipv6(0xfc00, 0, 0, 0, 0, 0, 0, 1),
            "IPv6 unique-local fd00::/7" to ipv6(0xfd12, 0x3456, 0, 0, 0, 0, 0, 1),
            "IPv6 link-local fe80::/10" to ipv6(0xfe80, 0, 0, 0, 0, 0, 0, 1),
            "IPv4-mapped IPv6 loopback ::ffff:127.0.0.1" to ipv6(0, 0, 0, 0, 0, 0xffff, 0x7f00, 0x0001),
            "IPv4-mapped IPv6 private ::ffff:10.0.0.1" to ipv6(0, 0, 0, 0, 0, 0xffff, 0x0a00, 0x0001),
            "NAT64 64:ff9b::/96 embedding a private IPv4" to ipv6(0x0064, 0xff9b, 0, 0, 0, 0, 0xc0a8, 0x0101),
        )
        blocked.forEach { (label, address) ->
            val dns = GuardedDns(allowedHost = { true }, resolver = { listOf(address) })
            assertFailsWith<BlockedHostException>("expected $label to be blocked") { dns.lookup("api.atlassian.com") }
        }
    }

    @Test
    fun `GuardedDns accepts a genuinely public address`() {
        val dns = GuardedDns(allowedHost = { true }, resolver = { listOf(ipv4(203, 0, 113, 10)) })
        assertEquals(1, dns.lookup("api.atlassian.com").size)
    }

    @Test
    fun `GuardedDns refuses if ANY resolved address in a multi-address answer is blocked`() {
        val dns = GuardedDns(allowedHost = { true }, resolver = { listOf(ipv4(203, 0, 113, 10), ipv4(127, 0, 0, 1)) })
        assertFailsWith<BlockedHostException> { dns.lookup("api.atlassian.com") }
    }

    @Test
    fun `production refuses a non-blank jira stubBaseUrl at boot`() = testApplication {
        val newPassword = "rotated-${UUID.randomUUID()}"
        configureApp(
            "bootstrap.adminInitialPassword" to newPassword,
            "jwt.secret" to strongJwtSecret(),
            "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
            "jira.stubBaseUrl" to "http://jira-stub:8094",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("jira.stubBaseUrl") { startApplication() }
        }
    }

    // --- Production-wiring integration tests (MED-1/MED-3): [buildGuardedJiraHttpClient] builds
    // EXACTLY the Ktor `HttpClient(OkHttp)` `jira/Jira.kt`'s `configureJira()` wires into
    // production — not a hand-rolled approximation — driven against the real, loopback
    // [JiraStubServer] WireMock fixture through the REAL OkHttp engine. ---

    @Test
    fun `production wiring - an allowed host resolving to a blocked address is BLOCKED_HOST, one attempt, no retry`() = runBlocking {
        val stubUrl = JiraStubServer.start()
        var resolveCalls = 0
        val httpClient = buildGuardedJiraHttpClient(
            allowedHost = { true },
            resolver = { resolveCalls++; InetAddress.getAllByName(it).toList() },
            skipAddressCheck = { false },
            requestTimeoutSeconds = 5,
        )
        // [MED-1] mutation-check: revert BlockedHostException to a bare RuntimeException and this
        // becomes UPSTREAM_UNAVAILABLE after 3 resolver calls (~2-6s of real backoff) instead.
        val jiraHttp = JiraHttp(httpClient, maxRetries = 2, maxResponseBytes = 1_000_000, maxConcurrentRequests = 4)
        val error = assertFailsWith<JiraFetchException> { jiraHttp.request(HttpMethod.Get, "$stubUrl/_edge/tenant_info") }
        assertEquals("BLOCKED_HOST", error.code)
        assertEquals(1, resolveCalls, "BLOCKED_HOST must not be retried")
    }

    @Test
    fun `production wiring - a disallowed host is BLOCKED_HOST without ever invoking the resolver`() = runBlocking {
        var resolveCalls = 0
        val httpClient = buildGuardedJiraHttpClient(
            allowedHost = { false },
            resolver = { resolveCalls++; listOf(InetAddress.getByName("203.0.113.10")) },
            requestTimeoutSeconds = 5,
        )
        val jiraHttp = JiraHttp(httpClient, maxRetries = 2, maxResponseBytes = 1_000_000, maxConcurrentRequests = 4)
        val error = assertFailsWith<JiraFetchException> { jiraHttp.request(HttpMethod.Get, "https://evil.example.com/probe") }
        assertEquals("BLOCKED_HOST", error.code)
        assertEquals(0, resolveCalls, "an already-disallowed host must never reach the resolver")
    }

    @Test
    fun `production wiring - a 3xx from the real OkHttp engine is REDIRECT, never followed`() = runBlocking {
        val stubUrl = JiraStubServer.start()
        val redirectPath = "/probe-redirect-${UUID.randomUUID()}"
        val override = JiraStubServer.addOverride(
            get(urlPathEqualTo(redirectPath)).willReturn(aResponse().withStatus(302).withHeader("Location", "https://evil.example/")),
        )
        try {
            val httpClient = buildGuardedJiraHttpClient(
                // The stub is loopback by construction (same convention as `jira.stubBaseUrl` in
                // development) — skip the address-shape check for it the same way production does.
                allowedHost = { true },
                skipAddressCheck = { true },
                requestTimeoutSeconds = 5,
            )
            val jiraHttp = JiraHttp(httpClient, maxRetries = 0, maxResponseBytes = 1_000_000, maxConcurrentRequests = 4)
            val error = assertFailsWith<JiraFetchException> { jiraHttp.request(HttpMethod.Get, "$stubUrl$redirectPath") }
            assertEquals("REDIRECT", error.code)
        } finally {
            JiraStubServer.removeOverride(override)
        }
    }

    @Test
    fun `production wiring - ignores a configured system proxy (Proxy_NO_PROXY)`() = runBlocking {
        val stubUrl = JiraStubServer.start()
        val originalHost = System.getProperty("http.proxyHost")
        val originalPort = System.getProperty("http.proxyPort")
        // An unroutable proxy target: if the guarded client honoured it, the call would fail
        // (connection refused/unreachable) instead of reaching the real, loopback stub directly.
        System.setProperty("http.proxyHost", "203.0.113.10")
        System.setProperty("http.proxyPort", "1")
        try {
            val httpClient = buildGuardedJiraHttpClient(allowedHost = { true }, skipAddressCheck = { true }, requestTimeoutSeconds = 5)
            val jiraHttp = JiraHttp(httpClient, maxRetries = 0, maxResponseBytes = 1_000_000, maxConcurrentRequests = 4)
            val result = jiraHttp.request(HttpMethod.Get, "$stubUrl/_edge/tenant_info")
            assertTrue(result.toString().contains("cloudId"), "expected the real tenant_info stub body: $result")
        } finally {
            originalHost?.let { System.setProperty("http.proxyHost", it) } ?: System.clearProperty("http.proxyHost")
            originalPort?.let { System.setProperty("http.proxyPort", it) } ?: System.clearProperty("http.proxyPort")
        }
    }

    /** [LOW-3] Counts OkHttp's own connection-pool checkout/checkin events — the leak signal. */
    private class CountingEventListener : EventListener() {
        val acquired = AtomicInteger(0)
        val released = AtomicInteger(0)
        override fun connectionAcquired(call: Call, connection: Connection) {
            acquired.incrementAndGet()
        }
        override fun connectionReleased(call: Call, connection: Connection) {
            released.incrementAndGet()
        }
    }

    @Test
    fun `every attempt releases its OkHttp connection back to the pool, even on repeated failures`() = runBlocking {
        val stubUrl = JiraStubServer.start()
        val failPath = "/probe-fail-${UUID.randomUUID()}"
        // A body large enough that OkHttp must actually STREAM it off the socket (rather than
        // having it already fully buffered after the initial read) — only then does an unread
        // body keep the connection checked out of the pool long enough for this test to observe.
        val largeBody = "x".repeat(2 * 1024 * 1024)
        val override = JiraStubServer.addOverride(get(urlPathEqualTo(failPath)).willReturn(aResponse().withStatus(503).withBody(largeBody)))
        val listener = CountingEventListener()
        try {
            val httpClient = buildGuardedJiraHttpClient(
                allowedHost = { true },
                skipAddressCheck = { true },
                requestTimeoutSeconds = 5,
                eventListener = listener,
            )
            val jiraHttp = JiraHttp(httpClient, maxRetries = 2, maxResponseBytes = 1_000_000, maxConcurrentRequests = 4, sleeper = {})
            assertFailsWith<JiraFetchException> { jiraHttp.request(HttpMethod.Get, "$stubUrl$failPath") }
            assertTrue(listener.acquired.get() >= 3, "the initial attempt plus 2 retries: ${listener.acquired.get()}")
            assertEquals(
                listener.acquired.get(),
                listener.released.get(),
                "every acquired connection must be released — no leaked connections after repeated failures",
            )
        } finally {
            JiraStubServer.removeOverride(override)
        }
    }

    @Test
    fun `a blocked stub host is audited with its REAL scheme, not a hardcoded https`() = runBlocking {
        withAuditCapture { capture ->
            val dns = GuardedDns(allowedHost = { false }, schemeFor = { "http" })
            assertFailsWith<BlockedHostException> { dns.lookup("jira-stub") }
            val event = capture.awaitEvent { it.message == "outbound.blocked" && it.hasKeyValue("scheme", "http") }
            assertNotNull(event, "expected outbound.blocked audited with the real (http) scheme, not a hardcoded https")
            Unit
        }
    }
}
