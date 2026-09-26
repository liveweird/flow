package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.authz.requireFeatureEnabled
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.users.UserRole
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure unit tests for the authz guards (no server, no database). */
class GuardsTest {

    private val admin = CallerPrincipal(userId = 1u, email = "admin@test", roles = setOf(UserRole.ADMIN))
    private val user = CallerPrincipal(userId = 2u, email = "user@test", roles = emptySet())

    @Test
    fun `isAdmin reflects the ADMIN role`() {
        assertTrue(admin.isAdmin())
        assertFalse(user.isAdmin())
    }

    @Test
    fun `requireFeatureEnabled rejects a caller whose set carries the feature`() {
        requireFeatureEnabled(user, ch.nokillswit.users.Feature.MFA)
        val gated = CallerPrincipal(
            userId = 3u,
            email = "gated@test",
            roles = emptySet(),
            disabledFeatures = setOf(ch.nokillswit.users.Feature.MFA),
        )
        assertFailsWith<ForbiddenException> { requireFeatureEnabled(gated, ch.nokillswit.users.Feature.MFA) }
    }

    @Test
    fun `requireAdmin passes an admin and rejects a regular user`() {
        requireAdmin(admin)
        assertFailsWith<ForbiddenException> { requireAdmin(user) }
    }

    @Test
    fun `requireSelfOrAdmin allows self, allows admin on anyone, rejects a third party`() {
        requireSelfOrAdmin(user, targetUserId = 2u)
        requireSelfOrAdmin(admin, targetUserId = 2u)
        assertFailsWith<ForbiddenException> { requireSelfOrAdmin(user, targetUserId = 3u) }
    }
}
