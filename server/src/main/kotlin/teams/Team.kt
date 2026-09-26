package ch.nokillswit.teams

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizedDescription
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Flat teams (V6): a name plus its members — the ownership/membership unit future features
 * scope their permissions to. No manager, no chain: Lettuce's management-chain rule is
 * deliberately NOT ported (see `.claude/docs/authorization.md`). ADMIN curates teams and
 * their rosters; everyone reads them.
 */
const val MAX_TEAM_NAME_LENGTH = 100
const val MAX_TEAM_DESCRIPTION_LENGTH = 500

/** A roster cap per request — a team of hundreds is a registry smell, not a use case. */
const val MAX_TEAM_MEMBERS = 200

@Serializable
data class TeamCreateRequest(
    val name: String,
    val description: String? = null,
    /** Optional initial roster — every id must be an ACTIVE user (400 otherwise). */
    val memberIds: List<UInt>? = null,
)

@Serializable
data class TeamUpdateRequest(
    val name: String,
    val description: String? = null,
)

/** One roster row: the user's display fields JOINED at read time, `deleted` when soft-deleted. */
@Serializable
data class TeamMemberResponse(
    val userId: UInt,
    val name: String,
    val email: String,
    val deleted: Boolean,
)

@Serializable
data class TeamResponse(
    val id: UInt,
    val name: String,
    val description: String?,
    val members: List<TeamMemberResponse>,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The list row — the roster collapsed to a count of ACTIVE members. */
@Serializable
data class TeamListItem(
    val id: UInt,
    val name: String,
    val description: String?,
    val memberCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias TeamPageResponse = PageResponse<TeamListItem>

data class TeamListFilter(
    val name: String? = null,
    /** Only teams this user is a member of. */
    val memberId: UInt? = null,
)

data class TeamListResult(val items: List<TeamListItem>, val total: Long)

/** Trims the scalars (the sanitizer convention — control characters are a 400). */
fun sanitizedTeamCreate(request: TeamCreateRequest): TeamCreateRequest = TeamCreateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    memberIds = request.memberIds?.distinct(),
)

fun sanitizedTeamUpdate(request: TeamUpdateRequest): TeamUpdateRequest = TeamUpdateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
)

/** The team rules — enforced by the route AND re-checked by the service. */
fun validateTeam(name: String, description: String?) {
    requireNameAndDescription(name, description, MAX_TEAM_NAME_LENGTH, MAX_TEAM_DESCRIPTION_LENGTH)
}

fun validateTeamCreate(request: TeamCreateRequest) {
    validateTeam(request.name, request.description)
    if ((request.memberIds?.size ?: 0) > MAX_TEAM_MEMBERS) {
        throw BadRequestException("A team may have at most $MAX_TEAM_MEMBERS members")
    }
}

fun validateTeamUpdate(request: TeamUpdateRequest) = validateTeam(request.name, request.description)
