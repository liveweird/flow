package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.LogoutRequest
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.users.PasswordUpdateRequest
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RefreshTest {

    private suspend fun io.ktor.client.HttpClient.login(email: String, password: String): LoginResponse =
        post("/api/v1/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email, password))
        }.body<LoginResponse>()

    @Test
    fun `a valid refresh token yields a fresh pair`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("refresh")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))

        assertEquals(HttpStatusCode.OK, response.status)
        val renewed = response.body<LoginResponse>()
        assertTrue(renewed.token.isNotBlank())
        assertNotEquals(session.token, renewed.token, "a fresh access token is minted")

        // Sliding refresh keeps the same credential revision across the fresh chain.
        val chained = client.postJson("/api/v1/refresh", RefreshRequest(renewed.refreshToken))
        assertEquals(HttpStatusCode.OK, chained.status)
    }

    @Test
    fun `an access token presented as a refresh token is rejected`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("typ")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.token))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `garbage refresh token returns 401`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().postJson("/api/v1/refresh", RefreshRequest("not-a-jwt"))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a refresh token revoked at logout is rejected`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("revoked")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val logout = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest(refreshToken = session.refreshToken))
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `same-second password change invalidates old refresh and permits a new session`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("rotated")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val changed = client.put("/api/v1/users/$userId/password") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "new-password!", currentPassword = "pw"))
        }
        assertEquals(HttpStatusCode.NoContent, changed.status)
        // Pin the legacy timestamp inside the token's own iat second. The former
        // second-truncated comparison would accept this token deterministically.
        val sameSecond = com.auth0.jwt.JWT.decode(session.refreshToken).issuedAt.time + 500
        TestUsers.stampPasswordChangedAt(userId, sameSecond)

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, response.status)

        val current = client.login(email, "new-password!")
        assertEquals(
            HttpStatusCode.OK,
            client.postJson("/api/v1/refresh", RefreshRequest(current.refreshToken)).status,
            "a token minted after the revision increment must work immediately",
        )
    }

    @Test
    fun `repeated password changes advance distinct generations without a clock boundary`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("repeated-rotation")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val generation0 = client.login(email, "pw")
        val sameInstant = com.auth0.jwt.JWT.decode(generation0.refreshToken).issuedAt.time + 500
        assertEquals(0L, TestUsers.service.read(userId)?.credentialRevision)

        TestUsers.service.updatePassword(userId, hashPassword("generation-one", cost = 4))
        TestUsers.stampPasswordChangedAt(userId, sameInstant)
        assertEquals(1L, TestUsers.service.read(userId)?.credentialRevision)
        val generation1 = client.login(email, "generation-one")
        TestUsers.service.updatePassword(userId, hashPassword("generation-two", cost = 4))
        TestUsers.stampPasswordChangedAt(userId, sameInstant)

        assertEquals(2L, TestUsers.service.read(userId)?.credentialRevision)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.postJson("/api/v1/refresh", RefreshRequest(generation0.refreshToken)).status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.postJson("/api/v1/refresh", RefreshRequest(generation1.refreshToken)).status,
        )
        val generation2 = client.login(email, "generation-two")
        assertEquals(
            HttpStatusCode.OK,
            client.postJson("/api/v1/refresh", RefreshRequest(generation2.refreshToken)).status,
        )
    }

    @Test
    fun `a refresh token without a jti is rejected as malformed`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("nojti")
        val userId = TestUsers.seed(email = email, password = "pw")
        // Correctly signed and typed, but missing the jti — such a token could never be
        // blocklisted, so the refresh path refuses it outright.
        val jtiLess = com.auth0.jwt.JWT.create()
            .withAudience("flow-api")
            .withIssuer("http://0.0.0.0:8084/")
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .withClaim("email", email)
            .withClaim("userId", userId.toLong())
            .withClaim("typ", "refresh")
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))

        val response = jsonClient().postJson("/api/v1/refresh", RefreshRequest(jtiLess))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a legacy refresh token without a credential revision is rejected`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("legacy-revision")
        val userId = TestUsers.seed(email = email, password = "pw")
        val legacy = com.auth0.jwt.JWT.create()
            .withAudience("flow-api")
            .withIssuer("http://0.0.0.0:8084/")
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .withJWTId(java.util.UUID.randomUUID().toString())
            .withClaim("email", email)
            .withClaim("userId", userId.toLong())
            .withClaim("typ", "refresh")
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))

        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().postJson("/api/v1/refresh", RefreshRequest(legacy)).status,
        )
    }

    @Test
    fun `non-integral numeric security claims are rejected without a server error`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("fractional-revision")
        val userId = TestUsers.seed(email = email, password = "pw")
        fun tokenBuilder() = com.auth0.jwt.JWT.create()
            .withAudience("flow-api")
            .withIssuer("http://0.0.0.0:8084/")
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .withJWTId(java.util.UUID.randomUUID().toString())
            .withClaim("typ", "refresh")

        val fractionalRevision = tokenBuilder()
            .withClaim("userId", userId.toLong())
            .withClaim("credentialRevision", 0.5)
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))
        val fractionalUserId = tokenBuilder()
            .withClaim("userId", userId.toDouble() + 0.5)
            .withClaim("credentialRevision", 0L)
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))
        val stringRevision = tokenBuilder()
            .withClaim("userId", userId.toLong())
            .withClaim("credentialRevision", "0")
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().postJson("/api/v1/refresh", RefreshRequest(fractionalRevision)).status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().postJson("/api/v1/refresh", RefreshRequest(fractionalUserId)).status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().postJson("/api/v1/refresh", RefreshRequest(stringRevision)).status,
        )
    }

    @Test
    fun `refresh for a soft-deleted user is rejected`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("gone")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        TestUsers.softDelete(userId)

        val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
