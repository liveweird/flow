package ch.nokillswit.teams

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizedDescription
import kotlinx.serialization.Serializable

/**
 * Flat teams (V6): a name and a description — the ownership unit the metrics layer points at; who is IN a team is the
 * dated Jira-account membership (`metrics.team_membership`, D1), not a Flow-login roster (V30 removed it) — and an
 * optional manager (V23): at most one Flow user per team, assigned by an ADMIN, who (with the ADMINs) may edit the
 * team's capacity. No chain: Lettuce's management-chain rule is deliberately NOT ported (see
 * `.claude/docs/authorization.md`). ADMIN curates teams and their manager; everyone reads them.
 */
const val MAX_TEAM_NAME_LENGTH = 100
const val MAX_TEAM_DESCRIPTION_LENGTH = 500

@Serializable
data class TeamCreateRequest(
    val name: String,
    val description: String? = null,
    /** Optional manager — an ACTIVE user (400 otherwise); omitted = no manager. */
    val managerUserId: UInt? = null,
)

@Serializable
data class TeamUpdateRequest(
    val name: String,
    val description: String? = null,
    /** PUT is a full replace: omitted = unset. An ACTIVE user (400 otherwise). */
    val managerUserId: UInt? = null,
)

@Serializable
data class TeamResponse(
    val id: UInt,
    val name: String,
    val description: String?,
    /** The manager as an ACTIVE user; null when unset OR when the assigned user was soft-deleted. */
    val managerUserId: UInt?,
    val managerName: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The list row. */
@Serializable
data class TeamListItem(
    val id: UInt,
    val name: String,
    val description: String?,
    val managerUserId: UInt?,
    val managerName: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias TeamPageResponse = PageResponse<TeamListItem>

data class TeamListFilter(
    val name: String? = null,
)

/** `affected` is the updated-row count (0 → 404); `managerFrom` the STORED manager before the write (the audit delta). */
data class TeamUpdateOutcome(val affected: Int, val managerFrom: UInt?)

data class TeamListResult(val items: List<TeamListItem>, val total: Long)

/** Trims the scalars (the sanitizer convention — control characters are a 400). */
fun sanitizedTeamCreate(request: TeamCreateRequest): TeamCreateRequest = TeamCreateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    managerUserId = request.managerUserId,
)

fun sanitizedTeamUpdate(request: TeamUpdateRequest): TeamUpdateRequest = TeamUpdateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    managerUserId = request.managerUserId,
)

/** The team rules — enforced by the route AND re-checked by the service. */
fun validateTeam(name: String, description: String?) {
    requireNameAndDescription(name, description, MAX_TEAM_NAME_LENGTH, MAX_TEAM_DESCRIPTION_LENGTH)
}

fun validateTeamCreate(request: TeamCreateRequest) = validateTeam(request.name, request.description)

fun validateTeamUpdate(request: TeamUpdateRequest) = validateTeam(request.name, request.description)
