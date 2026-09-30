package ch.nokillswit.infra.time

/**
 * Milliseconds in a fixed 24-hour day — for retention windows, staleness thresholds and elapsed-day
 * figures, where a constant length is the intent. Calendar-day boundaries (a DST day is 23h or 25h)
 * come from `metrics/WorkingCalendar.kt`, never from this constant.
 */
const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/** Milliseconds in a minute — for minute-denominated config (intervals, grace periods, relative JQL windows). */
const val MILLIS_PER_MINUTE = 60_000L
