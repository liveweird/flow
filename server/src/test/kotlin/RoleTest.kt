package ch.nokillswit

import ch.nokillswit.ingest.ingestWorkerStarted
import ch.nokillswit.plugins.runsWorker
import ch.nokillswit.plugins.servesApi
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `FLOW_ROLE` (`plugins/Role.kt`, `app.role`): `worker` serves only the health/ready probes —
 * every feature route module and the SPA/static catch-all early-return via `servesApi()` — while
 * `web`/`all` serve the full API. `.claude/docs/ingestion.md` "Roles" is the operator-facing
 * writeup.
 */
class RoleTest {

    @Test
    fun `worker role serves only the health and ready probes`() = testApplication {
        // A configured staticDir would normally serve the SPA — proving the early return in
        // plugins/Routing.kt actually bites, not merely reproducing ServerTest's "no staticDir
        // configured" 404.
        val staticDir = Files.createTempDirectory("role-test-static")
        staticDir.resolve("index.html").writeText("<html>spa</html>")

        configureApp("app.role" to "worker", "web.staticDir" to staticDir.toString())
        var servesApi: Boolean? = null
        var runsWorker: Boolean? = null
        application {
            servesApi = servesApi()
            runsWorker = runsWorker()
        }
        startApplication()

        assertEquals(false, servesApi, "app must have started")
        assertEquals(true, runsWorker, "app must have started")
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/health").status)
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/ready").status)
        // The default (unvalidated) client on purpose: the spec declares 200/400/401/403 for GET
        // /api/v1/users and no operation at all for a bare SPA path, so a genuine 404 here is
        // undeclared drift as far as OpenApiConformance is concerned — jsonClient()/authedClient()
        // would (rightly) flag it. CoverageGapsTest's "AutoHeadResponse" case documents the same
        // escape hatch: the plugin only wraps the shared test-client factories.
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/users").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/data-sources").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/some-app-route").status)
    }

    @Test
    fun `web role serves the API but runs no worker`() = testApplication {
        configureApp("app.role" to "web")
        var servesApi: Boolean? = null
        var runsWorker: Boolean? = null
        lateinit var app: Application
        application {
            servesApi = servesApi()
            runsWorker = runsWorker()
            app = this
        }
        startApplication()

        assertEquals(true, servesApi, "app must have started")
        assertEquals(false, runsWorker, "app must have started")
        assertEquals(HttpStatusCode.OK, jsonClient().get("/api/v1/health").status)
        // 401, not 404: the route is registered and the JWT challenge fires before requireAdmin.
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/users").status)
        // The scan loop starts on ApplicationStarted (ingest/IngestWorker.kt) — AFTER startApplication()
        // returns, so this must be read via the captured Application, not inside the application{} block.
        assertFalse(app.ingestWorkerStarted(), "the web role must start no ingest worker")
    }

    @Test
    fun `worker role starts the ingest worker`() = testApplication {
        configureApp("app.role" to "worker")
        lateinit var app: Application
        application { app = this }
        startApplication()

        assertTrue(app.ingestWorkerStarted(), "the worker role must start the ingest worker")
    }

    @Test
    fun `all role serves the API and runs the worker`() = testApplication {
        configureApp("app.role" to "all")
        var servesApi: Boolean? = null
        var runsWorker: Boolean? = null
        application {
            servesApi = servesApi()
            runsWorker = runsWorker()
        }
        startApplication()

        assertEquals(true, servesApi, "app must have started")
        assertEquals(true, runsWorker, "app must have started")
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().get("/api/v1/users").status)
    }

    @Test
    fun `the shipped default role is all`() {
        // configureApp() pins tests to "web"; the production default lives in application.yaml.
        val shipped = io.ktor.server.config.ApplicationConfig("application.yaml").property("app.role").getString()
        assertEquals(if (System.getenv("FLOW_ROLE").isNullOrBlank()) "all" else System.getenv("FLOW_ROLE"), shipped)
    }

    @Test
    fun `an unrecognized role refuses to start`() = testApplication {
        configureApp("app.role" to "bogus")
        assertStartupFails("app.role") { startApplication() }
    }
}
