package ch.nokillswit

import ch.nokillswit.reports.COMPARE_ROWS
import ch.nokillswit.reports.CompareCell
import ch.nokillswit.reports.CompareDirection
import ch.nokillswit.reports.CompareDrillView
import ch.nokillswit.reports.CompareGroup
import ch.nokillswit.reports.CompareRowSpec
import ch.nokillswit.reports.CompareSide
import ch.nokillswit.reports.CompareUnit
import ch.nokillswit.reports.compareCells
import ch.nokillswit.reports.meanOverWorkingDays
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of report 18 (`ComparePeopleKernels.kt`): the row table and the verdict rule, hand-computed, no database. */
class ComparePeopleKernelsTest {

    private val min = 5

    private fun compareRow(key: String): CompareRowSpec =
        COMPARE_ROWS.firstOrNull { it.key == key } ?: error("no compare-people row '$key'")

    private fun spec(direction: CompareDirection, unit: CompareUnit = CompareUnit.DAYS, target: Double? = null) =
        CompareRowSpec("x", CompareGroup.CYCLE, unit, direction, CompareDrillView.DELIVERED, target)

    private fun cell(value: Double?, n: Int = 10, hidden: Boolean = false) = CompareCell(value, n, hidden)

    @Test
    fun `higher is better picks the larger value and reports the gap`() {
        val verdict = compareCells(spec(CompareDirection.HIGHER), cell(4.0), cell(6.5), min)
        assertEquals(CompareSide.B, verdict.better)
        assertEquals(2.5, verdict.difference)
        assertEquals(2.5, verdict.margin)
        assertEquals(CompareSide.A, compareCells(spec(CompareDirection.HIGHER), cell(6.5), cell(4.0), min).better)
    }

    @Test
    fun `lower is better picks the smaller value, the difference stays B minus A`() {
        val verdict = compareCells(spec(CompareDirection.LOWER), cell(3.0), cell(5.0), min)
        assertEquals(CompareSide.A, verdict.better)
        assertEquals(2.0, verdict.difference)
        assertEquals(2.0, verdict.margin)
        assertEquals(CompareSide.B, compareCells(spec(CompareDirection.LOWER), cell(5.0), cell(3.0), min).better)
        assertEquals(-2.0, compareCells(spec(CompareDirection.LOWER), cell(5.0), cell(3.0), min).difference)
    }

    @Test
    fun `a target row prefers the value closer to the target from either side of it`() {
        val ratio = spec(CompareDirection.TARGET, CompareUnit.RATIO, target = 1.0)
        // 0.7 is 0.3 away, 1.1 is 0.1 away: B is closer, by 0.2 — although B's value is the larger one.
        val over = compareCells(ratio, cell(0.7), cell(1.1), min)
        assertEquals(CompareSide.B, over.better)
        assertEquals(0.2, over.margin!!, 1e-9)
        assertEquals(0.4, over.difference!!, 1e-9)
        // 1.4 is 0.4 away, 0.9 is 0.1 away: B is closer although it is the LOWER value.
        val under = compareCells(ratio, cell(1.4), cell(0.9), min)
        assertEquals(CompareSide.B, under.better)
        assertEquals(0.3, under.margin!!, 1e-9)
        assertEquals(CompareSide.A, compareCells(ratio, cell(1.0), cell(1.3), min).better)
        // Equidistant on both sides of the target: a tie.
        assertNull(compareCells(ratio, cell(0.8), cell(1.2), min).better)
    }

    @Test
    fun `a gap inside the unit tolerance is a tie, one outside is not`() {
        val days = spec(CompareDirection.LOWER, CompareUnit.DAYS) // tolerance 0.05
        val tie = compareCells(days, cell(3.00), cell(3.05), min)
        assertNull(tie.better)
        assertNull(tie.margin)
        assertEquals(0.05, tie.difference!!, 1e-9, "a tie still reports the difference")
        assertEquals(CompareSide.A, compareCells(days, cell(3.00), cell(3.06), min).better)
        // Items are exact: any difference counts, equal is a tie.
        val items = spec(CompareDirection.HIGHER, CompareUnit.ITEMS)
        assertEquals(CompareSide.B, compareCells(items, cell(4.0), cell(5.0), min).better)
        assertNull(compareCells(items, cell(5.0), cell(5.0), min).better)
        // A target row's tolerance applies to the gap between the two distances.
        val ratio = spec(CompareDirection.TARGET, CompareUnit.RATIO, target = 1.0)
        assertNull(compareCells(ratio, cell(0.9), cell(1.11), min).better, "0.10 vs 0.11 away: inside 0.01")
        assertEquals(CompareSide.A, compareCells(ratio, cell(0.9), cell(1.12), min).better)
    }

    @Test
    fun `every unit carries the tolerance the contract names`() {
        val tolerances = CompareUnit.entries.associateWith { it.tolerance }
        assertEquals(0.005, tolerances.getValue(CompareUnit.MD))
        assertEquals(0.005, tolerances.getValue(CompareUnit.MD_PER_MD))
        assertEquals(0.0, tolerances.getValue(CompareUnit.ITEMS))
        assertEquals(0.05, tolerances.getValue(CompareUnit.ITEMS_PER_DAY))
        assertEquals(0.05, tolerances.getValue(CompareUnit.ITEMS_PER_MD))
        assertEquals(0.05, tolerances.getValue(CompareUnit.DAYS))
        assertEquals(0.01, tolerances.getValue(CompareUnit.RATIO))
        assertEquals(0.005, tolerances.getValue(CompareUnit.SHARE))
    }

    @Test
    fun `a neutral row never names a side but still reports the difference`() {
        val verdict = compareCells(spec(CompareDirection.NEUTRAL, CompareUnit.MD), cell(2.0), cell(9.0), min)
        assertNull(verdict.better)
        assertNull(verdict.margin)
        assertEquals(7.0, verdict.difference)
    }

    @Test
    fun `a side under the minimum sample size gives no cue, whatever the row`() {
        for (direction in listOf(CompareDirection.HIGHER, CompareDirection.LOWER)) {
            val row = spec(direction)
            assertNull(compareCells(row, cell(1.0, n = 4), cell(9.0), min).better, "$direction A under")
            assertNull(compareCells(row, cell(1.0), cell(9.0, n = 4), min).better, "$direction B under")
            assertTrue(compareCells(row, cell(1.0, n = 5), cell(9.0, n = 5), min).better != null, "$direction at the minimum is enough")
        }
        val ratio = spec(CompareDirection.TARGET, CompareUnit.RATIO, target = 1.0)
        assertNull(compareCells(ratio, cell(0.2, n = 4), cell(1.0), min).better)
        // The difference is still there when both values are.
        assertEquals(8.0, compareCells(spec(CompareDirection.HIGHER), cell(1.0, n = 4), cell(9.0), min).difference)
    }

    @Test
    fun `the CODE rows - a size within a line is a tie, a smaller merge request is better, a volume names no side`() {
        fun row(key: String) = COMPARE_ROWS.single { it.key == key }
        val size = row("mrSizeLinesP50")
        assertEquals(CompareDirection.LOWER, size.direction)
        assertEquals(CompareUnit.LINES, size.unit)
        assertNull(compareCells(size, cell(40.0, n = 5), cell(40.5, n = 5), min).better, "half a line is a tie")
        val smaller = compareCells(size, cell(40.0, n = 5), cell(55.0, n = 5), min)
        assertEquals(CompareSide.A, smaller.better)
        assertEquals(15.0, smaller.margin)
        assertNull(compareCells(size, cell(40.0, n = 4), cell(55.0, n = 5), min).better, "a p50 over fewer than the minimum is no cue")
        val cycle = row("mrCycleWorkingDaysP50")
        assertEquals(CompareDirection.LOWER, cycle.direction)
        assertEquals(CompareSide.B, compareCells(cycle, cell(3.0, n = 5), cell(1.5, n = 5), min).better)
        // The volumes are NEUTRAL context: the difference, never a side.
        for (key in listOf("commits", "linesChanged", "mrsMerged", "reviewsGiven", "approvalsGiven", "comments")) {
            assertEquals(CompareDirection.NEUTRAL, row(key).direction, key)
            val verdict = compareCells(row(key), cell(2.0, n = 2), cell(90.0, n = 90), min)
            assertNull(verdict.better, key)
            assertEquals(88.0, verdict.difference, key)
        }
        assertTrue(COMPARE_ROWS.filter { it.group == CompareGroup.CODE }.none { it.requiresCapacity })
    }

    @Test
    fun `a pure count of items is not sample-gated - one against eight is a real difference`() {
        val items = spec(CompareDirection.LOWER, CompareUnit.ITEMS)
        val verdict = compareCells(items, cell(1.0, n = 1), cell(8.0, n = 8), min)
        assertEquals(CompareSide.A, verdict.better)
        assertEquals(7.0, verdict.difference)
        assertEquals(7.0, verdict.margin)
        val higher = spec(CompareDirection.HIGHER, CompareUnit.ITEMS)
        assertEquals(CompareSide.B, compareCells(higher, cell(1.0, n = 1), cell(8.0, n = 8), min).better)
        // Every other unit is a statistic over its sample and stays gated, whatever its direction.
        for (unit in CompareUnit.entries.filter { it != CompareUnit.ITEMS }) {
            assertNull(compareCells(spec(CompareDirection.LOWER, unit), cell(1.0, n = 1), cell(8.0, n = 8), min).better, "$unit")
        }
        // The pure item counts: the two delivery / WIP counts, and the NEUTRAL volumes of the CODE group (which no verdict reads anyway).
        val counts = setOf("deliveredItems", "wipAtEndItems", "commits", "mrsMerged", "reviewsGiven", "approvalsGiven", "comments")
        assertTrue(COMPARE_ROWS.filter { !it.unit.sampleGated }.map { it.key }.toSet() == counts)
        val codeCounts = COMPARE_ROWS.filter { !it.unit.sampleGated && it.group == CompareGroup.CODE }
        assertTrue(codeCounts.all { it.direction == CompareDirection.NEUTRAL })
    }

    @Test
    fun `a hidden or valueless side has neither a difference nor a cue`() {
        val row = spec(CompareDirection.HIGHER)
        val hidden = compareCells(row, cell(null, n = 3, hidden = true), cell(9.0), min)
        assertNull(hidden.better)
        assertNull(hidden.difference)
        assertNull(hidden.margin)
        val noValue = compareCells(row, cell(2.0), cell(null), min)
        assertNull(noValue.better)
        assertNull(noValue.difference)
    }

    @Test
    fun `a target goes with the TARGET direction and only with it`() {
        assertFailsWith<IllegalArgumentException> { spec(CompareDirection.TARGET) }
        assertFailsWith<IllegalArgumentException> { spec(CompareDirection.HIGHER, target = 1.0) }
    }

    @Test
    fun `the row table is unique, grouped in display order and gates exactly the four capacity rows`() {
        assertEquals(COMPARE_ROWS.size, COMPARE_ROWS.map { it.key }.toSet().size)
        assertEquals(
            listOf("deliveredMdPerAvailableMd", "deliveredItemsPerAvailableMd", "availableMd", "loggedPerAvailableMd"),
            COMPARE_ROWS.filter { it.requiresCapacity }.map { it.key },
        )
        val groups = COMPARE_ROWS.map { it.group }
        assertEquals(groups.sortedBy { it.ordinal }, groups, "rows are listed group by group")
        assertEquals(CompareDirection.TARGET, compareRow("reportedTimeRatioP50").direction)
        assertEquals(1.0, compareRow("reportedTimeRatioP50").target)
        assertEquals(CompareDirection.TARGET, compareRow("loggedPerAvailableMd").direction)
        assertEquals(CompareDirection.LOWER, compareRow("avgWip").direction)
        assertEquals(CompareDirection.LOWER, compareRow("taskSizeP50").direction)
        assertEquals(CompareUnit.MD, compareRow("taskSizeP50").unit)
        assertEquals(CompareDirection.LOWER, compareRow("reopenedShare").direction)
        assertEquals(CompareUnit.SHARE, compareRow("reopenedShare").unit)
        assertEquals(CompareGroup.DELIVERY, compareRow("reopenedShare").group)
        assertEquals(CompareDrillView.TASK_SIZE, compareRow("taskSizeP50").drill)
        assertEquals(CompareDrillView.REOPENED, compareRow("reopenedShare").drill)
        assertEquals(CompareDirection.NEUTRAL, compareRow("adjustedShare").direction)
        assertEquals(CompareDirection.NEUTRAL, compareRow("adjustmentMedianChange").direction)
        assertEquals(CompareDirection.HIGHER, compareRow("accuracyAtStartPred25").direction)
        assertEquals(CompareDirection.HIGHER, compareRow("flowEfficiencyP50").direction)
        assertNull(compareRow("availableMd").drill)
        assertFailsWith<IllegalStateException> { compareRow("nope") }
    }

    @Test
    fun `the mean over working days counts a day without tasks as zero`() {
        val days = (1..4).map { LocalDate.parse("2026-01-0$it") }
        assertEquals((2 + 0 + 3 + 1) / 4.0, meanOverWorkingDays(mapOf(days[0] to 2, days[2] to 3, days[3] to 1), days))
        // Only the working days are averaged over: a count on a day outside the list is ignored.
        assertEquals(1.5, meanOverWorkingDays(mapOf(days[0] to 3, LocalDate.parse("2026-02-01") to 9), days.take(2)))
        assertNull(meanOverWorkingDays(mapOf(days[0] to 2), emptyList()))
    }
}
