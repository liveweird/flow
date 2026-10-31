package ch.nokillswit

import ch.nokillswit.gitlab.GitLabDevStub
import ch.nokillswit.gitlab.buildGuardedGitLabHttpClient
import ch.nokillswit.infra.outbound.BlockedHostException
import ch.nokillswit.infra.outbound.GuardedDns
import ch.nokillswit.infra.outbound.SelfManagedHosts
import ch.nokillswit.infra.outbound.parseSelfManagedHosts
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The GitLab client's outbound guard (`gitlab/GitLab.kt` `buildGuardedGitLabHttpClient`, `infra/outbound/OutboundGuard.kt`,
 * `SelfManagedHosts.kt`; `.claude/docs/security.md` "Outbound HTTP calls"): the SAME production builder, driven through the
 * real OkHttp engine. The development stub is an in-JVM WireMock on loopback, exactly like the Jira stub tests.
 */
class GitLabOutboundGuardTest {
    private lateinit var wiremock: WireMockServer
    private lateinit var devStub: GitLabDevStub

    private val loopback = InetAddress.getByName("127.0.0.1")
    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    private val hosts = parseSelfManagedHosts("gitlab.example.com", "10.20.0.0/16")

    @BeforeTest
    fun startStub() {
        wiremock = WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().disableRequestJournal()).also { it.start() }
        devStub = GitLabDevStub("http", "localhost", wiremock.port())
    }

    @AfterTest
    fun stopStub() {
        wiremock.stop()
    }

    private fun blocked(audit: LogCapture, host: String) =
        audit.events.filter { it.message == "outbound.blocked" && it.hasKeyValue("host", host) }

    // --- DNS: every lookup is checked ---

    @Test
    fun `DNS rebinding - a public answer first and a private one second, each lookup is checked`() = runBlocking<Unit> {
        val answers = ArrayDeque(
            listOf(listOf(ip("203.0.113.10")), listOf(ip("10.21.0.5")), listOf(ip("127.0.0.1")), listOf(ip("10.20.1.5"))),
        )
        val resolved = AtomicInteger()
        val dns = GuardedDns(
            allowedHost = hosts::hostListed,
            resolver = { resolved.incrementAndGet(); answers.removeFirst() },
            addressAllowed = hosts::addressAllowed,
        )
        withAuditCapture { audit ->
            assertEquals(listOf(ip("203.0.113.10")), dns.lookup("gitlab.example.com"), "the first answer is public")
            // The host now resolves to a private address OUTSIDE the CIDRs: the second lookup is refused, audited.
            assertFailsWith<BlockedHostException> { dns.lookup("gitlab.example.com") }
            // ...and to loopback, which no CIDR can admit.
            assertFailsWith<BlockedHostException> { dns.lookup("gitlab.example.com") }
            // ...and to an address inside the CIDRs: allowed again (nothing is cached from the refusals).
            assertEquals(listOf(ip("10.20.1.5")), dns.lookup("gitlab.example.com"))
            assertEquals(4, resolved.get(), "every lookup resolved afresh")
            assertEquals(2, blocked(audit, "gitlab.example.com").size, "each refusal is audited outbound.blocked")
        }
    }

    @Test
    fun `a listed host answering one good and one bad address is refused as a whole`() = runBlocking<Unit> {
        val dns = GuardedDns(
            allowedHost = hosts::hostListed,
            resolver = { listOf(ip("10.20.1.5"), ip("10.99.0.1")) },
            addressAllowed = hosts::addressAllowed,
        )
        withAuditCapture { audit ->
            assertFailsWith<BlockedHostException> { dns.lookup("gitlab.example.com") }
            assertEquals(1, blocked(audit, "gitlab.example.com").size)
        }
        val mixedWithLoopback = GuardedDns(
            allowedHost = hosts::hostListed,
            resolver = { listOf(ip("203.0.113.10"), ip("127.0.0.1")) },
            addressAllowed = hosts::addressAllowed,
        )
        assertFailsWith<BlockedHostException> { mixedWithLoopback.lookup("gitlab.example.com") }
    }

    @Test
    fun `a listed host inside the CIDRs resolves, an unlisted host never reaches the resolver`() {
        var resolved = 0
        val dns = GuardedDns(
            allowedHost = hosts::hostListed,
            resolver = { resolved++; listOf(ip("10.20.1.5")) },
            addressAllowed = hosts::addressAllowed,
        )
        assertEquals(listOf(ip("10.20.1.5")), dns.lookup("gitlab.example.com"))
        assertFailsWith<BlockedHostException> { dns.lookup("other.example.com") }
        assertEquals(1, resolved, "the unlisted host was refused before resolution")
    }

    @Test
    fun `a DNS failure for a listed host is a plain UnknownHostException, not a block`() = runBlocking<Unit> {
        val dns = GuardedDns(
            allowedHost = hosts::hostListed,
            resolver = { throw UnknownHostException("detail") },
            addressAllowed = hosts::addressAllowed,
        )
        withAuditCapture { audit ->
            val failure = assertFailsWith<UnknownHostException> { dns.lookup("gitlab.example.com") }
            assertFalse(failure is BlockedHostException)
            assertTrue(blocked(audit, "gitlab.example.com").isEmpty())
        }
    }

    // --- the production client ---

    @Test
    fun `an unlisted host is refused without resolving and audits outbound_blocked with scheme and host only`() = runBlocking<Unit> {
        var resolved = 0
        val client = buildGuardedGitLabHttpClient(
            hosts, devStub = null, requestTimeoutSeconds = 5, resolver = { resolved++; listOf(ip("10.20.1.5")) },
        )
        try {
            withAuditCapture { audit ->
                assertFailsWith<BlockedHostException> {
                    client.get("https://evil.example.net/api/v4/version?private_token=glpat-secret-value") {
                        header("PRIVATE-TOKEN", "glpat-secret-value")
                    }
                }
                assertEquals(0, resolved, "an unlisted host never reaches the resolver")
                val event = assertNotNull(blocked(audit, "evil.example.net").singleOrNull())
                assertTrue(event.hasKeyValue("scheme", "https"))
                assertEquals(setOf("scheme", "host"), event.keyValuePairs.map { it.key }.toSet(), "scheme and host ONLY")
                assertFalse(event.formattedMessage.contains("glpat") || event.keyValuePairs.any { it.value.toString().contains("glpat") })
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a listed host that resolves into the forbidden ranges is refused even with CIDRs configured`() = runBlocking<Unit> {
        for (literal in listOf("127.0.0.1", "169.254.169.254", "10.99.0.1")) {
            var resolved = 0
            val client = buildGuardedGitLabHttpClient(
                hosts, devStub = null, requestTimeoutSeconds = 5, resolver = { resolved++; listOf(ip(literal)) },
            )
            try {
                assertFailsWith<BlockedHostException>(literal) { client.get("https://gitlab.example.com/api/v4/version") }
                assertEquals(1, resolved, "$literal: refused on the first lookup, never retried")
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `an IP-literal URL is refused even inside the CIDRs, since OkHttp never resolves a literal`() = runBlocking<Unit> {
        val client = buildGuardedGitLabHttpClient(
            hosts, devStub = null, requestTimeoutSeconds = 5, resolver = { error("must not resolve") },
        )
        try {
            withAuditCapture { audit ->
                val urls = listOf(
                    "https://10.20.1.5/api/v4/version", "https://127.0.0.1/", "https://169.254.169.254/latest/meta-data/", "https://[::1]/",
                )
                for (url in urls) {
                    assertFailsWith<BlockedHostException>(url) { client.get(url) }
                }
                assertTrue(blocked(audit, "10.20.1.5").isNotEmpty())
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a listed host is reachable on its listed port over https only`() = runBlocking<Unit> {
        val withPort = parseSelfManagedHosts("gitlab.example.com:8443", "10.20.0.0/16")
        val client = buildGuardedGitLabHttpClient(
            withPort, devStub = null, requestTimeoutSeconds = 5, resolver = { error("must not resolve") },
        )
        try {
            // Refused by the request check before any DNS: the entry means port 8443, not 443 and not any other.
            for (url in listOf("https://gitlab.example.com/x", "https://gitlab.example.com:9443/x", "http://gitlab.example.com:8443/x")) {
                assertFailsWith<BlockedHostException>(url) { client.get(url) }
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a redirect from the dev stub host is returned as a 302 and never followed`() = runBlocking<Unit> {
        val path = "/redirect-probe"
        wiremock.stubFor(
            get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(302).withHeader("Location", "https://evil.example.net/landed")),
        )
        wiremock.stubFor(get(urlPathEqualTo("/landed")).willReturn(aResponse().withStatus(200).withBody("followed")))
        var resolved = 0
        val client = buildGuardedGitLabHttpClient(
            SelfManagedHosts.NONE, devStub, 5, resolver = { resolved++; listOf(loopback) },
        )
        try {
            val response = client.get("http://localhost:${wiremock.port()}$path")
            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("https://evil.example.net/landed", response.headers["Location"])
            assertEquals(1, resolved, "only the original host was ever resolved: nothing followed")
            // A redirect to the stub's OWN origin is not followed either.
            wiremock.stubFor(get(urlPathEqualTo("/same-origin")).willReturn(aResponse().withStatus(301).withHeader("Location", "/landed")))
            val second = client.get("http://localhost:${wiremock.port()}/same-origin")
            assertEquals(HttpStatusCode.MovedPermanently, second.status)
        } finally {
            client.close()
        }
    }

    @Test
    fun `the dev stub host skips the address check, only on its own origin`() = runBlocking<Unit> {
        wiremock.stubFor(get(urlPathEqualTo("/ok")).willReturn(aResponse().withStatus(200).withBody("stub")))
        val client = buildGuardedGitLabHttpClient(hosts, devStub, 5, resolver = { listOf(loopback) })
        try {
            assertEquals("stub", client.get("http://localhost:${wiremock.port()}/ok").bodyAsText())
            // Another port of the stub host is another origin.
            assertFailsWith<BlockedHostException> { client.get("http://localhost:${wiremock.port() + 1}/ok") }
            // The skip does not leak to a listed host: loopback stays refused for it.
            assertFailsWith<BlockedHostException> { client.get("https://gitlab.example.com/ok") }
            // Without the stub configured (production), the same URL is refused outright.
            val production = buildGuardedGitLabHttpClient(hosts, devStub = null, requestTimeoutSeconds = 5, resolver = { listOf(loopback) })
            try {
                assertFailsWith<BlockedHostException> { production.get("http://localhost:${wiremock.port()}/ok") }
            } finally {
                production.close()
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `a configured system proxy is ignored (Proxy_NO_PROXY)`() = runBlocking<Unit> {
        wiremock.stubFor(get(urlPathEqualTo("/ok")).willReturn(aResponse().withStatus(200).withBody("direct")))
        val originalHost = System.getProperty("http.proxyHost")
        val originalPort = System.getProperty("http.proxyPort")
        // An unroutable proxy: if the client honoured it, the call would fail instead of reaching the stub directly.
        System.setProperty("http.proxyHost", "203.0.113.10")
        System.setProperty("http.proxyPort", "1")
        val client = buildGuardedGitLabHttpClient(SelfManagedHosts.NONE, devStub, 5, resolver = { listOf(loopback) })
        try {
            assertEquals("direct", client.get("http://localhost:${wiremock.port()}/ok").bodyAsText())
        } finally {
            client.close()
            originalHost?.let { System.setProperty("http.proxyHost", it) } ?: System.clearProperty("http.proxyHost")
            originalPort?.let { System.setProperty("http.proxyPort", it) } ?: System.clearProperty("http.proxyPort")
        }
    }
}
