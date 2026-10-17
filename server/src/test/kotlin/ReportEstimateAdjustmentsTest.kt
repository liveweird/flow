package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.AdjustmentFigures
import ch.nokillswit.reports.EstimateAdjustmentsReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.BeforeClass

/** A minimal slice of `sample-data/jira/expected.json`'s `estimates` — the generator's own change/late counts. */
@Serializable
internal data class GeneratorEstimates(
    val taskEstimateChangedAfterStartCount: Int,
    val taskEstimatedLateCount: Int,
    val taskEstimatedAfterDoneCount: Int,
)

@Serializable
private data class GeneratorExpected(val estimates: GeneratorEstimates)

private val ESTIMATES_JSON = Json { ignoreUnknownKeys = true }

internal val generatorEstimates: GeneratorEstimates by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    ESTIMATES_JSON.decodeFromString<GeneratorExpected>(file.readText()).estimates
}

/** One task or epic in the shape the independent computation needs (the stored flags; [estimateFlagsOf] re-derives them). */
private data class Item(
    val startedAt: Long?,
    val doneAt: Long?,
    val changes: Int,
    val estimatedLate: Boolean,
    val estimatedAfterDone: Boolean,
    val atStart: BigDecimal?,
    val atDone: BigDecimal?,
)

private fun TaskFact.item() = Item(startedAt, doneAt, changes, estimatedLate, estimatedAfterDone, estStart, estDone)

private fun EpicFact.item() = Item(startedAt, doneAt, changes, estimatedLate, estimatedAfterDone, atStart = ownStart, atDone = ownDone)

/**
 * The two flags re-derived from the stored estimates alone (A35), never from the kernel: late = started with no estimate at start but
 * one at done (done item) or now (open item); after done = done with no estimate at start and none at done but one now.
 */
private fun estimateFlagsOf(
    startedAt: Long?,
    doneAt: Long?,
    atStart: BigDecimal?,
    atDone: BigDecimal?,
    current: BigDecimal?,
): Pair<Boolean, Boolean> {
    // The kernel's "has an estimate" is `!= null` over a timeline that already maps 0 to null; the stored decimal(8,2) is the same
    // test (a positive value) -- 0 means unestimated (domain-model).
    fun BigDecimal?.has() = this != null && this.signum() > 0
    val late = startedAt != null && !atStart.has() && (if (doneAt != null) atDone.has() else current.has())
    val afterDone = doneAt != null && !atStart.has() && !atDone.has() && current.has()
    return late to afterDone
}

/** What one item kind's figures must be for [items] under [window] — computed straight from the raw rows. */
private data class ExpectedFigures(
    val started: Int,
    val changed: Int,
    val late: Int,
    /** The non-zero changes only: the items whose estimate did not move are [unchanged], never charted. */
    val changes: List<Double>,
    val unchanged: Int,
    val population: Int,
    val lateDone: Int,
    val afterDone: Int,
    val unestimated: Int,
)

private fun expectedFigures(items: List<Item>, window: Pair<Long, Long>): ExpectedFigures {
    fun Long?.inside() = this != null && this >= window.first && this < window.second
    val started = items.filter { it.startedAt.inside() }
    val done = items.filter { it.doneAt.inside() }
    val positive: (BigDecimal?) -> Boolean = { it != null && it.signum() > 0 }
    val measurable = done.filter { !it.estimatedLate && positive(it.atStart) && positive(it.atDone) }
    return ExpectedFigures(
        started = started.size,
        changed = started.count { it.changes > 0 },
        late = started.count { it.estimatedLate },
        changes = measurable.filter { it.atDone!!.compareTo(it.atStart!!) != 0 }
            .map { (it.atDone!!.toDouble() - it.atStart!!.toDouble()) / it.atStart.toDouble() },
        unchanged = measurable.count { it.atDone!!.compareTo(it.atStart!!) == 0 },
        population = done.size,
        lateDone = done.count { it.estimatedLate },
        afterDone = done.count { it.estimatedAfterDone },
        unestimated = done.count { !it.estimatedLate } - measurable.size,
    )
}

/**
 * `GET /api/v1/reports/estimate-adjustments` (v0.3.0 M4 commit 12, Report 5, `.claude/docs/measures.md`
 * "Reports 3, 4, 5"). The stub fixture grades every figure against an INDEPENDENT computation over the raw
 * `fact_task_delivery`/`fact_epic_delivery` rows and against the stub generator's own estimated-late and
 * changed-after-start counts; the started/done populations, the estimated-late separation and the org drill are
 * pinned exactly on hand-built rows in a fresh DISABLED connection.
 */
class ReportEstimateAdjustmentsTest {

    private suspend fun HttpClient.adjustments(query: String): EstimateAdjustmentsReport {
        val response = get("/api/v1/reports/estimate-adjustments?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET estimate-adjustments?$query")
        return response.body()
    }

    private fun AdjustmentFigures.assertMatches(label: String, expected: ExpectedFigures, minSample: Int) {
        assertEquals(expected.started, started, "$label started")
        assertEquals(expected.changed, changedAfterStart, "$label changedAfterStart")
        assertEquals(expected.late, estimatedLate, "$label estimatedLate")
        if (expected.started < minSample) {
            assertNull(share, "$label share is hidden below the minimum sample size")
        } else {
            assertEquals(expected.changed.toDouble() / expected.started, share!!, 1e-12, "$label share")
        }
        assertDistribution("$label changeDistribution", expected.changes, changeDistribution, minSample)
        assertEquals(expected.population, changeExcluded.population, "$label population")
        assertEquals(expected.unchanged, changeExcluded.unchanged, "$label excluded unchanged")
        assertEquals(expected.lateDone, changeExcluded.estimatedLate, "$label excluded estimatedLate")
        assertEquals(expected.afterDone, changeExcluded.estimatedAfterDone, "$label excluded estimatedAfterDone")
        assertEquals(expected.unestimated, changeExcluded.unestimated, "$label excluded unestimated")
        assertTrue(changeExcluded.estimatedAfterDone <= changeExcluded.unestimated, "$label afterDone is a breakout of unestimated")
        assertEquals(
            changeExcluded.population.toLong(),
            changeDistribution.n + changeExcluded.unchanged + changeExcluded.estimatedLate + changeExcluded.unestimated,
        )
    }

    @Test
    fun `task and epic figures equal an independent computation and the generator's own estimate counts`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-adjustments-unit")
        val zone = reportZone()
        val tasks = readTaskFacts(connId).map { it.item() }
        val epics = readEpicFacts(connId).map { it.item() }

        for ((from, to) in listOf("2024-01-01" to "2026-12-31", "2025-10-01" to "2025-12-31")) {
            val window = windowBounds(zone, from, to)
            val body = client.adjustments("connectionId=$connId&from=$from&to=$to")
            val min = body.meta.minSampleSize
            body.tasks.assertMatches("tasks $from..$to", expectedFigures(tasks, window), min)
            body.epics.assertMatches("epics $from..$to", expectedFigures(epics, window), min)
            assertEquals("TASK", body.meta.domainView.name)
        }

        // The whole fixture against the stub generator's own figures: its "late" stories are exactly the estimated-late
        // tasks, and its "change" stories plus those late ones (a late estimate is itself a change after start) are the
        // tasks with an estimate change after start.
        val whole = client.adjustments("connectionId=$connId&from=2024-01-01&to=2026-12-31")
        assertTrue(whole.tasks.estimatedLate > 0 && whole.tasks.changedAfterStart > 0, "the stub must carry adjusted tasks")
        assertEquals(generatorEstimates.taskEstimatedLateCount, whole.tasks.estimatedLate, "estimated-late tasks vs expected.json")
        assertEquals(
            generatorEstimates.taskEstimateChangedAfterStartCount + generatorEstimates.taskEstimatedLateCount,
            whole.tasks.changedAfterStart,
            "changed-after-start tasks vs expected.json (late estimates count as changes after start)",
        )
        // The backfill cohort (A35): estimated only after done_at, so unestimated here (never late, never a change after start).
        assertEquals(
            generatorEstimates.taskEstimatedAfterDoneCount,
            whole.tasks.changeExcluded.estimatedAfterDone,
            "after-done tasks vs expected.json",
        )
        assertTrue(whole.tasks.changeExcluded.estimatedAfterDone > 0, "the stub must carry backfilled tasks")
    }

    @Test
    fun `the stored estimate flags equal a re-derivation from the stored estimates, and the two are exclusive (A35)`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val tasks = readTaskFacts(connId)
        val epics = readEpicFacts(connId)
        assertTrue(tasks.isNotEmpty() && epics.isNotEmpty())
        for (t in tasks) {
            val (late, afterDone) = estimateFlagsOf(t.startedAt, t.doneAt, t.estStart, t.estDone, t.estCurrent)
            assertEquals(late, t.estimatedLate, "task ${t.issueId} estimatedLate")
            assertEquals(afterDone, t.estimatedAfterDone, "task ${t.issueId} estimatedAfterDone")
            assertTrue(!(t.estimatedLate && t.estimatedAfterDone), "task ${t.issueId}: late and after-done are exclusive")
            if (t.estimatedAfterDone) assertEquals(0, t.changes, "task ${t.issueId}: a backfill has no adjustment while worked on")
        }
        for (e in epics) {
            val (late, afterDone) = estimateFlagsOf(e.startedAt, e.doneAt, e.ownStart, e.ownDone, e.ownCurrent)
            assertEquals(late, e.estimatedLate, "epic ${e.issueId} estimatedLate")
            assertEquals(afterDone, e.estimatedAfterDone, "epic ${e.issueId} estimatedAfterDone")
        }
        assertEquals(generatorEstimates.taskEstimatedAfterDoneCount, tasks.count { it.estimatedAfterDone && it.doneAt != null })
    }

    @Test
    fun `groups sum to the totals and each equals its own independent computation`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-adjustments-groups")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val window = windowBounds(reportZone(), "2024-01-01", "2026-12-31")
        val taskFacts = readTaskFacts(connId)
        val epicFacts = readEpicFacts(connId)
        fun tasks(keep: (TaskFact) -> Boolean) = taskFacts.filter(keep).map { it.item() }
        fun epics(keep: (EpicFact) -> Boolean) = epicFacts.filter(keep).map { it.item() }

        val unit = client.adjustments(range)
        val min = unit.meta.minSampleSize
        assertTrue(unit.groups.isNotEmpty() && unit.groups.all { it.accountId == null })
        assertEquals(unit.tasks.started, unit.groups.sumOf { it.tasks.started })
        assertEquals(unit.tasks.changedAfterStart, unit.groups.sumOf { it.tasks.changedAfterStart })
        assertEquals(unit.tasks.estimatedLate, unit.groups.sumOf { it.tasks.estimatedLate })
        assertEquals(unit.tasks.changeDistribution.n, unit.groups.sumOf { it.tasks.changeDistribution.n })
        assertEquals(unit.tasks.changeExcluded.population, unit.groups.sumOf { it.tasks.changeExcluded.population })
        assertEquals(unit.tasks.changeExcluded.unchanged, unit.groups.sumOf { it.tasks.changeExcluded.unchanged })
        assertTrue(unit.tasks.changeExcluded.unchanged > 0, "the stub must carry measurable tasks whose estimate did not change")
        assertEquals(unit.epics.started, unit.groups.sumOf { it.epics!!.started })
        assertEquals(unit.epics.changeDistribution.n, unit.groups.sumOf { it.epics!!.changeDistribution.n })
        for (group in unit.groups) {
            val label = "team ${group.teamId}"
            group.tasks.assertMatches("$label tasks", expectedFigures(tasks { it.team == group.teamId }, window), min)
            group.epics!!.assertMatches("$label epics", expectedFigures(epics { it.owner == group.teamId }, window), min)
        }

        val teamId = unit.groups.first { it.teamId != null && it.tasks.started > 0 }.teamId!!
        val team = client.adjustments("$range&teamId=$teamId")
        team.tasks.assertMatches("TEAM tasks", expectedFigures(taskFacts.filter { it.team == teamId }.map { it.item() }, window), min)
        team.epics.assertMatches("TEAM epics", expectedFigures(epicFacts.filter { it.owner == teamId }.map { it.item() }, window), min)
        assertTrue(team.groups.isNotEmpty(), "TEAM level groups by assignee at done")
        assertTrue(team.groups.all { it.teamId == null && it.epics == null }, "TEAM groups are users, tasks only")
        assertEquals(team.tasks.started, team.groups.sumOf { it.tasks.started })
        assertEquals(team.tasks.changeDistribution.n, team.groups.sumOf { it.tasks.changeDistribution.n })
        assertEquals(team.tasks.changeExcluded.population, team.groups.sumOf { it.tasks.changeExcluded.population })

        val account = team.groups.first { it.accountId != null && it.tasks.started > 0 }.accountId!!
        val user = client.adjustments("$range&teamId=$teamId&accountId=$account")
        assertEquals(listOf(account), user.groups.map { it.accountId }, "USER level lists the one pick")
        user.tasks.assertMatches("USER tasks", expectedFigures(tasks { it.team == teamId && it.account == account }, window), min)
        assertEquals(0, user.epics.started, "epics are not attributed to users")
        assertEquals(0, user.epics.changeExcluded.population)

        val unassigned = client.adjustments("$range&teamId=0")
        unassigned.tasks.assertMatches("UNASSIGNED tasks", expectedFigures(tasks { it.team == null }, window), min)
        unassigned.epics.assertMatches("UNOWNED epics", expectedFigures(epics { it.owner == null }, window), min)
    }

    @Test
    fun `activityType and domain slices narrow the task population, the domain the epic population`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-adjustments-slices")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val window = windowBounds(reportZone(), "2024-01-01", "2026-12-31")
        val taskFacts = readTaskFacts(connId)
        val epicFacts = readEpicFacts(connId)
        fun tasks(keep: (TaskFact) -> Boolean) = taskFacts.filter(keep).map { it.item() }
        fun epics(keep: (EpicFact) -> Boolean) = epicFacts.filter(keep).map { it.item() }

        val activity = taskFacts.groupingBy { it.activityType }.eachCount().maxBy { it.value }.key
        val byActivity = client.adjustments("$range&activityType=$activity")
        val activityMin = byActivity.meta.minSampleSize
        byActivity.tasks.assertMatches("activity $activity", expectedFigures(tasks { it.activityType == activity }, window), activityMin)
        assertEquals(
            epicFacts.count { it.startedAt != null && it.startedAt >= window.first && it.startedAt < window.second },
            byActivity.epics.started,
            "activityType is not an epic attribute and never narrows epics",
        )

        val domain = taskFacts.mapNotNull { it.domainKey }.groupingBy { it }.eachCount().maxBy { it.value }.key
        val byDomain = client.adjustments("$range&domain=$domain")
        val min = byDomain.meta.minSampleSize
        byDomain.tasks.assertMatches("domain $domain tasks", expectedFigures(tasks { it.domainKey == domain }, window), min)
        byDomain.epics.assertMatches("domain $domain epics", expectedFigures(epics { it.domainKey == domain }, window), min)
    }

    private suspend fun displayNames(accountIds: List<String>): Map<String, String> = suspendTransaction(sharedDatabaseForTests()) {
        WorkItemStore.People.selectAll().where { WorkItemStore.People.accountId inList accountIds }.toList()
            .associate { it[WorkItemStore.People.accountId] to it[WorkItemStore.People.displayName] }
    }

    /** The team whose two busiest people hold the most measurable changes together (at least two people), and those two (busiest first). */
    private fun busiestPair(tasks: List<TaskFact>, window: Pair<Long, Long>): Triple<UInt, String, String> {
        val measured = tasks.filter { it.team != null && it.account != null }
            .filter { expectedFigures(listOf(it.item()), window).changes.isNotEmpty() }
        val pairs = measured.groupBy { it.team!! }.mapNotNull { (team, rows) ->
            val top = rows.groupingBy { it.account!! }.eachCount().entries.sortedByDescending { it.value }.take(2)
            if (top.size < 2) null else Triple(team, top.map { it.key }, top.sumOf { it.value })
        }
        val (team, people, _) = pairs.maxByOrNull { it.third } ?: error("the stub must carry a team with two measured people")
        return Triple(team, people[0], people[1])
    }

    @Test
    fun `several accountIds read the union, split every histogram bucket per person and list each pick in groups`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-adjustments-multi")
        val window = windowBounds(reportZone(), "2024-01-01", "2026-12-31")
        val taskFacts = readTaskFacts(connId)
        val (teamId, a, b) = busiestPair(taskFacts, window)
        val nobody = "adjustments-nobody"
        val picks = listOf(b, a, nobody)
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=$teamId"
        // The stub has very few ADJUSTED tasks (the unchanged ones are not charted), so the minimum sample size is pinned to 1.
        val both = withMinSampleSize(1) { client.adjustments("$query&accountId=$b&accountId=$a&accountId=$nobody") }

        val picked = taskFacts.filter { it.team == teamId && (it.account == a || it.account == b) }
        val names = displayNames(listOf(a, b))
        val min = both.meta.minSampleSize
        // The union: every figure equals an independent computation over the picked people's tasks together.
        both.tasks.assertMatches("union", expectedFigures(picked.map { it.item() }, window), min)
        assertTrue(!both.tasks.changeDistribution.hidden, "the busiest pair must clear the minimum sample size")
        // Every histogram bucket splits per person (request order, zero entry for the unknown pick, display name else id).
        val raw = picks.map { id ->
            Triple(id, names[id] ?: id, expectedFigures(picked.filter { it.account == id }.map { it.item() }, window).changes)
        }
        assertSharesInUnionEdges("changeDistribution", raw, both.tasks.changeDistribution)
        assertTrue(both.tasks.changeDistribution.byUser.last().counts.all { it == 0L }, "the unknown pick is an all-zero share")
        assertEquals(nobody, both.tasks.changeDistribution.byUser.last().label, "an unknown account is labelled by its id")
        assertTrue(both.tasks.changeDistribution.byUser.take(2).all { share -> share.counts.any { it > 0 } }, "each real pick has changes")

        // groups: one per pick (the unknown one empty), the TEAM-level rule per person, plain distributions, label-then-id order.
        assertEquals(3, both.groups.size)
        assertEquals(setOf(a, b, nobody), both.groups.mapNotNull { it.accountId }.toSet())
        assertTrue(both.groups.all { it.teamId == null && it.epics == null }, "USER groups are people, tasks only")
        assertLabelThenIdOrder("USER", both.groups.map { GroupIdentity(it.label, it.teamId, it.accountId) })
        for (group in both.groups) {
            val mine = picked.filter { it.account == group.accountId }
            group.tasks.assertMatches("group ${group.accountId}", expectedFigures(mine.map { it.item() }, window), min)
            assertEquals(emptyList(), group.tasks.changeDistribution.byUser, "a group's distribution is never split")
        }
        assertEquals(both.tasks.started, both.groups.sumOf { it.tasks.started })
        assertEquals(both.tasks.changeDistribution.n, both.groups.sumOf { it.tasks.changeDistribution.n })
        assertEquals(both.tasks.changeExcluded.population, both.groups.sumOf { it.tasks.changeExcluded.population })
        assertEquals(both.tasks.changeExcluded.unchanged, both.groups.sumOf { it.tasks.changeExcluded.unchanged })
        // Epics carry no user: the block stays all-zero whatever the picks.
        assertEquals(0, both.epics.started)
        assertEquals(0, both.epics.changeExcluded.population)
        assertTrue(both.epics.changeDistribution.hidden && both.epics.changeDistribution.byUser.isEmpty())
        // A repeated identical value collapses rather than fails.
        withMinSampleSize(1) {
            assertEquals(client.adjustments("$query&accountId=$a").tasks, client.adjustments("$query&accountId=$a&accountId=$a").tasks)
        }
    }

    @Test
    fun `one accountId keeps today's figures with a one-entry byUser`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-adjustments-one-user")
        val window = windowBounds(reportZone(), "2024-01-01", "2026-12-31")
        val taskFacts = readTaskFacts(connId)
        val (teamId, a, _) = busiestPair(taskFacts, window)
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31&teamId=$teamId"

        val one = withMinSampleSize(1) { client.adjustments("$query&accountId=$a") }
        val mine = taskFacts.filter { it.team == teamId && it.account == a }.map { it.item() }
        one.tasks.assertMatches("user $a", expectedFigures(mine, window), one.meta.minSampleSize)
        assertTrue(!one.tasks.changeDistribution.hidden, "the busiest person must clear the minimum sample size")
        val share = one.tasks.changeDistribution.byUser.single()
        assertEquals(a, share.accountId)
        assertEquals(one.tasks.changeDistribution.histogram.map { it.count }, share.counts, "the one share is the whole histogram")
        assertEquals(a, one.groups.single().accountId)
        assertEquals(one.tasks.started, one.groups.single().tasks.started)
        // Below USER level nothing is split: the TEAM and UNIT reads keep today's shape.
        assertTrue(client.adjustments(query).tasks.changeDistribution.byUser.isEmpty())
        assertTrue(client.adjustments("connectionId=$connId&from=2024-01-01&to=2026-12-31").tasks.changeDistribution.byUser.isEmpty())
    }

    @Test
    fun `at most eight accountIds on estimate adjustments`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-adjustments-account-bound")
        val (teamId, _, _) = busiestPair(readTaskFacts(connId), windowBounds(reportZone(), "2024-01-01", "2026-12-31"))
        fun url(count: Int) = "/api/v1/reports/estimate-adjustments?connectionId=$connId&teamId=$teamId" +
            (1..count).joinToString("") { "&accountId=adjustments-bound-$it" }

        assertEquals(HttpStatusCode.OK, client.get(url(8)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get(url(9)).status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/estimate-adjustments?connectionId=$connId&accountId=a").status)
    }

    /**
     * Hand-computed populations on hand-built rows (settings pinned to a minimum sample size of 2). Window: January
     * 2026. Tasks H1-H9 (a sub-task and a task outside the window never count), epics P1-P4 (P5 outside).
     */
    @Test
    fun `hand-built rows pin the started and done populations, the estimated-late separation and the drill exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "adjustments", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamA = TestTeams.seed(SyncedStubFixture.unique("adjust-a"))
        val teamB = TestTeams.seed(SyncedStubFixture.unique("adjust-b"))
        val s = noonUtc("2026-01-05")
        val d = noonUtc("2026-01-15")
        val taskRows = listOf(
            // +0.5
            handTask(1, s, d, estimateAtStartMd = 2.0, estimateAtDoneMd = 3.0, changes = 1, creditTeamId = teamA, account = "acc-1"),
            // -0.5
            handTask(2, s, d, estimateAtStartMd = 4.0, estimateAtDoneMd = 2.0, changes = 2, creditTeamId = teamA, account = "acc-1"),
            handTask(3, s, d, estimateAtDoneMd = 5.0, changes = 1, estimatedLate = true, creditTeamId = teamB), // late
            handTask(4, s, d, estimateAtStartMd = 5.0, estimateAtDoneMd = 5.0, creditTeamId = teamB), // 0.0
            // Open tasks are attributed to their CURRENT team/assignee (A25), whatever their credit columns say:
            // started, open, changed -> team B / acc-3
            handTask(5, s, null, estimateAtStartMd = 3.0, changes = 1, currentTeamId = teamB, currentAssignee = "acc-3"),
            // started, open, late (and changed) -> team A / acc-2 (the stale credit team B is ignored while open)
            handTask(6, s, null, changes = 1, estimatedLate = true, creditTeamId = teamB, currentTeamId = teamA, currentAssignee = "acc-2"),
            // done, never started, no estimates -> unestimated; credit null = UNASSIGNED (the current team A is ignored once done)
            handTask(7, null, d, currentTeamId = teamA, currentAssignee = "acc-9"),
            handTask(8, s, d, estimateAtStartMd = 2.0, creditTeamId = teamA, account = "acc-2"), // done, no estimate at done -> unestimated
            // +1.0, in the done population only (started before the window)
            handTask(9, noonUtc("2025-12-10"), d, estimateAtStartMd = 1.0, estimateAtDoneMd = 2.0, changes = 1, creditTeamId = teamB),
            // never counted:
            handTask(10, s, d, estimateAtStartMd = 1.0, estimateAtDoneMd = 9.0, changes = 3, subtask = true),
            handTask(11, noonUtc("2025-06-01"), noonUtc("2025-07-01"), estimateAtStartMd = 1.0, estimateAtDoneMd = 9.0, changes = 3),
            // done in the window, estimate entered only after done_at (a backfill, A35): unestimated, NOT late, no change after start
            handTask(
                12, noonUtc("2025-12-11"), d, estimateCurrentMd = 6.0, estimatedAfterDone = true, creditTeamId = teamA, account = "acc-1",
            ),
        )
        val epicRows = listOf(
            // +0.5
            handEpic(1, s, noonUtc("2026-01-20"), ownStart = 10.0, ownDone = 15.0, ownCurrent = 15.0, changes = 1, ownerTeamId = teamA),
            // started, open, late
            handEpic(2, noonUtc("2026-01-06"), null, ownCurrent = 8.0, changes = 1, estimatedLate = true, ownerTeamId = teamA),
            // 0.0
            handEpic(3, noonUtc("2026-01-07"), noonUtc("2026-01-21"), ownStart = 6.0, ownDone = 6.0, ownCurrent = 6.0, ownerTeamId = teamB),
            handEpic(4, null, noonUtc("2026-01-22")), // done, never started, unestimated, UNOWNED
            handEpic(
                5, noonUtc("2025-06-01"), noonUtc("2025-07-01"),
                ownStart = 1.0, ownDone = 9.0, ownCurrent = 9.0, changes = 2, ownerTeamId = teamA,
            ),
            // done in the window, started before it, estimated only after done_at (a backfill): unestimated + estimatedAfterDone
            handEpic(6, noonUtc("2025-12-12"), noonUtc("2026-01-23"), ownCurrent = 7.0, estimatedAfterDone = true, ownerTeamId = teamB),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, taskRows, configRevision = 1L)
            store.replaceFactEpicDelivery(connId, epicRows, configRevision = 1L)
        }
        try {
            val client = seededClient("reports-adjustments-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            val body = withMinSampleSize(2) { client.adjustments(query) }

            // Tasks: started in window H1-H6 + H8 = 7 (H9 started before it), changed = H1 H2 H3 H5 H6 = 5, late = H3 H6.
            assertEquals(7, body.tasks.started)
            assertEquals(5, body.tasks.changedAfterStart)
            assertEquals(5.0 / 7.0, body.tasks.share!!, 1e-12)
            assertEquals(2, body.tasks.estimatedLate)
            // Done in window: H1 H2 H3 H4 H7 H8 H9 H12 = 8; late H3; measurable H1 H2 H4 H9; unestimated H7 H8 H12 (H12 also
            // after done: started before the window, so it is in no started figure).
            assertEquals(8, body.tasks.changeExcluded.population)
            assertEquals(1, body.tasks.changeExcluded.estimatedLate)
            assertEquals(1, body.tasks.changeExcluded.estimatedAfterDone)
            assertEquals(3, body.tasks.changeExcluded.unestimated)
            // H4 (5 -> 5) is measurable but unchanged: counted in `unchanged`, not charted, so 3 + 1 + 1 + 3 = 8.
            assertEquals(1, body.tasks.changeExcluded.unchanged)
            assertDistribution("task change", listOf(0.5, -0.5, 1.0), body.tasks.changeDistribution, 2)
            assertEquals(-0.5, body.tasks.changeDistribution.min!!, 1e-9)
            assertEquals(1.0 / 3.0, body.tasks.changeDistribution.mean!!, 1e-9)
            assertEquals(0.5, body.tasks.changeDistribution.p50!!, 1e-9)
            assertEquals(0.9, body.tasks.changeDistribution.p90!!, 1e-9) // 0.5 + 0.8 * (1.0 - 0.5)
            assertEquals(3L, body.tasks.changeDistribution.histogram.sumOf { it.count })

            // Epics: started P1 P2 P3 = 3, changed P1 P2 = 2, late P2. Done P1 P3 P4 P6; measurable P1 P3; unestimated P4 P6
            // (P6 also after done; P5 and P6 started before the window, P5 is done before it).
            assertEquals(3, body.epics.started)
            assertEquals(2, body.epics.changedAfterStart)
            assertEquals(2.0 / 3.0, body.epics.share!!, 1e-12)
            assertEquals(1, body.epics.estimatedLate)
            assertEquals(4, body.epics.changeExcluded.population)
            assertEquals(0, body.epics.changeExcluded.estimatedLate)
            assertEquals(1, body.epics.changeExcluded.estimatedAfterDone)
            assertEquals(2, body.epics.changeExcluded.unestimated)
            // P3 (6 -> 6) is unchanged: only P1 is charted, which is below the minimum sample of 2 (hidden, n still set).
            assertEquals(1, body.epics.changeExcluded.unchanged)
            assertDistribution("epic change", listOf(0.5), body.epics.changeDistribution, 2)

            // UNIT drill (A25): A = H1 H2 H8 (done, credit) + H6 (open, current) + P1 P2; B = H3 H4 H9 + H5 (open) + P3;
            // the UNASSIGNED/UNOWNED rest (H7 — done with no credit team — and P4) is one null-team group.
            assertEquals(3, body.groups.size)
            val a = body.groups.single { it.teamId == teamA }
            assertEquals(4, a.tasks.started)
            assertEquals(3, a.tasks.changedAfterStart)
            assertEquals(1, a.tasks.estimatedLate)
            assertEquals(4, a.tasks.changeExcluded.population)
            assertEquals(2, a.tasks.changeDistribution.n)
            assertEquals(0, a.tasks.changeExcluded.unchanged)
            assertEquals(2, a.tasks.changeExcluded.unestimated)
            assertEquals(1, a.tasks.changeExcluded.estimatedAfterDone)
            assertEquals(2, a.epics!!.started)
            assertEquals(1, a.epics.estimatedLate)
            val b = body.groups.single { it.teamId == teamB }
            assertEquals(3, b.tasks.started)
            assertEquals(2, b.tasks.changedAfterStart)
            assertEquals(1, b.tasks.estimatedLate)
            assertEquals(3, b.tasks.changeExcluded.population)
            assertEquals(1, b.tasks.changeDistribution.n, "H9 is charted; H4 is unchanged")
            assertEquals(1, b.tasks.changeExcluded.unchanged)
            assertEquals(1, b.epics!!.started)
            assertEquals(1, b.epics.changeExcluded.estimatedAfterDone, "P6 is team B's backfilled epic")
            assertEquals(0, a.epics.changeExcluded.estimatedAfterDone)
            val rest = body.groups.single { it.teamId == null }
            assertEquals(0, rest.tasks.started, "the open tasks are not UNASSIGNED")
            assertEquals(0, rest.tasks.changedAfterStart)
            assertEquals(1, rest.tasks.changeExcluded.population)
            assertEquals(1, rest.epics!!.changeExcluded.population)
            assertEquals(0, rest.epics.started)
            assertEquals(body.tasks.started, body.groups.sumOf { it.tasks.started })
            assertEquals(body.epics.started, body.groups.sumOf { it.epics!!.started })

            // TEAM level (A): tasks by assignee, epics narrowed to the owner, no epic groups.
            val team = withMinSampleSize(2) { client.adjustments("$query&teamId=$teamA") }
            assertEquals(4, team.tasks.started)
            assertEquals(2, team.epics.started)
            assertEquals(setOf("acc-1", "acc-2"), team.groups.mapNotNull { it.accountId }.toSet())
            assertEquals(2, team.groups.single { it.accountId == "acc-1" }.tasks.started)
            assertEquals(2, team.groups.single { it.accountId == "acc-2" }.tasks.started, "H8 by assignee at done + H6 by assignee now")
            assertTrue(team.groups.all { it.epics == null })

            // USER level: the account's tasks; epics empty.
            val user = withMinSampleSize(2) { client.adjustments("$query&teamId=$teamA&accountId=acc-1") }
            assertEquals(2, user.tasks.started)
            assertEquals(2, user.tasks.changedAfterStart)
            assertEquals(2, user.tasks.changeDistribution.n)
            assertEquals(0, user.epics.started)
            assertNull(user.epics.share)
            assertEquals(listOf("acc-1"), user.groups.mapNotNull { it.accountId }, "USER level lists the one pick")
            assertTrue(user.groups.all { it.epics == null })
            // acc-2 in team A: H8 (done, at done) and H6 (open, now).
            val acc2 = withMinSampleSize(2) { client.adjustments("$query&teamId=$teamA&accountId=acc-2") }
            assertEquals(2, acc2.tasks.started)
            assertEquals(1, acc2.tasks.changedAfterStart)
            assertEquals(1, acc2.tasks.estimatedLate)
            assertEquals(1, acc2.tasks.changeExcluded.population)

            // `share` is hidden (counts only) below the minimum sample size: 7 tasks / 3 epics started.
            val small = withMinSampleSize(8) { client.adjustments(query) }
            assertEquals(7, small.tasks.started)
            assertEquals(5, small.tasks.changedAfterStart)
            assertNull(small.tasks.share)
            assertNull(small.epics.share)
            val exact = withMinSampleSize(7) { client.adjustments(query) }
            assertEquals(5.0 / 7.0, exact.tasks.share!!, 1e-12)
            assertNull(exact.epics.share, "3 started epics are below a minimum of 7")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteFactEpicDelivery(connId)
            }
            cleanUpTeams(listOf(teamA, teamB))
        }
    }

    /**
     * The per-person split on hand-built rows: the unchanged items are in no share and no histogram bucket, only in each person's
     * `unchanged` figure. acc-1: +0.5, +1.0, 0 (unchanged); acc-2: -0.5, 0, 0 (two unchanged). Minimum sample size 1.
     */
    @Test
    fun `the per-person split charts only the adjusted items and counts the unchanged ones per pick`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "adjustments-split", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val team = TestTeams.seed(SyncedStubFixture.unique("adjust-split"))
        val s = noonUtc("2026-01-05")
        val d = noonUtc("2026-01-15")
        fun task(id: Long, account: String, start: Double, done: Double) = handTask(
            id, s, d, estimateAtStartMd = start, estimateAtDoneMd = done, changes = if (start == done) 0 else 1, creditTeamId = team,
            account = account,
        )
        val taskRows = listOf(
            task(1, "acc-1", 2.0, 3.0), task(2, "acc-1", 2.0, 4.0), task(3, "acc-1", 5.0, 5.0),
            task(4, "acc-2", 4.0, 2.0), task(5, "acc-2", 3.0, 3.0), task(6, "acc-2", 1.0, 1.0),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, taskRows, configRevision = 1L) }
        try {
            val client = seededClient("reports-adjustments-split")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31&teamId=$team"
            val body = withMinSampleSize(1) { client.adjustments("$query&accountId=acc-2&accountId=acc-1") }

            assertDistribution("union", listOf(0.5, 1.0, -0.5), body.tasks.changeDistribution, 1)
            assertEquals(-0.5, body.tasks.changeDistribution.min!!, 1e-9)
            assertEquals(1.0, body.tasks.changeDistribution.max!!, 1e-9)
            assertEquals(3, body.tasks.changeExcluded.unchanged)
            assertEquals(6, body.tasks.changeExcluded.population)
            assertEquals(
                6L,
                body.tasks.changeDistribution.n + body.tasks.changeExcluded.unchanged + body.tasks.changeExcluded.estimatedLate +
                    body.tasks.changeExcluded.unestimated,
            )
            // Request order; each person's share counts only their adjusted items (acc-2: 1, acc-1: 2), and sum of shares = the bucket.
            assertSharesInUnionEdges(
                "split",
                listOf(Triple("acc-2", "acc-2", listOf(-0.5)), Triple("acc-1", "acc-1", listOf(0.5, 1.0))),
                body.tasks.changeDistribution,
            )
            assertEquals(3L, body.tasks.changeDistribution.byUser.sumOf { it.counts.sum() })
            // Each pick's own accounting: the unchanged ones per person.
            assertEquals(2, body.groups.single { it.accountId == "acc-2" }.tasks.changeExcluded.unchanged)
            assertEquals(1, body.groups.single { it.accountId == "acc-1" }.tasks.changeExcluded.unchanged)
            assertEquals(1L, body.groups.single { it.accountId == "acc-2" }.tasks.changeDistribution.n)
            assertEquals(2L, body.groups.single { it.accountId == "acc-1" }.tasks.changeDistribution.n)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `an empty window answers zeros with a null share and 400 for bad filters`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-adjustments-empty")

        val empty = client.adjustments("connectionId=$connId&from=2001-01-01&to=2001-01-07")
        assertEquals(0, empty.tasks.started)
        assertNull(empty.tasks.share)
        assertEquals(0, empty.tasks.changeDistribution.n)
        assertTrue(empty.tasks.changeDistribution.hidden)
        assertTrue(empty.groups.isEmpty())

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/estimate-adjustments?from=2026-01-01&to=2025-01-01").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/estimate-adjustments?accountId=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/estimate-adjustments?connectionId=999999").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/estimate-adjustments?sprintId=999999999").status)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
