package ch.nokillswit

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.sprintTotals
import ch.nokillswit.norm.PersonRef
import ch.nokillswit.reports.CompareDrillView
import ch.nokillswit.reports.CostsReport
import ch.nokillswit.reports.DrillItemKind
import ch.nokillswit.reports.ReportDrillItems
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

private const val PAGE = 500

/**
 * The hand-built CROWDED world of the pushdown golden tests: one disabled connection, a team T of three named members (alice, bob, cora)
 * and nine epics. 520 tasks (+ 40 sub-tasks rolled up into earlier ones, + nine worklogs logged ON the epics and five on issues above the
 * epic level) make 534 items in the sprint, and alice alone logs more than 500 worklogs. The figures are drawn from a tiny set (0.5, 1,
 * 0.25, 0.3333, 0.3334 MD; six days) so figures and anchors tie ACROSS the 500th place, and a few worklogs share an exact instant. Every
 * 17th worklog has no author, every 4th is foreign, the creation delays walk 0 / 1 day - 1 ms / 1 day / 1 day + 1 ms / 3 days / unknown.
 */
private class CrowdedWorld(val connId: UInt, val team: UInt, val alice: String, val bob: String, val cora: String) {
    val sprintId = 880_001L
    val openSprintId = 880_002L
    val noStartSprintId = 880_003L
    val epics = (1..EPICS).map { EPIC_BASE + it }
    val sprintQuery get() = sprintQuery(sprintId)
    fun sprintQuery(id: Long) = "connectionId=$connId&teamId=$team&sprintId=$id"
    val periodQuery get() = "connectionId=$connId&from=2026-01-01&to=2026-01-31"

    /** The UNIT-level read of the world: a `domain` with no `teamId` (every row of the world is domain AAA). */
    val unitQuery get() = "$periodQuery&domain=AAA"

    companion object {
        const val EPIC_BASE = 880_100L
        const val EPICS = 9
        const val TASKS = 520
        const val SUBTASKS = 40
    }
}

private fun day(iso: String): Long = noonUtc(iso)

@Suppress("LongMethod") // a data table: one world, built in one place
private suspend fun withCrowdedWorld(block: suspend (CrowdedWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "pushdown-golden", enabled = false)
    val team = TestTeams.seed(SyncedStubFixture.unique("pdg-team"))
    val tag = SyncedStubFixture.unique("pdg")
    val w = CrowdedWorld(connId, team, "$tag-alice", "$tag-bob", "$tag-cora")
    // Display names that disagree with the account ids' order, so the authors line's name tie-break is exercised.
    SyncedStubFixture.workItems().replacePeople(
        connId,
        listOf(
            PersonRef(w.alice, "Zoe Alice", null, active = true),
            PersonRef(w.bob, "Ann Bob", null, active = true),
            PersonRef(w.cora, "Mia Cora", null, active = true),
        ),
    )
    val epicRows = w.epics.mapIndexed { i, id -> DimEpicRow(id, "PDG-E${i + 1}", "Epic ${i + 1}", "AAA", null, "IN_PROGRESS", null, null) }
    val tasks = (0 until CrowdedWorld.TASKS).map { i ->
        val epic = if (i % 10 == 9) null else w.epics[i % CrowdedWorld.EPICS]
        DimTaskRow(10_001L + i, "PDG-${10_001 + i}", "Story", if (i % 7 == 0) "Bug" else "Story", null, "NONE", false, null, "AAA", epic)
    }
    val subtasks = (0 until CrowdedWorld.SUBTASKS).map { k ->
        val parent = tasks[k * 7]
        val key = "PDG-${20_001 + k}"
        DimTaskRow(20_001L + k, key, "Sub-task", parent.activityType, null, "NONE", true, parent.issueId, "AAA", parent.epicId)
    }
    // Sub-tasks the roll-up has no parent for: one with a NULL parent_task_id (it lists itself), one whose parent has no dim_task row
    // (it lists the parent's id, unlabelled).
    val orphans = listOf(
        DimTaskRow(20_901L, "PDG-20901", "Sub-task", "Story", null, "NONE", true, null, "AAA", w.epics[1]),
        DimTaskRow(20_902L, "PDG-20902", "Sub-task", "Story", null, "NONE", true, 50_999L, "AAA", w.epics[2]),
    )
    // The rounding boundary: an item (or a bar) summing to exactly x.xx5 next to one at x.xx49 -- numeric ROUND and BigDecimal HALF_UP
    // must agree on both the figure and the ORDER BY. Activity types Edge / EdgeBar / EdgeBar2 make them slices of their own.
    val edge = listOf(
        Triple(40_001L, "Edge", listOf(0.125)), Triple(40_002L, "Edge", listOf(0.1249)), Triple(40_003L, "Edge", listOf(0.0025, 0.0025)),
        Triple(40_004L, "Edge", listOf(0.0049)), Triple(40_005L, "Edge", listOf(2.675)), Triple(40_006L, "Edge", listOf(2.6749)),
        Triple(40_011L, "EdgeBar", listOf(0.0025)), Triple(40_012L, "EdgeBar", listOf(0.0025)),
        Triple(40_021L, "EdgeBar2", listOf(1.0)), Triple(40_022L, "EdgeBar2", listOf(0.125)),
    )
    val edgeTasks = edge.map { (id, kind, _) -> DimTaskRow(id, "PDG-$id", "Story", kind, null, "NONE", false, null, "AAA", w.epics[0]) }
    val authors = List(7) { w.alice } + List(2) { w.bob } + List(2) { w.cora }
    val figures = listOf(0.5, 0.5, 1.0, 0.25, 0.3333, 0.3333, 0.3334)
    val lates = listOf(null, 0L, MILLIS_PER_DAY - 1, MILLIS_PER_DAY, MILLIS_PER_DAY + 1, 3 * MILLIS_PER_DAY)
    var next = 0L
    fun log(
        issue: Long,
        activity: String,
        epic: Long?,
        n: Int,
        at: Long = day("2026-01-%02d".format(5 + n % 6)),
        md: Double = figures[n % figures.size],
    ): FactWorklogRow {
        next++
        return FactWorklogRow(
            worklogId = next, issueId = issue, authorAccountId = if (n % 17 == 16) null else authors[n % authors.size], authorTeamId = team,
            startedAt = at, createdAt = null, lateMs = lates[n % lates.size], md = md, taskDomainKey = "AAA",
            epicId = epic, epicDomainKey = "AAA", activityType = activity, workCategory = if (n % 5 == 0) "Run" else null,
            sprintIdAtStarted = null, sprintTeamIdAtStarted = null, foreignWork = n % 4 == 0, assigneeAccountIdAtStarted = null,
            assigneeTeamIdAtStarted = null,
        )
    }
    var n = 0
    val logs = ArrayList<FactWorklogRow>()
    tasks.forEach { t -> repeat(1 + (n % 3)) { logs += log(t.issueId, t.activityType, t.epicId, n++) } }
    subtasks.forEach { t -> repeat(1 + (n % 2)) { logs += log(t.issueId, t.activityType, t.epicId, n++) } }
    // Big figures, so both sit on the first page.
    orphans.forEach { t -> repeat(2) { logs += log(t.issueId, t.activityType, t.epicId, n++, md = 9.0) } }
    edge.forEach { (id, activity, mds) -> mds.forEach { logs += log(id, activity, w.epics[0], n++, at = day("2026-01-06"), md = it) } }
    w.epics.forEach { e -> logs += log(e, "Story", e, n++) }
    (1..5).forEach { logs += log(30_000L + it, "Story", null, n++) }
    // The sprint is Jan 5 00:00Z .. Jan 11 00:00Z: one worklog AT its start counts, one AT its end and one after do not
    // (the last two sit on issues of their own, so only the open sprint lists them).
    logs += log(10_001L, "Story", w.epics[0], n++, at = utc("2026-01-05T00:00:00Z"))
    logs += log(30_101L, "Story", null, n++, at = utc("2026-01-11T00:00:00Z"))
    logs += log(30_102L, "Story", null, n++, at = day("2026-01-20"))
    val store = MetricsStore(sharedDatabaseForTests())
    val end = utc("2026-01-11T00:00:00Z")
    val start = utc("2026-01-05T00:00:00Z")
    val sprint = DimSprintRow(w.sprintId, w.sprintId, team, "Golden sprint", "closed", start, end, end, null, null)
    // An OPEN sprint (no completion: its window runs to now; every worklog sits in January, so the oracle's and the server's clocks agree)
    // and a sprint with no start (an empty window).
    val open = DimSprintRow(w.openSprintId, w.openSprintId, team, "Open sprint", "active", start, null, null, null, null)
    val noStart = DimSprintRow(w.noStartSprintId, w.noStartSprintId, team, "Unstarted sprint", "closed", null, end, end, null, null)
    val sprints = listOf(sprint, open, noStart)
    suspendTransaction(sharedDatabaseForTests()) {
        store.insertTasks(connId, tasks + subtasks + orphans + edgeTasks, configRevision = 1L)
        store.insertEpics(connId, epicRows, configRevision = 1L)
        store.insertFactWorklog(connId, logs, configRevision = 1L)
        store.insertDimSprints(connId, sprints, configRevision = 1L)
        val facts = sprints.map { FactSprintRow(it.sprintId, team, it.completeAt, sprintTotals(emptyList()), null, load = null) }
        store.insertFactSprint(connId, facts, 1L)
    }
    try {
        block(w)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteSprintFacts(connId)
            store.deleteFactWorklog(connId)
            store.deleteDims(connId)
        }
        cleanUpTeams(listOf(team))
    }
}

/**
 * The set-based cost and compare-people worklog drills against the pre-pushdown JVM implementation (`DrillPushdownOracle.kt`), on a
 * world with more than 500 items, ties across the cut and every drill shape: the whole `ReportDrillItems` -- meta, bar, items in order,
 * total, truncated -- must be equal, and its JSON identical. The same comparison runs on the derived stub fixture in
 * `ReportCostsSprintsFixtureTest` and `ReportComparePeopleFixtureTest`.
 */
class ReportDrillPushdownGoldenTest {

    private suspend fun HttpClient.costsGolden(query: String): ReportDrillItems = assertCostsDrillGolden(query)

    private suspend fun HttpClient.compareGolden(world: CrowdedWorld, view: CompareDrillView, person: String): ReportDrillItems =
        assertCompareWorklogDrillGolden(
            "connectionId=${world.connId}&from=2026-01-01&to=2026-01-31&accountId=${world.alice}&accountId=${world.bob}", view, person,
        )

    @Test
    fun `the cost slice drills equal the JVM implementation over 500 items with ties`() = testApplication {
        usePostgresTestcontainer()
        withCrowdedWorld { w ->
            val client = seededClient("pdg-slice")
            val everything = client.costsGolden("${w.unitQuery}&by=ACTIVITY_TYPE&key=Story&key=Bug")
            assertTrue(everything.truncated && everything.items.size == PAGE && everything.total > PAGE, "the cut was exercised")
            assertEquals(everything.total, everything.bar.count)
            // The ties across the cut: figures are drawn from a handful of values, so the 500th and 501st item share one.
            assertTrue(everything.items.distinctBy { it.figure to it.anchorAt }.size < everything.items.size, "ties in figure and anchor")
            assertTrue(everything.items.any { it.itemKind == DrillItemKind.EPIC }, "an epic logged on directly")
            client.costsGolden("${w.unitQuery}&by=ACTIVITY_TYPE&key=Story")
            client.costsGolden("${w.unitQuery}&by=ACTIVITY_TYPE&key=Bug")
            client.costsGolden("${w.unitQuery}&by=ACTIVITY_TYPE&key=Nothing")
            client.costsGolden("${w.unitQuery}&by=WORK_CATEGORY&key=Run")
            client.costsGolden("${w.unitQuery}&by=WORK_CATEGORY&key=UNCATEGORIZED")
            client.costsGolden("${w.unitQuery}&by=WORK_CATEGORY&key=UNCATEGORIZED&key=Run")
            client.costsGolden("${w.periodQuery}&teamId=${w.team}&by=ACTIVITY_TYPE&key=Story")
            client.costsGolden("${w.periodQuery}&teamId=${w.team}&accountId=${w.alice}&by=ACTIVITY_TYPE&key=Story&key=Bug")
            client.costsGolden("${w.periodQuery}&teamId=0&by=ACTIVITY_TYPE&key=Story")
        }
    }

    @Test
    fun `the cost sprint-bar drills equal the JVM implementation for the whole bar and every epic segment`() = testApplication {
        usePostgresTestcontainer()
        withCrowdedWorld { w ->
            val client = seededClient("pdg-sprint")
            val chart = client.get("/api/v1/reports/costs?${w.sprintQuery}").body<CostsReport>().sprints.single()
            val whole = client.costsGolden(w.sprintQuery)
            assertTrue(whole.truncated && whole.items.size == PAGE && whole.total > PAGE, "the cut was exercised")
            assertEquals(chart.acMd, whole.bar.sumMd, "the whole bar is the chart's AC")
            val tops = chart.byEpic.mapNotNull { it.epicId }
            assertEquals(6, tops.size)
            var summed = 0.0
            for (segment in chart.byEpic) {
                val topIds = if (segment.key == "OTHER_EPICS") tops.joinToString("") { "&topEpicId=$it" } else ""
                val narrow = "by=EPIC&epicSegment=${segment.key}$topIds"
                val drill = client.costsGolden("${w.sprintQuery}&$narrow")
                assertEquals(segment.acMd, drill.bar.sumMd, "segment ${segment.key} is the chart's")
                summed += drill.bar.sumMd!!
            }
            assertEquals(chart.acMd, summed, 0.005 * chart.byEpic.size, "the segments add up to the bar")
            // Epics the chart folded or not shown at all, and the no-epic segment, whatever the chart drew.
            for (epic in w.epics) client.costsGolden("${w.sprintQuery}&by=EPIC&epicSegment=$epic")
            client.costsGolden("${w.sprintQuery}&by=EPIC&epicSegment=NO_EPIC")
            client.costsGolden("${w.sprintQuery}&by=EPIC&epicSegment=OTHER_EPICS&topEpicId=${w.epics[0]}")
            client.costsGolden("${w.sprintQuery}&by=EPIC&epicSegment=OTHER_EPICS" + w.epics.take(6).joinToString("") { "&topEpicId=$it" })
            client.costsGolden("${w.sprintQuery}&by=EPIC&epicSegment=OTHER_EPICS&topEpicId=1")
            client.costsGolden("${w.sprintQuery}&accountId=${w.bob}")
            client.costsGolden("${w.sprintQuery}&activityType=Bug")
        }
    }

    @Test
    fun `rounding boundaries, orphan sub-tasks, an open sprint and an unstarted one equal the JVM implementation`() =
        testApplication {
            usePostgresTestcontainer()
            withCrowdedWorld { w ->
                val client = seededClient("pdg-edge")
                // x.xx5 rounds up (HALF_UP), x.xx49 down, in an item's figure and in a bar: 0.125 -> 0.13 / 0.1249 -> 0.12, 0.0025 + 0.0025
                // on one item -> 0.01 / 0.0049 -> 0.0, 2.675 -> 2.68 / 2.6749 -> 2.67; a BAR of 0.0025 + 0.0025 and a bar of 1 + 0.125.
                val edge = client.costsGolden("${w.unitQuery}&by=ACTIVITY_TYPE&key=Edge")
                assertEquals(setOf(0.13, 0.12, 0.01, 0.0, 2.68, 2.67), edge.items.map { it.figure }.toSet())
                assertEquals(6, edge.bar.count)
                assertEquals(0.01, client.costsGolden("${w.unitQuery}&by=ACTIVITY_TYPE&key=EdgeBar").bar.sumMd)
                assertEquals(1.13, client.costsGolden("${w.unitQuery}&by=ACTIVITY_TYPE&key=EdgeBar2").bar.sumMd)
                client.costsGolden("${w.unitQuery}&by=ACTIVITY_TYPE&key=Edge&key=EdgeBar&key=EdgeBar2")
                // The orphan sub-tasks: one with no parent lists itself, one whose parent has no row lists the parent's id.
                val whole = client.costsGolden(w.sprintQuery)
                assertTrue(whole.items.any { it.issueKey == "20901" }, "a sub-task without a parent lists itself")
                assertTrue(whole.items.any { it.issueKey == "50999" }, "a sub-task whose parent has no row lists the parent's id")
                // The open sprint runs to now (all worklogs are in January: it holds every one from its start); the unstarted one is empty.
                val open = client.costsGolden(w.sprintQuery(w.openSprintId))
                assertTrue(open.total > whole.total, "the open sprint also holds the January 11..31 worklogs")
                client.costsGolden("${w.sprintQuery(w.openSprintId)}&by=EPIC&epicSegment=${w.epics[0]}")
                client.costsGolden("${w.sprintQuery(w.openSprintId)}&by=EPIC&epicSegment=NO_EPIC")
                val unstarted = client.costsGolden(w.sprintQuery(w.noStartSprintId))
                assertEquals(0, unstarted.total)
                assertEquals(0.0, unstarted.bar.sumMd)
            }
        }

    @Test
    fun `the compare-people worklog drills equal the JVM implementation over 500 worklogs with ties`() = testApplication {
        usePostgresTestcontainer()
        withCrowdedWorld { w ->
            val client = seededClient("pdg-compare")
            for (person in listOf(w.alice, w.bob)) {
                for (view in listOf(CompareDrillView.LOGGED, CompareDrillView.FOREIGN, CompareDrillView.LATE)) {
                    val drill = client.compareGolden(w, view, person)
                    assertEquals(drill.total, drill.bar.count)
                    assertEquals(drill.total > PAGE, drill.truncated)
                    assertTrue(drill.items.all { it.itemKind == DrillItemKind.WORKLOG })
                }
            }
            val alice = client.compareGolden(w, CompareDrillView.LOGGED, w.alice)
            assertTrue(alice.truncated && alice.items.size == PAGE, "alice logs more than 500 worklogs (total ${alice.total})")
            // LATE is strictly over a day: the exactly-one-day worklogs and the unknown delays are out.
            val late = client.compareGolden(w, CompareDrillView.LATE, w.alice)
            assertTrue(late.total > 0 && late.total < alice.total)
        }
    }
}
