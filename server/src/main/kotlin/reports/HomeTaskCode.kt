package ch.nokillswit.reports

import org.slf4j.LoggerFactory

/*
 * Home's "done tasks without a merge request" figure (`.claude/docs/reports.md` "Home overview", 1.53.0): per picked team, Report 22's OWN
 * `tasks` block over Home's CURRENT window -- the done tasks and how many have no MERGED merge request naming them. Neutral context: no
 * trend, no verdict, no comparison with the previous window (an empty or unlinked team is not a finding, and a task may need no code).
 *
 * Home defines nothing here. The population is `fetchTaskCodePopulation` (Report 22's own, with `credit_team_id IN (listed)` added to
 * the unit-wide filter where Report 22 has `credit_team_id = team`: the same rows per team), the links are `linkTasksToMergeRequests`
 * (Report 22's four-step read, key resolution included) and the figures are `taskCodeFigures` + `TaskMergeRequests.hasMerged`, so a
 * team's figure equals `GET /reports/task-code?teamId=<team>&from=<window.from>&to=<window.to>` (`ReportHomeTaskCodeTest`). ONE read
 * serves every listed team: the statements depend on the Jira connections, not on how many teams are listed, because a key is resolved
 * per key (never per team) and the link rows are looked up by key.
 *
 * Its row budget is its OWN `CodeRowBudget(reports.codeMaxRows)`: over it the section keeps the rest and this figure is `null` (D1:
 * degrade, never the page); the drill is the plain `400`, like the `CODE_*` ones.
 */

private val log = LoggerFactory.getLogger("ch.nokillswit.reports.HomeTaskCode")

/**
 * The linked done tasks (Report 22's rows) of the [teams] over Home's current window. A throw of [CodeRowBudgetExceededException]
 * (`BadRequestException`, Report 22's message) is for the caller to degrade or let through.
 */
private suspend fun HomeContext.taskCodeRows(
    deps: CodeActivityDeps,
    sources: List<CodeSource>,
    teams: Collection<UInt>,
): List<TaskMergeRequests> {
    val window = windows.current.let { it.fromMs to it.toMs }
    val tasks = fetchTaskCodePopulation(filter, connectionIds, window, creditTeams = teams)
    // commits = false: Home does not print the figure, so its combined read skips the commit statement (and its budget share).
    return linkTasksToMergeRequests(deps, tasks, sources, commits = false)
}

/**
 * Report 22's `tasks` for each of the [listed] teams, from one read; every listed team is a key (zero tasks are `0 / 0`, no share).
 * `null` when the read was over the row budget.
 */
internal suspend fun HomeContext.readHomeTaskCode(
    deps: CodeActivityDeps,
    sources: List<CodeSource>,
    listed: Collection<UInt>,
): Map<UInt, HomeTaskCodeTasks>? {
    val rows = try {
        taskCodeRows(deps, sources, listed)
    } catch (_: CodeRowBudgetExceededException) {
        // Not an audit event (observability.md "Code-read refusals"): one INFO with the budget and the team ids, no names.
        log.info("Home's tasks-and-code figure is over reports.codeMaxRows ({}): teams {} listed without it", deps.maxRows, listed.sorted())
        return null
    }
    val byTeam = rows.groupBy { it.task.creditTeamId }
    return listed.associateWith { team ->
        val figures = taskCodeFigures(byTeam[team].orEmpty(), calendar)
        HomeTaskCodeTasks(figures.done, figures.withMergeRequest, figures.withoutMergeRequest, figures.withoutShare)
    }
}

/**
 * The done tasks without a merged merge request of [teamId] over the CURRENT window (`GET /home/items?view=CODE_TASKS_WITHOUT_MR`):
 * Report 22's own `TASKS_WITHOUT_MR` selection and list over the same rows, so the list is the figure's `withoutMergeRequest` tasks and
 * equals `GET /task-code/items?view=TASKS_WITHOUT_MR` for the same team and window. An item is a TASK; its `linkedKeys` are the task's
 * UNMERGED merge requests (`<project path>!<iid>`). No active GitLab source (or no code layer): an empty list, like the `CODE_*` views;
 * over `reports.codeMaxRows` Report 22's plain 400.
 */
internal suspend fun ReportService.homeTaskCodeItems(ctx: HomeContext, teamId: UInt): ReportDrillItems {
    val deps = codeDeps
    val sources = deps?.let { resolveCodeSources(it.dataSources, null) }.orEmpty()
    if (deps == null || sources.isEmpty()) return drillItems(ctx.meta(), ReportDrillBar(0, null, null, null), emptyList())
    val view = TaskCodeDrillView.TASKS_WITHOUT_MR
    val rows = ctx.taskCodeRows(deps, sources, listOf(teamId))
    val selection = selectTaskCodeDrill(rows, TaskCodeDrillQuery(view, null), ctx.calendar, ctx.minSample, DEFAULT_TAIL_PERCENT)
    return taskCodeDrillItems(ctx.meta(), view, selection)
}
