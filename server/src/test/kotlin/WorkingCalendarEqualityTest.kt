package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `WorkingCalendar.workingDaysBetween` (week arithmetic over a holiday index, `WorkingDayIndex`) against the day-by-day walk it
 * replaced (`build-times.md` WHY 32), kept here as the reference: the answers must agree to the last bit, not to a tolerance,
 * because DERIVE stores them and the report digests pin them. Seeded, so a failure replays; the case grid covers holidays (also on
 * weekends and on the span's own first and last day), partial first and last days, same-day, adjacent-day and reversed spans,
 * year boundaries, DST zones (23h/25h days, half-hour shifts) and zones that skip a whole calendar date.
 */
class WorkingCalendarEqualityTest {
    /** The pre-WHY-32 implementation, verbatim: one `dayBoundsMs` per calendar day from the first to the last the span touches. */
    private fun referenceWalk(calendar: WorkingCalendar, fromMs: Long, toMs: Long): Double {
        if (toMs <= fromMs) return 0.0
        var total = 0.0
        var cursorDay = calendar.dayOf(fromMs)
        while (true) {
            val (dayStartMs, dayEndMs) = calendar.dayBoundsMs(cursorDay)
            if (dayStartMs >= toMs) break
            val segmentStart = maxOf(dayStartMs, fromMs)
            val segmentEnd = minOf(dayEndMs, toMs)
            if (segmentEnd > segmentStart && calendar.isWorkingDay(cursorDay)) {
                val dayLengthMs = (dayEndMs - dayStartMs).toDouble()
                total += (segmentEnd - segmentStart).toDouble() / dayLengthMs
            }
            cursorDay = cursorDay.plusDays(1)
        }
        return total
    }

    private fun assertSame(calendar: WorkingCalendar, fromMs: Long, toMs: Long, label: String) {
        val expected = referenceWalk(calendar, fromMs, toMs)
        val actual = calendar.workingDaysBetween(fromMs, toMs)
        assertEquals(expected.toRawBits(), actual.toRawBits(), "$label: [$fromMs, $toMs) expected $expected but was $actual")
    }

    private fun randomHolidays(random: Random, around: LocalDate): Set<LocalDate> =
        (0 until random.nextInt(0, 40)).map { around.plusDays(random.nextLong(-1_500, 1_500)) }.toSet()

    private val weekendChoices = listOf(setOf(6, 7), setOf(5, 6), setOf(7), emptySet(), setOf(1, 2, 3, 4, 5, 6, 7), setOf(3))

    private val zones = listOf(
        "Europe/Warsaw", "UTC", "America/New_York", "Asia/Kolkata", "Australia/Lord_Howe", "America/Sao_Paulo", "Pacific/Apia",
        "Pacific/Kiritimati", "Asia/Tokyo",
    ).map(ZoneId::of)

    @Test
    fun `workingDaysBetween equals the day-by-day walk bit for bit over random calendars and spans`() {
        val random = Random(20261023)
        val epoch = LocalDate.of(2018, 1, 1)
        var cases = 0
        repeat(120) { calendarNo ->
            val zone = zones[calendarNo % zones.size]
            val weekend = weekendChoices[random.nextInt(weekendChoices.size)]
            val calendar = WorkingCalendar(zone, weekend, randomHolidays(random, epoch.plusDays(1_000)))
            repeat(60) {
                val startDay = epoch.plusDays(random.nextLong(0, 2_000))
                val midnight = startDay.atStartOfDay(zone).toInstant().toEpochMilli()
                val from = when (random.nextInt(4)) {
                    0 -> midnight // a span starting on a day boundary: the first day is whole
                    else -> midnight + random.nextLong(0, 86_400_000L)
                }
                val length = when (random.nextInt(6)) {
                    0 -> random.nextLong(-3 * 86_400_000L, 3_600_000L) // reversed, empty and tiny
                    1 -> random.nextLong(0, 2 * 86_400_000L) // same or adjacent day
                    2 -> random.nextLong(0, 15 * 86_400_000L)
                    3 -> random.nextLong(0, 400 * 86_400_000L) // across a year boundary
                    4 -> random.nextInt(0, 400) * 86_400_000L // whole days
                    else -> random.nextLong(0, 1_500 * 86_400_000L)
                }
                assertSame(calendar, from, from + length, "$zone case ${cases++}")
            }
        }
        assertEquals(7_200, cases)
    }

    @Test
    fun `workingDaysBetween equals the walk for spans that start and end exactly on holidays and day boundaries`() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        val holidays = setOf(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 6), LocalDate.of(2026, 4, 6),
            LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 1),
        )
        val calendar = WorkingCalendar(warsaw, setOf(6, 7), holidays)
        val days = generateSequence(LocalDate.of(2025, 12, 20)) { it.plusDays(1) }.take(400).toList()
        val random = Random(7)
        repeat(3_000) {
            val a = days[random.nextInt(days.size)]
            val b = days[random.nextInt(days.size)]
            val fromOffset = listOf(0L, 1L, 43_200_000L, 86_399_999L)[random.nextInt(4)]
            val toOffset = listOf(0L, 1L, 43_200_000L, 86_399_999L)[random.nextInt(4)]
            val from = a.atStartOfDay(warsaw).toInstant().toEpochMilli() + fromOffset
            val to = b.atStartOfDay(warsaw).toInstant().toEpochMilli() + toOffset
            assertSame(calendar, from, to, "boundary $a+$fromOffset .. $b+$toOffset")
        }
    }

    @Test
    fun `workingDaysBetween equals the walk across the dates a zone skips and its DST days`() {
        val skipping = listOf(
            "Pacific/Apia" to LocalDate.of(2011, 12, 30), // the whole of 2011-12-30 never happened
            "Pacific/Kiritimati" to LocalDate.of(1994, 12, 31),
            "Europe/Warsaw" to LocalDate.of(2026, 3, 29), // a 23h day
            "Europe/Warsaw" to LocalDate.of(2026, 10, 25), // a 25h day
            "Australia/Lord_Howe" to LocalDate.of(2026, 4, 5), // a half-hour shift
        )
        val random = Random(99)
        for ((zoneId, around) in skipping) {
            val zone = ZoneId.of(zoneId)
            for (weekend in listOf(setOf(6, 7), emptySet())) {
                val calendar = WorkingCalendar(zone, weekend, setOf(around.plusDays(3)))
                repeat(400) {
                    val fromDay = around.minusDays(random.nextLong(0, 12))
                    val toDay = around.plusDays(random.nextLong(0, 12))
                    val from = fromDay.atStartOfDay(zone).toInstant().toEpochMilli() + random.nextLong(0, 86_400_000L)
                    val to = toDay.atStartOfDay(zone).toInstant().toEpochMilli() + random.nextLong(0, 86_400_000L)
                    assertSame(calendar, from, to, "$zoneId around $around")
                }
            }
        }
    }

    @Test
    fun `addWholeDays rounds like adding 1 point 0 one at a time`() {
        val random = Random(5)
        repeat(5_000) {
            val start = when (random.nextInt(3)) {
                0 -> 0.0
                1 -> random.nextDouble()
                else -> random.nextDouble() * 1_000
            }
            val count = random.nextLong(0, 3_000)
            var expected = start
            repeat(count.toInt()) { expected += 1.0 }
            assertEquals(expected.toRawBits(), WorkingCalendar.addWholeDays(start, count).toRawBits(), "start $start + $count")
        }
    }
}
