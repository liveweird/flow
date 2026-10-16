package ch.nokillswit

import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.FactEpicDeliveryRow
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.AccuracyMeasures
import ch.nokillswit.reports.Distribution
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
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

/**
 * Asserts [actual] against the accuracy measures of [ratios] (`actual ÷ estimate`) computed here with [quantile] and plain loops,
 * never `accuracyMeasures`: null under [minSample], else every field. The relative error is written `|r − 1| ÷ r` (the
 * actual as denominator), and the PRED boundary counts as within (a [DIST_TOLERANCE] slack for float noise).
 */
internal fun assertAccuracyMeasures(label: String, ratios: List<Double>, actual: AccuracyMeasures?, minSample: Int) {
    if (ratios.size < minSample) {
        assertNull(actual, "$label accuracy measures must be null when the view is hidden")
        return
    }
    assertNotNull(actual, "$label accuracy measures")
    val sorted = ratios.sorted()
    val mre = sorted.map { abs(it - 1.0) / it }
    val median = quantile(sorted, 0.5)
    val mean = sorted.average()
    val sd = if (sorted.size < 2) 0.0 else sqrt(sorted.sumOf { (it - mean) * (it - mean) } / (sorted.size - 1))
    fun close(name: String, want: Double, got: Double) =
        assertTrue(abs(want - got) < DIST_TOLERANCE, "$label $name: expected $want but was $got")
    fun pred(limit: Double) = mre.count { it <= limit + DIST_TOLERANCE }.toDouble() / sorted.size
    close("pred10", pred(0.10), actual.pred10)
    close("pred25", pred(0.25), actual.pred25)
    close("pred50", pred(0.50), actual.pred50)
    close("mdmre", quantile(mre.sorted(), 0.5), actual.mdmre)
    close("smape", sorted.sumOf { 2.0 * abs(it - 1.0) / (it + 1.0) } / sorted.size, actual.smape)
    close("iqr", quantile(sorted, 0.75) - quantile(sorted, 0.25), actual.iqr)
    close("idr", quantile(sorted, 0.9) - quantile(sorted, 0.1), actual.idr)
    close("mad", quantile(sorted.map { abs(it - median) }.sorted(), 0.5), actual.mad)
    // Below six items no point can lie two sample SDs out ((n − 1) ÷ √n < 2), and a constant sample has no spread: both read null.
    if (sorted.size < 6 || sorted.first() == sorted.last()) {
        assertNull(actual.tailShare, "$label tailShare is undefined for n < 6 or a constant sample")
    } else {
        assertNotNull(actual.tailShare, "$label tailShare")
        close("tailShare", sorted.count { abs(it - mean) > 2.0 * sd }.toDouble() / sorted.size, actual.tailShare)
    }
}

/**
 * Asserts [actual]'s `byUser` against each person's RAW values, bucketed independently into the union's own `[from, to)` edges
 * (the last equal-width bucket closed, the open tail bucket `v > from`; never the production `indexOf`): one share per entry of
 * [rawByUser] in that order, `Σ counts == the person's n`, `Σ shares == histogram[i].count`. A value within [DIST_TOLERANCE] of
 * an edge may legitimately land on either side (the production buckets by `floor((v - min) / width)`), so a bucket's share may
 * exceed the strict count by those ambiguous values only.
 */
internal fun assertSharesInUnionEdges(label: String, rawByUser: List<Triple<String, String, List<Double>>>, actual: Distribution) {
    assertEquals(rawByUser.map { it.first }, actual.byUser.map { it.accountId }, "$label share order")
    assertEquals(rawByUser.map { it.second }, actual.byUser.map { it.label }, "$label share labels")
    val histogram = actual.histogram
    fun candidates(v: Double): List<Int> = histogram.indices.filter { i ->
        val bucket = histogram[i]
        v >= bucket.from - DIST_TOLERANCE && (bucket.to == null || v <= bucket.to + DIST_TOLERANCE)
    }
    for ((raw, share) in rawByUser.zip(actual.byUser)) {
        assertEquals(histogram.size, share.counts.size, "$label ${share.accountId} counts align with the histogram")
        assertEquals(raw.third.size.toLong(), share.counts.sum(), "$label ${share.accountId} n")
        val sets = raw.third.map(::candidates)
        histogram.indices.forEach { i ->
            val strict = sets.count { it == listOf(i) }
            val ambiguous = sets.count { i in it && it.size > 1 }
            assertTrue(
                share.counts[i] in strict.toLong()..(strict + ambiguous).toLong(),
                "$label ${share.accountId} bucket $i: ${share.counts[i]} not within $strict..${strict + ambiguous}",
            )
        }
    }
    histogram.forEachIndexed { i, bucket ->
        assertEquals(bucket.count, actual.byUser.sumOf { it.counts[i] }, "$label bucket $i is the sum of its shares")
    }
}

/** Jira display names of [accountIds] as the report labels a pick (an account with no known name is labelled by its id). */
internal suspend fun displayNamesOf(accountIds: List<String>): Map<String, String> = suspendTransaction(sharedDatabaseForTests()) {
    WorkItemStore.People.selectAll().where { WorkItemStore.People.accountId inList accountIds }.toList()
        .associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
}

/** A `MetricsSettingsService` over the shared test database — `DerivedStubFixture`'s own construction. */
private fun estimationMetricsSettings() = MetricsSettingsService(
    sharedDatabaseForTests(),
    SyncedStubFixture.dataSources(),
    SyncJobsService(sharedDatabaseForTests(), 3),
)

/** Runs [block] with `metrics.settings.min_sample_size` pinned to [size], restoring the exact prior settings afterwards. */
internal suspend fun <T> withMinSampleSize(size: Int, block: suspend () -> T): T =
    withMetricsSettings(estimationMetricsSettings(), { it.copy(minSampleSize = size) }, block)

internal suspend fun reportZone(): ZoneId = suspendTransaction(sharedDatabaseForTests()) {
    ZoneId.of(MetricsSettingsService.Settings.selectAll().toList().single()[MetricsSettingsService.Settings.timeZone])
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
    val cycleMs: Long?,
    val cycleWorkingDays: BigDecimal?,
    val activeMs: Long,
)

internal suspend fun readTaskFacts(connId: UInt): List<TaskFact> = suspendTransaction(sharedDatabaseForTests()) {
    val t = MetricsTables.FactTaskDelivery
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
            cycleMs = it[t.cycleMs],
            cycleWorkingDays = it[t.cycleWorkingDays],
            activeMs = it[t.activeMs],
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
    val cycleMs: Long?,
    val cycleWorkingDays: BigDecimal?,
)

internal suspend fun readEpicFacts(connId: UInt): List<EpicFact> = suspendTransaction(sharedDatabaseForTests()) {
    val e = MetricsTables.FactEpicDelivery
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
            cycleMs = it[e.cycleMs],
            cycleWorkingDays = it[e.cycleWorkingDays],
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
    cycleMs: Long? = null,
    cycleWorkingDays: Double? = null,
    activeMs: Long = 0,
) = FactTaskDeliveryRow(
    issueId = issueId, issueKey = "HB-$issueId", createdAt = 0, startedAt = startedAt, doneAt = doneAt, reopenCount = 0,
    estimateAtStartMd = estimateAtStartMd, estimateAtDoneMd = estimateAtDoneMd, estimateCurrentMd = estimateCurrentMd,
    estimateSource = "OWN", estimateChangesAfterStart = changes, estimatedLate = estimatedLate, actualMd = actualMd,
    hasWorklogs = hasWorklogs, blockedMs = 0, blockedWorkingDays = 0.0, cycleMs = cycleMs, cycleWorkingDays = cycleWorkingDays,
    leadMs = null, leadWorkingDays = null, activeMs = activeMs, waitMs = 0, assigneeAccountIdAtDone = account, assigneeTeamIdAtDone = null,
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
    cycleMs: Long? = null,
    cycleWorkingDays: Double? = null,
) = FactEpicDeliveryRow(
    issueId = issueId, startedAt = startedAt, doneAt = doneAt, ownEstimateAtStartMd = ownStart, ownEstimateAtDoneMd = ownDone,
    ownEstimateCurrentMd = ownCurrent, estimateChangesAfterStart = changes, childSumEstimateMd = childSum,
    budgetSource = if (ownCurrent != null) "OWN" else "CHILDREN", actualMd = actualMd,
    cycleMs = cycleMs, cycleWorkingDays = cycleWorkingDays, blockedMs = 0, blockedWorkingDays = 0.0, domainKey = domain,
    workCategory = category, driftFlags = emptyList(),
    ownerTeamId = ownerTeamId,
)
