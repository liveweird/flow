package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The independent oracle of report 9's DONE band (brief 03 decision B, `.claude/docs/measures.md` "Report 9 — WIP"): the band
 * re-derived from the persisted `item_stage` / `fact_task_delivery` / `task_domain` / `dim_task` / `dim_epic` / `dim_domain` rows
 * with plain Kotlin loops, never through the endpoint's SQL (`reports/WipDoneBand.kt`), so a mistake in that statement cannot
 * grade itself. The rule it encodes: an item is in the band on day `d` when a DONE-stage `item_stage` interval covers the END of
 * `d` (`valid_from < day_end AND (valid_to IS NULL OR valid_to >= day_end)`); its status is that interval's; a task's team is
 * `credit_team_id` (null -> UNASSIGNED), its domain the `task_domain` row with `from <= done_at < to` (DERIVE's `domainRowAt`, which
 * stores it in `fact_task_delivery.domain_key`; else the current domain); an
 * epic's team is its domain's owner (null -> UNOWNED), its domain its own.
 */

/** `agg_daily_wip`-shaped rows (`stage = DONE`) of [connId]'s DONE band over [firstDay]..[lastDay], all four scopes/kinds. */
internal suspend fun independentDoneRows(connId: UInt, firstDay: String, lastDay: String, accountIds: Set<String>? = null): List<WipRow> =
    suspendTransaction(sharedDatabaseForTests()) {
        val days = MetricsTables.DimDate.selectAll()
            .where { (MetricsTables.DimDate.day greaterEq firstDay) and (MetricsTables.DimDate.day lessEq lastDay) }
            .toList().map { it[MetricsTables.DimDate.day] to it[MetricsTables.DimDate.dayEndMs] }
        val s = MetricsTables.ItemStage
        val doneIntervals = s.selectAll().where { (s.connectionId eq connId) and (s.stage eq "DONE") }.toList()
            .map { DoneInterval(it[s.issueId], it[s.statusId], it[s.validFrom], it[s.validTo]) }
        val t = MetricsTables.DimTask
        val tasks = t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList()
            .associate { it[t.issueId] to it[t.domainKey] }
        val f = MetricsTables.FactTaskDelivery
        val facts = f.selectAll().where { f.connectionId eq connId }.toList().associateBy { it[f.issueId] }
        val td = MetricsTables.TaskDomain
        val domainRows = td.selectAll().where { td.connectionId eq connId }.toList().groupBy { it[td.issueId] }
        val e = MetricsTables.DimEpic
        val epics = e.selectAll().where { e.connectionId eq connId }.toList().associate { it[e.issueId] to it[e.domainKey] }
        val d = MetricsTables.DimDomain
        val ownerOfDomain = d.selectAll().where { d.connectionId eq connId }.toList()
            .associate { it[d.domainKey] to it[d.ownerTeamId]?.value }

        val rows = ArrayList<WipRow>()
        for (interval in doneIntervals) {
            for ((day, dayEnd) in days) {
                if (!(interval.from < dayEnd && (interval.to == null || interval.to >= dayEnd))) continue
                val currentDomain = tasks[interval.issueId]
                if (interval.issueId in tasks) {
                    val fact = facts[interval.issueId]
                    val doneAt = fact?.get(f.doneAt)
                    if (accountIds != null && fact?.get(f.assigneeAccountIdAtDone) !in accountIds) continue
                    val team = fact?.get(f.creditTeamId)?.value?.toString() ?: "UNASSIGNED"
                    // DERIVE's own rule (`domainRowAt`): the row with `from <= done_at < to`, half-open the other way round from
                    // the WIP day-end rule, so a move and a Done in the same changelog entry (at T) counts under the NEW domain.
                    val domain = domainRows[interval.issueId].orEmpty()
                        .firstOrNull { row ->
                            val to = row[td.validTo]
                            doneAt != null && row[td.validFrom] <= doneAt && (to == null || doneAt < to)
                        }
                        ?.get(td.domainKey) ?: currentDomain
                    rows += WipRow("TEAM", team, day, "TASK", interval.statusId, "DONE", 1)
                    if (domain != null) rows += WipRow("DOMAIN", domain, day, "TASK", interval.statusId, "DONE", 1)
                } else if (interval.issueId in epics && accountIds == null) {
                    val domain = epics[interval.issueId]
                    val owner = domain?.let { ownerOfDomain[it] }?.toString() ?: "UNOWNED"
                    rows += WipRow("TEAM", owner, day, "EPIC", interval.statusId, "DONE", 1)
                    if (domain != null) rows += WipRow("DOMAIN", domain, day, "EPIC", interval.statusId, "DONE", 1)
                }
            }
        }
        rows.groupBy { listOf(it.scopeKind, it.scopeId, it.day, it.itemKind, it.statusId) }.map { (_, group) ->
            group.first().copy(count = group.sumOf { it.count })
        }
    }

private data class DoneInterval(val issueId: Long, val statusId: String, val from: Long, val to: Long?)

/**
 * Every stored `agg_daily_wip` row of [connId] plus the independently re-derived DONE band over the days the stored rows span —
 * what the aggregate held before decision B, graded by the report tests against the endpoint.
 */
internal suspend fun readWipRowsWithDone(connId: UInt): List<WipRow> {
    val stored = readWipRows(connId)
    require(stored.none { it.stage == "DONE" }) { "DERIVE must store no DONE row (decision B)" }
    return stored + independentDoneRows(connId, stored.minOf { it.day }, stored.maxOf { it.day })
}
