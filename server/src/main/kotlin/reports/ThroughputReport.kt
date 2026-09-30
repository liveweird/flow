package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
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
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The `bucket` query param: the period view's time resolution. Weeks start Monday; both in the configured zone. */
@Serializable
enum class ThroughputBucket { WEEK, MONTH }

/** A sprint's own delivered figures, live or frozen (`fact_sprint(_snapshot)`). */
@Serializable
data class ThroughputSnapshot(val deliveredMd: Double, val deliveredItems: Int)

/**
 * One sprint's delivered scope (the SPRINT view, Report 2 — `fact_sprint.delivered_md/_items`, priced
 * at the sprint's close). [snapshot]/[drift] mirror velocity's: the frozen `fact_sprint_snapshot`
 * figures (`null` until snapshotted, D13) and whether live differs from frozen.
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
)

/** One time bucket of the PERIOD view. [bucketStart] is the bucket's first day (ISO date, Monday for weeks, the 1st for months). */
@Serializable
data class ThroughputBucketRow(val bucketStart: String, val deliveredMd: Double, val deliveredItems: Int)

/**
 * One org-drill entry of the PERIOD view: a credit team's sums at UNIT level (`teamId` null =
 * UNASSIGNED, [accountId] null) or one assignee-at-done's sums at TEAM level ([accountId]/[label]
 * set; a null [accountId] is the unassigned bucket). Always empty at USER level.
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
): ThroughputReport = suspendTransaction(database) {
    // teamId=0: no sprint ever carries "no team", so the sprint view (and any sprint-relative window) is empty.
    val scope = resolveReportScope(filter, nowMs)
    val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
    val connectionIds = scope.connectionIds
    val sprintRows = scope.sprintRows

    val bySprint = when (filter.level) {
        ReportLevel.USER -> userSprints(sprintRows, requireNotNull(filter.accountId) { "USER level always carries an accountId" })
        else -> teamSprints(sprintRows)
    }.sortedWith(compareBy<ThroughputSprint, Long?>(nullsLast()) { it.completedAt }.thenBy { it.sprintId })

    val window = scope.window
    val tasks = if (window == null) emptyList() else fetchDoneTasks(filter, connectionIds, window)
    val byBucket = if (window == null) emptyList() else bucketRows(tasks, window, zone, bucket)
    val groups = when (filter.level) {
        ReportLevel.UNIT -> teamThroughputGroups(tasks)
        ReportLevel.TEAM -> userThroughputGroups(tasks)
        ReportLevel.USER -> emptyList()
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

private fun bucketRows(tasks: List<DoneTask>, window: Pair<Long, Long>, zone: ZoneId, bucket: ThroughputBucket): List<ThroughputBucketRow> {
    val byStart = tasks.groupBy { bucketStart(it.doneAt, zone, bucket) }
    return bucketStarts(window.first, window.second, zone, bucket).map { start ->
        val rows = byStart[start].orEmpty()
        ThroughputBucketRow(start.toString(), rows.fold(BigDecimal.ZERO) { acc, row -> acc + row.md }.toDouble(), rows.size)
    }
}

private fun List<DoneTask>.deliveredMd(): Double = fold(BigDecimal.ZERO) { acc, row -> acc + row.md }.toDouble()

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
            snapshot = frozen, drift = deliveredDrift(row.delivered, frozen),
        )
    }
}

private fun deliveredDrift(live: ThroughputSnapshot, frozen: ThroughputSnapshot?): Boolean {
    if (frozen == null) return false
    return kotlin.math.abs(live.deliveredMd - frozen.deliveredMd) > SPRINT_DRIFT_TOLERANCE_MD ||
        live.deliveredItems != frozen.deliveredItems
}

/**
 * USER-level sprint view: each sprint narrowed to the deliveries whose `assignee_at_commitment` is
 * [accountId] (`done_in_sprint` rows at `estimate_at_done_md` — the SAME predicate `fact_sprint`'s
 * own delivered total sums). `snapshot`/`drift` are `null`/`false`, velocity's documented narrowing.
 */
private suspend fun userSprints(sprintRows: List<SprintRow>, accountId: String): List<ThroughputSprint> {
    if (sprintRows.isEmpty()) return emptyList()
    val s = MetricsTables.FactSprintScope
    val rows = s.select(s.connectionId, s.sprintId, s.estimateAtDoneMd)
        .where {
            (s.connectionId inList sprintRows.map { it.connectionId }.distinct()) and
                (s.sprintId inList sprintRows.map { it.sprintId }.distinct()) and
                (s.assigneeAtCommitment eq accountId) and (s.doneInSprint eq true)
        }
        .toList().groupBy({ it[s.connectionId].value to it[s.sprintId] }, { it[s.estimateAtDoneMd] ?: BigDecimal.ZERO })
    return sprintRows.map { row ->
        val delivered = rows[row.connectionId to row.sprintId].orEmpty()
        ThroughputSprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            deliveredMd = delivered.fold(BigDecimal.ZERO, BigDecimal::add).toDouble(), deliveredItems = delivered.size,
            snapshot = null, drift = false,
        )
    }
}
