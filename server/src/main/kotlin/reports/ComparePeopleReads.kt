package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * Report 18's reads (`.claude/docs/reports.md` "Report 18"): the three fetches compare-people does not borrow from another report --
 * the DONE tasks with the slice keys the work mix groups by, the people's worklogs, and the people's WIP task-days across every team.
 * The cycle, accuracy, adjustment and blocked populations are the OTHER reports' own fetches (`fetchDoneCycleTasks`,
 * `fetchAccuracyTasks`, `fetchAdjustmentTasks`, `fetchBlockedItems`), run with a team-less USER filter. Each function runs inside
 * the caller's report transaction, and the drill reads the same ones.
 */

/** Work done by one person: a level-0 task DONE in the window, priced at done ([md] = the estimate at done, `0` unestimated). */
internal data class CompareDoneTask(
    val connectionId: UInt,
    val issueId: Long,
    val doneAt: Long,
    val accountId: String?,
    val creditTeamId: UInt?,
    /** The estimate at done; `null` = unestimated (an item worth 0 MD, [md]). */
    val estimateMd: BigDecimal?,
    /** `NONE` = no estimate at all (the data-quality `NO_ESTIMATE` predicate). */
    val estimateSource: String,
    val activityType: String,
    /** `null` = uncategorized. */
    val workCategory: String?,
    /** The domain per the request's `domainView` (TASK: the task's own; EPIC: the epic's, else the task's -- A21); `null` = none. */
    val domainKey: String?,
) {
    /** Unestimated counts as an item worth 0 MD (the throughput rule). */
    val md: BigDecimal get() = estimateMd ?: BigDecimal.ZERO
}

/** One worklog of a person, anchored on its `started_at`. [lateMs] is `null` when the creation time is unknown. */
internal data class CompareWorklog(
    val connectionId: UInt,
    val issueId: Long,
    val worklogId: Long,
    val accountId: String?,
    val startedAt: Long,
    val md: BigDecimal,
    val lateMs: Long?,
    val foreignWork: Boolean,
)

/**
 * The people's WIP over the period: [days] the listed calendar days (derived coverage and backfill start applied), [workingDays] those
 * of them that are working days, [taskDays] every (day, task, person) held in a working stage at a day end, [startedAt] the
 * `started_at` of the tasks held on the LAST listed day and [note] why the series is empty or partial.
 */
internal class CompareWip(
    val days: List<LocalDate>,
    val workingDays: List<LocalDate>,
    val taskDays: List<WipTaskDay>,
    val startedAt: Map<Pair<UInt, Long>, Long?>,
    val note: String?,
) {
    /** The last listed day, `null` when nothing is listed. */
    val lastDay: String? get() = days.lastOrNull()?.toString()

    /** The tasks held on the last listed day. */
    val atEnd: List<WipTaskDay> get() = lastDay?.let { last -> taskDays.filter { it.day == last } }.orEmpty()
}

/** The DONE level-0 tasks of the picked people in [window], with the keys the work mix groups by. */
internal suspend fun fetchCompareDoneTasks(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
): List<CompareDoneTask> {
    if (connectionIds.isEmpty()) return emptyList()
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    return t.select(
        t.connectionId, t.issueId, t.doneAt, t.assigneeAccountIdAtDone, t.creditTeamId, t.estimateAtDoneMd, t.estimateSource,
        t.activityType,
        t.workCategory, t.domainKey, t.epicDomainKey,
    ).where { predicate }.toList().map {
        CompareDoneTask(
            connectionId = it[t.connectionId].value,
            issueId = it[t.issueId],
            doneAt = it[t.doneAt]!!,
            accountId = it[t.assigneeAccountIdAtDone],
            creditTeamId = it[t.creditTeamId]?.value,
            estimateMd = it[t.estimateAtDoneMd],
            estimateSource = it[t.estimateSource],
            activityType = it[t.activityType],
            workCategory = it[t.workCategory],
            domainKey = if (filter.domainView == DomainView.EPIC) it[t.epicDomainKey] ?: it[t.domainKey] else it[t.domainKey],
        )
    }
}

/** The picked people's worklogs started in [window] (`worklogSlice`: the slices apply, the team does not). */
internal suspend fun fetchCompareWorklogs(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<CompareWorklog> {
    if (connectionIds.isEmpty()) return emptyList()
    val w = MetricsTables.FactWorklog
    val predicate = worklogSlice(filter, connectionIds, window)
    return w.select(w.connectionId, w.issueId, w.worklogId, w.authorAccountId, w.startedAt, w.md, w.lateMs, w.foreignWork)
        .where { predicate }.toList().map {
            CompareWorklog(
                connectionId = it[w.connectionId].value,
                issueId = it[w.issueId],
                worklogId = it[w.worklogId],
                accountId = it[w.authorAccountId],
                startedAt = it[w.startedAt],
                md = it[w.md],
                lateMs = it[w.lateMs],
                foreignWork = it[w.foreignWork],
            )
        }
}

/**
 * The WIP of the picked people across every team, read at query time (`agg_daily_wip` has no per-person scope): the plan's days
 * ([planSnapshotDays] -- the DERIVE cut-off and the backfill start apply, the note rides along) and one `item_stage` x `task_assignee`
 * statement over them ([readWipUserTaskDays]). Only connections with a successful DERIVE are read, like the WIP report's USER level.
 */
internal suspend fun R2dbcTransaction.fetchCompareWip(scope: ReportScope, filter: ReportFilter): CompareWip {
    val plan = planSnapshotDays(scope, filter, scope.calendar, userNote = null, startAtBackfill = true)
    if (plan.days.isEmpty()) return CompareWip(emptyList(), emptyList(), emptyList(), emptyMap(), plan.note)
    val derived = deriveClocks(scope.connectionIds).keys
    val request = WipTaskDaysRequest(
        scope.connectionIds.filter { it in derived }, accountIds = filter.accountIds,
        firstDay = plan.days.first().toString(), lastDay = plan.days.last().toString(),
    )
    val taskDays = readWipUserTaskDays(request)
    val lastDay = plan.days.last().toString()
    val endTasks = taskDays.filter { it.day == lastDay }.map { it.connectionId to it.issueId }.distinct()
    return CompareWip(
        days = plan.days,
        workingDays = plan.days.filter { scope.calendar.isWorkingDay(it) },
        taskDays = taskDays,
        startedAt = startedAtOf(endTasks),
        note = plan.note,
    )
}

/** `fact_task_delivery.started_at` of [tasks] (`null` = never started); a task missing from the facts is absent. */
private suspend fun startedAtOf(tasks: List<Pair<UInt, Long>>): Map<Pair<UInt, Long>, Long?> {
    if (tasks.isEmpty()) return emptyMap()
    val t = MetricsTables.FactTaskDelivery
    val wanted = tasks.toSet()
    return t.select(t.connectionId, t.issueId, t.startedAt)
        .where { (t.connectionId inList wanted.map { it.first }.distinct()) and (t.issueId inList wanted.map { it.second }.distinct()) }
        .toList().map { (it[t.connectionId].value to it[t.issueId]) to it[t.startedAt] }
        .filter { it.first in wanted }.toMap()
}
