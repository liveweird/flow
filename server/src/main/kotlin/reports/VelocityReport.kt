package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * `teamId=0` — the UNASSIGNED sentinel (`.claude/docs/reports.md`'s shared filter). A sprint
 * always carries a real team or is excluded, so this level is always empty.
 */
private const val UNASSIGNED_TEAM_ID: UInt = 0u

/** The tolerance a live `fact_sprint` figure may drift from its frozen `fact_sprint_snapshot` before it is flagged (plan §7, D13). */
private const val VELOCITY_DRIFT_TOLERANCE_MD = 0.005

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
    val completedAt: Long,
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

/** One `fact_sprint` row (joined with `dim_sprint` for its name), scoped to a real team. */
private data class SprintRow(
    val connectionId: UInt,
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val completedAt: Long,
    val live: VelocitySnapshot,
)

private data class SnapshotRow(val connectionId: UInt, val sprintId: Long, val figures: VelocitySnapshot)

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
    return kotlin.math.abs(live.initialMd - snapshot.initialMd) > VELOCITY_DRIFT_TOLERANCE_MD ||
        kotlin.math.abs(live.finalMd - snapshot.finalMd) > VELOCITY_DRIFT_TOLERANCE_MD ||
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
    val settings = metricsConfig.read()
    val connectionIds = resolveConnectionScope(filter.connectionId)
    val derivedAt = latestDerivedAt(connectionIds)

    if (filter.teamId == UNASSIGNED_TEAM_ID) {
        return@suspendTransaction VelocityReport(
            filter.toMeta(derivedAt, settings.configRevision, settings.minSampleSize),
            emptyList(),
            emptyList(),
        )
    }
    filter.teamId?.let { requireActiveTeam(it) }
    val narrowTeamId = filter.teamId.takeIf { filter.level != ReportLevel.UNIT }

    val sprintRows = resolveSprintRows(filter.period, connectionIds, narrowTeamId)
    val resolvedSprints = when (filter.period) {
        is ReportPeriod.DateRange -> emptyList()
        else -> sprintRows.groupBy { it.teamId }.map { (teamId, rows) -> ResolvedSprintGroup(teamId, rows.map { it.sprintId }) }
    }
    val meta = filter.toMeta(derivedAt, settings.configRevision, settings.minSampleSize, resolvedSprints)

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

private suspend fun ReportService.resolveConnectionScope(connectionId: UInt?): List<UInt> {
    if (connectionId == null) {
        return DataSourceService.Connections.select(DataSourceService.Connections.id)
            // Every non-deleted connection, like /reports/filters: a disabled one only has syncing paused.
            .where { DataSourceService.Connections.active() }
            .toList().map { it[DataSourceService.Connections.id].value }
    }
    val exists = DataSourceService.Connections.select(DataSourceService.Connections.id)
        .where { (DataSourceService.Connections.id eq connectionId) and DataSourceService.Connections.active() }
        .toList().isNotEmpty()
    if (!exists) throw BadRequestException("Unknown or inactive connectionId: $connectionId")
    return listOf(connectionId)
}

private suspend fun requireActiveTeam(teamId: UInt) {
    val exists = TeamService.Teams.select(TeamService.Teams.id)
        .where { (TeamService.Teams.id eq teamId) and TeamService.Teams.active() }
        .toList().isNotEmpty()
    if (!exists) throw BadRequestException("Unknown or inactive teamId: $teamId")
}

private suspend fun latestDerivedAt(connectionIds: List<UInt>): Long? {
    if (connectionIds.isEmpty()) return null
    return MetricsStore.DeriveRuns.select(MetricsStore.DeriveRuns.finishedAt)
        .where {
            (MetricsStore.DeriveRuns.status eq DERIVE_RUN_SUCCEEDED) and
                (MetricsStore.DeriveRuns.connectionId inList connectionIds.map { it.toInt() })
        }
        .toList().mapNotNull { it[MetricsStore.DeriveRuns.finishedAt] }.maxOrNull()
}

private fun sprintJoinQuery() = MetricsStore.FactSprint.join(
    MetricsStore.DimSprint,
    JoinType.INNER,
    onColumn = MetricsStore.FactSprint.sprintId,
    otherColumn = MetricsStore.DimSprint.sprintId,
    additionalConstraint = { MetricsStore.FactSprint.connectionId eq MetricsStore.DimSprint.connectionId },
).select(
    MetricsStore.FactSprint.connectionId, MetricsStore.FactSprint.sprintId, MetricsStore.DimSprint.name,
    MetricsStore.FactSprint.teamId, MetricsStore.FactSprint.completeAt,
    MetricsStore.FactSprint.committedMd, MetricsStore.FactSprint.committedItems,
    MetricsStore.FactSprint.finalMd, MetricsStore.FactSprint.finalItems,
)

private fun ResultRow.toSprintRow() = SprintRow(
    connectionId = this[MetricsStore.FactSprint.connectionId].value,
    sprintId = this[MetricsStore.FactSprint.sprintId],
    name = this[MetricsStore.DimSprint.name],
    teamId = this[MetricsStore.FactSprint.teamId]!!.value,
    completedAt = this[MetricsStore.FactSprint.completeAt]!!,
    live = VelocitySnapshot(
        initialMd = this[MetricsStore.FactSprint.committedMd].toDouble(),
        initialItems = this[MetricsStore.FactSprint.committedItems],
        finalMd = this[MetricsStore.FactSprint.finalMd].toDouble(),
        finalItems = this[MetricsStore.FactSprint.finalItems],
    ),
)

private suspend fun resolveSprintRows(period: ReportPeriod, connectionIds: List<UInt>, narrowTeamId: UInt?): List<SprintRow> {
    if (connectionIds.isEmpty()) return emptyList()
    return when (period) {
        is ReportPeriod.DateRange -> sprintRowsInRange(connectionIds, narrowTeamId, period.fromMs, period.toMs)
        is ReportPeriod.LastSprints -> sprintRowsLastN(connectionIds, narrowTeamId, period.count)
        is ReportPeriod.BySprintId -> sprintRowsForId(connectionIds, narrowTeamId, period.sprintId)
    }
}

private suspend fun sprintRowsInRange(connectionIds: List<UInt>, narrowTeamId: UInt?, fromMs: Long, toMs: Long): List<SprintRow> {
    var predicate: Op<Boolean> = (MetricsStore.FactSprint.connectionId inList connectionIds) and
        MetricsStore.FactSprint.teamId.isNotNull() and
        (MetricsStore.FactSprint.completeAt greaterEq fromMs) and
        (MetricsStore.FactSprint.completeAt less toMs) // toMs is exclusive: the day after `to` starts
    narrowTeamId?.let { predicate = predicate and (MetricsStore.FactSprint.teamId eq it) }
    return sprintJoinQuery().where { predicate }.toList().map { it.toSprintRow() }
}

private suspend fun sprintRowsLastN(connectionIds: List<UInt>, narrowTeamId: UInt?, count: Int): List<SprintRow> {
    var predicate: Op<Boolean> = (MetricsStore.FactSprint.connectionId inList connectionIds) and
        MetricsStore.FactSprint.teamId.isNotNull() and MetricsStore.FactSprint.completeAt.isNotNull()
    narrowTeamId?.let { predicate = predicate and (MetricsStore.FactSprint.teamId eq it) }
    val rows = sprintJoinQuery().where { predicate }
        .orderBy(MetricsStore.FactSprint.teamId to SortOrder.ASC, MetricsStore.FactSprint.completeAt to SortOrder.DESC)
        .toList().map { it.toSprintRow() }
    return rows.groupBy { it.teamId }.values.flatMap { it.take(count) }
}

private suspend fun sprintRowsForId(connectionIds: List<UInt>, narrowTeamId: UInt?, sprintId: Long): List<SprintRow> {
    val exists = MetricsStore.DimSprint.select(MetricsStore.DimSprint.sprintId)
        .where { (MetricsStore.DimSprint.connectionId inList connectionIds) and (MetricsStore.DimSprint.sprintId eq sprintId) }
        .toList().isNotEmpty()
    if (!exists) throw BadRequestException("Unknown sprintId: $sprintId")
    var predicate: Op<Boolean> = (MetricsStore.FactSprint.connectionId inList connectionIds) and
        MetricsStore.FactSprint.teamId.isNotNull() and (MetricsStore.FactSprint.sprintId eq sprintId)
    narrowTeamId?.let { predicate = predicate and (MetricsStore.FactSprint.teamId eq it) }
    return sprintJoinQuery().where { predicate }.toList().map { it.toSprintRow() }
}

private suspend fun fetchSnapshots(sprintRows: List<SprintRow>): List<SnapshotRow> {
    if (sprintRows.isEmpty()) return emptyList()
    val connectionIds = sprintRows.map { it.connectionId }.distinct()
    val sprintIds = sprintRows.map { it.sprintId }.distinct()
    return MetricsStore.FactSprintSnapshot.selectAll()
        .where {
            (MetricsStore.FactSprintSnapshot.connectionId inList connectionIds) and
                (MetricsStore.FactSprintSnapshot.sprintId inList sprintIds)
        }
        .toList()
        .map {
            SnapshotRow(
                connectionId = it[MetricsStore.FactSprintSnapshot.connectionId].value,
                sprintId = it[MetricsStore.FactSprintSnapshot.sprintId],
                figures = VelocitySnapshot(
                    initialMd = it[MetricsStore.FactSprintSnapshot.committedMd].toDouble(),
                    initialItems = it[MetricsStore.FactSprintSnapshot.committedItems],
                    finalMd = it[MetricsStore.FactSprintSnapshot.finalMd].toDouble(),
                    finalItems = it[MetricsStore.FactSprintSnapshot.finalItems],
                ),
            )
        }
}

/** [accountId] `null` fetches every user's own contribution (TEAM-level groups); non-null narrows to one (USER level). */
private suspend fun fetchScopeContributions(sprintRows: List<SprintRow>, accountId: String?): List<ScopeContribution> {
    if (sprintRows.isEmpty()) return emptyList()
    val connectionIds = sprintRows.map { it.connectionId }.distinct()
    val sprintIds = sprintRows.map { it.sprintId }.distinct()
    var predicate: Op<Boolean> = (MetricsStore.FactSprintScope.connectionId inList connectionIds) and
        (MetricsStore.FactSprintScope.sprintId inList sprintIds)
    accountId?.let { predicate = predicate and (MetricsStore.FactSprintScope.assigneeAtCommitment eq it) }
    return MetricsStore.FactSprintScope.selectAll().where { predicate }.toList().map {
        ScopeContribution(
            connectionId = it[MetricsStore.FactSprintScope.connectionId].value,
            sprintId = it[MetricsStore.FactSprintScope.sprintId],
            accountId = it[MetricsStore.FactSprintScope.assigneeAtCommitment],
            committed = it[MetricsStore.FactSprintScope.committed],
            inScopeAtClose = it[MetricsStore.FactSprintScope.inScopeAtClose],
            commitMd = it[MetricsStore.FactSprintScope.estimateAtCommitmentMd]?.toDouble(),
            closeMd = it[MetricsStore.FactSprintScope.estimateAtCloseMd]?.toDouble(),
        )
    }
}

/** Σ final MD/items per team, straight off `fact_sprint` (UNIT-level groups). */
private suspend fun teamGroups(sprintRows: List<SprintRow>): List<VelocityGroup> {
    val byTeam = sprintRows.groupBy { it.teamId }
    if (byTeam.isEmpty()) return emptyList()
    val names = TeamService.Teams.select(TeamService.Teams.id, TeamService.Teams.name)
        .where { TeamService.Teams.id inList byTeam.keys }
        .toList().associate { it[TeamService.Teams.id].value to it[TeamService.Teams.name] }
    return byTeam.map { (teamId, rows) ->
        VelocityGroup(
            teamId = teamId,
            label = names[teamId] ?: teamId.toString(),
            initialMd = rows.sumOf { it.live.initialMd },
            initialItems = rows.sumOf { it.live.initialItems },
            finalMd = rows.sumOf { it.live.finalMd },
            finalItems = rows.sumOf { it.live.finalItems },
        )
    }.sortedBy { it.label }
}

/**
 * Σ committed(∧inScopeAtClose)/final MD per `assignee_at_commitment` — the removed-row rule, so Σ
 * users == the team total (TEAM-level groups).
 */
private suspend fun userGroups(contributions: List<ScopeContribution>): List<VelocityGroup> {
    if (contributions.isEmpty()) return emptyList()
    val byAccount = contributions.groupBy { it.accountId }
    val accountIds = byAccount.keys.filterNotNull()
    val displayNames = if (accountIds.isEmpty()) {
        emptyMap()
    } else {
        WorkItemStore.People.select(WorkItemStore.People.accountId, WorkItemStore.People.displayName)
            .where { WorkItemStore.People.accountId inList accountIds }
            .orderBy(WorkItemStore.People.connectionId to SortOrder.DESC)
            .toList().associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
    }
    return byAccount.map { (accountId, rows) ->
        VelocityGroup(
            accountId = accountId,
            label = accountId?.let { displayNames[it] ?: it },
            initialMd = rows.filter { it.committed && it.inScopeAtClose }.sumOf { it.commitMd ?: 0.0 },
            initialItems = rows.count { it.committed && it.inScopeAtClose },
            finalMd = rows.filter { it.inScopeAtClose }.sumOf { it.closeMd ?: 0.0 },
            finalItems = rows.count { it.inScopeAtClose },
        )
    }.sortedBy { it.label ?: "" }
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
            initialMd = rows.filter { it.committed && it.inScopeAtClose }.sumOf { it.commitMd ?: 0.0 },
            initialItems = rows.count { it.committed && it.inScopeAtClose },
            finalMd = rows.filter { it.inScopeAtClose }.sumOf { it.closeMd ?: 0.0 },
            finalItems = rows.count { it.inScopeAtClose },
            snapshot = null,
            drift = false,
        )
    }
}
