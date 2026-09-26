package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class LogoutTest {

    @Test
    fun `logout revokes the access token - a repeat call with it is 401`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("logout")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw").body<LoginResponse>()

        val logout = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)

        // The jti is on the blocklist now: the same bearer no longer authenticates.
        val replay = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
        }
        assertEquals(HttpStatusCode.Unauthorized, replay.status)
    }

    @Test
    fun `logout without a token is 401`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().post("/api/v1/logout")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
