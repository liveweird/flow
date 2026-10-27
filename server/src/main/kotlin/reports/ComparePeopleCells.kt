package ch.nokillswit.reports

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.metrics.WorkingCalendar
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/*
 * Report 18's figures (`.claude/docs/reports.md` "Report 18"): one person's cell of every row, computed from the populations the other
 * reports already fetch. Every value function is the OTHER report's own (`workingDaysOf`, `ratioValueOf`, `ratioAtStartOf`,
 * `AdjustmentItem.adjustedChange` through `figuresOf`, `DoneItem.blockedValue`, ...), so a compare-people figure and the report it
 * mirrors cannot disagree on the same person. The inputs are tagged by the account they are attributed to (assignee at done; for the
 * adjustments the A25 rule; the author for worklogs; the assignee at each day end for WIP).
 */

/** A person's available man-days (A39), exact: only for a caller who may see them. [days] = the capacity days that person had. */
internal class ComparePersonCapacity(val exactMd: BigDecimal, val days: Int)

/** Everything one comparison reads, both people's rows together; [capacity] is `null` when the caller may not see capacity (A39). */
internal class CompareData(
    val minSample: Int,
    val window: Pair<Long, Long>,
    val calendar: WorkingCalendar,
    val done: List<CompareDoneTask>,
    val cycle: List<DoneCycleTask>,
    val accuracy: List<AccuracyTask>,
    val adjustments: List<AdjustmentItem>,
    val blocked: List<DoneItem>,
    val worklogs: List<CompareWorklog>,
    val wip: CompareWip,
    val capacity: Map<String, ComparePersonCapacity>?,
)

private fun plain(value: Double?, n: Int) = CompareCell(value, n, hidden = false)

/** A distribution-shaped cell: [pick] of the [values]' distribution, withheld (`hidden`, no value) under [minSample] values. */
private fun distributionCell(values: List<Double>, minSample: Int, pick: (Distribution) -> Double?): CompareCell {
    val distribution = buildDistribution(values, minSample, tailPercent = 0)
    return CompareCell(if (distribution.hidden) null else pick(distribution), values.size, distribution.hidden)
}

private fun share(part: Int, whole: Int): Double? = if (whole == 0) null else part.toDouble() / whole

private fun List<BigDecimal>.exactSum(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

private fun BigDecimal.over(divisor: BigDecimal): Double? = if (divisor.signum() == 0) null else toDouble() / divisor.toDouble()

/** Every row of [account] -- keyed by [CompareRowSpec.key]; the capacity rows only when [CompareData.capacity] is set. */
internal fun CompareData.cellsOf(account: String): Map<String, CompareCell> {
    val cells = LinkedHashMap<String, CompareCell>()
    cells += deliveryCells(account)
    cells += cycleCells(account)
    cells += estimationCells(account)
    cells += wipCells(account)
    cells += timeCells(account)
    cells += qualityCells(account)
    return cells
}

private fun CompareData.deliveryCells(account: String): Map<String, CompareCell> {
    val tasks = done.filter { it.accountId == account }
    val md = tasks.map { it.md }.exactSum()
    val cells = mutableMapOf(
        "deliveredMd" to plain(md.md(), tasks.size),
        "deliveredItems" to plain(tasks.size.toDouble(), tasks.size),
        "taskSizeP50" to distributionCell(
            accuracy.filter { it.accountId == account }.mapNotNull(::estimateAtStartOf), minSample,
        ) { it.p50 },
        "reopenedShare" to plain(share(tasks.count { it.reopenCount >= 1 }, tasks.size), tasks.size),
    )
    val available = capacity?.get(account)?.exactMd
    if (capacity != null) {
        cells["deliveredMdPerAvailableMd"] = plain(available?.let { md.over(it) }, tasks.size)
        cells["deliveredItemsPerAvailableMd"] = plain(available?.let { BigDecimal(tasks.size).over(it) }, tasks.size)
    }
    return cells
}

private fun CompareData.cycleCells(account: String): Map<String, CompareCell> {
    val tasks = cycle.filter { it.accountId == account }
    val working = tasks.mapNotNull(::workingDaysOf)
    val elapsed = tasks.mapNotNull(::elapsedDaysOf)
    return mapOf(
        "cycleWorkingDaysP50" to distributionCell(working, minSample) { it.p50 },
        "cycleWorkingDaysP90" to distributionCell(working, minSample) { it.p90 },
        "cycleElapsedDaysP50" to distributionCell(elapsed, minSample) { it.p50 },
        "cycleElapsedDaysP90" to distributionCell(elapsed, minSample) { it.p90 },
        "flowEfficiencyP50" to distributionCell(tasks.mapNotNull(::flowEfficiencyValueOf), minSample) { it.p50 },
        "reportedTimeRatioP50" to distributionCell(tasks.mapNotNull(::ratioValueOf), minSample) { it.p50 },
    )
}

private fun CompareData.estimationCells(account: String): Map<String, CompareCell> {
    val tasks = accuracy.filter { it.accountId == account }
    val start = tasks.mapNotNull(::ratioAtStartOf)
    val atDone = tasks.mapNotNull(::ratioAtDoneOf)
    val figures = figuresOf(adjustments.filter { it.account == account }, window, minSample, tailPercent = 0)
    val change = figures.changeDistribution
    return mapOf(
        "accuracyAtStartPred25" to predCell(start),
        "accuracyAtDonePred25" to predCell(atDone),
        "adjustedShare" to CompareCell(figures.share, figures.started, hidden = figures.share == null),
        "adjustmentMedianChange" to CompareCell(change.p50, change.n.toInt(), change.hidden),
    )
}

/** PRED(25): the share of the tasks whose estimate was within 25% of the actual; withheld under `minSampleSize` ratios. */
private fun CompareData.predCell(ratios: List<Double>): CompareCell {
    val measures = accuracyMeasures(ratios, minSample)
    return CompareCell(measures?.pred25, ratios.size, hidden = measures == null)
}

private fun CompareData.wipCells(account: String): Map<String, CompareCell> {
    val blockedItems = blocked.filter { it.account == account }
    val cycleItems = blockedItems.filter { (it.cycleWd ?: 0.0) > 0.0 }
    val cycleSum = cycleItems.sumOf { it.cycleWd!! }
    return wipFlowCells(account) + mapOf(
        "blockedItemsShare" to plain(share(blockedItems.count { it.blockedWd > 0.0 }, blockedItems.size), blockedItems.size),
        "blockedShareOfCycle" to plain(if (cycleSum > 0.0) cycleItems.sumOf { it.blockedWd } / cycleSum else null, cycleItems.size),
    )
}

/** The WIP rows: the mean over the listed working days, the count and the median age of the tasks held at the last listed day. */
private fun CompareData.wipFlowCells(account: String): Map<String, CompareCell> {
    if (wip.days.isEmpty()) {
        return listOf("avgWip", "wipAtEndItems", "wipAtEndMedianAgeWd").associateWith { plain(null, 0) }
    }
    val perDay = wip.dayCounts[account].orEmpty()
    val atEnd = wip.atEnd.filter { it.accountId == account }
    return mapOf(
        "avgWip" to plain(meanOverWorkingDays(perDay, wip.workingDays), wip.workingDays.size),
        "wipAtEndItems" to plain(atEnd.size.toDouble(), atEnd.size),
        "wipAtEndMedianAgeWd" to distributionCell(wipAges(atEnd), minSample) { it.p50 },
    )
}

/** The age in working days, at the END of the last listed day, of each task held then that has started (the Aging WIP rule). */
internal fun CompareData.wipAges(atEnd: List<WipTaskDay>): List<Double> {
    val lastDay = wip.days.lastOrNull() ?: return emptyList()
    val dayEnd = calendar.dayBoundsMs(lastDay).second
    return atEnd.mapNotNull { wip.startedAt[it.connectionId to it.issueId] }.map { ageOf(calendar, it, dayEnd) }
}

private fun CompareData.timeCells(account: String): Map<String, CompareCell> {
    val logs = worklogs.filter { it.accountId == account }
    val logged = logs.map { it.md }.exactSum()
    val foreign = logs.filter { it.foreignWork }.map { it.md }.exactSum()
    val cells = mutableMapOf(
        "loggedMd" to plain(logged.md(), logs.size),
        "foreignShare" to plain(foreign.over(logged), logs.size),
    )
    val person = capacity?.get(account)
    if (capacity != null) {
        cells["availableMd"] = plain((person?.exactMd ?: BigDecimal.ZERO).md(), person?.days ?: 0)
        cells["loggedPerAvailableMd"] = plain(person?.exactMd?.let { logged.over(it) }, logs.size)
    }
    return cells
}

private fun CompareData.qualityCells(account: String): Map<String, CompareCell> {
    val tasks = cycle.filter { it.accountId == account }
    val measurable = worklogs.filter { it.accountId == account && it.lateMs != null }
    val own = done.filter { it.accountId == account }
    return mapOf(
        "worklogCoverage" to plain(share(tasks.count { it.hasWorklogs }, tasks.size), tasks.size),
        "lateLoggingShare" to plain(share(measurable.count { it.lateMs!! > MILLIS_PER_DAY }, measurable.size), measurable.size),
        "noEstimateShare" to plain(share(own.count { it.estimateSource == NO_ESTIMATE_SOURCE }, own.size), own.size),
    )
}

/** `fact_task_delivery.estimate_source` of a task with no estimate at all (the data-quality `NO_ESTIMATE` predicate). */
internal const val NO_ESTIMATE_SOURCE = "NONE"

/** One person's share of one work-mix key: their delivered man-days there, the items, and the share of ALL their delivered man-days. */
@Serializable
data class CompareMixShare(val md: Double, val items: Int, val share: Double?)

/**
 * One key of a work mix with both people's share. [key] is `null` only for the tasks that have no domain; a task without a work
 * category is the literal key `UNCATEGORIZED` (the report filters' own value, so it can be sent back as a slice).
 */
@Serializable
data class CompareMixRow(val key: String?, val a: CompareMixShare, val b: CompareMixShare)

/** The delivered tasks of both people grouped three ways: by activity type, work category and domain. Neutral: no cue. */
@Serializable
data class CompareWorkMix(val activityType: List<CompareMixRow>, val workCategory: List<CompareMixRow>, val domain: List<CompareMixRow>)

internal fun workMixOf(done: List<CompareDoneTask>, people: List<String>): CompareWorkMix = CompareWorkMix(
    activityType = mixBy(done, people) { it.activityType },
    workCategory = mixBy(done, people) { it.workCategory ?: UNCATEGORIZED },
    domain = mixBy(done, people) { it.domainKey },
)

private fun mixBy(done: List<CompareDoneTask>, people: List<String>, keyOf: (CompareDoneTask) -> String?): List<CompareMixRow> {
    val (first, second) = people
    val sharesA = mixSharesOf(done, first, keyOf)
    val sharesB = mixSharesOf(done, second, keyOf)
    return sharesA.map { (key, a) -> CompareMixRow(key, a, sharesB.getValue(key)) }
        .sortedWith(compareByDescending<CompareMixRow> { it.a.md + it.b.md }.thenBy(nullsLast()) { it.key })
}

/**
 * One person's work-mix shares of [done] by [keyOf]: for EVERY key present among [done] (whoever's tasks they are), the person's
 * delivered man-days and items there and the share of all their delivered man-days (null when they delivered none). Compare people
 * lists the keys of both people with this; Person trend (report 20) calls it once per window on the person's own tasks.
 */
internal fun mixSharesOf(done: List<CompareDoneTask>, account: String, keyOf: (CompareDoneTask) -> String?): Map<String?, CompareMixShare> {
    val total = done.filter { it.accountId == account }.map { it.md }.exactSum()
    return done.groupBy(keyOf).mapValues { (_, rows) ->
        val own = rows.filter { it.accountId == account }
        val md = own.map { it.md }.exactSum()
        CompareMixShare(md.md(), own.size, md.over(total))
    }
}
