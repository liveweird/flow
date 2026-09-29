package ch.nokillswit

import ch.nokillswit.reports.buildDistribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure unit tests of `reports/Distribution.kt`'s Kotlin-fallback builder — no DB. */
class DistributionTest {

    @Test
    fun `known percentiles over 1 to 10 match PostgreSQL's percentile_cont formula`() {
        val values = (1..10).map { it.toDouble() }
        val distribution = buildDistribution(values, minSampleSize = 1)
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
        val distribution = buildDistribution(listOf(1.0, 2.0, 3.0), minSampleSize = 5)
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
        val distribution = buildDistribution(listOf(1.0, 2.0, 3.0), minSampleSize = 3)
        assertEquals(false, distribution.hidden)
        assertNotNull(distribution.mean)
    }

    @Test
    fun `histogram buckets sum to n and cover every value`() {
        val values = listOf(1.0, 3.0, 3.0, 5.0, 7.0, 9.0, 9.5, 10.0)
        val distribution = buildDistribution(values, minSampleSize = 1, histogramBuckets = 5)
        assertEquals(5, distribution.histogram.size)
        assertEquals(values.size.toLong(), distribution.histogram.sumOf { it.count })
        assertEquals(1.0, distribution.histogram.first().from)
        assertEquals(10.0, distribution.histogram.last().to)
        // every bucket's edges are contiguous
        distribution.histogram.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from) }
    }

    @Test
    fun `a single-valued dataset collapses into one bucket holding every value`() {
        val distribution = buildDistribution(listOf(4.0, 4.0, 4.0), minSampleSize = 1, histogramBuckets = 10)
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
        val distribution = buildDistribution(listOf(7.0), minSampleSize = 1)
        assertEquals(1L, distribution.n)
        assertEquals(false, distribution.hidden)
        assertEquals(7.0, distribution.mean)
        assertEquals(7.0, distribution.p50)
    }
}
