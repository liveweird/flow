package ch.nokillswit

import ch.nokillswit.users.PasswordUpdateRequest
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every authenticated endpoint answers a uniform 401 to callers without a valid bearer. The sweep is
 * DRIVEN BY THE SPEC: every operation in documentation.yaml that does not opt out with `security: []`
 * (login, the MFA step, refresh, password reset — the sanctioned public surface) is probed without a
 * token, so a route that lands outside `authenticate {}` fails here the moment its spec entry exists
 * (and the conformance plugin fails any route that lands WITHOUT a spec entry).
 */
class AnonymousAccessTest {

    @Test
    fun `every operation the spec secures answers 401 without a token`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        val secured = OpenApiSpec.parsed.paths.flatMap { (path, item) ->
            item.readOperationsMap()
                .filter { (_, op) -> op.security?.isEmpty() != true }
                .map { (method, op) -> Triple(path, method, op) }
        }
        assertTrue(secured.size >= MIN_SECURED_OPERATIONS, "the sweep covers ${secured.size} operations — the spec shrank?")
        val leaks = mutableListOf<String>()
        for ((path, method, op) in secured) {
            val url = path.replace(PATH_PARAMETER, "1")
            val httpMethod = HttpMethod.parse(method.name)
            val response = client.request(url) {
                this.method = httpMethod
                if (httpMethod in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)) {
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            }
            if (response.status != HttpStatusCode.Unauthorized) leaks += "${method.name} $path (${op.operationId}) -> ${response.status}"
        }
        assertEquals(emptyList(), leaks, "operations reachable without a token")
    }

    @Test
    fun `a forged token signed with the wrong secret is 401`() = testApplication {
        usePostgresTestcontainer()
        val forged = com.auth0.jwt.JWT.create()
            .withAudience("flow-api")
            .withIssuer("http://0.0.0.0:8084/")
            .withClaim("email", "attacker@test")
            .withClaim("userId", 1L)
            .withArrayClaim("roles", arrayOf("ADMIN"))
            .withClaim("typ", "access")
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("not-the-server-secret"))

        val response = jsonClient().put("/api/v1/users/1/password") {
            header(HttpHeaders.Authorization, "Bearer $forged")
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "whatever-works"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `a correctly signed access token without a jti is 401`() = testApplication {
        usePostgresTestcontainer()
        // Signed with the real dev secret but missing the jti — un-blocklistable, so the
        // verifier rejects it rather than skipping the revocation check.
        val jtiLess = com.auth0.jwt.JWT.create()
            .withAudience("flow-api")
            .withIssuer("http://0.0.0.0:8084/")
            .withClaim("email", "nobody@test")
            .withClaim("userId", 1L)
            .withArrayClaim("roles", arrayOf<String>())
            .withClaim("typ", "access")
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 60_000))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256("secret"))

        val response = jsonClient().put("/api/v1/users/1/password") {
            header(HttpHeaders.Authorization, "Bearer $jtiLess")
            contentType(ContentType.Application.Json)
            setBody(PasswordUpdateRequest(password = "whatever-works"))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private companion object {
        /** `{id}`-style template segments; every id becomes `1` — authentication runs before any lookup. */
        val PATH_PARAMETER = Regex("\\{[^}]+}")

        /** The secured surface after the Flow port (15 paths / 16 operations) — a shrink is worth a look. */
        const val MIN_SECURED_OPERATIONS = 16
    }
}
