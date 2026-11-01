package ch.nokillswit.reports

/*
 * The Home overview's Code section (`.claude/docs/reports.md` "Home overview" and "Report 21"): per picked team, four figures of Report 21
 * -- merged merge requests, the merge-request cycle p50, the open ones now with their age p50, merged without review -- each computed by
 * Report 21's OWN kernels (`flowFigures`, `openFigures`, `CodeFlowFigures.distribution`), so a figure here is the figure
 * `GET /reports/code-activity?teamId=…&from&to` prints for the same team and window. Home defines no figure of its own.
 *
 * ONE combined read per request, not one Report 21 read per team and window: the union of the listed teams' rosters (both windows and
 * now) is the key scope, the merge requests and their review summaries are read once over `[previous.from, current.to)`, and each
 * (team, window) then attributes by anchor with `CodeSelection(teamId, null)` -- a row is a team's only if its author held that team's
 * membership at the anchor, which puts the author in the team's roster, so the result equals the one-team read. Home needs no commits,
 * no actor events and no review requests (`CodeRowReads(false, false, false)`). It takes no `CodeReadPermits` permit (it never reads
 * `teamId=0`); `reports.codeMaxRows` is its heap bound, and over it the section degrades (D1) instead of failing the page.
 */

/** What Home read of the code layer: the [sources] (empty: none active), the listed teams' [rosters], who is [linked] and the [data]. */
internal class HomeCodeRead(
    val sources: List<CodeSource>,
    val freshness: List<CodeActivitySource>,
    val rosters: Map<UInt, Set<String>>,
    /** The accounts of the rosters that have at least one LINKED identity key on a source's host. */
    val linked: Set<String>,
    /** Null while there is no source or the read was over the row budget. */
    val data: CodeActivityData?,
    val overBudget: Boolean,
) {
    companion object {
        val NONE = HomeCodeRead(emptyList(), emptyList(), emptyMap(), emptySet(), null, false)
    }
}

/** Reads the code layer for the [listed] teams inside Home's transaction; [HomeCodeRead.NONE] without a configured or active source. */
internal suspend fun ReportService.readHomeCode(ctx: HomeContext, listed: Collection<UInt>): HomeCodeRead {
    val deps = codeDeps ?: return HomeCodeRead.NONE
    val sources = resolveCodeSources(deps.dataSources, null)
    if (sources.isEmpty()) return HomeCodeRead.NONE
    val freshness = codeSourceFreshness(deps.dataSources, sources)
    val both = ctx.windows.both
    // Report 21's roster: who belonged to a team at some point of the two windows, plus who belongs to it now (the open merge requests).
    val rosters = listed.associateWith { readTeamRoster(it, both.first, both.second) + readTeamRoster(it, ctx.nowMs, ctx.nowMs + 1) }
    val union = rosters.values.flatten().toSet()
    val linked = linkedKeysOfAccounts(deps.resolver, sources, union).linkedAccounts
    val request = CodeRowsRequest(
        sources, union, CodeSelection(null, null), both, ctx.nowMs, ctx.calendar, ctx.zone, ctx.minSample,
        CodeRowReads(commits = false, events = false, quality = false),
    )
    val data = try {
        loadCodeRows(deps.resolver, request, CodeRowBudget(deps.maxRows))
    } catch (_: CodeRowBudgetExceededException) {
        null
    }
    return HomeCodeRead(sources, freshness, rosters, linked, data, overBudget = data == null)
}

/** The cycle p50 of the merged merge requests, literally Report 21's `cycleWorkingDays` distribution read as a figure. */
private fun CodeFlowFigures.cycleP50(minSample: Int): Measured {
    val cycle = distribution(minSample, DEFAULT_TAIL_PERCENT, PickedAccounts(emptyList(), emptyMap()), MergedMrFigure::cycleWorkingDays)
    return Measured(cycle.p50, cycle.n.toInt(), cycle.hidden)
}

/**
 * Merged without review: [Measured.numerator] is Report 21's count and [Measured.n] the merged merge requests whose details were read
 * (the share's denominator); the share is hidden under the minimum sample (D2) -- the count never is.
 */
private fun CodeFlowFigures.withoutReview(minSample: Int): Measured {
    val read = mrs.count { it.review != null }
    return Measured(mergedWithoutReview.share.takeIf { read >= minSample }, read, read < minSample, mergedWithoutReview.count)
}

/** A window's flow figures of [team] (Report 21's `flowFigures` over the shared rows) with the clock inputs of Home's context. */
private fun HomeContext.flowOf(data: CodeActivityData, team: UInt, span: HomeSpan): CodeFlowFigures =
    kernelContext(data, team, span.fromMs to span.toMs).flowFigures(data.mrs, data.summaries)

private fun HomeContext.kernelContext(data: CodeActivityData, team: UInt, window: Pair<Long, Long>) =
    CodeKernelContext(CodeSelection(team, null), data.context.attribution, window, nowMs, calendar, zone, minSample)

/** The Code figures of [team], or null while the section is absent (no source, or over the row budget). */
internal fun HomeContext.homeTeamCode(read: HomeCodeRead, team: UInt): HomeTeamCode? {
    val data = read.data ?: return null
    val now = flowOf(data, team, windows.current)
    val before = flowOf(data, team, windows.previous)
    val open = kernelContext(data, team, windows.current.fromMs to windows.current.toMs).openFigures(data.mrs)
    fun trended(measure: HomeMeasure, previous: Measured, current: Measured) = trendedOf(measure, previous, current, minSample)
    return HomeTeamCode(
        linkedMembers = read.rosters.getValue(team).count { it in read.linked },
        merged = trended(HomeMeasure.CODE_MERGED, countFigure(before.merged), countFigure(now.merged)),
        cycleP50 = trended(HomeMeasure.CODE_CYCLE, before.cycleP50(minSample), now.cycleP50(minSample)),
        openCount = countFigure(open.count).toFigure(),
        openDrafts = open.drafts,
        openAgeP50 = Measured(open.ageP50, open.count, open.ageHidden).toFigure(),
        mergedWithoutReview = trended(HomeMeasure.CODE_WITHOUT_REVIEW, before.withoutReview(minSample), now.withoutReview(minSample)),
    )
}

/** Report 21's drill view behind a Code figure: the item SET of the figure (the cycle p50 lists the merged ones, the age p50 open). */
internal fun HomeDrillView.codeView(): CodeDrillView = when (this) {
    HomeDrillView.CODE_MERGED -> CodeDrillView.MRS_MERGED
    HomeDrillView.CODE_OPEN -> CodeDrillView.OPEN_MRS_WAITING
    HomeDrillView.CODE_MERGED_WITHOUT_REVIEW -> CodeDrillView.MERGED_WITHOUT_REVIEW
    else -> error("$this is no code view")
}

/**
 * The merge requests behind one Code figure of [teamId] over the CURRENT window (`GET /home/items`, `CODE_*`): the trimmed plan with the
 * one team's roster, then Report 21's own `selectCodeDrill` and `codeDrillItems`, so a list is exactly what the figure counted and equals
 * `GET /code-activity/items` for the same team and window. No source (or no code layer wired): an empty list. Over `reports.codeMaxRows`
 * it is Report 21's plain 400 (a user action with a clear message; only the overview degrades). No permit, like the overview.
 */
internal suspend fun ReportService.homeCodeItems(ctx: HomeContext, teamId: UInt, view: HomeDrillView): ReportDrillItems {
    val deps = codeDeps
    val sources = deps?.let { resolveCodeSources(it.dataSources, null) }.orEmpty()
    if (deps == null || sources.isEmpty()) return drillItems(ctx.meta(), ReportDrillBar(0, null, null, null), emptyList())
    val window = ctx.windows.current.let { it.fromMs to it.toMs }
    val roster = readTeamRoster(teamId, window.first, window.second) + readTeamRoster(teamId, ctx.nowMs, ctx.nowMs + 1)
    val request = CodeRowsRequest(
        sources, roster, CodeSelection(teamId, null), window, ctx.nowMs, ctx.calendar, ctx.zone, ctx.minSample,
        CodeRowReads(commits = false, events = false, quality = false),
    )
    val data = loadCodeRows(deps.resolver, request, CodeRowBudget(deps.maxRows))
    val selection = data.context.selectCodeDrill(CodeDrillQuery(view.codeView(), null, null, null), null, DEFAULT_TAIL_PERCENT, data)
    return codeDrillItems(ctx.meta(), teamId, selection, data.labels)
}
