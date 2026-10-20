package ch.nokillswit.reports

import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** The number of equal-width buckets [buildDistribution] spreads a dataset over by default (plan §7 doesn't pin a count). */
const val DEFAULT_HISTOGRAM_BUCKETS = 10

/** The default share (percent) of the largest values [buildDistribution] folds into the histogram's final open bucket. */
const val DEFAULT_TAIL_PERCENT = 5

/** The largest `tailPercent` a report accepts (`ReportTailPercent` in the spec): more would fold the body of the distribution away. */
const val MAX_TAIL_PERCENT = 25

/** The base bar width of the FIXED-width histogram layout (Task estimates only), in the values' own unit: 0.5 MD. */
const val FIXED_BUCKET_STEP = 0.5

/** The most bars (the open tail included) a fixed-width histogram draws; more widens the bar to the next multiple of the base step. */
const val MAX_FIXED_BUCKETS = 40

/** `percentile_cont`'s reported fractions (plan §7: "p50, p90, p95"), in the same order [buildDistribution] fills them. */
private val REPORTED_PERCENTILES = listOf(0.5, 0.9, 0.95)

/**
 * One bucket of [Distribution.histogram] — `[from, to)`, except the last EQUAL-WIDTH bucket, which is
 * closed on both ends (`width_bucket`'s own boundary convention); in the FIXED-width layout
 * ([Distribution.bucketWidth] set) a bar is `(from, to]` instead. The one exception to "equal width" is
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
@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * One entry per picked person at USER level (see [buildDistributionByUser]); empty everywhere else, and then OMITTED on the
     * wire (`encodeDefaults = false`), so the spec lists it as optional.
     */
    val byUser: List<DistributionShare> = emptyList(),
    /**
     * Set ONLY by the fixed-width layout (`fixedStep` of [buildDistribution], Task estimates): the width of every body bar, in the
     * values' unit; each bar is then `(from, to]` and the page labels it by its upper bound. Null = the default equal-width layout:
     * Ktor's default `Json` encodes defaults, so `EncodeDefault.Mode.NEVER` is what makes every other report's wire OMIT the key (and
     * stay byte-identical) rather than send `null`; the spec lists it as optional.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bucketWidth: Double? = null,
)

/**
 * One picked person's slice of [Distribution.histogram]: [counts] is index-aligned with the histogram's buckets (the open tail
 * bucket included), so over the shares `Σ counts[i] == histogram[i].count` and `Σ counts == that person's n`.
 */
@Serializable
data class DistributionShare(val accountId: String, val label: String, val counts: List<Long>)

/** One picked person's raw values, the input of [buildDistributionByUser]. */
data class UserValues(val accountId: String, val label: String, val values: List<Double>)

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
 * `tailPercent = N > 0` the values above the (100 − N)th percentile (roughly the top N%; with a small `n` a single top
 * value can be more than N%) fold into ONE final open bucket and the rest is spread over the equal-width
 * buckets: `X = percentile_cont((100 - N) / 100)` of all the values; the TAIL is the values strictly above `X`; the
 * BODY (values `<= X`) gets [histogramBuckets] equal-width buckets over `[min, X]` by the rule above (the last
 * body bucket closed at `X`, so a value equal to `X` stays in the body); then ONE bucket
 * `{from: X, to: null, count: tail.size}` follows. With no value above `X` (`N = 0`, ties at the top
 * swallowing the tail, or a cut equal to the minimum because most values tie there) there is no open bucket and the
 * histogram is exactly the plain one — so `tailPercent = 0`
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
    fixedStep: Double? = null,
): Distribution = distributionWithLayout(values, minSampleSize, tailPercent, histogramBuckets, fixedStep).first

/**
 * The [Distribution] of every picked person's values together (exactly [buildDistribution] over the flattened values), plus
 * [Distribution.byUser]: one [DistributionShare] per user in the GIVEN order (the request order), each a bucket count of that user's
 * values over the UNION's own bucket layout — the same edges, the open tail bucket included — so the shares add up to the
 * union's `histogram` by construction. A user with no values is an all-zero share. A hidden union carries no shares (a person's
 * slice of a distribution too small to show must not leak what the union hides).
 */
fun buildDistributionByUser(
    valuesByUser: List<UserValues>,
    minSampleSize: Int,
    tailPercent: Int,
    histogramBuckets: Int = DEFAULT_HISTOGRAM_BUCKETS,
    fixedStep: Double? = null,
): Distribution {
    val (union, layout) = distributionWithLayout(
        valuesByUser.flatMap { it.values }, minSampleSize, tailPercent, histogramBuckets, fixedStep,
    )
    if (layout == null) return union
    return union.copy(byUser = valuesByUser.map { DistributionShare(it.accountId, it.label, layout.counts(it.values).toList()) })
}

/**
 * Which bucket of a distribution's histogram each input value falls into: [histogram] is exactly [buildDistribution]'s
 * (`histogram[i]` is bucket `i`, the open tail last) and [indexes] is index-aligned with the INPUT values, so
 * `indexes.count { it == i } == histogram[i].count`.
 */
class BucketAssignment(val histogram: List<HistogramBucket>, val indexes: IntArray)

/**
 * The bucket of every value of [values] in the histogram [buildDistribution] draws for the same arguments, or `null` while the
 * distribution is hidden (`values.size < minSampleSize`: no histogram, so no bar to drill into). The ONE place a value meets a
 * bucket is [BucketLayout.indexOf]; this exposes it for the drill (`DistributionDrill.kt`) without exposing the class, so a bar and
 * the items behind it can never disagree on the edge or tail rule.
 */
fun bucketAssignment(
    values: List<Double>,
    minSampleSize: Int,
    tailPercent: Int,
    histogramBuckets: Int = DEFAULT_HISTOGRAM_BUCKETS,
    fixedStep: Double? = null,
): BucketAssignment? {
    val (distribution, layout) = distributionWithLayout(values, minSampleSize, tailPercent, histogramBuckets, fixedStep)
    if (layout == null) return null
    return BucketAssignment(distribution.histogram, IntArray(values.size) { layout.indexOf(values[it]) })
}

private fun distributionWithLayout(
    values: List<Double>,
    minSampleSize: Int,
    tailPercent: Int,
    histogramBuckets: Int,
    fixedStep: Double?,
): Pair<Distribution, BucketLayout?> {
    require(tailPercent in 0..MAX_TAIL_PERCENT) { "tailPercent must be between 0 and $MAX_TAIL_PERCENT" }
    val n = values.size.toLong()
    if (values.size < minSampleSize) return Distribution(n = n, hidden = true) to null

    val sorted = values.sorted()
    val (p50, p90, p95) = REPORTED_PERCENTILES.map { percentileContinuous(sorted, it) }
    val layout =
        if (fixedStep == null) bucketLayout(sorted, tailPercent, histogramBuckets) else fixedBucketLayout(sorted, tailPercent, fixedStep)
    val distribution = Distribution(
        n = n,
        hidden = false,
        mean = sorted.average(),
        min = sorted.first(),
        max = sorted.last(),
        p50 = p50,
        p90 = p90,
        p95 = p95,
        histogram = layout.histogram(sorted),
        bucketWidth = layout.width,
    )
    return distribution to layout
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

private const val PERCENT = 100.0

/**
 * The ONE bucket layout of a distribution's histogram (see [buildDistribution]): [edges] are the body bucket boundaries and — when
 * [tailFrom] is set — the open tail bucket follows them holding every value strictly above it. [indexOf] is the only place a
 * value is assigned to a bucket, for the union's histogram and for each person's share alike; the two layouts differ only in how
 * a value meets a BODY bucket ([bodyIndexOf]) and in [width] (set by the fixed-width layout alone).
 */
private abstract class BucketLayout(protected val edges: List<Double>, private val tailFrom: Double?) {
    protected val bodyBuckets = edges.size - 1

    /** The fixed bar width the layout drew ([Distribution.bucketWidth]); null for the equal-width layout. */
    open val width: Double? get() = null

    protected abstract fun bodyIndexOf(value: Double): Int

    /** The index of the bucket [value] falls into; the open tail bucket, when there is one, is the last. */
    fun indexOf(value: Double): Int = if (tailFrom != null && value > tailFrom) bodyBuckets else bodyIndexOf(value)

    /** Per-bucket counts of [values], index-aligned with [histogram]. */
    fun counts(values: Collection<Double>): LongArray {
        val counts = LongArray(bodyBuckets + if (tailFrom != null) 1 else 0)
        values.forEach { counts[indexOf(it)]++ }
        return counts
    }

    fun histogram(values: Collection<Double>): List<HistogramBucket> {
        val counts = counts(values)
        val body = (0 until bodyBuckets).map { HistogramBucket(edges[it], edges[it + 1], counts[it]) }
        return if (tailFrom == null) body else body + HistogramBucket(tailFrom, null, counts[bodyBuckets])
    }
}

/**
 * Equal-width buckets from [min] by [bucketSize] (a single-valued dataset is one zero-width bucket), each `[from, to)` but the
 * last, closed on both ends.
 */
private class EqualWidthLayout(
    private val min: Double,
    private val bucketSize: Double,
    edges: List<Double>,
    tailFrom: Double?,
) : BucketLayout(edges, tailFrom) {
    override fun bodyIndexOf(value: Double): Int {
        if (bucketSize == 0.0) return 0
        return ((value - min) / bucketSize).toInt().coerceIn(0, bodyBuckets - 1)
    }

    companion object {
        /**
         * Equal-width buckets over `[min, top]`; the last edge is [top] itself when [tailFrom] is set (`min + buckets * width`
         * can miss it by an ulp).
         */
        fun body(min: Double, top: Double, buckets: Int, tailFrom: Double?): BucketLayout {
            if (min == top) return EqualWidthLayout(min, 0.0, listOf(min, top), null)
            val bucketSize = (top - min) / buckets
            val edges = (0..buckets).map { min + it * bucketSize }
            return EqualWidthLayout(min, bucketSize, if (tailFrom == null) edges else edges.dropLast(1) + top, tailFrom)
        }
    }
}

/**
 * Fixed-width bars of [step] on the multiples of [step]: bar `k` covers `((k − 1)·step, k·step]` and bars run contiguously (empty
 * ones included) from the one holding the minimum, [firstK], to [lastK] (the bar holding the last body value). A value `v` is in bar
 * `ceil(v ÷ step)`, so an exact multiple sits on its own bar, labelled by its upper bound.
 */
private class FixedWidthLayout(
    private val step: Double,
    private val firstK: Long,
    lastK: Long,
    tailFrom: Double?,
) : BucketLayout((firstK - 1..lastK).map { it * step }, tailFrom) {
    override val width: Double get() = step

    override fun bodyIndexOf(value: Double): Int = (ceil(value / step).toLong() - firstK).toInt().coerceIn(0, bodyBuckets - 1)
}

/**
 * The layout of [sorted]: with a [tailPercent] the values above the (100 − [tailPercent])th percentile fold into a final open
 * bucket (see [buildDistribution]).
 */
private fun bucketLayout(sorted: List<Double>, tailPercent: Int, buckets: Int): BucketLayout {
    require(buckets > 0) { "histogramBuckets must be positive" }
    val plain = EqualWidthLayout.body(sorted.first(), sorted.last(), buckets, tailFrom = null)
    if (tailPercent == 0) return plain
    val cut = percentileContinuous(sorted, (100 - tailPercent) / PERCENT)
    // `sorted` is ascending, so the tail is a suffix: it starts at the first value strictly above the cut.
    val tailStart = sorted.indexOfFirst { it > cut }
    // A cut AT the minimum (more than (100 - N)% of the values tied there, e.g. mostly-zero blocked time) would squash
    // the body into one zero-width bucket and hide the real signal in the tail: show the plain histogram instead.
    if (tailStart < 0 || cut == sorted.first()) return plain
    return EqualWidthLayout.body(sorted.first(), cut, buckets, tailFrom = cut)
}

/**
 * The FIXED-width layout of [sorted] (Task estimates): bars of [baseStep] from the one holding the minimum to the one holding the
 * last body value. With a [tailPercent] the cut (the same percentile as the equal-width tail) rounds UP to a multiple of the step and
 * the values strictly above it fold into the open tail bucket; the same two "no tail" cases apply (nothing above the rounded cut, or
 * a cut AT the minimum). When the bars (the tail included) would exceed [MAX_FIXED_BUCKETS] the step becomes the smallest multiple
 * of [baseStep] that fits.
 */
private fun fixedBucketLayout(sorted: List<Double>, tailPercent: Int, baseStep: Double): BucketLayout {
    require(baseStep > 0.0) { "fixedStep must be positive" }
    val cut = if (tailPercent == 0) null else percentileContinuous(sorted, (100 - tailPercent) / PERCENT).takeIf { it != sorted.first() }
    var multiple = 1
    while (true) {
        val step = baseStep * multiple
        val firstK = ceil(sorted.first() / step).toLong()
        val cutK = cut?.let { ceil(it / step).toLong() }
        val tailed = cutK != null && sorted.last() > cutK * step
        val lastK = if (cutK != null && tailed) cutK else ceil(sorted.last() / step).toLong()
        if (lastK - firstK + 1 + (if (tailed) 1 else 0) <= MAX_FIXED_BUCKETS) {
            return FixedWidthLayout(step, firstK, lastK, if (tailed) lastK * step else null)
        }
        multiple++
    }
}
