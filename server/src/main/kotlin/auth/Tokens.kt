package ch.nokillswit.auth

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.plugins.JwtConfig
import ch.nokillswit.flowusers.Feature
import ch.nokillswit.flowusers.FlowUserRole
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date
import ch.nokillswit.infra.maskedSecret
import java.util.UUID

/** `typ` claim values distinguishing the two token kinds signed with the same HMAC secret. */
const val TOKEN_TYPE_ACCESS = "access"
const val TOKEN_TYPE_REFRESH = "refresh"

/** A freshly minted JWT and its expiry as epoch millis. A live, signed token: [toString] masks it. */
data class IssuedToken(val token: String, val expiresAt: Long) {
    override fun toString() = "IssuedToken(token=${maskedSecret(token)}, expiresAt=$expiresAt)"
}

/**
 * Short-lived token carried as the API bearer. It carries the user's [credentialRevision] (like the refresh
 * token): the per-request check in `plugins/Security.kt` compares it, and the token's `roles`, with the
 * stored row, so a password change or a role change ends the token at the next request.
 */
fun JwtConfig.issueAccessToken(
    userId: UInt,
    email: String,
    roles: Set<FlowUserRole>,
    disabledFeatures: Set<Feature>,
    credentialRevision: Long,
): IssuedToken =
    issueToken(userId, email, roles, disabledFeatures, TOKEN_TYPE_ACCESS, accessExpiresInSeconds, credentialRevision)

/** Longer-lived token exchanged at POST /api/v1/refresh for a fresh pair. */
fun JwtConfig.issueRefreshToken(
    userId: UInt,
    email: String,
    roles: Set<FlowUserRole>,
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
    roles: Set<FlowUserRole>,
    disabledFeatures: Set<Feature>,
    typ: String,
    ttlSeconds: Long,
    credentialRevision: Long,
): IssuedToken {
    val now = nowMillis()
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
        .withClaim("credentialRevision", credentialRevision)
        .withExpiresAt(Date(expiresAt))
        .sign(Algorithm.HMAC256(secret))
    return IssuedToken(token, expiresAt)
}
