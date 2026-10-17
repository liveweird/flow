package ch.nokillswit

import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobListFilter
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.PublicHolidayCreateRequest
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.TeamCapacityRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which connections get a `DERIVE` after a configuration change (`MetricsSettingsService.bumpRevision`'s fan-out rule,
 * `.claude/docs/metrics.md` "DERIVE fan-out per config change"): a global change (settings, membership, a team's default
 * capacity) reaches EVERY enabled connection, a per-connection one (the metrics-config PUT, a sprint-calendar save) only
 * its own. Direct service calls against [sharedDatabaseForTests] (the `MetricsConfigServiceTest` shape); both connections are
 * ENABLED (the fan-out only targets those), their open jobs are drained before the action under test so a PENDING job left
 * by the setup cannot coalesce with the one being asserted, and everything is torn down afterwards.
 */
class MetricsDeriveFanOutTest {
    private class Fixture(val x: UInt, val y: UInt, val teamId: UInt, val sprintId: Long)

    private val settings = DerivedStubFixture.metricsSettings()
    private val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
    private val dataSources = SyncedStubFixture.dataSources()

    private fun pendingDerive(connectionId: UInt): Int = runBlocking {
        jobs.list(
            connectionId,
            SyncJobListFilter(kind = SyncJobKind.DERIVE, status = SyncJobStatus.PENDING),
            PageRequest(page = 1, pageSize = 100, sort = emptyList()),
        ).items.size
    }

    private suspend fun drain(pair: Fixture) {
        jobs.cancelOpenForConnection(pair.x)
        jobs.cancelOpenForConnection(pair.y)
        assertEquals(0, pendingDerive(pair.x) + pendingDerive(pair.y), "the setup leaves nothing PENDING")
    }

    /** Two ENABLED connections X and Y, a team with one Jira member, and a sprint of X mapped to that team. */
    private suspend fun <T> withPair(prefix: String, block: suspend (Fixture) -> T): T {
        val x = SyncedStubFixture.createConnection(namePrefix = "$prefix-x", enabled = true)
        val y = SyncedStubFixture.createConnection(namePrefix = "$prefix-y", enabled = true)
        val teamId = TestTeams.seed(SyncedStubFixture.unique(prefix))
        try {
            CapacityFixtures.addMember(teamId, CapacityFixtures.people(x, 1).single())
            val sprintId = 1L
            CapacityFixtures.insertSprint(x, teamId, sprintId, CapacityFixtures.FRIDAY_MORNING, CapacityFixtures.TUESDAY_AFTERNOON)
            val pair = Fixture(x, y, teamId, sprintId)
            drain(pair)
            return block(pair)
        } finally {
            TestTeams.service.delete(teamId)
            for (id in listOf(x, y)) {
                jobs.cancelOpenForConnection(id)
                dataSources.delete(id)
            }
        }
    }

    @Test
    fun `a per-connection metrics-config PUT enqueues DERIVE for the saved connection only`() = runBlocking {
        withPair("fanout-config") { p ->
            val outcome = DerivedStubFixture.metricsConfig().replaceConfig(p.x, DataSourceMetricsConfigRequest())
            assertTrue(outcome.changed, "the first PUT writes (nothing was stored)")
            assertEquals(1, pendingDerive(p.x), "the saved connection re-derives")
            assertEquals(0, pendingDerive(p.y), "another enabled connection does not")
        }
    }

    @Test
    fun `a global settings PUT enqueues DERIVE for every enabled connection`() = runBlocking {
        withPair("fanout-settings") { p ->
            withMetricsSettings(settings, { it.copy(hoursPerDay = it.hoursPerDay + 1) }) {
                assertEquals(1, pendingDerive(p.x))
                assertEquals(1, pendingDerive(p.y))
            }
        }
    }

    @Test
    fun `a team membership change enqueues DERIVE for every enabled connection`() = runBlocking {
        withPair("fanout-membership") { p ->
            CapacityFixtures.addMember(p.teamId, CapacityFixtures.people(p.x, 1).single())
            assertEquals(1, pendingDerive(p.x))
            assertEquals(1, pendingDerive(p.y))
        }
    }

    @Test
    fun `a team default capacity change enqueues DERIVE for every enabled connection`() = runBlocking {
        withPair("fanout-default") { p ->
            val admin = TestUsers.seed(uniqueEmail("fanout-default"), "pw")
            val outcome = CapacityFixtures.service().replaceTeamCapacity(p.teamId, TeamCapacityRequest(21.0), admin, callerIsAdmin = true)
            assertTrue(outcome.changed)
            assertEquals(1, pendingDerive(p.x))
            assertEquals(1, pendingDerive(p.y))
        }
    }

    @Test
    fun `a sprint calendar save enqueues DERIVE for the sprint's own connection only`() = runBlocking {
        withPair("fanout-calendar") { p ->
            val admin = TestUsers.seed(uniqueEmail("fanout-calendar"), "pw")
            val service = CapacityFixtures.service()
            val calendar = settings.readCalendar()
            val grid = service.read(p.teamId, p.sprintId, calendar, canEdit = true)
            val document = SprintCalendarDocument(
                grid.teamCoefficient + 0.5, grid.days.map { SprintCalendarDay(it.day, it.enabled) },
                grid.people.map { SprintCalendarPerson(it.accountId, it.enabled, it.coefficient!!, it.cells) },
            )
            val outcome = service.replace(p.teamId, p.sprintId, document, admin, callerIsAdmin = true, calendar = calendar)
            assertTrue(outcome.changed)
            assertEquals(1, pendingDerive(p.x), "the sprint's connection re-derives")
            assertEquals(0, pendingDerive(p.y), "another enabled connection does not")
        }
    }

    @Test
    fun `adding and removing a public holiday each enqueue DERIVE for every enabled connection`() = runBlocking {
        withPair("fanout-holiday") { p ->
            val before = settings.currentRevision()
            val created = TestHolidays.service.create(PublicHolidayCreateRequest("2091-04-04", "Fan-out day"))
            try {
                assertEquals(before + 1, settings.currentRevision(), "the shared revision moves")
                assertEquals(1, pendingDerive(p.x))
                assertEquals(1, pendingDerive(p.y))
            } finally {
                drain(p)
                TestHolidays.service.delete(created.id)
            }
            assertEquals(before + 2, settings.currentRevision())
            assertEquals(1, pendingDerive(p.x), "a delete re-derives too")
            assertEquals(1, pendingDerive(p.y))
        }
    }

    @Test
    fun `a scoped bump enqueues nothing for a disabled connection and the global revision still advances`() = runBlocking {
        val disabled = SyncedStubFixture.createConnection(namePrefix = "fanout-disabled", enabled = false)
        try {
            val before = settings.currentRevision()
            val after = settings.bumpRevision(onlyConnectionId = disabled)
            assertTrue(after > before, "the global revision is monotonic whichever connections are targeted")
            assertEquals(0, pendingDerive(disabled), "a disabled connection is never derived")
        } finally {
            dataSources.delete(disabled)
        }
    }
}
