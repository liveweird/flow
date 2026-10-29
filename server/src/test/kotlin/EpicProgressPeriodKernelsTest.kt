package ch.nokillswit

import ch.nokillswit.reports.DayIncrement
import ch.nokillswit.reports.EpicProgressBasis
import ch.nokillswit.reports.EpicProgressLevel
import ch.nokillswit.reports.Evm
import ch.nokillswit.reports.MAX_TREND_WINDOW_DAYS
import ch.nokillswit.reports.MIN_PERIOD_DENOMINATOR_MD
import ch.nokillswit.reports.MIN_TREND_WINDOW_DAYS
import ch.nokillswit.reports.NO_EVM
import ch.nokillswit.reports.defaultBasis
import ch.nokillswit.reports.parseEpicProgressOptions
import ch.nokillswit.reports.periodCpi
import ch.nokillswit.reports.periodDelta
import ch.nokillswit.reports.periodSpi
import ch.nokillswit.reports.progressSeries
import ch.nokillswit.reports.trendDays
import ch.nokillswit.reports.trendOf
import io.ktor.http.parameters
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The pure period / trend arithmetic of the epic-progress report (A37), no database. */
class EpicProgressPeriodKernelsTest {

    private fun evm(pv: String, ev: String, ac: String) = Evm(BigDecimal(pv), BigDecimal(ev), BigDecimal(ac))

    private fun inc(day: String, pv: String = "0", ev: String = "0", ac: String = "0") = DayIncrement(day, evm(pv, ev, ac))

    private fun d(iso: String) = LocalDate.parse(iso)

    @Test
    fun `the minimum denominator is one man-day and an index under it is hidden`() {
        assertEquals(1.0, MIN_PERIOD_DENOMINATOR_MD)
        assertNull(evm("0.99", "5", "0.99").periodSpi)
        assertNull(evm("0.99", "5", "0.99").periodCpi)
        assertEquals(5.0, evm("1.00", "5", "1.00").periodSpi, "exactly the minimum is shown")
        assertEquals(5.0, evm("1.00", "5", "1.00").periodCpi)
        assertNull(evm("0", "5", "0").periodSpi)
        assertNull(evm("-3", "5", "-3").periodSpi, "a negative delta (a re-plan) is hidden too")
        assertEquals(0.0, evm("2", "0", "2").periodSpi, "no value earned in a period that planned some is a real 0")
    }

    @Test
    fun `defaults - an epic reads its whole life, everything else the period`() {
        assertEquals(EpicProgressBasis.CUMULATIVE, EpicProgressLevel.EPIC.defaultBasis())
        for (level in listOf(EpicProgressLevel.DOMAIN, EpicProgressLevel.TEAM)) {
            assertEquals(EpicProgressBasis.PERIOD, level.defaultBasis(), level.name)
        }
    }

    @Test
    fun `periodDelta is the running sum minus the sum the day before the first day`() {
        val increments = listOf(
            inc("2026-01-30", pv = "5"),
            inc("2026-02-01", pv = "2", ev = "1"),
            inc("2026-02-02", ev = "4", ac = "3"),
            inc("2026-02-04", pv = "6"),
        )
        val (before, delta) = periodDelta(increments, d("2026-02-02"), d("2026-02-04"))
        assertEquals(evm("7", "1", "0"), before)
        assertEquals(evm("6", "4", "3"), delta)
        assertEquals(NO_EVM to NO_EVM, periodDelta(increments, null, d("2026-02-04")), "an empty period")
        val (none, whole) = periodDelta(increments, d("2025-01-01"), d("2026-02-04"))
        assertEquals(NO_EVM, none)
        assertEquals(evm("13", "5", "3"), whole)
    }

    @Test
    fun `progressSeries rebases every point and the original plan by the same day`() {
        val days = listOf(d("2026-02-02"), d("2026-02-03"))
        val totals = listOf(evm("12", "5", "3"), evm("14", "9", "4"))
        val cumulative = progressSeries(days, totals, NO_EVM, null, null)
        assertEquals(listOf(12.0, 14.0), cumulative.map { it.pv })
        assertEquals(listOf(null, null), cumulative.map { it.pvOriginal })
        val rebased = progressSeries(days, totals, evm("10", "5", "1"), d("2026-02-01"), null)
        assertEquals(listOf(2.0, 4.0), rebased.map { it.pv })
        assertEquals(listOf(0.0, 4.0), rebased.map { it.ev })
        assertEquals(listOf(2.0, 3.0), rebased.map { it.ac })
    }

    @Test
    fun `trendDays - the end of every Monday-start week of the period, the last cut at asOf`() {
        // Mon Feb 2 .. Wed Feb 18: weeks end Sun Feb 8, Sun Feb 15, then asOf.
        assertEquals(listOf("2026-02-08", "2026-02-15", "2026-02-18"), trendDays(d("2026-02-02"), d("2026-02-18")).map { it.toString() })
        // A period starting on a Wednesday still gets that week's Sunday; one ending on a Sunday does not repeat it.
        assertEquals(listOf("2026-02-08", "2026-02-15"), trendDays(d("2026-02-04"), d("2026-02-15")).map { it.toString() })
        assertEquals(listOf("2026-02-04"), trendDays(d("2026-02-04"), d("2026-02-04")).map { it.toString() }, "one day inside a week")
        assertEquals(listOf("2026-02-01"), trendDays(d("2026-02-01"), d("2026-02-01")).map { it.toString() }, "a lone Sunday")
    }

    @Test
    fun `trendOf reads the trailing window, reaches before the period and hides thin denominators`() {
        val increments = listOf(
            inc("2026-01-30", pv = "2", ev = "2", ac = "2"),
            inc("2026-02-03", pv = "4", ev = "2", ac = "0.5"),
            inc("2026-02-12", pv = "0.5", ev = "3", ac = "4"),
        )
        val points = trendOf(increments, listOf(d("2026-02-08"), d("2026-02-15")), 7)
        // Feb 2..8: PV 4, EV 2, AC 0.5 -> SPI 0.5, CPI null (AC under 1). Feb 9..15: PV 0.5 -> SPI null, CPI 3/4.
        assertEquals(0.5, points[0].spi)
        assertNull(points[0].cpi)
        assertNull(points[1].spi)
        assertEquals(0.75, points[1].cpi)
        // A 14-day window from Feb 8 reaches back to Jan 26: PV 6, EV 4, AC 2.5.
        val wide = trendOf(increments, listOf(d("2026-02-08")), 14).single()
        assertEquals(4.0 / 6.0, wide.spi!!, 1e-12)
        assertEquals(4.0 / 2.5, wide.cpi!!, 1e-12)
    }

    @Test
    fun `the trend window is bounded 7 to 182 and a bad basis is a 400`() {
        assertEquals(28, parseEpicProgressOptions(parameters { }).trendWindowDays)
        assertNull(parseEpicProgressOptions(parameters { }).basis)
        assertEquals(EpicProgressBasis.CUMULATIVE, parseEpicProgressOptions(parameters { append("basis", "CUMULATIVE") }).basis)
        assertEquals(MIN_TREND_WINDOW_DAYS, parseEpicProgressOptions(parameters { append("trendWindowDays", "7") }).trendWindowDays)
        assertEquals(MAX_TREND_WINDOW_DAYS, parseEpicProgressOptions(parameters { append("trendWindowDays", "182") }).trendWindowDays)
        for (bad in listOf("6", "183", "x")) {
            assertFailsWith<BadRequestException>(bad) { parseEpicProgressOptions(parameters { append("trendWindowDays", bad) }) }
        }
        assertFailsWith<BadRequestException> { parseEpicProgressOptions(parameters { append("basis", "ALL") }) }
    }
}
