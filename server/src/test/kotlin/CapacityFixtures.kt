package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintCalendarService
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.PersonRef
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Instant
import java.util.UUID

/**
 * Capacity-management fixtures (domain-model A32): a private DISABLED connection (no worker ever derives it), a team with Jira members
 * and a hand-inserted `metrics.dim_sprint` row — no DERIVE needed, the calendar API only reads the sprint's dates and team. The standard
 * window is Fri 2026-03-06 .. Tue 2026-03-10 (five calendar days, Sat/Sun off by default), safely clear of the Warsaw DST change.
 */
object CapacityFixtures {
    val FRIDAY_MORNING: Long = Instant.parse("2026-03-06T09:00:00Z").toEpochMilli()
    val TUESDAY_AFTERNOON: Long = Instant.parse("2026-03-10T15:00:00Z").toEpochMilli()
    val WINDOW_DAYS = listOf("2026-03-06", "2026-03-07", "2026-03-08", "2026-03-09", "2026-03-10")

    class Seeded(
        val connectionId: UInt,
        val teamId: UInt,
        val teamName: String,
        val sprintId: Long,
        /** The team's members (sorted, like the grid's rows) followed by ONE known account that is not a member. */
        val accounts: List<String>,
        val memberCount: Int,
    ) {
        val members: List<String> get() = accounts.take(memberCount)
        val outsider: String get() = accounts.last()
    }

    fun service(): SprintCalendarService = SprintCalendarService(
        sharedDatabaseForTests(), DerivedStubFixture.metricsSettings(), membershipService(), TestTeams.service,
    )

    private fun membershipService() = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())

    /** Registers [count] known people (`norm.people`) on [connectionId]; returns their account ids, sorted. */
    suspend fun people(connectionId: UInt, count: Int): List<String> {
        val ids = List(count) { "account-${UUID.randomUUID()}" }
        SyncedStubFixture.workItems().replacePeople(
            connectionId, ids.mapIndexed { i, id -> PersonRef(id, "Person ${i + 1} ${id.takeLast(4)}", null, active = true) },
        )
        return ids.sorted()
    }

    suspend fun addMember(teamId: UInt, accountId: String) {
        membershipService().create(teamId, TeamMembershipCreateRequest(accountId, validFrom = 0L))
    }

    suspend fun insertSprint(connectionId: UInt, teamId: UInt?, sprintId: Long, startAt: Long?, endAt: Long?, completeAt: Long? = null) {
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.DimSprint.insert {
                it[MetricsTables.DimSprint.connectionId] = connectionId
                it[MetricsTables.DimSprint.sprintId] = sprintId
                it[MetricsTables.DimSprint.teamId] = teamId
                it[name] = "Sprint $sprintId"
                it[state] = "closed"
                it[MetricsTables.DimSprint.startAt] = startAt
                it[MetricsTables.DimSprint.endAt] = endAt
                it[MetricsTables.DimSprint.completeAt] = completeAt
                it[configRevision] = 1L
            }
        }
    }

    /** A connection + team with [members] Jira members and the standard-window sprint [sprintId]. */
    suspend fun seed(prefix: String, members: Int = 2, sprintId: Long = 1L): Seeded {
        val connectionId = SyncedStubFixture.createConnection(namePrefix = prefix, enabled = false)
        val accounts = people(connectionId, members + 1) // sorted
        val teamName = SyncedStubFixture.unique(prefix)
        val teamId = TestTeams.seed(teamName)
        val sorted = accounts.take(members).sorted() + accounts.last()
        sorted.take(members).forEach { addMember(teamId, it) }
        insertSprint(connectionId, teamId, sprintId, FRIDAY_MORNING, TUESDAY_AFTERNOON)
        return Seeded(connectionId, teamId, teamName, sprintId, sorted, members)
    }
}
