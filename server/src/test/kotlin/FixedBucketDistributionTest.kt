package ch.nokillswit

import ch.nokillswit.reports.FIXED_BUCKET_STEP
import ch.nokillswit.reports.HistogramBucket
import ch.nokillswit.reports.MAX_FIXED_BUCKETS
import ch.nokillswit.reports.UserValues
import ch.nokillswit.reports.bucketAssignment
import ch.nokillswit.reports.buildDistribution
import ch.nokillswit.reports.buildDistributionByUser
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests (no DB) of the FIXED-width histogram layout of `reports/Distribution.kt` (`fixedStep`, Task estimates, Report 19):
 * bars of 0.5 MD, each `(from, to]` labelled by its upper bound, contiguous from the bar holding the minimum (empty bars included),
 * the `tailPercent` cut rounded UP to a multiple of the width, and the width widened to the smallest multiple of 0.5 that fits
 * [MAX_FIXED_BUCKETS] bars. Every expectation is written out by hand; the equal-width layout stays `DistributionTest`'s.
 */
class FixedBucketDistributionTest {

    private fun fixed(values: List<Double>, tailPercent: Int = 0, minSampleSize: Int = 1) =
        buildDistribution(values, minSampleSize, tailPercent, fixedStep = FIXED_BUCKET_STEP)

    private fun bar(from: Double, to: Double?, count: Long) = HistogramBucket(from, to, count)

    @Test
    fun `bars are 0_5 MD wide, labelled by their upper bound, with an exact multiple on its own bar and empty bars kept`() {
        val distribution = fixed(listOf(0.125, 0.5, 0.75, 1.0, 1.0, 2.0, 2.5))
        assertEquals(0.5, distribution.bucketWidth)
        assertEquals(
            listOf(
                bar(0.0, 0.5, 2), // 0.125 (it sits on the "0.5" bar) and exactly 0.5
                bar(0.5, 1.0, 3), // 0.75 and the exact 1.0 twice: an exact multiple is on ITS bar, not the next one
                bar(1.0, 1.5, 0), // an empty bar between two full ones is drawn
                bar(1.5, 2.0, 1),
                bar(2.0, 2.5, 1),
            ),
            distribution.histogram,
        )
        // The unrelated statistics are the plain ones over every value.
        assertEquals(7L, distribution.n)
        assertEquals(0.125, distribution.min)
        assertEquals(2.5, distribution.max)
    }

    @Test
    fun `the first bar is the one holding the minimum`() {
        // An exact multiple minimum: 1.0 is on the (0.5, 1] bar, so nothing below it is drawn.
        assertEquals(listOf(bar(0.5, 1.0, 1), bar(1.0, 1.5, 1)), fixed(listOf(1.0, 1.2)).histogram)
        // A fractional minimum: 1.01 is on the (1, 1.5] bar.
        assertEquals(listOf(bar(1.0, 1.5, 2)), fixed(listOf(1.01, 1.5)).histogram)
        // One value, and ties: one bar holding them all, never a zero-width one.
        assertEquals(listOf(bar(1.0, 1.5, 3)), fixed(listOf(1.5, 1.5, 1.5)).histogram)
        assertEquals(listOf(bar(0.0, 0.5, 1)), fixed(listOf(0.3)).histogram)
    }

    @Test
    fun `the tail cut rounds up to a multiple of the width and the open bar holds what lies strictly above it`() {
        // 1..19 and an outlier 30; the 90th percentile is 18.1, which rounds up to 18.5: 19 and 30 fold into the open bar.
        val values = (1..19).map { it.toDouble() } + 30.0
        val tailed = fixed(values, tailPercent = 10)
        assertEquals(0.5, tailed.bucketWidth)
        assertEquals(37, tailed.histogram.size, "36 body bars from (0.5, 1] to (18, 18.5], then the open one")
        assertEquals(bar(0.5, 1.0, 1), tailed.histogram.first())
        assertEquals(bar(18.0, 18.5, 0), tailed.histogram[35], "the last body bar ends at the rounded cut and holds nothing here")
        assertEquals(bar(18.5, null, 2), tailed.histogram.last())
        assertEquals((1..18).map { 2 * it - 2 }, tailed.histogram.indices.filter { tailed.histogram[it].count == 1L && it < 36 })
        assertEquals(20L, tailed.histogram.sumOf { it.count })
        // The same values with the tail off would need 59 bars of 0.5 MD: they widen to 1 MD, 30 bars to (29, 30], no open bar.
        val plain = fixed(values)
        assertEquals(1.0, plain.bucketWidth)
        assertEquals(30, plain.histogram.size)
        assertTrue(plain.histogram.none { it.to == null })
        assertEquals(bar(29.0, 30.0, 1), plain.histogram.last())
        // A value exactly ON the cut (the 90th percentile of 11 values is the 10th, 10.0) stays in the last body bar: the open bar
        // is strictly above it.
        val onTheCut = fixed((1..9).map { it.toDouble() } + listOf(10.0, 30.0), tailPercent = 10)
        assertEquals(20, onTheCut.histogram.size)
        assertEquals(bar(9.5, 10.0, 1), onTheCut.histogram[18])
        assertEquals(bar(10.0, null, 1), onTheCut.histogram.last())
    }

    @Test
    fun `float noise in the percentile cut cannot move it a whole bar`() {
        // n = 20, tail 5: the cut is sorted[18] + 0.05 * (sorted[19] - sorted[18]) = 0.98 + 0.05 * 0.4, mathematically 1.0 but
        // 1.0000000000000002 as a double. Unsnapped, rounding it UP gives the bar ending at 1.5, which swallows 1.38 and the open bar.
        val values = List(18) { 0.5 } + listOf(0.98, 1.38)
        val tailed = fixed(values, tailPercent = 5)
        assertEquals(listOf(bar(0.0, 0.5, 18), bar(0.5, 1.0, 1), bar(1.0, null, 1)), tailed.histogram)
        // The same shape one bar up (the cut is 2.0 mathematically) and with other decimals: the open bar starts at the exact multiple.
        val shifted = fixed(List(18) { 1.5 } + listOf(1.98, 2.38), tailPercent = 5)
        assertEquals(bar(2.0, null, 1), shifted.histogram.last())
        assertEquals(bar(1.5, 2.0, 1), shifted.histogram[shifted.histogram.lastIndex - 1])
    }

    @Test
    fun `no open bar when most values tie at the minimum or nothing lies above the rounded cut`() {
        // The 95th percentile equals the minimum (most values tie there): the plain bars, as the equal-width layout does.
        val tied = fixed(List(30) { 1.0 } + listOf(5.0), tailPercent = 5)
        assertTrue(tied.histogram.none { it.to == null })
        assertEquals(bar(4.5, 5.0, 1), tied.histogram.last())
        // 0.25..10.0 in steps of 0.25: the 95th percentile 9.5125 rounds up to 10, which is the maximum.
        val body = fixed((1..40).map { it * 0.25 }, tailPercent = 5)
        assertTrue(body.histogram.none { it.to == null })
        assertEquals(bar(9.5, 10.0, 2), body.histogram.last())
    }

    @Test
    fun `more than 40 bars widen the bar to the smallest multiple of 0_5 that fits`() {
        // 40 bars exactly: (0, 0.5] .. (19.5, 20]
        val forty = fixed(listOf(0.5, 20.0))
        assertEquals(MAX_FIXED_BUCKETS, forty.histogram.size)
        assertEquals(0.5, forty.bucketWidth)
        // One more bar than the cap: 1 MD bars, (0, 1] .. (20, 21].
        val widened = fixed(listOf(0.5, 20.5))
        assertEquals(1.0, widened.bucketWidth)
        assertEquals(21, widened.histogram.size)
        assertEquals(bar(0.0, 1.0, 1), widened.histogram.first())
        assertEquals(bar(20.0, 21.0, 1), widened.histogram.last())
        // 1 MD would still draw 50 bars: the next multiple, 1.5 MD, draws 34.
        val wider = fixed(listOf(1.0, 50.0))
        assertEquals(1.5, wider.bucketWidth)
        assertEquals(34, wider.histogram.size)
        assertEquals(bar(0.0, 1.5, 1), wider.histogram.first())
        assertEquals(bar(49.5, 51.0, 1), wider.histogram.last())
        // The open bar counts toward the cap: 39 body bars + the open one is 40, 40 body bars + the open one widens.
        val body39 = fixed((1..39).map { it * 0.5 } + 100.0, tailPercent = 5)
        assertEquals(0.5, body39.bucketWidth)
        assertEquals(MAX_FIXED_BUCKETS, body39.histogram.size)
        assertEquals(bar(19.5, null, 1), body39.histogram.last(), "the outlier folds into the open bar, the 40th")
        val body40 = fixed((1..40).map { it * 0.5 } + listOf(100.0, 200.0), tailPercent = 5)
        assertEquals(1.0, body40.bucketWidth, "40 body bars and the open one are 41: the bar widens")
        assertEquals(bar(20.0, null, 2), body40.histogram.last())
    }

    @Test
    fun `byUser shares sit on the union's fixed bars and a hidden distribution carries no width`() {
        val ann = UserValues("ann", "Ann", listOf(0.5, 1.0, 3.0))
        val bob = UserValues("bob", "Bob", listOf(1.0, 0.25))
        val nobody = UserValues("nobody", "Nobody", emptyList())
        val split = buildDistributionByUser(listOf(bob, ann, nobody), 1, 0, fixedStep = FIXED_BUCKET_STEP)
        val union = fixed(listOf(0.5, 1.0, 3.0, 1.0, 0.25))
        assertEquals(union, split.copy(byUser = emptyList()))
        assertEquals(0.5, split.bucketWidth)
        assertEquals(listOf("bob", "ann", "nobody"), split.byUser.map { it.accountId })
        // Bars (0, .5] (.5, 1] (1, 1.5] (1.5, 2] (2, 2.5] (2.5, 3]
        assertEquals(listOf(1L, 1L, 0L, 0L, 0L, 0L), split.byUser[0].counts)
        assertEquals(listOf(1L, 1L, 0L, 0L, 0L, 1L), split.byUser[1].counts)
        assertEquals(List(6) { 0L }, split.byUser[2].counts)
        assertEquals(split.histogram.map { it.count }, split.histogram.indices.map { i -> split.byUser.sumOf { it.counts[i] } })

        val hidden = buildDistribution(listOf(1.0, 2.0), 3, 5, fixedStep = FIXED_BUCKET_STEP)
        assertTrue(hidden.hidden)
        assertNull(hidden.bucketWidth)
        assertTrue(hidden.histogram.isEmpty())
        // The default layout never sets it.
        assertNull(buildDistribution(listOf(1.0, 2.0, 3.0), 1, 5).bucketWidth)
    }

    @Test
    fun `bucketAssignment places every value on the bar the histogram draws, for random inputs with and without a tail`() {
        val random = Random(20261020)
        repeat(200) { round ->
            val size = 1 + random.nextInt(60)
            // Exact multiples, fractions of 0.125, and a few far outliers: the shapes the edge, tail and widening rules exist for.
            val values = List(size) {
                when (random.nextInt(5)) {
                    0 -> random.nextInt(1, 12) * 0.5
                    1 -> random.nextInt(1, 40) * 0.125
                    2 -> random.nextInt(1, 40) * 2.5
                    else -> random.nextInt(1, 100) / 8.0
                }
            }
            for (tailPercent in listOf(0, 5, 25)) {
                val label = "round $round n=$size tail=$tailPercent"
                val assignment = assertNotNull(bucketAssignment(values, 1, tailPercent, fixedStep = FIXED_BUCKET_STEP), label)
                val distribution = fixed(values, tailPercent)
                assertEquals(distribution.histogram, assignment.histogram, "$label histogram")
                assertTrue(distribution.histogram.size <= MAX_FIXED_BUCKETS, "$label at most $MAX_FIXED_BUCKETS bars")
                val width = assertNotNull(distribution.bucketWidth, label)
                assertTrue(width >= 0.5 && (width / 0.5) % 1.0 == 0.0, "$label the width is a multiple of 0.5, was $width")
                assertEquals(values.size.toLong(), distribution.histogram.sumOf { it.count }, "$label counts sum to n")
                values.forEachIndexed { at, value ->
                    val bucket = distribution.histogram[assignment.indexes[at]]
                    val inside = if (bucket.to == null) value > bucket.from else value > bucket.from && value <= bucket.to
                    assertTrue(inside, "$label $value must lie in (${bucket.from}, ${bucket.to}]")
                }
                // The bars are contiguous multiples of the width.
                distribution.histogram.filter { it.to != null }.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, label) }
                distribution.histogram.filter { it.to != null }.forEach { assertEquals(width, it.to!! - it.from, 1e-9, label) }
            }
        }
    }
}
