package ch.nokillswit.authz

import ch.nokillswit.users.Feature
import ch.nokillswit.users.UserRole

fun CallerPrincipal.isAdmin(): Boolean = UserRole.ADMIN in roles

fun requireAdmin(caller: CallerPrincipal) {
    if (!caller.isAdmin()) throw ForbiddenException("Admin role required")
}

/**
 * The area gate for per-user feature flags (V5): run as the FIRST guard of a gated route,
 * before any read, so a disabled caller gets a uniform 403 even for a missing id. No route
 * is gated yet — MFA (the only flag today) is login-scoped and read off the DB record; the
 * first gateable feature area brings the first call site. The set rides the JWT, so a
 * change takes effect at the next refresh (≤15 min) or login.
 */
fun requireFeatureEnabled(caller: CallerPrincipal, feature: Feature) {
    if (feature in caller.disabledFeatures) {
        throw ForbiddenException("The ${feature.name} feature is disabled for this account")
    }
}

fun requireSelfOrAdmin(caller: CallerPrincipal, targetUserId: UInt) {
    if (caller.isAdmin()) return
    if (caller.userId != targetUserId) throw ForbiddenException("Caller may only act on their own user")
}
