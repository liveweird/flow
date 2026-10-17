package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.teams.TeamService
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
@OptIn(ExperimentalSerializationApi::class)
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
    /**
     * The person's capacity (man-days available) in the period, 2 decimals — only for a caller who may see it (ADMIN or the active
     * manager of the filtered team, TEAM/USER level). `null` for everyone else and on the no-author row (not a person); Ktor's default
     * `Json` encodes defaults, so `EncodeDefault.Mode.NEVER` is what makes the wire OMIT the key rather than send `null`.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capacityMd: Double? = null,
    /** With a `split`: this row's figures per bucket, DENSE (one per `split.buckets` entry, in bucket order); omitted without one. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val byBucket: List<CostBucketFigures>? = null,
)

/**
 * The capacity block (domain-model A39): [totalMd] the rounded EXACT sum over the rows' people, [days] the period days up to today
 * and [calendarDays] how many of them read a saved sprint calendar (the rest are default days: one man-day per team member on a
 * working day).
 */
@Serializable
data class CostCapacity(val totalMd: Double, val days: Int, val calendarDays: Int)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CostMatrixReport(
    val meta: ReportMeta,
    val columns: List<CostColumn>,
    val rows: List<CostRow>,
    /** The grand total: the rounded EXACT sum of every worklog in scope. */
    val totalMd: Double,
    val foreignMd: Double,
    val foreignShare: Double?,
    /** Present ONLY for an ADMIN or the active manager of the filtered team (TEAM/USER level); omitted from the wire otherwise. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capacity: CostCapacity? = null,
    /** The time split (`split=WEEK|SPRINT`): the buckets in column order; omitted for `split=NONE`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val split: CostSplitBlock? = null,
    /** With a `split`: the footer per bucket (per-domain totals, total, foreign, capacity), one per bucket; omitted otherwise. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bucketTotals: List<CostBucketTotals>? = null,
)

/**
 * One grouped `fact_worklog` aggregate: the exact MD of one (author team, author, domain, foreign?) combination — with a `split`,
 * also per [bucket] (the key of the day's bucket; `null` without a split).
 */
internal data class CostAggregate(
    val teamId: UInt?,
    val account: String?,
    val domain: String?,
    val foreign: Boolean,
    val md: BigDecimal,
    val bucket: String? = null,
)

internal fun Iterable<CostAggregate>.exact(): BigDecimal = fold(BigDecimal.ZERO) { sum, aggregate -> sum + aggregate.md }

/**
 * A worklog logged on an epic carries the epic's own domain in BOTH columns (A21); an epic-less task falls back to its own domain in
 * the EPIC view.
 */
internal fun costDomain(view: DomainView, taskDomain: String?, epicDomain: String?): String? =
    if (view == DomainView.EPIC) epicDomain ?: taskDomain else taskDomain

internal fun ratio(part: BigDecimal, whole: BigDecimal): Double? = if (whole.signum() == 0) null else part.toDouble() / whole.toDouble()

private suspend fun fetchCostAggregates(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<CostAggregate> {
    val w = MetricsTables.FactWorklog
    val md = w.md.sum()
    return w.select(w.authorTeamId, w.authorAccountId, w.taskDomainKey, w.epicDomainKey, w.foreignWork, md)
        .where { worklogSlice(filter, connectionIds, window) }
        .groupBy(w.authorTeamId, w.authorAccountId, w.taskDomainKey, w.epicDomainKey, w.foreignWork)
        .toList()
        .map {
            val domain = costDomain(filter.domainView, it[w.taskDomainKey], it[w.epicDomainKey])
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

private fun emptyCostMatrix(meta: ReportMeta, split: CostSplit) = CostMatrixReport(
    meta, emptyList(), emptyList(), 0.0, 0.0, null,
    split = if (split == CostSplit.NONE) null else CostSplitBlock(split, emptyList()),
    bucketTotals = if (split == CostSplit.NONE) null else emptyList(),
)

/**
 * `GET /api/v1/reports/cost-matrix` (v0.3.0 M5 commit 17b, Report 16, `.claude/docs/measures.md` "Report 16"): the man-days
 * logged in the period (`fact_worklog.started_at`) as an author-team × domain matrix, with the foreign-work share per row. Cost
 * is charged to the AUTHOR's team as of `started_at` and to the task's domain (TASK view) or its epic's (EPIC view, the default,
 * an epic-less task falling back to its own). Sprint-relative periods use the resolved sprints' envelope. [showCapacity] (the
 * route's visibility rule: ADMIN or the filtered team's active manager) adds each author's capacity for the period at TEAM/USER
 * level of a real team — computed only then, never computed-and-stripped. [split] (`WEEK`/`SPRINT`, A39) cuts every figure into
 * buckets that partition the period (the `split` block, `byBucket`, `bucketTotals`); `NONE` is today's response, byte for byte.
 * See `.claude/docs/reports.md`.
 */
suspend fun ReportService.costMatrix(
    filter: ReportFilter,
    split: CostSplit,
    showCapacity: Boolean,
    nowMs: Long,
): CostMatrixReport = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    requireSplittable(split, filter.teamId)
    val window = scope.window
    if (window == null || scope.connectionIds.isEmpty()) return@reportTransaction emptyCostMatrix(scope.meta, split)
    val zone = WorkingCalendar.zoneOf(scope.settings.timeZone)
    val plan = if (split == CostSplit.NONE) {
        null
    } else {
        resolveCostBuckets(split, filter.period, filter.teamId, scope.connectionIds, window, scope.calendar, zone)
    }
    val aggregates = if (plan == null) {
        fetchCostAggregates(filter, scope.connectionIds, window)
    } else {
        fetchSplitCostAggregates(filter, scope.connectionIds, window, plan, scope.calendar)
    }
    val teamId = filter.teamId
    val shownFor = teamId?.takeIf { showCapacity && filter.level != ReportLevel.UNIT && it != UNASSIGNED_TEAM_ID }
    val capacityDays = shownFor?.let { loadCostCapacity(it, scope.connectionIds, window, scope.calendar, nowMs, plan?.sprintOwners) }
    assembleCostMatrix(filter, scope.meta, domainNames(scope.connectionIds), aggregates, plan, capacityDays)
}

/** The matrix from the fetched [aggregates]: columns, org-drill rows, the totals and, per [plan], the buckets and their capacity. */
private suspend fun assembleCostMatrix(
    filter: ReportFilter,
    meta: ReportMeta,
    names: Map<String, String>,
    aggregates: List<CostAggregate>,
    plan: CostBucketPlan?,
    capacityDays: CostCapacityDays?,
): CostMatrixReport {
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
            capacityMd = capacityDays?.let { days -> key.accountId?.let { days.exactFor(it).md() } },
            byBucket = plan?.let {
                // The no-author row is no person: no capacity function, so its bucket figures carry none either.
                val capacityOf = key.accountId?.let { account -> personBucketCapacity(it, capacityDays, listOf(account)) }
                bucketFigures(group, it.buckets, domains, capacityOf)
            },
        )
    }
    val people = rows.mapNotNull { it.accountId }.distinct()
    val total = aggregates.exact()
    val foreignTotal = aggregates.filter { it.foreign }.exact()
    return CostMatrixReport(
        meta, columns, rows, total.md(), foreignTotal.md(), ratio(foreignTotal, total),
        capacity = capacityDays?.let { days ->
            val totalMd = people.fold(BigDecimal.ZERO) { sum, account -> sum + days.exactFor(account) }.md()
            CostCapacity(totalMd, days.days.size, days.calendarDays)
        },
        split = plan?.block(),
        bucketTotals = plan?.let { bucketTotals(aggregates, it.buckets, columns, personBucketCapacity(plan, capacityDays, people)) },
    )
}
