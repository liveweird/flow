package ch.nokillswit.metrics

import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/** Matches `V15__create_metrics_config.sql`'s `account_id VARCHAR(100)`. */
const val MAX_ACCOUNT_ID_LENGTH = 100

/**
 * Dated Jira-user team membership (D1, `.claude/docs/domain-model.md`): one row is one
 * `[validFrom, validTo)` interval — `validTo == null` is the current, open-ended membership.
 * `accountId` is the Jira `accountId`, global across connections (two connections to the same
 * site share account ids).
 */
@Serializable
data class TeamMembershipResponse(
    val id: UInt,
    val accountId: String,
    val validFrom: Long,
    val validTo: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * The plain `{items}` wrapper (`.claude/docs/list-endpoints.md`'s "reserve the plain `{items}`
 * wrapper for genuinely bounded, admin-curated sets") — one team's dated membership history is
 * exactly that, never paged: API-STRUCT-002 forbids a top-level array response.
 */
@Serializable
data class TeamMembershipListResponse(val items: List<TeamMembershipResponse>)

@Serializable
data class TeamMembershipCreateRequest(
    val accountId: String,
    val validFrom: Long,
    val validTo: Long? = null,
)

/** A full-replace PUT over the mutable envelope only — `accountId`/the team are the row's fixed identity. */
@Serializable
data class TeamMembershipUpdateRequest(
    val validFrom: Long,
    val validTo: Long? = null,
)

/** Trims `accountId` (the sanitizer convention — control characters are a 400). */
fun sanitizedTeamMembershipCreate(request: TeamMembershipCreateRequest): TeamMembershipCreateRequest =
    request.copy(accountId = sanitizeSingleLine(request.accountId, "accountId"))

/** Mirrors `V15`'s `CHECK (valid_to IS NULL OR valid_to > valid_from)` — enforced by the route AND re-checked by the service. */
fun validateTeamMembershipDates(validFrom: Long, validTo: Long?) {
    if (validTo != null && validTo <= validFrom) {
        throw BadRequestException("validTo must be after validFrom")
    }
}

fun validateTeamMembershipCreate(request: TeamMembershipCreateRequest) {
    if (request.accountId.isBlank() || request.accountId.length > MAX_ACCOUNT_ID_LENGTH) {
        throw BadRequestException("accountId must be 1-$MAX_ACCOUNT_ID_LENGTH characters")
    }
    validateTeamMembershipDates(request.validFrom, request.validTo)
}

fun validateTeamMembershipUpdate(request: TeamMembershipUpdateRequest) =
    validateTeamMembershipDates(request.validFrom, request.validTo)
