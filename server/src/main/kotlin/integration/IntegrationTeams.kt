package ch.nokillswit.integration

import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The document of `GET /api/v1/integration/teams` (`.claude/docs/security.md` "Service tokens"): configuration, not a report - every
 * ACTIVE team with its dated Jira-account roster and its sprint capacity, in ONE answer. Every field is already readable by any signed-in
 * user through the team, membership, sprint-calendar and capacity GETs; nothing here is caller-shaped (no `canEdit`, no coefficient or
 * per-person cell, no email, no Flow user id, no connection address).
 */
@Serializable
data class IntegrationTeams(
    /** Epoch milliseconds of the read. */
    val asOf: Long,
    /** The effective filter, `YYYY-MM-DD` in the metrics time zone (the default is today minus 365 days). */
    val sprintsSince: String,
    /** True when the sprint row cap cut the answer short: some teams' sprints are incomplete. */
    val sprintsTruncated: Boolean,
    val items: List<IntegrationTeam>,
)

@Serializable
data class IntegrationTeam(
    val id: UInt,
    val name: String,
    /** The manager's name when the manager is an ACTIVE Flow user. */
    val managerName: String?,
    /** The team's one default capacity figure in man-days, null when none is set. */
    val defaultCapacityMd: Double?,
    /** Every membership interval, open and closed, most recent `validFrom` first. */
    val members: List<IntegrationMember>,
    /** The team's derived sprints on active connections, `startAt` ascending. */
    val sprints: List<IntegrationSprint>,
)

@Serializable
data class IntegrationMember(
    val accountId: String,
    /** From `norm.people`; null for an account with no people row. */
    val displayName: String?,
    val validFrom: Long,
    val validTo: Long?,
)

@Serializable
data class IntegrationSprint(
    val sprintId: Long,
    val connectionId: UInt,
    val name: String,
    val state: String,
    val startAt: Long?,
    val endAt: Long?,
    val completeAt: Long?,
    val hasCalendar: Boolean,
    /** Man-days of the sprint as of the last DERIVE. */
    val capacityMd: Double?,
    val capacitySource: String?,
)

private const val MIN_SPRINTS_SINCE_YEAR = 1970
private const val MAX_SPRINTS_SINCE_YEAR = 2999

/**
 * `sprintsSince` as an ISO date; null when absent. A malformed value or a year outside 1970..2999 is a 400 (the bound keeps the day's
 * epoch-millisecond conversion far from overflow).
 */
internal fun parseSprintsSince(raw: String?): LocalDate? {
    if (raw == null) return null
    val date = try {
        LocalDate.parse(raw)
    } catch (_: DateTimeParseException) {
        null
    }
    if (date == null || date.year !in MIN_SPRINTS_SINCE_YEAR..MAX_SPRINTS_SINCE_YEAR) {
        throw BadRequestException(
            "sprintsSince must be an ISO date (YYYY-MM-DD) between $MIN_SPRINTS_SINCE_YEAR and $MAX_SPRINTS_SINCE_YEAR",
        )
    }
    return date
}
