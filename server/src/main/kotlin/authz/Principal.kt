package ch.nokillswit.authz

import ch.nokillswit.users.Feature
import ch.nokillswit.users.UserRole
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

data class CallerPrincipal(
    val userId: UInt,
    val email: String,
    /** Additional roles — every caller is implicitly a regular user; empty = no extra privileges. */
    val roles: Set<UserRole>,
    /** Per-user feature flags (V5) — the DISABLED set; empty = full access. */
    val disabledFeatures: Set<Feature> = emptySet(),
)

fun ApplicationCall.caller(): CallerPrincipal {
    val principal = principal<JWTPrincipal>()
        ?: throw UnauthorizedException()
    val email = principal.payload.getClaim("email").asString()
        ?: throw UnauthorizedException("Missing email claim")
    val userIdLong = principal.payload.getClaim("userId").asLong()
        ?: throw UnauthorizedException("Missing userId claim")
    val roleNames = principal.payload.getClaim("roles").asList(String::class.java)
        ?: throw UnauthorizedException("Missing roles claim")
    val roles = roleNames.map { name ->
        runCatching { UserRole.valueOf(name) }
            .getOrElse { throw UnauthorizedException("Unknown role $name") }
    }.toSet()
    // Missing claim = token minted before feature flags (V5) → empty set (all enabled).
    // Deliberately permissive, unlike the required roles claim: outstanding tokens must stay
    // valid across the deploy. A present-but-unknown value is still a forged/foreign token → 401.
    val featureNames = principal.payload.getClaim("disabledFeatures").asList(String::class.java)
        ?: emptyList()
    val disabledFeatures = featureNames.map { name ->
        runCatching { Feature.valueOf(name) }
            .getOrElse { throw UnauthorizedException("Unknown feature $name") }
    }.toSet()
    return CallerPrincipal(
        userId = userIdLong.toUInt(),
        email = email,
        roles = roles,
        disabledFeatures = disabledFeatures,
    )
}
