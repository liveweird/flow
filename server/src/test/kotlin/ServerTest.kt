package ch.nokillswit

import io.ktor.client.request.get
import io.ktor.client.request.options
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.call.body
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.*

class ServerTest {

    @Test
    fun `unauthenticated API request returns a 401 problem+json body`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().post("/api/v1/logout?secret=must-not-be-reflected")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(
            response.headers["Content-Type"]?.startsWith("application/problem+json") == true,
            "401 challenge must be RFC 7807 problem+json",
        )
        assertContains(response.bodyAsText(), "\"status\":401")
        assertEquals("/api/v1/logout", response.body<ProblemDetail>().instance)
        assertFalse(response.bodyAsText().contains("must-not-be-reflected"))
    }

    @Test
    fun `security headers are set on responses`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().post("/api/v1/logout")
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        val csp = response.headers["Content-Security-Policy"]
        assertNotNull(csp, "Content-Security-Policy header should be present")
        // Pinned EXACTLY: a loosened directive (script-src, connect-src, …) must be a deliberate test edit.
        assertEquals(FULL_CSP, csp)
    }

    @Test
    fun `API JSON and problem+json responses are no-store`() = testApplication {
        usePostgresTestcontainer()
        val ok = jsonClient().get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("no-store", ok.headers[HttpHeaders.CacheControl], "a 200 application/json API answer")
        val problem = jsonClient().post("/api/v1/logout")
        assertEquals(HttpStatusCode.Unauthorized, problem.status)
        assertTrue(problem.headers["Content-Type"]?.startsWith("application/problem+json") == true)
        assertEquals("no-store", problem.headers[HttpHeaders.CacheControl], "a problem+json API answer")
    }

    @Test
    fun `static SPA assets keep their caching - hashed css and js cached a day, index html unchanged`() = testApplication {
        val staticDir = Files.createTempDirectory("server-test-static")
        try {
            staticDir.resolve("index.html").writeText("<html>spa</html>")
            staticDir.resolve("app.js").writeText("console.log(1)")
            staticDir.resolve("app.css").writeText("body{}")
            configureApp("web.staticDir" to staticDir.toString())
            startApplication()
            // The default client on purpose: non-/api/ paths are not in the OpenAPI spec.
            assertEquals("max-age=86400", client.get("/app.js").headers[HttpHeaders.CacheControl])
            assertEquals("max-age=86400", client.get("/app.css").headers[HttpHeaders.CacheControl])
            val index = client.get("/")
            assertEquals(HttpStatusCode.OK, index.status)
            assertNull(index.headers[HttpHeaders.CacheControl], "index.html stays un-cached-by-header, never no-store or max-age")
        } finally {
            staticDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `swagger UI is excluded from the strict CSP but still hardened`() = testApplication {
        usePostgresTestcontainer()
        val response = client.get("/openapi")
        // The strict app CSP must not cover the Swagger UI (it needs inline script/style)...
        assertNull(response.headers["Content-Security-Policy"])
        // ...but the non-CSP hardening headers still apply.
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
    }

    @Test
    fun `with the swagger UI mounted only openapi and its subtree are CSP-exempt`() = testApplication {
        configureApp("http.exposeOpenApi" to "true")
        startApplication()
        assertNull(client.get("/openapi").headers["Content-Security-Policy"])
        assertNull(client.get("/openapi/documentation.yaml").headers["Content-Security-Policy"])
        // A look-alike path is not the Swagger UI — it keeps the strict CSP.
        assertEquals(FULL_CSP, client.get("/openapiX").headers["Content-Security-Policy"])
    }

    @Test
    fun `with the swagger UI not mounted every openapi path carries the full CSP - the SPA catch-all serves it`() = testApplication {
        val staticDir = Files.createTempDirectory("server-test-openapi-csp")
        try {
            staticDir.resolve("index.html").writeText("<html>spa</html>")
            configureApp("http.exposeOpenApi" to "false", "web.staticDir" to staticDir.toString())
            startApplication()
            listOf("/openapi", "/openapi/x", "/openapi/documentation.yaml", "/openapiX").forEach { path ->
                val response = client.get(path)
                assertEquals(FULL_CSP, response.headers["Content-Security-Policy"], "CSP on $path (${response.status})")
            }
            assertEquals(HttpStatusCode.OK, client.get("/openapi").status, "the SPA catch-all answers /openapi with index.html")
        } finally {
            staticDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `configured corsHosts installs CORS and answers preflight`() = testApplication {
        configureApp("http.corsHosts" to "app.example.com")
        startApplication()
        val response = client.options("/api/v1/login") {
            header(HttpHeaders.Origin, "https://app.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("https://app.example.com", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `unknown route returns 404 when no SPA staticDir is configured`() = testApplication {
        usePostgresTestcontainer()
        val response = client.get("/definitely-not-a-route")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    private companion object {
        /** `plugins/SecurityHeaders.kt`'s APP_CSP, verbatim — any loosening must be a deliberate test edit. */
        const val FULL_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
            "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; " +
            "frame-ancestors 'none'"
    }
}
