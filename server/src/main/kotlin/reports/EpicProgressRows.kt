package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

// ---- the drill tables --------------------------------------------------------------------------

/** One epic of a domain with its EPIC-scope sum (cumulative up to asOf, or inside the period). */
private class EpicSum(val connectionId: UInt, val issueId: Long, val key: String, val name: String, val evm: Evm)

/**
 * Every epic of [domain] (`dim_epic.domain_key`) with its EPIC-scope increments up to asOf LEFT-joined (an epic with none still
 * lists its baseline), from [fromDay] on when given (the period, A37). Two joins on `dim_epic` — no list of epic ids leaves the
 * database.
 */
private suspend fun epicSums(ctx: ProgressContext, domain: String, fromDay: String?): List<EpicSum> {
    val d = MetricsTables.DimEpic
    val f = MetricsTables.AggDailyFlow
    val pv = f.pvMd.sum()
    val ev = f.evMd.sum()
    val ac = f.acMd.sum()
    return d.join(
        f,
        JoinType.LEFT,
        onColumn = d.connectionId,
        otherColumn = f.connectionId,
        additionalConstraint = {
            val upToAsOf = (f.scopeKind eq SCOPE_KIND_EPIC) and (f.scopeId eq d.issueId.castTo(VarCharColumnType(SCOPE_ID_LENGTH))) and
                (f.day lessEq ctx.asOfDay.toString())
            if (fromDay == null) upToAsOf else upToAsOf and (f.day greaterEq fromDay)
        },
    ).select(d.connectionId, d.issueId, d.issueKey, d.summary, pv, ev, ac)
        .where { (d.connectionId inList ctx.scope.connectionIds) and (d.domainKey eq domain) }
        .groupBy(d.connectionId, d.issueId, d.issueKey, d.summary)
        .toList()
        .map {
            EpicSum(
                it[d.connectionId].value,
                it[d.issueId],
                it[d.issueKey],
                it[d.summary] ?: it[d.issueKey],
                Evm(it[pv] ?: BigDecimal.ZERO, it[ev] ?: BigDecimal.ZERO, it[ac] ?: BigDecimal.ZERO),
            )
        }
}

/**
 * A domain's epics: those of `dim_epic.domain_key = domain` with a CURRENT baseline or any EV/AC up to asOf, each with its own
 * EPIC-scope figures — cumulative, and (A37) inside the period beside them. An epic is listed under its current domain at its
 * full value, so a domain the epic left keeps that epic's earlier PV, EV and AC in its DOMAIN total (as-was attribution, A47) but not
 * in these rows, and the epic's current domain's total holds only the part after the move: for an epic that moved, the rows and the
 * DOMAIN totals do not add up.
 */
internal suspend fun domainRows(ctx: ProgressContext, domain: String): List<EpicProgressRow> {
    val connectionIds = ctx.scope.connectionIds
    val epics = epicSums(ctx, domain, null)
    if (epics.isEmpty()) return emptyList()
    val periodSums = ctx.firstDay?.let { first -> epicSums(ctx, domain, first.toString()) }
        ?.associate { (it.connectionId to it.issueId) to it.evm }.orEmpty()
    val d = MetricsTables.DimEpic
    val p = MetricsTables.FactEpicPlan
    val withBaseline = p.join(d, JoinType.INNER, onColumn = p.connectionId, otherColumn = d.connectionId, additionalConstraint = {
        p.issueId eq d.issueId
    }).select(p.connectionId, p.issueId)
        .where { (d.connectionId inList connectionIds) and (d.domainKey eq domain) and p.supersededAt.isNull() }
        .toList().map { it[p.connectionId].value to it[p.issueId] }.toSet()
    return epics.mapNotNull { epic ->
        val id = epic.connectionId to epic.issueId
        val listed = id in withBaseline || epic.evm.ev.signum() != 0 || epic.evm.ac.signum() != 0
        if (!listed) return@mapNotNull null
        epic.evm.toRow(EpicProgressKind.EPIC, null, epic.key, epic.name, periodSums[id] ?: NO_EVM)
    }.sortedWith(compareBy({ it.key }, { it.name }))
}

/** `agg_daily_flow.scope_id`'s width (`VARCHAR(60)`) — what an epic's issue id is cast to for the join. */
private const val SCOPE_ID_LENGTH = 60
