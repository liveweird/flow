package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The most items [BlockedTimeReport.topItems] lists. */
const val BLOCKED_TOP_ITEMS = 20

/** Which DONE items a blocked-time report counts (`itemKind`): level-0 tasks, epics, or both. */
@Serializable
enum class BlockedItemKind { TASK, EPIC, BOTH }

/**
 * The DONE items the share-of-cycle read could not measure, each in ONE bucket (checked in this order): [neverStarted]
 * (no cycle) then [zeroCycle] (a cycle of 0 working days — nothing to divide by). So `shareOfCycle.n + neverStarted +
 * zeroCycle == population`.
 */
@Serializable
data class BlockedShareExcluded(val population: Int, val neverStarted: Int, val zeroCycle: Int)

/** One of the most-blocked DONE items: [share] = `blockedWorkingDays ÷ cycleWorkingDays`, null when it has no measurable cycle. */
@Serializable
data class BlockedTopItem(
    val issueKey: String,
    val summary: String?,
    val itemKind: String,
    val teamId: UInt?,
    val doneAt: Long,
    val blockedWorkingDays: Double,
    val cycleWorkingDays: Double?,
    val share: Double?,
)

/** One org-drill entry — a team at UNIT level (`teamId` null = UNASSIGNED / UNOWNED), an assignee at done at TEAM level. */
@Serializable
data class BlockedGroup(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val blockedWorkingDays: Distribution,
    val shareOfCycle: Distribution,
    val blockedItems: Int,
    val excluded: BlockedShareExcluded,
)

@Serializable
data class BlockedTimeReport(
    val meta: ReportMeta,
    val itemKind: BlockedItemKind,
    /** `blocked_working_days` of every DONE item in the period — zeros (never blocked) included. */
    val blockedWorkingDays: Distribution,
    /** `blocked_working_days ÷ cycle_working_days` of the items with a cycle above zero. */
    val shareOfCycle: Distribution,
    /** How many of the period's DONE items were blocked at all (`blocked_working_days > 0`). */
    val blockedItems: Int,
    val excluded: BlockedShareExcluded,
    /** The [BLOCKED_TOP_ITEMS] most-blocked items, most first. */
    val topItems: List<BlockedTopItem>,
    val groups: List<BlockedGroup>,
)

private data class DoneItem(
    val kind: String,
    val connectionId: UInt,
    val issueId: Long,
    val teamId: UInt?,
    val account: String?,
    val doneAt: Long,
    val blockedWd: Double,
    val cycleWd: Double?,
) {
    /** Share of the cycle spent blocked; null = excluded (no cycle, or a zero-working-day one). */
    val share: Double? get() = cycleWd?.takeIf { it > 0.0 }?.let { blockedWd / it }
}

private fun excludedOf(items: List<DoneItem>) = BlockedShareExcluded(
    population = items.size,
    neverStarted = items.count { it.cycleWd == null },
    zeroCycle = items.count { it.cycleWd == 0.0 },
)

/**
 * `GET /api/v1/reports/blocked-time` (v0.3.0 M5 commit 15, Report 12, `.claude/docs/measures.md` "Report 12"): the
 * blocked working days of DONE items by `done_at` — per item and as a share of its cycle — as [Distribution]s plus the
 * most-blocked items. Tasks are attributed to the credit team / assignee at done, epics to the owner team. See
 * `.claude/docs/reports.md`.
 */
suspend fun ReportService.blockedTime(filter: ReportFilter, itemKind: BlockedItemKind, nowMs: Long): BlockedTimeReport =
    suspendTransaction(database) {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val window = scope.window
        val items = if (window == null) {
            emptyList()
        } else {
            (if (itemKind != BlockedItemKind.EPIC) fetchDoneTasks(filter, scope.connectionIds, window) else emptyList()) +
                (if (itemKind != BlockedItemKind.TASK) fetchDoneEpics(filter, scope.connectionIds, window) else emptyList())
        }
        // Epics carry no user, so a TEAM-level drill by user covers the tasks only (Σ groups = the tasks' total).
        val drilled = if (filter.level == ReportLevel.TEAM) items.filter { it.kind == KIND_TASK } else items
        val top = items.filter { it.blockedWd > 0.0 }
            .sortedWith(compareByDescending<DoneItem> { it.blockedWd }.thenByDescending { it.doneAt }.thenBy { it.issueId })
            .take(BLOCKED_TOP_ITEMS)
        val labels = workItemLabels(top.map { it.connectionId to it.issueId })
        BlockedTimeReport(
            meta = scope.meta,
            itemKind = itemKind,
            blockedWorkingDays = buildDistribution(items.map { it.blockedWd }, minSample),
            shareOfCycle = buildDistribution(items.mapNotNull { it.share }, minSample),
            blockedItems = items.count { it.blockedWd > 0.0 },
            excluded = excludedOf(items),
            topItems = top.map { item ->
                val label = labels[item.connectionId to item.issueId]
                BlockedTopItem(
                    issueKey = label?.first ?: item.issueId.toString(),
                    summary = label?.second,
                    itemKind = item.kind,
                    teamId = item.teamId,
                    doneAt = item.doneAt,
                    blockedWorkingDays = item.blockedWd,
                    cycleWorkingDays = item.cycleWd,
                    share = item.share,
                )
            },
            groups = orgGroups(filter.level, drilled, { it.teamId }, { it.account }).map { (key, rows) ->
                BlockedGroup(
                    teamId = key.teamId,
                    accountId = key.accountId,
                    label = key.label,
                    blockedWorkingDays = buildDistribution(rows.map { it.blockedWd }, minSample),
                    shareOfCycle = buildDistribution(rows.mapNotNull { it.share }, minSample),
                    blockedItems = rows.count { it.blockedWd > 0.0 },
                    excluded = excludedOf(rows),
                )
            },
        )
    }

private suspend fun fetchDoneTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<DoneItem> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and t.doneAt.isNotNull() and
        (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(
        t.connectionId, t.issueId, t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, t.blockedWorkingDays, t.cycleWorkingDays,
    ).where { predicate }.toList().map {
        DoneItem(
            KIND_TASK, it[t.connectionId].value, it[t.issueId], it[t.creditTeamId]?.value, it[t.assigneeAccountIdAtDone],
            it[t.doneAt]!!, it[t.blockedWorkingDays].toDouble(), it[t.cycleWorkingDays]?.toDouble(),
        )
    }
}

/** DONE epics by owner team; none at USER level (epics have no user). */
private suspend fun fetchDoneEpics(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<DoneItem> {
    if (connectionIds.isEmpty() || filter.level == ReportLevel.USER) return emptyList()
    val e = MetricsTables.FactEpicDelivery
    val predicate = epicFactSlice(filter, connectionIds) and e.doneAt.isNotNull() and
        (e.doneAt greaterEq window.first) and (e.doneAt less window.second)
    return e.select(e.connectionId, e.issueId, e.doneAt, e.ownerTeamId, e.blockedWorkingDays, e.cycleWorkingDays)
        .where { predicate }.toList().map {
            DoneItem(
                KIND_EPIC, it[e.connectionId].value, it[e.issueId], it[e.ownerTeamId]?.value, null,
                it[e.doneAt]!!, it[e.blockedWorkingDays].toDouble(), it[e.cycleWorkingDays]?.toDouble(),
            )
        }
}
