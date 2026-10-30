package ch.nokillswit

import ch.nokillswit.metrics.HistoryHorizon
import ch.nokillswit.metrics.MAX_HISTORY_HORIZON_MONTHS
import ch.nokillswit.metrics.MIN_HISTORY_HORIZON_MONTHS
import ch.nokillswit.metrics.MetricsSettingsRequest
import ch.nokillswit.metrics.historyHorizonOf
import ch.nokillswit.metrics.validateMetricsSettings
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The pure kernel and validation of the history horizon (domain-model A48): no database, no clock. */
class HistoryHorizonTest {

    private val valid = MetricsSettingsRequest(
        hoursPerDay = 8.0,
        timeZone = "Europe/Warsaw",
        weekendDays = listOf(6, 7),
        commitmentGraceMinutes = 0,
        minSampleSize = 5,
        agingWindowItems = 50,
        agingPercentiles = listOf(50, 85, 95),
        backlogWindowSprints = 3,
        epicDriftDays = 14,
        epicEstimateGapTolerancePercent = 25,
        historyHorizonMonths = 24,
    )

    @Test
    fun `off is null and a number is months before today`() {
        val today = LocalDate.of(2026, 10, 30)
        assertNull(historyHorizonOf(null, today))
        assertEquals(HistoryHorizon(24, LocalDate.of(2024, 10, 30)), historyHorizonOf(24, today))
        assertEquals(HistoryHorizon(6, LocalDate.of(2026, 4, 30)), historyHorizonOf(6, today))
        assertEquals(HistoryHorizon(120, LocalDate.of(2016, 10, 30)), historyHorizonOf(120, today))
    }

    @Test
    fun `a month-end clamps to the last day of the shorter month`() {
        // Java's minusMonths keeps the day-of-month when it exists and otherwise takes the month's last day; the horizon is that day.
        assertEquals(LocalDate.of(2026, 2, 28), historyHorizonOf(7, LocalDate.of(2026, 9, 30))?.from)
        assertEquals(LocalDate.of(2025, 2, 28), historyHorizonOf(6, LocalDate.of(2025, 8, 31))?.from)
        assertEquals(LocalDate.of(2023, 2, 28), historyHorizonOf(12, LocalDate.of(2024, 2, 29))?.from, "a leap day lands on 28 February")
        assertEquals(LocalDate.of(2024, 2, 28), historyHorizonOf(12, LocalDate.of(2025, 2, 28))?.from, "28 February stays 28 February")
    }

    @Test
    fun `validation accepts null and 6 to 120 and refuses everything else`() {
        validateMetricsSettings(valid.copy(historyHorizonMonths = null))
        validateMetricsSettings(valid.copy(historyHorizonMonths = MIN_HISTORY_HORIZON_MONTHS))
        validateMetricsSettings(valid.copy(historyHorizonMonths = MAX_HISTORY_HORIZON_MONTHS))
        for (months in listOf(Int.MIN_VALUE, -1, 0, 1, 5, 121, 1200, Int.MAX_VALUE)) {
            assertFailsWith<BadRequestException>("months = $months") { validateMetricsSettings(valid.copy(historyHorizonMonths = months)) }
        }
    }

    @Test
    fun `the bounds are the ones the V39 CHECK enforces`() {
        assertEquals(6, MIN_HISTORY_HORIZON_MONTHS)
        assertEquals(120, MAX_HISTORY_HORIZON_MONTHS)
    }
}
