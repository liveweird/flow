package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.users.UserRole
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LoginTest {

    @Test
    fun `login returns 200 with token and future expiresAt`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("alice")
        TestUsers.seed(email = email, password = "correct-horse")

        val before = System.currentTimeMillis()
        val response = jsonClient().postJson("/api/v1/login", LoginRequest(email, "correct-horse"))

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<LoginResponse>()
        assertTrue(body.token.isNotBlank())
        assertTrue(body.expiresAt > before, "expiresAt should be in the future, got ${body.expiresAt}")
        assertEquals("en", body.language, "the stored language rides the login response (seed default)")
    }

    @Test
    fun `token has expected claims and verifies with the configured secret`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("bob")
        TestUsers.seed(email = email, password = "pw")

        val token = jsonClient().postJson("/api/v1/login", LoginRequest(email, "pw")).body<LoginResponse>().token

        val decoded = JWT.require(Algorithm.HMAC256("secret"))
            .withAudience("flow-api")
            .withIssuer("http://0.0.0.0:8084/")
            .build()
            .verify(token)

        assertEquals(email, decoded.getClaim("email").asString())
        assertEquals("access", decoded.getClaim("typ").asString())
        assertNotNull(decoded.id, "token should carry a jti claim for revocation support")
    }

    @Test
    fun `roles carries ADMIN for an admin and is empty for a regular user`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("admin")
        val userEmail = uniqueEmail("user")
        TestUsers.seed(email = adminEmail, password = "pw", role = UserRole.ADMIN)
        TestUsers.seed(email = userEmail, password = "pw", role = UserRole.USER)

        val client = jsonClient()
        val admin = client.login(adminEmail, "pw").body<LoginResponse>()
        val user = client.login(userEmail, "pw").body<LoginResponse>()

        assertEquals(listOf(UserRole.ADMIN), admin.roles)
        assertEquals(emptyList(), user.roles, "a regular user carries no additional roles")
    }

    @Test
    fun `a padded case-variant email matches its canonical account`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("case")
        TestUsers.seed(email = email, password = "pw")

        val response = jsonClient().postJson("/api/v1/login", LoginRequest("  ${email.uppercase()}  ", "pw"))
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `wrong password returns 401`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("dave")
        TestUsers.seed(email = email, password = "right-pw")

        val response = jsonClient().postJson("/api/v1/login", LoginRequest(email, "wrong-pw"))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `unknown email returns the same uniform 401`() = testApplication {
        usePostgresTestcontainer()
        val response = jsonClient().postJson("/api/v1/login", LoginRequest(uniqueEmail("ghost"), "whatever"))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `over-long password on an existing account returns 401 - not a 500 enumeration oracle`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("longpw")
        TestUsers.seed(email = email, password = "right-pw")

        // Over bcrypt's 72-byte limit the hasher throws; verifyPassword must treat it as
        // non-matching, or the 500 (existing account) vs 401 (unknown email) difference
        // would disclose account existence.
        val response = jsonClient().postJson("/api/v1/login", LoginRequest(email, "x".repeat(200)))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
