package ch.nokillswit.reports

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/** One of the two compared people; [label] is the Jira display name (the account id when none is known). */
@Serializable
data class ComparePerson(val accountId: String, val label: String)

/**
 * One row of the table: the figure [a] and [b] (index-aligned with `people`), how to read it ([direction], [target] for a TARGET row,
 * [tolerance] = the gap below which two values are a tie) and the verdict -- [difference] (B minus A), [better] and [margin] (see
 * [CompareVerdict]). [drill] names the `…/items` view that lists the items behind a cell (none for the available man-days).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CompareRow(
    val key: String,
    val group: CompareGroup,
    val unit: CompareUnit,
    val direction: CompareDirection,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val target: Double? = null,
    val tolerance: Double,
    val a: CompareCell,
    val b: CompareCell,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val better: CompareSide? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val difference: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val margin: Double? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val drill: CompareDrillView? = null,
)

/** The two histograms the page draws side by side: each is the UNION of the two people with a `byUser` split of every bucket. */
@Serializable
data class CompareCycleTime(val workingDays: Distribution, val elapsedDays: Distribution)

/**
 * The capacity block (A39): present ONLY for an ADMIN or the active manager of every team either person belonged to in the period;
 * [days] = the period days up to today. The per-person figures are the `availableMd` row and the three rows divided by it.
 */
@Serializable
data class ComparePeopleCapacity(val days: Int)

/** The working days the WIP rows were averaged over (`firstDay`/`lastDay` ISO dates of the listed calendar days; omitted when none). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CompareWipDays(
    val workingDays: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val firstDay: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastDay: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ComparePeopleReport(
    val meta: ReportMeta,
    /** Exactly two, in request order: [CompareSide.A] then [CompareSide.B]. */
    val people: List<ComparePerson>,
    val rows: List<CompareRow>,
    val cycleTime: CompareCycleTime,
    val workMix: CompareWorkMix,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val capacity: ComparePeopleCapacity? = null,
    val wipDays: CompareWipDays,
    /** Why the WIP rows are empty or partial (not derived yet, a period before the backfill window...); omitted otherwise. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val note: String? = null,
)

/**
 * `GET /api/v1/reports/compare-people` (Report 18, `.claude/docs/reports.md` "Report 18"): two people head to head over a `from`/`to`
 * period, read ACROSS every team they belonged to. Every row is a figure another report already defines, computed per person from
 * that report's own fetch and value function ([cellsOf]); [compareCells] turns each pair into the verdict. [grant] is the
 * route's A39 verdict (an ADMIN, or the active manager of every team either person belonged to), re-checked here against the memberships
 * this very transaction reads: only then are the four capacity rows and the `capacity` block computed and returned.
 * See `ComparePeopleKernels.kt` for the row table.
 */
internal suspend fun ReportService.comparePeople(
    filter: ReportFilter,
    nowMs: Long,
    grant: CapacityGrant,
): ComparePeopleReport = reportTransaction {
    val scope = resolveReportScope(filter, nowMs)
    val picked = pickedAccounts(filter)
    val data = loadCompareData(filter, scope, nowMs, grant)
    val showCapacity = data.capacity != null
    val (idA, idB) = picked.ids
    val cellsA = data.cellsOf(idA)
    val cellsB = data.cellsOf(idB)
    val rows = COMPARE_ROWS.filter { showCapacity || !it.requiresCapacity }.map { spec ->
        val a = cellsA.getValue(spec.key)
        val b = cellsB.getValue(spec.key)
        val verdict = compareCells(spec, a, b, data.minSample)
        CompareRow(
            key = spec.key, group = spec.group, unit = spec.unit, direction = spec.direction, target = spec.target,
            tolerance = spec.unit.tolerance, a = a, b = b, better = verdict.better, difference = verdict.difference,
            margin = verdict.margin, drill = spec.drill,
        )
    }
    ComparePeopleReport(
        meta = scope.meta,
        people = picked.ids.map { ComparePerson(it, picked.label(it)) },
        rows = rows,
        cycleTime = CompareCycleTime(
            workingDays = cycleDistribution(data, picked, filter.tailPercent, ::workingDaysOf),
            elapsedDays = cycleDistribution(data, picked, filter.tailPercent, ::elapsedDaysOf),
        ),
        workMix = workMixOf(data.done, picked.ids),
        capacity = if (showCapacity) ComparePeopleCapacity(comparePeopleCapacityDays(data.calendar, data.window, nowMs).size) else null,
        wipDays = CompareWipDays(
            data.wip.workingDays.size, data.wip.days.firstOrNull()?.toString(), data.wip.days.lastOrNull()?.toString(),
        ),
        note = data.wip.note,
    )
}

/** The union distribution of [valueOf] over the two people's cycle tasks with the per-person split of every bucket (the UNION's edges). */
internal fun cycleDistribution(
    data: CompareData,
    picked: PickedAccounts,
    tailPercent: Int,
    valueOf: (DoneCycleTask) -> Double?,
): Distribution {
    val byAccount = data.cycle.groupBy { it.accountId }
    return buildDistributionByUser(
        picked.ids.map { id -> UserValues(id, picked.label(id), byAccount[id].orEmpty().mapNotNull(valueOf)) },
        data.minSample,
        tailPercent,
    )
}

/**
 * Every population one comparison reads, for the filter's two people over `scope.window` (a `from`/`to` period: never null here).
 * [grant] is the route's A39 verdict, re-checked against the memberships read here (a [CapacityGrant.None] reads none at all).
 */
internal suspend fun R2dbcTransaction.loadCompareData(
    filter: ReportFilter,
    scope: ReportScope,
    nowMs: Long,
    grant: CapacityGrant,
): CompareData {
    val window = requireNotNull(scope.window) { "compare-people always has a from/to window" }
    val connections = scope.connectionIds
    val memberships =
        if (grant == CapacityGrant.None) emptyList() else readPeopleMemberships(filter.accountIds, window.first, window.second)
    val allowed = when (grant) {
        CapacityGrant.Admin -> true
        CapacityGrant.None -> false
        is CapacityGrant.Teams -> memberships.isNotEmpty() && grant.teamIds.containsAll(memberships.map { it.teamId })
    }
    val capacity = if (allowed) loadPeopleCapacity(memberships, connections, window, scope.calendar, nowMs) else null
    return CompareData(
        minSample = scope.settings.minSampleSize,
        window = window,
        calendar = scope.calendar,
        done = fetchCompareDoneTasks(filter, connections, window),
        cycle = fetchDoneCycleTasks(filter, connections, window),
        accuracy = fetchAccuracyTasks(filter, connections, window),
        adjustments = fetchAdjustmentTasks(filter, connections, window),
        blocked = fetchBlockedItems(filter, BlockedItemKind.TASK, connections, window),
        worklogs = fetchCompareWorklogs(filter, connections, window),
        wip = fetchCompareWip(scope, filter),
        capacity = capacity,
    )
}
