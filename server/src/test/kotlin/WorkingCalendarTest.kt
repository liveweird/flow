package ch.nokillswit

import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.WorkingCalendar
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `metrics/WorkingCalendar.kt` (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "Working days =
 * fractional calendar working days"): pure, no DB. Every span here is expressed as
 * `ZonedDateTime.toInstant().toEpochMilli()` rather than hand-computed epoch arithmetic, so a test
 * failure is never actually a mistake in the test's OWN day-boundary math.
 */
class WorkingCalendarTest {
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val mondayToFriday = setOf(6, 7) // ISO weekday numbers: Saturday, Sunday
    private val noHolidays = emptySet<LocalDate>()

    private fun atStartOfDay(zone: ZoneId, date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun atTime(zone: ZoneId, date: LocalDate, hour: Int): Long =
        date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    @Test
    fun `isWorkingDay excludes configured weekend days and holidays`() {
        val holiday = LocalDate.of(2026, 1, 1) // a Thursday, deliberately not a weekend day
        val calendar = WorkingCalendar(warsaw, mondayToFriday, setOf(holiday))

        assertTrue(calendar.isWorkingDay(LocalDate.of(2026, 1, 5))) // Monday
        assertTrue(calendar.isWorkingDay(LocalDate.of(2026, 1, 9))) // Friday
        assertEquals(false, calendar.isWorkingDay(LocalDate.of(2026, 1, 10))) // Saturday
        assertEquals(false, calendar.isWorkingDay(LocalDate.of(2026, 1, 11))) // Sunday
        assertEquals(false, calendar.isWorkingDay(holiday))
    }

    @Test
    fun `workingDaysBetween sums fractional coverage across a span, skipping weekends`() {
        val calendar = WorkingCalendar(warsaw, mondayToFriday, noHolidays)
        // Monday 12:00 to Wednesday 12:00 (2026-01-05 is a Monday): half of Monday, all of Tuesday, half of Wednesday = 2.0
        val from = atTime(warsaw, LocalDate.of(2026, 1, 5), 12)
        val to = atTime(warsaw, LocalDate.of(2026, 1, 7), 12)
        assertEquals(2.0, calendar.workingDaysBetween(from, to), 1e-9)

        // Friday 00:00 to the following Monday 00:00: only Friday counts (Saturday/Sunday are weekend days) = 1.0
        val fridayStart = atStartOfDay(warsaw, LocalDate.of(2026, 1, 9))
        val nextMondayStart = atStartOfDay(warsaw, LocalDate.of(2026, 1, 12))
        assertEquals(1.0, calendar.workingDaysBetween(fridayStart, nextMondayStart), 1e-9)
    }

    @Test
    fun `workingDaysBetween returns zero for an empty or reversed span`() {
        val calendar = WorkingCalendar(warsaw, mondayToFriday, noHolidays)
        val t = atStartOfDay(warsaw, LocalDate.of(2026, 1, 5))
        assertEquals(0.0, calendar.workingDaysBetween(t, t))
        assertEquals(0.0, calendar.workingDaysBetween(t + 1, t))
    }

    @Test
    fun `workingDaysBetween is additive - wd(a,b) + wd(b,c) equals wd(a,c)`() {
        val calendar = WorkingCalendar(warsaw, mondayToFriday, setOf(LocalDate.of(2026, 3, 17)))
        val random = Random(42)
        val base = atStartOfDay(warsaw, LocalDate.of(2026, 1, 1))
        repeat(200) {
            val a = base + random.nextLong(0, 60L * 24 * 60 * 60 * 1000)
            val b = a + random.nextLong(0, 20L * 24 * 60 * 60 * 1000)
            val c = b + random.nextLong(0, 20L * 24 * 60 * 60 * 1000)
            val ab = calendar.workingDaysBetween(a, b)
            val bc = calendar.workingDaysBetween(b, c)
            val ac = calendar.workingDaysBetween(a, c)
            assertEquals(ac, ab + bc, 1e-6, "wd($a,$b) + wd($b,$c) must equal wd($a,$c)")
        }
    }

    @Test
    fun `a full DST spring-forward day in Europe-Warsaw (23h) still counts as exactly one working day`() {
        // 2026-03-29 is Europe/Warsaw's spring-forward transition (02:00 -> 03:00, a 23h day) —
        // it happens to be a Sunday, so weekendDays is left empty here to isolate the DST math
        // from the (already separately tested) weekend-exclusion rule.
        val calendar = WorkingCalendar(warsaw, emptySet(), noHolidays)
        val day = LocalDate.of(2026, 3, 29)
        val dayStart = ZonedDateTime.of(day.atStartOfDay(), warsaw).toInstant().toEpochMilli()
        val dayEnd = ZonedDateTime.of(day.plusDays(1).atStartOfDay(), warsaw).toInstant().toEpochMilli()
        assertEquals(23L * 60 * 60 * 1000, dayEnd - dayStart, "sanity check: this really is a 23h day")
        assertEquals(1.0, calendar.workingDaysBetween(dayStart, dayEnd), 1e-9)
    }

    @Test
    fun `a full DST fall-back day in Europe-Warsaw (25h) still counts as exactly one working day`() {
        // 2026-10-25 is Europe/Warsaw's fall-back transition (03:00 -> 02:00, a 25h day) — also a
        // Sunday; weekendDays is left empty here for the same isolation reason as the spring test.
        val calendar = WorkingCalendar(warsaw, emptySet(), noHolidays)
        val day = LocalDate.of(2026, 10, 25)
        val dayStart = ZonedDateTime.of(day.atStartOfDay(), warsaw).toInstant().toEpochMilli()
        val dayEnd = ZonedDateTime.of(day.plusDays(1).atStartOfDay(), warsaw).toInstant().toEpochMilli()
        assertEquals(25L * 60 * 60 * 1000, dayEnd - dayStart, "sanity check: this really is a 25h day")
        assertEquals(1.0, calendar.workingDaysBetween(dayStart, dayEnd), 1e-9)
    }

    @Test
    fun `dayOf resolves an instant to its calendar day in the configured zone, across a UTC midnight boundary`() {
        val calendar = WorkingCalendar(warsaw, mondayToFriday, noHolidays)
        // 23:30 UTC on 2026-01-05 is already 00:30 on 2026-01-06 in Europe/Warsaw (UTC+1 in January).
        val utcInstant = ZonedDateTime.of(2026, 1, 5, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 1, 6), calendar.dayOf(utcInstant))
    }

    @Test
    fun `dimDateRows produces one row per calendar day inclusive, with correct working-day flags`() {
        val holiday = LocalDate.of(2026, 1, 7)
        val calendar = WorkingCalendar(warsaw, mondayToFriday, setOf(holiday))
        val from = atStartOfDay(warsaw, LocalDate.of(2026, 1, 5)) // Monday
        val to = atStartOfDay(warsaw, LocalDate.of(2026, 1, 11)) // the following Sunday

        val rows = calendar.dimDateRows(from, to)

        assertEquals(7, rows.size)
        assertEquals(LocalDate.of(2026, 1, 5).toString(), rows.first().day)
        assertEquals(LocalDate.of(2026, 1, 11).toString(), rows.last().day)
        val byDay = rows.associateBy { it.day }
        assertTrue(byDay.getValue(LocalDate.of(2026, 1, 5).toString()).isWorkingDay) // Monday
        assertEquals(false, byDay.getValue(LocalDate.of(2026, 1, 7).toString()).isWorkingDay) // the holiday, a Wednesday
        assertEquals(false, byDay.getValue(LocalDate.of(2026, 1, 10).toString()).isWorkingDay) // Saturday
        assertEquals(false, byDay.getValue(LocalDate.of(2026, 1, 11).toString()).isWorkingDay) // Sunday
        rows.forEach { row -> assertTrue(row.dayEndMs > row.dayStartMs) }
    }

    private fun settings(timeZone: String, weekendDays: List<Int>, holidays: List<String>) = MetricsSettingsResponse(
        configRevision = 1, hoursPerDay = 8.0, timeZone = timeZone, weekendDays = weekendDays, holidays = holidays,
        commitmentGraceMinutes = 0, minSampleSize = 5, agingWindowItems = 50, agingPercentiles = listOf(50, 85),
        backlogWindowSprints = 3, epicDriftDays = 7, updatedAt = 0, updatedByUserId = null,
    )

    @Test
    fun `of builds the calendar from the settings, skipping an unparseable holiday`() {
        val calendar = WorkingCalendar.of(settings("Europe/Warsaw", listOf(6, 7), listOf("2026-01-01", "not-a-date")))

        assertEquals(false, calendar.isWorkingDay(LocalDate.of(2026, 1, 1))) // the configured holiday
        assertEquals(false, calendar.isWorkingDay(LocalDate.of(2026, 1, 10))) // Saturday
        assertTrue(calendar.isWorkingDay(LocalDate.of(2026, 1, 5))) // Monday
        // Zone-aware: 23:30 UTC on Jan 5 is already Jan 6 in Warsaw.
        val utcInstant = ZonedDateTime.of(2026, 1, 5, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 1, 6), calendar.dayOf(utcInstant))
    }

    @Test
    fun `zoneOf falls back to UTC for an unparseable zone id`() {
        assertEquals(warsaw, WorkingCalendar.zoneOf("Europe/Warsaw"))
        assertEquals(ZoneId.of("UTC"), WorkingCalendar.zoneOf("Mars/Olympus_Mons"))
        val calendar = WorkingCalendar.of(settings("Mars/Olympus_Mons", emptyList(), emptyList()))
        val utcInstant = ZonedDateTime.of(2026, 1, 5, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 1, 5), calendar.dayOf(utcInstant))
    }
}
