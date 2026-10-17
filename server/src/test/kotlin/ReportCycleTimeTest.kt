package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
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
import org.junit.BeforeClass

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
        assertEquals(listOf(account), user.groups.map { it.accountId }, "USER level lists the one pick")
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
            val d = MetricsTables.DimSprint
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

    /** The raw per-person values of both measures for [picks] (request order, label = display name else id), read off the fact rows. */
    private fun rawByUser(tasks: List<TaskFact>, picks: List<String>, names: Map<String, String>, working: Boolean) =
        picks.map { id ->
            val mine = expectedCycle(tasks.filter { it.account == id })
            Triple(id, names[id] ?: id, if (working) mine.working else mine.elapsed)
        }

    private suspend fun displayNames(accountIds: List<String>): Map<String, String> = suspendTransaction(sharedDatabaseForTests()) {
        WorkItemStore.People.selectAll().where { WorkItemStore.People.accountId inList accountIds }.toList()
            .associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
    }

    /** The team with the most measurable tasks among at least two people, and its two busiest people (by measurable tasks). */
    private fun busiestPair(tasks: List<TaskFact>): Triple<UInt, String, String> {
        val candidates = tasks.filter { it.team != null && it.account != null && it.cycleWorkingDays != null }
            .groupBy { it.team!! }.filterValues { rows -> rows.mapNotNull { it.account }.distinct().size >= 2 }
        val (team, rows) = candidates.maxByOrNull { it.value.size } ?: error("the stub must carry a team with two measured people")
        val (a, b) = rows.groupingBy { it.account!! }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
        return Triple(team, a, b)
    }

    @Test
    fun `several accountIds read the union, split every histogram bucket per person and list each pick in groups`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")
        val (teamId, a, b) = busiestPair(tasks)
        val nobody = "cycle-time-nobody"
        val picks = listOf(b, a, nobody)
        val client = seededClient("reports-cycle-time-multi")
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=$teamId"
        val both = client.cycleTime("$query&accountId=$b&accountId=$a&accountId=$nobody")

        val picked = tasks.filter { it.team == teamId && (it.account == a || it.account == b) }
        val names = displayNames(listOf(a, b))
        // The union: every figure equals an independent computation over the picked people's tasks together.
        both.assertMatches("union", expectedCycle(picked))
        assertTrue(!both.elapsedDays.hidden && !both.workingDays.hidden, "the busiest pair must clear the minimum sample size")
        // Every histogram bucket splits per person (request order, zero entry for the unknown pick, display name else id).
        assertSharesInUnionEdges("elapsedDays", rawByUser(picked, picks, names, working = false), both.elapsedDays)
        assertSharesInUnionEdges("workingDays", rawByUser(picked, picks, names, working = true), both.workingDays)
        assertTrue(both.workingDays.byUser.last().counts.all { it == 0L }, "the unknown pick is an all-zero share")
        assertEquals(nobody, both.workingDays.byUser.last().label, "an account with no known display name is labelled by its id")
        assertTrue(both.workingDays.byUser.take(2).all { share -> share.counts.any { it > 0 } }, "each real pick has measured tasks")

        // groups: one per pick (the unknown one empty), the TEAM-level rule per person, plain distributions, label-then-id order.
        assertEquals(setOf(a, b, nobody), both.groups.mapNotNull { it.accountId }.toSet())
        assertEquals(3, both.groups.size)
        assertTrue(both.groups.all { it.teamId == null })
        assertLabelThenIdOrder("USER", both.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
        val min = both.meta.minSampleSize
        for (group in both.groups) {
            val mine = expectedCycle(picked.filter { it.account == group.accountId })
            assertDistribution("group ${group.accountId} working", mine.working, group.workingDays, min)
            assertDistribution("group ${group.accountId} elapsed", mine.elapsed, group.elapsedDays, min)
            assertEquals(mine.neverStarted, group.excluded.neverStarted)
            assertEquals(emptyList(), group.workingDays.byUser, "a group's distribution is never split")
            assertEquals(emptyList(), group.elapsedDays.byUser)
        }
        assertEquals(both.workingDays.n, both.groups.sumOf { it.workingDays.n })
        assertEquals(both.excluded.population, both.groups.sumOf { it.excluded.population })
        // A repeated identical value collapses rather than fails.
        assertEquals(client.cycleTime("$query&accountId=$a").workingDays, client.cycleTime("$query&accountId=$a&accountId=$a").workingDays)
    }

    @Test
    fun `one accountId keeps today's figures with a one-entry byUser`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")
        val (teamId, a, _) = busiestPair(tasks)
        val client = seededClient("reports-cycle-time-one-user")
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=$teamId"

        val one = client.cycleTime("$query&accountId=$a")
        one.assertMatches("user $a", expectedCycle(tasks.filter { it.team == teamId && it.account == a }))
        assertTrue(!one.elapsedDays.hidden && !one.workingDays.hidden, "the busiest person must clear the minimum sample size")
        for (distribution in listOf(one.elapsedDays, one.workingDays)) {
            val share = distribution.byUser.single()
            assertEquals(a, share.accountId)
            assertEquals(distribution.histogram.map { it.count }, share.counts, "the one share is the whole histogram")
        }
        assertEquals(a, one.groups.single().accountId)
        assertEquals(one.workingDays.n, one.groups.single().workingDays.n)
        // Below USER level nothing is split: the TEAM and UNIT reads keep today's shape.
        val team = client.cycleTime(query)
        assertTrue(team.elapsedDays.byUser.isEmpty() && team.workingDays.byUser.isEmpty())
        assertTrue(client.cycleTime("connectionId=$connId&from=2024-01-01&to=2026-12-31").workingDays.byUser.isEmpty())
    }

    @Test
    fun `the trend stays the union of the picked people`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val zone = reportZone()
        val from = "2025-01-01"
        val to = "2026-03-05"
        val tasks = doneInWindow(connId, from, to)
        val (teamId, a, b) = busiestPair(tasks)
        val client = seededClient("reports-cycle-time-multi-trend")
        val query = "connectionId=$connId&from=$from&to=$to&teamId=$teamId"
        val both = client.cycleTime("$query&accountId=$a&accountId=$b")

        val picked = tasks.filter { it.team == teamId && (it.account == a || it.account == b) && it.cycleWorkingDays != null }
        assertTrue(picked.isNotEmpty())
        val byBucket = picked.groupBy { bucketOf(it.doneAt!!, zone, weekly = true) }
        assertEquals(bucketRange(from, to, zone, weekly = true).map { it.toString() }, both.trend.map { it.bucketStart })
        for (bucket in both.trend) {
            val values = byBucket[LocalDate.parse(bucket.bucketStart)].orEmpty().map { it.cycleWorkingDays!!.toDouble() }.sorted()
            assertEquals(values.size.toLong(), bucket.n, "${bucket.bucketStart} n is the picked people together")
            if (values.size >= both.meta.minSampleSize) {
                assertTrue(abs(quantile(values, 0.5) - assertNotNull(bucket.p50)) < DIST_TOLERANCE, "${bucket.bucketStart} p50")
            } else {
                assertNull(bucket.p50, "${bucket.bucketStart} p50 hidden")
            }
        }
        assertEquals(both.workingDays.n, both.trend.sumOf { it.n }, "Σ bucket n == the union's workingDays.n, never one person's")
        assertEquals(client.cycleTime("$query&accountId=$b&accountId=$a").trend, both.trend, "request order does not matter")
        // The epics block is unchanged: at USER level it is the all-zero answer whatever the picks.
        assertEquals(client.cycleTime("$query&accountId=$a").epics, both.epics)
    }

    @Test
    fun `at most eight accountIds on cycle time`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cycle-time-account-bound")
        val (teamId, _, _) = busiestPair(doneInWindow(connId, "2024-01-01", "2026-12-31"))
        fun url(count: Int) = "/api/v1/reports/cycle-time?connectionId=$connId&teamId=$teamId" +
            (1..count).joinToString("") { "&accountId=cycle-time-bound-$it" }

        assertEquals(HttpStatusCode.OK, client.get(url(8)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(url(9)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cycle-time?connectionId=$connId&accountId=a").status)
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
            assertEquals(listOf("acc-1"), user.groups.mapNotNull { it.accountId }, "USER level lists the one pick")
            // teamId=0: the credit-less tasks.
            assertEquals(4, withMinSampleSize(2) { client.cycleTime("$query&teamId=0") }.excluded.population)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    /**
     * `tailPercent` end to end on six hand-built cycles. Working days 0 2 3 3 4 6 and elapsed days 1 2 3 4 5 6 (all n = 6, min sample 2):
     *  - default 5: working X = 4 + 0.75 * 2 = 5.5, elapsed X = 5 + 0.75 = 5.75; the lone 6 is the open bucket of both;
     *  - 0: the plain ten equal-width buckets over [0, 6] / [1, 6];
     *  - 10: working X = 5 (rank 0.9 * 5 = 4.5, midway between 4 and 6), the 6 is again the tail.
     */
    @Test
    fun `tailPercent folds the top values into one open bucket and 0 restores the plain histogram`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "cycle-time-tail", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val s = noonUtc("2026-01-02")
        val rows = listOf(
            handTask(1, s, noonUtc("2026-01-06"), cycleMs = 2 * DAY_MS, cycleWorkingDays = 2.0),
            handTask(2, s, noonUtc("2026-01-07"), cycleMs = 5 * DAY_MS, cycleWorkingDays = 3.0),
            handTask(3, s, noonUtc("2026-01-08"), cycleMs = DAY_MS, cycleWorkingDays = 0.0),
            handTask(4, s, noonUtc("2026-01-14"), cycleMs = 3 * DAY_MS, cycleWorkingDays = 3.0),
            handTask(6, s, noonUtc("2026-01-28"), cycleMs = 4 * DAY_MS, cycleWorkingDays = 4.0),
            handTask(7, s, noonUtc("2026-01-29"), cycleMs = 6 * DAY_MS, cycleWorkingDays = 6.0),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-cycle-time-tail")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            fun List<ch.nokillswit.reports.HistogramBucket>.counts() = map { it.count }

            val default = withMinSampleSize(2) { client.cycleTime(query) }
            // Working days, default 5%: ten buckets of 0.55 over [0, 5.5] (0 -> 0, 2 -> 3, 3 3 -> 5, 4 -> 7) then the open bucket {6}.
            assertEquals(listOf<Long>(1, 0, 0, 1, 0, 2, 0, 1, 0, 0, 1), default.workingDays.histogram.counts())
            assertNull(default.workingDays.histogram.last().to)
            assertEquals(5.5, default.workingDays.histogram.last().from, 1e-9)
            assertEquals(5.5, default.workingDays.histogram[9].to!!, 1e-9)
            // Elapsed days, default 5%: ten buckets of 0.475 over [1, 5.75] (1 -> 0, 2 -> 2, 3 -> 4, 4 -> 6, 5 -> 8) then {6}.
            assertEquals(listOf<Long>(1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1), default.elapsedDays.histogram.counts())
            assertEquals(5.75, default.elapsedDays.histogram.last().from, 1e-9)
            // The summary figures are over all six values whatever the tail.
            assertEquals(6L, default.workingDays.n)
            assertEquals(3.0, default.workingDays.mean!!, 1e-9)
            assertEquals(6.0, default.workingDays.max!!, 1e-9)

            // tailPercent=0 is the plain histogram: ten buckets of 0.6 over [0, 6], the 6 in the closed last one, no open bucket.
            val plain = withMinSampleSize(2) { client.cycleTime("$query&tailPercent=0") }
            assertEquals(listOf<Long>(1, 0, 0, 1, 0, 2, 1, 0, 0, 1), plain.workingDays.histogram.counts())
            assertTrue(plain.workingDays.histogram.all { it.to != null })
            assertEquals(6.0, plain.workingDays.histogram.last().to!!, 1e-9)
            assertEquals(default.workingDays.copy(histogram = emptyList()), plain.workingDays.copy(histogram = emptyList()))

            // tailPercent=10: X = 5 (rank 4.5 between 4 and 6), ten buckets of 0.5 over [0, 5] (0 -> 0, 2 -> 4, 3 3 -> 6, 4 -> 8) then {6}.
            val ten = withMinSampleSize(2) { client.cycleTime("$query&tailPercent=10") }
            assertEquals(listOf<Long>(1, 0, 0, 0, 1, 0, 2, 0, 1, 0, 1), ten.workingDays.histogram.counts())
            assertEquals(5.0, ten.workingDays.histogram.last().from, 1e-9)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
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
        for (bad in listOf("26", "-1", "x", "2.5", "5&tailPercent=6")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cycle-time?tailPercent=$bad").status, "tailPercent=$bad")
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
