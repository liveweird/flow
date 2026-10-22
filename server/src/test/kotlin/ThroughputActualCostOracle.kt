package ch.nokillswit

import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The INDEPENDENT re-derivation Throughput's actual cost is graded against on the derived stub fixture: raw `fact_worklog`,
 * `fact_task_delivery` (the sub-task -> parent map comes from ITS sub-task rows, not from `dim_task` the report joins),
 * `fact_sprint_scope`, `dim_sprint` and `fact_sprint`, with plain java.time and Kotlin collections -- never the report's own fetches or
 * kernels. The rules are the documented ones (`.claude/docs/reports.md` "Report 2 -- Throughput" -> "Actual cost").
 */
internal class ActualCostOracle private constructor(
    private val zone: ZoneId,
    private val tasks: List<OracleTask>,
    private val parentOf: Map<Long, Long>,
    private val logs: List<OracleLog>,
    private val scopes: Map<Long, Set<Long>>,
    val sprints: List<OracleSprint>,
) {
    class OracleTask(
        val issueId: Long,
        val doneAt: Long?,
        val credit: UInt?,
        val current: UInt?,
        val activityType: String,
    )

    class OracleLog(val issueId: Long, val author: String?, val startedAt: Long, val md: BigDecimal)

    class OracleSprint(val sprintId: Long, val teamId: UInt, val startAt: Long?, val completeAt: Long?)

    private fun owner(issueId: Long): Long = parentOf[issueId] ?: issueId

    private fun List<OracleLog>.sumMd(): Double =
        fold(BigDecimal.ZERO) { acc, log -> acc + log.md }.setScale(2, RoundingMode.HALF_UP).toDouble()

    private fun firstOf(day: LocalDate, bucket: String): LocalDate =
        if (bucket == "MONTH") day.withDayOfMonth(1) else day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun ms(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Per bucket start (ISO date): the rounded MD logged inside the bucket (clipped to `[from, to]` inclusive days) on the items of
     * [team] (`null` = everyone) -- credit team for a done task, current team for an open one -- that were not done before the bucket
     * began (still open, or delivered inside or after it -- started or not), optionally only [authors]' time and only [activityType]'s
     * items.
     */
    fun period(
        from: LocalDate,
        to: LocalDate,
        bucket: String,
        team: UInt? = null,
        authors: Set<String>? = null,
        activityType: String? = null,
    ): Map<String, Double> {
        val windowLo = ms(from)
        val windowHi = ms(to.plusDays(1))
        val result = LinkedHashMap<String, Double>()
        var start = firstOf(from, bucket)
        while (ms(start) < windowHi) {
            val next = if (bucket == "MONTH") start.plusMonths(1) else start.plusWeeks(1)
            val lo = maxOf(ms(start), windowLo)
            val hi = minOf(ms(next), windowHi)
            val items = tasks.filter { t ->
                (team == null || (if (t.doneAt != null) t.credit == team else t.current == team)) &&
                    (activityType == null || t.activityType == activityType) &&
                    (t.doneAt == null || t.doneAt >= lo)
            }.map { it.issueId }.toSet()
            result[start.toString()] = logs.filter { it.startedAt in lo until hi && owner(it.issueId) in items }
                .filter { authors == null || it.author in authors }.sumMd()
            start = next
        }
        return result
    }

    /** The MD logged between the sprint's dates `[start, end)` on the tasks of its FINAL scope (optionally only [authors]' time). */
    fun sprint(sprint: OracleSprint, authors: Set<String>? = null): Double {
        val start = sprint.startAt ?: return 0.0
        val end = sprint.completeAt ?: return 0.0
        val items = scopes[sprint.sprintId].orEmpty()
        return logs.filter { it.startedAt in start until end && owner(it.issueId) in items }
            .filter { authors == null || it.author in authors }.sumMd()
    }

    /** The team with the most sprints: the stub's FLO team. */
    val busiestTeam: UInt get() = sprints.groupBy { it.teamId }.maxByOrNull { it.value.size }!!.key

    val activityTypes: List<String> get() = tasks.map { it.activityType }.distinct().sorted()

    /** The [n] authors who logged the most MD, largest first. */
    fun topAuthors(n: Int): List<String> = logs.filter { it.author != null }.groupBy { it.author!! }
        .entries.sortedByDescending { entry -> entry.value.fold(BigDecimal.ZERO) { acc, log -> acc + log.md } }.take(n).map { it.key }

    companion object {
        suspend fun load(connId: UInt): ActualCostOracle = suspendTransaction(sharedDatabaseForTests()) {
            val zone = ZoneId.of(MetricsSettingsService.Settings.selectAll().toList().single()[MetricsSettingsService.Settings.timeZone])
            val t = MetricsTables.FactTaskDelivery
            val rows = t.selectAll().where { t.connectionId eq connId }.toList()
            val tasks = rows.filter { !it[t.isSubtask] }.map {
                OracleTask(
                    it[t.issueId], it[t.doneAt], it[t.creditTeamId]?.value, it[t.currentTeamId]?.value, it[t.activityType],
                )
            }
            val parentOf = rows.filter { it[t.isSubtask] && it[t.parentTaskId] != null }.associate { it[t.issueId] to it[t.parentTaskId]!! }
            val w = MetricsTables.FactWorklog
            val logs = w.selectAll().where { w.connectionId eq connId }.toList()
                .map { OracleLog(it[w.issueId], it[w.authorAccountId], it[w.startedAt], it[w.md]) }
            val s = MetricsTables.FactSprintScope
            val scopes = s.selectAll().where { (s.connectionId eq connId) and (s.inScopeAtClose eq true) }.toList()
                .groupBy({ it[s.sprintId] }, { it[s.issueId] }).mapValues { it.value.toSet() }
            val starts = MetricsTables.DimSprint.selectAll().where { MetricsTables.DimSprint.connectionId eq connId }.toList()
                .associate { it[MetricsTables.DimSprint.sprintId] to it[MetricsTables.DimSprint.startAt] }
            val fs = MetricsTables.FactSprint
            val sprints = fs.selectAll().where { (fs.connectionId eq connId) and fs.teamId.isNotNull() }.toList()
                .map { OracleSprint(it[fs.sprintId], it[fs.teamId]!!.value, starts[it[fs.sprintId]], it[fs.completeAt]) }
            ActualCostOracle(zone, tasks, parentOf, logs, scopes, sprints)
        }
    }
}
