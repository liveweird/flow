package ch.nokillswit

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
import ch.nokillswit.reports.CostCapacityDays
import ch.nokillswit.reports.loadCostCapacity
import kotlinx.coroutines.runBlocking
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
 * [u] and an admin user. Everything it seeded is removed in the `finally` (sprints, calendars, team defaults, teams).
 */
private class CapacityWorld(
    val connId: UInt,
    val t: UInt,
    val u: UInt,
    val alice: String,
    val bob: String,
    val carol: String,
    val dave: String,
    val adminId: UInt,
    val calendar: WorkingCalendar,
) {
    val calendars: SprintCalendarService = CapacityFixtures.service()
    private val memberships = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())

    suspend fun member(team: UInt, account: String, validFrom: Long = 0L, validTo: Long? = null) {
        memberships.create(team, TeamMembershipCreateRequest(account, validFrom, validTo))
    }

    suspend fun sprint(team: UInt, sprintId: Long, startDay: String, endDay: String) =
        CapacityFixtures.insertSprint(connId, team, sprintId, utc(startDay, 9), utc(endDay, 15))

    suspend fun save(team: UInt, sprintId: Long, document: SprintCalendarDocument) {
        calendars.replace(team, sprintId, document, adminId, callerIsAdmin = true, calendar = calendar)
    }

    /** The first instant of [day] in the configured zone. */
    fun startOf(day: String): Long = calendar.dayBoundsMs(LocalDate.parse(day)).first

    /**
     * The kernel for [team] over the days `[from, to]` (both inclusive), "now" being the middle of [today] (default: long after the
     * period, so every day counts), on [connectionIds] (default: the world's connection).
     */
    suspend fun capacity(
        team: UInt,
        from: String,
        to: String,
        today: String = "2026-06-01",
        connectionIds: List<UInt> = listOf(connId),
    ): CostCapacityDays {
        val window = startOf(from) to calendar.dayBoundsMs(LocalDate.parse(to)).second
        val nowMs = (calendar.dayBoundsMs(LocalDate.parse(today)).let { it.first + it.second }) / 2
        return suspendTransaction(sharedDatabaseForTests()) { loadCostCapacity(team, connectionIds, window, calendar, nowMs) }
    }
}

private fun CostCapacityDays.total(): Double = perPersonByDay.keys.sumOf { exactFor(it).toDouble() }

private fun CostCapacityDays.md(account: String): Double = exactFor(account).toDouble()

private fun runWorld(block: suspend (CapacityWorld) -> Unit) = runBlocking {
    val connId = SyncedStubFixture.createConnection(namePrefix = "cost-capacity", enabled = false)
    val people = CapacityFixtures.people(connId, 4)
    val t = TestTeams.seed(SyncedStubFixture.unique("cc-t"))
    val u = TestTeams.seed(SyncedStubFixture.unique("cc-u"))
    val adminId = TestUsers.seed(uniqueEmail("cc-admin"), "pw")
    val calendar = DerivedStubFixture.metricsSettings().readCalendar()
    val world = CapacityWorld(connId, t, u, people[0], people[1], people[2], people[3], adminId, calendar)
    try {
        block(world)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.DimSprint.deleteWhere { MetricsTables.DimSprint.connectionId eq connId }
            // A team default figure outlives the (soft-deleted) team, so the test that saved one removes its row.
            SprintCalendarService.TeamCapacityDefaults.deleteWhere { SprintCalendarService.TeamCapacityDefaults.teamId inList listOf(t, u) }
        }
        world.calendars.purgeConnection(connId)
        cleanUpTeams(listOf(t, u))
    }
}

/**
 * The per-person capacity kernel's database path (`loadCostCapacity`, domain-model A39; Compare people, A41, is its reader), driven
 * directly on hand-built worlds - the figures the removed Cost matrix capacity column once pinned over HTTP. January 2026 (Jan 1 and
 * Jan 6 are seeded public holidays, asserted first), fresh DISABLED connection. The pure helpers are `CostCapacityDaysTest`.
 */
class CostCapacityTest {

    private fun assertJanuaryHolidays(calendar: WorkingCalendar) {
        assertTrue(calendar.isHoliday(LocalDate.parse("2026-01-01")) && calendar.isHoliday(LocalDate.parse("2026-01-06")), "the V25 seed")
    }

    @Test
    fun `hand-built calendars pin the figure - coefficients, turned-off days and people, the holiday, a new and a stored non-member`() =
        runWorld { w ->
            assertJanuaryHolidays(w.calendar)
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
            val capacity = w.capacity(w.t, "2026-01-05", "2026-01-09")

            // Counted days: Jan 5, 7, 9 (the 6th is a holiday whatever is stored, the 8th is off). team x coefficient x cell:
            assertEquals(2.5, capacity.md(w.alice), EPS) // 2 x 0.5 x (0.5 + 1 + 1)
            assertEquals(6.0, capacity.md(w.bob), EPS) // 2 x 1 x 3
            assertEquals(6.0, capacity.md(w.carol), EPS) // 2 x 1 x 3
            assertEquals(0.0, capacity.md(w.dave), EPS) // turned off
            assertEquals(14.5, capacity.total(), EPS)
            assertEquals(5, capacity.days.size)
            assertEquals(5, capacity.calendarDays)
            assertEquals(
                listOf("2026-01-05", "2026-01-07", "2026-01-09"),
                capacity.perPersonByDay.getValue(w.alice).keys.sorted().map { it.toString() },
                "the holiday and the turned-off day carry no figure",
            )
        }

    @Test
    fun `days outside a stored calendar count a man-day per working-day member, days after today none, the team default never splits`() =
        runWorld { w ->
            assertJanuaryHolidays(w.calendar)
            // A sprint with no stored calendar and a team default figure: neither changes the per-person default-day rule.
            w.sprint(w.t, 1L, "2025-12-29", "2026-01-09")
            w.calendars.replaceTeamCapacity(w.t, TeamCapacityRequest(99.0), w.adminId, callerIsAdmin = true)
            w.member(w.t, w.alice)
            w.member(w.t, w.bob, validFrom = w.startOf("2026-01-05")) // joins mid-period

            // Dec 29..Jan 11: weekends (Jan 3-4, 10-11) and the holidays Jan 1 and Jan 6 count nothing.
            val past = w.capacity(w.t, "2025-12-29", "2026-01-11")
            assertEquals(8.0, past.md(w.alice), EPS) // Dec 29, 30, 31, Jan 2, 5, 7, 8, 9
            assertEquals(4.0, past.md(w.bob), EPS) // Jan 5, 7, 8, 9 - his membership starts that day
            assertEquals(12.0, past.total(), EPS)
            assertEquals(14, past.days.size)
            assertEquals(0, past.calendarDays)

            // Days after today have no capacity: a period reaching into the future stops at today (Jan 11 is the 14th day).
            val future = w.capacity(w.t, "2025-12-29", "2026-01-17", today = "2026-01-11")
            assertEquals(14, future.days.size, "the 14 days up to today, never the 6 after it")
            assertEquals(LocalDate.parse("2026-01-11"), future.days.last())
            assertEquals(0, future.calendarDays)
            assertEquals(8.0, future.md(w.alice), EPS)
            assertEquals(4.0, future.md(w.bob), EPS)

            // Today itself counts: with Jan 5 as today bob has exactly his first day.
            val firstDay = w.capacity(w.t, "2025-12-29", "2026-01-17", today = "2026-01-05")
            assertEquals(8, firstDay.days.size)
            assertEquals(5.0, firstDay.md(w.alice), EPS) // Dec 29, 30, 31, Jan 2, 5
            assertEquals(1.0, firstDay.md(w.bob), EPS)

            // A period entirely after today has no capacity day at all.
            val none = w.capacity(w.t, "2026-01-12", "2026-01-16", today = "2026-01-11")
            assertEquals(emptyList(), none.days)
            assertEquals(emptyMap(), none.perPersonByDay)
            assertEquals(0, none.calendarDays)
        }

    @Test
    fun `overlapping sprints count a day once, another team's calendar is ignored, a person who changed teams counts per team`() =
        runWorld { w ->
            // Team T: A = Mon 12 .. Fri 16 Jan, B = Wed 14 .. Tue 20 Jan (the 14th-16th are in both), C = Wed 21 .. Fri 23 Jan.
            w.sprint(w.t, 1L, "2026-01-12", "2026-01-16")
            w.sprint(w.t, 2L, "2026-01-14", "2026-01-20")
            w.sprint(w.u, 3L, "2026-01-21", "2026-01-23")
            w.member(w.t, w.alice)
            w.member(w.t, w.bob, validTo = w.startOf("2026-01-21")) // T until the 20th
            w.member(w.u, w.bob, validFrom = w.startOf("2026-01-21")) // then U
            w.save(w.t, 1L, storedCalendar("2026-01-12", "2026-01-16", 1.0, emptySet(), CalPerson(w.alice, coefficient = 1.0)))
            w.save(w.t, 2L, storedCalendar("2026-01-14", "2026-01-20", 1.0, emptySet(), CalPerson(w.alice, coefficient = 2.0)))
            // Sprint 3 was U's, its calendar saved for U (coefficient 2) - then its board was re-mapped to T: the row is ignored.
            w.save(w.u, 3L, storedCalendar("2026-01-21", "2026-01-23", 1.0, emptySet(), CalPerson(w.alice, coefficient = 2.0)))
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsTables.DimSprint.update({
                    (MetricsTables.DimSprint.connectionId eq w.connId) and (MetricsTables.DimSprint.sprintId eq 3L)
                }) { it[teamId] = w.t }
            }

            val team = w.capacity(w.t, "2026-01-12", "2026-01-23")
            // alice: A owns the 12th-16th (5 x 1); B owns the 17th-20th, weekend off, 19th and 20th at coefficient 2 (4); the
            // 21st-23rd have no usable calendar (U's coefficient 2 is ignored) - default days, 3. B owning 14th-16th too would add 6.
            assertEquals(12.0, team.md(w.alice), EPS)
            // bob: A's roster (5) + B's two counted days (2), on calendar days; from the 21st he is not T's member.
            assertEquals(7.0, team.md(w.bob), EPS)
            assertEquals(12, team.days.size)
            assertEquals(9, team.calendarDays)
            // The same person in U's drill: only U's membership days (the 21st-23rd).
            val unit = w.capacity(w.u, "2026-01-12", "2026-01-23")
            assertEquals(3.0, unit.md(w.bob), EPS)
            assertEquals(0, unit.calendarDays)
            assertEquals(setOf(w.bob), unit.perPersonByDay.keys)
        }

    @Test
    fun `a sprint roster reaches outside the period, so a member who left before the period still holds the days of the sprint`() =
        runWorld { w ->
            // Sprint Mon 12 .. Fri 16 Jan; carol was a member for the 12th-13th only, the period is the 14th-16th.
            w.sprint(w.t, 1L, "2026-01-12", "2026-01-16")
            w.member(w.t, w.alice)
            w.member(w.t, w.carol, validTo = w.startOf("2026-01-14"))
            w.save(w.t, 1L, storedCalendar("2026-01-12", "2026-01-16", 1.0, emptySet(), CalPerson(w.alice)))

            val capacity = w.capacity(w.t, "2026-01-14", "2026-01-16")
            assertEquals(3.0, capacity.md(w.alice), EPS)
            // The roster is the memberships overlapping the SPRINT's window (A3), not only the period: carol is on it and the
            // saved calendar gives her a man-day on every counted day of the period, whatever her membership says.
            assertEquals(3.0, capacity.md(w.carol), EPS)
            assertEquals(3, capacity.calendarDays)

            // Without a saved calendar the same days are default days: carol's membership ended, so she counts nothing.
            val defaults = w.capacity(w.t, "2026-01-14", "2026-01-16", connectionIds = emptyList())
            assertEquals(3.0, defaults.md(w.alice), EPS)
            assertEquals(0.0, defaults.md(w.carol), EPS)
            assertEquals(0, defaults.calendarDays)
        }

    @Test
    fun `a stored calendar on a connection outside the scope or a soft-deleted connection is not read`() = runWorld { w ->
        w.sprint(w.t, 1L, "2026-01-12", "2026-01-16")
        w.member(w.t, w.alice)
        w.save(w.t, 1L, storedCalendar("2026-01-12", "2026-01-16", 2.0, emptySet(), CalPerson(w.alice)))

        val scoped = w.capacity(w.t, "2026-01-12", "2026-01-16")
        assertEquals(10.0, scoped.md(w.alice), EPS) // team coefficient 2 x 5 days
        assertEquals(5, scoped.calendarDays)

        val outside = w.capacity(w.t, "2026-01-12", "2026-01-16", connectionIds = emptyList())
        assertEquals(5.0, outside.md(w.alice), EPS, "no scoped connection: no sprint, no calendar - default days")
        assertEquals(0, outside.calendarDays)

        SyncedStubFixture.dataSources().delete(w.connId)
        val deleted = w.capacity(w.t, "2026-01-12", "2026-01-16")
        assertEquals(5.0, deleted.md(w.alice), EPS, "the connection is soft-deleted: its stored calendar is not paired with the sprint")
        assertEquals(0, deleted.calendarDays)
    }

    @Test
    fun `the exact per-person figures are kept unrounded, so a sum of them is the exact sum`() = runWorld { w ->
        // Wed 2026-01-14 only: three people at coefficient 0.5 x cell 0.67 = 0.335 each (exact).
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
        val capacity = w.capacity(w.t, "2026-01-14", "2026-01-14")
        for (account in listOf(w.alice, w.bob, w.carol)) assertEquals(0.335, capacity.md(account), EPS)
        assertEquals(1.005, capacity.total(), EPS, "the exact sum 1.005, not the sum of the rounded figures 1.02")
        assertEquals(1, capacity.days.size)
        assertEquals(1, capacity.calendarDays)
    }
}
