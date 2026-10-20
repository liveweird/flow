package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/*
 * The cost matrix's second table (report 16, `byEpic`): the SAME worklogs as the author x domain matrix split BY EPIC. Rows are epics
 * (the 50 largest by exact MD, the rest folded into "Other epics"), then "No epic"; columns are the matrix's own rows for the level
 * (UNIT: author teams, TEAM: authors, USER: the one person). `fact_worklog.epic_id` is already the worklog's epic -- a task's epic as
 * of `started_at`, a sub-task's parent's, an epic-logged worklog's own id, null above the epic level (A31) -- so no join is needed.
 */

/** The most epics listed as their own row; the rest fold into one [CostEpicRowKind.OTHER] row. */
internal const val COST_EPIC_ROWS = 50

/** The drill `key` of the "No epic" row (`epic_id IS NULL`). */
internal const val NO_EPIC_KEY = "NO_EPIC"

/** The drill `key` of the "Other epics" row (every epic that did not make the listed top [COST_EPIC_ROWS]). */
internal const val OTHER_EPICS_KEY = "OTHER_EPICS"

/** What a row of the epic table is: an epic of its own, the epic-less worklogs, or the folded rest. */
@Serializable
enum class CostEpicRowKind { EPIC, NO_EPIC, OTHER }

/**
 * One column of the epic table: the matrix row of the same identity ([teamId] / [accountId] / [label] spelled exactly as `CostRow`
 * does), [totalMd] the rounded exact sum of its worklogs (equal to that row's `totalMd`). [active] is set on UNIT columns only, exactly as
 * `CostRow.active`: `false` marks a soft-deleted author team that still logged work (a drill with its `teamId` answers `400`).
 */
@Serializable
data class CostEpicColumn(val teamId: UInt?, val accountId: String?, val label: String?, val totalMd: Double, val active: Boolean?)

/**
 * One row. [kind] EPIC: [epicId], [issueKey], [summary] and the epic's [domain] (`dim_epic.domain_key`) name it; OTHER: [count] is the
 * number of folded epics; NO_EPIC / OTHER carry none of the identity fields. [cells] is DENSE (one per column, in column order), each
 * and [totalMd] a rounded EXACT sum, never a sum of rounded cells.
 */
@Serializable
data class CostEpicRow(
    val kind: CostEpicRowKind,
    val epicId: Long?,
    val issueKey: String?,
    val summary: String?,
    val domain: String?,
    val count: Int?,
    val cells: List<Double>,
    val totalMd: Double,
)

@Serializable
data class CostEpicBreakdown(val columns: List<CostEpicColumn>, val rows: List<CostEpicRow>)

/** One grouped `fact_worklog` aggregate: the exact MD of one (connection, epic, author team, author) combination. */
internal data class CostEpicAggregate(
    val connectionId: UInt,
    val epicId: Long?,
    val teamId: UInt?,
    val account: String?,
    val md: BigDecimal,
)

/** The epics listed as their own rows (largest first) and the ids folded into "Other epics". */
internal class EpicFold(val listed: List<Long>, val other: Set<Long>)

/**
 * The ONE ranking of epics by [exactByEpic] (their exact MD): total descending, then epic id ascending, cut at [COST_EPIC_ROWS]. The
 * table and the drill's `OTHER_EPICS` key share it, so a row's figure and its drill name the same epics.
 */
internal fun foldEpics(exactByEpic: Map<Long, BigDecimal>): EpicFold {
    val ranked = exactByEpic.entries
        .sortedWith(compareByDescending<Map.Entry<Long, BigDecimal>> { it.value }.thenBy { it.key })
        .map { it.key }
    return EpicFold(ranked.take(COST_EPIC_ROWS), ranked.drop(COST_EPIC_ROWS).toSet())
}

/** The matrix's worklogs ([worklogSlice], the very predicate the matrix reads) grouped per epic and author. */
internal suspend fun fetchCostEpicAggregates(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
): List<CostEpicAggregate> {
    val w = MetricsTables.FactWorklog
    val md = w.md.sum()
    return w.select(w.connectionId, w.epicId, w.authorTeamId, w.authorAccountId, md)
        .where { worklogSlice(filter, connectionIds, window) }
        .groupBy(w.connectionId, w.epicId, w.authorTeamId, w.authorAccountId)
        .toList()
        .map {
            val team = it[w.authorTeamId]?.value
            CostEpicAggregate(it[w.connectionId].value, it[w.epicId], team, it[w.authorAccountId], it[md] ?: BigDecimal.ZERO)
        }
}

private fun List<BigDecimal>.exactSum(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

/**
 * The (key, summary, domain) of each listed epic, read for the lowest connection id that logged on it (the domain from its `dim_epic`).
 * Epics merge across connections by their bare id. That relies on the product invariant that every data source points at ONE Jira
 * site (enforced separately), so an issue id is unique across connections and never names two different epics.
 */
private suspend fun epicIdentities(
    aggregates: List<CostEpicAggregate>,
    listed: List<Long>,
): Map<Long, Triple<String?, String?, String?>> {
    if (listed.isEmpty()) return emptyMap()
    val wanted = listed.toSet()
    val lowest = aggregates.filter { it.epicId in wanted }.groupBy({ it.epicId!! }, { it.connectionId }).mapValues { it.value.min() }
    val labels = workItemLabels(lowest.map { (epic, connection) -> connection to epic })
    val d = MetricsTables.DimEpic
    val domains = d.select(d.connectionId, d.issueId, d.domainKey)
        .where { (d.connectionId inList lowest.values.distinct()) and (d.issueId inList listed) }
        .toList().associate { (it[d.connectionId].value to it[d.issueId]) to it[d.domainKey] }
    return lowest.mapValues { (epic, connection) ->
        val label = labels[connection to epic]
        Triple(label?.first, label?.second, domains[connection to epic])
    }
}

/**
 * The epic table from the matrix's [aggregates]: columns are the matrix's own group keys and order (no zero rows); rows the listed
 * epics (exact total, then id), "Other epics" when something folded, "No epic" last when some worklog has no epic. Every figure is the
 * rounded exact sum of its own worklogs. Empty for no worklogs.
 */
internal suspend fun assembleCostEpics(filter: ReportFilter, aggregates: List<CostEpicAggregate>): CostEpicBreakdown {
    if (aggregates.isEmpty()) return CostEpicBreakdown(emptyList(), emptyList())
    val groupLevel = if (filter.level == ReportLevel.USER) ReportLevel.TEAM else filter.level
    val groups = orgGroups(groupLevel, aggregates, { it.teamId }, { it.account })
    val activeTeams = if (groupLevel == ReportLevel.UNIT) activeTeamIds(groups.mapNotNull { it.first.teamId }) else emptySet()
    val columns = groups.map { (key, rows) ->
        CostEpicColumn(
            teamId = if (groupLevel == ReportLevel.UNIT) key.teamId ?: UNASSIGNED_TEAM_ID else filter.teamId,
            accountId = key.accountId,
            label = key.label,
            totalMd = rows.map { it.md }.exactSum().md(),
            active = if (groupLevel == ReportLevel.UNIT) key.teamId == null || key.teamId in activeTeams else null,
        )
    }
    // Exact MD per epic (null = no epic) and column, in column order.
    val perEpic = HashMap<Long?, Array<BigDecimal>>()
    groups.forEachIndexed { index, (_, rows) ->
        rows.forEach { aggregate ->
            val cells = perEpic.getOrPut(aggregate.epicId) { Array(groups.size) { BigDecimal.ZERO } }
            cells[index] = cells[index] + aggregate.md
        }
    }
    val fold = foldEpics(perEpic.entries.filter { it.key != null }.associate { it.key!! to it.value.toList().exactSum() })
    val identities = epicIdentities(aggregates, fold.listed)
    fun row(kind: CostEpicRowKind, epic: Long?, parts: List<Array<BigDecimal>>, count: Int? = null): CostEpicRow {
        val cells = groups.indices.map { index -> parts.map { it[index] }.exactSum() }
        val identity = epic?.let { identities[it] }
        val figures = cells.map { it.md() }
        return CostEpicRow(
            kind = kind,
            epicId = epic,
            issueKey = identity?.first,
            summary = identity?.second,
            domain = identity?.third,
            count = count,
            cells = figures,
            totalMd = cells.exactSum().md(),
        )
    }
    val rows = fold.listed.map { row(CostEpicRowKind.EPIC, it, listOf(perEpic.getValue(it))) } +
        listOfNotNull(
            fold.other.takeIf { it.isNotEmpty() }?.let { row(CostEpicRowKind.OTHER, null, it.map(perEpic::getValue), it.size) },
            perEpic[null]?.let { row(CostEpicRowKind.NO_EPIC, null, listOf(it)) },
        )
    return CostEpicBreakdown(columns, rows)
}

/** `key` values of a `by=EPIC` drill: positive epic ids, [NO_EPIC_KEY] or [OTHER_EPICS_KEY] (`400` otherwise). */
internal fun requireEpicDrillKeys(keys: List<String>) {
    if (keys.any { it != NO_EPIC_KEY && it != OTHER_EPICS_KEY && epicIdOf(it) == null }) {
        throw BadRequestException("key must be an epic id, $NO_EPIC_KEY or $OTHER_EPICS_KEY")
    }
}

private fun epicIdOf(key: String): Long? = key.takeIf { text -> text.all { it in '0'..'9' } }?.toLongOrNull()?.takeIf { it > 0 }

/**
 * The slice of the matrix's worklogs the picked epic [keys] select: `epic_id IN ids`, `epic_id IS NULL` for [NO_EPIC_KEY] and, for
 * [OTHER_EPICS_KEY], every epic outside the listed top [COST_EPIC_ROWS] of the slice -- ranked by [foldEpics] over a `GROUP BY epic_id`
 * of the slice WITHOUT the `accountId` narrowing, so a TEAM-level cell (team + author) folds the same epics as its row. (A UNIT cell,
 * whose `teamId` narrows the slice, folds within that team: reports.md "Drill".) The ranking query runs only when the key is present.
 */
internal suspend fun epicKeysSlice(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    keys: List<String>,
): Op<Boolean> {
    val w = MetricsTables.FactWorklog
    val parts = mutableListOf<Op<Boolean>>()
    keys.mapNotNull(::epicIdOf).takeIf { it.isNotEmpty() }?.let { parts += (w.epicId inList it) }
    if (NO_EPIC_KEY in keys) parts += w.epicId.isNull()
    if (OTHER_EPICS_KEY in keys) {
        val md = w.md.sum()
        val exactByEpic = w.select(w.epicId, md)
            .where { worklogSlice(filter.copy(accountIds = emptyList()), connectionIds, window) and w.epicId.isNotNull() }
            .groupBy(w.epicId)
            .toList()
            .groupBy({ it[w.epicId]!! }, { it[md] ?: BigDecimal.ZERO })
            .mapValues { (_, sums) -> sums.exactSum() }
        val listed = foldEpics(exactByEpic).listed
        parts += if (listed.isEmpty()) w.epicId.isNotNull() else (w.epicId.isNotNull() and (w.epicId notInList listed))
    }
    return parts.reduce { left, right -> left or right }
}
