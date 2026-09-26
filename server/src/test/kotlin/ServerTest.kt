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
        assertContains(csp, "default-src 'self'")
        assertContains(csp, "frame-ancestors 'none'")
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
}
