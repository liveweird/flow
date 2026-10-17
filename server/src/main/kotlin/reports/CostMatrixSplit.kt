package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.select
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/*
 * The cost matrix's time split (report 16, domain-model A39 part 2): `split=WEEK|SPRINT` cuts the period into buckets, each with its
 * own cells, totals and (for the capacity viewers) capacity. ONE day -> bucket map (`CostBucketPlan.keyByDay`) decides the bucket of a
 * worklog (by its configured-zone day) and of a capacity day, so cost and capacity partition the period the same way.
 */

/** The `split` query parameter: `NONE` (today's shape, byte for byte), weekly buckets, or the filter team's sprints. */
@Serializable
enum class CostSplit { NONE, WEEK, SPRINT }

/** At most this many buckets (a degenerate one-day-sprint team could otherwise ask for a thousand column groups). */
internal const val MAX_COST_BUCKETS = 160

/** The key of the SPRINT split's last bucket: the period days no sprint of the team owns. */
internal const val OUTSIDE_BUCKET_KEY = "outside"

/**
 * One bucket of the split: [key] a Monday's ISO date (WEEK) or the sprint id (SPRINT; [OUTSIDE_BUCKET_KEY] for the time outside every
 * sprint); [label] the sprint name (`null` for a week and for "Outside sprints"); [from]/[to] the first/last period day it holds (ISO
 * dates, a week is clipped to the period); [sprintId] set for a sprint only.
 */
@Serializable
data class CostBucket(val key: String, val label: String?, val from: String, val to: String, val sprintId: Long?)

/** The split the response was cut by: [kind] (never `NONE` on the wire) and its [buckets] in column order. */
@Serializable
data class CostSplitBlock(val kind: CostSplit, val buckets: List<CostBucket>)

/**
 * One row's figures inside one bucket (dense: a [CostRow.byBucket] entry per bucket, in bucket order): [cells] one per PERIOD column,
 * then the rounded EXACT sums of the bucket's worklogs; [capacityMd] only where the row shows capacity.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CostBucketFigures(
    val cells: List<CostCell>,
    val totalMd: Double,
    val foreignMd: Double,
    val foreignShare: Double?,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capacityMd: Double? = null,
)

/** The footer of one bucket: [columns] the bucket's per-domain totals (one per period column), then its grand figures. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CostBucketTotals(
    val columns: List<CostColumn>,
    val totalMd: Double,
    val foreignMd: Double,
    val foreignShare: Double?,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capacityMd: Double? = null,
)

/**
 * The resolved split: the [buckets] in column order, [keyByDay] the ONE day -> bucket-key map over every period day, and (SPRINT only)
 * [sprintOwners] the day -> sprint map the capacity computation reuses.
 */
internal class CostBucketPlan(
    val kind: CostSplit,
    val buckets: List<CostBucket>,
    val keyByDay: Map<LocalDate, String>,
    val sprintOwners: Map<LocalDate, CostSprint>?,
) {
    fun block() = CostSplitBlock(kind, buckets)
}

/** `split=SPRINT` is per team: a request without a real team (`teamId` absent or `0`) is a `400`. Thrown right after the scope resolves. */
internal fun requireSplittable(split: CostSplit, teamId: UInt?) {
    if (split == CostSplit.SPRINT && (teamId == null || teamId == UNASSIGNED_TEAM_ID)) {
        throw BadRequestException("split=SPRINT needs a team (sprints are per team)")
    }
}

private fun checkBucketCount(count: Int) {
    if (count > MAX_COST_BUCKETS) throw BadRequestException("The split has $count buckets; at most $MAX_COST_BUCKETS — narrow the period")
}

/**
 * A sprint-relative period (`lastSprints`/`sprintId`) resolves to the sprints' envelope, which can span more than [MAX_COST_BUCKETS]
 * weeks (a date range tops out at 1100 days = 158 weeks, so only this period can trip the cap in WEEK mode): its `400` says so.
 */
private fun checkWeekCount(count: Int, sprintRelative: Boolean) {
    if (sprintRelative && count > MAX_COST_BUCKETS) {
        throw BadRequestException(
            "The period spans $count weeks; at most $MAX_COST_BUCKETS — " +
                "too many weeks for a weekly split, use the sprint split or a shorter period",
        )
    }
    checkBucketCount(count)
}

private fun weekPlan(
    window: Pair<Long, Long>,
    periodDays: List<LocalDate>,
    calendar: WorkingCalendar,
    zone: ZoneId,
    sprintRelative: Boolean,
): CostBucketPlan {
    val starts = bucketStarts(window.first, window.second, zone, ThroughputBucket.WEEK)
    checkWeekCount(starts.size, sprintRelative)
    val keyByDay = periodDays.associateWith { bucketStart(calendar.dayBoundsMs(it).first, zone, ThroughputBucket.WEEK).toString() }
    val buckets = starts.map { monday ->
        val days = periodDays.filter { keyByDay.getValue(it) == monday.toString() }
        CostBucket(monday.toString(), null, days.first().toString(), days.last().toString(), null)
    }
    return CostBucketPlan(CostSplit.WEEK, buckets, keyByDay, null)
}

private suspend fun sprintPlan(
    teamId: UInt,
    connectionIds: List<UInt>,
    periodDays: List<LocalDate>,
    calendar: WorkingCalendar,
): CostBucketPlan {
    val sprints = readCostSprints(teamId, connectionIds)
    val owners = sprintDayOwners(sprints, periodDays, calendar)
    val keyByDay = periodDays.associateWith { day -> owners[day]?.sprintId?.toString() ?: OUTSIDE_BUCKET_KEY }
    val sprintBuckets = sprints.mapNotNull { sprint ->
        val days = periodDays.filter { owners[it]?.sprintId == sprint.sprintId }
        days.takeIf { it.isNotEmpty() }
            ?.let { CostBucket(sprint.sprintId.toString(), sprint.name, it.first().toString(), it.last().toString(), sprint.sprintId) }
    }
    val outside = periodDays.filter { it !in owners }
    val buckets = if (outside.isEmpty()) {
        sprintBuckets
    } else {
        sprintBuckets + CostBucket(OUTSIDE_BUCKET_KEY, null, outside.first().toString(), outside.last().toString(), null)
    }
    checkBucketCount(buckets.size)
    return CostBucketPlan(CostSplit.SPRINT, buckets, keyByDay, owners)
}

/** Resolves [split] (`WEEK` or `SPRINT`) over the period [window] of [period]; the bucket-count `400` is thrown here. */
internal suspend fun resolveCostBuckets(
    split: CostSplit,
    period: ReportPeriod,
    teamId: UInt?,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    calendar: WorkingCalendar,
    zone: ZoneId,
): CostBucketPlan {
    val periodDays = periodDaysOf(calendar, window.first, window.second)
    return when (split) {
        CostSplit.WEEK -> weekPlan(window, periodDays, calendar, zone, sprintRelative = period !is ReportPeriod.DateRange)
        CostSplit.SPRINT -> sprintPlan(teamId ?: error("requireSplittable guarantees a team"), connectionIds, periodDays, calendar)
        CostSplit.NONE -> error("a NONE split has no buckets")
    }
}

/**
 * The split's data fetch: the slice's per-worklog rows (`started_at` cannot be grouped by a configured-zone day in SQL), each put in
 * its day's bucket in Kotlin and folded straight into per-(team, author, domain, foreign, bucket) EXACT sums, so memory stays at the
 * number of distinct combinations, not worklogs.
 */
internal suspend fun fetchSplitCostAggregates(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>,
    plan: CostBucketPlan,
    calendar: WorkingCalendar,
): List<CostAggregate> {
    val w = MetricsTables.FactWorklog
    val sums = LinkedHashMap<CostAggregate, BigDecimal>()
    w.select(w.startedAt, w.authorTeamId, w.authorAccountId, w.taskDomainKey, w.epicDomainKey, w.foreignWork, w.md)
        .where { worklogSlice(filter, connectionIds, window) }
        .collect { row ->
            val bucket = plan.keyByDay.getValue(calendar.dayOf(row[w.startedAt]))
            val domain = costDomain(filter.domainView, row[w.taskDomainKey], row[w.epicDomainKey])
            val key = CostAggregate(row[w.authorTeamId]?.value, row[w.authorAccountId], domain, row[w.foreignWork], BigDecimal.ZERO, bucket)
            sums.merge(key, row[w.md], BigDecimal::add)
        }
    return sums.map { (key, md) -> key.copy(md = md) }
}

/**
 * A bucket's capacity for [people] (their exact man-days on the days the plan maps to it, 0 for none); `null` — no capacity figure —
 * only where the caller may not see capacity. The no-author row, which is no person, is the caller's `null` (it passes no function).
 */
internal fun personBucketCapacity(plan: CostBucketPlan, days: CostCapacityDays?, people: List<String>): ((CostBucket) -> BigDecimal)? =
    days?.let { { bucket -> people.fold(BigDecimal.ZERO) { sum, account -> sum + it.exactIn(account, plan.keyByDay, bucket.key) } } }

/** One row's figures per bucket (dense, in bucket order), from the row's [group] of aggregates. */
internal fun bucketFigures(
    group: List<CostAggregate>,
    buckets: List<CostBucket>,
    domains: List<String?>,
    capacityOf: ((CostBucket) -> BigDecimal)?,
): List<CostBucketFigures> {
    val byBucket = group.groupBy { it.bucket }
    return buckets.map { bucket ->
        val inBucket = byBucket[bucket.key].orEmpty()
        val exact = inBucket.exact()
        val foreign = inBucket.filter { it.foreign }.exact()
        val byDomain = inBucket.groupBy { it.domain }
        CostBucketFigures(
            cells = domains.map { CostCell(it, byDomain[it].orEmpty().exact().md()) },
            totalMd = exact.md(),
            foreignMd = foreign.md(),
            foreignShare = ratio(foreign, exact),
            capacityMd = capacityOf?.invoke(bucket)?.md(),
        )
    }
}

/** The footer per bucket over ALL of [aggregates]: per-domain totals, the bucket total and foreign figures, and its [capacityOf]. */
internal fun bucketTotals(
    aggregates: List<CostAggregate>,
    buckets: List<CostBucket>,
    columns: List<CostColumn>,
    capacityOf: ((CostBucket) -> BigDecimal)?,
): List<CostBucketTotals> {
    val byBucket = aggregates.groupBy { it.bucket }
    return buckets.map { bucket ->
        val inBucket = byBucket[bucket.key].orEmpty()
        val exact = inBucket.exact()
        val foreign = inBucket.filter { it.foreign }.exact()
        val byDomain = inBucket.groupBy { it.domain }
        CostBucketTotals(
            columns = columns.map { it.copy(totalMd = byDomain[it.domain].orEmpty().exact().md()) },
            totalMd = exact.md(),
            foreignMd = foreign.md(),
            foreignShare = ratio(foreign, exact),
            capacityMd = capacityOf?.invoke(bucket)?.md(),
        )
    }
}
