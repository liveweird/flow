package ch.nokillswit.reports

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

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
 * level; always empty at USER level.
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

private fun ratioOf(tasks: List<DoneCycleTask>): Pair<List<Double>, ReportedTimeExcluded> {
    val worked = tasks.filter { it.hasWorklogs && it.actualMd.signum() > 0 }
    val started = worked.filter { it.cycleMs != null && it.cycleWorkingDays != null }
    val ratios = started.filter { it.cycleWorkingDays!!.signum() > 0 }.map { it.actualMd.toDouble() / it.cycleWorkingDays!!.toDouble() }
    return ratios to ReportedTimeExcluded(
        population = tasks.size,
        noWorklogs = tasks.size - worked.size,
        neverStarted = worked.size - started.size,
        zeroCycle = started.size - ratios.size,
    )
}

/** Flow efficiency (A18) = `active_ms ÷ cycle_ms` per measurable task, and the two disjoint exclusion buckets. */
private fun flowEfficiencyOf(tasks: List<DoneCycleTask>): Pair<List<Double>, FlowEfficiencyExcluded> {
    val started = tasks.filter { it.cycleMs != null }
    val values = started.filter { it.cycleMs!! > 0 }.map { it.activeMs.toDouble() / it.cycleMs!! }
    return values to FlowEfficiencyExcluded(
        population = tasks.size,
        neverStarted = tasks.size - started.size,
        zeroCycle = started.size - values.size,
    )
}

/**
 * `GET /api/v1/reports/reported-time-ratio` (v0.3.0 M4 commit 12b, Report 8, `.claude/docs/measures.md`
 * "Reports 7, 8"): level-0 DONE tasks by `done_at`, `actual_md ÷ cycle_working_days` — how much of the elapsed
 * working time was logged — as a [Distribution], with the status-based flow efficiency (A18, `active_ms ÷ cycle_ms`)
 * beside it. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.reportedTimeRatio(filter: ReportFilter, nowMs: Long): ReportedTimeRatioReport =
    suspendTransaction(database) {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val tasks = scope.window?.let { fetchDoneCycleTasks(filter, scope.connectionIds, it) }.orEmpty()
        val (ratios, excluded) = ratioOf(tasks)
        val (efficiency, efficiencyExcluded) = flowEfficiencyOf(tasks)
        ReportedTimeRatioReport(
            meta = scope.meta,
            ratio = buildDistribution(ratios, minSample),
            excluded = excluded,
            flowEfficiency = buildDistribution(efficiency, minSample),
            flowEfficiencyExcluded = efficiencyExcluded,
            groups = orgGroups(filter.level, tasks, { it.creditTeamId }, { it.accountId }).map { (key, rows) ->
                val (groupRatios, groupExcluded) = ratioOf(rows)
                val (groupEfficiency, groupEfficiencyExcluded) = flowEfficiencyOf(rows)
                ReportedTimeGroup(
                    teamId = key.teamId,
                    accountId = key.accountId,
                    label = key.label,
                    ratio = buildDistribution(groupRatios, minSample),
                    excluded = groupExcluded,
                    flowEfficiency = buildDistribution(groupEfficiency, minSample),
                    flowEfficiencyExcluded = groupEfficiencyExcluded,
                )
            },
        )
    }
