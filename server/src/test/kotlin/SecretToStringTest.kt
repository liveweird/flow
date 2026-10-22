package ch.nokillswit

import ch.nokillswit.auth.IssuedToken
import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.LogoutRequest
import ch.nokillswit.auth.MfaChallengeResponse
import ch.nokillswit.auth.MfaChallenges
import ch.nokillswit.auth.MfaVerifyRequest
import ch.nokillswit.auth.RefreshRequest
import ch.nokillswit.flowusers.FlowUser
import ch.nokillswit.flowusers.FlowUserCreateRequest
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.flowusers.PasswordUpdateRequest
import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.plugins.JwtConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checkup 3, D7-5: every data class that carries a password, token, hash, MFA code or signing key renders it
 * as `***` in `toString()`, so a templated request/response/config object can never put a secret into a log
 * line. The secrets below are distinctive markers; each object's rendering must contain none of them but must
 * still contain its non-secret fields (the override is not just an empty string).
 */
class SecretToStringTest {
    private val secret = "S3CR3T-MARKER-9f2c"

    private fun assertMasked(rendered: String, keeps: String) {
        assertFalse(secret in rendered, "the secret leaked into: $rendered")
        assertTrue("***" in rendered, "a present secret must render as ***: $rendered")
        assertTrue(keeps in rendered, "the non-secret fields must stay visible: $rendered")
    }

    @Test
    fun `maskedSecret hides a value and keeps only its presence`() {
        assertEquals("***", maskedSecret(secret))
        assertEquals("***", maskedSecret(""))
        assertEquals("null", maskedSecret(null))
    }

    @Test
    fun `auth request and response shapes mask their credentials`() {
        assertMasked(LoginRequest("a@b.c", secret).toString(), "a@b.c")
        assertMasked(MfaVerifyRequest(secret, secret).toString(), "MfaVerifyRequest")
        assertMasked(RefreshRequest(secret).toString(), "RefreshRequest")
        assertMasked(LogoutRequest(secret).toString(), "LogoutRequest")
        assertEquals("LogoutRequest(refreshToken=null)", LogoutRequest().toString())
        assertMasked(MfaChallengeResponse(challengeId = secret, expiresAt = 42).toString(), "expiresAt=42")
        val login = LoginResponse(
            token = secret,
            expiresAt = 1,
            refreshToken = secret,
            refreshExpiresAt = 2,
            userId = 7u,
            roles = listOf(FlowUserRole.ADMIN),
            disabledFeatures = emptyList(),
            language = "pl",
        )
        assertMasked(login.toString(), "userId=7")
        assertFalse(secret in login.toString())
        assertMasked(IssuedToken(secret, expiresAt = 9).toString(), "expiresAt=9")
    }

    @Test
    fun `an issued MFA challenge masks its code and id`() {
        val issued = MfaChallenges(ttlMillis = 60_000, maxAttempts = 3).issue(userId = 1u, credentialRevision = 0)
        val rendered = issued.toString()
        assertFalse(issued.code in rendered, "the MFA code leaked into: $rendered")
        assertFalse(issued.challengeId in rendered, "the challenge id leaked into: $rendered")
        assertTrue("expiresAt=${issued.expiresAt}" in rendered)
    }

    @Test
    fun `flow user shapes mask passwords and the password hash`() {
        val user = FlowUser(name = "Ann", email = "ann@example.test", passwordHash = secret)
        assertMasked(user.toString(), "ann@example.test")
        assertMasked(FlowUserCreateRequest("Ann", "ann@example.test", secret).toString(), "ann@example.test")
        val update = PasswordUpdateRequest(password = secret, currentPassword = secret).toString()
        assertMasked(update, "PasswordUpdateRequest")
        assertEquals("PasswordUpdateRequest(password=***, currentPassword=null)", PasswordUpdateRequest(secret).toString())
    }

    @Test
    fun `configuration and stored connection shapes mask their secrets`() {
        val jwt = JwtConfig(secret, "issuer-x", "aud-x", "realm-x", 900, 86_400)
        assertMasked(jwt.toString(), "issuer-x")
        val stored = DataSourceService.StoredJiraConnection(
            siteUrl = "https://example.atlassian.net",
            email = "svc@example.test",
            apiToken = secret,
            projectKeys = listOf("ABC"),
            authScheme = JiraAuthScheme.BASIC,
            backfillFrom = "2024-01-01",
        )
        assertMasked(stored.toString(), "https://example.atlassian.net")
    }
}
