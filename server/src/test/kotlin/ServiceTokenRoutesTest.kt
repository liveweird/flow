package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.integration.SERVICE_TOKEN_PATTERN
import ch.nokillswit.integration.ServiceToken
import ch.nokillswit.integration.ServiceTokenCreateRequest
import ch.nokillswit.integration.ServiceTokenCreateResponse
import ch.nokillswit.integration.ServiceTokenListResponse
import ch.nokillswit.integration.ServiceTokenService
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ADMIN service-token management API (V48): ADMIN only with reads included, the guard before the id lookup and the body
 * decode, the secret returned once, the live-name index as a 409, terminal revoke. Tokens are SHARED suite state - every test
 * mints UNIQUE names.
 */
class ServiceTokenRoutesTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private val expiredNameDetail =
        "A service token with this name already exists and is not revoked; an expired token keeps its name until it is revoked"

    private suspend fun HttpClient.issue(name: String, expiresAt: Long? = null): ServiceTokenCreateResponse {
        val response = postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(name, expiresAt))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    @Test
    fun `list is ADMIN only, newest first, and a USER gets 403`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("st-list", FlowUserRole.ADMIN)
        val first = admin.issue(name("list-a")).token
        val second = admin.issue(name("list-b")).token
        val items = admin.get("/api/v1/service-tokens").also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<ServiceTokenListResponse>().items
        assertTrue(items.indexOfFirst { it.id == second.id } < items.indexOfFirst { it.id == first.id }, "newest first")
        assertEquals(HttpStatusCode.Forbidden, seededClient("st-list-user", FlowUserRole.USER).get("/api/v1/service-tokens").status)
    }

    @Test
    fun `create returns the secret once with a Location, and the list never carries it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("st-create", FlowUserRole.ADMIN)
        val tokenName = name("create")
        val response = admin.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(tokenName))
        assertEquals(HttpStatusCode.Created, response.status)
        val created = response.body<ServiceTokenCreateResponse>()
        assertEquals("/api/v1/service-tokens/${created.token.id}", response.headers["Location"])
        assertTrue(SERVICE_TOKEN_PATTERN.matches(created.secret))
        assertEquals(tokenName, created.token.name)
        assertFalse(created.token.revoked)
        assertNull(created.token.expiresAt)
        assertNull(created.token.lastUsedAt)
        // The secret shows up in this response only: never in the list or the single read.
        val listed = admin.get("/api/v1/service-tokens").body<String>()
        assertTrue(tokenName in listed)
        assertFalse(created.secret in listed)
        assertFalse(created.secret in admin.get("/api/v1/service-tokens/${created.token.id}").body<String>())
    }

    @Test
    fun `create with an expiry keeps it and a blank name, a long name and a past expiry are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("st-validate", FlowUserRole.ADMIN)
        val expiresAt = System.currentTimeMillis() + 86_400_000L
        assertEquals(expiresAt, admin.issue(name("expiring"), expiresAt).token.expiresAt)
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest("   ")).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest("x".repeat(101))).status,
        )
        val past = admin.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(name("past"), System.currentTimeMillis() - 1_000))
        assertEquals(HttpStatusCode.BadRequest, past.status)
        assertTrue(past.body<ProblemDetail>().detail!!.contains("expiresAt"))
    }

    @Test
    fun `a non-admin gets 403 for a missing id and for a malformed body alike`() = testApplication {
        usePostgresTestcontainer()
        val user = seededClient("st-forbidden", FlowUserRole.USER)
        val admin = seededClient("st-forbidden-admin", FlowUserRole.ADMIN)
        val id = admin.issue(name("forbidden")).token.id
        assertEquals(HttpStatusCode.Forbidden, user.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(name("x"))).status)
        val malformed = user.post("/api/v1/service-tokens") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/service-tokens/999999").status)
        assertEquals(HttpStatusCode.Forbidden, user.get("/api/v1/service-tokens/$id").status)
        assertEquals(HttpStatusCode.Forbidden, user.post("/api/v1/service-tokens/999999/revoke").status)
        assertEquals(HttpStatusCode.Forbidden, user.post("/api/v1/service-tokens/$id/revoke").status)
        // The token was never revoked by the denied call.
        assertFalse(admin.get("/api/v1/service-tokens/$id").body<ServiceToken>().revoked)
    }

    @Test
    fun `a live name clash is 409 case-insensitively and the name is reusable after revoke`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("st-clash", FlowUserRole.ADMIN)
        val tokenName = name("Clash")
        val first = admin.issue(tokenName).token
        val clash = admin.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(tokenName.uppercase()))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertEquals(expiredNameDetail, clash.body<ProblemDetail>().detail)
        assertEquals(HttpStatusCode.NoContent, admin.post("/api/v1/service-tokens/${first.id}/revoke").status)
        val reused = admin.issue(tokenName).token
        assertTrue(reused.id != first.id)
    }

    @Test
    fun `an expired but unrevoked token still holds its name until it is revoked`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("st-expired")
        val adminId = TestUsers.seed(adminEmail, "pw-123456789")
        val admin = authedClient(adminEmail, "pw-123456789")
        val tokenName = name("expired")
        // A service whose clock lies in the past stores an expiry that has already passed.
        val past = System.currentTimeMillis() - 2 * 3_600_000L
        val expired = ServiceTokenService(sharedDatabaseForTests(), clock = { past }).create(tokenName, past + 3_600_000L, adminId).first
        assertFalse(expired.revoked)
        val clash = admin.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(tokenName))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertEquals(expiredNameDetail, clash.body<ProblemDetail>().detail)
        assertEquals(HttpStatusCode.NoContent, admin.post("/api/v1/service-tokens/${expired.id}/revoke").status)
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/v1/service-tokens", ServiceTokenCreateRequest(tokenName)).status)
    }

    @Test
    fun `get answers 200 for a token and 404 for an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("st-get", FlowUserRole.ADMIN)
        val created = admin.issue(name("get")).token
        val read = admin.get("/api/v1/service-tokens/${created.id}")
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals(created, read.body<ServiceToken>())
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/service-tokens/999999").status)
    }

    @Test
    fun `revoke is terminal - 204 once, 409 on a repeat, 404 for an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("st-revoke", FlowUserRole.ADMIN)
        val created = admin.issue(name("revoke")).token
        assertEquals(HttpStatusCode.NoContent, admin.post("/api/v1/service-tokens/${created.id}/revoke").status)
        val revoked = admin.get("/api/v1/service-tokens/${created.id}").body<ServiceToken>()
        assertTrue(revoked.revoked)
        assertNotNull(revoked.revokedAt)
        assertNotNull(revoked.revokedByName)
        assertEquals(HttpStatusCode.Conflict, admin.post("/api/v1/service-tokens/${created.id}/revoke").status)
        assertEquals(HttpStatusCode.NotFound, admin.post("/api/v1/service-tokens/999999/revoke").status)
    }
}
