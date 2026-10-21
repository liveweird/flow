package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Throughput's ACTUAL COST (AC = reported time, domain-model PV/EV/AC): the MD logged (`fact_worklog.md`, by `started_at`) INSIDE a
 * period bucket / a sprint's dates on the items the figure belongs to. ONE computation feeds `byBucket[].actualCostMd`,
 * `bySprint[].actualCostMd`, their `byUser` shares and the drill (`throughputActualCostItems`), so a figure and its list cannot
 * disagree. `.claude/docs/reports.md` "Report 2 -- Throughput" -> "Actual cost" has the rules.
 */

/**
 * One worklog counted toward a throughput item: logged on [ownerId] itself, or on one of its sub-tasks, which roll up to the parent task
 * (D2/A31). A worklog on an epic or an issue above the epic level has no task of its own, so it never matches an item.
 */
internal data class ActualCostLog(
    val connectionId: UInt,
    val ownerId: Long,
    val authorAccountId: String?,
    val startedAt: Long,
    val md: BigDecimal,
)

/** A level-0 task as the period view's "delivered or in progress" test sees it: the cycle interval of `fact_task_delivery`. */
internal data class ActualCostItem(val connectionId: UInt, val issueId: Long, val startedAt: Long?, val doneAt: Long?)

/**
 * Whether the task was delivered inside `[lo, hi)` OR in progress during it: started before `hi` and not done before `lo`
 * (`started_at` -> `done_at` is the cycle-time interval, A30; an open task has no `done_at`). A task done before `lo` is out,
 * whatever is logged on it afterwards; one that never started and is not delivered inside the interval is out. Pure.
 */
internal fun ActualCostItem.activeIn(lo: Long, hi: Long): Boolean {
    val done = doneAt
    val started = startedAt
    val deliveredIn = done != null && done >= lo && done < hi
    val inProgress = started != null && started < hi && (done == null || done >= lo)
    return deliveredIn || inProgress
}

/** The rounded EXACT sum of [this] worklogs' MD (never a sum of rounded parts, as the cost matrix). */
internal fun List<ActualCostLog>.exactMd(): BigDecimal = fold(BigDecimal.ZERO) { sum, log -> sum + log.md }

internal fun List<ActualCostLog>.costMd(): Double = exactMd().md()

/** The exact MD per author (`null` = a worklog with no known author). */
internal fun List<ActualCostLog>.byAuthor(): Map<String?, BigDecimal> =
    groupBy { it.authorAccountId }.mapValues { (_, logs) -> logs.exactMd() }

/**
 * The worklogs `[window)` of [connectionIds] by `started_at`, each rolled up to the task it counts toward (a sub-task's to its parent,
 * `dim_task`, as the cost drill does), narrowed to [authors] when given (USER level: the time the picked people reported).
 */
internal suspend fun fetchActualCostLogs(connectionIds: List<UInt>, window: Pair<Long, Long>, authors: List<String>?): List<ActualCostLog> {
    if (connectionIds.isEmpty() || window.second <= window.first) return emptyList()
    val w = MetricsTables.FactWorklog
    val task = MetricsTables.DimTask
    var predicate = (w.connectionId inList connectionIds) and (w.startedAt greaterEq window.first) and (w.startedAt less window.second)
    authors?.let { predicate = predicate and (w.authorAccountId inList it) }
    val source = w.join(task, JoinType.LEFT, onColumn = w.issueId, otherColumn = task.issueId) { w.connectionId eq task.connectionId }
    return source.select(w.connectionId, w.issueId, w.authorAccountId, w.startedAt, w.md, task.isSubtask, task.parentTaskId)
        .where { predicate }.toList().map {
            val parent = if (it.getOrNull(task.isSubtask) == true) it[task.parentTaskId] else null
            ActualCostLog(it[w.connectionId].value, parent ?: it[w.issueId], it[w.authorAccountId], it[w.startedAt], it[w.md])
        }
}

/**
 * The period view's item set: the level-0 tasks [taskFactSlice] selects (the team / domain / activity / category slice EXACTLY as Delivered
 * applies it, an OPEN task attributed to its current team as the WIP report does -- A25 -- and, at USER level, NOT narrowed to the picked
 * accounts: their time on the team's items is what is reported) that were delivered inside [window] or in progress during it.
 */
private suspend fun fetchActualCostItems(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
): Map<Pair<UInt, Long>, ActualCostItem> {
    if (connectionIds.isEmpty()) return emptyMap()
    val t = MetricsTables.FactTaskDelivery
    val deliveredIn = t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    val inProgress = t.startedAt.isNotNull() and (t.startedAt less window.second) and
        (t.doneAt.isNull() or (t.doneAt greaterEq window.first))
    val slice = taskFactSlice(filter.copy(accountIds = emptyList()), connectionIds, openAttribution = true)
    val predicate = slice and (deliveredIn or inProgress)
    return t.select(t.connectionId, t.issueId, t.startedAt, t.doneAt).where { predicate }.toList()
        .map { ActualCostItem(it[t.connectionId].value, it[t.issueId], it[t.startedAt], it[t.doneAt]) }
        .associateBy { it.connectionId to it.issueId }
}

/** The bucket's `[lo, hi)` in millis, clipped to the report's [window] like Delivered (only the requested range counts). Pure. */
private fun bucketBounds(start: LocalDate, window: Pair<Long, Long>, zone: ZoneId, bucket: ThroughputBucket): Pair<Long, Long> {
    val next = if (bucket == ThroughputBucket.WEEK) start.plusWeeks(1) else start.plusMonths(1)
    return maxOf(start.atStartOfDay(zone).toInstant().toEpochMilli(), window.first) to
        minOf(next.atStartOfDay(zone).toInstant().toEpochMilli(), window.second)
}

/**
 * The PERIOD view's counted worklogs per bucket start: a worklog logged inside the bucket (the report's own [bucketStart], the configured
 * zone, clipped to [window]) on a task that was delivered inside the bucket or in progress during it ([activeIn]) -- so a task running
 * across several buckets is split by log date and never counted twice. [filter]'s accounts are the AUTHORS to keep (USER level).
 */
internal suspend fun periodActualCostLogs(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    zone: ZoneId,
    bucket: ThroughputBucket,
): Map<LocalDate, List<ActualCostLog>> {
    val items = fetchActualCostItems(filter, connectionIds, window)
    if (items.isEmpty()) return emptyMap()
    val bounds = HashMap<LocalDate, Pair<Long, Long>>()
    return fetchActualCostLogs(connectionIds, window, filter.accountIds.takeIf { it.isNotEmpty() })
        .groupBy { bucketStart(it.startedAt, zone, bucket) }
        .mapValues { (start, logs) ->
            val (lo, hi) = bounds.getOrPut(start) { bucketBounds(start, window, zone, bucket) }
            logs.filter { log -> items[log.connectionId to log.ownerId]?.activeIn(lo, hi) == true }
        }
}

/**
 * A sprint's own dates `[start, end)`: `start_at` (a sprint without one starts where it ends, so has an empty window) to `complete_at`
 * (an open sprint ends at [nowMs]). Half-open, so a worklog on the boundary instant belongs to one sprint of a team only.
 */
internal fun SprintRow.costWindow(nowMs: Long): Pair<Long, Long> {
    val end = completedAt ?: nowMs
    return (startAt ?: end) to end
}

/** The task ids of each sprint's FINAL scope (`in_scope_at_close`: the kernel's `FINAL` bucket), by `(connection, sprint)`. */
private suspend fun fetchFinalScopeItems(sprintRows: List<SprintRow>): Map<Pair<UInt, Long>, Set<Long>> {
    val s = MetricsTables.FactSprintScope
    val wanted = sprintRows.map { it.connectionId to it.sprintId }.toSet()
    return s.select(s.connectionId, s.sprintId, s.issueId)
        .where {
            (s.connectionId inList sprintRows.map { it.connectionId }.distinct()) and
                (s.sprintId inList sprintRows.map { it.sprintId }.distinct()) and (s.inScopeAtClose eq true)
        }
        .toList().groupBy({ it[s.connectionId].value to it[s.sprintId] }, { it[s.issueId] })
        .filterKeys { it in wanted }.mapValues { it.value.toSet() }
}

/**
 * The SPRINT view's counted worklogs per `(connection, sprint)`: the time logged between the sprint's start and end ([costWindow]) on the
 * tasks of its FINAL scope -- delivered in the sprint or not; an item REMOVED from the sprint is not in it, so the time logged on it counts
 * for no sprint. [authors] narrows to the picked people's own time (USER level). No team / domain / activity / category slice, as
 * `bySprint`.
 */
internal suspend fun sprintActualCostLogs(
    sprintRows: List<SprintRow>,
    authors: List<String>?,
    nowMs: Long,
): Map<Pair<UInt, Long>, List<ActualCostLog>> {
    val windows = sprintRows.associate { (it.connectionId to it.sprintId) to it.costWindow(nowMs) }.filterValues { it.second > it.first }
    if (windows.isEmpty()) return emptyMap()
    val scopes = fetchFinalScopeItems(sprintRows)
    val envelope = windows.values.minOf { it.first } to windows.values.maxOf { it.second }
    val logs = fetchActualCostLogs(sprintRows.map { it.connectionId }.distinct(), envelope, authors)
    val byOwner = logs.groupBy { it.connectionId to it.ownerId }
    return windows.mapValues { (key, window) ->
        val inScope = scopes[key].orEmpty().flatMap { byOwner[key.first to it].orEmpty() }
        inScope.filter { it.startedAt >= window.first && it.startedAt < window.second }
    }
}
