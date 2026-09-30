package ch.nokillswit.reports

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.metrics.MetricsSettingsResponse

/*
 * The counting half of the data-quality report (report 14): turns the grouped SQL rows `DataQualityTasks.kt` fetched — plus the
 * epics of `DataQualityEpics.kt` — into the findings, the logged-hours figure and the org drill. Everything summed here is a
 * handful of `GROUP BY` rows (one per team × member), never the tasks or worklogs themselves; the label lookups (issue keys,
 * summaries, names) run for the capped lists only, inside the caller's `suspendTransaction`.
 */

/** Everything one report request fetched, so the counting functions take one argument. */
internal class DataQualityInput(
    val filter: ReportFilter,
    val settings: MetricsSettingsResponse,
    val taskScope: TaskScope,
    val worklogScope: WorklogScope,
    val tasks: List<TaskAgg>,
    val worklogs: List<WorklogAgg>,
    val epics: List<DqEpic>,
    val memberDays: List<MemberDays>,
) {
    val doneTasks: Int get() = tasks.filter { it.done }.sumOf { it.population }
    val openStartedTasks: Int get() = tasks.filter { !it.done }.sumOf { it.population }
    val workCategoryConfigured: Boolean get() = taskScope.workCategoryConnections.isNotEmpty()
}

private fun List<TaskAgg>.count(kind: TaskFindingKind, done: Boolean) = filter { it.done == done }.sumOf { it.counts[kind] ?: 0 }

private fun List<TaskAgg>.doneOpen(kind: TaskFindingKind) = DoneOpen(count(kind, done = true), count(kind, done = false))

private fun List<TaskAgg>.countsOf() = TaskCounts(
    done = filter { it.done }.sumOf { it.population }, openStarted = filter { !it.done }.sumOf { it.population },
    withoutWorklogs = count(TaskFindingKind.WITHOUT_WORKLOGS, done = true), unassigned = count(TaskFindingKind.UNASSIGNED, done = true),
    outsideSprint = count(TaskFindingKind.OUTSIDE_SPRINT, done = true), crossDomain = count(TaskFindingKind.CROSS_DOMAIN, done = true),
    noEstimate = doneOpen(TaskFindingKind.NO_ESTIMATE), noEpic = doneOpen(TaskFindingKind.NO_EPIC),
    noWorkCategory = doneOpen(TaskFindingKind.NO_WORK_CATEGORY),
)

/** The task findings of the whole read, each with its capped, labelled list. */
internal class TaskFindings(
    val coverage: WorklogCoverage,
    val noEstimate: TaskFinding,
    val noEpic: TaskFinding,
    val noWorkCategory: TaskFinding,
    val unassigned: TaskFinding,
    val outsideSprint: TaskFinding,
    val crossDomain: TaskFinding,
)

internal suspend fun taskFindings(input: DataQualityInput): TaskFindings {
    val counts = TaskFindingKind.entries.associateWith { kind ->
        val done = input.tasks.count(kind, done = true)
        val open = input.tasks.count(kind, done = false)
        val md = input.tasks.filter { it.done || !kind.doneOnly }.sumOf { it.md[kind] ?: 0.0 }
        Triple(done, open, md)
    }
    val rows = counts.mapValues { (kind, count) ->
        if (count.first + count.second == 0) emptyList() else fetchTaskItems(input.taskScope, kind)
    }
    val listed = rows.values.flatten()
    val labels = workItemLabels(listed.map { it.connectionId to it.issueId })
    val names = accountDisplayNames(listed.mapNotNull { it.account }.distinct())
    fun finding(kind: TaskFindingKind): TaskFinding {
        val (done, open, md) = counts.getValue(kind)
        return TaskFinding(
            done = done, open = open, total = done + open, md = md,
            items = rows.getValue(kind).map { t ->
                TaskRef(
                    issueKey = t.issueKey, summary = labels[t.connectionId to t.issueId]?.second, teamId = t.team,
                    assigneeAccountId = t.account, assignee = t.account?.let { names[it] ?: it }, doneAt = t.doneAt,
                    startedAt = t.startedAt, estimateMd = t.estimateMd?.toDouble(),
                )
            },
        )
    }
    val doneTasks = input.doneTasks
    val withWorklogs = doneTasks - counts.getValue(TaskFindingKind.WITHOUT_WORKLOGS).first
    return TaskFindings(
        coverage = WorklogCoverage(
            doneTasks, withWorklogs, if (doneTasks > 0) withWorklogs.toDouble() / doneTasks else null,
            finding(TaskFindingKind.WITHOUT_WORKLOGS),
        ),
        noEstimate = finding(TaskFindingKind.NO_ESTIMATE), noEpic = finding(TaskFindingKind.NO_EPIC),
        noWorkCategory = finding(TaskFindingKind.NO_WORK_CATEGORY), unassigned = finding(TaskFindingKind.UNASSIGNED),
        outsideSprint = finding(TaskFindingKind.OUTSIDE_SPRINT), crossDomain = finding(TaskFindingKind.CROSS_DOMAIN),
    )
}

/** Hours actually logged by team members (an author in no team has no roster to compare against) over their member-days. */
internal fun loggedHoursOf(input: DataQualityInput): LoggedHours {
    val hours = input.worklogs.filter { it.team != null }.sumOf { it.md } * input.settings.hoursPerDay
    val memberDays = input.memberDays.sumOf { it.days }
    return LoggedHours(memberDays, hours, if (memberDays > 0.0) hours / memberDays else null)
}

internal suspend fun lateLoggingOf(input: DataQualityInput): LateLogging {
    val worst = fetchWorstWorklogs(input.worklogScope)
    val labels = workItemLabels(worst.map { it.connectionId to it.issueId })
    val authors = accountDisplayNames(worst.mapNotNull { it.account }.distinct())
    return LateLogging(
        worklogs = input.worklogs.sumOf { it.worklogs },
        measurable = input.worklogs.sumOf { it.measurable },
        over1Day = input.worklogs.sumOf { it.over1Day },
        over7Days = input.worklogs.sumOf { it.over7Days },
        distribution = buildDistribution(
            fetchLatenessMs(input.worklogScope).map { it.toDouble() / MILLIS_PER_DAY }, input.settings.minSampleSize,
        ),
        worst = worst.map { w ->
            val label = labels[w.connectionId to w.issueId]
            LateWorklog(
                worklogId = w.worklogId, issueKey = label?.first ?: w.issueId.toString(), summary = label?.second,
                authorAccountId = w.account, author = w.account?.let { authors[it] ?: it }, teamId = w.team,
                startedAt = w.startedAt, lateDays = w.lateMs!!.toDouble() / MILLIS_PER_DAY,
            )
        },
    )
}

/** Worklog authors who were in no team when they logged — by account, most logged MD first. */
internal suspend fun authorsWithoutTeamOf(input: DataQualityInput): QualityList<AuthorWithoutTeam> {
    val byAccount = input.worklogs.filter { it.team == null }.groupBy { it.account }
    val names = accountDisplayNames(byAccount.keys.filterNotNull())
    return byAccount.map { (account, rows) ->
        AuthorWithoutTeam(account, account?.let { names[it] ?: it }, rows.sumOf { it.worklogs }, rows.sumOf { it.md })
    }.sortedWith(compareByDescending<AuthorWithoutTeam> { it.md }.thenBy(nullsLast()) { it.accountId }).capped()
}

/**
 * The roster is global (D1: a team's members are not tied to a connection), the findings are not. A UNIT read of a single
 * `connectionId` keeps only the teams that have a finding row in that connection, so its hours are not divided by the member-days
 * of teams that work elsewhere — the price is that a team silent in that one connection is not listed. The default read (every
 * connection) and TEAM/USER reads keep the whole roster: there a silent team with member-days IS the finding.
 */
internal fun rosterInScope(
    filter: ReportFilter,
    memberDays: List<MemberDays>,
    tasks: List<TaskAgg>,
    worklogs: List<WorklogAgg>,
    epics: List<DqEpic>,
): List<MemberDays> {
    if (filter.connectionId == null || filter.level != ReportLevel.UNIT) return memberDays
    val teams = (tasks.mapNotNull { it.team } + worklogs.mapNotNull { it.team } + epics.mapNotNull { it.owner }).toSet()
    return memberDays.filter { it.teamId in teams }
}

/** A row of the org drill before its label: a team (UNIT) or a member (TEAM). */
private data class GroupId(val teamId: UInt?, val accountId: String?)

/**
 * The org drill. UNIT: one group per team that has any finding row or roster (tasks by credit/current team, worklogs by author
 * team, epics by owner team; a null team is UNASSIGNED / UNOWNED). TEAM: one per member (tasks by assignee at done / now,
 * worklogs by author, roster members included), no epic counts. USER: none. Ordered by label (null last).
 */
internal suspend fun dataQualityGroups(input: DataQualityInput): List<DataQualityGroup> {
    val level = input.filter.level
    if (level == ReportLevel.USER) return emptyList()
    val unit = level == ReportLevel.UNIT
    fun id(team: UInt?, account: String?) = if (unit) GroupId(team, null) else GroupId(null, account)
    val tasks = input.tasks.groupBy { id(it.team, it.account) }
    val worklogs = input.worklogs.groupBy { id(it.team, it.account) }
    val epics = if (unit) input.epics.groupBy { GroupId(it.owner, null) } else emptyMap()
    val days = input.memberDays.groupBy { if (unit) GroupId(it.teamId, null) else GroupId(null, it.accountId) }
    val ids = (tasks.keys + worklogs.keys + epics.keys + days.keys)
    val teamLabels = teamNames(ids.mapNotNull { it.teamId })
    val accountLabels = accountDisplayNames(ids.mapNotNull { it.accountId })
    return ids.map { key ->
        val logs = worklogs[key].orEmpty()
        val memberDays = days[key].orEmpty().sumOf { it.days }
        val md = logs.sumOf { it.md }
        val hours = md * input.settings.hoursPerDay
        DataQualityGroup(
            teamId = key.teamId,
            accountId = key.accountId,
            label = key.teamId?.let { teamLabels[it] ?: it.toString() } ?: key.accountId?.let { accountLabels[it] ?: it },
            tasks = tasks[key].orEmpty().countsOf(),
            worklogs = WorklogCounts(
                worklogs = logs.sumOf { it.worklogs }, md = md, over1Day = logs.sumOf { it.over1Day },
                over7Days = logs.sumOf { it.over7Days }, hours = hours, memberDays = memberDays,
                hoursPerMemberDay = if (memberDays > 0.0) hours / memberDays else null,
            ),
            epics = if (unit) epicCountsOf(epics[key].orEmpty()) else null,
        )
    }.sortedWith(byLabelThenId({ it.label }, { it.teamId }, { it.accountId }))
}

private fun epicCountsOf(rows: List<DqEpic>) = EpicCounts(
    epics = rows.size, withoutEstimate = rows.count { it.withoutEstimate }, withoutDates = rows.count { it.withoutDates },
    outsidePvHorizon = rows.count { it.outsideHorizon }, drifting = rows.count { it.drifting },
)

/** The configuration- and DERIVE-level findings (`DataQualityConfig.kt`), gathered. */
internal class StructuralFindings(
    val domainsWithoutOwner: QualityList<UnownedDomain>,
    val unmappedStatuses: QualityList<UnmappedStatus>,
    val unmappedBoards: UnmappedBoardList,
    val snapshotDrift: QualityList<SnapshotDrift>,
    val deriveWarnings: List<DeriveWarning>,
)

internal suspend fun structuralFindings(
    input: DataQualityInput,
    mappings: Map<UInt, ConnectionMappings>,
    deriveClocks: Map<UInt, Long>,
    sprints: List<SprintRow>,
): StructuralFindings {
    val connectionIds = input.taskScope.connectionIds
    return StructuralFindings(
        domainsWithoutOwner = fetchUnownedDomains(input.filter, connectionIds).capped(),
        unmappedStatuses = fetchUnmappedStatuses(connectionIds, mappings).capped(),
        unmappedBoards = fetchUnmappedBoards(connectionIds, mappings, fetchDoneInTeamlessSprints(input.taskScope)),
        snapshotDrift = fetchSnapshotDrift(sprints).capped(),
        deriveWarnings = fetchDeriveWarnings(deriveClocks),
    )
}
