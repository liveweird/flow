package ch.nokillswit.reports

import ch.nokillswit.metrics.MetricsTables
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * One owner team's epic cycle time (UNIT level only; `teamId` null = UNOWNED): the same two [Distribution]s as
 * [EpicCycleTime] over that team's epics, hidden below the minimum sample size with `n` always set.
 */
@Serializable
data class EpicCycleTimeGroup(
    val teamId: UInt?,
    val label: String?,
    val elapsedDays: Distribution,
    val workingDays: Distribution,
    val excluded: CycleTimeExcluded,
)

/**
 * The `epics` block of [CycleTimeReport] (`.claude/docs/measures.md` Reports 7, 8, "Epic cycle time"): epics DONE by
 * their own status (D11) with `done_at` in the window, cycle = `fact_epic_delivery.cycle_ms` / `cycle_working_days`
 * (the same `done_at - started_at` rule as a task), attributed to the domain's OWNER team (A19; no owner = UNOWNED).
 * Epics carry no user, so [groups] is one per owner team at UNIT level and empty at TEAM level, and at USER level
 * the whole block is an all-zero answer — never a silently team-wide one.
 */
@Serializable
data class EpicCycleTime(
    val elapsedDays: Distribution,
    val workingDays: Distribution,
    val excluded: CycleTimeExcluded,
    val groups: List<EpicCycleTimeGroup>,
)

/** One DONE epic with the columns the epic cycle-time read needs. */
private data class DoneEpicCycle(val ownerTeamId: UInt?, val cycleMs: Long?, val cycleWorkingDays: BigDecimal?)

/** The measurable epics: `cycle_ms` set, i.e. started (the only exclusion, as for tasks). */
private fun measurableEpics(epics: List<DoneEpicCycle>): List<DoneEpicCycle> =
    epics.filter { it.cycleMs != null && it.cycleWorkingDays != null }

private fun epicDistributions(epics: List<DoneEpicCycle>, minSample: Int): Triple<Distribution, Distribution, CycleTimeExcluded> {
    val rows = measurableEpics(epics)
    return Triple(
        buildDistribution(rows.map { it.cycleMs!! / MS_PER_DAY }, minSample),
        buildDistribution(rows.map { it.cycleWorkingDays!!.toDouble() }, minSample),
        CycleTimeExcluded(population = epics.size, neverStarted = epics.size - rows.size),
    )
}

/**
 * The `epics` block: DONE epics by `done_at` in [window] (`null` = nothing to read), narrowed by the filter's
 * owner team / domain / work category (`epicFactSlice`; `activityType` is not an epic attribute and is ignored).
 * Must run inside the caller's transaction.
 */
internal suspend fun epicCycleTime(
    filter: ReportFilter,
    connectionIds: List<UInt>,
    window: Pair<Long, Long>?,
    minSample: Int,
): EpicCycleTime {
    // Epics carry no user: USER level reads nothing.
    val epics = if (window == null || filter.level == ReportLevel.USER) emptyList() else fetchDoneEpicCycles(filter, connectionIds, window)
    val (elapsed, working, excluded) = epicDistributions(epics, minSample)
    // Only UNIT drills (by owner team); TEAM has no further split of epics.
    val groups = if (filter.level == ReportLevel.UNIT) {
        orgGroups(ReportLevel.UNIT, epics, { it.ownerTeamId }, { null }).map { (key, rows) ->
            val (groupElapsed, groupWorking, groupExcluded) = epicDistributions(rows, minSample)
            EpicCycleTimeGroup(key.teamId, key.label, groupElapsed, groupWorking, groupExcluded)
        }
    } else {
        emptyList()
    }
    return EpicCycleTime(elapsedDays = elapsed, workingDays = working, excluded = excluded, groups = groups)
}

private suspend fun fetchDoneEpicCycles(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): List<DoneEpicCycle> {
    if (connectionIds.isEmpty()) return emptyList()
    val e = MetricsTables.FactEpicDelivery
    val predicate = epicFactSlice(filter, connectionIds) and
        e.doneAt.isNotNull() and (e.doneAt greaterEq window.first) and (e.doneAt less window.second)
    return e.select(e.ownerTeamId, e.cycleMs, e.cycleWorkingDays).where { predicate }.toList()
        .map { DoneEpicCycle(it[e.ownerTeamId]?.value, it[e.cycleMs], it[e.cycleWorkingDays]) }
}
