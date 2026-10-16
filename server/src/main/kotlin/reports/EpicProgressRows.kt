package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.teams.TeamService
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.SortOrder
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
 * EPIC-scope figures — cumulative, and (A37) inside the period beside them. An epic is listed under its current domain, so a
 * domain the epic left keeps that epic's earlier EV/AC in its DOMAIN total (as-was attribution) but not in these rows.
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

/**
 * The unit's drill: one row per domain (every domain the connections know, plus any DOMAIN scope with figures) on the EPIC
 * basis, then one per team (every active team, plus any TEAM scope with figures — UNASSIGNED, a soft-deleted team, marked
 * `active = false`) on the sprint/author basis. The DOMAIN rows sum to the unit's `asOf` AND, over the period, to its `period`
 * (the headline reads the DOMAIN scopes); the team rows are a different basis (sprint scope, author-team cost) and do not sum
 * to it.
 */
internal suspend fun unitRows(ctx: ProgressContext): List<EpicProgressRow> {
    val sums = scopeSums(ctx, null)
    val periodSums = ctx.firstDay?.let { scopeSums(ctx, it.toString()) }.orEmpty()
    return domainUnitRows(ctx.scope.connectionIds, sums, periodSums) + teamUnitRows(sums, periodSums)
}

private typealias ScopeSums = Map<Pair<String, String>, Evm>

/** The (scope kind, scope id) → summed TEAM and DOMAIN increments up to asOf, from [fromDay] on when given (the period). */
private suspend fun scopeSums(ctx: ProgressContext, fromDay: String?): ScopeSums {
    val f = MetricsTables.AggDailyFlow
    val pv = f.pvMd.sum()
    val ev = f.evMd.sum()
    val ac = f.acMd.sum()
    return f.select(f.scopeKind, f.scopeId, pv, ev, ac)
        .where {
            val upToAsOf = (f.connectionId inList ctx.scope.connectionIds) and
                (f.scopeKind inList listOf(SCOPE_KIND_TEAM, SCOPE_KIND_DOMAIN)) and (f.day lessEq ctx.asOfDay.toString())
            if (fromDay == null) upToAsOf else upToAsOf and (f.day greaterEq fromDay)
        }
        .groupBy(f.scopeKind, f.scopeId)
        .toList()
        .associate {
            (it[f.scopeKind] to it[f.scopeId]) to Evm(it[pv] ?: BigDecimal.ZERO, it[ev] ?: BigDecimal.ZERO, it[ac] ?: BigDecimal.ZERO)
        }
}

private suspend fun domainUnitRows(connectionIds: List<UInt>, sums: ScopeSums, periodSums: ScopeSums): List<EpicProgressRow> {
    val d = MetricsTables.DimDomain
    val names = d.select(d.domainKey, d.name)
        .where { d.connectionId inList connectionIds }
        .orderBy(d.connectionId to SortOrder.DESC) // the lowest connection id's name wins, as everywhere
        .toList().associate { it[d.domainKey] to it[d.name] }
    val withFigures = sums.filter { (key, evm) -> key.first == SCOPE_KIND_DOMAIN && !evm.isZero }.keys.map { it.second }
    return (names.keys + withFigures).distinct().sorted().map { key ->
        val scope = SCOPE_KIND_DOMAIN to key
        (sums[scope] ?: NO_EVM).toRow(EpicProgressKind.DOMAIN, null, key, names[key] ?: key, periodSums[scope] ?: NO_EVM)
    }
}

private suspend fun teamUnitRows(sums: ScopeSums, periodSums: ScopeSums): List<EpicProgressRow> {
    val t = TeamService.Teams
    val active = t.select(t.id, t.name).where { t.active() }.toList().associate { it[t.id].value to it[t.name] }
    fun teamIdOf(scopeId: String): UInt? = if (scopeId == SCOPE_UNASSIGNED) UNASSIGNED_TEAM_ID else scopeId.toUIntOrNull()
    val withFigures = sums.filter { (key, evm) -> key.first == SCOPE_KIND_TEAM && !evm.isZero }.keys.mapNotNull { teamIdOf(it.second) }
    val others = withFigures.filter { it != UNASSIGNED_TEAM_ID && it !in active }
    val names = active + teamNames(others) + (UNASSIGNED_TEAM_ID to TEAM_UNASSIGNED_NAME)
    val ids = (active.keys + withFigures).distinct()
    return ids.sortedWith(compareBy<UInt>({ it == UNASSIGNED_TEAM_ID }, { names[it] ?: it.toString() }, { it })).map { id ->
        val scope = SCOPE_KIND_TEAM to (if (id == UNASSIGNED_TEAM_ID) SCOPE_UNASSIGNED else id.toString())
        val isActive = id == UNASSIGNED_TEAM_ID || id in active
        (sums[scope] ?: NO_EVM).toRow(EpicProgressKind.TEAM, id, null, names[id] ?: id.toString(), periodSums[scope] ?: NO_EVM, isActive)
    }
}
