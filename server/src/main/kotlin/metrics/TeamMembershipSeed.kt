package ch.nokillswit.metrics

import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/** Upper bound on one seed request's `accountIds` — and on the Atlassian team id's length (a UUID today). */
const val MAX_SEED_ACCOUNTS = 100
const val MAX_ATLASSIAN_TEAM_ID_LENGTH = 200

/**
 * D1's membership seeding from the Atlassian Team field (`.claude/docs/domain-model.md` D1): the admin picks an Atlassian Team,
 * previews the ASSIGNEES of its issues and applies the people they tick. One bounded set, never paged.
 */
@Serializable
data class JiraTeamResponse(val id: String, val name: String, val issueCount: Long)

@Serializable
data class JiraTeamListResponse(val items: List<JiraTeamResponse>)

/** The existing membership that blocks a candidate: any row of the account overlapping `[validFrom, ∞)` (maybe this very team). */
@Serializable
data class SeedConflictResponse(val teamId: UInt, val teamName: String)

/**
 * One assignee of the Atlassian Team's issues. `firstSeen`/`lastSeen` are the creation instants (epoch millis) of the person's
 * earliest/latest issue for that team; `validFrom` is what seeding would store — the first instant of `firstSeen`'s calendar day in
 * the configured metrics zone (membership dates are zone days, like every other membership write). `conflict` is non-null when an
 * existing membership of the account overlaps `[validFrom, ∞)`, so the seed would be refused by the exclusion constraint.
 */
@Serializable
data class SeedCandidateResponse(
    val accountId: String,
    val displayName: String,
    val issueCount: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    val validFrom: Long,
    val conflict: SeedConflictResponse?,
)

@Serializable
data class SeedCandidateListResponse(val items: List<SeedCandidateResponse>)

@Serializable
data class TeamMembershipSeedRequest(val atlassianTeamId: String, val accountIds: List<String>)

enum class SeedSkipReason { CONFLICT, NOT_A_CANDIDATE }

@Serializable
data class SeededMembershipResponse(val accountId: String, val validFrom: Long)

@Serializable
data class SkippedSeedResponse(val accountId: String, val reason: SeedSkipReason)

@Serializable
data class TeamMembershipSeedResponse(val created: List<SeededMembershipResponse>, val skipped: List<SkippedSeedResponse>)

/** The `atlassianTeamId` query/body value: trimmed, control characters a 400, blank or overlong a 400. */
fun sanitizedAtlassianTeamId(raw: String?): String {
    val value = sanitizeSingleLine(raw.orEmpty(), "atlassianTeamId")
    if (value.isEmpty() || value.length > MAX_ATLASSIAN_TEAM_ID_LENGTH) {
        throw BadRequestException("atlassianTeamId must be 1-$MAX_ATLASSIAN_TEAM_ID_LENGTH characters")
    }
    return value
}

/** Trims the team id and every account id (control characters are a 400); the shape rules are [validateTeamMembershipSeed]'s. */
fun sanitizedTeamMembershipSeed(request: TeamMembershipSeedRequest): TeamMembershipSeedRequest = request.copy(
    atlassianTeamId = sanitizedAtlassianTeamId(request.atlassianTeamId),
    accountIds = request.accountIds.map { sanitizeSingleLine(it, "accountIds") },
)

/** 1..[MAX_SEED_ACCOUNTS] DISTINCT account ids, each 1..[MAX_ACCOUNT_ID_LENGTH] characters — enforced by the route AND the service. */
fun validateTeamMembershipSeed(request: TeamMembershipSeedRequest) {
    if (request.atlassianTeamId.isBlank() || request.atlassianTeamId.length > MAX_ATLASSIAN_TEAM_ID_LENGTH) {
        throw BadRequestException("atlassianTeamId must be 1-$MAX_ATLASSIAN_TEAM_ID_LENGTH characters")
    }
    if (request.accountIds.isEmpty() || request.accountIds.size > MAX_SEED_ACCOUNTS) {
        throw BadRequestException("accountIds must hold 1-$MAX_SEED_ACCOUNTS account ids")
    }
    if (request.accountIds.any { it.isBlank() || it.length > MAX_ACCOUNT_ID_LENGTH }) {
        throw BadRequestException("every account id must be 1-$MAX_ACCOUNT_ID_LENGTH characters")
    }
    if (request.accountIds.toSet().size != request.accountIds.size) {
        throw BadRequestException("accountIds must be distinct")
    }
}
