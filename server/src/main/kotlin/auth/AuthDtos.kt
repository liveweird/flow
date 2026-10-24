package ch.nokillswit.auth

import ch.nokillswit.flowusers.Feature
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.infra.maskedSecret
import kotlinx.serialization.Serializable

// The wire shapes of the auth routes. Every one that carries a password, a token or an MFA code overrides
// `toString()` through `maskedSecret` (a default data-class rendering would print the value into any log line).

@Serializable
data class LoginRequest(val email: String, val password: String) {
    override fun toString(): String = "LoginRequest(email=$email, password=${maskedSecret(password)})"
}

@Serializable
data class PasswordResetRequest(val email: String)

/** The confirm step of the self-service reset: the emailed single-use token plus the password the owner chose. */
@Serializable
data class PasswordResetConfirmRequest(val token: String, val password: String) {
    override fun toString(): String =
        "PasswordResetConfirmRequest(token=${maskedSecret(token)}, password=${maskedSecret(password)})"
}

/**
 * The MFA branch of POST /api/v1/login: credentials verified, second factor pending — no
 * tokens yet. The client sends the emailed 6-digit code with [challengeId] to
 * POST /api/v1/login/mfa to obtain the ordinary [LoginResponse].
 */
@Serializable
data class MfaChallengeResponse(
    // Literal discriminator against LoginResponse in the login 200 oneOf; @EncodeDefault
    // keeps the defaulted value on the wire.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val mfaRequired: Boolean = true,
    val challengeId: String,
    /** Epoch millis when the challenge (and its code) expires. */
    val expiresAt: Long,
) {
    override fun toString(): String =
        "MfaChallengeResponse(mfaRequired=$mfaRequired, challengeId=${maskedSecret(challengeId)}, expiresAt=$expiresAt)"
}

@Serializable
data class MfaVerifyRequest(val challengeId: String, val code: String) {
    override fun toString(): String =
        "MfaVerifyRequest(challengeId=${maskedSecret(challengeId)}, code=${maskedSecret(code)})"
}

@Serializable
data class RefreshRequest(val refreshToken: String) {
    override fun toString(): String = "RefreshRequest(refreshToken=${maskedSecret(refreshToken)})"
}

@Serializable
data class LogoutRequest(val refreshToken: String? = null) {
    override fun toString(): String = "LogoutRequest(refreshToken=${maskedSecret(refreshToken)})"
}

@Serializable
data class LoginResponse(
    val token: String,
    val expiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val userId: UInt,
    /** Additional roles of the authenticated user — empty for a regular user. */
    val roles: List<FlowUserRole>,
    /** Per-user feature flags (V5) — the admin-disabled set; empty = full access. */
    val disabledFeatures: List<Feature>,
    /** The user's stored language (V1) — the SPA applies it to the UI on login/refresh. */
    val language: String,
) {
    override fun toString(): String =
        "LoginResponse(token=${maskedSecret(token)}, expiresAt=$expiresAt, refreshToken=${maskedSecret(refreshToken)}, " +
            "refreshExpiresAt=$refreshExpiresAt, userId=$userId, roles=$roles, disabledFeatures=$disabledFeatures, " +
            "language=$language)"
}
