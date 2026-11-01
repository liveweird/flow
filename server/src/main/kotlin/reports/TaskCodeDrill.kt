package ch.nokillswit.reports

import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.http.Parameters
import io.ktor.server.plugins.BadRequestException

/*
 * `GET /api/v1/reports/task-code/items` (Report 22's drill, `.claude/docs/reports.md` "Report 22"): the done tasks behind ONE figure of the
 * report. Every view runs the report's OWN read ([readTaskCode], shared with `taskCode`) and the report's OWN per-task properties
 * ([TaskMergeRequests.hasMerged], `mergedAfterDone`, `codeLeadWorkingDays`, `mergeLagWorkingDays`) and, for the two histograms, the one
 * bucket rule ([distributionDrillSelection]), so a figure and its items cannot disagree. An item is a TASK (its Jira key and summary);
 * its `linkedKeys` are the merge requests linked to it, `<project path>!<iid>` (all its merged ones, not only the last merge a
 * figure measured).
 */

/**
 * The figure a drill lists the tasks of. `TASKS_WITH_MR` / `TASKS_WITHOUT_MR` are the report's `tasks.withMergeRequest` /
 * `withoutMergeRequest`, `MERGED_AFTER_DONE` its `mergedAfterDone.count`; the two histogram views take a REQUIRED `bucketIndex` into that
 * distribution's `histogram`: `CODE_LEAD_TIME` the tasks behind a bar of `codeLeadTimeWorkingDays`, `MERGED_AFTER_DONE_LAG` of
 * `mergedAfterDone.lagWorkingDays`.
 */
enum class TaskCodeDrillView(val histogram: Boolean = false) {
    TASKS_WITH_MR,
    TASKS_WITHOUT_MR,
    CODE_LEAD_TIME(histogram = true),
    MERGED_AFTER_DONE,
    MERGED_AFTER_DONE_LAG(histogram = true),
}

/** The figure's identity, parsed (every `400` that needs no data is thrown by [taskCodeDrillQuery]). */
internal class TaskCodeDrillQuery(val view: TaskCodeDrillView, val bucketIndex: Int?)

/** Parses `view` and the bar's `bucketIndex`: required on the histogram views, a `400` on any other. */
internal fun Parameters.taskCodeDrillQuery(): TaskCodeDrillQuery {
    val view = requireEnum<TaskCodeDrillView>("view")
    val bucketIndex = optionalBucketIndex()
    if (view.histogram && bucketIndex == null) throw BadRequestException("bucketIndex is required for the $view view")
    if (!view.histogram && bucketIndex != null) throw BadRequestException("bucketIndex applies to the histogram views only")
    return TaskCodeDrillQuery(view, bucketIndex)
}

/** One task behind a figure and the figure's own value for it (working days; null where the view has none, or the task has none). */
internal class TaskDrillRow(val linked: TaskMergeRequests, val figure: Double?) {
    val drillRow: DrillRow
        get() {
            val task = linked.task
            val ref = task.ref
            return DrillRow(ref.connectionId, ref.issueId, DrillItemKind.TASK, task.accountId, task.creditTeamId, task.doneAt, figure)
        }
}

private fun plain(rows: List<TaskMergeRequests>, keep: (TaskMergeRequests) -> Boolean, figure: (TaskMergeRequests) -> Double?) =
    rows.filter(keep).map { TaskDrillRow(it, figure(it)) }.let { DistributionSelection(ReportDrillBar(it.size, null, null, null), it) }

/**
 * The tasks behind the figure [query] names and the bar recomputed over exactly them. `figure` is the view's working days: the code lead
 * time for the with-a-merge-request and lead-time views, the lag for the merged-after-done ones, none for the without view.
 */
internal fun selectTaskCodeDrill(
    rows: List<TaskMergeRequests>,
    query: TaskCodeDrillQuery,
    calendar: WorkingCalendar,
    minSample: Int,
    tailPercent: Int,
): DistributionSelection<TaskDrillRow> {
    val lead = { row: TaskMergeRequests -> row.codeLeadWorkingDays(calendar) }
    val lag = { row: TaskMergeRequests -> row.mergeLagWorkingDays(calendar) }
    fun histogram(valueOf: (TaskMergeRequests) -> Double?) = distributionDrillSelection(
        rows, minSample, tailPercent, checkNotNull(query.bucketIndex), segment = null, valueOf = valueOf,
        drillRow = { row, value -> TaskDrillRow(row, value) }, accountOf = { null },
    )
    return when (query.view) {
        TaskCodeDrillView.TASKS_WITH_MR -> plain(rows, { it.hasMerged }, lead)
        TaskCodeDrillView.TASKS_WITHOUT_MR -> plain(rows, { !it.hasMerged }, { null })
        TaskCodeDrillView.MERGED_AFTER_DONE -> plain(rows, { it.mergedAfterDone }, lag)
        TaskCodeDrillView.CODE_LEAD_TIME -> histogram(lead)
        TaskCodeDrillView.MERGED_AFTER_DONE_LAG -> histogram(lag)
    }
}

/**
 * The merge requests an item lists for [view]: the without view lists the task's linked merge requests that are NOT merged (the open or
 * closed ones that kept it out of "with"; none for a task nothing names), every other view the MERGED ones (the merge requests the figure
 * counted).
 */
private fun TaskMergeRequests.listedMrs(view: TaskCodeDrillView): List<CodeMrRef> =
    if (view == TaskCodeDrillView.TASKS_WITHOUT_MR) unmergedMrs else mergedMrs

/**
 * The items behind one figure of Report 22 (`GET /api/v1/reports/task-code/items`). The parent's parameters decide the scope (a team or a
 * domain; no user level, `accountId` is a `400` before this runs).
 */
internal suspend fun ReportService.taskCodeItems(filter: ReportFilter, query: TaskCodeDrillQuery, nowMs: Long): ReportDrillItems =
    reportTransaction {
        val read = readTaskCode(filter, nowMs)
        val scope = read.scope
        val selection = selectTaskCodeDrill(read.data.rows, query, scope.calendar, scope.settings.minSampleSize, filter.tailPercent)
        val ordered = selection.rows.associateBy { it.drillRow.connectionId to it.drillRow.issueId }
        val listed = selection.rows.map { it.drillRow }.sortedWith(DRILL_ORDER).take(DRILL_MAX_ITEMS)
        // The GitLab keys are fetched for the LISTED tasks' merge requests only (labels, like every drill).
        val mrsOf = listed.associate { row ->
            val key = row.connectionId to row.issueId
            key to ordered.getValue(key).linked.listedMrs(query.view)
        }
        val keys = fetchMergeRequestKeys(mrsOf.values.flatten())
        val linkedKeys = mrsOf.mapValues { (_, refs) -> refs.map { keys.getValue(it) }.sorted() }
        listedDrillItems(scope.meta, selection.bar, listed, total = selection.rows.size, linkedKeys = linkedKeys)
    }
