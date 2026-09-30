package ch.nokillswit

import ch.nokillswit.jira.JiraHttpClientKey
import io.ktor.client.HttpClient
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `jira/Jira.kt` builds ONE long-lived guarded client; it must be closed when the application stops. */
class JiraHttpClientLifecycleTest {

    @Test
    fun `the shared Jira HttpClient is closed on ApplicationStopped`() {
        lateinit var client: HttpClient
        testApplication {
            usePostgresTestcontainer()
            client = application.attributes[JiraHttpClientKey]
            assertTrue(client.isActive, "the client is live while the application runs")
        }
        // testApplication stops the application (ApplicationStopped) when its block returns.
        assertFalse(client.isActive, "the client must be closed once the application has stopped")
    }
}
