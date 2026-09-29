package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.CycleTimeReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY_MS = 86_400_000L

private data class ExpectedCycle(val elapsed: List<Double>, val working: List<Double>, val population: Int, val neverStarted: Int)

/** Measurable = started (`cycle_ms` set); the only exclusion is a task that never started. */
private fun expectedCycle(tasks: List<TaskFact>): ExpectedCycle {
    val measurable = tasks.filter { it.cycleMs != null && it.cycleWorkingDays != null }
    return ExpectedCycle(
        elapsed = measurable.map { it.cycleMs!!.toDouble() / DAY_MS },
        working = measurable.map { it.cycleWorkingDays!!.toDouble() },
        population = tasks.size,
        neverStarted = tasks.size - measurable.size,
    )
}

/** The bucket start of an instant — java.time written out here, independent of the report's own bucket code. */
private fun bucketOf(atMs: Long, zone: ZoneId, weekly: Boolean): LocalDate {
    val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    return if (weekly) day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else day.withDayOfMonth(1)
}

/** Every bucket start covering inclusive ISO dates [from]..[to], oldest first. */
private fun bucketRange(from: String, to: String, zone: ZoneId, weekly: Boolean): List<LocalDate> {
    val last = bucketOf(LocalDate.parse(to).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1, zone, weekly)
    val starts = mutableListOf<LocalDate>()
    var cursor = bucketOf(LocalDate.parse(from).atStartOfDay(zone).toInstant().toEpochMilli(), zone, weekly)
    while (!cursor.isAfter(last)) {
        starts += cursor
        cursor = if (weekly) cursor.plusWeeks(1) else cursor.plusMonths(1)
    }
    return starts
}

/**
 * `GET /api/v1/reports/cycle-time` (v0.3.0 M4 commit 12b, Report 7, `.claude/docs/measures.md` "Reports 7, 8").
 * The stub fixture grades every distribution, group and trend bucket against an INDEPENDENT computation over the
 * raw `fact_task_delivery` rows (own percentile arithmetic and bucket dates); the exclusion, the zero-working-day
 * cycle, the per-bucket minimum sample size and the org drill are pinned exactly on hand-built rows in a fresh
 * DISABLED connection.
 */
class ReportCycleTimeTest {

    private suspend fun HttpClient.cycleTime(query: String): CycleTimeReport {
        val response = get("/api/v1/reports/cycle-time?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cycle-time?$query")
        return response.body()
    }

    private fun CycleTimeReport.assertMatches(label: String, expected: ExpectedCycle) {
        assertDistribution("$label elapsedDays", expected.elapsed, elapsedDays, meta.minSampleSize)
        assertDistribution("$label workingDays", expected.working, workingDays, meta.minSampleSize)
        assertEquals(expected.population, excluded.population, "$label population")
        assertEquals(expected.neverStarted, excluded.neverStarted, "$label neverStarted")
        assertEquals((excluded.population - excluded.neverStarted).toLong(), workingDays.n, "$label n == population - neverStarted")
        assertEquals(elapsedDays.n, workingDays.n)
    }

    private suspend fun doneInWindow(connId: UInt, from: String, to: String): List<TaskFact> {
        val bounds = windowBounds(reportZone(), from, to)
        return readTaskFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
    }

    @Test
    fun `UNIT distributions and the exclusion equal an independent computation`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cycle-time-unit")

        for ((from, to) in listOf("2024-01-01" to "2026-12-31", "2025-11-01" to "2025-12-31")) {
            val tasks = doneInWindow(connId, from, to)
            val expected = expectedCycle(tasks)
            assertTrue(expected.working.isNotEmpty(), "window $from..$to must yield cycle times")
            assertEquals(expected.population - expected.neverStarted, expected.working.size, "window $from..$to measurable count")
            assertTrue(tasks.all { (it.cycleMs == null) == (it.startedAt == null) }, "a cycle exists exactly when the task started")
            val body = client.cycleTime("connectionId=$connId&from=$from&to=$to")
            body.assertMatches("$from..$to", expected)
            assertEquals("TASK", body.meta.domainView.name)
        }
        // The stub fixture has NO DONE task that never started (every generated DONE task passed through IN_PROGRESS), so
        // the `neverStarted` exclusion is only non-vacuous on the hand-built rows below; here it is pinned as 0 = independent.
    }

    @Test
    fun `groups at UNIT TEAM and USER level each equal their own independent computation`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cycle-time-groups")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val unit = client.cycleTime(range)
        val min = unit.meta.minSampleSize
        assertTrue(unit.groups.isNotEmpty() && unit.groups.all { it.accountId == null }, "UNIT groups are teams")
        assertEquals(unit.workingDays.n, unit.groups.sumOf { it.workingDays.n }, "UNIT Σ group n")
        assertEquals(unit.excluded.population, unit.groups.sumOf { it.excluded.population })
        assertEquals(unit.excluded.neverStarted, unit.groups.sumOf { it.excluded.neverStarted })
        for (group in unit.groups) {
            val expected = expectedCycle(tasks.filter { it.team == group.teamId })
            assertDistribution("team ${group.teamId} working", expected.working, group.workingDays, min)
            assertDistribution("team ${group.teamId} elapsed", expected.elapsed, group.elapsedDays, min)
            assertEquals(expected.neverStarted, group.excluded.neverStarted)
        }
        val labels = unit.groups.map { it.label }
        assertEquals(labels.sortedWith(nullsLast(naturalOrder())), labels, "groups are ordered by label, null last")

        val teamId = unit.groups.first { it.teamId != null && it.workingDays.n > 0 }.teamId!!
        val team = client.cycleTime("$range&teamId=$teamId")
        team.assertMatches("team $teamId", expectedCycle(tasks.filter { it.team == teamId }))
        assertTrue(team.groups.isNotEmpty() && team.groups.all { it.teamId == null }, "TEAM groups are users")
        assertEquals(team.workingDays.n, team.groups.sumOf { it.workingDays.n })
        assertEquals(team.excluded.population, team.groups.sumOf { it.excluded.population })
        for (group in team.groups) {
            val expected = expectedCycle(tasks.filter { it.team == teamId && it.account == group.accountId })
            assertDistribution("user ${group.accountId} working", expected.working, group.workingDays, min)
        }

        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = client.cycleTime("$range&teamId=$teamId&accountId=$account")
        assertTrue(user.groups.isEmpty())
        user.assertMatches("user $account", expectedCycle(tasks.filter { it.team == teamId && it.account == account }))

        val unassigned = tasks.filter { it.team == null }
        assertTrue(unassigned.isNotEmpty(), "the fixture must carry DONE tasks with no credit team")
        client.cycleTime("$range&teamId=0").assertMatches("UNASSIGNED", expectedCycle(unassigned))
    }

    @Test
    fun `activityType and domain slices narrow the population and lastSprints reads the sprint envelope`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cycle-time-slices")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val activity = tasks.groupingBy { it.activityType }.eachCount().maxBy { it.value }.key
        client.cycleTime("$range&activityType=$activity")
            .assertMatches("activity $activity", expectedCycle(tasks.filter { it.activityType == activity }))
        val domain = tasks.mapNotNull { it.domainKey }.groupingBy { it }.eachCount().maxBy { it.value }.key
        client.cycleTime("$range&domain=$domain").assertMatches("domain $domain", expectedCycle(tasks.filter { it.domainKey == domain }))

        val body = client.cycleTime("connectionId=$connId&lastSprints=2")
        val sprintIds = body.meta.resolvedSprints.flatMap { it.sprintIds }
        assertTrue(sprintIds.isNotEmpty())
        assertNull(body.meta.from)
        val bounds = suspendTransaction(sharedDatabaseForTests()) {
            val d = MetricsStore.DimSprint
            val rows = d.selectAll().where { (d.connectionId eq connId) and (d.sprintId inList sprintIds) }.toList()
            rows.minOf { it[d.startAt] ?: it[d.completeAt]!! } to rows.maxOf { it[d.completeAt]!! } + 1
        }
        val enveloped = readTaskFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
        body.assertMatches("lastSprints=2 envelope", expectedCycle(enveloped))
    }

    @Test
    fun `the trend's every bucket equals an independent p50 p90 and n, hidden below the minimum sample size`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cycle-time-trend")
        val zone = reportZone()
        val from = "2025-01-01"
        val to = "2026-03-05"
        val tasks = doneInWindow(connId, from, to).filter { it.cycleWorkingDays != null }
        assertTrue(tasks.isNotEmpty())

        for ((param, weekly) in listOf("WEEK" to true, "MONTH" to false)) {
            val body = client.cycleTime("connectionId=$connId&from=$from&to=$to&bucket=$param")
            val min = body.meta.minSampleSize
            val starts = bucketRange(from, to, zone, weekly)
            assertEquals(starts.map { it.toString() }, body.trend.map { it.bucketStart }, "$param buckets are zero-filled and consecutive")
            val byBucket = tasks.groupBy { bucketOf(it.doneAt!!, zone, weekly) }
            for (bucket in body.trend) {
                val values = byBucket[LocalDate.parse(bucket.bucketStart)].orEmpty().map { it.cycleWorkingDays!!.toDouble() }.sorted()
                assertEquals(values.size.toLong(), bucket.n, "$param ${bucket.bucketStart} n")
                if (values.size < min) {
                    assertNull(bucket.p50, "$param ${bucket.bucketStart} p50 hidden")
                    assertNull(bucket.p90, "$param ${bucket.bucketStart} p90 hidden")
                } else {
                    assertTrue(abs(quantile(values, 0.5) - assertNotNull(bucket.p50)) < DIST_TOLERANCE, "$param ${bucket.bucketStart} p50")
                    assertTrue(abs(quantile(values, 0.9) - assertNotNull(bucket.p90)) < DIST_TOLERANCE, "$param ${bucket.bucketStart} p90")
                }
            }
            assertEquals(body.workingDays.n, body.trend.sumOf { it.n }, "$param Σ bucket n == workingDays.n")
            assertTrue(body.trend.any { it.p50 != null }, "$param must have a visible bucket")
            if (weekly) assertTrue(body.trend.all { LocalDate.parse(it.bucketStart).dayOfWeek == DayOfWeek.MONDAY })
        }
        assertEquals(
            client.cycleTime("connectionId=$connId&from=$from&to=$to").trend,
            client.cycleTime("connectionId=$connId&from=$from&to=$to&bucket=WEEK").trend,
            "WEEK is the default",
        )
    }

    /**
     * Hand-computed cycle times on hand-built rows (settings pinned to a minimum sample size of 2). Window: January 2026,
     * weekly buckets starting Dec 29, Jan 5/12/19/26. Seven DONE tasks; a sub-task, an open task and one done outside the
     * window never count.
     */
    @Test
    fun `hand-built rows pin the never-started exclusion, a zero-working-day cycle, the trend and the drill exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "cycle-time", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("cycle-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("cycle-y"))
        val s = noonUtc("2026-01-02")
        val rows = listOf(
            handTask(1, s, noonUtc("2026-01-06"), cycleMs = 2 * DAY_MS, cycleWorkingDays = 2.0, creditTeamId = teamX, account = "acc-1"),
            handTask(2, s, noonUtc("2026-01-07"), cycleMs = 5 * DAY_MS, cycleWorkingDays = 3.0, creditTeamId = teamX, account = "acc-1"),
            // a cycle of zero working days is a real value: it stays in
            handTask(3, s, noonUtc("2026-01-08"), cycleMs = DAY_MS, cycleWorkingDays = 0.0),
            handTask(4, s, noonUtc("2026-01-14"), cycleMs = 3 * DAY_MS, cycleWorkingDays = 3.0, creditTeamId = teamY),
            handTask(5, null, noonUtc("2026-01-15")), // never started: excluded, no cycle
            handTask(6, s, noonUtc("2026-01-28"), cycleMs = 4 * DAY_MS, cycleWorkingDays = 4.0),
            handTask(7, s, noonUtc("2026-01-29"), cycleMs = 6 * DAY_MS, cycleWorkingDays = 6.0),
            // never counted:
            handTask(8, s, noonUtc("2026-01-10"), cycleMs = 9 * DAY_MS, cycleWorkingDays = 9.0, subtask = true),
            handTask(9, s, null),
            handTask(10, noonUtc("2025-05-01"), noonUtc("2025-06-01"), cycleMs = 9 * DAY_MS, cycleWorkingDays = 9.0),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-cycle-time-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            val body = withMinSampleSize(2) { client.cycleTime(query) }

            assertEquals(7, body.excluded.population)
            assertEquals(1, body.excluded.neverStarted)
            assertDistribution("working", listOf(2.0, 3.0, 0.0, 3.0, 4.0, 6.0), body.workingDays, 2)
            assertDistribution("elapsed", listOf(2.0, 5.0, 1.0, 3.0, 4.0, 6.0), body.elapsedDays, 2)
            assertEquals(3.0, body.workingDays.mean!!, 1e-9) // sorted 0 2 3 3 4 6
            assertEquals(3.5, body.elapsedDays.mean!!, 1e-9)
            assertEquals(3.0, body.workingDays.p50!!, 1e-9)

            // Weekly trend: n 0 / 3 / 1 (hidden) / 0 / 2.
            assertEquals(listOf("2025-12-29", "2026-01-05", "2026-01-12", "2026-01-19", "2026-01-26"), body.trend.map { it.bucketStart })
            assertEquals(listOf(0L, 3L, 1L, 0L, 2L), body.trend.map { it.n })
            val week = body.trend[1]
            assertEquals(2.0, week.p50!!, 1e-9) // 0 2 3
            assertEquals(2.8, week.p90!!, 1e-9) // 2 + 0.8 * (3 - 2)
            assertNull(body.trend[0].p50)
            assertNull(body.trend[2].p50, "one task is below a minimum sample size of 2")
            assertNull(body.trend[2].p90)
            assertEquals(5.0, body.trend[4].p50!!, 1e-9) // 4 6
            assertEquals(5.8, body.trend[4].p90!!, 1e-9) // 4 + 0.9 * (6 - 4)

            // Monthly trend: the one January bucket holds all six measurable tasks.
            val month = withMinSampleSize(2) { client.cycleTime("$query&bucket=MONTH") }.trend.single()
            assertEquals("2026-01-01", month.bucketStart)
            assertEquals(6L, month.n)
            assertEquals(3.0, month.p50!!, 1e-9)
            assertEquals(5.0, month.p90!!, 1e-9) // rank 4.5 over 0 2 3 3 4 6

            // UNIT drill: X = tasks 1 2; Y = task 4; the rest (3 5 6 7) is UNASSIGNED with one never started.
            val groups = withMinSampleSize(2) { client.cycleTime(query).groups }
            assertEquals(3, groups.size)
            val x = groups.single { it.teamId == teamX }
            assertEquals(2, x.workingDays.n)
            assertEquals(2.5, x.workingDays.mean!!, 1e-9)
            val y = groups.single { it.teamId == teamY }
            assertEquals(1, y.workingDays.n)
            assertTrue(y.workingDays.hidden)
            val rest = groups.single { it.teamId == null }
            assertEquals(4, rest.excluded.population)
            assertEquals(1, rest.excluded.neverStarted)
            assertEquals(3, rest.workingDays.n)

            // TEAM and USER level for X.
            val team = withMinSampleSize(2) { client.cycleTime("$query&teamId=$teamX") }
            assertEquals(2, team.excluded.population)
            assertEquals(listOf("acc-1"), team.groups.mapNotNull { it.accountId })
            val user = withMinSampleSize(2) { client.cycleTime("$query&teamId=$teamX&accountId=acc-1") }
            assertEquals(2, user.workingDays.n)
            assertTrue(user.groups.isEmpty())
            // teamId=0: the credit-less tasks.
            assertEquals(4, withMinSampleSize(2) { client.cycleTime("$query&teamId=0") }.excluded.population)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    @Test
    fun `bad filters are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-cycle-time-400")

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cycle-time?bucket=DAY").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cycle-time?from=2026-01-01&to=2025-01-01").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cycle-time?accountId=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cycle-time?connectionId=999999").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cycle-time?teamId=999999").status)
    }
}
