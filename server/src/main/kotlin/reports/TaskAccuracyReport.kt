package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * The DONE level-0 tasks a task-accuracy read could NOT turn into a ratio, each in exactly ONE bucket
 * (`.claude/docs/measures.md` "Reports 3, 4, 5"): [noWorklogs] (D14 — checked first, it applies to both
 * views; a task with `has_worklogs` true but `actual_md` 0.00 — a minute or two logged — is counted here too,
 * mirroring the epics' `noActual`, never as a ratio of exactly 0), then [neverStarted] (no `started_at`, so no
 * estimate at start — `atStart` only), then
 * [unestimatedAtStart] (no estimate at start, incl. estimated-late) / [unestimatedAtDone] (no estimate at
 * done — `atDone` only). So `atStart.n + noWorklogs + neverStarted + unestimatedAtStart == population` and
 * `atDone.n + noWorklogs + unestimatedAtDone == population`.
 */
@Serializable
data class TaskAccuracyExcluded(
    val population: Int,
    val noWorklogs: Int,
    val neverStarted: Int,
    val unestimatedAtStart: Int,
    val unestimatedAtDone: Int,
)

/**
 * One org-drill entry: a credit team at UNIT level (`teamId` null = UNASSIGNED, [accountId] null) or an
 * assignee at done at TEAM level ([accountId]/[label] set; a null [accountId] is the unassigned bucket).
 * At USER level one entry per picked account (an empty one when it has no task). Each entry carries its own distributions
 * (hidden below the minimum sample size, `n` always set) and exclusion counts.
 */
@Serializable
data class TaskAccuracyGroup(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val atStart: Distribution,
    val atDone: Distribution,
    val excluded: TaskAccuracyExcluded,
)

@Serializable
data class TaskEstimationAccuracyReport(
    val meta: ReportMeta,
    /** `actual_md ÷ estimate at start` (D15) — the primary view. */
    val atStart: Distribution,
    /** `actual_md ÷ estimate at done` — the second view. */
    val atDone: Distribution,
    val excluded: TaskAccuracyExcluded,
    val groups: List<TaskAccuracyGroup>,
    /** [AccuracyMeasures] over the ratios [atStart] summarises (the union at USER level); null while it is hidden. */
    val accuracyAtStart: AccuracyMeasures?,
    /** [AccuracyMeasures] over the ratios [atDone] summarises (the union at USER level); null while it is hidden. */
    val accuracyAtDone: AccuracyMeasures?,
)

/**
 * One DONE level-0 task — just what the accuracy ratios need (and, for the drill, which issue it is). Task estimates (Report 19)
 * reads the same row and fetch for the estimates themselves.
 */
internal data class AccuracyTask(
    val connectionId: UInt,
    val issueId: Long,
    val doneAt: Long,
    val creditTeamId: UInt?,
    val accountId: String?,
    val startedAt: Long?,
    val hasWorklogs: Boolean,
    val actualMd: BigDecimal,
    val estimateAtStartMd: BigDecimal?,
    val estimateAtDoneMd: BigDecimal?,
)

/**
 * `actual ÷ estimate` when there is actual cost and a positive estimate; null = excluded (no actual cost, or unestimated — an estimate of
 * 0 is stored as null). The ONE ratio rule: Task accuracy (callers already keep only worked tasks) and Epic accuracy both read it.
 */
internal fun actualOverEstimate(actual: BigDecimal, estimate: BigDecimal?): Double? =
    if (actual.signum() > 0 && estimate.isEstimate()) actual.toDouble() / estimate!!.toDouble() else null

/** A task with logged time (`has_worklogs` and an `actual_md` above 0.00 — D14): the only ones that have a ratio at all. */
internal fun AccuracyTask.isWorked(): Boolean = hasWorklogs && actualMd.signum() > 0

/**
 * The task's `actual ÷ estimate at start` (D15): null = in no bar of the `atStart` view — not worked, never started, or
 * unestimated at start. The report's partition AND the drill both read this one function.
 */
internal fun ratioAtStartOf(task: AccuracyTask): Double? =
    if (task.isWorked() && task.startedAt != null) actualOverEstimate(task.actualMd, task.estimateAtStartMd) else null

/** The task's `actual ÷ estimate at done`: null = in no bar of the `atDone` view (not worked, or unestimated at done). */
internal fun ratioAtDoneOf(task: AccuracyTask): Double? =
    if (task.isWorked()) actualOverEstimate(task.actualMd, task.estimateAtDoneMd) else null

/** The two ratio lists plus the partition counts of [tasks] (the pure core of the report). */
private data class AccuracyResult(val atStart: List<Double>, val atDone: List<Double>, val excluded: TaskAccuracyExcluded)

private fun accuracyOf(tasks: List<AccuracyTask>): AccuracyResult {
    val worked = tasks.filter { it.isWorked() }
    val noWorklogs = tasks.size - worked.size
    val neverStarted = worked.filter { it.startedAt == null }
    val startable = worked.filter { it.startedAt != null }
    val startRatios = startable.mapNotNull(::ratioAtStartOf)
    val doneRatios = worked.mapNotNull(::ratioAtDoneOf)
    return AccuracyResult(
        atStart = startRatios,
        atDone = doneRatios,
        excluded = TaskAccuracyExcluded(
            population = tasks.size,
            noWorklogs = noWorklogs,
            neverStarted = neverStarted.size,
            unestimatedAtStart = startable.size - startRatios.size,
            unestimatedAtDone = worked.size - doneRatios.size,
        ),
    )
}

/**
 * `GET /api/v1/reports/task-estimation-accuracy` (v0.3.0 M4 commit 12, Report 3, `.claude/docs/measures.md`
 * "Reports 3, 4, 5"): DONE level-0 tasks by `done_at`, `actual_md ÷ estimate` as a [Distribution] against
 * the estimate at start (D15) and at done, plus the [AccuracyMeasures] of each view. At USER level the report takes several
 * `accountId`s: both distributions read the union and carry a [Distribution.byUser] split of every histogram bucket, `groups`
 * lists each pick, and the measures and the exclusion accounting stay the union. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.taskEstimationAccuracy(filter: ReportFilter, nowMs: Long): TaskEstimationAccuracyReport =
    reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val tasks = scope.window?.let { fetchAccuracyTasks(filter, scope.connectionIds, it) }.orEmpty()
        val total = accuracyOf(tasks)
        val picked = pickedAccounts(filter)
        val distributions = PickedDistributions<AccuracyTask>(picked, minSample, filter.tailPercent) { it.accountId }
        val groupRows = if (filter.level == ReportLevel.USER) {
            pickedGroups(picked, tasks) { it.accountId }
        } else {
            orgGroups(filter.level, tasks, { it.creditTeamId }, { it.accountId })
        }
        TaskEstimationAccuracyReport(
            meta = scope.meta,
            atStart = distributions.drawn(tasks, { accuracyOf(it).atStart }, total.atStart),
            atDone = distributions.drawn(tasks, { accuracyOf(it).atDone }, total.atDone),
            excluded = total.excluded,
            groups = groupRows.map { (key, rows) ->
                val result = accuracyOf(rows)
                TaskAccuracyGroup(
                    teamId = key.teamId,
                    accountId = key.accountId,
                    label = key.label,
                    atStart = distributions.plain(result.atStart),
                    atDone = distributions.plain(result.atDone),
                    excluded = result.excluded,
                )
            },
            accuracyAtStart = accuracyMeasures(total.atStart, minSample),
            accuracyAtDone = accuracyMeasures(total.atDone, minSample),
        )
    }

/** The columns an [AccuracyTask] is read from (`fact_task_delivery`). */
internal val ACCURACY_COLUMNS: List<Expression<*>> = MetricsTables.FactTaskDelivery.let { t ->
    listOf(
        t.connectionId, t.issueId, t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, t.startedAt, t.hasWorklogs, t.actualMd,
        t.estimateAtStartMd, t.estimateAtDoneMd,
    )
}

/** One [AccuracyTask] from a row that selected [ACCURACY_COLUMNS]. */
internal fun accuracyTaskOf(row: ResultRow): AccuracyTask {
    val t = MetricsTables.FactTaskDelivery
    return AccuracyTask(
        connectionId = row[t.connectionId].value,
        issueId = row[t.issueId],
        doneAt = row[t.doneAt]!!,
        creditTeamId = row[t.creditTeamId]?.value,
        accountId = row[t.assigneeAccountIdAtDone],
        startedAt = row[t.startedAt],
        hasWorklogs = row[t.hasWorklogs],
        actualMd = row[t.actualMd],
        estimateAtStartMd = row[t.estimateAtStartMd],
        estimateAtDoneMd = row[t.estimateAtDoneMd],
    )
}

internal suspend fun fetchAccuracyTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<AccuracyTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(ACCURACY_COLUMNS).where { predicate }.map(::accuracyTaskOf).toList()
}
