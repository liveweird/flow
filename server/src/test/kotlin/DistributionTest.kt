package ch.nokillswit

import ch.nokillswit.reports.HistogramBucket
import ch.nokillswit.reports.UserValues
import ch.nokillswit.reports.bucketAssignment
import ch.nokillswit.reports.buildDistribution
import ch.nokillswit.reports.buildDistributionByUser
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure unit tests of `reports/Distribution.kt`'s Kotlin-fallback builder — no DB. */
class DistributionTest {

    @Test
    fun `known percentiles over 1 to 10 match PostgreSQL's percentile_cont formula`() {
        val values = (1..10).map { it.toDouble() }
        val distribution = buildDistribution(values, minSampleSize = 1, tailPercent = 0)
        assertEquals(10L, distribution.n)
        assertEquals(false, distribution.hidden)
        assertEquals(5.5, distribution.mean)
        assertEquals(1.0, distribution.min)
        assertEquals(10.0, distribution.max)
        // percentile_cont(0.5) over 1..10: rank = 0.5*9 = 4.5 -> interpolate between index 4 (5.0) and 5 (6.0)
        assertEquals(5.5, distribution.p50)
        // percentile_cont(0.9): rank = 0.9*9 = 8.1 -> interpolate between index 8 (9.0) and 9 (10.0)
        assertEquals(9.1, distribution.p90!!, 1e-9)
        // percentile_cont(0.95): rank = 0.95*9 = 8.55 -> interpolate between index 8 (9.0) and 9 (10.0)
        assertEquals(9.55, distribution.p95!!, 1e-9)
    }

    @Test
    fun `hidden below the minimum sample size - only n is set`() {
        val distribution = buildDistribution(listOf(1.0, 2.0, 3.0), minSampleSize = 5, tailPercent = 0)
        assertEquals(3L, distribution.n)
        assertTrue(distribution.hidden)
        assertNull(distribution.mean)
        assertNull(distribution.min)
        assertNull(distribution.max)
        assertNull(distribution.p50)
        assertNull(distribution.p90)
        assertNull(distribution.p95)
        assertEquals(emptyList(), distribution.histogram)
    }

    @Test
    fun `n equal to the minimum sample size is not hidden`() {
        val distribution = buildDistribution(listOf(1.0, 2.0, 3.0), minSampleSize = 3, tailPercent = 0)
        assertEquals(false, distribution.hidden)
        assertNotNull(distribution.mean)
    }

    @Test
    fun `histogram buckets sum to n and cover every value`() {
        val values = listOf(1.0, 3.0, 3.0, 5.0, 7.0, 9.0, 9.5, 10.0)
        val distribution = buildDistribution(values, minSampleSize = 1, tailPercent = 0, histogramBuckets = 5)
        assertEquals(5, distribution.histogram.size)
        assertEquals(values.size.toLong(), distribution.histogram.sumOf { it.count })
        assertEquals(1.0, distribution.histogram.first().from)
        assertEquals(10.0, distribution.histogram.last().to)
        // every bucket's edges are contiguous
        distribution.histogram.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from) }
        // tailPercent = 0 never produces an open bucket
        assertTrue(distribution.histogram.all { it.to != null })
    }

    @Test
    fun `a single-valued dataset collapses into one bucket holding every value`() {
        val distribution = buildDistribution(listOf(4.0, 4.0, 4.0), minSampleSize = 1, tailPercent = 0, histogramBuckets = 10)
        assertEquals(1, distribution.histogram.size)
        assertEquals(4.0, distribution.histogram.single().from)
        assertEquals(4.0, distribution.histogram.single().to)
        assertEquals(3L, distribution.histogram.single().count)
        assertEquals(4.0, distribution.p50)
        assertEquals(4.0, distribution.p90)
        assertEquals(4.0, distribution.p95)
    }

    @Test
    fun `a single-value list still reports n and every statistic equal to that value`() {
        val distribution = buildDistribution(listOf(7.0), minSampleSize = 1, tailPercent = 0)
        assertEquals(1L, distribution.n)
        assertEquals(false, distribution.hidden)
        assertEquals(7.0, distribution.mean)
        assertEquals(7.0, distribution.p50)
    }

    // --- the long tail: tailPercent folds the top N% into one final open bucket ---

    private fun assertBucket(expected: HistogramBucket, actual: HistogramBucket, label: String) {
        assertEquals(expected.from, actual.from, 1e-9, "$label from")
        if (expected.to == null) assertNull(actual.to, "$label to") else assertEquals(expected.to, actual.to!!, 1e-9, "$label to")
        assertEquals(expected.count, actual.count, "$label count")
    }

    private fun assertHistogram(expected: List<HistogramBucket>, actual: List<HistogramBucket>) {
        assertEquals(expected.size, actual.size, "bucket count")
        expected.zip(actual).forEachIndexed { i, (want, got) -> assertBucket(want, got, "bucket $i") }
    }

    @Test
    fun `an outlier is folded into a final open bucket and the body spreads over equal-width buckets`() {
        // 1..10 plus 100, n = 11. tailPercent 10: rank = 0.9 * 10 = 10 -> X = sorted[9] = 10, so the tail is {100}.
        val values = (1..10).map { it.toDouble() } + 100.0
        val tailed = buildDistribution(values, minSampleSize = 1, tailPercent = 10, histogramBuckets = 3)
        // body 1..10 over [1, 10], width 3: 1-3 | 4-6 | 7-9 and the closed last bucket also takes 10 -> 3, 3, 4
        assertHistogram(
            listOf(
                HistogramBucket(1.0, 4.0, 3), HistogramBucket(4.0, 7.0, 3), HistogramBucket(7.0, 10.0, 4),
                HistogramBucket(10.0, null, 1),
            ),
            tailed.histogram,
        )
        // the plain histogram of the same input is what the tail fixes: everything but the outlier in the first bucket
        val plain = buildDistribution(values, minSampleSize = 1, tailPercent = 0, histogramBuckets = 3)
        assertHistogram(
            listOf(HistogramBucket(1.0, 34.0, 10), HistogramBucket(34.0, 67.0, 0), HistogramBucket(67.0, 100.0, 1)),
            plain.histogram,
        )
    }

    @Test
    fun `an interpolated cut keeps only values strictly above it in the tail`() {
        // 1..20, tailPercent 5: rank = 0.95 * 19 = 18.05 -> X = 19 + 0.05 * (20 - 19) = 19.05; the tail is {20}.
        val distribution = buildDistribution((1..20).map { it.toDouble() }, minSampleSize = 1, tailPercent = 5, histogramBuckets = 5)
        // body 1..19 over [1, 19.05], width 3.61: {1..4} {5..8} {9..11} {12..15} {16..19}
        val width = 18.05 / 5
        val edges = (0..5).map { 1.0 + it * width }
        assertHistogram(
            listOf(
                HistogramBucket(edges[0], edges[1], 4), HistogramBucket(edges[1], edges[2], 4), HistogramBucket(edges[2], edges[3], 3),
                HistogramBucket(edges[3], edges[4], 4), HistogramBucket(edges[4], edges[5], 4), HistogramBucket(19.05, null, 1),
            ),
            distribution.histogram,
        )
    }

    @Test
    fun `negative values fold the same way`() {
        // -10..8 step 2 plus 50, n = 11. tailPercent 10: X = sorted[9] = 8; the tail is {50}.
        val values = (-10..8 step 2).map { it.toDouble() } + 50.0
        val distribution = buildDistribution(values, minSampleSize = 1, tailPercent = 10, histogramBuckets = 3)
        // body -10..8 over [-10, 8], width 6: {-10,-8,-6} {-4,-2,0} {2,4,6,8}
        assertHistogram(
            listOf(
                HistogramBucket(-10.0, -4.0, 3), HistogramBucket(-4.0, 2.0, 3), HistogramBucket(2.0, 8.0, 4),
                HistogramBucket(8.0, null, 1),
            ),
            distribution.histogram,
        )
    }

    @Test
    fun `ties at the top swallow the tail so the histogram stays the plain one`() {
        // The 90th percentile is 5 and no value exceeds it, so there is no open bucket.
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0)
        val tailed = buildDistribution(values, minSampleSize = 1, tailPercent = 10, histogramBuckets = 4)
        val plain = buildDistribution(values, minSampleSize = 1, tailPercent = 0, histogramBuckets = 4)
        assertEquals(plain, tailed)
        assertTrue(tailed.histogram.all { it.to != null })
    }

    @Test
    fun `one value and all-equal values never get an open bucket`() {
        val one = buildDistribution(listOf(7.0), minSampleSize = 1, tailPercent = 25)
        assertEquals(listOf(HistogramBucket(7.0, 7.0, 1)), one.histogram)
        val equal = buildDistribution(listOf(4.0, 4.0, 4.0, 4.0), minSampleSize = 1, tailPercent = 25)
        assertEquals(listOf(HistogramBucket(4.0, 4.0, 4)), equal.histogram)
    }

    @Test
    fun `a tied body spans up to the cut and the tail still gets its bucket`() {
        // 1 x 19 then 100, n = 20: the 95th percentile is rank 18.05, between sorted[18] (1) and sorted[19] (100):
        // X = 1 + 0.05 * 99 = 5.95. The body {1 x 19} spans [1, 5.95] (2 buckets, width 2.475); the tail is {100}.
        val values = List(19) { 1.0 } + 100.0
        val distribution = buildDistribution(values, minSampleSize = 1, tailPercent = 5, histogramBuckets = 2)
        assertHistogram(
            listOf(HistogramBucket(1.0, 3.475, 19), HistogramBucket(3.475, 5.95, 0), HistogramBucket(5.95, null, 1)),
            distribution.histogram,
        )
    }

    @Test
    fun `a cut at the minimum keeps the plain histogram - mostly-zero values never lose their shape`() {
        // 96 zeros and 4 blocked values: the 95th percentile is 0 (= min), so folding would leave one zero-width body
        // bucket and hide the four real values in the tail. The plain histogram over [0, 8] is kept instead.
        val values = List(96) { 0.0 } + listOf(2.0, 4.0, 6.0, 8.0)
        val tailed = buildDistribution(values, minSampleSize = 1, tailPercent = 5, histogramBuckets = 4)
        val plain = buildDistribution(values, minSampleSize = 1, tailPercent = 0, histogramBuckets = 4)
        assertEquals(plain.histogram, tailed.histogram)
        assertTrue(tailed.histogram.all { it.to != null })
    }

    @Test
    fun `the last body bucket ends exactly at the cut where the tail begins`() {
        val values = (1..301).map { it * 0.1 }
        for (n in 1..25) {
            val histogram = buildDistribution(values, minSampleSize = 1, tailPercent = n).histogram
            val tail = histogram.last()
            if (tail.to == null) assertEquals(tail.from, histogram[histogram.size - 2].to, "tailPercent=$n")
        }
    }

    @Test
    fun `the tail changes the histogram only, never n mean min max or the percentiles`() {
        val values = (1..40).map { (it * it).toDouble() }
        val plain = buildDistribution(values, minSampleSize = 1, tailPercent = 0)
        val tailed = buildDistribution(values, minSampleSize = 1, tailPercent = 10)
        assertEquals(plain.copy(histogram = emptyList()), tailed.copy(histogram = emptyList()))
        assertNull(tailed.histogram.last().to)
    }

    @Test
    fun `a hidden distribution is untouched by tailPercent`() {
        assertEquals(
            buildDistribution(listOf(1.0, 9.0), minSampleSize = 3, tailPercent = 0),
            buildDistribution(listOf(1.0, 9.0), minSampleSize = 3, tailPercent = 25),
        )
    }

    @Test
    fun `tailPercent outside 0 to 25 is refused`() {
        assertFailsWith<IllegalArgumentException> { buildDistribution(listOf(1.0, 2.0), minSampleSize = 1, tailPercent = 26) }
        assertFailsWith<IllegalArgumentException> { buildDistribution(listOf(1.0, 2.0), minSampleSize = 1, tailPercent = -1) }
    }

    /** The pre-tail algorithm, written out independently: `buckets` equal-width bins over `[min, max]`, `floor`-based, last closed. */
    private fun referenceHistogram(values: List<Double>, buckets: Int): List<HistogramBucket> {
        val min = values.min()
        val max = values.max()
        if (min == max) return listOf(HistogramBucket(min, max, values.size.toLong()))
        val width = (max - min) / buckets
        val counts = LongArray(buckets)
        for (v in values) counts[minOf(floor((v - min) / width).toInt(), buckets - 1)]++
        return (0 until buckets).map { HistogramBucket(min + it * width, min + (it + 1) * width, counts[it]) }
    }

    /** An independent `percentile_cont` (rank arithmetic written differently from the production helper). */
    private fun referencePercentile(sorted: List<Double>, fraction: Double): Double {
        val rank = fraction * (sorted.size - 1)
        val below = sorted[floor(rank).toInt()]
        val above = sorted[ceil(rank).toInt()]
        return below + (above - below) * (rank - floor(rank))
    }

    @Test
    fun `tailPercent 0 reproduces the pre-tail algorithm on random inputs`() {
        val random = Random(20261015)
        repeat(200) {
            val n = random.nextInt(1, 120)
            // Mixed shapes: heavy-tailed, with duplicates, with negatives.
            val values = List(n) {
                val v = random.nextDouble(-20.0, 60.0) * if (random.nextInt(10) == 0) 50 else 1
                if (random.nextBoolean()) Math.round(v).toDouble() else v
            }
            val buckets = random.nextInt(1, 15)
            assertHistogram(referenceHistogram(values, buckets), buildDistribution(values, 1, 0, buckets).histogram)
        }
    }

    @Test
    fun `with a tail the counts sum to n the open bucket holds exactly the values above the cut and only it is open`() {
        val random = Random(42)
        repeat(300) {
            val n = random.nextInt(1, 150)
            val values = List(n) { Math.round(random.nextDouble(-30.0, 80.0) * if (random.nextInt(8) == 0) 20 else 1).toDouble() / 4 }
            val tailPercent = random.nextInt(1, 26)
            val buckets = random.nextInt(1, 12)
            val histogram = buildDistribution(values, 1, tailPercent, buckets).histogram
            assertEquals(n.toLong(), histogram.sumOf { it.count }, "Σ counts == n")
            val sorted = values.sorted()
            val cut = referencePercentile(sorted, (100 - tailPercent) / 100.0)
            val above = values.count { it > cut }.toLong()
            if (above == 0L) {
                assertTrue(histogram.all { it.to != null }, "no tail, no open bucket")
                assertHistogram(referenceHistogram(values, buckets), histogram)
            } else {
                assertNull(histogram.last().to)
                assertEquals(above, histogram.last().count, "open bucket count")
                assertEquals(cut, histogram.last().from, 1e-9, "open bucket from")
                assertTrue(histogram.dropLast(1).all { it.to != null }, "only the last bucket is open")
                // the body spans [min, cut], not [min, body max]
                assertHistogram(equalWidth(values.filter { it <= cut }, sorted.first(), cut, buckets), histogram.dropLast(1))
                assertTrue(abs(histogram.first().from - sorted.first()) < 1e-9)
            }
        }
    }

    // --- the per-person split: buildDistributionByUser stacks each person over the union's own bucket layout ---

    @Test
    fun `per-user shares sum to each bucket's count and to each user's n`() {
        val random = Random(20261016)
        var withTail = 0
        var withTies = 0
        repeat(300) { round ->
            val perUser = List(random.nextInt(1, 6)) { u ->
                UserValues(
                    "acc-$u", "User $u",
                    List(random.nextInt(0, 40)) {
                        // Mixed shapes: heavy-tailed, negatives, and (every third round) rounded values that tie.
                        val v = random.nextDouble(-10.0, 40.0) * if (random.nextInt(8) == 0) 30 else 1
                        if (round % 3 == 0) Math.round(v).toDouble() else v
                    },
                )
            }
            val all = perUser.flatMap { it.values }
            if (all.isEmpty()) return@repeat
            val tailPercent = if (round % 2 == 0) 0 else random.nextInt(1, 26)
            val buckets = random.nextInt(1, 12)
            val split = buildDistributionByUser(perUser, 1, tailPercent, buckets)
            if (split.histogram.last().to == null) withTail++
            if (all.distinct().size < all.size) withTies++
            // The union is exactly the plain distribution of the flattened values, bar the shares.
            assertEquals(buildDistribution(all, 1, tailPercent, buckets), split.copy(byUser = emptyList()), "round $round union")
            assertEquals(perUser.map { it.accountId }, split.byUser.map { it.accountId }, "round $round request order")
            assertEquals(perUser.map { it.label }, split.byUser.map { it.label }, "round $round labels")
            split.byUser.forEach { assertEquals(split.histogram.size, it.counts.size, "round $round counts align with the histogram") }
            split.histogram.forEachIndexed { i, bucket ->
                assertEquals(bucket.count, split.byUser.sumOf { it.counts[i] }, "round $round bucket $i")
            }
            perUser.zip(split.byUser).forEach { (user, share) ->
                assertEquals(user.values.size.toLong(), share.counts.sum(), "round $round ${user.accountId} n")
            }
        }
        assertTrue(withTail > 20 && withTies > 20, "the random rounds must cover a tail and ties ($withTail, $withTies)")
    }

    @Test
    fun `hand-built shares split the body buckets and the open tail bucket per person`() {
        // 1..10 plus 100 (tail), tailPercent 10, 3 buckets: bodies {1-3} {4-6} {7-10} and the open bucket {100} (see the outlier test).
        val ann = UserValues("ann", "Ann", listOf(1.0, 2.0, 5.0, 10.0, 100.0))
        val bob = UserValues("bob", "Bob", listOf(3.0, 4.0, 6.0, 7.0, 8.0, 9.0))
        val split = buildDistributionByUser(listOf(bob, ann), minSampleSize = 1, tailPercent = 10, histogramBuckets = 3)
        assertEquals(listOf(3L, 3L, 4L, 1L), split.histogram.map { it.count })
        assertEquals(listOf("bob", "ann"), split.byUser.map { it.accountId })
        assertEquals(listOf(1L, 2L, 3L, 0L), split.byUser[0].counts)
        assertEquals(listOf(2L, 1L, 1L, 1L), split.byUser[1].counts)
    }

    @Test
    fun `a hidden union carries no shares`() {
        val split = buildDistributionByUser(
            listOf(UserValues("a", "A", listOf(1.0, 2.0)), UserValues("b", "B", listOf(3.0))),
            minSampleSize = 4,
            tailPercent = 5,
        )
        assertTrue(split.hidden)
        assertEquals(3L, split.n)
        assertEquals(emptyList(), split.byUser)
        assertEquals(buildDistribution(listOf(1.0, 2.0, 3.0), 4, 5), split)
        // A plain distribution never carries shares either.
        assertEquals(emptyList(), buildDistribution(listOf(1.0, 2.0, 3.0, 4.0), 1, 0).byUser)
    }

    @Test
    fun `a user with no values is an all-zero share`() {
        val split = buildDistributionByUser(
            listOf(UserValues("idle", "Idle", emptyList()), UserValues("busy", "Busy", listOf(1.0, 2.0, 3.0, 9.0))),
            minSampleSize = 1,
            tailPercent = 0,
            histogramBuckets = 4,
        )
        val idle = split.byUser.first()
        assertEquals("idle", idle.accountId)
        assertEquals(split.histogram.size, idle.counts.size)
        assertTrue(idle.counts.all { it == 0L })
        assertEquals(split.histogram.map { it.count }, split.byUser.last().counts)
        // Even if EVERY user is empty the union is hidden (n = 0 < any minimum) and carries no shares.
        val none = buildDistributionByUser(listOf(UserValues("idle", "Idle", emptyList())), minSampleSize = 1, tailPercent = 5)
        assertTrue(none.hidden)
        assertEquals(emptyList(), none.byUser)
    }

    @Test
    fun `bucketAssignment agrees with buildDistribution's histogram for random inputs, with and without a tail`() {
        val random = Random(20261017)
        repeat(200) { round ->
            val size = 1 + random.nextInt(60)
            // Ties, zeros and a few far outliers: the shapes the tail and the zero-width layouts exist for.
            val values = List(size) {
                when (random.nextInt(6)) {
                    0 -> 0.0
                    1 -> random.nextInt(5).toDouble()
                    2 -> random.nextDouble() * 400
                    else -> random.nextDouble() * 10
                }
            }
            for (tailPercent in listOf(0, 5, 25)) {
                val label = "round $round n=$size tail=$tailPercent"
                val assignment = assertNotNull(bucketAssignment(values, minSampleSize = 1, tailPercent = tailPercent), label)
                val histogram = buildDistribution(values, minSampleSize = 1, tailPercent = tailPercent).histogram
                assertEquals(histogram, assignment.histogram, "$label histogram")
                assertEquals(values.size, assignment.indexes.size, "$label one index per value, in input order")
                assertEquals(
                    histogram.map { it.count },
                    histogram.indices.map { bucket -> assignment.indexes.count { it == bucket }.toLong() },
                    "$label the indexes count to the histogram's own counts",
                )
                assertTrue(assignment.indexes.all { it in histogram.indices }, label)
                // The tail bucket holds exactly the values strictly above its `from`; the body holds the rest.
                val tail = histogram.last().takeIf { it.to == null }
                values.forEachIndexed { at, value ->
                    if (tail != null) assertEquals(value > tail.from, assignment.indexes[at] == histogram.lastIndex, "$label value $value")
                }
            }
        }
    }

    @Test
    fun `a hidden distribution has no assignment`() {
        assertNull(bucketAssignment(listOf(1.0, 2.0, 3.0), minSampleSize = 4, tailPercent = 5))
        assertNull(bucketAssignment(emptyList(), minSampleSize = 1, tailPercent = 0))
        assertNotNull(bucketAssignment(listOf(1.0, 2.0, 3.0), minSampleSize = 3, tailPercent = 5))
    }

    private fun equalWidth(values: List<Double>, min: Double, max: Double, buckets: Int): List<HistogramBucket> {
        if (min == max) return listOf(HistogramBucket(min, max, values.size.toLong()))
        val width = (max - min) / buckets
        val counts = LongArray(buckets)
        for (v in values) counts[minOf(floor((v - min) / width).toInt(), buckets - 1)]++
        return (0 until buckets).map { HistogramBucket(min + it * width, min + (it + 1) * width, counts[it]) }
    }
}
