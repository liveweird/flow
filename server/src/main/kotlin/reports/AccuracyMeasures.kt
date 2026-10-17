package ch.nokillswit.reports

import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

/**
 * The accuracy measures of an estimate-vs-actual ratio sample (`.claude/docs/measures.md` "Reports 3, 4, 5"): every field is a pure
 * function of the ratios `r = actual ÷ estimate` the view's own [Distribution] summarises, unrounded on the wire.
 *
 * The relative error is taken against the ACTUAL (Conte et al.): `MRE = |actual − estimate| ÷ actual = |1 − 1/r|`.
 * - [pred10]/[pred25]/[pred50]: the share of items with `MRE <= 0.10/0.25/0.50` (the boundary counts as within), fractions.
 * - [mdmre]: the median MRE (fraction). [smape]: the mean of `2|r − 1| ÷ (r + 1)` (fraction, 0..2).
 * - [iqr] = p75 − p25 and [idr] = p90 − p10 of the ratios, [mad] = the median of `|r − median r|` (unscaled): ratio units.
 * - [tailShare]: the share of ratios with `|r − mean| > 2s`, `s` the SAMPLE standard deviation (n − 1) — a normal distribution has
 *   about 4.6% there. Null when `n < 6` — with the sample SD no point lies more than `(n − 1) ÷ √n` SDs from the mean, which is below 2
 *   for `n <= 5`, so a smaller view would always read a misleading 0% — or when the sample is constant, `s == 0` (no spread to measure).
 */
@Serializable
data class AccuracyMeasures(
    val pred10: Double,
    val pred25: Double,
    val pred50: Double,
    val mdmre: Double,
    val smape: Double,
    val iqr: Double,
    val idr: Double,
    val mad: Double,
    val tailShare: Double?,
)

private const val MEDIAN = 0.5
private const val P25 = 0.25
private const val P75 = 0.75
private const val P10 = 0.10
private const val P90 = 0.90
private const val PRED_10 = 0.10
private const val PRED_25 = 0.25
private const val PRED_50 = 0.50
private const val TAIL_SIGMAS = 2.0

/** The smallest sample whose tail beyond [TAIL_SIGMAS] sample SDs can be non-empty: `(n − 1) ÷ √n > 2` first holds at n = 6. */
private const val MIN_TAIL_SAMPLE = 6
private const val SMAPE_NUMERATOR = 2.0

/** Absorbs the last-ulp noise of `|1 − 1/r|` so an item exactly on a PRED threshold (a 25% miss: 4 vs 3) counts as within. */
private const val PRED_EPSILON = 1e-9

/** The [AccuracyMeasures] of [ratios], or null when there are fewer than [minSampleSize] of them (the Distribution's own hiding rule). */
fun accuracyMeasures(ratios: List<Double>, minSampleSize: Int): AccuracyMeasures? {
    if (ratios.isEmpty() || ratios.size < minSampleSize) return null
    val sorted = ratios.sorted()
    val mre = sorted.map { abs(1.0 - 1.0 / it) }
    val median = percentileContinuous(sorted, MEDIAN)
    return AccuracyMeasures(
        pred10 = predShare(mre, PRED_10),
        pred25 = predShare(mre, PRED_25),
        pred50 = predShare(mre, PRED_50),
        mdmre = percentileContinuous(mre.sorted(), MEDIAN),
        smape = sorted.map { SMAPE_NUMERATOR * abs(it - 1.0) / (it + 1.0) }.average(),
        iqr = percentileContinuous(sorted, P75) - percentileContinuous(sorted, P25),
        idr = percentileContinuous(sorted, P90) - percentileContinuous(sorted, P10),
        mad = percentileContinuous(sorted.map { abs(it - median) }.sorted(), MEDIAN),
        tailShare = tailShare(sorted),
    )
}

private fun predShare(mre: List<Double>, threshold: Double): Double = mre.count { it <= threshold + PRED_EPSILON }.toDouble() / mre.size

/** The share beyond two sample standard deviations of the mean; null for `n < 6` ([MIN_TAIL_SAMPLE]) or a constant sample (`s == 0`). */
private fun tailShare(values: List<Double>): Double? {
    // [values] is sorted: equal ends = a constant sample, tested exactly because a float mean of equal values can sit an ulp off.
    if (values.size < MIN_TAIL_SAMPLE || values.first() == values.last()) return null
    val mean = values.average()
    val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    return values.count { abs(it - mean) > TAIL_SIGMAS * sd }.toDouble() / values.size
}
