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
import org.junit.BeforeClass

/**
 * Hand-built rows and the 400 matrix of the histogram drills of Task accuracy, Epic accuracy and Blocked time. The fixture
 * reconciliation is `ReportDrillAccuracyBlockedTest`; here the rows are placed by hand in a fresh DISABLED connection, so the
 * boundary and exclusion cases are exact: a task that never started has an at-done ratio but no at-start one, a task with
 * 0.00 logged or a zero estimate is in no bucket of either view, an epic with only a child-sum budget has no ratio, a
 * never-blocked item is a real zero in the blocked-days view, and `itemKind=BOTH` lists both kinds with their own `itemKind`.
 */
class ReportDrillAccuracyBlockedHandBuiltTest {

    private suspend fun HttpClient.keysByBucket(path: String, query: String, view: String, buckets: Int): List<Set<String>> =
        (0 until buckets).map { index -> drillItems(path, "$query&view=$view&bucketIndex=$index").items.map { it.issueKey }.toSet() }

    @Test
    fun `task accuracy drills place each ratio by its own view and keep every exclusion out of the bars`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "drill-task-accuracy", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val team = TestTeams.seed(SyncedStubFixture.unique("drill-accuracy"))
        val s = noonUtc("2026-01-02")
        val done = noonUtc("2026-01-15")
        fun task(id: Long, actual: Double, atStart: Double?, atDone: Double?, account: String, started: Long? = s) =
            handTask(
                id, started, done, actualMd = actual, estimateAtStartMd = atStart, estimateAtDoneMd = atDone,
                creditTeamId = team, account = account,
            )
        val rows = listOf(
            task(1, 1.0, 2.0, 1.0, "acc-1"), // start 0.5, done 1.0
            task(2, 2.0, 2.0, 4.0, "acc-2"), // start 1.0, done 0.5
            task(3, 3.0, 2.0, 2.0, "acc-1"), // 1.5, 1.5
            task(4, 5.0, 2.0, 2.0, "acc-2"), // 2.5, 2.5
            handTask(
                5, s, done, hasWorklogs = false, estimateAtStartMd = 2.0, estimateAtDoneMd = 2.0, creditTeamId = team, account = "acc-1",
            ),
            task(6, 0.0, 2.0, 2.0, "acc-1"), // 0.00 logged: noWorklogs, in neither view
            task(7, 2.0, 2.0, 2.0, "acc-1", started = null), // never started: done 1.0, but no at-start ratio
            task(8, 2.0, null, 4.0, "acc-2"), // unestimated at start: done 0.5 only
            task(9, 2.0, 0.0, 0.0, "acc-2"), // an estimate of 0 is no estimate
            handTask(10, s, done, actualMd = 9.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 1.0, creditTeamId = team, subtask = true),
            handTask(11, s, null, actualMd = 9.0, estimateAtStartMd = 1.0, estimateAtDoneMd = 1.0, creditTeamId = team),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactTaskDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-drill-task-accuracy-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31&tailPercent=0"
            val path = "task-estimation-accuracy"
            withMinSampleSize(4) {
                // At start 0.5 1 1.5 2.5 over ten buckets of width 0.2: 0.5 -> 0, 1 -> 2, 1.5 -> 5, 2.5 (the max) -> 9.
                val start = client.keysByBucket(path, query, "AT_START", 10)
                assertEquals(setOf("1"), start[0])
                assertEquals(setOf("2"), start[2])
                assertEquals(setOf("3"), start[5])
                assertEquals(setOf("4"), start[9])
                val everywhere = start.flatten().toSet()
                assertEquals(setOf("1", "2", "3", "4"), everywhere, "5 6 9 (no ratio), 7 (never started), 8 (no estimate) are out")
                // At done 0.5 0.5 1 1 1.5 2.5 over the same layout: the never-started 7 and the unestimated-at-start 8 are IN.
                val done = client.keysByBucket(path, query, "AT_DONE", 10)
                assertEquals(setOf("2", "8"), done[0])
                assertEquals(setOf("1", "7"), done[2])
                assertEquals(setOf("3"), done[5])
                assertEquals(setOf("4"), done[9])
                assertEquals(setOf("1", "2", "3", "4", "7", "8"), done.flatten().toSet(), "5 6 9 are in no bucket; 10 sub-task, 11 open")
                val top = client.drillItems(path, "$query&view=AT_START&bucketIndex=9")
                assertEquals(listOf(2.5), top.items.map { it.figure })
                assertEquals("TASK", top.items.single().itemKind.name)
                assertEquals(team, top.items.single().teamId)
                assertEquals("acc-2", top.items.single().accountId)

                // A segment narrows to one picked person's share; the union's layout stays.
                val picks = "$query&teamId=$team&accountId=acc-1&accountId=acc-2"
                val own = client.drillItems(path, "$picks&view=AT_DONE&bucketIndex=0&segmentAccountId=acc-2")
                assertEquals(listOf("2", "8"), own.items.map { it.issueKey }.sorted())
                assertEquals(2, own.bar.count)
                assertEquals(0.5, own.bar.from)
                val none = client.drillItems(path, "$picks&view=AT_DONE&bucketIndex=0&segmentAccountId=acc-1")
                assertEquals(0, none.bar.count, "acc-1 has nothing in bucket 0")
                assertEquals(own.bar.from, none.bar.from, "the segment keeps the union's bucket")
                assertEquals(own.bar.to, none.bar.to)
                val acc1 = client.drillItems(path, "$picks&view=AT_DONE&bucketIndex=2&segmentAccountId=acc-1")
                assertEquals(listOf("1", "7"), acc1.items.map { it.issueKey }.sorted())
            }
            val hidden = withMinSampleSize(5) { client.get("/api/v1/reports/$path/items?$query&view=AT_START&bucketIndex=0") }
            assertEquals(HttpStatusCode.BadRequest, hidden.status, "4 at-start ratios are below a minimum sample size of 5")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactTaskDelivery(connId) }
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `epic accuracy drills use the own estimate and keep a no-actual, never-started or child-sum epic out`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "drill-epic-accuracy", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val team = TestTeams.seed(SyncedStubFixture.unique("drill-epic-accuracy"))
        val s = noonUtc("2026-01-05")
        val doneAt = noonUtc("2026-01-20")
        fun epic(id: Long, actual: Double, ownStart: Double?, ownDone: Double?, started: Long? = s, childSum: Double = 0.0) =
            handEpic(
                id, started, doneAt, ownStart = ownStart, ownDone = ownDone, ownCurrent = ownDone, childSum = childSum,
                actualMd = actual, ownerTeamId = team,
            )
        val rows = listOf(
            epic(1, 4.0, 2.0, 4.0), // start 2.0, done 1.0
            epic(2, 1.0, 2.0, 2.0), // 0.5, 0.5
            epic(3, 3.0, 2.0, 2.0), // 1.5, 1.5
            epic(4, 5.0, 2.0, 2.0), // 2.5, 2.5
            epic(5, 0.0, 2.0, 2.0), // noActual
            epic(6, 2.0, 2.0, 2.0, started = null), // never started: done 1.0 only
            epic(7, 2.0, null, null, childSum = 9.0), // a budget that is only the child sum: no ratio
            handEpic(8, s, null, ownStart = 2.0, ownDone = 2.0, actualMd = 2.0, ownerTeamId = team), // open
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactEpicDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-drill-epic-accuracy-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31&tailPercent=0&teamId=$team"
            val path = "epic-estimation-accuracy"
            withMinSampleSize(4) {
                // At start 0.5 1.5 2 2.5 over ten buckets of width 0.2: 0.5 -> 0, 1.5 -> 5, 2 -> 7, 2.5 (the max) -> 9.
                val start = client.keysByBucket(path, query, "AT_START", 10)
                assertEquals(setOf("2"), start[0])
                assertEquals(setOf("3"), start[5])
                assertEquals(setOf("1"), start[7])
                assertEquals(setOf("4"), start[9])
                assertEquals(setOf("1", "2", "3", "4"), start.flatten().toSet(), "5 noActual, 6 never started, 7 child-sum, 8 open")
                // At done 0.5 1 1 1.5 2.5: epic 6 never started but is measured at done.
                val done = client.keysByBucket(path, query, "AT_DONE", 10)
                assertEquals(setOf("2"), done[0])
                assertEquals(setOf("1", "6"), done[2])
                assertEquals(setOf("3"), done[5])
                assertEquals(setOf("4"), done[9])
                assertEquals(setOf("1", "2", "3", "4", "6"), done.flatten().toSet())
                val item = client.drillItems(path, "$query&view=AT_START&bucketIndex=7").items.single()
                assertEquals("EPIC", item.itemKind.name)
                assertEquals(team, item.teamId)
                assertEquals(null, item.accountId)
                assertEquals(2.0, item.figure)
                assertEquals(doneAt, item.anchorAt)
            }
            // USER level reads no epics (the report's own all-zero answer): there is no bar to drill into.
            val user = withMinSampleSize(1) {
                client.get("/api/v1/reports/$path/items?$query&accountId=acc-1&view=AT_START&bucketIndex=0")
            }
            assertEquals(HttpStatusCode.BadRequest, user.status)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactEpicDelivery(connId) }
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `blocked time drills count a never-blocked item as a zero, drop a no-cycle item from the share and list both kinds for BOTH`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = SyncedStubFixture.createConnection(namePrefix = "drill-blocked", enabled = false)
            val store = MetricsStore(sharedDatabaseForTests())
            val s = noonUtc("2026-01-02")
            fun task(id: Long, done: String?, blocked: Double, cycle: Double?, subtask: Boolean = false) =
                handTask(
                    id, if (cycle == null) null else s, done?.let { noonUtc(it) }, account = "acc-1", subtask = subtask,
                    cycleMs = cycle?.let { 1L }, cycleWorkingDays = cycle,
                ).copy(blockedMs = 1L, blockedWorkingDays = blocked)
            val tasks = listOf(
                task(1, "2026-01-06", 0.0, 4.0), // blocked 0, share 0
                task(2, "2026-01-07", 1.0, 2.0), // 1, 0.5
                task(3, "2026-01-08", 2.0, 8.0), // 2, 0.25
                task(4, "2026-01-09", 10.0, 10.0), // 10, 1.0
                task(5, "2026-01-15", 0.0, null), // never started: blocked 0, no share
                task(6, "2026-01-16", 0.0, 0.0), // zero cycle: blocked 0, no share
                task(8, "2026-01-10", 9.0, 9.0, subtask = true),
                task(9, null, 9.0, 9.0),
                task(10, "2025-05-01", 9.0, 9.0),
            )
            val epics = listOf(
                handEpic(21, s, noonUtc("2026-01-20"))
                    .copy(blockedMs = 1L, blockedWorkingDays = 5.0, cycleMs = 1L, cycleWorkingDays = 10.0),
                handEpic(22, s, noonUtc("2026-01-21")).copy(blockedMs = 0L, blockedWorkingDays = 0.0, cycleMs = 1L, cycleWorkingDays = 4.0),
            )
            suspendTransaction(sharedDatabaseForTests()) {
                store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
                store.replaceFactEpicDelivery(connId, epics, configRevision = 1L)
            }
            try {
                val client = seededClient("reports-drill-blocked-hand-built")
                val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31&tailPercent=0"
                val path = "blocked-time"
                withMinSampleSize(4) {
                    // Tasks: blocked 0 1 2 10 and the zeros of 5 and 6 over ten buckets of width 1: 0 -> 0, 1 -> 1, 2 -> 2, 10 -> 9.
                    val days = client.keysByBucket(path, query, "BLOCKED_WORKING_DAYS", 10)
                    assertEquals(setOf("1", "5", "6"), days[0], "a never-blocked item (even one with no cycle) is a real zero")
                    assertEquals(setOf("2"), days[1])
                    assertEquals(setOf("3"), days[2])
                    assertEquals(setOf("4"), days[9])
                    assertEquals(setOf("1", "2", "3", "4", "5", "6"), days.flatten().toSet(), "8 sub-task, 9 open, 10 outside the window")
                    // Share 0 0.5 0.25 1 over ten buckets of width 0.1: 0 -> 0, 0.25 -> 2, 0.5 -> 5, 1 (the max) -> 9; 5 and 6 have none.
                    val share = client.keysByBucket(path, query, "SHARE_OF_CYCLE", 10)
                    assertEquals(setOf("1"), share[0])
                    assertEquals(setOf("3"), share[2])
                    assertEquals(setOf("2"), share[5])
                    assertEquals(setOf("4"), share[9])
                    assertEquals(setOf("1", "2", "3", "4"), share.flatten().toSet(), "5 never started and 6 zero cycle have no share")
                    val top = client.drillItems(path, "$query&view=BLOCKED_WORKING_DAYS&bucketIndex=9").items.single()
                    assertEquals("TASK", top.itemKind.name)
                    assertEquals("acc-1", top.accountId)
                    assertEquals(10.0, top.figure)

                    // BOTH adds the two epics (blocked 5 and 0): width stays 1, epic 21 -> 5, epic 22 -> 0. Epics have no account.
                    val both = "$query&itemKind=BOTH"
                    val bothDays = client.keysByBucket(path, both, "BLOCKED_WORKING_DAYS", 10)
                    assertEquals(setOf("1", "5", "6", "22"), bothDays[0])
                    assertEquals(setOf("21"), bothDays[5])
                    val epicItem = client.drillItems(path, "$both&view=BLOCKED_WORKING_DAYS&bucketIndex=5").items.single()
                    assertEquals("EPIC", epicItem.itemKind.name)
                    assertEquals(null, epicItem.accountId)
                    val bothShare = client.keysByBucket(path, both, "SHARE_OF_CYCLE", 10)
                    assertEquals(setOf("1", "22"), bothShare[0])
                    assertEquals(setOf("2", "21"), bothShare[5])
                    val kinds = bothDays.flatten().toSet() + bothShare.flatten().toSet()
                    assertTrue(kinds.containsAll(setOf("21", "22", "1")))
                }
                withMinSampleSize(2) {
                    // Epics alone: blocked 5 and 0 over ten buckets of width 0.5: 0 -> 0, 5 (the max) -> 9.
                    val epicDays = client.keysByBucket(path, "$query&itemKind=EPIC", "BLOCKED_WORKING_DAYS", 10)
                    assertEquals(setOf("22"), epicDays[0])
                    assertEquals(setOf("21"), epicDays[9])
                }
            } finally {
                suspendTransaction(sharedDatabaseForTests()) {
                    store.deleteFactTaskDelivery(connId)
                    store.deleteFactEpicDelivery(connId)
                }
            }
        }

    @Test
    fun `the accuracy and blocked drills reject a bad view, bucketIndex or segment and the parent's own 400s`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-drill-accuracy-blocked-400")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val busiest = readTaskFacts(connId)
            .filter { it.team != null && it.account != null && it.doneAt != null && it.hasWorklogs && it.estDone != null }
            .groupingBy { it.team!! to it.account!! }.eachCount().maxBy { it.value }.key
        val team = busiest.first
        val person = busiest.second

        withMinSampleSize(1) {
            for ((path, view) in listOf(
                "task-estimation-accuracy" to "AT_DONE",
                "epic-estimation-accuracy" to "AT_START",
                "blocked-time" to "BLOCKED_WORKING_DAYS",
            )) {
                suspend fun assertStatus(expected: HttpStatusCode, query: String, why: String) =
                    assertEquals(expected, client.get("/api/v1/reports/$path/items?$query").status, "$path: $why")

                assertStatus(HttpStatusCode.OK, "$range&view=$view&bucketIndex=0", "the baseline answers")
                assertStatus(HttpStatusCode.BadRequest, "$range&bucketIndex=0", "view is required")
                assertStatus(HttpStatusCode.BadRequest, "$range&view=NOPE&bucketIndex=0", "an unknown view")
                assertStatus(HttpStatusCode.BadRequest, "$range&view=$view", "bucketIndex is required")
                assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=abc", "bucketIndex is an integer")
                assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=-1", "bucketIndex is not negative")
                assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=999", "bucketIndex beyond the histogram")
                assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=0&bucketIndex=1", "a repeated bucketIndex")
                assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=0&accountId=$person", "accountId needs teamId")
                assertStatus(HttpStatusCode.BadRequest, "$range&view=$view&bucketIndex=0&tailPercent=26", "tailPercent beyond 25")
                val backwards = "connectionId=$connId&from=2026-01-01&to=2025-01-01&view=$view&bucketIndex=0"
                assertStatus(HttpStatusCode.BadRequest, backwards, "to before from")
            }
            // The segment rule is the task report's only (the others carry no people): UNIT level, a stranger and a real pick.
            val task = "$range&view=AT_DONE&bucketIndex=0"
            suspend fun taskStatus(query: String) = client.get("/api/v1/reports/task-estimation-accuracy/items?$query").status
            assertEquals(HttpStatusCode.BadRequest, taskStatus("$task&segmentAccountId=$person"), "UNIT level")
            assertEquals(
                HttpStatusCode.BadRequest, taskStatus("$task&teamId=$team&accountId=$person&segmentAccountId=somebody-else"), "a stranger",
            )
            assertEquals(HttpStatusCode.OK, taskStatus("$task&teamId=$team&accountId=$person&segmentAccountId=$person"), "a real pick")
            val itemKind = client.get("/api/v1/reports/blocked-time/items?$range&view=BLOCKED_WORKING_DAYS&bucketIndex=0&itemKind=NONE")
            assertEquals(HttpStatusCode.BadRequest, itemKind.status, "blocked-time validates the parent's itemKind")
            val epicUser = client.get(
                "/api/v1/reports/epic-estimation-accuracy/items?$range&view=AT_START&bucketIndex=0&teamId=$team&accountId=$person",
            )
            assertEquals(HttpStatusCode.BadRequest, epicUser.status, "USER level reads no epics: no bar")
        }
        // A distribution too small to show draws no bar: the whole fixture hidden by a huge minimum sample size.
        for ((path, view) in listOf(
            "task-estimation-accuracy" to "AT_START", "epic-estimation-accuracy" to "AT_START", "blocked-time" to "SHARE_OF_CYCLE",
        )) {
            val hidden = withMinSampleSize(100_000) { client.get("/api/v1/reports/$path/items?$range&view=$view&bucketIndex=0") }
            assertEquals(HttpStatusCode.BadRequest, hidden.status, "$path: a hidden distribution has no bar")
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
