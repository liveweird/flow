package ch.nokillswit.reports

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.sumMd
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** The four figures every commitments row carries, live or frozen (`fact_sprint(_snapshot)`/`fact_sprint_scope` alike). */
@Serializable
data class CommitmentsSnapshot(val initialMd: Double, val initialItems: Int, val finalMd: Double, val finalItems: Int)

/**
 * One sprint's commitments (Report 1, `.claude/docs/measures.md` "Report 1 — Commitments"): `initial`
 * = committed, `final` = final scope (A17). [snapshot] is the frozen `fact_sprint_snapshot` figures
 * (`null` until the sprint was closed AND team-mapped at some past DERIVE — D13); [drift] compares
 * the two, `false` whenever no snapshot exists yet. At USER level every figure, the snapshot included, is the SUM over the
 * picked accounts (the snapshot: their frozen share of the sprint's stored scope, see [buildUserSprints]) and [byUser] splits it
 * per picked account; [byUser] is empty at UNIT/TEAM level. [capacityMd]/[capacitySource] (`CALENDAR`/`TEAM_DEFAULT`/`DEFAULT`, A3/A32;
 * `CONFIGURED` only on a row not yet re-derived since V24) are the TEAM's sprint capacity — `null` when the sprint has none, and
 * always `null` at USER level (capacity is team-only; picking users hides it). [byEpic] splits the LIVE `initial*`/`final*` figures
 * by the current epic of each item (top 6 + Other + No epic, [commitmentsByEpic]); the segments add up to them exactly, the frozen
 * [snapshot] is not split.
 */
@Serializable
data class CommitmentsSprint(
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val completedAt: Long?,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val snapshot: CommitmentsSnapshot?,
    val drift: Boolean,
    val capacityMd: Double?,
    val capacitySource: String?,
    val byUser: List<CommitmentsUserShare>,
    val byEpic: List<CommitmentsEpicSegment>,
)

/** One picked account's own share of a sprint's commitments at USER level (live figures, `assignee_at_commitment`) — zeros included. */
@Serializable
data class CommitmentsUserShare(
    val accountId: String,
    val label: String,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
)

/**
 * One org-drill entry: a team's own sums at UNIT level ([teamId]/[label] set, [accountId] null,
 * summed straight off `fact_sprint`), or one user's `assignee_at_commitment` sums at TEAM level
 * ([accountId]/[label] set, summed off `fact_sprint_scope` — the same removed-row rule the team
 * total itself follows, so Σ users == the team total; a `null` [accountId]/[label] is the
 * unassigned-at-commitment bucket, never a stored sentinel — the `credit_team_id` convention).
 * At USER level one entry per picked account (its period totals; a picked account with no scope row in the period is a zero
 * entry), same shape as TEAM level.
 */
@Serializable
data class CommitmentsGroup(
    val teamId: UInt? = null,
    val accountId: String? = null,
    val label: String? = null,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
)

@Serializable
data class CommitmentsReport(val meta: ReportMeta, val sprints: List<CommitmentsSprint>, val groups: List<CommitmentsGroup>)

/** The committed(∧inScopeAtClose)/final figures of one account's (or any group's) scope rows — the ONE rule live and frozen share. */
private fun figuresOf(rows: List<ScopeContribution>): CommitmentsSnapshot = CommitmentsSnapshot(
    initialMd = sumMd(rows.filter { it.committed && it.inScopeAtClose }.map { it.commitMd }),
    initialItems = rows.count { it.committed && it.inScopeAtClose },
    finalMd = sumMd(rows.filter { it.inScopeAtClose }.map { it.closeMd }),
    finalItems = rows.count { it.inScopeAtClose },
)

private fun scopeDrift(live: CommitmentsSnapshot, snapshot: CommitmentsSnapshot?): Boolean {
    if (snapshot == null) return false
    return kotlin.math.abs(live.initialMd - snapshot.initialMd) > SPRINT_DRIFT_TOLERANCE_MD ||
        kotlin.math.abs(live.finalMd - snapshot.finalMd) > SPRINT_DRIFT_TOLERANCE_MD ||
        live.initialItems != snapshot.initialItems ||
        live.finalItems != snapshot.finalItems
}

/**
 * `GET /api/v1/reports/commitments` (v0.3.0 M4 commit 10b, Report 1). Levels (from the parsed
 * filter): UNIT groups every team's closed sprints in the period by team (Σ final MD/items,
 * straight off `fact_sprint`); TEAM narrows to one team's own sprints, grouped by
 * `fact_sprint_scope.assignee_at_commitment`; USER narrows `sprints` itself to the 1..8 picked accounts'
 * summed contribution (`groups` lists each account's period totals, every [CommitmentsSprint.byUser] its per-sprint
 * share). `teamId = 0` (UNASSIGNED) is always empty.
 */
suspend fun ReportService.commitments(filter: ReportFilter): CommitmentsReport = reportTransaction {
    // A sprint-anchored report reads no time window, so `nowMs` only feeds the scope's unused `window`.
    val scope = resolveReportScope(filter, nowMillis())
    val sprintRows = scope.sprintRows
    val meta = scope.meta

    if (filter.level == ReportLevel.USER) {
        val accountIds = filter.accountIds
        check(accountIds.isNotEmpty()) { "USER level always carries accountIds (ReportFilter's own invariant)" }
        val contributions = fetchScopeContributions(sprintRows, accountIds)
        val frozen = fetchFrozenScopes(sprintRows, accountIds, ::frozenContributionsOf) { it.accountId }
        val labels = accountDisplayNames(accountIds)
        val byEpic = commitmentsByEpic(sprintRows, accountIds)
        return@reportTransaction CommitmentsReport(
            meta,
            buildUserSprints(sprintRows, accountIds, labels, contributions, frozen, byEpic),
            pickedUserGroups(accountIds, labels, contributions),
        )
    }

    val snapshotByKey = fetchSnapshots(sprintRows).associateBy { it.connectionId to it.sprintId }
    val byEpic = commitmentsByEpic(sprintRows, accountIds = null)
    val sprints = sprintRows.map { row ->
        val snapshot = snapshotByKey[row.connectionId to row.sprintId]?.figures
        CommitmentsSprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            initialMd = row.live.initialMd, initialItems = row.live.initialItems,
            finalMd = row.live.finalMd, finalItems = row.live.finalItems,
            snapshot = snapshot, drift = scopeDrift(row.live, snapshot),
            capacityMd = row.capacityMd, capacitySource = row.capacitySource, byUser = emptyList(),
            byEpic = byEpic[row.connectionId to row.sprintId].orEmpty(),
        )
    }
    val groups = when (filter.level) {
        ReportLevel.UNIT -> teamGroups(sprintRows)
        ReportLevel.TEAM -> userGroups(fetchScopeContributions(sprintRows, accountIds = null))
        ReportLevel.USER -> emptyList() // handled above
    }
    CommitmentsReport(meta, sprints, groups)
}

/** [accountIds] `null` fetches every user's own contribution (TEAM-level groups); non-null narrows to those accounts (USER level). */
private suspend fun fetchScopeContributions(sprintRows: List<SprintRow>, accountIds: Collection<String>?): List<ScopeContribution> {
    if (sprintRows.isEmpty()) return emptyList()
    val connectionIds = sprintRows.map { it.connectionId }.distinct()
    val sprintIds = sprintRows.map { it.sprintId }.distinct()
    var predicate: Op<Boolean> = (MetricsTables.FactSprintScope.connectionId inList connectionIds) and
        (MetricsTables.FactSprintScope.sprintId inList sprintIds)
    accountIds?.let { predicate = predicate and (MetricsTables.FactSprintScope.assigneeAtCommitment inList it) }
    // connection IN (...) AND sprint IN (...) is a cross product: keep exactly the (connection, sprint) pairs
    // in scope, since two connections to one Jira site share sprint ids.
    val inScope = sprintRows.map { it.connectionId to it.sprintId }.toSet()
    return MetricsTables.FactSprintScope.selectAll().where { predicate }.toList().filter {
        (it[MetricsTables.FactSprintScope.connectionId].value to it[MetricsTables.FactSprintScope.sprintId]) in inScope
    }.map {
        ScopeContribution(
            connectionId = it[MetricsTables.FactSprintScope.connectionId].value,
            sprintId = it[MetricsTables.FactSprintScope.sprintId],
            accountId = it[MetricsTables.FactSprintScope.assigneeAtCommitment],
            committed = it[MetricsTables.FactSprintScope.committed],
            inScopeAtClose = it[MetricsTables.FactSprintScope.inScopeAtClose],
            commitMd = it[MetricsTables.FactSprintScope.estimateAtCommitmentMd]?.toDouble(),
            closeMd = it[MetricsTables.FactSprintScope.estimateAtCloseMd]?.toDouble(),
        )
    }
}

/** Σ final MD/items per team, straight off `fact_sprint` (UNIT-level groups). */
private suspend fun teamGroups(sprintRows: List<SprintRow>): List<CommitmentsGroup> =
    orgGroups(ReportLevel.UNIT, sprintRows, { it.teamId }, { null }).map { (key, rows) ->
        CommitmentsGroup(
            teamId = key.teamId,
            label = key.label,
            initialMd = sumMd(rows.map { it.live.initialMd }),
            initialItems = rows.sumOf { it.live.initialItems },
            finalMd = sumMd(rows.map { it.live.finalMd }),
            finalItems = rows.sumOf { it.live.finalItems },
        )
    }

/**
 * Σ committed(∧inScopeAtClose)/final MD per `assignee_at_commitment` — the removed-row rule, so Σ
 * users == the team total (TEAM-level groups); the unassigned (null) group last, as sprint consistency.
 */
private suspend fun userGroups(contributions: List<ScopeContribution>): List<CommitmentsGroup> =
    orgGroups(ReportLevel.TEAM, contributions, { null }, { it.accountId }).map { (key, rows) ->
        val figures = figuresOf(rows)
        CommitmentsGroup(
            accountId = key.accountId,
            label = key.label,
            initialMd = figures.initialMd,
            initialItems = figures.initialItems,
            finalMd = figures.finalMd,
            finalItems = figures.finalItems,
        )
    }

/**
 * The USER level's own `sprints` — one row per sprint, narrowed to [contributions]' picked [accountIds] and SUMMED over them (the
 * same removed-row rule [userGroups] applies, per sprint instead of summed across them). `snapshot` is those accounts' FROZEN figures,
 * computed by the same [figuresOf] rule from the sprint's `fact_sprint_snapshot.scope` ([frozen], keyed like [contributions];
 * per-user commitments come from the snapshot, no child table); `null` for a sprint with no snapshot
 * (D13), and [drift] is the team level's own [scopeDrift] over the two. [byUser] is each picked account's live share in request
 * order, zeros included; [byEpic] the picked accounts' summed live scope split by epic; capacity is team-only (A3), so it is `null` here.
 */
private fun buildUserSprints(
    sprintRows: List<SprintRow>,
    accountIds: List<String>,
    labels: Map<String, String>,
    contributions: List<ScopeContribution>,
    frozen: Map<Pair<UInt, Long>, List<ScopeContribution>>,
    byEpic: Map<Pair<UInt, Long>, List<CommitmentsEpicSegment>>,
): List<CommitmentsSprint> {
    val byKey = contributions.groupBy { it.connectionId to it.sprintId }
    return sprintRows.map { row ->
        val rows = byKey[row.connectionId to row.sprintId].orEmpty()
        val live = figuresOf(rows)
        val snapshot = frozen[row.connectionId to row.sprintId]?.let(::figuresOf)
        val byAccount = rows.groupBy { it.accountId }
        CommitmentsSprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            initialMd = live.initialMd, initialItems = live.initialItems,
            finalMd = live.finalMd, finalItems = live.finalItems,
            snapshot = snapshot, drift = scopeDrift(live, snapshot),
            capacityMd = null, capacitySource = null,
            byUser = accountIds.map { userShare(it, labels, figuresOf(byAccount[it].orEmpty())) },
            byEpic = byEpic[row.connectionId to row.sprintId].orEmpty(),
        )
    }
}

private fun userShare(accountId: String, labels: Map<String, String>, figures: CommitmentsSnapshot) = CommitmentsUserShare(
    accountId = accountId, label = labels[accountId] ?: accountId,
    initialMd = figures.initialMd, initialItems = figures.initialItems, finalMd = figures.finalMd, finalItems = figures.finalItems,
)

/**
 * USER-level `groups`: each picked account's period totals by the [userGroups] rule (Σ committed(∧inScopeAtClose)/final over its
 * scope rows), ordered like it (label, then account id) — but an explicit pick is always listed, a zero entry when the account has no
 * scope row in the period.
 */
private fun pickedUserGroups(
    accountIds: List<String>,
    labels: Map<String, String>,
    contributions: List<ScopeContribution>,
): List<CommitmentsGroup> {
    val byAccount = contributions.groupBy { it.accountId }
    return accountIds.map { id ->
        val share = userShare(id, labels, figuresOf(byAccount[id].orEmpty()))
        CommitmentsGroup(
            accountId = id, label = share.label,
            initialMd = share.initialMd, initialItems = share.initialItems, finalMd = share.finalMd, finalItems = share.finalItems,
        )
    }.sortedWith(byLabelThenId({ it.label }, { null }, { it.accountId }))
}
