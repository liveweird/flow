package ch.nokillswit.metrics

import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * A team's work-category targets (Costs part 3, `V32__team_work_category_targets.sql`): the share of the team's effort each work category
 * SHOULD take, in whole percent. ONE current set per team, replaced whole; a non-empty set sums to exactly 100, an empty one means "no
 * targets configured". `UNCATEGORIZED` is the "No category" row (the reports' literal for a worklog with no work category) and is always
 * an allowed key. Read at query time, so a change bumps no config revision and enqueues no DERIVE.
 */
const val UNCATEGORIZED_CATEGORY = "UNCATEGORIZED"

/** The most targets one PUT carries: far above the categories any one team can choose, and it bounds the payload and the audit line. */
const val MAX_WORK_CATEGORY_TARGETS = 100

private const val TARGETS_TOTAL_PERCENT = 100

@Serializable
data class TeamWorkCategoryTarget(val key: String, val targetPercent: Int)

/** The WHOLE set — a full replace; an empty list clears it. An omitted `targets` is a 400, so a client bug never clears silently. */
@Serializable
data class TeamWorkCategoryTargetsRequest(val targets: List<TeamWorkCategoryTarget>)

/** [mapped] false = a stored target whose category the team can no longer choose (its mapping was removed). */
@Serializable
data class TeamWorkCategoryTargetRow(val key: String, val targetPercent: Int?, val mapped: Boolean)

@Serializable
data class TeamWorkCategoryTargetsResponse(
    val teamId: UInt,
    val categories: List<TeamWorkCategoryTargetRow>,
    val updatedAt: Long?,
    val updatedByUserId: UInt?,
)

/** [changed] false = the stored set already was that (nothing written, nothing audited); [from] is the previous set. */
data class TeamWorkCategoryTargetsOutcome(
    val changed: Boolean,
    val from: List<TeamWorkCategoryTarget>,
    val to: List<TeamWorkCategoryTarget>,
)

/** Trims every key (a control character is a 400); idempotent. */
fun sanitizedTeamWorkCategoryTargets(request: TeamWorkCategoryTargetsRequest): TeamWorkCategoryTargetsRequest =
    TeamWorkCategoryTargetsRequest(request.targets.map { it.copy(key = sanitizeSingleLine(it.key, "key")) })

/**
 * The shape rules — enforced by the route AND re-checked by the service. That the keys are categories the team can choose needs the
 * database and is the service's, inside its locked transaction.
 */
fun validateTeamWorkCategoryTargets(request: TeamWorkCategoryTargetsRequest) {
    val targets = request.targets
    if (targets.size > MAX_WORK_CATEGORY_TARGETS) {
        throw BadRequestException("At most $MAX_WORK_CATEGORY_TARGETS work-category targets")
    }
    targets.forEach {
        if (it.key.isBlank() || it.key.length > WORK_CATEGORY_MAX_LENGTH) {
            throw BadRequestException("A work category key must be 1-$WORK_CATEGORY_MAX_LENGTH characters")
        }
        if (it.targetPercent !in 0..TARGETS_TOTAL_PERCENT) {
            throw BadRequestException("targetPercent must be a whole percent from 0 to $TARGETS_TOTAL_PERCENT")
        }
    }
    val duplicates = targets.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
    if (duplicates.isNotEmpty()) throw BadRequestException("Duplicate work category: ${duplicates.sorted().joinToString()}")
    val total = targets.sumOf { it.targetPercent }
    if (targets.isNotEmpty() && total != TARGETS_TOTAL_PERCENT) {
        throw BadRequestException("The targets must add up to exactly $TARGETS_TOTAL_PERCENT percent (got $total)")
    }
}
