package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.WorkingCalendar
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import java.time.ZoneId

/*
 * `GET /api/v1/reports/home` (`.claude/docs/reports.md` "Home overview"): the overview of the picked active teams in ONE transaction,
 * composed from the other reports' own fetches and value functions (their names are cited where reused). The per-section
 * builders here are the figure half of what `HomeDrill.kt` lists item by item, so a drill is exactly what its figure counted.
 */

/** What every Home read shares: settings and calendar (one read), the active connections, the DERIVE stamp, the windows, the clock. */
internal class HomeContext(
    val settings: MetricsSettingsResponse,
    val calendar: WorkingCalendar,
    val filter: ReportFilter,
    val windows: HomeWindows,
    val connections: List<HomeConnection>,
    val stamp: DeriveStamp,
    val nowMs: Long,
) {
    val connectionIds: List<UInt> = connections.map { it.id }
    val minSample: Int get() = settings.minSampleSize
    val zone: ZoneId = WorkingCalendar.zoneOf(settings.timeZone)

    /** `meta`: level UNIT, domain view TASK, `from`/`to` = the window; [resolvedSprints] only on the overview itself. */
    fun meta(resolvedSprints: List<ResolvedSprintGroup> = emptyList()): ReportMeta =
        filter.toMeta(stamp.derivedAt, stamp.configRevision, minSample, resolvedSprints)
}

internal suspend fun ReportService.homeContext(nowMs: Long): HomeContext {
    val (settings, calendar) = metricsSettings.readWithCalendar()
    val connections = fetchHomeConnections()
    val filter = homeFilter(calendar, nowMs)
    val stamp = deriveStamp(connections.map { it.id })
    return HomeContext(settings, calendar, filter, homeWindowsOf(calendar, filter), connections, stamp, nowMs)
}

// ---- The per-item values the figures and the drills share ----

/** The estimate at done (MD) of a task that has one; `null` = unestimated (out of the median). */
internal fun estimateMdOf(task: HomeDoneTask): Double? = task.estimateAtDoneMd.takeIf { it.isEstimate() }?.toDouble()

/** The cycle in working days of a started task ([workingDaysOf]); `null` = never started. */
internal fun cycleDaysOf(task: HomeDoneTask): Double? = workingDaysOf(task.toCycle())

/** The logged cost (MD) of a task with logged time (D14: `has_worklogs` and an `actual_md` above 0); `null` otherwise. */
internal fun costMdOf(task: HomeDoneTask): Double? = task.actualMd.toDouble().takeIf { task.toAccuracy().isWorked() }

/** `actual ÷ estimate at start` (D15, [ratioAtStartOf]); `null` = in no PRED sample. */
internal fun taskRatioOf(task: HomeDoneTask): Double? = ratioAtStartOf(task.toAccuracy())

internal fun HomeDoneTask.hasNoEstimate(): Boolean = estimateSource == NO_ESTIMATE_SOURCE

internal fun HomeDoneTask.hasNoAssignee(): Boolean = accountId == null

/** The age of an open item in working days at the request's clock ([ageOf]). */
internal fun HomeContext.ageOfItem(item: OpenItem): Double = ageOf(calendar, item.startedAt, nowMs)

internal fun HomeContext.epicGapTolerance(): BigDecimal = estimateGapTolerance(settings.epicEstimateGapTolerancePercent)

// ---- The sections ----

private fun granularityOf(current: List<HomeDoneTask>, previous: List<HomeDoneTask>, minSample: Int) = HomeGranularity(
    estimateMd = trended(HomeMeasure.ESTIMATE_MD, current, previous, minSample) { medianFigure(it.mapNotNull(::estimateMdOf), minSample) },
    cycleWorkingDays = trended(HomeMeasure.CYCLE_WORKING_DAYS, current, previous, minSample) {
        medianFigure(it.mapNotNull(::cycleDaysOf), minSample)
    },
    costMd = trended(HomeMeasure.COST_MD, current, previous, minSample) { medianFigure(it.mapNotNull(::costMdOf), minSample) },
)

private const val CYCLE_P85 = 0.85

private fun throughputOf(ctx: HomeContext, current: List<HomeDoneTask>, previous: List<HomeDoneTask>): HomeThroughput {
    val min = ctx.minSample
    fun cycle(rows: List<HomeDoneTask>) = rows.mapNotNull(::cycleDaysOf)
    return HomeThroughput(
        weeks = weeklyDelivery(current, ctx.windows.current, ctx.zone),
        deliveredMd = trended(HomeMeasure.DELIVERED_MD, current, previous, min) { sumFigure(it.sumMdAtDone().md(), it.size) },
        deliveredItems = trended(HomeMeasure.DELIVERED_ITEMS, current, previous, min) { countFigure(it.size) },
        cycleP50 = trended(HomeMeasure.CYCLE_PERCENTILE, current, previous, min) { medianFigure(cycle(it), min) },
        cycleP85 = trended(HomeMeasure.CYCLE_PERCENTILE, current, previous, min) { percentileFigure(cycle(it), CYCLE_P85, min) },
    )
}

private fun qualityOf(
    ctx: HomeContext,
    current: List<HomeDoneTask>,
    previous: List<HomeDoneTask>,
    epics: List<DqEpic>,
): HomeQuality {
    fun tasks(finding: (HomeDoneTask) -> Boolean) =
        trended(HomeMeasure.FINDING_COUNT, current, previous, ctx.minSample) { countFigure(it.count(finding)) }
    val tolerance = ctx.epicGapTolerance()
    return HomeQuality(
        tasksNoEstimate = tasks(HomeDoneTask::hasNoEstimate),
        tasksUnassigned = tasks(HomeDoneTask::hasNoAssignee),
        tasksEstimatedLate = tasks { it.estimatedLate },
        epicsNoEstimate = countFigure(epics.count { it.withoutEstimate }).toFigure(),
        epicsNoDates = countFigure(epics.count { it.withoutDates }).toFigure(),
        epicsEstimateGap = countFigure(epics.count { it.estimateGap(tolerance) }).toFigure(),
    )
}

private const val WIP_P50 = 0.50
private const val WIP_P85 = 0.85
private const val WIP_P95 = 0.95

private fun wipOf(ctx: HomeContext, items: List<OpenItem>): HomeWip {
    val hidden = items.size < ctx.minSample
    val ages = items.map { ctx.ageOfItem(it) }.sorted()
    fun at(fraction: Double) = if (hidden) null else percentileContinuous(ages, fraction)
    return HomeWip(items.size, hidden, at(WIP_P50), at(WIP_P85), at(WIP_P95))
}

private fun homeSprintOf(row: SprintRow, snapshot: SnapshotRow?) = HomeSprint(
    sprintId = row.sprintId, name = row.name, startAt = row.startAt, completedAt = row.completedAt!!,
    capacityMd = row.capacityMd, capacitySource = row.capacitySource,
    initialMd = row.live.initialMd, initialItems = row.live.initialItems, finalMd = row.live.finalMd, finalItems = row.live.finalItems,
    deliveredMd = row.delivered.deliveredMd, deliveredItems = row.delivered.deliveredItems,
    snapshot = snapshot?.full, drift = figuresDrift(row.full, snapshot?.full),
)

/** Every row the figures are built from, read once: both windows' done tasks and accuracy epics, the open items, the epic population. */
private class HomeRows(
    val done: List<HomeDoneTask>,
    val epics: List<AccuracyEpic>,
    val openTasks: List<OpenItem>,
    val openEpics: List<OpenItem>,
    val dqEpics: List<DqEpic>,
)

private suspend fun ReportService.readHomeRows(ctx: HomeContext): HomeRows {
    val conns = ctx.connectionIds
    val both = ctx.windows.both
    val current = ctx.windows.current.let { it.fromMs to it.toMs }
    return HomeRows(
        done = fetchHomeDoneTasks(ctx.filter, conns, both),
        epics = fetchAccuracyEpics(ctx.filter, conns, both),
        openTasks = fetchOpenTasks(ctx.filter, conns),
        openEpics = fetchOpenEpics(ctx.filter, conns),
        dqEpics = fetchDqEpics(ctx.filter, conns, current, emptyMap(), ctx.nowMs),
    )
}

private fun teamOf(ctx: HomeContext, team: HomeTeamRef, rows: HomeRows, sprint: HomeSprint?): HomeTeam {
    val windows = ctx.windows
    val tasks = rows.done.filter { it.creditTeamId == team.id }
    val currentTasks = tasks.filter { windows.current.contains(it.doneAt) }
    val previousTasks = tasks.filter { windows.previous.contains(it.doneAt) }
    val epics = rows.epics.filter { it.ownerTeamId == team.id }
    return HomeTeam(
        teamId = team.id,
        name = team.name,
        lastSprint = sprint,
        granularity = granularityOf(currentTasks, previousTasks, ctx.minSample),
        accuracy = HomeAccuracy(
            tasks = predLine(currentTasks, previousTasks, ctx.minSample, ::taskRatioOf),
            epics = predLine(
                epics.filter { windows.current.contains(it.doneAt) }, epics.filter { windows.previous.contains(it.doneAt) },
                ctx.minSample,
            ) { it.ratioAtStart },
        ),
        wipEpics = wipOf(ctx, rows.openEpics.filter { it.teamId == team.id }),
        wipTasks = wipOf(ctx, rows.openTasks.filter { it.teamId == team.id }),
        throughput = throughputOf(ctx, currentTasks, previousTasks),
        quality = qualityOf(ctx, currentTasks, previousTasks, rows.dqEpics.filter { it.owner == team.id }),
    )
}

private suspend fun freshnessOf(ctx: HomeContext): List<HomeFreshness> {
    val derives = lastDeriveFinishedAt(ctx.connectionIds)
    return ctx.connections.map { HomeFreshness(it.id, it.name, it.lastSyncAt, derives[it.id]) }
}

/**
 * `GET /api/v1/reports/home`: see [HomeOverview]. [teamIds] names the teams to list (each a real ACTIVE team, else `400`; at most
 * [HOME_MAX_TEAMS], enforced by the route); empty = the first [HOME_MAX_TEAMS] active teams in name order, whoever asks. The listed
 * teams always come back in NAME order whatever the order of [teamIds], zero and empty figures included, so all charts share one
 * order. [HomeOverview.unassigned] counts the work of NO ACTIVE team (no team, or a soft-deleted one), never "of no listed team":
 * the reads stay unit-wide and only the per-team figures are computed for the listed teams.
 */
suspend fun ReportService.home(nowMs: Long, teamIds: List<UInt> = emptyList()): HomeOverview = reportTransaction {
    val ctx = homeContext(nowMs)
    val active = fetchHomeTeams()
    val teams = if (teamIds.isEmpty()) {
        active.take(HOME_MAX_TEAMS)
    } else {
        val known = active.map { it.id }.toSet()
        teamIds.firstOrNull { it !in known }?.let { throw BadRequestException("Unknown or inactive teamId: $it") }
        active.filter { it.id in teamIds }
    }
    val rows = readHomeRows(ctx)
    // Only the listed teams' last sprints are kept, so only their snapshots are read (the sprint rows themselves are a unit-wide read).
    val listedIds = teams.map { it.id }.toSet()
    val sprintRows = resolveSprintRows(ReportPeriod.LastSprints(1), ctx.connectionIds, null)
        .filter { it.teamId in listedIds }.associateBy { it.teamId }
    val snapshots = fetchSnapshots(sprintRows.values.toList()).associateBy { it.connectionId to it.sprintId }
    val activeIds = active.map { it.id }.toSet()
    val resolved = teams.mapNotNull { team -> sprintRows[team.id]?.let { ResolvedSprintGroup(team.id, listOf(it.sprintId)) } }
    HomeOverview(
        meta = ctx.meta(resolved),
        window = ctx.windows.current.toWire(),
        previousWindow = ctx.windows.previous.toWire(),
        timeZone = ctx.settings.timeZone,
        epicEstimateGapTolerancePercent = ctx.settings.epicEstimateGapTolerancePercent,
        freshness = freshnessOf(ctx),
        teams = teams.map { team ->
            val sprint = sprintRows[team.id]?.let { homeSprintOf(it, snapshots[it.connectionId to it.sprintId]) }
            teamOf(ctx, team, rows, sprint)
        },
        unassigned = HomeUnassigned(
            doneTasks = rows.done.count { ctx.windows.current.contains(it.doneAt) && it.creditTeamId !in activeIds },
            openTasks = rows.openTasks.count { it.teamId !in activeIds },
            epics = rows.dqEpics.count { it.owner !in activeIds },
        ),
    )
}
