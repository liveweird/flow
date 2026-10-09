package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The hierarchy finding of the data-quality report (report 14, A31): the issues ABOVE the epic level (Jira `hierarchy_level >= 2`,
 * e.g. a Program over epics). DERIVE builds no task, epic or backlog row for them, so without this list the exclusion would be
 * invisible; only their own worklogs survive, in `fact_worklog` with a null `epic_id`. A property of a connection, not of a
 * team: neither the team nor the domain filter narrows it. Runs inside the caller's `suspendTransaction`.
 */

/** The lowest Jira hierarchy level above the epic (level 1): such an issue is outside the task/epic model (A31). */
private const val FIRST_LEVEL_ABOVE_EPIC = 2

/** A live issue above the epic level, left out of the metrics model; [worklogMd] is the man-days logged on it itself (0 when none). */
@Serializable
data class AboveEpicItem(
    val connectionId: UInt,
    val issueKey: String,
    val summary: String?,
    val issueType: String,
    val hierarchyLevel: Int,
    val projectKey: String,
    val worklogMd: Double,
)

/**
 * The live (not deleted, not moved out) `norm.work_items` of the connections with `hierarchy_level >= 2`, highest level first,
 * then by key; `total` counts them all (SQL), `items` carries the first [DATA_QUALITY_MAX_ITEMS] with Σ `fact_worklog.md` of each.
 */
internal suspend fun fetchItemsAboveEpic(connectionIds: List<UInt>): QualityList<AboveEpicItem> {
    if (connectionIds.isEmpty()) return QualityList(0, emptyList())
    val w = WorkItemStore.WorkItems
    val live = (w.connectionId inList connectionIds) and w.deletedAt.isNull() and w.movedOutAt.isNull() and
        (w.hierarchyLevel greaterEq FIRST_LEVEL_ABOVE_EPIC)
    val size = Count(w.issueId)
    val total = w.select(size).where { live }.toList().single()[size].toInt()
    if (total == 0) return QualityList(0, emptyList())
    val rows = w.select(w.connectionId, w.issueId, w.issueKey, w.summary, w.issueType, w.hierarchyLevel, w.projectKey)
        .where { live }
        .orderBy(w.hierarchyLevel to SortOrder.DESC, w.issueKey to SortOrder.ASC, w.connectionId to SortOrder.ASC)
        .limit(DATA_QUALITY_MAX_ITEMS).toList()
    val f = MetricsTables.FactWorklog
    val md = f.md.sum()
    val listedConnections = rows.map { it[w.connectionId].value }.distinct()
    val listedIssues = rows.map { it[w.issueId] }
    val logged = f.select(f.connectionId, f.issueId, md)
        .where { (f.connectionId inList listedConnections) and (f.issueId inList listedIssues) }
        .groupBy(f.connectionId, f.issueId).toList()
        .associate { (it[f.connectionId].value to it[f.issueId]) to (it[md]?.toDouble() ?: 0.0) }
    return QualityList(
        total,
        rows.map {
            AboveEpicItem(
                connectionId = it[w.connectionId].value, issueKey = it[w.issueKey], summary = it[w.summary],
                issueType = it[w.issueType], hierarchyLevel = it[w.hierarchyLevel]!!, projectKey = it[w.projectKey],
                worklogMd = logged[it[w.connectionId].value to it[w.issueId]] ?: 0.0,
            )
        },
    )
}
