package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.TaskEstimatesReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

/** The derived stub fixture's FLO domain: the scope of every UNIT-level read (a report is never read for the whole unit). */
private const val FLO_DOMAIN = DerivedStubFixture.DOMAIN

private const val DEFAULT_TAIL = 5
private const val FROM = "2024-01-01"
private const val TO = "2026-12-31"

/**
 * `GET /api/v1/reports/task-estimates` (Report 19, `.claude/docs/measures.md` "Report 19"). Reads [DerivedStubFixture]'s connection
 * (every request narrows via `connectionId`); every distribution — statistics AND the fixed 0.5-MD bars — is graded against an
 * INDEPENDENT computation over the raw `fact_task_delivery` rows (`expectedEstimates`, `referenceFixedHistogram`), never the report's
 * `buildDistribution`. The exclusion buckets, the exact bar edges and the minimum sample size are pinned on hand-built rows in a
 * fresh DISABLED connection. The pure bar rule is `FixedBucketDistributionTest`; the drill is `ReportDrillAccuracyBlockedTest`.
 */
class ReportTaskEstimatesTest {

    private suspend fun HttpClient.estimates(query: String): TaskEstimatesReport {
        val response = get("/api/v1/reports/task-estimates?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET task-estimates?$query")
        return response.body()
    }

    private fun TaskEstimatesReport.assertMatches(label: String, expected: ExpectedEstimates, tail: Int = DEFAULT_TAIL) {
        val minSample = meta.minSampleSize
        assertEstimateDistribution("$label atDone", expected.atDone, atDone, minSample, tail)
        assertEstimateDistribution("$label atStart", expected.atStart, atStart, minSample, tail)
        assertEquals(expected.population, excluded.population, "$label population")
        assertEquals(expected.neverStarted, excluded.neverStarted, "$label neverStarted")
        assertEquals(expected.unestimatedAtStart, excluded.unestimatedAtStart, "$label unestimatedAtStart")
        assertEquals(expected.unestimatedAtDone, excluded.unestimatedAtDone, "$label unestimatedAtDone")
        // The partition: every DONE task is either in a distribution or in exactly one exclusion bucket.
        assertEquals(excluded.population.toLong(), atDone.n + excluded.unestimatedAtDone, "$label atDone partition")
        assertEquals(
            excluded.population.toLong(), atStart.n + excluded.neverStarted + excluded.unestimatedAtStart, "$label atStart partition",
        )
    }

    private suspend fun doneInWindow(connId: UInt, from: String, to: String): List<TaskFact> {
        val bounds = windowBounds(reportZone(), from, to)
        return readTaskFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
    }

    /** The done tasks of the stub's FLO domain: the oracle of the UNIT level, which is "a domain with no team" (never the whole unit). */
    private suspend fun doneInDomain(connId: UInt, from: String, to: String): List<TaskFact> =
        doneInWindow(connId, from, to).filter { it.domainKey == FLO_DOMAIN }

    @Test
    fun `UNIT distributions and exclusion counts equal an independent computation and partition the population`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-estimates-unit")

        for ((from, to) in listOf(FROM to TO, "2025-11-01" to "2025-12-31")) {
            val expected = expectedEstimates(doneInDomain(connId, from, to))
            assertTrue(expected.atDone.isNotEmpty() && expected.atStart.isNotEmpty(), "window $from..$to must yield both views")
            // The default tail, no tail and the widest tail each fold (or not) the same values into their own bars.
            for (tail in listOf(null, 0, 25)) {
                val query = "connectionId=$connId&domain=$FLO_DOMAIN&from=$from&to=$to" + (tail?.let { "&tailPercent=$it" } ?: "")
                val body = client.estimates(query)
                body.assertMatches("$from..$to tail=$tail", expected, tail ?: DEFAULT_TAIL)
                assertEquals(from, body.meta.from)
                assertEquals("TASK", body.meta.domainView.name)
            }
        }
        // The whole fixture must exercise the exclusions the stub generator seeds (unestimated tasks, never-started tasks).
        val whole = expectedEstimates(doneInDomain(connId, FROM, TO))
        assertTrue(whole.neverStarted + whole.unestimatedAtStart + whole.unestimatedAtDone > 0, "the stub must carry excluded tasks")
    }

    @Test
    fun `groups at UNIT and TEAM level each equal their own independent computation and sum to the totals`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-estimates-groups")
        val range = "connectionId=$connId&from=$FROM&to=$TO"
        val tasks = doneInWindow(connId, FROM, TO)
        val domainTasks = tasks.filter { it.domainKey == FLO_DOMAIN }

        val unit = client.estimates("$range&domain=$FLO_DOMAIN")
        assertTrue(unit.groups.isNotEmpty() && unit.groups.all { it.accountId == null }, "UNIT groups are teams")
        assertEquals(unit.atDone.n, unit.groups.sumOf { it.atDone.n }, "UNIT Σ group atDone n")
        assertEquals(unit.atStart.n, unit.groups.sumOf { it.atStart.n }, "UNIT Σ group atStart n")
        assertEquals(unit.excluded.population, unit.groups.sumOf { it.excluded.population })
        assertEquals(unit.excluded.neverStarted, unit.groups.sumOf { it.excluded.neverStarted })
        assertEquals(unit.excluded.unestimatedAtDone, unit.groups.sumOf { it.excluded.unestimatedAtDone })
        for (group in unit.groups) {
            val expected = expectedEstimates(domainTasks.filter { it.team == group.teamId })
            val min = unit.meta.minSampleSize
            assertEstimateDistribution("team ${group.teamId} atDone", expected.atDone, group.atDone, min, DEFAULT_TAIL)
            assertEstimateDistribution("team ${group.teamId} atStart", expected.atStart, group.atStart, min, DEFAULT_TAIL)
            assertEquals(expected.neverStarted, group.excluded.neverStarted)
        }

        val teamId = unit.groups.first { it.teamId != null && it.atDone.n > 0 }.teamId!!
        val team = client.estimates("$range&teamId=$teamId")
        team.assertMatches("team $teamId", expectedEstimates(tasks.filter { it.team == teamId }))
        assertTrue(team.groups.isNotEmpty() && team.groups.all { it.teamId == null }, "TEAM groups are users")
        assertEquals(team.atDone.n, team.groups.sumOf { it.atDone.n })
        assertEquals(team.atStart.n, team.groups.sumOf { it.atStart.n })
        assertEquals(team.excluded.population, team.groups.sumOf { it.excluded.population })
        for (group in team.groups) {
            val expected = expectedEstimates(tasks.filter { it.team == teamId && it.account == group.accountId })
            val min = team.meta.minSampleSize
            assertEstimateDistribution("user ${group.accountId} atDone", expected.atDone, group.atDone, min, DEFAULT_TAIL)
        }

        // USER level: the one account's own numbers; groups lists that one pick.
        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = withMinSampleSize(1) { client.estimates("$range&teamId=$teamId&accountId=$account") }
        assertEquals(listOf(account), user.groups.map { it.accountId }, "USER level lists the one pick")
        user.assertMatches("user $account", expectedEstimates(tasks.filter { it.team == teamId && it.account == account }))
    }

    /** The raw per-person values of one view for [picks] (request order, label = display name else id), read off the fact rows. */
    private fun rawByUser(tasks: List<TaskFact>, picks: List<String>, names: Map<String, String>, atDone: Boolean) =
        picks.map { id ->
            val mine = expectedEstimates(tasks.filter { it.account == id })
            Triple(id, names[id] ?: id, if (atDone) mine.atDone else mine.atStart)
        }

    /** The team with the most started and estimated tasks among at least two people, and its two busiest people. */
    private fun busiestPair(tasks: List<TaskFact>): Triple<UInt, String, String> {
        val candidates = tasks.filter { it.team != null && it.account != null && expectedEstimates(listOf(it)).atStart.isNotEmpty() }
            .groupBy { it.team!! }.filterValues { rows -> rows.mapNotNull { it.account }.distinct().size >= 2 }
        val (team, rows) = candidates.maxByOrNull { it.value.size } ?: error("the stub must carry a team with two measured people")
        val (a, b) = rows.groupingBy { it.account!! }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
        return Triple(team, a, b)
    }

    @Test
    fun `several accountIds read the union, split every histogram bucket per person and list each pick in groups`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val tasks = doneInWindow(connId, FROM, TO)
        val (teamId, a, b) = busiestPair(tasks)
        val nobody = "task-estimates-nobody"
        val picks = listOf(b, a, nobody)
        val client = seededClient("reports-task-estimates-multi")
        val query = "connectionId=$connId&from=$FROM&to=$TO&teamId=$teamId"
        val both = client.estimates("$query&accountId=$b&accountId=$a&accountId=$nobody")

        val picked = tasks.filter { it.team == teamId && (it.account == a || it.account == b) }
        val names = displayNamesOf(listOf(a, b))
        // The union: every figure and the exclusion accounting equal an independent computation over the picked people together.
        both.assertMatches("union", expectedEstimates(picked))
        assertTrue(!both.atDone.hidden && !both.atStart.hidden, "the busiest pair must clear the minimum sample size")
        // Every bar splits per person (request order, zero entry for the unknown pick, display name else id), placed exactly.
        assertFixedShares("atDone", rawByUser(picked, picks, names, atDone = true), both.atDone)
        assertFixedShares("atStart", rawByUser(picked, picks, names, atDone = false), both.atStart)
        assertTrue(both.atDone.byUser.last().counts.all { it == 0L }, "the unknown pick is an all-zero share")
        assertEquals(nobody, both.atDone.byUser.last().label, "an account with no known display name is labelled by its id")
        assertTrue(both.atDone.byUser.take(2).all { share -> share.counts.any { it > 0 } }, "each real pick has estimated tasks")

        // groups: one per pick (the unknown one empty), plain (unsplit) distributions, label-then-id order.
        assertEquals(setOf(a, b, nobody), both.groups.mapNotNull { it.accountId }.toSet())
        assertEquals(3, both.groups.size)
        assertLabelThenIdOrder("USER", both.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
        val min = both.meta.minSampleSize
        for (group in both.groups) {
            val mine = expectedEstimates(picked.filter { it.account == group.accountId })
            assertEstimateDistribution("group ${group.accountId} atDone", mine.atDone, group.atDone, min, DEFAULT_TAIL)
            assertEstimateDistribution("group ${group.accountId} atStart", mine.atStart, group.atStart, min, DEFAULT_TAIL)
            assertEquals(mine.neverStarted, group.excluded.neverStarted)
            assertEquals(emptyList(), group.atDone.byUser, "a group's distribution is never split")
        }
        assertEquals(both.atDone.n, both.groups.sumOf { it.atDone.n })
        assertEquals(both.excluded.population, both.groups.sumOf { it.excluded.population })
        // A repeated identical value collapses rather than fails.
        assertEquals(client.estimates("$query&accountId=$a").atDone, client.estimates("$query&accountId=$a&accountId=$a").atDone)
    }

    @Test
    fun `one accountId keeps today's figures with a one-entry byUser`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val tasks = doneInWindow(connId, FROM, TO)
        val (teamId, a, _) = busiestPair(tasks)
        val client = seededClient("reports-task-estimates-one-user")
        val query = "connectionId=$connId&from=$FROM&to=$TO&teamId=$teamId"

        // One person's sample may sit under the fixture's minimum sample size, so it is pinned to 1 to see the figures.
        val one = withMinSampleSize(1) { client.estimates("$query&accountId=$a") }
        one.assertMatches("user $a", expectedEstimates(tasks.filter { it.team == teamId && it.account == a }))
        assertTrue(!one.atDone.hidden && !one.atStart.hidden, "the busiest person has estimated tasks in both views")
        for (distribution in listOf(one.atDone, one.atStart)) {
            val share = distribution.byUser.single()
            assertEquals(a, share.accountId)
            assertEquals(distribution.histogram.map { it.count }, share.counts, "the one share is the whole histogram")
        }
        assertEquals(a, one.groups.single().accountId)
        // Below USER level nothing is split: the TEAM and UNIT reads keep today's shape.
        assertTrue(client.estimates(query).atDone.byUser.isEmpty() && client.estimates(query).atStart.byUser.isEmpty())
        assertTrue(client.estimates("connectionId=$connId&domain=$FLO_DOMAIN&from=$FROM&to=$TO").atDone.byUser.isEmpty())
    }

    @Test
    fun `at most eight accountIds on task estimates`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-estimates-account-bound")
        val (teamId, _, _) = busiestPair(doneInWindow(connId, FROM, TO))
        fun url(count: Int) = "/api/v1/reports/task-estimates?connectionId=$connId&teamId=$teamId" +
            (1..count).joinToString("") { "&accountId=task-estimates-bound-$it" }

        assertEquals(HttpStatusCode.OK, client.get(url(8)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(url(9)).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.get("/api/v1/reports/task-estimates?connectionId=$connId&domain=$FLO_DOMAIN&accountId=a").status,
        )
        // The other filters answer 400 exactly like every report's parser does.
        val scope = "domain=$FLO_DOMAIN"
        for (bad in listOf("from=2026-01-01&to=2025-01-01", "connectionId=999999", "teamId=999999", "domainView=NOPE", "tailPercent=26")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/task-estimates?$scope&$bad").status, bad)
        }
    }

    @Test
    fun `only task estimates sets bucketWidth - every other report keeps its distributions as they were`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-task-estimates-width")
        val range = "connectionId=$connId&domain=$FLO_DOMAIN&from=$FROM&to=$TO"
        val own = client.get("/api/v1/reports/task-estimates?$range").bodyAsText()
        assertTrue("\"bucketWidth\":0.5" in own, "task estimates carries the width of its bars")
        for (report in listOf(
            "task-estimation-accuracy", "epic-estimation-accuracy", "estimate-adjustments", "cycle-time", "reported-time-ratio",
            "blocked-time", "data-quality", "wip",
        )) {
            assertFalse("bucketWidth" in client.get("/api/v1/reports/$report?$range").bodyAsText(), "$report must not carry bucketWidth")
        }
    }

    /**
     * Hand-computed exclusion precedence, the MD values and the EXACT bars, on hand-built `fact_task_delivery` rows in a fresh
     * DISABLED connection (cleaned up afterwards), the minimum sample size pinned. Population 9 in the window; a sub-task, an open task
     * and a task done outside the window never count. A task WITHOUT worklogs is in both views (the measure is the estimate itself).
     */
    @Test
    fun `hand-built rows pin the exclusion buckets, the MD values and the hidden state exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "task-estimates", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val started = noonUtc("2026-01-05")
        val done = noonUtc("2026-01-15")
        val rows = listOf(
            handTask(1, started, done, hasWorklogs = false, estimateAtStartMd = 2.0, estimateAtDoneMd = 4.0), // no worklogs: IN, 2.0 / 4.0
            handTask(2, started, done, actualMd = 1.0, estimateAtStartMd = 0.1, estimateAtDoneMd = 0.5), // 0.1 / 0.5
            handTask(3, started, done, actualMd = 7.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 1.0), // 1.0 / 1.0
            handTask(4, started, done, actualMd = 1.0, estimatedLate = true, estimateAtDoneMd = 1.5), // unestimatedAtStart; done 1.5
            handTask(5, null, done, actualMd = 1.0, estimateAtStartMd = 2.0, estimateAtDoneMd = 3.0), // neverStarted wins; done 3.0
            handTask(6, started, done, actualMd = 1.0, estimateAtStartMd = 1.0), // start 1.0; unestimatedAtDone
            handTask(7, started, done, actualMd = 1.0, estimateAtStartMd = 0.0, estimateAtDoneMd = 0.0), // 0 = no estimate, both views
            handTask(8, started, done, actualMd = 1.0), // no estimate at all
            handTask(12, started, done, actualMd = 1.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 2.0), // 1.0 / 2.0
            // never counted:
            handTask(9, started, done, actualMd = 9.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 1.0, subtask = true),
            handTask(10, started, null, actualMd = 9.0, estimateAtStartMd = 1.0),
            handTask(11, noonUtc("2025-06-01"), noonUtc("2025-06-15"), actualMd = 9.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 1.0),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-task-estimates-hand-built")
            val period = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            // The TASK view of domain AAA reads all nine (the rows' own domain); the BBB check below names its own.
            val query = "$period&domain=AAA"

            val visible = withMinSampleSize(5) { client.estimates("$query&tailPercent=0") }
            assertEquals(9, visible.excluded.population)
            assertEquals(1, visible.excluded.neverStarted)
            assertEquals(3, visible.excluded.unestimatedAtStart)
            assertEquals(3, visible.excluded.unestimatedAtDone)
            val atDone = listOf(4.0, 0.5, 1.0, 1.5, 3.0, 2.0)
            val atStart = listOf(2.0, 0.1, 1.0, 1.0, 1.0)
            assertEstimateDistribution("atDone", atDone, visible.atDone, 5, 0)
            assertEstimateDistribution("atStart", atStart, visible.atStart, 5, 0)
            // Hand-computed. atDone 0.5 1 1.5 2 3 4: mean 2, p50 1.75, p90 3.5, p95 3.75. atStart 0.1 1 1 1 2: mean 1.02, p50 1, p90 1.6.
            assertEquals(2.0, visible.atDone.mean!!, 1e-9)
            assertEquals(1.75, visible.atDone.p50!!, 1e-9)
            assertEquals(3.5, visible.atDone.p90!!, 1e-9)
            assertEquals(3.75, visible.atDone.p95!!, 1e-9)
            assertEquals(0.5, visible.atDone.min!!, 1e-9)
            assertEquals(4.0, visible.atDone.max!!, 1e-9)
            assertEquals(1.02, visible.atStart.mean!!, 1e-9)
            assertEquals(1.0, visible.atStart.p50!!, 1e-9)
            assertEquals(1.6, visible.atStart.p90!!, 1e-9)
            // The exact bars, (from, to] and labelled by the upper bound: the exact 0.5 and 1.0 sit on their own bars, 0.1 on "0.5".
            assertEquals(0.5, visible.atDone.bucketWidth)
            assertEquals(listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0), visible.atDone.histogram.map { it.to })
            assertEquals(listOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5), visible.atDone.histogram.map { it.from })
            assertEquals(listOf(1L, 1L, 1L, 1L, 0L, 1L, 0L, 1L), visible.atDone.histogram.map { it.count })
            assertEquals(listOf(0.5, 1.0, 1.5, 2.0), visible.atStart.histogram.map { it.to })
            assertEquals(listOf(1L, 3L, 0L, 1L), visible.atStart.histogram.map { it.count })
            // The widest tail: the cut 2.75 rounds up to 3.0, so 4.0 folds into the open bar and the 3.0 stays on the (2.5, 3] bar.
            val tailed = withMinSampleSize(5) { client.estimates("$query&tailPercent=25") }
            assertEquals(listOf(1L, 1L, 1L, 1L, 0L, 1L), tailed.atDone.histogram.take(6).map { it.count })
            assertEquals(3.0, tailed.atDone.histogram.last().from)
            assertNull(tailed.atDone.histogram.last().to)
            assertEquals(1L, tailed.atDone.histogram.last().count)
            assertEquals(7, tailed.atDone.histogram.size)

            // Same rows, minimum sample size 6: atStart (n = 5) is hidden (count only, no width), atDone (n = 6) is not.
            val hidden = withMinSampleSize(6) { client.estimates(query) }
            assertEquals(5, hidden.atStart.n)
            assertTrue(hidden.atStart.hidden)
            assertNull(hidden.atStart.mean)
            assertNull(hidden.atStart.bucketWidth)
            assertTrue(hidden.atStart.histogram.isEmpty())
            assertTrue(!hidden.atDone.hidden)
            assertEquals(6, hidden.meta.minSampleSize)

            // The team-less rows are all UNASSIGNED, so teamId=0 sees the same nine, in one group.
            val unassigned = client.estimates("$query&teamId=0")
            assertEquals(9, unassigned.excluded.population)
            assertEquals(9, unassigned.groups.sumOf { it.excluded.population })
            // Domain views: the rows' own domain is AAA.
            assertEquals(9, client.estimates(query).excluded.population)
            assertEquals(0, client.estimates("$period&domain=BBB").excluded.population)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
