package ch.nokillswit

import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.FactEpicDeliveryRow
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.Distribution
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Shared test-side helpers of the estimation report tests (`ReportTaskEstimationAccuracyTest`,
 * `ReportEpicEstimationAccuracyTest`, `ReportEstimateAdjustmentsTest`, v0.3.0 M4 commit 12): an
 * INDEPENDENT distribution check (its own percentile arithmetic, never `buildDistribution`), raw
 * readers of the fact tables the reports are graded against, and builders for hand-computed fact rows.
 */

internal const val DIST_TOLERANCE = 1e-9

/** `percentile_cont`'s linear interpolation between the closest ranks of an ascending list — written out here, not imported. */
internal fun quantile(sorted: List<Double>, q: Double): Double {
    if (sorted.size == 1) return sorted.single()
    val rank = q * (sorted.size - 1)
    val lo = floor(rank).toInt()
    val hi = ceil(rank).toInt()
    return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo])
}

/** Asserts [actual] is the distribution of [expected] under [minSample]: `n` always, everything else only when not hidden. */
internal fun assertDistribution(label: String, expected: List<Double>, actual: Distribution, minSample: Int) {
    assertEquals(expected.size.toLong(), actual.n, "$label n")
    assertEquals(expected.size < minSample, actual.hidden, "$label hidden")
    if (actual.hidden) {
        assertNull(actual.mean, "$label mean must be null when hidden")
        assertNull(actual.p50, "$label p50 must be null when hidden")
        assertTrue(actual.histogram.isEmpty(), "$label histogram must be empty when hidden")
        return
    }
    val sorted = expected.sorted()
    fun close(name: String, want: Double, got: Double?) {
        assertNotNull(got, "$label $name")
        assertTrue(abs(want - got) < DIST_TOLERANCE, "$label $name: expected $want but was $got")
    }
    close("mean", sorted.average(), actual.mean)
    close("min", sorted.first(), actual.min)
    close("max", sorted.last(), actual.max)
    close("p50", quantile(sorted, 0.5), actual.p50)
    close("p90", quantile(sorted, 0.9), actual.p90)
    close("p95", quantile(sorted, 0.95), actual.p95)
    assertEquals(expected.size.toLong(), actual.histogram.sumOf { it.count }, "$label histogram counts must sum to n")
}

/** A `MetricsConfigService` over the shared test database — `DerivedStubFixture`'s own construction. */
private fun estimationMetricsConfig() = MetricsConfigService(
    sharedDatabaseForTests(),
    SyncedStubFixture.workItems(),
    SyncedStubFixture.dataSources(),
    SyncJobsService(sharedDatabaseForTests(), 3),
)

/** Runs [block] with `metrics.settings.min_sample_size` pinned to [size], restoring the exact prior settings afterwards. */
internal suspend fun <T> withMinSampleSize(size: Int, block: suspend () -> T): T =
    withMetricsSettings(estimationMetricsConfig(), { it.copy(minSampleSize = size) }, block)

internal suspend fun reportZone(): ZoneId = suspendTransaction(sharedDatabaseForTests()) {
    ZoneId.of(MetricsConfigService.Settings.selectAll().toList().single()[MetricsConfigService.Settings.timeZone])
}

/** `[fromMs, toMs)` for inclusive ISO dates in [zone] — the reports' own exclusive-`to` convention, re-derived. */
internal fun windowBounds(zone: ZoneId, from: String, to: String): Pair<Long, Long> =
    LocalDate.parse(from).atStartOfDay(zone).toInstant().toEpochMilli() to
        LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

/** Noon UTC of an ISO date — a hand-built row's timestamp, far from any zone's day boundary. */
internal fun noonUtc(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneId.of("UTC")).plusHours(12).toInstant().toEpochMilli()

/**
 * One level-0 `fact_task_delivery` row as stored — the independent read the task reports are graded against.
 * [team]/[account] follow A25's branch on `done_at`: `credit_team_id`/assignee-at-done once done,
 * `current_team_id`/`current_assignee_account_id` while open (never a COALESCE).
 */
internal data class TaskFact(
    val issueId: Long,
    val startedAt: Long?,
    val doneAt: Long?,
    val team: UInt?,
    val account: String?,
    val hasWorklogs: Boolean,
    val actual: BigDecimal,
    val estStart: BigDecimal?,
    val estDone: BigDecimal?,
    val changes: Int,
    val estimatedLate: Boolean,
    val activityType: String,
    val domainKey: String?,
    val workCategory: String?,
)

internal suspend fun readTaskFacts(connId: UInt): List<TaskFact> = suspendTransaction(sharedDatabaseForTests()) {
    val t = MetricsStore.FactTaskDelivery
    t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList().map {
        TaskFact(
            issueId = it[t.issueId],
            startedAt = it[t.startedAt],
            doneAt = it[t.doneAt],
            team = (if (it[t.doneAt] == null) it[t.currentTeamId] else it[t.creditTeamId])?.value,
            account = if (it[t.doneAt] == null) it[t.currentAssigneeAccountId] else it[t.assigneeAccountIdAtDone],
            hasWorklogs = it[t.hasWorklogs],
            actual = it[t.actualMd],
            estStart = it[t.estimateAtStartMd],
            estDone = it[t.estimateAtDoneMd],
            changes = it[t.estimateChangesAfterStart],
            estimatedLate = it[t.estimatedLate],
            activityType = it[t.activityType],
            domainKey = it[t.domainKey],
            workCategory = it[t.workCategory],
        )
    }
}

/** One `fact_epic_delivery` row as stored. */
internal data class EpicFact(
    val issueId: Long,
    val owner: UInt?,
    val startedAt: Long?,
    val doneAt: Long?,
    val ownStart: BigDecimal?,
    val ownDone: BigDecimal?,
    val ownCurrent: BigDecimal?,
    val childSum: BigDecimal,
    val actual: BigDecimal,
    val changes: Int,
    val domainKey: String?,
    val workCategory: String?,
)

internal suspend fun readEpicFacts(connId: UInt): List<EpicFact> = suspendTransaction(sharedDatabaseForTests()) {
    val e = MetricsStore.FactEpicDelivery
    e.selectAll().where { e.connectionId eq connId }.toList().map {
        EpicFact(
            issueId = it[e.issueId],
            owner = it[e.ownerTeamId]?.value,
            startedAt = it[e.startedAt],
            doneAt = it[e.doneAt],
            ownStart = it[e.ownEstimateAtStartMd],
            ownDone = it[e.ownEstimateAtDoneMd],
            ownCurrent = it[e.ownEstimateCurrentMd],
            childSum = it[e.childSumEstimateMd],
            actual = it[e.actualMd],
            changes = it[e.estimateChangesAfterStart],
            domainKey = it[e.domainKey],
            workCategory = it[e.workCategory],
        )
    }
}

/** Soft-deletes the throwaway teams a hand-built test seeded (their fact rows are removed first, so no FK reads them). */
internal suspend fun cleanUpTeams(ids: List<UInt>) {
    for (id in ids) TestTeams.service.delete(id)
}

/** A hand-built `fact_task_delivery` row: only the columns the estimation reports read are parameters. */
internal fun handTask(
    issueId: Long,
    startedAt: Long? = null,
    doneAt: Long? = null,
    hasWorklogs: Boolean = true,
    actualMd: Double = 0.0,
    estimateAtStartMd: Double? = null,
    estimateAtDoneMd: Double? = null,
    estimateCurrentMd: Double? = null,
    changes: Int = 0,
    estimatedLate: Boolean = false,
    creditTeamId: UInt? = null,
    account: String? = null,
    currentTeamId: UInt? = null,
    currentAssignee: String? = null,
    domain: String? = "AAA",
    epicDomain: String? = null,
    category: String? = null,
    activityType: String = "Story",
    subtask: Boolean = false,
) = FactTaskDeliveryRow(
    issueId = issueId, issueKey = "HB-$issueId", createdAt = 0, startedAt = startedAt, doneAt = doneAt, reopenCount = 0,
    estimateAtStartMd = estimateAtStartMd, estimateAtDoneMd = estimateAtDoneMd, estimateCurrentMd = estimateCurrentMd,
    estimateSource = "OWN", estimateChangesAfterStart = changes, estimatedLate = estimatedLate, actualMd = actualMd,
    hasWorklogs = hasWorklogs, blockedMs = 0, blockedWorkingDays = 0.0, cycleMs = null, cycleWorkingDays = null, leadMs = null,
    leadWorkingDays = null, activeMs = 0, waitMs = 0, assigneeAccountIdAtDone = account, assigneeTeamIdAtDone = null,
    sprintIdAtDone = null, sprintTeamIdAtDone = null, creditTeamId = creditTeamId, currentTeamId = currentTeamId,
    currentAssigneeAccountId = currentAssignee, domainKey = domain, epicId = null, epicDomainKey = epicDomain,
    crossDomain = epicDomain != null && epicDomain != domain, activityType = activityType, workCategory = category,
    isSubtask = subtask, parentTaskId = null, currentStage = if (doneAt == null) "IN_PROGRESS" else "DONE", flags = emptyList(),
)

/** A hand-built `fact_epic_delivery` row (its `dim_epic` twin is inserted by the test for the issue key). */
internal fun handEpic(
    issueId: Long,
    startedAt: Long? = null,
    doneAt: Long? = null,
    ownStart: Double? = null,
    ownDone: Double? = null,
    ownCurrent: Double? = null,
    childSum: Double = 0.0,
    actualMd: Double = 0.0,
    changes: Int = 0,
    ownerTeamId: UInt? = null,
    domain: String? = "AAA",
    category: String? = null,
) = FactEpicDeliveryRow(
    issueId = issueId, startedAt = startedAt, doneAt = doneAt, ownEstimateAtStartMd = ownStart, ownEstimateAtDoneMd = ownDone,
    ownEstimateCurrentMd = ownCurrent, estimateChangesAfterStart = changes, childSumEstimateMd = childSum,
    budgetSource = if (ownCurrent != null) "OWN" else "CHILDREN", actualMd = actualMd, cycleMs = null, cycleWorkingDays = null,
    blockedMs = 0, blockedWorkingDays = 0.0, domainKey = domain, workCategory = category, driftFlags = emptyList(),
    ownerTeamId = ownerTeamId,
)
