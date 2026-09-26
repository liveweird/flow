package ch.nokillswit.teams

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.db.lockActiveForUpdate
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
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
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    /** The membership join (hard-delete, V6): a wholesale add/remove per row, no history worth keeping. */
    object TeamMembers : Table("team_members") {
        val teamId = reference("team_id", Teams)
        val userId = reference("user_id", UserService.Users)
        override val primaryKey = PrimaryKey(teamId, userId)
    }

    suspend fun list(filter: TeamListFilter, paging: PageRequest): TeamListResult = suspendTransaction(database) {
        val predicate = buildPredicate(filter) and Teams.active()
        val total = Teams.selectAll().where { predicate }.count()
        val rows = Teams.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val counts = activeMemberCounts(rows.map { it[Teams.id].value })
        TeamListResult(
            items = rows.map { row ->
                val id = row[Teams.id].value
                TeamListItem(
                    id = id,
                    name = row[Teams.name],
                    description = row[Teams.description],
                    memberCount = counts[id] ?: 0,
                    createdAt = row[Teams.createdAt],
                    updatedAt = row[Teams.updatedAt],
                )
            },
            total = total,
        )
    }

    /** The team with its roster (soft-deleted members kept, flagged), or null when missing/deleted. */
    suspend fun read(id: UInt): TeamResponse? = suspendTransaction(database) {
        val row = Teams.selectAll().where { (Teams.id eq id) and Teams.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        TeamResponse(
            id = id,
            name = row[Teams.name],
            description = row[Teams.description],
            members = membersOf(id),
            createdAt = row[Teams.createdAt],
            updatedAt = row[Teams.updatedAt],
        )
    }

    /**
     * Creates the team and its initial roster in ONE transaction: an unknown or soft-deleted
     * member id is a 400 (client-supplied FK — never a 500 from the constraint). A name clash
     * with an active team rides the V6 partial index into the central 23505 → 409.
     */
    suspend fun create(request: TeamCreateRequest): UInt = suspendTransaction(database) {
        validateTeamCreate(request) // re-checked service-side so direct callers stay guarded
        val members = request.memberIds.orEmpty()
        requireActiveUsers(members)
        val stamp = nowMillis()
        val id = Teams.insert {
            it[name] = request.name
            it[description] = request.description
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Teams.id].value
        members.forEach { member ->
            TeamMembers.insert {
                it[teamId] = id
                it[userId] = member
            }
        }
        id
    }

    /** Name/description replace; the roster is managed per member. Returns the affected-row count (0 → 404). */
    suspend fun update(id: UInt, request: TeamUpdateRequest): Int = suspendTransaction(database) {
        validateTeamUpdate(request)
        Teams.selectAll().where { (Teams.id eq id) and Teams.active() }
            .forUpdate().toList().singleOrNull() ?: return@suspendTransaction 0
        Teams.update({ (Teams.id eq id) and Teams.active() }) {
            it[name] = request.name
            it[description] = request.description
            it[updatedAt] = nowMillis()
        }
    }

    /** Soft delete; the roster rows stay for the record (the table is never read for a deleted team). */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        if (!Teams.lockActiveForUpdate(id)) return@suspendTransaction 0
        Teams.update({ (Teams.id eq id) and Teams.active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = nowMillis()
        }
    }

    /** Adds one active user; missing team or user → 404, already a member → 409. */
    suspend fun addMember(teamId: UInt, userId: UInt): Unit = suspendTransaction(database) {
        requireActiveTeam(teamId)
        requireActiveUsers(listOf(userId), asNotFound = true)
        val present = TeamMembers.selectAll()
            .where { (TeamMembers.teamId eq teamId) and (TeamMembers.userId eq userId) }
            .count() > 0
        if (present) throw ConflictException("User is already a member of this team")
        TeamMembers.insert {
            it[TeamMembers.teamId] = teamId
            it[TeamMembers.userId] = userId
        }
        Teams.update({ Teams.id eq teamId }) { it[updatedAt] = nowMillis() }
    }

    /** Removes one membership row (a soft-deleted user's row included); returns the row count (0 → 404). */
    suspend fun removeMember(teamId: UInt, userId: UInt): Int = suspendTransaction(database) {
        requireActiveTeam(teamId)
        val removed = TeamMembers.deleteWhere { (TeamMembers.teamId eq teamId) and (TeamMembers.userId eq userId) }
        if (removed > 0) Teams.update({ Teams.id eq teamId }) { it[updatedAt] = nowMillis() }
        removed
    }

    private suspend fun membersOf(teamId: UInt): List<TeamMemberResponse> =
        TeamMembers.innerJoin(UserService.Users)
            .select(UserService.Users.id, UserService.Users.name, UserService.Users.email, UserService.Users.markedAsDeleted)
            .where { TeamMembers.teamId eq teamId }
            .orderBy(UserService.Users.name to SortOrder.ASC, UserService.Users.id to SortOrder.ASC)
            .map {
                TeamMemberResponse(
                    userId = it[UserService.Users.id].value,
                    name = it[UserService.Users.name],
                    email = it[UserService.Users.email],
                    deleted = it[UserService.Users.markedAsDeleted],
                )
            }
            .toList()

    /** One grouped query for the page's member counts (active users only) — not a per-row lookup. */
    private suspend fun activeMemberCounts(teamIds: List<UInt>): Map<UInt, Int> {
        if (teamIds.isEmpty()) return emptyMap()
        val count = TeamMembers.userId.count()
        return TeamMembers.innerJoin(UserService.Users)
            .select(TeamMembers.teamId, count)
            .where { (TeamMembers.teamId inList teamIds) and (UserService.Users.markedAsDeleted eq false) }
            .groupBy(TeamMembers.teamId)
            .map { it[TeamMembers.teamId].value to it[count].toInt() }
            .toList()
            .toMap()
    }

    private suspend fun requireActiveTeam(teamId: UInt) {
        val exists = Teams.select(Teams.id).where { (Teams.id eq teamId) and Teams.active() }.count() > 0
        if (!exists) throw NotFoundException("Team not found")
    }

    /**
     * Every id must be an ACTIVE user. A create's unknown member is the client's fault (400 —
     * the `requireValidReferences` idiom); a single-member add names a resource (404).
     */
    private suspend fun requireActiveUsers(ids: List<UInt>, asNotFound: Boolean = false) {
        if (ids.isEmpty()) return
        val found = UserService.Users.select(UserService.Users.id)
            .where { (UserService.Users.id inList ids) and (UserService.Users.markedAsDeleted eq false) }
            .map { it[UserService.Users.id].value }
            .toList()
            .toSet()
        val missing = ids.filterNot { it in found }
        if (missing.isNotEmpty()) {
            if (asNotFound) throw NotFoundException("User not found")
            throw BadRequestException("Unknown or deleted member ids: ${missing.joinToString()}")
        }
    }

    private fun buildPredicate(filter: TeamListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and Teams.name.containsNormalized(it) }
        filter.memberId?.let { member ->
            val memberOf = TeamMembers.select(TeamMembers.teamId).where { TeamMembers.userId eq member }
            op = op and (Teams.id inSubQuery memberOf)
        }
        return op
    }
}
