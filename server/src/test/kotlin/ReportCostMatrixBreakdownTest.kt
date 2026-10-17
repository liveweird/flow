package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.reports.CostBreakdownSlice
import ch.nokillswit.reports.CostMatrixReport
import ch.nokillswit.reports.ReportDrillItems
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

private const val ROUNDING_SLACK = 0.005

/** The hand-built world of one breakdown test: a fresh DISABLED connection, two teams and four named authors. Removed in the `finally`. */
private class BreakdownWorld(
    val connId: UInt,
    val t1: UInt,
    val t2: UInt,
    val alice: String,
    val bob: String,
    val carol: String,
    val dave: String,
) {
    val range get() = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
}

/** A worklog `issueId` logged by [account] (`epicId` set = logged ON that epic). */
private fun log(
    id: Long,
    issue: Long,
    team: UInt?,
    account: String?,
    day: String,
    md: Double,
    activity: String,
    category: String?,
    domain: String = "AAA",
    epicId: Long? = null,
): FactWorklogRow = wl(id, team, account, day, md, domain, activity = activity, category = category).copy(issueId = issue, epicId = epicId)

/**
 * January 2026, Europe/Warsaw. Task 101 (Alice, Bob) with a sub-task 102 (Alice), task 103 (domain BBB, Alice), the epic 104 logged on
 * by Bob, task 105 (Carol, three thirds) and task 106 (Dave, no team, whose activity type is NULL in the stored row):
 *
 *  - w1 101 Alice 1.0 / w2 101 Bob 0.5 / w3 102 (sub-task of 101) Alice 0.25 — Development, category Run
 *  - w4 103 Alice 2.0 — Testing, no category, domain BBB
 *  - w5 104 (an epic) Bob 1.5 — Planning, Run
 *  - w6/w7/w8 105 Carol 0.3333 each — Support Run / Support none / Testing Build
 *  - w9 106 Dave 0.5 — activity NULL, Build, no team; w10 Alice 99 MD on 2026-02-20 (outside January)
 */
private suspend fun withBreakdownWorld(block: suspend (BreakdownWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "cost-breakdown", enabled = false)
    val tag = UUID.randomUUID().toString()
    val ids = listOf("alice", "bob", "carol", "dave").associateWith { "account-$tag-$it" }
    SyncedStubFixture.workItems().replacePeople(
        connId,
        ids.map { (name, id) -> PersonRef(id, name.replaceFirstChar(Char::uppercase), null, active = true) },
    )
    val t1 = TestTeams.seed(SyncedStubFixture.unique("cb-t1"))
    val t2 = TestTeams.seed(SyncedStubFixture.unique("cb-t2"))
    val alice = ids.getValue("alice")
    val bob = ids.getValue("bob")
    val carol = ids.getValue("carol")
    val dave = ids.getValue("dave")
    val world = BreakdownWorld(connId, t1, t2, alice, bob, carol, dave)
    val store = MetricsStore(sharedDatabaseForTests())
    suspendTransaction(sharedDatabaseForTests()) {
        store.insertTasks(
            connId,
            listOf(
                DimTaskRow(101, "CB-101", "Story", "Development", "Run", "NONE", false, null, "AAA", null),
                DimTaskRow(102, "CB-102", "Sub-task", "Development", "Run", "NONE", true, 101, "AAA", null),
                DimTaskRow(103, "CB-103", "Story", "Testing", null, "NONE", false, null, "BBB", null),
                DimTaskRow(105, "CB-105", "Story", "Support", "Run", "NONE", false, null, "AAA", null),
                DimTaskRow(106, "CB-106", "Story", "Other", "Build", "NONE", false, null, "AAA", null),
            ),
            configRevision = 1L,
        )
        store.insertEpics(
            connId,
            listOf(DimEpicRow(104, "CB-E104", "The epic", "AAA", "Run", "IN_PROGRESS", null, null)),
            configRevision = 1L,
        )
        store.insertFactWorklog(
            connId,
            listOf(
                log(1, 101, t1, alice, "2026-01-10", 1.0, "Development", "Run"),
                log(2, 101, t1, bob, "2026-01-11", 0.5, "Development", "Run"),
                log(3, 102, t1, alice, "2026-01-12", 0.25, "Development", "Run"),
                log(4, 103, t1, alice, "2026-01-13", 2.0, "Testing", null, domain = "BBB"),
                log(5, 104, t1, bob, "2026-01-14", 1.5, "Planning", "Run", epicId = 104),
                log(6, 105, t2, carol, "2026-01-15", 0.3333, "Support", "Run"),
                log(7, 105, t2, carol, "2026-01-16", 0.3333, "Support", null),
                log(8, 105, t2, carol, "2026-01-17", 0.3333, "Testing", "Build"),
                log(9, 106, null, dave, "2026-01-18", 0.5, "Other", "Build"),
                log(10, 101, t1, alice, "2026-02-20", 99.0, "Development", "Run"),
            ),
            configRevision = 1L,
        )
        // The derive always fills the activity type, so a NULL is a corrupt row; the stored column allows it and the key is UNCATEGORIZED.
        exec("UPDATE metrics.fact_worklog SET activity_type = NULL WHERE connection_id = $connId AND worklog_id = 9")
    }
    try {
        block(world)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteFactWorklog(connId)
            store.deleteDims(connId)
        }
        cleanUpTeams(listOf(t1, t2))
    }
}

private fun List<CostBreakdownSlice>.asMap(): Map<String, Double> = associate { it.key to it.md }

private fun List<CostBreakdownSlice>.exactSumTolerance() = ROUNDING_SLACK * size + 1e-9

/**
 * Report 16's activity-type and work-category breakdowns (`byActivityType`, `byWorkCategory`) and the cost drill behind a slice
 * (`GET /api/v1/reports/cost-matrix/items`): the same worklogs as the matrix, one slice per key, the rounded exact sums, a null key
 * as `UNCATEGORIZED`, and a drill whose bar equals the slice figure.
 */
class ReportCostMatrixBreakdownTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }

    private suspend fun HttpClient.cost(query: String): CostMatrixReport {
        val response = get("/api/v1/reports/cost-matrix?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cost-matrix?$query")
        return response.body()
    }

    private suspend fun HttpClient.items(query: String): ReportDrillItems {
        val response = get("/api/v1/reports/cost-matrix/items?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cost-matrix/items?$query")
        return response.body()
    }

    private suspend fun HttpClient.status(query: String): HttpStatusCode = get("/api/v1/reports/cost-matrix/items?$query").status

    @Test
    fun `hand-built rows pin both lists - order, null keys and the exact sums against the total`() = testApplication {
        usePostgresTestcontainer()
        withBreakdownWorld { w ->
            val report = seededClient("cb-lists").cost(w.range)
            assertEquals(6.75, report.totalMd)
            assertEquals(
                listOf("Testing" to 2.33, "Development" to 1.75, "Planning" to 1.5, "Support" to 0.67, "UNCATEGORIZED" to 0.5),
                report.byActivityType.map { it.key to it.md },
                "by md descending, a NULL activity type is UNCATEGORIZED, the period excludes the 99 MD of February",
            )
            assertEquals(
                listOf("Run" to 3.58, "UNCATEGORIZED" to 2.33, "Build" to 0.83),
                report.byWorkCategory.map { it.key to it.md },
                "work categories: none is UNCATEGORIZED",
            )
            // The unrounded sum rides beside the rounded one (Support: two thirds, shown 0.67).
            assertEquals(0.6666, report.byActivityType.single { it.key == "Support" }.exactMd, 1e-9)
            // Exact sums: every list adds up to the grand total to the cent of its rounding; the thirds make the rounded categories sum to
            // 6.74 against 6.75 — the matrix's own 0.005-per-addend rule.
            for (slices in listOf(report.byActivityType, report.byWorkCategory)) {
                assertEquals(report.totalMd, slices.sumOf { it.md }, slices.exactSumTolerance())
            }
            assertEquals(6.74, report.byWorkCategory.sumOf { it.md }, 1e-9)
        }
    }

    @Test
    fun `ties on the figure order by key, and the slices narrow both lists`() = testApplication {
        usePostgresTestcontainer()
        withBreakdownWorld { w ->
            val client = seededClient("cb-slices")
            // Carol's team: three thirds, each category 0.33 — equal figures order by key.
            val team = client.cost("${w.range}&teamId=${w.t2}")
            assertEquals(listOf("Support" to 0.67, "Testing" to 0.33), team.byActivityType.map { it.key to it.md })
            assertEquals(listOf("Build" to 0.33, "Run" to 0.33, "UNCATEGORIZED" to 0.33), team.byWorkCategory.map { it.key to it.md })
            // One author (USER level) and the UNASSIGNED bucket.
            val user = client.cost("${w.range}&teamId=${w.t1}&accountId=${w.bob}")
            assertEquals(mapOf("Planning" to 1.5, "Development" to 0.5), user.byActivityType.asMap())
            assertEquals(listOf("UNCATEGORIZED" to 0.5), client.cost("${w.range}&teamId=0").byActivityType.map { it.key to it.md })
            // Each filter narrows BOTH lists: the other breakdown is the slice's share, not the whole.
            val activity = client.cost("${w.range}&activityType=Development")
            assertEquals(listOf("Development" to 1.75), activity.byActivityType.map { it.key to it.md })
            assertEquals(listOf("Run" to 1.75), activity.byWorkCategory.map { it.key to it.md })
            val none = client.cost("${w.range}&workCategory=UNCATEGORIZED")
            assertEquals(listOf("UNCATEGORIZED" to 2.33), none.byWorkCategory.map { it.key to it.md })
            assertEquals(listOf("Testing" to 2.0, "Support" to 0.33), none.byActivityType.map { it.key to it.md })
            assertEquals(2.33, none.totalMd)
            // The domain slice follows the view's column: only task 103's worklog is BBB.
            val domain = client.cost("${w.range}&domain=BBB")
            assertEquals(listOf("Testing" to 2.0), domain.byActivityType.map { it.key to it.md })
            assertEquals(listOf("UNCATEGORIZED" to 2.0), domain.byWorkCategory.map { it.key to it.md })
            // A matrix with nothing in it has empty lists.
            val empty = client.cost("connectionId=${w.connId}&from=2024-01-01&to=2024-01-31")
            assertTrue(empty.byActivityType.isEmpty() && empty.byWorkCategory.isEmpty())
            // Both breakdowns ride a split response unchanged.
            val split = client.cost("${w.range}&split=WEEK")
            assertEquals(client.cost(w.range).byActivityType, split.byActivityType)
        }
    }

    @Test
    fun `the drill lists a slice's tasks - sub-tasks rolled up, authors named, a bar equal to the slice figure`() = testApplication {
        usePostgresTestcontainer()
        withBreakdownWorld { w ->
            val client = seededClient("cb-drill")
            // Development: task 101 with its sub-task 102's worklog rolled up; Alice 1.25 then Bob 0.5.
            val development = client.items("${w.range}&by=ACTIVITY_TYPE&key=Development")
            assertEquals(1, development.items.size)
            val task = development.items.single()
            assertEquals(listOf("101", 1.75), listOf(task.issueKey, task.figure))
            assertEquals("TASK", task.itemKind.name)
            assertEquals("Alice, Bob", task.assignee, "the largest contributor first")
            assertNull(task.accountId, "two authors: no single account")
            assertEquals(1, development.bar.count)
            assertEquals(1.75, development.bar.sumMd)
            assertEquals(listOf(1, false), listOf(development.total, development.truncated))
            assertEquals(development.bar.count, development.total)

            // Work category Run: the task, the epic (logged ON it) and Carol's task, largest first; the epic lists as an EPIC with its key.
            val run = client.items("${w.range}&by=WORK_CATEGORY&key=Run")
            assertEquals(listOf("101" to 1.75, "CB-E104" to 1.5, "105" to 0.33), run.items.map { it.issueKey to it.figure })
            assertEquals(listOf("TASK", "EPIC", "TASK"), run.items.map { it.itemKind.name })
            assertEquals(listOf(null, "The epic", null), run.items.map { it.summary })
            val epic = run.items[1]
            assertEquals(listOf(w.bob, "Bob"), listOf(epic.accountId, epic.assignee), "one author: named and carried as the account")
            assertEquals(3, run.bar.count)
            assertEquals(3.58, run.bar.sumMd, "the bar is the rounded EXACT sum (3.5833), the slice's own figure")

            // UNCATEGORIZED selects the NULLs: task 103 (no category) and Carol's one uncategorised third.
            val none = client.items("${w.range}&by=WORK_CATEGORY&key=UNCATEGORIZED")
            assertEquals(listOf("103" to 2.0, "105" to 0.33), none.items.map { it.issueKey to it.figure })
            assertEquals(2.33, none.bar.sumMd)

            // A NULL activity type is listed under UNCATEGORIZED, and a repeated key is the folded "Other": the union of its slices.
            val other = client.items("${w.range}&by=ACTIVITY_TYPE&key=Planning&key=Support&key=UNCATEGORIZED")
            assertEquals(listOf("CB-E104" to 1.5, "105" to 0.67, "106" to 0.5), other.items.map { it.issueKey to it.figure })
            assertEquals(2.67, other.bar.sumMd, "(1.5 + 0.6666 + 0.5) rounded once")
            assertEquals("Dave", other.items.last().assignee)
            assertTrue(other.items.all { it.anchorAt != null })

            // Every key of both lists: the drill's bar is the slice's figure and counts the listed items.
            val report = client.cost(w.range)
            for ((by, slices) in listOf("ACTIVITY_TYPE" to report.byActivityType, "WORK_CATEGORY" to report.byWorkCategory)) {
                for (slice in slices) {
                    val drill = client.items("${w.range}&by=$by&key=${slice.key}")
                    assertEquals(slice.md, drill.bar.sumMd, "$by ${slice.key}")
                    assertEquals(drill.items.size, drill.bar.count, "$by ${slice.key}")
                    assertEquals(drill.bar.count, drill.total)
                }
                // All keys at once = the whole matrix.
                val all = client.items("${w.range}&by=$by" + slices.joinToString("") { "&key=${it.key}" })
                assertEquals(report.totalMd, all.bar.sumMd, "$by: every key is the grand total")
            }
        }
    }

    @Test
    fun `the drill follows the matrix's level and slices`() = testApplication {
        usePostgresTestcontainer()
        withBreakdownWorld { w ->
            val client = seededClient("cb-drill-slices")
            val aliceOnly = client.items("${w.range}&teamId=${w.t1}&accountId=${w.alice}&by=ACTIVITY_TYPE&key=Development")
            val task = aliceOnly.items.single()
            assertEquals(listOf("101", 1.25, w.alice, "Alice"), listOf(task.issueKey, task.figure, task.accountId, task.assignee))
            assertEquals(1.25, aliceOnly.bar.sumMd)
            assertEquals(1.75, client.items("${w.range}&teamId=${w.t1}&by=ACTIVITY_TYPE&key=Development").bar.sumMd)
            assertEquals(0, client.items("${w.range}&teamId=${w.t2}&by=ACTIVITY_TYPE&key=Development").bar.count)
            // The activity-type filter narrows a work-category drill: Run under Development is task 101 alone.
            val narrowed = client.items("${w.range}&activityType=Development&by=WORK_CATEGORY&key=Run")
            assertEquals(listOf("101" to 1.75), narrowed.items.map { it.issueKey to it.figure })
            // An unknown key and an empty period are valid empty answers (never 404).
            val unknown = client.items("${w.range}&by=ACTIVITY_TYPE&key=Nonexistent")
            assertEquals(listOf(0, 0.0, false), listOf(unknown.bar.count, unknown.bar.sumMd, unknown.truncated))
            assertEquals(0, client.items("connectionId=${w.connId}&from=2024-01-01&to=2024-01-31&by=ACTIVITY_TYPE&key=Development").total)
            // domainView=TASK moves nothing of a breakdown (it is a column concern), but the domain slice follows the view.
            assertEquals(2.0, client.items("${w.range}&domain=BBB&by=ACTIVITY_TYPE&key=Testing").bar.sumMd)
        }
    }

    @Test
    fun `the drill 400s - by, key, the bound, control characters and the parent's own parser errors`() = testApplication {
        usePostgresTestcontainer()
        withBreakdownWorld { w ->
            val client = seededClient("cb-drill-400")
            val base = w.range
            assertEquals(HttpStatusCode.BadRequest, client.status("$base&key=Development"), "by is required")
            assertEquals(HttpStatusCode.BadRequest, client.status("$base&by=DOMAIN&key=Development"), "an unknown by")
            assertEquals(HttpStatusCode.BadRequest, client.status("$base&by=ACTIVITY_TYPE"), "key is required")
            assertEquals(HttpStatusCode.BadRequest, client.status("$base&by=ACTIVITY_TYPE&key="), "a blank key is no key")
            assertEquals(HttpStatusCode.BadRequest, client.status("$base&by=ACTIVITY_TYPE&key=A%01B"), "a control character")
            val tooMany = (1..101).joinToString("") { "&key=k$it" }
            assertEquals(HttpStatusCode.BadRequest, client.status("$base&by=ACTIVITY_TYPE$tooMany"), "101 keys")
            val hundred = (1..100).joinToString("") { "&key=k$it" }
            assertEquals(HttpStatusCode.OK, client.status("$base&by=ACTIVITY_TYPE$hundred"), "100 keys is the bound")
            assertEquals(
                HttpStatusCode.OK, client.status("$base&by=ACTIVITY_TYPE&key=Development&key=Development"),
                "duplicates collapse before the bound",
            )
            val reversed = "connectionId=${w.connId}&from=2026-02-01&to=2026-01-01"
            assertEquals(HttpStatusCode.BadRequest, client.status("$reversed&by=ACTIVITY_TYPE&key=x"), "from after to")
            assertEquals(
                HttpStatusCode.BadRequest, client.status("$base&accountId=${w.alice}&by=ACTIVITY_TYPE&key=x"), "accountId needs teamId",
            )
            assertEquals(HttpStatusCode.BadRequest, client.status("$base&teamId=999999&by=ACTIVITY_TYPE&key=x"), "an unknown team")
            assertEquals(HttpStatusCode.BadRequest, client.status("$base&by=ACTIVITY_TYPE&by=WORK_CATEGORY&key=x"), "a repeated by")
        }
    }

    @Test
    fun `two slices of 0_125 each show 0_13 but merge to the drill's 0_25 through their exact figures`() = testApplication {
        usePostgresTestcontainer()
        withBreakdownWorld { w ->
            val store = MetricsStore(sharedDatabaseForTests())
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertFactWorklog(
                    w.connId,
                    listOf(
                        log(2001, 7001, w.t1, w.alice, "2026-01-21", 0.125, "TinyA", "Tiny"),
                        log(2002, 7002, w.t1, w.alice, "2026-01-21", 0.125, "TinyB", "Tiny"),
                    ),
                    configRevision = 1L,
                )
            }
            val client = seededClient("cb-exact")
            val tiny = client.cost(w.range).byActivityType.filter { it.key.startsWith("Tiny") }
            assertEquals(listOf(0.13, 0.13), tiny.map { it.md }, "each rounds half-up on its own")
            assertEquals(listOf(0.125, 0.125), tiny.map { it.exactMd })
            // The merged slice: the sum of the exact figures rounded once IS the drill's bar (0.13 + 0.13 would be 0.26).
            val merged = client.items("${w.range}&by=ACTIVITY_TYPE&key=TinyA&key=TinyB")
            assertEquals(0.25, merged.bar.sumMd)
            assertEquals(0.25, tiny.sumOf { it.exactMd })
        }
    }

    @Test
    fun `the 501st item truncates the list while the bar and the total cover them all`() = testApplication {
        usePostgresTestcontainer()
        withBreakdownWorld { w ->
            val store = MetricsStore(sharedDatabaseForTests())
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertFactWorklog(
                    w.connId,
                    (1..501).map { log(1_000L + it, 5_000L + it, w.t1, w.alice, "2026-01-20", 0.1, "Crowded", "Crowd") },
                    configRevision = 1L,
                )
            }
            val drill = seededClient("cb-drill-501").items("${w.range}&by=ACTIVITY_TYPE&key=Crowded")
            assertEquals(500, drill.items.size)
            assertEquals(501, drill.total)
            assertEquals(501, drill.bar.count)
            assertEquals(50.1, drill.bar.sumMd)
            assertTrue(drill.truncated)
        }
    }

    // ---- the derived fixture: an independent read of fact_worklog ----------------------------------------------------------------

    private data class Stored(val activity: String?, val category: String?, val startedAt: Long, val md: BigDecimal)

    private suspend fun storedWorklogs(connId: UInt, bounds: Pair<Long, Long>): List<Stored> =
        suspendTransaction(sharedDatabaseForTests()) {
            val w = MetricsTables.FactWorklog
            w.selectAll().where { w.connectionId eq connId }.toList()
                .map { Stored(it[w.activityType], it[w.workCategory], it[w.startedAt], it[w.md]) }
                .filter { it.startedAt >= bounds.first && it.startedAt < bounds.second }
        }

    private fun BigDecimal.md(): Double = setScale(2, RoundingMode.HALF_UP).toDouble()

    @Test
    fun `fixture - both lists equal an independent grouping of the worklogs and every drill bar equals its slice`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("cb-fixture")
        val bounds = windowBounds(reportZone(), "2024-01-01", "2026-12-31")
        val logs = storedWorklogs(connId, bounds)
        assertTrue(logs.isNotEmpty(), "the window must hold worklogs")
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val report = client.cost(query)
        val expected = mapOf<String, (Stored) -> String?>("ACTIVITY_TYPE" to { it.activity }, "WORK_CATEGORY" to { it.category })
        for ((by, slices) in listOf("ACTIVITY_TYPE" to report.byActivityType, "WORK_CATEGORY" to report.byWorkCategory)) {
            val grouped = logs.groupBy({ expected.getValue(by)(it) ?: "UNCATEGORIZED" }, { it.md })
                .mapValues { (_, parts) -> parts.fold(BigDecimal.ZERO, BigDecimal::add).md() }
            assertEquals(grouped, slices.asMap(), "$by equals an independent grouping")
            assertEquals(grouped.size, slices.size, "$by one slice per key")
            assertEquals(slices.sortedWith(compareByDescending<CostBreakdownSlice> { it.md }.thenBy { it.key }), slices, "$by order")
            assertEquals(report.totalMd, slices.sumOf { it.md }, slices.exactSumTolerance(), "$by adds up to the total")
            for (slice in slices) {
                val drill = client.items("$query&by=$by&key=${URLEncoder.encode(slice.key, "UTF-8")}")
                assertEquals(slice.md, drill.bar.sumMd, "$by ${slice.key}: the bar is the slice")
                assertEquals(drill.bar.count, drill.total, "$by ${slice.key}")
                assertEquals(drill.total > drill.items.size, drill.truncated)
                if (!drill.truncated) {
                    assertEquals(
                        drill.bar.sumMd!!, drill.items.sumOf { it.figure ?: 0.0 },
                        ROUNDING_SLACK * drill.items.size + 1e-9, "$by ${slice.key}: the items add up to the bar",
                    )
                }
            }
        }
    }
}
