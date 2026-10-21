package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.authz.requireFeatureEnabled
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.authz.requireTeamManagerOrAdmin
import ch.nokillswit.flowusers.FlowUserRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure unit tests for the authz guards (no server, no database). */
class GuardsTest {

    private val admin = CallerPrincipal(userId = 1u, email = "admin@test", roles = setOf(FlowUserRole.ADMIN))
    private val user = CallerPrincipal(userId = 2u, email = "user@test", roles = emptySet())

    @Test
    fun `isAdmin reflects the ADMIN role`() {
        assertTrue(admin.isAdmin())
        assertFalse(user.isAdmin())
    }

    @Test
    fun `requireFeatureEnabled rejects a caller whose set carries the feature`() {
        requireFeatureEnabled(user, ch.nokillswit.flowusers.Feature.MFA)
        val gated = CallerPrincipal(
            userId = 3u,
            email = "gated@test",
            roles = emptySet(),
            disabledFeatures = setOf(ch.nokillswit.flowusers.Feature.MFA),
        )
        assertFailsWith<ForbiddenException> { requireFeatureEnabled(gated, ch.nokillswit.flowusers.Feature.MFA) }
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

    @Test
    fun `requireTeamManagerOrAdmin passes an admin without consulting the lookup, passes the manager, rejects everyone else uniformly`() =
        runBlocking<Unit> {
            val asked = mutableListOf<Pair<UInt, UInt>>()
            val managerOfTeam7: suspend (UInt, UInt) -> Boolean = { team, who ->
                asked += team to who
                team == 7u && who == user.userId
            }
            requireTeamManagerOrAdmin(admin, teamId = 7u, isManager = managerOfTeam7)
            requireTeamManagerOrAdmin(admin, teamId = 999u, isManager = managerOfTeam7)
            assertEquals(emptyList(), asked, "an admin never reaches the lookup")

            requireTeamManagerOrAdmin(user, teamId = 7u, isManager = managerOfTeam7)
            val stranger = CallerPrincipal(userId = 9u, email = "stranger@test", roles = emptySet())
            assertFailsWith<ForbiddenException> { requireTeamManagerOrAdmin(stranger, teamId = 7u, isManager = managerOfTeam7) }
            // The manager of ANOTHER team, and an unknown team, fail the same way (the lookup answers false).
            assertFailsWith<ForbiddenException> { requireTeamManagerOrAdmin(user, teamId = 8u, isManager = managerOfTeam7) }
            assertFailsWith<ForbiddenException> { requireTeamManagerOrAdmin(user, teamId = 999999u, isManager = managerOfTeam7) }
        }

    @Test
    fun `requireTeamManagerOrAdmin fails closed - a lookup failure propagates for a non-admin and is never reached for an admin`() =
        runBlocking<Unit> {
            val broken: suspend (UInt, UInt) -> Boolean = { _, _ -> error("database unavailable") }
            requireTeamManagerOrAdmin(admin, teamId = 7u, isManager = broken)
            val failure = assertFailsWith<IllegalStateException> { requireTeamManagerOrAdmin(user, teamId = 7u, isManager = broken) }
            assertEquals("database unavailable", failure.message)
        }
}
