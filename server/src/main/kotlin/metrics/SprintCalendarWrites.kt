package ch.nokillswit.metrics

import ch.nokillswit.infra.db.nowMillis
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import java.math.BigDecimal

/*
 * The calendar store's upserts and the PUT outcome shapes, apart from `SprintCalendarService` for its file-size ceiling. Both writes
 * run inside the caller's transaction, after its team lock, validation and no-op check (the service's `replace` / `replaceTeamCapacity`).
 */

/**
 * [changed] false = a byte-equal re-PUT: nothing written, no revision bump, no audit line. [capacityMd] is the saved calendar's capacity,
 * [capacityMdFrom] the previously stored calendar's (null when none was stored), [replacedTeamId] the team whose leftover row (the sprint's
 * board was re-mapped) this write removed.
 */
data class SprintCalendarReplaceOutcome(
    val changed: Boolean,
    val connectionId: UInt,
    val capacityMd: Double?,
    val capacityMdFrom: Double? = null,
    val replacedTeamId: UInt? = null,
)

/** [changed] false = the figure was already that; [from] the previous figure (null = none). */
data class TeamCapacityReplaceOutcome(val changed: Boolean, val from: Double?)

/**
 * Writes [calendarJson] as the calendar of ([connectionId], [sprintId]) for [teamId]: an update of the team's own row, else (no such
 * row) an insert that first removes a leftover of the sprint's previous team (its board was re-mapped). Returns that previous team's id,
 * or null when nothing was replaced.
 */
internal suspend fun writeSprintCalendar(
    connectionId: UInt,
    sprintId: Long,
    teamId: UInt,
    startMs: Long,
    calendarJson: String,
    byUserId: UInt,
): UInt? {
    val c = SprintCalendarService.SprintCalendars
    val updated = c.update({ (c.connectionId eq connectionId) and (c.sprintId eq sprintId) and (c.teamId eq teamId) }) {
        it[c.sprintStartMs] = startMs
        it[c.calendar] = calendarJson
        it[c.updatedAt] = nowMillis()
        it[c.updatedByUserId] = byUserId.toLong()
    }
    if (updated > 0) return null
    // A leftover of the sprint's previous team (its board was re-mapped): replaced by this team's calendar.
    val replacedTeamId = c.select(c.teamId).where { (c.connectionId eq connectionId) and (c.sprintId eq sprintId) }
        .toList().singleOrNull()?.get(c.teamId)?.value
    c.deleteWhere { (c.connectionId eq connectionId) and (c.sprintId eq sprintId) and (c.teamId neq teamId) }
    c.insert {
        it[c.connectionId] = connectionId
        it[c.sprintId] = sprintId
        it[c.teamId] = teamId
        it[c.sprintStartMs] = startMs
        it[c.calendar] = calendarJson
        it[c.updatedAt] = nowMillis()
        it[c.updatedByUserId] = byUserId.toLong()
    }
    return replacedTeamId
}

/** Sets the team's default capacity figure ([wanted], null clears it): an update of its row, else an insert. */
internal suspend fun writeTeamCapacity(teamId: UInt, wanted: BigDecimal?, byUserId: UInt) {
    val d = SprintCalendarService.TeamCapacityDefaults
    val updated = d.update({ d.teamId eq teamId }) {
        it[d.defaultCapacityMd] = wanted
        it[d.updatedAt] = nowMillis()
        it[d.updatedByUserId] = byUserId.toLong()
    }
    if (updated == 0) {
        d.insert {
            it[d.teamId] = teamId
            it[d.defaultCapacityMd] = wanted
            it[d.updatedAt] = nowMillis()
            it[d.updatedByUserId] = byUserId.toLong()
        }
    }
}
