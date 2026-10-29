package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.AdjustmentFigures
import ch.nokillswit.reports.EstimateAdjustmentsReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

/** The derived stub fixture's FLO domain: the scope of every UNIT-level read (a report is never read for the whole unit). */
private const val FLO_DOMAIN = DerivedStubFixture.DOMAIN

private const val FROM = "2024-01-01"
private const val TO = "2026-12-31"
private val TAILS = listOf(0, 5, 25)

/**
 * The reconciliation gate and the hand-built cases of the change-histogram drill
 * (`GET /api/v1/reports/estimate-adjustments/items`, `.claude/docs/reports.md` "Drill to items"). On the derived stub fixture, for
 * EVERY bucket of the tasks' and the epics' change histogram — with the open tail off, at its default and at its widest, at UNIT
 * and TEAM level and at USER level with two picks (union and both segments) — the drill's bar equals the histogram's count and
 * range, the listed changes lie in it, and all buckets together hold exactly an INDEPENDENT re-derivation of the charted changes
 * from the raw fact rows. Hand-built rows in a fresh DISABLED connection then pin what the histogram leaves out: the unchanged,
 * estimated-late, unestimated and backfilled items, the open ones, sub-tasks and rows outside the window are in no bucket.
 */
class ReportDrillAdjustmentsTest {

    private suspend fun HttpClient.adjustments(query: String): EstimateAdjustmentsReport {
        val response = get("/api/v1/reports/estimate-adjustments?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET estimate-adjustments?$query")
        return response.body()
    }

    /** The charted change of one done item, straight from its stored estimates: null = not charted (the report's four exclusions). */
    private fun changeOf(estimatedLate: Boolean, atStart: BigDecimal?, atDone: BigDecimal?): Double? {
        val estimated = atStart != null && atDone != null && atStart.signum() > 0 && atDone.signum() > 0
        if (estimatedLate || !estimated) return null
        if (atStart.compareTo(atDone) == 0) return null
        return (atDone.toDouble() - atStart.toDouble()) / atStart.toDouble()
    }

    private fun taskChanges(tasks: List<TaskFact>, window: Pair<Long, Long>): List<Double> = tasks
        .filter { it.doneAt != null && it.doneAt >= window.first && it.doneAt < window.second }
        .mapNotNull { changeOf(it.estimatedLate, it.estStart, it.estDone) }

    private fun epicChanges(epics: List<EpicFact>, window: Pair<Long, Long>): List<Double> = epics
        .filter { it.doneAt != null && it.doneAt >= window.first && it.doneAt < window.second }
        .mapNotNull { changeOf(it.estimatedLate, it.ownStart, it.ownDone) }

    /** The team whose two busiest people hold the most charted changes together (at least two people), and those two. */
    private fun busiestPair(tasks: List<TaskFact>, window: Pair<Long, Long>): Triple<UInt, String, String> {
        val measured = tasks.filter { it.team != null && it.account != null }.filter { taskChanges(listOf(it), window).isNotEmpty() }
        val pairs = measured.groupBy { it.team!! }.mapNotNull { (team, rows) ->
            val top = rows.groupingBy { it.account!! }.eachCount().entries.sortedByDescending { it.value }.take(2)
            if (top.size < 2) null else Triple(team, top.map { it.key }, top.sumOf { it.value })
        }
        val (team, people, _) = pairs.maxByOrNull { it.third } ?: error("the stub must carry a team with two people with charted changes")
        return Triple(team, people[0], people[1])
    }

    /** A histogram with nothing charted is hidden and has no bar: every index answers 400; otherwise every bar reconciles. */
    private suspend fun HttpClient.reconcile(
        label: String,
        query: String,
        view: String,
        figures: AdjustmentFigures,
        oracle: List<Double>,
    ): Int {
        val distribution = figures.changeDistribution
        assertEquals(oracle.size.toLong(), distribution.n, "$label/$view n == the independent count of charted changes")
        if (distribution.hidden) {
            val items = get("/api/v1/reports/estimate-adjustments/items?$query&view=$view&bucketIndex=0")
            assertEquals(HttpStatusCode.BadRequest, items.status, "$label/$view: a hidden histogram has no bar")
            return 0
        }
        return assertHistogramDrillsReconcile(label, "estimate-adjustments", query, view, distribution, oracle)
    }

    @Test
    fun `every bar of the change histograms reconciles with its drill at UNIT TEAM and USER level for every tail`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val window = windowBounds(reportZone(), FROM, TO)
        val tasks = readTaskFacts(connId)
        val epics = readEpicFacts(connId)
        val (teamId, a, b) = busiestPair(tasks, window)
        val client = seededClient("reports-drill-adjustments")
        val base = "connectionId=$connId&from=$FROM&to=$TO"
        val team = tasks.filter { it.team == teamId }
        var buckets = 0
        var epicBuckets = 0
        // The stub has few ADJUSTED items (the unchanged ones are not charted), so the minimum sample size is pinned to 1:
        // report and drill read the SAME setting.
        withMinSampleSize(1) {
            for ((level, query, scoped) in listOf(
                Triple(
                    "UNIT", "$base&domain=$FLO_DOMAIN",
                    tasks.filter { it.domainKey == FLO_DOMAIN } to epics.filter { it.domainKey == FLO_DOMAIN },
                ),
                Triple("TEAM", "$base&teamId=$teamId", team to epics.filter { it.owner == teamId }),
                Triple(
                    "USER", "$base&teamId=$teamId&accountId=$b&accountId=$a",
                    team.filter { it.account == a || it.account == b } to emptyList(),
                ),
            )) {
                for (tail in TAILS) {
                    val q = "$query&tailPercent=$tail"
                    val report = client.adjustments(q)
                    val label = "$level/tail$tail"
                    buckets += client.reconcile(label, q, "TASKS", report.tasks, taskChanges(scoped.first, window))
                    epicBuckets += client.reconcile(label, q, "EPICS", report.epics, epicChanges(scoped.second, window))
                    if (level == "USER") assertEquals(2, report.tasks.changeDistribution.byUser.size, "USER carries both picks' segments")
                    // The excluded never appear: every listed task is a done task whose change moved.
                    val first = client.drillItems("estimate-adjustments", "$q&view=TASKS&bucketIndex=0")
                    assertTrue(first.items.all { it.itemKind.name == "TASK" && it.figure != 0.0 }, "$label tasks are TASK items that moved")
                }
            }
        }
        assertTrue(buckets > 60, "the gate must have walked many task buckets, walked $buckets")
        // The stub's epics never change their own estimate between start and done, so their histogram is hidden there (and the
        // drill answers 400, which `reconcile` asserts); the epic buckets are pinned on hand-built rows below.
        assertEquals(0, epicBuckets, "if the stub gains adjusted epics, walk their buckets like the tasks' and drop this pin")
    }

    @Test
    fun `hand-built rows keep every excluded item out of the bars and place each change by its own bucket`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "drill-adjustments", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val team = TestTeams.seed(SyncedStubFixture.unique("drill-adjustments"))
        val s = noonUtc("2026-01-02")
        val done = noonUtc("2026-01-15")
        fun task(id: Long, atStart: Double?, atDone: Double?, account: String, late: Boolean = false) =
            handTask(
                id, s, done, estimateAtStartMd = atStart, estimateAtDoneMd = atDone, estimatedLate = late,
                creditTeamId = team, account = account,
            )
        val tasks = listOf(
            task(1, 2.0, 3.0, "acc-1"), // +0.5
            task(2, 4.0, 2.0, "acc-2"), // -0.5
            task(3, 2.0, 2.0, "acc-1"), // unchanged: counted, never charted
            task(4, 2.0, 4.0, "acc-1"), // +1.0
            task(5, null, 4.0, "acc-2", late = true), // estimated late
            task(6, null, null, "acc-1"), // unestimated
            task(7, 3.0, 4.0, "acc-1"), // +0.3333
            handTask( // backfilled after done: no estimate at start or done, one now
                8, s, done, estimateCurrentMd = 3.0, estimatedAfterDone = true, creditTeamId = team, account = "acc-1",
            ),
            handTask(9, s, null, estimateAtStartMd = 2.0, estimateAtDoneMd = 4.0, creditTeamId = team, currentTeamId = team), // open
            handTask(10, s, done, estimateAtStartMd = 1.0, estimateAtDoneMd = 9.0, creditTeamId = team, account = "acc-1", subtask = true),
            handTask(11, s, noonUtc("2025-05-01"), estimateAtStartMd = 1.0, estimateAtDoneMd = 9.0, creditTeamId = team, account = "acc-1"),
            task(12, 0.0, 2.0, "acc-1"), // an estimate of 0 is no estimate
            task(13, 2.0, 1.0, "acc-2"), // -0.5
        )
        val epicDone = noonUtc("2026-01-20")
        fun epic(id: Long, atStart: Double?, atDone: Double?, late: Boolean = false, doneAt: Long? = epicDone) =
            handEpic(
                id, s, doneAt, ownStart = atStart, ownDone = atDone, ownCurrent = atDone, estimatedLate = late, ownerTeamId = team,
            )
        val epics = listOf(
            epic(21, 2.0, 3.0), // +0.5
            epic(22, 4.0, 2.0), // -0.5
            epic(23, 2.0, 2.0), // unchanged
            epic(24, null, 4.0, late = true), // estimated late
            epic(25, 3.0, 4.0), // +0.3333
            epic(26, 2.0, 4.0, doneAt = null), // open
            epic(28, 2.0, 4.0), // +1.0
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
            store.replaceFactEpicDelivery(connId, epics, configRevision = 1L)
        }
        try {
            val client = seededClient("reports-drill-adjustments-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31&tailPercent=0&teamId=$team"
            val path = "estimate-adjustments"
            suspend fun keys(q: String, view: String, index: Int, extra: String = "") =
                client.drillItems(path, "$q&view=$view&bucketIndex=$index$extra").items.map { it.issueKey }.toSet()
            suspend fun all(q: String, view: String) = (0 until 10).flatMap { keys(q, view, it) }.toSet()
            withMinSampleSize(4) {
                // Tasks: -0.5 -0.5 0.3333 0.5 1 over ten buckets of width 0.15: -0.5 -> 0, 0.3333 -> 5, 0.5 -> 6, 1 (the max) -> 9.
                assertEquals(setOf("2", "13"), keys(query, "TASKS", 0))
                assertEquals(setOf("7"), keys(query, "TASKS", 5))
                assertEquals(setOf("1"), keys(query, "TASKS", 6))
                assertEquals(setOf("4"), keys(query, "TASKS", 9))
                assertEquals(
                    setOf("1", "2", "4", "7", "13"), all(query, "TASKS"),
                    "3 unchanged, 5 late, 6 + 12 unestimated, 8 backfilled, 9 open, 10 sub-task, 11 outside the window are in no bar",
                )
                val report = client.adjustments(query)
                assertEquals(5L, report.tasks.changeDistribution.n)
                assertEquals(1, report.tasks.changeExcluded.unchanged)
                val top = client.drillItems(path, "$query&view=TASKS&bucketIndex=9")
                assertEquals(1, top.bar.count)
                assertEquals(1.0, top.items.single().figure)
                assertEquals("TASK", top.items.single().itemKind.name)
                assertEquals("acc-1", top.items.single().accountId)
                assertEquals(team, top.items.single().teamId)
                assertEquals(done, top.items.single().anchorAt)
                val negative = client.drillItems(path, "$query&view=TASKS&bucketIndex=0")
                assertEquals(listOf(-0.5, -0.5), negative.items.map { it.figure }, "a shrink is a negative signed change")

                // A segment narrows to one picked person's share; the union's layout stays.
                val picks = "$query&accountId=acc-1&accountId=acc-2"
                assertEquals(setOf("2", "13"), keys(picks, "TASKS", 0, "&segmentAccountId=acc-2"))
                assertEquals(emptySet(), keys(picks, "TASKS", 0, "&segmentAccountId=acc-1"))
                assertEquals(setOf("1"), keys(picks, "TASKS", 6, "&segmentAccountId=acc-1"))
                val own = client.drillItems(path, "$picks&view=TASKS&bucketIndex=0&segmentAccountId=acc-1")
                assertEquals(0, own.bar.count)
                assertEquals(negative.bar.from, own.bar.from, "the segment keeps the union's bucket")
                assertEquals(negative.bar.to, own.bar.to)

                // Epics (their own estimate, owner team): -0.5 0.3333 0.5 1 over the same layout; no account.
                assertEquals(setOf("22"), keys(query, "EPICS", 0))
                assertEquals(setOf("25"), keys(query, "EPICS", 5))
                assertEquals(setOf("21"), keys(query, "EPICS", 6))
                assertEquals(setOf("28"), keys(query, "EPICS", 9))
                assertEquals(setOf("21", "22", "25", "28"), all(query, "EPICS"), "23 unchanged, 24 late and 26 open are in no bar")
                val epic = client.drillItems(path, "$query&view=EPICS&bucketIndex=6").items.single()
                assertEquals("EPIC", epic.itemKind.name)
                assertEquals(null, epic.accountId)
                assertEquals(team, epic.teamId)
                assertEquals(epicDone, epic.anchorAt)
            }
            // Below the minimum sample size the histogram is hidden: there is no bar to list.
            val hidden = withMinSampleSize(6) { client.get("/api/v1/reports/$path/items?$query&view=TASKS&bucketIndex=0") }
            assertEquals(HttpStatusCode.BadRequest, hidden.status, "5 charted changes are below a minimum sample size of 6")
            val outside = withMinSampleSize(4) { client.get("/api/v1/reports/$path/items?$query&view=EPICS&bucketIndex=10") }
            assertEquals(HttpStatusCode.BadRequest, outside.status, "bucketIndex beyond the histogram")
            // USER level reads no epics (the report's own all-zero answer): there is no epic bar either.
            val user = withMinSampleSize(1) {
                client.get("/api/v1/reports/$path/items?$query&accountId=acc-1&view=EPICS&bucketIndex=0")
            }
            assertEquals(HttpStatusCode.BadRequest, user.status)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteFactEpicDelivery(connId)
            }
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `the adjustments drill rejects a bad view, bucketIndex or segment and the parent's own 400s`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-drill-adjustments-400")
        val range = "connectionId=$connId&from=$FROM&to=$TO&domain=$FLO_DOMAIN"
        val window = windowBounds(reportZone(), FROM, TO)
        val (team, person, _) = busiestPair(readTaskFacts(connId), window)
        val path = "estimate-adjustments"

        suspend fun status(query: String) = client.get("/api/v1/reports/$path/items?$query").status

        withMinSampleSize(1) {
            val ok = "$range&view=TASKS&bucketIndex=0"
            assertEquals(HttpStatusCode.OK, status(ok), "the baseline answers")
            assertEquals(HttpStatusCode.BadRequest, status("$range&bucketIndex=0"), "view is required")
            assertEquals(HttpStatusCode.BadRequest, status("$range&view=NOPE&bucketIndex=0"), "an unknown view")
            assertEquals(HttpStatusCode.BadRequest, status("$range&view=TASKS"), "bucketIndex is required")
            assertEquals(HttpStatusCode.BadRequest, status("$range&view=TASKS&bucketIndex=abc"), "bucketIndex is an integer")
            assertEquals(HttpStatusCode.BadRequest, status("$range&view=TASKS&bucketIndex=-1"), "bucketIndex is not negative")
            assertEquals(HttpStatusCode.BadRequest, status("$range&view=TASKS&bucketIndex=999"), "bucketIndex beyond the histogram")
            assertEquals(HttpStatusCode.BadRequest, status("$ok&bucketIndex=1"), "a repeated bucketIndex")
            assertEquals(HttpStatusCode.BadRequest, status("$ok&accountId=$person"), "accountId needs teamId")
            assertEquals(HttpStatusCode.BadRequest, status("$ok&tailPercent=26"), "tailPercent beyond 25")
            val backwards = "connectionId=$connId&from=2026-01-01&to=2025-01-01&domain=$FLO_DOMAIN&view=TASKS&bucketIndex=0"
            assertEquals(HttpStatusCode.BadRequest, status(backwards), "to before from")
            assertEquals(HttpStatusCode.BadRequest, status("$ok&segmentAccountId=$person"), "UNIT level has no segment")
            assertEquals(
                HttpStatusCode.BadRequest, status("$ok&teamId=$team&accountId=$person&segmentAccountId=somebody-else"), "a stranger",
            )
            assertEquals(
                HttpStatusCode.OK, status("$ok&teamId=$team&accountId=$person&segmentAccountId=$person"), "a real pick",
            )
            val epicsOfPerson = "$range&view=EPICS&bucketIndex=0&teamId=$team&accountId=$person"
            assertEquals(HttpStatusCode.BadRequest, status(epicsOfPerson), "USER level reads no epics")
        }
        // A distribution too small to show draws no bar: the whole fixture hidden by a huge minimum sample size.
        for (view in listOf("TASKS", "EPICS")) {
            val hidden = withMinSampleSize(100_000) { status("$range&view=$view&bucketIndex=0") }
            assertEquals(HttpStatusCode.BadRequest, hidden, "$view: a hidden distribution has no bar")
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
