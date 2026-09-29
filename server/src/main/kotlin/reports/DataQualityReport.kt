package ch.nokillswit.reports

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The most entries any list of the data-quality report carries; the `total` beside it says how many matched. */
const val DATA_QUALITY_MAX_ITEMS = 50

/** A capped list: the first [DATA_QUALITY_MAX_ITEMS] matches in [items] and the [total] number of matches ("and N more"). */
@Serializable
data class QualityList<T>(val total: Int, val items: List<T>)

internal fun <T> List<T>.capped(): QualityList<T> = QualityList(size, take(DATA_QUALITY_MAX_ITEMS))

/** The sizes of the populations the findings are counted over (`.claude/docs/measures.md` "Report 14"). */
@Serializable
data class DataQualityPopulations(val doneTasks: Int, val openStartedTasks: Int, val epics: Int, val worklogs: Int)

/**
 * D14: how many DONE tasks of the period carry worklogs. [coverage] is `withWorklogs ÷ doneTasks` as a fraction 0..1
 * (`null` with no DONE task); [without] lists the ones that do not (`has_worklogs = false`).
 */
@Serializable
data class WorklogCoverage(val doneTasks: Int, val withWorklogs: Int, val coverage: Double?, val without: TaskFinding)

/**
 * Logged hours against the roster: [hours] = Σ `fact_worklog.md` × `hoursPerDay` of team members, [memberDays] = their
 * `team_membership` rows clipped to the period (up to now) counted in working days; [hoursPerMemberDay] = the quotient
 * (`null` without member-days), to read against the report's `hoursPerDay`.
 */
@Serializable
data class LoggedHours(val memberDays: Double, val hours: Double, val hoursPerMemberDay: Double?)

/** One of the most belatedly logged worklogs: [lateDays] = `late_ms` in days. */
@Serializable
data class LateWorklog(
    val worklogId: Long,
    val issueKey: String,
    val summary: String?,
    val authorAccountId: String?,
    val author: String?,
    val teamId: UInt?,
    val startedAt: Long,
    val lateDays: Double,
)

/**
 * Late logging over the period's worklogs (`started_at` in it): [worklogs] of them, [measurable] of which know when they
 * were created (`late_ms` set, ≥ 0); [over1Day]/[over7Days] count those logged more than 1 / 7 days after they started;
 * [distribution] is the lateness in days (hidden below `minSampleSize`); [worst] the latest-logged ones.
 */
@Serializable
data class LateLogging(
    val worklogs: Int,
    val measurable: Int,
    val over1Day: Int,
    val over7Days: Int,
    val distribution: Distribution,
    val worst: List<LateWorklog>,
)

/**
 * Tasks and epics with missing data. The task findings count DONE tasks of the period and — separately — open started ones;
 * [workCategoryConfigured] says whether any connection in scope has a work-category field at all (with none, [noWorkCategory]
 * is empty by design); the epic findings count epics open or done in the period.
 */
@Serializable
data class MissingData(
    val noEstimate: TaskFinding,
    val noEpic: TaskFinding,
    val noWorkCategory: TaskFinding,
    val workCategoryConfigured: Boolean,
    val unassigned: TaskFinding,
    val epicsWithoutEstimate: QualityList<EpicRef>,
    val epicsWithoutDates: QualityList<EpicRef>,
    val epicsOutsidePvHorizon: QualityList<EpicRef>,
)

/** Per-group task counts; the `DoneOpen` findings split DONE-in-period from currently open started tasks. */
@Serializable
data class TaskCounts(
    val done: Int,
    val openStarted: Int,
    val withoutWorklogs: Int,
    val unassigned: Int,
    val outsideSprint: Int,
    val crossDomain: Int,
    val noEstimate: DoneOpen,
    val noEpic: DoneOpen,
    val noWorkCategory: DoneOpen,
)

/** Per-group worklog counts and logged hours against the roster ([hoursPerMemberDay] `null` without member-days). */
@Serializable
data class WorklogCounts(
    val worklogs: Int,
    val md: Double,
    val over1Day: Int,
    val over7Days: Int,
    val hours: Double,
    val memberDays: Double,
    val hoursPerMemberDay: Double?,
)

/** Per-owner-team epic counts (UNIT level only). */
@Serializable
data class EpicCounts(val epics: Int, val withoutEstimate: Int, val withoutDates: Int, val outsidePvHorizon: Int, val drifting: Int)

/**
 * One row of the org drill: a team at UNIT level (`teamId` null = UNASSIGNED tasks and authors / UNOWNED epics) or a member
 * at TEAM level (`accountId` null = unassigned tasks / authors with no account). [epics] is null at TEAM level — epics carry
 * no user.
 */
@Serializable
data class DataQualityGroup(
    val teamId: UInt?,
    val accountId: String?,
    val label: String?,
    val tasks: TaskCounts,
    val worklogs: WorklogCounts,
    val epics: EpicCounts?,
)

@Serializable
data class DataQualityReport(
    val meta: ReportMeta,
    /** The configured `hoursPerDay` the logged hours per member-day are to be read against. */
    val hoursPerDay: Double,
    val populations: DataQualityPopulations,
    val groups: List<DataQualityGroup>,
    val worklogCoverage: WorklogCoverage,
    val loggedHours: LoggedHours,
    val lateLogging: LateLogging,
    val missing: MissingData,
    /** DONE tasks with no sprint at done (D10) — `md` is their estimate at done. */
    val outsideSprint: TaskFinding,
    /** DONE tasks whose epic lives in another domain (`cross_domain`). */
    val crossDomain: TaskFinding,
    /** Epics with a D11 drift flag; `flags` names them. */
    val epicDrift: QualityList<EpicRef>,
    val domainsWithoutOwner: QualityList<UnownedDomain>,
    val unmappedStatuses: QualityList<UnmappedStatus>,
    val unmappedBoards: UnmappedBoardList,
    val authorsWithoutTeam: QualityList<AuthorWithoutTeam>,
    val snapshotDrift: QualityList<SnapshotDrift>,
    val deriveWarnings: List<DeriveWarning>,
)

/**
 * `GET /api/v1/reports/data-quality` (v0.3.0 M5 commit 17, Report 14, `.claude/docs/measures.md` "Report 14"): where the
 * data the other reports stand on is missing or inconsistent — worklog coverage, logged hours per member-day, late logging,
 * missing estimate/epic/work category, epic dates and drift, mapping gaps, sprint-snapshot drift and DERIVE warnings.
 * Every list is capped with a `total` beside it. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.dataQuality(filter: ReportFilter, nowMs: Long): DataQualityReport = suspendTransaction(database) {
    val scope = resolveReportScope(filter, nowMs)
    val calendar = workingCalendarOf(scope.settings)
    val connectionIds = scope.connectionIds
    val mappings = readConnectionMappings(connectionIds)
    val clocks = deriveClocks(connectionIds)
    val taskScope = TaskScope(filter, connectionIds, scope.window, mappings.filterValues { it.workCategoryConfigured }.keys)
    val worklogScope = WorklogScope(filter, connectionIds, scope.window)
    val tasks = fetchTaskAggs(taskScope)
    val worklogs = fetchWorklogAggs(worklogScope)
    val epics = fetchDqEpics(filter, connectionIds, scope.window, clocks, nowMs)
    val input = DataQualityInput(
        filter = filter,
        settings = scope.settings,
        taskScope = taskScope,
        worklogScope = worklogScope,
        tasks = tasks,
        worklogs = worklogs,
        epics = epics,
        memberDays = rosterInScope(filter, fetchMemberDays(filter, calendar, scope.window, nowMs), tasks, worklogs, epics),
    )
    val sprintScoped = if (filter.level == ReportLevel.USER) emptyList() else scope.sprintRows
    val structure = structuralFindings(input, mappings, clocks, sprintScoped)
    val found = taskFindings(input)
    DataQualityReport(
        meta = scope.meta,
        hoursPerDay = scope.settings.hoursPerDay,
        populations = DataQualityPopulations(
            doneTasks = input.doneTasks, openStartedTasks = input.openStartedTasks,
            epics = epics.size, worklogs = worklogs.sumOf { it.worklogs },
        ),
        groups = dataQualityGroups(input),
        worklogCoverage = found.coverage,
        loggedHours = loggedHoursOf(input),
        lateLogging = lateLoggingOf(input),
        missing = MissingData(
            noEstimate = found.noEstimate, noEpic = found.noEpic, noWorkCategory = found.noWorkCategory,
            workCategoryConfigured = input.workCategoryConfigured, unassigned = found.unassigned,
            epicsWithoutEstimate = epics.filter { it.withoutEstimate }.sortedWith(DQ_EPIC_ORDER).map { it.toRef(false) }.capped(),
            epicsWithoutDates = epics.filter { it.withoutDates }.sortedWith(DQ_EPIC_ORDER).map { it.toRef(false) }.capped(),
            epicsOutsidePvHorizon = epics.filter { it.outsideHorizon }.sortedWith(DQ_EPIC_ORDER).map { it.toRef(false) }.capped(),
        ),
        outsideSprint = found.outsideSprint,
        crossDomain = found.crossDomain,
        epicDrift = epics.filter { it.drifting }.sortedWith(DQ_EPIC_ORDER).map { it.toRef(true) }.capped(),
        domainsWithoutOwner = structure.domainsWithoutOwner,
        unmappedStatuses = structure.unmappedStatuses,
        unmappedBoards = structure.unmappedBoards,
        authorsWithoutTeam = authorsWithoutTeamOf(input),
        snapshotDrift = structure.snapshotDrift,
        deriveWarnings = structure.deriveWarnings,
    )
}
