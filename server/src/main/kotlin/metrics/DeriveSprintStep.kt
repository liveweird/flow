package ch.nokillswit.metrics

import ch.nokillswit.norm.HierarchyBucket
import ch.nokillswit.norm.NormalizedFieldInterval
import ch.nokillswit.norm.PROCESSING_VERSION
import ch.nokillswit.norm.SprintRef
import ch.nokillswit.norm.TrackedField
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.norm.hierarchyBucket
import java.time.LocalDate

/**
 * [runSprintStep]'s own result — the sprint count for `derive_runs.row_counts`, plus whether the
 * connection's own Sprint field could not be resolved this run (`MetricsConfigService
 * .detectedSprintFieldId` returned `null` despite the connection HAVING sprints) — the sprint step
 * then skips writing ANY sprint facts entirely rather than fabricating membership from a
 * display-name match against a field that may have been renamed or localized on the real tenant.
 */
data class SprintStepOutcome(val sprintCount: Int, val fieldUnresolved: Boolean)

/**
 * The sprint step's own body (v0.3.0 M3 commit 8, moved to `DeriveSprintStep.kt` purely to keep that
 * class under detekt's `LargeClass` threshold — see the thin delegating method there): `dim_sprint`,
 * `fact_sprint_scope`, `fact_sprint`, and D13's append-only `fact_sprint_snapshot` for every
 * newly-closed, team-mapped sprint. Level-0, non-sub-task, non-epic tasks ONLY (`levelZeroTasks`
 * below) — matching `sample-data/jira/generate.mjs`'s own `sprintItemsOf` (sub-tasks follow their
 * parent task's sprint, never carrying independent scope of their own). [sprintFieldId] is the
 * connection's own detected Sprint custom field id (`MetricsConfigService.detectedSprintFieldId`) —
 * `null` means the profile detected none, in which case this whole step is skipped (see
 * [SprintStepOutcome]).
 */
internal suspend fun runSprintStep(
    workItemStore: WorkItemStore,
    metricsStore: MetricsStore,
    connectionId: UInt,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    context: DeriveContext,
    sprintCalendars: SprintCalendarService,
    derivedById: Map<Long, ItemDerived>,
    graceMs: Long,
    configRevision: Long,
    sprintFieldId: String?,
): SprintStepOutcome {
    val sprints = workItemStore.allSprintRefs(connectionId)
    if (sprints.isEmpty()) return SprintStepOutcome(0, fieldUnresolved = false)
    if (sprintFieldId == null) return SprintStepOutcome(0, fieldUnresolved = true)

    val capacity = sprintCalendars.forDerive(connectionId)
    val claimsBySprint = claimedDaysOfRun(sprints, context)
    val laterSprintIdsBySprint = laterSprintIdsPerSprint(sprints)
    val existingSnapshotSprintIds = metricsStore.existingSnapshotSprintIds(connectionId)
    val firstSuccessfulStartedAt = metricsStore.firstSuccessfulDeriveRunStartedAt(connectionId)

    val scopeItemsBySprint = mutableMapOf<Long, MutableList<SprintScopeItem>>()
    val levelZeroTasks = workItems.filter { hierarchyBucket(it.hierarchyLevel) == HierarchyBucket.TASK && !it.isSubtask }
    for (batch in levelZeroTasks.chunked(DERIVE_BATCH_SIZE)) {
        val ids = batch.map { it.issueId }
        val sprintChangesByIssue =
            workItemStore.fieldChangesByFieldIds(connectionId, listOf(sprintFieldId), ids).groupBy { it.issueId }
        val assigneeIntervalsByIssue = workItemStore.fieldIntervalsByIssue(connectionId, TrackedField.ASSIGNEE, ids)

        val scopeRowsBatch = mutableListOf<FactSprintScopeRow>()
        val taskSprintRowsBatch = mutableListOf<TaskSprintRow>()
        for (item in batch) {
            val derived = derivedById.getValue(item.issueId)
            val memberships =
                sprintMembership(item.createdAt, sprintChangesByIssue[item.issueId].orEmpty(), item.currentSprintIds)
            if (memberships.isEmpty()) continue
            memberships.forEach { taskSprintRowsBatch += TaskSprintRow(item.issueId, it.sprintId, it.fromAtMs, it.toAtMs) }
            val itemScopeRows = itemSprintScopeRows(
                item, derived, memberships, sprints, laterSprintIdsBySprint, assigneeIntervalsByIssue[item.issueId].orEmpty(),
                graceMs, context.now,
            )
            itemScopeRows.forEach { (sprintId, row) ->
                scopeItemsBySprint.getOrPut(sprintId) { mutableListOf() } += row
                scopeRowsBatch += FactSprintScopeRow(sprintId, row)
            }
        }
        metricsStore.insertFactSprintScope(connectionId, scopeRowsBatch, configRevision)
        metricsStore.insertTaskSprint(connectionId, taskSprintRowsBatch)
    }

    val dimRows = mutableListOf<DimSprintRow>()
    val factRows = mutableListOf<FactSprintRow>()
    for (sprint in sprints) {
        val teamId = sprint.boardId?.let { context.boardTeamByBoardId[it] }
        val (capacityMd, capacitySource) = sprintCapacity(sprint, teamId, capacity, claimsBySprint[sprint.sprintId].orEmpty(), context)
        dimRows += DimSprintRow(
            sprint.sprintId, sprint.boardId, teamId, sprint.name, sprint.state,
            sprint.startAtMs, sprint.endAtMs, sprint.completeAtMs, capacityMd, capacitySource,
        )
        val totals = sprintTotals(scopeItemsBySprint[sprint.sprintId].orEmpty())
        val load = sprintLoad(totals.committedMd, capacityMd)
        val factRow = FactSprintRow(sprint.sprintId, teamId, sprint.completeAtMs, totals, capacityMd, load)
        factRows += factRow

        val closedAndMapped = sprint.state.equals("closed", ignoreCase = true) && sprint.completeAtMs != null && teamId != null
        if (closedAndMapped && sprint.sprintId !in existingSnapshotSprintIds) {
            val reconstructed = firstSuccessfulStartedAt == null || sprint.completeAtMs!! < firstSuccessfulStartedAt
            metricsStore.insertFactSprintSnapshot(
                connectionId, factRow, scopeItemsBySprint[sprint.sprintId].orEmpty(), configRevision, PROCESSING_VERSION,
                reconstructed, context.now,
            )
        }
    }
    metricsStore.insertDimSprints(connectionId, dimRows, configRevision)
    metricsStore.insertFactSprint(connectionId, factRows, configRevision)
    return SprintStepOutcome(sprints.size, fieldUnresolved = false)
}

/**
 * One task's `fact_sprint_scope` row for every sprint it was ever a member of (extracted out of
 * [runSprintStep] purely to stay under detekt's `CyclomaticComplexMethod` threshold — the sprint
 * loop's own branching lives here instead).
 */
private fun itemSprintScopeRows(
    item: WorkItemStore.DerivationWorkItemRow,
    derived: ItemDerived,
    memberships: List<SprintMembershipInterval>,
    sprints: List<SprintRef>,
    laterSprintIdsBySprint: Map<Long, Set<Long>>,
    assigneeIntervals: List<NormalizedFieldInterval>,
    graceMs: Long,
    now: Long,
): List<Pair<Long, SprintScopeItem>> {
    val membershipsBySprintId = memberships.groupBy { it.sprintId }
    return sprints.mapNotNull { sprint ->
        val startAt = sprint.startAtMs ?: return@mapNotNull null
        val intervals = membershipsBySprintId[sprint.sprintId] ?: return@mapNotNull null
        val row = sprintScope(
            issueId = item.issueId,
            sprintStartAtMs = startAt,
            sprintCloseAtMs = sprint.completeAtMs ?: now,
            graceMs = graceMs,
            membershipIntervals = intervals,
            estimateTimeline = derived.estimateTimeline,
            assigneeIntervals = assigneeIntervals,
            doneAtMs = derived.done,
            inLaterSprintOfTeam = memberships.any { it.sprintId in laterSprintIdsBySprint[sprint.sprintId].orEmpty() },
        ) ?: return@mapNotNull null
        sprint.sprintId to row
    }
}

/**
 * `committed_md / capacity_md`, or `null` where no honest ratio fits the column: no capacity, a capacity of 0, or a ratio above
 * [MAX_LOAD] (`fact_sprint.load` and `fact_sprint_snapshot.load` are `NUMERIC(8, 4)`; a manager can set a team default as small as
 * 0.01, and an overflowing insert would fail the whole connection's DERIVE).
 */
internal fun sprintLoad(committedMd: Double, capacityMd: Double?): Double? {
    if (capacityMd == null || capacityMd <= 0.0) return null
    val ratio = committedMd / capacityMd
    return ratio.takeIf { it <= MAX_LOAD }
}

/** The largest value `NUMERIC(8, 4)` holds. */
internal const val MAX_LOAD = 9999.9999

/**
 * The days each of the run's sprints may not count because an earlier sprint of the same team holds them (A42), by sprint id —
 * computed ONCE per run: the run is one connection and a team has one board on one connection, so grouping the run's sprints by team
 * is exactly the page's scope (`claimedDaysOnConnection`). A sprint with no team or no start claims and is claimed by nothing.
 */
private fun claimedDaysOfRun(sprints: List<SprintRef>, context: DeriveContext): Map<Long, Map<LocalDate, SprintCalendarClaim>> =
    sprints.mapNotNull { sprint ->
        val teamId = sprint.boardId?.let { context.boardTeamByBoardId[it] }
        val startAt = sprint.startAtMs
        if (teamId == null || startAt == null) {
            null
        } else {
            teamId to ClaimSprint(sprint.sprintId, sprint.name, startAt, sprint.endAtMs, sprint.completeAtMs)
        }
    }.groupBy({ it.first }, { it.second }).values.flatMap { claimedDaysBySprint(it, context.calendar).entries }
        .associate { it.key to it.value }

/**
 * (capacityMd, capacitySource) for one sprint (A3, A32, A42), first match wins: `CALENDAR` — the sprint's stored calendar, only while
 * it belongs to the sprint's CURRENT team and the sprint has a window, re-projected onto the current window, roster and [claimed] days
 * exactly as the GET shows it (no carry: that seeds only a sprint with no stored calendar); `TEAM_DEFAULT` — the team's default figure,
 * which needs a team but NOT a start (a sprint without one still gets it); `DEFAULT` — members x the working days of the sprint's
 * window `[startAt, endAt ?: completeAt ?: startAt)` that no earlier sprint of the team holds, no per-member absence data (CALENDAR and
 * DEFAULT both need a start). No team, or no figure the sprint qualifies for, maps to no capacity at all (`null`, `null`).
 */
private fun sprintCapacity(
    sprint: SprintRef,
    teamId: UInt?,
    capacity: SprintCapacityInputs,
    claimed: Map<LocalDate, SprintCalendarClaim>,
    context: DeriveContext,
): Pair<Double?, String?> {
    if (teamId == null) return null to null
    val startAt = sprint.startAtMs
    val stored = capacity.calendarsBySprintId[sprint.sprintId]?.takeIf { it.teamId == teamId }
    if (startAt != null && stored != null) {
        val window = sprintWindow(startAt, sprint.endAtMs, sprint.completeAtMs, context.calendar)
        if (window != null) {
            val endAt = sprint.endAtMs ?: sprint.completeAtMs ?: startAt
            val members = sprintMemberAccountIds(teamId, startAt, endAt, context)
            val projected = projectCalendar(stored.document, window, members, context.calendar, claimed, carry = null)
            return calendarCapacityMd(projected) to "CALENDAR"
        }
    }
    capacity.teamDefaultMdByTeamId[teamId]?.let { return it to "TEAM_DEFAULT" }
    if (startAt == null) return null to null
    val endAt = sprint.endAtMs ?: sprint.completeAtMs ?: startAt
    val countedDays = defaultCapacityDayCount(startAt, sprint.endAtMs, sprint.completeAtMs, context.calendar, claimed)
    return (sprintMemberAccountIds(teamId, startAt, endAt, context).size * countedDays.toDouble()) to "DEFAULT"
}

/** The accounts holding a membership of [teamId] that overlaps `[startAt, endAt)` — A3's member predicate, also the calendar roster. */
private fun sprintMemberAccountIds(teamId: UInt, startAt: Long, endAt: Long, context: DeriveContext): List<String> =
    context.membershipsByAccount.filterValues { intervals ->
        intervals.any { it.teamId == teamId && it.validFrom < endAt && (it.validTo == null || it.validTo > startAt) }
    }.keys.toList()

/**
 * Every sprint id LATER than each sprint of its own board (D10: one board per team, so "later sprint
 * of the same board" already means "later sprint of the same team") — ordered by `startAtMs` (a
 * sprint with no start, e.g. still FUTURE, sorts last), tie-broken by sprint id. A sprint whose board
 * is unknown (`boardId == null`) has no later sprints of anything.
 */
private fun laterSprintIdsPerSprint(sprints: List<SprintRef>): Map<Long, Set<Long>> {
    val result = mutableMapOf<Long, Set<Long>>()
    sprints.filter { it.boardId != null }.groupBy { it.boardId!! }.values.forEach { boardSprints ->
        val ordered = boardSprints.sortedWith(compareBy({ it.startAtMs ?: Long.MAX_VALUE }, { it.sprintId }))
        ordered.forEachIndexed { index, sprint -> result[sprint.sprintId] = ordered.drop(index + 1).map { it.sprintId }.toSet() }
    }
    return result
}
