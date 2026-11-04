package ch.nokillswit

import ch.nokillswit.infra.outbound.BlockedHostException
import ch.nokillswit.infra.outbound.Origin
import ch.nokillswit.infra.outbound.isAllowedJiraOrigin
import ch.nokillswit.jira.JiraHttpClientKey
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Jira client's per-request origin rule (`isAllowedJiraOrigin`, wired by `configureJira`): `https` on 443 to an allowed Jira
 * host, or - development only - exactly the stub's origin. It closes the transport-level gap that OkHttp never hands an IP-literal
 * URL to the DNS guard. The rule is unit-tested first; the wiring is pinned through the client `configureJira` publishes.
 */
class JiraOriginGuardTest {
    private val stub = Origin("http", "jira-stub", 8094)

    private fun allowed(scheme: String, host: String, port: Int, devStub: Origin? = null, dev: Boolean = false) =
        isAllowedJiraOrigin(scheme, host, port, devStub, dev)

    @Test
    fun `https 443 to the gateway or a genuine tenant is allowed, nothing else is`() {
        assertTrue(allowed("https", "api.atlassian.com", 443))
        assertTrue(allowed("https", "acme-corp.atlassian.net", 443))
        assertFalse(allowed("https", "api.atlassian.com", 8443), "another port")
        assertFalse(allowed("http", "api.atlassian.com", 80), "plain http")
        assertFalse(allowed("http", "api.atlassian.com", 443), "http on 443")
        assertFalse(allowed("https", "api.atlassian.net", 443), "a reserved label")
        assertFalse(allowed("https", "evil.example.com", 443))
    }

    @Test
    fun `an IP-literal origin is refused, whatever its address`() {
        for (host in listOf("10.20.1.5", "127.0.0.1", "169.254.169.254", "203.0.113.10", "[::1]", "::1", "2130706433")) {
            assertFalse(allowed("https", host, 443), host)
            assertFalse(allowed("https", host, 443, devStub = stub, dev = true), "$host, even in development")
        }
    }

    @Test
    fun `the stub origin is allowed in development only, and only itself`() {
        assertTrue(allowed("http", "jira-stub", 8094, devStub = stub, dev = true))
        assertTrue(allowed("http", "JIRA-STUB", 8094, devStub = stub, dev = true))
        assertFalse(allowed("http", "jira-stub", 8094, devStub = stub, dev = false), "production never honours the stub")
        assertFalse(allowed("http", "jira-stub", 8095, devStub = stub, dev = true), "another port")
        assertFalse(allowed("https", "jira-stub", 8094, devStub = stub, dev = true), "another scheme")
        assertFalse(allowed("http", "other-host", 8094, devStub = stub, dev = true))
        assertFalse(allowed("http", "jira-stub", 8094, devStub = null, dev = true), "no stub configured")
    }

    @Test
    fun `the client configureJira publishes refuses an IP-literal, a foreign port, plain http and a Host header`() = testApplication {
        usePostgresTestcontainer()
        val client = application.attributes[JiraHttpClientKey]
        withAuditCapture { audit ->
            for (url in listOf(
                "https://10.20.1.5/rest/api", "https://127.0.0.1/x", "https://169.254.169.254/latest/meta-data/",
                "https://[::1]/x", "https://acme.atlassian.net:8443/x", "http://acme.atlassian.net/x", "https://evil.example.com/x",
            )) {
                assertFailsWith<BlockedHostException>(url) { client.get(url) }
            }
            assertFailsWith<BlockedHostException> {
                client.get("https://api.atlassian.com/x") { headers.append("Host", "evil.example.com") }
            }
            assertEquals(8, audit.events.count { it.message == "outbound.blocked" }, "each refusal is audited")
        }
    }
}
