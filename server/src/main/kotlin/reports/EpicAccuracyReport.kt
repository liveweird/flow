package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsStore
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The most epics [EpicEstimationAccuracyReport.epics] lists (the plan's `≤ 200 rows`); `epicsTruncated` says when more matched. */
const val EPIC_ACCURACY_MAX_ROWS = 200

/**
 * The DONE epics an accuracy read could NOT turn into a ratio, each in exactly ONE bucket
 * (`.claude/docs/measures.md` Report 4): [noActual] (`actual_md = 0` — checked first), then
 * [neverStarted] (no `started_at`, so no estimate at start — `atStart` only, as for tasks), then
 * [unestimatedAtStart] / [unestimatedAtDone] (no OWN estimate at that snapshot — an epic whose budget is
 * only the child sum, `budget_source = CHILDREN`, has none). So `atStart.n + noActual + neverStarted +
 * unestimatedAtStart == population` and `atDone.n + noActual + unestimatedAtDone == population`.
 */
@Serializable
data class EpicAccuracyExcluded(
    val population: Int,
    val noActual: Int,
    val neverStarted: Int,
    val unestimatedAtStart: Int,
    val unestimatedAtDone: Int,
)

/**
 * One DONE epic in scope. [ratio] is `actualMd ÷ ownEstimateAtStartMd` (D15), [ratioAtDone] the same at
 * done; each is null when that epic is excluded from the matching distribution. [childSumMd] is the sum of
 * the child estimates, shown beside the own estimate.
 */
@Serializable
data class EpicAccuracyRow(
    val issueKey: String,
    val summary: String?,
    val ownerTeamId: UInt?,
    val doneAt: Long,
    val ownEstimateAtStartMd: Double?,
    val ownEstimateAtDoneMd: Double?,
    val childSumMd: Double,
    val actualMd: Double,
    val ratio: Double?,
    val ratioAtDone: Double?,
)

/** One owner team's own distributions (UNIT level only; `teamId` null = UNOWNED). */
@Serializable
data class EpicAccuracyGroup(
    val teamId: UInt?,
    val label: String?,
    val atStart: Distribution,
    val atDone: Distribution,
    val excluded: EpicAccuracyExcluded,
)

@Serializable
data class EpicEstimationAccuracyReport(
    val meta: ReportMeta,
    val atStart: Distribution,
    val atDone: Distribution,
    val excluded: EpicAccuracyExcluded,
    /** Every DONE epic in scope, newest `doneAt` first (then issue id), at most [EPIC_ACCURACY_MAX_ROWS]. */
    val epics: List<EpicAccuracyRow>,
    val epicsTruncated: Boolean,
    val groups: List<EpicAccuracyGroup>,
)

/** One DONE epic's facts, before it becomes a row. */
private data class AccuracyEpic(
    val connectionId: UInt,
    val issueId: Long,
    val ownerTeamId: UInt?,
    val startedAt: Long?,
    val doneAt: Long,
    val ownAtStart: BigDecimal?,
    val ownAtDone: BigDecimal?,
    val childSum: BigDecimal,
    val actual: BigDecimal,
) {
    /** `actual ÷ own estimate at start` (D15); null = excluded (no actual cost, or no own estimate at start). */
    val ratioAtStart: Double? get() = if (startedAt == null) null else ratioOf(actual, ownAtStart)
    val ratioAtDone: Double? get() = ratioOf(actual, ownAtDone)
}

/** `actual ÷ estimate` when there is actual cost and an own estimate; null = excluded. */
private fun ratioOf(actual: BigDecimal, estimate: BigDecimal?): Double? =
    if (actual.signum() > 0 && estimate != null && estimate.signum() > 0) actual.toDouble() / estimate.toDouble() else null

private data class EpicAccuracyResult(val atStart: List<Double>, val atDone: List<Double>, val excluded: EpicAccuracyExcluded)

private fun epicAccuracyOf(epics: List<AccuracyEpic>): EpicAccuracyResult {
    val noActual = epics.count { it.actual.signum() <= 0 }
    val worked = epics.filter { it.actual.signum() > 0 }
    val neverStarted = worked.count { it.startedAt == null }
    val atStart = epics.mapNotNull { it.ratioAtStart }
    val atDone = epics.mapNotNull { it.ratioAtDone }
    return EpicAccuracyResult(
        atStart = atStart,
        atDone = atDone,
        excluded = EpicAccuracyExcluded(
            population = epics.size,
            noActual = noActual,
            neverStarted = neverStarted,
            unestimatedAtStart = worked.size - neverStarted - atStart.size,
            unestimatedAtDone = worked.size - atDone.size,
        ),
    )
}

/**
 * `GET /api/v1/reports/epic-estimation-accuracy` (v0.3.0 M4 commit 12, Report 4, `.claude/docs/measures.md`
 * "Reports 3, 4, 5"): epics DONE by their own status, by `done_at`, `actual_md ÷ OWN estimate` as a
 * [Distribution] at start (D15) and at done, the child-sum estimate listed alongside. Epics have an owner
 * team (A19) and no user, so UNIT groups by owner team, TEAM has no groups and USER is always empty.
 * See `.claude/docs/reports.md`.
 */
suspend fun ReportService.epicEstimationAccuracy(filter: ReportFilter, nowMs: Long): EpicEstimationAccuracyReport =
    suspendTransaction(database) {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val window = scope.window.takeIf { filter.level != ReportLevel.USER }
        val epics = window?.let { fetchAccuracyEpics(filter, scope.connectionIds, it) }.orEmpty()
        val total = epicAccuracyOf(epics)
        // Epics carry no user: only UNIT level drills (by owner team); TEAM has no groups, USER is empty.
        val groups = if (filter.level != ReportLevel.UNIT) {
            emptyList()
        } else {
            orgGroups(ReportLevel.UNIT, epics, { it.ownerTeamId }, { null }).map { (key, rows) ->
                val result = epicAccuracyOf(rows)
                EpicAccuracyGroup(
                    teamId = key.teamId,
                    label = key.label,
                    atStart = buildDistribution(result.atStart, minSample),
                    atDone = buildDistribution(result.atDone, minSample),
                    excluded = result.excluded,
                )
            }
        }
        val listed = epics.sortedWith(compareByDescending<AccuracyEpic> { it.doneAt }.thenBy { it.connectionId }.thenBy { it.issueId })
            .take(EPIC_ACCURACY_MAX_ROWS)
        EpicEstimationAccuracyReport(
            meta = scope.meta,
            atStart = buildDistribution(total.atStart, minSample),
            atDone = buildDistribution(total.atDone, minSample),
            excluded = total.excluded,
            epics = epicRows(listed),
            epicsTruncated = epics.size > EPIC_ACCURACY_MAX_ROWS,
            groups = groups,
        )
    }

private suspend fun fetchAccuracyEpics(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<AccuracyEpic> {
    if (connectionIds.isEmpty()) return emptyList()
    val e = MetricsStore.FactEpicDelivery
    val predicate = epicFactSlice(filter, connectionIds) and
        e.doneAt.isNotNull() and (e.doneAt greaterEq window.first) and (e.doneAt less window.second)
    return e.select(
        e.connectionId, e.issueId, e.ownerTeamId, e.startedAt, e.doneAt,
        e.ownEstimateAtStartMd, e.ownEstimateAtDoneMd, e.childSumEstimateMd, e.actualMd,
    ).where { predicate }.toList().map {
        AccuracyEpic(
            connectionId = it[e.connectionId].value,
            issueId = it[e.issueId],
            ownerTeamId = it[e.ownerTeamId]?.value,
            startedAt = it[e.startedAt],
            doneAt = it[e.doneAt]!!,
            ownAtStart = it[e.ownEstimateAtStartMd],
            ownAtDone = it[e.ownEstimateAtDoneMd],
            childSum = it[e.childSumEstimateMd],
            actual = it[e.actualMd],
        )
    }
}

private suspend fun epicRows(epics: List<AccuracyEpic>): List<EpicAccuracyRow> {
    if (epics.isEmpty()) return emptyList()
    val d = MetricsStore.DimEpic
    val dims = d.select(d.connectionId, d.issueId, d.issueKey, d.summary)
        .where {
            (d.connectionId inList epics.map { it.connectionId }.distinct()) and (d.issueId inList epics.map { it.issueId }.distinct())
        }
        .toList().associate { (it[d.connectionId].value to it[d.issueId]) to (it[d.issueKey] to it[d.summary]) }
    return epics.map { epic ->
        val (key, summary) = dims[epic.connectionId to epic.issueId] ?: (epic.issueId.toString() to null)
        EpicAccuracyRow(
            issueKey = key,
            summary = summary,
            ownerTeamId = epic.ownerTeamId,
            doneAt = epic.doneAt,
            ownEstimateAtStartMd = epic.ownAtStart?.toDouble(),
            ownEstimateAtDoneMd = epic.ownAtDone?.toDouble(),
            childSumMd = epic.childSum.toDouble(),
            actualMd = epic.actual.toDouble(),
            ratio = epic.ratioAtStart,
            ratioAtDone = epic.ratioAtDone,
        )
    }
}
