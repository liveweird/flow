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

private fun ratioValues(tasks: List<DoneCycleTask>): List<Double> = ratioOf(tasks).first

private fun flowEfficiencyValues(tasks: List<DoneCycleTask>): List<Double> = flowEfficiencyOf(tasks).first

/** Builds this report's distributions: [drawn] splits per picked person at USER level, [plain] never does (a group's own). */
private class ReportedTimeDistributions(private val picked: PickedAccounts, private val minSample: Int, private val tailPercent: Int) {
    fun plain(tasks: List<DoneCycleTask>, valuesOf: (List<DoneCycleTask>) -> List<Double>): Distribution =
        buildDistribution(valuesOf(tasks), minSample, tailPercent)

    fun drawn(tasks: List<DoneCycleTask>, valuesOf: (List<DoneCycleTask>) -> List<Double>): Distribution {
        if (picked.ids.isEmpty()) return plain(tasks, valuesOf)
        val byAccount = tasks.groupBy { it.accountId }
        return buildDistributionByUser(
            picked.ids.map { UserValues(it, picked.label(it), valuesOf(byAccount[it].orEmpty())) },
            minSample,
            tailPercent,
        )
    }
}

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
        val (_, excluded) = ratioOf(tasks)
        val (_, efficiencyExcluded) = flowEfficiencyOf(tasks)
        val picked = pickedAccounts(filter)
        val distributions = ReportedTimeDistributions(picked, scope.settings.minSampleSize, filter.tailPercent)
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
                    ratio = distributions.plain(rows, ::ratioValues),
                    excluded = ratioOf(rows).second,
                    flowEfficiency = distributions.plain(rows, ::flowEfficiencyValues),
                    flowEfficiencyExcluded = flowEfficiencyOf(rows).second,
                )
            },
        )
    }
