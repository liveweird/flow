package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
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

/** A level-0 task as the period view's item test sees it: when (if ever) `fact_task_delivery` says it was done. */
internal data class ActualCostItem(val connectionId: UInt, val issueId: Long, val doneAt: Long?)

/**
 * The period view's item test for a bucket beginning at [lo]: the task was NOT DONE BEFORE the bucket began -- still open, or delivered
 * inside or after the bucket (`done_at` null or `>= lo`). There is deliberately no `started_at` condition, so time logged while the task
 * was still To Do counts like any other, and a task running across buckets contributes to each by log date. A task done before [lo] is
 * out for every worklog of the bucket (time logged after it was done is excluded). Pure.
 */
internal fun ActualCostItem.notDoneBefore(lo: Long): Boolean = doneAt == null || doneAt >= lo

/** The rounded EXACT sum of [this] worklogs' MD (never a sum of rounded parts, as Costs). */
internal fun List<ActualCostLog>.exactMd(): BigDecimal = fold(BigDecimal.ZERO) { sum, log -> sum + log.md }

internal fun List<ActualCostLog>.costMd(): Double = exactMd().md()

/** The exact MD per author (`null` = a worklog with no known author). */
internal fun List<ActualCostLog>.byAuthor(): Map<String?, BigDecimal> =
    groupBy { it.authorAccountId }.mapValues { (_, logs) -> logs.exactMd() }

/**
 * The worklogs `[window)` of [connectionIds] by `started_at`, each rolled up to the task it counts toward (a sub-task's to its parent,
 * `dim_task`, as the cost drill does), narrowed to [authors] when given (USER level: the time the picked people reported) and, in SQL,
 * to the worklogs of the tasks [owners] selects (their own issue ids) -- directly or through a sub-task -- so a slice reads only its own
 * time, never every worklog of the unit. ONE query; [owners] is a prefilter (the callers still match `(connection, owner)` in memory).
 */
internal suspend fun fetchActualCostLogs(
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    authors: List<String>?,
    owners: AbstractQuery<*>,
): List<ActualCostLog> {
    if (connectionIds.isEmpty() || window.second <= window.first) return emptyList()
    val w = MetricsTables.FactWorklog
    val task = MetricsTables.DimTask
    var predicate = (w.connectionId inList connectionIds) and (w.startedAt greaterEq window.first) and (w.startedAt less window.second) and
        ((w.issueId inSubQuery owners) or (task.parentTaskId inSubQuery owners))
    authors?.let { predicate = predicate and (w.authorAccountId inList it) }
    val source = w.join(task, JoinType.LEFT, onColumn = w.issueId, otherColumn = task.issueId) { w.connectionId eq task.connectionId }
    return source.select(w.connectionId, w.issueId, w.authorAccountId, w.startedAt, w.md, task.isSubtask, task.parentTaskId)
        .where { predicate }.toList().map {
            val parent = if (it.getOrNull(task.isSubtask) == true) it[task.parentTaskId] else null
            ActualCostLog(it[w.connectionId].value, parent ?: it[w.issueId], it[w.authorAccountId], it[w.startedAt], it[w.md])
        }
}

/**
 * The period view's item predicate: the level-0 tasks [taskFactSlice] selects (the team / domain / activity / category slice EXACTLY as
 * Delivered applies it, an OPEN task attributed to its current team as the WIP report does -- A25 -- and, at USER level, NOT narrowed to
 * the picked accounts: their time on the team's items is what is reported) that were not done before [window] began. The per-bucket
 * [notDoneBefore] test then runs in memory with each bucket's own start.
 */
private fun itemPredicate(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): Op<Boolean> {
    val t = MetricsTables.FactTaskDelivery
    val slice = taskFactSlice(filter.copy(accountIds = emptyList()), connectionIds, openAttribution = true)
    return slice and (t.doneAt.isNull() or (t.doneAt greaterEq window.first))
}

private suspend fun fetchActualCostItems(predicate: Op<Boolean>): Map<Pair<UInt, Long>, ActualCostItem> {
    val t = MetricsTables.FactTaskDelivery
    return t.select(t.connectionId, t.issueId, t.doneAt).where { predicate }.toList()
        .map { ActualCostItem(it[t.connectionId].value, it[t.issueId], it[t.doneAt]) }
        .associateBy { it.connectionId to it.issueId }
}

/** The bucket's first millisecond, clipped to the report's [window] like Delivered (only the requested range counts). Pure. */
private fun bucketLo(start: LocalDate, window: Pair<Long, Long>, zone: ZoneId): Long =
    maxOf(start.atStartOfDay(zone).toInstant().toEpochMilli(), window.first)

/**
 * The PERIOD view's counted worklogs per bucket start: a worklog logged inside the bucket (the report's own [bucketStart], the configured
 * zone, clipped to [window]) on a task that was not done before the bucket began ([notDoneBefore]) -- delivered inside or after it, or
 * still open, whether or not it had started when the time was logged -- so a task running across several buckets is split by log date
 * and never counted twice, and the figure does not depend on the bucket size for the time logged up to a task's done bucket. [filter]'s
 * accounts are the AUTHORS to keep (USER level).
 */
internal suspend fun periodActualCostLogs(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    zone: ZoneId,
    bucket: ThroughputBucket,
): Map<LocalDate, List<ActualCostLog>> {
    if (connectionIds.isEmpty()) return emptyMap()
    val predicate = itemPredicate(filter, connectionIds, window)
    val items = fetchActualCostItems(predicate)
    if (items.isEmpty()) return emptyMap()
    val t = MetricsTables.FactTaskDelivery
    val owners = t.select(t.issueId).where { predicate }
    val lows = HashMap<LocalDate, Long>()
    return fetchActualCostLogs(connectionIds, window, filter.accountIds.takeIf { it.isNotEmpty() }, owners)
        .groupBy { bucketStart(it.startedAt, zone, bucket) }
        .mapValues { (start, logs) ->
            val lo = lows.getOrPut(start) { bucketLo(start, window, zone) }
            logs.filter { log -> items[log.connectionId to log.ownerId]?.notDoneBefore(lo) == true }
        }
}

/**
 * A sprint's own dates `[start, end)`: `start_at` (a sprint without one starts where it ends, so has an empty window) to `complete_at`
 * (an open sprint ends at [nowMs]). Instant-based (unlike the day-bucketed period view) and half-open, so a worklog on the boundary
 * instant of two ABUTTING sprints belongs to the later one; sprints of one team whose windows OVERLAP (a late close, parallel sprints)
 * each count a worklog inside both.
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
    val s = MetricsTables.FactSprintScope
    val owners = s.select(s.issueId).where {
        (s.connectionId inList sprintRows.map { it.connectionId }.distinct()) and
            (s.sprintId inList sprintRows.map { it.sprintId }.distinct()) and (s.inScopeAtClose eq true)
    }
    val logs = fetchActualCostLogs(sprintRows.map { it.connectionId }.distinct(), envelope, authors, owners)
    val byOwner = logs.groupBy { it.connectionId to it.ownerId }
    return windows.mapValues { (key, window) ->
        val inScope = scopes[key].orEmpty().flatMap { byOwner[key.first to it].orEmpty() }
        inScope.filter { it.startedAt >= window.first && it.startedAt < window.second }
    }
}
