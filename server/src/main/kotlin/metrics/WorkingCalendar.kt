package ch.nokillswit.metrics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The working calendar (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "Working days =
 * fractional calendar working days"): pure, no DB, no Jira shapes — [zoneId]/[weekendDays]
 * (ISO-8601 weekday numbers, 1=Monday..7=Sunday)/[holidays] (ISO dates) come straight off
 * `metrics.settings` (`MetricsConfigService`). Every day boundary is computed via [ZonedDateTime]
 * rather than `24 * 60 * 60 * 1000` arithmetic, so a DST transition day's actual length (23h or 25h
 * in `Europe/Warsaw`) is reflected correctly rather than assumed to be exactly 24 hours.
 */
class WorkingCalendar(
    private val zoneId: ZoneId,
    private val weekendDays: Set<Int>,
    private val holidays: Set<LocalDate>,
) {
    /** The calendar day (in [zoneId]) an epoch-millis instant falls on. */
    fun dayOf(atMs: Long): LocalDate = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMs), zoneId).toLocalDate()

    /** Neither a configured weekend day nor a configured holiday. */
    fun isWorkingDay(day: LocalDate): Boolean = day.dayOfWeek.value !in weekendDays && day !in holidays

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
     */
    fun workingDaysBetween(fromMs: Long, toMs: Long): Double {
        if (toMs <= fromMs) return 0.0
        var total = 0.0
        var cursorDay = dayOf(fromMs)
        while (true) {
            val (dayStartMs, dayEndMs) = dayBoundsMs(cursorDay)
            if (dayStartMs >= toMs) break
            val segmentStart = maxOf(dayStartMs, fromMs)
            val segmentEnd = minOf(dayEndMs, toMs)
            if (segmentEnd > segmentStart && isWorkingDay(cursorDay)) {
                val dayLengthMs = (dayEndMs - dayStartMs).toDouble()
                total += (segmentEnd - segmentStart).toDouble() / dayLengthMs
            }
            cursorDay = cursorDay.plusDays(1)
        }
        return total
    }

    /** One `metrics.dim_date` row — `day` is the ISO date string the table stores (`VARCHAR(10)`, the `backfill_from` precedent). */
    data class DimDateRow(val day: String, val dayStartMs: Long, val dayEndMs: Long, val isWorkingDay: Boolean)

    /** Every calendar day from [dayOf] of [fromMs] to [dayOf] of [toMs], inclusive — `MetricsDeriver`'s `dim_date` upsert range. */
    fun dimDateRows(fromMs: Long, toMs: Long): List<DimDateRow> {
        if (toMs < fromMs) return emptyList()
        val fromDay = dayOf(fromMs)
        val toDay = dayOf(toMs)
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
        /** The configured zone (`metrics.settings.time_zone`), UTC when the stored id is unparseable — the one place it is resolved. */
        fun zoneOf(timeZone: String): ZoneId = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.of("UTC"))

        /**
         * The configured calendar (`metrics.settings` zone, weekend days and holidays) — the one builder DERIVE and every report
         * share, so a stored holiday that no longer parses is skipped the same way in both.
         */
        fun of(settings: MetricsSettingsResponse): WorkingCalendar {
            val holidays = settings.holidays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
            return WorkingCalendar(zoneOf(settings.timeZone), settings.weekendDays.toSet(), holidays)
        }
    }
}
