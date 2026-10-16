package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintCalendarCell
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintCalendarService
import ch.nokillswit.metrics.TeamCapacityRequest
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.CostMatrixReport
import ch.nokillswit.reports.CostRow
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9

/** A person of a hand-built stored calendar: [cells] overrides single days' availability (every other cell is a full man-day). */
private class CalPerson(
    val account: String,
    val coefficient: Double = 1.0,
    val enabled: Boolean = true,
    val cells: Map<String, Double> = emptyMap(),
)

private fun utc(day: String, hour: Int): Long = Instant.parse("${day}T%02d:00:00Z".format(hour)).toEpochMilli()

/**
 * A stored calendar over `[from, to]`: weekends off, [off] days off, a public holiday left ENABLED as stored (the projection turns it
 * off, A33), every [people] cell a full man-day unless overridden.
 */
private fun storedCalendar(
    from: String,
    to: String,
    teamCoefficient: Double,
    off: Set<String>,
    vararg people: CalPerson,
): SprintCalendarDocument {
    val last = LocalDate.parse(to)
    val days = generateSequence(LocalDate.parse(from)) { it.plusDays(1).takeIf { next -> !next.isAfter(last) } }.toList()
    val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    return SprintCalendarDocument(
        teamCoefficient,
        days.map { SprintCalendarDay(it.toString(), it.dayOfWeek !in weekend && it.toString() !in off) },
        people.map { p ->
            val cells = days.map { SprintCalendarCell(it.toString(), p.cells[it.toString()] ?: 1.0) }
            SprintCalendarPerson(p.account, p.enabled, p.coefficient, cells)
        },
    )
}

/**
 * The hand-built world of one capacity test: a fresh DISABLED connection with four known people, the filter team [t], another team
 * [u] and an admin user. Everything it seeded is removed in the `finally` (worklogs, dims, sprints, calendars, teams).
 */
private class CapacityWorld(
    val connId: UInt,
    val t: UInt,
    val u: UInt,
    val tName: String,
    val uName: String,
    val alice: String,
    val bob: String,
    val carol: String,
    val dave: String,
    val adminId: UInt,
) {
    val store = MetricsStore(sharedDatabaseForTests())
    val calendars: SprintCalendarService = CapacityFixtures.service()
    val memberships = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())

    fun range(from: String, to: String) = "connectionId=$connId&from=$from&to=$to"

    suspend fun member(team: UInt, account: String, validFrom: Long = 0L, validTo: Long? = null) {
        memberships.create(team, TeamMembershipCreateRequest(account, validFrom, validTo))
    }

    private var nextWorklogId = 1L

    /** One 1-MD worklog per (author team or null, author or null, ISO day). */
    suspend fun log(vararg rows: Triple<UInt?, String?, String>) {
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertFactWorklog(
                connId,
                rows.map { (team, account, day) -> wl(nextWorklogId++, team, account, day, 1.0, "AAA") },
                configRevision = 1L,
            )
        }
    }

    suspend fun sprint(team: UInt, sprintId: Long, startDay: String, endDay: String) =
        CapacityFixtures.insertSprint(connId, team, sprintId, utc(startDay, 9), utc(endDay, 15))

    suspend fun save(team: UInt, sprintId: Long, document: SprintCalendarDocument) {
        val calendar = DerivedStubFixture.metricsSettings().readCalendar()
        calendars.replace(team, sprintId, document, adminId, callerIsAdmin = true, calendar = calendar)
    }
}

private suspend fun withCapacityWorld(block: suspend (CapacityWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "cost-capacity", enabled = false)
    val people = CapacityFixtures.people(connId, 4)
    val tName = SyncedStubFixture.unique("cc-t")
    val uName = SyncedStubFixture.unique("cc-u")
    val t = TestTeams.seed(tName)
    val u = TestTeams.seed(uName)
    val adminId = TestUsers.seed(uniqueEmail("cc-admin"), "pw")
    val world = CapacityWorld(connId, t, u, tName, uName, people[0], people[1], people[2], people[3], adminId)
    try {
        block(world)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            world.store.deleteFactWorklog(connId)
            world.store.deleteDims(connId)
            MetricsTables.DimSprint.deleteWhere { MetricsTables.DimSprint.connectionId eq connId }
            // A team default figure outlives the (soft-deleted) team, so the test that saved one removes its row.
            SprintCalendarService.TeamCapacityDefaults.deleteWhere { SprintCalendarService.TeamCapacityDefaults.teamId inList listOf(t, u) }
        }
        world.calendars.purgeConnection(connId)
        cleanUpTeams(listOf(t, u))
    }
}

/**
 * The cost matrix's per-person capacity column (report 16, domain-model A39): who sees it, and the hand-computed figure on stored
 * calendars and on default days. January 2026 (Jan 1 and Jan 6 are seeded public holidays, asserted first), fresh DISABLED connection.
 */
class ReportCostMatrixCapacityTest {

    private class UserClient(val id: UInt, val client: HttpClient)

    private suspend fun ApplicationTestBuilder.userClient(prefix: String): UserClient {
        val email = uniqueEmail(prefix)
        val id = TestUsers.seed(email = email, password = "pw", role = UserRole.USER, name = prefix)
        return UserClient(id, authedClient(email, "pw"))
    }

    private suspend fun HttpClient.setManager(world: CapacityWorld, team: UInt, userId: UInt?) {
        val name = if (team == world.t) world.tName else world.uName
        val request = TeamUpdateRequest(name = name, managerUserId = userId)
        assertEquals(HttpStatusCode.NoContent, putJson("/api/v1/teams/$team", request).status)
    }

    private suspend fun HttpClient.cost(query: String): CostMatrixReport {
        val response = get("/api/v1/reports/cost-matrix?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cost-matrix?$query")
        return response.body()
    }

    /**
     * The 14 days up to today plus six after it. The server reads its clock per request, so when the configured zone's day rolled over
     * between the test's reading and the response the reading is taken again; returns the day the server saw with the response.
     */
    private suspend fun HttpClient.costReachingIntoTheFuture(w: CapacityWorld): Pair<LocalDate, CostMatrixReport> {
        repeat(2) {
            val today = LocalDate.now(reportZone())
            w.log(Triple(w.t, w.alice, today.minusDays(3).toString()), Triple(w.t, w.bob, today.minusDays(2).toString()))
            val report = cost("${w.range(today.minusDays(13).toString(), today.plusDays(6).toString())}&teamId=${w.t}")
            if (LocalDate.now(reportZone()) == today) return today to report
        }
        error("the day rolled over twice in a row")
    }

    private fun CostMatrixReport.row(account: String?): CostRow = rows.single { it.accountId == account }

    private suspend fun assertJanuaryHolidays() {
        val calendar = DerivedStubFixture.metricsSettings().readCalendar()
        assertTrue(calendar.isHoliday(LocalDate.parse("2026-01-01")) && calendar.isHoliday(LocalDate.parse("2026-01-06")), "the V25 seed")
    }

    private fun WorkingCalendar.workingDaysIn(from: LocalDate, to: LocalDate): Int =
        generateSequence(from) { it.plusDays(1).takeIf { next -> !next.isAfter(to) } }.count { isWorkingDay(it) }

    @Test
    fun `an admin and the team's manager see each author's capacity, a plain user and another team's manager do not, nothing leaks`() =
        testApplication {
            usePostgresTestcontainer()
            assertJanuaryHolidays()
            withCapacityWorld { w ->
                w.member(w.t, w.alice)
                w.member(w.t, w.bob)
                w.log(Triple(w.t, w.alice, "2026-01-12"), Triple(w.t, w.bob, "2026-01-13"))
                val admin = seededClient("cc-visible-admin", UserRole.ADMIN)
                val manager = userClient("cc-visible-manager")
                val otherManager = userClient("cc-visible-other")
                val plain = seededClient("cc-visible-plain", UserRole.USER)
                admin.setManager(w, w.t, manager.id)
                admin.setManager(w, w.u, otherManager.id)
                val query = "${w.range("2026-01-01", "2026-01-31")}&teamId=${w.t}"

                // January 2026: 22 weekdays, minus the two seeded holidays.
                val workingDays = 20.0
                for ((who, client) in listOf("admin" to admin, "manager" to manager.client)) {
                    val report = client.cost(query)
                    assertEquals(workingDays, report.row(w.alice).capacityMd, "$who: alice")
                    assertEquals(workingDays, report.row(w.bob).capacityMd, "$who: bob")
                    assertEquals(2 * workingDays, report.capacity!!.totalMd, "$who: total")
                    assertEquals(31, report.capacity.days)
                    assertEquals(0, report.capacity.calendarDays)
                }
                for ((who, client) in listOf("plain user" to plain, "another team's manager" to otherManager.client)) {
                    val raw = client.get("/api/v1/reports/cost-matrix?$query")
                    assertEquals(HttpStatusCode.OK, raw.status, who)
                    val body = raw.bodyAsText()
                    assertFalse("capacity" in body, "$who: the raw JSON must not carry any capacity key")
                    val report = client.cost(query)
                    assertNull(report.capacity, who)
                    assertTrue(report.rows.all { it.capacityMd == null }, who)
                    assertEquals(2, report.rows.size, "$who: the same rows as everyone")
                    // The split adds fields of its own (per-bucket figures, the footer): none of them may carry capacity either.
                    for (split in listOf("WEEK", "SPRINT")) {
                        val splitRaw = client.get("/api/v1/reports/cost-matrix?$query&split=$split")
                        assertEquals(HttpStatusCode.OK, splitRaw.status, "$who: split=$split")
                        val splitBody = splitRaw.bodyAsText()
                        assertTrue("byBucket" in splitBody && "bucketTotals" in splitBody, "$who: split=$split is really split")
                        assertFalse("capacity" in splitBody, "$who: split=$split must not carry any capacity key")
                    }
                }
            }
        }

    @Test
    fun `a cleared or soft-deleted manager loses the column next request, and UNIT level and the unassigned bucket never carry it`() =
        testApplication {
            usePostgresTestcontainer()
            withCapacityWorld { w ->
                w.member(w.t, w.alice)
                w.log(Triple(w.t, w.alice, "2026-01-12"), Triple(null, w.dave, "2026-01-12"))
                val admin = seededClient("cc-grant-admin", UserRole.ADMIN)
                val manager = userClient("cc-grant-manager")
                val range = w.range("2026-01-12", "2026-01-16")
                val team = "$range&teamId=${w.t}"

                admin.setManager(w, w.t, manager.id)
                assertNotNull(manager.client.cost(team).capacity, "the manager sees it")
                assertNotNull(manager.client.cost("$team&accountId=${w.alice}").capacity, "USER level for the manager carries it")
                assertEquals(5.0, manager.client.cost("$team&accountId=${w.alice}").row(w.alice).capacityMd)

                admin.setManager(w, w.t, null)
                val cleared = manager.client.get("/api/v1/reports/cost-matrix?$team")
                assertEquals(HttpStatusCode.OK, cleared.status)
                assertFalse("capacity" in cleared.bodyAsText(), "a cleared grant takes effect on the next request, no re-login")

                admin.setManager(w, w.t, manager.id)
                assertNotNull(manager.client.cost(team).capacity)
                TestUsers.softDelete(manager.id)
                assertNull(manager.client.cost(team).capacity, "a soft-deleted manager with a still-valid token")

                for (query in listOf(range, "$range&teamId=0")) {
                    val report = admin.cost(query)
                    assertNull(report.capacity, query)
                    assertTrue(report.rows.isNotEmpty() && report.rows.all { it.capacityMd == null }, query)
                }
            }
        }

    @Test
    fun `hand-built calendars pin the figure - coefficients, turned-off days and people, the holiday, a new and a stored non-member`() =
        testApplication {
            usePostgresTestcontainer()
            assertJanuaryHolidays()
            withCapacityWorld { w ->
                // Mon 2026-01-05 .. Fri 2026-01-09; Tue the 6th is a public holiday, Thu the 8th is turned off.
                w.sprint(w.t, 1L, "2026-01-05", "2026-01-09")
                w.member(w.t, w.alice)
                w.member(w.t, w.bob) // a member the stored document does not list: added with coefficient 1 and full cells
                w.save(
                    w.t, 1L,
                    storedCalendar(
                        "2026-01-05", "2026-01-09", teamCoefficient = 2.0, off = setOf("2026-01-08"),
                        CalPerson(w.alice, coefficient = 0.5, cells = mapOf("2026-01-05" to 0.5)),
                        CalPerson(w.carol), // stored, never a member: counted on calendar days
                        CalPerson(w.dave, enabled = false),
                    ),
                )
                w.log(
                    Triple(w.t, w.alice, "2026-01-05"), Triple(w.t, w.bob, "2026-01-05"),
                    Triple(w.t, w.carol, "2026-01-05"), Triple(w.t, w.dave, "2026-01-05"),
                )
                val report = seededClient("cc-hand-admin", UserRole.ADMIN).cost("${w.range("2026-01-05", "2026-01-09")}&teamId=${w.t}")

                // Counted days: Jan 5, 7, 9 (the 6th is a holiday whatever is stored, the 8th is off). team x coefficient x cell:
                assertEquals(2.5, report.row(w.alice).capacityMd) // 2 x 0.5 x (0.5 + 1 + 1)
                assertEquals(6.0, report.row(w.bob).capacityMd) // 2 x 1 x 3
                assertEquals(6.0, report.row(w.carol).capacityMd) // 2 x 1 x 3
                assertEquals(0.0, report.row(w.dave).capacityMd) // turned off
                assertEquals(14.5, report.capacity!!.totalMd)
                assertEquals(5, report.capacity.days)
                assertEquals(5, report.capacity.calendarDays)
            }
        }

    @Test
    fun `days outside a stored calendar count a man-day per working-day member, days after today none, the team default never splits`() =
        testApplication {
            usePostgresTestcontainer()
            assertJanuaryHolidays()
            withCapacityWorld { w ->
                val calendar = DerivedStubFixture.metricsSettings().readCalendar()
                val admin = seededClient("cc-default-admin", UserRole.ADMIN)
                // A sprint with no stored calendar and a team default figure: neither changes the per-person default-day rule.
                w.sprint(w.t, 1L, "2025-12-29", "2026-01-09")
                w.calendars.replaceTeamCapacity(w.t, TeamCapacityRequest(99.0), w.adminId, callerIsAdmin = true)
                w.member(w.t, w.alice)
                w.member(w.t, w.bob, validFrom = calendar.dayBoundsMs(LocalDate.parse("2026-01-05")).first) // joins mid-period
                w.log(Triple(w.t, w.alice, "2025-12-30"), Triple(w.t, w.bob, "2026-01-07"))

                // Dec 29..Jan 11: weekends (Jan 3-4, 10-11) and the holidays Jan 1 and Jan 6 count nothing.
                val past = admin.cost("${w.range("2025-12-29", "2026-01-11")}&teamId=${w.t}")
                assertEquals(8.0, past.row(w.alice).capacityMd) // Dec 29, 30, 31, Jan 2, 5, 7, 8, 9
                assertEquals(4.0, past.row(w.bob).capacityMd) // Jan 5, 7, 8, 9 - his membership starts that day
                assertEquals(12.0, past.capacity!!.totalMd)
                assertEquals(14, past.capacity.days)
                assertEquals(0, past.capacity.calendarDays)

                // Days after today have no capacity: a period reaching a week into the future stops at today.
                val (today, future) = admin.costReachingIntoTheFuture(w)
                val from = today.minusDays(13)
                assertEquals(14, future.capacity!!.days, "the 14 days up to today, never the 6 after it")
                assertEquals(0, future.capacity.calendarDays)
                val expected = calendar.workingDaysIn(from, today).toDouble()
                assertEquals(expected, future.row(w.alice).capacityMd)
                assertEquals(expected, future.row(w.bob).capacityMd, "bob joined long before: the same days")
            }
        }

    @Test
    fun `overlapping sprints count a day once, another team's calendar is ignored, a person who changed teams counts per team`() =
        testApplication {
            usePostgresTestcontainer()
            withCapacityWorld { w ->
                val calendar = DerivedStubFixture.metricsSettings().readCalendar()
                // Team T: A = Mon 12 .. Fri 16 Jan, B = Wed 14 .. Tue 20 Jan (the 14th-16th are in both), C = Wed 21 .. Fri 23 Jan.
                w.sprint(w.t, 1L, "2026-01-12", "2026-01-16")
                w.sprint(w.t, 2L, "2026-01-14", "2026-01-20")
                w.sprint(w.u, 3L, "2026-01-21", "2026-01-23")
                w.member(w.t, w.alice)
                w.member(w.t, w.bob, validTo = calendar.dayBoundsMs(LocalDate.parse("2026-01-21")).first) // T until the 20th
                w.member(w.u, w.bob, validFrom = calendar.dayBoundsMs(LocalDate.parse("2026-01-21")).first) // then U
                w.save(w.t, 1L, storedCalendar("2026-01-12", "2026-01-16", 1.0, emptySet(), CalPerson(w.alice, coefficient = 1.0)))
                w.save(w.t, 2L, storedCalendar("2026-01-14", "2026-01-20", 1.0, emptySet(), CalPerson(w.alice, coefficient = 2.0)))
                // Sprint 3 was U's, its calendar saved for U (coefficient 2) - then its board was re-mapped to T: the row is ignored.
                w.save(w.u, 3L, storedCalendar("2026-01-21", "2026-01-23", 1.0, emptySet(), CalPerson(w.alice, coefficient = 2.0)))
                suspendTransaction(sharedDatabaseForTests()) {
                    MetricsTables.DimSprint.update({
                        (MetricsTables.DimSprint.connectionId eq w.connId) and (MetricsTables.DimSprint.sprintId eq 3L)
                    }) { it[teamId] = w.t }
                }
                w.log(Triple(w.t, w.alice, "2026-01-12"), Triple(w.t, w.bob, "2026-01-13"), Triple(w.u, w.bob, "2026-01-22"))
                val admin = seededClient("cc-owner-admin", UserRole.ADMIN)
                val range = w.range("2026-01-12", "2026-01-23")

                val team = admin.cost("$range&teamId=${w.t}")
                // alice: A owns the 12th-16th (5 x 1); B owns the 17th-20th, weekend off, 19th and 20th at coefficient 2 (4); the
                // 21st-23rd have no usable calendar (U's coefficient 2 is ignored) - default days, 3. B owning 14th-16th too would add 6.
                assertEquals(12.0, team.row(w.alice).capacityMd)
                // bob: A's roster (5) + B's two counted days (2), on calendar days; from the 21st he is not T's member.
                assertEquals(7.0, team.row(w.bob).capacityMd)
                assertEquals(12, team.capacity!!.days)
                assertEquals(9, team.capacity.calendarDays)
                // The same person in U's drill: only U's membership days (the 21st-23rd).
                val unit = admin.cost("$range&teamId=${w.u}")
                assertEquals(3.0, unit.row(w.bob).capacityMd)
                assertEquals(0, unit.capacity!!.calendarDays)
                assertNotEquals(team.row(w.bob).capacityMd, unit.row(w.bob).capacityMd)
            }
        }

    @Test
    fun `the totals capacity is the rounded exact sum and the no-author row has none`() = testApplication {
        usePostgresTestcontainer()
        withCapacityWorld { w ->
            // Wed 2026-01-14 only: three people at coefficient 0.5 x cell 0.67 = 0.335 each (exact), which rounds to 0.34 per row.
            w.sprint(w.t, 1L, "2026-01-14", "2026-01-14")
            w.save(
                w.t, 1L,
                storedCalendar(
                    "2026-01-14", "2026-01-14", 1.0, emptySet(),
                    CalPerson(w.alice, 0.5, cells = mapOf("2026-01-14" to 0.67)),
                    CalPerson(w.bob, 0.5, cells = mapOf("2026-01-14" to 0.67)),
                    CalPerson(w.carol, 0.5, cells = mapOf("2026-01-14" to 0.67)),
                ),
            )
            w.log(
                Triple(w.t, w.alice, "2026-01-14"), Triple(w.t, w.bob, "2026-01-14"),
                Triple(w.t, w.carol, "2026-01-14"), Triple(w.t, null, "2026-01-14"),
            )
            val report = seededClient("cc-round-admin", UserRole.ADMIN).cost("${w.range("2026-01-14", "2026-01-14")}&teamId=${w.t}")
            val people = listOf(w.alice, w.bob, w.carol).map { report.row(it).capacityMd }
            assertEquals(listOf(0.34, 0.34, 0.34), people)
            assertEquals(1.01, report.capacity!!.totalMd, EPS, "the rounded exact sum 1.005, not the sum of the rounded rows 1.02")
            assertTrue(people.sumOf { it!! } > report.capacity.totalMd)
            assertNull(report.row(null).capacityMd, "the no-author row is not a person")
        }
    }
}
