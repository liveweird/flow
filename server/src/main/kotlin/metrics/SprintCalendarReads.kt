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
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.time.LocalDate

/*
 * The calendar store's reads for the per-person capacity kernel (`CostCapacity.kt`, A39/A41) and the day claims (A42), apart from
 * `SprintCalendarService` for its file-size ceiling. Runs inside the caller's transaction (the report's, with its statement budget).
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

/**
 * The days each sprint of [teamId] on [connectionId] may not count because an earlier sprint of the team holds them (A42), by sprint
 * id: ONE `dim_sprint` select into [claimedDaysBySprint]. A team has one board on one connection, so the connection is the scope DERIVE
 * (per connection) uses too; a sprint with no claim is absent.
 */
internal suspend fun claimedDaysOnConnection(
    connectionId: UInt,
    teamId: UInt,
    calendar: WorkingCalendar,
): Map<Long, Map<LocalDate, SprintCalendarClaim>> {
    val d = MetricsTables.DimSprint
    val sprints = d.select(d.sprintId, d.name, d.startAt, d.endAt, d.completeAt)
        .where { (d.connectionId eq connectionId) and (d.teamId eq teamId) and d.startAt.isNotNull() }
        .toList()
        .map { ClaimSprint(it[d.sprintId], it[d.name], it[d.startAt]!!, it[d.endAt], it[d.completeAt]) }
    return claimedDaysBySprint(sprints, calendar)
}
