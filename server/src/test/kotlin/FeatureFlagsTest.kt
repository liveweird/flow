package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.users.Feature
import ch.nokillswit.users.UserFeaturesUpdateRequest
import ch.nokillswit.users.UserPageResponse
import ch.nokillswit.users.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Per-user feature flags (V12) end to end: the PUT /users/{id}/features matrix, the
 * login/refresh claim roundtrip, and the users-list feature/featureEnabled filter pair.
 * MFA is the only Feature today (login-scoped — its behavior lives in MfaLoginTest);
 * `requireFeatureEnabled` awaits its first area-gating consumer. The
 * `user.features_changed` audit event lives in AuditTest.
 */
class FeatureFlagsTest {

    private suspend fun HttpClient.setFlags(userId: UInt, vararg features: Feature) =
        putJson("/api/v1/users/$userId/features", UserFeaturesUpdateRequest(features.toList()))

    @Test
    fun `changing flags without a token is 401`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().putJson("/api/v1/users/1/features", UserFeaturesUpdateRequest(emptyList()))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `admin replaces, reads back, and clears a user's disabled set - idempotent wholesale PUT`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ff-admin", role = ch.nokillswit.users.UserRole.ADMIN)
        val targetId = TestUsers.seed(email = uniqueEmail("ff-target"), password = "pw-123456789")

        assertEquals(HttpStatusCode.NoContent, admin.setFlags(targetId, Feature.MFA).status)
        assertEquals(
            listOf(Feature.MFA),
            admin.get("/api/v1/users/$targetId").body<UserResponse>().disabledFeatures,
        )
        // Wholesale replace is idempotent — a same-set re-PUT is 204 again, not a 409 transition.
        assertEquals(HttpStatusCode.NoContent, admin.setFlags(targetId, Feature.MFA).status)
        // An empty array re-enables everything.
        assertEquals(HttpStatusCode.NoContent, admin.setFlags(targetId).status)
        assertEquals(emptyList(), admin.get("/api/v1/users/$targetId").body<UserResponse>().disabledFeatures)
    }

    @Test
    fun `changing flags is ADMIN-only, self-change allowed, unknown or soft-deleted target 404, junk feature 400`() =
        testApplication {
            usePostgresTestcontainer()
            val adminEmail = uniqueEmail("ff-admin2")
            val adminId = TestUsers.seed(email = adminEmail, password = "pw")
            val admin = authedClient(adminEmail, "pw")
            val plainEmail = uniqueEmail("ff-plain")
            val plainId = TestUsers.seed(email = plainEmail, password = "pw", role = ch.nokillswit.users.UserRole.USER)
            val plain = authedClient(plainEmail, "pw")

            assertEquals(HttpStatusCode.Forbidden, plain.setFlags(plainId, Feature.MFA).status)

            // Self-change is deliberately allowed: the users routes are never feature-gated,
            // so an admin can always adjust their own flags back.
            assertEquals(HttpStatusCode.NoContent, admin.setFlags(adminId, Feature.MFA).status)
            assertEquals(HttpStatusCode.NoContent, admin.setFlags(adminId).status)

            assertEquals(HttpStatusCode.NotFound, admin.setFlags(999_999_999u, Feature.MFA).status)
            val goneId = TestUsers.seed(email = uniqueEmail("ff-gone"), password = "pw-123456789")
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/users/$goneId").status)
            assertEquals(HttpStatusCode.NotFound, admin.setFlags(goneId, Feature.MFA).status)

            val junk = admin.put("/api/v1/users/$plainId/features") {
                contentType(ContentType.Application.Json)
                setBody("""{"disabledFeatures":["WIZARDRY"]}""")
            }
            assertEquals(HttpStatusCode.BadRequest, junk.status)
        }

    @Test
    fun `login and refresh carry the current set - a change lands at the next refresh`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ff-admin3", role = ch.nokillswit.users.UserRole.ADMIN)
        val email = uniqueEmail("ff-sliding")
        val userId = TestUsers.seed(email = email, password = "pw-123456789")

        val json = jsonClient()
        val firstLogin = json.postJson("/api/v1/login", LoginRequest(email, "pw-123456789"))
            .body<LoginResponse>()
        // A fresh user's only disabled flag is the inverted-default MFA (opt-in).
        assertEquals(listOf(Feature.MFA), firstLogin.disabledFeatures)

        assertEquals(HttpStatusCode.NoContent, admin.setFlags(userId).status)

        // A refresh with the PRE-change refresh token re-reads the user and carries the new
        // set — but the now-MFA-enabled account still refreshes fine (MFA gates login only).
        val refreshed = json.postJson("/api/v1/refresh", RefreshRequest(firstLogin.refreshToken))
            .body<LoginResponse>()
        assertEquals(emptyList(), refreshed.disabledFeatures)
    }

    @Test
    fun `the users list filters by feature state and rejects a lone pair half`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("ff-admin4", role = ch.nokillswit.users.UserRole.ADMIN)
        val prefix = "FF-${UUID.randomUUID()}"
        val offId = TestUsers.seed(email = uniqueEmail("ff-off"), password = "pw-123456789", name = "$prefix Off")
        val onId = TestUsers.seed(email = uniqueEmail("ff-on"), password = "pw-123456789", name = "$prefix On")
        // MFA is inverted-default: both start disabled — clear onId's row to make it enabled.
        assertEquals(HttpStatusCode.NoContent, admin.setFlags(offId, Feature.MFA).status)
        assertEquals(HttpStatusCode.NoContent, admin.setFlags(onId).status)

        val disabled = admin.get("/api/v1/users") {
            parameter("name", prefix)
            parameter("feature", "MFA")
            parameter("featureEnabled", "false")
        }.body<UserPageResponse>()
        assertEquals(listOf(offId), disabled.items.map { it.id })
        assertEquals(listOf(Feature.MFA), disabled.items.single().disabledFeatures)

        val enabled = admin.get("/api/v1/users") {
            parameter("name", prefix)
            parameter("feature", "MFA")
            parameter("featureEnabled", "true")
        }.body<UserPageResponse>()
        assertEquals(listOf(onId), enabled.items.map { it.id })

        // The pair rule: a lone half (either one) is 400, as is an unknown feature name.
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.get("/api/v1/users") { parameter("feature", "MFA") }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.get("/api/v1/users") { parameter("featureEnabled", "true") }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.get("/api/v1/users") {
                parameter("feature", "WIZARDRY")
                parameter("featureEnabled", "true")
            }.status,
        )
    }
}
