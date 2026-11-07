package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.integration.IntegrationReadService
import ch.nokillswit.integration.IntegrationTeams
import ch.nokillswit.integration.ServiceTokenCreateRequest
import ch.nokillswit.integration.ServiceTokenCreateResponse
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarService
import ch.nokillswit.metrics.TeamCapacityRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.teams.TeamCreateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.BeforeClass
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /api/v1/integration/teams`, the one bulk read of a service-token consumer (`.claude/docs/security.md` "Service tokens"): the
 * document's content (roster with and without a known display name, sprint rows with `capacityMd`/`hasCalendar`, the team default), the
 * `sprintsSince` window and its 400s, what it must leave out (soft-deleted teams and connections), the sprint cap, and the D12 pin that
 * nothing caller-shaped is in the JSON. The shared database holds other tests' teams, so every assertion looks at OWN rows.
 */
class IntegrationTeamsReadTest {

    private val url = "/api/v1/integration/teams"
    private val day = 24L * 60 * 60 * 1000

    private suspend fun ApplicationTestBuilder.tokenClient(prefix: String): suspend (String) -> HttpResponse {
        val secret = seededClient(prefix, FlowUserRole.ADMIN)
            .postJson("/api/v1/service-tokens", ServiceTokenCreateRequest("$prefix-${UUID.randomUUID().toString().substring(0, 8)}"))
            .body<ServiceTokenCreateResponse>().secret
        val client: HttpClient = jsonClient()
        return { query -> client.get(url + query) { header(HttpHeaders.Authorization, "Bearer $secret") } }
    }

    private suspend fun HttpResponse.document(): IntegrationTeams {
        assertEquals(HttpStatusCode.OK, status)
        return Json.decodeFromString(bodyAsText())
    }

    private fun IntegrationTeams.team(id: UInt) = items.single { it.id == id }

    private suspend fun softDeleteConnection(connectionId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            exec("UPDATE source_connections SET marked_as_deleted = TRUE WHERE id = $connectionId")
        }
    }

    /** A stored (empty) calendar row, so the sprint reports `hasCalendar`. */
    private suspend fun storeCalendar(connectionId: UInt, sprintId: Long, teamId: UInt, startMs: Long) {
        val json = Json.encodeToString(SprintCalendarDocument(1.0, emptyList(), emptyList()))
        suspendTransaction(sharedDatabaseForTests()) {
            SprintCalendarService.SprintCalendars.insert {
                it[SprintCalendarService.SprintCalendars.connectionId] = connectionId
                it[SprintCalendarService.SprintCalendars.sprintId] = sprintId
                it[SprintCalendarService.SprintCalendars.teamId] = teamId
                it[sprintStartMs] = startMs
                it[calendar] = json
                it[updatedAt] = 1_000L
            }
        }
    }

    @Test
    fun `the document carries the roster with names, the sprints with their capacity and the team default`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("it-teams-content", FlowUserRole.ADMIN)
        val manager = TestUsers.seed(uniqueEmail("it-teams-mgr"), "pw", name = "Manager Mia", role = FlowUserRole.USER)
        val s = CapacityFixtures.seed("it-teams-content")
        // A member whose name is unknown: the person is known only on a connection that is soft-deleted afterwards.
        val ghostConnection = SyncedStubFixture.createConnection(namePrefix = "it-teams-ghost", enabled = false)
        val ghost = CapacityFixtures.people(ghostConnection, 1).single()
        CapacityFixtures.addMember(s.teamId, ghost)
        softDeleteConnection(ghostConnection)
        CapacityFixtures.insertSprint(
            s.connectionId, s.teamId, 2L, CapacityFixtures.FRIDAY_MORNING + day, CapacityFixtures.TUESDAY_AFTERNOON + day,
            completeAt = CapacityFixtures.TUESDAY_AFTERNOON + day, capacityMd = 7.5, capacitySource = "CALENDAR",
        )
        storeCalendar(s.connectionId, 2L, s.teamId, CapacityFixtures.FRIDAY_MORNING + day)
        assertTrue(admin.putJson("/api/v1/teams/${s.teamId}/capacity", TeamCapacityRequest(12.5)).status.value in 200..299)
        val managedName = SyncedStubFixture.unique("it-teams-managed")
        val managed = TestTeams.service.create(TeamCreateRequest(name = managedName, managerUserId = manager))
        val read = tokenClient("it-teams-content")
        val before = System.currentTimeMillis()

        val document = read("?sprintsSince=2026-01-01").document()

        assertTrue(document.asOf in before..System.currentTimeMillis())
        assertEquals("2026-01-01", document.sprintsSince)
        assertEquals(document.items.map { it.id }.sorted(), document.items.map { it.id }, "teams come in id order")
        val team = document.team(s.teamId)
        assertEquals(s.teamName, team.name)
        assertNull(team.managerName)
        assertEquals(12.5, team.defaultCapacityMd)
        assertEquals(3, team.members.size, "every interval of the team, open ones included")
        assertEquals((s.members + ghost).toSet(), team.members.map { it.accountId }.toSet())
        val byAccount = team.members.associateBy { it.accountId }
        assertTrue(s.members.all { byAccount.getValue(it).displayName?.startsWith("Person ") == true }, "names come from norm.people")
        assertNull(byAccount.getValue(ghost).displayName, "an account with no (active) people row has no name")
        assertTrue(team.members.all { it.validFrom == 0L && it.validTo == null })
        assertEquals(listOf(1L, 2L), team.sprints.map { it.sprintId }, "startAt ascending")
        val plain = team.sprints[0]
        assertEquals(s.connectionId, plain.connectionId)
        assertEquals("Sprint 1", plain.name)
        assertEquals("closed", plain.state)
        assertEquals(CapacityFixtures.FRIDAY_MORNING, plain.startAt)
        assertEquals(CapacityFixtures.TUESDAY_AFTERNOON, plain.endAt)
        assertNull(plain.completeAt)
        assertFalse(plain.hasCalendar)
        assertNull(plain.capacityMd)
        assertNull(plain.capacitySource)
        val calendared = team.sprints[1]
        assertTrue(calendared.hasCalendar)
        assertEquals(7.5, calendared.capacityMd)
        assertEquals("CALENDAR", calendared.capacitySource)
        assertEquals(CapacityFixtures.TUESDAY_AFTERNOON + day, calendared.completeAt)

        val managedTeam = document.team(managed)
        assertEquals("Manager Mia", managedTeam.managerName)
        assertNull(managedTeam.defaultCapacityMd)
        assertEquals(emptyList(), managedTeam.members)
        assertEquals(emptyList(), managedTeam.sprints)
    }

    @Test
    fun `sprintsSince filters on the sprint end, the default window is a year back and a null end always stays`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("it-teams-window", members = 1)
        val calendar = DerivedStubFixture.metricsSettings().readCalendar()
        val now = System.currentTimeMillis()
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, 10L, now - 40 * day, now - 10 * day)
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, 11L, now - 440 * day, now - 400 * day)
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, 12L, now - 500 * day, null)
        // The boundary is the since day's 00:00 in the metrics time zone, inclusive.
        val boundary = calendar.dayBoundsMs(LocalDate.parse("2026-02-10")).first
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, 13L, boundary - 20 * day, boundary)
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, 14L, boundary - 20 * day, boundary - 1)
        val read = tokenClient("it-teams-window")
        fun IntegrationTeams.ids() = team(s.teamId).sprints.map { it.sprintId }.toSet()

        val today = calendar.dayOf(now)
        val default = read("").document()
        val yearBack = setOf(today, calendar.dayOf(System.currentTimeMillis())).map { it.minusDays(365).toString() }
        assertTrue(default.sprintsSince in yearBack, "${default.sprintsSince} is not a year before today")
        assertEquals(setOf(10L, 12L), default.ids().intersect(setOf(10L, 11L, 12L)), "a year back by default; no end date stays")
        assertEquals(default.ids(), read("?sprintsSince=").document().ids(), "a blank value is the default")

        val recent = read("?sprintsSince=${today.minusDays(30)}").document()
        assertEquals(today.minusDays(30).toString(), recent.sprintsSince)
        assertEquals(setOf(10L, 12L), recent.ids().intersect(setOf(10L, 11L, 12L)))

        val edge = read("?sprintsSince=2026-02-10").document().ids()
        assertTrue(13L in edge && 14L !in edge, "an end exactly at the since day's start is in, a millisecond earlier is out")
        assertTrue(11L in read("?sprintsSince=2000-01-01").document().ids())
    }

    @Test
    fun `a malformed or repeated sprintsSince is a 400 and a blank one is not`() = testApplication {
        usePostgresTestcontainer()
        val read = tokenClient("it-teams-400")
        for (bad in listOf("yesterday", "2026-13-45", "2026-02-30", "20260101", "1969-12-31", "3000-01-01", "+999999999-01-01")) {
            val response = read("?sprintsSince=$bad")
            assertEquals(HttpStatusCode.BadRequest, response.status, bad)
            assertEquals("application/problem+json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            assertTrue(response.body<ProblemDetail>().detail.orEmpty().contains("sprintsSince must be an ISO date"), bad)
        }
        val repeated = read("?sprintsSince=2026-01-01&sprintsSince=2026-01-02")
        assertEquals(HttpStatusCode.BadRequest, repeated.status)
        assertEquals("Parameter 'sprintsSince' must not be repeated", repeated.body<ProblemDetail>().detail)
        assertEquals(HttpStatusCode.OK, read("?sprintsSince=2026-01-01&other=1").status, "unknown parameter names stay ignored")
    }

    @Test
    fun `a soft-deleted team, a soft-deleted connection's sprints and another team's sprints stay out`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("it-teams-gone")
        val other = CapacityFixtures.seed("it-teams-other", members = 1)
        val doomed = CapacityFixtures.seed("it-teams-doomed", members = 1)
        val deletedConnection = SyncedStubFixture.createConnection(namePrefix = "it-teams-deleted", enabled = false)
        CapacityFixtures.insertSprint(deletedConnection, s.teamId, 3L, CapacityFixtures.FRIDAY_MORNING, CapacityFixtures.TUESDAY_AFTERNOON)
        // A merely disabled connection still lists (the team's own sprint-calendars route does too); only a deleted one is hidden.
        assertEquals(1, TestTeams.service.delete(doomed.teamId))
        val read = tokenClient("it-teams-gone")

        val before = read("?sprintsSince=2000-01-01").document()
        assertEquals(setOf(1L, 3L), before.team(s.teamId).sprints.map { it.sprintId }.toSet())
        softDeleteConnection(deletedConnection)
        val after = read("?sprintsSince=2000-01-01").document()

        assertEquals(listOf(1L), after.team(s.teamId).sprints.map { it.sprintId }, "the deleted connection's sprint is gone")
        assertEquals(listOf(other.connectionId), after.team(other.teamId).sprints.map { it.connectionId })
        assertTrue(after.items.none { it.id == doomed.teamId }, "a soft-deleted team is not listed")
    }

    @Test
    fun `nothing caller-shaped is in the JSON - no canEdit, coefficient, cells, email or user id key`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("it-teams-d12")
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, 2L, CapacityFixtures.FRIDAY_MORNING, CapacityFixtures.TUESDAY_AFTERNOON)
        storeCalendar(s.connectionId, 2L, s.teamId, CapacityFixtures.FRIDAY_MORNING)
        val managerEmail = uniqueEmail("it-teams-d12")
        val manager = TestUsers.seed(managerEmail, "pw", name = "D12 Manager")
        TestTeams.service.create(TeamCreateRequest(name = SyncedStubFixture.unique("it-teams-d12-mgr"), managerUserId = manager))
        val text = tokenClient("it-teams-d12")("?sprintsSince=2000-01-01").bodyAsText()

        val keys = mutableSetOf<String>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> keys += key; collect(value) }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        collect(Json.parseToJsonElement(text))
        assertTrue(keys.containsAll(setOf("asOf", "sprintsSince", "sprintsTruncated", "items", "managerName", "members", "sprints")))
        val forbidden = listOf("canedit", "coefficient", "cells", "email", "userid", "managerid", "updatedby", "createdby", "url")
        assertEquals(emptyList(), keys.filter { key -> forbidden.any { it in key.lowercase() } }, "keys that should not be on the wire")
        assertTrue("D12 Manager" in text && managerEmail !in text, "the manager's name is on the wire, the manager's email is not")
    }

    @Test
    fun `a user's JWT is refused here like on every integration operation`() = testApplication {
        usePostgresTestcontainer()
        val response = seededClient("it-teams-jwt", FlowUserRole.ADMIN).get(url)
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Missing or invalid service token", response.body<ProblemDetail>().detail)
    }

    @Test
    fun `the sprint cap cuts the answer and says so`() = runBlocking {
        val s = CapacityFixtures.seed("it-teams-cap", members = 1)
        val friday = CapacityFixtures.FRIDAY_MORNING
        val tuesday = CapacityFixtures.TUESDAY_AFTERNOON
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, 2L, friday + day, tuesday)
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, 3L, friday + 2 * day, tuesday)
        val service = CapacityFixtures.service()

        val exact = service.sprintsOfTeams(listOf(s.teamId), sinceMs = 0L, cap = 3)
        assertFalse(exact.truncated, "exactly cap rows is not truncated")
        assertEquals(listOf(1L, 2L, 3L), exact.byTeam.getValue(s.teamId).map { it.sprintId })

        val cut = service.sprintsOfTeams(listOf(s.teamId), sinceMs = 0L, cap = 2)
        assertTrue(cut.truncated)
        assertEquals(listOf(1L, 2L), cut.byTeam.getValue(s.teamId).map { it.sprintId }, "the earliest starts survive the cut")

        assertEquals(emptyMap(), service.sprintsOfTeams(emptyList(), sinceMs = 0L).byTeam)
        assertEquals(emptyMap(), service.teamCapacityDefaults(emptyList()))

        // Through the composing service: the document-level flag and the row count (the cap is global across teams).
        val capped = IntegrationReadService(
            TestTeams.service,
            TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings()),
            service,
            DerivedStubFixture.metricsSettings(),
            SyncedStubFixture.workItems(),
            sprintCap = 1,
        ).teams(LocalDate.parse("2000-01-01"))
        assertTrue(capped.sprintsTruncated)
        assertEquals(1, capped.items.sumOf { it.sprints.size })
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
