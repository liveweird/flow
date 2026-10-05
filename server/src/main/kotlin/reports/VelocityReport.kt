package ch.nokillswit.reports

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.sumMd
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** The four figures every velocity row carries, live or frozen (`fact_sprint(_snapshot)`/`fact_sprint_scope` alike). */
@Serializable
data class VelocitySnapshot(val initialMd: Double, val initialItems: Int, val finalMd: Double, val finalItems: Int)

/**
 * One sprint's own velocity (Report 1, `.claude/docs/measures.md` "Report 1 — Velocity"): `initial`
 * = committed, `final` = final scope (A17). [snapshot] is the frozen `fact_sprint_snapshot` figures
 * (`null` until the sprint was closed AND team-mapped at some past DERIVE — D13); [drift] compares
 * the two, `false` whenever no snapshot exists yet.
 */
@Serializable
data class VelocitySprint(
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val completedAt: Long?,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
    val snapshot: VelocitySnapshot?,
    val drift: Boolean,
)

/**
 * One org-drill entry: a team's own sums at UNIT level ([teamId]/[label] set, [accountId] null,
 * summed straight off `fact_sprint`), or one user's `assignee_at_commitment` sums at TEAM level
 * ([accountId]/[label] set, summed off `fact_sprint_scope` — the same removed-row rule the team
 * total itself follows, so Σ users == the team total; a `null` [accountId]/[label] is the
 * unassigned-at-commitment bucket, never a stored sentinel — the `credit_team_id` convention).
 * Always empty at USER level, where [VelocityReport.sprints] is already that one user's own split.
 */
@Serializable
data class VelocityGroup(
    val teamId: UInt? = null,
    val accountId: String? = null,
    val label: String? = null,
    val initialMd: Double,
    val initialItems: Int,
    val finalMd: Double,
    val finalItems: Int,
)

@Serializable
data class VelocityReport(val meta: ReportMeta, val sprints: List<VelocitySprint>, val groups: List<VelocityGroup>)

/** One `fact_sprint_scope` row's contribution to the committed/final buckets (the removed-row rule applied by the caller). */
private data class ScopeContribution(
    val connectionId: UInt,
    val sprintId: Long,
    val accountId: String?,
    val committed: Boolean,
    val inScopeAtClose: Boolean,
    val commitMd: Double?,
    val closeMd: Double?,
)

private fun velocityDrift(live: VelocitySnapshot, snapshot: VelocitySnapshot?): Boolean {
    if (snapshot == null) return false
    return kotlin.math.abs(live.initialMd - snapshot.initialMd) > SPRINT_DRIFT_TOLERANCE_MD ||
        kotlin.math.abs(live.finalMd - snapshot.finalMd) > SPRINT_DRIFT_TOLERANCE_MD ||
        live.initialItems != snapshot.initialItems ||
        live.finalItems != snapshot.finalItems
}

/**
 * `GET /api/v1/reports/velocity` (v0.3.0 M4 commit 10b, Report 1). Levels (from the parsed
 * filter): UNIT groups every team's closed sprints in the period by team (Σ final MD/items,
 * straight off `fact_sprint`); TEAM narrows to one team's own sprints, grouped by
 * `fact_sprint_scope.assignee_at_commitment`; USER narrows `sprints` itself to that one account's
 * own contribution (`groups` stays empty). `teamId = 0` (UNASSIGNED) is always empty.
 */
suspend fun ReportService.velocity(filter: ReportFilter): VelocityReport = suspendTransaction(database) {
    // A sprint-anchored report reads no time window, so `nowMs` only feeds the scope's unused `window`.
    val scope = resolveReportScope(filter, nowMillis())
    val sprintRows = scope.sprintRows
    val meta = scope.meta

    if (filter.level == ReportLevel.USER) {
        val accountId = requireNotNull(filter.accountId) { "USER level always carries an accountId (ReportFilter's own invariant)" }
        val contributions = fetchScopeContributions(sprintRows, accountId)
        return@suspendTransaction VelocityReport(meta, buildUserSprints(sprintRows, contributions), emptyList())
    }

    val snapshotByKey = fetchSnapshots(sprintRows).associateBy { it.connectionId to it.sprintId }
    val sprints = sprintRows.map { row ->
        val snapshot = snapshotByKey[row.connectionId to row.sprintId]?.figures
        VelocitySprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            initialMd = row.live.initialMd, initialItems = row.live.initialItems,
            finalMd = row.live.finalMd, finalItems = row.live.finalItems,
            snapshot = snapshot, drift = velocityDrift(row.live, snapshot),
        )
    }
    val groups = when (filter.level) {
        ReportLevel.UNIT -> teamGroups(sprintRows)
        ReportLevel.TEAM -> userGroups(fetchScopeContributions(sprintRows, accountId = null))
        ReportLevel.USER -> emptyList() // handled above
    }
    VelocityReport(meta, sprints, groups)
}

/** [accountId] `null` fetches every user's own contribution (TEAM-level groups); non-null narrows to one (USER level). */
private suspend fun fetchScopeContributions(sprintRows: List<SprintRow>, accountId: String?): List<ScopeContribution> {
    if (sprintRows.isEmpty()) return emptyList()
    val connectionIds = sprintRows.map { it.connectionId }.distinct()
    val sprintIds = sprintRows.map { it.sprintId }.distinct()
    var predicate: Op<Boolean> = (MetricsTables.FactSprintScope.connectionId inList connectionIds) and
        (MetricsTables.FactSprintScope.sprintId inList sprintIds)
    accountId?.let { predicate = predicate and (MetricsTables.FactSprintScope.assigneeAtCommitment eq it) }
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
private suspend fun teamGroups(sprintRows: List<SprintRow>): List<VelocityGroup> =
    orgGroups(ReportLevel.UNIT, sprintRows, { it.teamId }, { null }).map { (key, rows) ->
        VelocityGroup(
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
private suspend fun userGroups(contributions: List<ScopeContribution>): List<VelocityGroup> =
    orgGroups(ReportLevel.TEAM, contributions, { null }, { it.accountId }).map { (key, rows) ->
        VelocityGroup(
            accountId = key.accountId,
            label = key.label,
            initialMd = sumMd(rows.filter { it.committed && it.inScopeAtClose }.map { it.commitMd }),
            initialItems = rows.count { it.committed && it.inScopeAtClose },
            finalMd = sumMd(rows.filter { it.inScopeAtClose }.map { it.closeMd }),
            finalItems = rows.count { it.inScopeAtClose },
        )
    }

/**
 * The USER level's own `sprints` — one row per sprint, narrowed to [contributions]' one account
 * (the same removed-row rule [userGroups] applies, per sprint instead of summed across them).
 * `snapshot`/`drift` are not computed here: a per-user frozen figure would need parsing
 * `fact_sprint_snapshot.scope`'s JSONB (`.claude/docs/metrics.md`'s own documented "per-user
 * velocity from the snapshot needs no child table" — a reader this commit does not add) — always
 * `null`/`false`, a documented scope narrowing rather than a silent guess.
 */
private fun buildUserSprints(sprintRows: List<SprintRow>, contributions: List<ScopeContribution>): List<VelocitySprint> {
    val byKey = contributions.groupBy { it.connectionId to it.sprintId }
    return sprintRows.map { row ->
        val rows = byKey[row.connectionId to row.sprintId].orEmpty()
        VelocitySprint(
            sprintId = row.sprintId, name = row.name, teamId = row.teamId, completedAt = row.completedAt,
            initialMd = sumMd(rows.filter { it.committed && it.inScopeAtClose }.map { it.commitMd }),
            initialItems = rows.count { it.committed && it.inScopeAtClose },
            finalMd = sumMd(rows.filter { it.inScopeAtClose }.map { it.closeMd }),
            finalItems = rows.count { it.inScopeAtClose },
            snapshot = null,
            drift = false,
        )
    }
}
