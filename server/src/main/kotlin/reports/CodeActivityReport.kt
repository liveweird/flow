package ch.nokillswit.reports

import ch.nokillswit.identities.IdentityResolver
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.ingest.DataProfileSections
import ch.nokillswit.ingest.DataSourceService
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

/** What Report 21 reads beyond the Jira star: the data sources (the GitLab hosts, the freshness) and the identity resolver. */
class CodeActivityDeps(
    val dataSources: DataSourceService,
    val resolver: IdentityResolver,
    /** `reports.codeMaxRows`: the per-request row budget of every code read (`CodeActivityBudget.kt`). */
    val maxRows: Int = DEFAULT_CODE_MAX_ROWS,
    /** `reports.codeReadPermits`: how many Report 21 computations and drills run at once (`CodeActivityBudget.kt`). */
    val permits: CodeReadPermits = CodeReadPermits(DEFAULT_CODE_READ_PERMITS, DEFAULT_REPORT_STATEMENT_TIMEOUT_SECONDS * 1000L),
)

/**
 * Runs [block] (a Report 21 read) under the per-process permit gate. Called OUTSIDE `reportTransaction`: the route has no transaction
 * open at this point (the validators' own are closed), and a request waiting for a permit must hold no pooled connection. A 304 never
 * gets here (`respondRevalidated` runs the answer only on a cache miss).
 */
internal suspend fun <T> ReportService.withCodeReadPermit(block: suspend () -> T): T =
    checkNotNull(codeDeps) { "the code activity report is not configured on this service" }.permits.withPermit(block)

private val PROFILE_JSON = Json { ignoreUnknownKeys = true }

/**
 * `GET /api/v1/reports/code-activity` (Report 21, `.claude/docs/reports.md` "Report 21", domain-model A50): the merge-request FLOW of one
 * team over the GitLab code layer (cycle time, time to first review, merged without review / approval, open merge requests waiting,
 * size, the weekly merged bars, review concentration) and, as NEUTRAL context, each member's volumes. Read at QUERY time from
 * `norm.code_*`: [loadCodeActivity] reads, the kernels compute, this assembles. `resolveReportScope` supplies the team check, `meta`, the
 * sprint-relative window and `resolvedSprints` over the JIRA star (it is given no `connectionId`: that one names a GitLab source here,
 * and a Jira one is the same `400` as an unknown one).
 */
internal suspend fun ReportService.codeActivity(filter: ReportFilter, nowMs: Long): CodeActivityReport =
    withCodeReadPermit { computeCodeActivity(filter, nowMs) }

private suspend fun ReportService.computeCodeActivity(filter: ReportFilter, nowMs: Long): CodeActivityReport = reportTransaction {
    val deps = checkNotNull(codeDeps) { "the code activity report is not configured on this service" }
    val (scope, sources, data) = readCodeActivity(deps, filter, nowMs)
    val picked = pickedAccounts(filter)
    val minSample = scope.settings.minSampleSize
    val freshness = codeSourceFreshness(deps.dataSources, sources)
    // A sprint period with no sprint to resolve reads nothing: every figure is empty, like the other reports'.
    val figures = data?.let { codeActivityFigures(it) } ?: emptyCodeActivityFigures(minSample)
    val flow = figures.flow
    CodeActivityReport(
        meta = scope.meta,
        sources = freshness,
        quality = figures.quality.toWire(),
        flow = CodeActivityFlow(
            merged = flow.merged,
            closedUnmerged = flow.closedUnmerged,
            // The value functions are the drill's too (CodeActivityDrill.kt): a bar and its items cannot disagree.
            cycleWorkingDays = flow.distribution(minSample, filter.tailPercent, picked, MergedMrFigure::cycleWorkingDays),
            cycleElapsedDays = flow.distribution(minSample, filter.tailPercent, picked, MergedMrFigure::cycleElapsedDays),
            timeToFirstReviewWorkingDays = flow.distribution(minSample, filter.tailPercent, picked, MergedMrFigure::firstReviewWorkingDays),
            sizeLines = flow.distribution(minSample, filter.tailPercent, picked, MergedMrFigure::sizeLines),
            sizeFiles = flow.distribution(minSample, filter.tailPercent, picked, MergedMrFigure::sizeFiles),
            mergedWithoutReview = flow.mergedWithoutReview.toWire(),
            mergedWithoutApproval = flow.mergedWithoutApproval.toWire(),
            weekly = flow.weekly.map { CodeActivityWeek(it.bucketStart.toString(), it.merged) },
        ),
        open = figures.open.toWire(),
        review = figures.review.takeIf { it.n > 0 }?.let {
            CodeActivityReview(it.n, it.reviewersPerMergedMrP50, it.topReviewerShare)
        },
        groups = codeActivityGroups(figures.members, picked, data?.labels.orEmpty()),
    )
}

private fun emptyCodeActivityFigures(minSample: Int): CodeActivityFigures {
    val flow = CodeFlowFigures(emptyList(), 0, CodeShare(0, null), CodeShare(0, null), emptyList())
    return CodeActivityFigures(
        flow = flow,
        review = reviewConcentration(flow, minSample),
        open = CodeOpenFigures(0, 0, ageHidden = true, ageP50 = null, ageP85 = null),
        members = emptyList(),
        quality = CodeQuality(0, 0, 0, 0, 0, 0, 0, 0),
    )
}

private fun CodeQuality.toWire() = CodeActivityQuality(
    unlinkedIdentities, unlinkedCommits, unlinkedMergeRequests, unlinkedEvents, commitsLinesUnknown, mrsSizeUnknown, mrsDetailsNotRead,
    eventsUndated,
)

private fun CodeShare.toWire() = CodeActivityShare(count, share)

private fun CodeOpenFigures.toWire() = CodeActivityOpen(count, drafts, CodeActivityOpenAge(count, ageHidden, ageP50, ageP85))

/**
 * Each source's freshness: its last successful sync and the two pending counts of its last data profile (the PROFILE stream writes them
 * after every sync; a source never profiled, or whose stored profile does not decode, reads 0). In connection order.
 */
internal suspend fun codeSourceFreshness(dataSources: DataSourceService, sources: List<CodeSource>): List<CodeActivitySource> {
    if (sources.isEmpty()) return emptyList()
    val c = DataSourceService.Connections
    val rows = c.select(c.id, c.name, c.lastSyncSucceededAt).where { c.id inList sources.map { it.connectionId } }.toList()
        .associateBy { it[c.id].value }
    return sources.mapNotNull { source ->
        val row = rows[source.connectionId] ?: return@mapNotNull null
        val gitlab = dataSources.readProfile(source.connectionId)?.profileJson
            ?.let { json -> catchingFailures({ PROFILE_JSON.decodeFromString<DataProfileSections>(json) }) { null } }?.gitlab
        CodeActivitySource(
            connectionId = source.connectionId,
            name = row[c.name],
            baseUrl = source.baseUrl,
            lastSyncAt = row[c.lastSyncSucceededAt],
            commitFilesPending = gitlab?.commits?.filesPending ?: 0,
            mrDetailsPending = gitlab?.mergeRequests?.detailsPending ?: 0,
        )
    }
}

/** A member with no figure at all (a pick whose rows are all elsewhere). */
private fun zeroFigures(member: CodeMember) = CodeMemberFigures(member, 0, 0, 0, 0, 0, 0, 0, 0, null, null, 0, 0, 0, 0, 0, 0)

private fun CodeMemberFigures.toGroup(label: String?) = CodeActivityGroup(
    accountId = member.accountId,
    label = label,
    commits = commits,
    filesChanged = filesChanged,
    linesAdded = linesAdded,
    linesDeleted = linesDeleted,
    commitsLinesUnknown = commitsLinesUnknown,
    mrsMerged = mrsMerged,
    mrsOpened = mrsOpened,
    openMrs = openMrs,
    mrCycleWorkingDaysP50 = mrCycleWorkingDaysP50,
    mrSizeLinesP50 = mrSizeLinesP50,
    reviewsGiven = reviewsGiven,
    approvalsGiven = approvalsGiven,
    changesRequestedGiven = changesRequestedGiven,
    comments = comments,
    reviewRequestsReceived = reviewRequestsReceived,
)

private fun unlinkedLabel(member: CodeMember, labels: Map<Pair<String, String>, String>): String? =
    if (member.host != null && member.identityKey != null) labels[member.host to member.identityKey] else null

/**
 * The `groups`: TEAM level one per LINKED member (the roster is seeded with zeros), ordered like every report's org groups (display
 * name, then account id); USER level one per pick in that order; then, for `teamId=0`, one per undecided unlinked identity (named by
 * its identity's display name or username, absent for a ghost) ordered by that name. A key is never put on the wire: it is an
 * unsalted hash of an address.
 */
private suspend fun codeActivityGroups(
    members: List<CodeMemberFigures>,
    picked: PickedAccounts,
    labels: Map<Pair<String, String>, String>,
): List<CodeActivityGroup> {
    val linked = members.filter { it.member.accountId != null }
    val ordered = if (picked.ids.isNotEmpty()) {
        pickedGroups(picked, linked) { it.member.accountId }
    } else {
        orgGroups(ReportLevel.TEAM, linked, { null }, { it.member.accountId })
    }
    val linkedGroups = ordered.map { (key, rows) ->
        (rows.singleOrNull() ?: zeroFigures(CodeMember(key.accountId, null))).toGroup(key.label)
    }
    val unlinked = members.filter { it.member.accountId == null }
        .map { it to unlinkedLabel(it.member, labels) }
        .sortedWith(compareBy({ it.second == null }, { it.second }))
        .map { (figures, label) -> figures.toGroup(label) }
    return linkedGroups + unlinked
}
