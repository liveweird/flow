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

/**
 * ADMIN, or the team's current ACTIVE manager (V23). [isManager] answers false for a missing or deleted team too,
 * so run this as the FIRST guard of a team-scoped write, before any read: every other caller gets a uniform 403
 * whether or not the id exists. The lookup is injected so the guard stays DB-free (`GuardsTest` is pure). Consumers: the capacity
 * writes (`metrics/SprintCalendarRoutes.kt`), which additionally re-check a non-admin's grant inside their locked write transaction.
 */
suspend fun requireTeamManagerOrAdmin(
    caller: CallerPrincipal,
    teamId: UInt,
    isManager: suspend (teamId: UInt, userId: UInt) -> Boolean,
) {
    if (caller.isAdmin()) return
    if (!isManager(teamId, caller.userId)) throw ForbiddenException("Team manager or admin role required")
}
