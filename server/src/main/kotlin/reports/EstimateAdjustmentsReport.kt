package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * The DONE items (by `done_at`) that [AdjustmentFigures.changeDistribution] could NOT take, each in ONE
 * bucket: [estimatedLate] (no estimate at start, one by the time it was done — counted separately, never a `+∞` change),
 * else [unestimated] (either snapshot missing, incl. never started). So
 * `changeDistribution.n + estimatedLate + unestimated == population`. [estimatedAfterDone] is a breakout of [unestimated],
 * not a fourth bucket: the done items whose estimate first appeared AFTER `done_at` (a backfill, domain-model A35 — they have
 * no estimate at start or at done, so they are unestimated for this report), `estimatedAfterDone <= unestimated`.
 * This block counts DONE items (anchor `done_at`), unlike [AdjustmentFigures.estimatedLate], which counts STARTED ones.
 */
@Serializable
data class AdjustmentChangeExcluded(val population: Int, val estimatedLate: Int, val estimatedAfterDone: Int, val unestimated: Int)

/**
 * The adjustment figures of one item kind. [started] items have `started_at` in the period (Anchor:
 * `started_at`); [changedAfterStart] of those had an estimate change after start; [share] is their
 * fraction (0..1), null when fewer than `minSampleSize` items [started]; [estimatedLate] of them gained an estimate only after start
 * (for a done item: by the time it was done — an estimate set only after `done_at` is a backfill, not "late", A35; for an open
 * one: by now). A change counts as an adjustment only between `started_at` and `done_at`, so a [changedAfterStart] item changed
 * while it was worked on.
 * [changeDistribution] is the fractional change start → done (`(done − start) ÷ start`, so `0.25` is
 * +25 %, negative = shrank) over items DONE in the period with both estimates (Anchor: `done_at`).
 */
@Serializable
data class AdjustmentFigures(
    val started: Int,
    val changedAfterStart: Int,
    /** Hidden (null, counts only) below `minSampleSize` started items, like a [Distribution]. */
    val share: Double?,
    val estimatedLate: Int,
    val changeDistribution: Distribution,
    val changeExcluded: AdjustmentChangeExcluded,
)

/**
 * One org-drill entry: a team at UNIT level (`teamId` null = UNASSIGNED tasks / UNOWNED epics, [accountId]
 * null, both kinds) or an assignee at done at TEAM level (tasks only — [epics] is then null, epics have no
 * user; a null [accountId] is the unassigned bucket). At USER level one entry per picked account (tasks only, [epics] null —
 * the TEAM rule; an empty entry when the pick has no task).
 */
@Serializable
data class EstimateAdjustmentsGroup(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val tasks: AdjustmentFigures,
    val epics: AdjustmentFigures?,
)

@Serializable
data class EstimateAdjustmentsReport(
    val meta: ReportMeta,
    val tasks: AdjustmentFigures,
    val epics: AdjustmentFigures,
    val groups: List<EstimateAdjustmentsGroup>,
)

/**
 * One task or epic, before it is counted; [account] is null for epics (no user attribution). For a task
 * [team]/[account] follow A25: `credit`/assignee-at-done once done, `current`/assignee-now while open.
 */
private data class AdjustmentItem(
    val team: UInt?,
    val account: String?,
    val startedAt: Long?,
    val doneAt: Long?,
    val changes: Int,
    val estimatedLate: Boolean,
    val estimatedAfterDone: Boolean,
    val atStart: BigDecimal?,
    val atDone: BigDecimal?,
)

private fun Long?.within(window: Pair<Long, Long>): Boolean = this != null && this >= window.first && this < window.second

/**
 * The figures of [items]. With [picked] (USER level, the top-level tasks block only) the change histogram also splits per picked
 * person ([Distribution.byUser]); without it, and for every group's own figures, the distribution stays plain.
 */
private fun figuresOf(
    items: List<AdjustmentItem>,
    window: Pair<Long, Long>,
    minSample: Int,
    tailPercent: Int,
    picked: PickedAccounts? = null,
): AdjustmentFigures {
    val started = items.filter { it.startedAt.within(window) }
    val changed = started.count { it.changes > 0 }
    val done = items.filter { it.doneAt.within(window) }
    val late = done.filter { it.estimatedLate }
    val measurable = done.filterNot { it.estimatedLate }.filter { it.atStart.isEstimate() && it.atDone.isEstimate() }
    fun changeOf(item: AdjustmentItem): Double {
        val start = item.atStart!!.toDouble()
        return (item.atDone!!.toDouble() - start) / start
    }
    val changeDistribution = if (picked == null || picked.ids.isEmpty()) {
        buildDistribution(measurable.map(::changeOf), minSample, tailPercent)
    } else {
        val byAccount = measurable.groupBy { it.account }
        buildDistributionByUser(
            picked.ids.map { id -> UserValues(id, picked.label(id), byAccount[id].orEmpty().map(::changeOf)) },
            minSample,
            tailPercent,
        )
    }
    return AdjustmentFigures(
        started = started.size,
        changedAfterStart = changed,
        share = if (started.size < minSample) null else changed.toDouble() / started.size,
        estimatedLate = started.count { it.estimatedLate },
        changeDistribution = changeDistribution,
        changeExcluded = AdjustmentChangeExcluded(
            population = done.size,
            estimatedLate = late.size,
            estimatedAfterDone = done.count { it.estimatedAfterDone },
            unestimated = done.size - late.size - measurable.size,
        ),
    )
}

/**
 * `GET /api/v1/reports/estimate-adjustments` (v0.3.0 M4 commit 12, Report 5, `.claude/docs/measures.md`
 * "Reports 3, 4, 5"): how often, and by how much, an estimate moved after its item started — tasks
 * (level-0, credit team / assignee at done) and epics (own estimate, owner team) side by side. The
 * started-population figures anchor on `started_at`, the change distribution on `done_at`, both in the
 * period window. At USER level the report takes several `accountId`s: the tasks' change distribution reads the union of the
 * picks and carries a [Distribution.byUser] split of every histogram bucket, `groups` lists each pick (tasks only), and the
 * epics block stays all-zero (epics have no user). See `.claude/docs/reports.md`.
 */
suspend fun ReportService.estimateAdjustments(filter: ReportFilter, nowMs: Long): EstimateAdjustmentsReport =
    reportTransaction {
        val scope = resolveReportScope(filter, nowMs)
        val minSample = scope.settings.minSampleSize
        val window = scope.window
        val tasks = window?.let { fetchAdjustmentTasks(filter, scope.connectionIds, it) }.orEmpty()
        // Epics have no user: a USER-level read of them is empty by definition.
        val epics = window.takeIf { filter.level != ReportLevel.USER }
            ?.let { fetchAdjustmentEpics(filter, scope.connectionIds, it) }.orEmpty()
        val empty = window ?: (0L to 0L)
        val picked = pickedAccounts(filter)
        EstimateAdjustmentsReport(
            meta = scope.meta,
            tasks = figuresOf(tasks, empty, minSample, filter.tailPercent, picked),
            epics = figuresOf(epics, empty, minSample, filter.tailPercent),
            groups = adjustmentGroups(filter.level, picked, tasks, epics, empty, minSample, filter.tailPercent),
        )
    }

private suspend fun fetchAdjustmentTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<AdjustmentItem> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds, openAttribution = true) and (
        (t.startedAt.isNotNull() and (t.startedAt greaterEq window.first) and (t.startedAt less window.second)) or
            (t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second))
        )
    return t.select(
        t.creditTeamId, t.assigneeAccountIdAtDone, t.currentTeamId, t.currentAssigneeAccountId, t.startedAt, t.doneAt,
        t.estimateChangesAfterStart, t.estimatedLate, t.estimatedAfterDone, t.estimateAtStartMd, t.estimateAtDoneMd,
    ).where { predicate }.toList().map {
        val open = it[t.doneAt] == null
        AdjustmentItem(
            team = (if (open) it[t.currentTeamId] else it[t.creditTeamId])?.value,
            account = if (open) it[t.currentAssigneeAccountId] else it[t.assigneeAccountIdAtDone],
            startedAt = it[t.startedAt],
            doneAt = it[t.doneAt],
            changes = it[t.estimateChangesAfterStart],
            estimatedLate = it[t.estimatedLate],
            estimatedAfterDone = it[t.estimatedAfterDone],
            atStart = it[t.estimateAtStartMd],
            atDone = it[t.estimateAtDoneMd],
        )
    }
}

private suspend fun fetchAdjustmentEpics(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<AdjustmentItem> {
    if (connectionIds.isEmpty()) return emptyList()
    val e = MetricsTables.FactEpicDelivery
    val predicate = epicFactSlice(filter, connectionIds) and (
        (e.startedAt.isNotNull() and (e.startedAt greaterEq window.first) and (e.startedAt less window.second)) or
            (e.doneAt.isNotNull() and (e.doneAt greaterEq window.first) and (e.doneAt less window.second))
        )
    return e.select(
        e.ownerTeamId, e.startedAt, e.doneAt, e.estimateChangesAfterStart, e.estimatedLate, e.estimatedAfterDone,
        e.ownEstimateAtStartMd, e.ownEstimateAtDoneMd,
    ).where { predicate }.toList().map {
        AdjustmentItem(
            team = it[e.ownerTeamId]?.value,
            account = null,
            startedAt = it[e.startedAt],
            doneAt = it[e.doneAt],
            changes = it[e.estimateChangesAfterStart],
            estimatedLate = it[e.estimatedLate],
            estimatedAfterDone = it[e.estimatedAfterDone],
            atStart = it[e.ownEstimateAtStartMd],
            atDone = it[e.ownEstimateAtDoneMd],
        )
    }
}

/** One item tagged with its kind, so a single [orgGroups] drill can hold tasks and epics side by side at UNIT level. */
private data class KindedItem(val item: AdjustmentItem, val isEpic: Boolean)

private suspend fun adjustmentGroups(
    level: ReportLevel,
    picked: PickedAccounts,
    tasks: List<AdjustmentItem>,
    epics: List<AdjustmentItem>,
    window: Pair<Long, Long>,
    minSample: Int,
    tailPercent: Int,
): List<EstimateAdjustmentsGroup> {
    // UNIT: tasks and epics share the team key (UNASSIGNED tasks / UNOWNED epics together); TEAM: tasks only, by user; USER: tasks
    // only, one entry per pick.
    val items = tasks.map { KindedItem(it, false) } + if (level == ReportLevel.UNIT) epics.map { KindedItem(it, true) } else emptyList()
    val drill = if (level == ReportLevel.USER) {
        pickedGroups(picked, items) { it.item.account }
    } else {
        orgGroups(level, items, { it.item.team }, { it.item.account })
    }
    return drill.map { (key, rows) ->
        EstimateAdjustmentsGroup(
            teamId = key.teamId,
            accountId = key.accountId,
            label = key.label,
            tasks = figuresOf(rows.filterNot { it.isEpic }.map { it.item }, window, minSample, tailPercent),
            epics = if (level == ReportLevel.UNIT) {
                figuresOf(rows.filter { it.isEpic }.map { it.item }, window, minSample, tailPercent)
            } else {
                null
            },
        )
    }
}
