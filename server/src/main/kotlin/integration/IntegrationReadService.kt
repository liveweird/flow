package ch.nokillswit.integration

import ch.nokillswit.metrics.MAX_INTEGRATION_SPRINTS
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.SprintCalendarService
import ch.nokillswit.metrics.SprintCalendarSummary
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.teams.TeamService
import java.time.LocalDate

/** The default cap on teams in the document (a unit has tens); `teamsTruncated` says when it bit. */
const val MAX_INTEGRATION_TEAMS = 1_000

/** The default look-back of `sprintsSince`, in days before today (metrics time zone). */
private const val DEFAULT_SPRINTS_LOOKBACK_DAYS = 365L

/**
 * Composes `GET /api/v1/integration/teams` from the owning features' services, one read each in its own transaction: this package touches
 * no other feature's table (`.claude/docs/persistence.md`). The reads are not one snapshot - a concurrent edit may show up in one part
 * and not another - which a periodic consumer re-reading everything tolerates. Display names come from ONE `norm` call for every distinct
 * account id of the document (the one-team route does the same for one team).
 */
class IntegrationReadService(
    private val teams: TeamService,
    private val memberships: TeamMembershipService,
    private val sprints: SprintCalendarService,
    private val metricsSettings: MetricsSettingsService,
    private val workItems: WorkItemStore,
    /** The team cap; a test lowers it to reach `teamsTruncated` without seeding a thousand teams. */
    private val teamCap: Int = MAX_INTEGRATION_TEAMS,
    /** The sprint row cap; a test lowers it to reach `sprintsTruncated` without seeding thousands of rows. */
    private val sprintCap: Int = MAX_INTEGRATION_SPRINTS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [sprintsSince] is a day in the metrics time zone; null = [DEFAULT_SPRINTS_LOOKBACK_DAYS] days before today there. */
    suspend fun teams(sprintsSince: LocalDate?): IntegrationTeams {
        val asOf = clock()
        val calendar = metricsSettings.readCalendar()
        val since = sprintsSince ?: calendar.dayOf(asOf).minusDays(DEFAULT_SPRINTS_LOOKBACK_DAYS)
        val activeTeams = teams.listActive(teamCap)
        val teamIds = activeTeams.items.map { it.id }
        val rosters = memberships.listAll()
        val sprintRows = sprints.sprintsOfTeams(teamIds, calendar.dayBoundsMs(since).first, sprintCap)
        val defaults = sprints.teamCapacityDefaults(teamIds)
        val names = workItems.displayNames(teamIds.flatMap { id -> rosters[id].orEmpty().map { it.accountId } }.distinct())
        return IntegrationTeams(
            asOf = asOf,
            sprintsSince = since.toString(),
            teamsTruncated = activeTeams.truncated,
            sprintsTruncated = sprintRows.truncated,
            items = activeTeams.items.map { team ->
                IntegrationTeam(
                    id = team.id,
                    name = team.name,
                    managerName = team.managerName,
                    defaultCapacityMd = defaults[team.id],
                    members = rosters[team.id].orEmpty()
                        .map { IntegrationMember(it.accountId, names[it.accountId], it.validFrom, it.validTo) },
                    sprints = sprintRows.byTeam[team.id].orEmpty().map { it.toIntegrationSprint() },
                )
            },
        )
    }

    private fun SprintCalendarSummary.toIntegrationSprint() =
        IntegrationSprint(sprintId, connectionId, name, state, startAt, endAt, completeAt, hasCalendar, capacityMd, capacitySource)
}
