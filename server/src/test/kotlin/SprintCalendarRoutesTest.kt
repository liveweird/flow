package ch.nokillswit

import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintCalendarClaim
import ch.nokillswit.metrics.SprintCalendarCell
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintCalendarResponse
import ch.nokillswit.metrics.SprintCalendarSummaryPage
import ch.nokillswit.metrics.TeamCapacityRequest
import ch.nokillswit.metrics.calendarCapacityMd
import ch.nokillswit.metrics.projectCalendar
import ch.nokillswit.metrics.sprintWindow
import ch.nokillswit.metrics.TeamCapacityResponse
import ch.nokillswit.metrics.TeamMembershipListResponse
import ch.nokillswit.metrics.TeamMembershipUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capacity management's API (domain-model A32): the materialized defaults, the PUT round trip and the formula (hand-computed here, never
 * through the kernel), the re-projection onto the current roster, coefficient carry-forward, the window edge cases, the validation
 * matrix, the no-op PUT and the team default. Who may write is `SprintCalendarAuthorizationTest`; the pure kernels are
 * `CapacityKernelsTest`.
 */
class SprintCalendarRoutesTest {

    private fun calendarUrl(s: CapacityFixtures.Seeded, sprintId: Long = s.sprintId) =
        "/api/v1/teams/${s.teamId}/sprint-calendars/$sprintId"

    private suspend fun HttpClient.calendar(s: CapacityFixtures.Seeded, sprintId: Long = s.sprintId) =
        get(calendarUrl(s, sprintId)).body<SprintCalendarResponse>()

    private suspend fun HttpClient.revision() = get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision

    /** The GET response turned back into a PUT body — what the page sends on Save. */
    private fun SprintCalendarResponse.asDocument() = SprintCalendarDocument(
        teamCoefficient = teamCoefficient,
        days = days.map { SprintCalendarDay(it.day, it.enabled) },
        people = people.map { SprintCalendarPerson(it.accountId, it.enabled, it.coefficient!!, it.cells) },
    )

    private fun SprintCalendarDocument.withPerson(index: Int, change: (SprintCalendarPerson) -> SprintCalendarPerson) =
        copy(people = people.mapIndexed { i, p -> if (i == index) change(p) else p })

    private fun SprintCalendarPerson.withCell(day: String, availability: Double) =
        copy(cells = cells.map { if (it.day == day) SprintCalendarCell(day, availability) else it })

    @Test
    fun `an unstored sprint materializes the defaults - weekend off, every cell 1, members as rows`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-defaults", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-defaults")

        val c = admin.calendar(s)
        assertFalse(c.hasCalendar)
        assertTrue(c.canEdit)
        assertNull(c.note)
        assertEquals("2026-03-06", c.window?.from)
        assertEquals("2026-03-10", c.window?.to)
        assertEquals(CapacityFixtures.WINDOW_DAYS, c.days.map { it.day })
        assertEquals(listOf(true, false, false, true, true), c.days.map { it.enabled }, "Sat/Sun are off, the working days are on")
        assertEquals(s.members, c.people.map { it.accountId }, "the rows are the team's members overlapping the sprint")
        assertTrue(c.people.all { it.member && it.enabled && it.coefficient == 1.0 })
        assertTrue(c.people.all { p -> p.cells.map { it.day } == CapacityFixtures.WINDOW_DAYS && p.cells.all { it.availability == 1.0 } })
        assertTrue(c.people.all { it.displayName.startsWith("Person ") }, "display names come from norm.people")
        assertEquals(1.0, c.teamCoefficient)
        // 2 people x 3 working days (Fri, Mon, Tue), coefficients 1.
        assertEquals(6.0, c.capacityMd)
        assertNull(c.derivedCapacityMd)
        assertNull(c.updatedAt)
    }

    @Test
    fun `a public holiday starts disabled and is named`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-holiday", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-holiday")
        TestHolidays.with("2026-03-09", "Test holiday") {
            val c = admin.calendar(s)
            assertEquals(listOf(true, false, false, false, true), c.days.map { it.enabled })
            assertEquals(listOf(null, null, null, "Test holiday", null), c.days.map { it.holiday }, "only the holiday day is named")
            assertEquals(4.0, c.capacityMd, "2 people x Fri + Tue")
        }
        assertTrue(admin.calendar(s).days.all { it.holiday == null }, "and the name is gone with the holiday")
    }

    @Test
    fun `a holiday day saved enabled is accepted and projected off, the stored choice returns with the holiday gone`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-holiday-lock", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-holiday-lock")
        val url = calendarUrl(s)
        fun SprintCalendarDocument.mondayEnabled(enabled: Boolean) =
            copy(days = days.map { if (it.day == "2026-03-09") it.copy(enabled = enabled) else it })
        val base = admin.calendar(s).asDocument()
        assertEquals(HttpStatusCode.NoContent, admin.putJson(url, base.mondayEnabled(false)).status)

        TestHolidays.with("2026-03-09", "Test holiday") {
            // Sent enabled on a holiday: no 400, a real change (the stored value moves), projected off everywhere.
            val enabledMonday = base.mondayEnabled(true)
            val before = admin.revision()
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, enabledMonday).status)
            assertEquals(before + 1, admin.revision(), "the stored document changed, so the revision bumps")
            val shown = admin.calendar(s)
            assertFalse(shown.days.single { it.day == "2026-03-09" }.enabled, "GET projects the holiday off")
            assertEquals("Test holiday", shown.days.single { it.day == "2026-03-09" }.holiday)
            assertEquals(4.0, shown.capacityMd, "2 people x Fri + Tue")

            // DERIVE reads the same kernel: the stored document still says enabled, the projection does not.
            val stored = CapacityFixtures.service().forDerive(s.connectionId).calendarsBySprintId.getValue(s.sprintId).document
            assertTrue(stored.days.single { it.day == "2026-03-09" }.enabled, "the stored choice is kept as sent")
            val window = sprintWindow(CapacityFixtures.FRIDAY_MORNING, CapacityFixtures.TUESDAY_AFTERNOON, null, TestHolidays.calendar())!!
            val projected = projectCalendar(stored, window, s.members, TestHolidays.calendar(), emptyMap(), carry = null)
            assertEquals(4.0, calendarCapacityMd(projected))

            // A byte-equal re-PUT is still a no-op.
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, enabledMonday).status)
            assertEquals(before + 1, admin.revision())
        }
        val restored = admin.calendar(s)
        assertTrue(restored.days.single { it.day == "2026-03-09" }.enabled, "the stored enabled=true returns once the holiday is gone")
        assertNull(restored.days.single { it.day == "2026-03-09" }.holiday)
        assertEquals(6.0, restored.capacityMd)
    }

    @Test
    fun `re-saving the GET-shaped document of a calendar stored before the holiday existed is a no-op and keeps the stored choice`() =
        testApplication {
            usePostgresTestcontainer()
            val admin = seededClient("cal-holiday-resave", UserRole.ADMIN)
            val s = CapacityFixtures.seed("cal-holiday-resave")
            val url = calendarUrl(s)
            fun storedMonday() = runBlocking {
                CapacityFixtures.service().forDerive(s.connectionId).calendarsBySprintId.getValue(s.sprintId).document
                    .days.single { it.day == "2026-03-09" }.enabled
            }
            // Saved before the holiday exists: Monday is a normal enabled working day.
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, admin.calendar(s).asDocument()).status)
            assertTrue(storedMonday())

            TestHolidays.with("2026-03-09", "Test holiday") {
                withAuditCapture { capture ->
                    val shown = admin.calendar(s)
                    assertFalse(shown.days.single { it.day == "2026-03-09" }.enabled, "GET projects the holiday off")
                    val before = admin.revision()
                    val audits = capture.events.count { it.message == "team.sprint_calendar_updated" }

                    // The UI re-saves the document it was shown, unchanged: a no-op, not a write that flips the stored true to false.
                    assertEquals(HttpStatusCode.NoContent, admin.putJson(url, shown.asDocument()).status)
                    assertEquals(before, admin.revision(), "no revision bump")
                    assertEquals(audits, capture.events.count { it.message == "team.sprint_calendar_updated" }, "no audit")
                    assertTrue(storedMonday(), "the stored choice is kept")

                    // A real change elsewhere keeps the held-back choice too.
                    assertEquals(HttpStatusCode.NoContent, admin.putJson(url, shown.asDocument().copy(teamCoefficient = 1.5)).status)
                    assertEquals(before + 1, admin.revision())
                    assertTrue(storedMonday(), "and a real write elsewhere does not overwrite it either")
                }
            }
            val restored = admin.calendar(s)
            assertTrue(restored.days.single { it.day == "2026-03-09" }.enabled, "the stored enabled=true returns with the holiday gone")
            assertNull(restored.days.single { it.day == "2026-03-09" }.holiday)
        }

    @Test
    fun `a day claimed by the team's earlier sprint is off, names the sprint, and a re-save keeps the stored choice`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-claim", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-claim") // sprint 1: Fri 03-06 09:00Z .. Tue 03-10 15:00Z
        // Sprint 2 starts EARLIER (Mon 03-02) although its id is higher, and ends on Fri 03-06: it holds the Friday.
        CapacityFixtures.insertSprint(
            s.connectionId, s.teamId, 2L, java.time.Instant.parse("2026-03-02T09:00:00Z").toEpochMilli(),
            java.time.Instant.parse("2026-03-06T12:00:00Z").toEpochMilli(),
        )
        val url = calendarUrl(s)
        fun storedFriday() = runBlocking {
            CapacityFixtures.service().forDerive(s.connectionId).calendarsBySprintId.getValue(s.sprintId).document
                .days.single { it.day == "2026-03-06" }.enabled
        }

        val shown = admin.calendar(s)
        assertEquals(listOf(false, false, false, true, true), shown.days.map { it.enabled }, "the claimed Friday starts off")
        assertEquals(
            listOf(SprintCalendarClaim(2L, "Sprint 2")), shown.days.mapNotNull { it.claimedBy }, "and only it, naming the earlier sprint",
        )
        assertEquals(SprintCalendarClaim(2L, "Sprint 2"), shown.days.single { it.day == "2026-03-06" }.claimedBy)
        assertEquals(4.0, shown.capacityMd, "2 people x Mon + Tue")
        val earlier = admin.calendar(s, 2L)
        assertTrue(earlier.days.all { it.claimedBy == null }, "the earlier sprint holds its own days")
        assertEquals(10.0, earlier.capacityMd, "2 people x Mon..Fri, the Friday included")

        // The first save sends the claimed day off as shown: the stored choice is the working day it would otherwise be.
        assertEquals(HttpStatusCode.NoContent, admin.putJson(url, shown.asDocument()).status)
        assertTrue(storedFriday())
        // An unchanged re-save of the GET-shaped document is a no-op: no bump, and the stored choice is not overwritten.
        val before = admin.revision()
        assertEquals(HttpStatusCode.NoContent, admin.putJson(url, admin.calendar(s).asDocument()).status)
        assertEquals(before, admin.revision())
        assertTrue(storedFriday())
        // Sent enabled on the claimed day: accepted, projected off.
        val sentOn = admin.calendar(s).asDocument()
            .copy(days = shown.days.map { SprintCalendarDay(it.day, it.day == "2026-03-06" || it.enabled) })
        assertEquals(HttpStatusCode.NoContent, admin.putJson(url, sentOn).status)
        assertEquals(4.0, admin.calendar(s).capacityMd)

        // The claim goes with the earlier sprint: the stored choice (on) comes back.
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.DimSprint.deleteWhere {
                (MetricsTables.DimSprint.connectionId eq s.connectionId) and (MetricsTables.DimSprint.sprintId eq 2L)
            }
        }
        val freed = admin.calendar(s)
        assertTrue(freed.days.all { it.claimedBy == null })
        assertTrue(freed.days.single { it.day == "2026-03-06" }.enabled)
        assertEquals(6.0, freed.capacityMd, "2 people x Fri + Mon + Tue")
    }

    @Test
    fun `a sprint of another team on the same connection neither claims nor is claimed`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-claim-team", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-claim-team") // sprint 1: Fri 03-06 09:00Z .. Tue 03-10 15:00Z
        val otherTeam = TestTeams.seed(SyncedStubFixture.unique("cal-claim-other"))
        // An EARLIER sprint of another team that holds the Friday, and a LATER one that holds the Monday and Tuesday.
        val earlierStart = java.time.Instant.parse("2026-03-02T09:00:00Z").toEpochMilli()
        val earlierEnd = java.time.Instant.parse("2026-03-06T12:00:00Z").toEpochMilli()
        CapacityFixtures.insertSprint(s.connectionId, otherTeam, 2L, earlierStart, earlierEnd)
        CapacityFixtures.insertSprint(
            s.connectionId, otherTeam, 3L, java.time.Instant.parse("2026-03-09T09:00:00Z").toEpochMilli(),
            java.time.Instant.parse("2026-03-10T09:00:00Z").toEpochMilli(),
        )

        val shown = admin.calendar(s)
        assertTrue(shown.days.all { it.claimedBy == null }, "another team's sprint holds nothing of this team's")
        assertEquals(listOf(true, false, false, true, true), shown.days.map { it.enabled })
        assertEquals(6.0, shown.capacityMd, "2 people x Fri + Mon + Tue")
        val other = admin.get("/api/v1/teams/$otherTeam/sprint-calendars/3").body<SprintCalendarResponse>()
        assertTrue(other.days.all { it.claimedBy == null }, "and this team's sprint does not claim on the other team's")
        val otherFirst = admin.get("/api/v1/teams/$otherTeam/sprint-calendars/2").body<SprintCalendarResponse>()
        assertTrue(otherFirst.days.all { it.claimedBy == null })
    }

    @Test
    fun `a first save over a holiday stores the day as a working day so it returns when the holiday is removed`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-holiday-first", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-holiday-first")
        val url = calendarUrl(s)
        fun stored(day: String) = runBlocking {
            CapacityFixtures.service().forDerive(s.connectionId).calendarsBySprintId.getValue(s.sprintId).document
                .days.single { it.day == day }.enabled
        }
        TestHolidays.with("2026-03-09", "Test holiday") {
            val shown = admin.calendar(s)
            assertFalse(shown.hasCalendar)
            assertFalse(shown.days.single { it.day == "2026-03-09" }.enabled, "GET projects the holiday off")
            // The first save of a never-saved calendar sends the holiday day off, as shown.
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, shown.asDocument()).status)
            assertTrue(stored("2026-03-09"), "a weekday holiday is stored as the working day it would otherwise be")
            assertFalse(stored("2026-03-07"), "a weekend day stays off")
            assertFalse(admin.calendar(s).days.single { it.day == "2026-03-09" }.enabled, "and is still projected off")
        }
        assertTrue(admin.calendar(s).days.single { it.day == "2026-03-09" }.enabled, "the day returns with the holiday gone")
    }

    @Test
    fun `a PUT stores the calendar, the formula holds, the revision bumps and the change is audited`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-put", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-put")
        val (personA, personB) = s.members
        val url = calendarUrl(s)
        // Tue off; A: coefficient 0.8 with a half Monday; B off; team coefficient 1.2.
        val document = admin.calendar(s).asDocument().copy(teamCoefficient = 1.2)
            .copy(days = admin.calendar(s).days.map { SprintCalendarDay(it.day, it.enabled && it.day != "2026-03-10") })
            .withPerson(0) { it.copy(coefficient = 0.8).withCell("2026-03-09", 0.5) }
            .withPerson(1) { it.copy(enabled = false) }

        withAuditCapture { capture ->
            val before = admin.revision()
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, document).status)
            assertEquals(before + 1, admin.revision(), "a real change bumps the shared revision")
            // 1.2 x (0.8 x (Fri 1 + Mon 0.5)) = 1.44 (B is off, Sat/Sun/Tue are off).
            val event = assertNotNull(
                capture.awaitEvent { it.message == "team.sprint_calendar_updated" && it.hasKeyValue("sprintId", s.sprintId) },
            )
            assertTrue(event.hasKeyValue("teamId", s.teamId.toLong()))
            assertTrue(event.hasKeyValue("connectionId", s.connectionId.toLong()))
            assertTrue(event.hasKeyValue("capacityMd", 1.44))
            assertTrue(event.hasKeyValue("authorizedBy", "ADMIN"))

            val stored = admin.calendar(s)
            assertTrue(stored.hasCalendar)
            assertEquals(1.44, stored.capacityMd)
            assertEquals(1.2, stored.teamCoefficient)
            assertEquals(listOf(personA, personB), stored.people.map { it.accountId })
            assertEquals(0.8, stored.people[0].coefficient)
            assertFalse(stored.people[1].enabled)
            assertEquals(0.5, stored.people[0].cells.single { it.day == "2026-03-09" }.availability)
            assertFalse(stored.days.single { it.day == "2026-03-10" }.enabled)
            assertNotNull(stored.updatedAt)

            // Byte-equal re-PUT: no bump, no audit.
            val auditCount = capture.events.count { it.message == "team.sprint_calendar_updated" }
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, document).status)
            assertEquals(before + 1, admin.revision(), "a canonically equal document must not bump the revision")
            assertEquals(auditCount, capture.events.count { it.message == "team.sprint_calendar_updated" }, "and must not audit")

            // A real change again does.
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, document.withPerson(0) { it.withCell("2026-03-06", 0.75) }).status)
            assertEquals(before + 2, admin.revision())
        }
    }

    @Test
    fun `a stored calendar is re-projected onto the current roster - new members count, former ones stay flagged`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-reproject", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-reproject")
        val url = calendarUrl(s)
        val document = admin.calendar(s).asDocument().copy(teamCoefficient = 1.2)
            .withPerson(0) { it.copy(coefficient = 0.8).withCell("2026-03-09", 0.5) }
            .withPerson(1) { it.copy(enabled = false) }
        assertEquals(HttpStatusCode.NoContent, admin.putJson(url, document).status)
        // Fri 1 + Mon 0.5 + Tue 1 for A at 0.8, times 1.2.
        assertEquals(2.4, admin.calendar(s).capacityMd, "1.2 x 0.8 x 2.5")

        // The outsider joins the team: a default row (counted, coefficient 1, full cells on the 3 working days) appears.
        CapacityFixtures.addMember(s.teamId, s.outsider)
        val joined = admin.calendar(s)
        assertEquals(s.accounts, joined.people.map { it.accountId })
        val newcomer = joined.people.single { it.accountId == s.outsider }
        assertTrue(newcomer.member && newcomer.enabled && newcomer.coefficient == 1.0)
        assertEquals(6.0, joined.capacityMd, "+ 1.2 x 1 x 3 working days")

        // A is no longer a member (the membership ends before the sprint): the stored row stays, flagged, and still counts.
        val memberships = admin.get("/api/v1/teams/${s.teamId}/jira-memberships").body<TeamMembershipListResponse>().items
        val aMembership = memberships.single { it.accountId == s.members[0] }
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/${s.teamId}/jira-memberships/${aMembership.id}", TeamMembershipUpdateRequest(0L, 1L)).status,
        )
        val left = admin.calendar(s)
        assertFalse(left.people.single { it.accountId == s.members[0] }.member)
        assertEquals(joined.capacityMd, left.capacityMd, "a former member's stored row still counts")
    }

    @Test
    fun `coefficients carry forward from the team's most recent earlier calendar, turn-offs never do`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-carry", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-carry")
        val weekMs = 7L * 24 * 3600 * 1000
        val later = 2L
        CapacityFixtures.insertSprint(
            s.connectionId, s.teamId, later, CapacityFixtures.FRIDAY_MORNING + 2 * weekMs, CapacityFixtures.TUESDAY_AFTERNOON + 2 * weekMs,
        )
        val earlier = 3L
        CapacityFixtures.insertSprint(
            s.connectionId, s.teamId, earlier,
            CapacityFixtures.FRIDAY_MORNING - 2 * weekMs, CapacityFixtures.TUESDAY_AFTERNOON - 2 * weekMs,
        )

        // Nothing stored anywhere yet: plain defaults.
        assertEquals(1.0, admin.calendar(s, later).teamCoefficient)

        // Store a calendar for the FIRST sprint: team 0.5, A 0.7, B 1.3 and off.
        val first = admin.calendar(s).asDocument().copy(teamCoefficient = 0.5)
            .withPerson(0) { it.copy(coefficient = 0.7) }
            .withPerson(1) { it.copy(coefficient = 1.3, enabled = false) }
        assertEquals(HttpStatusCode.NoContent, admin.putJson(calendarUrl(s), first).status)

        // The later sprint seeds the coefficients, but B is counted again; the earlier sprint (older start) is not seeded by it.
        val seeded = admin.calendar(s, later)
        assertFalse(seeded.hasCalendar)
        assertEquals(0.5, seeded.teamCoefficient)
        assertEquals(listOf(0.7, 1.3), seeded.people.map { it.coefficient })
        assertTrue(seeded.people.all { it.enabled }, "turn-offs never carry")
        val notSeeded = admin.calendar(s, earlier)
        assertEquals(1.0, notSeeded.teamCoefficient)
        assertEquals(listOf(1.0, 1.0), notSeeded.people.map { it.coefficient })
    }

    @Test
    fun `a sprint without a start date has no calendar - GET says why, PUT is 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-nostart", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-nostart")
        val noStart = 20L
        CapacityFixtures.insertSprint(s.connectionId, s.teamId, noStart, startAt = null, endAt = null)

        val c = admin.calendar(s, noStart)
        assertNull(c.window)
        assertNotNull(c.note)
        assertTrue(c.days.isEmpty() && c.people.isEmpty())
        assertNull(c.capacityMd)
        val body = SprintBody.empty()
        assertEquals(HttpStatusCode.Conflict, admin.putJson(calendarUrl(s, noStart), body).status)
    }

    @Test
    fun `a window over 60 days has no grid - GET says why, PUT is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-toolong", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-toolong")
        val long = 21L
        CapacityFixtures.insertSprint(
            s.connectionId, s.teamId, long, CapacityFixtures.FRIDAY_MORNING, CapacityFixtures.FRIDAY_MORNING + 61L * 24 * 3600 * 1000,
        )
        val c = admin.calendar(s, long)
        assertNull(c.window)
        assertTrue(c.note!!.contains("60"))
        assertTrue(c.days.isEmpty())
        assertEquals(HttpStatusCode.BadRequest, admin.putJson(calendarUrl(s, long), SprintBody.empty()).status)
    }

    @Test
    fun `an invalid document is 400 and stores nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-invalid", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-invalid")
        val url = calendarUrl(s)
        val valid = admin.calendar(s).asDocument()
        val day = "2026-03-06"
        val cases: Map<String, SprintCalendarDocument> = mapOf(
            "cell above 1" to valid.withPerson(0) { it.withCell(day, 1.01) },
            "negative cell" to valid.withPerson(0) { it.withCell(day, -0.25) },
            "person coefficient above 2" to valid.withPerson(0) { it.copy(coefficient = 2.5) },
            "team coefficient above 2" to valid.copy(teamCoefficient = 2.01),
            "negative team coefficient" to valid.copy(teamCoefficient = -0.1),
            "three decimals" to valid.withPerson(0) { it.withCell(day, 0.125) },
            "missing day" to valid.copy(days = valid.days.drop(1)),
            "extra day" to valid.copy(days = valid.days + valid.days.first().copy(day = "2026-03-11")),
            "repeated day" to valid.copy(days = valid.days + valid.days.first()),
            "missing cell" to valid.withPerson(0) { it.copy(cells = it.cells.drop(1)) },
            "duplicate account" to valid.copy(people = valid.people + valid.people.first()),
            "unknown account" to valid.copy(people = valid.people + valid.people.first().copy(accountId = "no-such-account")),
            "control character" to valid.withPerson(0) { it.copy(accountId = "bad\u0001id") },
            "blank account" to valid.withPerson(0) { it.copy(accountId = " ") },
            "malformed day" to valid.copy(days = valid.days.map { it.copy(day = "not-a-date") }),
        )
        val before = admin.revision()
        for ((label, document) in cases) {
            assertEquals(HttpStatusCode.BadRequest, admin.putJson(url, document).status, label)
        }
        assertEquals(before, admin.revision(), "a rejected save bumps nothing")
        assertFalse(admin.calendar(s).hasCalendar, "and stores nothing")
    }

    @Test
    fun `a known account that is not a member is allowed`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-outsider", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-outsider")
        val valid = admin.calendar(s).asDocument()
        val outsider = valid.people.first().copy(accountId = s.outsider)
        assertEquals(HttpStatusCode.NoContent, admin.putJson(calendarUrl(s), valid.copy(people = valid.people + outsider)).status)
        val c = admin.calendar(s)
        assertEquals(s.accounts, c.people.map { it.accountId })
        assertFalse(c.people.single { it.accountId == s.outsider }.member)
        assertEquals(9.0, c.capacityMd, "3 people x 3 working days")
    }

    @Test
    fun `an unknown team, an unknown sprint and another team's sprint are 404 and nothing is modified`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-404", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-404")
        val other = CapacityFixtures.seed("cal-404-other", sprintId = 7L)
        val body = admin.calendar(s).asDocument()

        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/teams/999999/sprint-calendars/1").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/teams/999999/sprint-calendars").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/teams/999999/sprint-calendars/1", body).status)
        assertEquals(HttpStatusCode.NotFound, admin.get(calendarUrl(s, 424242L)).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson(calendarUrl(s, 424242L), body).status)
        // The other team's sprint, addressed under THIS team: 404 on read and write, and the other team's sprint stays untouched.
        assertEquals(HttpStatusCode.NotFound, admin.get(calendarUrl(s, other.sprintId)).status)
        val before = admin.revision()
        assertEquals(HttpStatusCode.NotFound, admin.putJson(calendarUrl(s, other.sprintId), body).status)
        assertEquals(before, admin.revision())
        assertFalse(admin.calendar(other).hasCalendar)
    }

    @Test
    fun `the team default reads, sets, no-ops, clears and rejects invalid figures`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cap-default", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cap-default")
        val url = "/api/v1/teams/${s.teamId}/capacity"

        val empty = admin.get(url).body<TeamCapacityResponse>()
        assertNull(empty.defaultCapacityMd)
        assertTrue(empty.canEdit)
        assertNull(empty.updatedAt)

        withAuditCapture { capture ->
            val before = admin.revision()
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, TeamCapacityRequest(40.0)).status)
            assertEquals(before + 1, admin.revision())
            val set = admin.get(url).body<TeamCapacityResponse>()
            assertEquals(40.0, set.defaultCapacityMd)
            assertNotNull(set.updatedAt)
            val event = assertNotNull(
                capture.awaitEvent { it.message == "team.capacity_default_updated" && it.hasKeyValue("teamId", s.teamId.toLong()) },
            )
            assertTrue(event.hasKeyValue("defaultCapacityMd", 40.0) && event.hasKeyValue("authorizedBy", "ADMIN"))

            // The same figure again: nothing.
            val audited = capture.events.count { it.message == "team.capacity_default_updated" }
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, TeamCapacityRequest(40.0)).status)
            assertEquals(before + 1, admin.revision())
            assertEquals(audited, capture.events.count { it.message == "team.capacity_default_updated" })

            // null clears; clearing an already-cleared default is a no-op too.
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, TeamCapacityRequest(null)).status)
            assertEquals(before + 2, admin.revision())
            assertNull(admin.get(url).body<TeamCapacityResponse>().defaultCapacityMd)
            assertEquals(HttpStatusCode.NoContent, admin.putJson(url, TeamCapacityRequest(null)).status)
            assertEquals(before + 2, admin.revision())
        }
        for (bad in listOf(-1.0, 1_000_000.0, 12.345)) {
            assertEquals(HttpStatusCode.BadRequest, admin.putJson(url, TeamCapacityRequest(bad)).status, "figure $bad")
        }
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/teams/999999/capacity").status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/teams/999999/capacity", TeamCapacityRequest(1.0)).status)
    }

    @Test
    fun `the sprint list pages, sorts and flags the stored calendars`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-list", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-list")
        val dayMs = 24L * 3600 * 1000
        for ((id, weeks) in listOf(2L to 2, 3L to 4)) {
            val shift = weeks * 7 * dayMs
            CapacityFixtures.insertSprint(
                s.connectionId, s.teamId, id, CapacityFixtures.FRIDAY_MORNING + shift, CapacityFixtures.TUESDAY_AFTERNOON + shift,
            )
        }
        assertEquals(HttpStatusCode.NoContent, admin.putJson(calendarUrl(s, 2L), admin.calendar(s, 2L).asDocument()).status)

        val all = admin.get("/api/v1/teams/${s.teamId}/sprint-calendars").body<SprintCalendarSummaryPage>()
        assertEquals(3L, all.total)
        assertEquals(listOf(1L, 2L, 3L), all.items.map { it.sprintId }, "default sort is id ascending")
        assertEquals(listOf(false, true, false), all.items.map { it.hasCalendar })
        assertTrue(all.items.all { it.connectionId == s.connectionId && it.startAt != null })

        val newestFirst = admin.get("/api/v1/teams/${s.teamId}/sprint-calendars?sort=-startAt&pageSize=2&page=1")
            .body<SprintCalendarSummaryPage>()
        assertEquals(3L, newestFirst.total)
        assertEquals(listOf(3L, 2L), newestFirst.items.map { it.sprintId })
        val second = admin.get("/api/v1/teams/${s.teamId}/sprint-calendars?sort=-startAt&pageSize=2&page=2")
            .body<SprintCalendarSummaryPage>()
        assertEquals(listOf(1L), second.items.map { it.sprintId })

        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/teams/${s.teamId}/sprint-calendars?sort=name").status)
    }

    @Test
    fun `a pre-V24 CONFIGURED dim_sprint row is served by the sprint list and the calendar`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-legacy", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-legacy")
        CapacityFixtures.insertSprint(
            s.connectionId, s.teamId, 2L, CapacityFixtures.FRIDAY_MORNING, CapacityFixtures.TUESDAY_AFTERNOON,
            capacityMd = 20.0, capacitySource = "CONFIGURED",
        )
        val legacy = admin.get("/api/v1/teams/${s.teamId}/sprint-calendars").body<SprintCalendarSummaryPage>()
            .items.single { it.sprintId == 2L }
        assertEquals(20.0, legacy.capacityMd)
        assertEquals("CONFIGURED", legacy.capacitySource)
        val detail = admin.calendar(s, 2L)
        assertEquals(20.0, detail.derivedCapacityMd)
        assertEquals("CONFIGURED", detail.derivedCapacitySource)
    }

    @Test
    fun `DERIVE's input and the PURGE drain see the stored calendars and defaults`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cal-derive-input", UserRole.ADMIN)
        val s = CapacityFixtures.seed("cal-derive-input")
        assertEquals(HttpStatusCode.NoContent, admin.putJson(calendarUrl(s), admin.calendar(s).asDocument()).status)
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/teams/${s.teamId}/capacity", TeamCapacityRequest(55.5)).status)

        val service = CapacityFixtures.service()
        val inputs = service.forDerive(s.connectionId)
        val stored = inputs.calendarsBySprintId.getValue(s.sprintId)
        assertEquals(s.teamId, stored.teamId)
        assertEquals(CapacityFixtures.FRIDAY_MORNING, stored.sprintStartMs)
        assertEquals(6.0, ch.nokillswit.metrics.calendarCapacityMd(stored.document))
        assertEquals(55.5, inputs.teamDefaultMdByTeamId.getValue(s.teamId))

        assertEquals(1, service.purgeConnection(s.connectionId))
        assertTrue(service.forDerive(s.connectionId).calendarsBySprintId.isEmpty())
        assertEquals(55.5, service.forDerive(s.connectionId).teamDefaultMdByTeamId[s.teamId], "the per-team default is never purged")
    }

    /** A document that can never be valid for any window — enough to reach the 409/400 window checks. */
    private object SprintBody {
        fun empty() = SprintCalendarDocument(1.0, emptyList(), emptyList())
    }
}
