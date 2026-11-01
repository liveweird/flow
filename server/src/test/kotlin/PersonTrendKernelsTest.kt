package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.COMPARE_ROWS
import ch.nokillswit.reports.CompareCell
import ch.nokillswit.reports.CompareRowSpec
import ch.nokillswit.reports.CuePoint
import ch.nokillswit.reports.MAX_TREND_POINTS
import ch.nokillswit.reports.TREND_ROWS
import ch.nokillswit.reports.TREND_ROW_KEYS
import ch.nokillswit.reports.TrendCoverage
import ch.nokillswit.reports.TrendVerdict
import ch.nokillswit.reports.coverageOf
import ch.nokillswit.reports.cueOf
import ch.nokillswit.reports.pointEnds
import ch.nokillswit.reports.sliceByAnchor
import ch.nokillswit.reports.trendWindowOf
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of report 20 (`PersonTrendKernels.kt`): the grid, the slice, the coverage and the cue, hand-computed, no database. */
class PersonTrendKernelsTest {

    private val calendar = WorkingCalendar(ZoneId.of("UTC"), weekendDays = emptySet(), holidays = emptySet())

    private fun day(date: String): LocalDate = LocalDate.parse(date)

    private fun spec(key: String): CompareRowSpec = TREND_ROWS.single { it.key == key }

    private fun point(end: String, value: Double?, n: Int = 10, coverage: TrendCoverage = TrendCoverage.FULL, hidden: Boolean = false) =
        CuePoint(end, coverage, CompareCell(value, n, hidden))

    @Test
    fun `the rows are Compare people rows in the display order, none of them a capacity row`() {
        assertEquals(17, TREND_ROW_KEYS.size)
        assertEquals(TREND_ROW_KEYS, TREND_ROWS.map { it.key })
        assertEquals(TREND_ROW_KEYS.size, TREND_ROW_KEYS.toSet().size, "no key twice")
        assertTrue(TREND_ROWS.none { it.requiresCapacity })
        assertTrue(TREND_ROWS.all { row -> COMPARE_ROWS.any { it === row } }, "each row IS Compare people's spec, not a copy")
        assertEquals("cycleWorkingDaysP50", TREND_ROW_KEYS.first())
        assertEquals("taskSizeP50", TREND_ROW_KEYS[13])
        val code = listOf("mrsMerged", "mrCycleWorkingDaysP50", "reviewsGiven")
        assertEquals(code, TREND_ROW_KEYS.takeLast(3), "the CODE rows close the list")
    }

    @Test
    fun `the points run from to back by 14 days while not before from, oldest first`() {
        assertEquals(listOf(day("2026-01-01"), day("2026-01-15"), day("2026-01-29")), pointEnds(day("2026-01-01"), day("2026-01-29")))
        // 28 days back is exactly `from`: a point on it; 27 would stop one step short.
        assertEquals(3, pointEnds(day("2026-01-02"), day("2026-01-30")).size)
        assertEquals(listOf(day("2026-01-16"), day("2026-01-30")), pointEnds(day("2026-01-02"), day("2026-01-30")).drop(1))
        assertEquals(listOf(day("2026-01-29")), pointEnds(day("2026-01-29"), day("2026-01-29")), "a one-day range is one point")
        assertEquals(listOf(day("2026-01-15")), pointEnds(day("2026-01-02"), day("2026-01-15")), "13 days of range: only `to`")
        // A year (365 days, from = to - 364) is 27 points; the 1100-day cap is MAX_TREND_POINTS.
        assertEquals(27, pointEnds(day("2025-06-16"), day("2026-06-15")).size)
        assertEquals(79, MAX_TREND_POINTS)
        val capped = pointEnds(day("2026-06-15").minusDays(1099), day("2026-06-15"))
        assertEquals(MAX_TREND_POINTS, capped.size)
        assertEquals(day("2026-06-15"), capped.last())
    }

    @Test
    fun `a window is 90 calendar days ending on its point, in the zone's day bounds`() {
        val window = trendWindowOf(day("2026-03-31"), calendar)
        assertEquals(day("2026-01-01"), window.from, "Jan 1 .. Mar 31 is 31 + 28 + 31 = 90 days")
        assertEquals(day("2026-03-31"), window.end)
        assertEquals(calendar.dayBoundsMs(day("2026-01-01")).first, window.bounds.first)
        assertEquals(calendar.dayBoundsMs(day("2026-03-31")).second, window.bounds.second)
        assertEquals(90L * 86_400_000L, window.bounds.second - window.bounds.first, "UTC has no DST: exactly 90 x 24 h")
    }

    @Test
    fun `a slice is the half-open anchor range, inclusive at the start and exclusive at the end`() {
        val anchors = listOf(5L, 10L, 10L, 15L, 20L, 20L, 30L)
        fun slice(from: Long, to: Long) = anchors.sliceByAnchor(from to to) { it }
        assertEquals(listOf(10L, 10L, 15L), slice(10, 20), "10 in, 20 out")
        assertEquals(anchors, slice(0, 100))
        assertEquals(listOf(5L), slice(0, 10))
        assertEquals(listOf(30L), slice(21, 31))
        assertEquals(emptyList(), slice(31, 100))
        assertEquals(emptyList(), slice(11, 15), "between two anchors")
        assertEquals(emptyList(), slice(20, 20), "an empty window")
        assertEquals(emptyList(), emptyList<Long>().sliceByAnchor(0L to 10L) { it })
    }

    @Test
    fun `coverage - nothing derived or a window wholly outside the span is NONE, one straddling an edge is PARTIAL`() {
        val window = trendWindowOf(day("2026-03-31"), calendar) // Jan 1 .. Mar 31
        assertEquals(TrendCoverage.NONE, coverageOf(window, null, null), "nothing derived")
        assertEquals(TrendCoverage.NONE, coverageOf(window, null, day("2025-01-01")))
        assertEquals(TrendCoverage.FULL, coverageOf(window, day("2026-03-31"), null), "derived through the last day: full")
        assertEquals(TrendCoverage.FULL, coverageOf(window, day("2026-06-01"), day("2026-01-01")), "backfill on the first day: full")
        assertEquals(TrendCoverage.PARTIAL, coverageOf(window, day("2026-03-30"), null), "the last day is past the derived span")
        assertEquals(TrendCoverage.PARTIAL, coverageOf(window, day("2026-06-01"), day("2026-01-02")), "starts a day before the backfill")
        assertEquals(TrendCoverage.PARTIAL, coverageOf(window, day("2026-02-01"), day("2026-02-01")), "both edges")
        assertEquals(TrendCoverage.NONE, coverageOf(window, day("2025-12-31"), null), "starts after the last derived day")
        assertEquals(TrendCoverage.PARTIAL, coverageOf(window, day("2026-01-01"), null), "one derived day only")
        assertEquals(TrendCoverage.NONE, coverageOf(window, day("2026-06-01"), day("2026-04-01")), "ends before the backfill")
        assertEquals(TrendCoverage.PARTIAL, coverageOf(window, day("2026-06-01"), day("2026-03-31")), "the backfill day is its last")
    }

    @Test
    fun `the cue is the first against the last FULL shown point, B better is BETTER and A better is WORSE`() {
        // cycleWorkingDaysP50: lower is better, p50 5.0 -> 3.0 is better.
        val better = cueOf(
            spec("cycleWorkingDaysP50"),
            listOf(point("2026-01-01", 5.0), point("2026-01-15", 9.0), point("2026-01-29", 3.0)), 5,
        )
        assertNotNull(better)
        assertEquals("2026-01-01", better.fromEnd)
        assertEquals("2026-01-29", better.toEnd)
        assertEquals(TrendVerdict.BETTER, better.verdict)
        assertEquals(-2.0, better.difference)
        val worse = cueOf(spec("cycleWorkingDaysP50"), listOf(point("2026-01-01", 3.0), point("2026-01-29", 5.5)), 5)
        assertEquals(TrendVerdict.WORSE, worse?.verdict)
        assertEquals(2.5, worse?.difference)
        // Higher is better: deliveredMd 4 -> 6.
        assertEquals(TrendVerdict.BETTER, cueOf(spec("deliveredMd"), listOf(point("a", 4.0), point("b", 6.0)), 5)?.verdict)
    }

    @Test
    fun `the cue skips PARTIAL, NONE, hidden and valueless points at both ends`() {
        val points = listOf(
            point("2026-01-01", 99.0, coverage = TrendCoverage.NONE),
            point("2026-01-15", 98.0, coverage = TrendCoverage.PARTIAL),
            point("2026-01-29", null, hidden = true),
            point("2026-02-12", null),
            point("2026-02-26", 4.0),
            point("2026-03-12", 7.0),
            point("2026-03-26", 1.0, coverage = TrendCoverage.PARTIAL),
            point("2026-04-09", null, n = 0),
        )
        val cue = cueOf(spec("deliveredMd"), points, 5)
        assertNotNull(cue)
        assertEquals("2026-02-26", cue.fromEnd)
        assertEquals("2026-03-12", cue.toEnd)
        assertEquals(3.0, cue.difference)
        assertEquals(TrendVerdict.BETTER, cue.verdict)
    }

    @Test
    fun `fewer than two usable points is no cue at all`() {
        assertNull(cueOf(spec("deliveredMd"), emptyList(), 5))
        assertNull(cueOf(spec("deliveredMd"), listOf(point("a", 4.0)), 5), "one point has nothing to be compared with")
        val allPartial = listOf(point("a", 1.0, coverage = TrendCoverage.PARTIAL), point("b", 9.0, coverage = TrendCoverage.PARTIAL))
        assertNull(cueOf(spec("deliveredMd"), allPartial, 5))
        assertNull(cueOf(spec("deliveredMd"), listOf(point("a", 1.0), point("b", null)), 5))
    }

    @Test
    fun `a tie within the unit's tolerance has a difference and no verdict`() {
        // MD tolerance is 0.005: a 0.004 gap is a tie, 0.006 is not.
        val tie = cueOf(spec("deliveredMd"), listOf(point("a", 4.000), point("b", 4.004)), 5)
        assertNotNull(tie)
        assertNull(tie.verdict)
        assertEquals(0.004, tie.difference!!, 1e-9)
        assertEquals(TrendVerdict.BETTER, cueOf(spec("deliveredMd"), listOf(point("a", 4.000), point("b", 4.006)), 5)?.verdict)
    }

    @Test
    fun `a sample-gated row needs both samples at the minimum, a pure item count does not`() {
        // flowEfficiencyP50 is a SHARE (gated): 3 samples against a minimum of 5 on the first point -> difference but no verdict.
        val gated = cueOf(spec("flowEfficiencyP50"), listOf(point("a", 0.2, n = 3), point("b", 0.6, n = 8)), 5)
        assertNotNull(gated)
        assertNull(gated.verdict)
        assertEquals(0.4, gated.difference!!, 1e-9)
        val enough = cueOf(spec("flowEfficiencyP50"), listOf(point("a", 0.2, n = 5), point("b", 0.6, n = 8)), 5)
        assertEquals(TrendVerdict.BETTER, enough?.verdict)
        // deliveredItems is ITEMS: one item against eight is a fact, the verdict stands.
        assertEquals(TrendVerdict.BETTER, cueOf(spec("deliveredItems"), listOf(point("a", 1.0, n = 1), point("b", 8.0, n = 8)), 5)?.verdict)
        assertFalse(spec("deliveredItems").unit.sampleGated)
    }
}
