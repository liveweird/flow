package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.reports.PersonTrendReport
import ch.nokillswit.reports.TREND_ROW_KEYS
import ch.nokillswit.reports.TrendCoverage
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val FROM = "2025-03-06"
private const val TO = "2026-03-05"

/**
 * Report 20 on the DERIVED stub fixture (read-only): the busiest assignee over a year (27 windows, 14 rows each), every point compared
 * with `GET /compare-people` over its window -- done facts, worklogs AND the WIP read at query time -- so slicing ONE wide fetch is proven
 * to be the per-window fetch on real data; the coverage labels against the fixture's known DERIVE day and backfill start; the cue
 * recomputed from the points; and the `in-test person-trend-365d` timing line (`build-times.md` "In-test targets").
 */
class ReportPersonTrendFixtureTest {

    /** The assignees with the most done tasks in the year, busiest first (the pair's second is the other side of each comparison). */
    private suspend fun busiestPair(connId: UInt): List<String> {
        val bounds = windowBounds(reportZone(), FROM, TO)
        return jdbcRows(
            """SELECT assignee_account_id_at_done, count(*) c FROM metrics.fact_task_delivery
               WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ?
                 AND assignee_account_id_at_done IS NOT NULL AND cycle_ms IS NOT NULL
               GROUP BY 1 ORDER BY c DESC, 1 LIMIT 2""",
            connId.toInt(), bounds.first, bounds.second,
        ).map { it[0] as String }
    }

    private fun PersonTrendReport.values(key: String): List<Double?> = rows.single { it.key == key }.points.map { it.value }

    @Test
    fun `the busiest person - every point of a year equals Compare people over its window, the WIP rows included`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val (person, other) = busiestPair(connId)
        val client = seededClient("trend-fixture", FlowUserRole.USER)
        val base = "connectionId=$connId"
        val query = "$base&from=$FROM&to=$TO&accountId=$person"

        client.personTrend(query) // warm: the first request pays the JIT and the pool, not the figure below
        val startedAt = System.nanoTime()
        val trend = client.personTrend(query)
        // An in-test target (build-times.md), not a gate: a plain line in the suite's <system-out>, never a `fixture-cost` marker (that one
        // is subtracted from the class weights).
        println("in-test\tperson-trend-365d\t${(System.nanoTime() - startedAt) / 1_000_000L}")

        assertEquals(27, trend.windows.size)
        assertEquals(TO, trend.windows.last().end)
        assertEquals(FROM, trend.windows.first().end, "364 days: 26 steps back from `to` land on `from`")
        assertEquals(TREND_ROW_KEYS, trend.rows.map { it.key })
        val count = client.assertPersonTrendIsComparePeople(trend, person, other, base)
        assertEquals(27 * 14, count.cells)
        assertTrue(count.mixPoints > 0)

        // Not vacuous: real figures in several windows, WIP included.
        val delivering = trend.rows.single { it.key == "deliveredItems" }.points.count { it.n > 0 }
        assertTrue(delivering >= 3, "the person delivered in several windows ($delivering)")
        assertTrue(trend.values("avgWip").any { (it ?: 0.0) > 0.0 }, "the person held work in progress")
        assertTrue(trend.values("cycleWorkingDaysP50").count { it != null } >= 2, "enough cycle times to show a value twice")
        assertTrue(trend.workMix.activityType.isNotEmpty() && trend.workMix.workCategory.isNotEmpty())
    }

    @Test
    fun `coverage follows the fixture's DERIVE day and backfill start, and the cue is recomputable from the points`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val person = busiestPair(connId).first()
        val client = seededClient("trend-fixture-cue", FlowUserRole.USER)
        val trend = client.personTrend("connectionId=$connId&from=$FROM&to=$TO&accountId=$person")

        // DERIVE ran on 2026-03-05 and the backfill starts on 2025-09-01 (SyncedStubFixture.BACKFILL_FROM): a window is FULL when it
        // starts on or after the backfill day and ends on or before the derived day.
        val byEnd = trend.windows.associate { it.end to it.coverage }
        assertEquals(TrendCoverage.FULL, byEnd["2026-03-05"], "ends on the derived day")
        assertEquals(TrendCoverage.FULL, byEnd["2025-12-11"], "starts 2025-09-13, after the backfill")
        assertEquals(TrendCoverage.PARTIAL, byEnd["2025-11-27"], "starts 2025-08-30, a day before the backfill")
        assertEquals(TrendCoverage.PARTIAL, byEnd["2025-09-04"], "ends after the backfill day, starts months before it")
        assertEquals(TrendCoverage.NONE, byEnd["2025-08-21"], "ends before the backfill day")
        assertEquals(TrendCoverage.NONE, trend.windows.first().coverage)
        assertEquals(7, trend.windows.count { it.coverage == TrendCoverage.FULL })
        assertNull(trend.note?.takeIf { it.startsWith("Not derived yet") })

        val full = trend.windows.filter { it.coverage == TrendCoverage.FULL }.map { it.end }.toSet()
        var cues = 0
        for (row in trend.rows) {
            val usable = row.points.filter { it.end in full && !it.hidden && it.value != null }
            val cue = row.trend
            if (usable.size < 2) {
                assertNull(cue, "${row.key}: fewer than two usable points, no cue")
                continue
            }
            assertNotNull(cue, "${row.key}: two usable points, a cue")
            assertEquals(usable.first().end, cue.fromEnd, "${row.key} fromEnd")
            assertEquals(usable.last().end, cue.toEnd, "${row.key} toEnd")
            assertEquals(usable.last().value!! - usable.first().value!!, cue.difference!!, 1e-12, "${row.key} difference")
            cues++
        }
        assertTrue(cues >= 3, "the busiest person has several rows with a cue ($cues)")
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
