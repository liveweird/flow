package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.metrics.historyHorizon
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import java.time.ZoneId

/*
 * `GET /api/v1/reports/home` (`.claude/docs/reports.md` "Home overview"): the overview of the picked active teams in ONE transaction,
 * composed from the other reports' own fetches and value functions (their names are cited where reused). The per-section
 * builders here are the figure half of what `HomeDrill.kt` lists item by item, so a drill is exactly what its figure counted: the
 * done-task figures are the SAME predicates and arithmetic aggregated in Postgres (`HomeDoneFigures.kt`), graded row for row against
 * the per-item functions below by `HomeDoneFiguresGoldenTest`.
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
    val filter = homeFilter(calendar, nowMs, settings.historyHorizon(calendar.dayOf(nowMs)))
    val stamp = deriveStamp(connections.map { it.id })
    return HomeContext(settings, calendar, filter, homeWindowsOf(calendar, filter), connections, stamp, nowMs)
}

// ---- The per-item values the drills list (the figures aggregate the same predicates in Postgres, `HomeDoneSql.kt`) ----

/** The estimate at done (MD) of a task that has one; `null` = unestimated (out of the median). */
internal fun estimateMdOf(task: HomeDoneTask): Double? = task.estimateAtDoneMd.takeIf { it.isEstimate() }?.toDouble()

/** The cycle in working days of a started task (`workingDaysOf`'s value: both cycle columns set); `null` = never started. */
internal fun cycleDaysOf(task: HomeDoneTask): Double? = task.cycleWorkingDays?.toDouble()

/** The logged cost (MD) of a task with logged time (D14: `has_worklogs` and an `actual_md` above 0); `null` otherwise. */
internal fun costMdOf(task: HomeDoneTask): Double? = task.worklogActualMd?.takeIf { it.isWorkedMd() }?.toDouble()

/** `actual ÷ estimate at start` (D15, [actualOverEstimate] over a worked, started task); `null` = in no PRED sample. */
internal fun taskRatioOf(task: HomeDoneTask): Double? = task.worklogActualMd?.let { actualOverEstimate(it, task.estimateAtStartMd) }

internal fun HomeDoneTask.hasNoEstimate(): Boolean = noEstimate

internal fun HomeDoneTask.hasNoAssignee(): Boolean = accountId == null

/** The age in working days at the request's clock of an item open since [startedAt] ([ageOf]). */
internal fun HomeContext.ageAt(startedAt: Long): Double = ageOf(calendar, startedAt, nowMs)

/** The age of an open item in working days at the request's clock. */
internal fun HomeContext.ageOfItem(item: OpenItem): Double = ageAt(item.startedAt)

internal fun HomeContext.epicGapTolerance(): BigDecimal = estimateGapTolerance(settings.epicEstimateGapTolerancePercent)

// ---- The sections ----

private fun qualityOf(ctx: HomeContext, done: HomeDoneTeamFigures, epics: List<DqEpic>): HomeQuality {
    val findings = taskFindingsOf(done.current.agg, done.previous.agg, ctx.minSample)
    val tolerance = ctx.epicGapTolerance()
    return HomeQuality(
        tasksNoEstimate = findings.noEstimate,
        tasksUnassigned = findings.unassigned,
        tasksEstimatedLate = findings.estimatedLate,
        epicsNoEstimate = countFigure(epics.count { it.withoutEstimate }).toFigure(),
        epicsNoDates = countFigure(epics.count { it.withoutDates }).toFigure(),
        epicsEstimateGap = countFigure(epics.count { it.estimateGap(tolerance) }).toFigure(),
    )
}

private val WIP_FRACTIONS = listOf(0.50, 0.85, 0.95)

private fun wipOf(ctx: HomeContext, open: OpenWip, team: UInt): HomeWip {
    val (p50, p85, p95) = open.percentiles(team, ctx.minSample, WIP_FRACTIONS) ?: return HomeWip(open.count(team), true, null, null, null)
    return HomeWip(open.count(team), false, p50, p85, p95)
}

private fun homeSprintOf(row: SprintRow, snapshot: SnapshotRow?) = HomeSprint(
    sprintId = row.sprintId, name = row.name, startAt = row.startAt, completedAt = row.completedAt!!,
    capacityMd = row.capacityMd, capacitySource = row.capacitySource,
    initialMd = row.live.initialMd, initialItems = row.live.initialItems, finalMd = row.live.finalMd, finalItems = row.live.finalItems,
    deliveredMd = row.delivered.deliveredMd, deliveredItems = row.delivered.deliveredItems,
    snapshot = snapshot?.full, drift = figuresDrift(row.full, snapshot?.full),
)

/**
 * Everything the figures are built from, read once: both windows' done-task figures of the LISTED teams, aggregated in Postgres
 * (`HomeDoneFigures.kt`, no row loaded) and, as a count, the current window's done tasks of no active team, the accuracy epics, the epic
 * population, and the open items as counts and the few ages the percentiles read (`HomeOpenItems.kt`, never one object per open item).
 */
private class HomeRows(
    val done: Map<UInt, HomeDoneTeamFigures>,
    val doneOfNoActiveTeam: Int,
    val epics: List<AccuracyEpic>,
    val openTasks: OpenWip,
    val openEpics: OpenWip,
    val dqEpics: List<DqEpic>,
)

private suspend fun ReportService.readHomeRows(ctx: HomeContext, listed: Set<UInt>, activeIds: Set<UInt>): HomeRows {
    val conns = ctx.connectionIds
    val both = ctx.windows.both
    val current = ctx.windows.current.let { it.fromMs to it.toMs }
    suspend fun openWip(rows: OpenRows) =
        if (conns.isEmpty()) OpenWip.EMPTY else readOpenWip(rows, listed, ctx.minSample, WIP_FRACTIONS, ctx::ageAt)
    return HomeRows(
        done = readHomeDoneFigures(ctx.filter, conns, ctx.windows, listed, ctx.calendar, ctx.zone),
        doneOfNoActiveTeam = countHomeDoneOutside(ctx.filter, conns, current, activeIds),
        epics = fetchAccuracyEpics(ctx.filter, conns, both),
        openTasks = openWip(openTaskRows(ctx.filter, conns)),
        openEpics = openWip(openEpicRows(ctx.filter, conns)),
        dqEpics = fetchDqEpics(ctx.filter, conns, current, emptyMap(), ctx.nowMs),
    )
}

private fun teamOf(ctx: HomeContext, team: HomeTeamRef, rows: HomeRows, sprint: HomeSprint?, code: HomeCodeRead): HomeTeam {
    val windows = ctx.windows
    val done = rows.done.getValue(team.id)
    val epics = rows.epics.filter { it.ownerTeamId == team.id }
    return HomeTeam(
        teamId = team.id,
        name = team.name,
        lastSprint = sprint,
        granularity = granularityOf(done.current, done.previous, ctx.minSample),
        accuracy = HomeAccuracy(
            tasks = taskPredOf(done.current.agg, done.previous.agg, ctx.minSample),
            epics = predLine(
                epics.filter { windows.current.contains(it.doneAt) }, epics.filter { windows.previous.contains(it.doneAt) },
                ctx.minSample,
            ) { it.ratioAtStart },
        ),
        wipEpics = wipOf(ctx, rows.openEpics, team.id),
        wipTasks = wipOf(ctx, rows.openTasks, team.id),
        throughput = throughputOf(done, ctx.minSample),
        quality = qualityOf(ctx, done, rows.dqEpics.filter { it.owner == team.id }),
        code = ctx.homeTeamCode(code, team.id),
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
 * the sprint and epic reads stay unit-wide, the done tasks are read for the listed teams only (the others are one count) and the per-team
 * figures are computed for the listed teams.
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
    val listedIds = teams.map { it.id }.toSet()
    val activeIds = active.map { it.id }.toSet()
    val rows = readHomeRows(ctx, listedIds, activeIds)
    val code = readHomeCode(ctx, listedIds)
    // Only the listed teams' last sprints are kept, so only their snapshots are read (the sprint rows themselves are a unit-wide read).
    val sprintRows = resolveSprintRows(ReportPeriod.LastSprints(1), ctx.connectionIds, null, ctx.filter.historyHorizon, ctx.calendar)
        .filter { it.teamId in listedIds }.associateBy { it.teamId }
    val snapshots = fetchSnapshots(sprintRows.values.toList()).associateBy { it.connectionId to it.sprintId }
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
            teamOf(ctx, team, rows, sprint, code)
        },
        unassigned = HomeUnassigned(
            doneTasks = rows.doneOfNoActiveTeam,
            openTasks = rows.openTasks.countOutside(activeIds),
            epics = rows.dqEpics.count { it.owner !in activeIds },
        ),
        code = HomeCode(code.freshness, code.overBudget),
    )
}
