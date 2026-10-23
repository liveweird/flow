package ch.nokillswit

import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintCalendarResponse
import ch.nokillswit.metrics.TeamCapacityRequest
import ch.nokillswit.metrics.TeamCapacityResponse
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Who may edit a team's capacity (domain-model A32, `.claude/docs/authorization.md`): ADMIN or the team's own ACTIVE manager, checked
 * FIRST. A plain user, a manager of another team, a cleared grant, a soft-deleted manager and a manager of a soft-deleted team all get
 * a uniform 403 (even with a malformed body or an unknown id); every read is open to any authenticated user.
 */
class SprintCalendarAuthorizationTest {

    private class Manager(val id: UInt, val client: HttpClient)

    private suspend fun ApplicationTestBuilder.userClient(prefix: String): Manager {
        val email = uniqueEmail(prefix)
        val id = TestUsers.seed(email = email, password = "pw", role = FlowUserRole.USER, name = prefix)
        return Manager(id, authedClient(email, "pw"))
    }

    private suspend fun HttpClient.setManager(s: CapacityFixtures.Seeded, userId: UInt?) {
        assertEquals(
            HttpStatusCode.NoContent,
            putJson("/api/v1/teams/${s.teamId}", TeamUpdateRequest(name = s.teamName, managerUserId = userId)).status,
        )
    }

    private suspend fun HttpClient.putCapacity(teamId: UInt, md: Double) =
        putJson("/api/v1/teams/$teamId/capacity", TeamCapacityRequest(md))

    private fun url(teamId: UInt, sprintId: Long) = "/api/v1/teams/$teamId/sprint-calendars/$sprintId"

    private suspend fun HttpClient.document(s: CapacityFixtures.Seeded): SprintCalendarDocument =
        get(url(s.teamId, s.sprintId)).body<SprintCalendarResponse>().let { c ->
            val people = c.people.map { SprintCalendarPerson(it.accountId, it.enabled, it.coefficient!!, it.cells) }
            SprintCalendarDocument(c.teamCoefficient, c.days.map { SprintCalendarDay(it.day, it.enabled) }, people)
        }

    private suspend fun HttpClient.revision() = get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision

    @Test
    fun `a plain user reads everything but is 403 on both writes, malformed body and unknown ids included`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("cap-authz-user")
        val admin = seededClient("cap-authz-user-admin", FlowUserRole.ADMIN)
        val user = userClient("cap-authz-user").client
        val document = admin.document(s)
        val before = admin.revision()

        assertEquals(HttpStatusCode.OK, user.get(url(s.teamId, s.sprintId)).status)
        assertEquals(HttpStatusCode.OK, user.get("/api/v1/teams/${s.teamId}/sprint-calendars").status)
        val readable = user.get("/api/v1/teams/${s.teamId}/capacity").body<TeamCapacityResponse>()
        assertFalse(readable.canEdit)
        assertFalse(user.get(url(s.teamId, s.sprintId)).body<SprintCalendarResponse>().canEdit)

        assertEquals(HttpStatusCode.Forbidden, user.putJson(url(s.teamId, s.sprintId), document).status)
        assertEquals(HttpStatusCode.Forbidden, user.putCapacity(s.teamId, 10.0).status)
        // Uniform 403: an unknown team and an unknown sprint answer the same, and a malformed body never reaches the decoder.
        assertEquals(HttpStatusCode.Forbidden, user.putJson(url(999999u, 1L), document).status)
        assertEquals(HttpStatusCode.Forbidden, user.putJson(url(s.teamId, 424242L), document).status)
        assertEquals(HttpStatusCode.Forbidden, user.putJson("/api/v1/teams/999999/capacity", TeamCapacityRequest(10.0)).status)
        assertEquals(HttpStatusCode.Forbidden, user.put(url(s.teamId, s.sprintId)) { setBody("{ not json") }.status)
        assertEquals(HttpStatusCode.Forbidden, user.put("/api/v1/teams/${s.teamId}/capacity") { setBody("{ not json") }.status)

        assertEquals(before, admin.revision(), "no denied request changed anything")
        assertFalse(admin.get(url(s.teamId, s.sprintId)).body<SprintCalendarResponse>().hasCalendar)
    }

    @Test
    fun `the team's manager edits both and is audited as MANAGER, ADMIN as ADMIN`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("cap-authz-mgr")
        val admin = seededClient("cap-authz-mgr-admin", FlowUserRole.ADMIN)
        val manager = userClient("cap-authz-mgr")
        admin.setManager(s, manager.id)
        val document = admin.document(s)

        withAuditCapture { capture ->
            val read = manager.client.get(url(s.teamId, s.sprintId)).body<SprintCalendarResponse>()
            assertTrue(read.canEdit)
            assertTrue(manager.client.get("/api/v1/teams/${s.teamId}/capacity").body<TeamCapacityResponse>().canEdit)

            assertEquals(HttpStatusCode.NoContent, manager.client.putJson(url(s.teamId, s.sprintId), document).status)
            val calendarEvent = assertNotNull(
                capture.awaitEvent { it.message == "team.sprint_calendar_updated" && it.hasKeyValue("teamId", s.teamId.toLong()) },
            )
            assertTrue(calendarEvent.hasKeyValue("byUserId", manager.id.toLong()))
            assertTrue(calendarEvent.hasKeyValue("authorizedBy", "MANAGER"))

            assertEquals(HttpStatusCode.NoContent, manager.client.putCapacity(s.teamId, 33.0).status)
            val defaultEvent = assertNotNull(
                capture.awaitEvent { it.message == "team.capacity_default_updated" && it.hasKeyValue("teamId", s.teamId.toLong()) },
            )
            assertTrue(defaultEvent.hasKeyValue("authorizedBy", "MANAGER"))

            assertEquals(HttpStatusCode.NoContent, admin.putCapacity(s.teamId, 34.0).status)
            assertNotNull(
                capture.awaitEvent { it.message == "team.capacity_default_updated" && it.hasKeyValue("authorizedBy", "ADMIN") },
            )
        }
        // Being the manager grants nothing beyond capacity: the team record itself stays ADMIN only.
        assertEquals(
            HttpStatusCode.Forbidden,
            manager.client.putJson("/api/v1/teams/${s.teamId}", TeamUpdateRequest(name = s.teamName, managerUserId = manager.id)).status,
        )
    }

    @Test
    fun `a manager of another team is 403 on every write and the sprint stays unmodified`() = testApplication {
        usePostgresTestcontainer()
        val mine = CapacityFixtures.seed("cap-authz-own")
        val theirs = CapacityFixtures.seed("cap-authz-other", sprintId = 5L)
        val admin = seededClient("cap-authz-other-admin", FlowUserRole.ADMIN)
        val manager = userClient("cap-authz-other")
        admin.setManager(mine, manager.id)
        val before = admin.revision()

        assertEquals(HttpStatusCode.Forbidden, manager.client.putJson(url(theirs.teamId, theirs.sprintId), admin.document(theirs)).status)
        assertEquals(HttpStatusCode.Forbidden, manager.client.putCapacity(theirs.teamId, 1.0).status)
        // My own team id with the OTHER team's sprint: the guard passes (I manage it), the sprint is not mine - 404, nothing written.
        assertEquals(HttpStatusCode.NotFound, manager.client.putJson(url(mine.teamId, theirs.sprintId), admin.document(theirs)).status)
        assertEquals(before, admin.revision())
        assertFalse(admin.get(url(theirs.teamId, theirs.sprintId)).body<SprintCalendarResponse>().hasCalendar)
        assertEquals(null, admin.get("/api/v1/teams/${theirs.teamId}/capacity").body<TeamCapacityResponse>().defaultCapacityMd)
    }

    @Test
    fun `a cleared grant is 403 on the very next request with the same access token`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("cap-authz-cleared")
        val admin = seededClient("cap-authz-cleared-admin", FlowUserRole.ADMIN)
        val manager = userClient("cap-authz-cleared")
        admin.setManager(s, manager.id)
        assertEquals(HttpStatusCode.NoContent, manager.client.putCapacity(s.teamId, 5.0).status)

        admin.setManager(s, null)
        assertEquals(HttpStatusCode.Forbidden, manager.client.putCapacity(s.teamId, 6.0).status)
        assertEquals(HttpStatusCode.Forbidden, manager.client.putJson(url(s.teamId, s.sprintId), admin.document(s)).status)
        assertEquals(5.0, admin.get("/api/v1/teams/${s.teamId}/capacity").body<TeamCapacityResponse>().defaultCapacityMd)
        // Reassigned to someone else: still 403 for the old manager.
        val other = userClient("cap-authz-cleared-other")
        admin.setManager(s, other.id)
        assertEquals(HttpStatusCode.Forbidden, manager.client.putCapacity(s.teamId, 7.0).status)
        assertEquals(HttpStatusCode.NoContent, other.client.putCapacity(s.teamId, 7.0).status)
    }

    @Test
    fun `a soft-deleted manager's still-unexpired token is 401`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("cap-authz-gone")
        val admin = seededClient("cap-authz-gone-admin", FlowUserRole.ADMIN)
        val manager = userClient("cap-authz-gone")
        admin.setManager(s, manager.id)
        assertEquals(HttpStatusCode.NoContent, manager.client.putJson(url(s.teamId, s.sprintId), admin.document(s)).status)

        // The per-request access check ends the deleted account's token before any guard runs (the manager grant's own
        // "ACTIVE user" predicate in `TeamService.isActiveManager` stays as the second line of defence).
        TestUsers.softDelete(manager.id)
        assertEquals(HttpStatusCode.Unauthorized, manager.client.putJson(url(s.teamId, s.sprintId), admin.document(s)).status)
        assertEquals(HttpStatusCode.Unauthorized, manager.client.putCapacity(s.teamId, 9.0).status)
    }

    @Test
    fun `a soft-deleted team is 403 for its manager, 404 for ADMIN and for every read`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("cap-authz-deleted")
        val admin = seededClient("cap-authz-deleted-admin", FlowUserRole.ADMIN)
        val manager = userClient("cap-authz-deleted")
        admin.setManager(s, manager.id)
        val document = admin.document(s)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/${s.teamId}").status)

        assertEquals(HttpStatusCode.Forbidden, manager.client.putJson(url(s.teamId, s.sprintId), document).status)
        assertEquals(HttpStatusCode.Forbidden, manager.client.putCapacity(s.teamId, 1.0).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(url(s.teamId, s.sprintId), document).status)
        assertEquals(HttpStatusCode.NotFound, admin.putCapacity(s.teamId, 1.0).status)
        // The reads' posture: a soft-deleted team is gone (404), like TeamService.read.
        assertEquals(HttpStatusCode.NotFound, manager.client.get(url(s.teamId, s.sprintId)).status)
        assertEquals(HttpStatusCode.NotFound, manager.client.get("/api/v1/teams/${s.teamId}/capacity").status)
        assertEquals(HttpStatusCode.NotFound, manager.client.get("/api/v1/teams/${s.teamId}/sprint-calendars").status)
    }

    @Test
    fun `the service re-checks a non-admin grant inside its locked transaction`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("cap-authz-service")
        val stranger = TestUsers.seed(email = uniqueEmail("cap-authz-service"), password = "pw", role = FlowUserRole.USER)
        val service = CapacityFixtures.service()
        val denied = runCatching { service.replaceTeamCapacity(s.teamId, TeamCapacityRequest(12.0), stranger, callerIsAdmin = false) }
        assertTrue(denied.exceptionOrNull() is ForbiddenException, "a non-manager reaching the service directly is refused")
        assertEquals(null, service.readTeamCapacity(s.teamId, canEdit = false).defaultCapacityMd)
        assertTrue(service.replaceTeamCapacity(s.teamId, TeamCapacityRequest(12.0), stranger, callerIsAdmin = true).changed)
    }
}
