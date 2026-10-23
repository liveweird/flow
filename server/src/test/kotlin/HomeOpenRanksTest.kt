package ch.nokillswit

import ch.nokillswit.reports.SparseAges
import ch.nokillswit.reports.percentileContinuous
import ch.nokillswit.reports.percentileRanks
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Home overview reads only the ranks of the sorted ages that its percentiles touch (`HomeOpenItems.kt`); no database. The figure must
 * be `percentileContinuous`'s own to the last bit, whatever the size, so a list that knows ONLY those ranks is graded against the full one.
 */
class HomeOpenRanksTest {

    private val fractions = listOf(0.50, 0.85, 0.95)

    @Test
    fun `a list holding only the touched ranks gives the same percentiles as the full list for every size up to 3000`() {
        val random = Random(7)
        for (size in 1..3000) {
            val full = List(size) { random.nextDouble() * 400 }.sorted()
            val ranks = percentileRanks(size, fractions)
            val sparse = SparseAges(size, ranks.associateWith { full[it] })
            for (fraction in fractions) {
                assertEquals(percentileContinuous(full, fraction), percentileContinuous(sparse, fraction), "size $size at $fraction")
            }
        }
    }

    @Test
    fun `only a handful of ranks are touched, however large the list`() {
        assertEquals(setOf(0), percentileRanks(1, fractions))
        assertEquals(setOf(0, 1), percentileRanks(2, fractions))
        assertTrue(percentileRanks(1_000_000, fractions).size <= 2 * fractions.size)
        // 0.5 * (5 - 1) = 2 exactly: one rank; 0.85 * 4 = 3.4 and 0.95 * 4 = 3.8: ranks 3 and 4.
        assertEquals(setOf(2, 3, 4), percentileRanks(5, fractions))
    }
}
