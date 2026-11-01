package ch.nokillswit.reports

import ch.nokillswit.norm.projectKeyFromIssueKey

/*
 * Report 22, Tasks and code (`.claude/docs/reports.md` "Report 22", domain-model A51): the done tasks of a team or domain and the GitLab
 * merge requests that name them by issue key - how long from a task's start to its code merging, which done tasks have no merged merge
 * request, which merged code after the task was done. Read at QUERY time (no DERIVE step, no migration beyond V46's stored candidates);
 * `resolveReportScope` supplies the team check, `meta`, the sprint-relative window and the working calendar over the JIRA star, and the
 * task population is the cycle-time one (`taskFactSlice`, level-0 tasks by `done_at`, the D5 credit team).
 */

/** What [loadTaskCode] read: each done task with its merge requests. */
internal class TaskCodeData(val rows: List<TaskMergeRequests>)

/** What one request read, shared by the report and its drill: the dependencies, the Jira scope, the GitLab sources, the linked tasks. */
internal class TaskCodeRead(val deps: CodeActivityDeps, val scope: ReportScope, val sources: List<CodeSource>, val data: TaskCodeData)

/**
 * The report's whole read, inside the caller's [reportTransaction]: [taskCode] and the drill ([taskCodeItems]) both run THIS, so a figure
 * and its items cannot disagree.
 */
internal suspend fun ReportService.readTaskCode(filter: ReportFilter, nowMs: Long): TaskCodeRead {
    val deps = checkNotNull(codeDeps) { "the tasks and code report is not configured on this service" }
    val scope = resolveReportScope(filter, nowMs)
    val sources = resolveCodeSources(deps.dataSources, null)
    // A sprint period with no sprint to resolve reads nothing: every figure is empty, like the other reports'.
    val data = scope.window?.let { loadTaskCode(deps, filter, scope, sources, it) } ?: TaskCodeData(emptyList())
    return TaskCodeRead(deps, scope, sources, data)
}

internal suspend fun ReportService.taskCode(filter: ReportFilter, nowMs: Long): TaskCodeReport = reportTransaction {
    val read = readTaskCode(filter, nowMs)
    val scope = read.scope
    val minSample = scope.settings.minSampleSize
    val figures = taskCodeFigures(read.data.rows, scope.calendar)
    TaskCodeReport(
        meta = scope.meta,
        sources = codeSourceFreshness(read.deps.dataSources, read.sources),
        tasks = TaskCodeTasks(figures.done, figures.withMergeRequest, figures.withoutMergeRequest, figures.withoutShare),
        codeLeadTimeWorkingDays = buildDistribution(figures.leadTimes, minSample, filter.tailPercent),
        mergedAfterDone = TaskCodeMergedAfterDone(
            figures.mergedAfterDone, figures.mergedAfterDoneShare, buildDistribution(figures.lags, minSample, filter.tailPercent),
        ),
        quality = TaskCodeQuality(figures.neverStarted, figures.onlyUnmerged, figures.ambiguousKeys),
    )
}

/**
 * The read plan, in the task -> merge request direction and bounded by the done tasks: the tasks; their old keys (`issuekey` changes);
 * each task's keys cut to the KNOWN Jira projects ([knownProjectKeys], one definition with Report 21's drill) and resolved to the live
 * issues that name them - current key or old key, across EVERY active Jira source whatever `connectionId` says (it narrows the
 * population only), which decides ambiguity; then the link rows of the keys that name their task alone, charged to the row budget.
 * Without a GitLab source nothing is linked.
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
    if (gitlabIds.isEmpty() || tasks.isEmpty()) return TaskCodeData(linkTasks(tasks, emptyMap(), emptyMap(), emptyMap()))
    val known = knownProjectKeys(deps.dataSources)
    val oldKeys = fetchKeysOfIssues(tasks.map { it.ref })
    val keysOf = tasks.associate { task ->
        task.ref to (setOf(task.issueKey) + oldKeys[task.ref].orEmpty()).filterTo(HashSet()) { projectKeyFromIssueKey(it) in known }
    }
    val everyJira = deps.dataSources.activeJiraProjectKeys().keys
    val lookup = keysOf.values.flatten().toSet()
    val current = fetchLiveIssuesByKey(everyJira, lookup)
    val historic = fetchIssuesByHistoricKey(everyJira, lookup)
    val resolution = lookup.associateWith { current[it].orEmpty() + historic[it].orEmpty() }
    val wanted = tasks.flatMap { task -> keysOf.getValue(task.ref).filter { resolution[it] == setOf(task.ref) } }.toSet()
    val links = fetchTaskLinks(gitlabIds, wanted, CodeRowBudget(deps.maxRows)).groupBy { it.key }
    return TaskCodeData(linkTasks(tasks, keysOf, links, resolution))
}
