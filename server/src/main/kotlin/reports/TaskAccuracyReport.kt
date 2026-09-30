package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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
 * Always empty at USER level. Each entry carries its own distributions (hidden below the minimum sample
 * size, `n` always set) and exclusion counts.
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
)

/** One DONE level-0 task — just what the accuracy ratios need. */
private data class AccuracyTask(
    val creditTeamId: UInt?,
    val accountId: String?,
    val startedAt: Long?,
    val hasWorklogs: Boolean,
    val actualMd: BigDecimal,
    val estimateAtStartMd: BigDecimal?,
    val estimateAtDoneMd: BigDecimal?,
)

/** `actual ÷ estimate` for an estimated task; null = unestimated (an estimate of 0 is stored as null). */
private fun ratioOf(actual: BigDecimal, estimate: BigDecimal?): Double? =
    if (estimate.isEstimate()) actual.toDouble() / estimate!!.toDouble() else null

/** The two ratio lists plus the partition counts of [tasks] (the pure core of the report). */
private data class AccuracyResult(val atStart: List<Double>, val atDone: List<Double>, val excluded: TaskAccuracyExcluded)

private fun accuracyOf(tasks: List<AccuracyTask>): AccuracyResult {
    val worked = tasks.filter { it.hasWorklogs && it.actualMd.signum() > 0 }
    val noWorklogs = tasks.size - worked.size
    val neverStarted = worked.filter { it.startedAt == null }
    val startable = worked.filter { it.startedAt != null }
    val startRatios = startable.mapNotNull { ratioOf(it.actualMd, it.estimateAtStartMd) }
    val doneRatios = worked.mapNotNull { ratioOf(it.actualMd, it.estimateAtDoneMd) }
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
 * the estimate at start (D15) and at done. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.taskEstimationAccuracy(filter: ReportFilter, nowMs: Long): TaskEstimationAccuracyReport =
    suspendTransaction(database) {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val tasks = scope.window?.let { fetchAccuracyTasks(filter, scope.connectionIds, it) }.orEmpty()
        val total = accuracyOf(tasks)
        val groups = orgGroups(filter.level, tasks, { it.creditTeamId }, { it.accountId }).map { (key, rows) ->
            val result = accuracyOf(rows)
            TaskAccuracyGroup(
                teamId = key.teamId,
                accountId = key.accountId,
                label = key.label,
                atStart = buildDistribution(result.atStart, minSample),
                atDone = buildDistribution(result.atDone, minSample),
                excluded = result.excluded,
            )
        }
        TaskEstimationAccuracyReport(
            meta = scope.meta,
            atStart = buildDistribution(total.atStart, minSample),
            atDone = buildDistribution(total.atDone, minSample),
            excluded = total.excluded,
            groups = groups,
        )
    }

private suspend fun fetchAccuracyTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<AccuracyTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(
        t.creditTeamId, t.assigneeAccountIdAtDone, t.startedAt, t.hasWorklogs, t.actualMd, t.estimateAtStartMd, t.estimateAtDoneMd,
    ).where { predicate }.toList().map {
        AccuracyTask(
            creditTeamId = it[t.creditTeamId]?.value,
            accountId = it[t.assigneeAccountIdAtDone],
            startedAt = it[t.startedAt],
            hasWorklogs = it[t.hasWorklogs],
            actualMd = it[t.actualMd],
            estimateAtStartMd = it[t.estimateAtStartMd],
            estimateAtDoneMd = it[t.estimateAtDoneMd],
        )
    }
}
