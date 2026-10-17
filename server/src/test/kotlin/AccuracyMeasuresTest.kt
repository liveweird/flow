package ch.nokillswit

import ch.nokillswit.reports.accuracyMeasures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure accuracy kernel (`AccuracyMeasures.kt`), pinned on hand-computed values — no database. */
class AccuracyMeasuresTest {

    private val tolerance = 1e-12

    private fun tailOf(vararg ratios: Double): Double? = assertNotNull(accuracyMeasures(ratios.toList(), minSampleSize = 1)).tailShare

    /**
     * Ten ratios, sorted: 0.5 0.8 0.9 1 1 1.25 1.25 1.5 2 4. Worked by hand:
     * - MRE = |1 − 1/r| = 1, .25, .111, 0, 0, .2, .2, .333, .5, .75 -> sorted 0 0 .111 .2 .2 .25 .333 .5 .75 1.
     * - PRED(10): 0, 0 -> 2/10; PRED(25): + .111 .2 .2 and the .25 ON the boundary -> 6/10; PRED(50): + .333 .5 -> 8/10.
     * - MdMRE = (.2 + .25) / 2 = .225 (ranks 4 and 5 of 0..9).
     * - sMAPE = mean 2|r−1|/(r+1) = (2/3 + 2/9 + 2/19 + 0 + 0 + 2/9 + 2/9 + 0.4 + 2/3 + 1.2) / 10.
     * - IQR = p75 − p25 = (1.25 + .75·.25) − (.9 + .25·.1) = 1.4375 − .925 = .5125.
     * - IDR = p90 − p10 = (2 + .1·2) − (.5 + .9·.3) = 2.2 − .77 = 1.43.
     * - MAD: median r = 1.125, |r − 1.125| sorted .125 ×4, .225, .325, .375, .625, .875, 2.875 -> (.225 + .325) / 2 = .275.
     * - Tail share: mean 1.42, sample SD sqrt(Σ(r−1.42)² / 9) = .99504 -> 2s = 1.99; only 4 (2.58 away) lies beyond -> 1/10.
     */
    @Test
    fun `a hand-computed ten-value set pins every measure`() {
        val measures = assertNotNull(accuracyMeasures(listOf(1.25, 4.0, 0.5, 1.0, 1.5, 0.9, 1.25, 2.0, 0.8, 1.0), minSampleSize = 5))
        assertEquals(0.2, measures.pred10, tolerance)
        assertEquals(0.6, measures.pred25, tolerance)
        assertEquals(0.8, measures.pred50, tolerance)
        assertEquals(0.225, measures.mdmre, tolerance)
        assertEquals((2.0 / 3 + 3 * 2.0 / 9 + 2.0 / 19 + 0.4 + 2.0 / 3 + 1.2) / 10, measures.smape, tolerance)
        assertEquals(0.5125, measures.iqr, tolerance)
        assertEquals(1.43, measures.idr, tolerance)
        assertEquals(0.275, measures.mad, tolerance)
        assertEquals(0.1, assertNotNull(measures.tailShare), tolerance)
    }

    @Test
    fun `an item exactly on a PRED threshold counts as within, one hair beyond does not`() {
        // 4 vs 3: r = 4/3, MRE = |4 − 3| / 4 = exactly 25%.
        val on = assertNotNull(accuracyMeasures(listOf(4.0 / 3.0), minSampleSize = 1))
        assertEquals(1.0, on.pred25, 0.0)
        assertEquals(0.0, on.pred10, 0.0)
        // 2 vs 1 is a 50% miss on the boundary; 1 vs 2 a 100% miss, never within.
        assertEquals(0.5, assertNotNull(accuracyMeasures(listOf(2.0, 0.5), minSampleSize = 1)).pred50, 0.0)
        // 10% exactly: actual 10, estimate 9 -> r = 10/9.
        assertEquals(1.0, assertNotNull(accuracyMeasures(listOf(10.0 / 9.0), minSampleSize = 1)).pred10, 0.0)
        // Just over the 25% line: actual 4, estimate 2.99 -> MRE = 1.01 / 4 = 25.25%.
        assertEquals(0.0, assertNotNull(accuracyMeasures(listOf(4.0 / 2.99), minSampleSize = 1)).pred25, 0.0)
    }

    @Test
    fun `a perfect estimate reads zero everywhere and has no tail`() {
        val measures = assertNotNull(accuracyMeasures(List(6) { 1.0 }, minSampleSize = 1))
        assertEquals(1.0, measures.pred10, 0.0)
        assertEquals(1.0, measures.pred25, 0.0)
        assertEquals(1.0, measures.pred50, 0.0)
        assertEquals(0.0, measures.mdmre, 0.0)
        assertEquals(0.0, measures.smape, 0.0)
        assertEquals(0.0, measures.iqr, 0.0)
        assertEquals(0.0, measures.idr, 0.0)
        assertEquals(0.0, measures.mad, 0.0)
        assertNull(measures.tailShare, "a constant sample has no spread to measure a tail against")
    }

    @Test
    fun `a constant sample that is not one has no tail share even where a float mean drifts`() {
        assertNull(assertNotNull(accuracyMeasures(List(10) { 0.1 }, minSampleSize = 1)).tailShare)
    }

    @Test
    fun `tail share is null below six items and defined from six`() {
        val one = assertNotNull(accuracyMeasures(listOf(1.5), minSampleSize = 1))
        assertNull(one.tailShare)
        assertEquals(0.0, one.iqr, 0.0)
        assertEquals(0.0, one.mad, 0.0)
        assertNull(tailOf(1.0, 2.0))
        // Five items with a wild outlier: the largest possible distance is (n − 1) ÷ √n = 1.79 SDs, so "beyond 2s" is impossible -> null.
        assertNull(tailOf(1.0, 1.0, 1.0, 1.0, 100.0))
        // Six items, five equal and one far out: the outlier is (n − 1) ÷ √n = 2.04 SDs from the mean -> 1/6.
        assertEquals(1.0 / 6, assertNotNull(tailOf(1.0, 1.0, 1.0, 1.0, 1.0, 100.0)), tolerance)
        // Six spread-out items with nothing beyond 2s: defined, and 0.
        assertEquals(0.0, assertNotNull(tailOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)), tolerance)
    }

    @Test
    fun `ties and a very large ratio keep every measure finite and the tail comparison strict`() {
        // Four ties at 1 and a 1e6 ratio, n = 5: MRE of the big one is 1 − 1e-6 (finite, never within PRED(50)); no tail below six.
        val five = assertNotNull(accuracyMeasures(listOf(1.0, 1.0, 1.0, 1.0, 1e6), minSampleSize = 1))
        assertEquals(0.8, five.pred10, 0.0)
        assertEquals(0.8, five.pred50, 0.0)
        assertEquals(0.0, five.mad, 0.0)
        assertEquals(0.6 * (1e6 - 1.0), five.idr, 1e-3) // p90 sits 0.6 of the way from rank 3 (1) to rank 4 (1e6)
        assertNull(five.tailShare)
        listOf(five.pred10, five.pred25, five.pred50, five.mdmre, five.smape, five.iqr, five.idr, five.mad)
            .forEach { assertTrue(it.isFinite()) }
        // Six values tied in two groups (1, 1, 1, 3, 3, 3): s = sqrt(6 / 5), every point is 1 away from the mean and 1 < 2s -> 0.
        assertEquals(0.0, assertNotNull(tailOf(1.0, 1.0, 1.0, 3.0, 3.0, 3.0)), 0.0)
        val six = assertNotNull(accuracyMeasures(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1e6), minSampleSize = 1))
        assertEquals(1.0 / 6, assertNotNull(six.tailShare), tolerance)
        assertTrue(six.smape.isFinite() && six.smape < 2.0)
    }

    @Test
    fun `fewer items than the minimum sample size has no measures`() {
        assertNull(accuracyMeasures(listOf(1.0, 2.0, 3.0), minSampleSize = 4))
        assertNull(accuracyMeasures(emptyList(), minSampleSize = 1))
        assertNotNull(accuracyMeasures(listOf(1.0, 2.0, 3.0, 4.0), minSampleSize = 4))
    }
}
