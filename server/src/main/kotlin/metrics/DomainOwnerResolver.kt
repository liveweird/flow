package ch.nokillswit.metrics

import ch.nokillswit.infra.db.active
import ch.nokillswit.metrics.MetricsConfigService.DomainMap
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * A19/A22 owner-team resolution for the metrics domains (checkup D3 — split out of the former
 * all-in-one `MetricsConfigService`): the explicitly configured owners ([domainOwnerTeamIds]), the
 * ONE pure agreement/fallback algorithm ([resolveOwnerTeamByDomain]) and the GET-time default
 * filling ([withResolvedOwners]). `MetricsDeriver` and `MetricsConfigService.effectiveConfig` both
 * go through this class, so the algorithm lives in exactly one place. Reads the
 * `metrics.domain_map` table that `MetricsConfigService` owns (same package).
 */
class DomainOwnerResolver(
    private val database: R2dbcDatabase,
    private val workItemStore: WorkItemStore,
) {

    /**
     * A19/A22 (V17, commit 9d/9e, `.claude/docs/domain-model.md` "Amendments"): every project key
     * this connection has an EXPLICITLY configured owner team for — `metrics/MetricsDeriver.kt`'s
     * `ownerTeamByDomain` resolves per DOMAIN key (several project rows may share one), agreeing
     * configured owners winning outright, a disagreement resolving to none, and only a project with
     * NO configured owner at all falling back to the one mapped `board_team_map` board on it. Only
     * rows with a non-null `owner_team_id` are returned here (an unconfigured project is simply
     * absent, never present with a `null` value) — `ownerTeamByDomain` itself additionally drops any
     * value pointing at a currently soft-deleted team (A22).
     */
    suspend fun domainOwnerTeamIds(connectionId: UInt): Map<String, UInt> = suspendTransaction(database) {
        DomainMap.select(DomainMap.projectKey, DomainMap.ownerTeamId)
            .where { (DomainMap.connectionId eq connectionId) and DomainMap.ownerTeamId.isNotNull() }
            .toList().associate { it[DomainMap.projectKey] to it[DomainMap.ownerTeamId]!!.value }
    }

    /**
     * A19/A22 (v0.3.0 M1 commit 4 / M3 commit 9e, `.claude/docs/domain-model.md` "Amendments",
     * `.claude/docs/metrics.md`) — resolves each configured DOMAIN key's (not project's — several
     * project rows may share one) owner team. Pure and DB-free: the ONE implementation
     * `MetricsDeriver.ownerTeamByDomain` (the DERIVE run) and [withResolvedOwners] (the GET
     * default/display, below) both call, rather than duplicating the agreement/fallback algorithm.
     *
     * 1. Every project row belonging to the domain that carries a CONFIGURED owner in
     *    [configuredOwners] must AGREE on the same team — rows with no configured owner are
     *    ignored when checking agreement, so a single configured row among several unconfigured
     *    ones still "agrees" trivially. A genuine DISAGREEMENT between two or more distinct
     *    configured owners resolves to NO owner outright — it does NOT fall through to the board
     *    fallback below. A configured owner that is not currently ACTIVE (soft-deleted) also
     *    resolves to no owner rather than falling through (A22 — the admin's explicit choice is
     *    never silently replaced).
     * 2. Absent any configured owner at all, the team of the SINGLE `board_team_map` board (also
     *    active-team-filtered) mapped across ALL of the domain's project keys
     *    ([boardsByProject]) — no mapped board, or more than one distinct team among several
     *    boards across the domain's projects, resolves to no owner.
     * 3. Otherwise absent from the map entirely — the caller's `UNOWNED`/`null` bucket.
     */
    fun resolveOwnerTeamByDomain(
        projectKeysByDomain: Map<String, List<String>>,
        configuredOwners: Map<String, UInt>,
        boardsByProject: Map<String, List<Long>>,
        boardTeamByBoardId: Map<Long, UInt>,
        activeTeamIds: Set<UInt>,
    ): Map<String, UInt> {
        val activeBoardTeamByBoardId = boardTeamByBoardId.filterValues { it in activeTeamIds }
        return projectKeysByDomain.mapNotNull { (domainKey, projectKeys) ->
            val distinctConfigured = projectKeys.mapNotNull { configuredOwners[it] }.distinct()
            val owner = when {
                distinctConfigured.size > 1 -> null
                distinctConfigured.size == 1 -> distinctConfigured.single().takeIf { it in activeTeamIds }
                else -> projectKeys.flatMap { boardsByProject[it].orEmpty() }
                    .mapNotNull { activeBoardTeamByBoardId[it] }.distinct().singleOrNull()
            }
            owner?.let { domainKey to it }
        }.toMap()
    }

    /**
     * v0.3.0 M3 commit 9e: fills every [DataSourceMetricsConfig.domains] row whose
     * [MetricsDomainMapping.ownerTeamId] is unconfigured (`null`) with the SAME computed default
     * [resolveOwnerTeamByDomain] would give a DERIVE run — so a `GET` always shows the owner a
     * report/DERIVE run would actually use, whether an admin configured it explicitly or this
     * connection is entirely unconfigured. An EXPLICITLY stored (non-null) value is never
     * overwritten — [resolveOwnerTeamByDomain]'s own [configuredOwners] input is built from those
     * same explicit values, so a domain that already agrees on one owner resolves to it here too.
     */
    internal suspend fun withResolvedOwners(connectionId: UInt, config: DataSourceMetricsConfig): DataSourceMetricsConfig {
        if (config.domains.none { it.ownerTeamId == null }) return config
        val activeTeamIds = TeamService.Teams.select(TeamService.Teams.id).where { TeamService.Teams.active() }
            .toList().map { it[TeamService.Teams.id].value }.toSet()
        val configuredOwners = config.domains.mapNotNull { domain -> domain.ownerTeamId?.let { domain.projectKey to it } }.toMap()
        val boardsByProject = workItemStore.allBoardRefs(connectionId).filter { it.projectKey != null }
            .groupBy({ it.projectKey!! }, { it.boardId })
        val boardTeamByBoardId = config.boards.associate { it.boardId to it.teamId }
        val projectKeysByDomain = config.domains.groupBy({ it.domainKey }, { it.projectKey })
        val resolved = resolveOwnerTeamByDomain(projectKeysByDomain, configuredOwners, boardsByProject, boardTeamByBoardId, activeTeamIds)
        val domains = config.domains.map { domain ->
            if (domain.ownerTeamId != null) domain else domain.copy(ownerTeamId = resolved[domain.domainKey])
        }
        return config.copy(domains = domains)
    }
}
