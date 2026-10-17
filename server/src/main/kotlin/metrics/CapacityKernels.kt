package ch.nokillswit.metrics

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Capacity management's pure kernels (domain-model A32, no DB, the `Derive*Kernels.kt` shape): the sprint window, the default and
 * re-projected calendar documents and the capacity formula. `SprintCalendarService` (GET/PUT) and DERIVE both call them, so the page's
 * total and the stored `dim_sprint.capacity_md` can never disagree.
 */

/** A window longer than this many calendar days gets no calendar (a grid that wide is not a sprint). */
const val MAX_CALENDAR_DAYS = 60

/** The default coefficient of a team or person: full productivity. */
const val DEFAULT_COEFFICIENT = 1.0

/** The default availability of a person on a day: one full man-day. */
const val DEFAULT_AVAILABILITY = 1.0

/**
 * The calendar days a sprint covers, in the configured zone: [fromDay] is the day of its start, [toDay] the day of its last instant
 * (`endMs - 1`, so a sprint ending exactly at midnight does not gain a day). [startMs]/[endMs] are the instants the member overlap
 * (A3) is evaluated over.
 */
data class SprintWindow(val fromDay: LocalDate, val toDay: LocalDate, val startMs: Long, val endMs: Long) {
    // Clamped, never wrapped: an absurd end instant must read as "far over the cap", not overflow back under MAX_CALENDAR_DAYS.
    val dayCount: Int get() = (ChronoUnit.DAYS.between(fromDay, toDay) + 1).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /** Every day, first to last — call only on a window [resolveSprintWindow] accepted (at most [MAX_CALENDAR_DAYS]). */
    val days: List<LocalDate> get() = (0 until dayCount).map { fromDay.plusDays(it.toLong()) }
}

/** Why a sprint has, or does not have, a calendar window. */
sealed interface SprintWindowOutcome {
    data class Window(val window: SprintWindow) : SprintWindowOutcome

    /** The sprint has no `start_at` (still FUTURE, or Jira never sent one): no calendar. */
    data object NoStart : SprintWindowOutcome

    /** The window spans [days] > [MAX_CALENDAR_DAYS] calendar days. */
    data class TooLong(val days: Int) : SprintWindowOutcome
}

/**
 * A3's instants: `end = end_at ?: complete_at ?: start_at`. [calendar] supplies the zone, so a window across the Europe/Warsaw
 * DST change still counts calendar days (never 24 h blocks).
 */
fun resolveSprintWindow(startAtMs: Long?, endAtMs: Long?, completeAtMs: Long?, calendar: WorkingCalendar): SprintWindowOutcome {
    if (startAtMs == null) return SprintWindowOutcome.NoStart
    val endMs = endAtMs ?: completeAtMs ?: startAtMs
    val window = SprintWindow(
        fromDay = calendar.dayOf(startAtMs),
        toDay = calendar.dayOf(maxOf(startAtMs, endMs - 1)),
        startMs = startAtMs,
        endMs = endMs,
    )
    return if (window.dayCount > MAX_CALENDAR_DAYS) SprintWindowOutcome.TooLong(window.dayCount) else SprintWindowOutcome.Window(window)
}

/** The window, or null for either reason there is none (what DERIVE needs: no window means the next precedence step). */
fun sprintWindow(startAtMs: Long?, endAtMs: Long?, completeAtMs: Long?, calendar: WorkingCalendar): SprintWindow? =
    (resolveSprintWindow(startAtMs, endAtMs, completeAtMs, calendar) as? SprintWindowOutcome.Window)?.window

/**
 * A fresh calendar: a day is counted when [WorkingCalendar.isWorkingDay] (so never on a public holiday), every member is counted
 * with a full cell on every day.
 * [carry] (decision 2) seeds ONLY the team coefficient and each person's coefficient — turn-offs and cells never carry; a person
 * absent from [carry] gets 1.
 */
fun defaultCalendar(
    window: SprintWindow,
    memberAccountIds: Collection<String>,
    calendar: WorkingCalendar,
    carry: SprintCalendarDocument?,
): SprintCalendarDocument = projectCalendar(
    stored = null, window = window, memberAccountIds = memberAccountIds, calendar = calendar, carry = carry,
)

/**
 * The document a sprint shows and DERIVE sums (decision 4): the CURRENT [window] and the CURRENT roster laid over [stored].
 * Days are the window's (a stored `enabled` is kept, a new day takes the working-day default) — EXCEPT a public holiday
 * ([WorkingCalendar.isHoliday]), which is ALWAYS off whatever is stored: a saved `enabled = true` on a holiday is accepted
 * and projected off, here, so GET and DERIVE (both call this) can never disagree, and removing the holiday from the dictionary
 * gives the stored choice back; people are [stored] ∪ members,
 * sorted by account id (a stored non-member stays, the service flags it); a person's cells are the window's (a stored availability
 * is kept, a new day gets a full cell). A person not in [stored] is new: counted, coefficient from [carry] else 1. A null [stored]
 * is the pure default.
 */
fun projectCalendar(
    stored: SprintCalendarDocument?,
    window: SprintWindow,
    memberAccountIds: Collection<String>,
    calendar: WorkingCalendar,
    carry: SprintCalendarDocument?,
): SprintCalendarDocument {
    val days = window.days
    val storedDays = stored?.days.orEmpty().associate { it.day to it.enabled }
    val storedPeople = stored?.people.orEmpty().associateBy { it.accountId }
    val carriedCoefficients = carry?.people.orEmpty().associate { it.accountId to it.coefficient }
    val people = (storedPeople.keys + memberAccountIds).sorted().map { accountId ->
        val known = storedPeople[accountId]
        val cellsByDay = known?.cells.orEmpty().associate { it.day to it.availability }
        SprintCalendarPerson(
            accountId = accountId,
            enabled = known?.enabled ?: true,
            coefficient = known?.coefficient ?: carriedCoefficients[accountId] ?: DEFAULT_COEFFICIENT,
            cells = days.map { SprintCalendarCell(it.toString(), cellsByDay[it.toString()] ?: DEFAULT_AVAILABILITY) },
        )
    }
    return SprintCalendarDocument(
        teamCoefficient = stored?.teamCoefficient ?: carry?.teamCoefficient ?: DEFAULT_COEFFICIENT,
        days = days.map { day ->
            val enabled = !calendar.isHoliday(day) && (storedDays[day.toString()] ?: calendar.isWorkingDay(day))
            SprintCalendarDay(day.toString(), enabled)
        },
        people = people,
    )
}

/**
 * Each enabled person's man-days on each enabled day: `teamCoefficient × coefficient × cell`, exact decimal arithmetic (every input has
 * at most two decimals, so nothing rounds here). A turned-off person, a turned-off day and a day with no cell (counts 0) contribute
 * nothing (a turned-off person is absent from the map; a person with no counted day maps to an empty map). [calendarCapacityMd] is
 * the rounded sum of this map, so the capacity page, DERIVE's `dim_sprint.capacity_md` and the cost matrix's per-person figure
 * (report 16) read the one formula and cannot disagree.
 */
fun personDayCapacity(document: SprintCalendarDocument): Map<String, Map<LocalDate, BigDecimal>> {
    val countedDays = document.days.filter { it.enabled }.map { it.day }
    val teamCoefficient = BigDecimal.valueOf(document.teamCoefficient)
    val byPerson = LinkedHashMap<String, MutableMap<LocalDate, BigDecimal>>()
    for (person in document.people.filter { it.enabled }) {
        val cells = person.cells.associate { it.day to it.availability }
        val factor = teamCoefficient * BigDecimal.valueOf(person.coefficient)
        val perDay = byPerson.getOrPut(person.accountId) { LinkedHashMap() }
        for (day in countedDays) {
            val cell = cells[day] ?: continue
            perDay.merge(LocalDate.parse(day), factor * BigDecimal.valueOf(cell), BigDecimal::add)
        }
    }
    return byPerson
}

/**
 * The calendar's capacity in man-days: the exact sum of [personDayCapacity] (`teamCoefficient × Σ_{enabled people} (coefficient ×
 * Σ_{enabled days} cell)`), rounded half-up to two at the end — the `sumMd` precedent, so the figure matches the `NUMERIC(8, 2)` it is
 * stored in. A day with no cell for a person counts 0.
 */
fun calendarCapacityMd(document: SprintCalendarDocument): Double =
    personDayCapacity(document).values.flatMap { it.values }
        .fold(BigDecimal.ZERO, BigDecimal::add).setScale(MD_SCALE, RoundingMode.HALF_UP).toDouble()

/** One stored calendar as DERIVE reads it: the team it was saved for, the sprint start it was saved under, and the document. */
data class StoredSprintCalendar(val teamId: UInt, val sprintStartMs: Long, val document: SprintCalendarDocument)

/**
 * What DERIVE's sprint step needs for one connection: its stored calendars by sprint id, and every team's default figure. The step
 * resolves CALENDAR (only when [StoredSprintCalendar.teamId] is still the sprint's team) > TEAM_DEFAULT > the A3 DEFAULT.
 */
data class SprintCapacityInputs(
    val calendarsBySprintId: Map<Long, StoredSprintCalendar>,
    val teamDefaultMdByTeamId: Map<UInt, Double>,
)
