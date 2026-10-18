package ch.nokillswit.metrics

import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import java.time.DateTimeException
import java.time.ZoneId

/** ISO weekday numbers Jira/Java both use: 1 = Monday .. 7 = Sunday. */
private val VALID_WEEKDAYS = 1..7

/** A percentile is a number strictly between 0 and 100 (0/100 are degenerate, never useful here). */
private val VALID_PERCENTILE = 1..99

/** Report 11 (aging WIP) reads `p85` off this list by name — the A9 overview relies on it always being present. */
private const val REQUIRED_AGING_PERCENTILE = 85

/** The epic estimate-gap tolerance, a whole percent (V31 CHECK). */
private val PERCENT_RANGE = 0..100

private const val MIN_HOURS_PER_DAY = 0.0
private const val MAX_HOURS_PER_DAY = 24.0

/**
 * The ONE global `metrics.settings` singleton (v0.3.0 M1 commit 3, `.claude/docs/domain-model.md`
 * "Configuration"): the calendar/thresholds every DERIVE run reads under a recorded
 * `configRevision`. `timeZone` defaults to `Europe/Warsaw` (main-session amendment A4).
 * `hoursPerDay` is a manual setting in v0.3.0 (A5) — reading Jira's own time-tracking
 * configuration is deferred to BACKLOG.md.
 */
@Serializable
data class MetricsSettingsResponse(
    val configRevision: Long,
    val hoursPerDay: Double,
    val timeZone: String,
    val weekendDays: List<Int>,
    val commitmentGraceMinutes: Int,
    val minSampleSize: Int,
    val agingWindowItems: Int,
    val agingPercentiles: List<Int>,
    val backlogWindowSprints: Int,
    val epicDriftDays: Int,
    val epicEstimateGapTolerancePercent: Int,
    val updatedAt: Long,
    val updatedByUserId: UInt?,
)

/** A full-replace PUT (API-RES-004, the features-PUT idiom) — every field is required, no partial update. */
@Serializable
data class MetricsSettingsRequest(
    val hoursPerDay: Double,
    val timeZone: String,
    val weekendDays: List<Int>,
    val commitmentGraceMinutes: Int,
    val minSampleSize: Int,
    val agingWindowItems: Int,
    val agingPercentiles: List<Int>,
    val backlogWindowSprints: Int,
    val epicDriftDays: Int,
    val epicEstimateGapTolerancePercent: Int,
)

/** The outcome of a PUT — [changed] false means an idempotent re-PUT, no write/revision bump/audit (the features-PUT precedent). */
data class MetricsSettingsUpdateOutcome(val response: MetricsSettingsResponse, val changed: Boolean)

/** The stored response, reshaped back into request form — [MetricsSettingsService.replace]'s own no-op comparison. */
internal fun MetricsSettingsResponse.asRequest(): MetricsSettingsRequest = MetricsSettingsRequest(
    hoursPerDay = hoursPerDay,
    timeZone = timeZone,
    weekendDays = weekendDays,
    commitmentGraceMinutes = commitmentGraceMinutes,
    minSampleSize = minSampleSize,
    agingWindowItems = agingWindowItems,
    agingPercentiles = agingPercentiles,
    backlogWindowSprints = backlogWindowSprints,
    epicDriftDays = epicDriftDays,
    epicEstimateGapTolerancePercent = epicEstimateGapTolerancePercent,
)

/**
 * Enforced by the route AND re-checked by the service (the `validateTeam` idiom) — mirrors every
 * CHECK constraint `V15__create_metrics_config.sql` declares on `metrics.settings`, plus the
 * checks no CHECK constraint can express (`ZoneId.of`, the all-weekend and
 * percentile-set rules below).
 */
fun validateMetricsSettings(request: MetricsSettingsRequest) {
    if (request.hoursPerDay <= MIN_HOURS_PER_DAY || request.hoursPerDay > MAX_HOURS_PER_DAY) {
        throw BadRequestException("hoursPerDay must be > 0 and <= 24")
    }
    try {
        ZoneId.of(request.timeZone)
    } catch (e: DateTimeException) {
        throw BadRequestException("timeZone must be a valid IANA zone id, e.g. Europe/Warsaw: ${e.message}", e)
    }
    if (request.weekendDays.any { it !in VALID_WEEKDAYS }) {
        throw BadRequestException("weekendDays must each be 1 (Monday) .. 7 (Sunday)")
    }
    if (request.weekendDays.toSet() == VALID_WEEKDAYS.toSet()) {
        throw BadRequestException("weekendDays must not mark every day of the week as non-working")
    }
    if (request.commitmentGraceMinutes < 0) throw BadRequestException("commitmentGraceMinutes must be >= 0")
    if (request.minSampleSize < 1) throw BadRequestException("minSampleSize must be >= 1")
    if (request.agingWindowItems < 1) throw BadRequestException("agingWindowItems must be >= 1")
    if (request.agingPercentiles.isEmpty()) throw BadRequestException("agingPercentiles must not be empty")
    if (request.agingPercentiles.any { it !in VALID_PERCENTILE }) {
        throw BadRequestException("agingPercentiles must each be between 1 and 99")
    }
    if (request.agingPercentiles.toSet().size != request.agingPercentiles.size) {
        throw BadRequestException("agingPercentiles must not contain duplicates")
    }
    if (REQUIRED_AGING_PERCENTILE !in request.agingPercentiles) {
        throw BadRequestException("agingPercentiles must include $REQUIRED_AGING_PERCENTILE (the data-quality overview reads it by name)")
    }
    if (request.backlogWindowSprints < 1) throw BadRequestException("backlogWindowSprints must be >= 1")
    if (request.epicDriftDays < 0) throw BadRequestException("epicDriftDays must be >= 0")
    if (request.epicEstimateGapTolerancePercent !in PERCENT_RANGE) {
        throw BadRequestException("epicEstimateGapTolerancePercent must be between 0 and 100")
    }
}
