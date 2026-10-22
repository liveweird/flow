package ch.nokillswit.metrics

import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.db.lockActiveForWrite
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.requireActiveOrNotFound
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val TeamWorkCategoryTargetServiceKey = AttributeKey<TeamWorkCategoryTargetService>("TeamWorkCategoryTargetService")

/**
 * Per-team work-category targets (`V32__team_work_category_targets.sql`). The write is ONE transaction that locks the ACTIVE team row
 * (`TeamService.Teams.lockActiveForWrite`, the capacity writes' precedent), validates the keys against the categories the team can choose
 * RIGHT THEN, compares with the stored set and replaces it whole (hard delete + insert) only when it differs.
 *
 * The categories a team can choose (`availableWorkCategories`, `TeamWorkCategoryTargetReads.kt`, shared with the Costs report) are the
 * distinct `metrics.work_category_map.category` labels of EVERY ACTIVE data source, plus [UNCATEGORIZED_CATEGORY] - not only those of
 * the sources the team owns a board on: Costs attributes a team's worklogs by author
 * team across all active connections, so a team with no board of its own must still be able to target Run, Build, and so on.
 *
 * Cross-feature reads (listed in `.claude/docs/persistence.md`): `teams/TeamService.Teams` (the active check, the lock) and
 * `ingest/DataSourceService.Connections` (active connections only). Its own table is written here and nowhere else.
 */
class TeamWorkCategoryTargetService(private val database: R2dbcDatabase) {
    object TeamWorkCategoryTargets : Table("metrics.team_work_category_targets") {
        val teamId = reference("team_id", TeamService.Teams)
        val category = varchar("category", WORK_CATEGORY_MAX_LENGTH)
        val targetPercent = integer("target_percent")
        val updatedAt = long("updated_at")
        val updatedByUserId = long("updated_by_user_id").nullable()
        override val primaryKey = PrimaryKey(teamId, category)
    }

    private val targets = TeamWorkCategoryTargets

    /** 404 unless the team is ACTIVE — the PUT's pre-check, before the body decodes. */
    suspend fun requireTeam(teamId: UInt) {
        suspendTransaction(database) { TeamService.Teams.requireActiveOrNotFound(teamId) }
    }

    /** The available categories merged with the stored set (see the response docs for the order); 404 for a missing or deleted team. */
    suspend fun read(teamId: UInt): TeamWorkCategoryTargetsResponse = suspendTransaction(database) {
        TeamService.Teams.requireActiveOrNotFound(teamId)
        val available = availableWorkCategories()
        val rows = targets.selectAll().where { targets.teamId eq teamId }.toList()
        val stored = rows.associate { it[targets.category] to it[targets.targetPercent] }
        val mappedKeys = (available - UNCATEGORIZED_CATEGORY).sorted() + UNCATEGORIZED_CATEGORY
        val unmappedKeys = (stored.keys - available).sorted()
        TeamWorkCategoryTargetsResponse(
            teamId = teamId,
            categories = mappedKeys.map { TeamWorkCategoryTargetRow(it, stored[it], mapped = true) } +
                unmappedKeys.map { TeamWorkCategoryTargetRow(it, stored.getValue(it), mapped = false) },
            updatedAt = rows.maxOfOrNull { it[targets.updatedAt] },
            updatedByUserId = rows.firstNotNullOfOrNull { it[targets.updatedByUserId] }?.toUInt(),
        )
    }

    /**
     * Replaces the team's whole set. 404 for a missing or soft-deleted team; 400 for a shape violation or a key the team cannot choose
     * (an unmapped category is rejected here — the client drops it before saving). A set equal to the stored one writes nothing.
     */
    suspend fun replace(teamId: UInt, request: TeamWorkCategoryTargetsRequest, byUserId: UInt): TeamWorkCategoryTargetsOutcome =
        suspendTransaction(database) {
            validateTeamWorkCategoryTargets(request) // re-checked service-side so direct callers stay guarded
            if (!TeamService.Teams.lockActiveForWrite(teamId)) throw NotFoundException("Team not found")
            val available = availableWorkCategories()
            val unknown = request.targets.map { it.key }.filter { it !in available }
            if (unknown.isNotEmpty()) {
                throw BadRequestException("Not a work category this team can choose: ${unknown.sorted().joinToString()}")
            }
            val from = targets.selectAll().where { targets.teamId eq teamId }.toList()
                .map { TeamWorkCategoryTarget(it[targets.category], it[targets.targetPercent]) }.sortedBy { it.key }
            val to = request.targets.sortedBy { it.key }
            if (from == to) return@suspendTransaction TeamWorkCategoryTargetsOutcome(changed = false, from, to)
            targets.deleteWhere { targets.teamId eq teamId }
            val stamp = nowMillis()
            targets.insertRows(to) {
                this[targets.teamId] = teamId
                this[targets.category] = it.key
                this[targets.targetPercent] = it.targetPercent
                this[targets.updatedAt] = stamp
                this[targets.updatedByUserId] = byUserId.toLong()
            }
            TeamWorkCategoryTargetsOutcome(changed = true, from, to)
        }
}
