package ch.nokillswit

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.HomeDrillView
import ch.nokillswit.reports.HomeTeam
import ch.nokillswit.reports.ReportDrillItem
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.home
import ch.nokillswit.reports.homeItems
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val TOL = 1e-9

/**
 * `GET /api/v1/reports/home/items` (`.claude/docs/reports.md` "Home overview"): the items behind one figure of one team. Every view
 * is graded against `HomeOracle.kt`'s independent predicates and against the overview's own figure (`bar.count` = its `n`, for a
 * PRED its numerator), on the derived fixture (read-only) and on the hand-built world, whose lists are also pinned by hand.
 */
class ReportHomeDrillTest {

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    /** The figure's `n`, or a PRED's numerator, the overview prints for [view] -- what the drill's `bar.count` must equal. */
    private fun HomeTeam.countOf(view: HomeDrillView): Int = when (view) {
        HomeDrillView.TASK_ESTIMATE -> granularity.estimateMd.n
        HomeDrillView.TASK_CYCLE -> granularity.cycleWorkingDays.n
        HomeDrillView.TASK_COST -> granularity.costMd.n
        HomeDrillView.TASK_PRED10 -> accuracy.tasks.pred10.numerator!!
        HomeDrillView.TASK_PRED25 -> accuracy.tasks.pred25.numerator!!
        HomeDrillView.TASK_PRED50 -> accuracy.tasks.pred50.numerator!!
        HomeDrillView.EPIC_PRED10 -> accuracy.epics.pred10.numerator!!
        HomeDrillView.EPIC_PRED25 -> accuracy.epics.pred25.numerator!!
        HomeDrillView.EPIC_PRED50 -> accuracy.epics.pred50.numerator!!
        HomeDrillView.WIP_TASKS -> wipTasks.count
        HomeDrillView.WIP_EPICS -> wipEpics.count
        HomeDrillView.QUALITY_TASKS_NO_ESTIMATE -> quality.tasksNoEstimate.n
        HomeDrillView.QUALITY_TASKS_UNASSIGNED -> quality.tasksUnassigned.n
        HomeDrillView.QUALITY_TASKS_ESTIMATED_LATE -> quality.tasksEstimatedLate.n
        HomeDrillView.QUALITY_EPICS_NO_ESTIMATE -> quality.epicsNoEstimate.n
        HomeDrillView.QUALITY_EPICS_NO_DATES -> quality.epicsNoDates.n
        HomeDrillView.QUALITY_EPICS_ESTIMATE_GAP -> quality.epicsEstimateGap.n
        // The derived fixture has no GitLab source: no code section and an empty list (ReportHomeCodeTest has the figures).
        HomeDrillView.CODE_MERGED -> code?.merged?.n ?: 0
        HomeDrillView.CODE_OPEN -> code?.openCount?.n ?: 0
        HomeDrillView.CODE_MERGED_WITHOUT_REVIEW -> code?.mergedWithoutReview?.numerator ?: 0
    }

    /** What the drill labels an issue with: its `norm.work_items` key, an epic's `dim_epic` key over it, else the id (no norm twin). */
    private fun keyOf(connIds: List<UInt>): (Long) -> String {
        val ids = connIds.joinToString(",") { it.toInt().toString() }
        fun keysOf(table: String) = jdbcRows("SELECT issue_id, issue_key FROM $table WHERE connection_id IN ($ids)")
            .associate { (it[0] as Number).toLong() to it[1] as String }
        val keys = keysOf("norm.work_items") + keysOf("metrics.dim_epic")
        return { id -> keys[id] ?: id.toString() }
    }

    /** Every view of [team] at [nowMs]: the list equals the oracle's set and figures, and its bar the overview's figure. */
    private suspend fun assertEveryViewReconciles(
        label: String,
        team: UInt,
        connIds: List<UInt>,
        nowMs: Long,
        zone: ZoneId,
        tolerance: BigDecimal,
    ): Int {
        val svc = service()
        val overview = svc.home(nowMs, listOf(team)).teams.single { it.teamId == team }
        val (cur, _) = oracleWindows(zone, nowMs)
        val tasks = oracleDoneTasks(connIds, team, cur)
        val epics = oracleEpics(connIds, team)
        val openTasks = oracleOpenTasks(connIds, team)
        val openEpics = oracleOpenEpics(epics)
        val key = keyOf(connIds)
        var nonEmpty = 0
        for (view in HomeDrillView.entries) {
            val what = "$label $view"
            val drill = svc.homeItems(view, team, nowMs)
            if (drill.bar.count > 0) nonEmpty++
            val expected = oracleViewItems(view, tasks, epics, openTasks, openEpics, cur, tolerance) {
                oracleWorkingDays(zone, it.startedAt, nowMs)
            }
            assertEquals(overview.countOf(view), drill.bar.count, "$what: bar.count == the overview figure's n (a PRED's numerator)")
            assertEquals(expected.size, drill.bar.count, "$what: bar.count == the independent count")
            assertEquals(drill.bar.count, drill.total, "$what: total == bar.count")
            assertEquals(drill.items.size, minOf(drill.total, 500), "$what: listed up to the cap")
            if (!drill.truncated) {
                assertEquals(expected.keys.map { key(it) }.sorted(), drill.items.map { it.issueKey }.sorted(), "$what: the same items")
                val byKey = expected.entries.associate { key(it.key) to it.value }
                for (item in drill.items) {
                    val want = byKey.getValue(item.issueKey)
                    if (want == null) {
                        assertNull(item.figure, "$what ${item.issueKey} figure")
                    } else {
                        assertTrue(abs(want - assertNotNull(item.figure)) < TOL, "$what ${item.issueKey}")
                    }
                    assertEquals(team, item.teamId, "$what ${item.issueKey} team")
                }
            }
            val epicView = view.name.startsWith("EPIC_") || view == HomeDrillView.WIP_EPICS || view.name.startsWith("QUALITY_EPICS")
            assertTrue(drill.items.all { it.itemKind.name == if (epicView) "EPIC" else "TASK" }, "$what item kind")
            val sums = view == HomeDrillView.TASK_ESTIMATE || view == HomeDrillView.TASK_COST
            if (sums) {
                val sum = expected.values.sumOf { it ?: 0.0 }
                assertEquals(sum, assertNotNull(drill.bar.sumMd), 0.005 + expected.size * 1e-6, "$what: bar.sumMd == Σ figure")
            } else {
                assertNull(drill.bar.sumMd, "$what: no MD sum")
            }
            assertTrue(drill.bar.from == null && drill.bar.to == null)
            val ordered = drill.items.sortedWith(
                compareByDescending<ReportDrillItem, Double?>(nullsFirst()) { it.figure }
                    .thenByDescending(nullsFirst()) { it.anchorAt }.thenBy { it.issueKey },
            )
            assertEquals(ordered, drill.items, "$what: order")
        }
        return nonEmpty
    }

    @Test
    fun `every view of every team reconciles with the overview figure on the derived fixture`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val zone = reportZone()
        val team = oracleFixtureTeam(connId)
        for (now in listOf(DerivedStubFixture.PINNED_NOW, homeAt("2025-11-30T12:00:00+01:00"))) {
            val nonEmpty = assertEveryViewReconciles("fixture@$now", team, listOf(connId), now, zone, BigDecimal("0.25"))
            assertTrue(nonEmpty >= 7, "the fixture must exercise most views, not only empty lists (was $nonEmpty at $now)")
        }
    }

    private fun ReportDrillItems.pairs(): List<Pair<String, Double?>> = items.map { it.issueKey to it.figure }

    @Test
    fun `hand-built rows pin each view's membership and figure, and the 400 matrix`() = testApplication {
        usePostgresTestcontainer()
        withHomeScenario { world ->
            val svc = service()
            val now = HomeScenario.NOW
            suspend fun items(view: HomeDrillView) = svc.homeItems(view, world.team, now)
            suspend fun keys(view: HomeDrillView) = items(view).items.map { it.issueKey }.sorted()

            val nonEmpty = assertEveryViewReconciles("hand-built", world.team, listOf(world.connId), now, reportZone(), BigDecimal("0.25"))
            assertEquals(16, nonEmpty, "every view but EPIC_PRED10 has items in the hand-built world")

            // Granularity: the tasks the medians were taken over.
            val estimate = items(HomeDrillView.TASK_ESTIMATE)
            assertEquals(listOf("104" to 5.0, "103" to 3.0, "102" to 2.0, "101" to 1.0), estimate.pairs())
            assertEquals(11.0, estimate.bar.sumMd)
            assertEquals(listOf("101", "102", "103", "104", "105"), keys(HomeDrillView.TASK_CYCLE))
            val cost = items(HomeDrillView.TASK_COST)
            assertEquals(listOf("104" to 5.5, "103" to 3.2, "102" to 3.0, "101" to 1.0), cost.pairs())
            assertEquals(12.7, cost.bar.sumMd)

            // PRED: only the items within the share; 103 sits exactly on 25 %.
            assertEquals(listOf("101", "104"), keys(HomeDrillView.TASK_PRED10))
            assertEquals(listOf("101", "103", "104"), keys(HomeDrillView.TASK_PRED25))
            assertEquals(listOf("101", "102", "103", "104"), keys(HomeDrillView.TASK_PRED50))
            assertEquals(listOf("HE-203"), keys(HomeDrillView.EPIC_PRED25))
            assertEquals(emptyList(), keys(HomeDrillView.EPIC_PRED10))
            assertEquals(1.2, items(HomeDrillView.EPIC_PRED50).items.single().figure!!, TOL)

            // WIP: ages in working days to the clock, anchored at the start.
            val wip = items(HomeDrillView.WIP_TASKS)
            assertEquals(listOf("133" to 7.0, "132" to 3.0, "131" to 2.0, "134" to 2.0, "139" to 0.5), wip.pairs())
            assertEquals(homeAt("2026-02-09T00:00:00+01:00"), wip.items.first().anchorAt)
            assertEquals(listOf("HE-201", "HE-202"), keys(HomeDrillView.WIP_EPICS))

            // Findings: the figure is the estimate; for the gap the signed gap in MD.
            assertEquals(listOf("105"), keys(HomeDrillView.QUALITY_TASKS_NO_ESTIMATE))
            assertEquals(listOf("105"), keys(HomeDrillView.QUALITY_TASKS_UNASSIGNED))
            assertEquals(listOf("103" to 3.0), items(HomeDrillView.QUALITY_TASKS_ESTIMATED_LATE).pairs())
            assertEquals(listOf("HE-201" to null), items(HomeDrillView.QUALITY_EPICS_NO_ESTIMATE).pairs())
            assertEquals(listOf("HE-202" to 4.0), items(HomeDrillView.QUALITY_EPICS_NO_DATES).pairs())
            assertEquals(listOf("HE-202" to 4.0), items(HomeDrillView.QUALITY_EPICS_ESTIMATE_GAP).pairs())

            // The other team's tasks and the team-less ones never leak into this team's lists.
            val foreign = setOf("121", "122", "137", "138", "HE-206", "HE-207")
            assertTrue(HomeDrillView.entries.all { v -> items(v).items.none { it.issueKey in foreign } })

            // The 400 matrix (the real clock is irrelevant: the checks come first), and a 200 for a plain user.
            val retired = TestTeams.seed(SyncedStubFixture.unique("chome-retired"))
            TestTeams.service.delete(retired)
            val client = seededClient("home-drill", FlowUserRole.USER)
            suspend fun status(query: String) = client.get("/api/v1/reports/home/items$query").status
            assertEquals(HttpStatusCode.OK, status("?view=TASK_COST&teamId=${world.team}"))
            val ok = client.get("/api/v1/reports/home/items?view=WIP_TASKS&teamId=${world.team}").body<ReportDrillItems>()
            assertEquals(ok.bar.count, ok.total)
            val bad = mapOf(
                "no parameters" to "",
                "unknown view" to "?view=NOPE&teamId=${world.team}",
                "missing view" to "?teamId=${world.team}",
                "missing teamId" to "?view=TASK_COST",
                "non-numeric teamId" to "?view=TASK_COST&teamId=abc",
                "teamId 0 (Home lists teams only)" to "?view=TASK_COST&teamId=0",
                "unknown team" to "?view=TASK_COST&teamId=987654321",
                "inactive team" to "?view=TASK_COST&teamId=$retired",
                "a view of another report" to "?view=DELIVERED&teamId=${world.team}",
                "repeated view" to "?view=TASK_COST&view=TASK_CYCLE&teamId=${world.team}",
            )
            for ((what, query) in bad) assertEquals(HttpStatusCode.BadRequest, status(query), what)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
