package ch.nokillswit

import ch.nokillswit.reports.Distribution
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Shared test-side helpers of the Task estimates tests (`ReportTaskEstimatesTest`, `ReportDrillAccuracyBlockedTest`,
 * `ReportDrillAccuracyBlockedHandBuiltTest`, `ReportTailPercentTest`): what the report must answer for a set of raw fact rows, and an
 * INDEPENDENT re-derivation of its fixed 0.5-MD histogram (`.claude/docs/reports.md` "Report 19") — written here from the rule
 * (bar `k` = `((k − 1)·w, k·w]`, the bar of `v` is `ceil(v ÷ w)`, `w` the smallest multiple of 0.5 that draws at most 40 bars), never
 * by calling the production layout.
 */

/** What Task estimates must answer for a set of DONE tasks, read off the raw `fact_task_delivery` rows. */
internal data class ExpectedEstimates(
    val atDone: List<Double>,
    val atStart: List<Double>,
    val population: Int,
    val neverStarted: Int,
    val unestimatedAtStart: Int,
    val unestimatedAtDone: Int,
)

private fun BigDecimal?.isPositive() = this != null && this.signum() > 0

internal fun expectedEstimates(tasks: List<TaskFact>): ExpectedEstimates {
    val atDone = tasks.filter { it.estDone.isPositive() }.map { it.estDone!!.toDouble() }
    val started = tasks.filter { it.startedAt != null }
    val atStart = started.filter { it.estStart.isPositive() }.map { it.estStart!!.toDouble() }
    return ExpectedEstimates(
        atDone = atDone,
        atStart = atStart,
        population = tasks.size,
        neverStarted = tasks.size - started.size,
        unestimatedAtStart = started.size - atStart.size,
        unestimatedAtDone = tasks.size - atDone.size,
    )
}

/** One bar of the reference histogram: `(from, to]`, or the open tail `(from, ∞)` when [to] is null. */
internal data class ReferenceBar(val from: Double, val to: Double?, val count: Long)

/** The bar width and the bars the fixed-width rule draws for [values] under [tailPercent] (non-empty [values]). */
internal fun referenceFixedHistogram(values: List<Double>, tailPercent: Int): Pair<Double, List<ReferenceBar>> {
    val sorted = values.sorted()
    val cut = if (tailPercent > 0) quantile(sorted, (100 - tailPercent) / 100.0) else null
    // A cut AT the minimum (most values tie there) draws no tail, as in the equal-width layout.
    val tailCut = cut?.takeIf { it != sorted.first() }
    var multiple = 1
    while (true) {
        val width = 0.5 * multiple
        fun barOf(v: Double) = ceil(v / width).toLong()
        val tailFrom = tailCut?.let { barOf(it) * width }
        val tailCount = if (tailFrom == null) 0L else sorted.count { it > tailFrom }.toLong()
        val body = if (tailCount > 0) sorted.filter { it <= tailFrom!! } else sorted
        val first = barOf(sorted.first())
        // With a tail the last body bar ends at the rounded cut; without, at the bar of the maximum.
        val last = if (tailCount > 0) barOf(tailCut!!) else barOf(sorted.last())
        if (last - first + 1 + (if (tailCount > 0) 1 else 0) <= 40) {
            val perBar = body.groupingBy(::barOf).eachCount()
            val drawn = (first..last).map { k -> ReferenceBar((k - 1) * width, k * width, (perBar[k] ?: 0).toLong()) }
            return width to drawn + (if (tailCount > 0) listOf(ReferenceBar(tailFrom!!, null, tailCount)) else emptyList())
        }
        multiple++
    }
}

/**
 * Asserts [actual] is the Task estimates distribution of [values] under [minSample]/[tailPercent]: the statistics through
 * [assertDistribution], and — unless hidden — `bucketWidth` plus every bar's edges and count against [referenceFixedHistogram].
 */
internal fun assertEstimateDistribution(label: String, values: List<Double>, actual: Distribution, minSample: Int, tailPercent: Int) {
    assertDistribution(label, values, actual, minSample)
    if (actual.hidden) {
        assertNull(actual.bucketWidth, "$label a hidden distribution carries no bucket width")
        return
    }
    val (width, bars) = referenceFixedHistogram(values, tailPercent)
    assertEquals(width, actual.bucketWidth, "$label bucketWidth")
    assertEquals(bars.size, actual.histogram.size, "$label bar count")
    bars.zip(actual.histogram).forEachIndexed { at, (want, got) ->
        assertTrue(abs(want.from - got.from) < DIST_TOLERANCE, "$label bar $at from: expected ${want.from} but was ${got.from}")
        if (want.to == null) assertNull(got.to, "$label bar $at is the open tail") else {
            assertNotNull(got.to, "$label bar $at to")
            assertTrue(abs(want.to - got.to) < DIST_TOLERANCE, "$label bar $at to: expected ${want.to} but was ${got.to}")
        }
        assertEquals(want.count, got.count, "$label bar $at ($want) count")
    }
}

/**
 * Asserts [actual]'s `byUser` against each person's RAW values placed EXACTLY by the fixed layout's interval rule (a bar is
 * `(from, to]`, the open tail `> from`; the edges are exact multiples of the width, so no float slack is needed): one share per entry
 * of [rawByUser] in that order, and `Σ shares == histogram[i].count`.
 */
internal fun assertFixedShares(label: String, rawByUser: List<Triple<String, String, List<Double>>>, actual: Distribution) {
    assertEquals(rawByUser.map { it.first }, actual.byUser.map { it.accountId }, "$label share order")
    assertEquals(rawByUser.map { it.second }, actual.byUser.map { it.label }, "$label share labels")
    val histogram = actual.histogram
    for ((raw, share) in rawByUser.zip(actual.byUser)) {
        val expected = histogram.map { bar ->
            raw.third.count { v -> if (bar.to == null) v > bar.from else v > bar.from && v <= bar.to!! }.toLong()
        }
        assertEquals(expected, share.counts, "$label ${share.accountId} counts per bar")
    }
    histogram.forEachIndexed { i, bar ->
        assertEquals(bar.count, actual.byUser.sumOf { it.counts[i] }, "$label bar $i is the sum of its shares")
    }
}
