package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.CompareDirection
import ch.nokillswit.reports.CompareUnit
import ch.nokillswit.reports.HomeMeasure
import ch.nokillswit.reports.HomeSpan
import ch.nokillswit.reports.HomeVerdict
import ch.nokillswit.reports.Measured
import ch.nokillswit.reports.ThroughputBucket
import ch.nokillswit.reports.bucketStart
import ch.nokillswit.reports.bucketStarts
import ch.nokillswit.reports.countFigure
import ch.nokillswit.reports.homeFilter
import ch.nokillswit.reports.homeWindowsOf
import ch.nokillswit.reports.percentileFigureSorted
import ch.nokillswit.reports.predFigures
import ch.nokillswit.reports.predShareOf
import ch.nokillswit.reports.sumFigure
import ch.nokillswit.reports.trendOf
import ch.nokillswit.reports.trendedOf
import ch.nokillswit.reports.weekStartsMs
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the Home overview (`HomeKernels.kt`): windows, figure builders and the trend rule, hand-computed, no database. */
class HomeKernelsTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val calendar = WorkingCalendar(warsaw, setOf(6, 7), emptyMap())

    private fun homeWindows(calendar: WorkingCalendar, nowMs: Long) = homeWindowsOf(calendar, homeFilter(calendar, nowMs))

    private fun homeAt(zoned: String): Long = ZonedDateTime.parse(zoned).toInstant().toEpochMilli()

    private fun measured(value: Double?, n: Int = 10, hidden: Boolean = false) = Measured(value, n, hidden)

    @Test
    fun `the windows are 90 days each by date arithmetic across Europe-Warsaw's DST changes`() {
        // Both clock changes lie inside the two windows: 2025-10-26 (a 25-hour day) in the previous one, 2026-03-29 (a 23-hour day)
        // in the current.
        val windows = homeWindows(calendar, homeAt("2026-04-10T10:00:00+02:00"))
        assertEquals(LocalDate.parse("2026-01-11"), windows.current.fromDate)
        assertEquals(LocalDate.parse("2026-04-10"), windows.current.toDate)
        assertEquals(LocalDate.parse("2025-10-13"), windows.previous.fromDate)
        assertEquals(LocalDate.parse("2026-01-10"), windows.previous.toDate)
        assertEquals(90L, ChronoUnit.DAYS.between(windows.current.fromDate, windows.current.toDate) + 1)
        assertEquals(90L, ChronoUnit.DAYS.between(windows.previous.fromDate, windows.previous.toDate) + 1)
        // The bounds are the zone's own whole days, contiguous, and NOT a multiple of 24 h apart (never ms arithmetic).
        assertEquals(homeAt("2025-10-13T00:00:00+02:00"), windows.previous.fromMs)
        assertEquals(homeAt("2026-01-11T00:00:00+01:00"), windows.previous.toMs)
        assertEquals(windows.previous.toMs, windows.current.fromMs)
        assertEquals(homeAt("2026-04-11T00:00:00+02:00"), windows.current.toMs)
        val hours = 60 * 60 * 1000L
        assertEquals(90 * 24 * hours + hours, windows.previous.toMs - windows.previous.fromMs, "the previous window holds the 25-hour day")
        assertEquals(90 * 24 * hours - hours, windows.current.toMs - windows.current.fromMs, "the current window holds the 23-hour day")
        assertEquals(windows.previous.fromMs to windows.current.toMs, windows.both)
        assertEquals("2026-01-11", windows.current.toWire().from)
        assertEquals("2026-04-10", windows.current.toWire().to)
    }

    @Test
    fun `a window edge is half-open on the day boundary and today is the last day`() {
        val windows = homeWindows(calendar, homeAt("2026-07-01T23:59:59+02:00"))
        assertEquals(LocalDate.parse("2026-07-01"), windows.current.toDate)
        assertTrue(windows.current.contains(homeAt("2026-07-01T23:59:59.999+02:00")))
        assertFalse(windows.current.contains(homeAt("2026-07-02T00:00:00+02:00")))
        assertTrue(windows.current.contains(windows.current.fromMs))
        assertFalse(windows.current.contains(windows.current.fromMs - 1))
        assertTrue(windows.previous.contains(windows.current.fromMs - 1))
    }

    @Test
    fun `the median and the percentiles equal an independent quantile, hidden under the minimum`() {
        val values = listOf(10.0, 1.0, 4.0, 2.0, 3.0)
        val sorted = values.sorted()
        assertEquals(Measured(quantile(sorted, 0.5), 5, false), medianFigure(values, 5))
        assertEquals(quantile(sorted, 0.85), percentileFigure(values, 0.85, 5).value!!, 1e-12)
        assertEquals(Measured(null, 5, true), medianFigure(values, 6), "n under the minimum: no value, n kept")
        assertEquals(Measured(7.0, 1, false), medianFigure(listOf(7.0), 1), "a single value is its own percentile")
        assertEquals(Measured(null, 0, true), medianFigure(emptyList(), 1), "nothing measured is hidden, never a zero")
    }

    @Test
    fun `percentileFigureSorted equals percentileFigure on sorted values and never indexes a hidden list`() {
        // Even and odd n, one value, ties: the sorted twin is the unsorted one without the sort.
        val samples = listOf(
            listOf(7.0), listOf(3.0, 1.0), listOf(5.0, 1.0, 3.0), listOf(4.0, 4.0, 1.0, 9.0), listOf(2.0, 8.0, 5.0, 1.0, 5.0),
        )
        for (values in samples) {
            val sorted = values.sorted()
            for (fraction in listOf(0.5, 0.85)) {
                assertEquals(percentileFigure(values, fraction, 1), percentileFigureSorted(sorted, fraction, 1), "$values at $fraction")
            }
        }
        assertEquals(Measured(7.0, 1, false), percentileFigureSorted(listOf(7.0), 0.85, 1), "n = 1 is its own percentile")
        assertEquals(Measured(2.5, 4, false), percentileFigureSorted(listOf(1.0, 2.0, 3.0, 4.0), 0.5, 4), "even n interpolates")
        assertEquals(Measured(3.0, 5, false), percentileFigureSorted(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.5, 5), "odd n reads the middle")
        // Under the minimum the list is only measured: a list that throws on any read proves nothing is indexed (also n = 0).
        val untouchable = object : AbstractList<Double>() {
            override val size = 2
            override fun get(index: Int): Double = error("a hidden figure reads no value")
        }
        assertEquals(Measured(null, 2, true), percentileFigureSorted(untouchable, 0.5, 3))
        assertEquals(Measured(null, 0, true), percentileFigureSorted(emptyList(), 0.5, 1))
    }

    @Test
    fun `predShareOf is the one share division and agrees with predFigures`() {
        assertEquals(0.4, predShareOf(2, 5))
        assertEquals(0.0, predShareOf(0, 3))
        assertEquals(1.0, predShareOf(3, 3))
        assertEquals(1.0 / 3.0, predShareOf(1, 3))
        val ratios = listOf(1.0, 10.0 / 9.0, 4.0 / 3.0, 2.0 / 3.0, 3.0)
        val pred = predFigures(ratios, 1)
        assertEquals(pred.pred25.value, predShareOf(pred.pred25.numerator!!, pred.pred25.n))
        assertEquals(pred.pred50.value, predShareOf(pred.pred50.numerator!!, pred.pred50.n))
    }

    @Test
    fun `trendedOf is the figure of the current side beside the trend of the two measured sides`() {
        val previous = measured(10.0)
        val current = measured(8.0)
        val figure = trendedOf(HomeMeasure.CYCLE_WORKING_DAYS, previous, current, 5)
        assertEquals(8.0, figure.value)
        assertEquals(10, figure.n)
        assertEquals(trendOf(HomeMeasure.CYCLE_WORKING_DAYS, previous, current, 5), figure.previous)
        assertEquals(HomeVerdict.BETTER, figure.previous!!.verdict)
        val counted = trendedOf(HomeMeasure.FINDING_COUNT, countFigure(1), Measured(3.0, 3, false, numerator = 3), 5)
        assertEquals(3, counted.numerator, "the numerator of the current side rides along")
    }

    @Test
    fun `counts and sums are never hidden`() {
        assertEquals(Measured(0.0, 0, false), countFigure(0))
        assertEquals(Measured(3.0, 3, false), countFigure(3))
        assertEquals(Measured(12.5, 4, false), sumFigure(12.5, 4))
    }

    @Test
    fun `PRED shares and numerators count an item exactly on a threshold as within, hidden under the minimum`() {
        // MRE = |1 - 1/r|: 0, 0.1, 0.25, 0.5 (each exactly on a threshold, absorbed by the epsilon) and 2/3 (beyond all three).
        val ratios = listOf(1.0, 10.0 / 9.0, 4.0 / 3.0, 2.0 / 3.0, 3.0)
        val pred = predFigures(ratios, 5)
        assertEquals(listOf(2, 3, 4), listOf(pred.pred10, pred.pred25, pred.pred50).map { it.numerator })
        assertEquals(listOf(0.4, 0.6, 0.8), listOf(pred.pred10, pred.pred25, pred.pred50).map { it.value })
        assertTrue(listOf(pred.pred10, pred.pred25, pred.pred50).all { it.n == 5 && !it.hidden })
        val hidden = predFigures(ratios, 6)
        assertEquals(listOf(null, null, null), listOf(hidden.pred10, hidden.pred25, hidden.pred50).map { it.value })
        assertTrue(listOf(hidden.pred10, hidden.pred25, hidden.pred50).all { it.hidden && it.n == 5 })
        val hiddenNumerators = listOf(hidden.pred10, hidden.pred25, hidden.pred50).map { it.numerator }
        assertEquals(listOf(2, 3, 4), hiddenNumerators, "the drill still lists them")
        assertEquals(0, predFigures(emptyList(), 1).pred25.numerator)
    }

    @Test
    fun `the trend table reads the granularity medians and the cycle percentiles lower-is-better, delivery and PRED higher`() {
        assertEquals(CompareDirection.LOWER, HomeMeasure.ESTIMATE_MD.direction)
        assertEquals(CompareDirection.LOWER, HomeMeasure.CYCLE_WORKING_DAYS.direction)
        assertEquals(CompareDirection.LOWER, HomeMeasure.COST_MD.direction)
        assertEquals(CompareDirection.LOWER, HomeMeasure.CYCLE_PERCENTILE.direction)
        assertEquals(CompareDirection.LOWER, HomeMeasure.FINDING_COUNT.direction)
        assertEquals(CompareDirection.HIGHER, HomeMeasure.PRED.direction)
        assertEquals(CompareDirection.HIGHER, HomeMeasure.DELIVERED_MD.direction)
        assertEquals(CompareDirection.HIGHER, HomeMeasure.DELIVERED_ITEMS.direction)
        assertEquals(CompareUnit.ITEMS, HomeMeasure.DELIVERED_ITEMS.unit)
        assertEquals(CompareUnit.ITEMS, HomeMeasure.FINDING_COUNT.unit)
        assertEquals(CompareUnit.SHARE, HomeMeasure.PRED.unit)
    }

    @Test
    fun `trendOf names the better side per direction and keeps current minus previous`() {
        val lower = trendOf(HomeMeasure.CYCLE_WORKING_DAYS, measured(10.0), measured(8.0), 5)
        assertEquals(HomeVerdict.BETTER, lower.verdict)
        assertEquals(-2.0, lower.difference)
        assertEquals(10.0, lower.value)
        assertEquals(10, lower.n)
        assertEquals(HomeVerdict.WORSE, trendOf(HomeMeasure.CYCLE_WORKING_DAYS, measured(8.0), measured(10.0), 5).verdict)
        assertEquals(HomeVerdict.BETTER, trendOf(HomeMeasure.PRED, measured(0.4), measured(0.6), 5).verdict)
        assertEquals(HomeVerdict.WORSE, trendOf(HomeMeasure.PRED, measured(0.6), measured(0.4), 5).verdict)
        assertEquals(HomeVerdict.BETTER, trendOf(HomeMeasure.DELIVERED_MD, measured(20.0), measured(25.0), 5).verdict)
        assertEquals(HomeVerdict.WORSE, trendOf(HomeMeasure.DELIVERED_MD, measured(25.0), measured(20.0), 5).verdict)
    }

    @Test
    fun `a tie within the unit's tolerance reads as no verdict but keeps the difference`() {
        val tie = trendOf(HomeMeasure.CYCLE_WORKING_DAYS, measured(10.0), measured(10.04), 5)
        assertNull(tie.verdict)
        assertEquals(0.04, tie.difference!!, 1e-9)
        // Exactly the tolerance (0.05 days) is still a tie; just beyond it is not.
        assertNull(trendOf(HomeMeasure.CYCLE_WORKING_DAYS, measured(10.0), measured(10.05), 5).verdict)
        assertEquals(HomeVerdict.WORSE, trendOf(HomeMeasure.CYCLE_WORKING_DAYS, measured(10.0), measured(10.06), 5).verdict)
        assertNull(trendOf(HomeMeasure.PRED, measured(0.5), measured(0.5), 5).verdict)
    }

    @Test
    fun `a hidden side takes the verdict and the difference away, an unhidden small sample only the verdict`() {
        val hiddenPrevious = trendOf(HomeMeasure.PRED, measured(null, n = 3, hidden = true), measured(0.6), 5)
        assertNull(hiddenPrevious.verdict)
        assertNull(hiddenPrevious.difference)
        assertTrue(hiddenPrevious.hidden)
        assertNull(hiddenPrevious.value)
        val hiddenCurrent = trendOf(HomeMeasure.PRED, measured(0.4), measured(null, n = 2, hidden = true), 5)
        assertNull(hiddenCurrent.verdict)
        assertNull(hiddenCurrent.difference)
        // A sum under the minimum sample (a non-ITEMS unit is sample-gated) has a difference but no cue.
        val small = trendOf(HomeMeasure.DELIVERED_MD, measured(10.0, n = 2), measured(20.0, n = 9), 5)
        assertNull(small.verdict)
        assertEquals(10.0, small.difference)
    }

    @Test
    fun `item counts are not sample-gated, even at one item against none`() {
        val better = trendOf(HomeMeasure.FINDING_COUNT, countFigure(5), countFigure(3), 5)
        assertEquals(HomeVerdict.BETTER, better.verdict)
        assertEquals(-2.0, better.difference)
        assertEquals(HomeVerdict.WORSE, trendOf(HomeMeasure.FINDING_COUNT, countFigure(0), countFigure(1), 5).verdict)
        assertEquals(HomeVerdict.BETTER, trendOf(HomeMeasure.DELIVERED_ITEMS, countFigure(1), countFigure(2), 5).verdict)
        assertNull(trendOf(HomeMeasure.FINDING_COUNT, countFigure(2), countFigure(2), 5).verdict, "equal counts are a tie")
    }

    @Test
    fun `the week starts are the Monday midnights of the window's zone, ascending, one per week`() {
        val from = calendar.dayBoundsMs(LocalDate.parse("2026-03-02")).first
        val to = calendar.dayBoundsMs(LocalDate.parse("2026-03-22")).second
        val window = HomeSpan(LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-22"), from, to)
        val starts = weekStartsMs(window, calendar, warsaw)
        assertEquals(
            listOf("2026-03-02T00:00:00+01:00", "2026-03-09T00:00:00+01:00", "2026-03-16T00:00:00+01:00").map(::homeAt), starts,
            "three weeks, each Monday's first instant",
        )
        // A window starting mid-week still lists its first Monday (the one on or before it), like the zero-filled weekly series.
        val midWeek = HomeSpan(
            LocalDate.parse("2026-03-04"), LocalDate.parse("2026-03-10"),
            homeAt("2026-03-04T00:00:00+01:00"), homeAt("2026-03-11T00:00:00+01:00"),
        )
        val mondays = listOf("2026-03-02T00:00:00+01:00", "2026-03-09T00:00:00+01:00").map(::homeAt)
        assertEquals(mondays, weekStartsMs(midWeek, calendar, warsaw))
    }

    @Test
    fun `the index of the last week start at or before an instant names its Monday, every hour across both clock changes`() {
        // 2025-10-13 .. 2026-04-12 holds the autumn change (Sunday 2025-10-26, 25 h) and the spring one (Sunday 2026-03-29, 23 h).
        val first = LocalDate.parse("2025-10-13")
        val last = LocalDate.parse("2026-04-12")
        val window = HomeSpan(first, last, calendar.dayBoundsMs(first).first, calendar.dayBoundsMs(last).second)
        val starts = weekStartsMs(window, calendar, warsaw)
        val mondays = bucketStarts(window.fromMs, window.toMs, warsaw, ThroughputBucket.WEEK)
        assertEquals(mondays.size, starts.size)
        assertEquals(starts.sorted().distinct(), starts, "strictly ascending: width_bucket needs it")
        val hour = 3_600_000L
        val weekLengths = starts.zipWithNext { a, b -> (b - a) / hour }.toSet()
        assertEquals(setOf(167L, 168L, 169L), weekLengths, "the two clock-change weeks are 169 h (autumn) and 167 h (spring) long")
        // The SQL's `width_bucket(done_at, starts)` is the count of starts at or before the instant: the 1-based week index.
        fun indexOf(atMs: Long) = starts.count { it <= atMs }
        var checked = 0
        var t = window.fromMs
        while (t < window.toMs) {
            assertEquals(bucketStart(t, warsaw, ThroughputBucket.WEEK), mondays[indexOf(t) - 1], "week of $t")
            checked++
            t += hour
        }
        for (start in starts.drop(1)) {
            assertEquals(mondays[indexOf(start) - 1], bucketStart(start, warsaw, ThroughputBucket.WEEK), "a Monday midnight opens its week")
            assertEquals(indexOf(start) - 1, indexOf(start - 1), "one millisecond earlier is the week before")
        }
        assertTrue(checked > 26 * 7 * 24 - 2, "every hour of the 26 weeks: $checked")
    }
}
