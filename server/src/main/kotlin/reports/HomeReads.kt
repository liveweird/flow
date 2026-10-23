package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.teams.TeamService
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The Home overview's reads (`HomeReport.kt`, `HomeDrill.kt`): inside the caller's `reportTransaction`. The task, epic and open-item
 * reads are the other reports' own fetches (`fetchAccuracyEpics`, `fetchDqEpics`; the open items are counted and sampled in Postgres,
 * `HomeOpenItems.kt`) called under the
 * UNIT default filter; what is new is the ONE done-task read both windows are split from (only the LISTED teams' rows, filtered on
 * `credit_team_id` in SQL; the work of no active team is a count, `countHomeDoneOutside`) and the freshness/team lookups (the last
 * successful sync is the connection row's own `last_sync_succeeded_at`).
 */

/** One DONE level-0 task with every column the overview's figures and drills read (both windows come from one read). */
internal data class HomeDoneTask(
    val connectionId: UInt,
    val issueId: Long,
    val doneAt: Long,
    val creditTeamId: UInt?,
    val accountId: String?,
    val startedAt: Long?,
    val estimateAtStartMd: BigDecimal?,
    val estimateAtDoneMd: BigDecimal?,
    val actualMd: BigDecimal,
    val hasWorklogs: Boolean,
    val cycleMs: Long?,
    val cycleWorkingDays: BigDecimal?,
    val activeMs: Long,
    val estimateSource: String,
    val estimatedLate: Boolean,
) {
    /** The accuracy reports' own row shape, so [ratioAtStartOf]/`isWorked` stay the one definition. */
    fun toAccuracy() = AccuracyTask(
        connectionId, issueId, doneAt, creditTeamId, accountId, startedAt, hasWorklogs, actualMd, estimateAtStartMd, estimateAtDoneMd,
    )

    /** The cycle reports' own row shape, so [workingDaysOf] stays the one definition. */
    fun toCycle() = DoneCycleTask(
        connectionId, issueId, doneAt, creditTeamId, accountId, cycleMs, cycleWorkingDays, activeMs, hasWorklogs, actualMd,
    )
}

/** The level-0 tasks done in [window] (both 90-day windows) and credited to one of [teams], under [filter]'s slice (the UNIT default). */
internal suspend fun fetchHomeDoneTasks(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    teams: Collection<UInt>,
): List<HomeDoneTask> {
    if (connectionIds.isEmpty() || teams.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = doneInWindow(filter, connectionIds, window) and (t.creditTeamId inList teams)
    return t.select(
        t.connectionId, t.issueId, t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, t.startedAt, t.estimateAtStartMd,
        t.estimateAtDoneMd, t.actualMd, t.hasWorklogs, t.cycleMs, t.cycleWorkingDays, t.activeMs, t.estimateSource, t.estimatedLate,
    ).where { predicate }.toList().map {
        HomeDoneTask(
            connectionId = it[t.connectionId].value,
            issueId = it[t.issueId],
            doneAt = it[t.doneAt]!!,
            creditTeamId = it[t.creditTeamId]?.value,
            accountId = it[t.assigneeAccountIdAtDone],
            startedAt = it[t.startedAt],
            estimateAtStartMd = it[t.estimateAtStartMd],
            estimateAtDoneMd = it[t.estimateAtDoneMd],
            actualMd = it[t.actualMd],
            hasWorklogs = it[t.hasWorklogs],
            cycleMs = it[t.cycleMs],
            cycleWorkingDays = it[t.cycleWorkingDays],
            activeMs = it[t.activeMs],
            estimateSource = it[t.estimateSource],
            estimatedLate = it[t.estimatedLate],
        )
    }
}

/**
 * How many level-0 tasks done in [window] belong to no team in [activeTeams] (credited to none, or to an inactive one): the overview's
 * `unassigned.doneTasks`. One `GROUP BY credit_team_id` count (a handful of rows), so the unit's done tasks are never loaded for it.
 */
internal suspend fun countHomeDoneOutside(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    activeTeams: Set<UInt>,
): Int {
    if (connectionIds.isEmpty()) return 0
    val t = MetricsTables.FactTaskDelivery
    val count = t.issueId.count()
    val predicate = doneInWindow(filter, connectionIds, window)
    return t.select(t.creditTeamId, count).where { predicate }.groupBy(t.creditTeamId).toList()
        .filter { row -> row[t.creditTeamId]?.value.let { it == null || it !in activeTeams } }
        .sumOf { it[count] }.toInt()
}

/** The done tasks of the slice in [window] (`[first, second)`), whoever they are credited to. */
private fun doneInWindow(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): Op<Boolean> {
    val t = MetricsTables.FactTaskDelivery
    return taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
}

/** An active team, as the overview lists it. */
internal data class HomeTeamRef(val id: UInt, val name: String)

/** Every ACTIVE team in name order (soft-deleted ones are not listed; `ReportService.filters` lists the same set). */
internal suspend fun fetchHomeTeams(): List<HomeTeamRef> =
    TeamService.Teams.select(TeamService.Teams.id, TeamService.Teams.name)
        .where { TeamService.Teams.active() }
        .orderBy(TeamService.Teams.name)
        .toList().map { HomeTeamRef(it[TeamService.Teams.id].value, it[TeamService.Teams.name]) }

/** An active connection (paused ones included — they still own what they derived). */
internal data class HomeConnection(val id: UInt, val name: String, val lastSyncAt: Long?)

internal suspend fun fetchHomeConnections(): List<HomeConnection> =
    DataSourceService.Connections.let { c ->
        c.select(c.id, c.name, c.lastSyncSucceededAt)
            .where { c.active() }
            .orderBy(c.name)
            .toList().map { HomeConnection(it[c.id].value, it[c.name], it[c.lastSyncSucceededAt]) }
    }

/** Each connection's newest SUCCEEDED DERIVE `finished_at` (a connection that never derived is absent). */
internal suspend fun lastDeriveFinishedAt(connectionIds: List<UInt>): Map<UInt, Long> {
    if (connectionIds.isEmpty()) return emptyMap()
    val runs = MetricsTables.DeriveRuns
    val newest = runs.finishedAt.max()
    return runs.select(runs.connectionId, newest)
        .where { (runs.status eq DERIVE_RUN_SUCCEEDED) and (runs.connectionId inList connectionIds.map { it.toInt() }) }
        .groupBy(runs.connectionId)
        .toList().mapNotNull { row -> row[newest]?.let { row[runs.connectionId].toUInt() to it } }.toMap()
}
