package ch.nokillswit.metrics

import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import java.time.format.DateTimeParseException

const val MAX_PUBLIC_HOLIDAY_NAME_LENGTH = 100

/** `YYYY-MM-DD` exactly (`LocalDate.parse` alone would also take a signed or five-digit year, which the VARCHAR(10) column cannot hold). */
private val ISO_DATE_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * One entry of the global, ADMIN-curated public-holiday dictionary (`metrics.public_holidays`, V25; ported from Lettuce): on this
 * date nobody works. It feeds the one working calendar, so the day is non-working in `dim_date`, in DEFAULT capacity and in every
 * report, and it is locked off in capacity calendars.
 */
@Serializable
data class PublicHolidayItem(
    val id: UInt,
    val date: String,
    val name: String,
)

/** The unpaged dictionary read — bounded, admin-curated, intrinsically small (the Lettuce shape). */
@Serializable
data class PublicHolidayList(
    val items: List<PublicHolidayItem>,
)

@Serializable
data class PublicHolidayCreateRequest(
    val date: String,
    val name: String,
)

/** Trims the name (a control character is a 400); idempotent. */
fun sanitizedPublicHoliday(request: PublicHolidayCreateRequest): PublicHolidayCreateRequest =
    request.copy(name = sanitizeSingleLine(request.name, "name"))

/**
 * A holiday's shape: a strict ISO date and a non-blank bounded name. Uniqueness of the date is the database's
 * `UNIQUE (holiday_date)` (23505 -> the central 409 mapping), deliberately no pre-check. Enforced by the route AND the service.
 */
fun validatePublicHoliday(request: PublicHolidayCreateRequest) {
    if (!ISO_DATE_SHAPE.matches(request.date)) throw BadRequestException("date must be an ISO date (YYYY-MM-DD): ${request.date}")
    try {
        java.time.LocalDate.parse(request.date)
    } catch (e: DateTimeParseException) {
        throw BadRequestException("date must be an ISO date (YYYY-MM-DD): ${request.date}", e)
    }
    if (request.name.isBlank()) throw BadRequestException("Holiday name must not be blank")
    if (request.name.length > MAX_PUBLIC_HOLIDAY_NAME_LENGTH) {
        throw BadRequestException("Holiday name must be at most $MAX_PUBLIC_HOLIDAY_NAME_LENGTH characters")
    }
}
