package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * Shared test-side helpers of the daily-snapshot report tests (`ReportWipTest`, `ReportBacklogTest`, v0.3.0 M5
 * commit 15): independent readers of `agg_daily_wip` / `agg_daily_flow` (the reports are graded against SUMs
 * taken straight off the rows, never against their own query code), the ISO-day calendar, and inserters for
 * hand-built aggregate rows.
 */

/** One `agg_daily_wip` row as stored. */
internal data class WipRow(
    val scopeKind: String,
    val scopeId: String,
    val day: String,
    val itemKind: String,
    val statusId: String,
    val stage: String,
    val count: Int,
)

/** One `agg_daily_flow` row's backlog columns as stored. */
internal data class FlowBacklogRow(val scopeKind: String, val scopeId: String, val day: String, val items: Int, val md: BigDecimal)

internal suspend fun readWipRows(connId: UInt): List<WipRow> = suspendTransaction(sharedDatabaseForTests()) {
    val w = MetricsTables.AggDailyWip
    w.selectAll().where { w.connectionId eq connId }.toList().map {
        WipRow(it[w.scopeKind], it[w.scopeId], it[w.day], it[w.itemKind], it[w.statusId], it[w.stage], it[w.itemCount])
    }
}

internal suspend fun readFlowBacklogRows(connId: UInt): List<FlowBacklogRow> = suspendTransaction(sharedDatabaseForTests()) {
    val f = MetricsTables.AggDailyFlow
    f.selectAll().where { f.connectionId eq connId }.toList().map {
        FlowBacklogRow(it[f.scopeKind], it[f.scopeId], it[f.day], it[f.backlogItems], it[f.backlogMd])
    }
}

/** One closed, team-mapped sprint as stored: `fact_sprint` (team, completion, delivered MD) joined with `dim_sprint.start_at`. */
internal data class ClosedSprint(val sprintId: Long, val teamId: UInt, val startAt: Long?, val completeAt: Long, val deliveredMd: Double)

/** Every closed team-mapped sprint of [connId], oldest completion first. */
internal suspend fun readClosedSprints(connId: UInt): List<ClosedSprint> = suspendTransaction(sharedDatabaseForTests()) {
    val s = MetricsTables.FactSprint
    val d = MetricsTables.DimSprint
    val starts = d.selectAll().where { d.connectionId eq connId }.toList().associate { it[d.sprintId] to it[d.startAt] }
    s.selectAll().where { s.connectionId eq connId }.toList()
        .filter { it[s.teamId] != null && it[s.completeAt] != null }
        .map {
            ClosedSprint(it[s.sprintId], it[s.teamId]!!.value, starts[it[s.sprintId]], it[s.completeAt]!!, it[s.deliveredMd].toDouble())
        }
        .sortedBy { it.completeAt }
}

/** Every ISO date of the inclusive range [from]..[to], oldest first — the series the reports must list. */
internal fun isoDays(from: String, to: String): List<String> =
    generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(LocalDate.parse(to)) }.map { it.toString() }.toList()

/** The configured-zone ISO day of an instant — the day the fixture's pinned DERIVE `now` falls on, re-derived here. */
internal fun dayOfInstant(atMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate().toString()

internal suspend fun insertWipRows(connId: UInt, rows: List<WipRow>) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsTables.AggDailyWip.batchInsert(rows) {
        this[MetricsTables.AggDailyWip.connectionId] = connId
        this[MetricsTables.AggDailyWip.scopeKind] = it.scopeKind
        this[MetricsTables.AggDailyWip.scopeId] = it.scopeId
        this[MetricsTables.AggDailyWip.day] = it.day
        this[MetricsTables.AggDailyWip.itemKind] = it.itemKind
        this[MetricsTables.AggDailyWip.statusId] = it.statusId
        this[MetricsTables.AggDailyWip.stage] = it.stage
        this[MetricsTables.AggDailyWip.itemCount] = it.count
        this[MetricsTables.AggDailyWip.configRevision] = 1L
    }
}

internal suspend fun deleteWipRows(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsTables.AggDailyWip.deleteWhere { MetricsTables.AggDailyWip.connectionId eq connId }
}

internal suspend fun insertFlowBacklogRows(connId: UInt, rows: List<FlowBacklogRow>) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsTables.AggDailyFlow.batchInsert(rows) {
        this[MetricsTables.AggDailyFlow.connectionId] = connId
        this[MetricsTables.AggDailyFlow.scopeKind] = it.scopeKind
        this[MetricsTables.AggDailyFlow.scopeId] = it.scopeId
        this[MetricsTables.AggDailyFlow.day] = it.day
        this[MetricsTables.AggDailyFlow.backlogItems] = it.items
        this[MetricsTables.AggDailyFlow.backlogMd] = it.md
        this[MetricsTables.AggDailyFlow.configRevision] = 1L
    }
}

internal suspend fun deleteFlowRows(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsTables.AggDailyFlow.deleteWhere { MetricsTables.AggDailyFlow.connectionId eq connId }
}

/** A hand-built closed sprint: only what the backlog-in-sprints mean reads — its team, completion and `delivered_md`. */
internal data class HandSprint(val sprintId: Long, val teamId: UInt, val completeAt: Long?, val deliveredMd: Double)

internal suspend fun insertHandSprints(connId: UInt, sprints: List<HandSprint>) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsTables.FactSprint.batchInsert(sprints) {
        this[MetricsTables.FactSprint.connectionId] = connId
        this[MetricsTables.FactSprint.sprintId] = it.sprintId
        this[MetricsTables.FactSprint.teamId] = it.teamId
        this[MetricsTables.FactSprint.completeAt] = it.completeAt
        this[MetricsTables.FactSprint.deliveredMd] = BigDecimal.valueOf(it.deliveredMd)
        this[MetricsTables.FactSprint.configRevision] = 1L
    }
}

internal suspend fun deleteFactSprints(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsTables.FactSprint.deleteWhere { MetricsTables.FactSprint.connectionId eq connId }
}

/**
 * A SUCCEEDED `derive_runs` row started at [startedAt] — what marks a hand-built connection as "derived through that
 * day" for the snapshot reports' cut-off. `finished_at` is left null so the DERIVE run's own retention prune (which
 * hard-deletes finished rows older than the retention window) can never remove a run dated in the past.
 */
internal suspend fun insertSucceededDerive(connId: UInt, startedAt: Long) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsTables.DeriveRuns.insert {
        it[MetricsTables.DeriveRuns.connectionId] = connId.toInt()
        it[MetricsTables.DeriveRuns.configRevision] = 1L
        it[MetricsTables.DeriveRuns.processingVersion] = 1
        it[MetricsTables.DeriveRuns.startedAt] = startedAt
        it[MetricsTables.DeriveRuns.status] = "SUCCEEDED"
    }
}

internal suspend fun deleteDeriveRuns(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsTables.DeriveRuns.deleteWhere { MetricsTables.DeriveRuns.connectionId eq connId.toInt() }
}

/*
 * Independent readers of the per-person WIP read (brief 04, `ReportWipUserTest`): plain JDBC SQL written in a DIFFERENT shape
 * from `reports/WipUserReport.kt` (correlated `EXISTS` instead of joins), so the endpoint never grades itself.
 */

private fun <T> withJdbc(block: (java.sql.Connection) -> T): T =
    java.sql.DriverManager.getConnection(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password).use(block)

/** Every Jira account that holds an assignee interval or finished a task on [connId] — "every assignee account". */
internal fun readAssigneeAccounts(connId: UInt): List<String> = withJdbc { conn ->
    conn.prepareStatement(
        "SELECT account_id FROM metrics.task_assignee WHERE connection_id = ? AND account_id IS NOT NULL " +
            "UNION SELECT assignee_account_id_at_done FROM metrics.fact_task_delivery " +
            "WHERE connection_id = ? AND assignee_account_id_at_done IS NOT NULL ORDER BY 1",
    ).use { st ->
        st.setInt(1, connId.toInt())
        st.setInt(2, connId.toInt())
        st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
    }
}

/**
 * The level-0 tasks NOBODY holds, per `(day, stage)` over [firstDay]..[lastDay]: open stages — no non-null assignee interval covers the
 * end of the day and the covering sprint (not yet closed) resolves to team [teamId] (`0` = to no team); DONE — finished with no
 * assignee at done and credited to [teamId] (`0` = no credit team). Added to the per-person counts it must give the TEAM aggregate.
 */
internal fun independentUnassignedWip(connId: UInt, teamId: UInt, firstDay: String, lastDay: String): Map<Pair<String, String>, Int> =
    withJdbc { conn ->
        val covers = "%s.valid_from < d.day_end_ms AND (%s.valid_to IS NULL OR %s.valid_to >= d.day_end_ms)"
        fun cover(alias: String) = covers.format(alias, alias, alias)
        val sprintOfTeam = "EXISTS (SELECT 1 FROM norm.work_item_field_intervals fi JOIN metrics.dim_sprint ds " +
            "ON ds.connection_id = fi.connection_id AND ds.sprint_id = fi.value_id::bigint " +
            "WHERE fi.connection_id = t.connection_id AND fi.issue_id = t.issue_id AND fi.field = 'SPRINT' " +
            "AND fi.from_at < d.day_end_ms AND (fi.to_at IS NULL OR fi.to_at >= d.day_end_ms) " +
            "AND (ds.complete_at IS NULL OR ds.complete_at > d.day_end_ms) AND ds.team_id %s)"
        val open = "SELECT d.day, s.stage, COUNT(*) FROM metrics.dim_date d " +
            "JOIN metrics.dim_task t ON t.connection_id = ? AND t.is_subtask = false " +
            "JOIN metrics.item_stage s ON s.connection_id = t.connection_id AND s.issue_id = t.issue_id " +
            "AND s.stage <> 'DONE' AND ${cover("s")} " +
            "WHERE d.day >= ? AND d.day <= ? " +
            "AND NOT EXISTS (SELECT 1 FROM metrics.task_assignee ta WHERE ta.connection_id = t.connection_id " +
            "AND ta.issue_id = t.issue_id AND ta.account_id IS NOT NULL AND ${cover("ta")}) " +
            "AND " + (if (teamId == 0u) "NOT " + sprintOfTeam.format("IS NOT NULL") else sprintOfTeam.format("= $teamId")) +
            " GROUP BY d.day, s.stage"
        val done = "SELECT d.day, 'DONE', COUNT(*) FROM metrics.dim_date d " +
            "JOIN metrics.dim_task t ON t.connection_id = ? AND t.is_subtask = false " +
            "JOIN metrics.item_stage s ON s.connection_id = t.connection_id AND s.issue_id = t.issue_id " +
            "AND s.stage = 'DONE' AND ${cover("s")} " +
            "LEFT JOIN metrics.fact_task_delivery f ON f.connection_id = t.connection_id AND f.issue_id = t.issue_id " +
            "WHERE d.day >= ? AND d.day <= ? AND f.assignee_account_id_at_done IS NULL AND f.credit_team_id " +
            (if (teamId == 0u) "IS NULL" else "= $teamId") + " GROUP BY d.day"
        val counts = HashMap<Pair<String, String>, Int>()
        for (sql in listOf(open, done)) {
            conn.prepareStatement(sql).use { st ->
                st.setInt(1, connId.toInt())
                st.setString(2, firstDay)
                st.setString(3, lastDay)
                st.executeQuery().use { rs -> while (rs.next()) counts.merge(rs.getString(1) to rs.getString(2), rs.getInt(3), Int::plus) }
            }
        }
        counts
    }
