package ch.nokillswit

import ch.nokillswit.reports.CycleTimeReport
import ch.nokillswit.reports.Distribution
import ch.nokillswit.reports.ReportedTimeRatioReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val DAY_MS = 86_400_000.0
private const val FROM = "2024-01-01"
private const val TO = "2026-12-31"
private val TAILS = listOf(0, 5, 25)

/** The UNIT level of a scoped read is "a domain with no team" (a report is never read for the whole unit): the stub's FLO domain. */
private const val UNIT_DOMAIN = DerivedStubFixture.DOMAIN

/**
 * The reconciliation gate of the histogram drills of Cycle time and Reported time
 * (`GET /api/v1/reports/{cycle-time,reported-time-ratio}/items`, `.claude/docs/reports.md` "Drill to items"): on the derived stub
 * fixture, for EVERY bucket of EVERY histogram — at UNIT, TEAM and USER level (two picks: union and both segments), with the
 * open tail off, at its default and at its widest — the drill's bar equals the histogram's count and range, the listed
 * figures lie in it, and all buckets together hold exactly an INDEPENDENT re-derivation of the measured values. A failing
 * reconciliation is a real divergence between a bar and its items, never grounds to loosen a test. The hand-built rows and the
 * 400 matrix are `ReportDrillDistributionHandBuiltTest`.
 */
class ReportDrillDistributionTest {

    private suspend fun HttpClient.cycle(query: String): CycleTimeReport {
        val response = get("/api/v1/reports/cycle-time?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cycle-time?$query")
        return response.body()
    }

    private suspend fun HttpClient.ratio(query: String): ReportedTimeRatioReport {
        val response = get("/api/v1/reports/reported-time-ratio?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET reported-time-ratio?$query")
        return response.body()
    }

    private suspend fun HttpClient.reconcileCycle(label: String, query: String, view: String, shown: Distribution, oracle: List<Double>) =
        assertHistogramDrillsReconcile(label, "cycle-time", query, view, shown, oracle)

    private suspend fun doneTasks(connId: UInt): List<TaskFact> {
        val bounds = windowBounds(reportZone(), FROM, TO)
        return readTaskFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
    }

    private fun measured(tasks: List<TaskFact>) = tasks.filter { it.cycleMs != null && it.cycleWorkingDays != null }

    private fun working(tasks: List<TaskFact>) = measured(tasks).map { it.cycleWorkingDays!!.toDouble() }

    private fun elapsed(tasks: List<TaskFact>) = measured(tasks).map { it.cycleMs!! / DAY_MS }

    private fun ratios(tasks: List<TaskFact>) = tasks.filter { it.hasWorklogs && it.actual.signum() > 0 }
        .filter { it.cycleMs != null && it.cycleWorkingDays != null && it.cycleWorkingDays.signum() > 0 }
        .map { it.actual.toDouble() / it.cycleWorkingDays!!.toDouble() }

    private fun flow(tasks: List<TaskFact>) = tasks.filter { (it.cycleMs ?: 0) > 0 }.map { it.activeMs.toDouble() / it.cycleMs!! }

    /** The team with the most measured tasks among at least two people who each have a ratio, and its two busiest such people. */
    private fun busiestPair(tasks: List<TaskFact>): Triple<UInt, String, String> {
        val usable = tasks.filter { it.team != null && it.account != null && ratios(listOf(it)).isNotEmpty() }
        val candidates = usable.groupBy { it.team!! }.filterValues { rows -> rows.mapNotNull { it.account }.distinct().size >= 2 }
        val (team, rows) = candidates.maxByOrNull { it.value.size } ?: error("the stub must carry a team with two people with ratios")
        val (a, b) = rows.groupingBy { it.account!! }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
        return Triple(team, a, b)
    }

    @Test
    fun `every bar of the cycle time task histograms reconciles with its drill at UNIT TEAM and USER level for every tail`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val tasks = doneTasks(connId)
            val (teamId, a, b) = busiestPair(tasks)
            val client = seededClient("reports-drill-cycle-tasks")
            val base = "connectionId=$connId&from=$FROM&to=$TO"
            val team = tasks.filter { it.team == teamId }
            val scopes = listOf(
                Triple("UNIT", "$base&domain=$UNIT_DOMAIN", tasks.filter { it.domainKey == UNIT_DOMAIN }),
                Triple("TEAM", "$base&teamId=$teamId", team),
                Triple("USER", "$base&teamId=$teamId&accountId=$b&accountId=$a", team.filter { it.account in setOf(a, b) }),
            )
            var buckets = 0
            for ((level, query, scoped) in scopes) {
                for (tail in TAILS) {
                    val q = "$query&tailPercent=$tail"
                    val report = client.cycle(q)
                    val label = "$level/tail$tail"
                    buckets += client.reconcileCycle(label, q, "WORKING_DAYS", report.workingDays, working(scoped))
                    buckets += client.reconcileCycle(label, q, "ELAPSED_DAYS", report.elapsedDays, elapsed(scoped))
                    if (level == "USER") assertEquals(2, report.workingDays.byUser.size, "USER carries both picks' segments")
                    if (tail > 0 && level == "UNIT") {
                        assertTrue(report.workingDays.histogram.any { it.to == null }, "tail $tail must give the fixture an open bucket")
                    }
                }
            }
            assertTrue(buckets > 100, "the gate must have walked many buckets, walked $buckets")
        }

    @Test
    fun `every bar of the cycle time epic histograms reconciles with its drill, and USER level has no bar`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val bounds = windowBounds(reportZone(), FROM, TO)
        val epics = readEpicFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
        val teamId = epics.mapNotNull { it.owner }.groupingBy { it }.eachCount().maxBy { it.value }.key
        val client = seededClient("reports-drill-cycle-epics")
        val base = "connectionId=$connId&from=$FROM&to=$TO"
        fun measuredEpics(rows: List<EpicFact>) = rows.filter { it.cycleMs != null && it.cycleWorkingDays != null }
        fun workingOf(rows: List<EpicFact>) = measuredEpics(rows).map { it.cycleWorkingDays!!.toDouble() }
        fun elapsedOf(rows: List<EpicFact>) = measuredEpics(rows).map { it.cycleMs!! / DAY_MS }

        var buckets = 0
        for ((level, query, scoped) in listOf(
            Triple("UNIT", "$base&domain=$UNIT_DOMAIN", epics.filter { it.domainKey == UNIT_DOMAIN }),
            Triple("TEAM", "$base&teamId=$teamId", epics.filter { it.owner == teamId }),
        )) {
            // A minimum sample size of 1 so even a small owner team shows its histogram: report and drill read the SAME setting.
            withMinSampleSize(1) {
                for (tail in TAILS) {
                    val q = "$query&tailPercent=$tail"
                    val report = client.cycle(q)
                    val label = "$level/tail$tail"
                    val working = report.epics.workingDays
                    val elapsed = report.epics.elapsedDays
                    buckets += client.reconcileCycle(label, q, "EPIC_WORKING_DAYS", working, workingOf(scoped))
                    buckets += client.reconcileCycle(label, q, "EPIC_ELAPSED_DAYS", elapsed, elapsedOf(scoped))
                    val first = client.drillItems("cycle-time", "$q&view=EPIC_WORKING_DAYS&bucketIndex=0")
                    assertTrue(first.items.all { it.itemKind.name == "EPIC" && it.accountId == null }, "$level epics are EPIC items")
                }
            }
        }
        assertTrue(buckets > 20, "the gate must have walked the epic buckets, walked $buckets")

        // USER level reads no epics (the report's own all-zero block): there is no bar to drill into.
        val (userTeam, a, _) = busiestPair(doneTasks(connId))
        for (view in listOf("EPIC_WORKING_DAYS", "EPIC_ELAPSED_DAYS")) {
            val response = withMinSampleSize(1) {
                client.get("/api/v1/reports/cycle-time/items?$base&teamId=$userTeam&accountId=$a&view=$view&bucketIndex=0")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "$view at USER level")
        }
    }

    @Test
    fun `every bar of the reported time ratio and flow efficiency histograms reconciles with its drill at UNIT TEAM and USER level`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val tasks = doneTasks(connId)
            val (teamId, a, b) = busiestPair(tasks)
            val client = seededClient("reports-drill-reported-time")
            val base = "connectionId=$connId&from=$FROM&to=$TO"
            val team = tasks.filter { it.team == teamId }
            val scopes = listOf(
                Triple("UNIT", "$base&domain=$UNIT_DOMAIN", tasks.filter { it.domainKey == UNIT_DOMAIN }),
                Triple("TEAM", "$base&teamId=$teamId", team),
                Triple("USER", "$base&teamId=$teamId&accountId=$b&accountId=$a", team.filter { it.account in setOf(a, b) }),
            )
            var buckets = 0
            for ((level, query, scoped) in scopes) {
                for (tail in TAILS) {
                    val q = "$query&tailPercent=$tail"
                    val report = client.ratio(q)
                    val label = "$level/tail$tail"
                    buckets += client.assertHistogramDrillsReconcile(label, "reported-time-ratio", q, "RATIO", report.ratio, ratios(scoped))
                    buckets += client.assertHistogramDrillsReconcile(
                        label, "reported-time-ratio", q, "FLOW_EFFICIENCY", report.flowEfficiency, flow(scoped),
                    )
                    if (level == "USER") assertEquals(2, report.ratio.byUser.size)
                }
            }
            assertTrue(buckets > 100, "the gate must have walked many buckets, walked $buckets")
        }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
