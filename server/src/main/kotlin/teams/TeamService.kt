package ch.nokillswit.teams

import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.metrics.MetricsSettingsService.Settings as MetricsSettingsTable
import ch.nokillswit.metrics.TeamMembershipService.TeamMembership as MetricsTeamMembershipTable
import ch.nokillswit.flowusers.FlowUserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.db.lockActiveForWrite
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val TeamServiceKey = AttributeKey<TeamService>("TeamService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to TeamService.Teams.id,
    "name" to TeamService.Teams.name,
    "createdAt" to TeamService.Teams.createdAt,
    "updatedAt" to TeamService.Teams.updatedAt,
)

/** The ONE sortable whitelist — derived from the column map so the two can never drift. */
val TEAM_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

class TeamService(private val database: R2dbcDatabase) {
    object Teams : UIntIdTable("teams"), SoftDeletable {
        // Case-folded name uniqueness rides the partial unique index uq_teams_name_active
        // (V6, active rows only) — Exposed defs are query-only, so no `.uniqueIndex()` here.
        val name = varchar("name", length = MAX_TEAM_NAME_LENGTH)
        val description = varchar("description", length = MAX_TEAM_DESCRIPTION_LENGTH).nullable()

        /** V23: the optional manager. May name a soft-deleted user — readers resolve it through ACTIVE users. */
        // The one foreign key from the team tables to Users (V30 dropped the V6 `team_members.user_id`), so the
        // implicit Teams -> Users join is unambiguous.
        val managerUserId = reference("manager_user_id", FlowUserService.FlowUsers).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    suspend fun list(filter: TeamListFilter, paging: PageRequest): TeamListResult = suspendTransaction(database) {
        val predicate = buildPredicate(filter) and Teams.active()
        val total = Teams.selectAll().where { predicate }.count()
        val rows = Teams.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val managers = activeUserNames(rows.mapNotNull { it[Teams.managerUserId]?.value })
        TeamListResult(
            items = rows.map { row ->
                val id = row[Teams.id].value
                val managerId = row[Teams.managerUserId]?.value?.takeIf { it in managers }
                TeamListItem(
                    id = id,
                    name = row[Teams.name],
                    description = row[Teams.description],
                    managerUserId = managerId,
                    managerName = managerId?.let(managers::get),
                    createdAt = row[Teams.createdAt],
                    updatedAt = row[Teams.updatedAt],
                )
            },
            total = total,
        )
    }

    /** The team, or null when missing/deleted. */
    suspend fun read(id: UInt): TeamResponse? = suspendTransaction(database) {
        val row = Teams.selectAll().where { (Teams.id eq id) and Teams.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        val managerId = row[Teams.managerUserId]?.value
        val managerName = managerId?.let { activeUserNames(listOf(it))[it] }
        TeamResponse(
            id = id,
            name = row[Teams.name],
            description = row[Teams.description],
            managerUserId = managerId.takeIf { managerName != null },
            managerName = managerName,
            createdAt = row[Teams.createdAt],
            updatedAt = row[Teams.updatedAt],
        )
    }

    /**
     * Creates the team and its optional manager in ONE transaction: an unknown or soft-deleted manager id is a 400
     * (client-supplied FK — never a 500 from the constraint). A name clash with an active team rides the V6 partial
     * index into the central 23505 → 409.
     */
    suspend fun create(request: TeamCreateRequest): UInt = suspendTransaction(database) {
        validateTeamCreate(request) // re-checked service-side so direct callers stay guarded
        requireActiveManager(request.managerUserId)
        val stamp = nowMillis()
        Teams.insert {
            it[name] = request.name
            it[description] = request.description
            it[managerUserId] = request.managerUserId
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Teams.id].value
    }

    /**
     * Name/description/manager replace (full replace — an omitted manager clears it). `affected` 0 → 404;
     * `managerFrom` is the stored manager before the write.
     */
    suspend fun update(id: UInt, request: TeamUpdateRequest): TeamUpdateOutcome = suspendTransaction(database) {
        validateTeamUpdate(request)
        // NO KEY UPDATE: serializes with other team writers but not with DERIVE's FK locks (persistence.md "Row locks vs. FK locks").
        val before = Teams.selectAll().where { (Teams.id eq id) and Teams.active() }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).toList().singleOrNull()
            ?: return@suspendTransaction TeamUpdateOutcome(0, null)
        requireActiveManager(request.managerUserId)
        val affected = Teams.update({ (Teams.id eq id) and Teams.active() }) {
            it[name] = request.name
            it[description] = request.description
            it[managerUserId] = request.managerUserId
            it[updatedAt] = nowMillis()
        }
        TeamUpdateOutcome(affected, before[Teams.managerUserId]?.value)
    }

    /**
     * Whether [userId] is the team's current manager: an ACTIVE team whose manager is this ACTIVE user — one
     * query. False for a missing/deleted team too, so `requireTeamManagerOrAdmin` answers a uniform 403.
     */
    suspend fun isActiveManager(teamId: UInt, userId: UInt): Boolean = suspendTransaction(database) {
        Teams.innerJoin(FlowUserService.FlowUsers) // the one Teams → users FK is `manager_user_id`
            .select(Teams.id)
            .where {
                (Teams.id eq teamId) and Teams.active() and (Teams.managerUserId eq userId) and FlowUserService.FlowUsers.active()
            }
            .count() > 0
    }

    /**
     * Soft delete. In the SAME transaction, closes/removes this team's D1 Jira-user memberships
     * (cross-feature write, `.claude/docs/persistence.md`'s permission list) — otherwise an
     * account left in an OPEN membership on a deleted team could never be re-teamed (its own
     * `EXCLUDE` constraint would 409 forever against a membership row nobody can end).
     */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        if (!Teams.lockActiveForWrite(id)) return@suspendTransaction 0
        val affected = Teams.update({ (Teams.id eq id) and Teams.active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = nowMillis()
        }
        if (affected > 0) closeOpenJiraMemberships(id)
        affected
    }

    /**
     * Rows still open at [nowMillis] (`valid_to` null or in the future) are closed at `now`; rows
     * that never started yet (`valid_from` in the future) are removed outright — closing them at
     * `now` would produce a backwards `[valid_from > valid_to]` interval the V15 CHECK forbids.
     * Bumps the shared `metrics.settings.config_revision` (nested into this same transaction) only
     * when a membership row actually changed.
     */
    private suspend fun closeOpenJiraMemberships(teamId: UInt) {
        val now = nowMillis()
        val removedFuture = MetricsTeamMembershipTable.deleteWhere {
            (MetricsTeamMembershipTable.teamId eq teamId) and (MetricsTeamMembershipTable.validFrom greater now)
        }
        val closedCurrent = MetricsTeamMembershipTable.update({
            (MetricsTeamMembershipTable.teamId eq teamId) and (MetricsTeamMembershipTable.validFrom lessEq now) and
                (MetricsTeamMembershipTable.validTo.isNull() or (MetricsTeamMembershipTable.validTo greater now))
        }) { it[validTo] = now }
        if (removedFuture > 0 || closedCurrent > 0) {
            MetricsSettingsTable.update({ MetricsSettingsTable.id eq 1 }) {
                it[configRevision] = MetricsSettingsTable.configRevision + 1
            }
        }
    }

    /** One batched lookup: the display names of the ACTIVE users among [ids] (a soft-deleted user is absent). */
    private suspend fun activeUserNames(ids: Collection<UInt>): Map<UInt, String> {
        if (ids.isEmpty()) return emptyMap()
        return FlowUserService.FlowUsers.select(FlowUserService.FlowUsers.id, FlowUserService.FlowUsers.name)
            .where { (FlowUserService.FlowUsers.id inList ids.distinct()) and FlowUserService.FlowUsers.active() }
            .map { it[FlowUserService.FlowUsers.id].value to it[FlowUserService.FlowUsers.name] }
            .toList()
            .toMap()
    }

    /** The manager, when set, must be an ACTIVE user — a client-supplied FK is a 400, never a 500 from the constraint. */
    private suspend fun requireActiveManager(userId: UInt?) {
        if (userId == null) return
        val exists = FlowUserService.FlowUsers.select(FlowUserService.FlowUsers.id)
            .where { (FlowUserService.FlowUsers.id eq userId) and FlowUserService.FlowUsers.active() }
            .count() > 0
        if (!exists) throw BadRequestException("Unknown or deleted manager ids: $userId")
    }

    private fun buildPredicate(filter: TeamListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and Teams.name.containsNormalized(it) }
        return op
    }
}
