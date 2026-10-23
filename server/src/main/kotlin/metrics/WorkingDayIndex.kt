package ch.nokillswit.metrics

import java.time.LocalDate
import java.time.ZoneId

/**
 * How many FULL calendar days of a span count as working days, in O(log holidays) and without a table: whole-week arithmetic over the
 * configured weekend days minus a sorted array of the dates that are non-working although their weekday is not (the public holidays
 * on a working weekday, and the dates a zone skips outright). Memory is 7 booleans plus one `LongArray` of at most the dictionary's
 * size; built once per [WorkingCalendar] (lazily, on its first `workingDaysBetween`), a few microseconds. It replaces the per-day
 * walk that `WorkingCalendar.workingDaysBetween` used to make for every span (a `LocalDateTime` and boxed ints per day; the Aging
 * WIP request's age of its 500 listed items was ~100 MiB of garbage, `build-times.md` WHY 32).
 */
internal class WorkingDayIndex(weekendDays: Set<Int>, holidays: Collection<LocalDate>, skippedDates: Collection<LocalDate>) {
    /** Indexed by `floorMod(epochDay + 3, 7)` (1970-01-01 was a Thursday, ISO 4, so index 3): is that weekday a working weekday? */
    private val workingWeekday = BooleanArray(DAYS_PER_WEEK) { (it + 1) !in weekendDays }
    private val workingPerWeek = workingWeekday.count { it }
    private val offDays: LongArray = (holidays + skippedDates).map { it.toEpochDay() }
        .filter { workingWeekday[weekdayIndex(it)] }.distinct().sorted().toLongArray()

    /** Working days among the calendar days `[firstEpochDay, lastEpochDay]`, both inclusive (0 when the range is empty). */
    fun count(firstEpochDay: Long, lastEpochDay: Long): Long {
        if (lastEpochDay < firstEpochDay) return 0L
        val span = lastEpochDay - firstEpochDay + 1
        val weeks = span / DAYS_PER_WEEK
        var weekdays = weeks * workingPerWeek
        for (offset in 0 until span % DAYS_PER_WEEK) {
            if (workingWeekday[weekdayIndex(firstEpochDay + weeks * DAYS_PER_WEEK + offset)]) weekdays++
        }
        return weekdays - (firstAtLeast(lastEpochDay + 1) - firstAtLeast(firstEpochDay))
    }

    /** The index in [offDays] of the first date at or after [epochDay] (so a difference of two is the dates between them). */
    private fun firstAtLeast(epochDay: Long): Int {
        var low = 0
        var high = offDays.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (offDays[mid] < epochDay) low = mid + 1 else high = mid
        }
        return low
    }

    private fun weekdayIndex(epochDay: Long): Int = Math.floorMod(epochDay + 3, DAYS_PER_WEEK.toLong()).toInt()

    companion object {
        private const val DAYS_PER_WEEK = 7
        private const val HOURS_PER_DAY = 24L

        /**
         * The dates [zone] skips outright (Pacific/Apia 2011-12-30), whose `[dayStart, dayEnd)` is empty ([isEmptyDay]): the per-day
         * walk counted them as 0, so they are non-working here too. Only a historical gap of a day or more can do it; every real
         * zone's list is empty or a handful, and a fixed-offset zone has no transitions at all.
         */
        fun skippedDates(zone: ZoneId, isEmptyDay: (LocalDate) -> Boolean): List<LocalDate> =
            zone.rules.transitions.filter { it.isGap && it.duration.toHours() >= HOURS_PER_DAY }.flatMap { gap ->
                generateSequence(gap.dateTimeBefore.toLocalDate()) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(gap.dateTimeAfter.toLocalDate()) }.filter(isEmptyDay).toList()
            }
    }
}
