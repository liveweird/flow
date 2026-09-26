package ch.nokillswit.auth

import ch.nokillswit.plugins.JwtConfig
import ch.nokillswit.users.Feature
import ch.nokillswit.users.UserRole
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date
import java.util.UUID

/** `typ` claim values distinguishing the two token kinds signed with the same HMAC secret. */
const val TOKEN_TYPE_ACCESS = "access"
const val TOKEN_TYPE_REFRESH = "refresh"

/** A freshly minted JWT and its expiry as epoch millis. */
data class IssuedToken(val token: String, val expiresAt: Long)

/** Short-lived token carried as the API bearer. */
fun JwtConfig.issueAccessToken(
    userId: UInt,
    email: String,
    roles: Set<UserRole>,
    disabledFeatures: Set<Feature>,
): IssuedToken =
    issueToken(userId, email, roles, disabledFeatures, TOKEN_TYPE_ACCESS, accessExpiresInSeconds)

/** Longer-lived token exchanged at POST /api/v1/refresh for a fresh pair. */
fun JwtConfig.issueRefreshToken(
    userId: UInt,
    email: String,
    roles: Set<UserRole>,
    disabledFeatures: Set<Feature>,
    credentialRevision: Long,
): IssuedToken =
    issueToken(
        userId,
        email,
        roles,
        disabledFeatures,
        TOKEN_TYPE_REFRESH,
        refreshExpiresInSeconds,
        credentialRevision,
    )

private fun JwtConfig.issueToken(
    userId: UInt,
    email: String,
    roles: Set<UserRole>,
    disabledFeatures: Set<Feature>,
    typ: String,
    ttlSeconds: Long,
    credentialRevision: Long? = null,
): IssuedToken {
    val now = System.currentTimeMillis()
    val expiresAt = now + ttlSeconds * 1000
    val token = JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withIssuedAt(Date(now))
        .withJWTId(UUID.randomUUID().toString())
        .withClaim("email", email)
        .withClaim("userId", userId.toLong())
        .withArrayClaim("roles", roles.map { it.name }.sorted().toTypedArray())
        .withArrayClaim("disabledFeatures", disabledFeatures.map { it.name }.sorted().toTypedArray())
        .withClaim("typ", typ)
        .apply { credentialRevision?.let { withClaim("credentialRevision", it) } }
        .withExpiresAt(Date(expiresAt))
        .sign(Algorithm.HMAC256(secret))
    return IssuedToken(token, expiresAt)
}
