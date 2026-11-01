package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.reports.PersonTrendReport
import ch.nokillswit.reports.PersonTrendRow
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.TREND_ROW_KEYS
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * `GET /api/v1/reports/person-trend/items` (Report 20's drill, `.claude/docs/reports.md` "Report 20"): for every point and every row
 * with a drill, the list reconciles with the point (`bar.count` is the point's `n`, or the share's numerator, as Compare people's drill
 * does with its cell) and IS `GET /compare-people/items` over the point's window with a second person as side B; then the 400 matrix.
 * The hand-built worlds (facts, worklogs, WIP bridges) and the derived stub fixture.
 */
class ReportPersonTrendDrillTest {

    private suspend fun HttpClient.trendItems(query: String, view: String, pointEnd: String): ReportDrillItems =
        drillItems("person-trend", "$query&view=$view&pointEnd=$pointEnd")

    private val byN = setOf(
        "deliveredMd", "deliveredItems", "cycleWorkingDaysP50", "cycleWorkingDaysP90", "flowEfficiencyP50", "accuracyAtStartPred25",
        "taskSizeP50", "mrsMerged", "mrCycleWorkingDaysP50", "reviewsGiven",
    )
    private val numeratorShares = setOf("blockedItemsShare", "lateLoggingShare", "noEstimateShare", "reopenedShare")

    /** The point's figure against its list, by the rules of `ReportComparePeopleDrillTest.reconcile`. */
    private fun assertReconciles(row: PersonTrendRow, index: Int, drill: ReportDrillItems, where: String) {
        val point = row.points[index]
        assertEquals(drill.bar.count, drill.total, "$where total")
        when (row.key) {
            in byN -> assertEquals(point.n, drill.bar.count, "$where: the drill lists the point's n items")
            in numeratorShares -> if (point.value != null) {
                assertEquals(Math.round(point.value * point.n).toInt(), drill.bar.count, "$where: the numerator's items")
            }
            "wipAtEndMedianAgeWd" -> assertEquals(point.n, drill.items.count { it.figure != null }, "$where: the aged items")
            "worklogCoverage" -> if (point.value != null) {
                assertEquals(point.n - Math.round(point.value * point.n).toInt(), drill.bar.count, "$where: the gap")
            }
            "avgWip" -> Unit // n counts working days, the list the distinct tasks: only the one-truth check below applies
            else -> error("unreconciled row ${row.key}")
        }
    }

    /**
     * Every point x every row with a drill (one request per point and DISTINCT view): the list reconciles with the point and equals the
     * Compare-people drill of the same person over `from=window.from&to=window.end`. Returns the (row, point) pairs checked and how
     * many of them listed something.
     */
    private suspend fun HttpClient.checkEveryPoint(trend: PersonTrendReport, person: String, other: String, slice: String): Pair<Int, Int> {
        var checked = 0
        var listed = 0
        val trendQuery = "$slice&from=${trend.meta.from}&to=${trend.meta.to}&accountId=$person"
        trend.windows.forEachIndexed { i, window ->
            val compareQuery = "$slice&from=${window.from}&to=${window.end}&accountId=$person&accountId=$other"
            for ((view, rows) in trend.rows.filter { it.drill != null }.groupBy { it.drill!!.name }) {
                val drill = trendItems(trendQuery, view, window.end)
                val want = drillItems("compare-people", "$compareQuery&view=$view&segmentAccountId=$person")
                val where = "$view @ ${window.end}"
                assertEquals(want.items, drill.items, "$where items")
                assertEquals(want.bar, drill.bar, "$where bar")
                assertEquals(want.total, drill.total, "$where total")
                assertEquals(want.truncated, drill.truncated, "$where truncated")
                assertEquals(window.from, drill.meta.from, "$where meta.from is the window")
                assertEquals(window.end, drill.meta.to, "$where meta.to is the window")
                assertTrue(drill.items.all { it.accountId == person }, "$where lists only the person's")
                for (row in rows) {
                    assertReconciles(row, i, drill, "${row.key} @ ${window.end}")
                    checked++
                    if (drill.bar.count > 0) listed++
                }
            }
        }
        return checked to listed
    }

    @Test
    fun `hand-built world - every point of every row reconciles with its list, which is Compare people's drill over the window`() =
        testApplication {
            usePostgresTestcontainer()
            withCompareWorld { w ->
                val client = seededClient("trend-drill-world", FlowUserRole.USER)
                val base = "connectionId=${w.connId}"
                withMinSampleSize(2) {
                    for ((person, other) in listOf(w.alice to w.bob, w.bob to w.alice)) {
                        val trend = client.personTrend("$base&from=2026-01-01&to=2026-01-29&accountId=$person")
                        val (checked, listed) = client.checkEveryPoint(trend, person, other, base)
                        assertEquals(3 * TREND_ROW_KEYS.size, checked, "every point of every row")
                        assertTrue(listed >= 15, "the world's items are really listed ($listed)")
                    }
                    // A hand-computed anchor: alice's four tasks are all done inside the last window (task 4 has no estimate).
                    val query = "$base&from=2026-01-01&to=2026-01-29&accountId=${w.alice}"
                    suspend fun keys(view: String, end: String) = client.trendItems(query, view, end).items.map { it.issueKey }.toSet()
                    assertEquals(setOf("1", "2", "3", "4"), keys("DELIVERED", "2026-01-29"))
                    assertEquals(setOf("1", "2"), keys("DELIVERED", "2026-01-15"))
                    assertEquals(emptySet(), keys("DELIVERED", "2026-01-01"), "nothing done by Jan 1")
                    assertEquals(setOf("4"), keys("NO_ESTIMATE", "2026-01-29"))
                }
            }
        }

    @Test
    fun `WIP world - the work-in-progress points list the distinct tasks and the aged tasks of the window`() = testApplication {
        usePostgresTestcontainer()
        withCompareWipWorld { w ->
            val client = seededClient("trend-drill-wip", FlowUserRole.USER)
            val base = "connectionId=${w.connId}"
            withMinSampleSize(1) {
                var held = 0
                for ((person, other) in listOf(w.a1 to w.a2, w.a2 to w.a1, w.a3 to w.a1)) {
                    val trend = client.personTrend("$base&from=2026-01-01&to=2026-01-29&accountId=$person")
                    val (checked, listed) = client.checkEveryPoint(trend, person, other, base)
                    assertEquals(3 * TREND_ROW_KEYS.size, checked)
                    assertTrue(listed > 0, "something is listed")
                    val query = "$base&from=2026-01-01&to=2026-01-29&accountId=$person"
                    held += trend.windows.sumOf { client.trendItems(query, "WIP_TASK_DAYS", it.end).items.size }
                }
                assertTrue(held > 0, "the world holds work in progress, so the WIP lists are not empty ($held)")
            }
        }
    }

    @Test
    fun `derived fixture - the busiest person's points equal Compare people's drill, WIP rows included`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val bounds = windowBounds(reportZone(), "2025-03-06", "2026-03-05")
        val (person, other) = jdbcRows(
            """SELECT assignee_account_id_at_done FROM metrics.fact_task_delivery
               WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ?
                 AND assignee_account_id_at_done IS NOT NULL AND cycle_ms IS NOT NULL
               GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 2""",
            connId.toInt(), bounds.first, bounds.second,
        ).map { it[0] as String }
        val client = seededClient("trend-drill-fixture", FlowUserRole.USER)
        val base = "connectionId=$connId"
        // Seven points: the 84 days that end on the fixture's DERIVE day, FULL windows included.
        val trend = client.personTrend("$base&from=2025-12-11&to=2026-03-05&accountId=$person")
        assertEquals(7, trend.windows.size)
        val (checked, listed) = client.checkEveryPoint(trend, person, other, base)
        assertEquals(7 * TREND_ROW_KEYS.size, checked)
        assertTrue(listed >= 20, "the person's real items are listed ($listed)")
    }

    @Test
    fun `the 400 matrix - the point, the view, the bucket and the parent's own rules`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val client = seededClient("trend-drill-400", FlowUserRole.USER)
            val q = "connectionId=${w.connId}&from=2026-01-01&to=2026-01-29&accountId=${w.alice}"
            val ok = "view=DELIVERED&pointEnd=2026-01-15"
            suspend fun status(query: String) = client.get("/api/v1/reports/person-trend/items?$query").status
            assertEquals(HttpStatusCode.OK, status("$q&$ok"))
            assertEquals(HttpStatusCode.OK, status("$q&view=CYCLE_WORKING_DAYS&pointEnd=2026-01-01"), "the range's first day is a point")
            assertEquals(HttpStatusCode.OK, status("$q&view=WIP_AT_END&pointEnd=2026-01-29"), "its last day is a point")
            val bad = mapOf(
                "no pointEnd" to "$q&view=DELIVERED",
                "a blank pointEnd" to "$q&view=DELIVERED&pointEnd=",
                "a pointEnd off the grid" to "$q&view=DELIVERED&pointEnd=2026-01-16",
                "a pointEnd before the range" to "$q&view=DELIVERED&pointEnd=2025-12-18",
                "a pointEnd after the range" to "$q&view=DELIVERED&pointEnd=2026-02-12",
                "a pointEnd that is no date" to "$q&view=DELIVERED&pointEnd=soon",
                "a repeated pointEnd" to "$q&$ok&pointEnd=2026-01-29",
                "no view" to "$q&pointEnd=2026-01-15",
                "an unknown view" to "$q&view=NOPE&pointEnd=2026-01-15",
                "a Compare-people view no row drills" to "$q&view=CYCLE_ELAPSED_DAYS&pointEnd=2026-01-15",
                "another view no row drills" to "$q&view=ADJUSTED&pointEnd=2026-01-15",
                "a bucketIndex" to "$q&$ok&bucketIndex=0",
                "a bucketIndex on a cycle view" to "$q&view=CYCLE_WORKING_DAYS&pointEnd=2026-01-15&bucketIndex=0",
                "a second person" to "$q&$ok&accountId=${w.bob}",
                "no person" to "connectionId=${w.connId}&from=2026-01-01&to=2026-01-29&$ok",
                "a team period" to "$q&teamId=1&$ok",
                "a sprint period" to "$q&lastSprints=2&$ok",
                "a span over the cap" to "connectionId=${w.connId}&accountId=${w.alice}&from=2020-01-01&to=2026-01-29&$ok",
                "an unknown connection" to "connectionId=999999&from=2026-01-01&to=2026-01-29&accountId=${w.alice}&$ok",
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
