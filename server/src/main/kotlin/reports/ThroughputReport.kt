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
 * included); [byUser] is empty at UNIT/TEAM level.
 */
@Serializable
data class ThroughputSprint(
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val completedAt: Long?,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val snapshot: ThroughputSnapshot?,
    val drift: Boolean,
    val byUser: List<ThroughputUserShare>,
)

/** One picked account's own delivered share of a sprint or bucket at USER level — zeros included. */
@Serializable
data class ThroughputUserShare(val accountId: String, val label: String, val deliveredMd: Double, val deliveredItems: Int)

/**
 * One time bucket of the PERIOD view. [bucketStart] is the bucket's first day (ISO date, Monday for weeks, the 1st for months).
 * [byUser] splits the bucket per picked account at USER level (request order, zeros included); empty at UNIT/TEAM level.
 */
@Serializable
data class ThroughputBucketRow(
    val bucketStart: String,
    val deliveredMd: Double,
    val deliveredItems: Int,
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

/** One level-0 task done inside the window — just what the period view aggregates. */
private data class DoneTask(val doneAt: Long, val md: BigDecimal, val creditTeamId: UInt?, val accountId: String?)

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
    val bySprint = when (filter.level) {
        ReportLevel.USER -> userSprints(sprintRows, picked)
        else -> teamSprints(sprintRows)
    }.sortedWith(compareBy<ThroughputSprint, Long?>(nullsLast()) { it.completedAt }.thenBy { it.sprintId })

    val window = scope.window
    val tasks = if (window == null) emptyList() else fetchDoneTasks(filter, connectionIds, window)
    val byBucket = if (window == null) emptyList() else bucketRows(tasks, window, zone, bucket, picked)
    val groups = when (filter.level) {
        ReportLevel.UNIT -> teamThroughputGroups(tasks)
        ReportLevel.TEAM -> userThroughputGroups(tasks)
        ReportLevel.USER -> pickedUserGroups(tasks, picked)
    }
    ThroughputReport(scope.meta, bySprint, byBucket, groups)
}

private suspend fun fetchDoneTasks(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<DoneTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(t.doneAt, t.estimateAtDoneMd, t.creditTeamId, t.assigneeAccountIdAtDone).where { predicate }.toList().map {
        DoneTask(
            doneAt = it[t.doneAt]!!,
            // Unestimated counts as an item worth 0 MD (measures.md: "unestimated -> 0 MD, counted").
            md = it[t.estimateAtDoneMd] ?: BigDecimal.ZERO,
            creditTeamId = it[t.creditTeamId]?.value,
            accountId = it[t.assigneeAccountIdAtDone],
        )
    }
}

private fun bucketRows(
    tasks: List<DoneTask>,
    window: Pair<Long, Long>,
    zone: ZoneId,
    bucket: ThroughputBucket,
    picked: PickedUsers,
): List<ThroughputBucketRow> {
    val byStart = tasks.groupBy { bucketStart(it.doneAt, zone, bucket) }
    return bucketStarts(window.first, window.second, zone, bucket).map { start ->
        val rows = byStart[start].orEmpty()
        ThroughputBucketRow(start.toString(), rows.deliveredMd(), rows.size, picked.sharesOf(rows.mdByAccount()))
    }
}

/**
 * The picked accounts of a USER-level request: their ids in request order and their Jira display names (the account id when
 * none is known). Both empty below USER level, so [sharesOf] answers an empty `byUser` there.
 */
private class PickedUsers(val ids: List<String>, val labels: Map<String, String>) {
    fun label(id: String): String = labels[id] ?: id

    /** One share per picked account, in request order, from each account's delivered `md` list (zeros for an account with none). */
    fun sharesOf(mdByAccount: Map<String?, List<BigDecimal>>): List<ThroughputUserShare> = ids.map { id ->
        val md = mdByAccount[id].orEmpty()
        ThroughputUserShare(id, label(id), md.fold(BigDecimal.ZERO, BigDecimal::add).toDouble(), md.size)
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
private fun pickedUserGroups(tasks: List<DoneTask>, picked: PickedUsers): List<ThroughputGroup> =
    picked.sharesOf(tasks.mdByAccount()).map {
        ThroughputGroup(
            teamId = null, accountId = it.accountId, label = it.label, deliveredMd = it.deliveredMd, deliveredItems = it.deliveredItems,
        )
    }.sortedWith(byLabelThenId({ it.label }, { null }, { it.accountId }))

private fun List<DoneTask>.deliveredMd(): Double = fold(BigDecimal.ZERO) { acc, row -> acc + row.md }.toDouble()

private fun List<DoneTask>.mdByAccount(): Map<String?, List<BigDecimal>> = groupBy({ it.accountId }, { it.md })

private suspend fun teamThroughputGroups(tasks: List<DoneTask>): List<ThroughputGroup> =
    orgGroups(ReportLevel.UNIT, tasks, { it.creditTeamId }, { null }).map { (key, rows) ->
        ThroughputGroup(
            teamId = key.teamId, accountId = null, label = key.label, deliveredMd = rows.deliveredMd(), deliveredItems = rows.size,
        )
    }

private suspend fun userThroughputGroups(tasks: List<DoneTask>): List<ThroughputGroup> =
    orgGroups(ReportLevel.TEAM, tasks, { null }, { it.accountId }).map { (key, rows) ->
        ThroughputGroup(
            teamId = null, accountId = key.accountId, label = key.label, deliveredMd = rows.deliveredMd(), deliveredItems = rows.size,
        )
    }

/** UNIT/TEAM sprint view: the whole-team figures off `fact_sprint`, beside the frozen snapshot and the drift flag. */
private suspend fun teamSprints(sprintRows: List<SprintRow>): List<ThroughputSprint> {
    val snapshots = fetchSnapshots(sprintRows).associateBy { it.connectionId to it.sprintId }
    return sprintRows.map { row ->
        val frozen = snapshots[row.connectionId to row.sprintId]?.delivered
        ThroughputSprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            deliveredMd = row.delivered.deliveredMd, deliveredItems = row.delivered.deliveredItems,
            snapshot = frozen, drift = deliveredDrift(row.delivered, frozen), byUser = emptyList(),
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
private suspend fun userSprints(sprintRows: List<SprintRow>, picked: PickedUsers): List<ThroughputSprint> {
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
        val live = ThroughputSnapshot(delivered.fold(BigDecimal.ZERO) { acc, entry -> acc + entry.second }.toDouble(), delivered.size)
        val snapshot = frozen[row.connectionId to row.sprintId]?.let {
            sprintTotals(it).let { totals -> ThroughputSnapshot(totals.deliveredMd, totals.deliveredItems) }
        }
        ThroughputSprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            deliveredMd = live.deliveredMd, deliveredItems = live.deliveredItems,
            snapshot = snapshot, drift = deliveredDrift(live, snapshot),
            byUser = picked.sharesOf(delivered.groupBy({ it.first }, { it.second })),
        )
    }
}
