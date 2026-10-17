package ch.nokillswit

import ch.nokillswit.reports.Distribution
import ch.nokillswit.reports.ReportDrillItems
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EDGE_SLACK = 1e-9
private const val DRILL_CAP = 500

/** One `…/items` call that must answer 200: [path] is the report path (`cycle-time`), [query] the parent filter plus the identity. */
internal suspend fun HttpClient.drillItems(path: String, query: String): ReportDrillItems {
    val response = get("/api/v1/reports/$path/items?$query")
    assertEquals(HttpStatusCode.OK, response.status, "GET $path/items?$query")
    return response.body()
}

/**
 * The reconciliation gate of a histogram drill (`.claude/docs/reports.md` "Drill to items"): for EVERY bucket of [distribution]
 * the drill's bar equals the histogram's own (count, range), lists exactly that many items (up to the cap), every listed figure lies
 * in the bucket's range, the buckets together hold exactly the INDEPENDENT [oracle] values, and with a per-person split every
 * segment equals that person's `byUser` count and the segments partition the bucket. Returns the number of buckets checked.
 */
internal suspend fun HttpClient.assertHistogramDrillsReconcile(
    label: String,
    path: String,
    query: String,
    view: String,
    distribution: Distribution,
    oracle: List<Double>,
): Int {
    assertTrue(!distribution.hidden, "$label must show a histogram, or this pins nothing")
    val histogram = distribution.histogram
    val figures = mutableListOf<Double>()
    var complete = true
    histogram.forEachIndexed { index, bucket ->
        val where = "$label/$view bucket $index"
        val drill = drillItems(path, "$query&view=$view&bucketIndex=$index")
        assertEquals(bucket.count.toInt(), drill.bar.count, "$where bar.count == the histogram's count")
        assertEquals(bucket.from, drill.bar.from, "$where bar.from")
        assertEquals(bucket.to, drill.bar.to, "$where bar.to (null = the open tail)")
        assertEquals(null, drill.bar.sumMd, "$where a histogram bar has no MD")
        assertEquals(drill.bar.count, drill.total, "$where total == bar.count")
        assertEquals(minOf(drill.total, DRILL_CAP), drill.items.size, "$where listed up to the cap")
        assertEquals(drill.total > DRILL_CAP, drill.truncated, "$where truncated")
        assertEquals(distribution.n, histogram.sumOf { it.count }, "$where the buckets add up to n")
        val values = drill.items.map { assertNotNullFigure(it.figure, where) }
        for (value in values) {
            val top = bucket.to
            val inRange = if (top == null) value > bucket.from else value >= bucket.from - EDGE_SLACK && value <= top + EDGE_SLACK
            assertTrue(inRange, "$where figure $value lies in [${bucket.from}, ${bucket.to})")
        }
        assertEquals(values.sortedDescending(), values, "$where largest figure first")
        if (drill.truncated) complete = false else figures += values
        for (share in distribution.byUser) {
            val segment = drillItems(path, "$query&view=$view&bucketIndex=$index&segmentAccountId=${share.accountId}")
            assertEquals(share.counts[index].toInt(), segment.bar.count, "$where ${share.accountId} segment == byUser count")
            assertEquals(segment.bar.count, segment.total)
            assertTrue(segment.items.all { it.accountId == share.accountId }, "$where a segment lists only its person")
            assertEquals(bucket.from, segment.bar.from, "$where the segment keeps the union's bucket")
            assertEquals(bucket.to, segment.bar.to)
        }
        if (distribution.byUser.isNotEmpty()) {
            val segments = distribution.byUser.sumOf { share -> share.counts[index] }
            assertEquals(drill.bar.count.toLong(), segments, "$where Σ segments == the union's bar")
        }
    }
    if (complete) {
        val expected = oracle.sorted()
        val actual = figures.sorted()
        assertEquals(expected.size, actual.size, "$label/$view every measured item is in exactly one bucket")
        expected.zip(actual).forEachIndexed { at, (want, got) ->
            assertTrue(kotlin.math.abs(want - got) < EDGE_SLACK, "$label/$view value #$at: expected $want but was $got")
        }
    }
    return histogram.size
}

private fun assertNotNullFigure(figure: Double?, where: String): Double = figure ?: error("$where: a histogram item always has a figure")
