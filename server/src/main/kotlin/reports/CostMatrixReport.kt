package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.teams.TeamService
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * One column of the matrix: a domain ([domain] the domain key, [name] its display name, `null` name when no `dim_domain` row
 * names it) with its column total [totalMd]. `domain = null` is the `(no domain)` column — a worklog with no domain at all,
 * which invariant 6 (`task_domain_key` is never null) says cannot happen; the column exists so a corrupt row could never
 * silently drop out of the totals.
 */
@Serializable
data class CostColumn(val domain: String?, val name: String?, val totalMd: Double)

/** One cell: the MD the row's author(s) logged on [domain] (`null` = the `(no domain)` column). */
@Serializable
data class CostCell(val domain: String?, val md: Double)

/**
 * One matrix row. UNIT level: an author team ([teamId], `0` = UNASSIGNED — the authors in no team, sorted last — with a `null`
 * [label]); TEAM/USER level: an author of the requested team ([teamId] = the requested team, [accountId] the author, `null`
 * for worklogs with no known author), [label] the Jira display name. [cells] is DENSE — one per column, in column order.
 * [foreignShare] = [foreignMd] ÷ the row's exact MD, `null` when it logged nothing. [active] is set on UNIT rows only: `false` marks
 * a soft-deleted author team that still logged work in the period (its own drill answers `400`); UNASSIGNED and live teams are `true`.
 */
@Serializable
data class CostRow(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val cells: List<CostCell>,
    val totalMd: Double,
    val foreignMd: Double,
    val foreignShare: Double?,
    val active: Boolean? = null,
)

@Serializable
data class CostMatrixReport(
    val meta: ReportMeta,
    val columns: List<CostColumn>,
    val rows: List<CostRow>,
    /** The grand total: the rounded EXACT sum of every worklog in scope. */
    val totalMd: Double,
    val foreignMd: Double,
    val foreignShare: Double?,
)

/** One grouped `fact_worklog` aggregate: the exact MD of one (author team, author, domain, foreign?) combination. */
private data class CostAggregate(val teamId: UInt?, val account: String?, val domain: String?, val foreign: Boolean, val md: BigDecimal)

private fun Iterable<CostAggregate>.exact(): BigDecimal = fold(BigDecimal.ZERO) { sum, aggregate -> sum + aggregate.md }

private fun ratio(part: BigDecimal, whole: BigDecimal): Double? = if (whole.signum() == 0) null else part.toDouble() / whole.toDouble()

private suspend fun fetchCostAggregates(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<CostAggregate> {
    val w = MetricsTables.FactWorklog
    val md = w.md.sum()
    return w.select(w.authorTeamId, w.authorAccountId, w.taskDomainKey, w.epicDomainKey, w.foreignWork, md)
        .where { worklogSlice(filter, connectionIds, window) }
        .groupBy(w.authorTeamId, w.authorAccountId, w.taskDomainKey, w.epicDomainKey, w.foreignWork)
        .toList()
        .map {
            // A worklog logged on an epic carries the epic's own domain in BOTH columns (A21); an epic-less task falls back
            // to its own domain in the EPIC view.
            val domain = if (filter.domainView == DomainView.EPIC) it[w.epicDomainKey] ?: it[w.taskDomainKey] else it[w.taskDomainKey]
            CostAggregate(it[w.authorTeamId]?.value, it[w.authorAccountId], domain, it[w.foreignWork], it[md] ?: BigDecimal.ZERO)
        }
}

/** Domain display names (the lowest connection id's name wins for a key seen on several). */
private suspend fun domainNames(connectionIds: List<UInt>): Map<String, String> {
    val d = MetricsTables.DimDomain
    return d.select(d.domainKey, d.name).where { d.connectionId inList connectionIds }
        .orderBy(d.connectionId to SortOrder.DESC)
        .toList().associate { it[d.domainKey] to it[d.name] }
}

/** Which of [ids] are live (not soft-deleted) teams. */
private suspend fun activeTeamIds(ids: List<UInt>): Set<UInt> {
    if (ids.isEmpty()) return emptySet()
    val t = TeamService.Teams
    return t.select(t.id).where { (t.id inList ids) and t.active() }.toList().map { it[t.id].value }.toSet()
}

private fun emptyCostMatrix(meta: ReportMeta) = CostMatrixReport(meta, emptyList(), emptyList(), 0.0, 0.0, null)

/**
 * `GET /api/v1/reports/cost-matrix` (v0.3.0 M5 commit 17b, Report 16, `.claude/docs/measures.md` "Report 16"): the man-days
 * logged in the period (`fact_worklog.started_at`) as an author-team × domain matrix, with the foreign-work share per row. Cost
 * is charged to the AUTHOR's team as of `started_at` and to the task's domain (TASK view) or its epic's (EPIC view, the default,
 * an epic-less task falling back to its own). Sprint-relative periods use the resolved sprints' envelope. See
 * `.claude/docs/reports.md`.
 */
suspend fun ReportService.costMatrix(filter: ReportFilter, nowMs: Long): CostMatrixReport = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val window = scope.window
    if (window == null || scope.connectionIds.isEmpty()) return@reportTransaction emptyCostMatrix(scope.meta)
    val aggregates = fetchCostAggregates(filter, scope.connectionIds, window)

    val names = domainNames(scope.connectionIds)
    val domains = aggregates.map { it.domain }.distinct().sortedWith(compareBy(nullsLast()) { it })
    val columns = domains.map { domain ->
        CostColumn(domain, domain?.let { names[it] }, aggregates.filter { it.domain == domain }.exact().md())
    }

    // The org drill: teams at UNIT level, authors within the requested team otherwise (USER = the one author's row).
    val groupLevel = if (filter.level == ReportLevel.USER) ReportLevel.TEAM else filter.level
    val groups = orgGroups(groupLevel, aggregates, { it.teamId }, { it.account })
    val activeTeams = if (groupLevel == ReportLevel.UNIT) activeTeamIds(groups.mapNotNull { it.first.teamId }) else emptySet()
    val rows = groups.map { (key, group) ->
        val exact = group.exact()
        val foreign = group.filter { it.foreign }.exact()
        CostRow(
            teamId = if (groupLevel == ReportLevel.UNIT) key.teamId ?: UNASSIGNED_TEAM_ID else filter.teamId,
            accountId = key.accountId,
            label = key.label,
            cells = domains.map { domain -> CostCell(domain, group.filter { it.domain == domain }.exact().md()) },
            totalMd = exact.md(),
            foreignMd = foreign.md(),
            foreignShare = ratio(foreign, exact),
            active = if (groupLevel == ReportLevel.UNIT) key.teamId == null || key.teamId in activeTeams else null,
        )
    }

    val total = aggregates.exact()
    val foreignTotal = aggregates.filter { it.foreign }.exact()
    CostMatrixReport(scope.meta, columns, rows, total.md(), foreignTotal.md(), ratio(foreignTotal, total))
}
