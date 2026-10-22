package ch.nokillswit.reports

import kotlinx.serialization.Serializable

/**
 * The DONE level-0 tasks a task-estimates read could NOT draw, each in exactly one bucket per view
 * (`.claude/docs/measures.md` "Report 19"; a started task with no estimate is in both views' unestimated buckets):
 * [neverStarted] (no `started_at`, so no estimate at start — `atStart` only, checked first as [ratioAtStartOf] does), then
 * [unestimatedAtStart] (no estimate at start, incl. estimated-late) / [unestimatedAtDone] (no estimate at done — `atDone` only). There
 * is no `noWorklogs` bucket: the measure is the estimate itself, not a ratio to the logged time, so a task without worklogs is drawn.
 * So `atStart.n + neverStarted + unestimatedAtStart == population` and `atDone.n + unestimatedAtDone == population`.
 */
@Serializable
data class TaskEstimatesExcluded(
    val population: Int,
    val neverStarted: Int,
    val unestimatedAtStart: Int,
    val unestimatedAtDone: Int,
)

/** One org-drill entry, the shape of [TaskAccuracyGroup] with the two estimate distributions (hidden below the minimum sample size). */
@Serializable
data class TaskEstimatesGroup(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val atDone: Distribution,
    val atStart: Distribution,
    val excluded: TaskEstimatesExcluded,
)

@Serializable
data class TaskEstimatesReport(
    val meta: ReportMeta,
    /** The estimate at done, in MD — the primary view. */
    val atDone: Distribution,
    /** The estimate at start, in MD — the second view. */
    val atStart: Distribution,
    val excluded: TaskEstimatesExcluded,
    val groups: List<TaskEstimatesGroup>,
)

/** The task's estimate at done, in MD: null = in no bar of the `atDone` view (unestimated at done; 0 is stored null). */
internal fun estimateAtDoneOf(task: AccuracyTask): Double? = task.estimateAtDoneMd.takeIf { it.isEstimate() }?.toDouble()

/** The task's estimate at start, in MD: null = in no bar of the `atStart` view — never started, or unestimated at start. */
internal fun estimateAtStartOf(task: AccuracyTask): Double? =
    if (task.startedAt == null) null else task.estimateAtStartMd.takeIf { it.isEstimate() }?.toDouble()

/** The two estimate lists plus the partition counts of [tasks] (the pure core of the report). */
private data class EstimatesResult(val atDone: List<Double>, val atStart: List<Double>, val excluded: TaskEstimatesExcluded)

private fun estimatesOf(tasks: List<AccuracyTask>): EstimatesResult {
    val atDone = tasks.mapNotNull(::estimateAtDoneOf)
    val atStart = tasks.mapNotNull(::estimateAtStartOf)
    val neverStarted = tasks.count { it.startedAt == null }
    return EstimatesResult(
        atDone = atDone,
        atStart = atStart,
        excluded = TaskEstimatesExcluded(
            population = tasks.size,
            neverStarted = neverStarted,
            unestimatedAtStart = tasks.size - neverStarted - atStart.size,
            unestimatedAtDone = tasks.size - atDone.size,
        ),
    )
}

/**
 * `GET /api/v1/reports/task-estimates` (Report 19, `.claude/docs/measures.md` "Report 19"): the distribution of the DONE level-0
 * tasks' estimates in MD — at done (primary) and at start — by `done_at`, in fixed 0.5-MD bars. It reuses Task accuracy's fetch
 * ([fetchAccuracyTasks]) and row, but not its population: a task without worklogs is drawn. At USER level the report takes several
 * `accountId`s: both distributions read the union and carry a [Distribution.byUser] split, `groups` lists each pick and the
 * exclusion accounting stays the union. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.taskEstimates(filter: ReportFilter, nowMs: Long): TaskEstimatesReport = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val tasks = scope.window?.let { fetchAccuracyTasks(filter, scope.connectionIds, it) }.orEmpty()
    val total = estimatesOf(tasks)
    val picked = pickedAccounts(filter)
    val minSample = scope.settings.minSampleSize
    val distributions = PickedDistributions<AccuracyTask>(picked, minSample, filter.tailPercent, FIXED_BUCKET_STEP) { it.accountId }
    val groupRows = if (filter.level == ReportLevel.USER) {
        pickedGroups(picked, tasks) { it.accountId }
    } else {
        orgGroups(filter.level, tasks, { it.creditTeamId }, { it.accountId })
    }
    TaskEstimatesReport(
        meta = scope.meta,
        atDone = distributions.drawn(tasks, { estimatesOf(it).atDone }, total.atDone),
        atStart = distributions.drawn(tasks, { estimatesOf(it).atStart }, total.atStart),
        excluded = total.excluded,
        groups = groupRows.map { (key, rows) ->
            val result = estimatesOf(rows)
            TaskEstimatesGroup(
                teamId = key.teamId,
                accountId = key.accountId,
                label = key.label,
                atDone = distributions.plain(result.atDone),
                atStart = distributions.plain(result.atStart),
                excluded = result.excluded,
            )
        },
    )
}
