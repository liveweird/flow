package ch.nokillswit.reports

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.metrics.MD_SCALE
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import io.ktor.server.plugins.BadRequestException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/*
 * The plumbing the reports share (velocity — commit 10b, throughput — commit 10c, the estimation
 * batch — commit 12): connection scope + existence checks, the `fact_sprint` ⨝ `dim_sprint` reader,
 * the `lastSprints`/`sprintId`/`from`-`to` period resolution, the frozen-snapshot reader, and — for
 * the ITEM-based reports (throughput's period view, estimation accuracy/adjustments) — the period
 * window and the `fact_task_delivery`/`fact_epic_delivery` slice predicates. Every function is
 * `internal` and runs inside the CALLER's `suspendTransaction`.
 */

/**
 * A man-day figure rounded half-up for the wire. A total is ALWAYS the rounded EXACT sum, never the sum of rounded
 * cells (`.claude/docs/reports.md` "Report 16").
 */
internal fun BigDecimal.md(): Double = setScale(MD_SCALE, RoundingMode.HALF_UP).toDouble()

/**
 * `teamId=0` — the UNASSIGNED sentinel (`.claude/docs/reports.md`'s shared filter). A sprint
 * always carries a real team or is excluded, so this level is always empty for sprint figures.
 */
internal const val UNASSIGNED_TEAM_ID: UInt = 0u

/** The tolerance a live `fact_sprint` figure may drift from its frozen `fact_sprint_snapshot` before it is flagged (plan §7, D13). */
internal const val SPRINT_DRIFT_TOLERANCE_MD = 0.005

/** One `fact_sprint` row (joined with `dim_sprint` for its name/start), scoped to a real team; every report's figures ride along. */
internal data class SprintRow(
    val connectionId: UInt,
    val sprintId: Long,
    val name: String,
    val teamId: UInt,
    val startAt: Long?,
    /** `null` only for an ACTIVE/future sprint, reachable through an explicit `sprintId` (measures.md conventions). */
    val completedAt: Long?,
    val live: VelocitySnapshot,
    val delivered: ThroughputSnapshot,
    /** Every one of the fourteen `fact_sprint` figures (sprint consistency, report 6). */
    val full: SprintFigures,
)

internal data class SnapshotRow(
    val connectionId: UInt,
    val sprintId: Long,
    val figures: VelocitySnapshot,
    val delivered: ThroughputSnapshot,
    val full: SprintFigures,
)

/** `meta.resolvedSprints`: empty for a `from`/`to` period (it names no sprints), else each team's own resolved set. */
internal fun resolvedSprintGroups(period: ReportPeriod, sprintRows: List<SprintRow>): List<ResolvedSprintGroup> = when (period) {
    is ReportPeriod.DateRange -> emptyList()
    else -> sprintRows.groupBy { it.teamId }.map { (teamId, rows) -> ResolvedSprintGroup(teamId, rows.map { it.sprintId }) }
}

internal suspend fun resolveConnectionScope(connectionId: UInt?): List<UInt> {
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

internal suspend fun requireActiveTeam(teamId: UInt) {
    val exists = TeamService.Teams.select(TeamService.Teams.id)
        .where { (TeamService.Teams.id eq teamId) and TeamService.Teams.active() }
        .toList().isNotEmpty()
    if (!exists) throw BadRequestException("Unknown or inactive teamId: $teamId")
}

internal suspend fun latestDerivedAt(connectionIds: List<UInt>): Long? {
    if (connectionIds.isEmpty()) return null
    return MetricsTables.DeriveRuns.select(MetricsTables.DeriveRuns.finishedAt)
        .where {
            (MetricsTables.DeriveRuns.status eq DERIVE_RUN_SUCCEEDED) and
                (MetricsTables.DeriveRuns.connectionId inList connectionIds.map { it.toInt() })
        }
        .toList().mapNotNull { it[MetricsTables.DeriveRuns.finishedAt] }.maxOrNull()
}

/** Team names by id — an empty [ids] costs no query. */
internal suspend fun teamNames(ids: Collection<UInt>): Map<UInt, String> {
    if (ids.isEmpty()) return emptyMap()
    return TeamService.Teams.select(TeamService.Teams.id, TeamService.Teams.name)
        .where { TeamService.Teams.id inList ids }
        .toList().associate { it[TeamService.Teams.id].value to it[TeamService.Teams.name] }
}

/** Jira display names by account id (the lowest connection id's name wins for an account seen on several). */
internal suspend fun accountDisplayNames(accountIds: Collection<String>): Map<String, String> {
    if (accountIds.isEmpty()) return emptyMap()
    return WorkItemStore.People.select(WorkItemStore.People.accountId, WorkItemStore.People.displayName)
        .where { WorkItemStore.People.accountId inList accountIds }
        .orderBy(WorkItemStore.People.connectionId to SortOrder.DESC)
        .toList().associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
}

private fun sprintJoinQuery() = MetricsTables.FactSprint.join(
    MetricsTables.DimSprint,
    JoinType.INNER,
    onColumn = MetricsTables.FactSprint.sprintId,
    otherColumn = MetricsTables.DimSprint.sprintId,
    additionalConstraint = { MetricsTables.FactSprint.connectionId eq MetricsTables.DimSprint.connectionId },
).select(
    MetricsTables.FactSprint.connectionId, MetricsTables.FactSprint.sprintId, MetricsTables.DimSprint.name,
    MetricsTables.DimSprint.startAt,
    MetricsTables.FactSprint.teamId, MetricsTables.FactSprint.completeAt,
    MetricsTables.FactSprint.committedMd, MetricsTables.FactSprint.committedItems,
    MetricsTables.FactSprint.finalMd, MetricsTables.FactSprint.finalItems,
    MetricsTables.FactSprint.deliveredMd, MetricsTables.FactSprint.deliveredItems,
    MetricsTables.FactSprint.addedMd, MetricsTables.FactSprint.addedItems,
    MetricsTables.FactSprint.removedMd, MetricsTables.FactSprint.removedItems,
    MetricsTables.FactSprint.carriedOverMd, MetricsTables.FactSprint.carriedOverItems,
    MetricsTables.FactSprint.droppedMd, MetricsTables.FactSprint.droppedItems,
)

private fun ResultRow.toSprintRow() = SprintRow(
    connectionId = this[MetricsTables.FactSprint.connectionId].value,
    sprintId = this[MetricsTables.FactSprint.sprintId],
    name = this[MetricsTables.DimSprint.name],
    teamId = this[MetricsTables.FactSprint.teamId]!!.value,
    startAt = this[MetricsTables.DimSprint.startAt],
    completedAt = this[MetricsTables.FactSprint.completeAt],
    live = VelocitySnapshot(
        initialMd = this[MetricsTables.FactSprint.committedMd].toDouble(),
        initialItems = this[MetricsTables.FactSprint.committedItems],
        finalMd = this[MetricsTables.FactSprint.finalMd].toDouble(),
        finalItems = this[MetricsTables.FactSprint.finalItems],
    ),
    delivered = ThroughputSnapshot(
        deliveredMd = this[MetricsTables.FactSprint.deliveredMd].toDouble(),
        deliveredItems = this[MetricsTables.FactSprint.deliveredItems],
    ),
    full = SprintFigures(
        committedMd = this[MetricsTables.FactSprint.committedMd].toDouble(),
        committedItems = this[MetricsTables.FactSprint.committedItems],
        addedMd = this[MetricsTables.FactSprint.addedMd].toDouble(),
        addedItems = this[MetricsTables.FactSprint.addedItems],
        removedMd = this[MetricsTables.FactSprint.removedMd].toDouble(),
        removedItems = this[MetricsTables.FactSprint.removedItems],
        finalMd = this[MetricsTables.FactSprint.finalMd].toDouble(),
        finalItems = this[MetricsTables.FactSprint.finalItems],
        deliveredMd = this[MetricsTables.FactSprint.deliveredMd].toDouble(),
        deliveredItems = this[MetricsTables.FactSprint.deliveredItems],
        carriedOverMd = this[MetricsTables.FactSprint.carriedOverMd].toDouble(),
        carriedOverItems = this[MetricsTables.FactSprint.carriedOverItems],
        droppedMd = this[MetricsTables.FactSprint.droppedMd].toDouble(),
        droppedItems = this[MetricsTables.FactSprint.droppedItems],
    ),
)

internal suspend fun resolveSprintRows(period: ReportPeriod, connectionIds: List<UInt>, narrowTeamId: UInt?): List<SprintRow> {
    if (connectionIds.isEmpty()) return emptyList()
    return when (period) {
        is ReportPeriod.DateRange -> sprintRowsInRange(connectionIds, narrowTeamId, period.fromMs, period.toMs)
        is ReportPeriod.LastSprints -> sprintRowsLastN(connectionIds, narrowTeamId, period.count)
        is ReportPeriod.BySprintId -> sprintRowsForId(connectionIds, narrowTeamId, period.sprintId)
    }
}

private suspend fun sprintRowsInRange(connectionIds: List<UInt>, narrowTeamId: UInt?, fromMs: Long, toMs: Long): List<SprintRow> {
    var predicate: Op<Boolean> = (MetricsTables.FactSprint.connectionId inList connectionIds) and
        MetricsTables.FactSprint.teamId.isNotNull() and
        (MetricsTables.FactSprint.completeAt greaterEq fromMs) and
        (MetricsTables.FactSprint.completeAt less toMs) // toMs is exclusive: the day after `to` starts
    narrowTeamId?.let { predicate = predicate and (MetricsTables.FactSprint.teamId eq it) }
    return sprintJoinQuery().where { predicate }.toList().map { it.toSprintRow() }
}

private suspend fun sprintRowsLastN(connectionIds: List<UInt>, narrowTeamId: UInt?, count: Int): List<SprintRow> {
    var predicate: Op<Boolean> = (MetricsTables.FactSprint.connectionId inList connectionIds) and
        MetricsTables.FactSprint.teamId.isNotNull() and MetricsTables.FactSprint.completeAt.isNotNull()
    narrowTeamId?.let { predicate = predicate and (MetricsTables.FactSprint.teamId eq it) }
    val rows = sprintJoinQuery().where { predicate }
        .orderBy(MetricsTables.FactSprint.teamId to SortOrder.ASC, MetricsTables.FactSprint.completeAt to SortOrder.DESC)
        .toList().map { it.toSprintRow() }
    return rows.groupBy { it.teamId }.values.flatMap { it.take(count) }
}

private suspend fun sprintRowsForId(connectionIds: List<UInt>, narrowTeamId: UInt?, sprintId: Long): List<SprintRow> {
    val exists = MetricsTables.DimSprint.select(MetricsTables.DimSprint.sprintId)
        .where { (MetricsTables.DimSprint.connectionId inList connectionIds) and (MetricsTables.DimSprint.sprintId eq sprintId) }
        .toList().isNotEmpty()
    if (!exists) throw BadRequestException("Unknown sprintId: $sprintId")
    var predicate: Op<Boolean> = (MetricsTables.FactSprint.connectionId inList connectionIds) and
        MetricsTables.FactSprint.teamId.isNotNull() and (MetricsTables.FactSprint.sprintId eq sprintId)
    narrowTeamId?.let { predicate = predicate and (MetricsTables.FactSprint.teamId eq it) }
    return sprintJoinQuery().where { predicate }.toList().map { it.toSprintRow() }
}

internal suspend fun fetchSnapshots(sprintRows: List<SprintRow>): List<SnapshotRow> {
    if (sprintRows.isEmpty()) return emptyList()
    val connectionIds = sprintRows.map { it.connectionId }.distinct()
    val sprintIds = sprintRows.map { it.sprintId }.distinct()
    return MetricsTables.FactSprintSnapshot.selectAll()
        .where {
            (MetricsTables.FactSprintSnapshot.connectionId inList connectionIds) and
                (MetricsTables.FactSprintSnapshot.sprintId inList sprintIds)
        }
        .toList()
        .map {
            SnapshotRow(
                connectionId = it[MetricsTables.FactSprintSnapshot.connectionId].value,
                sprintId = it[MetricsTables.FactSprintSnapshot.sprintId],
                figures = VelocitySnapshot(
                    initialMd = it[MetricsTables.FactSprintSnapshot.committedMd].toDouble(),
                    initialItems = it[MetricsTables.FactSprintSnapshot.committedItems],
                    finalMd = it[MetricsTables.FactSprintSnapshot.finalMd].toDouble(),
                    finalItems = it[MetricsTables.FactSprintSnapshot.finalItems],
                ),
                delivered = ThroughputSnapshot(
                    deliveredMd = it[MetricsTables.FactSprintSnapshot.deliveredMd].toDouble(),
                    deliveredItems = it[MetricsTables.FactSprintSnapshot.deliveredItems],
                ),
                full = SprintFigures(
                    committedMd = it[MetricsTables.FactSprintSnapshot.committedMd].toDouble(),
                    committedItems = it[MetricsTables.FactSprintSnapshot.committedItems],
                    addedMd = it[MetricsTables.FactSprintSnapshot.addedMd].toDouble(),
                    addedItems = it[MetricsTables.FactSprintSnapshot.addedItems],
                    removedMd = it[MetricsTables.FactSprintSnapshot.removedMd].toDouble(),
                    removedItems = it[MetricsTables.FactSprintSnapshot.removedItems],
                    finalMd = it[MetricsTables.FactSprintSnapshot.finalMd].toDouble(),
                    finalItems = it[MetricsTables.FactSprintSnapshot.finalItems],
                    deliveredMd = it[MetricsTables.FactSprintSnapshot.deliveredMd].toDouble(),
                    deliveredItems = it[MetricsTables.FactSprintSnapshot.deliveredItems],
                    carriedOverMd = it[MetricsTables.FactSprintSnapshot.carriedOverMd].toDouble(),
                    carriedOverItems = it[MetricsTables.FactSprintSnapshot.carriedOverItems],
                    droppedMd = it[MetricsTables.FactSprintSnapshot.droppedMd].toDouble(),
                    droppedItems = it[MetricsTables.FactSprintSnapshot.droppedItems],
                ),
            )
        }
}

/** The literal `workCategory` value that selects items with no category (`.claude/docs/reports.md`). */
internal const val UNCATEGORIZED = "UNCATEGORIZED"

/**
 * The item-anchored period's `[fromMs, toMsExclusive)` window: a `from`/`to` period as parsed; a
 * `lastSprints`/`sprintId` period the resolved sprints' overall envelope — `[min(start_at, else
 * complete_at, else now), max(complete_at, else now)]` (inclusive end). An OPEN sprint (no
 * `complete_at`, reachable only by an explicit `sprintId`) therefore ends at [nowMs]; a sprint with no
 * `start_at` starts where it ends, and a not-yet-started future sprint starts after `now`, giving an
 * empty window and so an empty read. `null` when no sprint resolved (nothing to read).
 */
internal fun periodWindow(period: ReportPeriod, sprintRows: List<SprintRow>, nowMs: Long): Pair<Long, Long>? = when (period) {
    is ReportPeriod.DateRange -> period.fromMs to period.toMs
    else -> if (sprintRows.isEmpty()) {
        null
    } else {
        sprintRows.minOf { it.startAt ?: it.completedAt ?: nowMs } to sprintRows.maxOf { it.completedAt ?: nowMs } + 1
    }
}

/** What an ITEM-anchored report (one that reads task/epic facts by a timestamp) needs before its own query. */
internal data class ReportScope(
    val settings: MetricsSettingsResponse,
    val connectionIds: List<UInt>,
    val meta: ReportMeta,
    /** The resolved sprint rows (empty for a `from`/`to` period narrower than sprints, and for `teamId=0`). */
    val sprintRows: List<SprintRow>,
    /** [periodWindow]; `null` = nothing to read. */
    val window: Pair<Long, Long>?,
)

/**
 * The shared preamble of the item-anchored reports: settings, connection scope, the `400` existence
 * checks, sprint resolution (only to describe/resolve a sprint-relative period; `teamId=0` resolves no
 * sprint at all), `meta` and the window. Runs inside the caller's transaction.
 */
internal suspend fun ReportService.resolveReportScope(filter: ReportFilter, nowMs: Long): ReportScope {
    val settings = metricsSettings.read()
    val connectionIds = resolveConnectionScope(filter.connectionId)
    val derivedAt = latestDerivedAt(connectionIds)
    val unassigned = filter.teamId == UNASSIGNED_TEAM_ID
    if (!unassigned) filter.teamId?.let { requireActiveTeam(it) }
    val narrowTeamId = filter.teamId.takeIf { filter.level != ReportLevel.UNIT && !unassigned }
    val sprintRows = if (unassigned) emptyList() else resolveSprintRows(filter.period, connectionIds, narrowTeamId)
    val meta = filter.toMeta(derivedAt, settings.configRevision, settings.minSampleSize, resolvedSprintGroups(filter.period, sprintRows))
    return ReportScope(settings, connectionIds, meta, sprintRows, periodWindow(filter.period, sprintRows, nowMs))
}

/**
 * The `fact_task_delivery` rows a task report reads, before any time anchor: the scoped connections,
 * level-0 only (`is_subtask = false`, D2), the org filter (team = the D5 credit team, `teamId=0` =
 * no credit team; user = assignee at done — and, with [openAttribution] (A25), an OPEN task is
 * attributed to `current_team_id`/`current_assignee_account_id` instead, branching on `done_at`, never
 * `COALESCE`, since a DONE task with no credit team is legitimately UNASSIGNED) and the `domain` (TASK view: the task's own domain; EPIC
 * view: the epic's domain, an epic-less task — or one whose epic is outside the ingested scope —
 * falling back to its own, A21), `activityType` and `workCategory` (`UNCATEGORIZED` = none) slices.
 */
internal fun taskFactSlice(filter: ReportFilter, connectionIds: List<UInt>, openAttribution: Boolean = false): Op<Boolean> {
    val t = MetricsTables.FactTaskDelivery
    var predicate: Op<Boolean> = (t.connectionId inList connectionIds) and (t.isSubtask eq false)
    filter.teamId?.let { team ->
        val unassigned = team == UNASSIGNED_TEAM_ID
        val credit = if (unassigned) t.creditTeamId.isNull() else (t.creditTeamId eq team)
        predicate = predicate and if (openAttribution) {
            val current = if (unassigned) t.currentTeamId.isNull() else (t.currentTeamId eq team)
            (t.doneAt.isNotNull() and credit) or (t.doneAt.isNull() and current)
        } else {
            credit
        }
    }
    filter.accountId?.let { account ->
        predicate = predicate and if (openAttribution) {
            (t.doneAt.isNotNull() and (t.assigneeAccountIdAtDone eq account)) or
                (t.doneAt.isNull() and (t.currentAssigneeAccountId eq account))
        } else {
            t.assigneeAccountIdAtDone eq account
        }
    }
    filter.domain?.let { domain ->
        predicate = predicate and when (filter.domainView) {
            DomainView.TASK -> t.domainKey eq domain
            DomainView.EPIC -> (t.epicDomainKey eq domain) or (t.epicDomainKey.isNull() and (t.domainKey eq domain))
        }
    }
    filter.activityType?.let { predicate = predicate and (t.activityType eq it) }
    filter.workCategory?.let { category ->
        predicate = predicate and if (category == UNCATEGORIZED) t.workCategory.isNull() else (t.workCategory eq category)
    }
    return predicate
}

/**
 * The `fact_worklog` rows of the period: `started_at` in [window], the author's team (`teamId=0` = no team) and account, and
 * the domain per `domainView` (TASK: the task's; EPIC: the epic's, else the task's — A21), `activityType`, `workCategory`.
 */
internal fun worklogSlice(filter: ReportFilter, connectionIds: List<UInt>, window: Pair<Long, Long>): Op<Boolean> {
    val w = MetricsTables.FactWorklog
    var predicate: Op<Boolean> = (w.connectionId inList connectionIds) and
        (w.startedAt greaterEq window.first) and (w.startedAt less window.second)
    filter.teamId?.let { team ->
        predicate = predicate and if (team == UNASSIGNED_TEAM_ID) w.authorTeamId.isNull() else (w.authorTeamId eq team)
    }
    filter.accountId?.let { predicate = predicate and (w.authorAccountId eq it) }
    filter.domain?.let { domain ->
        predicate = predicate and when (filter.domainView) {
            DomainView.TASK -> w.taskDomainKey eq domain
            DomainView.EPIC -> (w.epicDomainKey eq domain) or (w.epicDomainKey.isNull() and (w.taskDomainKey eq domain))
        }
    }
    filter.activityType?.let { predicate = predicate and (w.activityType eq it) }
    filter.workCategory?.let { category ->
        predicate = predicate and if (category == UNCATEGORIZED) w.workCategory.isNull() else (w.workCategory eq category)
    }
    return predicate
}

/**
 * The `fact_epic_delivery` rows an epic report reads, before any time anchor: the scoped connections,
 * the owner team (A19 — `teamId=0` = no owner, UNOWNED), the epic's own `domain` (an epic's domain is
 * always its own space, so `domainView` never changes it) and `workCategory` (`UNCATEGORIZED` = none).
 * Epics carry no activity type and no user (measures.md), so `activityType` is ignored here and a
 * USER-level read is answered empty by the report itself.
 */
internal fun epicFactSlice(filter: ReportFilter, connectionIds: List<UInt>): Op<Boolean> {
    val e = MetricsTables.FactEpicDelivery
    var predicate: Op<Boolean> = e.connectionId inList connectionIds
    filter.teamId?.let { team ->
        predicate = predicate and if (team == UNASSIGNED_TEAM_ID) e.ownerTeamId.isNull() else (e.ownerTeamId eq team)
    }
    filter.domain?.let { predicate = predicate and (e.domainKey eq it) }
    filter.workCategory?.let { category ->
        predicate = predicate and if (category == UNCATEGORIZED) e.workCategory.isNull() else (e.workCategory eq category)
    }
    return predicate
}

/** Deterministic group order: label (null last), then team id, then account id (the tiebreakers for equal labels). */
internal fun <T> byLabelThenId(label: (T) -> String?, teamId: (T) -> UInt?, accountId: (T) -> String?): Comparator<T> =
    compareBy<T, String?>(nullsLast()) { label(it) }
        .thenBy(nullsLast<UInt>()) { teamId(it) }
        .thenBy(nullsLast<String>()) { accountId(it) }

/** One org-drill entry's identity: a credit team (UNIT level, `teamId` null = UNASSIGNED) or an assignee (TEAM level). */
internal data class OrgGroupKey(val teamId: UInt?, val accountId: String?, val label: String?)

/**
 * The org drill of a DONE-item report: UNIT groups [items] by team (label = the team name), TEAM by user (label = the
 * Jira display name, a null account = the unassigned bucket), USER none. Ordered deterministically (label, null last,
 * then team id, then account id). The caller turns each `(key, rows)` into its own group DTO.
 */
internal suspend fun <T> orgGroups(
    level: ReportLevel,
    items: List<T>,
    team: (T) -> UInt?,
    account: (T) -> String?,
): List<Pair<OrgGroupKey, List<T>>> {
    val keyed: List<Pair<OrgGroupKey, List<T>>> = when (level) {
        ReportLevel.UNIT -> {
            val byTeam = items.groupBy(team)
            val names = teamNames(byTeam.keys.filterNotNull())
            byTeam.map { (id, rows) -> OrgGroupKey(id, null, id?.let { names[it] ?: it.toString() }) to rows }
        }
        ReportLevel.TEAM -> {
            val byAccount = items.groupBy(account)
            val displayNames = accountDisplayNames(byAccount.keys.filterNotNull())
            byAccount.map { (id, rows) -> OrgGroupKey(null, id, id?.let { displayNames[it] ?: it }) to rows }
        }
        ReportLevel.USER -> emptyList()
    }
    return keyed.sortedWith(byLabelThenId({ it.first.label }, { it.first.teamId }, { it.first.accountId }))
}

/** A positive estimate — `0` means unestimated (domain-model.md), and a stored `null` never reaches a division. */
internal fun BigDecimal?.isEstimate(): Boolean = this != null && this.signum() > 0
