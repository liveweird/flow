package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * The CODE rows of Compare people (Report 18) and Person trend (Report 20), A50: a person's commits, lines, merge requests, reviews,
 * approvals and comments over a window, by the VERY functions Report 21's member cells use (`memberFigures`, `commitMember`, `actOf`,
 * `mergedFigures`, `selectCodeDrill`) -- one truth. What differs is only the selection: Compare people attributes by person (A41), so
 * the team never narrows ([CodeSelection] with a null team) and no membership is read. The Jira slices (`domain`, `domainView`,
 * `activityType`, `workCategory`, `connectionId`) do not apply: a GitLab project is not a Jira domain and `connectionId` names a Jira
 * source here, so the code is read from EVERY active GitLab source (the WIP rows are the other rows with no slice).
 */

/**
 * The code populations of the compared people over one RANGE, grouped by the account their identity key is LINKED to (a person with no
 * link has none: zeros). Person trend reads the range once and each window [figuresOf] its slice; Compare people's range is its window.
 * Commits are read only when [commitsRead] (Person trend's rows need none): the commit cells exist only then, so a row asked of a read
 * that skipped them fails loudly instead of answering 0.
 */
internal class CodePeople(
    private val identities: CodeIdentities,
    private val commits: Map<String, List<CodeCommitRow>>,
    private val mrs: Map<String, List<CodeMrRow>>,
    private val events: Map<String, List<CodeEventRow>>,
    private val zone: ZoneId,
    private val nowMs: Long,
    val commitsRead: Boolean,
) {
    /**
     * The rows of [account] for [window] (`[from, to)`): the commits and the acts anchored inside it, every merge request the person
     * authored (the kernels window them by `merged_at` and `created_at` themselves), under a selection of that one person in ANY team.
     * The same shape the Report 21 kernels and drill take, so the Compare people drill is Report 21's own `selectCodeDrill`.
     */
    fun dataOf(account: String, window: Pair<Long, Long>, calendar: WorkingCalendar, minSample: Int): CodeActivityData {
        val context = CodeKernelContext(
            CodeSelection(teamId = null, accounts = setOf(account)), CodeAttribution(identities, emptyMap()), window, nowMs, calendar,
            zone, minSample,
        )
        return CodeActivityData(
            context = context,
            commits = commits[account].orEmpty().sliceByAnchor(window) { it.committedAt },
            mrs = mrs[account].orEmpty(),
            events = events[account].orEmpty().sliceByAnchor(window) { it.eventAt!! },
            requests = emptyList(),
            summaries = emptyList(),
            keyActivity = emptyList(),
            qualityCounts = CodeQualityCounts(0, 0, 0, 0),
            roster = setOf(account),
            labels = emptyMap(),
        )
    }

    /** [account]'s Report 21 member figures over [window]: the cells of that report's `groups`, attributed to the person in any team. */
    fun figuresOf(account: String, window: Pair<Long, Long>, calendar: WorkingCalendar, minSample: Int): CodeMemberFigures {
        val data = dataOf(account, window, calendar, minSample)
        // The member cells read only the merged merge requests' cycle and size: no review summaries, no flow shares, no weekly bars.
        val merged = data.context.mergedFigures(data.mrs, emptyList())
        val flow = CodeFlowFigures(merged, 0, CodeShare(0, null), CodeShare(0, null), emptyList())
        return data.context.memberFigures(flow, data.commits, data.mrs, data.events, data.requests, data.roster)
            .first { it.member.accountId == account }
    }

    companion object {
        /** No GitLab source, or none of the people linked: every count is 0. */
        fun none(commitsRead: Boolean = true): CodePeople = CodePeople(
            CodeIdentities(emptyMap(), emptyMap(), emptyMap()), emptyMap(), emptyMap(), emptyMap(), ZoneOffset.UTC, 0L, commitsRead,
        )
    }
}

/**
 * The code populations of [accounts] over [range], read ONCE for all of them across every ACTIVE GitLab source: the keys LINKED to
 * them (one statement per host), their commits (only when [withCommits]), merge requests and dated acts (three statements), and the
 * decisions of the keys those rows name (the merge requests' authors included, for "not the author's own review"). Inside the caller's
 * report transaction. Empty when [deps] is null, no source is active or none of the people has a link.
 */
internal suspend fun loadCodePeople(
    deps: CodeActivityDeps?,
    accounts: Collection<String>,
    range: Pair<Long, Long>,
    zone: ZoneId,
    nowMs: Long,
    withCommits: Boolean,
): CodePeople {
    deps ?: return CodePeople.none(withCommits)
    val sources = resolveCodeSources(deps.dataSources, null)
    val scope = keysOfAccounts(deps.resolver, sources, accounts)
    if (scope.isEmpty()) return CodePeople.none(withCommits)
    val connections = sources.map { it.connectionId }
    val commits = if (withCommits) fetchCodeCommits(connections, scope, range) else emptyList()
    val mrs = fetchCodeMergeRequests(connections, scope, range)
    val events = fetchCodeActorEvents(connections, scope, range)
    val seen = connections.associateWith { mutableSetOf<String>() }
    commits.forEach { seen.getValue(it.connectionId) += it.identityKey }
    mrs.forEach { row -> row.authorKey?.let { seen.getValue(row.connectionId) += it } }
    events.forEach { event ->
        listOfNotNull(event.actorKey, event.mrAuthorKey).forEach { seen.getValue(event.mr.connectionId) += it }
    }
    val identities = loadCodeIdentities(deps.resolver, sources, seen)
    return CodePeople(
        identities = identities,
        commits = commits.groupBy { identities.accountOf(it.connectionId, it.identityKey) }.linked(),
        mrs = mrs.groupBy { identities.accountOf(it.connectionId, it.authorKey) }.linked(),
        events = events.groupBy { identities.accountOf(it.mr.connectionId, it.actorKey) }.linked()
            .mapValues { (_, rows) -> rows.sortedBy { it.eventAt } },
        zone = zone,
        nowMs = nowMs,
        commitsRead = withCommits,
    )
}

/** The groups of a LINKED account: rows whose key is linked to nobody are no one's cell. */
private fun <T> Map<String?, List<T>>.linked(): Map<String, List<T>> =
    entries.mapNotNull { (account, rows) -> account?.let { it to rows } }.toMap()

/**
 * The CODE cells of [account] over this comparison's window (`CompareData.window`), keys as [COMPARE_ROWS]. `n` is the cell's sample:
 * the commits for both commit rows (the unknown-lines ones are in the list with a null figure and add nothing to the sum), the merged
 * merge requests for `mrsMerged` and the cycle p50, those with a known size for the size p50. The two p50 cells are `hidden` under
 * `minSampleSize` like every distribution-shaped row; a count is a fact, never hidden (a person with no link has 0).
 */
internal fun CompareData.codeCells(account: String): Map<String, CompareCell> {
    val f = code.figuresOf(account, window, calendar, minSample)
    val cells = LinkedHashMap<String, CompareCell>()
    if (code.commitsRead) {
        cells["commits"] = CompareCell(f.commits.toDouble(), f.commits, hidden = false)
        cells["linesChanged"] = CompareCell((f.linesAdded + f.linesDeleted).toDouble(), f.commits, hidden = false)
    }
    cells["mrsMerged"] = CompareCell(f.mrsMerged.toDouble(), f.mrsMerged, hidden = false)
    cells["mrCycleWorkingDaysP50"] = CompareCell(f.mrCycleWorkingDaysP50, f.mrsMerged, hidden = f.mrsMerged < minSample)
    cells["mrSizeLinesP50"] = CompareCell(f.mrSizeLinesP50, f.mrsSized, hidden = f.mrsSized < minSample)
    cells["reviewsGiven"] = CompareCell(f.reviewsGiven.toDouble(), f.reviewsGiven, hidden = false)
    cells["approvalsGiven"] = CompareCell(f.approvalsGiven.toDouble(), f.approvalsGiven, hidden = false)
    cells["comments"] = CompareCell(f.comments.toDouble(), f.comments, hidden = false)
    return cells
}

/** Compare people's drill views over the code layer and the Report 21 view each one is (`selectCodeDrill` lists exactly that figure). */
internal fun CompareDrillView.codeView(): CodeDrillView? = when (this) {
    CompareDrillView.COMMITS -> CodeDrillView.COMMITS
    CompareDrillView.MRS_MERGED -> CodeDrillView.MRS_MERGED
    CompareDrillView.MR_CYCLE_WORKING_DAYS -> CodeDrillView.MR_CYCLE_WORKING_DAYS
    CompareDrillView.MR_SIZE_LINES -> CodeDrillView.MR_SIZE_LINES
    CompareDrillView.REVIEWS_GIVEN -> CodeDrillView.REVIEWS_GIVEN
    CompareDrillView.APPROVALS_GIVEN -> CodeDrillView.APPROVALS_GIVEN
    CompareDrillView.COMMENTS -> CodeDrillView.COMMENTS
    else -> null
}
