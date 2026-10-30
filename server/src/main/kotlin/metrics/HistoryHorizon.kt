package ch.nokillswit.metrics

import java.time.LocalDate

/** The shortest history horizon an ADMIN may configure: six months always cover Home's 90 + 90 day trend (>= 181 days). */
const val MIN_HISTORY_HORIZON_MONTHS = 6

/** The longest history horizon: ten years, the existing data-source backfill cap. */
const val MAX_HISTORY_HORIZON_MONTHS = 120

/**
 * The history horizon (domain-model A48): work older than [months] months before today is hidden from the reports. [from] is the
 * first day that still counts (`today` minus [months] months), in the metrics time zone.
 */
data class HistoryHorizon(val months: Int, val from: LocalDate)

/**
 * The horizon for a setting of [months] months (null = off, so null) as seen on [today]. `LocalDate.minusMonths` clamps to the end of a
 * shorter month (31 March minus one month is 28 February), which is the day the horizon takes -- `HistoryHorizonTest` pins it.
 */
fun historyHorizonOf(months: Int?, today: LocalDate): HistoryHorizon? =
    months?.let { HistoryHorizon(it, today.minusMonths(it.toLong())) }

/** The stored setting as a horizon on [today] (the caller's day in the metrics time zone); null when the horizon is off. */
fun MetricsSettingsResponse.historyHorizon(today: LocalDate): HistoryHorizon? = historyHorizonOf(historyHorizonMonths, today)
