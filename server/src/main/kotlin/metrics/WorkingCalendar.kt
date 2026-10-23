package ch.nokillswit.metrics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The working calendar (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "Working days =
 * fractional calendar working days"): pure, no DB, no Jira shapes — [zoneId]/[weekendDays]
 * (ISO-8601 weekday numbers, 1=Monday..7=Sunday) come off `metrics.settings` and [holidays] (date → name) off the public-holiday
 * dictionary `metrics.public_holidays` (`PublicHolidayService`); `MetricsSettingsService.readCalendar`/`readWithCalendar` is the ONE
 * place that reads both and builds this, so DERIVE, the reports and the capacity page can never see different calendars. A holiday
 * is never a working day, and capacity calendars lock it off (`CapacityKernels.projectCalendar`). Every day boundary is computed via [ZonedDateTime]
 * rather than `24 * 60 * 60 * 1000` arithmetic, so a DST transition day's actual length (23h or 25h
 * in `Europe/Warsaw`) is reflected correctly rather than assumed to be exactly 24 hours.
 */
class WorkingCalendar(
    private val zoneId: ZoneId,
    private val weekendDays: Set<Int>,
    private val holidays: Map<LocalDate, String?> = emptyMap(),
) {
    /**
     * A calendar whose non-working dates are known only as dates, without dictionary names ([holidayName] answers null for them;
     * pure kernels, `dim_date`-backed callers).
     */
    constructor(zoneId: ZoneId, weekendDays: Set<Int>, holidays: Set<LocalDate>) :
        this(zoneId, weekendDays, holidays.associateWith<LocalDate, String?> { null })

    /** The calendar day (in [zoneId]) an epoch-millis instant falls on. */
    fun dayOf(atMs: Long): LocalDate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMs), zoneId).toLocalDate()

    /** Neither a configured weekend day nor a public holiday. */
    fun isWorkingDay(day: LocalDate): Boolean = day.dayOfWeek.value !in weekendDays && day !in holidays

    /** [day] is in the public-holiday dictionary (a holiday on a weekend still is one). */
    fun isHoliday(day: LocalDate): Boolean = day in holidays

    /** A configured weekend day (holidays aside). */
    fun isWeekend(day: LocalDate): Boolean = day.dayOfWeek.value in weekendDays

    /** The dictionary name of the holiday on [day], null when it is none (or the calendar was built from bare dates). */
    fun holidayName(day: LocalDate): String? = holidays[day]

    /** The `[dayStart, dayEnd)` boundary of [day] in [zoneId], as epoch millis. */
    fun dayBoundsMs(day: LocalDate): Pair<Long, Long> {
        val start = day.atStartOfDay(zoneId)
        val end = day.plusDays(1).atStartOfDay(zoneId)
        return start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
    }

    /**
     * Fractional working days covered by the half-open interval `[fromMs, toMs)` — sums, over every
     * calendar day the interval intersects, the COVERED FRACTION of that day's own actual length
     * (DST-safe: a 23h or 25h transition day's fraction is computed against its real length, never
     * against a flat 24h assumption) when [isWorkingDay] flags it. Zero (or negative) spans return
     * `0.0`. Additive by construction: `workingDaysBetween(a, b) + workingDaysBetween(b, c) ==
     * workingDaysBetween(a, c)` for any `a <= b <= c`.
     *
     * O(log holidays) whatever the span: only the first and last day can be partial, every day between is a full 1.0 and they are
     * COUNTED ([WorkingDayIndex]) rather than visited. The result is bit-for-bit the one the old day-by-day running sum gave (the
     * first fraction, then `+ 1.0` once per working day, then the last fraction; [addWholeDays] reproduces that rounding order,
     * `WorkingCalendarEqualityTest` pins it against a copy of the walk).
     */
    fun workingDaysBetween(fromMs: Long, toMs: Long): Double {
        if (toMs <= fromMs) return 0.0
        val firstDay = dayOf(fromMs)
        val lastDay = dayOf(toMs - 1)
        var total = coveredFraction(firstDay, fromMs, toMs)
        if (lastDay.isAfter(firstDay)) {
            total = addWholeDays(total, index.count(firstDay.toEpochDay() + 1, lastDay.toEpochDay() - 1))
            total += coveredFraction(lastDay, fromMs, toMs)
        }
        return total
    }

    /** Whole-week arithmetic plus the holidays and zone-skipped dates, built on the first span measured. */
    private val index: WorkingDayIndex by lazy {
        val skipped = WorkingDayIndex.skippedDates(zoneId) { dayBoundsMs(it).let { (start, end) -> start == end } }
        WorkingDayIndex(weekendDays, holidays.keys, skipped)
    }

    /** The covered fraction of [day]'s own length by `[fromMs, toMs)`; 0.0 when [day] is not a working day or the two do not meet. */
    private fun coveredFraction(day: LocalDate, fromMs: Long, toMs: Long): Double {
        val (dayStartMs, dayEndMs) = dayBoundsMs(day)
        val segmentStart = maxOf(dayStartMs, fromMs)
        val segmentEnd = minOf(dayEndMs, toMs)
        if (segmentEnd <= segmentStart || !isWorkingDay(day)) return 0.0
        return (segmentEnd - segmentStart).toDouble() / (dayEndMs - dayStartMs).toDouble()
    }

    /** One `metrics.dim_date` row — `day` is the ISO date string the table stores (`VARCHAR(10)`, the `backfill_from` precedent). */
    data class DimDateRow(val day: String, val dayStartMs: Long, val dayEndMs: Long, val isWorkingDay: Boolean)

    /** Every calendar day from [dayOf] of [fromMs] to [dayOf] of [toMs], inclusive — `MetricsStore.ensureDimDate`'s wanted-row range. */
    fun dimDateRows(fromMs: Long, toMs: Long): List<DimDateRow> {
        if (toMs < fromMs) return emptyList()
        return dimDateRows(dayOf(fromMs), dayOf(toMs))
    }

    /** Every calendar day from [fromDay] to [toDay], inclusive (empty when [toDay] precedes [fromDay]). */
    fun dimDateRows(fromDay: LocalDate, toDay: LocalDate): List<DimDateRow> {
        val rows = mutableListOf<DimDateRow>()
        var day = fromDay
        while (!day.isAfter(toDay)) {
            val (start, end) = dayBoundsMs(day)
            rows += DimDateRow(day.toString(), start, end, isWorkingDay(day))
            day = day.plusDays(1)
        }
        return rows
    }

    companion object {
        /**
         * [start] with 1.0 added [count] times, rounding exactly as the one-at-a-time sum does: inside a binade (`[2^e, 2^(e+1))`,
         * `e` below 52) adding whole numbers is exact, so a run of them collapses into one addition, and only the step that crosses
         * into the next binade (or starts below 1.0) rounds, once, as the single `+ 1.0` would. At most one iteration per binade.
         */
        internal fun addWholeDays(start: Double, count: Long): Double {
            var total = start
            var left = count
            while (left > 0) {
                if (total >= 1.0 && total < TWO_POW_52) {
                    val exactSteps = Math.ceil(Math.scalb(1.0, Math.getExponent(total) + 1) - total).toLong() - 1
                    val jump = minOf(exactSteps, left)
                    total += jump.toDouble()
                    left -= jump
                }
                if (left > 0) {
                    total += 1.0
                    left--
                }
            }
            return total
        }

        private const val TWO_POW_52 = 4503599627370496.0

        /** The configured zone (`metrics.settings.time_zone`), UTC when the stored id is unparseable — the one place it is resolved. */
        fun zoneOf(timeZone: String): ZoneId = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.of("UTC"))

        /**
         * The configured calendar (`metrics.settings` zone and weekend days plus the public-holiday dictionary's [holidays], ISO date
         * → name) — built only by `MetricsSettingsService`, so DERIVE and every report share it. A stored holiday date that no
         * longer parses is skipped the same way everywhere.
         */
        fun of(settings: MetricsSettingsResponse, holidays: Map<String, String>): WorkingCalendar {
            val parsed = holidays.mapNotNull { (iso, name) -> runCatching { LocalDate.parse(iso) }.getOrNull()?.let { it to name } }.toMap()
            return WorkingCalendar(zoneOf(settings.timeZone), settings.weekendDays.toSet(), parsed)
        }
    }
}
