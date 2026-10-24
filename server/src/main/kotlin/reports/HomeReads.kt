package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.teams.TeamService
import java.math.BigDecimal
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.alias
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

/**
 * One DONE level-0 task as the overview's figures and drills read it: only what they need, each gated column folded into the select
 * list by the read ([fetchHomeDoneTasks]) so a row is eleven columns, not the fifteen of the fact row. Each column an R2DBC row decodes
 * costs ~270 bytes of allocation, which is why the shape is what it is.
 */
internal data class HomeDoneTask(
    val connectionId: UInt,
    val issueId: Long,
    val doneAt: Long,
    val creditTeamId: UInt?,
    val accountId: String?,
    /** `actual_md` while the task has worklogs (`has_worklogs`), else `null`: with [isWorkedMd] the D14 "worked" test in one column. */
    val worklogActualMd: BigDecimal?,
    /** `estimate_at_start_md` of a task that started, else `null` (a task that never started has no `actual ÷ estimate at start`, D15). */
    val estimateAtStartMd: BigDecimal?,
    val estimateAtDoneMd: BigDecimal?,
    /** `cycle_working_days` of a task whose `cycle_ms` is set too (a started one), else `null`: what `workingDaysOf` reads. */
    val cycleWorkingDays: BigDecimal?,
    /** `estimate_source` is [NO_ESTIMATE_SOURCE]. */
    val noEstimate: Boolean,
    val estimatedLate: Boolean,
)

/** The D14 "worked" test over [HomeDoneTask.worklogActualMd]: worklogs and an `actual_md` above 0 (`AccuracyTask.isWorked`). */
internal fun BigDecimal?.isWorkedMd(): Boolean = this != null && signum() > 0

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
    val worklogActual = valueWhen(t.hasWorklogs, t.actualMd)
    val startedEstimate = valueWhen(t.startedAt.isNotNull(), t.estimateAtStartMd)
    val cycleDays = valueWhen(t.cycleMs.isNotNull(), t.cycleWorkingDays)
    val noEstimate = (t.estimateSource eq NO_ESTIMATE_SOURCE).alias("no_estimate")
    return t.select(
        t.connectionId, t.issueId, t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, worklogActual, startedEstimate,
        t.estimateAtDoneMd, cycleDays, noEstimate, t.estimatedLate,
    ).where { predicate }.map {
        HomeDoneTask(
            connectionId = it[t.connectionId].value,
            issueId = it[t.issueId],
            doneAt = it[t.doneAt]!!,
            creditTeamId = it[t.creditTeamId]?.value,
            accountId = it[t.assigneeAccountIdAtDone],
            worklogActualMd = it.getOrNull(worklogActual),
            estimateAtStartMd = it.getOrNull(startedEstimate),
            estimateAtDoneMd = it[t.estimateAtDoneMd],
            cycleWorkingDays = it.getOrNull(cycleDays),
            noEstimate = it[noEstimate],
            estimatedLate = it[t.estimatedLate],
        )
    }.toList()
}

/**
 * `CASE WHEN condition THEN value END`: the column [value] while [condition] holds, else NULL (one decoded column instead of two).
 * Typed as [value] is, so a NOT NULL column's result is read with `getOrNull`. Exposed's own `Case` is not used: it rebuilds a list
 * of its branches on every `columnType` call, which a `ResultRow` makes once per value read (~18 % of this read's allocation).
 */
internal class ValueWhen<T>(private val condition: Expression<*>, private val value: ExpressionWithColumnType<T>) :
    ExpressionWithColumnType<T>() {
    override val columnType: IColumnType<T & Any> = value.columnType

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append("CASE WHEN ")
            append(condition)
            append(" THEN ")
            append(value)
            append(" END")
        }
    }
}

internal fun <T> valueWhen(condition: Expression<*>, value: ExpressionWithColumnType<T>): ExpressionWithColumnType<T> =
    ValueWhen(condition, value)

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
internal fun doneInWindow(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): Op<Boolean> {
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
