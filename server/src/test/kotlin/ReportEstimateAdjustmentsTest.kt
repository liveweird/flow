package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.AdjustmentFigures
import ch.nokillswit.reports.EstimateAdjustmentsReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A minimal slice of `sample-data/jira/expected.json`'s `estimates` — the generator's own change/late counts. */
@Serializable
private data class GeneratorEstimates(val taskEstimateChangedAfterStartCount: Int, val taskEstimatedLateCount: Int)

@Serializable
private data class GeneratorExpected(val estimates: GeneratorEstimates)

private val ESTIMATES_JSON = Json { ignoreUnknownKeys = true }

private val generatorEstimates: GeneratorEstimates by lazy {
    val file = listOf(File("sample-data/jira/expected.json"), File("../sample-data/jira/expected.json"))
        .firstOrNull { it.isFile }
        ?: error("sample-data/jira/expected.json not found from ${File(".").absolutePath}")
    ESTIMATES_JSON.decodeFromString<GeneratorExpected>(file.readText()).estimates
}

/** One task or epic in the shape the independent computation needs (epics: `estimatedLate` derived like the kernel). */
private data class Item(
    val startedAt: Long?,
    val doneAt: Long?,
    val changes: Int,
    val estimatedLate: Boolean,
    val atStart: BigDecimal?,
    val atDone: BigDecimal?,
)

private fun TaskFact.item() = Item(startedAt, doneAt, changes, estimatedLate, estStart, estDone)

private fun EpicFact.item() = Item(
    startedAt, doneAt, changes,
    estimatedLate = startedAt != null && !(ownStart != null && ownStart.signum() > 0) && ownCurrent != null && ownCurrent.signum() > 0,
    atStart = ownStart, atDone = ownDone,
)

/** What one item kind's figures must be for [items] under [window] — computed straight from the raw rows. */
private data class ExpectedFigures(
    val started: Int,
    val changed: Int,
    val late: Int,
    val changes: List<Double>,
    val population: Int,
    val lateDone: Int,
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
        changes = measurable.map { (it.atDone!!.toDouble() - it.atStart!!.toDouble()) / it.atStart.toDouble() },
        population = done.size,
        lateDone = done.count { it.estimatedLate },
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
        assertEquals(expected.lateDone, changeExcluded.estimatedLate, "$label excluded estimatedLate")
        assertEquals(expected.unestimated, changeExcluded.unestimated, "$label excluded unestimated")
        assertEquals(changeExcluded.population.toLong(), changeDistribution.n + changeExcluded.estimatedLate + changeExcluded.unestimated)
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
        assertTrue(user.groups.isEmpty())
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
        )
        val epicRows = listOf(
            // +0.5
            handEpic(1, s, noonUtc("2026-01-20"), ownStart = 10.0, ownDone = 15.0, ownCurrent = 15.0, changes = 1, ownerTeamId = teamA),
            handEpic(2, noonUtc("2026-01-06"), null, ownCurrent = 8.0, changes = 1, ownerTeamId = teamA), // started, open, late
            // 0.0
            handEpic(3, noonUtc("2026-01-07"), noonUtc("2026-01-21"), ownStart = 6.0, ownDone = 6.0, ownCurrent = 6.0, ownerTeamId = teamB),
            handEpic(4, null, noonUtc("2026-01-22")), // done, never started, unestimated, UNOWNED
            handEpic(
                5, noonUtc("2025-06-01"), noonUtc("2025-07-01"),
                ownStart = 1.0, ownDone = 9.0, ownCurrent = 9.0, changes = 2, ownerTeamId = teamA,
            ),
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
            // Done in window: H1 H2 H3 H4 H7 H8 H9 = 7; late H3; measurable H1 H2 H4 H9; unestimated H7 H8.
            assertEquals(7, body.tasks.changeExcluded.population)
            assertEquals(1, body.tasks.changeExcluded.estimatedLate)
            assertEquals(2, body.tasks.changeExcluded.unestimated)
            assertDistribution("task change", listOf(0.5, -0.5, 0.0, 1.0), body.tasks.changeDistribution, 2)
            assertEquals(0.25, body.tasks.changeDistribution.mean!!, 1e-9)
            assertEquals(0.25, body.tasks.changeDistribution.p50!!, 1e-9)
            assertEquals(0.85, body.tasks.changeDistribution.p90!!, 1e-9) // 0.5 + 0.7 * (1.0 - 0.5)

            // Epics: started P1 P2 P3 = 3, changed P1 P2 = 2, late P2. Done P1 P3 P4; measurable P1 P3; unestimated P4.
            assertEquals(3, body.epics.started)
            assertEquals(2, body.epics.changedAfterStart)
            assertEquals(2.0 / 3.0, body.epics.share!!, 1e-12)
            assertEquals(1, body.epics.estimatedLate)
            assertEquals(3, body.epics.changeExcluded.population)
            assertEquals(0, body.epics.changeExcluded.estimatedLate)
            assertEquals(1, body.epics.changeExcluded.unestimated)
            assertDistribution("epic change", listOf(0.5, 0.0), body.epics.changeDistribution, 2)
            assertEquals(0.25, body.epics.changeDistribution.mean!!, 1e-9)

            // UNIT drill (A25): A = H1 H2 H8 (done, credit) + H6 (open, current) + P1 P2; B = H3 H4 H9 + H5 (open) + P3;
            // the UNASSIGNED/UNOWNED rest (H7 — done with no credit team — and P4) is one null-team group.
            assertEquals(3, body.groups.size)
            val a = body.groups.single { it.teamId == teamA }
            assertEquals(4, a.tasks.started)
            assertEquals(3, a.tasks.changedAfterStart)
            assertEquals(1, a.tasks.estimatedLate)
            assertEquals(3, a.tasks.changeExcluded.population)
            assertEquals(2, a.tasks.changeDistribution.n)
            assertEquals(1, a.tasks.changeExcluded.unestimated)
            assertEquals(2, a.epics!!.started)
            assertEquals(1, a.epics.estimatedLate)
            val b = body.groups.single { it.teamId == teamB }
            assertEquals(3, b.tasks.started)
            assertEquals(2, b.tasks.changedAfterStart)
            assertEquals(1, b.tasks.estimatedLate)
            assertEquals(3, b.tasks.changeExcluded.population)
            assertEquals(2, b.tasks.changeDistribution.n)
            assertEquals(1, b.epics!!.started)
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
            assertTrue(user.groups.isEmpty())
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
}
