package ch.nokillswit.metrics

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.lockActiveForWrite
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.requireActiveOrNotFound
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.math.BigDecimal
import java.time.LocalDate

val SprintCalendarServiceKey = AttributeKey<SprintCalendarService>("SprintCalendarService")

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

private val D = MetricsTables.DimSprint

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to MetricsTables.DimSprint.sprintId,
    "startAt" to MetricsTables.DimSprint.startAt,
)

/** The sortable whitelist of `GET /teams/{id}/sprint-calendars` — derived from the column map so the two cannot drift. */
val SPRINT_CALENDAR_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * Capacity management (domain-model A32, `V24__capacity_management.sql`): the team's one default MD figure and the per-sprint person x day
 * calendars. Every write runs in ONE transaction that locks the ACTIVE team row (`TeamService.Teams.lockActiveForWrite`, the
 * `TeamMembershipService.create` precedent), re-checks a non-admin caller's manager grant inside it (a grant cleared a moment ago cannot
 * ride a request that passed the route guard earlier) and addresses every row through the path's team id.
 *
 * Cross-feature reads (listed in `.claude/docs/persistence.md`): `teams/TeamService.Teams` (the lock, the active check) and
 * `isActiveManager` (`Teams` ⋈ `FlowUsers`), `norm/WorkItemStore.People` (display names, the known-account check),
 * `ingest/DataSourceService.Connections` (active connections only) and `metrics.dim_sprint` (the sprint's dates, team and
 * last-derived figure).
 */
class SprintCalendarService(
    private val database: R2dbcDatabase,
    private val metricsSettings: MetricsSettingsService,
    private val teamMembership: TeamMembershipService,
    private val teams: TeamService,
) {
    object TeamCapacityDefaults : Table("metrics.team_capacity_defaults") {
        val teamId = reference("team_id", TeamService.Teams)
        val defaultCapacityMd = decimal("default_capacity_md", precision = 8, scale = CAPACITY_MD_SCALE).nullable()
        val updatedAt = long("updated_at")
        val updatedByUserId = long("updated_by_user_id").nullable()
        override val primaryKey = PrimaryKey(teamId)
    }

    /** One jsonb document per (connection, sprint), read and written whole (the `norm.board_columns.status_ids` rule). */
    object SprintCalendars : Table("metrics.sprint_calendars") {
        val connectionId = reference("connection_id", DataSourceService.Connections)
        val sprintId = long("sprint_id")
        val teamId = reference("team_id", TeamService.Teams)
        val sprintStartMs = long("sprint_start_ms")
        val calendar = jsonb("calendar")
        val updatedAt = long("updated_at")
        val updatedByUserId = long("updated_by_user_id").nullable()
        override val primaryKey = PrimaryKey(connectionId, sprintId)
    }

    private data class SprintRow(
        val connectionId: UInt,
        val sprintId: Long,
        val name: String,
        val state: String,
        val startAt: Long?,
        val endAt: Long?,
        val completeAt: Long?,
        val capacityMd: Double?,
        val capacitySource: String?,
    )

    /** The team's derived sprints on active connections, one page; a missing or soft-deleted team is 404 (the team reads' posture). */
    suspend fun listSprints(teamId: UInt, paging: PageRequest): SprintCalendarListResult = suspendTransaction(database) {
        TeamService.Teams.requireActiveOrNotFound(teamId)
        val predicate = (D.teamId eq teamId) and DataSourceService.Connections.active()
        val total = sprintsOfTeam().where { predicate }.count()
        val rows = sprintsOfTeam().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS)
            .orderBy(D.connectionId to SortOrder.ASC).toList().map { it.toSprintRow() }
        val stored = SprintCalendars.select(SprintCalendars.connectionId, SprintCalendars.sprintId)
            .where { (SprintCalendars.teamId eq teamId) and (SprintCalendars.sprintId inList rows.map { it.sprintId }) }
            .toList().map { it[SprintCalendars.connectionId].value to it[SprintCalendars.sprintId] }.toSet()
        SprintCalendarListResult(
            rows.map { SprintCalendarSummary(it.sprintId, it.connectionId, it.name, it.state, it.startAt, it.endAt, it.completeAt,
                (it.connectionId to it.sprintId) in stored, it.capacityMd, it.capacitySource) },
            total,
        )
    }

    /**
     * The sprint's calendar as it would be stored NOW: the stored document re-projected onto the current window and roster (decision 4),
     * else the defaults seeded from the team's most recent earlier calendar (decision 2). Defaults are materialized on read,
     * persisted on write.
     */
    suspend fun read(teamId: UInt, sprintId: Long, calendar: WorkingCalendar, canEdit: Boolean): SprintCalendarResponse =
        suspendTransaction(database) {
            TeamService.Teams.requireActiveOrNotFound(teamId)
            val sprint = resolveSprint(teamId, sprintId)
            val row = storedRow(sprint, teamId)
            fun response(
                window: SprintCalendarWindow?,
                note: String?,
                document: SprintCalendarDocument?,
                people: List<SprintCalendarPersonView>,
                claimed: Map<LocalDate, SprintCalendarClaim> = emptyMap(),
            ) =
                SprintCalendarResponse(
                    teamId = teamId, sprintId = sprintId, connectionId = sprint.connectionId, sprintName = sprint.name,
                    sprintState = sprint.state, window = window, note = note, hasCalendar = row != null, canEdit = canEdit,
                    capacityMd = document?.let(::calendarCapacityMd), teamCoefficient = document?.teamCoefficient ?: DEFAULT_COEFFICIENT,
                    days = document?.days.orEmpty().map {
                        val day = LocalDate.parse(it.day)
                        SprintCalendarDayView(it.day, it.enabled, calendar.holidayName(day), claimed[day])
                    },
                    people = people,
                    derivedCapacityMd = sprint.capacityMd, derivedCapacitySource = sprint.capacitySource,
                    updatedAt = row?.get(SprintCalendars.updatedAt), updatedByUserId = row?.get(SprintCalendars.updatedByUserId)?.toUInt(),
                )
            when (val outcome = windowOf(sprint, calendar)) {
                SprintWindowOutcome.NoStart -> response(null, "The sprint has no start date, so it has no calendar", null, emptyList())
                is SprintWindowOutcome.TooLong ->
                    response(null, "The sprint spans ${outcome.days} days; a calendar covers at most $MAX_CALENDAR_DAYS", null, emptyList())
                is SprintWindowOutcome.Window -> {
                    val window = outcome.window
                    val members = teamMembership.memberAccountIds(teamId, window.startMs, window.endMs)
                    val claimed = claimedOf(sprint, teamId, calendar)
                    val document = project(row, window, members, calendar, claimed, teamId)
                    val shown = SprintCalendarWindow(window.fromDay.toString(), window.toDay.toString())
                    response(shown, null, document, personViews(document, members, canEdit), claimed)
                }
            }
        }

    /** 404 unless the team is ACTIVE — the team-capacity PUT's pre-check, before the body decodes. */
    suspend fun requireTeam(teamId: UInt) {
        suspendTransaction(database) { TeamService.Teams.requireActiveOrNotFound(teamId) }
    }

    /** 404 unless the sprint is a derived sprint of [teamId] on an active connection — the route's pre-check, before the body decodes. */
    suspend fun requireSprint(teamId: UInt, sprintId: Long) {
        suspendTransaction(database) {
            TeamService.Teams.requireActiveOrNotFound(teamId)
            resolveSprint(teamId, sprintId)
        }
    }

    /**
     * A full-replace PUT of the sprint's calendar (409 without a start date, 400 for a window over [MAX_CALENDAR_DAYS] days or an invalid
     * document). A canonically equal document is a no-op: no write, no revision bump. A real change bumps `config_revision`
     * (enqueues DERIVE for the sprint's own connection only).
     */
    suspend fun replace(
        teamId: UInt,
        sprintId: Long,
        request: SprintCalendarDocument,
        byUserId: UInt,
        callerIsAdmin: Boolean,
        calendar: WorkingCalendar,
    ): SprintCalendarReplaceOutcome = suspendTransaction(database) {
        lockTeamAndAuthorize(teamId, byUserId, callerIsAdmin)
        val sprint = resolveSprint(teamId, sprintId)
        val window = when (val outcome = windowOf(sprint, calendar)) {
            SprintWindowOutcome.NoStart -> throw ConflictException("The sprint has no start date, so it has no calendar")
            is SprintWindowOutcome.TooLong ->
                throw BadRequestException("The sprint spans ${outcome.days} days; a calendar covers at most $MAX_CALENDAR_DAYS")
            is SprintWindowOutcome.Window -> outcome.window
        }
        // Validate the sanitized request BEFORE rounding it, so 0.125 is a 400 and never silently becomes 0.13.
        val sanitized = sanitizedSprintCalendar(request)
        validateSprintCalendar(sanitized, window)
        val sent = canonicalSprintCalendar(sanitized)
        val members = teamMembership.memberAccountIds(teamId, window.startMs, window.endMs)
        val existing = storedRow(sprint, teamId)
        val stored = existing?.let { decode(it[SprintCalendars.calendar]) }
        // GET shows a holiday or claimed day OFF whatever is stored (A33, A42), so a re-saved GET-shaped document says false there: keep
        // the stored choice instead, or an unchanged re-save would be a real write that destroys it (it returns when the lock goes).
        val claimed = claimedOf(sprint, teamId, calendar)
        val document = sent.keepingStoredLockedChoices(stored, calendar, claimed)
        // An account must be known to norm.people unless the page itself handed it out: it is on the stored document or the roster.
        requireKnownAccounts(document.people.map { it.accountId }, except = members + stored?.people.orEmpty().map { it.accountId })
        val derived = calendarCapacityMd(projectCalendar(document, window, members, calendar, claimed, carry = null))
        val derivedFrom = stored?.let { calendarCapacityMd(projectCalendar(it, window, members, calendar, claimed, carry = null)) }
        // A moved sprint start re-saves too: `sprint_start_ms` orders the carry-forward.
        val sameStart = existing?.get(SprintCalendars.sprintStartMs) == window.startMs
        if (stored != null && sameStart && canonicalSprintCalendar(stored) == document) {
            return@suspendTransaction SprintCalendarReplaceOutcome(changed = false, sprint.connectionId, derived, derivedFrom)
        }
        val json = canonicalJson(Json.encodeToString(document))
        val updated = SprintCalendars.update({ rowOf(sprint, teamId) }) {
            it[sprintStartMs] = window.startMs
            it[SprintCalendars.calendar] = json
            it[updatedAt] = nowMillis()
            it[updatedByUserId] = byUserId.toLong()
        }
        var replacedTeamId: UInt? = null
        if (updated == 0) {
            // A leftover of the sprint's previous team (its board was re-mapped): replaced by this team's calendar.
            replacedTeamId = SprintCalendars.select(SprintCalendars.teamId)
                .where { (SprintCalendars.connectionId eq sprint.connectionId) and (SprintCalendars.sprintId eq sprint.sprintId) }
                .toList().singleOrNull()?.get(SprintCalendars.teamId)?.value
            SprintCalendars.deleteWhere {
                (SprintCalendars.connectionId eq sprint.connectionId) and (SprintCalendars.sprintId eq sprint.sprintId) and
                    (SprintCalendars.teamId neq teamId)
            }
            SprintCalendars.insert {
                it[connectionId] = sprint.connectionId
                it[SprintCalendars.sprintId] = sprint.sprintId
                it[SprintCalendars.teamId] = teamId
                it[sprintStartMs] = window.startMs
                it[SprintCalendars.calendar] = json
                it[updatedAt] = nowMillis()
                it[updatedByUserId] = byUserId.toLong()
            }
        }
        // Scoped: a calendar is keyed by connection and DERIVE reads only its own connection's (`forDerive`); the carry-forward
        // seed is a GET-projection concern (`carry = null` in DERIVE), so no other connection's derived numbers move.
        metricsSettings.bumpRevision(onlyConnectionId = sprint.connectionId)
        SprintCalendarReplaceOutcome(changed = true, sprint.connectionId, derived, derivedFrom, replacedTeamId)
    }

    suspend fun readTeamCapacity(teamId: UInt, canEdit: Boolean): TeamCapacityResponse = suspendTransaction(database) {
        TeamService.Teams.requireActiveOrNotFound(teamId)
        val row = TeamCapacityDefaults.selectAll().where { TeamCapacityDefaults.teamId eq teamId }.toList().singleOrNull()
        TeamCapacityResponse(
            teamId, row?.get(TeamCapacityDefaults.defaultCapacityMd)?.toDouble(), canEdit, row?.get(TeamCapacityDefaults.updatedAt),
        )
    }

    /** Sets (or, with null, clears) the team's default figure; `changed` when it did (DERIVE enqueued), with the previous figure. */
    suspend fun replaceTeamCapacity(
        teamId: UInt,
        request: TeamCapacityRequest,
        byUserId: UInt,
        callerIsAdmin: Boolean,
    ): TeamCapacityReplaceOutcome =
        suspendTransaction(database) {
            validateTeamCapacity(request)
            lockTeamAndAuthorize(teamId, byUserId, callerIsAdmin)
            val wanted = request.defaultCapacityMd?.let { BigDecimal.valueOf(it).setScale(CAPACITY_MD_SCALE) }
            val row = TeamCapacityDefaults.selectAll().where { TeamCapacityDefaults.teamId eq teamId }.toList().singleOrNull()
            val current = row?.get(TeamCapacityDefaults.defaultCapacityMd)
            val unchanged = if (current == null || wanted == null) current == wanted else current.compareTo(wanted) == 0
            val from = current?.toDouble()
            if (unchanged) return@suspendTransaction TeamCapacityReplaceOutcome(changed = false, from)
            val updated = TeamCapacityDefaults.update({ TeamCapacityDefaults.teamId eq teamId }) {
                it[defaultCapacityMd] = wanted
                it[updatedAt] = nowMillis()
                it[updatedByUserId] = byUserId.toLong()
            }
            if (updated == 0) {
                TeamCapacityDefaults.insert {
                    it[TeamCapacityDefaults.teamId] = teamId
                    it[defaultCapacityMd] = wanted
                    it[updatedAt] = nowMillis()
                    it[updatedByUserId] = byUserId.toLong()
                }
            }
            metricsSettings.bumpRevision()
            TeamCapacityReplaceOutcome(changed = true, from)
        }

    /**
     * DERIVE's input: the connection's stored calendars and every team's non-null default figure — of ACTIVE teams only, so a
     * soft-deleted team's capacity stops feeding DERIVE exactly when its GET/PUT start answering 404 (its sprints fall back to the A3
     * DEFAULT, domain-model A22/A32).
     */
    suspend fun forDerive(connectionId: UInt): SprintCapacityInputs = suspendTransaction(database) {
        val activeTeams = TeamService.Teams.select(TeamService.Teams.id).where { TeamService.Teams.active() }
            .toList().map { it[TeamService.Teams.id].value }.toSet()
        val calendars = SprintCalendars.selectAll().where { SprintCalendars.connectionId eq connectionId }.toList()
            .filter { it[SprintCalendars.teamId].value in activeTeams }.associate {
                it[SprintCalendars.sprintId] to StoredSprintCalendar(
                    it[SprintCalendars.teamId].value, it[SprintCalendars.sprintStartMs], decode(it[SprintCalendars.calendar]),
                )
            }
        val defaults = TeamCapacityDefaults.selectAll().where { TeamCapacityDefaults.defaultCapacityMd.isNotNull() }.toList()
            .filter { it[TeamCapacityDefaults.teamId].value in activeTeams }
            .associate { it[TeamCapacityDefaults.teamId].value to it[TeamCapacityDefaults.defaultCapacityMd]!!.toDouble() }
        SprintCapacityInputs(calendars, defaults)
    }

    /** The PURGE step: drains the connection's calendars; `team_capacity_defaults` is per team and is never purged. */
    suspend fun purgeConnection(connectionId: UInt): Int = suspendTransaction(database) {
        SprintCalendars.deleteWhere { SprintCalendars.connectionId eq connectionId }
    }

    // --- internals ---------------------------------------------------------------------------------------------------

    /** Locks the ACTIVE team (404 when gone) and re-checks a non-admin's manager grant inside the same transaction (403). */
    private suspend fun lockTeamAndAuthorize(teamId: UInt, byUserId: UInt, callerIsAdmin: Boolean) {
        if (!TeamService.Teams.lockActiveForWrite(teamId)) throw NotFoundException("Team not found")
        if (!callerIsAdmin && !teams.isActiveManager(teamId, byUserId)) throw ForbiddenException("Team manager or admin role required")
    }

    private fun sprintsOfTeam() = D.join(DataSourceService.Connections, JoinType.INNER, D.connectionId, DataSourceService.Connections.id)
        .selectAll()

    private fun ResultRow.toSprintRow() = SprintRow(
        this[D.connectionId].value, this[D.sprintId], this[D.name], this[D.state], this[D.startAt], this[D.endAt], this[D.completeAt],
        this[D.capacityMd]?.toDouble(), this[D.capacitySource],
    )

    /** The `dim_sprint` row of [sprintId] under [teamId] on an ACTIVE connection; two connections to one site (parked): the lowest id. */
    private suspend fun resolveSprint(teamId: UInt, sprintId: Long): SprintRow =
        sprintsOfTeam().where { (D.teamId eq teamId) and (D.sprintId eq sprintId) and DataSourceService.Connections.active() }
            .orderBy(D.connectionId to SortOrder.ASC).limit(1).toList().firstOrNull()?.toSprintRow()
            ?: throw NotFoundException("Sprint not found")

    private fun windowOf(sprint: SprintRow, calendar: WorkingCalendar) =
        resolveSprintWindow(sprint.startAt, sprint.endAt, sprint.completeAt, calendar)

    private fun rowOf(sprint: SprintRow, teamId: UInt): Op<Boolean> =
        (SprintCalendars.connectionId eq sprint.connectionId) and (SprintCalendars.sprintId eq sprint.sprintId) and
            (SprintCalendars.teamId eq teamId)

    private suspend fun storedRow(sprint: SprintRow, teamId: UInt): ResultRow? =
        SprintCalendars.selectAll().where { rowOf(sprint, teamId) }.toList().singleOrNull()

    /** A malformed stored document is an invariant violation: fail loudly (the `FrozenScope.kt` precedent), never repair silently. */
    private fun decode(json: String): SprintCalendarDocument = Json.decodeFromString(json)

    /** The stored document (else the carry-forward defaults) laid over the current window and roster. Carry seeds a NEW calendar only. */
    private suspend fun project(
        row: ResultRow?,
        window: SprintWindow,
        members: Set<String>,
        calendar: WorkingCalendar,
        claimed: Map<LocalDate, SprintCalendarClaim>,
        teamId: UInt,
    ): SprintCalendarDocument {
        val stored = row?.let { decode(it[SprintCalendars.calendar]) }
        val carry = if (stored == null) carryForward(teamId, window.startMs) else null
        return projectCalendar(stored, window, members, calendar, claimed, carry)
    }

    /** The days of [sprint] the team's earlier sprints on its connection already hold (A42). */
    private suspend fun claimedOf(sprint: SprintRow, teamId: UInt, calendar: WorkingCalendar): Map<LocalDate, SprintCalendarClaim> =
        claimedDaysOnConnection(sprint.connectionId, teamId, calendar)[sprint.sprintId].orEmpty()

    /**
     * The team's most recent calendar saved for an EARLIER sprint start on an ACTIVE connection (decision 2: coefficients carry,
     * turn-offs never); a start tie takes the highest sprint id, then the lowest connection id, so the seed never flips between reads.
     */
    private suspend fun carryForward(teamId: UInt, startMs: Long): SprintCalendarDocument? =
        SprintCalendars.join(DataSourceService.Connections, JoinType.INNER, SprintCalendars.connectionId, DataSourceService.Connections.id)
            .selectAll()
            .where {
                (SprintCalendars.teamId eq teamId) and (SprintCalendars.sprintStartMs less startMs) and
                    DataSourceService.Connections.active()
            }
            .orderBy(
                SprintCalendars.sprintStartMs to SortOrder.DESC,
                SprintCalendars.sprintId to SortOrder.DESC,
                SprintCalendars.connectionId to SortOrder.ASC,
            ).limit(1).toList().firstOrNull()
            ?.let { decode(it[SprintCalendars.calendar]) }
}
