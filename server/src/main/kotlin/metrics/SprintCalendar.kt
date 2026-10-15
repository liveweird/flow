package ch.nokillswit.metrics

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException

/*
 * Capacity management's wire shapes and validators (domain-model A32). The calendar document is both the stored
 * `metrics.sprint_calendars.calendar` jsonb and the PUT body (full replace); the route checks its SHAPE right after the guard,
 * the service re-checks shape + window.
 */

const val MAX_CALENDAR_PEOPLE = 200
const val COEFFICIENT_MAX = 2.0
const val AVAILABILITY_MAX = 1.0

/** `NUMERIC(8, 2)` (`default_capacity_md`, `dim_sprint.capacity_md`): at most six integer digits and two decimals. */
const val CAPACITY_MD_MAX = 999_999.99
const val CAPACITY_MD_SCALE = 2

@Serializable
data class SprintCalendarDay(val day: String, val enabled: Boolean)

@Serializable
data class SprintCalendarCell(val day: String, val availability: Double)

@Serializable
data class SprintCalendarPerson(
    val accountId: String,
    val enabled: Boolean,
    val coefficient: Double,
    val cells: List<SprintCalendarCell>,
)

/** `capacity = teamCoefficient × Σ_{enabled people}(coefficient × Σ_{enabled days} cell)` — see [calendarCapacityMd]. */
@Serializable
data class SprintCalendarDocument(
    val teamCoefficient: Double,
    val days: List<SprintCalendarDay>,
    val people: List<SprintCalendarPerson>,
)

@Serializable
data class SprintCalendarWindow(val from: String, val to: String)

/** A row of the grid: the stored person plus the Jira display name and whether they are a member of the team during the sprint now. */
@Serializable
data class SprintCalendarPersonView(
    val accountId: String,
    val displayName: String,
    val member: Boolean,
    val enabled: Boolean,
    /** Null unless the caller can edit the team's capacity: a person's coefficient is shown to the manager or an ADMIN only. */
    val coefficient: Double?,
    val cells: List<SprintCalendarCell>,
)

/**
 * One sprint's calendar as the page shows it. [window] is null (and [days]/[people] empty, [note] says why) for a sprint without a
 * start date or one spanning over [MAX_CALENDAR_DAYS] days. [capacityMd] is the capacity of THIS grid if saved: the formula over the
 * PROJECTED document (current window and roster). [derivedCapacityMd]/[derivedCapacitySource] are the EFFECTIVE figure and where it came
 * from, `dim_sprint` as of the last DERIVE (the calendar, the team default or members × working days) — they may lag and differ.
 */
@Serializable
data class SprintCalendarResponse(
    val teamId: UInt,
    val sprintId: Long,
    val connectionId: UInt,
    val sprintName: String,
    val sprintState: String,
    val window: SprintCalendarWindow?,
    val note: String?,
    val hasCalendar: Boolean,
    val canEdit: Boolean,
    val capacityMd: Double?,
    val teamCoefficient: Double,
    val days: List<SprintCalendarDay>,
    val people: List<SprintCalendarPersonView>,
    val derivedCapacityMd: Double?,
    val derivedCapacitySource: String?,
    val updatedAt: Long?,
    val updatedByUserId: UInt?,
)

@Serializable
data class SprintCalendarSummary(
    val sprintId: Long,
    val connectionId: UInt,
    val name: String,
    val state: String,
    val startAt: Long?,
    val endAt: Long?,
    val completeAt: Long?,
    val hasCalendar: Boolean,
    val capacityMd: Double?,
    val capacitySource: String?,
)

typealias SprintCalendarSummaryPage = PageResponse<SprintCalendarSummary>

data class SprintCalendarListResult(val items: List<SprintCalendarSummary>, val total: Long)

@Serializable
data class TeamCapacityResponse(val teamId: UInt, val defaultCapacityMd: Double?, val canEdit: Boolean, val updatedAt: Long?)

/** The team's one default figure; an explicit null clears it ("no default"), an omitted field is a 400 (never a silent clear). */
@Serializable
data class TeamCapacityRequest(val defaultCapacityMd: Double?)

/** Trims every account id (a control character is a 400); idempotent. */
fun sanitizedSprintCalendar(document: SprintCalendarDocument): SprintCalendarDocument =
    document.copy(people = document.people.map { it.copy(accountId = sanitizeSingleLine(it.accountId, "accountId")) })

/** The document in its canonical order (days, people by account id, cells by day), at two decimals — what is compared and stored. */
fun canonicalSprintCalendar(document: SprintCalendarDocument): SprintCalendarDocument = SprintCalendarDocument(
    teamCoefficient = round2(document.teamCoefficient),
    days = document.days.sortedBy { it.day },
    people = document.people.sortedBy { it.accountId }.map { person ->
        person.copy(
            coefficient = round2(person.coefficient),
            cells = person.cells.sortedBy { it.day }.map { it.copy(availability = round2(it.availability)) },
        )
    },
)

// `+ 0.0` turns -0.0 into 0.0 so two spellings of zero compare equal.
private fun round2(value: Double): Double =
    BigDecimal.valueOf(value).setScale(CAPACITY_MD_SCALE, RoundingMode.HALF_UP).toDouble() + 0.0

private fun requireRange(value: Double, max: Double, field: String) {
    if (!value.isFinite() || value < 0 || value > max) throw BadRequestException("$field must be between 0 and $max")
    requireScale(value, field)
}

private fun requireScale(value: Double, field: String) {
    if (BigDecimal.valueOf(value).stripTrailingZeros().scale() > CAPACITY_MD_SCALE) {
        throw BadRequestException("$field must have at most $CAPACITY_MD_SCALE decimal places")
    }
}

private fun parseDay(raw: String, field: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (_: DateTimeParseException) {
    throw BadRequestException("$field must be an ISO date (yyyy-MM-dd): $raw")
}

private fun requireDistinct(values: List<String>, field: String) {
    if (values.toSet().size != values.size) throw BadRequestException("$field must not repeat")
}

/** The window-independent rules: ranges, scales, bounds, duplicates, ISO dates. Runs in the route right after the guard. */
fun validateSprintCalendarShape(document: SprintCalendarDocument) {
    requireRange(document.teamCoefficient, COEFFICIENT_MAX, "teamCoefficient")
    if (document.days.size > MAX_CALENDAR_DAYS) throw BadRequestException("days must have at most $MAX_CALENDAR_DAYS entries")
    if (document.people.size > MAX_CALENDAR_PEOPLE) throw BadRequestException("people must have at most $MAX_CALENDAR_PEOPLE entries")
    document.days.forEach { parseDay(it.day, "days[].day") }
    requireDistinct(document.days.map { it.day }, "days[].day")
    requireDistinct(document.people.map { it.accountId }, "people[].accountId")
    document.people.forEach { person ->
        if (person.accountId.isBlank() || person.accountId.length > MAX_ACCOUNT_ID_LENGTH) {
            throw BadRequestException("accountId must be 1-$MAX_ACCOUNT_ID_LENGTH characters")
        }
        requireRange(person.coefficient, COEFFICIENT_MAX, "coefficient")
        if (person.cells.size > MAX_CALENDAR_DAYS) throw BadRequestException("cells must have at most $MAX_CALENDAR_DAYS entries")
        person.cells.forEach {
            parseDay(it.day, "cells[].day")
            requireRange(it.availability, AVAILABILITY_MAX, "availability")
        }
        requireDistinct(person.cells.map { it.day }, "cells[].day")
    }
}

/** The document must cover EXACTLY the sprint's [window]: its days, and every person's cells. */
fun validateSprintCalendarWindow(document: SprintCalendarDocument, window: SprintWindow) {
    val expected = window.days.map { it.toString() }.toSet()
    val label = "${window.fromDay}..${window.toDay}"
    if (document.days.map { it.day }.toSet() != expected) {
        throw BadRequestException("days must be exactly the sprint's window $label")
    }
    document.people.forEach { person ->
        if (person.cells.map { it.day }.toSet() != expected) {
            throw BadRequestException("cells of ${person.accountId} must be exactly the sprint's window $label")
        }
    }
}

fun validateSprintCalendar(document: SprintCalendarDocument, window: SprintWindow) {
    validateSprintCalendarShape(document)
    validateSprintCalendarWindow(document, window)
}

/** A default figure is null or `0..`[CAPACITY_MD_MAX] with at most two decimals (Postgres would silently round more). */
fun validateTeamCapacity(request: TeamCapacityRequest) {
    request.defaultCapacityMd?.let { requireRange(it, CAPACITY_MD_MAX, "defaultCapacityMd") }
}
