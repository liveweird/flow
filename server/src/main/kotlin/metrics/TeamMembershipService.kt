package ch.nokillswit.metrics

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.lockActiveForUpdate
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.db.orVanished
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val TeamMembershipServiceKey = AttributeKey<TeamMembershipService>("TeamMembershipService")

/** A PUT that changed nothing bumps no revision and prompts no audit (the features-PUT precedent). */
data class TeamMembershipUpdateOutcome(val response: TeamMembershipResponse, val changed: Boolean)

/**
 * D1's dated Jira-user team membership (v0.3.0 M1 commit 3, `V15__create_metrics_config.sql`).
 * `metrics.team_membership` is global by `account_id` — Flow's teams package already owns
 * `team_members` (Flow LOGIN membership), so this is a second, Jira-account-scoped join beside
 * it, not a replacement.
 *
 * Two cross-feature reads, listed here per `.claude/docs/persistence.md`'s permission rule:
 * [requireKnownTeam]/[create] query `teams/TeamService.Teams` directly, and [requireKnownAccount]
 * queries `norm/WorkItemStore.People` directly (an unknown Jira account id is a 400 — the
 * client-supplied-FK idiom `teams/TeamService.kt`'s `requireActiveUsers` already uses for Flow
 * user ids). `teams/TeamService.kt`'s own `delete` makes the THIRD cross-feature reference, the
 * other direction: it closes/removes this table's rows for a team it just soft-deleted.
 *
 * **Read-before-guard for reads, active-required for writes.** [list] only requires the team to
 * EXIST (`requireKnownTeam`, any activation state) — a soft-deleted team keeps its membership
 * history for the record, the `team_members` roster precedent
 * (`.claude/docs/authorization.md`'s "read-before-guard" idiom: a soft-deleted team's own `GET` is
 * `200`, not `404`). [create] requires the team to be ACTIVE, locked
 * (`TeamService.Teams.lockActiveForUpdate`) for the duration of the transaction so a concurrent
 * `TeamService.delete` can never race an insert into a team it is in the middle of soft-deleting.
 * [update]/[delete] of an EXISTING membership row need no team-active check at all — the row's own
 * `team_id` FK already proves the path's `teamId` was a real team, and an admin must still be able
 * to correct/remove a historical row after its team is gone.
 */
class TeamMembershipService(
    private val database: R2dbcDatabase,
    private val metricsConfig: MetricsConfigService,
) {
    object TeamMembership : UIntIdTable("metrics.team_membership") {
        val accountId = varchar("account_id", MAX_ACCOUNT_ID_LENGTH)
        val teamId = reference("team_id", TeamService.Teams)
        val validFrom = long("valid_from")
        val validTo = long("valid_to").nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
    }

    /** Every membership row of one team, most recent `validFrom` first — the team's own dated roster. */
    suspend fun list(teamId: UInt): List<TeamMembershipResponse> = suspendTransaction(database) {
        requireKnownTeam(teamId)
        TeamMembership.selectAll().where { TeamMembership.teamId eq teamId }
            .orderBy(TeamMembership.validFrom to SortOrder.DESC, TeamMembership.id to SortOrder.ASC)
            .toList().map { it.toResponse() }
    }

    /**
     * A new dated membership row. The team must be ACTIVE, locked for the whole transaction (see
     * class doc) — a missing/soft-deleted/concurrently-deleted team is `404`. The account id must
     * be known to `norm.people` for SOME connection (400 — a client-supplied-FK error, never a
     * 500). An overlapping `[validFrom, validTo)` for the SAME account id raises SQLSTATE 23P01 at
     * the database (the EXCLUDE constraint, invariant 1) — propagated uncaught to
     * `plugins/ErrorHandling.kt`'s central 409 mapping. Bumps the shared config revision in the
     * SAME transaction (nested — `MetricsConfigService.bumpRevision` reuses this connection).
     */
    suspend fun create(teamId: UInt, request: TeamMembershipCreateRequest): TeamMembershipResponse = suspendTransaction(database) {
        validateTeamMembershipCreate(request) // re-checked service-side so direct callers stay guarded
        if (!TeamService.Teams.lockActiveForUpdate(teamId)) throw NotFoundException("Team not found")
        requireKnownAccount(request.accountId)
        val stamp = nowMillis()
        val id = TeamMembership.insert {
            it[accountId] = request.accountId
            it[TeamMembership.teamId] = teamId
            it[validFrom] = request.validFrom
            it[validTo] = request.validTo
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[TeamMembership.id].value
        metricsConfig.bumpRevision()
        readRow(teamId, id).orVanished("Team membership", id)
    }

    /**
     * A dated envelope replace (validFrom/validTo only — the team/account are fixed). Null means
     * the row is missing (→ 404, no team-active check — see class doc). Identical dates are a
     * no-op: no write, no revision bump, [TeamMembershipUpdateOutcome.changed] false — the route
     * skips its audit line on exactly this outcome (the features-PUT precedent).
     */
    suspend fun update(teamId: UInt, membershipId: UInt, request: TeamMembershipUpdateRequest): TeamMembershipUpdateOutcome? =
        suspendTransaction(database) {
            validateTeamMembershipUpdate(request) // re-checked service-side so direct callers stay guarded
            val current = readRow(teamId, membershipId) ?: return@suspendTransaction null
            if (current.validFrom == request.validFrom && current.validTo == request.validTo) {
                return@suspendTransaction TeamMembershipUpdateOutcome(current, changed = false)
            }
            TeamMembership.update({ (TeamMembership.id eq membershipId) and (TeamMembership.teamId eq teamId) }) {
                it[validFrom] = request.validFrom
                it[validTo] = request.validTo
                it[updatedAt] = nowMillis()
            }
            metricsConfig.bumpRevision()
            val updated = readRow(teamId, membershipId).orVanished("Team membership", membershipId)
            TeamMembershipUpdateOutcome(updated, changed = true)
        }

    /**
     * Hard delete (a pure dated join, the `team_members` idiom) — captures and returns the row
     * BEFORE removing it, in the SAME transaction (so the audit line names what was removed
     * without a second, racy round trip); null means the row was missing (→ 404).
     */
    suspend fun delete(teamId: UInt, membershipId: UInt): TeamMembershipResponse? = suspendTransaction(database) {
        val existing = readRow(teamId, membershipId) ?: return@suspendTransaction null
        val removed = TeamMembership.deleteWhere { (TeamMembership.id eq membershipId) and (TeamMembership.teamId eq teamId) }
        if (removed == 0) return@suspendTransaction null
        metricsConfig.bumpRevision()
        existing
    }

    /** Account ids currently (as of [now]) a member of [teamId] — `/api/v1/jira-users`' own `teamId` filter. */
    suspend fun currentAccountIds(teamId: UInt, now: Long): Set<String> = suspendTransaction(database) {
        TeamMembership.select(TeamMembership.accountId)
            .where {
                (TeamMembership.teamId eq teamId) and (TeamMembership.validFrom lessEq now) and
                    ((TeamMembership.validTo.isNull()) or (TeamMembership.validTo greater now))
            }
            .map { it[TeamMembership.accountId] }.toList().toSet()
    }

    /** Every account id that has EVER held a membership row, any team, any date — `/jira-users`' UNIT-scope input. */
    suspend fun everMemberedAccountIds(): Set<String> = suspendTransaction(database) {
        TeamMembership.select(TeamMembership.accountId).withDistinct()
            .map { it[TeamMembership.accountId] }.toList().toSet()
    }

    /** One account's dated membership interval — `MetricsDeriver`'s own point-in-time "team at instant" read (v0.3.0 M3 commit 7). */
    data class MembershipInterval(val teamId: UInt, val validFrom: Long, val validTo: Long?)

    /**
     * EVERY membership row, grouped by account id (v0.3.0 M3 commit 7) — loaded ONCE per DERIVE run
     * rather than queried per issue; `metrics/MetricsDeriver.kt` resolves "team at instant" itself
     * over this map (a plain interval-containment scan, no DB round trip per lookup).
     */
    suspend fun allMembershipsByAccount(): Map<String, List<MembershipInterval>> = suspendTransaction(database) {
        TeamMembership.selectAll().toList()
            .map { row ->
                val interval =
                    MembershipInterval(row[TeamMembership.teamId].value, row[TeamMembership.validFrom], row[TeamMembership.validTo])
                interval to row[TeamMembership.accountId]
            }
            .groupBy({ (_, accountId) -> accountId }) { (interval, _) -> interval }
    }

    private suspend fun readRow(teamId: UInt, membershipId: UInt): TeamMembershipResponse? =
        TeamMembership.selectAll().where { (TeamMembership.id eq membershipId) and (TeamMembership.teamId eq teamId) }
            .toList().singleOrNull()?.toResponse()

    /** Read-before-guard: a team must EXIST (any activation state) — see class doc. */
    private suspend fun requireKnownTeam(teamId: UInt) {
        val exists = TeamService.Teams.select(TeamService.Teams.id).where { TeamService.Teams.id eq teamId }.count() > 0
        if (!exists) throw NotFoundException("Team not found")
    }

    /** Cross-feature read of `norm.people` (connection-agnostic, since account ids are global) — unknown id is a 400. */
    private suspend fun requireKnownAccount(accountId: String) {
        val exists = WorkItemStore.People.select(WorkItemStore.People.accountId)
            .where { WorkItemStore.People.accountId eq accountId }
            .count() > 0
        if (!exists) throw BadRequestException("Unknown Jira account id: $accountId")
    }

    private fun ResultRow.toResponse(): TeamMembershipResponse = TeamMembershipResponse(
        id = this[TeamMembership.id].value,
        accountId = this[TeamMembership.accountId],
        validFrom = this[TeamMembership.validFrom],
        validTo = this[TeamMembership.validTo],
        createdAt = this[TeamMembership.createdAt],
        updatedAt = this[TeamMembership.updatedAt],
    )
}
