package ch.nokillswit.reports

import ch.nokillswit.identities.IdentityResolver
import ch.nokillswit.metrics.WorkingCalendar
import java.time.ZoneId

/*
 * Report 21's read plan (`.claude/docs/reports.md` "Report 21"): the scope, the keys of the people in play, the four reads and the
 * identity / membership lookups, in the order the data allows. It runs inside the caller's report transaction and computes nothing
 * (CodeActivityKernels.kt / CodeActivityMembers.kt do), so the figures can be tested without a database.
 */

/** What one request reads. [picks] are the USER level's accounts (empty: the whole team); [teamId] `0` is the UNASSIGNED bucket. */
internal class CodeActivityRequest(
    val sources: List<CodeSource>,
    val teamId: UInt,
    val picks: List<String>,
    val window: Pair<Long, Long>,
    val nowMs: Long,
    /** A drill reads no `quality` block: the two connection-wide aggregate reads that only it uses are skipped (their data stays empty). */
    val forDrill: Boolean = false,
)

/** Everything the figures need: the rows, who they are and the freshly read quality inputs. */
internal class CodeActivityData(
    val context: CodeKernelContext,
    val commits: List<CodeCommitRow>,
    val mrs: List<CodeMrRow>,
    val events: List<CodeEventRow>,
    val requests: List<CodeEventRow>,
    val summaries: List<CodeReviewSummary>,
    val keyActivity: List<CodeKeyActivity>,
    val qualityCounts: CodeQualityCounts,
    /** The team's people (or the picks): listed in `groups` even with no row. Empty for `teamId=0`. */
    val roster: Set<String>,
    /** Display names of the unlinked identity keys of the rows, read for `teamId=0` only. */
    val labels: Map<Pair<String, String>, String>,
)

/** The figures of one request, ready for the wire mapping (part (b)). */
internal class CodeActivityFigures(
    val flow: CodeFlowFigures,
    val review: CodeReviewConcentration,
    val open: CodeOpenFigures,
    val members: List<CodeMemberFigures>,
    val quality: CodeQuality,
)

/** The keys by connection that [rows] name, for the identity lookup. */
private fun keysOf(data: Map<UInt, MutableSet<String>>, connectionId: UInt, vararg keys: String?) {
    keys.filterNotNull().forEach { data.getValue(connectionId) += it }
}

/** Which of the optional reads [loadCodeRows] performs (the merge requests and the review summaries are always read). */
internal class CodeRowReads(
    /** The commits read. */
    val commits: Boolean,
    /** The actor events and the dated review requests read. */
    val events: Boolean,
    /** The two connection-wide aggregate reads of the `quality` block (a drill and Home read none). */
    val quality: Boolean,
)

/**
 * The roster-driven read plan of [loadCodeRows]: [sources], the [roster] whose LINKED keys are the key scope (or every key of the sources
 * when [allKeys]), the [firstPass] selection that decides which merged merge requests get their review summaries read (and that the
 * returned context carries), the [window] and the clock inputs of the kernels.
 */
internal class CodeRowsRequest(
    val sources: List<CodeSource>,
    val roster: Set<String>,
    val firstPass: CodeSelection,
    val window: Pair<Long, Long>,
    val nowMs: Long,
    val calendar: WorkingCalendar,
    val zone: ZoneId,
    val minSample: Int,
    val reads: CodeRowReads,
    /** No key scope: every key of the sources is read (the UNASSIGNED bucket). */
    val allKeys: Boolean = false,
)

/**
 * Reads everything a request needs. The key scope is the roster's (or the picks') LINKED keys, or none for `teamId=0` (every key of
 * the sources); rows are then attributed by their anchors in the kernels. The review summaries are read for the READ merged merge
 * requests in scope only, after the first attribution pass. Every row read is charged to [budget] (one per request, shared by the
 * four reads and the summaries): a selection over it is a 400, never a cut one (`CodeActivityBudget.kt`).
 */
internal suspend fun loadCodeActivity(
    resolver: IdentityResolver,
    request: CodeActivityRequest,
    calendar: WorkingCalendar,
    zone: ZoneId,
    minSample: Int,
    budget: CodeRowBudget,
): CodeActivityData {
    val window = request.window
    val unassignedAll = request.teamId == UNASSIGNED_TEAM_ID && request.picks.isEmpty()
    // A mover's rows are attributed by anchor, and an open merge request by now: the roster is who belonged to the team at some point of
    // the window, plus who belongs to it now (for the open merge requests) -- not everyone in between.
    val roster = when {
        request.picks.isNotEmpty() -> request.picks.toSet()
        unassignedAll -> emptySet()
        else -> readTeamRoster(request.teamId, window.first, window.second) +
            readTeamRoster(request.teamId, request.nowMs, request.nowMs + 1)
    }
    val selection = CodeSelection(request.teamId, request.picks.takeIf { it.isNotEmpty() }?.toSet())
    val reads = CodeRowReads(commits = true, events = true, quality = !request.forDrill)
    val rows = CodeRowsRequest(request.sources, roster, selection, window, request.nowMs, calendar, zone, minSample, reads, unassignedAll)
    return loadCodeRows(resolver, rows, budget)
}

/**
 * The read plan under [loadCodeActivity], driven by an explicit roster: the key scope, the reads [CodeRowsRequest.reads] asks for, the
 * identity and membership lookups, then the review summaries of the merged merge requests in `firstPass`. Home's code section calls it
 * with the union of its teams' rosters and no commits or events (`HomeCode.kt`).
 */
internal suspend fun loadCodeRows(resolver: IdentityResolver, request: CodeRowsRequest, budget: CodeRowBudget): CodeActivityData {
    val sources = request.sources
    val connections = sources.map { it.connectionId }
    val window = request.window
    val roster = request.roster
    val reads = request.reads
    val scope: CodeKeyScope = if (request.allKeys) null else keysOfAccounts(resolver, sources, roster)
    val commits = if (reads.commits) fetchCodeCommits(connections, scope, window, budget) else emptyList()
    val mrs = fetchCodeMergeRequests(connections, scope, window, budget)
    val events = if (reads.events) fetchCodeActorEvents(connections, scope, window, budget) else emptyList()
    val requests = if (reads.events) fetchCodeReviewRequests(connections, scope, window, budget) else emptyList()
    val activity = if (reads.quality) fetchCodeKeyActivity(connections, window) else emptyList()
    val qualityCounts = if (reads.quality) fetchCodeQualityCounts(connections, window) else CodeQualityCounts(0, 0, 0, 0)

    val seen = connections.associateWith { mutableSetOf<String>() }
    commits.forEach { keysOf(seen, it.connectionId, it.identityKey) }
    mrs.forEach { keysOf(seen, it.connectionId, it.authorKey) }
    (events + requests).forEach { keysOf(seen, it.mr.connectionId, it.actorKey, it.mrAuthorKey, *it.targetKeys.toTypedArray()) }
    activity.forEach { keysOf(seen, it.connectionId, it.key) }
    val identities = loadCodeIdentities(resolver, sources, seen)
    val accounts = seen.flatMap { (id, keys) -> keys.mapNotNull { identities.accountOf(id, it) } }.toSet() + roster
    val memberships = readCodeMemberships(accounts)
    val selection = request.firstPass
    val first = CodeKernelContext(
        selection, CodeAttribution(identities, memberships), window, request.nowMs, request.calendar, request.zone, request.minSample,
    )

    val readMrs = first.mergedInScope(mrs).map { it.row }.filter { it.detailsRead }.map { it.ref }
    val summaries = fetchCodeReviewSummaries(readMrs, budget)
    // The reviewers may be people who did nothing else in the window: their decisions are read after the summaries are known.
    summaries.forEach { keysOf(seen, it.mr.connectionId, it.actorKey) }
    val context = CodeKernelContext(
        selection, CodeAttribution(loadCodeIdentities(resolver, sources, seen), memberships), window, request.nowMs, request.calendar,
        request.zone, request.minSample,
    )
    val labels = if (selection.teamId == UNASSIGNED_TEAM_ID) undecidedLabels(context.attribution.identities, sources, seen) else emptyMap()
    return CodeActivityData(context, commits, mrs, events, requests, summaries, activity, qualityCounts, roster, labels)
}

/** The labels of the undecided keys of [seen], by (host, key): the lowest connection id of a host wins. */
private suspend fun undecidedLabels(
    identities: CodeIdentities,
    sources: List<CodeSource>,
    seen: Map<UInt, Set<String>>,
): Map<Pair<String, String>, String> {
    val keys = seen.flatMap { (id, keys) -> keys.filterNot { identities.isDecided(id, it) } }
    val byConnection = fetchCodeIdentityLabels(sources.map { it.connectionId }, keys)
    val hostOf = sources.associate { it.connectionId to it.host }
    return byConnection.entries.sortedByDescending { it.key.first }
        .associate { (id, label) -> (hostOf.getValue(id.first) to id.second) to label }
}

/** The figures of [data]: the flow, the open merge requests, the review concentration, the members and the `quality` block. */
internal fun codeActivityFigures(data: CodeActivityData): CodeActivityFigures = with(data.context) {
    val flow = flowFigures(data.mrs, data.summaries)
    CodeActivityFigures(
        flow = flow,
        review = reviewConcentration(flow, minSample),
        open = openFigures(data.mrs),
        members = memberFigures(flow, data.commits, data.mrs, data.events, data.requests, data.roster),
        quality = codeQuality(data.keyActivity, data.qualityCounts, attribution.identities),
    )
}

/** What a code request read: the Jira star's [scope] (team, `meta`, window), the GitLab [sources] and the [data] (null: no window). */
internal data class CodeRead(val scope: ReportScope, val sources: List<CodeSource>, val data: CodeActivityData?)

/**
 * The shared head of Report 21 and its drill (inside the caller's report transaction): the GitLab sources of the filter's `connectionId`,
 * `resolveReportScope` over the JIRA star (given no `connectionId`: it names a GitLab source here), and [loadCodeActivity] over the window.
 * A sprint period with no sprint to resolve has no window, hence no data: every figure is empty, like the other reports'.
 */
internal suspend fun ReportService.readCodeActivity(
    deps: CodeActivityDeps,
    filter: ReportFilter,
    nowMs: Long,
    forDrill: Boolean = false,
): CodeRead {
    val sources = resolveCodeSources(deps.dataSources, filter.connectionId)
    val scope = resolveReportScope(filter.copy(connectionId = null), nowMs)
    val teamId = checkNotNull(filter.teamId) { "TEAM_ONLY: the parser refuses a request without a teamId" }
    val data = scope.window?.let {
        val request = CodeActivityRequest(sources, teamId, filter.accountIds, it, nowMs, forDrill)
        val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
        loadCodeActivity(deps.resolver, request, scope.calendar, zone, scope.settings.minSampleSize, CodeRowBudget(deps.maxRows))
    }
    return CodeRead(scope, sources, data)
}
