package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.flowusers.FlowUserCreateRequest
import ch.nokillswit.flowusers.FlowUserLanguageUpdateRequest
import ch.nokillswit.flowusers.FlowUserResponse
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.flowusers.FlowUserUpdateRequest
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
class FlowUserLanguageTest {

    private fun langPath(id: UInt) = "/api/v1/flow-users/$id/language"

    @Test
    fun `a user sets their own language - en default, idempotent 204, audited once`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("lang-self")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = FlowUserRole.USER)
        val client = authedClient(email, "pw-123456789")
        val admin = seededClient("lang-admin", role = FlowUserRole.ADMIN)

        assertEquals("en", admin.get("/api/v1/flow-users/$id").body<FlowUserResponse>().language)

        withAuditCapture { capture ->
            assertEquals(
                HttpStatusCode.NoContent,
                client.putJson(langPath(id), FlowUserLanguageUpdateRequest("pl")).status,
            )
            assertEquals("pl", admin.get("/api/v1/flow-users/$id").body<FlowUserResponse>().language)
            val event = capture.awaitEvent { it.message == "user.language_changed" }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("targetUserId", id.toLong()))
            assertTrue(event.hasKeyValue("from", "en"))
            assertTrue(event.hasKeyValue("to", "pl"))

            // A same-value re-PUT is 204 again and stays audit-silent.
            assertEquals(
                HttpStatusCode.NoContent,
                client.putJson(langPath(id), FlowUserLanguageUpdateRequest("pl")).status,
            )
            val events = capture.events.count { it.message == "user.language_changed" }
            assertEquals(1, events, "a same-value re-PUT must not re-audit")
        }
    }

    @Test
    fun `an admin may set another user's language - a stranger gets 403 even with a bogus code`() = testApplication {
        usePostgresTestcontainer()
        val targetEmail = uniqueEmail("lang-target")
        val targetId = TestUsers.seed(email = targetEmail, password = "pw-123456789", role = FlowUserRole.USER)
        val admin = seededClient("lang-admin2", role = FlowUserRole.ADMIN)
        val stranger = seededClient("lang-stranger", role = FlowUserRole.USER)

        assertEquals(HttpStatusCode.NoContent, admin.putJson(langPath(targetId), FlowUserLanguageUpdateRequest("pl")).status)

        // 403 wins over 400: the guard runs before the payload is even validated.
        assertEquals(
            HttpStatusCode.Forbidden,
            stranger.putJson(langPath(targetId), FlowUserLanguageUpdateRequest("xx")).status,
        )
    }

    @Test
    fun `an unsupported code is 400 - unknown and soft-deleted targets are 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lang-admin3", role = FlowUserRole.ADMIN)
        val email = uniqueEmail("lang-gone")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = FlowUserRole.USER)

        val bad = admin.putJson(langPath(id), FlowUserLanguageUpdateRequest("xx"))
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.body<ProblemDetail>().detail!!.contains("Unsupported language"))

        assertEquals(HttpStatusCode.NotFound, admin.putJson(langPath(999999u), FlowUserLanguageUpdateRequest("pl")).status)
        TestUsers.softDelete(id)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(langPath(id), FlowUserLanguageUpdateRequest("pl")).status)
    }

    @Test
    fun `create accepts a language, defaults to en, rejects an unsupported one`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lang-create", role = FlowUserRole.ADMIN)

        val pl = admin.postJson(
            "/api/v1/flow-users",
            FlowUserCreateRequest(name = "PL Person", email = uniqueEmail("lang-pl"), password = "initial-pass-123", language = "pl"),
        )
        assertEquals(HttpStatusCode.Created, pl.status)
        assertEquals("pl", pl.body<FlowUserResponse>().language)

        val default = admin.postJson(
            "/api/v1/flow-users",
            FlowUserCreateRequest(name = "EN Person", email = uniqueEmail("lang-en"), password = "initial-pass-123"),
        )
        assertEquals("en", default.body<FlowUserResponse>().language)

        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson(
                "/api/v1/flow-users",
                FlowUserCreateRequest(name = "XX Person", email = uniqueEmail("lang-xx"), password = "initial-pass-123", language = "xx"),
            ).status,
        )
    }

    @Test
    fun `the whole-user PUT leaves the language unchanged`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lang-put", role = FlowUserRole.ADMIN)
        val email = uniqueEmail("lang-keep")
        val id = TestUsers.seed(email = email, password = "pw-123456789", role = FlowUserRole.USER)
        admin.putJson(langPath(id), FlowUserLanguageUpdateRequest("pl"))

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/flow-users/$id", FlowUserUpdateRequest(name = "Renamed", email = email, roles = emptyList())).status,
        )
        assertEquals("pl", admin.get("/api/v1/flow-users/$id").body<FlowUserResponse>().language)
    }

    @Test
    fun `the user wire shapes decode with their defaults absent`() {
        // Pure decode checks: absent optional fields resolve to the documented defaults
        // (language "en", baseline role) — the same defaulting the routes rely on.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val create = json.decodeFromString<FlowUserCreateRequest>(
            """{"name":"A","email":"a@b","password":"initial-pass-123"}""",
        )
        assertEquals(null, create.roles)
        assertEquals(null, create.language)
        val user = json.decodeFromString<ch.nokillswit.flowusers.FlowUser>(
            """{"name":"A","email":"a@b","passwordHash":"h"}""",
        )
        assertEquals("en", user.language)
        assertEquals(FlowUserRole.USER, user.role)
        assertEquals(0, user.passwordChangedAt)
        assertTrue(user.disabledFeatures.isEmpty())
    }

    @Test
    fun `the language PUT requires authentication`() = testApplication {
        usePostgresTestcontainer()
        assertEquals(
            HttpStatusCode.Unauthorized,
            jsonClient().putJson(langPath(1u), FlowUserLanguageUpdateRequest("pl")).status,
        )
    }
}
