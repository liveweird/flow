package ch.nokillswit.metrics

import ch.nokillswit.norm.WorkItemStore
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Instant
import java.time.ZoneId

/**
 * Seeds a Flow team's dated Jira memberships (`metrics.team_membership`) from the Atlassian Team field of the issues
 * (`norm.work_items.team_value`, `.claude/docs/domain-model.md` D1). The rule, all admin-driven: the candidates are the ASSIGNEES of
 * the live TASK-level issues (the one epic/task rule, A31) of the chosen Atlassian Team ([WorkItemStore.teamAssignees]); a seeded
 * membership starts at the first instant of the calendar day (configured metrics zone — membership dates are zone days) of the
 * person's earliest such issue — moved forward to the end of their latest CLOSED membership that reaches past it (a team mover can
 * still be seeded) — and is open-ended; the admin ticks the people. An OPEN membership of the account is a conflict.
 *
 * Reads `norm` through the [WorkItemStore] facade and writes only through [TeamMembershipService]'s own insert path (the account-known
 * check, the exclusion constraint) — no validation is duplicated here. The one cross-feature read is the team lookup/lock, via
 * [TeamMembershipService] (`teams/TeamService.Teams`).
 */
class TeamMembershipSeedService(
    private val database: R2dbcDatabase,
    private val memberships: TeamMembershipService,
    private val workItems: WorkItemStore,
    private val metricsSettings: MetricsSettingsService,
) {
    /** Every Atlassian Team seen on a live task-level issue of an active connection — the picker's options (bounded, never paged). */
    suspend fun jiraTeams(): List<JiraTeamResponse> =
        workItems.atlassianTeams().map { JiraTeamResponse(it.id, it.name, it.issueCount) }

    /** The preview: [candidates] of an ACTIVE team (`404` otherwise), read in one transaction so the conflicts match the people. */
    suspend fun candidatesFor(teamId: UInt, atlassianTeamId: String): List<SeedCandidateResponse> = suspendTransaction(database) {
        memberships.requireActiveTeam(teamId)
        candidates(atlassianTeamId)
    }

    /**
     * Creates the memberships of the ticked [TeamMembershipSeedRequest.accountIds]. ONE transaction: the team is locked ACTIVE first
     * (`404` otherwise, the [TeamMembershipService.create] precedent), the candidates are RECOMPUTED here — never trusted from the
     * client — and each requested account is created through [TeamMembershipService.insertMembership], or skipped as
     * [SeedSkipReason.NOT_A_CANDIDATE] (not an assignee of that Atlassian Team's issues) / [SeedSkipReason.CONFLICT] (an open or
     * still-overlapping membership). The rows are INSERTED in account-id order whatever the request order, so two concurrent seeds
     * (of different teams) take the exclusion constraint's waits in the same order and can never deadlock (40P01 → 500); the
     * response keeps the request order. The shared `config_revision` bumps exactly ONCE, in the same transaction, and only when a
     * row was created. A concurrent write that slips an overlap in between our read and insert surfaces as the central 409 and
     * rolls the whole batch back — nothing is half-seeded.
     */
    suspend fun seed(teamId: UInt, request: TeamMembershipSeedRequest): TeamMembershipSeedResponse = suspendTransaction(database) {
        validateTeamMembershipSeed(request) // re-checked service-side so direct callers stay guarded
        memberships.lockActiveTeam(teamId)
        val byAccount = candidates(request.atlassianTeamId).associateBy { it.accountId }
        val created = mutableMapOf<String, SeededMembershipResponse>()
        val skipped = mutableMapOf<String, SkippedSeedResponse>()
        for (accountId in request.accountIds) {
            val candidate = byAccount[accountId]
            when {
                candidate == null -> skipped[accountId] = SkippedSeedResponse(accountId, SeedSkipReason.NOT_A_CANDIDATE)
                candidate.conflict != null -> skipped[accountId] = SkippedSeedResponse(accountId, SeedSkipReason.CONFLICT)
                else -> created[accountId] = SeededMembershipResponse(accountId, candidate.validFrom)
            }
        }
        for (row in created.values.sortedBy { it.accountId }) {
            memberships.insertMembership(teamId, TeamMembershipCreateRequest(row.accountId, row.validFrom, validTo = null))
        }
        if (created.isNotEmpty()) metricsSettings.bumpRevision()
        TeamMembershipSeedResponse(
            created = request.accountIds.mapNotNull { created[it] },
            skipped = request.accountIds.mapNotNull { skipped[it] },
        )
    }

    private suspend fun candidates(atlassianTeamId: String): List<SeedCandidateResponse> {
        val assignees = workItems.teamAssignees(atlassianTeamId)
        if (assignees.isEmpty()) return emptyList()
        val zone = WorkingCalendar.zoneOf(metricsSettings.read().timeZone)
        val existing = memberships.membershipsOfAccounts(assignees.map { it.accountId }).groupBy { it.accountId }
        return assignees.map { row ->
            val (validFrom, blocking) = startAndBlocker(startOfDayMillis(row.firstCreatedAt, zone), existing[row.accountId].orEmpty())
            SeedCandidateResponse(
                accountId = row.accountId,
                displayName = row.displayName,
                issueCount = row.issueCount,
                firstSeen = row.firstCreatedAt,
                lastSeen = row.lastCreatedAt,
                validFrom = validFrom,
                conflict = blocking?.let { SeedConflictResponse(it.teamId, it.teamName) },
            )
        }
    }

    /**
     * The start a seeded membership would get and what blocks it. An existing row `[from, to)` overlaps the open interval
     * `[start, ∞)` exactly when it is open-ended or ends after `start`. Closed rows that do are stepped over: the start moves to
     * the latest of their ends (a person who left another team after their first issue for this one can still be seeded, from the
     * day they left). Whatever still overlaps from the moved start — an OPEN membership, always — is the blocker (the open one, else
     * the latest-ending).
     */
    private fun startAndBlocker(
        firstDayStart: Long,
        existing: List<TeamMembershipService.NamedMembership>,
    ): Pair<Long, TeamMembershipService.NamedMembership?> {
        val closedEnds = existing.mapNotNull { it.validTo }.filter { it > firstDayStart }
        val start = if (existing.any { it.validTo == null }) firstDayStart else closedEnds.maxOrNull() ?: firstDayStart
        val blocking = existing.filter { it.validTo == null || it.validTo > start }
            .maxByOrNull { it.validTo ?: Long.MAX_VALUE }
        return start to blocking
    }

    private fun startOfDayMillis(instantMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(instantMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
}
