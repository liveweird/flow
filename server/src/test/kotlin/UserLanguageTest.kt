package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.UserCreateRequest
import ch.nokillswit.users.UserLanguageUpdateRequest
import ch.nokillswit.users.UserResponse
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserUpdateRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The per-user language (V18, Lettuce's V61 model): the dedicated self-or-admin PUT, the
 * create-time default, and the whole-user PUT's deliberate hands-off. Email rendering in
 * the stored language is pinned by PasswordResetTest/MfaLoginTest; the login response's
 * `language` by LoginTest.
 */
class UserLanguageTest {

    private fun langPath(id: UInt) = "/api/v1/users/$id/language"

    @Test
    fun `a user sets their own language - en default, idempotent 204, audited once`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("lang-self")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)
        val client = authedClient(email, "pw-123456789")
        val admin = seededClient("lang-admin", role = UserRole.ADMIN)

        assertEquals("en", admin.get("/api/v1/users/$id").body<UserResponse>().language)

        withAuditCapture { capture ->
            assertEquals(
                HttpStatusCode.NoContent,
                client.putJson(langPath(id), UserLanguageUpdateRequest("pl")).status,
            )
            assertEquals("pl", admin.get("/api/v1/users/$id").body<UserResponse>().language)
            val event = capture.awaitEvent { it.message == "user.language_changed" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("targetUserId", id.toLong()))
            assertTrue(event.hasKeyValue("from", "en"))
            assertTrue(event.hasKeyValue("to", "pl"))

            // A same-value re-PUT is 204 again and stays audit-silent.
            assertEquals(
                HttpStatusCode.NoContent,
                client.putJson(langPath(id), UserLanguageUpdateRequest("pl")).status,
            )
            val events = capture.events.count { it.message == "user.language_changed" }
            assertEquals(1, events, "a same-value re-PUT must not re-audit")
        }
    }

    @Test
    fun `an admin may set another user's language - a stranger gets 403 even with a bogus code`() = testApplication {
        usePostgresTestcontainer()
        val targetEmail = uniqueEmail("lang-target")
        val targetId = TestUsers.seed(email = targetEmail, password = "pw-123456789", role = UserRole.USER)
        val admin = seededClient("lang-admin2", role = UserRole.ADMIN)
        val stranger = seededClient("lang-stranger", role = UserRole.USER)

        assertEquals(HttpStatusCode.NoContent, admin.putJson(langPath(targetId), UserLanguageUpdateRequest("pl")).status)

        // 403 wins over 400: the guard runs before the payload is even validated.
        assertEquals(
            HttpStatusCode.Forbidden,
            stranger.putJson(langPath(targetId), UserLanguageUpdateRequest("xx")).status,
        )
    }

    @Test
    fun `an unsupported code is 400 - unknown and soft-deleted targets are 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lang-admin3", role = UserRole.ADMIN)
        val email = uniqueEmail("lang-gone")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)

        val bad = admin.putJson(langPath(id), UserLanguageUpdateRequest("xx"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.body<ProblemDetail>().detail!!.contains("Unsupported language"))

        assertEquals(HttpStatusCode.NotFound, admin.putJson(langPath(999999u), UserLanguageUpdateRequest("pl")).status)
        TestUsers.softDelete(id)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(langPath(id), UserLanguageUpdateRequest("pl")).status)
    }

    @Test
    fun `create accepts a language, defaults to en, rejects an unsupported one`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lang-create", role = UserRole.ADMIN)

        val pl = admin.postJson(
            "/api/v1/users",
            UserCreateRequest(name = "PL Person", email = uniqueEmail("lang-pl"), password = "initial-pass-123", language = "pl"),
        )
        assertEquals(HttpStatusCode.Created, pl.status)
        assertEquals("pl", pl.body<UserResponse>().language)

        val default = admin.postJson(
            "/api/v1/users",
            UserCreateRequest(name = "EN Person", email = uniqueEmail("lang-en"), password = "initial-pass-123"),
        )
        assertEquals("en", default.body<UserResponse>().language)

        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson(
                "/api/v1/users",
                UserCreateRequest(name = "XX Person", email = uniqueEmail("lang-xx"), password = "initial-pass-123", language = "xx"),
            ).status,
        )
    }

    @Test
    fun `the whole-user PUT leaves the language unchanged`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lang-put", role = UserRole.ADMIN)
        val email = uniqueEmail("lang-keep")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = UserRole.USER)
        admin.putJson(langPath(id), UserLanguageUpdateRequest("pl"))

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/users/$id", UserUpdateRequest(name = "Renamed", email = email, roles = emptyList())).status,
        )
        assertEquals("pl", admin.get("/api/v1/users/$id").body<UserResponse>().language)
    }

    @Test
    fun `the user wire shapes decode with their defaults absent`() {
        // Pure decode checks: absent optional fields resolve to the documented defaults
        // (language "en", baseline role) — the same defaulting the routes rely on.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val create = json.decodeFromString<UserCreateRequest>(
            """{"name":"A","email":"a@b","password":"initial-pass-123"}""",
        )
        assertEquals(null, create.roles)
        assertEquals(null, create.language)
        val user = json.decodeFromString<ch.nokillswit.users.User>(
            """{"name":"A","email":"a@b","passwordHash":"h"}""",
        )
        assertEquals("en", user.language)
        assertEquals(UserRole.USER, user.role)
        assertEquals(0, user.passwordChangedAt)
        assertTrue(user.disabledFeatures.isEmpty())
    }

    @Test
    fun `the language PUT requires authentication`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().putJson(langPath(1u), UserLanguageUpdateRequest("pl")).status,
        )
    }
}
