package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.LogoutRequest
import ch.nokillswit.auth.MAX_GRACE_REUSES
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.flowusers.PasswordUpdateRequest
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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
    fun `a replay inside the grace window mints another pair and is audited as a grace re-mint`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("grace")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val rotated = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
        assertEquals(HttpStatusCode.OK, rotated.status)
        val renewed = rotated.body<LoginResponse>()
        assertNotEquals(session.refreshToken, renewed.refreshToken)

        // The first response "got lost": the client retries with the token it still holds.
        withAuditCapture { audit ->
            val retry = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
            assertEquals(HttpStatusCode.OK, retry.status)
            val regranted = retry.body<LoginResponse>()
            assertNotEquals(renewed.refreshToken, regranted.refreshToken, "the retry gets its own pair")
            assertNotNull(
                audit.awaitEvent {
                    it.formattedMessage.contains("refresh.replay_grace") && it.hasKeyValue("userId", userId.toLong())
                },
                "a grace re-mint is audited as refresh.replay_grace",
            )
            val me = client.get("/api/v1/teams") {
                header(HttpHeaders.Authorization, "Bearer ${regranted.token}")
            }
            assertEquals(HttpStatusCode.OK, me.status)
            assertEquals(
                HttpStatusCode.OK,
                client.postJson("/api/v1/refresh", RefreshRequest(regranted.refreshToken)).status,
                "the re-minted refresh token rotates normally",
            )
        }
    }

    @Test
    fun `a rotated refresh token replayed after the grace is rejected as replayed and audited`() = testApplication {
        // Grace 0 = strict one-time use; the window boundary itself is pinned with a clock in TokenBlocklistRotationTest.
        configureApp("security.refresh.replayGraceSeconds" to "0")
        startApplication()
        val email = uniqueEmail("replay")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val rotated = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
        assertEquals(HttpStatusCode.OK, rotated.status)
        val renewed = rotated.body<LoginResponse>()
        assertNotEquals(session.refreshToken, renewed.refreshToken)

        withAuditCapture { audit ->
            val replay = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
            assertEquals(HttpStatusCode.Unauthorized, replay.status)
            assertEquals("Refresh token already used", replay.body<ProblemDetail>().detail)
            assertNotNull(
                audit.awaitEvent {
                    it.formattedMessage.contains("refresh.rejected") &&
                        it.hasKeyValue("reason", "replayed") &&
                        it.hasKeyValue("userId", userId.toLong())
                },
                "a replayed refresh token is audited as refresh.rejected reason=replayed",
            )
        }

        // The replay does not take the live chain down with it: no family-wide revocation.
        val chained = client.postJson("/api/v1/refresh", RefreshRequest(renewed.refreshToken))
        assertEquals(HttpStatusCode.OK, chained.status)
        val me = client.get("/api/v1/teams") {
            header(HttpHeaders.Authorization, "Bearer ${renewed.token}")
        }
        assertEquals(HttpStatusCode.OK, me.status, "the access token minted by the rotation authenticates")
    }

    @Test
    fun `concurrent refreshes with one refresh token mint exactly one pair when the grace is off`() = testApplication {
        configureApp("security.refresh.replayGraceSeconds" to "0")
        startApplication()
        val email = uniqueEmail("race")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val statuses = coroutineScope {
            (1..8).map {
                async(Dispatchers.Default) {
                    client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken)).status
                }
            }.awaitAll()
        }

        assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "exactly one refresh wins: $statuses")
        assertEquals(7, statuses.count { it == HttpStatusCode.Unauthorized }, "the rest are rejected as replays: $statuses")
    }

    @Test
    fun `concurrent refreshes with one refresh token get one claim plus the capped grace`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("race-grace")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val responses = coroutineScope {
            (1..8).map {
                async(Dispatchers.Default) {
                    client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
                }
            }.awaitAll()
        }

        // The rotation is recorded inside the winner's transaction, before its commit, so every loser sees
        // it: the winner plus MAX_GRACE_REUSES grace re-mints succeed, the rest are replays.
        val statuses = responses.map { it.status }
        assertEquals(1 + MAX_GRACE_REUSES, statuses.count { it == HttpStatusCode.OK }, "$statuses")
        assertEquals(8 - 1 - MAX_GRACE_REUSES, statuses.count { it == HttpStatusCode.Unauthorized }, "$statuses")
        val refreshTokens = responses.filter { it.status == HttpStatusCode.OK }.map { it.body<LoginResponse>().refreshToken }
        assertEquals(refreshTokens.size, refreshTokens.toSet().size, "every success holds its own pair")
    }

    @Test
    fun `a rotated token replayed after logout is revoked, not a grace re-mint - whether the response was lost or not`() =
        testApplication {
            usePostgresTestcontainer()
            val email = uniqueEmail("grace-logout")
            TestUsers.seed(email = email, password = "pw")
            val client = jsonClient()

            suspend fun logout(accessToken: String, refreshToken: String) {
                val response = client.post("/api/v1/logout") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody(LogoutRequest(refreshToken = refreshToken))
                }
                assertEquals(HttpStatusCode.NoContent, response.status)
            }

            suspend fun assertReplayRevoked(refreshToken: String) = withAuditCapture { audit ->
                val replay = client.postJson("/api/v1/refresh", RefreshRequest(refreshToken))
                assertEquals(HttpStatusCode.Unauthorized, replay.status)
                assertNotNull(
                    audit.awaitEvent { it.formattedMessage.contains("refresh.rejected") && it.hasKeyValue("reason", "revoked") },
                    "after a logout the replay is reason=revoked, not a grace re-mint",
                )
            }

            // A: the rotation's response is lost, the client signs out with the token it still holds.
            val a = client.login(email, "pw")
            assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/refresh", RefreshRequest(a.refreshToken)).status)
            logout(a.token, a.refreshToken)
            assertReplayRevoked(a.refreshToken)

            // B: R1 -> R2, the client signs out with R2; a copy of R1 must not mint.
            val b = client.login(email, "pw")
            val rotated = client.postJson("/api/v1/refresh", RefreshRequest(b.refreshToken)).body<LoginResponse>()
            logout(rotated.token, rotated.refreshToken)
            assertReplayRevoked(b.refreshToken)
        }

    @Test
    fun `a logout presenting another user's refresh token ends that user's grace too`() = testApplication {
        usePostgresTestcontainer()
        val emailA = uniqueEmail("pair-a")
        val emailB = uniqueEmail("pair-b")
        TestUsers.seed(email = emailA, password = "pw")
        TestUsers.seed(email = emailB, password = "pw")
        val client = jsonClient()
        val a = client.login(emailA, "pw")
        val aRotated = client.postJson("/api/v1/refresh", RefreshRequest(a.refreshToken)).body<LoginResponse>()
        val b = client.login(emailB, "pw")

        // B's access token authenticates the call; the body carries A's live refresh token.
        val logout = client.post("/api/v1/logout") {
            header(HttpHeaders.Authorization, "Bearer ${b.token}")
            contentType(ContentType.Application.Json)
            setBody(LogoutRequest(refreshToken = aRotated.refreshToken))
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)

        // A's rotated-away token is no longer inside a grace: its replay is revoked, not re-minted.
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/refresh", RefreshRequest(a.refreshToken)).status)
    }

    @Test
    fun `a refresh token without an exp claim is rejected, the same token with one is accepted`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("noexp")
        val userId = TestUsers.seed(email = email, password = "pw")
        fun token(withExp: Boolean) = com.auth0.jwt.JWT.create()
            .withAudience("flow-api")
            .withIssuer("http://0.0.0.0:8084/")
            .withIssuedAt(java.util.Date())
            .apply { if (withExp) withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000)) }
            .withJWTId(java.util.UUID.randomUUID().toString())
            .withClaim("email", email)
            .withClaim("userId", userId.toLong())
            .withClaim("credentialRevision", 0L)
            .withClaim("typ", "refresh")
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))
        val client = jsonClient()

        // Positive control first: the builder, secret and claims are valid, so only the missing exp can explain a 401.
        assertEquals(HttpStatusCode.OK, client.postJson("/api/v1/refresh", RefreshRequest(token(withExp = true))).status)
        assertEquals(HttpStatusCode.Unauthorized, client.postJson("/api/v1/refresh", RefreshRequest(token(withExp = false))).status)
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

        withAuditCapture { audit ->
            val response = client.postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertNotNull(
                audit.awaitEvent { it.formattedMessage.contains("refresh.rejected") && it.hasKeyValue("reason", "revoked") },
                "a logout-revoked token stays reason=revoked, apart from a rotation replay",
            )
        }
    }

    @Test
    fun `same-second password change invalidates old refresh and permits a new session`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("rotated")
        val userId = TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw")

        val changed = client.put("/api/v1/flow-users/$userId/password") {
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
