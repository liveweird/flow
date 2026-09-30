package ch.nokillswit.reports

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.WorkingCalendar
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** Milliseconds in the day `elapsedDays` is measured in (wall-clock days, weekends included). */
private val MS_PER_DAY = MILLIS_PER_DAY.toDouble()

/**
 * DONE level-0 tasks a cycle-time read could not measure: [neverStarted] (no `started_at`, so no cycle —
 * the ONLY exclusion, `.claude/docs/measures.md` Report 7; a cycle of zero working days is a real value and
 * stays in). So `elapsedDays.n == workingDays.n == population - neverStarted`.
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
 * level; always empty at USER level.
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
)

/** One DONE level-0 task with the columns the two cycle-based reports (7 and 8) read. */
internal data class DoneCycleTask(
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
    val t = MetricsStore.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(
        t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, t.cycleMs, t.cycleWorkingDays, t.activeMs, t.hasWorklogs, t.actualMd,
    ).where { predicate }.toList().map {
        DoneCycleTask(
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

private fun cycleDistributions(tasks: List<DoneCycleTask>, minSample: Int): Triple<Distribution, Distribution, CycleTimeExcluded> {
    val rows = measurable(tasks)
    return Triple(
        buildDistribution(rows.map { it.cycleMs!! / MS_PER_DAY }, minSample),
        buildDistribution(rows.map { it.cycleWorkingDays!!.toDouble() }, minSample),
        CycleTimeExcluded(population = tasks.size, neverStarted = tasks.size - rows.size),
    )
}

/**
 * `GET /api/v1/reports/cycle-time` (v0.3.0 M4 commit 12b, Report 7, `.claude/docs/measures.md` "Reports 7, 8"):
 * level-0 DONE tasks by `done_at`, `cycle_ms` (wall-clock days) and `cycle_working_days` as [Distribution]s plus a
 * per-bucket p50/p90 trend on working days. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.cycleTime(filter: ReportFilter, bucket: ThroughputBucket, nowMs: Long): CycleTimeReport =
    suspendTransaction(database) {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
        val window = scope.window
        val tasks = window?.let { fetchDoneCycleTasks(filter, scope.connectionIds, it) }.orEmpty()
        val (elapsed, working, excluded) = cycleDistributions(tasks, minSample)
        CycleTimeReport(
            meta = scope.meta,
            elapsedDays = elapsed,
            workingDays = working,
            excluded = excluded,
            trend = window?.let { trend(tasks, it, zone, bucket, minSample) }.orEmpty(),
            groups = orgGroups(filter.level, tasks, { it.creditTeamId }, { it.accountId }).map { (key, rows) ->
                val (groupElapsed, groupWorking, groupExcluded) = cycleDistributions(rows, minSample)
                CycleTimeGroup(key.teamId, key.accountId, key.label, groupElapsed, groupWorking, groupExcluded)
            },
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
        // the settings PUT); p50/p90 are then null and only `n` is set.
        val distribution = buildDistribution(values, minSample)
        CycleTimeTrendBucket(start.toString(), distribution.p50, distribution.p90, distribution.n)
    }
}
