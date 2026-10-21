package ch.nokillswit

import ch.nokillswit.reports.CompareCell
import ch.nokillswit.reports.CompareRow
import ch.nokillswit.reports.ComparePeopleReport
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val FROM = "2024-01-01"
private const val TO = "2026-12-31"
private const val TOL = 1e-9

/** Plain JDBC: every row of [sql] (`?` bound to [params]) as a list of its columns. Independent of the report code and of Exposed. */
internal fun jdbcRows(sql: String, vararg params: Any?): List<List<Any?>> =
    PostgresTestSupport.connect().use { conn ->
        conn.prepareStatement(sql).use { st ->
            params.forEachIndexed { index, value -> st.setObject(index + 1, value) }
            st.executeQuery().use { rs ->
                val columns = rs.metaData.columnCount
                generateSequence { if (rs.next()) (1..columns).map { rs.getObject(it) } else null }.toList()
            }
        }
    }

private fun Any?.dbl(): Double? = (this as Number?)?.toDouble()

private fun Any?.int(): Int = (this as Number).toInt()

/**
 * Report 18 on the DERIVED stub fixture (read-only): the two busiest assignees, every cell compared to an INDEPENDENT SQL
 * re-derivation over `fact_task_delivery`, `fact_worklog` and the `item_stage` x `task_assignee` bridges (never the report's code):
 * the figures, their `n`, the histogram shares and the WIP rows.
 */
class ReportComparePeopleFixtureTest {

    private suspend fun HttpClient.compare(query: String): ComparePeopleReport {
        val response = get("/api/v1/reports/compare-people?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET compare-people?$query")
        return response.body()
    }

    private fun ComparePeopleReport.row(key: String): CompareRow = rows.single { it.key == key }

    private fun ComparePeopleReport.cell(key: String, person: Int): CompareCell = row(key).let { if (person == 0) it.a else it.b }

    private fun assertCell(label: String, value: Double?, n: Int, cell: CompareCell, hidden: Boolean = false) {
        if (value == null) {
            assertNull(cell.value, "$label value")
        } else {
            assertNotNull(cell.value, "$label value")
            assertTrue(abs(value - cell.value) < TOL, "$label: expected $value but was ${cell.value}")
        }
        assertEquals(n, cell.n, "$label n")
        assertEquals(hidden, cell.hidden, "$label hidden")
    }

    /** One row of aggregates over the person's DONE level-0 tasks in [bounds]; [extra] narrows them further. */
    private fun doneSql(select: String, account: String, connId: UInt, bounds: Pair<Long, Long>, extra: String = ""): List<Any?> = jdbcRows(
        """SELECT $select FROM metrics.fact_task_delivery
           WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ? AND assignee_account_id_at_done = ? $extra""",
        connId.toInt(), bounds.first, bounds.second, account,
    ).single()

    private fun busiestPair(connId: UInt, bounds: Pair<Long, Long>): List<String> = jdbcRows(
        """SELECT assignee_account_id_at_done, count(*) c FROM metrics.fact_task_delivery
           WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ?
             AND assignee_account_id_at_done IS NOT NULL AND cycle_ms IS NOT NULL
           GROUP BY 1 ORDER BY c DESC, 1 LIMIT 2""",
        connId.toInt(), bounds.first, bounds.second,
    ).map { it[0] as String }

    @Test
    fun `the task, cycle, accuracy, adjustment, blocked, worklog and quality figures equal an independent SQL re-derivation`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val bounds = windowBounds(reportZone(), FROM, TO)
            val (a, b) = busiestPair(connId, bounds)
            val client = seededClient("cmp-fixture", FlowUserRole.ADMIN)
            val report = client.compare("connectionId=$connId&from=$FROM&to=$TO&accountId=$a&accountId=$b&tailPercent=0")
            val min = report.meta.minSampleSize
            assertEquals(listOf(a, b), report.people.map { it.accountId })

            listOf(a, b).forEachIndexed { i, account ->
                fun sql(select: String, extra: String = "") = doneSql(select, account, connId, bounds, extra)
                val done = sql(
                    """count(*), coalesce(sum(coalesce(estimate_at_done_md, 0)), 0), count(*) FILTER (WHERE estimate_source = 'NONE'),
                       count(*) FILTER (WHERE has_worklogs), count(*) FILTER (WHERE blocked_working_days > 0),
                       count(*) FILTER (WHERE cycle_working_days > 0),
                       coalesce(sum(blocked_working_days) FILTER (WHERE cycle_working_days > 0), 0),
                       coalesce(sum(cycle_working_days) FILTER (WHERE cycle_working_days > 0), 0)""",
                )
                val items = done[0].int()
                assertTrue(items > 0, "$account must have delivered something")
                assertCell("$account deliveredItems", items.toDouble(), items, report.cell("deliveredItems", i))
                assertEquals(Math.round(done[1].dbl()!! * 100) / 100.0, report.cell("deliveredMd", i).value)
                assertCell("$account noEstimateShare", done[2].int().toDouble() / items, items, report.cell("noEstimateShare", i))
                assertCell("$account worklogCoverage", done[3].int().toDouble() / items, items, report.cell("worklogCoverage", i))
                assertCell("$account blockedItemsShare", done[4].int().toDouble() / items, items, report.cell("blockedItemsShare", i))
                val blockedShare = if (done[7].dbl()!! > 0.0) done[6].dbl()!! / done[7].dbl()!! else null
                assertCell("$account blockedShareOfCycle", blockedShare, done[5].int(), report.cell("blockedShareOfCycle", i))

                // Cycle: working / elapsed days p50 and p90 over the started tasks; flow efficiency and the reported-time ratio p50.
                val cycle = sql(
                    """count(cycle_ms), percentile_cont(0.5) WITHIN GROUP (ORDER BY cycle_working_days),
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY cycle_working_days),
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY cycle_ms / 86400000.0),
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY cycle_ms / 86400000.0)""",
                    "AND cycle_ms IS NOT NULL",
                )
                val measured = cycle[0].int()
                val hidden = measured < min
                fun shown(x: Any?) = if (hidden) null else x.dbl()
                assertCell("$account wd p50", shown(cycle[1]), measured, report.cell("cycleWorkingDaysP50", i), hidden)
                assertCell("$account wd p90", shown(cycle[2]), measured, report.cell("cycleWorkingDaysP90", i), hidden)
                assertCell("$account ed p50", shown(cycle[3]), measured, report.cell("cycleElapsedDaysP50", i), hidden)
                assertCell("$account ed p90", shown(cycle[4]), measured, report.cell("cycleElapsedDaysP90", i), hidden)
                val fe = sql("count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY active_ms::float8 / cycle_ms)", "AND cycle_ms > 0")
                val feN = fe[0].int()
                val feValue = if (feN < min) null else fe[1].dbl()
                assertCell("$account flow efficiency", feValue, feN, report.cell("flowEfficiencyP50", i), feN < min)
                val ratio = sql(
                    "count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY actual_md::float8 / cycle_working_days)",
                    "AND has_worklogs AND actual_md > 0 AND cycle_ms IS NOT NULL AND cycle_working_days > 0",
                )
                val ratioN = ratio[0].int()
                val ratioValue = if (ratioN < min) null else ratio[1].dbl()
                assertCell("$account ratio", ratioValue, ratioN, report.cell("reportedTimeRatioP50", i), ratioN < min)

                // PRED(25): the share of tasks whose |1 - 1/r| (r = actual / estimate) is within 0.25.
                for ((key, estimate, extra) in listOf(
                    Triple("accuracyAtStartPred25", "estimate_at_start_md", "AND started_at IS NOT NULL"),
                    Triple("accuracyAtDonePred25", "estimate_at_done_md", ""),
                )) {
                    val pred = sql(
                        "count(*), avg((abs(1 - $estimate / actual_md) <= 0.25 + 1e-9)::int)",
                        "AND has_worklogs AND actual_md > 0 AND $estimate > 0 $extra",
                    )
                    val n = pred[0].int()
                    assertCell("$account $key", if (n < min) null else pred[1].dbl(), n, report.cell(key, i), n < min)
                }
            }
            // Not vacuous: the busiest pair clears the minimum sample on the headline figures, so real values were compared.
            val headline = listOf(
                "cycleWorkingDaysP50", "cycleElapsedDaysP90", "flowEfficiencyP50", "reportedTimeRatioP50", "accuracyAtStartPred25",
            )
            for (key in headline) {
                assertNotNull(report.row(key).a.value, "$key A")
                assertNotNull(report.row(key).b.value, "$key B")
            }
            assertNotNull(report.capacity, "an admin sees the capacity block")
        }

    @Test
    fun `the adjustment, logging and histogram figures equal an independent re-derivation`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val bounds = windowBounds(reportZone(), FROM, TO)
        val (a, b) = busiestPair(connId, bounds)
        val client = seededClient("cmp-fixture-2", FlowUserRole.ADMIN)
        val report = client.compare("connectionId=$connId&from=$FROM&to=$TO&accountId=$b&accountId=$a")
        val min = report.meta.minSampleSize
        val people = listOf(b, a)

        people.forEachIndexed { i, account ->
            // A25: started in the window, attributed to the assignee at done (done) or the current assignee (open).
            val started = jdbcRows(
                """SELECT count(*), count(*) FILTER (WHERE estimate_changes_after_start > 0) FROM metrics.fact_task_delivery
                   WHERE connection_id = ? AND is_subtask = false AND started_at >= ? AND started_at < ?
                     AND (CASE WHEN done_at IS NULL THEN current_assignee_account_id ELSE assignee_account_id_at_done END) = ?""",
                connId.toInt(), bounds.first, bounds.second, account,
            ).single()
            val startedN = started[0].int()
            val shareHidden = startedN < min
            val share = if (shareHidden) null else started[1].int().toDouble() / startedN
            assertCell("$account adjustedShare", share, startedN, report.cell("adjustedShare", i), shareHidden)
            val change = doneSql(
                """count(*), percentile_cont(0.5)
                   WITHIN GROUP (ORDER BY (estimate_at_done_md - estimate_at_start_md) / estimate_at_start_md)""",
                account, connId, bounds,
                """AND NOT estimated_late AND estimate_at_start_md > 0 AND estimate_at_done_md > 0
                   AND estimate_at_done_md <> estimate_at_start_md""",
            )
            val changeN = change[0].int()
            val changeValue = if (changeN < min) null else change[1].dbl()
            assertCell("$account change", changeValue, changeN, report.cell("adjustmentMedianChange", i), changeN < min)

            val logs = jdbcRows(
                """SELECT count(*), coalesce(sum(md), 0), coalesce(sum(md) FILTER (WHERE foreign_work), 0), count(late_ms),
                          count(*) FILTER (WHERE late_ms > 86400000)
                   FROM metrics.fact_worklog WHERE connection_id = ? AND started_at >= ? AND started_at < ? AND author_account_id = ?""",
                connId.toInt(), bounds.first, bounds.second, account,
            ).single()
            val md = logs[1].dbl()!!
            assertEquals(Math.round(md * 100) / 100.0, report.cell("loggedMd", i).value)
            assertEquals(logs[0].int(), report.cell("loggedMd", i).n)
            assertCell("$account foreign", if (md > 0.0) logs[2].dbl()!! / md else null, logs[0].int(), report.cell("foreignShare", i))
            val measurable = logs[3].int()
            val late = if (measurable > 0) logs[4].int().toDouble() / measurable else null
            assertCell("$account late", late, measurable, report.cell("lateLoggingShare", i))
        }

        // The histograms: every measured task of both people is in exactly one bucket, split by person in request order.
        val names = displayNamesOf(people)
        fun values(account: String, column: String) = jdbcRows(
            """SELECT $column FROM metrics.fact_task_delivery
               WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ?
                 AND assignee_account_id_at_done = ? AND cycle_ms IS NOT NULL""",
            connId.toInt(), bounds.first, bounds.second, account,
        ).map { it[0].dbl()!! }
        for ((label, column, distribution) in listOf(
            Triple("working", "cycle_working_days", report.cycleTime.workingDays),
            Triple("elapsed", "cycle_ms / 86400000.0", report.cycleTime.elapsedDays),
        )) {
            val raw = people.map { Triple(it, names[it] ?: it, values(it, column)) }
            assertSharesInUnionEdges(label, raw, distribution)
            assertDistribution(label, raw.flatMap { it.third }, distribution, min)
        }
        // The delivered work mix adds up to each person's delivered md (within the rounding of each addend).
        people.forEachIndexed { i, account ->
            val md = report.cell("deliveredMd", i).value!!
            for (list in listOf(report.workMix.activityType, report.workMix.workCategory, report.workMix.domain)) {
                val total = list.sumOf { if (i == 0) it.a.md else it.b.md }
                assertTrue(abs(total - md) <= 0.005 * list.size, "$account: the mix adds up to the delivered md ($total vs $md)")
            }
        }
    }

    @Test
    fun `the WIP rows equal an independent per-day count over the stage and assignee bridges`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val calendar = DerivedStubFixture.metricsSettings().readCalendar()
        // The people who held the most work in progress on the most days, so the figures are not vacuously zero.
        val busiest = jdbcRows(
            """SELECT ta.account_id, count(*) c FROM metrics.task_assignee ta
               JOIN metrics.item_stage s ON s.connection_id = ta.connection_id AND s.issue_id = ta.issue_id AND s.stage = 'IN_PROGRESS'
                 AND s.valid_from < COALESCE(ta.valid_to, 9223372036854775807) AND ta.valid_from < COALESCE(s.valid_to, 9223372036854775807)
               WHERE ta.connection_id = ? AND ta.account_id IS NOT NULL GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 2""",
            connId.toInt(),
        ).map { it[0] as String }
        assertEquals(2, busiest.size, "the fixture must have two assignees who worked in progress")
        val (a, b) = busiest
        val client = seededClient("cmp-fixture-wip", FlowUserRole.ADMIN)
        val report = client.compare("connectionId=$connId&from=2025-01-01&to=2026-12-31&accountId=$a&accountId=$b")

        val firstDay = assertNotNull(report.wipDays.firstDay)
        val lastDay = assertNotNull(report.wipDays.lastDay)
        val days = jdbcRows(
            "SELECT day, is_working_day, day_end_ms FROM metrics.dim_date WHERE day >= ? AND day <= ? ORDER BY day", firstDay, lastDay,
        )
        val working = days.filter { it[1] as Boolean }.map { it[0] as String }
        assertEquals(working.size, report.wipDays.workingDays)
        val counts = jdbcRows(
            """SELECT d.day, ta.account_id, count(*) FROM metrics.dim_date d
               JOIN metrics.dim_task t ON t.connection_id = ? AND t.is_subtask = false
               JOIN metrics.task_assignee ta ON ta.connection_id = t.connection_id AND ta.issue_id = t.issue_id AND ta.account_id IN (?, ?)
                 AND ta.valid_from < d.day_end_ms AND (ta.valid_to IS NULL OR ta.valid_to >= d.day_end_ms)
               WHERE d.day >= ? AND d.day <= ?
                 AND EXISTS (SELECT 1 FROM metrics.item_stage s WHERE s.connection_id = t.connection_id AND s.issue_id = t.issue_id
                             AND s.stage IN ('IN_PROGRESS', 'WAITING', 'BLOCKED') AND s.valid_from < d.day_end_ms
                             AND (s.valid_to IS NULL OR s.valid_to >= d.day_end_ms))
               GROUP BY 1, 2""",
            connId.toInt(), a, b, firstDay, lastDay,
        ).associate { (it[0] as String to it[1] as String) to it[2].int() }

        val dayEnd = days.last()[2] as Long
        var anyWip = false
        listOf(a, b).forEachIndexed { i, account ->
            val avg = working.sumOf { counts[it to account] ?: 0 }.toDouble() / working.size
            assertCell("$account avgWip", avg, working.size, report.cell("avgWip", i))
            anyWip = anyWip || avg > 0.0

            val held = jdbcRows(
                """SELECT t.issue_id FROM metrics.dim_task t
                   JOIN metrics.task_assignee ta ON ta.connection_id = t.connection_id AND ta.issue_id = t.issue_id AND ta.account_id = ?
                     AND ta.valid_from < ? AND (ta.valid_to IS NULL OR ta.valid_to >= ?)
                   WHERE t.connection_id = ? AND t.is_subtask = false
                     AND EXISTS (SELECT 1 FROM metrics.item_stage s WHERE s.connection_id = t.connection_id AND s.issue_id = t.issue_id
                                 AND s.stage IN ('IN_PROGRESS', 'WAITING', 'BLOCKED') AND s.valid_from < ?
                                 AND (s.valid_to IS NULL OR s.valid_to >= ?))""",
                account, dayEnd, dayEnd, connId.toInt(), dayEnd, dayEnd,
            ).map { it[0] as Long }
            assertCell("$account wipAtEndItems", held.size.toDouble(), held.size, report.cell("wipAtEndItems", i))
            val ages = held.mapNotNull { issue ->
                val sql = "SELECT started_at FROM metrics.fact_task_delivery WHERE connection_id = ? AND issue_id = ?"
                jdbcRows(sql, connId.toInt(), issue).singleOrNull()?.get(0) as Long?
            }.map { if (dayEnd <= it) 0.0 else calendar.workingDaysBetween(it, dayEnd) }.sorted()
            val ageHidden = ages.size < report.meta.minSampleSize
            val median = if (ages.isEmpty() || ageHidden) null else quantile(ages, 0.5)
            assertCell("$account median age", median, ages.size, report.cell("wipAtEndMedianAgeWd", i), ageHidden)
        }
        assertTrue(report.row("wipAtEndItems").a.n + report.row("wipAtEndItems").b.n > 0, "someone holds work at the last listed day")
        assertTrue(anyWip, "the two busiest people must hold work in progress on some working day")
        assertNull(report.note?.takeIf { it.startsWith("Not derived yet") }, "the derived fixture is derived")
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
