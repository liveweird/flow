package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.metrics.sprintTotals
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select

/** The `bucket` query param: the period view's time resolution. Weeks start Monday; both in the configured zone. */
@Serializable
enum class ThroughputBucket { WEEK, MONTH }

/** A sprint's own delivered figures, live or frozen (`fact_sprint(_snapshot)`). */
@Serializable
data class ThroughputSnapshot(val deliveredMd: Double, val deliveredItems: Int)

/**
 * One sprint's delivered scope (the SPRINT view, Report 2 — `fact_sprint.delivered_md/_items`, priced
 * at the sprint's close). [snapshot]/[drift] mirror Commitments': the frozen `fact_sprint_snapshot`
 * figures (`null` until snapshotted, D13) and whether live differs from frozen. At USER level every figure, the snapshot
 * included, is the SUM over the picked accounts and [byUser] splits the live figures per picked account (request order, zeros
 * included); [byUser] is empty at UNIT/TEAM level. [byEpic] splits the LIVE delivered figures by the current epic of each item
 * (top 6 + Other + No epic, [throughputByEpic]), adding up to them exactly; the frozen [snapshot] is not split.
 * [actualCostMd] is the sprint's ACTUAL COST: the MD logged between its start and end on the tasks of its FINAL scope (delivered or not;
 * [sprintActualCostLogs]), live like the delivered figures (the frozen [snapshot] carries none); at USER level only the picked people's own
 * logged time, [ThroughputUserShare.actualCostMd] per person.
 */
@Serializable
data class ThroughputSprint(
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val completedAt: Long?,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val actualCostMd: Double,
    val snapshot: ThroughputSnapshot?,
    val drift: Boolean,
    val byUser: List<ThroughputUserShare>,
    val byEpic: List<ThroughputEpicSegment>,
)

/**
 * One picked account's own share of a sprint or bucket at USER level — zeros included. Delivered is by ASSIGNEE (at done / at commitment);
 * [actualCostMd] is by worklog AUTHOR: the MD that person reported on the figure's items.
 */
@Serializable
data class ThroughputUserShare(
    val accountId: String,
    val label: String,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val actualCostMd: Double,
)

/**
 * One time bucket of the PERIOD view. [bucketStart] is the bucket's first day (ISO date, Monday for weeks, the 1st for months).
 * [byUser] splits the bucket per picked account at USER level (request order, zeros included); empty at UNIT/TEAM level.
 * [actualCostMd] is the MD logged inside the bucket on the scope's items delivered in it or in progress during it
 * ([periodActualCostLogs]); at USER level only the picked people's own logged time.
 */
@Serializable
data class ThroughputBucketRow(
    val bucketStart: String,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val actualCostMd: Double,
    val byUser: List<ThroughputUserShare>,
)

/**
 * One org-drill entry of the PERIOD view: a credit team's sums at UNIT level (`teamId` null =
 * UNASSIGNED, [accountId] null) or one assignee-at-done's sums at TEAM level ([accountId]/[label]
 * set; a null [accountId] is the unassigned bucket) — or, at USER level, one entry per picked account (its period totals, a zero
 * entry when it delivered nothing; [teamId] null).
 */
@Serializable
data class ThroughputGroup(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val deliveredMd: Double,
    val deliveredItems: Int,
)

@Serializable
data class ThroughputReport(
    val meta: ReportMeta,
    val bySprint: List<ThroughputSprint>,
    val byBucket: List<ThroughputBucketRow>,
    val groups: List<ThroughputGroup>,
)

/** The first day of the [bucket] containing the instant [atMs], in [zone] — weeks start Monday. Pure. */
fun bucketStart(atMs: Long, zone: ZoneId, bucket: ThroughputBucket): LocalDate {
    val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    return when (bucket) {
        ThroughputBucket.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        ThroughputBucket.MONTH -> day.withDayOfMonth(1)
    }
}

/**
 * Every bucket start from the bucket of [fromMs] through the bucket of the last instant before
 * [toMsExclusive] — zero-fills the gaps. Pure.
 */
fun bucketStarts(fromMs: Long, toMsExclusive: Long, zone: ZoneId, bucket: ThroughputBucket): List<LocalDate> {
    if (toMsExclusive <= fromMs) return emptyList()
    val last = bucketStart(toMsExclusive - 1, zone, bucket)
    val starts = mutableListOf<LocalDate>()
    var cursor = bucketStart(fromMs, zone, bucket)
    while (!cursor.isAfter(last)) {
        starts += cursor
        cursor = if (bucket == ThroughputBucket.WEEK) cursor.plusWeeks(1) else cursor.plusMonths(1)
    }
    return starts
}

/**
 * One level-0 task done inside the window — what the period view aggregates and the drill (`ThroughputDrill.kt`) lists.
 * [estimateMd] is `null` for an unestimated task, which counts as an item worth 0 MD ([md]).
 */
internal data class ThroughputTask(
    val connectionId: UInt,
    val issueId: Long,
    val doneAt: Long,
    val estimateMd: BigDecimal?,
    val creditTeamId: UInt?,
    val accountId: String?,
) {
    /** Unestimated counts as an item worth 0 MD (measures.md: "unestimated -> 0 MD, counted"). */
    val md: BigDecimal get() = estimateMd ?: BigDecimal.ZERO
}

/**
 * `GET /api/v1/reports/throughput` (v0.3.0 M4 commit 10c, Report 2 + the item counts of 13,
 * `.claude/docs/measures.md` "Report 2"). Two views that differ by design: [ThroughputReport.bySprint]
 * (sprints closed in the period, straight off `fact_sprint`) and [ThroughputReport.byBucket] /
 * [ThroughputReport.groups] (level-0 tasks by `done_at`, priced at done). See `.claude/docs/reports.md`.
 */
suspend fun ReportService.throughput(
    filter: ReportFilter,
    bucket: ThroughputBucket,
    nowMs: Long,
): ThroughputReport = reportTransaction {
    // teamId=0: no sprint ever carries "no team", so the sprint view (and any sprint-relative window) is empty.
    val scope = resolveReportScope(filter, nowMs)
    val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
    val connectionIds = scope.connectionIds
    val sprintRows = scope.sprintRows

    val picked = pickedUsers(filter)
    val byEpic = throughputByEpic(sprintRows, picked.ids.takeIf { it.isNotEmpty() })
    val sprintCost = sprintActualCostLogs(sprintRows, picked.ids.takeIf { it.isNotEmpty() }, nowMs)
    val bySprint = when (filter.level) {
        ReportLevel.USER -> userSprints(sprintRows, picked, byEpic, sprintCost)
        else -> teamSprints(sprintRows, byEpic, sprintCost)
    }.sortedWith(compareBy<ThroughputSprint, Long?>(nullsLast()) { it.completedAt }.thenBy { it.sprintId })

    val window = scope.window
    val tasks = if (window == null) emptyList() else fetchThroughputTasks(filter, connectionIds, window)
    val periodCost = if (window == null) emptyMap() else periodActualCostLogs(filter, connectionIds, window, zone, bucket)
    val byBucket = if (window == null) emptyList() else bucketRows(tasks, window, zone, bucket, picked, periodCost)
    val groups = when (filter.level) {
        ReportLevel.UNIT -> teamThroughputGroups(tasks)
        ReportLevel.TEAM -> userThroughputGroups(tasks)
        ReportLevel.USER -> pickedUserGroups(tasks, picked)
    }
    ThroughputReport(scope.meta, bySprint, byBucket, groups)
}

internal suspend fun fetchThroughputTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<ThroughputTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    val rows = t.select(t.connectionId, t.issueId, t.doneAt, t.estimateAtDoneMd, t.creditTeamId, t.assigneeAccountIdAtDone)
        .where { predicate }.toList()
    return rows.map {
        ThroughputTask(
            connectionId = it[t.connectionId].value,
            issueId = it[t.issueId],
            doneAt = it[t.doneAt]!!,
            estimateMd = it[t.estimateAtDoneMd],
            creditTeamId = it[t.creditTeamId]?.value,
            accountId = it[t.assigneeAccountIdAtDone],
        )
    }
}

private fun bucketRows(
    tasks: List<ThroughputTask>,
    window: Pair<Long, Long>,
    zone: ZoneId,
    bucket: ThroughputBucket,
    picked: PickedUsers,
    cost: Map<LocalDate, List<ActualCostLog>>,
): List<ThroughputBucketRow> {
    val byStart = tasks.groupBy { bucketStart(it.doneAt, zone, bucket) }
    return bucketStarts(window.first, window.second, zone, bucket).map { start ->
        val rows = byStart[start].orEmpty()
        val logs = cost[start].orEmpty()
        ThroughputBucketRow(
            start.toString(), rows.deliveredMd(), rows.size, logs.costMd(), picked.sharesOf(rows.mdByAccount(), logs.byAuthor()),
        )
    }
}

/**
 * The picked accounts of a USER-level request: their ids in request order and their Jira display names (the account id when
 * none is known). Both empty below USER level, so [sharesOf] answers an empty `byUser` there.
 */
private class PickedUsers(val ids: List<String>, val labels: Map<String, String>) {
    fun label(id: String): String = labels[id] ?: id

    /**
     * One share per picked account, in request order, from each account's delivered `md` list and its exact logged MD ([costByAccount],
     * by worklog author) -- zeros for an account with none.
     */
    fun sharesOf(
        mdByAccount: Map<String?, List<BigDecimal>>,
        costByAccount: Map<String?, BigDecimal>,
    ): List<ThroughputUserShare> = ids.map { id ->
        val md = mdByAccount[id].orEmpty()
        val cost = (costByAccount[id] ?: BigDecimal.ZERO).md()
        ThroughputUserShare(id, label(id), md.fold(BigDecimal.ZERO, BigDecimal::add).toDouble(), md.size, cost)
    }
}

private suspend fun pickedUsers(filter: ReportFilter): PickedUsers {
    if (filter.level != ReportLevel.USER) return PickedUsers(emptyList(), emptyMap())
    check(filter.accountIds.isNotEmpty()) { "USER level always carries accountIds (ReportFilter's own invariant)" }
    return PickedUsers(filter.accountIds, accountDisplayNames(filter.accountIds))
}

/**
 * USER-level `groups`: each picked account's period totals (the TEAM-level rule — tasks by `assignee_at_done`), ordered like
 * the other groups (label, then account id); an explicit pick is always listed, a zero entry when it delivered nothing.
 */
private fun pickedUserGroups(tasks: List<ThroughputTask>, picked: PickedUsers): List<ThroughputGroup> =
    picked.sharesOf(tasks.mdByAccount(), costByAccount = emptyMap()).map {
        ThroughputGroup(
            teamId = null, accountId = it.accountId, label = it.label, deliveredMd = it.deliveredMd, deliveredItems = it.deliveredItems,
        )
    }.sortedWith(byLabelThenId({ it.label }, { null }, { it.accountId }))

private fun List<ThroughputTask>.deliveredMd(): Double = fold(BigDecimal.ZERO) { acc, row -> acc + row.md }.toDouble()

private fun List<ThroughputTask>.mdByAccount(): Map<String?, List<BigDecimal>> = groupBy({ it.accountId }, { it.md })

private suspend fun teamThroughputGroups(tasks: List<ThroughputTask>): List<ThroughputGroup> =
    orgGroups(ReportLevel.UNIT, tasks, { it.creditTeamId }, { null }).map { (key, rows) ->
        ThroughputGroup(
            teamId = key.teamId, accountId = null, label = key.label, deliveredMd = rows.deliveredMd(), deliveredItems = rows.size,
        )
    }

private suspend fun userThroughputGroups(tasks: List<ThroughputTask>): List<ThroughputGroup> =
    orgGroups(ReportLevel.TEAM, tasks, { null }, { it.accountId }).map { (key, rows) ->
        ThroughputGroup(
            teamId = null, accountId = key.accountId, label = key.label, deliveredMd = rows.deliveredMd(), deliveredItems = rows.size,
        )
    }

/** UNIT/TEAM sprint view: the whole-team figures off `fact_sprint`, beside the frozen snapshot and the drift flag. */
private suspend fun teamSprints(
    sprintRows: List<SprintRow>,
    byEpic: Map<Pair<UInt, Long>, List<ThroughputEpicSegment>>,
    cost: Map<Pair<UInt, Long>, List<ActualCostLog>>,
): List<ThroughputSprint> {
    val snapshots = fetchSnapshots(sprintRows).associateBy { it.connectionId to it.sprintId }
    return sprintRows.map { row ->
        val frozen = snapshots[row.connectionId to row.sprintId]?.delivered
        ThroughputSprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            deliveredMd = row.delivered.deliveredMd, deliveredItems = row.delivered.deliveredItems,
            actualCostMd = cost[row.connectionId to row.sprintId].orEmpty().costMd(),
            snapshot = frozen, drift = deliveredDrift(row.delivered, frozen), byUser = emptyList(),
            byEpic = byEpic[row.connectionId to row.sprintId].orEmpty(),
        )
    }
}

private fun deliveredDrift(live: ThroughputSnapshot, frozen: ThroughputSnapshot?): Boolean {
    if (frozen == null) return false
    return kotlin.math.abs(live.deliveredMd - frozen.deliveredMd) > SPRINT_DRIFT_TOLERANCE_MD ||
        live.deliveredItems != frozen.deliveredItems
}

/**
 * USER-level sprint view: each sprint narrowed to the deliveries whose `assignee_at_commitment` is one of the [picked] accounts
 * (`done_in_sprint` rows at `estimate_at_done_md` — the SAME predicate `fact_sprint`'s own delivered total sums) and SUMMED over
 * them. `snapshot` is those accounts' FROZEN share, computed by the same kernel ([sprintTotals]) over the sprint's stored
 * `fact_sprint_snapshot.scope` ([fetchFrozenScopes]); `null` for a sprint with no snapshot (D13), and [ThroughputSprint.drift]
 * is the team level's own [deliveredDrift]. [ThroughputSprint.byUser] is each picked account's live share in request order.
 */
private suspend fun userSprints(
    sprintRows: List<SprintRow>,
    picked: PickedUsers,
    byEpic: Map<Pair<UInt, Long>, List<ThroughputEpicSegment>>,
    cost: Map<Pair<UInt, Long>, List<ActualCostLog>>,
): List<ThroughputSprint> {
    if (sprintRows.isEmpty()) return emptyList()
    val s = MetricsTables.FactSprintScope
    val rows = s.select(s.connectionId, s.sprintId, s.assigneeAtCommitment, s.estimateAtDoneMd)
        .where {
            (s.connectionId inList sprintRows.map { it.connectionId }.distinct()) and
                (s.sprintId inList sprintRows.map { it.sprintId }.distinct()) and
                (s.assigneeAtCommitment inList picked.ids) and (s.doneInSprint eq true)
        }
        .toList().groupBy(
            { it[s.connectionId].value to it[s.sprintId] },
            { it[s.assigneeAtCommitment] to (it[s.estimateAtDoneMd] ?: BigDecimal.ZERO) },
        )
    val frozen = fetchFrozenScopes(sprintRows, picked.ids, ::frozenScopeItemsOf) { it.assigneeAtCommitment }
    return sprintRows.map { row ->
        val delivered = rows[row.connectionId to row.sprintId].orEmpty()
        val logs = cost[row.connectionId to row.sprintId].orEmpty()
        val live = ThroughputSnapshot(delivered.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.second }.toDouble(), delivered.size)
        val snapshot = frozen[row.connectionId to row.sprintId]?.let {
            sprintTotals(it).let { totals -> ThroughputSnapshot(totals.deliveredMd, totals.deliveredItems) }
        }
        ThroughputSprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            deliveredMd = live.deliveredMd, deliveredItems = live.deliveredItems, actualCostMd = logs.costMd(),
            snapshot = snapshot, drift = deliveredDrift(live, snapshot),
            byUser = picked.sharesOf(delivered.groupBy({ it.first }, { it.second }), logs.byAuthor()),
            byEpic = byEpic[row.connectionId to row.sprintId].orEmpty(),
        )
    }
}
