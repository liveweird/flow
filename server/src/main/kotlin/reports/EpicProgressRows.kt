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
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

// ---- the drill tables --------------------------------------------------------------------------

/**
 * A domain's epics: those of `dim_epic.domain_key = domain` with a CURRENT baseline or any EV/AC up to asOf, each with its own
 * EPIC-scope figures. An epic is listed under its current domain, so a domain the epic left keeps that epic's earlier EV/AC in
 * its DOMAIN total (as-was attribution) but not in these rows. Two joins on `dim_epic` — no list of epic ids leaves the database.
 */
internal suspend fun domainRows(ctx: ProgressContext, domain: String): List<EpicProgressRow> {
    val connectionIds = ctx.scope.connectionIds
    val d = MetricsTables.DimEpic
    val f = MetricsTables.AggDailyFlow
    val pv = f.pvMd.sum()
    val ev = f.evMd.sum()
    val ac = f.acMd.sum()
    // Every epic of the domain, its EPIC-scope increments up to asOf LEFT-joined (an epic with none still lists its baseline).
    val epics = d.join(
        f,
        JoinType.LEFT,
        onColumn = d.connectionId,
        otherColumn = f.connectionId,
        additionalConstraint = {
            (f.scopeKind eq SCOPE_KIND_EPIC) and (f.scopeId eq d.issueId.castTo(VarCharColumnType(SCOPE_ID_LENGTH))) and
                (f.day lessEq ctx.asOfDay.toString())
        },
    ).select(d.connectionId, d.issueId, d.issueKey, d.summary, pv, ev, ac)
        .where { (d.connectionId inList connectionIds) and (d.domainKey eq domain) }
        .groupBy(d.connectionId, d.issueId, d.issueKey, d.summary)
        .toList()
    if (epics.isEmpty()) return emptyList()
    val p = MetricsTables.FactEpicPlan
    val withBaseline = p.join(d, JoinType.INNER, onColumn = p.connectionId, otherColumn = d.connectionId, additionalConstraint = {
        p.issueId eq d.issueId
    }).select(p.connectionId, p.issueId)
        .where { (d.connectionId inList connectionIds) and (d.domainKey eq domain) and p.supersededAt.isNull() }
        .toList().map { it[p.connectionId].value to it[p.issueId] }.toSet()
    return epics.mapNotNull { row ->
        val evm = Evm(row[pv] ?: BigDecimal.ZERO, row[ev] ?: BigDecimal.ZERO, row[ac] ?: BigDecimal.ZERO)
        val listed = (row[d.connectionId].value to row[d.issueId]) in withBaseline || evm.ev.signum() != 0 || evm.ac.signum() != 0
        if (!listed) return@mapNotNull null
        evm.toRow(EpicProgressKind.EPIC, null, row[d.issueKey], row[d.summary] ?: row[d.issueKey])
    }.sortedWith(compareBy({ it.key }, { it.name }))
}

/** `agg_daily_flow.scope_id`'s width (`VARCHAR(60)`) — what an epic's issue id is cast to for the join. */
private const val SCOPE_ID_LENGTH = 60

/**
 * The unit's drill: one row per domain (every domain the connections know, plus any DOMAIN scope with figures) on the EPIC
 * basis, then one per team (every active team, plus any TEAM scope with figures — UNASSIGNED, a soft-deleted team, marked
 * `active = false`) on the sprint/author basis. The DOMAIN rows sum to the unit's `asOf` (which reads the DOMAIN scopes); the team
 * rows are a different basis (sprint scope, author-team cost) and do not sum to that headline.
 */
internal suspend fun unitRows(ctx: ProgressContext): List<EpicProgressRow> {
    val connectionIds = ctx.scope.connectionIds
    val f = MetricsTables.AggDailyFlow
    val pv = f.pvMd.sum()
    val ev = f.evMd.sum()
    val ac = f.acMd.sum()
    val sums = f.select(f.scopeKind, f.scopeId, pv, ev, ac)
        .where {
            (f.connectionId inList connectionIds) and (f.scopeKind inList listOf(SCOPE_KIND_TEAM, SCOPE_KIND_DOMAIN)) and
                (f.day lessEq ctx.asOfDay.toString())
        }
        .groupBy(f.scopeKind, f.scopeId)
        .toList()
        .associate {
            (it[f.scopeKind] to it[f.scopeId]) to Evm(it[pv] ?: BigDecimal.ZERO, it[ev] ?: BigDecimal.ZERO, it[ac] ?: BigDecimal.ZERO)
        }
    return domainUnitRows(connectionIds, sums) + teamUnitRows(sums)
}

private suspend fun domainUnitRows(connectionIds: List<UInt>, sums: Map<Pair<String, String>, Evm>): List<EpicProgressRow> {
    val d = MetricsTables.DimDomain
    val names = d.select(d.domainKey, d.name)
        .where { d.connectionId inList connectionIds }
        .orderBy(d.connectionId to SortOrder.DESC) // the lowest connection id's name wins, as everywhere
        .toList().associate { it[d.domainKey] to it[d.name] }
    val withFigures = sums.filter { (key, evm) -> key.first == SCOPE_KIND_DOMAIN && !evm.isZero }.keys.map { it.second }
    return (names.keys + withFigures).distinct().sorted().map { key ->
        (sums[SCOPE_KIND_DOMAIN to key] ?: NO_EVM).toRow(EpicProgressKind.DOMAIN, null, key, names[key] ?: key)
    }
}

private suspend fun teamUnitRows(sums: Map<Pair<String, String>, Evm>): List<EpicProgressRow> {
    val t = TeamService.Teams
    val active = t.select(t.id, t.name).where { t.active() }.toList().associate { it[t.id].value to it[t.name] }
    fun teamIdOf(scopeId: String): UInt? = if (scopeId == SCOPE_UNASSIGNED) UNASSIGNED_TEAM_ID else scopeId.toUIntOrNull()
    val withFigures = sums.filter { (key, evm) -> key.first == SCOPE_KIND_TEAM && !evm.isZero }.keys.mapNotNull { teamIdOf(it.second) }
    val others = withFigures.filter { it != UNASSIGNED_TEAM_ID && it !in active }
    val names = active + teamNames(others) + (UNASSIGNED_TEAM_ID to TEAM_UNASSIGNED_NAME)
    val ids = (active.keys + withFigures).distinct()
    return ids.sortedWith(compareBy<UInt>({ it == UNASSIGNED_TEAM_ID }, { names[it] ?: it.toString() }, { it })).map { id ->
        val scopeId = if (id == UNASSIGNED_TEAM_ID) SCOPE_UNASSIGNED else id.toString()
        val isActive = id == UNASSIGNED_TEAM_ID || id in active
        (sums[SCOPE_KIND_TEAM to scopeId] ?: NO_EVM).toRow(EpicProgressKind.TEAM, id, null, names[id] ?: id.toString(), isActive)
    }
}
