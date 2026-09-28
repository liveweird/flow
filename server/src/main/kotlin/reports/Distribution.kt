package ch.nokillswit.reports

import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.Serializable

/** The number of equal-width buckets [buildDistribution] spreads a dataset over by default (plan §7 doesn't pin a count). */
const val DEFAULT_HISTOGRAM_BUCKETS = 10

/** `percentile_cont`'s reported fractions (plan §7: "p50, p90, p95"), in the same order [buildDistribution] fills them. */
private val REPORTED_PERCENTILES = listOf(0.5, 0.9, 0.95)

/**
 * One equal-width bucket of [Distribution.histogram] — `[from, to)`, except the LAST bucket, which is
 * closed on both ends (`width_bucket`'s own boundary convention).
 */
@Serializable
data class HistogramBucket(val from: Double, val to: Double, val count: Long)

/**
 * The shared distribution shape every report with a per-item measure returns (plan §7): `n`/
 * `hidden` are always set; every OTHER field is null/empty while [hidden] is true — a group with
 * fewer than `metrics.settings.min_sample_size` items shows a count only, never a statistically
 * meaningless percentile (`.claude/docs/domain-model.md` "Distributions").
 */
@Serializable
data class Distribution(
    val n: Long,
    val hidden: Boolean,
    val mean: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
    val p50: Double? = null,
    val p90: Double? = null,
    val p95: Double? = null,
    val histogram: List<HistogramBucket> = emptyList(),
)

/**
 * Builds a [Distribution] from an already-fetched list of values.
 *
 * **SQL vs. Kotlin, and why this commit chooses Kotlin.** The plan allows either a
 * `percentile_cont`/`width_bucket` SQL fragment over a whitelisted column, or "a pure Kotlin
 * fallback over a fetched list if that is simpler". Commit 10a (this one) builds the reports
 * FOUNDATION only — no report query exists yet to attach a SQL fragment to; velocity, throughput,
 * cycle time and every other per-item measure land in later commits (plan §10, commits 10b/12/15).
 * A SQL fragment needs a concrete column expression to whitelist, and there is none here to whitelist
 * YET. The pure-Kotlin path is also what keeps this function testable with no database at all
 * ([DistributionTest]). A later report whose own dataset is too large to pull wholesale into the
 * JVM can instead compute `percentile_cont`/`width_bucket` in SQL and feed ITS OWN numbers into this
 * SAME [Distribution] shape — the wire contract never changes, only which side does the arithmetic.
 *
 * The percentile method mirrors PostgreSQL's own `percentile_cont` (linear interpolation between the
 * two closest ranks over the SORTED values) so a later SQL-backed report and this Kotlin path never
 * disagree on the same input. The histogram mirrors `width_bucket`'s own boundary rule: `buckets`
 * equal-width bins over `[min, max]`, every bucket half-open `[from, to)` except the LAST, which is
 * closed on both ends so the maximum value always lands somewhere. A single-valued dataset
 * (`min == max`) collapses into ONE bucket holding every value, avoiding a division by zero.
 */
fun buildDistribution(values: List<Double>, minSampleSize: Int, histogramBuckets: Int = DEFAULT_HISTOGRAM_BUCKETS): Distribution {
    val n = values.size.toLong()
    if (values.size < minSampleSize) return Distribution(n = n, hidden = true)

    val sorted = values.sorted()
    val (p50, p90, p95) = REPORTED_PERCENTILES.map { percentileContinuous(sorted, it) }
    return Distribution(
        n = n,
        hidden = false,
        mean = sorted.average(),
        min = sorted.first(),
        max = sorted.last(),
        p50 = p50,
        p90 = p90,
        p95 = p95,
        histogram = histogram(sorted, sorted.first(), sorted.last(), histogramBuckets),
    )
}

/** PostgreSQL's `percentile_cont(fraction) WITHIN GROUP (ORDER BY …)` — linear interpolation between the two closest ranks. */
private fun percentileContinuous(sorted: List<Double>, fraction: Double): Double {
    if (sorted.size == 1) return sorted.single()
    val rank = fraction * (sorted.size - 1)
    val lower = floor(rank).toInt()
    val upper = ceil(rank).toInt()
    if (lower == upper) return sorted[lower]
    val weight = rank - lower
    return sorted[lower] + weight * (sorted[upper] - sorted[lower])
}

private fun histogram(sorted: List<Double>, min: Double, max: Double, buckets: Int): List<HistogramBucket> {
    require(buckets > 0) { "histogramBuckets must be positive" }
    if (min == max) return listOf(HistogramBucket(min, max, sorted.size.toLong()))
    val width = (max - min) / buckets
    val edges = (0..buckets).map { min + it * width }
    val counts = LongArray(buckets)
    sorted.forEach { value ->
        val index = ((value - min) / width).toInt().coerceIn(0, buckets - 1)
        counts[index]++
    }
    return (0 until buckets).map { i -> HistogramBucket(edges[i], edges[i + 1], counts[i]) }
}
