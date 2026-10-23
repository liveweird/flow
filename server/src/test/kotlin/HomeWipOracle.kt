package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.AGING_STAGES
import ch.nokillswit.reports.HomeContext
import ch.nokillswit.reports.HomeDrillView
import ch.nokillswit.reports.HomeWip
import ch.nokillswit.reports.KIND_EPIC
import ch.nokillswit.reports.KIND_TASK
import ch.nokillswit.reports.OpenItem
import ch.nokillswit.reports.ReportDrillBar
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.DrillItemKind
import ch.nokillswit.reports.DrillRow
import ch.nokillswit.reports.ageOfItem
import ch.nokillswit.reports.drillItems
import ch.nokillswit.reports.openEpicPredicate
import ch.nokillswit.reports.openTaskPredicate
import ch.nokillswit.reports.percentileContinuous
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The Home overview's open-item reads as they were BEFORE the Postgres pushdown (master d739291): every open task and epic of the unit
 * is fetched, aged in the JVM and filtered per team. Kept as the golden reference `HomeWipPushdownGoldenTest` grades the set-based
 * reads (`HomeOpenItems.kt`) against; the same two fetches rebuild the old Aging WIP read for `ReportAgingWipCapTest`.
 */

/** Every open task (A25: the CURRENT team and assignee). */
internal suspend fun legacyOpenTasks(filter: ReportFilter, connectionIds: List<UInt>): List<OpenItem> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = openTaskPredicate(filter, connectionIds)
    return t.select(t.connectionId, t.issueId, t.currentTeamId, t.currentAssigneeAccountId, t.startedAt, t.currentStage)
        .where { predicate }.toList().map {
            OpenItem(
                KIND_TASK, it[t.connectionId].value, it[t.issueId], it[t.currentTeamId]?.value, it[t.currentAssigneeAccountId],
                it[t.startedAt]!!, it[t.currentStage],
            )
        }
}

/** Open epics in an IN_PROGRESS, WAITING or BLOCKED stage, attributed to the owner team; a user read has none. */
internal suspend fun legacyOpenEpics(filter: ReportFilter, connectionIds: List<UInt>): List<OpenItem> {
    if (connectionIds.isEmpty() || filter.accountId != null) return emptyList()
    val e = MetricsTables.FactEpicDelivery
    val predicate = openEpicPredicate(filter, connectionIds)
    val candidates = e.select(e.connectionId, e.issueId, e.ownerTeamId, e.startedAt).where { predicate }.toList()
    if (candidates.isEmpty()) return emptyList()
    val d = MetricsTables.DimEpic
    val started = d.select(d.connectionId, d.issueId, d.currentStage)
        .where {
            (d.connectionId inList candidates.map { it[e.connectionId].value }.distinct()) and
                (d.issueId inList candidates.map { it[e.issueId] }.distinct()) and (d.currentStage inList AGING_STAGES)
        }
        .toList().associate { (it[d.connectionId].value to it[d.issueId]) to it[d.currentStage] }
    return candidates.filter { (it[e.connectionId].value to it[e.issueId]) in started }
        .map {
            val stage = started.getValue(it[e.connectionId].value to it[e.issueId])
            OpenItem(KIND_EPIC, it[e.connectionId].value, it[e.issueId], it[e.ownerTeamId]?.value, null, it[e.startedAt]!!, stage)
        }
}

private const val P50 = 0.50
private const val P85 = 0.85
private const val P95 = 0.95

/** The old `wipOf`: the count, and (unless under the minimum) the age percentiles of every item of the team. */
internal fun legacyWip(ctx: HomeContext, items: List<OpenItem>): HomeWip {
    val hidden = items.size < ctx.minSample
    val ages = items.map { ctx.ageOfItem(it) }.sorted()
    fun at(fraction: Double) = if (hidden) null else percentileContinuous(ages, fraction)
    return HomeWip(items.size, hidden, at(P50), at(P85), at(P95))
}

/** The old `WIP_TASKS` / `WIP_EPICS` drill: every open item of [teamId] aged, sorted by the drill order and cut in `drillItems`. */
internal suspend fun legacyWipDrill(ctx: HomeContext, view: HomeDrillView, teamId: UInt): ReportDrillItems {
    val epics = view == HomeDrillView.WIP_EPICS
    val open = if (epics) legacyOpenEpics(ctx.filter, ctx.connectionIds) else legacyOpenTasks(ctx.filter, ctx.connectionIds)
    val kind = if (epics) DrillItemKind.EPIC else DrillItemKind.TASK
    val rows = open.filter { it.teamId == teamId }.map {
        DrillRow(it.connectionId, it.issueId, kind, it.account, it.teamId, it.startedAt, ctx.ageOfItem(it))
    }
    return drillItems(ctx.meta(), ReportDrillBar(rows.size, null, null, null), rows)
}
