package ch.nokillswit

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.HomeDrillView
import ch.nokillswit.reports.HomeTeam
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.fetchHomeDoneTasks
import ch.nokillswit.reports.fetchHomeTeams
import ch.nokillswit.reports.home
import ch.nokillswit.reports.homeContext
import ch.nokillswit.reports.homeItems
import ch.nokillswit.reports.percentileContinuous
import ch.nokillswit.reports.percentileRanks
import ch.nokillswit.reports.rankSql
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Home overview's done-task figures are aggregated in Postgres (`reports/HomeDoneFigures.kt`, `HomeDoneSql.kt`; build-times WHY 38).
 * Every figure must equal what the per-row Kotlin kernels give over the same rows (`rowDoneFigures` in `HomeDoneOracle.kt`), to the last
 * bit of the double, over seeded random worlds (`HomeDoneRandomWorld.kt`: empty, one-, two- and three-task windows, an even and an odd
 * count, heavy ties, NUMERIC extremes, PRED boundary pairs, tasks on every window edge and one millisecond either side of every Monday
 * midnight across a clock change). `HomeDonePushdownGoldenTest` grades the same figures against the legacy read over the hand-built world.
 */
class HomeDoneFiguresGoldenTest {

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    private val seeds = listOf(11L, 23L, 37L, 41L, 59L)

    private fun assertTeamEquals(label: String, team: HomeTeam, expected: LegacyDoneFigures) {
        assertEquals(expected.granularity.estimateMd, team.granularity.estimateMd, "estimateMd of $label")
        assertEquals(expected.granularity.cycleWorkingDays, team.granularity.cycleWorkingDays, "cycleWorkingDays of $label")
        assertEquals(expected.granularity.costMd, team.granularity.costMd, "costMd of $label")
        assertEquals(expected.taskAccuracy.pred10, team.accuracy.tasks.pred10, "pred10 of $label")
        assertEquals(expected.taskAccuracy.pred25, team.accuracy.tasks.pred25, "pred25 of $label")
        assertEquals(expected.taskAccuracy.pred50, team.accuracy.tasks.pred50, "pred50 of $label")
        assertEquals(expected.throughput.weeks, team.throughput.weeks, "weeks of $label")
        assertEquals(expected.throughput.deliveredMd, team.throughput.deliveredMd, "deliveredMd of $label")
        assertEquals(expected.throughput.deliveredItems, team.throughput.deliveredItems, "deliveredItems of $label")
        assertEquals(expected.throughput.cycleP50, team.throughput.cycleP50, "cycleP50 of $label")
        assertEquals(expected.throughput.cycleP85, team.throughput.cycleP85, "cycleP85 of $label")
        assertEquals(expected.tasksNoEstimate, team.quality.tasksNoEstimate, "tasksNoEstimate of $label")
        assertEquals(expected.tasksUnassigned, team.quality.tasksUnassigned, "tasksUnassigned of $label")
        assertEquals(expected.tasksEstimatedLate, team.quality.tasksEstimatedLate, "tasksEstimatedLate of $label")
    }

    private suspend fun ReportService.assertWorld(world: HomeDoneRandomWorld, seed: Long, min: Int) {
        for (picks in listOf(world.listed.take(3), world.listed.drop(3))) {
            val overview = home(world.now, picks)
            reportTransaction {
                val ctx = homeContext(world.now)
                assertEquals(HOME_RANDOM_PREVIOUS_FROM to HOME_RANDOM_TO, ctx.windows.both, "the world's edges are the request's windows")
                assertEquals(HOME_RANDOM_CURRENT_FROM, ctx.windows.current.fromMs)
                assertEquals(picks.size, overview.teams.size)
                for (team in overview.teams) {
                    val rows = fetchHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both, setOf(team.teamId))
                    assertTeamEquals("team ${world.listed.indexOf(team.teamId)}, seed $seed, min $min", team, rowDoneFigures(ctx, rows))
                }
                // unassigned.doneTasks: the work of no active team, whoever is listed (the soft-deleted team, team-less rows).
                val all = legacyHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both)
                val active = fetchHomeTeams().map { it.id }.toSet()
                assertEquals(legacyDoneOutside(ctx, all, active), overview.unassigned.doneTasks, "unassigned, seed $seed, min $min")
                assertTrue(overview.unassigned.doneTasks >= 30 + 30, "the deleted team's and the team-less rows, at least")
            }
        }
    }

    @Test
    fun `the figures equal the per-row kernels on five random worlds at minSampleSize 3 and 1`() = testApplication {
        usePostgresTestcontainer()
        for (seed in seeds) {
            withHomeDoneRandomWorld(seed) { world ->
                for (min in listOf(3, 1)) withMinSampleSize(min) { service().assertWorld(world, seed, min) }
            }
        }
    }

    @Test
    fun `the world exercises the hidden, single-value and shown branches and every week of the window`() = testApplication {
        usePostgresTestcontainer()
        withHomeDoneRandomWorld(seeds.first()) { world ->
            val teams = withMinSampleSize(3) { service().home(world.now, world.listed.take(3)).teams }
            val shown = teams.sortedBy { world.listed.indexOf(it.teamId) }
            val (empty, single, pair) = shown
            assertEquals(0, empty.throughput.deliveredItems.n)
            assertTrue(empty.granularity.estimateMd.hidden && empty.accuracy.tasks.pred10.hidden)
            assertEquals(1, single.throughput.deliveredItems.n)
            assertEquals(2, pair.throughput.deliveredItems.n)
            assertTrue(listOf(single, pair).all { it.granularity.costMd.hidden }, "under the minimum of 3")
            val one = withMinSampleSize(1) { service().home(world.now, world.listed.take(3)).teams.single { it.teamId == world.listed[1] } }
            assertTrue(one.throughput.deliveredItems.n == 1 && one.throughput.cycleP50.n <= 1)
            val big = withMinSampleSize(3) { service().home(world.now, world.listed.drop(3)).teams }.single { it.teamId == world.listed[4] }
            assertEquals(14, big.throughput.weeks.size, "the Mondays of 2026-01-19 .. 2026-04-20")
            assertEquals(big.throughput.deliveredItems.n, big.throughput.weeks.sumOf { it.deliveredItems })
            assertTrue(big.throughput.weeks.all { it.deliveredItems > 0 }, "the edge rows fill every week")
            assertTrue(big.accuracy.tasks.pred25.numerator!! > 0 && !big.granularity.cycleWorkingDays.hidden)
        }
    }

    /** The figures of [team] the drill of [view] must reproduce: its count (a PRED's numerator) and, for a median, the figure itself. */
    private fun HomeTeam.countOf(view: HomeDrillView): Int = when (view) {
        HomeDrillView.TASK_ESTIMATE -> granularity.estimateMd.n
        HomeDrillView.TASK_CYCLE -> granularity.cycleWorkingDays.n
        HomeDrillView.TASK_COST -> granularity.costMd.n
        HomeDrillView.TASK_PRED10 -> accuracy.tasks.pred10.numerator!!
        HomeDrillView.TASK_PRED25 -> accuracy.tasks.pred25.numerator!!
        HomeDrillView.TASK_PRED50 -> accuracy.tasks.pred50.numerator!!
        HomeDrillView.QUALITY_TASKS_NO_ESTIMATE -> quality.tasksNoEstimate.n
        HomeDrillView.QUALITY_TASKS_UNASSIGNED -> quality.tasksUnassigned.n
        HomeDrillView.QUALITY_TASKS_ESTIMATED_LATE -> quality.tasksEstimatedLate.n
        else -> error("not a done-task view: $view")
    }

    @Test
    fun `every done-task drill reconciles with the aggregated figure on random worlds`() = testApplication {
        usePostgresTestcontainer()
        val views = HomeDrillView.entries.filter { it.name.startsWith("TASK_") || it.name.startsWith("QUALITY_TASKS_") }
        assertEquals(9, views.size)
        for (seed in seeds.take(3)) {
            withHomeDoneRandomWorld(seed) { world ->
                withMinSampleSize(3) {
                    val svc = service()
                    val overview = world.listed.chunked(3).map { svc.home(world.now, it) }
                    for (team in overview.flatMap { it.teams }) {
                        for (view in views) {
                            val drill = svc.homeItems(view, team.teamId, world.now)
                            val label = "$view of ${world.listed.indexOf(team.teamId)}, seed $seed"
                            assertEquals(team.countOf(view), drill.total, "$label: the figure's count is the drill's total")
                            assertEquals(drill.total, drill.bar.count, "$label: bar")
                            assertTrue(!drill.truncated, "$label: small enough to list whole")
                            assertDrillMedian(label, team, view, drill.items.mapNotNull { it.figure })
                        }
                        val estimate = svc.homeItems(HomeDrillView.TASK_ESTIMATE, team.teamId, world.now)
                        assertEquals(team.throughput.deliveredMd.value, estimate.bar.sumMd, "delivered MD is the estimate drill's sum")
                    }
                }
            }
        }
    }

    /** A median (or the cycle's p85) recomputed from the drill's own items is the figure: the list is exactly what the figure counted. */
    private fun assertDrillMedian(label: String, team: HomeTeam, view: HomeDrillView, figures: List<Double>) {
        val sorted = figures.sorted()
        fun check(fraction: Double, value: Double?, hidden: Boolean, what: String) {
            if (!hidden) assertEquals(value, percentileContinuous(sorted, fraction), "$label: $what from the listed items")
        }
        when (view) {
            HomeDrillView.TASK_ESTIMATE -> check(MEDIAN, team.granularity.estimateMd.value, team.granularity.estimateMd.hidden, "median")
            HomeDrillView.TASK_COST -> check(MEDIAN, team.granularity.costMd.value, team.granularity.costMd.hidden, "median")
            HomeDrillView.TASK_CYCLE -> {
                check(MEDIAN, team.granularity.cycleWorkingDays.value, team.granularity.cycleWorkingDays.hidden, "median")
                check(MEDIAN, team.throughput.cycleP50.value, team.throughput.cycleP50.hidden, "p50")
                check(P85, team.throughput.cycleP85.value, team.throughput.cycleP85.hidden, "p85")
            }
            else -> Unit
        }
    }

    @Test
    fun `the rank SQL names the places percentileContinuous reads for every sample size up to 3000`() = testApplication {
        usePostgresTestcontainer()
        for (fraction in listOf(MEDIAN, P85) + FLOAT_SENSITIVE) {
            val floors = HashMap<Int, Long>()
            val ceils = HashMap<Int, Long>()
            PostgresTestSupport.connect().use { conn ->
                val sql = "SELECT n, ${rankSql(fraction, "n", false)}, ${rankSql(fraction, "n", true)} FROM generate_series(1, $MAX_SIZE) n"
                conn.createStatement().executeQuery(sql).use { rs ->
                    while (rs.next()) {
                        floors[rs.getInt(1)] = rs.getLong(2)
                        ceils[rs.getInt(1)] = rs.getLong(3)
                    }
                }
            }
            assertEquals(MAX_SIZE, floors.size)
            for (n in 1..MAX_SIZE) {
                val ranks = percentileRanks(n, listOf(fraction))
                assertEquals(ranks.min() + 1L, floors.getValue(n), "lower place of n=$n at $fraction")
                assertEquals(ranks.max() + 1L, ceils.getValue(n), "upper place of n=$n at $fraction")
            }
        }
    }

    private companion object {
        const val MEDIAN = 0.5
        const val P85 = 0.85
        const val MAX_SIZE = 3000

        /**
         * Fractions whose exact DECIMAL product with `n - 1` names another place than the double one for some n up to 3000 (0.5 and 0.85 do
         * not, which would leave the float8 product unpinned): the template computes the double product, like `percentileContinuous`.
         */
        val FLOAT_SENSITIVE = listOf(0.07, 0.28, 0.35, 0.7)
    }
}
