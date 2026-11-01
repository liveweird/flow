package ch.nokillswit

import ch.nokillswit.reports.CodeActivityGroup
import ch.nokillswit.reports.CodeActivityReport
import ch.nokillswit.reports.ReportDrillItems
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Report 21's "every bar = its drill" gate (`.claude/docs/reports.md` "Drill to items"), shared by the hand-built world and the processed
 * GitLab stub. [reconcile] reads the report for [base] (the parent's query: team, period, connection, tail) and then asks the drill for
 * EVERY figure the page draws -- each bar of each histogram (and, at USER level, each person's share of it), the flow tiles, each weekly
 * bar and each member's cells -- asserting the drill's `bar.count` (and its `total`, its listed items and `truncated`) equals the figure.
 * A TEAM-level member's cell is the request with that member as the only pick; the unsegmented drill is the sum of every group's cell.
 */
internal class CodeDrillReconciler(private val client: HttpClient, private val base: String) {
    /** How many drills were compared with a figure, so a test can assert it did not pass vacuously. */
    var compared = 0
        private set

    suspend fun drill(query: String, extra: String = ""): ReportDrillItems {
        val response = client.get("/api/v1/reports/code-activity/items?$query&$extra")
        assertEquals(HttpStatusCode.OK, response.status, "$query&$extra")
        return response.body()
    }

    suspend fun status(query: String, extra: String): HttpStatusCode =
        client.get("/api/v1/reports/code-activity/items?$query&$extra").status

    private fun ReportDrillItems.assertConsistent(label: String): ReportDrillItems {
        assertEquals(bar.count, total, "$label: total equals the bar")
        assertEquals(minOf(total, 500), items.size, "$label: the listed items")
        assertEquals(total > items.size, truncated, "$label: truncated")
        items.forEach { assertTrue(it.itemKind.name in setOf("COMMIT", "MERGE_REQUEST"), "$label: itemKind") }
        return this
    }

    private suspend fun expect(count: Int, label: String, query: String, extra: String): ReportDrillItems {
        val drilled = drill(query, extra).assertConsistent(label)
        assertEquals(count, drilled.bar.count, "$label: ${drilled.bar}")
        compared++
        return drilled
    }

    suspend fun reconcile(label: String, members: Boolean = true) {
        val report = client.get("/api/v1/reports/code-activity?$base").let {
            assertEquals(HttpStatusCode.OK, it.status, label)
            it.body<CodeActivityReport>()
        }
        histograms(report, label)
        tiles(report, label)
        report.flow.weekly.forEach { week ->
            expect(week.merged, "$label WEEK ${week.bucketStart}", base, "view=WEEK&bucketStart=${week.bucketStart}")
            // The bar at USER level is the picks' union; a person's own share of it is the sum over the picks.
            val picks = report.groups.mapNotNull { it.accountId }.takeIf { report.meta.level.name == "USER" }.orEmpty()
            if (picks.isNotEmpty()) {
                val shares = picks.sumOf { drill(base, "view=WEEK&bucketStart=${week.bucketStart}&segmentAccountId=$it").bar.count }
                assertEquals(week.merged, shares, "$label WEEK ${week.bucketStart}: the picks' shares add up")
            }
        }
        if (members) members(report, label)
    }

    private suspend fun histograms(report: CodeActivityReport, label: String) {
        val flow = report.flow
        val views = listOf(
            "MR_CYCLE_WORKING_DAYS" to flow.cycleWorkingDays, "MR_CYCLE_ELAPSED_DAYS" to flow.cycleElapsedDays,
            "TIME_TO_FIRST_REVIEW" to flow.timeToFirstReviewWorkingDays,
            "MR_SIZE_LINES" to flow.sizeLines,
            "MR_SIZE_FILES" to flow.sizeFiles,
        )
        for ((view, distribution) in views) {
            val name = "$label $view"
            // The whole measured set is the distribution's sample, hidden or not.
            expect(distribution.n.toInt(), "$name (all)", base, "view=$view")
            if (distribution.hidden) {
                assertEquals(HttpStatusCode.BadRequest, status(base, "view=$view&bucketIndex=0"), "$name: a hidden distribution has no bar")
                continue
            }
            distribution.histogram.forEachIndexed { index, bucket ->
                val drilled = expect(bucket.count.toInt(), "$name #$index", base, "view=$view&bucketIndex=$index")
                assertEquals(bucket.from, drilled.bar.from, "$name #$index from")
                assertEquals(bucket.to, drilled.bar.to, "$name #$index to")
                distribution.byUser.forEach { share ->
                    val person = "bucketIndex=$index&segmentAccountId=${share.accountId}"
                    expect(share.counts[index].toInt(), "$name #$index ${share.accountId}", base, "view=$view&$person")
                }
            }
            val past = "view=$view&bucketIndex=${distribution.histogram.size}"
            assertEquals(HttpStatusCode.BadRequest, status(base, past), "$name: past the last bar")
        }
    }

    private suspend fun tiles(report: CodeActivityReport, label: String) {
        val flow = report.flow
        expect(flow.merged, "$label MRS_MERGED", base, "view=MRS_MERGED")
        expect(flow.mergedWithoutReview.count, "$label MERGED_WITHOUT_REVIEW", base, "view=MERGED_WITHOUT_REVIEW")
        expect(flow.mergedWithoutApproval.count, "$label MERGED_WITHOUT_APPROVAL", base, "view=MERGED_WITHOUT_APPROVAL")
        expect(report.open.count, "$label OPEN_MRS_WAITING", base, "view=OPEN_MRS_WAITING")
        expect(report.open.count + report.open.drafts, "$label OPEN_MRS", base, "view=OPEN_MRS")
    }

    private val cells: List<Pair<String, (CodeActivityGroup) -> Int>> = listOf(
        "COMMITS" to { it.commits }, "MRS_MERGED" to { it.mrsMerged }, "MRS_OPENED" to { it.mrsOpened }, "OPEN_MRS" to { it.openMrs },
        "MR_CYCLE_WORKING_DAYS" to { it.mrsMerged }, "REVIEWS_GIVEN" to { it.reviewsGiven }, "APPROVALS_GIVEN" to { it.approvalsGiven },
        "CHANGES_REQUESTED_GIVEN" to { it.changesRequestedGiven }, "COMMENTS" to { it.comments },
        "REVIEW_REQUESTS_RECEIVED" to { it.reviewRequestsReceived },
    )

    private suspend fun members(report: CodeActivityReport, label: String) {
        val groups = report.groups
        // The unsegmented drill is every group's cell added up (the unlinked identities of `teamId=0` included).
        for ((view, cell) in cells) expect(groups.sumOf(cell), "$label all members $view", base, "view=$view")
        // At TEAM level a member's cell is the request with that member as the only pick; at USER level the picks' drill narrowed to him.
        val user = report.meta.level.name == "USER"
        for (group in groups.filter { it.accountId != null }) {
            val own = if (user) base else "$base&accountId=${group.accountId}"
            val segment = if (user) "&segmentAccountId=${group.accountId}" else ""
            val name = "$label ${group.accountId}"
            for ((view, cell) in cells) expect(cell(group), "$name $view", own, "view=$view$segment")
            p50(group.mrCycleWorkingDaysP50, "$name cycle p50", own, "MR_CYCLE_WORKING_DAYS$segment")
            p50(group.mrSizeLinesP50, "$name size p50", own, "MR_SIZE_LINES$segment")
        }
    }

    /** The p50 cell is the median of the figures of the drill of its merge requests (null: hidden under the minimum sample). */
    private suspend fun p50(cell: Double?, label: String, query: String, viewAndSegment: String) {
        if (cell == null) return
        val values = drill(query, "view=$viewAndSegment").items.mapNotNull { it.figure }.sorted()
        assertTrue(values.isNotEmpty(), label)
        val rank = (values.size - 1) * 0.5
        val low = values[rank.toInt()]
        val high = values[minOf(rank.toInt() + 1, values.lastIndex)]
        val median = low + (high - low) * (rank - rank.toInt())
        assertTrue(abs(median - assertNotNull(cell)) < 1e-6, "$label: the figures' median $median is not $cell")
        compared++
    }
}
