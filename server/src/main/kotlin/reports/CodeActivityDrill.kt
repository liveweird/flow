package ch.nokillswit.reports

import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.norm.MrEventKind
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate

/*
 * `GET /api/v1/reports/code-activity/items` (Report 21's drill, `.claude/docs/reports.md` "Report 21"): the commits or merge requests
 * behind ONE figure of the report. Every view runs the report's OWN read plan ([readCodeActivity]) and its OWN attribution and value
 * functions -- `flowFigures` for the merged merge requests, `commitMember` / `openedMember` / `openNowMember` / `actOf` /
 * `requestReceivers` for the member cells, the `MergedMrFigure` properties the distributions are built from, `CodeWeekGrid` for the weekly
 * bars -- so a figure and its items cannot disagree. Nothing is re-derived and no identity key leaves the server: an item is named by its
 * project path and iid / sha.
 */

/**
 * The figure a drill lists the items of. The five histogram views (the `flow` distributions) take an optional `bucketIndex`: with one, the
 * bar's items (the layout is the union's, `segmentAccountId` then narrows to a person's share); without, the whole measured set (a
 * member's p50 cell). `WEEK` takes the REQUIRED `bucketStart` of one weekly bar. `MRS_MERGED`, `MERGED_WITHOUT_REVIEW` and
 * `MERGED_WITHOUT_APPROVAL` are the flow tiles, `OPEN_MRS_WAITING` the open tile (the non-draft ones) and `OPEN_MRS` the member cell
 * (drafts included); the rest are the member cells.
 */
enum class CodeDrillView(val histogram: Boolean = false) {
    COMMITS,
    MRS_MERGED,
    MRS_OPENED,
    OPEN_MRS,
    OPEN_MRS_WAITING,
    MR_CYCLE_WORKING_DAYS(histogram = true),
    MR_CYCLE_ELAPSED_DAYS(histogram = true),
    TIME_TO_FIRST_REVIEW(histogram = true),
    MR_SIZE_LINES(histogram = true),
    MR_SIZE_FILES(histogram = true),
    MERGED_WITHOUT_REVIEW,
    MERGED_WITHOUT_APPROVAL,
    REVIEWS_GIVEN,
    APPROVALS_GIVEN,
    CHANGES_REQUESTED_GIVEN,
    COMMENTS,
    REVIEW_REQUESTS_RECEIVED,
    WEEK,
}

/** The bar's identity, parsed (every `400` that needs no data is thrown by [codeDrillQuery]). */
internal class CodeDrillQuery(val view: CodeDrillView, val bucketIndex: Int?, val bucketStart: LocalDate?, val segmentAccountId: String?)

/** Parses `view` and the bar's identity: `bucketIndex` only on the histogram views, `bucketStart` required on (and only on) `WEEK`. */
internal fun Parameters.codeDrillQuery(): CodeDrillQuery {
    val view = requireEnum<CodeDrillView>("view")
    val bucketIndex = optionalBucketIndex()
    val bucketStart = optionalString("bucketStart")?.let { parseIsoDate(it, "bucketStart") }
    if (bucketIndex != null && !view.histogram) throw BadRequestException("bucketIndex applies to the histogram views only")
    if (view == CodeDrillView.WEEK && bucketStart == null) throw BadRequestException("bucketStart is required for the WEEK view")
    if (view != CodeDrillView.WEEK && bucketStart != null) throw BadRequestException("bucketStart applies to the WEEK view only")
    return CodeDrillQuery(view, bucketIndex, bucketStart, optionalString("segmentAccountId"))
}

/**
 * One item behind a figure, identity only (labels are fetched for the listed ones, `CodeActivityDrillLabels.kt`): a commit
 * (`connectionId`, [projectId], [sha]) or a merge request (`connectionId`, [mrId]), whose [member] is the author / actor the figure
 * attributes it to, [anchorAt] the instant it is anchored by and [figure] the figure's own value (null: none, or unknown).
 */
internal data class CodeDrillRow(
    val kind: DrillItemKind,
    val connectionId: UInt,
    val projectId: Long,
    val sha: String?,
    val mrId: Long?,
    val member: CodeMember,
    val anchorAt: Long,
    val figure: Double?,
) {
    val mrRef: CodeMrRef get() = CodeMrRef(connectionId, mrId!!)
}

private fun commitRow(commit: CodeCommitRow, member: CodeMember) = CodeDrillRow(
    DrillItemKind.COMMIT, commit.connectionId, commit.projectId, commit.sha, null, member, commit.committedAt,
    commit.linesCounted?.toDouble(),
)

private fun mrRow(row: CodeMrRow, member: CodeMember, anchorAt: Long, figure: Double?) =
    CodeDrillRow(DrillItemKind.MERGE_REQUEST, row.connectionId, row.projectId, null, row.mrId, member, anchorAt, figure)

private fun actRow(mr: CodeMrRef, member: CodeMember, anchorAt: Long) =
    CodeDrillRow(DrillItemKind.MERGE_REQUEST, mr.connectionId, 0, null, mr.mrId, member, anchorAt, null)

/** The drill order (figure descending, nulls last; newest anchor; connection; id): the cut to [DRILL_MAX_ITEMS] before a label is read. */
private val CODE_DRILL_ORDER: Comparator<CodeDrillRow> =
    compareByDescending<CodeDrillRow, Double?>(nullsFirst()) { it.figure }
        .thenByDescending { it.anchorAt }
        .thenBy { it.connectionId }
        .thenBy { it.mrId ?: 0L }
        .thenBy { it.sha.orEmpty() }

/** A merged merge request of the flow, anchored by `merged_at`, with [figure] its own value. */
private fun MergedMrFigure.row(figure: Double?) = mrRow(scoped.row, scoped.member, scoped.row.mergedAt!!, figure)

private fun CodeDrillRow.narrowedTo(segment: String?): Boolean = segment == null || member.accountId == segment

/** One row per `(member, merge request)` pair, anchored by the earliest act (distinct merge requests, as the cells count them). */
private fun List<Pair<CodeMember, Pair<CodeMrRef, Long>>>.distinctPairs(): List<CodeDrillRow> =
    groupBy { it.first to it.second.first }
        .map { (key, acts) -> actRow(key.second, key.first, acts.minOf { it.second.second }) }

/** The rows of [view] over [data]: no `bucketIndex` / `bucketStart` handling (the histogram and week views are in [selectCodeDrill]). */
private fun CodeKernelContext.viewRows(view: CodeDrillView, data: CodeActivityData, flow: CodeFlowFigures): List<CodeDrillRow> {
    fun acts(accept: (CodeAct) -> Boolean) = data.events.mapNotNull(::actOf).filter(accept)
    fun distinctActs(accept: (CodeAct) -> Boolean) = acts(accept).map { it.member to (it.event.mr to it.at) }.distinctPairs()
    return when (view) {
        CodeDrillView.COMMITS -> data.commits.mapNotNull { commit -> commitMember(commit)?.let { commitRow(commit, it) } }
        CodeDrillView.MRS_MERGED -> flow.mrs.map { it.row(it.cycleWorkingDays) }
        CodeDrillView.MRS_OPENED -> data.mrs.mapNotNull { row ->
            openedMember(row)?.let { mrRow(row, it, row.createdAt, row.sizeLines?.toDouble()) }
        }
        CodeDrillView.OPEN_MRS, CodeDrillView.OPEN_MRS_WAITING -> data.mrs.mapNotNull { row ->
            val member = openNowMember(row)?.takeIf { view == CodeDrillView.OPEN_MRS || !row.draft }
            member?.let { mrRow(row, it, row.createdAt, openAgeWorkingDays(row)) }
        }
        CodeDrillView.MERGED_WITHOUT_REVIEW -> flow.mrs.filter { it.mergedWithoutReview() }.map { it.row(it.cycleWorkingDays) }
        CodeDrillView.MERGED_WITHOUT_APPROVAL -> flow.mrs.filter { it.mergedWithoutApproval() }.map { it.row(it.cycleWorkingDays) }
        CodeDrillView.REVIEWS_GIVEN -> distinctActs { it.review }
        CodeDrillView.APPROVALS_GIVEN -> distinctActs { it.review && it.event.kind == MrEventKind.APPROVAL }
        CodeDrillView.CHANGES_REQUESTED_GIVEN -> distinctActs { it.review && it.event.kind == MrEventKind.CHANGES_REQUESTED }
        // Comments are ACTS, not distinct merge requests: one row per comment, so the list adds up to the cell.
        CodeDrillView.COMMENTS -> acts { it.comment }.map { actRow(it.event.mr, it.member, it.at) }
        CodeDrillView.REVIEW_REQUESTS_RECEIVED -> data.requests
            .flatMap { event -> requestReceivers(event).map { it to (event.mr to event.eventAt!!) } }.distinctPairs()
        CodeDrillView.WEEK, CodeDrillView.MR_CYCLE_WORKING_DAYS, CodeDrillView.MR_CYCLE_ELAPSED_DAYS, CodeDrillView.TIME_TO_FIRST_REVIEW,
        CodeDrillView.MR_SIZE_LINES, CodeDrillView.MR_SIZE_FILES -> error("$view is selected by selectCodeDrill")
    }
}

/** The value a histogram view measures: the very property the report's distribution is built from. */
private fun CodeDrillView.measure(): (MergedMrFigure) -> Double? = when (this) {
    CodeDrillView.MR_CYCLE_WORKING_DAYS -> MergedMrFigure::cycleWorkingDays
    CodeDrillView.MR_CYCLE_ELAPSED_DAYS -> MergedMrFigure::cycleElapsedDays
    CodeDrillView.TIME_TO_FIRST_REVIEW -> MergedMrFigure::firstReviewWorkingDays
    CodeDrillView.MR_SIZE_LINES -> MergedMrFigure::sizeLines
    CodeDrillView.MR_SIZE_FILES -> MergedMrFigure::sizeFiles
    else -> error("$this is no histogram view")
}

/** The rows behind the figure [query] names and the bar recomputed over exactly them. */
internal fun CodeKernelContext.selectCodeDrill(
    query: CodeDrillQuery,
    segment: String?,
    tailPercent: Int,
    data: CodeActivityData,
): DistributionSelection<CodeDrillRow> {
    val flow = flowFigures(data.mrs, data.summaries)
    val view = query.view
    if (view.histogram) {
        val measure = view.measure()
        val index = query.bucketIndex
        if (index != null) {
            val drillRow = { f: MergedMrFigure, value: Double -> f.row(value) }
            return distributionDrillSelection(flow.mrs, minSample, tailPercent, index, segment, measure, drillRow = drillRow) {
                it.member.accountId
            }
        }
        val rows = flow.mrs.mapNotNull { f -> measure(f)?.let { f.row(it) } }.filter { it.narrowedTo(segment) }
        return DistributionSelection(ReportDrillBar(rows.size, null, null, null), rows)
    }
    if (view == CodeDrillView.WEEK) {
        val grid = weekGrid()
        val week = grid.days.indexOf(query.bucketStart!!)
        if (week < 0) throw BadRequestException("bucketStart ${query.bucketStart} is not the first day of a week of the period")
        val rows = flow.mrs.filter { grid.indexOf(it.scoped.row.mergedAt!!) == week }.map { it.row(it.cycleWorkingDays) }
            .filter { it.narrowedTo(segment) }
        return DistributionSelection(ReportDrillBar(rows.size, null, null, null), rows)
    }
    val rows = viewRows(view, data, flow).filter { it.narrowedTo(segment) }
    return DistributionSelection(ReportDrillBar(rows.size, null, null, null), rows)
}

/**
 * The items behind one figure of Report 21 (`GET /api/v1/reports/code-activity/items`, `.claude/docs/reports.md` "Report 21"). The parent's
 * parameters decide the scope; `segmentAccountId` narrows to one picked person (USER level only, else `400`, as everywhere). At TEAM level
 * a member's cell is the USER-level request with that member as the only pick (the same rows, attributed by the same anchors).
 */
internal suspend fun ReportService.codeActivityItems(
    filter: ReportFilter,
    query: CodeDrillQuery,
    nowMs: Long,
): ReportDrillItems = reportTransaction {
    val deps = checkNotNull(codeDeps) { "the code activity report is not configured on this service" }
    val segment = filter.requireSegment(query.segmentAccountId)
    val read = readCodeActivity(deps, filter, nowMs, forDrill = true)
    val meta = read.scope.meta
    val data = read.data
    if (data == null) {
        // A sprint period with no sprint to resolve reads nothing: there is no bar to name, and every list is empty.
        if (query.bucketIndex != null || query.bucketStart != null) {
            throw BadRequestException("The period has nothing to read: there is no bar to list")
        }
        return@reportTransaction ReportDrillItems(meta, ReportDrillBar(0, null, null, null), emptyList(), total = 0, truncated = false)
    }
    val selection = data.context.selectCodeDrill(query, segment, filter.tailPercent, data)
    codeDrillItems(meta, checkNotNull(filter.teamId), selection, data.labels)
}

/**
 * Who the item is shown as: the linked account's display name (the id when unknown); an unlinked identity by its display name or username
 * (only read for `teamId=0`), never by its key; nobody for an author-less merge request.
 */
private fun assigneeOf(member: CodeMember, names: Map<String, String>, unlinkedLabels: Map<Pair<String, String>, String>): String? {
    if (member.accountId != null) return names[member.accountId] ?: member.accountId
    return if (member.host != null && member.identityKey != null) unlinkedLabels[member.host to member.identityKey] else null
}

/**
 * Sorts, cuts to [DRILL_MAX_ITEMS], labels ONLY the listed rows and assembles the response (`total` and `bar` cover every row). [teamId]
 * is the request's team, null where the figure belongs to no team (Compare people's code drill reads a person across teams).
 */
internal suspend fun codeDrillItems(
    meta: ReportMeta,
    teamId: UInt?,
    selection: DistributionSelection<CodeDrillRow>,
    unlinkedLabels: Map<Pair<String, String>, String>,
): ReportDrillItems {
    val rows = selection.rows
    val listed = rows.sortedWith(CODE_DRILL_ORDER).take(DRILL_MAX_ITEMS)
    val labels = fetchCodeDrillLabels(listed)
    val names = accountDisplayNames(listed.mapNotNull { it.member.accountId }.distinct())
    val items = listed.map { row ->
        val member = row.member
        ReportDrillItem(
            issueKey = labels.issueKey(row),
            summary = labels.summary(row),
            itemKind = row.kind,
            accountId = member.accountId,
            assignee = assigneeOf(member, names, unlinkedLabels),
            teamId = teamId,
            anchorAt = row.anchorAt,
            figure = row.figure,
        )
    }.sortedWith(
        compareByDescending<ReportDrillItem, Double?>(nullsFirst()) { it.figure }
            .thenByDescending(nullsFirst()) { it.anchorAt }
            .thenBy { it.issueKey },
    )
    return ReportDrillItems(meta, selection.bar, items, total = rows.size, truncated = rows.size > items.size)
}
