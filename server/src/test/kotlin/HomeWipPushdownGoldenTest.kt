package ch.nokillswit

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.HomeDrillView
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.fetchHomeTeams
import ch.nokillswit.reports.home
import ch.nokillswit.reports.homeContext
import ch.nokillswit.reports.homeItems
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Home overview's WIP figures (`wipTasks`, `wipEpics`, `unassigned.openTasks`) and the `WIP_TASKS` / `WIP_EPICS` drills read the
 * open items in Postgres (`reports/HomeOpenItems.kt`: counts per team, the few order statistics the percentiles need, and the capped
 * top 500 of one team). Every answer must equal the old read - all open items fetched, aged and sorted in the JVM
 * (`HomeWipOracle.kt`) - over `HomeOpenWorld.kt`: ages tied inside a weekend, a drill cut inside that tie block, teams under, at and
 * far over the minimum, a team of one, a soft-deleted team's work, team-less work and two connections.
 */
class HomeWipPushdownGoldenTest {

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    /** Both pick sets of the world against the old figures; returns the overview of the first so a test can pin the world's shape. */
    private suspend fun assertOverviewsEqualOldRead(world: HomeOpenWorld) {
        val svc = service()
        for (picks in listOf(world.picked, world.others)) {
            val overview = svc.home(world.now, picks)
            svc.reportTransaction {
                val ctx = svc.homeContext(world.now)
                val tasks = legacyOpenTasks(ctx.filter, ctx.connectionIds)
                val epics = legacyOpenEpics(ctx.filter, ctx.connectionIds)
                val active = fetchHomeTeams().map { it.id }.toSet()
                assertEquals(picks.size, overview.teams.size)
                for (team in overview.teams) {
                    assertEquals(legacyWip(ctx, tasks.filter { it.teamId == team.teamId }), team.wipTasks, "tasks of ${team.teamId}")
                    assertEquals(legacyWip(ctx, epics.filter { it.teamId == team.teamId }), team.wipEpics, "epics of ${team.teamId}")
                }
                assertEquals(tasks.count { it.teamId !in active }, overview.unassigned.openTasks, "open tasks of no active team")
            }
        }
    }

    @Test
    fun `the overview's WIP figures equal the old read at minSampleSize 3`() = testApplication {
        usePostgresTestcontainer()
        withHomeOpenWorld { world ->
            withMinSampleSize(3) {
                assertOverviewsEqualOldRead(world)
                val teams = service().home(world.now, world.picked).teams.associateBy { it.teamId }
                val a = teams.getValue(world.a)
                assertTrue(a.wipTasks.count > 700 && !a.wipTasks.hidden, "A carries hundreds of open tasks")
                assertTrue(a.wipEpics.count > 600 && !a.wipEpics.hidden)
                assertEquals(3, teams.getValue(world.b).wipTasks.count)
                assertFalse(teams.getValue(world.b).wipTasks.hidden, "exactly the minimum is shown")
                assertNotNull(teams.getValue(world.b).wipTasks.p95)
                assertTrue(teams.getValue(world.c).wipTasks.hidden, "under the minimum is hidden")
                assertNull(teams.getValue(world.c).wipTasks.p50)
                assertEquals(2, teams.getValue(world.c).wipTasks.count)
                val unassigned = service().home(world.now, world.picked).unassigned
                assertTrue(unassigned.openTasks >= 10 + 30, "at least the soft-deleted team's 10 and the 30 team-less tasks")
            }
        }
    }

    @Test
    fun `the overview's WIP figures equal the old read at minSampleSize 1, a team of one included`() = testApplication {
        usePostgresTestcontainer()
        withHomeOpenWorld { world ->
            withMinSampleSize(1) {
                assertOverviewsEqualOldRead(world)
                val g = service().home(world.now, world.others).teams.single { it.teamId == world.g }
                assertEquals(1, g.wipTasks.count)
                assertEquals(g.wipTasks.p50, g.wipTasks.p95)
                assertNotNull(g.wipTasks.p50)
            }
        }
    }

    @Test
    fun `the WIP drills equal the old read for every team, the cut falling inside a weekend tie block`() = testApplication {
        usePostgresTestcontainer()
        withHomeOpenWorld { world ->
            withMinSampleSize(3) {
                val svc = service()
                for (team in listOf(world.a, world.b, world.c, world.d, world.g)) {
                    for (view in listOf(HomeDrillView.WIP_TASKS, HomeDrillView.WIP_EPICS)) {
                        val expected = svc.reportTransaction { legacyWipDrill(svc.homeContext(world.now), view, team) }
                        assertEquals(expected, svc.homeItems(view, team, world.now), "$view of $team")
                    }
                }
                val tasks = svc.homeItems(HomeDrillView.WIP_TASKS, world.a, world.now)
                assertTrue(tasks.truncated && tasks.items.size == 500 && tasks.total == tasks.bar.count && tasks.total > 700)
                val epics = svc.homeItems(HomeDrillView.WIP_EPICS, world.a, world.now)
                assertTrue(epics.truncated && epics.items.size == 500 && epics.total > 600)
                // The cut is inside the weekend block: its items are listed in anchor-descending order (the drill's within-tie order).
                val block = tasks.items.filter { it.figure == tasks.items[499].figure }
                assertTrue(block.size > 1 && block.map { it.anchorAt!! } == block.map { it.anchorAt!! }.sortedDescending())
            }
        }
    }
}
