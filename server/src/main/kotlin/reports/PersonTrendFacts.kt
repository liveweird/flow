package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.Query
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager

/*
 * Person trend's reads of `fact_task_delivery` and `fact_worklog` (report 20, build-times.md WHY 46 and 47). Compare people reads four
 * populations from `fact_task_delivery` with four statements -- the done tasks ([fetchCompareDoneTasks]), the cycle tasks
 * ([fetchDoneCycleTasks]), the accuracy tasks ([fetchAccuracyTasks]) and the blocked items ([fetchBlockedItems]) -- over the SAME predicate
 * (`taskFactSlice`, a done_at in the window), so the same rows. One statement over the union of the four column lists reads each row once
 * (20 columns instead of 39) and builds the four objects from it. `PersonTrendFactsTest` pins the equality with the four separate fetches
 * and with [fetchCompareWorklogs].
 *
 * WHY 47: the rows are decoded BY POSITION, not through Exposed's `ResultRow` (the idiom of `HomeDoneSql`, WHY 38): the SQL is rendered
 * once, each row is a plain array read column by column, and the builders below index it with positions taken from the very column lists
 * the statement selects. A team of 15 reads ~8,300 task rows and as many worklogs per year, and the `ResultRow` path costs a mapper call
 * and an expression lookup per cell.
 */

/** Runs [query] and returns each row as a plain array of its [width] cells, read by position (no `ResultRow`, no per-cell lookup). */
private suspend fun positionalRows(query: Query, width: Int): List<Array<Any?>> {
    val tx = TransactionManager.current()
    return tx.exec(query.prepareSQL(tx, true), query.arguments().single(), StatementType.SELECT) { row ->
        Array(width) { row.get(it) }
    }?.toList().orEmpty().filterNotNull()
}

private fun Any?.uint(): UInt = (this as Number).toInt().toUInt()

private fun Any?.uintOrNull(): UInt? = (this as Number?)?.toInt()?.toUInt()

private fun Any?.long(): Long = (this as Number).toLong()

private fun Any?.longOrNull(): Long? = (this as Number?)?.toLong()

private fun Any?.int(): Int = (this as Number).toInt()

private fun Any?.decimal(): BigDecimal = this as BigDecimal

private fun Any?.decimalOrNull(): BigDecimal? = this as BigDecimal?

/** The four populations of the delivered tasks of the filter's accounts in a window, each exactly what its own fetch returns. */
internal class DeliveredFacts(
    val done: List<CompareDoneTask>,
    val cycle: List<DoneCycleTask>,
    val accuracy: List<AccuracyTask>,
    val blocked: List<DoneItem>,
)

/** The union of the four populations' columns: the statement selects it, the builders index into it. */
private val FACT_COLUMNS: List<Expression<*>> =
    (COMPARE_DONE_COLUMNS + DONE_CYCLE_COLUMNS + ACCURACY_COLUMNS + DONE_TASK_COLUMNS).distinct()

/** [fetchCompareDoneTasks], [fetchDoneCycleTasks], [fetchAccuracyTasks] and [fetchBlockedItems] (`TASK`) of [window] from one statement. */
internal suspend fun fetchDeliveredFacts(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): DeliveredFacts {
    if (connectionIds.isEmpty()) return DeliveredFacts(emptyList(), emptyList(), emptyList(), emptyList())
    val t = MetricsTables.FactTaskDelivery
    val predicate = taskFactSlice(filter, connectionIds) and
        t.doneAt.isNotNull() and (t.doneAt greaterEq window.first) and (t.doneAt less window.second)
    val at = TaskPositions()
    val rows = positionalRows(t.select(FACT_COLUMNS).where { predicate }, FACT_COLUMNS.size)
    val done = ArrayList<CompareDoneTask>(rows.size)
    val cycle = ArrayList<DoneCycleTask>(rows.size)
    val accuracy = ArrayList<AccuracyTask>(rows.size)
    val blocked = ArrayList<DoneItem>(rows.size)
    for (row in rows) {
        done += at.doneOf(row, filter.domainView)
        cycle += at.cycleOf(row)
        accuracy += at.accuracyOf(row)
        blocked += at.blockedOf(row)
    }
    return DeliveredFacts(done, cycle, accuracy, blocked)
}

/**
 * The positions of the `fact_task_delivery` columns in [FACT_COLUMNS], one field per column, and the four builders: each reads the same
 * cells in the same way as its `…Of(ResultRow)` twin ([compareDoneTaskOf], [doneCycleTaskOf], [accuracyTaskOf], [doneTaskItemOf]).
 */
private class TaskPositions {
    private val t = MetricsTables.FactTaskDelivery
    private fun at(column: Expression<*>): Int = FACT_COLUMNS.indexOf(column).also { check(it >= 0) { "$column is not selected" } }
    private val connectionId = at(t.connectionId)
    private val issueId = at(t.issueId)
    private val doneAt = at(t.doneAt)
    private val account = at(t.assigneeAccountIdAtDone)
    private val creditTeamId = at(t.creditTeamId)
    private val estimateAtDone = at(t.estimateAtDoneMd)
    private val estimateAtStart = at(t.estimateAtStartMd)
    private val estimateSource = at(t.estimateSource)
    private val activityType = at(t.activityType)
    private val workCategory = at(t.workCategory)
    private val domainKey = at(t.domainKey)
    private val epicDomainKey = at(t.epicDomainKey)
    private val reopenCount = at(t.reopenCount)
    private val cycleMs = at(t.cycleMs)
    private val cycleWorkingDays = at(t.cycleWorkingDays)
    private val activeMs = at(t.activeMs)
    private val hasWorklogs = at(t.hasWorklogs)
    private val actualMd = at(t.actualMd)
    private val startedAt = at(t.startedAt)
    private val blockedWorkingDays = at(t.blockedWorkingDays)

    fun doneOf(row: Array<Any?>, domainView: DomainView) = CompareDoneTask(
        connectionId = row[connectionId].uint(),
        issueId = row[issueId].long(),
        doneAt = row[doneAt].long(),
        accountId = row[account] as String?,
        creditTeamId = row[creditTeamId].uintOrNull(),
        estimateMd = row[estimateAtDone].decimalOrNull(),
        estimateSource = row[estimateSource] as String,
        activityType = row[activityType] as String,
        workCategory = row[workCategory] as String?,
        domainKey = (if (domainView == DomainView.EPIC) row[epicDomainKey] ?: row[domainKey] else row[domainKey]) as String?,
        reopenCount = row[reopenCount].int(),
    )

    fun cycleOf(row: Array<Any?>) = DoneCycleTask(
        connectionId = row[connectionId].uint(),
        issueId = row[issueId].long(),
        doneAt = row[doneAt].long(),
        creditTeamId = row[creditTeamId].uintOrNull(),
        accountId = row[account] as String?,
        cycleMs = row[cycleMs].longOrNull(),
        cycleWorkingDays = row[cycleWorkingDays].decimalOrNull(),
        activeMs = row[activeMs].long(),
        hasWorklogs = row[hasWorklogs] as Boolean,
        actualMd = row[actualMd].decimal(),
    )

    fun accuracyOf(row: Array<Any?>) = AccuracyTask(
        connectionId = row[connectionId].uint(),
        issueId = row[issueId].long(),
        doneAt = row[doneAt].long(),
        creditTeamId = row[creditTeamId].uintOrNull(),
        accountId = row[account] as String?,
        startedAt = row[startedAt].longOrNull(),
        hasWorklogs = row[hasWorklogs] as Boolean,
        actualMd = row[actualMd].decimal(),
        estimateAtStartMd = row[estimateAtStart].decimalOrNull(),
        estimateAtDoneMd = row[estimateAtDone].decimalOrNull(),
    )

    fun blockedOf(row: Array<Any?>) = DoneItem(
        KIND_TASK, row[connectionId].uint(), row[issueId].long(), row[creditTeamId].uintOrNull(), row[account] as String?,
        row[doneAt].long(), row[blockedWorkingDays].decimal().toDouble(), row[cycleWorkingDays].decimalOrNull()?.toDouble(),
    )
}

/** [fetchCompareWorklogs] (the same predicate and columns) read by position: Person trend's twin of Compare people's worklog fetch. */
internal suspend fun fetchTrendWorklogs(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<CompareWorklog> {
    if (connectionIds.isEmpty()) return emptyList()
    val w = MetricsTables.FactWorklog
    val predicate = worklogSlice(filter, connectionIds, window)
    val columns = listOf(w.connectionId, w.issueId, w.worklogId, w.authorAccountId, w.startedAt, w.md, w.lateMs, w.foreignWork)
    return positionalRows(w.select(columns).where { predicate }, columns.size).map { row ->
        CompareWorklog(
            connectionId = row[0].uint(),
            issueId = row[1].long(),
            worklogId = row[2].long(),
            accountId = row[3] as String?,
            startedAt = row[4].long(),
            md = row[5].decimal(),
            lateMs = row[6].longOrNull(),
            foreignWork = row[7] as Boolean,
        )
    }
}
