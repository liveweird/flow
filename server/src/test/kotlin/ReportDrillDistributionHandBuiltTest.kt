package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private const val DAY = 86_400_000L

/**
 * Hand-built rows and the 400 matrix of the histogram drills of Cycle time and Reported time
 * (`GET /api/v1/reports/{cycle-time,reported-time-ratio}/items`). The fixture reconciliation is `ReportDrillDistributionTest`;
 * here the rows are placed by hand in a fresh DISABLED connection, so the boundary cases are exact: a cycle of zero working days
 * lands in the FIRST bucket, and every task a report excludes from a view is in no bucket of it.
 */
class ReportDrillDistributionHandBuiltTest {

    private suspend fun HttpClient.keysByBucket(path: String, query: String, view: String, buckets: Int): List<Set<String>> =
        (0 until buckets).map { index -> drillItems(path, "$query&view=$view&bucketIndex=$index").items.map { it.issueKey }.toSet() }

    @Test
    fun `a zero-working-day cycle lands in bucket 0 and a task that never started is in no bucket of any view`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "drill-cycle", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val team = TestTeams.seed(SyncedStubFixture.unique("drill-cycle"))
        val s = noonUtc("2026-01-02")
        val done = noonUtc("2026-01-15")
        val rows = listOf(
            handTask(1, s, done, cycleMs = DAY / 2, cycleWorkingDays = 0.0, creditTeamId = team, account = "acc-1"),
            handTask(2, s, done, cycleMs = DAY, cycleWorkingDays = 1.0, creditTeamId = team, account = "acc-1"),
            handTask(3, s, done, cycleMs = 2 * DAY, cycleWorkingDays = 2.0, creditTeamId = team, account = "acc-2"),
            handTask(4, s, done, cycleMs = 10 * DAY, cycleWorkingDays = 10.0, creditTeamId = team, account = "acc-2"),
            handTask(5, null, done, creditTeamId = team, account = "acc-1"), // never started: not measurable
            // never counted: a sub-task and an open task
            handTask(6, s, done, cycleMs = DAY, cycleWorkingDays = 1.0, creditTeamId = team, account = "acc-1", subtask = true),
            handTask(7, s, null, creditTeamId = team),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-drill-cycle-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31&tailPercent=0"
            withMinSampleSize(3) {
                // Working days 0 1 2 10 over ten equal buckets of width 1: 0 -> bucket 0, 1 -> 1, 2 -> 2, 10 (the max) -> 9.
                val byBucket = client.keysByBucket("cycle-time", query, "WORKING_DAYS", 10)
                assertEquals(setOf("1"), byBucket[0], "zero working days is a real value in the first bucket")
                assertEquals(setOf("2"), byBucket[1])
                assertEquals(setOf("3"), byBucket[2])
                assertEquals(setOf("4"), byBucket[9], "the maximum is in the closed last bucket")
                val everywhere = byBucket.flatten().toSet()
                assertEquals(setOf("1", "2", "3", "4"), everywhere, "5 (never started), 6 (sub-task) and 7 (open) are in no bucket")
                // The elapsed view: 0.5 1 2 10 days over buckets of width 0.95 — 0.5 and 1 share the first.
                val elapsed = client.keysByBucket("cycle-time", query, "ELAPSED_DAYS", 10)
                assertEquals(setOf("1", "2"), elapsed[0])
                assertEquals(setOf("3"), elapsed[1])
                assertEquals(setOf("1", "2", "3", "4"), elapsed.flatten().toSet())

                // A segment narrows to one picked person's share; the union's layout stays.
                val picks = "$query&teamId=$team&accountId=acc-1&accountId=acc-2"
                val segment = client.drillItems("cycle-time", "$picks&view=WORKING_DAYS&bucketIndex=0&segmentAccountId=acc-2")
                assertEquals(0, segment.bar.count, "acc-2 has nothing in bucket 0")
                assertEquals(0.0, segment.bar.from)
                assertEquals(1.0, segment.bar.to)
                val own = client.drillItems("cycle-time", "$picks&view=WORKING_DAYS&bucketIndex=9&segmentAccountId=acc-2")
                assertEquals(listOf("4"), own.items.map { it.issueKey })
                assertEquals(listOf(10.0), own.items.map { it.figure })
                assertEquals(listOf("acc-2"), own.items.map { it.accountId })
            }
            // Below the minimum sample size the histogram is hidden: no bar, so a 400.
            val hidden = withMinSampleSize(5) { client.get("/api/v1/reports/cycle-time/items?$query&view=WORKING_DAYS&bucketIndex=0") }
            assertEquals(HttpStatusCode.BadRequest, hidden.status)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `the epic views list epics with their owner team and keep a never-started epic out of every bucket`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "drill-epics", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val team = TestTeams.seed(SyncedStubFixture.unique("drill-epic"))
        val s = noonUtc("2026-01-02")
        val epics = listOf(
            handEpic(1, s, noonUtc("2026-01-20"), cycleMs = 10 * DAY, cycleWorkingDays = 8.0, ownerTeamId = team),
            handEpic(2, s, noonUtc("2026-01-21"), cycleMs = DAY, cycleWorkingDays = 0.0, ownerTeamId = team),
            handEpic(3, s, noonUtc("2026-01-15"), cycleMs = 6 * DAY, cycleWorkingDays = 4.0, ownerTeamId = team),
            handEpic(4, null, noonUtc("2026-01-14"), ownerTeamId = team), // never started
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactEpicDelivery(connId, epics, configRevision = 1L) }
        try {
            val client = seededClient("reports-drill-epic-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31&tailPercent=0&teamId=$team"
            withMinSampleSize(3) {
                // Working days 0 4 8 over ten buckets of width 0.8: 0 -> 0, 4 -> 5, 8 (the max) -> 9.
                val byBucket = client.keysByBucket("cycle-time", query, "EPIC_WORKING_DAYS", 10)
                assertEquals(setOf("2"), byBucket[0])
                assertEquals(setOf("3"), byBucket[5])
                assertEquals(setOf("1"), byBucket[9])
                assertEquals(setOf("1", "2", "3"), byBucket.flatten().toSet(), "4 never started")
                val item = client.drillItems("cycle-time", "$query&view=EPIC_ELAPSED_DAYS&bucketIndex=0").items.single()
                assertEquals("EPIC", item.itemKind.name)
                assertEquals(team, item.teamId)
                assertEquals(null, item.accountId)
                assertEquals(1.0, item.figure)
            }
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactEpicDelivery(connId) }
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `reported time drills keep every excluded task out of the bars - no worklogs, never started, a zero cycle`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "drill-ratio", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val s = noonUtc("2026-01-02")
        val done = noonUtc("2026-01-15")
        val rows = listOf(
            handTask(1, s, done, actualMd = 4.0, cycleMs = 2 * DAY, cycleWorkingDays = 2.0, activeMs = DAY), // ratio 2.0; flow 0.5
            handTask(2, s, done, actualMd = 3.0, cycleMs = 3 * DAY, cycleWorkingDays = 3.0, activeMs = 3 * DAY), // 1.0; 1.0
            handTask(3, s, done, actualMd = 1.0, cycleMs = 4 * DAY, cycleWorkingDays = 4.0, activeMs = DAY), // 0.25; 0.25
            handTask(4, s, done, actualMd = 6.0, cycleMs = 3 * DAY, cycleWorkingDays = 3.0), // 2.0; flow 0.0
            // no ratio (no worklogs); flow 0.5
            handTask(5, s, done, hasWorklogs = false, cycleMs = 3 * DAY, cycleWorkingDays = 3.0, activeMs = 3 * DAY / 2),
            // no ratio (0.00 logged); flow 1.0
            handTask(6, s, done, actualMd = 0.0, cycleMs = 3 * DAY, cycleWorkingDays = 3.0, activeMs = 3 * DAY),
            handTask(7, null, done, actualMd = 2.0), // never started: neither
            handTask(8, null, done, hasWorklogs = false), // neither
            // a zero-working-day cycle: no ratio; flow 0.5
            handTask(9, s, done, actualMd = 2.0, cycleMs = DAY, cycleWorkingDays = 0.0, activeMs = DAY / 2),
            handTask(13, done, done, actualMd = 1.0, cycleMs = 0, cycleWorkingDays = 0.0), // zero cycle: neither
            handTask(10, s, done, actualMd = 9.0, cycleMs = DAY, cycleWorkingDays = 1.0, subtask = true),
            handTask(11, s, null, actualMd = 9.0),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-drill-ratio-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31&tailPercent=0"
            withMinSampleSize(4) {
                // Ratios 0.25 1 2 2 over ten buckets of width 0.175: 0.25 -> 0, 1 -> 4, both 2.0 -> 9 (the closed last bucket).
                val ratio = client.keysByBucket("reported-time-ratio", query, "RATIO", 10)
                assertEquals(setOf("3"), ratio[0])
                assertEquals(setOf("2"), ratio[4])
                assertEquals(setOf("1", "4"), ratio[9])
                assertEquals(setOf("1", "2", "3", "4"), ratio.flatten().toSet(), "5 6 9 (no ratio), 7 8 13 (excluded) are in no bucket")
                val top = client.drillItems("reported-time-ratio", "$query&view=RATIO&bucketIndex=9")
                assertEquals(listOf(2.0, 2.0), top.items.map { it.figure })
                assertEquals(0.0, top.items.sumOf { it.figure!! } - 4.0)
                // Flow efficiency 0 .25 .5 .5 .5 1 1 over ten buckets of width 0.1 (tasks 1-6 and 9).
                val flow = client.keysByBucket("reported-time-ratio", query, "FLOW_EFFICIENCY", 10)
                assertEquals(setOf("4"), flow[0])
                assertEquals(setOf("3"), flow[2])
                assertEquals(setOf("1", "5", "9"), flow[5])
                assertEquals(setOf("2", "6"), flow[9])
                assertEquals(setOf("1", "2", "3", "4", "5", "6", "9"), flow.flatten().toSet(), "7 8 13 are never measured")
                assertTrue(flow.flatten().none { it in setOf("10", "11") })
            }
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
        }
    }

    @Test
    fun `the histogram drills reject a bad view, a bad bucketIndex, a bad segment and the parent's own 400s`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-drill-distribution-400")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        // The busiest assignee of the busiest team: a one-person USER level whose histograms are not hidden.
        val busiest = readTaskFacts(connId).filter { it.team != null && it.account != null && it.doneAt != null && it.cycleMs != null }
            .groupingBy { it.team!! to it.account!! }.eachCount().maxBy { it.value }.key
        val team = busiest.first
        val person = busiest.second

        for ((path, view) in listOf("cycle-time" to "WORKING_DAYS", "reported-time-ratio" to "RATIO")) {
            suspend fun status(query: String) = client.get("/api/v1/reports/$path/items?$query")
            suspend fun assertStatus(expected: HttpStatusCode, query: String, why: String) =
                assertEquals(expected, status(query).status, "$path: $why")

            assertStatus(HttpStatusCode.OK, "$range&view=$view&bucketIndex=0", "the baseline answers")
            assertStatus(HttpStatusCode.BadRequest, "$range&bucketIndex=0", "view is required")
            assertStatus(HttpStatusCode.BadRequest, "$range&view=NOPE&bucketIndex=0", "an unknown view")
            assertStatus(HttpStatusCode.BadRequest, "$range&view=$view", "bucketIndex is required")
            assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=abc", "bucketIndex is an integer")
            assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=-1", "bucketIndex is not negative")
            assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=999", "bucketIndex beyond the histogram")
            assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=0&bucketIndex=1", "a repeated bucketIndex")
            assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=0&segmentAccountId=$person", "UNIT level")
            assertStatus(
                HttpStatusCode.BadRequest,
                "$range&view=$view&bucketIndex=0&teamId=$team&accountId=$person&segmentAccountId=somebody-else",
                "a stranger to the picks",
            )
            assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=0&accountId=$person", "accountId needs teamId")
            assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=0&tailPercent=26", "tailPercent beyond 25")
            val backwards = "connectionId=$connId&from=2026-01-01&to=2025-01-01&view=$view&bucketIndex=0"
            assertStatus(HttpStatusCode.BadRequest, backwards, "to before from")
            assertStatus(
                HttpStatusCode.OK,
                "$range&view=$view&bucketIndex=0&teamId=$team&accountId=$person&segmentAccountId=$person",
                "a pick that IS among the picked accounts",
            )
        }
        val response = client.get("/api/v1/reports/cycle-time/items?$range&view=WORKING_DAYS&bucketIndex=0&bucket=DAY")
        assertEquals(HttpStatusCode.BadRequest, response.status, "cycle-time validates the parent's bucket")
        // A distribution too small to show draws no bar: the whole fixture hidden by a huge minimum sample size.
        val hidden = withMinSampleSize(100_000) { client.get("/api/v1/reports/cycle-time/items?$range&view=WORKING_DAYS&bucketIndex=0") }
        assertEquals(HttpStatusCode.BadRequest, hidden.status, "a hidden distribution has no bar")
    }
}
