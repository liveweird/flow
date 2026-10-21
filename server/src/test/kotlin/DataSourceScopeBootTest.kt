package ch.nokillswit

import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `ingest.scopeRules` (`INGEST_SCOPE_RULES`) boot switch (`ingest/DataSourceScope.kt`, `.claude/docs/security.md`
 * fail-closed list): `off` is for the isolated e2e stack only, so outside development mode it refuses to start, and an
 * unknown value fails startup in every mode. The rules themselves are `DataSourceScopeTest`'s.
 */
class DataSourceScopeBootTest {
    private fun request(keys: List<String>) = DataSourceRequest(
        name = "scope-boot-${UUID.randomUUID()}",
        enabled = true,
        syncIntervalMinutes = 60,
        backfillFrom = "2025-01-01",
        reconcileHourUtc = 3,
        jira = JiraConnectionRequest(
            TEST_JIRA_SITE_URL, "svc-${UUID.randomUUID()}@example.com", "token-${UUID.randomUUID()}", keys, JiraAuthScheme.BASIC,
        ),
    )

    @Test
    fun `production refuses ingest scopeRules off at boot`() = testApplication {
        // The checks fire in module order (JWT -> mail -> crypto -> database); the earlier ones must pass first.
        configureApp(
            "bootstrap.adminInitialPassword" to "rotated-${UUID.randomUUID()}",
            "jwt.secret" to strongJwtSecret(),
            "security.encryption.key" to strongEncryptionKey(),
            "mail.transport" to "disabled",
            "ingest.scopeRules" to "off",
        )
        serverConfig { developmentMode = false }
        withSeedRestored {
            assertStartupFails("ingest.scopeRules") { startApplication() }
        }
    }

    @Test
    fun `an unknown ingest scopeRules value fails startup`() = testApplication {
        configureApp("ingest.scopeRules" to "sometimes")
        assertStartupFails("ingest.scopeRules") { startApplication() }
    }

    @Test
    fun `development mode with scopeRules off boots and lets two sources share a project key`() = testApplication {
        configureApp("ingest.scopeRules" to "off")
        startApplication()
        val admin = seededClient("scope-boot-off", FlowUserRole.ADMIN)
        val shared = uniqueProjectKeys(1)
        val first = admin.postJson("/api/v1/data-sources", request(shared))
        val second = admin.postJson("/api/v1/data-sources", request(shared))
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, second.status)
        listOf(first, second).forEach { admin.delete("/api/v1/data-sources/${it.body<DataSourceResponse>().id}") }
    }
}
