package ch.nokillswit.reports

import io.ktor.server.plugins.BadRequestException

/**
 * The items behind ONE bar of a distribution report's histogram — the shared body of every `…/items` route whose bar is a
 * histogram bucket. [rows] is the report's own fetch and [valueOf] its own per-item measure (`null` = the report excludes the
 * item, so it is in no bucket), exactly what the report feeds [buildDistribution]; [bucketAssignment] then places every value
 * with the ONE `BucketLayout.indexOf` ([fixedStep] selects the fixed-width layout, Task estimates only), so the bucket [bucketIndex]
 * holds precisely the items the bar counts.
 *
 * The layout is always the UNION's (every row, whatever [segment]); [segment] (a picked account, USER level) then narrows the
 * listed items — and with them `bar` and `total` — to that person's share of the bucket, which is the report's `byUser` count.
 * `bar.from`/`bar.to` are the bucket's range (`to` null = the open tail bucket). A hidden distribution (fewer than [minSample]
 * measured items) draws no histogram, so there is no bar to list: `400`, like an index outside the histogram.
 */
internal suspend fun <T> distributionDrillItems(
    meta: ReportMeta,
    rows: List<T>,
    minSample: Int,
    tailPercent: Int,
    bucketIndex: Int,
    segment: String?,
    valueOf: (T) -> Double?,
    fixedStep: Double? = null,
    drillRow: (row: T, value: Double) -> DrillRow,
): ReportDrillItems {
    val measured = rows.mapNotNull { row -> valueOf(row)?.let { row to it } }
    val assignment = bucketAssignment(measured.map { it.second }, minSample, tailPercent, fixedStep = fixedStep)
        ?: throw BadRequestException("The distribution is hidden (fewer than $minSample items): there is no bar to list")
    val buckets = assignment.histogram
    if (bucketIndex !in buckets.indices) {
        throw BadRequestException("bucketIndex $bucketIndex is outside the histogram (0..${buckets.lastIndex})")
    }
    val listed = measured.filterIndexed { index, _ -> assignment.indexes[index] == bucketIndex }
        .map { (row, value) -> drillRow(row, value) }
        .filter { segment == null || it.accountId == segment }
    val bucket = buckets[bucketIndex]
    return drillItems(meta, ReportDrillBar(count = listed.size, sumMd = null, from = bucket.from, to = bucket.to), listed)
}
