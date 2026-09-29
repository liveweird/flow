package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.ReportedTimeRatioReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY_MS = 86_400_000L

private data class ExpectedRatio(
    val ratios: List<Double>,
    val population: Int,
    val noWorklogs: Int,
    val neverStarted: Int,
    val zeroCycle: Int,
)

/** Disjoint buckets in the order the measures.md row lists them: no worklogs (or 0.00 actual), never started, zero cycle. */
private fun expectedRatio(tasks: List<TaskFact>): ExpectedRatio {
    val worked = tasks.filter { it.hasWorklogs && it.actual.signum() > 0 }
    val started = worked.filter { it.cycleMs != null && it.cycleWorkingDays != null }
    val ratios = started.filter { it.cycleWorkingDays!!.signum() > 0 }.map { it.actual.toDouble() / it.cycleWorkingDays!!.toDouble() }
    return ExpectedRatio(ratios, tasks.size, tasks.size - worked.size, worked.size - started.size, started.size - ratios.size)
}

private data class ExpectedFlow(val values: List<Double>, val population: Int, val neverStarted: Int, val zeroCycle: Int)

/** Flow efficiency (A18) = `active_ms / cycle_ms`; worklogs play no part; buckets: never started, then a zero-length cycle. */
private fun expectedFlow(tasks: List<TaskFact>): ExpectedFlow {
    val started = tasks.filter { it.cycleMs != null }
    val values = started.filter { it.cycleMs!! > 0 }.map { it.activeMs.toDouble() / it.cycleMs!! }
    return ExpectedFlow(values, tasks.size, tasks.size - started.size, started.size - values.size)
}

/**
 * `GET /api/v1/reports/reported-time-ratio` (v0.3.0 M4 commit 12b, Report 8, `.claude/docs/measures.md`
 * "Reports 7, 8"). The stub fixture grades the distribution, the exclusion buckets and every group against an
 * INDEPENDENT computation over the raw `fact_task_delivery` rows; the bucket precedence, the zero-cycle exclusion and
 * the hidden state are pinned exactly on hand-built rows in a fresh DISABLED connection.
 */
class ReportReportedTimeRatioTest {

    private suspend fun HttpClient.ratio(query: String): ReportedTimeRatioReport {
        val response = get("/api/v1/reports/reported-time-ratio?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET reported-time-ratio?$query")
        return response.body()
    }

    private fun ReportedTimeRatioReport.assertFlow(label: String, tasks: List<TaskFact>) {
        val expected = expectedFlow(tasks)
        assertDistribution("$label flowEfficiency", expected.values, flowEfficiency, meta.minSampleSize)
        assertEquals(expected.population, flowEfficiencyExcluded.population, "$label flow population")
        assertEquals(expected.neverStarted, flowEfficiencyExcluded.neverStarted, "$label flow neverStarted")
        assertEquals(expected.zeroCycle, flowEfficiencyExcluded.zeroCycle, "$label flow zeroCycle")
        assertEquals(
            flowEfficiencyExcluded.population.toLong(),
            flowEfficiency.n + flowEfficiencyExcluded.neverStarted + flowEfficiencyExcluded.zeroCycle,
            "$label flow partition",
        )
    }

    private fun ReportedTimeRatioReport.assertMatches(label: String, expected: ExpectedRatio) {
        assertDistribution("$label ratio", expected.ratios, ratio, meta.minSampleSize)
        assertEquals(expected.population, excluded.population, "$label population")
        assertEquals(expected.noWorklogs, excluded.noWorklogs, "$label noWorklogs")
        assertEquals(expected.neverStarted, excluded.neverStarted, "$label neverStarted")
        assertEquals(expected.zeroCycle, excluded.zeroCycle, "$label zeroCycle")
        // The partition: every DONE task is in the distribution or in exactly one exclusion bucket.
        assertEquals(
            excluded.population.toLong(),
            ratio.n + excluded.noWorklogs + excluded.neverStarted + excluded.zeroCycle,
            "$label partition",
        )
    }

    private suspend fun doneInWindow(connId: UInt, from: String, to: String): List<TaskFact> {
        val bounds = windowBounds(reportZone(), from, to)
        return readTaskFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
    }

    @Test
    fun `UNIT distribution and exclusion buckets equal an independent computation and partition the population`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-ratio-unit")

        for ((from, to) in listOf("2024-01-01" to "2026-12-31", "2025-11-01" to "2025-12-31")) {
            val tasks = doneInWindow(connId, from, to)
            val expected = expectedRatio(tasks)
            assertTrue(expected.ratios.isNotEmpty(), "window $from..$to must yield ratios")
            assertTrue(expectedFlow(tasks).values.isNotEmpty(), "window $from..$to must yield flow efficiencies")
            val body = client.ratio("connectionId=$connId&from=$from&to=$to")
            body.assertMatches("$from..$to", expected)
            body.assertFlow("$from..$to", tasks)
            assertEquals("TASK", body.meta.domainView.name)
        }
        val whole = expectedRatio(doneInWindow(connId, "2024-01-01", "2026-12-31"))
        assertTrue(whole.noWorklogs + whole.neverStarted + whole.zeroCycle > 0, "the stub must carry excluded tasks")
    }

    @Test
    fun `groups at UNIT TEAM and USER level each equal their own independent computation and sum to the totals`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-ratio-groups")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val unit = client.ratio(range)
        val min = unit.meta.minSampleSize
        assertTrue(unit.groups.isNotEmpty() && unit.groups.all { it.accountId == null })
        assertEquals(unit.ratio.n, unit.groups.sumOf { it.ratio.n })
        assertEquals(unit.excluded.population, unit.groups.sumOf { it.excluded.population })
        assertEquals(unit.excluded.noWorklogs, unit.groups.sumOf { it.excluded.noWorklogs })
        assertEquals(unit.excluded.zeroCycle, unit.groups.sumOf { it.excluded.zeroCycle })
        assertEquals(unit.flowEfficiency.n, unit.groups.sumOf { it.flowEfficiency.n })
        assertEquals(unit.flowEfficiencyExcluded.zeroCycle, unit.groups.sumOf { it.flowEfficiencyExcluded.zeroCycle })
        for (group in unit.groups) {
            val mine = tasks.filter { it.team == group.teamId }
            val expected = expectedRatio(mine)
            assertDistribution("team ${group.teamId}", expected.ratios, group.ratio, min)
            assertEquals(expected.noWorklogs, group.excluded.noWorklogs)
            val flow = expectedFlow(mine)
            assertDistribution("team ${group.teamId} flow", flow.values, group.flowEfficiency, min)
            assertEquals(flow.neverStarted, group.flowEfficiencyExcluded.neverStarted)
        }
        unit.assertFlow("UNIT", tasks)

        val teamId = unit.groups.first { it.teamId != null && it.ratio.n > 0 }.teamId!!
        val team = client.ratio("$range&teamId=$teamId")
        team.assertMatches("team $teamId", expectedRatio(tasks.filter { it.team == teamId }))
        team.assertFlow("team $teamId", tasks.filter { it.team == teamId })
        assertEquals(team.flowEfficiency.n, team.groups.sumOf { it.flowEfficiency.n })
        assertTrue(team.groups.isNotEmpty() && team.groups.all { it.teamId == null })
        assertEquals(team.ratio.n, team.groups.sumOf { it.ratio.n })
        assertEquals(team.excluded.population, team.groups.sumOf { it.excluded.population })
        for (group in team.groups) {
            val expected = expectedRatio(tasks.filter { it.team == teamId && it.account == group.accountId })
            assertDistribution("user ${group.accountId}", expected.ratios, group.ratio, min)
            val flow = expectedFlow(tasks.filter { it.team == teamId && it.account == group.accountId })
            assertDistribution("user ${group.accountId} flow", flow.values, group.flowEfficiency, min)
        }

        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = client.ratio("$range&teamId=$teamId&accountId=$account")
        assertTrue(user.groups.isEmpty())
        user.assertMatches("user $account", expectedRatio(tasks.filter { it.team == teamId && it.account == account }))

        val unassigned = tasks.filter { it.team == null }
        assertTrue(unassigned.isNotEmpty())
        client.ratio("$range&teamId=0").assertMatches("UNASSIGNED", expectedRatio(unassigned))
        client.ratio("$range&teamId=0").assertFlow("UNASSIGNED", unassigned)
    }

    @Test
    fun `activityType and domain slices narrow the population`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-ratio-slices")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val activity = tasks.groupingBy { it.activityType }.eachCount().maxBy { it.value }.key
        client.ratio("$range&activityType=$activity")
            .assertMatches("activity $activity", expectedRatio(tasks.filter { it.activityType == activity }))
        val domain = tasks.mapNotNull { it.domainKey }.groupingBy { it }.eachCount().maxBy { it.value }.key
        client.ratio("$range&domain=$domain").assertMatches("domain $domain", expectedRatio(tasks.filter { it.domainKey == domain }))
    }

    /**
     * Hand-computed buckets and ratios on hand-built rows (settings pinned to a minimum sample size of 4/5). Ten DONE
     * tasks in the window; a sub-task, an open task and one done outside the window never count.
     */
    @Test
    fun `hand-built rows pin the disjoint exclusion buckets, the ratios and the hidden state exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "reported-ratio", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("ratio-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("ratio-y"))
        val s = noonUtc("2026-01-02")
        val done = noonUtc("2026-01-15")
        val rows = listOf(
            // ratio 2.0; flow efficiency 0.5 (active 1 day of 2)
            handTask(1, s, done, actualMd = 4.0, cycleMs = 2 * DAY_MS, cycleWorkingDays = 2.0, activeMs = DAY_MS, creditTeamId = teamX),
            // 1.0; flow 1.0
            handTask(2, s, done, actualMd = 3.0, cycleMs = 3 * DAY_MS, cycleWorkingDays = 3.0, activeMs = 3 * DAY_MS, creditTeamId = teamX),
            // 0.25; flow 0.25
            handTask(3, s, done, actualMd = 1.0, cycleMs = 4 * DAY_MS, cycleWorkingDays = 4.0, activeMs = DAY_MS, creditTeamId = teamY),
            // 2.0; flow 0.0 (never in an IN_PROGRESS stage: all blocked)
            handTask(4, s, done, actualMd = 6.0, cycleMs = 3 * DAY_MS, cycleWorkingDays = 3.0),
            // noWorklogs — but still measured for flow efficiency (0.5): worklogs play no part there
            handTask(5, s, done, hasWorklogs = false, cycleMs = 3 * DAY_MS, cycleWorkingDays = 3.0, activeMs = 3 * DAY_MS / 2),
            // noWorklogs (0.00); flow 1.0
            handTask(6, s, done, hasWorklogs = true, actualMd = 0.0, cycleMs = 3 * DAY_MS, cycleWorkingDays = 3.0, activeMs = 3 * DAY_MS),
            handTask(7, null, done, actualMd = 2.0), // neverStarted (both measures)
            handTask(8, null, done, hasWorklogs = false), // noWorklogs wins over neverStarted for the ratio; neverStarted for flow
            // ratio zeroCycle (0 working days over a 1-day elapsed cycle); flow 0.5, measured
            handTask(9, s, done, actualMd = 2.0, cycleMs = DAY_MS, cycleWorkingDays = 0.0, activeMs = DAY_MS / 2),
            // a zero-length elapsed cycle: zeroCycle for both measures
            handTask(13, done, done, actualMd = 1.0, cycleMs = 0, cycleWorkingDays = 0.0),
            // never counted:
            handTask(10, s, done, actualMd = 9.0, cycleMs = DAY_MS, cycleWorkingDays = 1.0, subtask = true),
            handTask(11, s, null, actualMd = 9.0),
            handTask(12, noonUtc("2025-05-01"), noonUtc("2025-06-01"), actualMd = 9.0, cycleMs = DAY_MS, cycleWorkingDays = 1.0),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-ratio-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"

            val visible = withMinSampleSize(4) { client.ratio(query) }
            assertEquals(10, visible.excluded.population)
            assertEquals(3, visible.excluded.noWorklogs)
            assertEquals(1, visible.excluded.neverStarted)
            assertEquals(2, visible.excluded.zeroCycle)
            assertDistribution("ratio", listOf(2.0, 1.0, 0.25, 2.0), visible.ratio, 4)
            // Hand-computed: sorted 0.25 1 2 2 -> mean 1.3125, p50 1.5, p90 2.0.
            assertEquals(1.3125, visible.ratio.mean!!, 1e-9)
            assertEquals(1.5, visible.ratio.p50!!, 1e-9)
            assertEquals(2.0, visible.ratio.p90!!, 1e-9)

            // Flow efficiency (A18) over the same population: 0.5 1.0 0.25 0.0 0.5 1.0 0.5 (tasks 1-6 and 9).
            val efficiency = listOf(0.5, 1.0, 0.25, 0.0, 0.5, 1.0, 0.5)
            assertDistribution("flowEfficiency", efficiency, visible.flowEfficiency, 4)
            assertEquals(3.75 / 7, visible.flowEfficiency.mean!!, 1e-9)
            assertEquals(0.5, visible.flowEfficiency.p50!!, 1e-9) // sorted 0 .25 .5 .5 .5 1 1
            assertEquals(1.0, visible.flowEfficiency.p90!!, 1e-9)
            assertEquals(10, visible.flowEfficiencyExcluded.population)
            assertEquals(2, visible.flowEfficiencyExcluded.neverStarted)
            assertEquals(1, visible.flowEfficiencyExcluded.zeroCycle)

            val hidden = withMinSampleSize(5) { client.ratio(query) }
            assertEquals(4, hidden.ratio.n)
            assertTrue(hidden.ratio.hidden)
            assertNull(hidden.ratio.mean)
            assertEquals(7, hidden.flowEfficiency.n)
            assertTrue(!hidden.flowEfficiency.hidden, "seven measurable tasks reach a minimum sample size of 5")

            // UNIT drill: X = tasks 1 2, Y = task 3, the credit-less rest.
            val groups = withMinSampleSize(2) { client.ratio(query).groups }
            assertEquals(3, groups.size)
            val x = groups.single { it.teamId == teamX }
            assertEquals(2, x.ratio.n)
            assertEquals(1.5, x.ratio.mean!!, 1e-9)
            val y = groups.single { it.teamId == teamY }
            assertEquals(1, y.ratio.n)
            assertTrue(y.ratio.hidden)
            assertEquals(2, x.flowEfficiency.n)
            assertEquals(0.75, x.flowEfficiency.mean!!, 1e-9)
            assertTrue(y.flowEfficiency.hidden)
            val rest = groups.single { it.teamId == null }
            assertEquals(7, rest.excluded.population)
            assertEquals(3, rest.excluded.noWorklogs)
            assertEquals(1, rest.excluded.neverStarted)
            assertEquals(2, rest.excluded.zeroCycle)
            assertEquals(1, rest.ratio.n)
            assertEquals(4, rest.flowEfficiency.n) // tasks 4 5 6 9
            assertEquals(2, rest.flowEfficiencyExcluded.neverStarted)
            assertEquals(1, rest.flowEfficiencyExcluded.zeroCycle)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    @Test
    fun `bad filters are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-ratio-400")

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/reported-time-ratio?from=2026-01-01&to=2025-01-01").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/reported-time-ratio?accountId=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/reported-time-ratio?connectionId=999999").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/reported-time-ratio?lastSprints=0").status)
    }
}
