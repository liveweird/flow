package ch.nokillswit.reports

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Sum
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The TASK and WORKLOG side of the data-quality report (report 14, `.claude/docs/measures.md` "Report 14"). Counts and sums are
 * computed in SQL — one grouped query per population (`GROUP BY` team/assignee, conditional aggregates) — and only the capped
 * lists (`ORDER BY … LIMIT 50`) fetch rows, so the cost does not grow with the number of tasks or worklogs. The counting of the
 * grouped rows and the JSON shapes live in `DataQualityAssembly.kt`; every function runs inside the caller's `suspendTransaction`.
 */

private const val DQ_WEEK_MS = 7 * MILLIS_PER_DAY

/** The precision `fact_task_delivery`'s estimate columns carry; a sum over them never needs more integer digits than this. */
private const val ESTIMATE_SUM_PRECISION = 14
private const val ESTIMATE_SCALE = 2

/**
 * A task finding and its predicate over `fact_task_delivery`. [doneOnly] findings only make sense once a task is done, so they
 * read the DONE population alone; the others also read the open started one.
 */
internal enum class TaskFindingKind(val doneOnly: Boolean) {
    WITHOUT_WORKLOGS(true),
    NO_ESTIMATE(false),
    NO_EPIC(false),
    NO_WORK_CATEGORY(false),
    UNASSIGNED(true),
    OUTSIDE_SPRINT(true),
    CROSS_DOMAIN(true),
    ;

    /** [workCategoryConnections]: only tasks of connections with a work-category field can lack a category. */
    fun predicate(workCategoryConnections: Set<UInt>): Op<Boolean> {
        val t = MetricsStore.FactTaskDelivery
        return when (this) {
            WITHOUT_WORKLOGS -> t.hasWorklogs eq false
            NO_ESTIMATE -> t.estimateSource eq "NONE"
            NO_EPIC -> t.epicId.isNull()
            NO_WORK_CATEGORY -> if (workCategoryConnections.isEmpty()) {
                Op.FALSE
            } else {
                (t.connectionId inList workCategoryConnections) and t.workCategory.isNull()
            }
            UNASSIGNED -> t.assigneeAccountIdAtDone.isNull()
            OUTSIDE_SPRINT -> t.sprintIdAtDone.isNull()
            CROSS_DOMAIN -> t.crossDomain eq true
        }
    }
}

/**
 * The read window and org filter every task query shares: [filter], the scoped [connectionIds], the item-anchored [window]
 * (`null` = no DONE task can be in the period) and the connections with a configured work-category field.
 */
internal class TaskScope(
    val filter: ReportFilter,
    val connectionIds: List<UInt>,
    val window: Pair<Long, Long>?,
    val workCategoryConnections: Set<UInt>,
) {
    private val t = MetricsStore.FactTaskDelivery

    /** Level-0 tasks with `done_at` in the period — the D5 credit team and the assignee at done. */
    val doneTasks: Op<Boolean>? = window?.let {
        taskFactSlice(filter, connectionIds) and t.doneAt.isNotNull() and (t.doneAt greaterEq it.first) and (t.doneAt less it.second)
    }

    /** Every open started level-0 task, whatever the period — the CURRENT team and assignee (A25). */
    val openTasks: Op<Boolean> =
        taskFactSlice(filter, connectionIds, openAttribution = true) and t.doneAt.isNull() and t.startedAt.isNotNull()
}

/**
 * One `GROUP BY` row of the task counting: the team/assignee of a DONE ([done]) or open population, its [population] size and,
 * per finding, how many of its tasks match ([counts]) and their estimates in MD ([md]).
 */
internal class TaskAgg(
    val team: UInt?,
    val account: String?,
    val done: Boolean,
    val population: Int,
    val counts: Map<TaskFindingKind, Int>,
    val md: Map<TaskFindingKind, Double>,
)

private fun conditionalCount(condition: Op<Boolean>): Count = Count(Case().When(condition, intLiteral(1)))

private fun conditionalSum(condition: Op<Boolean>, value: Column<BigDecimal?>) =
    Sum(Case().When(condition, value), DecimalColumnType(ESTIMATE_SUM_PRECISION, ESTIMATE_SCALE))

internal suspend fun fetchTaskAggs(scope: TaskScope): List<TaskAgg> {
    if (scope.connectionIds.isEmpty()) return emptyList()
    val done = scope.doneTasks?.let { aggregateTasks(scope, it, done = true) }.orEmpty()
    return done + aggregateTasks(scope, scope.openTasks, done = false)
}

private suspend fun aggregateTasks(scope: TaskScope, population: Op<Boolean>, done: Boolean): List<TaskAgg> {
    val t = MetricsStore.FactTaskDelivery
    val team = if (done) t.creditTeamId else t.currentTeamId
    val account = if (done) t.assigneeAccountIdAtDone else t.currentAssigneeAccountId
    val estimate = if (done) t.estimateAtDoneMd else t.estimateCurrentMd
    val kinds = TaskFindingKind.entries.filter { done || !it.doneOnly }
    val size = Count(t.issueId)
    val counts = kinds.associateWith { conditionalCount(it.predicate(scope.workCategoryConnections)) }
    val sums = kinds.associateWith { conditionalSum(it.predicate(scope.workCategoryConnections), estimate) }
    val columns: List<Expression<*>> = listOf(team, account, size) + counts.values + sums.values
    return t.select(columns).where { population }.groupBy(team, account).toList().map { row ->
        TaskAgg(
            team = row[team]?.value, account = row[account], done = done, population = row[size].toInt(),
            counts = counts.mapValues { (_, expression) -> row[expression].toInt() },
            md = sums.mapValues { (_, expression) -> row[expression]?.toDouble() ?: 0.0 },
        )
    }
}

/** One task a capped list points at, attributed by [done] state (credit/assignee at done, else current). */
internal data class DqTask(
    val connectionId: UInt,
    val issueId: Long,
    val issueKey: String,
    val startedAt: Long?,
    val doneAt: Long?,
    val team: UInt?,
    val account: String?,
    val estimateMd: BigDecimal?,
)

/**
 * The first [DATA_QUALITY_MAX_ITEMS] tasks matching [kind]: DONE ones newest first, then open ones oldest first (then
 * connection and issue id). A done-only kind reads the DONE population alone.
 */
internal suspend fun fetchTaskItems(scope: TaskScope, kind: TaskFindingKind): List<DqTask> {
    val t = MetricsStore.FactTaskDelivery
    val population = when {
        scope.doneTasks == null -> if (kind.doneOnly) return emptyList() else scope.openTasks
        kind.doneOnly -> scope.doneTasks
        else -> scope.doneTasks or scope.openTasks
    }
    val predicate = population and kind.predicate(scope.workCategoryConnections)
    return t.select(
        t.connectionId, t.issueId, t.issueKey, t.startedAt, t.doneAt, t.creditTeamId, t.assigneeAccountIdAtDone, t.currentTeamId,
        t.currentAssigneeAccountId, t.estimateAtDoneMd, t.estimateCurrentMd,
    ).where { predicate }
        .orderBy(
            t.doneAt.isNull() to SortOrder.ASC, t.doneAt to SortOrder.DESC_NULLS_LAST, t.startedAt to SortOrder.ASC,
            t.connectionId to SortOrder.ASC, t.issueId to SortOrder.ASC,
        )
        .limit(DATA_QUALITY_MAX_ITEMS).toList().map { row ->
            val done = row[t.doneAt] != null
            DqTask(
                connectionId = row[t.connectionId].value, issueId = row[t.issueId], issueKey = row[t.issueKey],
                startedAt = row[t.startedAt], doneAt = row[t.doneAt],
                team = (if (done) row[t.creditTeamId] else row[t.currentTeamId])?.value,
                account = if (done) row[t.assigneeAccountIdAtDone] else row[t.currentAssigneeAccountId],
                estimateMd = if (done) row[t.estimateAtDoneMd] else row[t.estimateCurrentMd],
            )
        }
}

/**
 * DONE tasks of the period that were done inside a sprint with no team (`sprint_id_at_done` set, `sprint_team_id_at_done`
 * null): `(connection, sprint id)` → count. The caller attributes them to unmapped boards or reports the residual.
 */
internal suspend fun fetchDoneInTeamlessSprints(scope: TaskScope): Map<Pair<UInt, Long>, Int> {
    val done = scope.doneTasks ?: return emptyMap()
    if (scope.connectionIds.isEmpty()) return emptyMap()
    val t = MetricsStore.FactTaskDelivery
    val size = Count(t.issueId)
    return t.select(t.connectionId, t.sprintIdAtDone, size)
        .where { done and t.sprintIdAtDone.isNotNull() and t.sprintTeamIdAtDone.isNull() }
        .groupBy(t.connectionId, t.sprintIdAtDone).toList()
        .associate { (it[t.connectionId].value to it[t.sprintIdAtDone]!!) to it[size].toInt() }
}

/** One worklog a capped list points at, attributed to its author and the author's team as-was at `started_at`. */
internal data class DqWorklog(
    val connectionId: UInt,
    val issueId: Long,
    val worklogId: Long,
    val account: String?,
    val team: UInt?,
    val startedAt: Long,
    /** `created_at − started_at` clamped at 0; null only when `created_at` is unknown. */
    val lateMs: Long?,
)

/**
 * The `fact_worklog` rows of the period: `started_at` in [window], the author's team (`teamId=0` = no team) and account, and
 * the domain per `domainView` (TASK: the task's; EPIC: the epic's, else the task's — A21), `activityType`, `workCategory`.
 */
internal fun worklogSlice(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): Op<Boolean> {
    val w = MetricsStore.FactWorklog
    var predicate: Op<Boolean> = (w.connectionId inList connectionIds) and
        (w.startedAt greaterEq window.first) and (w.startedAt less window.second)
    filter.teamId?.let { team ->
        predicate = predicate and if (team == UNASSIGNED_TEAM_ID) w.authorTeamId.isNull() else (w.authorTeamId eq team)
    }
    filter.accountId?.let { predicate = predicate and (w.authorAccountId eq it) }
    filter.domain?.let { domain ->
        predicate = predicate and when (filter.domainView) {
            DomainView.TASK -> w.taskDomainKey eq domain
            DomainView.EPIC -> (w.epicDomainKey eq domain) or (w.epicDomainKey.isNull() and (w.taskDomainKey eq domain))
        }
    }
    filter.activityType?.let { predicate = predicate and (w.activityType eq it) }
    filter.workCategory?.let { category ->
        predicate = predicate and if (category == UNCATEGORIZED) w.workCategory.isNull() else (w.workCategory eq category)
    }
    return predicate
}

/** One `GROUP BY` row of the worklog counting: an author's team and account, with the counts and MD of their worklogs. */
internal class WorklogAgg(
    val team: UInt?,
    val account: String?,
    val worklogs: Int,
    val md: Double,
    /** Worklogs whose creation time is known (`late_ms` set). */
    val measurable: Int,
    val over1Day: Int,
    val over7Days: Int,
)

/** The worklog read: the period's `fact_worklog` slice, or nothing without a window or a connection. */
internal class WorklogScope(val filter: ReportFilter, val connectionIds: List<UInt>, val window: Pair<Long, Long>?) {
    val predicate: Op<Boolean>? = window?.takeIf { connectionIds.isNotEmpty() }?.let { worklogSlice(filter, connectionIds, it) }
}

internal suspend fun fetchWorklogAggs(scope: WorklogScope): List<WorklogAgg> {
    val slice = scope.predicate ?: return emptyList()
    val w = MetricsStore.FactWorklog
    val size = Count(w.worklogId)
    val md = Sum(w.md, w.md.columnType)
    val measurable = Count(w.lateMs)
    val over1 = Count(Case().When(w.lateMs greater MILLIS_PER_DAY, intLiteral(1)))
    val over7 = Count(Case().When(w.lateMs greater DQ_WEEK_MS, intLiteral(1)))
    return w.select(w.authorTeamId, w.authorAccountId, size, md, measurable, over1, over7)
        .where { slice }.groupBy(w.authorTeamId, w.authorAccountId).toList().map {
            WorklogAgg(
                team = it[w.authorTeamId]?.value, account = it[w.authorAccountId], worklogs = it[size].toInt(),
                md = it[md]?.toDouble() ?: 0.0, measurable = it[measurable].toInt(), over1Day = it[over1].toInt(),
                over7Days = it[over7].toInt(),
            )
        }
}

/** `late_ms` of every measurable worklog of the period — one numeric column, the input of the lateness `Distribution`. */
internal suspend fun fetchLatenessMs(scope: WorklogScope): List<Long> {
    val slice = scope.predicate ?: return emptyList()
    val w = MetricsStore.FactWorklog
    return w.select(w.lateMs).where { slice and w.lateMs.isNotNull() }.toList().map { it[w.lateMs]!! }
}

/** The [DATA_QUALITY_MAX_ITEMS] latest-logged worklogs (`late_ms > 0`), latest first. */
internal suspend fun fetchWorstWorklogs(scope: WorklogScope): List<DqWorklog> {
    val slice = scope.predicate ?: return emptyList()
    val w = MetricsStore.FactWorklog
    return w.select(w.connectionId, w.issueId, w.worklogId, w.authorAccountId, w.authorTeamId, w.startedAt, w.lateMs)
        .where { slice and (w.lateMs greater 0L) }
        .orderBy(w.lateMs to SortOrder.DESC, w.startedAt to SortOrder.DESC, w.worklogId to SortOrder.ASC)
        .limit(DATA_QUALITY_MAX_ITEMS).toList().map {
            DqWorklog(
                connectionId = it[w.connectionId].value, issueId = it[w.issueId], worklogId = it[w.worklogId],
                account = it[w.authorAccountId], team = it[w.authorTeamId]?.value, startedAt = it[w.startedAt], lateMs = it[w.lateMs],
            )
        }
}

/** Working days one team member was on the team's roster within the read window (`team_membership` × the working calendar). */
internal data class MemberDays(val teamId: UInt, val accountId: String, val days: Double)

/**
 * The member-day denominator of "logged hours per member per working day": every dated membership row (D1) overlapping
 * `[window.first, min(window.second, nowMs))` — the future has no logged hours to compare — clipped to it and counted in
 * working days by the configured calendar. A real `teamId` reads that team, `teamId=0` (UNASSIGNED) has no roster, UNIT
 * reads every team, `accountId` narrows to that member. No window means no days.
 */
internal suspend fun fetchMemberDays(
    filter: ReportFilter,
    calendar: WorkingCalendar,
    window: Pair<Long, Long>?,
    nowMs: Long,
): List<MemberDays> {
    if (window == null || filter.teamId == UNASSIGNED_TEAM_ID) return emptyList()
    val start = window.first
    val end = minOf(window.second, nowMs)
    if (end <= start) return emptyList()
    val m = TeamMembershipService.TeamMembership
    var predicate: Op<Boolean> = (m.validFrom less end) and (m.validTo.isNull() or (m.validTo greater start))
    filter.teamId?.let { predicate = predicate and (m.teamId eq it) }
    filter.accountId?.let { predicate = predicate and (m.accountId eq it) }
    return m.select(m.teamId, m.accountId, m.validFrom, m.validTo).where { predicate }.toList().map {
        val from = maxOf(it[m.validFrom], start)
        val to = minOf(it[m.validTo] ?: end, end)
        MemberDays(it[m.teamId].value, it[m.accountId], calendar.workingDaysBetween(from, to))
    }
}

/** One task a finding points at: enough to find it in Jira and to see whose it is ([doneAt] null = still open). */
@Serializable
data class TaskRef(
    val issueKey: String,
    val summary: String?,
    val teamId: UInt?,
    val assigneeAccountId: String?,
    val assignee: String?,
    val doneAt: Long?,
    val startedAt: Long?,
    val estimateMd: Double?,
)

/** A count split by the two task populations: DONE in the period and currently open (started). */
@Serializable
data class DoneOpen(val done: Int, val open: Int)

/**
 * One task finding: [done] DONE tasks of the period plus [open] open started ones (open is 0 for a finding that only makes sense
 * once a task is done), [total] their sum, [md] the estimates of the matching tasks (at done / current; unestimated
 * counts 0) and the first [DATA_QUALITY_MAX_ITEMS] of them in [items] — DONE ones newest first, then open ones by age.
 */
@Serializable
data class TaskFinding(val done: Int, val open: Int, val total: Int, val md: Double, val items: List<TaskRef>)
