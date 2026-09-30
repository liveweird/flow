package ch.nokillswit.reports

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.metrics.SprintTotals
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The fourteen sprint figures (`fact_sprint` / `fact_sprint_snapshot` / a per-user split of
 * `fact_sprint_scope`, all through [DeriveKernels.sprintTotals]'s own bucket predicates), MD beside items.
 * Used as the wire shape of a sprint's frozen [SprintConsistencySprint.snapshot].
 */
@Serializable
data class SprintFigures(
    val committedMd: Double,
    val committedItems: Int,
    val addedMd: Double,
    val addedItems: Int,
    val removedMd: Double,
    val removedItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val carriedOverMd: Double,
    val carriedOverItems: Int,
    val droppedMd: Double,
    val droppedItems: Int,
)

/**
 * One sprint's consistency row (Report 6.1-6.3 with the item counts of 13): every scope bucket, live,
 * beside the frozen [snapshot] (`null` until first snapshotted, D13 — and always at USER level) and
 * [drift] (any figure differs beyond [SPRINT_DRIFT_TOLERANCE_MD] MD / exact items).
 */
@Serializable
data class SprintConsistencySprint(
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val completedAt: Long?,
    val committedMd: Double,
    val committedItems: Int,
    val addedMd: Double,
    val addedItems: Int,
    val removedMd: Double,
    val removedItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val carriedOverMd: Double,
    val carriedOverItems: Int,
    val droppedMd: Double,
    val droppedItems: Int,
    val snapshot: SprintFigures?,
    val drift: Boolean,
)

/**
 * One org-drill entry: a team's summed figures at UNIT level ([teamId]/[label] set), or one
 * `assignee_at_commitment`'s at TEAM level ([accountId]/[label] set; both null = unassigned at
 * commitment). Always empty at USER level, where the sprints themselves are the one user's split.
 */
@Serializable
data class SprintConsistencyGroup(
    val teamId: UInt? = null,
    val accountId: String? = null,
    val label: String? = null,
    val committedMd: Double,
    val committedItems: Int,
    val addedMd: Double,
    val addedItems: Int,
    val removedMd: Double,
    val removedItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val deliveredMd: Double,
    val deliveredItems: Int,
    val carriedOverMd: Double,
    val carriedOverItems: Int,
    val droppedMd: Double,
    val droppedItems: Int,
)

@Serializable
data class SprintConsistencyReport(
    val meta: ReportMeta,
    val sprints: List<SprintConsistencySprint>,
    val groups: List<SprintConsistencyGroup>,
)

internal fun SprintTotals.toFigures() = SprintFigures(
    committedMd, committedItems, addedMd, addedItems, removedMd, removedItems, finalMd, finalItems,
    deliveredMd, deliveredItems, carriedOverMd, carriedOverItems, droppedMd, droppedItems,
)

private fun SprintFigures.sum(other: SprintFigures) = SprintFigures(
    committedMd + other.committedMd, committedItems + other.committedItems,
    addedMd + other.addedMd, addedItems + other.addedItems,
    removedMd + other.removedMd, removedItems + other.removedItems,
    finalMd + other.finalMd, finalItems + other.finalItems,
    deliveredMd + other.deliveredMd, deliveredItems + other.deliveredItems,
    carriedOverMd + other.carriedOverMd, carriedOverItems + other.carriedOverItems,
    droppedMd + other.droppedMd, droppedItems + other.droppedItems,
)

private val NO_FIGURES = SprintFigures(0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0)

private fun figuresDrift(live: SprintFigures, snapshot: SprintFigures?): Boolean {
    if (snapshot == null) return false
    fun md(a: Double, b: Double) = kotlin.math.abs(a - b) > SPRINT_DRIFT_TOLERANCE_MD
    return md(live.committedMd, snapshot.committedMd) || live.committedItems != snapshot.committedItems ||
        md(live.addedMd, snapshot.addedMd) || live.addedItems != snapshot.addedItems ||
        md(live.removedMd, snapshot.removedMd) || live.removedItems != snapshot.removedItems ||
        md(live.finalMd, snapshot.finalMd) || live.finalItems != snapshot.finalItems ||
        md(live.deliveredMd, snapshot.deliveredMd) || live.deliveredItems != snapshot.deliveredItems ||
        md(live.carriedOverMd, snapshot.carriedOverMd) || live.carriedOverItems != snapshot.carriedOverItems ||
        md(live.droppedMd, snapshot.droppedMd) || live.droppedItems != snapshot.droppedItems
}

private fun SprintRow.toConsistencySprint(figures: SprintFigures, snapshot: SprintFigures?, drift: Boolean) = SprintConsistencySprint(
    sprintId = sprintId, name = name, teamId = teamId, completedAt = completedAt,
    committedMd = figures.committedMd, committedItems = figures.committedItems,
    addedMd = figures.addedMd, addedItems = figures.addedItems,
    removedMd = figures.removedMd, removedItems = figures.removedItems,
    finalMd = figures.finalMd, finalItems = figures.finalItems,
    deliveredMd = figures.deliveredMd, deliveredItems = figures.deliveredItems,
    carriedOverMd = figures.carriedOverMd, carriedOverItems = figures.carriedOverItems,
    droppedMd = figures.droppedMd, droppedItems = figures.droppedItems,
    snapshot = snapshot, drift = drift,
)

private fun SprintFigures.toGroup(teamId: UInt?, accountId: String?, label: String?) = SprintConsistencyGroup(
    teamId = teamId, accountId = accountId, label = label,
    committedMd = committedMd, committedItems = committedItems,
    addedMd = addedMd, addedItems = addedItems,
    removedMd = removedMd, removedItems = removedItems,
    finalMd = finalMd, finalItems = finalItems,
    deliveredMd = deliveredMd, deliveredItems = deliveredItems,
    carriedOverMd = carriedOverMd, carriedOverItems = carriedOverItems,
    droppedMd = droppedMd, droppedItems = droppedItems,
)

/** One `fact_sprint_scope` row as the kernel's own [SprintScopeItem], so the bucket predicates are [DeriveKernels.sprintTotals]'s. */
private data class ScopeEntry(val connectionId: UInt, val sprintId: Long, val item: SprintScopeItem)

/**
 * `GET /api/v1/reports/sprint-consistency` (v0.3.0 M4 commit 10d, reports 6.1-6.3 with the item
 * counts of 13, `.claude/docs/measures.md` "Report 6"). Levels: UNIT `groups` per team summing every
 * figure; TEAM `groups` per `assignee_at_commitment` (Σ groups == the team figures for every
 * bucket); USER narrows `sprints` to that account's own rows (`snapshot` null, `drift` false,
 * `groups` empty). `teamId = 0` (UNASSIGNED) is always empty. See `.claude/docs/reports.md`.
 */
suspend fun ReportService.sprintConsistency(filter: ReportFilter): SprintConsistencyReport = suspendTransaction(database) {
    // A sprint-anchored report reads no time window, so `nowMs` only feeds the scope's unused `window`.
    val scope = resolveReportScope(filter, nowMillis())
    // Chronological, so `meta.resolvedSprints` lists each team's sprints in that order too (the scope's meta is unsorted).
    val sprintRows = scope.sprintRows.sortedWith(compareBy<SprintRow, Long?>(nullsLast()) { it.completedAt }.thenBy { it.sprintId })
    val meta = scope.meta.copy(resolvedSprints = resolvedSprintGroups(filter.period, sprintRows))

    when (filter.level) {
        ReportLevel.USER -> {
            val accountId = requireNotNull(filter.accountId) { "USER level always carries an accountId (ReportFilter's own invariant)" }
            val entries = fetchScopeEntries(sprintRows, accountId).groupBy { it.connectionId to it.sprintId }
            val sprints = sprintRows.map { row ->
                val items = entries[row.connectionId to row.sprintId].orEmpty().map { it.item }
                row.toConsistencySprint(DeriveKernels.sprintTotals(items).toFigures(), snapshot = null, drift = false)
            }
            SprintConsistencyReport(meta, sprints, emptyList())
        }
        else -> {
            val snapshots = fetchSnapshots(sprintRows).associateBy { it.connectionId to it.sprintId }
            val sprints = sprintRows.map { row ->
                val frozen = snapshots[row.connectionId to row.sprintId]?.full
                row.toConsistencySprint(row.full, frozen, figuresDrift(row.full, frozen))
            }
            val groups = if (filter.level == ReportLevel.UNIT) {
                teamConsistencyGroups(sprintRows)
            } else {
                userConsistencyGroups(fetchScopeEntries(sprintRows, accountId = null))
            }
            SprintConsistencyReport(meta, sprints, groups)
        }
    }
}

/** [accountId] `null` fetches every user's own rows (TEAM-level groups); non-null narrows to one (USER level). */
private suspend fun fetchScopeEntries(sprintRows: List<SprintRow>, accountId: String?): List<ScopeEntry> {
    if (sprintRows.isEmpty()) return emptyList()
    val s = MetricsStore.FactSprintScope
    var predicate: Op<Boolean> = (s.connectionId inList sprintRows.map { it.connectionId }.distinct()) and
        (s.sprintId inList sprintRows.map { it.sprintId }.distinct())
    accountId?.let { predicate = predicate and (s.assigneeAtCommitment eq it) }
    // connection IN (...) AND sprint IN (...) is a cross product: keep exactly the (connection, sprint) pairs
    // in scope, since two connections to one Jira site share sprint ids.
    val inScope = sprintRows.map { it.connectionId to it.sprintId }.toSet()
    return s.selectAll().where { predicate }.toList().filter { (it[s.connectionId].value to it[s.sprintId]) in inScope }.map {
        ScopeEntry(
            connectionId = it[s.connectionId].value,
            sprintId = it[s.sprintId],
            item = SprintScopeItem(
                issueId = it[s.issueId],
                addedAtMs = it[s.addedAt],
                removedAtMs = it[s.removedAt],
                committed = it[s.committed],
                inScopeAtClose = it[s.inScopeAtClose],
                estimateAtCommitmentMd = it[s.estimateAtCommitmentMd]?.toDouble(),
                estimateAtCloseMd = it[s.estimateAtCloseMd]?.toDouble(),
                estimateAtDoneMd = it[s.estimateAtDoneMd]?.toDouble(),
                assigneeAtCommitment = it[s.assigneeAtCommitment],
                doneInSprint = it[s.doneInSprint],
                carriedOver = it[s.carriedOver],
                dropped = it[s.dropped],
            ),
        )
    }
}

/** Σ of every figure per team, straight off the sprint rows' own `fact_sprint` figures (UNIT-level groups). */
private suspend fun teamConsistencyGroups(sprintRows: List<SprintRow>): List<SprintConsistencyGroup> =
    orgGroups(ReportLevel.UNIT, sprintRows, { it.teamId }, { null }).map { (key, rows) ->
        rows.map { it.full }.fold(NO_FIGURES, SprintFigures::sum).toGroup(key.teamId, null, key.label)
    }

/** One group per `assignee_at_commitment` (null = unassigned, last), the kernel's own bucket predicates over that user's rows. */
private suspend fun userConsistencyGroups(entries: List<ScopeEntry>): List<SprintConsistencyGroup> =
    orgGroups(ReportLevel.TEAM, entries, { null }, { it.item.assigneeAtCommitment }).map { (key, rows) ->
        DeriveKernels.sprintTotals(rows.map { it.item }).toFigures().toGroup(null, key.accountId, key.label)
    }
