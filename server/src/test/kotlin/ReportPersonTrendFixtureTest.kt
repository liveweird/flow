package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.PersonTrendReport
import ch.nokillswit.reports.TREND_ROW_KEYS
import ch.nokillswit.reports.TrendCoverage
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val FROM = "2025-03-06"
private const val TO = "2026-03-05"

/**
 * Report 20 on the DERIVED stub fixture (read-only): the busiest assignee over a year (27 windows, every row), every point compared
 * with `GET /compare-people` over its window -- done facts, worklogs AND the WIP read at query time -- so slicing ONE wide fetch is proven
 * to be the per-window fetch on real data; the coverage labels against the fixture's known DERIVE day and backfill start; the cue
 * recomputed from the points; and the `in-test person-trend-365d` timing line (`build-times.md` "In-test targets").
 */
class ReportPersonTrendFixtureTest {

    /** The assignees with the most done tasks in the year, busiest first (the pair's second is the other side of each comparison). */
    private suspend fun busiestPair(connId: UInt): List<String> = busiest(connId, 2)

    private suspend fun busiest(connId: UInt, limit: Int): List<String> {
        val bounds = windowBounds(reportZone(), FROM, TO)
        return jdbcRows(
            """SELECT assignee_account_id_at_done, count(*) c FROM metrics.fact_task_delivery
               WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ?
                 AND assignee_account_id_at_done IS NOT NULL AND cycle_ms IS NOT NULL
               GROUP BY 1 ORDER BY c DESC, 1 LIMIT ?""",
            connId.toInt(), bounds.first, bounds.second, limit,
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
        assertEquals(27 * TREND_ROW_KEYS.size, count.cells)
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

    /**
     * The shared derived fixture is never mutated (testing.md): this case derives its OWN disabled clone, creates the memberships on the
     * stub's accounts (global by account id, so each row is deleted explicitly - `TeamService.delete` only closes the open ones and would
     * leave `[0, now)` rows behind for a later class's exclusion constraint) and removes them in a `finally`, then the team.
     */
    @Test
    fun `the team reference - every point of a year equals the median of the peers' Compare people cells, as the roster changes`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = SyncedStubFixture.createConnection(namePrefix = "trend-ref-clone", enabled = false)
            SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }
            val roster = busiest(connId, 5)
            val person = roster[0]
            val (first, second) = roster.slice(1..2)
            val joiner = roster[3]
            val leaver = roster[4]
            val teamId = TestTeams.seed(SyncedStubFixture.unique("trend-ref-fixture"))
            val memberships = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())
            val created = mutableListOf<UInt>()
            suspend fun join(account: String, from: Long, to: Long? = null) {
                created += memberships.create(teamId, TeamMembershipCreateRequest(account, from, to)).id
            }
            try {
                // The person joins on 2025-06-01; two peers from the start, one joins on 2025-09-20 and one leaves on 2025-12-01.
                join(person, noonUtc("2025-06-01"))
                for (peer in listOf(first, second)) join(peer, 0L)
                join(joiner, noonUtc("2025-09-20"))
                join(leaver, 0L, noonUtc("2025-12-01"))
                val client = seededClient("trend-fixture-ref", FlowUserRole.USER)
                val base = "connectionId=$connId"
                val query = "$base&from=$FROM&to=$TO&accountId=$person"

                client.personTrend(query) // warm
                val startedAt = System.nanoTime()
                val trend = client.personTrend(query)
                println("in-test\tperson-trend-365d-team-warm\t${(System.nanoTime() - startedAt) / 1_000_000L}")

                // The roster each window must have, from the dates above alone (a day is covered when the membership overlaps it).
                val peers = trend.windows.map { window ->
                    val end = LocalDate.parse(window.end)
                    if (end < LocalDate.parse("2025-06-01")) {
                        null
                    } else {
                        listOfNotNull(
                            first, second, joiner.takeIf { end >= LocalDate.parse("2025-09-20") },
                            leaver.takeIf { end <= LocalDate.parse("2025-12-01") },
                        )
                    }
                }
                assertEquals(peers.map { it != null }, trend.windows.map { it.team != null })
                assertTrue(trend.windows.mapNotNull { it.team }.all { it.id == teamId })
                val sizes = peers.filterNotNull().map { it.size }.toSet()
                assertEquals(setOf(3, 4), sizes, "the roster changes: three members, then four, then three")
                val compared = client.assertReferenceIsPeerMedian(trend, person, peers, base)
                assertEquals(peers.count { it != null } * TREND_ROW_KEYS.size, compared)
                val values = trend.rows.flatMap { row -> row.points.mapNotNull { it.reference?.value } }
                assertTrue(values.size > 20, "not vacuous: real medians over real figures (${values.size})")
                val points = trend.rows.flatMap { it.points }
                assertTrue(points.any { p -> p.reference?.let { it.value == null } == true }, "and some without a value")
            } finally {
                created.forEach { memberships.delete(teamId, it) }
                TestTeams.service.delete(teamId)
            }
        }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
