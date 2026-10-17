package ch.nokillswit.reports

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select

/** Milliseconds in the day `elapsedDays` is measured in (wall-clock days, weekends included). */
internal val MS_PER_DAY = MILLIS_PER_DAY.toDouble()

/**
 * DONE level-0 tasks (or, in the `epics` block, DONE epics) a cycle-time read could not measure: [neverStarted] (no
 * `started_at`, so no cycle — the ONLY exclusion, `.claude/docs/measures.md` Report 7; a cycle of zero working days is
 * a real value and stays in). So `elapsedDays.n == workingDays.n == population - neverStarted`.
 */
@Serializable
data class CycleTimeExcluded(val population: Int, val neverStarted: Int)

/**
 * One bucket of [CycleTimeReport.trend]: the working-day cycle time of tasks done in that bucket (`p50`/`p90` null
 * when `n` is below the minimum sample size).
 */
@Serializable
data class CycleTimeTrendBucket(val bucketStart: String, val p50: Double?, val p90: Double?, val n: Long)

/**
 * One org-drill entry — a credit team at UNIT level (`teamId` null = UNASSIGNED), an assignee at done at TEAM
 * level, and at USER level one entry per picked account (an empty one when it has no task).
 */
@Serializable
data class CycleTimeGroup(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val elapsedDays: Distribution,
    val workingDays: Distribution,
    val excluded: CycleTimeExcluded,
)

@Serializable
data class CycleTimeReport(
    val meta: ReportMeta,
    /** `cycle_ms` in wall-clock days. */
    val elapsedDays: Distribution,
    /** `cycle_working_days` (the configured calendar and zone). */
    val workingDays: Distribution,
    val excluded: CycleTimeExcluded,
    /** One bucket per week/month across the whole window (zero-filled), by `done_at`, on working days. */
    val trend: List<CycleTimeTrendBucket>,
    val groups: List<CycleTimeGroup>,
    /** The epics beside the tasks: DONE epics by `done_at`, cycle from `fact_epic_delivery`, owner team (A19), no user. */
    val epics: EpicCycleTime,
)

/** One DONE level-0 task with the columns the two cycle-based reports (7 and 8) read. */
internal data class DoneCycleTask(
    val connectionId: UInt,
    val issueId: Long,
    val doneAt: Long,
    val creditTeamId: UInt?,
    val accountId: String?,
    val cycleMs: Long?,
    val cycleWorkingDays: BigDecimal?,
    val activeMs: Long,
    val hasWorklogs: Boolean,
    val actualMd: BigDecimal,
)

internal suspend fun fetchDoneCycleTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<DoneCycleTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(
        t.connectionId, t.issueId, t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, t.cycleMs, t.cycleWorkingDays, t.activeMs,
        t.hasWorklogs, t.actualMd,
    ).where { predicate }.toList().map {
        DoneCycleTask(
            connectionId = it[t.connectionId].value,
            issueId = it[t.issueId],
            doneAt = it[t.doneAt]!!,
            creditTeamId = it[t.creditTeamId]?.value,
            accountId = it[t.assigneeAccountIdAtDone],
            cycleMs = it[t.cycleMs],
            cycleWorkingDays = it[t.cycleWorkingDays],
            activeMs = it[t.activeMs],
            hasWorklogs = it[t.hasWorklogs],
            actualMd = it[t.actualMd],
        )
    }
}

/** The measurable tasks: `cycle_ms` set, i.e. started. */
private fun measurable(tasks: List<DoneCycleTask>): List<DoneCycleTask> = tasks.filter { it.cycleMs != null && it.cycleWorkingDays != null }

/** A task's wall-clock cycle in days, `null` when it is not measurable (never started) — the drill reads the same value. */
internal fun elapsedDaysOf(task: DoneCycleTask): Double? =
    if (task.cycleMs != null && task.cycleWorkingDays != null) task.cycleMs / MS_PER_DAY else null

/** A task's cycle in working days, `null` when it is not measurable (never started) — the drill reads the same value. */
internal fun workingDaysOf(task: DoneCycleTask): Double? =
    if (task.cycleMs != null && task.cycleWorkingDays != null) task.cycleWorkingDays.toDouble() else null

private fun elapsedValues(tasks: List<DoneCycleTask>): List<Double> = tasks.mapNotNull(::elapsedDaysOf)

private fun workingValues(tasks: List<DoneCycleTask>): List<Double> = tasks.mapNotNull(::workingDaysOf)

private fun excludedOf(tasks: List<DoneCycleTask>): CycleTimeExcluded =
    CycleTimeExcluded(population = tasks.size, neverStarted = tasks.size - measurable(tasks).size)

/** Builds this report's distributions: [drawn] splits per picked person at USER level, [plain] never does (a group's own). */
private class CycleTimeDistributions(private val picked: PickedAccounts, private val minSample: Int, private val tailPercent: Int) {
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
 * `GET /api/v1/reports/cycle-time` (v0.3.0 M4 commit 12b, Report 7, `.claude/docs/measures.md` "Reports 7, 8"):
 * level-0 DONE tasks by `done_at`, `cycle_ms` (wall-clock days) and `cycle_working_days` as [Distribution]s plus a
 * per-bucket p50/p90 trend on working days, and an `epics` block (DONE epics' cycle by owner team). At USER level the report
 * takes several `accountId`s: both distributions read the union of the picks and carry a [Distribution.byUser] split of every
 * histogram bucket, `groups` lists each pick, and the trend and the epics stay the union. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.cycleTime(filter: ReportFilter, bucket: ThroughputBucket, nowMs: Long): CycleTimeReport =
    reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
        val window = scope.window
        val tasks = window?.let { fetchDoneCycleTasks(filter, scope.connectionIds, it) }.orEmpty()
        val picked = pickedAccounts(filter)
        val distributions = CycleTimeDistributions(picked, minSample, filter.tailPercent)
        val groupRows = if (filter.level == ReportLevel.USER) {
            pickedGroups(picked, tasks) { it.accountId }
        } else {
            orgGroups(filter.level, tasks, { it.creditTeamId }, { it.accountId })
        }
        CycleTimeReport(
            meta = scope.meta,
            elapsedDays = distributions.drawn(tasks, ::elapsedValues),
            workingDays = distributions.drawn(tasks, ::workingValues),
            excluded = excludedOf(tasks),
            trend = window?.let { trend(tasks, it, zone, bucket, minSample) }.orEmpty(),
            groups = groupRows.map { (key, rows) ->
                CycleTimeGroup(
                    key.teamId,
                    key.accountId,
                    key.label,
                    distributions.plain(rows, ::elapsedValues),
                    distributions.plain(rows, ::workingValues),
                    excludedOf(rows),
                )
            },
            epics = epicCycleTime(filter, scope.connectionIds, window, minSample),
        )
    }

private fun trend(
    tasks: List<DoneCycleTask>,
    window: Pair<Long, Long>,
    zone: java.time.ZoneId,
    bucket: ThroughputBucket,
    minSample: Int,
): List<CycleTimeTrendBucket> {
    val byStart = measurable(tasks).groupBy { bucketStart(it.doneAt, zone, bucket) }
    return bucketStarts(window.first, window.second, zone, bucket).map { start ->
        val values = byStart[start].orEmpty().map { it.cycleWorkingDays!!.toDouble() }
        // An empty bucket (n = 0) is hidden like any other below-minimum one, because `minSampleSize >= 1` (validated on
        // the settings PUT); p50/p90 are then null and only `n` is set. Only p50/p90 are read, never the histogram, so no tail.
        val distribution = buildDistribution(values, minSample, tailPercent = 0)
        CycleTimeTrendBucket(start.toString(), distribution.p50, distribution.p90, distribution.n)
    }
}
