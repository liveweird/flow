package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.flowusers.Feature
import ch.nokillswit.flowusers.FlowUserFeaturesUpdateRequest
import ch.nokillswit.flowusers.FlowUserLanguageUpdateRequest
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.flowusers.FlowUserUpdateRequest
import ch.nokillswit.flowusers.PasswordUpdateRequest
import ch.nokillswit.plugins.ProblemDetail
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The per-request access check (`TokenBlocklistService.accessVerdict`, `plugins/Security.kt`; `security.md` "JWT model"): an
 * access token carries the user's `credentialRevision`, and every authenticated request compares it, the token's `roles`
 * and the `jti` with the stored row in ONE statement. A delete, a role change, a password change or a logout therefore ends
 * the token on the very next request - not at its 15-minute expiry. Each case holds BOTH tokens of the session, like the SPA.
 */
class StaleAccessTokenTest {

    private val password = "pw-123456789"

    private suspend fun HttpClient.session(email: String): LoginResponse = login(email, password).body()

    private suspend fun HttpClient.getWith(path: String, token: String): HttpResponse =
        get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private suspend fun HttpClient.refresh(session: LoginResponse): HttpResponse =
        postJson("/api/v1/refresh", RefreshRequest(session.refreshToken))

    private fun userUpdate(email: String, roles: List<FlowUserRole>) =
        FlowUserUpdateRequest(name = "Test", email = email, roles = roles)

    private fun rejected(reason: String, userId: UInt): (ch.qos.logback.classic.spi.ILoggingEvent) -> Boolean = {
        it.formattedMessage.contains("access.rejected") && it.hasKeyValue("reason", reason) && it.hasKeyValue("userId", userId.toLong())
    }

    @Test
    fun `the access token carries the credential revision of the stored row`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("stale-claim-admin", FlowUserRole.ADMIN)
        val email = uniqueEmail("stale-claim")
        val id = TestUsers.seed(email = email, password = password, role = FlowUserRole.USER)
        val client = jsonClient()

        fun revisionClaim(token: String) = JWT.decode(token).getClaim("credentialRevision").asLong()
        val before = client.session(email)
        assertEquals(0L, revisionClaim(before.token))
        assertEquals(revisionClaim(before.token), revisionClaim(before.refreshToken), "both tokens of the pair carry it")

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/flow-users/$id/password", PasswordUpdateRequest(password = "admin-chosen-pw")).status,
        )
        val stored = assertNotNull(TestUsers.service.read(id)).credentialRevision
        assertEquals(1L, stored)
        val after = client.login(email, "admin-chosen-pw").body<LoginResponse>()
        assertEquals(stored, revisionClaim(after.token))
    }

    @Test
    fun `a deleted account's access token is 401 on the next request and its refresh is rejected`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("stale-del-admin", FlowUserRole.ADMIN)
        val email = uniqueEmail("stale-del")
        val id = TestUsers.seed(email = email, password = password, role = FlowUserRole.USER)
        val client = jsonClient()
        val session = client.session(email)
        assertEquals(HttpStatusCode.OK, client.getWith("/api/v1/teams", session.token).status)

        withAuditCapture { audit ->
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/flow-users/$id").status)

            val stale = client.getWith("/api/v1/teams", session.token)
            assertEquals(HttpStatusCode.Unauthorized, stale.status)
            assertEquals(
                "Access token is no longer valid - refresh or sign in again",
                stale.body<ProblemDetail>().detail,
                "one text for every rejection reason",
            )
            assertNotNull(audit.awaitEvent(rejected("user_gone", id)), "audited access.rejected reason=user_gone")
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(session).status, "the refresh re-reads the user: signed out")
        }
    }

    @Test
    fun `a demotion ends the old token, the refresh succeeds with the new roles, and a promotion does the same`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("stale-role-admin", FlowUserRole.ADMIN)
        val email = uniqueEmail("stale-role")
        val id = TestUsers.seed(email = email, password = password, role = FlowUserRole.ADMIN)
        val client = jsonClient()
        val session = client.session(email)
        assertEquals(HttpStatusCode.OK, client.getWith("/api/v1/flow-users", session.token).status)

        withAuditCapture { audit ->
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson("/api/v1/flow-users/$id", userUpdate(email, emptyList())).status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.getWith("/api/v1/teams", session.token).status)
            assertNotNull(audit.awaitEvent(rejected("roles_changed", id)), "audited access.rejected reason=roles_changed")

            // The refresh token's revision is unchanged by a role change: the refresh succeeds and mints the NEW roles.
            val refreshed = client.refresh(session)
            assertEquals(HttpStatusCode.OK, refreshed.status)
            val demoted = refreshed.body<LoginResponse>()
            assertEquals(emptyList(), demoted.roles)
            assertEquals(HttpStatusCode.OK, client.getWith("/api/v1/teams", demoted.token).status)
            assertEquals(HttpStatusCode.Forbidden, client.getWith("/api/v1/flow-users", demoted.token).status)

            // And back up: the regular-user token dies at the next request, the refresh brings the ADMIN role.
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson("/api/v1/flow-users/$id", userUpdate(email, listOf(FlowUserRole.ADMIN))).status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.getWith("/api/v1/teams", demoted.token).status)
            val promoted = client.refresh(demoted).body<LoginResponse>()
            assertEquals(listOf(FlowUserRole.ADMIN), promoted.roles)
            assertEquals(HttpStatusCode.OK, client.getWith("/api/v1/flow-users", promoted.token).status)
        }
    }

    @Test
    fun `an admin's password reset ends the target's access token and refresh token`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("stale-reset-admin", FlowUserRole.ADMIN)
        val email = uniqueEmail("stale-reset")
        val id = TestUsers.seed(email = email, password = password, role = FlowUserRole.USER)
        val client = jsonClient()
        val session = client.session(email)

        withAuditCapture { audit ->
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson("/api/v1/flow-users/$id/password", PasswordUpdateRequest(password = "admin-chosen-pw")).status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.getWith("/api/v1/teams", session.token).status)
            assertNotNull(
                audit.awaitEvent(rejected("credential_changed", id)),
                "audited access.rejected reason=credential_changed",
            )
            assertEquals(HttpStatusCode.Unauthorized, client.refresh(session).status)
        }
    }

    @Test
    fun `changing your own password ends the token you changed it with`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("stale-self")
        val id = TestUsers.seed(email = email, password = password, role = FlowUserRole.USER)
        val client = jsonClient()
        val session = client.session(email)

        val changed = client.put("/api/v1/flow-users/$id/password") {
            header(HttpHeaders.Authorization, "Bearer ${session.token}")
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "brand-new-password", currentPassword = password))
        }
        assertEquals(HttpStatusCode.NoContent, changed.status)
        assertEquals(HttpStatusCode.Unauthorized, client.getWith("/api/v1/teams", session.token).status)
        assertEquals(HttpStatusCode.Unauthorized, client.refresh(session).status)
        assertEquals(HttpStatusCode.OK, client.login(email, "brand-new-password").status)
    }

    @Test
    fun `features, name, email and language changes leave the token working`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("stale-keep-admin", FlowUserRole.ADMIN)
        val email = uniqueEmail("stale-keep")
        val id = TestUsers.seed(email = email, password = password, role = FlowUserRole.USER)
        val client = jsonClient()
        val session = client.session(email)

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/flow-users/$id/features", FlowUserFeaturesUpdateRequest(emptyList<Feature>())).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/flow-users/$id", userUpdate(uniqueEmail("stale-keep2"), emptyList()).copy(name = "Renamed")).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/flow-users/$id/language", FlowUserLanguageUpdateRequest("pl")).status,
        )
        assertEquals(HttpStatusCode.OK, client.getWith("/api/v1/teams", session.token).status)
    }

    @Test
    fun `a signed access token without the credentialRevision claim is malformed and 401`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("stale-legacy")
        val id = TestUsers.seed(email = email, password = password, role = FlowUserRole.USER)
        val client = jsonClient()

        // The dev secret and the minting shape of Tokens.kt, minus (or plus) the one claim under test.
        fun mint(revision: Long?): String = JWT.create()
            .withAudience("flow-api")
            .withIssuer("http://0.0.0.0:8084/")
            .withJWTId(UUID.randomUUID().toString())
            .withClaim("email", email)
            .withClaim("userId", id.toLong())
            .withArrayClaim("roles", arrayOf<String>())
            .withClaim("typ", "access")
            .apply { revision?.let { withClaim("credentialRevision", it) } }
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("secret"))

        assertEquals(HttpStatusCode.OK, client.getWith("/api/v1/teams", mint(0)).status, "the control: same token WITH the claim")
        withAuditCapture { audit ->
            assertEquals(HttpStatusCode.Unauthorized, client.getWith("/api/v1/teams", mint(null)).status)
            assertNotNull(audit.awaitEvent(rejected("malformed", id)), "audited access.rejected reason=malformed")
        }
    }

    @Test
    fun `a logged-out access token is 401 and the rejection is audited as revoked`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("stale-logout")
        val id = TestUsers.seed(email = email, password = password, role = FlowUserRole.USER)
        val client = jsonClient()
        val session = client.session(email)

        withAuditCapture { audit ->
            val logout = client.post("/api/v1/logout") { header(HttpHeaders.Authorization, "Bearer ${session.token}") }
            assertEquals(HttpStatusCode.NoContent, logout.status)
            assertEquals(HttpStatusCode.Unauthorized, client.getWith("/api/v1/teams", session.token).status)
            assertNotNull(audit.awaitEvent(rejected("revoked", id)), "audited access.rejected reason=revoked")
        }
    }
}
