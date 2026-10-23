package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The 500 items Aging WIP lists, picked inside Postgres. The report orders by age (working days since `started_at`) descending, then
 * connection id, then issue id. Age never grows with `started_at` (a later start is never older), so the 500 oldest are the 500
 * smallest `started_at`, and a SQL `ORDER BY started_at, connection_id, issue_id LIMIT 500` per kind finds them without reading the
 * rest. Two items with different `started_at` can still tie on age (starts inside the same non-working stretch, or both after the
 * clock): the working-day arithmetic stays in `WorkingCalendar`, so the page past the 500th is read while the age equals the
 * 500th's, and the exact order (connection id, issue id among equals) is then applied to that small set in the JVM.
 */

/** The listed items, oldest first, with their ages, and how many open items matched the filter (all of them, not just the listed). */
internal class AgingSelection(val listed: List<Pair<OpenItem, Double>>, val total: Long)

/** Open level-0 tasks in an IN_PROGRESS, WAITING or BLOCKED stage (A25: attributed to the CURRENT team and assignee). */
internal fun openTaskPredicate(filter: ReportFilter, connectionIds: List<UInt>): Op<Boolean> {
    val t = MetricsTables.FactTaskDelivery
    return taskFactSlice(filter, connectionIds, openAttribution = true) and t.doneAt.isNull() and
        t.startedAt.isNotNull() and (t.currentStage inList AGING_STAGES)
}

/** Open epics (started, not done) of the scope; the stage lives on `dim_epic`, so the join in [EpicOpenSource] adds it. */
internal fun openEpicPredicate(filter: ReportFilter, connectionIds: List<UInt>): Op<Boolean> {
    val e = MetricsTables.FactEpicDelivery
    return epicFactSlice(filter, connectionIds) and e.doneAt.isNull() and e.startedAt.isNotNull()
}

/**
 * One kind of open item, read in (`started_at`, connection id, issue id) order a page at a time; [page] starts after [after]
 * (keyset paging: each step costs the same however deep it is) and [count] is the number of rows matching in all.
 */
private interface OpenSource {
    suspend fun page(limit: Int, after: OpenItem?): List<OpenItem>
    suspend fun count(): Long
}

private class TaskOpenSource(filter: ReportFilter, connectionIds: List<UInt>) : OpenSource {
    private val t = MetricsTables.FactTaskDelivery
    private val predicate = openTaskPredicate(filter, connectionIds)

    override suspend fun page(limit: Int, after: OpenItem?): List<OpenItem> =
        t.select(t.connectionId, t.issueId, t.currentTeamId, t.currentAssigneeAccountId, t.startedAt, t.currentStage)
            .where { after?.let { predicate and pastTask(it) } ?: predicate }
            .orderBy(t.startedAt to SortOrder.ASC, t.connectionId to SortOrder.ASC, t.issueId to SortOrder.ASC)
            .limit(limit)
            .toList().map {
                OpenItem(
                    KIND_TASK, it[t.connectionId].value, it[t.issueId], it[t.currentTeamId]?.value, it[t.currentAssigneeAccountId],
                    it[t.startedAt]!!, it[t.currentStage],
                )
            }

    override suspend fun count(): Long = t.select(t.issueId).where { predicate }.count()

    /** (started_at, connection_id, issue_id) strictly after [item]'s. */
    private fun pastTask(item: OpenItem): Op<Boolean> =
        (t.startedAt greater item.startedAt) or (
            (t.startedAt eq item.startedAt) and (
                (t.connectionId greater item.connectionId) or ((t.connectionId eq item.connectionId) and (t.issueId greater item.issueId))
                )
            )
}

private class EpicOpenSource(filter: ReportFilter, connectionIds: List<UInt>) : OpenSource {
    private val e = MetricsTables.FactEpicDelivery
    private val d = MetricsTables.DimEpic
    private val source = e.join(d, JoinType.INNER, e.connectionId, d.connectionId, additionalConstraint = { e.issueId eq d.issueId })
    private val predicate = openEpicPredicate(filter, connectionIds) and (d.currentStage inList AGING_STAGES)

    override suspend fun page(limit: Int, after: OpenItem?): List<OpenItem> =
        source.select(e.connectionId, e.issueId, e.ownerTeamId, e.startedAt, d.currentStage)
            .where { after?.let { predicate and pastEpic(it) } ?: predicate }
            .orderBy(e.startedAt to SortOrder.ASC, e.connectionId to SortOrder.ASC, e.issueId to SortOrder.ASC)
            .limit(limit)
            .toList().map {
                OpenItem(
                    KIND_EPIC, it[e.connectionId].value, it[e.issueId], it[e.ownerTeamId]?.value, null, it[e.startedAt]!!,
                    it[d.currentStage],
                )
            }

    override suspend fun count(): Long = source.select(e.issueId).where { predicate }.count()

    /** (started_at, connection_id, issue_id) strictly after [item]'s. */
    private fun pastEpic(item: OpenItem): Op<Boolean> =
        (e.startedAt greater item.startedAt) or (
            (e.startedAt eq item.startedAt) and (
                (e.connectionId greater item.connectionId) or ((e.connectionId eq item.connectionId) and (e.issueId greater item.issueId))
                )
            )
}

/** Rows read per step past the 500th while their age still equals the 500th's (a tie block is a weekend or a holiday: a few dozen rows). */
private const val TIE_PAGE = 100

/**
 * The oldest [AGING_MAX_ITEMS] of [source] as a superset of its true top 500: the first 500 by `started_at`, plus every later row
 * whose age still equals the 500th's. Returns them with their ages (each computed once, and never past the first row that differs),
 * and the source's matching row count (a full count is only asked when the first page is full).
 */
private suspend fun candidatesOf(source: OpenSource, ageOf: (OpenItem) -> Double): Pair<List<Pair<OpenItem, Double>>, Long> {
    val head = source.page(AGING_MAX_ITEMS, null).map { it to ageOf(it) }
    if (head.size < AGING_MAX_ITEMS) return head to head.size.toLong()
    val boundary = head.last().second
    val candidates = head.toMutableList()
    var last = head.last().first
    while (true) {
        val batch = source.page(TIE_PAGE, last)
        var tied = 0
        for (item in batch) {
            val age = ageOf(item)
            if (age != boundary) break
            candidates += item to age
            tied++
        }
        if (tied < TIE_PAGE) break
        last = batch.last()
    }
    return candidates to source.count()
}

/** The report's listed items (oldest first, ties by connection then issue id) and the number of open items matching in all. */
internal suspend fun selectAgingItems(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    calendar: WorkingCalendar,
    nowMs: Long,
): AgingSelection {
    if (connectionIds.isEmpty()) return AgingSelection(emptyList(), 0)
    val age = { item: OpenItem -> ageOf(calendar, item.startedAt, nowMs) }
    val (tasks, taskTotal) = candidatesOf(TaskOpenSource(filter, connectionIds), age)
    // Epics carry no user: a user-level read has none.
    val (epics, epicTotal) = if (filter.accountId != null) {
        emptyList<Pair<OpenItem, Double>>() to 0L
    } else {
        candidatesOf(EpicOpenSource(filter, connectionIds), age)
    }
    val oldestFirst = compareByDescending<Pair<OpenItem, Double>> { it.second }
        .thenBy { it.first.connectionId }.thenBy { it.first.issueId }
    val listed = (tasks + epics).sortedWith(oldestFirst).take(AGING_MAX_ITEMS)
    return AgingSelection(listed, taskTotal + epicTotal)
}
