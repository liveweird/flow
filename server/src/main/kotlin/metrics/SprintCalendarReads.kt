package ch.nokillswit.metrics

import ch.nokillswit.infra.db.active
import ch.nokillswit.ingest.DataSourceService
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll

/*
 * The calendar store's read for the cost matrix (report 16, domain-model A39), apart from `SprintCalendarService` for its file-size
 * ceiling. Runs inside the caller's transaction (the report's, with its statement budget).
 */

/**
 * The stored calendars saved FOR [teamId] on the given ACTIVE connections, by (connection id, sprint id) so a caller pairs a calendar
 * only with the sprint row of its own connection. A row saved for another team after a board re-map is not returned (DERIVE's
 * `takeIf { it.teamId == teamId }`). A malformed stored document fails loudly (the service's `decode` rule), never repaired silently.
 */
internal suspend fun storedCalendarsForTeam(
    teamId: UInt,
    connectionIds: Collection<UInt>,
): Map<Pair<UInt, Long>, StoredSprintCalendar> {
    if (connectionIds.isEmpty()) return emptyMap()
    val c = SprintCalendarService.SprintCalendars
    return c.join(DataSourceService.Connections, JoinType.INNER, c.connectionId, DataSourceService.Connections.id)
        .selectAll()
        .where { (c.teamId eq teamId) and (c.connectionId inList connectionIds) and DataSourceService.Connections.active() }
        .toList()
        .associate {
            val stored = StoredSprintCalendar(it[c.teamId].value, it[c.sprintStartMs], Json.decodeFromString(it[c.calendar]))
            (it[c.connectionId].value to it[c.sprintId]) to stored
        }
}
