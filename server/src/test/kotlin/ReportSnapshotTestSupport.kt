package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
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
    val w = MetricsStore.AggDailyWip
    w.selectAll().where { w.connectionId eq connId }.toList().map {
        WipRow(it[w.scopeKind], it[w.scopeId], it[w.day], it[w.itemKind], it[w.statusId], it[w.stage], it[w.itemCount])
    }
}

internal suspend fun readFlowBacklogRows(connId: UInt): List<FlowBacklogRow> = suspendTransaction(sharedDatabaseForTests()) {
    val f = MetricsStore.AggDailyFlow
    f.selectAll().where { f.connectionId eq connId }.toList().map {
        FlowBacklogRow(it[f.scopeKind], it[f.scopeId], it[f.day], it[f.backlogItems], it[f.backlogMd])
    }
}

/** One closed, team-mapped sprint as stored: `fact_sprint` (team, completion, delivered MD) joined with `dim_sprint.start_at`. */
internal data class ClosedSprint(val sprintId: Long, val teamId: UInt, val startAt: Long?, val completeAt: Long, val deliveredMd: Double)

/** Every closed team-mapped sprint of [connId], oldest completion first. */
internal suspend fun readClosedSprints(connId: UInt): List<ClosedSprint> = suspendTransaction(sharedDatabaseForTests()) {
    val s = MetricsStore.FactSprint
    val d = MetricsStore.DimSprint
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
    MetricsStore.AggDailyWip.batchInsert(rows) {
        this[MetricsStore.AggDailyWip.connectionId] = connId
        this[MetricsStore.AggDailyWip.scopeKind] = it.scopeKind
        this[MetricsStore.AggDailyWip.scopeId] = it.scopeId
        this[MetricsStore.AggDailyWip.day] = it.day
        this[MetricsStore.AggDailyWip.itemKind] = it.itemKind
        this[MetricsStore.AggDailyWip.statusId] = it.statusId
        this[MetricsStore.AggDailyWip.stage] = it.stage
        this[MetricsStore.AggDailyWip.itemCount] = it.count
        this[MetricsStore.AggDailyWip.configRevision] = 1L
    }
}

internal suspend fun deleteWipRows(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsStore.AggDailyWip.deleteWhere { MetricsStore.AggDailyWip.connectionId eq connId }
}

internal suspend fun insertFlowBacklogRows(connId: UInt, rows: List<FlowBacklogRow>) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsStore.AggDailyFlow.batchInsert(rows) {
        this[MetricsStore.AggDailyFlow.connectionId] = connId
        this[MetricsStore.AggDailyFlow.scopeKind] = it.scopeKind
        this[MetricsStore.AggDailyFlow.scopeId] = it.scopeId
        this[MetricsStore.AggDailyFlow.day] = it.day
        this[MetricsStore.AggDailyFlow.backlogItems] = it.items
        this[MetricsStore.AggDailyFlow.backlogMd] = it.md
        this[MetricsStore.AggDailyFlow.configRevision] = 1L
    }
}

internal suspend fun deleteFlowRows(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsStore.AggDailyFlow.deleteWhere { MetricsStore.AggDailyFlow.connectionId eq connId }
}

/** A hand-built closed sprint: only what the backlog-in-sprints mean reads — its team, completion and `delivered_md`. */
internal data class HandSprint(val sprintId: Long, val teamId: UInt, val completeAt: Long?, val deliveredMd: Double)

internal suspend fun insertHandSprints(connId: UInt, sprints: List<HandSprint>) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsStore.FactSprint.batchInsert(sprints) {
        this[MetricsStore.FactSprint.connectionId] = connId
        this[MetricsStore.FactSprint.sprintId] = it.sprintId
        this[MetricsStore.FactSprint.teamId] = it.teamId
        this[MetricsStore.FactSprint.completeAt] = it.completeAt
        this[MetricsStore.FactSprint.deliveredMd] = BigDecimal.valueOf(it.deliveredMd)
        this[MetricsStore.FactSprint.configRevision] = 1L
    }
}

internal suspend fun deleteFactSprints(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsStore.FactSprint.deleteWhere { MetricsStore.FactSprint.connectionId eq connId }
}

/**
 * A SUCCEEDED `derive_runs` row started at [startedAt] — what marks a hand-built connection as "derived through that
 * day" for the snapshot reports' cut-off. `finished_at` is left null so the DERIVE run's own retention prune (which
 * hard-deletes finished rows older than the retention window) can never remove a run dated in the past.
 */
internal suspend fun insertSucceededDerive(connId: UInt, startedAt: Long) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsStore.DeriveRuns.insert {
        it[MetricsStore.DeriveRuns.connectionId] = connId.toInt()
        it[MetricsStore.DeriveRuns.configRevision] = 1L
        it[MetricsStore.DeriveRuns.processingVersion] = 1
        it[MetricsStore.DeriveRuns.startedAt] = startedAt
        it[MetricsStore.DeriveRuns.status] = "SUCCEEDED"
    }
}

internal suspend fun deleteDeriveRuns(connId: UInt) = suspendTransaction(sharedDatabaseForTests()) {
    MetricsStore.DeriveRuns.deleteWhere { MetricsStore.DeriveRuns.connectionId eq connId.toInt() }
}
