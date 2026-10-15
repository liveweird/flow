package ch.nokillswit

import ch.nokillswit.metrics.MAX_CALENDAR_DAYS
import ch.nokillswit.metrics.SprintCalendarCell
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintWindowOutcome
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.metrics.calendarCapacityMd
import ch.nokillswit.metrics.defaultCalendar
import ch.nokillswit.metrics.projectCalendar
import ch.nokillswit.metrics.resolveSprintWindow
import ch.nokillswit.metrics.sprintLoad
import ch.nokillswit.metrics.sprintWindow
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The pure capacity kernels (A32): the formula, the sprint window, the default and re-projected documents. No DB. */
class CapacityKernelsTest {
    private val warsaw = WorkingCalendar(ZoneId.of("Europe/Warsaw"), weekendDays = setOf(6, 7), holidays = emptySet())

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun person(id: String, coefficient: Double, vararg cells: Pair<String, Double>, enabled: Boolean = true) =
        SprintCalendarPerson(id, enabled, coefficient, cells.map { SprintCalendarCell(it.first, it.second) })

    /** Five days D1..D5 with D2 off, two counted people and one off person. */
    private val days = listOf("2026-03-02", "2026-03-03", "2026-03-04", "2026-03-05", "2026-03-06")
    private val document = SprintCalendarDocument(
        teamCoefficient = 1.2,
        days = days.mapIndexed { i, d -> SprintCalendarDay(d, enabled = i != 1) },
        people = listOf(
            person("a", 0.8, days[0] to 1.0, days[1] to 1.0, days[2] to 0.5, days[3] to 1.0, days[4] to 0.5),
            person("b", 1.2, days[0] to 0.5, days[1] to 0.5, days[2] to 1.0, days[3] to 0.25, days[4] to 1.0),
            person("c", 1.0, days[0] to 1.0, days[1] to 1.0, days[2] to 1.0, days[3] to 1.0, days[4] to 1.0, enabled = false),
        ),
    )

    @Test
    fun `the formula is team coefficient times the people times their coefficient times the counted cells`() {
        // a: 0.8 x (1 + 0.5 + 1 + 0.5) = 2.4; b: 1.2 x (0.5 + 1 + 0.25 + 1) = 3.3; c is off, D2 is off; 1.2 x 5.7 = 6.84.
        assertEquals(6.84, calendarCapacityMd(document))
    }

    @Test
    fun `the figure is exact decimal arithmetic rounded half-up to two decimals at the end`() {
        val half = SprintCalendarDocument(
            1.0, listOf(SprintCalendarDay("2026-03-02", true)), listOf(person("a", 1.05, "2026-03-02" to 0.5)),
        )
        assertEquals(0.53, calendarCapacityMd(half), "1.05 x 0.5 = 0.525 rounds half-up")
        val drift = SprintCalendarDocument(
            1.0, days.take(2).map { SprintCalendarDay(it, true) }, listOf(person("a", 1.0, days[0] to 0.1, days[1] to 0.2)),
        )
        assertEquals(0.3, calendarCapacityMd(drift), "0.1 + 0.2 carries no binary drift")
        assertEquals(0.0, calendarCapacityMd(SprintCalendarDocument(1.0, emptyList(), emptyList())))
    }

    @Test
    fun `a day with no cell for a person counts zero`() {
        val gap = document.copy(people = listOf(person("a", 1.0, days[0] to 1.0)))
        assertEquals(1.2, calendarCapacityMd(gap))
    }

    @Test
    fun `the window spans calendar days in the configured zone across the DST change`() {
        // Fri 2026-03-27 12:00 CET .. Mon 2026-03-30 12:00 CEST: four calendar days although the span is 71 hours.
        val window = assertIs<SprintWindowOutcome.Window>(
            resolveSprintWindow(ms("2026-03-27T11:00:00Z"), ms("2026-03-30T10:00:00Z"), null, warsaw),
        ).window
        assertEquals(listOf("2026-03-27", "2026-03-28", "2026-03-29", "2026-03-30"), window.days.map { it.toString() })
    }

    @Test
    fun `an end at midnight does not add a day, and the end falls back to the completion then the start`() {
        // 00:00 Warsaw on Mar 11 (CET) is 23:00Z on Mar 10.
        val midnight = sprintWindow(ms("2026-03-09T09:00:00Z"), ms("2026-03-10T23:00:00Z"), null, warsaw)!!
        assertEquals("2026-03-10", midnight.toDay.toString())
        val viaComplete = sprintWindow(ms("2026-03-09T09:00:00Z"), null, ms("2026-03-11T09:00:00Z"), warsaw)!!
        assertEquals("2026-03-11", viaComplete.toDay.toString())
        val single = sprintWindow(ms("2026-03-09T09:00:00Z"), null, null, warsaw)!!
        assertEquals(single.fromDay, single.toDay)
        val inverted = sprintWindow(ms("2026-03-09T09:00:00Z"), ms("2026-03-01T09:00:00Z"), null, warsaw)!!
        assertEquals(1, inverted.dayCount)
    }

    @Test
    fun `no start date is no window, and the window is capped at 60 days`() {
        assertEquals(SprintWindowOutcome.NoStart, resolveSprintWindow(null, ms("2026-03-10T09:00:00Z"), null, warsaw))
        assertNull(sprintWindow(null, null, null, warsaw))
        val start = ms("2026-03-02T09:00:00Z")
        val day = 24L * 3600 * 1000
        assertIs<SprintWindowOutcome.Window>(resolveSprintWindow(start, start + (MAX_CALENDAR_DAYS - 1) * day, null, warsaw))
        val tooLong = assertIs<SprintWindowOutcome.TooLong>(resolveSprintWindow(start, start + MAX_CALENDAR_DAYS * day, null, warsaw))
        assertEquals(MAX_CALENDAR_DAYS + 1, tooLong.days)
        assertNull(sprintWindow(start, start + MAX_CALENDAR_DAYS * day, null, warsaw))
    }

    @Test
    fun `an absurd end instant is far over the cap, never wrapped back under it`() {
        val tooLong = assertIs<SprintWindowOutcome.TooLong>(resolveSprintWindow(0L, Long.MAX_VALUE, null, warsaw))
        assertEquals(Int.MAX_VALUE, tooLong.days)
        assertNull(sprintWindow(0L, Long.MAX_VALUE, null, warsaw))
    }

    @Test
    fun `the default calendar counts working days and members only, coefficients alone carry`() {
        val window = sprintWindow(ms("2026-03-06T09:00:00Z"), ms("2026-03-10T15:00:00Z"), null, warsaw)!!
        val plain = defaultCalendar(window, listOf("m2", "m1"), warsaw, carry = null)
        assertEquals(1.0, plain.teamCoefficient)
        assertEquals(listOf(true, false, false, true, true), plain.days.map { it.enabled })
        assertEquals(listOf("m1", "m2"), plain.people.map { it.accountId })
        assertEquals(6.0, calendarCapacityMd(plain))

        val carried = defaultCalendar(window, listOf("m1", "m2"), warsaw, carry = document.copy(
            teamCoefficient = 0.5,
            people = listOf(person("m1", 0.7, "x" to 0.0, enabled = false)),
        ))
        assertEquals(0.5, carried.teamCoefficient)
        assertEquals(listOf(0.7, 1.0), carried.people.map { it.coefficient }, "an absent person gets 1")
        assertEquals(listOf(true, true), carried.people.map { it.enabled }, "turn-offs never carry")
        assertEquals(listOf(true, false, false, true, true), carried.days.map { it.enabled })
        assertEquals(1.0, carried.people[0].cells.first().availability, "cells never carry")
    }

    @Test
    fun `projection keeps the stored values, adds the new member and day, drops a vanished day and keeps a former member`() {
        // The sprint moved: now Tue 03-03 .. Mon 03-09 (07 and 08 are the weekend), D1 vanished, 07..09 are new.
        val window = sprintWindow(ms("2026-03-03T09:00:00Z"), ms("2026-03-09T15:00:00Z"), null, warsaw)!!
        val projected = projectCalendar(document, window, memberAccountIds = listOf("a", "new"), calendar = warsaw, carry = null)

        assertEquals(window.days.map { it.toString() }, projected.days.map { it.day })
        assertEquals(
            listOf(false, true, true, true, false, false, true),
            projected.days.map { it.enabled },
            "03-03 keeps its stored OFF, 03-04..03-06 their stored ON, the new days take the working-day default",
        )
        assertEquals(listOf("a", "b", "c", "new"), projected.people.map { it.accountId }, "b and c are kept though not members")
        val a = projected.people.single { it.accountId == "a" }
        assertEquals(0.8, a.coefficient)
        assertEquals(0.5, a.cells.single { it.day == "2026-03-04" }.availability, "a stored cell is kept")
        assertEquals(1.0, a.cells.single { it.day == "2026-03-09" }.availability, "a new day gets a full cell")
        assertEquals(false, projected.people.single { it.accountId == "c" }.enabled, "a stored off person stays off")
        val newcomer = projected.people.single { it.accountId == "new" }
        assertEquals(true, newcomer.enabled)
        assertEquals(1.0, newcomer.coefficient)
        assertEquals(1.2, projected.teamCoefficient)
        // Counted days: 03-04, 03-05, 03-06, 03-09. a: 0.8 x (0.5 + 1 + 0.5 + 1) = 2.4; b: 1.2 x (1 + 0.25 + 1 + 1) = 3.9;
        // new: 1 x 4 full cells = 4; b and c: c is off. (2.4 + 3.9 + 4) x 1.2 = 12.36.
        assertEquals(12.36, calendarCapacityMd(projected))
    }

    @Test
    fun `the load is null without a capacity, with a zero one or past the NUMERIC(8,4) maximum, and exact at the maximum`() {
        assertEquals(2.5, sprintLoad(10.0, 4.0))
        assertNull(sprintLoad(10.0, null))
        assertNull(sprintLoad(10.0, 0.0))
        assertEquals(9999.9999, sprintLoad(9999.9999, 1.0))
        assertNull(sprintLoad(100.0, 0.01), "10000 does not fit NUMERIC(8, 4)")
    }
}
