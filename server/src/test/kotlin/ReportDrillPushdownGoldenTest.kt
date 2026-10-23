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
    val epics = (1..EPICS).map { EPIC_BASE + it }
    val sprintQuery get() = "connectionId=$connId&teamId=$team&sprintId=$sprintId"
    val periodQuery get() = "connectionId=$connId&from=2026-01-01&to=2026-01-31"

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
    val authors = List(7) { w.alice } + List(2) { w.bob } + List(2) { w.cora }
    val figures = listOf(0.5, 0.5, 1.0, 0.25, 0.3333, 0.3333, 0.3334)
    val lates = listOf(null, 0L, MILLIS_PER_DAY - 1, MILLIS_PER_DAY, MILLIS_PER_DAY + 1, 3 * MILLIS_PER_DAY)
    var next = 0L
    fun log(issue: Long, activity: String, epic: Long?, n: Int, at: Long = day("2026-01-%02d".format(5 + n % 6))): FactWorklogRow {
        next++
        return FactWorklogRow(
            worklogId = next, issueId = issue, authorAccountId = if (n % 17 == 16) null else authors[n % authors.size], authorTeamId = team,
            startedAt = at, createdAt = null, lateMs = lates[n % lates.size], md = figures[n % figures.size], taskDomainKey = "AAA",
            epicId = epic, epicDomainKey = "AAA", activityType = activity, workCategory = if (n % 5 == 0) "Run" else null,
            sprintIdAtStarted = null, sprintTeamIdAtStarted = null, foreignWork = n % 4 == 0, assigneeAccountIdAtStarted = null,
            assigneeTeamIdAtStarted = null,
        )
    }
    var n = 0
    val logs = ArrayList<FactWorklogRow>()
    tasks.forEach { t -> repeat(1 + (n % 3)) { logs += log(t.issueId, t.activityType, t.epicId, n++) } }
    subtasks.forEach { t -> repeat(1 + (n % 2)) { logs += log(t.issueId, t.activityType, t.epicId, n++) } }
    w.epics.forEach { e -> logs += log(e, "Story", e, n++) }
    (1..5).forEach { logs += log(30_000L + it, "Story", null, n++) }
    // The sprint is Jan 5 00:00Z .. Jan 11 00:00Z: one worklog AT its start counts, one AT its end and one after do not.
    logs += log(10_001L, "Story", w.epics[0], n++, at = utc("2026-01-05T00:00:00Z"))
    logs += log(10_002L, "Story", w.epics[1], n++, at = utc("2026-01-11T00:00:00Z"))
    logs += log(10_003L, "Story", w.epics[2], n++, at = day("2026-01-20"))
    val store = MetricsStore(sharedDatabaseForTests())
    val end = utc("2026-01-11T00:00:00Z")
    val sprint = DimSprintRow(w.sprintId, w.sprintId, team, "Golden sprint", "closed", utc("2026-01-05T00:00:00Z"), end, end, null, null)
    suspendTransaction(sharedDatabaseForTests()) {
        store.insertTasks(connId, tasks + subtasks, configRevision = 1L)
        store.insertEpics(connId, epicRows, configRevision = 1L)
        store.insertFactWorklog(connId, logs, configRevision = 1L)
        store.insertDimSprints(connId, listOf(sprint), configRevision = 1L)
        val fact = FactSprintRow(w.sprintId, team, sprint.completeAt, sprintTotals(emptyList()), null, load = null)
        store.insertFactSprint(connId, listOf(fact), 1L)
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
            val everything = client.costsGolden("${w.periodQuery}&by=ACTIVITY_TYPE&key=Story&key=Bug")
            assertTrue(everything.truncated && everything.items.size == PAGE && everything.total > PAGE, "the cut was exercised")
            assertEquals(everything.total, everything.bar.count)
            // The ties across the cut: figures are drawn from a handful of values, so the 500th and 501st item share one.
            assertTrue(everything.items.distinctBy { it.figure to it.anchorAt }.size < everything.items.size, "ties in figure and anchor")
            assertTrue(everything.items.any { it.itemKind == DrillItemKind.EPIC }, "an epic logged on directly")
            client.costsGolden("${w.periodQuery}&by=ACTIVITY_TYPE&key=Story")
            client.costsGolden("${w.periodQuery}&by=ACTIVITY_TYPE&key=Bug")
            client.costsGolden("${w.periodQuery}&by=ACTIVITY_TYPE&key=Nothing")
            client.costsGolden("${w.periodQuery}&by=WORK_CATEGORY&key=Run")
            client.costsGolden("${w.periodQuery}&by=WORK_CATEGORY&key=UNCATEGORIZED")
            client.costsGolden("${w.periodQuery}&by=WORK_CATEGORY&key=UNCATEGORIZED&key=Run")
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
