package ch.nokillswit

import ch.nokillswit.metrics.SprintCalendarCell
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.StoredSprintCalendar
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.CostCapacityDays
import ch.nokillswit.reports.CostMembership
import ch.nokillswit.reports.CostSprint
import ch.nokillswit.reports.computeCostCapacity
import ch.nokillswit.reports.periodDaysOf
import ch.nokillswit.reports.sprintDayOwners
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The exact capacity of [accountId] over the period (0 for a person with none) - a test oracle over the per-day map. */
internal fun CostCapacityDays.exactFor(accountId: String): BigDecimal =
    perPersonByDay[accountId]?.values?.fold(BigDecimal.ZERO, BigDecimal::add) ?: BigDecimal.ZERO

/** The pure helpers of the per-person capacity kernel (no database): the day map and the default-day rule. */
class CostCapacityDaysTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val calendar = WorkingCalendar(warsaw, weekendDays = setOf(6, 7), holidays = setOf(LocalDate.parse("2026-01-06")))

    private fun ms(day: String, hour: Int = 9) = Instant.parse("${day}T%02d:00:00Z".format(hour)).toEpochMilli()

    private fun sprint(id: Long, start: String, end: String) = CostSprint(1u, id, "S$id", ms(start), ms(end, 15), null)

    @Test
    fun `the period days are the configured-zone days of the half-open window`() {
        // 2026-01-05 00:00 .. 2026-01-08 00:00 Warsaw is three days, the exclusive end adding none.
        val from = calendar.dayBoundsMs(LocalDate.parse("2026-01-05")).first
        val to = calendar.dayBoundsMs(LocalDate.parse("2026-01-07")).second
        assertEquals(listOf("2026-01-05", "2026-01-06", "2026-01-07"), periodDaysOf(calendar, from, to).map { it.toString() })
        assertEquals(emptyList(), periodDaysOf(calendar, to, to))
    }

    @Test
    fun `the first sprint owns a day two sprints share, and a sprint outside the period owns nothing`() {
        val days = periodDaysOf(calendar, ms("2026-01-12", 0), ms("2026-01-23", 23))
        val owners = sprintDayOwners(
            listOf(sprint(1, "2026-01-12", "2026-01-16"), sprint(2, "2026-01-14", "2026-01-20"), sprint(3, "2026-03-01", "2026-03-05")),
            days, calendar,
        )
        assertEquals(1L, owners.getValue(LocalDate.parse("2026-01-14")).sprintId)
        assertEquals(1L, owners.getValue(LocalDate.parse("2026-01-16")).sprintId)
        assertEquals(2L, owners.getValue(LocalDate.parse("2026-01-17")).sprintId)
        assertEquals(null, owners[LocalDate.parse("2026-01-21")])
        assertEquals(9, owners.size)
    }

    @Test
    fun `with no saved calendar a member counts one man-day on a working day, from the day the membership starts`() {
        val days = periodDaysOf(calendar, ms("2026-01-02", 0), ms("2026-01-09", 23)) // Fri 2nd .. Fri 9th; the 6th is a holiday
        val joined = calendar.dayBoundsMs(LocalDate.parse("2026-01-07")).first
        val result = computeCostCapacity(
            days, emptyMap(), emptyMap(),
            listOf(CostMembership("a", 0L, null), CostMembership("b", joined, null)), calendar,
        )
        // a: Jan 2, 5, 7, 8, 9 (the weekend 3-4 and the holiday the 6th count nothing); b: Jan 7, 8, 9.
        assertEquals(BigDecimal(5), result.exactFor("a"))
        assertEquals(BigDecimal(3), result.exactFor("b"))
        assertEquals(BigDecimal.ZERO, result.exactFor("nobody"))
        assertEquals(0, result.calendarDays)
        assertEquals(8, result.days.size)
    }

    @Test
    fun `a stored calendar is paired only with the sprint row of its own connection`() {
        val days = periodDaysOf(calendar, ms("2026-01-12", 0), ms("2026-01-16", 23))
        val owners = sprintDayOwners(listOf(sprint(5, "2026-01-12", "2026-01-16")), days, calendar) // connection 1
        val document = SprintCalendarDocument(
            3.0,
            days.map { SprintCalendarDay(it.toString(), true) },
            listOf(SprintCalendarPerson("a", true, 1.0, days.map { SprintCalendarCell(it.toString(), 1.0) })),
        )
        val stored = StoredSprintCalendar(7u, ms("2026-01-12"), document)
        val members = listOf(CostMembership("a", 0L, null))

        // The same sprint id saved on connection 2 only: connection 1's row is a default-day sprint (1 MD a day, not 3).
        val other = computeCostCapacity(days, owners, mapOf((2u to 5L) to stored), members, calendar)
        assertEquals(0, other.calendarDays)
        assertEquals(BigDecimal(5), other.exactFor("a"))
        // Saved on connection 1: a calendar day at the team coefficient 3.
        val own = computeCostCapacity(days, owners, mapOf((1u to 5L) to stored), members, calendar)
        assertEquals(5, own.calendarDays)
        assertEquals(15, own.exactFor("a").toInt())
    }
}
