package ch.nokillswit.reports

import kotlinx.serialization.Serializable

/**
 * The DONE level-0 tasks a reported-time-ratio read could NOT turn into a ratio, each in exactly ONE bucket
 * (`.claude/docs/measures.md` Report 8), checked in this order: [noWorklogs] (D14 — no worklogs, or `actual_md`
 * of 0.00), then [neverStarted] (no `started_at`, so no cycle), then [zeroCycle] (`cycle_working_days = 0` — a
 * same-day or weekend cycle has nothing to divide by). So `ratio.n + noWorklogs + neverStarted + zeroCycle ==
 * population`.
 */
@Serializable
data class ReportedTimeExcluded(val population: Int, val noWorklogs: Int, val neverStarted: Int, val zeroCycle: Int)

/**
 * The DONE level-0 tasks a flow-efficiency read could not measure (`.claude/docs/measures.md` "Flow efficiency
 * (A18)"), each in exactly ONE bucket, in this order: [neverStarted] (no `started_at`, so no cycle), then
 * [zeroCycle] (`cycle_ms = 0` — a zero-length elapsed cycle has nothing to divide by). Worklogs play no part, so
 * unlike the reported-time ratio there is no `noWorklogs` bucket. So `flowEfficiency.n + neverStarted + zeroCycle ==
 * population`. (`zeroCycle` here is zero ELAPSED time; the ratio's `zeroCycle` is zero WORKING days.)
 */
@Serializable
data class FlowEfficiencyExcluded(val population: Int, val neverStarted: Int, val zeroCycle: Int)

/**
 * One org-drill entry — a credit team at UNIT level (`teamId` null = UNASSIGNED), an assignee at done at TEAM
 * level, and at USER level one entry per picked account (an empty one when it has no task).
 */
@Serializable
data class ReportedTimeGroup(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val ratio: Distribution,
    val excluded: ReportedTimeExcluded,
    val flowEfficiency: Distribution,
    val flowEfficiencyExcluded: FlowEfficiencyExcluded,
)

@Serializable
data class ReportedTimeRatioReport(
    val meta: ReportMeta,
    /** `actual_md ÷ cycle_working_days` — man-days logged per working day of cycle time. */
    val ratio: Distribution,
    val excluded: ReportedTimeExcluded,
    /** A18: `active_ms ÷ cycle_ms` over the same DONE level-0 population, shown beside the ratio. */
    val flowEfficiency: Distribution,
    val flowEfficiencyExcluded: FlowEfficiencyExcluded,
    val groups: List<ReportedTimeGroup>,
)

/**
 * A task's reported-time ratio `actual_md ÷ cycle_working_days`, `null` when [reportedRatiosOf] excludes it (no worklogs or no logged
 * MD, never started, a zero-working-day cycle). The report's partition and the drill both read THIS function.
 */
internal fun ratioValueOf(task: DoneCycleTask): Double? {
    if (!task.hasWorklogs || task.actualMd.signum() <= 0) return null
    if (task.cycleMs == null || task.cycleWorkingDays == null || task.cycleWorkingDays.signum() <= 0) return null
    return task.actualMd.toDouble() / task.cycleWorkingDays.toDouble()
}

private fun reportedRatiosOf(tasks: List<DoneCycleTask>): Pair<List<Double>, ReportedTimeExcluded> {
    val worked = tasks.filter { it.hasWorklogs && it.actualMd.signum() > 0 }
    val started = worked.filter { it.cycleMs != null && it.cycleWorkingDays != null }
    val ratios = started.mapNotNull(::ratioValueOf)
    return ratios to ReportedTimeExcluded(
        population = tasks.size,
        noWorklogs = tasks.size - worked.size,
        neverStarted = worked.size - started.size,
        zeroCycle = started.size - ratios.size,
    )
}

/** A task's flow efficiency `active_ms ÷ cycle_ms`, `null` when it never started or its cycle is zero (A18); the drill reads it too. */
internal fun flowEfficiencyValueOf(task: DoneCycleTask): Double? =
    if (task.cycleMs == null || task.cycleMs <= 0) null else task.activeMs.toDouble() / task.cycleMs

/** Flow efficiency (A18) = `active_ms ÷ cycle_ms` per measurable task, and the two disjoint exclusion buckets. */
private fun flowEfficiencyOf(tasks: List<DoneCycleTask>): Pair<List<Double>, FlowEfficiencyExcluded> {
    val started = tasks.filter { it.cycleMs != null }
    val values = started.mapNotNull(::flowEfficiencyValueOf)
    return values to FlowEfficiencyExcluded(
        population = tasks.size,
        neverStarted = tasks.size - started.size,
        zeroCycle = started.size - values.size,
    )
}

private fun ratioValues(tasks: List<DoneCycleTask>): List<Double> = reportedRatiosOf(tasks).first

private fun flowEfficiencyValues(tasks: List<DoneCycleTask>): List<Double> = flowEfficiencyOf(tasks).first

/**
 * `GET /api/v1/reports/reported-time-ratio` (v0.3.0 M4 commit 12b, Report 8, `.claude/docs/measures.md`
 * "Reports 7, 8"): level-0 DONE tasks by `done_at`, `actual_md ÷ cycle_working_days` — how much of the elapsed
 * working time was logged — as a [Distribution], with the status-based flow efficiency (A18, `active_ms ÷ cycle_ms`)
 * beside it. At USER level the report takes several `accountId`s: both distributions read the union of the picks and carry a
 * [Distribution.byUser] split of every histogram bucket, and `groups` lists each pick. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.reportedTimeRatio(filter: ReportFilter, nowMs: Long): ReportedTimeRatioReport =
    reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val tasks = scope.window?.let { fetchDoneCycleTasks(filter, scope.connectionIds, it) }.orEmpty()
        val (_, excluded) = reportedRatiosOf(tasks)
        val (_, efficiencyExcluded) = flowEfficiencyOf(tasks)
        val picked = pickedAccounts(filter)
        val distributions = PickedDistributions<DoneCycleTask>(picked, scope.settings.minSampleSize, filter.tailPercent) { it.accountId }
        val groupRows = if (filter.level == ReportLevel.USER) {
            pickedGroups(picked, tasks) { it.accountId }
        } else {
            orgGroups(filter.level, tasks, { it.creditTeamId }, { it.accountId })
        }
        ReportedTimeRatioReport(
            meta = scope.meta,
            ratio = distributions.drawn(tasks, ::ratioValues),
            excluded = excluded,
            flowEfficiency = distributions.drawn(tasks, ::flowEfficiencyValues),
            flowEfficiencyExcluded = efficiencyExcluded,
            groups = groupRows.map { (key, rows) ->
                ReportedTimeGroup(
                    teamId = key.teamId,
                    accountId = key.accountId,
                    label = key.label,
                    ratio = distributions.plain(ratioValues(rows)),
                    excluded = reportedRatiosOf(rows).second,
                    flowEfficiency = distributions.plain(flowEfficiencyValues(rows)),
                    flowEfficiencyExcluded = flowEfficiencyOf(rows).second,
                )
            },
        )
    }
