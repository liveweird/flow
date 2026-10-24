package ch.nokillswit

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.DrillItemKind
import ch.nokillswit.reports.DrillRow
import ch.nokillswit.reports.HomeContext
import ch.nokillswit.reports.HomeDrillView
import ch.nokillswit.reports.PRED_10
import ch.nokillswit.reports.PRED_25
import ch.nokillswit.reports.PRED_50
import ch.nokillswit.reports.ReportDrillBar
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.costMdOf
import ch.nokillswit.reports.countHomeDoneOutside
import ch.nokillswit.reports.cycleDaysOf
import ch.nokillswit.reports.drillItems
import ch.nokillswit.reports.estimateMdOf
import ch.nokillswit.reports.fetchHomeDoneTasks
import ch.nokillswit.reports.fetchHomeTeams
import ch.nokillswit.reports.hasNoAssignee
import ch.nokillswit.reports.hasNoEstimate
import ch.nokillswit.reports.home
import ch.nokillswit.reports.homeContext
import ch.nokillswit.reports.homeItems
import ch.nokillswit.reports.md
import ch.nokillswit.reports.predWithin
import ch.nokillswit.reports.sumMdAtDone
import ch.nokillswit.reports.taskRatioOf
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Home overview's done-task read is filtered to the listed teams in Postgres (`credit_team_id`, `reports/HomeReads.kt`) and the work
 * of no active team is a `GROUP BY` count. Every answer must equal the old read - the whole unit's done tasks of both windows fetched
 * and filtered or counted in Kotlin (`HomeDoneOracle.kt`) - over `HomeOpenWorld.kt`'s done tasks (`HomeDoneWorld.kt`): listed teams, an
 * unpicked team, a soft-deleted team's work, team-less rows, both windows' edges and two connections. The row itself is trimmed to
 * eleven columns (`HomeDoneTask`): its per-item values must equal the old ones over the fifteen (`LegacyDoneTask`).
 */
class HomeDonePushdownGoldenTest {

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    @Test
    fun `the SQL-filtered read and the outside count equal the old read, team by team`() = testApplication {
        usePostgresTestcontainer()
        withHomeOpenWorld { world ->
            val svc = service()
            svc.reportTransaction {
                val ctx = svc.homeContext(world.now)
                assertEquals(HOME_DONE_FROM to HOME_DONE_TO, ctx.windows.both, "the world's edges are the request's windows")
                assertEquals(HOME_DONE_CURRENT_FROM, ctx.windows.current.fromMs)
                val all = legacyHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both)
                val active = fetchHomeTeams().map { it.id }.toSet()
                assertTrue(world.e !in active && world.allTeams.filter { it != world.e }.all { it in active })

                // One team at a time, every team of the world, and some multi-team picks.
                val picks = world.allTeams.map { setOf(it) } +
                    listOf(world.picked.toSet(), world.others.toSet(), setOf(world.a, world.d, world.e))
                for (pick in picks) {
                    val expected = all.filter { it.creditTeamId in pick }.sortedBy { it.issueId }.map { it.expectedHomeRow() }
                    val actual = fetchHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both, pick).sortedBy { it.issueId }
                    assertEquals(expected, actual, "done tasks of $pick")
                }
                assertEquals(emptyList(), fetchHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both, emptySet()))

                // The world's shape: both windows, both connections, the edges, all the credit kinds.
                val a = all.filter { it.creditTeamId == world.a }
                assertTrue(a.count { ctx.windows.current.contains(it.doneAt) } > 200)
                assertTrue(a.count { ctx.windows.previous.contains(it.doneAt) } > 200)
                assertEquals(setOf(world.connA, world.connB), a.map { it.connectionId }.toSet())
                assertTrue(all.none { it.doneAt == HOME_DONE_FROM - 1 || it.doneAt == HOME_DONE_TO }, "the outer edges are in no window")
                assertTrue(all.any { it.doneAt == HOME_DONE_FROM } && all.any { it.doneAt == HOME_DONE_TO - 1 })
                assertTrue(all.any { it.creditTeamId == null })
                assertTrue(all.any { it.creditTeamId == world.e } && all.any { it.creditTeamId == world.d })

                val outside = legacyDoneOutside(ctx, all, active)
                assertTrue(outside >= 25 + 30, "the soft-deleted team's 25 and the 30 team-less tasks, at least")
                val current = ctx.windows.current.let { it.fromMs to it.toMs }
                assertEquals(outside, countHomeDoneOutside(ctx.filter, ctx.connectionIds, current, active))
                // Fewer active teams (a picked-only set) moves the count; the count follows the set it is given.
                val fewer = setOf(world.a)
                assertEquals(legacyDoneOutside(ctx, all, fewer), countHomeDoneOutside(ctx.filter, ctx.connectionIds, current, fewer))
                assertEquals(0, countHomeDoneOutside(ctx.filter, emptyList(), current, active))
            }
        }
    }

    @Test
    fun `the trimmed row yields the old per-item values, row by row`() = testApplication {
        usePostgresTestcontainer()
        withHomeOpenWorld { world ->
            val svc = service()
            svc.reportTransaction {
                val ctx = svc.homeContext(world.now)
                val all = legacyHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both)
                val rows = fetchHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both, world.allTeams.toSet())
                    .associateBy { it.connectionId to it.issueId }
                // Every credited fact row against its trimmed twin: the old per-item values (built on AccuracyTask/DoneCycleTask over all
                // fifteen columns) must be the new ones (over the eleven the read folds them into), to the last bit of the doubles.
                var compared = 0
                // The world's own teams only: the shared container holds other classes' credited rows too (a full run, not alone).
                for (old in all.filter { it.creditTeamId in world.allTeams }) {
                    val row = rows.getValue(old.connectionId to old.issueId)
                    assertEquals(legacyEstimateMdOf(old), estimateMdOf(row), "estimate of ${old.issueId}")
                    assertEquals(legacyCycleDaysOf(old), cycleDaysOf(row), "cycle of ${old.issueId}")
                    assertEquals(legacyCostMdOf(old), costMdOf(row), "cost of ${old.issueId}")
                    assertEquals(legacyTaskRatioOf(old), taskRatioOf(row), "ratio of ${old.issueId}")
                    assertEquals(old.hasNoEstimate(), row.hasNoEstimate(), "no estimate of ${old.issueId}")
                    assertEquals(old.hasNoAssignee(), row.hasNoAssignee(), "no assignee of ${old.issueId}")
                    assertEquals(old.estimatedLate, row.estimatedLate, "late of ${old.issueId}")
                    compared++
                }
                assertTrue(compared > 600, "the whole credited world is compared: $compared")
                // Every branch of the folded columns occurs: worked and not (with `actual_md` above 0 either way), started and not, a cycle
                // of one column without the other.
                assertTrue(all.any { it.hasWorklogs && it.actualMd.signum() > 0 })
                assertTrue(all.any { !it.hasWorklogs && it.actualMd.signum() > 0 })
                assertTrue(all.any { it.startedAt == null && it.hasWorklogs && it.estimateAtStartMd != null })
                assertTrue(all.any { it.cycleMs != null && it.cycleWorkingDays == null })
                assertTrue(all.any { it.cycleMs == null && it.cycleWorkingDays != null })
            }
        }
    }

    @Test
    fun `the overview's done-task figures equal the old read at minSampleSize 3 and 1`() = testApplication {
        usePostgresTestcontainer()
        withHomeOpenWorld { world ->
            for (min in listOf(3, 1)) {
                withMinSampleSize(min) {
                    val svc = service()
                    for (picks in listOf(world.picked, world.others, listOf(world.d), listOf(world.a, world.g, world.c))) {
                        val overview = svc.home(world.now, picks)
                        svc.reportTransaction {
                            val ctx = svc.homeContext(world.now)
                            val all = legacyHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both)
                            val active = fetchHomeTeams().map { it.id }.toSet()
                            assertEquals(legacyDoneOutside(ctx, all, active), overview.unassigned.doneTasks, "unassigned, min $min")
                            assertEquals(picks.size, overview.teams.size)
                            for (team in overview.teams) {
                                val expected = legacyDoneFigures(ctx, all.filter { it.creditTeamId == team.teamId })
                                val label = "team ${team.teamId}, min $min"
                                assertEquals(expected.granularity, team.granularity, "granularity of $label")
                                assertEquals(expected.taskAccuracy, team.accuracy.tasks, "task accuracy of $label")
                                assertEquals(expected.throughput, team.throughput, "throughput of $label")
                                assertEquals(expected.tasksNoEstimate, team.quality.tasksNoEstimate, "no-estimate of $label")
                                assertEquals(expected.tasksUnassigned, team.quality.tasksUnassigned, "unassigned of $label")
                                assertEquals(expected.tasksEstimatedLate, team.quality.tasksEstimatedLate, "late of $label")
                            }
                        }
                    }
                    val a = svc.home(world.now, world.picked).teams.single { it.teamId == world.a }
                    assertTrue(a.throughput.deliveredItems.n > 200, "A delivers in the current window")
                    assertTrue(a.throughput.deliveredItems.previous!!.n > 200, "A delivers in the previous window")
                }
            }
        }
    }

    /** The drill rows of the old `HomeDrillContext` for [team], built from the old read's rows with the same per-item value functions. */
    private fun expectedRows(view: HomeDrillView, ctx: HomeContext, team: List<LegacyDoneTask>): Pair<List<DrillRow>, BigDecimal?> {
        val tasks = team.filter { ctx.windows.current.contains(it.doneAt) }
        fun row(t: LegacyDoneTask, figure: Double?) =
            DrillRow(t.connectionId, t.issueId, DrillItemKind.TASK, t.accountId, t.creditTeamId, t.doneAt, figure)
        fun findings(finding: (LegacyDoneTask) -> Boolean) = tasks.filter(finding).map { row(it, it.estimateAtDoneMd?.toDouble()) } to null
        return when (view) {
            HomeDrillView.TASK_ESTIMATE -> {
                val own = tasks.filter { legacyEstimateMdOf(it) != null }
                own.map { row(it, legacyEstimateMdOf(it)) } to own.map { it.expectedHomeRow() }.sumMdAtDone()
            }
            HomeDrillView.TASK_CYCLE -> tasks.mapNotNull { t -> legacyCycleDaysOf(t)?.let { row(t, it) } } to null
            HomeDrillView.TASK_COST -> {
                val own = tasks.filter { legacyCostMdOf(it) != null }
                own.map { row(it, legacyCostMdOf(it)) } to own.fold(BigDecimal.ZERO) { acc, t -> acc + t.actualMd }
            }
            HomeDrillView.TASK_PRED10, HomeDrillView.TASK_PRED25, HomeDrillView.TASK_PRED50 -> {
                val threshold = when (view) {
                    HomeDrillView.TASK_PRED10 -> PRED_10
                    HomeDrillView.TASK_PRED25 -> PRED_25
                    else -> PRED_50
                }
                val within = { ratio: Double -> predWithin(listOf(ratio), threshold) == 1 }
                tasks.mapNotNull { t -> legacyTaskRatioOf(t)?.takeIf(within)?.let { row(t, it) } } to null
            }
            HomeDrillView.QUALITY_TASKS_NO_ESTIMATE -> findings { it.hasNoEstimate() }
            HomeDrillView.QUALITY_TASKS_UNASSIGNED -> findings { it.hasNoAssignee() }
            HomeDrillView.QUALITY_TASKS_ESTIMATED_LATE -> findings { it.estimatedLate }
            else -> error("not a done-task view: $view")
        }
    }

    @Test
    fun `the done-task drills equal the old read for every team`() = testApplication {
        usePostgresTestcontainer()
        withHomeOpenWorld { world ->
            withMinSampleSize(3) {
                val svc = service()
                val views = HomeDrillView.entries.filter { it.name.startsWith("TASK_") || it.name.startsWith("QUALITY_TASKS_") }
                assertEquals(9, views.size, "the three granularity, three PRED and three finding views")
                for (team in listOf(world.a, world.b, world.c, world.d, world.g)) {
                    for (view in views) {
                        val expected = svc.reportTransaction {
                            val ctx = svc.homeContext(world.now)
                            val all = legacyHomeDoneTasks(ctx.filter, ctx.connectionIds, ctx.windows.both)
                            val (rows, sum) = expectedRows(view, ctx, all.filter { it.creditTeamId == team })
                            drillItems(ctx.meta(), ReportDrillBar(rows.size, sum?.md(), null, null), rows)
                        }
                        assertEquals(expected, svc.homeItems(view, team, world.now), "$view of $team")
                    }
                }
                val estimate = svc.homeItems(HomeDrillView.TASK_ESTIMATE, world.a, world.now)
                assertTrue(estimate.total > 100, "A's drill is a real list")
            }
        }
    }
}
