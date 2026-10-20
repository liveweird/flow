package ch.nokillswit

import ch.nokillswit.metrics.ClaimSprint
import ch.nokillswit.metrics.MAX_CALENDAR_DAYS
import ch.nokillswit.metrics.SprintCalendarCell
import ch.nokillswit.metrics.SprintCalendarClaim
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintWindowOutcome
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.metrics.calendarCapacityMd
import ch.nokillswit.metrics.claimedDaysBySprint
import ch.nokillswit.metrics.defaultCalendar
import ch.nokillswit.metrics.defaultCapacityDayCount
import ch.nokillswit.metrics.keepingStoredLockedChoices
import ch.nokillswit.metrics.personDayCapacity
import ch.nokillswit.metrics.projectCalendar
import ch.nokillswit.metrics.resolveSprintWindow
import ch.nokillswit.metrics.sprintLoad
import ch.nokillswit.metrics.sprintWindow
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
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
    fun `the per-person per-day man-days sum to the calendar capacity, and a turned-off person or day contributes nothing`() {
        // Team coefficient 2; alice 0.5 and bob 1.5; D2 off; carol off. A cell of 0.5 on an off day (alice D2) and every cell of carol
        // must not appear.
        val tripled = SprintCalendarDocument(
            teamCoefficient = 2.0,
            days = days.take(3).mapIndexed { i, d -> SprintCalendarDay(d, enabled = i != 1) },
            people = listOf(
                person("alice", 0.5, days[0] to 1.0, days[1] to 0.5, days[2] to 0.5),
                person("bob", 1.5, days[0] to 1.0, days[1] to 1.0, days[2] to 0.25),
                person("carol", 1.0, days[0] to 1.0, days[1] to 1.0, days[2] to 1.0, enabled = false),
            ),
        )
        val byPerson = personDayCapacity(tripled)
        // 2 x coefficient x cell, exact: alice 1 x 1.0 and 1 x 0.5; bob 3 x 1.0 and 3 x 0.25.
        fun md(value: String) = BigDecimal(value)
        assertEquals(setOf("alice", "bob"), byPerson.keys, "a turned-off person is absent")
        val aliceDays = byPerson.getValue("alice").keys
        assertEquals(setOf(LocalDate.parse(days[0]), LocalDate.parse(days[2])), aliceDays, "a turned-off day is absent")
        assertEquals(0, md("1.0").compareTo(byPerson.getValue("alice").getValue(LocalDate.parse(days[0]))))
        assertEquals(0, md("0.5").compareTo(byPerson.getValue("alice").getValue(LocalDate.parse(days[2]))))
        assertEquals(0, md("3.0").compareTo(byPerson.getValue("bob").getValue(LocalDate.parse(days[0]))))
        assertEquals(0, md("0.75").compareTo(byPerson.getValue("bob").getValue(LocalDate.parse(days[2]))))
        val exact = byPerson.values.flatMap { it.values }.fold(BigDecimal.ZERO, BigDecimal::add)
        assertEquals(0, md("5.25").compareTo(exact))
        assertEquals(5.25, calendarCapacityMd(tripled), "the calendar capacity is the rounded sum of the per-person per-day map")
        // The identity holds on the shared fixture document too (two counted people, one off, one off day).
        val fixtureExact = personDayCapacity(document).values.flatMap { it.values }.fold(BigDecimal.ZERO, BigDecimal::add)
        assertEquals(calendarCapacityMd(document), fixtureExact.setScale(2, RoundingMode.HALF_UP).toDouble())
        assertEquals(emptyMap(), personDayCapacity(SprintCalendarDocument(1.0, emptyList(), emptyList())))
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
        val plain = defaultCalendar(window, listOf("m2", "m1"), warsaw, emptyMap(), carry = null)
        assertEquals(1.0, plain.teamCoefficient)
        assertEquals(listOf(true, false, false, true, true), plain.days.map { it.enabled })
        assertEquals(listOf("m1", "m2"), plain.people.map { it.accountId })
        assertEquals(6.0, calendarCapacityMd(plain))

        val carried = defaultCalendar(window, listOf("m1", "m2"), warsaw, emptyMap(), carry = document.copy(
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
    fun `a public holiday is always off - in a fresh calendar and over a stored enabled day - and comes back with the stored choice`() {
        val window = sprintWindow(ms("2026-03-06T09:00:00Z"), ms("2026-03-10T15:00:00Z"), null, warsaw)!!
        val withHoliday = WorkingCalendar(
            ZoneId.of("Europe/Warsaw"), setOf(6, 7), mapOf(java.time.LocalDate.of(2026, 3, 9) to "Test holiday"),
        )
        assertEquals(true, withHoliday.isHoliday(java.time.LocalDate.of(2026, 3, 9)))

        val fresh = defaultCalendar(window, listOf("m1"), withHoliday, emptyMap(), carry = null)
        assertEquals(listOf(true, false, false, false, true), fresh.days.map { it.enabled }, "Mon 03-09 is a weekday but a holiday")
        assertEquals(2.0, calendarCapacityMd(fresh))

        // The same stored document (the holiday day saved ENABLED) projects off with the holiday and on without it.
        val stored = SprintCalendarDocument(
            1.0, window.days.map { SprintCalendarDay(it.toString(), true) },
            listOf(person("m1", 1.0, *window.days.map { it.toString() to 1.0 }.toTypedArray())),
        )
        val locked = projectCalendar(stored, window, listOf("m1"), withHoliday, emptyMap(), carry = null)
        assertEquals(listOf(true, true, true, false, true), locked.days.map { it.enabled }, "only the holiday is forced off")
        assertEquals(4.0, calendarCapacityMd(locked))
        val free = projectCalendar(stored, window, listOf("m1"), warsaw, emptyMap(), carry = null)
        assertEquals(listOf(true, true, true, true, true), free.days.map { it.enabled }, "without the holiday the stored choice stands")
        assertEquals(5.0, calendarCapacityMd(free))
    }

    private fun claimSprint(id: Long, from: String, to: String?) =
        ClaimSprint(id, "Sprint $id", ms(from), to?.let(::ms), null)

    private fun claim(id: Long) = SprintCalendarClaim(id, "Sprint $id")

    private fun day(iso: String) = LocalDate.parse(iso)

    // The test name is a measures.md "Pinned by" reference: it cannot wrap.
    @Suppress("MaxLineLength")
    @Test
    fun `a day inside an earlier sprint's window is claimed by it - earlier by start then by id, whatever its length, and a sprint inside another has no day of its own`() {
        val s10 = claimSprint(10, "2026-03-02T09:00:00Z", "2026-03-06T09:00:00Z") // Mon 03-02 .. Fri 03-06
        val s11 = claimSprint(11, "2026-03-06T12:00:00Z", "2026-03-10T12:00:00Z") // Fri 03-06 .. Tue 03-10
        val s12 = claimSprint(12, "2026-03-06T12:00:00Z", "2026-03-08T12:00:00Z") // the same start as 11, a higher id, inside it
        // Given out of order: the kernel sorts by (start, id) itself.
        val claims = claimedDaysBySprint(listOf(s12, s11, s10), warsaw)

        assertNull(claims[10L], "the first sprint holds every day it covers")
        assertEquals(mapOf(day("2026-03-06") to claim(10)), claims[11L], "the Friday the first sprint ends on is its, not the next one's")
        assertEquals(
            mapOf(day("2026-03-06") to claim(10), day("2026-03-07") to claim(11), day("2026-03-08") to claim(11)),
            claims[12L],
            "the tie on the start goes to the lower id, and a sprint wholly inside others has no counted day",
        )

        // Any window length claims: a sprint over the 60-day cap has no calendar of its own but still holds its days.
        val long = claimSprint(1, "2026-01-05T09:00:00Z", "2026-04-30T09:00:00Z")
        val inside = claimSprint(2, "2026-03-02T09:00:00Z", "2026-03-04T09:00:00Z")
        val overLong = claimedDaysBySprint(listOf(inside, long), warsaw)
        assertNull(overLong[1L], "the first sprint holds every day it covers")
        assertEquals(listOf(day("2026-03-02"), day("2026-03-03"), day("2026-03-04")), overLong.getValue(2L).keys.sorted())

        // The 60-day cap only decides who gets a grid: a LATER too-long sprint is claimed on like any other, so its DEFAULT skips the days.
        val head = claimSprint(3, "2026-01-05T09:00:00Z", "2026-01-16T15:00:00Z") // Mon 01-05 .. Fri 01-16
        val tail = claimSprint(4, "2026-01-15T09:00:00Z", "2026-04-30T09:00:00Z") // Thu 01-15 .. Thu 04-30, over the cap
        assertNull(sprintWindow(tail.startAtMs, tail.endAtMs, null, warsaw), "the later sprint has no calendar window")
        val tailClaims = claimedDaysBySprint(listOf(tail, head), warsaw).getValue(4L)
        assertEquals(mapOf(day("2026-01-15") to claim(3), day("2026-01-16") to claim(3)), tailClaims)
        assertEquals(
            defaultCapacityDayCount(tail.startAtMs, tail.endAtMs, null, warsaw, emptyMap()) - 2,
            defaultCapacityDayCount(tail.startAtMs, tail.endAtMs, null, warsaw, tailClaims),
            "Thu and Fri are working days held by the earlier sprint, so the over-long sprint's DEFAULT does not count them again",
        )
        assertEquals(emptyMap(), claimedDaysBySprint(emptyList(), warsaw))
        assertEquals(emptyMap(), claimedDaysBySprint(listOf(s10), warsaw), "a lone sprint claims nothing")
    }

    // The test name is a measures.md "Pinned by" reference: it cannot wrap.
    @Suppress("MaxLineLength")
    @Test
    fun `a claimed day is locked off like a holiday - in a fresh calendar and over a stored enabled day - and the stored choice returns when the claim goes`() {
        // Fri 03-06 .. Tue 03-10; Fri is claimed by the earlier sprint, Sat/Sun are the weekend.
        val window = sprintWindow(ms("2026-03-06T12:00:00Z"), ms("2026-03-10T12:00:00Z"), null, warsaw)!!
        val claimed = mapOf(day("2026-03-06") to claim(10))

        val fresh = defaultCalendar(window, listOf("m1"), warsaw, claimed, carry = null)
        assertEquals(listOf(false, false, false, true, true), fresh.days.map { it.enabled }, "Friday is a weekday but claimed")
        assertEquals(2.0, calendarCapacityMd(fresh))
        assertEquals(2, defaultCapacityDayCount(window.startMs, window.endMs, null, warsaw, claimed), "the DEFAULT counts the same days")
        assertEquals(3, defaultCapacityDayCount(window.startMs, window.endMs, null, warsaw, emptyMap()))

        val stored = SprintCalendarDocument(
            1.0, window.days.map { SprintCalendarDay(it.toString(), true) },
            listOf(person("m1", 1.0, *window.days.map { it.toString() to 1.0 }.toTypedArray())),
        )
        val locked = projectCalendar(stored, window, listOf("m1"), warsaw, claimed, carry = null)
        assertEquals(listOf(false, true, true, true, true), locked.days.map { it.enabled }, "only the claimed day is forced off")
        assertEquals(4.0, calendarCapacityMd(locked))
        val free = projectCalendar(stored, window, listOf("m1"), warsaw, emptyMap(), carry = null)
        assertEquals(listOf(true, true, true, true, true), free.days.map { it.enabled }, "without the claim the stored choice stands")
        assertEquals(5.0, calendarCapacityMd(free))

        // A re-saved GET-shaped document says false on the claimed day: the stored choice (true) comes back, a sent true stays.
        val shown = locked.copy(days = locked.days.map { SprintCalendarDay(it.day, it.enabled) })
        val kept = shown.keepingStoredLockedChoices(stored, warsaw, claimed)
        assertEquals(stored.days, kept.days, "the unchanged re-save equals the stored document")
        val firstSave = fresh.keepingStoredLockedChoices(null, warsaw, claimed)
        assertEquals(true, firstSave.days.first().enabled, "no stored choice: the weekday it would otherwise be")
        assertEquals(false, firstSave.days[1].enabled, "a weekend day stays off")
        val sentOn = stored.keepingStoredLockedChoices(shown, warsaw, claimed)
        assertEquals(stored.days, sentOn.days, "an enabled day sent on a claimed day stays as sent")
    }

    @Test
    fun `projection keeps the stored values, adds the new member and day, drops a vanished day and keeps a former member`() {
        // The sprint moved: now Tue 03-03 .. Mon 03-09 (07 and 08 are the weekend), D1 vanished, 07..09 are new.
        val window = sprintWindow(ms("2026-03-03T09:00:00Z"), ms("2026-03-09T15:00:00Z"), null, warsaw)!!
        val projected = projectCalendar(
            document, window, memberAccountIds = listOf("a", "new"), calendar = warsaw, claimed = emptyMap(), carry = null,
        )

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
