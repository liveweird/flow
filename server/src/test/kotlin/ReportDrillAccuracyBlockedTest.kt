package ch.nokillswit

import ch.nokillswit.reports.BlockedTimeReport
import ch.nokillswit.reports.EpicEstimationAccuracyReport
import ch.nokillswit.reports.TaskEstimationAccuracyReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FROM = "2024-01-01"
private const val TO = "2026-12-31"
private val TAILS = listOf(0, 5, 25)

/**
 * The reconciliation gate of the histogram drills of Task accuracy, Epic accuracy and Blocked time
 * (`GET /api/v1/reports/{task-estimation-accuracy,epic-estimation-accuracy,blocked-time}/items`, `.claude/docs/reports.md`
 * "Drill to items"): on the derived stub fixture, for EVERY bucket of EVERY histogram — with the open tail off, at its default
 * and at its widest, and at every level the report has (task accuracy also at USER level with two picks: union and both
 * segments; blocked time with `itemKind` TASK, EPIC and BOTH) — the drill's bar equals the histogram's count and range, the listed
 * figures lie in it, and all buckets together hold exactly an INDEPENDENT re-derivation of the measured values from the raw fact
 * rows. A failing reconciliation is a real divergence between a bar and its items, never grounds to loosen a test. Hand-built rows
 * and the 400 matrix are `ReportDrillAccuracyBlockedHandBuiltTest`.
 */
class ReportDrillAccuracyBlockedTest {

    private suspend fun HttpClient.taskAccuracy(query: String): TaskEstimationAccuracyReport {
        val response = get("/api/v1/reports/task-estimation-accuracy?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET task-estimation-accuracy?$query")
        return response.body()
    }

    private suspend fun HttpClient.epicAccuracy(query: String): EpicEstimationAccuracyReport {
        val response = get("/api/v1/reports/epic-estimation-accuracy?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET epic-estimation-accuracy?$query")
        return response.body()
    }

    private suspend fun HttpClient.blocked(query: String): BlockedTimeReport {
        val response = get("/api/v1/reports/blocked-time?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET blocked-time?$query")
        return response.body()
    }

    private suspend fun doneTasks(connId: UInt): List<TaskFact> {
        val bounds = windowBounds(reportZone(), FROM, TO)
        return readTaskFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
    }

    private fun ratio(actual: BigDecimal, estimate: BigDecimal?): Double? =
        estimate?.takeIf { it.signum() > 0 }?.let { actual.toDouble() / it.toDouble() }

    private fun worked(tasks: List<TaskFact>) = tasks.filter { it.hasWorklogs && it.actual.signum() > 0 }

    private fun atStart(tasks: List<TaskFact>) = worked(tasks).filter { it.startedAt != null }.mapNotNull { ratio(it.actual, it.estStart) }

    private fun atDone(tasks: List<TaskFact>) = worked(tasks).mapNotNull { ratio(it.actual, it.estDone) }

    /** The team with the most tasks that have both ratios among at least two people who each have one, and its two busiest such people. */
    private fun busiestPair(tasks: List<TaskFact>): Triple<UInt, String, String> {
        val usable = tasks.filter {
            it.team != null && it.account != null && atStart(listOf(it)).isNotEmpty() && atDone(listOf(it)).isNotEmpty()
        }
        val candidates = usable.groupBy { it.team!! }.filterValues { rows -> rows.mapNotNull { it.account }.distinct().size >= 2 }
        val (team, rows) = candidates.maxByOrNull { it.value.size } ?: error("the stub must carry a team with two people with ratios")
        val (a, b) = rows.groupingBy { it.account!! }.eachCount().entries.sortedByDescending { it.value }.take(2).map { it.key }
        return Triple(team, a, b)
    }

    @Test
    fun `every bar of the task accuracy histograms reconciles with its drill at UNIT TEAM and USER level for every tail`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val tasks = doneTasks(connId)
            val (teamId, a, b) = busiestPair(tasks)
            val client = seededClient("reports-drill-task-accuracy")
            val base = "connectionId=$connId&from=$FROM&to=$TO"
            val team = tasks.filter { it.team == teamId }
            val scopes = listOf(
                Triple("UNIT", base, tasks),
                Triple("TEAM", "$base&teamId=$teamId", team),
                Triple("USER", "$base&teamId=$teamId&accountId=$b&accountId=$a", team.filter { it.account in setOf(a, b) }),
            )
            var buckets = 0
            for ((level, query, scoped) in scopes) {
                for (tail in TAILS) {
                    val q = "$query&tailPercent=$tail"
                    val report = client.taskAccuracy(q)
                    val label = "$level/tail$tail"
                    val path = "task-estimation-accuracy"
                    buckets += client.assertHistogramDrillsReconcile(label, path, q, "AT_START", report.atStart, atStart(scoped))
                    buckets += client.assertHistogramDrillsReconcile(label, path, q, "AT_DONE", report.atDone, atDone(scoped))
                    if (level == "USER") assertEquals(2, report.atStart.byUser.size, "USER carries both picks' segments")
                    if (tail > 0 && level == "UNIT") {
                        assertTrue(report.atStart.histogram.any { it.to == null }, "tail $tail must give the fixture an open bucket")
                    }
                }
            }
            assertTrue(buckets > 100, "the gate must have walked many buckets, walked $buckets")
        }

    @Test
    fun `every bar of the epic accuracy histograms reconciles with its drill at UNIT and TEAM level`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val bounds = windowBounds(reportZone(), FROM, TO)
        val epics = readEpicFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
        val teamId = epics.mapNotNull { it.owner }.groupingBy { it }.eachCount().maxBy { it.value }.key
        val client = seededClient("reports-drill-epic-accuracy")
        val base = "connectionId=$connId&from=$FROM&to=$TO"
        fun workedEpics(rows: List<EpicFact>) = rows.filter { it.actual.signum() > 0 }
        fun startOf(rows: List<EpicFact>) = workedEpics(rows).filter { it.startedAt != null }.mapNotNull { ratio(it.actual, it.ownStart) }
        fun doneOf(rows: List<EpicFact>) = workedEpics(rows).mapNotNull { ratio(it.actual, it.ownDone) }

        var buckets = 0
        // A minimum sample size of 1 so even a small owner team shows its histogram: report and drill read the SAME setting.
        withMinSampleSize(1) {
            for ((level, query, scoped) in listOf(
                Triple("UNIT", base, epics),
                Triple("TEAM", "$base&teamId=$teamId", epics.filter { it.owner == teamId }),
            )) {
                for (tail in TAILS) {
                    val q = "$query&tailPercent=$tail"
                    val report = client.epicAccuracy(q)
                    val label = "$level/tail$tail"
                    val path = "epic-estimation-accuracy"
                    buckets += client.assertHistogramDrillsReconcile(label, path, q, "AT_START", report.atStart, startOf(scoped))
                    buckets += client.assertHistogramDrillsReconcile(label, path, q, "AT_DONE", report.atDone, doneOf(scoped))
                    val first = client.drillItems(path, "$q&view=AT_DONE&bucketIndex=0")
                    assertTrue(first.items.all { it.itemKind.name == "EPIC" && it.accountId == null }, "$level epics are EPIC items")
                }
            }
        }
        assertTrue(buckets > 20, "the gate must have walked the epic buckets, walked $buckets")
    }

    @Test
    fun `every bar of the blocked time histograms reconciles with its drill for TASK EPIC and BOTH at UNIT TEAM and USER level`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val bounds = windowBounds(reportZone(), FROM, TO)
            val all = readDoneFacts(connId).filter { it.doneAt >= bounds.first && it.doneAt < bounds.second }
            val tasks = all.filter { it.kind == "TASK" }
            val epics = all.filter { it.kind == "EPIC" }
            assertTrue(tasks.any { it.blocked > 0.0 } && epics.isNotEmpty(), "the fixture must carry blocked tasks and epics")
            val teamId = tasks.mapNotNull { it.team }.groupingBy { it }.eachCount().maxBy { it.value }.key
            val teamTasks = tasks.filter { it.team == teamId }
            val person = teamTasks.mapNotNull { it.account }.groupingBy { it }.eachCount().maxBy { it.value }.key
            val client = seededClient("reports-drill-blocked")
            val base = "connectionId=$connId&from=$FROM&to=$TO"
            // TEAM covers the team's tasks and (for EPIC/BOTH) the epics it owns; USER only that assignee's tasks (epics have no user).
            val scopes = listOf(
                Triple("UNIT", base, tasks to epics),
                Triple("TEAM", "$base&teamId=$teamId", tasks.filter { it.team == teamId } to epics.filter { it.team == teamId }),
                Triple("USER", "$base&teamId=$teamId&accountId=$person", teamTasks.filter { it.account == person } to emptyList()),
            )
            var buckets = 0
            withMinSampleSize(1) {
                for ((level, query, population) in scopes) {
                    for ((kind, items) in listOf(
                        "TASK" to population.first,
                        "EPIC" to population.second,
                        "BOTH" to population.first + population.second,
                    )) {
                        if (items.isEmpty()) continue
                        for (tail in TAILS) {
                            val q = "$query&itemKind=$kind&tailPercent=$tail"
                            val report = client.blocked(q)
                            val label = "$level/$kind/tail$tail"
                            val path = "blocked-time"
                            buckets += client.assertHistogramDrillsReconcile(
                                label, path, q, "BLOCKED_WORKING_DAYS", report.blockedWorkingDays, items.map { it.blocked },
                            )
                            if (items.any { it.share != null }) {
                                buckets += client.assertHistogramDrillsReconcile(
                                    label, path, q, "SHARE_OF_CYCLE", report.shareOfCycle, items.mapNotNull { it.share },
                                )
                            }
                        }
                    }
                }
            }
            assertTrue(buckets > 100, "the gate must have walked many buckets, walked $buckets")
        }
}
