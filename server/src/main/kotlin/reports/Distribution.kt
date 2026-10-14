package ch.nokillswit.reports

import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.Serializable

/** The number of equal-width buckets [buildDistribution] spreads a dataset over by default (plan §7 doesn't pin a count). */
const val DEFAULT_HISTOGRAM_BUCKETS = 10

/** The default share (percent) of the largest values [buildDistribution] folds into the histogram's final open bucket. */
const val DEFAULT_TAIL_PERCENT = 5

/** The largest `tailPercent` a report accepts (`ReportTailPercent` in the spec): more would fold the body of the distribution away. */
const val MAX_TAIL_PERCENT = 25

/** `percentile_cont`'s reported fractions (plan §7: "p50, p90, p95"), in the same order [buildDistribution] fills them. */
private val REPORTED_PERCENTILES = listOf(0.5, 0.9, 0.95)

/**
 * One bucket of [Distribution.histogram] — `[from, to)`, except the last EQUAL-WIDTH bucket, which is
 * closed on both ends (`width_bucket`'s own boundary convention). The one exception to "equal width" is
 * the final OPEN tail bucket [buildDistribution] appends when `tailPercent > 0` and the top values
 * stand apart: `to` is `null` (no upper bound) and it holds every value strictly above `from`.
 * `to` has NO default on purpose — the wire format omits default values (`encodeDefaults = false`),
 * and the open bucket must say `"to": null` explicitly.
 */
@Serializable
data class HistogramBucket(val from: Double, val to: Double?, val count: Long)

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
 * FOUNDATION only — no report query exists yet to attach a SQL fragment to; commitments, throughput,
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
 *
 * **The long tail (`tailPercent`).** Equal-width buckets over `[min, max]` let a few far outliers stretch
 * every bucket, so the first one ends up holding nearly everything and no distribution shows. With
 * `tailPercent = N > 0` the top N% fold into ONE final open bucket and the rest is spread over the equal-width
 * buckets: `X = percentile_cont((100 - N) / 100)` of all the values; the TAIL is the values strictly above `X`; the
 * BODY (values `<= X`) gets [histogramBuckets] equal-width buckets over `[min, X]` by the rule above (the last
 * body bucket closed at `X`, so a value equal to `X` stays in the body); then ONE bucket
 * `{from: X, to: null, count: tail.size}` follows. With no value above `X` (`N = 0`, or ties at the top
 * swallowing the tail) there is no open bucket and the histogram is exactly the plain one — so `tailPercent = 0`
 * reproduces the pre-tail histogram bit for bit. Only the HISTOGRAM changes: `n`, `mean`, `min`, `max` and
 * `p50`/`p90`/`p95` stay over ALL values, the bucket counts always sum to `n`, and a hidden distribution
 * (`n < minSampleSize`) is untouched. [tailPercent] has no default so no call site can forget to thread
 * the report's own value (`ReportFilter.tailPercent`); it must be `0..`[MAX_TAIL_PERCENT].
 */
fun buildDistribution(
    values: List<Double>,
    minSampleSize: Int,
    tailPercent: Int,
    histogramBuckets: Int = DEFAULT_HISTOGRAM_BUCKETS,
): Distribution {
    require(tailPercent in 0..MAX_TAIL_PERCENT) { "tailPercent must be between 0 and $MAX_TAIL_PERCENT" }
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
        histogram = histogramWithTail(sorted, tailPercent, histogramBuckets),
    )
}

/** PostgreSQL's `percentile_cont(fraction) WITHIN GROUP (ORDER BY …)` — linear interpolation between the two closest ranks. */
internal fun percentileContinuous(sorted: List<Double>, fraction: Double): Double {
    if (sorted.size == 1) return sorted.single()
    val rank = fraction * (sorted.size - 1)
    val lower = floor(rank).toInt()
    val upper = ceil(rank).toInt()
    if (lower == upper) return sorted[lower]
    val weight = rank - lower
    return sorted[lower] + weight * (sorted[upper] - sorted[lower])
}

/** The histogram of [sorted] with the top [tailPercent]% folded into a final open bucket (see [buildDistribution]). */
private fun histogramWithTail(sorted: List<Double>, tailPercent: Int, buckets: Int): List<HistogramBucket> {
    if (tailPercent == 0) return histogram(sorted, sorted.first(), sorted.last(), buckets)
    val cut = percentileContinuous(sorted, (100 - tailPercent) / PERCENT)
    // `sorted` is ascending, so the tail is a suffix: it starts at the first value strictly above the cut.
    val tailStart = sorted.indexOfFirst { it > cut }
    if (tailStart < 0) return histogram(sorted, sorted.first(), sorted.last(), buckets)
    return histogram(sorted.subList(0, tailStart), sorted.first(), cut, buckets) +
        HistogramBucket(cut, null, (sorted.size - tailStart).toLong())
}

private const val PERCENT = 100.0

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
