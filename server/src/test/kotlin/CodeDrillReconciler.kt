package ch.nokillswit

import ch.nokillswit.reports.CodeActivityData
import ch.nokillswit.reports.CodeActivityGroup
import ch.nokillswit.reports.CodeActivityReport
import ch.nokillswit.reports.CodeDrillQuery
import ch.nokillswit.reports.CodeDrillRow
import ch.nokillswit.reports.CodeDrillView
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.readCodeActivity
import ch.nokillswit.reports.requireSegment
import ch.nokillswit.reports.selectCodeDrill
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What a drill answers, however it was asked: its `bar.count`, the cap bookkeeping (HTTP only) and the items' figures and identities. */
internal class DrillList(
    val count: Int,
    val total: Int,
    /** The items listed over HTTP (null in-process, where nothing is capped). */
    val listed: Int?,
    val truncated: Boolean?,
    val from: Double?,
    val to: Double?,
    val figures: List<Double?>,
    /** One stable identity per item: the issue key over HTTP, the row's merge request id / commit sha in-process. */
    val ids: List<String>,
)

/**
 * A way to ask Report 21's drill. [list] is null when the drill refuses (a `400`). [member] makes a TEAM-level member's cell: that member
 * as the only pick.
 */
internal interface CodeDrills {
    suspend fun list(
        view: String,
        bucketIndex: Int? = null,
        bucketStart: String? = null,
        segment: String? = null,
        member: String? = null,
    ): DrillList?
}

/** The drill over HTTP: the route, the labels and the wire. [base] is the parent's query (team, period, connection, tail, picks). */
internal class HttpCodeDrills(private val client: HttpClient, private val base: String) : CodeDrills {
    override suspend fun list(view: String, bucketIndex: Int?, bucketStart: String?, segment: String?, member: String?): DrillList? {
        val query = buildString {
            append(base)
            if (member != null) append("&accountId=$member")
            append("&view=$view")
            if (bucketIndex != null) append("&bucketIndex=$bucketIndex")
            if (bucketStart != null) append("&bucketStart=$bucketStart")
            if (segment != null) append("&segmentAccountId=$segment")
        }
        val response = client.get("/api/v1/reports/code-activity/items?$query")
        if (response.status == HttpStatusCode.BadRequest) return null
        assertEquals(HttpStatusCode.OK, response.status, query)
        val drilled = response.body<ReportDrillItems>()
        assertEquals(drilled.bar.count, drilled.total, "$query: total equals the bar")
        assertEquals(drilled.total > drilled.items.size, drilled.truncated, "$query: truncated")
        drilled.items.forEach { assertTrue(it.itemKind.name in setOf("COMMIT", "MERGE_REQUEST"), "$query: itemKind") }
        return DrillList(
            drilled.bar.count, drilled.total, drilled.items.size, drilled.truncated, drilled.bar.from, drilled.bar.to,
            drilled.items.map { it.figure }, drilled.items.map { it.issueKey },
        )
    }
}

/**
 * The drill in-process: ONE read of the code layer per filter ([readCodeActivity] inside a report transaction) and every figure selected
 * through the pure [selectCodeDrill] -- the very function the route calls after the same read, so nothing the route computes is skipped but
 * the HTTP envelope and the labels (which [HttpCodeDrills] pins once per view). The ids are the rows' own identities.
 */
internal class InProcessCodeDrills(
    private val service: ReportService,
    private val filter: ReportFilter,
    private val nowMs: Long,
) : CodeDrills {
    private val loaded = mutableMapOf<List<String>, CodeActivityData>()

    private suspend fun dataOf(of: ReportFilter): CodeActivityData = loaded.getOrPut(of.accountIds) {
        val deps = checkNotNull(service.codeDeps)
        service.reportTransaction { checkNotNull(service.readCodeActivity(deps, of, nowMs, forDrill = true).data) }
    }

    override suspend fun list(view: String, bucketIndex: Int?, bucketStart: String?, segment: String?, member: String?): DrillList? {
        val of = if (member == null) filter else filter.copy(level = ReportLevel.USER, accountIds = listOf(member))
        val data = dataOf(of)
        val rows: List<CodeDrillRow>
        val from: Double?
        val to: Double?
        try {
            val seg = of.requireSegment(segment)
            val query = CodeDrillQuery(CodeDrillView.valueOf(view), bucketIndex, bucketStart?.let(LocalDate::parse), seg)
            val selection = data.context.selectCodeDrill(query, seg, of.tailPercent, data)
            rows = selection.rows
            from = selection.bar.from
            to = selection.bar.to
            assertEquals(rows.size, selection.bar.count, "$view: the bar is recomputed over exactly the rows")
        } catch (_: BadRequestException) {
            return null
        }
        val ids = rows.map { if (it.mrId != null) "mr:${it.connectionId}:${it.mrId}" else "c:${it.connectionId}:${it.projectId}:${it.sha}" }
        return DrillList(rows.size, rows.size, null, null, from, to, rows.map { it.figure }, ids)
    }
}

/**
 * Report 21's "every bar = its drill" gate (`.claude/docs/reports.md` "Drill to items"), shared by the hand-built world (over HTTP) and the
 * processed GitLab stub (in-process). [reconcile] asks [drills] for EVERY figure of [report] -- each bar of each histogram (and, at USER
 * level,
 * each person's share of it), the flow tiles, each weekly bar and each member's cells -- and asserts the drill's `bar.count` (and its
 * `total`, its listed items and `truncated`) equals the figure, that the bars of a histogram and the weeks together hold exactly the items
 * their whole (identity, not only count), and that the members' cells add up to the unsegmented drill. A TEAM-level member's cell is the
 * request with that member as the only pick.
 */
internal class CodeDrillReconciler(private val drills: CodeDrills, private val report: CodeActivityReport) {
    /** How many drills were compared with a figure, so a test can assert it did not pass vacuously. */
    var compared = 0
        private set

    private suspend fun expect(
        count: Int,
        label: String,
        view: String,
        bucketIndex: Int? = null,
        bucketStart: String? = null,
        segment: String? = null,
        member: String? = null,
    ): DrillList {
        val drilled = assertNotNull(drills.list(view, bucketIndex, bucketStart, segment, member), "$label: the drill refused")
        assertEquals(count, drilled.count, "$label")
        drilled.listed?.let { assertEquals(minOf(drilled.total, 500), it, "$label: the listed items") }
        compared++
        return drilled
    }

    suspend fun reconcile(label: String, members: Boolean = true) {
        histograms(label)
        tiles(label)
        weeks(label)
        if (members) members(label)
    }

    private val distributions = with(report.flow) {
        listOf(
            "MR_CYCLE_WORKING_DAYS" to cycleWorkingDays, "MR_CYCLE_ELAPSED_DAYS" to cycleElapsedDays,
            "TIME_TO_FIRST_REVIEW" to timeToFirstReviewWorkingDays, "MR_SIZE_LINES" to sizeLines, "MR_SIZE_FILES" to sizeFiles,
        )
    }

    private suspend fun histograms(label: String) {
        for ((view, distribution) in distributions) {
            val name = "$label $view"
            // The whole measured set is the distribution's sample, hidden or not.
            val all = expect(distribution.n.toInt(), "$name (all)", view)
            if (distribution.hidden) {
                assertEquals(null, drills.list(view, bucketIndex = 0), "$name: a hidden distribution has no bar")
                continue
            }
            val inBars = mutableListOf<String>()
            distribution.histogram.forEachIndexed { index, bucket ->
                val drilled = expect(bucket.count.toInt(), "$name #$index", view, bucketIndex = index)
                assertEquals(bucket.from, drilled.from, "$name #$index from")
                assertEquals(bucket.to, drilled.to, "$name #$index to")
                inBars += drilled.ids
                distribution.byUser.forEach { share ->
                    val who = "$name #$index ${share.accountId}"
                    expect(share.counts[index].toInt(), who, view, bucketIndex = index, segment = share.accountId)
                }
            }
            assertEquals(all.ids.sorted(), inBars.sorted(), "$name: the bars together hold exactly the measured items")
            assertEquals(null, drills.list(view, bucketIndex = distribution.histogram.size), "$name: past the last bar")
        }
    }

    private suspend fun tiles(label: String) {
        val flow = report.flow
        expect(flow.merged, "$label MRS_MERGED", "MRS_MERGED")
        expect(flow.mergedWithoutReview.count, "$label MERGED_WITHOUT_REVIEW", "MERGED_WITHOUT_REVIEW")
        expect(flow.mergedWithoutApproval.count, "$label MERGED_WITHOUT_APPROVAL", "MERGED_WITHOUT_APPROVAL")
        expect(report.open.count, "$label OPEN_MRS_WAITING", "OPEN_MRS_WAITING")
        expect(report.open.count + report.open.drafts, "$label OPEN_MRS", "OPEN_MRS")
    }

    private suspend fun weeks(label: String) {
        val merged = drills.list("MRS_MERGED")
        val picks = report.groups.mapNotNull { it.accountId }.takeIf { report.meta.level == ReportLevel.USER }.orEmpty()
        val inWeeks = mutableListOf<String>()
        report.flow.weekly.forEach { week ->
            val drilled = expect(week.merged, "$label WEEK ${week.bucketStart}", "WEEK", bucketStart = week.bucketStart)
            inWeeks += drilled.ids
            // The bar at USER level is the picks' union; a person's own share of it is the sum over the picks.
            if (picks.isNotEmpty()) {
                val shares = picks.sumOf { assertNotNull(drills.list("WEEK", bucketStart = week.bucketStart, segment = it)).count }
                assertEquals(week.merged, shares, "$label WEEK ${week.bucketStart}: the picks' shares add up")
            }
        }
        assertEquals(assertNotNull(merged).ids.sorted(), inWeeks.sorted(), "$label: the weeks hold exactly the merged merge requests")
    }

    private val cells: List<Pair<String, (CodeActivityGroup) -> Int>> = listOf(
        "COMMITS" to { it.commits }, "MRS_MERGED" to { it.mrsMerged }, "MRS_OPENED" to { it.mrsOpened }, "OPEN_MRS" to { it.openMrs },
        "REVIEWS_GIVEN" to { it.reviewsGiven }, "APPROVALS_GIVEN" to { it.approvalsGiven },
        "CHANGES_REQUESTED_GIVEN" to { it.changesRequestedGiven }, "COMMENTS" to { it.comments },
        "REVIEW_REQUESTS_RECEIVED" to { it.reviewRequestsReceived },
    )

    /** The histogram views whose member count has no cell of its own: it is a person's share of the distribution's sample. */
    private val measured = listOf("MR_CYCLE_WORKING_DAYS", "TIME_TO_FIRST_REVIEW", "MR_SIZE_LINES", "MR_SIZE_FILES")

    private suspend fun members(label: String) {
        val groups = report.groups
        // The unsegmented drill is every group's cell added up (the unlinked identities of `teamId=0` included).
        for ((view, cell) in cells) expect(groups.sumOf(cell), "$label all members $view", view)
        // At TEAM level a member's cell is the request with that member as the only pick; at USER level the picks' drill narrowed to him.
        val user = report.meta.level == ReportLevel.USER
        val linked = groups.filter { it.accountId != null }
        val memberCounts = measured.associateWith { mutableListOf<Int>() }
        for (group in linked) {
            val person = group.accountId!!
            val name = "$label $person"
            val own = if (user) null else person
            val segment = if (user) person else null
            for ((view, cell) in cells) expect(cell(group), "$name $view", view, segment = segment, member = own)
            for (view in measured) {
                val drilled = assertNotNull(drills.list(view, segment = segment, member = own), "$name $view")
                compared++
                memberCounts.getValue(view) += drilled.count
                val distribution = distributions.first { it.first == view }.second
                if (view == "MR_CYCLE_WORKING_DAYS") assertEquals(group.mrsMerged, drilled.count, "$name cycle count is the merged count")
                if (user && !distribution.hidden) {
                    val share = distribution.byUser.first { it.accountId == person }
                    assertEquals(share.counts.sum().toInt(), drilled.count, "$name $view: the person's share of the sample")
                }
                when (view) {
                    "MR_CYCLE_WORKING_DAYS" -> p50(group.mrCycleWorkingDaysP50, "$name cycle p50", drilled)
                    "MR_SIZE_LINES" -> p50(group.mrSizeLinesP50, "$name size p50", drilled)
                }
            }
        }
        // The members' shares of a distribution's sample add up to the sample (when everyone in it is a listed member).
        if (linked.size == groups.size) {
            for (view in measured) {
                val sample = distributions.first { it.first == view }.second.n.toInt()
                assertEquals(sample, memberCounts.getValue(view).sum(), "$label $view: the members' shares add up to the sample")
            }
        }
    }

    /** The p50 cell is the median of the figures of the drill of its merge requests (null: hidden under the minimum sample). */
    private fun p50(cell: Double?, label: String, drilled: DrillList) {
        if (cell == null) return
        val values = drilled.figures.filterNotNull().sorted()
        assertTrue(values.isNotEmpty(), label)
        val rank = (values.size - 1) * 0.5
        val low = values[rank.toInt()]
        val high = values[minOf(rank.toInt() + 1, values.lastIndex)]
        val median = low + (high - low) * (rank - rank.toInt())
        assertTrue(abs(median - cell) < 1e-6, "$label: the figures' median $median is not $cell")
        compared++
    }
}

/**
 * One request per view over HTTP (the route, the labels, the wire) against the same figure selected in-process: the counts agree and, when
 * nothing is cut, so do the figures. A histogram view asks for its first bar and `WEEK` for the first week with a merge request.
 */
internal suspend fun assertRouteAgrees(http: HttpCodeDrills, local: InProcessCodeDrills, report: CodeActivityReport) {
    val week = report.flow.weekly.firstOrNull { it.merged > 0 }?.bucketStart
    for (view in CodeDrillView.entries) {
        val bar = if (view.histogram && !report.hiddenFor(view)) 0 else null
        val start = week.takeIf { view == CodeDrillView.WEEK }
        if (view == CodeDrillView.WEEK && week == null) continue
        val over = assertNotNull(http.list(view.name, bucketIndex = bar, bucketStart = start), view.name)
        val inProcess = assertNotNull(local.list(view.name, bucketIndex = bar, bucketStart = start), view.name)
        assertEquals(inProcess.count, over.count, "${view.name}: the route and the selection agree")
        if (over.truncated != true) {
            val order = compareBy<Double?> { it ?: Double.MAX_VALUE }
            assertEquals(inProcess.figures.sortedWith(order), over.figures.sortedWith(order), "${view.name}: the figures")
        }
    }
}

private fun CodeActivityReport.hiddenFor(view: CodeDrillView): Boolean = when (view) {
    CodeDrillView.MR_CYCLE_WORKING_DAYS -> flow.cycleWorkingDays.hidden
    CodeDrillView.MR_CYCLE_ELAPSED_DAYS -> flow.cycleElapsedDays.hidden
    CodeDrillView.TIME_TO_FIRST_REVIEW -> flow.timeToFirstReviewWorkingDays.hidden
    CodeDrillView.MR_SIZE_LINES -> flow.sizeLines.hidden
    CodeDrillView.MR_SIZE_FILES -> flow.sizeFiles.hidden
    else -> false
}
