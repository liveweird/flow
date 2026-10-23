package ch.nokillswit.metrics

import ch.nokillswit.infra.db.active
import ch.nokillswit.norm.SprintRef
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.select

// The DERIVE run's read-only setup (`MetricsDeriver.runDerivation` builds the [DeriveContext] once, before the passes): the
// configuration maps, the connection-wide reference reads and the owner-team resolution. Top-level, like the step files.

/**
 * The field ids `custom_fields` actually needs to keep for this connection's derivation (review round 2b, plan §5 "read from
 * custom_fields only the configured field ids"): handed to `workItemsForDerivation`, whose SQL drops every OTHER
 * `customfield_*` (Rank, an ADF-shaped rich-text field, …) before it is transferred — so none of it is ever held in the
 * connection-wide `itemsById` reference map every batch's kernels consult for epic/sub-task lookups.
 */
internal fun relevantCustomFieldIds(config: DataSourceMetricsConfig): Set<String> = setOfNotNull(
    config.fields.estimateTask, config.fields.estimateEpic, config.fields.workCategory, config.fields.epicStart, config.fields.epicDue,
)

/**
 * A22: a sprint counts as CLOSED for "current sprint excludes closed sprints" — either its own
 * Jira `state == "closed"`, or a `complete_at` at or before the DERIVE run's own pinned clock
 * [now] (the same `state`/`completeAtMs` OR the sprint step's own `closedAndMapped` check
 * already applies for the D13 snapshot decision, `runSprintStep`).
 */
private fun isSprintClosed(sprint: SprintRef, now: Long): Boolean =
    sprint.state.equals("closed", ignoreCase = true) || (sprint.completeAtMs != null && sprint.completeAtMs <= now)

internal suspend fun buildContext(
    workItemStore: WorkItemStore,
    teamMembership: TeamMembershipService,
    domainOwners: DomainOwnerResolver,
    connectionId: UInt,
    workItems: List<WorkItemStore.DerivationWorkItemRow>,
    config: DataSourceMetricsConfig,
    calendar: WorkingCalendar,
    now: Long,
    hoursPerDay: Double,
    epicDriftDays: Int,
): DeriveContext {
    val stageMap = config.statusStages.associate { it.statusId to ItemStage.valueOf(it.stage.name) }
    val configMaps = ConfigMaps(
        stageMap = stageMap,
        stageMapByDomain = config.domainStatusStages.groupBy { it.domainKey }.mapValues { (_, overrides) ->
            stageMap + overrides.associate { it.statusId to ItemStage.valueOf(it.stage.name) }
        },
        domainByProject = config.domains.associate { it.projectKey to it.domainKey },
        activityTypeByIssueType = config.activityTypes.associate { it.issueType to it.activityType },
        workCategoryMap = config.workCategories.associate { it.valueId to it.category },
        boardTeamByBoardId = config.boards.associate { it.boardId to it.teamId },
        workCategoryFieldId = config.fields.workCategory,
        epicDriftDays = epicDriftDays,
        epicStartFieldId = config.fields.epicStart,
        epicDueFieldId = config.fields.epicDue,
    )
    val sprints = workItemStore.allSprintRefs(connectionId)
    val activeTeamIds = activeTeamIds()
    return DeriveContext(
        configMaps = configMaps,
        itemsById = workItems.associateBy { it.issueId },
        sprintBoardById = sprints.associate { it.sprintId to it.boardId },
        sprintClosedById = sprints.associate { it.sprintId to isSprintClosed(it, now) },
        membershipsByAccount = teamMembership.allMembershipsByAccount(),
        worklogSecondsByIssue = workItemStore.worklogSecondsByIssue(connectionId),
        ownerTeamByDomain = ownerTeamByDomain(
            workItemStore, domainOwners, connectionId, config, configMaps.boardTeamByBoardId, activeTeamIds,
        ),
        activeTeamIds = activeTeamIds,
        calendar = calendar,
        now = now,
        hoursPerDay = hoursPerDay,
    )
}

/**
 * A22: every team id currently ACTIVE (not soft-deleted) — read INSIDE `derive()`'s own
 * transaction (`buildContext` is called from `runDerivation`, itself run inside `derive()`'s
 * `suspendTransaction` at its ONE call site), so a config/team edit racing this run always sees a
 * consistent snapshot. The SAME `TeamService.Teams`/`active()` read `MetricsConfigService
 * .referenceData` already uses for the metrics-config PUT's own validation.
 */
private suspend fun activeTeamIds(): Set<UInt> =
    TeamService.Teams.select(TeamService.Teams.id).where { TeamService.Teams.active() }
        .toList().map { it[TeamService.Teams.id].value }.toSet()

/**
 * A19/A22 (`.claude/docs/domain-model.md` "Amendments", `.claude/docs/metrics.md`): each
 * configured DOMAIN's (not project's — several project rows may share one `domainKey`) owner
 * team, resolved via the ONE shared implementation, `DomainOwnerResolver
 * .resolveOwnerTeamByDomain` (moved there in v0.3.0 M3 commit 9e so the metrics-config GET's
 * own owner-default filling never duplicates this algorithm) — see that function's own doc for
 * the agreement/fallback rules. [configuredOwners] is read UNFILTERED by team activity: a
 * configured owner that is now soft-deleted still BLOCKS the board fallback and resolves to
 * none (A22) — `resolveOwnerTeamByDomain` itself applies the activity filter.
 */
private suspend fun ownerTeamByDomain(
    workItemStore: WorkItemStore,
    domainOwners: DomainOwnerResolver,
    connectionId: UInt,
    config: DataSourceMetricsConfig,
    boardTeamByBoardId: Map<Long, UInt>,
    activeTeamIds: Set<UInt>,
): Map<String, UInt> {
    val configuredOwners = domainOwners.domainOwnerTeamIds(connectionId)
    val boardsByProject = workItemStore.allBoardRefs(connectionId).filter { it.projectKey != null }
        .groupBy({ it.projectKey!! }, { it.boardId })
    val projectKeysByDomain = config.domains.groupBy({ it.domainKey }, { it.projectKey })
    return domainOwners.resolveOwnerTeamByDomain(
        projectKeysByDomain, configuredOwners, boardsByProject, boardTeamByBoardId, activeTeamIds,
    )
}
