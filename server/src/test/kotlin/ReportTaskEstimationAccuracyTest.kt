package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.TaskEstimationAccuracyReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

/** What the report must answer for [tasks], computed independently in the test from the raw fact rows. */
private data class ExpectedAccuracy(
    val atStart: List<Double>,
    val atDone: List<Double>,
    val population: Int,
    val noWorklogs: Int,
    val neverStarted: Int,
    val unestimatedAtStart: Int,
    val unestimatedAtDone: Int,
)

private fun BigDecimal?.positive() = this != null && this.signum() > 0

private fun expectedAccuracy(tasks: List<TaskFact>): ExpectedAccuracy {
    // A task with `has_worklogs` but `actual_md` 0.00 (a minute or two logged) is "no worklogs" too, never a ratio of 0.
    val worked = tasks.filter { it.hasWorklogs && it.actual.signum() > 0 }
    val started = worked.filter { it.startedAt != null }
    val atStart = started.filter { it.estStart.positive() }.map { it.actual.toDouble() / it.estStart!!.toDouble() }
    val atDone = worked.filter { it.estDone.positive() }.map { it.actual.toDouble() / it.estDone!!.toDouble() }
    return ExpectedAccuracy(
        atStart = atStart,
        atDone = atDone,
        population = tasks.size,
        noWorklogs = tasks.size - worked.size,
        neverStarted = worked.size - started.size,
        unestimatedAtStart = started.size - atStart.size,
        unestimatedAtDone = worked.size - atDone.size,
    )
}

/**
 * `GET /api/v1/reports/task-estimation-accuracy` (v0.3.0 M4 commit 12, Report 3, `.claude/docs/measures.md`
 * "Reports 3, 4, 5"). Reads [DerivedStubFixture]'s connection (every request narrows via `connectionId`);
 * every distribution is graded against an INDEPENDENT computation over the raw `fact_task_delivery` rows —
 * own percentile arithmetic, never the report's `buildDistribution`. The exclusion buckets and the minimum
 * sample size are pinned exactly on hand-built rows in a fresh DISABLED connection.
 */
class ReportTaskEstimationAccuracyTest {

    private suspend fun HttpClient.accuracy(query: String): TaskEstimationAccuracyReport {
        val response = get("/api/v1/reports/task-estimation-accuracy?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET task-estimation-accuracy?$query")
        return response.body()
    }

    private fun TaskEstimationAccuracyReport.assertMatches(label: String, expected: ExpectedAccuracy) {
        val minSample = meta.minSampleSize
        assertDistribution("$label atStart", expected.atStart, atStart, minSample)
        assertDistribution("$label atDone", expected.atDone, atDone, minSample)
        assertEquals(expected.population, excluded.population, "$label population")
        assertEquals(expected.noWorklogs, excluded.noWorklogs, "$label noWorklogs")
        assertEquals(expected.neverStarted, excluded.neverStarted, "$label neverStarted")
        assertEquals(expected.unestimatedAtStart, excluded.unestimatedAtStart, "$label unestimatedAtStart")
        assertEquals(expected.unestimatedAtDone, excluded.unestimatedAtDone, "$label unestimatedAtDone")
        // The partition: every DONE task is either in a distribution or in exactly one exclusion bucket.
        assertEquals(excluded.population.toLong(), atStart.n + excluded.noWorklogs + excluded.neverStarted + excluded.unestimatedAtStart)
        assertEquals(excluded.population.toLong(), atDone.n + excluded.noWorklogs + excluded.unestimatedAtDone)
    }

    private suspend fun doneInWindow(connId: UInt, from: String, to: String): List<TaskFact> {
        val bounds = windowBounds(reportZone(), from, to)
        return readTaskFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
    }

    @Test
    fun `UNIT distributions and exclusion counts equal an independent computation and partition the population`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-accuracy-unit")

        for ((from, to) in listOf("2024-01-01" to "2026-12-31", "2025-11-01" to "2025-12-31")) {
            val tasks = doneInWindow(connId, from, to)
            val expected = expectedAccuracy(tasks)
            assertTrue(expected.atStart.isNotEmpty() && expected.atDone.isNotEmpty(), "window $from..$to must yield ratios in both views")
            val body = client.accuracy("connectionId=$connId&from=$from&to=$to")
            body.assertMatches("$from..$to", expected)
            assertEquals(from, body.meta.from)
            assertEquals("TASK", body.meta.domainView.name)
        }
        // The whole fixture must exercise the exclusions the stub generator seeds (unestimated tasks, no worklogs).
        val whole = expectedAccuracy(doneInWindow(connId, "2024-01-01", "2026-12-31"))
        assertTrue(whole.noWorklogs + whole.unestimatedAtStart + whole.unestimatedAtDone > 0, "the stub must carry excluded tasks")
    }

    @Test
    fun `groups at UNIT and TEAM level each equal their own independent computation and sum to the totals`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-accuracy-groups")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val unit = client.accuracy(range)
        assertTrue(unit.groups.isNotEmpty() && unit.groups.all { it.accountId == null }, "UNIT groups are teams")
        assertEquals(unit.atStart.n, unit.groups.sumOf { it.atStart.n }, "UNIT Σ group atStart n")
        assertEquals(unit.atDone.n, unit.groups.sumOf { it.atDone.n }, "UNIT Σ group atDone n")
        assertEquals(unit.excluded.population, unit.groups.sumOf { it.excluded.population })
        assertEquals(unit.excluded.noWorklogs, unit.groups.sumOf { it.excluded.noWorklogs })
        assertEquals(unit.excluded.unestimatedAtStart, unit.groups.sumOf { it.excluded.unestimatedAtStart })
        for (group in unit.groups) {
            val mine = tasks.filter { it.team == group.teamId }
            val expected = expectedAccuracy(mine)
            assertDistribution("team ${group.teamId} atStart", expected.atStart, group.atStart, unit.meta.minSampleSize)
            assertDistribution("team ${group.teamId} atDone", expected.atDone, group.atDone, unit.meta.minSampleSize)
            assertEquals(expected.noWorklogs, group.excluded.noWorklogs)
        }

        val teamId = unit.groups.first { it.teamId != null && it.atStart.n > 0 }.teamId!!
        val team = client.accuracy("$range&teamId=$teamId")
        team.assertMatches("team $teamId", expectedAccuracy(tasks.filter { it.team == teamId }))
        assertTrue(team.groups.isNotEmpty(), "TEAM level groups by assignee at done")
        assertTrue(team.groups.all { it.teamId == null }, "TEAM groups are users")
        assertEquals(team.atStart.n, team.groups.sumOf { it.atStart.n })
        assertEquals(team.atDone.n, team.groups.sumOf { it.atDone.n })
        assertEquals(team.excluded.population, team.groups.sumOf { it.excluded.population })
        for (group in team.groups) {
            val expected = expectedAccuracy(tasks.filter { it.team == teamId && it.account == group.accountId })
            assertDistribution("user ${group.accountId} atStart", expected.atStart, group.atStart, team.meta.minSampleSize)
        }

        // USER level: the one account's own numbers; groups lists that one pick.
        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = client.accuracy("$range&teamId=$teamId&accountId=$account")
        assertEquals(listOf(account), user.groups.map { it.accountId }, "USER level lists the one pick")
        user.assertMatches("user $account", expectedAccuracy(tasks.filter { it.team == teamId && it.account == account }))
    }

    @Test
    fun `teamId 0 selects the UNASSIGNED credit and the activityType and domain slices narrow the population`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-accuracy-slices")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val unassigned = tasks.filter { it.team == null }
        assertTrue(unassigned.isNotEmpty(), "the fixture must carry DONE tasks with no credit team")
        client.accuracy("$range&teamId=0").assertMatches("UNASSIGNED", expectedAccuracy(unassigned))

        val activity = tasks.groupingBy { it.activityType }.eachCount().maxBy { it.value }.key
        client.accuracy("$range&activityType=$activity")
            .assertMatches("activity $activity", expectedAccuracy(tasks.filter { it.activityType == activity }))

        val domain = tasks.mapNotNull { it.domainKey }.groupingBy { it }.eachCount().maxBy { it.value }.key
        client.accuracy("$range&domain=$domain").assertMatches("domain $domain", expectedAccuracy(tasks.filter { it.domainKey == domain }))
    }

    /** The raw per-person ratios of both views for [picks] (request order, label = display name else id), read off the fact rows. */
    private fun rawByUser(tasks: List<TaskFact>, picks: List<String>, names: Map<String, String>, atStart: Boolean) =
        picks.map { id ->
            val mine = expectedAccuracy(tasks.filter { it.account == id })
            Triple(id, names[id] ?: id, if (atStart) mine.atStart else mine.atDone)
        }

    /** The team with the most ratio-bearing tasks among at least two people, and its two busiest people. */
    private fun busiestPair(tasks: List<TaskFact>): Triple<UInt, String, String> {
        val candidates = tasks.filter { it.team != null && it.account != null && expectedAccuracy(listOf(it)).atStart.isNotEmpty() }
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
        val nobody = "task-accuracy-nobody"
        val picks = listOf(b, a, nobody)
        val client = seededClient("reports-task-accuracy-multi")
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=$teamId"
        val both = client.accuracy("$query&accountId=$b&accountId=$a&accountId=$nobody")

        val picked = tasks.filter { it.team == teamId && (it.account == a || it.account == b) }
        val names = displayNamesOf(listOf(a, b))
        // The union: every figure, the exclusion accounting and the accuracy measures equal an independent computation over the
        // picked people's tasks together (the measures are never per person).
        val union = expectedAccuracy(picked)
        both.assertMatches("union", union)
        assertAccuracyMeasures("union atStart", union.atStart, both.accuracyAtStart, both.meta.minSampleSize)
        assertAccuracyMeasures("union atDone", union.atDone, both.accuracyAtDone, both.meta.minSampleSize)
        assertTrue(!both.atStart.hidden && !both.atDone.hidden, "the busiest pair must clear the minimum sample size")
        // Every histogram bucket splits per person (request order, zero entry for the unknown pick, display name else id).
        assertSharesInUnionEdges("atStart", rawByUser(picked, picks, names, atStart = true), both.atStart)
        assertSharesInUnionEdges("atDone", rawByUser(picked, picks, names, atStart = false), both.atDone)
        assertTrue(both.atStart.byUser.last().counts.all { it == 0L }, "the unknown pick is an all-zero share")
        assertEquals(nobody, both.atStart.byUser.last().label, "an account with no known display name is labelled by its id")
        assertTrue(both.atStart.byUser.take(2).all { share -> share.counts.any { it > 0 } }, "each real pick has measured tasks")

        // groups: one per pick (the unknown one empty), the TEAM-level rule per person, plain distributions, label-then-id order.
        assertEquals(setOf(a, b, nobody), both.groups.mapNotNull { it.accountId }.toSet())
        assertEquals(3, both.groups.size)
        assertTrue(both.groups.all { it.teamId == null })
        assertLabelThenIdOrder("USER", both.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
        val min = both.meta.minSampleSize
        for (group in both.groups) {
            val mine = expectedAccuracy(picked.filter { it.account == group.accountId })
            assertDistribution("group ${group.accountId} atStart", mine.atStart, group.atStart, min)
            assertDistribution("group ${group.accountId} atDone", mine.atDone, group.atDone, min)
            assertEquals(mine.noWorklogs, group.excluded.noWorklogs)
            assertEquals(emptyList(), group.atStart.byUser, "a group's distribution is never split")
            assertEquals(emptyList(), group.atDone.byUser)
        }
        assertEquals(both.atStart.n, both.groups.sumOf { it.atStart.n })
        assertEquals(both.excluded.population, both.groups.sumOf { it.excluded.population })
        // A repeated identical value collapses rather than fails.
        assertEquals(client.accuracy("$query&accountId=$a").atStart, client.accuracy("$query&accountId=$a&accountId=$a").atStart)
    }

    @Test
    fun `one accountId keeps today's figures with a one-entry byUser`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")
        val (teamId, a, _) = busiestPair(tasks)
        val client = seededClient("reports-task-accuracy-one-user")
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=$teamId"

        // One person's sample may sit under the fixture's minimum sample size, so it is pinned to 1 to see the figures.
        val one = withMinSampleSize(1) { client.accuracy("$query&accountId=$a") }
        val expected = expectedAccuracy(tasks.filter { it.team == teamId && it.account == a })
        one.assertMatches("user $a", expected)
        assertAccuracyMeasures("user $a atStart", expected.atStart, one.accuracyAtStart, one.meta.minSampleSize)
        assertTrue(!one.atStart.hidden && !one.atDone.hidden, "the busiest person has measured tasks in both views")
        for (distribution in listOf(one.atStart, one.atDone)) {
            val share = distribution.byUser.single()
            assertEquals(a, share.accountId)
            assertEquals(distribution.histogram.map { it.count }, share.counts, "the one share is the whole histogram")
        }
        assertEquals(a, one.groups.single().accountId)
        assertEquals(one.atStart.n, one.groups.single().atStart.n)
        // Below USER level nothing is split: the TEAM and UNIT reads keep today's shape.
        val team = client.accuracy(query)
        assertTrue(team.atStart.byUser.isEmpty() && team.atDone.byUser.isEmpty())
        assertTrue(client.accuracy("connectionId=$connId&from=2024-01-01&to=2026-12-31").atStart.byUser.isEmpty())
    }

    @Test
    fun `at most eight accountIds on task estimation accuracy`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-accuracy-account-bound")
        val (teamId, _, _) = busiestPair(doneInWindow(connId, "2024-01-01", "2026-12-31"))
        fun url(count: Int) = "/api/v1/reports/task-estimation-accuracy?connectionId=$connId&teamId=$teamId" +
            (1..count).joinToString("") { "&accountId=task-accuracy-bound-$it" }

        assertEquals(HttpStatusCode.OK, client.get(url(8)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(url(9)).status)
        val noTeam = client.get("/api/v1/reports/task-estimation-accuracy?connectionId=$connId&accountId=a")
        assertEquals(HttpStatusCode.BadRequest, noTeam.status)
    }

    @Test
    fun `accuracy measures equal an independent computation at UNIT and TEAM level and are null when hidden`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-accuracy-measures")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val tasks = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val unit = client.accuracy(range)
        val unitExpected = expectedAccuracy(tasks)
        assertTrue(unit.accuracyAtStart != null && unit.accuracyAtDone != null, "the whole fixture clears the minimum sample size")
        assertAccuracyMeasures("UNIT atStart", unitExpected.atStart, unit.accuracyAtStart, unit.meta.minSampleSize)
        assertAccuracyMeasures("UNIT atDone", unitExpected.atDone, unit.accuracyAtDone, unit.meta.minSampleSize)

        val (teamId, _, _) = busiestPair(tasks)
        val team = client.accuracy("$range&teamId=$teamId")
        val teamExpected = expectedAccuracy(tasks.filter { it.team == teamId })
        assertAccuracyMeasures("TEAM $teamId atStart", teamExpected.atStart, team.accuracyAtStart, team.meta.minSampleSize)
        assertAccuracyMeasures("TEAM $teamId atDone", teamExpected.atDone, team.accuracyAtDone, team.meta.minSampleSize)
        // Groups carry no measures: they stay a count-and-distribution per entry.

        // Hand-built ratios on a fresh DISABLED connection: 4/3, 1, 3/2, 1/2, 5/4 and 11/10 (MRE .25 .0 .333 1.0 .2 .0909), the
        // first exactly ON the 25% boundary, so PRED(25) = 4/6 only when the boundary counts as within.
        val handConn = SyncedStubFixture.createConnection(namePrefix = "task-accuracy-measures", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val started = noonUtc("2026-01-05")
        val done = noonUtc("2026-01-15")
        val rows = listOf(
            handTask(1, started, done, actualMd = 4.0, estimateAtStartMd = 3.0, estimateAtDoneMd = 3.0),
            handTask(2, started, done, actualMd = 2.0, estimateAtStartMd = 2.0, estimateAtDoneMd = 2.0),
            handTask(3, started, done, actualMd = 3.0, estimateAtStartMd = 2.0, estimateAtDoneMd = 2.0),
            handTask(4, started, done, actualMd = 1.0, estimateAtStartMd = 2.0, estimateAtDoneMd = 2.0),
            handTask(5, started, done, actualMd = 5.0, estimateAtStartMd = 4.0, estimateAtDoneMd = 4.0),
            handTask(6, started, done, actualMd = 11.0, estimateAtStartMd = 10.0, estimateAtDoneMd = 10.0),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(handConn, rows, configRevision = 1L) }
        try {
            val hand = "connectionId=$handConn&from=2026-01-01&to=2026-01-31"
            val visible = withMinSampleSize(6) { client.accuracy(hand) }
            val ratios = listOf(4.0 / 3.0, 1.0, 1.5, 0.5, 1.25, 1.1)
            assertAccuracyMeasures("hand atStart", ratios, visible.accuracyAtStart, 6)
            assertAccuracyMeasures("hand atDone", ratios, visible.accuracyAtDone, 6)
            assertEquals(4.0 / 6.0, visible.accuracyAtStart!!.pred25, 1e-9, "the 25% boundary item counts as within")
            assertEquals(5.0 / 6.0, visible.accuracyAtStart!!.pred50, 1e-9)
            assertEquals(2.0 / 6.0, visible.accuracyAtStart!!.pred10, 1e-9)

            // One short of the minimum: the distribution and its measures are both hidden.
            val hidden = withMinSampleSize(7) { client.accuracy(hand) }
            assertTrue(hidden.atStart.hidden && hidden.atDone.hidden)
            assertNull(hidden.accuracyAtStart)
            assertNull(hidden.accuracyAtDone)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(handConn) }
        }
    }

    /**
     * Hand-computed exclusion precedence (incl. `has_worklogs` with `actual_md` 0.00), the `> 0` estimate guard, the
     * ratios, the minimum sample size and the EPIC domain view (task 1's epic sits in another domain) — on
     * hand-built `fact_task_delivery` rows in a fresh DISABLED connection (cleaned up afterwards), settings pinned
     * to a minimum sample size of 4/5. Population 10 in the window; a sub-task, an open task and a task done
     * outside the window never count.
     */
    @Test
    fun `hand-built rows pin every exclusion bucket, the ratios and the hidden state exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "task-accuracy", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val started = noonUtc("2026-01-05")
        val done = noonUtc("2026-01-15")
        val rows = listOf(
            // start 2.0, done 1.0; its epic is in domain BBB, its own domain AAA
            handTask(1, started, done, actualMd = 4.0, estimateAtStartMd = 2.0, estimateAtDoneMd = 4.0, epicDomain = "BBB"),
            handTask(2, started, done, actualMd = 3.0, estimateAtStartMd = 3.0, estimateAtDoneMd = 2.0), // 1.0, 1.5
            handTask(3, started, done, actualMd = 5.0, estimateAtDoneMd = 5.0, estimatedLate = true), // unestimatedAtStart; done 1.0
            handTask(4, started, done, hasWorklogs = false, estimateAtStartMd = 2.0, estimateAtDoneMd = 2.0), // noWorklogs
            handTask(5, null, done, actualMd = 6.0, estimateAtDoneMd = 3.0), // neverStarted; done 2.0
            handTask(6, started, done, actualMd = 1.0, estimateAtStartMd = 1.0), // start 1.0; unestimatedAtDone
            handTask(7, null, done, hasWorklogs = false), // noWorklogs wins over neverStarted
            handTask(8, started, done, actualMd = 2.0, estimateAtStartMd = 4.0, estimateAtDoneMd = 4.0), // 0.5, 0.5
            // worklogs exist but `actual_md` rounds to 0.00: noWorklogs, never a ratio of exactly 0
            handTask(12, started, done, hasWorklogs = true, actualMd = 0.0, estimateAtStartMd = 2.0, estimateAtDoneMd = 2.0),
            // an estimate of exactly 0.0 is unestimated (the `> 0` guard), in both views
            handTask(13, started, done, actualMd = 2.0, estimateAtStartMd = 0.0, estimateAtDoneMd = 0.0),
            // never counted:
            handTask(9, started, done, actualMd = 9.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 1.0, subtask = true),
            handTask(10, started, null, actualMd = 9.0, estimateAtStartMd = 1.0),
            handTask(11, noonUtc("2025-06-01"), noonUtc("2025-06-15"), actualMd = 9.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 1.0),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-task-accuracy-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"

            val visible = withMinSampleSize(4) { client.accuracy(query) }
            assertEquals(10, visible.excluded.population)
            assertEquals(3, visible.excluded.noWorklogs)
            assertEquals(1, visible.excluded.neverStarted)
            assertEquals(2, visible.excluded.unestimatedAtStart)
            assertEquals(2, visible.excluded.unestimatedAtDone)
            assertDistribution("atStart", listOf(2.0, 1.0, 1.0, 0.5), visible.atStart, 4)
            assertDistribution("atDone", listOf(1.0, 1.5, 1.0, 2.0, 0.5), visible.atDone, 4)
            // Hand-computed: atDone sorted 0.5, 1, 1, 1.5, 2 -> mean 1.2, p50 1.0, p90 1.8.
            assertEquals(1.2, visible.atDone.mean!!, 1e-9)
            assertEquals(1.0, visible.atDone.p50!!, 1e-9)
            assertEquals(1.8, visible.atDone.p90!!, 1e-9)

            // Same rows, minimum sample size 5: atStart (n = 4) is hidden (count only), atDone (n = 5) is not.
            val hidden = withMinSampleSize(5) { client.accuracy(query) }
            assertEquals(4, hidden.atStart.n)
            assertTrue(hidden.atStart.hidden)
            assertNull(hidden.atStart.mean)
            assertTrue(!hidden.atDone.hidden)
            assertEquals(5, hidden.meta.minSampleSize)

            // The team-less rows are all UNASSIGNED, so teamId=0 sees the same ten.
            val unassigned = client.accuracy("$query&teamId=0")
            assertEquals(10, unassigned.excluded.population)
            assertEquals(10, unassigned.groups.sumOf { it.excluded.population })

            // Domain views: task 1 is AAA by its own domain but BBB by its epic's.
            assertEquals(10, client.accuracy("$query&domain=AAA").excluded.population, "TASK view (default): own domain")
            assertEquals(0, client.accuracy("$query&domain=BBB").excluded.population, "TASK view: nobody's own domain is BBB")
            val epicBbb = client.accuracy("$query&domain=BBB&domainView=EPIC")
            assertEquals(1, epicBbb.excluded.population, "EPIC view: only task 1's epic is in BBB")
            assertEquals(1, epicBbb.atStart.n)
            assertEquals("EPIC", epicBbb.meta.domainView.name)
            assertEquals(9, client.accuracy("$query&domain=AAA&domainView=EPIC").excluded.population, "EPIC view: task 1 left AAA")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
        }
    }

    @Test
    fun `lastSprints reads the envelope of the resolved sprints`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-accuracy-window")

        // lastSprints resolves each team's closed sprints; the period is their envelope and meta names them.
        val body = client.accuracy("connectionId=$connId&lastSprints=2")
        assertTrue(body.meta.resolvedSprints.isNotEmpty())
        assertNull(body.meta.from)
        val sprintIds = body.meta.resolvedSprints.flatMap { it.sprintIds }
        val bounds = suspendTransaction(sharedDatabaseForTests()) {
            val d = MetricsTables.DimSprint
            val rows = d.selectAll().where { (d.connectionId eq connId) and (d.sprintId inList sprintIds) }.toList()
            rows.minOf { it[d.startAt] ?: it[d.completeAt]!! } to rows.maxOf { it[d.completeAt]!! } + 1
        }
        val expected = readTaskFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
        body.assertMatches("lastSprints=2 envelope", expectedAccuracy(expected))
    }

    @Test
    fun `bad filters are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-task-accuracy-400")

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/task-estimation-accuracy?from=2026-01-01&to=2025-01-01").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/task-estimation-accuracy?accountId=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/task-estimation-accuracy?connectionId=999999").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/task-estimation-accuracy?teamId=999999").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/task-estimation-accuracy?domainView=NOPE").status)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
