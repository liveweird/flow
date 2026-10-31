package ch.nokillswit

import ch.nokillswit.gitlab.GitLabHostsKey
import ch.nokillswit.gitlab.GitLabHttpClientKey
import ch.nokillswit.gitlab.GitLabSettingsKey
import io.ktor.client.HttpClient
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.isActive

/**
 * `gitlab/GitLab.kt` `configureGitLab()`'s boot behaviour (`.claude/docs/security.md` "Outbound HTTP calls"): what refuses startup,
 * and what a disabled, a development and a production-mode boot publish. The modules before it run too (Flyway, Database),
 * so a production-mode test satisfies the earlier fail-closed checks first (`.claude/docs/testing.md`).
 */
class GitLabBootTest {
    private val testCa: String = File(assertNotNull(javaClass.getResource("/gitlab-test-ca.pem")).toURI()).absolutePath

    private fun ApplicationTestBuilder.productionConfig(vararg gitlab: Pair<String, String>) {
        configureApp(
            "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
            "jwt.secret" to strongJwtSecret(),
            "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
            *gitlab,
        )
        serverConfig { developmentMode = false }
    }

    private fun assertProductionRefuses(part: String, vararg gitlab: Pair<String, String>) = testApplication {
        productionConfig(*gitlab)
        withSeedRestored { assertStartupFails(part) { startApplication() } }
    }

    private fun assertDevelopmentRefuses(part: String, vararg gitlab: Pair<String, String>) = testApplication {
        configureApp(*gitlab)
        assertStartupFails(part) { startApplication() }
    }

    // Production-specific rules only: the stub refusal and one valid start. Everything else refuses in ANY mode, so one
    // development boot per key is enough (SelfManagedHostsTest unit-tests every host/CIDR case).

    @Test
    fun `production refuses a non-blank gitlab stubBaseUrl`() = assertProductionRefuses(
        "gitlab.stubBaseUrl", "gitlab.stubBaseUrl" to "http://gitlab-stub:8095",
    )

    @Test
    fun `a malformed host entry refuses startup`() = assertDevelopmentRefuses(
        "gitlab.allowedHosts", "gitlab.allowedHosts" to "*.example.com",
    )

    @Test
    fun `a bad CIDR refuses startup`() = assertDevelopmentRefuses(
        "gitlab.allowedCidrs", "gitlab.allowedHosts" to "gitlab.example.com", "gitlab.allowedCidrs" to "169.254.169.254/32",
    )

    @Test
    fun `CIDRs without hosts refuse startup`() = assertDevelopmentRefuses(
        "gitlab.allowedCidrs", "gitlab.allowedCidrs" to "10.20.0.0/16",
    )

    @Test
    fun `an unreadable CA bundle refuses startup`() {
        val missing = "/nonexistent/ca-${UUID.randomUUID()}.pem"
        assertDevelopmentRefuses("gitlab.caBundle", "gitlab.allowedHosts" to "gitlab.example.com", "gitlab.caBundle" to missing)
    }

    @Test
    fun `a CA bundle without hosts refuses startup`() = assertDevelopmentRefuses(
        "gitlab.caBundle", "gitlab.caBundle" to testCa,
    )

    @Test
    fun `an explicit gitlab maxResponseBytes above jira maxResponseBytes refuses startup, naming both values`() = testApplication {
        configureApp("jira.maxResponseBytes" to "4194304", "gitlab.maxResponseBytes" to "4194305")
        val failure = runCatching { startApplication() }.exceptionOrNull()
        assertNotNull(failure, "startup must fail closed, even while GitLab is disabled")
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("gitlab.maxResponseBytes" in messages && "4194305" in messages && "4194304" in messages, messages)
    }

    @Test
    fun `a malformed numeric setting refuses startup`() {
        assertDevelopmentRefuses("gitlab.maxResponseBytes", "gitlab.maxResponseBytes" to "10")
        assertDevelopmentRefuses("gitlab.requestTimeoutSeconds", "gitlab.requestTimeoutSeconds" to "0")
    }

    @Test
    fun `lowering only jira maxResponseBytes boots, and GitLab follows it`() = testApplication {
        configureApp("jira.maxResponseBytes" to (2L * 1024 * 1024).toString())
        startApplication()
        assertEquals(2L * 1024 * 1024, application.attributes[GitLabSettingsKey].maxResponseBytes)
    }

    @Test
    fun `the default gitlab maxResponseBytes is 8 MiB, and an explicit lower value is kept`() {
        testApplication {
            configureApp()
            startApplication()
            assertEquals(8L * 1024 * 1024, application.attributes[GitLabSettingsKey].maxResponseBytes)
        }
        testApplication {
            configureApp("gitlab.maxResponseBytes" to "1048576")
            startApplication()
            assertEquals(1048576L, application.attributes[GitLabSettingsKey].maxResponseBytes)
        }
    }

    @Test
    fun `a malformed stub URL refuses startup in development, without echoing it`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to "http://user:pw-marker@/path")
        val failure = runCatching { startApplication() }.exceptionOrNull()
        assertNotNull(failure, "startup must fail closed")
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue("gitlab.stubBaseUrl" in messages, messages)
        assertFalse("pw-marker" in messages, "the stub URL is never echoed")
    }

    @Test
    fun `a stub URL with credentials, a query or a fragment refuses startup`() {
        for (stub in listOf("http://user@gitlab-stub:8095", "http://gitlab-stub:8095/?x=1", "http://gitlab-stub:8095/#f")) {
            assertDevelopmentRefuses("gitlab.stubBaseUrl", "gitlab.stubBaseUrl" to stub)
        }
    }

    @Test
    fun `an empty configuration is GitLab disabled - nothing is built`() = testApplication {
        configureApp()
        startApplication()
        val attributes = application.attributes
        val settings = attributes[GitLabSettingsKey]
        assertFalse(settings.enabled)
        assertFalse(attributes[GitLabHostsKey].enabled)
        assertNull(settings.devStub)
        assertNull(settings.tls)
        assertFalse(attributes.contains(GitLabHttpClientKey), "no client is built while GitLab is disabled")
    }

    @Test
    fun `development accepts the stub and builds a client, closed when the application stops`() {
        lateinit var client: HttpClient
        testApplication {
            configureApp("gitlab.stubBaseUrl" to "http://gitlab-stub:8095")
            startApplication()
            val settings = application.attributes[GitLabSettingsKey]
            assertTrue(settings.enabled)
            assertEquals("gitlab-stub", settings.devStub?.host)
            assertEquals(8095, settings.devStub?.port)
            assertEquals("http", settings.devStub?.scheme)
            assertFalse(settings.hosts.enabled, "the stub does not list a host")
            client = application.attributes[GitLabHttpClientKey]
            assertTrue(client.isActive)
        }
        assertFalse(client.isActive, "the GitLab client is closed on ApplicationStopped")
    }

    @Test
    fun `a configured list builds a client with the bundle's trust, in development and in production`() {
        val settings = arrayOf(
            "gitlab.allowedHosts" to "gitlab.example.com, git.example.com:8443",
            "gitlab.allowedCidrs" to "10.20.0.0/16",
            "gitlab.caBundle" to testCa,
        )
        testApplication {
            configureApp(*settings)
            startApplication()
            val published = application.attributes[GitLabSettingsKey]
            assertTrue(published.enabled && published.hosts.enabled)
            assertNull(published.devStub)
            assertNotNull(published.tls)
            assertTrue(application.attributes[GitLabHostsKey].hostListed("gitlab.example.com"))
            assertTrue(application.attributes[GitLabHttpClientKey].isActive)
        }
        testApplication {
            productionConfig(*settings)
            withSeedRestored {
                startApplication()
                assertTrue(application.attributes[GitLabHttpClientKey].isActive, "production starts with a valid configuration")
            }
        }
    }
}
