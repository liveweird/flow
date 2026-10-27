package ch.nokillswit

import ch.nokillswit.reports.ComparePeopleReport
import ch.nokillswit.reports.DrillItemKind
import ch.nokillswit.reports.Distribution
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val EDGE = 1e-9

/**
 * `GET /api/v1/reports/compare-people/items` (Report 18's drill): every view of every row on the hand-built world
 * (`ComparePeopleFixtures.kt`) against the hand-computed item sets, the reconciliation of every cell and every histogram bar of the
 * report with its drill (on the hand-built world and on the derived fixture), and the 400 matrix.
 */
class ReportComparePeopleDrillTest {

    private suspend fun HttpClient.report(query: String): ComparePeopleReport {
        val response = get("/api/v1/reports/compare-people?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET compare-people?$query")
        return response.body()
    }

    private suspend fun HttpClient.items(query: String, view: String, account: String, extra: String = ""): ReportDrillItems =
        drillItems("compare-people", "$query&view=$view&segmentAccountId=$account$extra")

    private fun ReportDrillItems.keys(): Set<String> = items.map { it.issueKey }.toSet()

    @Test
    fun `every view lists exactly the hand-computed items of the person, with the figure and the bar`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val client = seededClient("cmp-drill-views", FlowUserRole.USER)
            val q = w.query()
            withMinSampleSize(2) {
                suspend fun check(account: String, view: String, keys: Set<String>, sumMd: Double? = null) {
                    val drill = client.items(q, view, account)
                    assertEquals(keys, drill.keys(), "$account $view")
                    assertEquals(keys.size, drill.bar.count, "$account $view bar.count")
                    assertEquals(drill.bar.count, drill.total)
                    assertEquals(sumMd, drill.bar.sumMd, "$account $view sumMd")
                    assertNull(drill.bar.from); assertNull(drill.bar.to)
                    assertTrue(drill.items.all { it.accountId == account }, "$account $view lists only its person")
                    assertTrue(!drill.truncated)
                }
                fun tasks(vararg ids: Int) = ids.map { it.toString() }.toSet()
                fun logs(vararg ids: Int) = ids.map { it.toString() }.toSet()

                check(w.alice, "DELIVERED", tasks(1, 2, 3, 4), sumMd = 7.0)
                check(w.alice, "NO_ESTIMATE", tasks(4))
                check(w.alice, "TASK_SIZE", tasks(1, 2, 3))
                check(w.alice, "REOPENED", emptySet())
                check(w.alice, "CYCLE_WORKING_DAYS", tasks(1, 2, 3, 4))
                check(w.alice, "CYCLE_ELAPSED_DAYS", tasks(1, 2, 3, 4))
                check(w.alice, "REPORTED_TIME_RATIO", tasks(1, 2, 3, 4))
                check(w.alice, "FLOW_EFFICIENCY", tasks(1, 2, 3, 4))
                check(w.alice, "ACCURACY_AT_START", tasks(1, 2, 3))
                check(w.alice, "ACCURACY_AT_DONE", tasks(1, 2, 3))
                check(w.alice, "ADJUSTED", tasks(3, 5), null)
                check(w.alice, "ADJUSTMENT_CHANGE", tasks(3))
                check(w.alice, "BLOCKED", tasks(2))
                check(w.alice, "NO_WORKLOGS", emptySet())
                check(w.alice, "LOGGED", logs(1, 2, 3), sumMd = 4.0)
                check(w.alice, "FOREIGN", logs(2), sumMd = 2.0)
                check(w.alice, "LATE", logs(1), sumMd = 1.0)
                check(w.alice, "WIP_TASK_DAYS", emptySet())
                check(w.alice, "WIP_AT_END", emptySet())

                check(w.bob, "DELIVERED", tasks(11, 12, 13, 14), sumMd = 8.0)
                check(w.bob, "NO_ESTIMATE", emptySet())
                check(w.bob, "TASK_SIZE", tasks(11, 12, 13))
                check(w.bob, "REOPENED", tasks(11, 12))
                check(w.bob, "CYCLE_WORKING_DAYS", tasks(11, 12, 13))
                check(w.bob, "CYCLE_ELAPSED_DAYS", tasks(11, 12, 13))
                check(w.bob, "REPORTED_TIME_RATIO", tasks(11, 12, 13))
                check(w.bob, "FLOW_EFFICIENCY", tasks(11, 12, 13))
                check(w.bob, "ACCURACY_AT_START", tasks(11, 12, 13))
                check(w.bob, "ACCURACY_AT_DONE", tasks(11, 12, 13))
                check(w.bob, "ADJUSTED", tasks(13))
                check(w.bob, "ADJUSTMENT_CHANGE", tasks(13))
                check(w.bob, "BLOCKED", tasks(11, 12))
                check(w.bob, "NO_WORKLOGS", tasks(14))
                check(w.bob, "LOGGED", logs(5, 6, 7, 8), sumMd = 4.25)
                check(w.bob, "FOREIGN", logs(7), sumMd = 1.0)
                check(w.bob, "LATE", logs(5, 7, 8), sumMd = 3.75)

                // The figures: per-item values in the row's unit, largest first; a worklog row is a WORKLOG at its started_at.
                val delivered = client.items(q, "DELIVERED", w.alice)
                assertEquals(listOf(3.0, 2.0, 2.0, null), delivered.items.map { it.figure }, "estimate at done, the unestimated task last")
                assertEquals(DrillItemKind.TASK, delivered.items.first().itemKind)
                assertEquals(listOf(6.0, 4.0, 2.0, 1.0), client.items(q, "CYCLE_WORKING_DAYS", w.alice).items.map { it.figure })
                assertEquals(listOf(8.0, 6.0, 3.0, 2.0), client.items(q, "CYCLE_ELAPSED_DAYS", w.alice).items.map { it.figure })
                assertEquals(listOf(2.0, 1.5, 1.0), client.items(q, "ACCURACY_AT_START", w.alice).items.map { it.figure })
                // The estimate at start in MD (Report 19's rule), largest first; the reopened tasks' figure is their reopen count.
                assertEquals(listOf(3.0, 2.0, 1.0), client.items(q, "TASK_SIZE", w.alice).items.map { it.figure })
                assertEquals(listOf(4.0, 2.0, 1.0), client.items(q, "TASK_SIZE", w.bob).items.map { it.figure })
                assertEquals(listOf(2.0, 1.0), client.items(q, "REOPENED", w.bob).items.map { it.figure })
                assertEquals(listOf(1.0), client.items(q, "ADJUSTMENT_CHANGE", w.alice).items.map { it.figure })
                assertEquals(listOf(-0.5), client.items(q, "ADJUSTMENT_CHANGE", w.bob).items.map { it.figure })
                assertEquals(listOf(1.5), client.items(q, "BLOCKED", w.alice).items.map { it.figure })
                val logged = client.items(q, "LOGGED", w.bob)
                assertEquals(listOf(1.5, 1.25, 1.0, 0.5), logged.items.map { it.figure })
                assertTrue(logged.items.all { it.itemKind == DrillItemKind.WORKLOG })
                assertEquals(noonUtc("2026-01-08"), logged.items.first().anchorAt, "a worklog is anchored on its started_at")

                // The same slices as the report: only the Bug tasks.
                val bugs = client.items("$q&activityType=Bug", "DELIVERED", w.alice)
                assertEquals(tasks(3), bugs.keys())
                assertEquals(2.0, bugs.bar.sumMd)
            }
        }
    }

    @Test
    fun `every cell of every row reconciles with its drill, and every histogram bar with its per-person count`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val client = seededClient("cmp-drill-reconcile", FlowUserRole.ADMIN)
            val q = w.query()
            val report = withMinSampleSize(2) { client.report(q) }
            withMinSampleSize(2) { reconcile(client, q, report) }
        }
    }

    @Test
    fun `the derived fixture - every cell and every histogram bar of the two busiest people reconciles with its drill`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val bounds = windowBounds(reportZone(), "2024-01-01", "2026-12-31")
        val (a, b) = jdbcRows(
            "SELECT assignee_account_id_at_done FROM metrics.fact_task_delivery WHERE connection_id = ? AND is_subtask = false " +
                "AND done_at >= ? AND done_at < ? AND assignee_account_id_at_done IS NOT NULL AND cycle_ms IS NOT NULL " +
                "GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 2",
            connId.toInt(), bounds.first, bounds.second,
        ).map { it[0] as String }
        val client = seededClient("cmp-drill-fixture", FlowUserRole.ADMIN)
        val q = "connectionId=$connId&from=2024-01-01&to=2026-12-31&accountId=$a&accountId=$b"
        reconcile(client, q, client.report(q))
    }

    /** The reconciliation gate: each cell's drill is the cell's own set, each histogram bucket's segments are the `byUser` counts. */
    private suspend fun reconcile(client: HttpClient, q: String, report: ComparePeopleReport) {
        val who = report.people.map { it.accountId }
        // Cell n == drill count for the rows whose figure is a distribution or a count; the numerator for a share.
        val byN = setOf(
            "deliveredMd", "deliveredItems", "cycleWorkingDaysP50", "cycleWorkingDaysP90", "cycleElapsedDaysP50", "cycleElapsedDaysP90",
            "flowEfficiencyP50", "reportedTimeRatioP50", "accuracyAtStartPred25", "accuracyAtDonePred25", "adjustmentMedianChange",
            "wipAtEndItems", "loggedMd", "taskSizeP50",
        )
        var checked = 0
        for (row in report.rows) {
            val view = row.drill ?: continue
            for ((index, account) in who.withIndex()) {
                val cell = if (index == 0) row.a else row.b
                val drill = client.items(q, view.name, account)
                val label = "${row.key} $account"
                assertEquals(drill.bar.count, drill.total, "$label total")
                if (row.key in byN) {
                    assertEquals(cell.n, drill.bar.count, "$label: the drill lists the cell's n items")
                } else if (row.key in setOf("blockedItemsShare", "adjustedShare", "lateLoggingShare", "noEstimateShare", "reopenedShare")) {
                    val numerator = (cell.value!! * cell.n).let { Math.round(it).toInt() }
                    assertEquals(numerator, drill.bar.count, "$label: the numerator's items")
                } else if (row.key == "wipAtEndMedianAgeWd") {
                    // The age's n counts the held tasks with a started_at; the drill lists every held task, a null figure for the others.
                    assertEquals(cell.n, drill.items.count { it.figure != null }, "$label: the aged items")
                } else if (row.key == "blockedShareOfCycle") {
                    // The drill lists the blocked tasks; the share is over the tasks with a cycle: positive exactly when some are listed.
                    assertEquals((cell.value ?: 0.0) > 0.0, drill.bar.count > 0, "$label: blocked tasks listed iff the share is positive")
                    assertTrue(drill.bar.count <= cell.n, "$label: no more blocked tasks than tasks with a cycle")
                } else if (row.key == "worklogCoverage") {
                    assertEquals(cell.n - Math.round(cell.value!! * cell.n).toInt(), drill.bar.count, "$label: the gap")
                } else if (row.key == "avgWip") {
                    // The sum of the days each task was held on a working day is the average's numerator.
                    val days = drill.items.sumOf { it.figure!! }
                    val workingDays = report.wipDays.workingDays
                    if (workingDays > 0 && cell.value != null) {
                        assertEquals(Math.round(cell.value * workingDays).toDouble(), days, "$label: Σ task-days")
                    }
                }
                checked++
            }
        }
        assertTrue(checked > 20, "every row with a drill was reconciled ($checked cells)")

        val histograms = listOf("CYCLE_WORKING_DAYS" to report.cycleTime.workingDays, "CYCLE_ELAPSED_DAYS" to report.cycleTime.elapsedDays)
        for ((view, distribution) in histograms) {
            assertTrue(!distribution.hidden, "$view must draw a histogram")
            assertHistogramBars(client, q, view, distribution, who)
        }
    }

    private suspend fun assertHistogramBars(client: HttpClient, q: String, view: String, distribution: Distribution, who: List<String>) {
        distribution.histogram.forEachIndexed { index, bucket ->
            var sum = 0
            for ((person, account) in who.withIndex()) {
                val drill = client.items(q, view, account, "&bucketIndex=$index")
                val where = "$view bucket $index $account"
                assertEquals(distribution.byUser[person].counts[index].toInt(), drill.bar.count, "$where == byUser count")
                assertEquals(bucket.from, drill.bar.from, "$where from")
                assertEquals(bucket.to, drill.bar.to, "$where to")
                assertNull(drill.bar.sumMd)
                assertTrue(drill.items.all { it.accountId == account }, "$where lists only its person")
                for (item in drill.items) {
                    val value = assertNotNull(item.figure)
                    val top = bucket.to
                    val inBucket = if (top == null) value > bucket.from else value >= bucket.from - EDGE && value <= top + EDGE
                    assertTrue(inBucket, "$where figure $value")
                }
                sum += drill.bar.count
            }
            assertEquals(bucket.count.toInt(), sum, "$view bucket $index: the two segments partition the bar")
        }
        // Without a bucket the cell's whole measured set: its count is the person's n of the distribution.
        for ((person, account) in who.withIndex()) {
            val whole = client.items(q, view, account).bar.count
            assertEquals(distribution.byUser[person].counts.sum().toInt(), whole, "$view $account whole set")
        }
    }

    @Test
    fun `the 400 matrix - the view, the segment, the bucket and the parent's own rules`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val client = seededClient("cmp-drill-400", FlowUserRole.USER)
            val q = w.query()
            val ok = "view=CYCLE_WORKING_DAYS&segmentAccountId=${w.alice}"
            suspend fun status(query: String) = client.get("/api/v1/reports/compare-people/items?$query").status
            withMinSampleSize(2) {
                assertEquals(HttpStatusCode.OK, status("$q&$ok"))
                assertEquals(HttpStatusCode.OK, status("$q&$ok&bucketIndex=0"))
                val bad = mapOf(
                    "no view" to "$q&segmentAccountId=${w.alice}",
                    "an unknown view" to "$q&view=NOPE&segmentAccountId=${w.alice}",
                    "no segment" to "$q&view=DELIVERED",
                    "a segment outside the picks" to "$q&view=DELIVERED&segmentAccountId=somebody-else",
                    "a bucket on a view without a histogram" to "$q&view=DELIVERED&segmentAccountId=${w.alice}&bucketIndex=0",
                    "a bucket outside the histogram" to "$q&$ok&bucketIndex=99",
                    "a negative bucket" to "$q&$ok&bucketIndex=-1",
                    "a bucket that is not a number" to "$q&$ok&bucketIndex=x",
                    "one person only" to "connectionId=${w.connId}&accountId=${w.alice}&$ok",
                    "a team period" to "$q&teamId=1&$ok",
                    "a repeated segment" to "$q&$ok&segmentAccountId=${w.bob}",
                )
                for ((what, query) in bad) assertEquals(HttpStatusCode.BadRequest, status(query), what)
            }
            // A bar of a distribution under the minimum sample size does not exist (the union has 4 + 3 = 7 measured cycles).
            withMinSampleSize(8) { assertEquals(HttpStatusCode.BadRequest, status("$q&$ok&bucketIndex=0"), "a hidden distribution") }
            // A cell hidden under the minimum still lists its items (the cell's n is public).
            withMinSampleSize(8) { assertEquals(HttpStatusCode.OK, status("$q&$ok"), "the whole set of a hidden cell") }
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
