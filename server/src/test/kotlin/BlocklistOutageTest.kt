package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JWT `validate` block's blocklist lookup (`plugins/Security.kt`) runs a database query on
 * every authenticated request. A query that THROWS — database unreachable, pool acquire timeout —
 * is an outage, not a bad token: it must answer the catch-all's 500, never the 401 that the SPA
 * reads as session expiry (ported from Lettuce v3.16.2; see "JWT model" in
 * `.claude/docs/security.md`). The app's blocklist service is swapped for one pointed at an
 * unreachable database, a deterministic fast failure without a real outage.
 */
class BlocklistOutageTest {

    private fun brokenBlocklist() = TokenBlocklistService(
        R2dbcDatabase.connect(
            url = "r2dbc:postgresql://localhost:1/nonexistent",
            user = "nobody",
            password = "nobody",
        ),
    )

    @Test
    fun `a failing blocklist lookup answers 500, not 401`() = testApplication {
        configureApp()
        application { attributes.put(TokenBlocklistServiceKey, brokenBlocklist()) }
        startApplication()

        // Login mints tokens without consulting the blocklist, so a real access token is available.
        val email = "blocklist-outage-${UUID.randomUUID()}@test"
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val login = client.post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, "pw"))
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = login.body<LoginResponse>().token

        val response = client.get("/api/v1/teams") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val problem = response.body<ProblemDetail>()
        assertEquals(HttpStatusCode.InternalServerError.value, problem.status)
        assertEquals("An unexpected error occurred", problem.detail)
    }

    @Test
    fun `a refresh token used as bearer stays 401 during an outage`() = testApplication {
        // The typ check runs before the blocklist lookup, so a structurally wrong token is
        // rejected as such even while the database is unreachable.
        configureApp()
        application { attributes.put(TokenBlocklistServiceKey, brokenBlocklist()) }
        startApplication()

        val email = "blocklist-outage-refresh-${UUID.randomUUID()}@test"
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val login = client.post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, "pw"))
        }
        val refreshToken = login.body<LoginResponse>().refreshToken

        val response = client.get("/api/v1/teams") {
            header(HttpHeaders.Authorization, "Bearer $refreshToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a malformed token never reaches the lookup and stays 401 during an outage`() = testApplication {
        configureApp()
        application { attributes.put(TokenBlocklistServiceKey, brokenBlocklist()) }
        startApplication()

        val response = jsonClient().get("/api/v1/teams") {
            header(HttpHeaders.Authorization, "Bearer not-a-jwt")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Missing or invalid bearer token", response.body<ProblemDetail>().detail)
    }
}
