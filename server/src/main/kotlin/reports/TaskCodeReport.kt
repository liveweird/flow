package ch.nokillswit.reports

import ch.nokillswit.norm.projectKeyFromIssueKey

/*
 * Report 22, Tasks and code (`.claude/docs/reports.md` "Report 22", domain-model A51): the done tasks of a team or domain and the GitLab
 * merge requests that name them by issue key - how long from a task's start to its code merging, which done tasks have no merged merge
 * request, which merged code after the task was done. Read at QUERY time (no DERIVE step, no migration beyond V46's stored candidates);
 * `resolveReportScope` supplies the team check, `meta`, the sprint-relative window and the working calendar over the JIRA star, and the
 * task population is the cycle-time one (`taskFactSlice`, level-0 tasks by `done_at`, the D5 credit team).
 */

/** What [loadTaskCode] read: each done task with its merge requests, and the connection-wide key quality. */
internal class TaskCodeData(val rows: List<TaskMergeRequests>, val keys: KeyQuality)

internal suspend fun ReportService.taskCode(filter: ReportFilter, nowMs: Long): TaskCodeReport = reportTransaction {
    val deps = checkNotNull(codeDeps) { "the tasks and code report is not configured on this service" }
    val scope = resolveReportScope(filter, nowMs)
    val minSample = scope.settings.minSampleSize
    val sources = resolveCodeSources(deps.dataSources, null)
    // A sprint period with no sprint to resolve reads nothing: every figure is empty, like the other reports'.
    val data = scope.window?.let { loadTaskCode(deps, filter, scope, sources, it) } ?: TaskCodeData(emptyList(), KeyQuality(0, 0))
    val figures = taskCodeFigures(data.rows, scope.calendar)
    TaskCodeReport(
        meta = scope.meta,
        sources = codeSourceFreshness(deps.dataSources, sources),
        tasks = TaskCodeTasks(figures.done, figures.withMergeRequest, figures.withoutMergeRequest, figures.withoutShare),
        codeLeadTimeWorkingDays = buildDistribution(figures.leadTimes, minSample, filter.tailPercent),
        mergedAfterDone = TaskCodeMergedAfterDone(
            figures.mergedAfterDone, figures.mergedAfterDoneShare, buildDistribution(figures.lags, minSample, filter.tailPercent),
        ),
        quality = TaskCodeQuality(figures.neverStarted, figures.onlyUnmerged, data.keys.unresolved, data.keys.ambiguous),
    )
}

/**
 * The read plan, in the task -> merge request direction: the done tasks; what the `issuekey` changes say (old keys); the distinct
 * candidate keys of the live merge requests within the known Jira projects, each resolved to the live issues it names (current key or
 * old key, across every Jira source of the request) - which gives the connection-wide quality and decides ambiguity; then the link
 * rows of ONLY the keys the done tasks have, charged to the row budget. Without a GitLab source nothing is linked.
 */
private suspend fun ReportService.loadTaskCode(
    deps: CodeActivityDeps,
    filter: ReportFilter,
    scope: ReportScope,
    sources: List<CodeSource>,
    window: Pair<Long, Long>,
): TaskCodeData {
    val tasks = fetchTaskCodePopulation(filter, scope.connectionIds, window)
    val gitlabIds = sources.map { it.connectionId }
    if (gitlabIds.isEmpty()) return TaskCodeData(linkTasks(tasks, emptyMap(), emptyMap(), emptyMap()), KeyQuality(0, 0))
    val history = fetchIssueKeyHistory(scope.connectionIds)
    val known = knownProjectsOf(deps.dataSources, scope.connectionIds, history)
    val candidates = fetchCandidateKeys(gitlabIds).filterTo(HashSet()) { projectKeyFromIssueKey(it) in known }
    val current = fetchLiveIssuesByKey(scope.connectionIds, candidates)
    val resolution = candidates.associateWith { current[it].orEmpty() + history.issuesByKey[it].orEmpty() }
    val keysOf = tasks.associate { it.ref to (setOf(it.issueKey) + history.keysByIssue[it.ref].orEmpty()) }
    val wanted = keysOf.values.flatten().filterTo(HashSet()) { it in candidates }
    val links = fetchTaskLinks(gitlabIds, wanted, CodeRowBudget(deps.maxRows)).groupBy { it.key }
    return TaskCodeData(linkTasks(tasks, keysOf, links, resolution), keyQuality(candidates, resolution))
}
