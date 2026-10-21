package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.SprintTotals
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.reports.CostEpicBreakdown
import ch.nokillswit.reports.CostEpicRow
import ch.nokillswit.reports.CostEpicRowKind
import ch.nokillswit.reports.CostMatrixReport
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ResolvedSprintGroup
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.math.RoundingMode
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

private const val EPS = 1e-9
private const val UNASSIGNED_TEAM = 0u

/** The hand-built world of one epic test: a fresh DISABLED connection, two teams and four named authors. Removed in the `finally`. */
private class EpicWorld(
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

/** A worklog on [issue] (`epicId` = the epic the derive stored: the task's, the parent's, the epic itself, or none). */
private fun log(
    id: Long,
    issue: Long,
    team: UInt?,
    account: String?,
    day: String,
    md: Double,
    epicId: Long?,
    activity: String = "Development",
) =
    wl(id, team, account, day, md, "AAA", activity = activity).copy(issueId = issue, epicId = epicId)

private fun epic(id: Long, summary: String, domain: String = "AAA") =
    DimEpicRow(id, "EW-E$id", summary, domain, "Run", "IN_PROGRESS", null, null)

private fun task(id: Long, epicId: Long?, parent: Long? = null) =
    DimTaskRow(id, "EW-$id", if (parent == null) "Story" else "Sub-task", "Development", "Run", "NONE",
        parent != null, parent, "AAA", epicId)

private suspend fun withEpicWorld(
    tasks: List<DimTaskRow>,
    epics: List<DimEpicRow>,
    logs: (EpicWorld) -> List<FactWorklogRow>,
    block: suspend (EpicWorld) -> Unit,
) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "cost-epics", enabled = false)
    val tag = UUID.randomUUID().toString()
    val ids = listOf("alice", "bob", "carol", "dave").associateWith { "account-$tag-$it" }
    SyncedStubFixture.workItems().replacePeople(
        connId,
        ids.map { (name, id) -> PersonRef(id, name.replaceFirstChar(Char::uppercase), null, active = true) },
    )
    val t1 = TestTeams.seed(SyncedStubFixture.unique("ce-t1"))
    val t2 = TestTeams.seed(SyncedStubFixture.unique("ce-t2"))
    val world = EpicWorld(connId, t1, t2, ids.getValue("alice"), ids.getValue("bob"), ids.getValue("carol"), ids.getValue("dave"))
    val store = MetricsStore(sharedDatabaseForTests())
    suspendTransaction(sharedDatabaseForTests()) {
        store.insertTasks(connId, tasks, configRevision = 1L)
        store.insertEpics(connId, epics, configRevision = 1L)
        store.insertFactWorklog(connId, logs(world), configRevision = 1L)
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

/**
 * January 2026. Epic 901 "Checkout" (domain AAA), epic 902 "Search" (domain BBB); T1 = Alice, Bob and a worklog with no author, T2 = Carol,
 * Dave in no team:
 *  - w1 task 101 (epic 901) Alice 1.0 / w2 sub-task 102 of 101 Bob 0.5 (the stored epic is the parent's 901) / w3 ON epic 901 Alice 0.25
 *  - w4 task 103 (no epic) Alice 2.0 / w5 issue 104 above the epic level Carol 0.75 (no epic) — both "No epic"
 *  - w6 task 105 (epic 902) Carol 1.0, activity Testing / w8 task 106 (epic 902) the no-author T1 worklog 0.5
 *  - w9 task 107 (no epic) Dave, no team, 4.0; w7 Alice 99 MD on 2026-02-20 (outside January)
 */
private suspend fun withAttributionWorld(block: suspend (EpicWorld) -> Unit) = withEpicWorld(
    tasks = listOf(task(101, 901), task(102, 901, parent = 101), task(103, null), task(105, 902), task(106, 902), task(107, null)),
    epics = listOf(epic(901, "Checkout"), epic(902, "Search", domain = "BBB")),
    logs = { w ->
        listOf(
            log(1, 101, w.t1, w.alice, "2026-01-10", 1.0, 901),
            log(2, 102, w.t1, w.bob, "2026-01-11", 0.5, 901),
            log(3, 901, w.t1, w.alice, "2026-01-12", 0.25, 901),
            log(4, 103, w.t1, w.alice, "2026-01-13", 2.0, null),
            log(5, 104, w.t2, w.carol, "2026-01-14", 0.75, null),
            log(6, 105, w.t2, w.carol, "2026-01-15", 1.0, 902, activity = "Testing"),
            log(7, 101, w.t1, w.alice, "2026-02-20", 99.0, 901),
            log(8, 106, w.t1, null, "2026-01-16", 0.5, 902),
            log(9, 107, null, w.dave, "2026-01-17", 4.0, null),
        )
    },
    block = block,
)

private fun CostEpicBreakdown.row(kind: CostEpicRowKind, epicId: Long? = null): CostEpicRow =
    rows.single { it.kind == kind && it.epicId == epicId }

/** The rows as `key to cells` for terse comparisons (`EPIC` rows by id, the sentinels by name). */
private fun CostEpicBreakdown.byKey(): Map<String, List<Double>> =
    rows.associate { (if (it.kind == CostEpicRowKind.EPIC) it.epicId.toString() else it.kind.name) to it.cells }

private fun List<BigDecimal>.exactSum(): BigDecimal = fold(BigDecimal.ZERO, BigDecimal::add)

private fun BigDecimal.md(): Double = setScale(2, RoundingMode.HALF_UP).toDouble()

private fun CostEpicRow.drillKey(): String = when (kind) {
    CostEpicRowKind.EPIC -> epicId.toString()
    CostEpicRowKind.NO_EPIC -> "NO_EPIC"
    CostEpicRowKind.OTHER -> "OTHER_EPICS"
}

/**
 * Report 16's epic table (`byEpic`) and its drill (`GET /api/v1/reports/cost-matrix/items?by=EPIC`): the matrix's own worklogs split by
 * the epic stored on each, rows = epics (the 50 largest, then "Other epics" and "No epic"), columns = the matrix's rows for the level.
 */
class ReportCostMatrixEpicsTest {

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
    fun `hand-built rows pin attribution - sub-task to the parent's epic, epic-logged to its own, epic-less and above-epic to No epic`() =
        testApplication {
            usePostgresTestcontainer()
            withAttributionWorld { w ->
                val report = seededClient("ce-attribution").cost(w.range)
                val epics = report.byEpic
                val t1 = epics.columns.indexOfFirst { it.teamId == w.t1 }
                val t2 = epics.columns.indexOfFirst { it.teamId == w.t2 }
                val none = epics.columns.indexOfFirst { it.teamId == UNASSIGNED_TEAM }
                assertEquals(listOf(4.25, 1.75, 4.0), listOf(t1, t2, none).map { epics.columns[it].totalMd }, "columns: T1, T2, UNASSIGNED")
                assertEquals(
                    listOf("901", "902", "NO_EPIC"),
                    epics.rows.map { if (it.kind == CostEpicRowKind.EPIC) it.epicId.toString() else it.kind.name },
                    "by exact total (1.75, 1.5), No epic last although it is the largest (6.75)",
                )
                // Epic 901: task 101 (1.0), its sub-task's worklog (Bob 0.5, the parent's epic) and the worklog ON the epic (0.25) = 1.75.
                val checkout = epics.row(CostEpicRowKind.EPIC, 901)
                assertEquals(
                    listOf("EW-E901", "Checkout", "AAA", null),
                    listOf(checkout.issueKey, checkout.summary, checkout.domain, checkout.count),
                )
                assertEquals(1.75, checkout.totalMd)
                assertEquals(listOf(1.75, 0.0, 0.0), listOf(t1, t2, none).map { checkout.cells[it] })
                val search = epics.row(CostEpicRowKind.EPIC, 902)
                assertEquals(listOf("EW-E902", "Search", "BBB"), listOf(search.issueKey, search.summary, search.domain))
                assertEquals(listOf(0.5, 1.0, 0.0), listOf(t1, t2, none).map { search.cells[it] }, "T1's no-author worklog counts")
                // No epic: the epic-less task 103 (2.0), the issue above the epic level (T2 0.75) and Dave's task 107 (4.0).
                val noEpic = epics.row(CostEpicRowKind.NO_EPIC)
                assertEquals(listOf(null, null, null, null), listOf(noEpic.epicId, noEpic.issueKey, noEpic.summary, noEpic.count))
                assertEquals(listOf(2.0, 0.75, 4.0), listOf(t1, t2, none).map { noEpic.cells[it] })
                assertEquals(6.75, noEpic.totalMd)
                // The period excludes the 99 MD of February; the rows add up to the total.
                assertEquals(report.totalMd, epics.rows.sumOf { it.totalMd }, EPS)
                assertTrue(epics.rows.all { it.cells.size == epics.columns.size }, "dense cells")
            }
        }

    @Test
    fun `rows order by exact total then epic id, No epic last, and the columns are the matrix's rows that logged with equal totals`() =
        testApplication {
            usePostgresTestcontainer()
            withEpicWorld(
                tasks = emptyList(),
                epics = listOf(epic(910, "Ten"), epic(911, "Eleven"), epic(912, "Twelve"), epic(913, "Thirteen")),
                logs = { w ->
                    listOf(
                        log(1, 910, w.t1, w.alice, "2026-01-10", 1.0, 910), log(2, 910, w.t1, w.bob, "2026-01-10", 1.0, 910),
                        log(3, 911, w.t1, w.alice, "2026-01-11", 2.0, 911), log(4, 912, w.t1, w.bob, "2026-01-12", 3.0, 912),
                        log(5, 913, w.t1, w.alice, "2026-01-13", 0.5, 913), log(6, 920, w.t1, w.alice, "2026-01-14", 10.0, null),
                    )
                },
            ) { w ->
                val client = seededClient("ce-order")
                val team = client.cost("${w.range}&teamId=${w.t1}")
                assertEquals(
                    listOf(912L to 3.0, 910L to 2.0, 911L to 2.0, 913L to 0.5),
                    team.byEpic.rows.filter { it.kind == CostEpicRowKind.EPIC }.map { it.epicId!! to it.totalMd },
                    "exact total descending, the equal 2.0 totals by epic id",
                )
                assertEquals(CostEpicRowKind.NO_EPIC, team.byEpic.rows.last().kind, "No epic is last although its 10.0 is the largest")
                assertEquals(
                    team.rows.map { listOf(it.teamId, it.accountId, it.label, it.totalMd) },
                    team.byEpic.columns.map { listOf(it.teamId, it.accountId, it.label, it.totalMd) },
                    "the columns are the matrix's rows, same identity, order and total",
                )
                assertEquals(listOf(13.5, 4.0), team.byEpic.columns.map { it.totalMd })
                val unit = client.cost(w.range)
                assertEquals(
                    unit.rows.map { listOf(it.teamId, it.label, it.totalMd) },
                    unit.byEpic.columns.map { listOf(it.teamId, it.label, it.totalMd) },
                )
                // A picked person who logged nothing in the slice is a zero row of the matrix and no column of the epic table.
                val zero = client.cost("${w.range}&teamId=${w.t1}&accountId=${w.carol}")
                assertEquals(listOf(w.carol), zero.rows.map { it.accountId })
                assertEquals(CostEpicBreakdown(emptyList(), emptyList()), zero.byEpic)
                val empty = client.cost("connectionId=${w.connId}&from=2024-01-01&to=2024-01-31")
                assertEquals(CostEpicBreakdown(emptyList(), emptyList()), empty.byEpic)
            }
        }

    /** 52 epics of one team: 49 of 1.0 (Alice), then 1049 (Bob 0.3333), 1050 (Bob 0.3333) and 1051 (Alice 0.3333). */
    private suspend fun withCrowdedWorld(
        extra: (EpicWorld) -> List<FactWorklogRow> = { emptyList() },
        block: suspend (EpicWorld) -> Unit,
    ) = withEpicWorld(
        tasks = emptyList(),
        epics = (1000L..1051L).map { epic(it, "Crowd $it") },
        logs = { w ->
            (1000L..1048L).map { log(it, it, w.t1, w.alice, "2026-01-10", 1.0, it) } + listOf(
                log(2049, 1049, w.t1, w.bob, "2026-01-11", 0.3333, 1049),
                log(2050, 1050, w.t1, w.bob, "2026-01-11", 0.3333, 1050),
                log(2051, 1051, w.t1, w.alice, "2026-01-11", 0.3333, 1051),
            ) + extra(w)
        },
        block = block,
    )

    @Test
    fun `the 51st epic folds into Other epics with exact per-column sums, and the rows add up to the grand total`() = testApplication {
        usePostgresTestcontainer()
        withCrowdedWorld { w ->
            val report = seededClient("ce-fold").cost("${w.range}&teamId=${w.t1}")
            val rows = report.byEpic.rows
            assertEquals(51, rows.size, "50 listed epics and the fold")
            assertEquals(50, rows.count { it.kind == CostEpicRowKind.EPIC })
            val listed = rows.filter { it.kind == CostEpicRowKind.EPIC }.map { it.epicId!! }
            assertEquals((1000L..1049L).toList(), listed, "the tie at 0.3333 goes to the lower id")
            val other = rows.last()
            assertEquals(CostEpicRowKind.OTHER, other.kind)
            assertEquals(listOf(2, null, null), listOf(other.count, other.epicId, other.issueKey), "1050 and 1051 are folded")
            // Alice, Bob: one third each shows 0.33, but the row is the rounded EXACT sum 0.6666 = 0.67 (never 0.33 + 0.33).
            assertEquals(listOf(0.33, 0.33), other.cells)
            assertEquals(0.67, other.totalMd)
            assertEquals(report.totalMd, rows.sumOf { it.totalMd }, 0.005 * rows.size + EPS)
            assertEquals(50.0, report.totalMd)
            assertEquals(report.byEpic.columns.map { it.totalMd }, report.rows.map { it.totalMd })
        }
    }

    @Test
    fun `the epic table follows the level and the slices - UNIT teams, TEAM authors with the no-author column, USER one column`() =
        testApplication {
            usePostgresTestcontainer()
            withAttributionWorld { w ->
                val client = seededClient("ce-levels")
                val unit = client.cost(w.range).byEpic
                assertEquals(setOf(w.t1, w.t2, UNASSIGNED_TEAM), unit.columns.map { it.teamId }.toSet())
                assertEquals(UNASSIGNED_TEAM, unit.columns.last().teamId, "UNASSIGNED last")
                assertTrue(unit.columns.dropLast(1).all { it.label != null && it.accountId == null })
                assertNull(unit.columns.last().label)

                val team = client.cost("${w.range}&teamId=${w.t1}").byEpic
                assertEquals(listOf("Alice", "Bob", null), team.columns.map { it.label }, "authors by name, the no-author bucket last")
                assertEquals(listOf(w.alice, w.bob, null), team.columns.map { it.accountId })
                assertEquals(listOf(w.t1, w.t1, w.t1), team.columns.map { it.teamId }, "every column carries the requested team")
                assertEquals(listOf(3.25, 0.5, 0.5), team.columns.map { it.totalMd })
                assertEquals(
                    mapOf("901" to listOf(1.25, 0.5, 0.0), "902" to listOf(0.0, 0.0, 0.5), "NO_EPIC" to listOf(2.0, 0.0, 0.0)),
                    team.byKey(),
                )

                val user = client.cost("${w.range}&teamId=${w.t1}&accountId=${w.alice}").byEpic
                assertEquals(listOf(w.alice), user.columns.map { it.accountId })
                assertEquals(mapOf("901" to listOf(1.25), "NO_EPIC" to listOf(2.0)), user.byKey())

                val unassigned = client.cost("${w.range}&teamId=0").byEpic
                assertEquals(
                    listOf(listOf(UNASSIGNED_TEAM, w.dave, "Dave", 4.0)),
                    unassigned.columns.map { listOf(it.teamId, it.accountId, it.label, it.totalMd) },
                )
                assertEquals(mapOf("NO_EPIC" to listOf(4.0)), unassigned.byKey())

                // The slices narrow the worklogs the table reads, like the matrix: Testing is Carol's task 105 alone.
                val testing = client.cost("${w.range}&activityType=Testing").byEpic
                assertEquals(listOf(w.t2), testing.columns.map { it.teamId })
                assertEquals(mapOf("902" to listOf(1.0)), testing.byKey())

                // The table is independent of the split and of the capacity block an editor sees.
                val period = client.cost("${w.range}&teamId=${w.t1}")
                assertEquals(period.byEpic, client.cost("${w.range}&teamId=${w.t1}&split=WEEK").byEpic)
                val admin = seededClient("ce-levels-admin", FlowUserRole.ADMIN).cost("${w.range}&teamId=${w.t1}")
                assertEquals(period.byEpic, admin.byEpic)
            }
        }

    // ---- the drill: by=EPIC ----------------------------------------------------------------------------------------------------

    @Test
    fun `the drill lists an epic's items - sub-tasks rolled up, NO_EPIC the epic-less, OTHER_EPICS the fold, each bar equal to its row`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("ce-drill")
            withAttributionWorld { w ->
                val report = client.cost(w.range)
                val checkout = client.items("${w.range}&by=EPIC&key=901")
                // Task 101 with its sub-task's worklog rolled up (1.0 + 0.5), then the epic itself (0.25), logged on directly.
                assertEquals(listOf("101" to 1.5, "EW-E901" to 0.25), checkout.items.map { it.issueKey to it.figure })
                assertEquals(listOf("TASK", "EPIC"), checkout.items.map { it.itemKind.name })
                assertEquals(listOf(2, 1.75), listOf(checkout.bar.count, checkout.bar.sumMd))
                assertEquals(report.byEpic.row(CostEpicRowKind.EPIC, 901).totalMd, checkout.bar.sumMd)
                // No epic: the epic-less task, the issue above the epic level (listed as a TASK) and Dave's task.
                val none = client.items("${w.range}&by=EPIC&key=NO_EPIC")
                assertEquals(setOf("103" to 2.0, "104" to 0.75, "107" to 4.0), none.items.map { it.issueKey to it.figure }.toSet())
                assertTrue(none.items.all { it.itemKind.name == "TASK" })
                assertEquals(report.byEpic.row(CostEpicRowKind.NO_EPIC).totalMd, none.bar.sumMd)
                // Several keys = a union; an unknown epic lists nothing (never 404).
                assertEquals(1.75 + 1.5, client.items("${w.range}&by=EPIC&key=901&key=902").bar.sumMd)
                assertEquals(0, client.items("${w.range}&by=EPIC&key=424242").bar.count)
                // Nothing is folded here, so OTHER_EPICS selects nothing.
                assertEquals(0, client.items("${w.range}&by=EPIC&key=OTHER_EPICS").bar.count)
            }
            withCrowdedWorld { w ->
                val report = client.cost("${w.range}&teamId=${w.t1}")
                val other = client.items("${w.range}&teamId=${w.t1}&by=EPIC&key=OTHER_EPICS")
                assertEquals(setOf("EW-E1050", "EW-E1051"), other.items.map { it.issueKey }.toSet(), "the two folded epics")
                assertEquals(2, other.bar.count)
                assertEquals(0.67, other.bar.sumMd, "the union's rounded exact sum, the row's figure")
                assertEquals(report.byEpic.rows.last().totalMd, other.bar.sumMd)
                // The 50th, listed epic is its own key; 1050 (folded) still drills by its id.
                assertEquals(0.33, client.items("${w.range}&teamId=${w.t1}&by=EPIC&key=1049").bar.sumMd)
                assertEquals(0.33, client.items("${w.range}&teamId=${w.t1}&by=EPIC&key=1050").bar.sumMd)
                // A cell of the fold: Alice's share is epic 1051 alone, ranked over the team (the cell's own column is not the ranking).
                val cell = client.items("${w.range}&teamId=${w.t1}&accountId=${w.alice}&by=EPIC&key=OTHER_EPICS")
                assertEquals(listOf("EW-E1051"), cell.items.map { it.issueKey })
                assertEquals(report.byEpic.rows.last().cells[0], cell.bar.sumMd)
            }
        }

    @Test
    fun `a cell drills through the parent filter - teamId and accountId narrow the epic's items to the column, the bar equals the cell`() =
        testApplication {
            usePostgresTestcontainer()
            withAttributionWorld { w ->
                val client = seededClient("ce-cell")
                val team = client.cost("${w.range}&teamId=${w.t1}").byEpic
                val aliceColumn = team.columns.indexOfFirst { it.accountId == w.alice }
                val bobColumn = team.columns.indexOfFirst { it.accountId == w.bob }
                // Alice's cell of epic 901 (1.25): task 101 (1.0) and the epic (0.25); Bob's (0.5) is the sub-task's, rolled up.
                val alice = client.items("${w.range}&teamId=${w.t1}&accountId=${w.alice}&by=EPIC&key=901")
                assertEquals(listOf("101" to 1.0, "EW-E901" to 0.25), alice.items.map { it.issueKey to it.figure })
                assertEquals(team.row(CostEpicRowKind.EPIC, 901).cells[aliceColumn], alice.bar.sumMd)
                val bob = client.items("${w.range}&teamId=${w.t1}&accountId=${w.bob}&by=EPIC&key=901")
                assertEquals(listOf("101" to 0.5), bob.items.map { it.issueKey to it.figure })
                assertEquals(team.row(CostEpicRowKind.EPIC, 901).cells[bobColumn], bob.bar.sumMd)
                // A UNIT-level cell narrows by the team alone.
                val unit = client.cost(w.range).byEpic
                val carol = client.items("${w.range}&teamId=${w.t2}&by=EPIC&key=NO_EPIC")
                assertEquals(unit.row(CostEpicRowKind.NO_EPIC).cells[unit.columns.indexOfFirst { it.teamId == w.t2 }], carol.bar.sumMd)
                assertEquals(listOf("104"), carol.items.map { it.issueKey })
                // A person with nothing under the epic: a valid empty list.
                assertEquals(0, client.items("${w.range}&teamId=${w.t1}&accountId=${w.alice}&by=EPIC&key=902").bar.count)
            }
        }

    @Test
    fun `the epic drill 400s - a key that is neither an epic id nor a sentinel`() = testApplication {
        usePostgresTestcontainer()
        withAttributionWorld { w ->
            val client = seededClient("ce-400")
            val bad = listOf("abc", "-1", "0", "12x", "%2B5", "9999999999999999999", "no_epic", "Other_Epics", "UNCATEGORIZED")
            for (key in bad) {
                assertEquals(HttpStatusCode.BadRequest, client.status("${w.range}&by=EPIC&key=$key"), "key $key")
            }
            assertEquals(HttpStatusCode.BadRequest, client.status("${w.range}&by=EPIC&key=901&key=abc"), "one bad key spoils the lot")
            assertEquals(HttpStatusCode.BadRequest, client.status("${w.range}&by=EPIC"), "key is required")
            for (key in listOf("901", "NO_EPIC", "OTHER_EPICS")) {
                assertEquals(HttpStatusCode.OK, client.status("${w.range}&by=EPIC&key=$key"), "key $key")
            }
            assertEquals(HttpStatusCode.OK, client.status("${w.range}&by=EPIC&key=901&key=NO_EPIC&key=OTHER_EPICS&key=902"))
            // The parent's own parser errors still come first.
            val noTeam = client.status("${w.range}&accountId=${w.alice}&by=EPIC&key=901")
            assertEquals(HttpStatusCode.BadRequest, noTeam, "accountId needs teamId")
        }
    }

    @Test
    fun `a UNIT read marks a soft-deleted author team's column inactive and its drill is a 400`() = testApplication {
        usePostgresTestcontainer()
        withAttributionWorld { w ->
            val client = seededClient("ce-inactive")
            val gone = TestTeams.seed(SyncedStubFixture.unique("ce-gone"))
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore(sharedDatabaseForTests())
                    .insertFactWorklog(w.connId, listOf(log(60, 108, gone, w.alice, "2026-01-20", 0.5, 902)), configRevision = 1L)
            }
            TestTeams.service.delete(gone)
            val unit = client.cost(w.range)
            val column = unit.byEpic.columns.single { it.teamId == gone }
            assertEquals(listOf(false, 0.5), listOf(column.active, column.totalMd))
            assertTrue(unit.byEpic.columns.filter { it.teamId != gone }.all { it.active == true }, "live teams and UNASSIGNED are active")
            val asMatrix = unit.rows.map { it.teamId to it.active }
            assertEquals(asMatrix, unit.byEpic.columns.map { it.teamId to it.active }, "as the matrix's rows")
            assertTrue(client.cost("${w.range}&teamId=${w.t1}").byEpic.columns.all { it.active == null }, "author columns carry no flag")
            assertEquals(HttpStatusCode.BadRequest, client.status("${w.range}&teamId=$gone&by=EPIC&key=902"), "the deleted team's drill")
        }
    }

    @Test
    fun `an epic with no dim_epic row is still a row, unnamed and drillable`() = testApplication {
        usePostgresTestcontainer()
        withEpicWorld(
            tasks = listOf(task(701, 777)),
            epics = emptyList(),
            logs = { w -> listOf(log(1, 701, w.t1, w.alice, "2026-01-10", 1.5, 777)) },
        ) { w ->
            val client = seededClient("ce-unnamed")
            val row = client.cost(w.range).byEpic.row(CostEpicRowKind.EPIC, 777)
            assertEquals(listOf(null, null, null, 1.5), listOf(row.issueKey, row.summary, row.domain, row.totalMd))
            assertEquals(1.5, client.items("${w.range}&by=EPIC&key=777").bar.sumMd)
        }
    }

    @Test
    fun `the epic table follows a domain slice`() = testApplication {
        usePostgresTestcontainer()
        withAttributionWorld { w ->
            val client = seededClient("ce-domain")
            val other = wl(70, w.t1, w.alice, "2026-01-18", 0.4, "BBB").copy(issueId = 120, epicId = 902)
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsStore(sharedDatabaseForTests()).insertFactWorklog(w.connId, listOf(other), configRevision = 1L)
            }
            val bbb = client.cost("${w.range}&domain=BBB")
            assertEquals(listOf(w.t1), bbb.byEpic.columns.map { it.teamId })
            assertEquals(mapOf("902" to listOf(0.4)), bbb.byEpic.byKey())
            assertEquals(bbb.totalMd, bbb.byEpic.rows.sumOf { it.totalMd }, EPS)
            val aaa = client.cost("${w.range}&domain=AAA")
            assertEquals(aaa.totalMd, aaa.byEpic.rows.sumOf { it.totalMd }, 0.005 * aaa.byEpic.rows.size + EPS)
            val t1 = aaa.byEpic.columns.indexOfFirst { it.teamId == w.t1 }
            assertEquals(0.5, aaa.byEpic.row(CostEpicRowKind.EPIC, 902).cells[t1], "the BBB worklog is not in AAA")
            assertEquals(CostEpicBreakdown(emptyList(), emptyList()), client.cost("${w.range}&domain=NOPE").byEpic)
        }
    }

    private fun closedSprint(id: Long, team: UInt, start: String, complete: String) = DimSprintRow(
        id, null, team, "Sprint $id", "closed", noonUtc(start), noonUtc(complete), noonUtc(complete), null, null,
    ) to FactSprintRow(id, team, noonUtc(complete), SprintTotals(0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0), null, null)

    @Test
    fun `a sprint-relative period reads the matrix's envelope, so the epic rows add up to the matrix total`() = testApplication {
        usePostgresTestcontainer()
        withAttributionWorld { w ->
            val client = seededClient("ce-sprints")
            val store = MetricsStore(sharedDatabaseForTests())
            val (dim1, fact1) = closedSprint(101, w.t1, "2026-01-10", "2026-01-12")
            val (dim2, fact2) = closedSprint(201, w.t2, "2026-01-14", "2026-01-15")
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertDimSprints(w.connId, listOf(dim1, dim2), configRevision = 1L)
                store.insertFactSprint(w.connId, listOf(fact1, fact2), configRevision = 1L)
            }
            try {
                val report = client.cost("connectionId=${w.connId}&lastSprints=1")
                val resolved = setOf(ResolvedSprintGroup(w.t1, listOf(101)), ResolvedSprintGroup(w.t2, listOf(201)))
                assertEquals(resolved, report.meta.resolvedSprints.toSet())
                assertEquals(5.5, report.totalMd, "Jan 10-15: the worklogs of Jan 16-17 fall outside the union envelope")
                assertEquals(report.totalMd, report.byEpic.rows.sumOf { it.totalMd }, EPS)
                assertEquals(listOf(1.75, 1.0, 2.75), report.byEpic.rows.map { it.totalMd }, "901, 902, No epic")
                assertEquals(report.rows.map { it.teamId to it.totalMd }, report.byEpic.columns.map { it.teamId to it.totalMd })
                // The team's own envelope (Jan 10-12) is what its TEAM read and its drill use.
                val team = client.cost("connectionId=${w.connId}&lastSprints=1&teamId=${w.t1}")
                assertEquals(team.totalMd, team.byEpic.rows.sumOf { it.totalMd }, EPS)
                assertEquals(1.75, client.items("connectionId=${w.connId}&lastSprints=1&teamId=${w.t1}&by=EPIC&key=901").bar.sumMd)
            } finally {
                suspendTransaction(sharedDatabaseForTests()) { store.deleteSprintFacts(w.connId) }
            }
        }
    }

    /**
     * The deliberate OTHER_EPICS divergences: the drill ranks over the slice WITHOUT the `accountId` narrowing. T1 logs 49 epics of 1.0 and
     * 1049/1050/1051 of 0.3333; T2 logs 1000 (2.0) and 1051 (5.0). Over T1 alone 1050 and 1051 fold; over the unit 1051 is the largest,
     * so 1049 and 1050 fold.
     */
    @Test
    fun `OTHER_EPICS ranks over the team slice for a USER read, and within the team for a UNIT team cell`() = testApplication {
        usePostgresTestcontainer()
        val carolLogs = { w: EpicWorld ->
            listOf(log(3000, 1000, w.t2, w.carol, "2026-01-12", 2.0, 1000), log(3001, 1051, w.t2, w.carol, "2026-01-12", 5.0, 1051))
        }
        withCrowdedWorld(extra = carolLogs) { w ->
            val client = seededClient("ce-diverge")
            val unit = client.cost(w.range).byEpic
            assertEquals(2, unit.rows.single { it.kind == CostEpicRowKind.OTHER }.count, "over the unit 1049 and 1050 fold")
            // A UNIT (T1 x Other) cell drills with teamId=T1 alone and folds within T1: 1050 and 1051, not the table's 1049 and 1050.
            val cell = client.items("${w.range}&teamId=${w.t1}&by=EPIC&key=OTHER_EPICS")
            assertEquals(setOf("EW-E1050", "EW-E1051"), cell.items.map { it.issueKey }.toSet())
            // A USER read of Bob (two epics, so no Other row of his own) drills OTHER_EPICS over the TEAM's slice: only 1050 is his.
            val bob = client.cost("${w.range}&teamId=${w.t1}&accountId=${w.bob}").byEpic
            assertTrue(bob.rows.none { it.kind == CostEpicRowKind.OTHER }, "two epics: nothing folds in his own table")
            val drill = client.items("${w.range}&teamId=${w.t1}&accountId=${w.bob}&by=EPIC&key=OTHER_EPICS")
            assertEquals(listOf("EW-E1050"), drill.items.map { it.issueKey })
            assertEquals(0.33, drill.bar.sumMd)
        }
    }

    // ---- the derived fixture: an independent read of fact_worklog ----------------------------------------------------------------

    private data class Stored(val epicId: Long?, val team: UInt?, val startedAt: Long, val md: BigDecimal)

    private suspend fun storedWorklogs(connId: UInt, bounds: Pair<Long, Long>): List<Stored> =
        suspendTransaction(sharedDatabaseForTests()) {
            val w = MetricsTables.FactWorklog
            w.selectAll().where { w.connectionId eq connId }.toList()
                .map { Stored(it[w.epicId], it[w.authorTeamId]?.value, it[w.startedAt], it[w.md]) }
                .filter { it.startedAt >= bounds.first && it.startedAt < bounds.second }
        }

    @Test
    fun `fixture - the epic rows equal an independent grouping of the worklogs by epic and author and reconcile to the matrix`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val client = seededClient("ce-fixture")
            val logs = storedWorklogs(connId, windowBounds(reportZone(), "2024-01-01", "2026-12-31"))
            assertTrue(logs.any { it.epicId != null } && logs.any { it.epicId == null }, "worklogs with and without an epic")
            val report = client.cost("connectionId=$connId&from=2024-01-01&to=2026-12-31")
            val epics = report.byEpic
            // Independent grouping: exact MD per epic, the 50 largest (total desc, id asc) listed, the rest folded, per author team.
            val perEpic = logs.filter { it.epicId != null }.groupBy({ it.epicId!! }, { it.md }).mapValues { it.value.exactSum() }
            val ranked = perEpic.entries
                .sortedWith(compareByDescending<Map.Entry<Long, BigDecimal>> { it.value }.thenBy { it.key }).map { it.key }
            val listed = ranked.take(50)
            val folded = ranked.drop(50).toSet()
            assertEquals(listed, epics.rows.filter { it.kind == CostEpicRowKind.EPIC }.map { it.epicId }, "the listed epics in order")
            assertEquals(folded.size, epics.rows.singleOrNull { it.kind == CostEpicRowKind.OTHER }?.count ?: 0)
            assertEquals(logs.any { it.epicId == null }, epics.rows.any { it.kind == CostEpicRowKind.NO_EPIC })
            val teams = epics.columns.map { it.teamId }
            assertEquals(logs.map { it.team ?: UNASSIGNED_TEAM }.toSet(), teams.toSet(), "one column per author team that logged")
            fun List<Stored>.cells() = teams.map { team -> filter { (it.team ?: UNASSIGNED_TEAM) == team }.map { it.md }.exactSum().md() }
            for (row in epics.rows) {
                val own = when (row.kind) {
                    CostEpicRowKind.EPIC -> logs.filter { it.epicId == row.epicId }
                    CostEpicRowKind.OTHER -> logs.filter { it.epicId in folded }
                    CostEpicRowKind.NO_EPIC -> logs.filter { it.epicId == null }
                }
                assertEquals(own.cells(), row.cells, "${row.kind} ${row.epicId}: cells")
                assertEquals(own.map { it.md }.exactSum().md(), row.totalMd, "${row.kind} ${row.epicId}: total")
            }
            // Reconciliation to the matrix: each column is its matrix row; the exact rows are the exact grand total.
            assertEquals(report.rows.map { it.teamId to it.totalMd }, epics.columns.map { it.teamId to it.totalMd })
            assertEquals(report.totalMd, logs.map { it.md }.exactSum().md(), "the matrix total")
            assertEquals(report.totalMd, epics.rows.sumOf { it.totalMd }, 0.005 * epics.rows.size + EPS, "rows add up to the total")
        }

    @Test
    fun `fixture - every epic row's and every TEAM-level cell's drill bar equals its figure`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("ce-fixture-drill")
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val unit = client.cost(query)
        // The busiest live team's TEAM-level table (the stub's authors sit in no team: UNASSIGNED, `teamId=0`): every row's drill, then
        // the cells of its first rows against the person's drill.
        val team = unit.rows.filter { it.active == true }.maxBy { it.totalMd }.teamId!!
        val scoped = "$query&teamId=$team"
        val epics = client.cost(scoped).byEpic
        assertTrue(epics.rows.any { it.kind == CostEpicRowKind.EPIC }, "the busiest team worked on epics")
        for (row in epics.rows) {
            val drill = client.items("$scoped&by=EPIC&key=${row.drillKey()}")
            assertEquals(row.totalMd, drill.bar.sumMd ?: 0.0, "${row.kind} ${row.epicId}: the bar is the row's figure")
            assertEquals(drill.bar.count, drill.total)
        }
        for (row in epics.rows.take(8)) {
            for ((index, column) in epics.columns.withIndex()) {
                val account = column.accountId ?: continue
                val drill = client.items("$scoped&accountId=$account&by=EPIC&key=${row.drillKey()}")
                assertEquals(row.cells[index], drill.bar.sumMd ?: 0.0, "${row.kind} ${row.epicId} x $account: the bar is the cell")
            }
        }
    }
}
