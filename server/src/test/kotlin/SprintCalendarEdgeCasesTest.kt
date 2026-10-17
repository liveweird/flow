package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintCalendarResponse
import ch.nokillswit.metrics.SprintCalendarService
import ch.nokillswit.metrics.SprintCalendarSummaryPage
import ch.nokillswit.metrics.TeamCapacityRequest
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capacity management's seams the main route tests do not reach (A32): what DERIVE is fed once a team or connection is soft-deleted,
 * the carry-forward's deterministic and team/connection-scoped choice, a leftover calendar of the sprint's previous team, the service's
 * own validation and no-op rules, who sees the per-person coefficients, and malformed or oversized bodies.
 */
class SprintCalendarEdgeCasesTest {
    private companion object {
        const val CALENDAR_EVENT = "team.sprint_calendar_updated"
        const val DEFAULT_EVENT = "team.capacity_default_updated"
    }

    private val weekMs = 7L * 24 * 3600 * 1000

    private fun calendarUrl(s: CapacityFixtures.Seeded, sprintId: Long = s.sprintId) =
        "/api/v1/teams/${s.teamId}/sprint-calendars/$sprintId"

    private suspend fun HttpClient.calendar(s: CapacityFixtures.Seeded, sprintId: Long = s.sprintId) =
        get(calendarUrl(s, sprintId)).body<SprintCalendarResponse>()

    /** The GET turned back into a PUT body; needs an editor's view (coefficients present). */
    private fun SprintCalendarResponse.asDocument() = SprintCalendarDocument(
        teamCoefficient, days.map { SprintCalendarDay(it.day, it.enabled) },
        people.map { SprintCalendarPerson(it.accountId, it.enabled, it.coefficient!!, it.cells) },
    )

    private suspend fun rawPut(client: HttpClient, url: String, body: String) = client.put(url) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun softDeleteConnection(connectionId: UInt) {
        suspendTransaction(sharedDatabaseForTests()) {
            exec("UPDATE source_connections SET marked_as_deleted = TRUE WHERE id = $connectionId")
        }
    }

    /** A calendar row written straight to the table: only the team coefficient matters to the carry-forward assertions. */
    private suspend fun insertCalendar(connectionId: UInt, sprintId: Long, teamId: UInt, startMs: Long, teamCoefficient: Double) {
        val json = Json.encodeToString(SprintCalendarDocument(teamCoefficient, emptyList(), emptyList()))
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

    private suspend fun storedTeams(connectionId: UInt, sprintId: Long): List<UInt> = suspendTransaction(sharedDatabaseForTests()) {
        val t = SprintCalendarService.SprintCalendars
        t.selectAll().where { (t.connectionId eq connectionId) and (t.sprintId eq sprintId) }.toList().map { it[t.teamId].value }
    }

    private suspend fun ApplicationTestBuilder.userClient(prefix: String): Pair<UInt, HttpClient> {
        val email = uniqueEmail(prefix)
        val id = TestUsers.seed(email = email, password = "pw", role = UserRole.USER, name = prefix)
        return id to authedClient(email, "pw")
    }

    @Test
    fun `a soft-deleted team's calendars and default stop feeding DERIVE`() = runBlocking {
        val s = CapacityFixtures.seed("cal-edge-team-gone")
        val admin = TestUsers.seed(uniqueEmail("cal-edge-team-gone"), "pw")
        val service = CapacityFixtures.service()
        val calendar = DerivedStubFixture.metricsSettings().readCalendar()
        val grid = service.read(s.teamId, s.sprintId, calendar, canEdit = true)
        val document = SprintCalendarDocument(
            grid.teamCoefficient, grid.days.map { SprintCalendarDay(it.day, it.enabled) },
            grid.people.map { SprintCalendarPerson(it.accountId, it.enabled, it.coefficient!!, it.cells) },
        )
        service.replace(s.teamId, s.sprintId, document, admin, callerIsAdmin = true, calendar = calendar)
        service.replaceTeamCapacity(s.teamId, TeamCapacityRequest(33.0), admin, callerIsAdmin = true)
        val live = service.forDerive(s.connectionId)
        assertTrue(s.sprintId in live.calendarsBySprintId)
        assertEquals(33.0, live.teamDefaultMdByTeamId[s.teamId])

        TestTeams.service.delete(s.teamId)
        val gone = service.forDerive(s.connectionId)
        assertFalse(s.sprintId in gone.calendarsBySprintId, "its calendar no longer feeds DERIVE")
        assertFalse(s.teamId in gone.teamDefaultMdByTeamId, "and neither does its default figure")
    }

    @Test
    fun `a soft-deleted connection hides its sprints - the list is empty and a GET is 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-edge-conn-gone", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-edge-conn-gone")
        val url = "/api/v1/teams/${s.teamId}/sprint-calendars"
        assertEquals(1L, admin.get(url).body<SprintCalendarSummaryPage>().total)

        softDeleteConnection(s.connectionId)
        assertEquals(0L, admin.get(url).body<SprintCalendarSummaryPage>().total)
        assertEquals(HttpStatusCode.NotFound, admin.get("$url/${s.sprintId}").status)
    }

    @Test
    fun `another team's leftover calendar row is ignored on read and replaced on write, and the audit says so`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-edge-leftover", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-edge-leftover")
        val formerTeam = TestTeams.seed(SyncedStubFixture.unique("cal-edge-former"))
        insertCalendar(s.connectionId, s.sprintId, formerTeam, CapacityFixtures.FRIDAY_MORNING, teamCoefficient = 0.3)

        val read = admin.calendar(s)
        assertFalse(read.hasCalendar, "the row belongs to another team")
        assertEquals(1.0, read.teamCoefficient)

        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, admin.putJson(calendarUrl(s), read.asDocument()).status)
            val event = assertNotNull(capture.awaitEvent { it.message == CALENDAR_EVENT && it.hasKeyValue("teamId", s.teamId.toLong()) })
            assertTrue(event.hasKeyValue("replacedTeamId", formerTeam.toLong()))
        }
        assertEquals(listOf(s.teamId), storedTeams(s.connectionId, s.sprintId), "the former team's row is replaced")
        assertTrue(admin.calendar(s).hasCalendar)
    }

    @Test
    fun `the audit carries the previous figures - the calendar's and the team default's`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-edge-audit-from", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-edge-audit-from")
        val document = admin.calendar(s).asDocument()
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, admin.putJson(calendarUrl(s), document).status)
            val first = assertNotNull(capture.awaitEvent { it.message == CALENDAR_EVENT && it.hasKeyValue("teamId", s.teamId.toLong()) })
            assertNull(first.keyValuePairs?.firstOrNull { it.key == "capacityMdFrom" }?.value, "no stored calendar before")
            val changed = document.copy(teamCoefficient = 2.0)
            assertEquals(HttpStatusCode.NoContent, admin.putJson(calendarUrl(s), changed).status)
            assertNotNull(capture.awaitEvent { it.message == CALENDAR_EVENT && it.hasKeyValue("capacityMdFrom", 6.0) })

            val url = "/api/v1/teams/${s.teamId}/capacity"
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, TeamCapacityRequest(40.0)).status)
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, TeamCapacityRequest(45.5)).status)
            assertNotNull(capture.awaitEvent { it.message == DEFAULT_EVENT && it.hasKeyValue("defaultCapacityMdFrom", 40.0) })
        }
    }

    @Test
    fun `the carry-forward ignores other teams and soft-deleted connections and breaks a start tie by sprint id`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-edge-carry", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-edge-carry")
        val later = 2L
        CapacityFixtures.insertSprint(
            s.connectionId, s.teamId, later, CapacityFixtures.FRIDAY_MORNING + 4 * weekMs, CapacityFixtures.TUESDAY_AFTERNOON + 4 * weekMs,
        )
        val tieStart = CapacityFixtures.FRIDAY_MORNING - 2 * weekMs
        insertCalendar(s.connectionId, 3L, s.teamId, tieStart, teamCoefficient = 0.4)
        insertCalendar(s.connectionId, 4L, s.teamId, tieStart, teamCoefficient = 0.6)
        // More recent, but another team's — and the team's own on a soft-deleted connection: both skipped.
        val otherTeam = TestTeams.seed(SyncedStubFixture.unique("cal-edge-carry-other"))
        insertCalendar(s.connectionId, 9L, otherTeam, tieStart + weekMs, teamCoefficient = 0.2)
        val deleted = SyncedStubFixture.createConnection(namePrefix = "cal-edge-carry-deleted", enabled = false)
        insertCalendar(deleted, 5L, s.teamId, tieStart + 2 * weekMs, teamCoefficient = 0.9)
        softDeleteConnection(deleted)

        assertEquals(0.6, admin.calendar(s, later).teamCoefficient, "highest sprint id of the tied start, this team, active connection")
    }

    @Test
    fun `the service rejects a figure with three decimals instead of rounding it, and a moved sprint start re-saves an equal document`() =
        runBlocking {
            val s = CapacityFixtures.seed("cal-edge-service")
            val admin = TestUsers.seed(uniqueEmail("cal-edge-service"), "pw")
            val service = CapacityFixtures.service()
            val calendar = DerivedStubFixture.metricsSettings().readCalendar()
            val read = service.read(s.teamId, s.sprintId, calendar, canEdit = true)
            val document = SprintCalendarDocument(
                read.teamCoefficient, read.days.map { SprintCalendarDay(it.day, it.enabled) },
                read.people.map { SprintCalendarPerson(it.accountId, it.enabled, it.coefficient!!, it.cells) },
            )

            val precise = document.copy(
                people = document.people.mapIndexed { i, p ->
                    if (i == 0) p.copy(cells = p.cells.map { it.copy(availability = 0.125) }) else p
                },
            )
            assertFailsWith<BadRequestException> { service.replace(s.teamId, s.sprintId, precise, admin, true, calendar) }
            assertFalse(service.read(s.teamId, s.sprintId, calendar, canEdit = true).hasCalendar, "nothing was stored")

            assertTrue(service.replace(s.teamId, s.sprintId, document, admin, true, calendar).changed)
            assertFalse(service.replace(s.teamId, s.sprintId, document, admin, true, calendar).changed, "byte-equal, same start: a no-op")

            // The sprint starts an hour later on the same day: the window's days are unchanged, but the saved start moves with it.
            val movedStart = CapacityFixtures.FRIDAY_MORNING + 3_600_000L
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsTables.DimSprint.update({
                    (MetricsTables.DimSprint.connectionId eq s.connectionId) and (MetricsTables.DimSprint.sprintId eq s.sprintId)
                }) { it[startAt] = movedStart }
            }
            assertTrue(service.replace(s.teamId, s.sprintId, document, admin, true, calendar).changed, "a moved start re-saves")
            val savedStart = suspendTransaction(sharedDatabaseForTests()) {
                val t = SprintCalendarService.SprintCalendars
                t.selectAll().where { (t.connectionId eq s.connectionId) and (t.sprintId eq s.sprintId) }.toList().single()[t.sprintStartMs]
            }
            assertEquals(movedStart, savedStart)
        }

    @Test
    fun `an unchanged GET document is saveable even when its roster accounts are no longer known to norm people`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-edge-unknown-roster", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-edge-unknown-roster")
        val document = admin.calendar(s).asDocument()
        // The connection's people are rebuilt without anyone: the roster's accounts are now unknown to norm.people.
        SyncedStubFixture.workItems().replacePeople(s.connectionId, emptyList())

        assertEquals(HttpStatusCode.NoContent, admin.putJson(calendarUrl(s), document).status, "the page's own document is always saveable")
        val stranger = document.copy(people = document.people + document.people.first().copy(accountId = "no-such-account-${s.sprintId}"))
        assertEquals(HttpStatusCode.BadRequest, admin.putJson(calendarUrl(s), stranger).status, "in neither the document nor the roster")
    }

    @Test
    fun `per-person coefficients are shown to the manager and ADMINs only, the rest of the grid to every reader`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("cal-edge-coef")
        val admin = seededClient("cal-edge-coef-admin", UserRole.ADMIN)
        val (_, plain) = userClient("cal-edge-coef-plain")
        val (managerId, manager) = userClient("cal-edge-coef-mgr")
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/${s.teamId}", TeamUpdateRequest(name = s.teamName, managerUserId = managerId)).status,
        )

        val seen = plain.calendar(s)
        assertFalse(seen.canEdit)
        assertTrue(seen.people.isNotEmpty() && seen.people.all { it.coefficient == null }, "no coefficient for a reader")
        assertTrue(seen.people.all { p -> p.cells.size == seen.days.size && p.cells.all { it.availability == 1.0 } }, "the grid is visible")
        assertEquals(6.0, seen.capacityMd)
        for (editor in listOf(admin, manager)) {
            val view = editor.calendar(s)
            assertTrue(view.canEdit)
            assertTrue(view.people.all { it.coefficient == 1.0 })
        }
    }

    @Test
    fun `non-finite literals, unknown keys and over-sized bodies are 400, never 500, and store nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-edge-bodies", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-edge-bodies")
        val url = calendarUrl(s)
        val capUrl = "/api/v1/teams/${s.teamId}/capacity"
        val good = Json.encodeToString(admin.calendar(s).asDocument())

        for (literal in listOf("NaN", "Infinity", "-Infinity", "1e400")) {
            val body = good.replaceFirst("\"teamCoefficient\":1.0", "\"teamCoefficient\":$literal")
            assertEquals(HttpStatusCode.BadRequest, rawPut(admin, url, body).status, literal)
            assertEquals(HttpStatusCode.BadRequest, rawPut(admin, capUrl, """{"defaultCapacityMd":$literal}""").status, "capacity $literal")
        }
        for (key in listOf("teamId", "managerUserId", "connectionId")) {
            assertEquals(HttpStatusCode.BadRequest, rawPut(admin, url, good.replaceFirst("{", "{\"$key\":1,")).status, "calendar $key")
            assertEquals(HttpStatusCode.BadRequest, rawPut(admin, capUrl, """{"defaultCapacityMd":1.0,"$key":1}""").status, "capacity $key")
        }
        assertEquals(HttpStatusCode.BadRequest, rawPut(admin, capUrl, "{}").status, "an omitted figure never clears the default")
        assertEquals(HttpStatusCode.NoContent, rawPut(admin, capUrl, """{"defaultCapacityMd":null}""").status, "an explicit null clears it")

        val valid = admin.calendar(s).asDocument()
        val day = valid.days.first()
        val person = valid.people.first()
        val start = java.time.LocalDate.parse("2026-03-06")
        val sixtyOneDays = valid.copy(days = List(61) { day.copy(day = start.plusDays(it.toLong()).toString()) })
        val twoHundredOnePeople = valid.copy(people = List(201) { person.copy(accountId = "acct-$it") })
        val cells = List(61) { person.cells.first().copy(day = start.plusDays(it.toLong()).toString()) }
        val sixtyOneCells = valid.copy(people = listOf(person.copy(cells = cells)))
        for ((label, document) in mapOf("61 days" to sixtyOneDays, "201 people" to twoHundredOnePeople, "61 cells" to sixtyOneCells)) {
            assertEquals(HttpStatusCode.BadRequest, admin.putJson(url, document).status, label)
        }
        assertFalse(admin.calendar(s).hasCalendar, "nothing was stored")
    }

    @Test
    fun `a manager may reference a non-member of an active connection, not one of an inactive or unknown connection`() = testApplication {
        usePostgresTestcontainer()
        val s = CapacityFixtures.seed("cal-edge-mgr-accounts")
        val admin = seededClient("cal-edge-mgr-accounts-admin", UserRole.ADMIN)
        val (managerId, manager) = userClient("cal-edge-mgr-accounts")
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/${s.teamId}", TeamUpdateRequest(name = s.teamName, managerUserId = managerId)).status,
        )
        val document = admin.calendar(s).asDocument()
        val template = document.people.first()
        val inactiveConnection = SyncedStubFixture.createConnection(namePrefix = "cal-edge-inactive", enabled = false)
        val inactiveOnly = CapacityFixtures.people(inactiveConnection, 1).single()
        softDeleteConnection(inactiveConnection)

        val withOutsider = document.copy(people = document.people + template.copy(accountId = s.outsider))
        assertEquals(HttpStatusCode.NoContent, manager.putJson(calendarUrl(s), withOutsider).status, "an active connection's person")
        val withInactive = document.copy(people = document.people + template.copy(accountId = inactiveOnly))
        assertEquals(HttpStatusCode.BadRequest, manager.putJson(calendarUrl(s), withInactive).status, "a soft-deleted connection's person")
        val withUnknown = document.copy(people = document.people + template.copy(accountId = "no-such-account-${s.teamId}"))
        assertEquals(HttpStatusCode.BadRequest, manager.putJson(calendarUrl(s), withUnknown).status, "an unknown account")
    }
}
