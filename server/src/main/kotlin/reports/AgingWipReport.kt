package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.select

/** The most items [AgingWipReport.items] lists (the plan's `≤ 500`); `itemsTruncated` says when more matched. */
const val AGING_MAX_ITEMS = 500

private const val STAGE_IN_PROGRESS = "IN_PROGRESS"
private const val STAGE_WAITING = "WAITING"

/** The stages an item is "in progress" in for aging: started and not done (A30: waiting work is still aging). */
private val AGING_STAGES = listOf(STAGE_IN_PROGRESS, STAGE_WAITING)

/** `band` of an item at or below the lowest configured threshold. */
private const val BAND_WITHIN = "WITHIN"

/** One configured threshold: [percentile] (`metrics.settings.aging_percentiles`) of the cycle working days; null while hidden. */
@Serializable
data class AgingPercentile(val percentile: Int, val workingDays: Double?)

/**
 * The cycle-time thresholds items are compared against: the percentiles of `cycle_working_days` over the LAST
 * `aging_window_items` DONE items of the scope (`n` of them). [hidden] (`n < minSampleSize`) leaves every
 * `workingDays` null and every item without a band.
 */
@Serializable
data class AgingThresholds(val n: Long, val hidden: Boolean, val percentiles: List<AgingPercentile>)

/**
 * One IN_PROGRESS or WAITING item: [ageWorkingDays] = working days from `started_at` to the request's clock. [band] is the highest
 * configured threshold the age passes (`"P85"` = above p85 but not above the next one), [BAND_WITHIN] when it passes none,
 * null when the matching thresholds are hidden. [blocked] = it is blocked as of the connection's last DERIVE (a blocked
 * spell covers that clock). [waiting] = its current stage is WAITING (started, nothing actively worked on — A30).
 */
@Serializable
data class AgingItem(
    val issueKey: String,
    val summary: String?,
    val itemKind: String,
    val teamId: UInt?,
    val assigneeAccountId: String?,
    val assignee: String?,
    val startedAt: Long,
    val ageWorkingDays: Double,
    val blocked: Boolean,
    val waiting: Boolean,
    val band: String?,
)

@Serializable
data class AgingWipReport(
    val meta: ReportMeta,
    /** For tasks: the last N DONE level-0 tasks (credit team). */
    val thresholds: AgingThresholds,
    /** For epics: the last N DONE epics (owner team) — an epic's cycle is a different scale from a task's. */
    val epicThresholds: AgingThresholds,
    /** Oldest first, at most [AGING_MAX_ITEMS]. */
    val items: List<AgingItem>,
    val itemsTruncated: Boolean,
)

private data class OpenItem(
    val kind: String,
    val connectionId: UInt,
    val issueId: Long,
    val teamId: UInt?,
    val account: String?,
    val startedAt: Long,
    val waiting: Boolean,
)

/**
 * `key`/`summary` of work items by (connection, issue id): `norm.work_items` holds every issue, and `dim_epic` — the
 * derived epic dimension the other epic reports read — takes precedence for an epic.
 */
internal suspend fun workItemLabels(items: Collection<Pair<UInt, Long>>): Map<Pair<UInt, Long>, Pair<String, String?>> {
    if (items.isEmpty()) return emptyMap()
    val connectionIds = items.map { it.first }.distinct()
    val issueIds = items.map { it.second }.distinct()
    val w = WorkItemStore.WorkItems
    val labels = w.select(w.connectionId, w.issueId, w.issueKey, w.summary)
        .where { (w.connectionId inList connectionIds) and (w.issueId inList issueIds) }
        .toList().associate { (it[w.connectionId].value to it[w.issueId]) to (it[w.issueKey] to it[w.summary]) }
    val d = MetricsTables.DimEpic
    val epics = d.select(d.connectionId, d.issueId, d.issueKey, d.summary)
        .where { (d.connectionId inList connectionIds) and (d.issueId inList issueIds) }
        .toList().associate { (it[d.connectionId].value to it[d.issueId]) to (it[d.issueKey] to it[d.summary]) }
    return labels + epics
}

/**
 * `GET /api/v1/reports/aging-wip` (v0.3.0 M5 commit 15, Report 11, `.claude/docs/measures.md` "Report 11"): every
 * IN_PROGRESS or WAITING task and epic with its age in working days at the request's clock ([nowMs]) against the cycle-time
 * percentiles of the last N DONE items. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.agingWip(filter: ReportFilter, nowMs: Long): AgingWipReport = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val calendar = WorkingCalendar.of(scope.settings)
    val percentiles = scope.settings.agingPercentiles.sorted()
    val window = scope.settings.agingWindowItems
    val minSample = scope.settings.minSampleSize
    val taskThresholds = thresholdsOf(fetchDoneCycles(filter, scope.connectionIds, window, tasks = true), percentiles, minSample)
    val epicThresholds = thresholdsOf(fetchDoneCycles(filter, scope.connectionIds, window, tasks = false), percentiles, minSample)
    val open = fetchOpenTasks(filter, scope.connectionIds) + fetchOpenEpics(filter, scope.connectionIds)
    val aged = open.map { it to ageOf(calendar, it.startedAt, nowMs) }
        .sortedWith(
            compareByDescending<Pair<OpenItem, Double>> { it.second }.thenBy { it.first.connectionId }.thenBy { it.first.issueId },
        )
    val listed = aged.take(AGING_MAX_ITEMS)
    val labels = workItemLabels(listed.map { it.first.connectionId to it.first.issueId })
    val blocked = openBlocked(listed.map { it.first })
    val names = accountDisplayNames(listed.mapNotNull { it.first.account }.distinct())
    AgingWipReport(
        meta = scope.meta,
        thresholds = taskThresholds.first,
        epicThresholds = epicThresholds.first,
        items = listed.map { (item, age) ->
            val label = labels[item.connectionId to item.issueId]
            val thresholds = if (item.kind == KIND_EPIC) epicThresholds else taskThresholds
            AgingItem(
                issueKey = label?.first ?: item.issueId.toString(),
                summary = label?.second,
                itemKind = item.kind,
                teamId = item.teamId,
                assigneeAccountId = item.account,
                assignee = item.account?.let { names[it] ?: it },
                startedAt = item.startedAt,
                ageWorkingDays = age,
                blocked = (item.connectionId to item.issueId) in blocked,
                waiting = item.waiting,
                band = bandOf(age, thresholds.second),
            )
        },
        itemsTruncated = aged.size > AGING_MAX_ITEMS,
    )
}

internal const val KIND_TASK = "TASK"
internal const val KIND_EPIC = "EPIC"

/** Working days from [startedAt] to [nowMs] — never negative (a clock behind `started_at` reads as age 0). */
private fun ageOf(calendar: WorkingCalendar, startedAt: Long, nowMs: Long): Double =
    if (nowMs <= startedAt) 0.0 else calendar.workingDaysBetween(startedAt, nowMs)

/** The thresholds plus their raw `(percentile, workingDays)` pairs the banding reads (null values while hidden). */
private fun thresholdsOf(
    cycles: List<Double>,
    percentiles: List<Int>,
    minSample: Int,
): Pair<AgingThresholds, List<Pair<Int, Double>>> {
    if (cycles.size < minSample) {
        return AgingThresholds(cycles.size.toLong(), true, percentiles.map { AgingPercentile(it, null) }) to emptyList()
    }
    val sorted = cycles.sorted()
    val values = percentiles.map { it to percentileContinuous(sorted, it / 100.0) }
    return AgingThresholds(cycles.size.toLong(), false, values.map { AgingPercentile(it.first, it.second) }) to values
}

private fun bandOf(age: Double, thresholds: List<Pair<Int, Double>>): String? {
    if (thresholds.isEmpty()) return null
    val passed = thresholds.filter { age > it.second }.maxByOrNull { it.first }
    return passed?.let { "P${it.first}" } ?: BAND_WITHIN
}

/** `cycle_working_days` of the last [limit] DONE tasks (or epics) of the scope, newest first — the aging window. */
private suspend fun fetchDoneCycles(filter: ReportFilter, connectionIds: List<UInt>, limit: Int, tasks: Boolean): List<Double> {
    if (connectionIds.isEmpty()) return emptyList()
    if (tasks) {
        val t = MetricsTables.FactTaskDelivery
        // Thresholds belong to the team, not the user: an `accountId` narrows the listed items only.
        val predicate = taskFactSlice(filter.copy(accountId = null), connectionIds) and
            t.doneAt.isNotNull() and t.cycleWorkingDays.isNotNull()
        return t.select(t.cycleWorkingDays).where { predicate }
            .orderBy(t.doneAt to SortOrder.DESC, t.issueId to SortOrder.DESC).limit(limit)
            .toList().map { it[t.cycleWorkingDays]!!.toDouble() }
    }
    val e = MetricsTables.FactEpicDelivery
    val predicate = epicFactSlice(filter, connectionIds) and e.doneAt.isNotNull() and e.cycleWorkingDays.isNotNull()
    return e.select(e.cycleWorkingDays).where { predicate }
        .orderBy(e.doneAt to SortOrder.DESC, e.issueId to SortOrder.DESC).limit(limit)
        .toList().map { it[e.cycleWorkingDays]!!.toDouble() }
}

/** Open level-0 tasks in an IN_PROGRESS or WAITING stage (A25: attributed to the CURRENT team and assignee). */
private suspend fun fetchOpenTasks(filter: ReportFilter, connectionIds: List<UInt>): List<OpenItem> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds, openAttribution = true) and t.doneAt.isNull() and
        t.startedAt.isNotNull() and (t.currentStage inList AGING_STAGES)
    return t.select(t.connectionId, t.issueId, t.currentTeamId, t.currentAssigneeAccountId, t.startedAt, t.currentStage)
        .where { predicate }.toList().map {
            val team = it[t.currentTeamId]?.value
            OpenItem(
                KIND_TASK, it[t.connectionId].value, it[t.issueId], team, it[t.currentAssigneeAccountId], it[t.startedAt]!!,
                it[t.currentStage] == STAGE_WAITING,
            )
        }
}

/** Open epics whose own stage is IN_PROGRESS or WAITING, attributed to the owner team; epics carry no user, so a user read has none. */
private suspend fun fetchOpenEpics(filter: ReportFilter, connectionIds: List<UInt>): List<OpenItem> {
    if (connectionIds.isEmpty() || filter.accountId != null) return emptyList()
    val e = MetricsTables.FactEpicDelivery
    val predicate = epicFactSlice(filter, connectionIds) and e.doneAt.isNull() and e.startedAt.isNotNull()
    val candidates = e.select(e.connectionId, e.issueId, e.ownerTeamId, e.startedAt).where { predicate }.toList()
    if (candidates.isEmpty()) return emptyList()
    val d = MetricsTables.DimEpic
    val started = d.select(d.connectionId, d.issueId, d.currentStage)
        .where {
            (d.connectionId inList candidates.map { it[e.connectionId].value }.distinct()) and
                (d.issueId inList candidates.map { it[e.issueId] }.distinct()) and (d.currentStage inList AGING_STAGES)
        }
        .toList().associate { (it[d.connectionId].value to it[d.issueId]) to (it[d.currentStage] == STAGE_WAITING) }
    return candidates.filter { (it[e.connectionId].value to it[e.issueId]) in started }
        .map {
            val waiting = started.getValue(it[e.connectionId].value to it[e.issueId])
            OpenItem(KIND_EPIC, it[e.connectionId].value, it[e.issueId], it[e.ownerTeamId]?.value, null, it[e.startedAt]!!, waiting)
        }
}

/**
 * The (connection, issue) pairs among [items] that are blocked "right now": an `item_blocked` row covering the connection's
 * DERIVE clock C (its newest successful run's start) — `valid_from <= C AND (valid_to IS NULL OR valid_to >= C)`. The deriver
 * never writes an open row: it closes a still-open Flagged/blocked-status interval AT the derive clock
 * (`blockedIntervals`, `windowToMs = doneAtMs ?: now`), so "blocked now" is "blocked as of the last derive".
 * A connection that never derived has no rows and no clock: nothing is blocked.
 */
private suspend fun openBlocked(items: List<OpenItem>): Set<Pair<UInt, Long>> {
    if (items.isEmpty()) return emptySet()
    val clocks = deriveClocks(items.map { it.connectionId }.distinct())
    if (clocks.isEmpty()) return emptySet()
    val b = MetricsTables.ItemBlocked
    return b.select(b.connectionId, b.issueId, b.validFrom, b.validTo)
        .where {
            (b.connectionId inList clocks.keys.toList()) and (b.issueId inList items.map { it.issueId }.distinct()) and
                (b.validFrom lessEq clocks.values.max())
        }
        .toList()
        .filter { row ->
            val clock = clocks.getValue(row[b.connectionId].value)
            row[b.validFrom] <= clock && (row[b.validTo]?.let { it >= clock } ?: true)
        }
        .map { it[b.connectionId].value to it[b.issueId] }.toSet()
}
