package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.ItemStageRow
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.AGING_MAX_ITEMS
import ch.nokillswit.reports.AgingPositionKind
import ch.nokillswit.reports.AgingWipReport
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.agingWip
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where each aging item sits (`statusId`/`statusName`/`stage`/`position`) and the x-axis `positions` of
 * `GET /api/v1/reports/aging-wip` (`.claude/docs/reports.md` "Report 11"): hand-built rows pin the order in STATUS and COLUMN
 * mode, `(no column)`, an epic and the 500 cap; the fixture's positions are graded against an independent read of
 * `metrics.item_stage` and `norm.board_columns`, which also pins the "exactly one open stage row" invariant.
 */
class ReportAgingWipPositionsTest {

    private suspend fun HttpClient.aging(query: String): AgingWipReport {
        val response = get("/api/v1/reports/aging-wip?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET aging-wip?$query")
        return response.body()
    }

    private fun filter(connId: UInt, teamId: UInt? = null, accountId: String? = null) = ReportFilter(
        period = ReportPeriod.DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 0L, 1L),
        level = when {
            teamId == null -> ReportLevel.UNIT
            accountId == null -> ReportLevel.TEAM
            else -> ReportLevel.USER
        },
        teamId = teamId, accountIds = listOfNotNull(accountId), domainView = DomainView.TASK, domain = null, activityType = null,
        workCategory = null, connectionId = connId,
    )

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    private suspend fun seedStatuses(connId: UInt, statuses: List<Pair<String, String>>) = suspendTransaction(sharedDatabaseForTests()) {
        val st = WorkItemStore.Statuses
        statuses.forEach { (id, name) ->
            st.insert {
                it[connectionId] = connId
                it[statusId] = id
                it[st.name] = name
                it[category] = "IN_PROGRESS"
            }
        }
    }

    private suspend fun cleanUp(connId: UInt, store: MetricsStore) = suspendTransaction(sharedDatabaseForTests()) {
        store.deleteFactTaskDelivery(connId)
        store.deleteFactEpicDelivery(connId)
        store.deleteDims(connId)
        MetricsTables.ItemStage.deleteWhere { MetricsTables.ItemStage.connectionId eq connId }
        WorkItemStore.Statuses.deleteWhere { WorkItemStore.Statuses.connectionId eq connId }
        WorkItemStore.BoardColumns.deleteWhere { WorkItemStore.BoardColumns.connectionId eq connId }
        MetricsConfigService.BoardTeamMap.deleteWhere { MetricsConfigService.BoardTeamMap.connectionId eq connId }
    }

    @Test
    fun `hand-built rows pin each item's status, stage and position, and the x-axis order in STATUS and COLUMN mode`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "aging-pos", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("aging-pos-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("aging-pos-y"))
        val now = noonUtc("2026-01-14")
        // Status 99 sits on no board column; status 5 is only task 11's CLOSED earlier stage.
        seedStatuses(
            connId,
            listOf(
                "10" to "In Dev", "20" to "Review", "30" to "Parked", "40" to "Epic Doing", "50" to "Epic Hold", "99" to "Zeta",
                "5" to "Backlog",
            ),
        )
        fun open(id: Long, startedDay: String, team: UInt = teamX, stage: String = "IN_PROGRESS", account: String? = null) =
            handTask(id, noonUtc(startedDay), null, currentTeamId = team, currentAssignee = account).copy(currentStage = stage)
        val tasks = listOf(
            open(11, "2026-01-13", account = "acc-1"), open(12, "2026-01-12"), open(13, "2026-01-09"),
            open(14, "2026-01-08", stage = "WAITING"), open(15, "2026-01-07"), open(21, "2026-01-02", team = teamY),
        )
        val epics = listOf(31L, 33L).map { handEpic(it, noonUtc("2026-01-12"), null, ownerTeamId = teamX) }
        val dims = listOf(
            DimEpicRow(31, "EP-31", "Doing epic", "AAA", null, "IN_PROGRESS", null, null),
            DimEpicRow(33, "EP-33", "Held epic", "AAA", null, "WAITING", null, null),
        )
        fun current(id: Long, stage: String, statusId: String) = ItemStageRow(id, stage, statusId, noonUtc("2026-01-05"), null)
        val itemStages = listOf(
            ItemStageRow(11, "NOT_STARTED", "5", noonUtc("2026-01-02"), noonUtc("2026-01-05")), current(11, "IN_PROGRESS", "10"),
            current(12, "IN_PROGRESS", "20"), current(13, "IN_PROGRESS", "20"), current(14, "WAITING", "30"),
            current(15, "IN_PROGRESS", "99"), current(21, "IN_PROGRESS", "10"), current(31, "IN_PROGRESS", "40"),
            current(33, "WAITING", "50"),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
            store.replaceFactEpicDelivery(connId, epics, configRevision = 1L)
            store.insertEpics(connId, dims, configRevision = 1L)
            store.insertItemStage(connId, itemStages)
        }
        try {
            // No board for team X yet: STATUS mode, ordered by earliest stage (IN_PROGRESS before WAITING), then name.
            val statusMode = service().agingWip(filter(connId, teamX), now)
            assertEquals(listOf("40", "10", "20", "99", "50", "30"), statusMode.positions.map { it.key })
            assertEquals(listOf("Epic Doing", "In Dev", "Review", "Zeta", "Epic Hold", "Parked"), statusMode.positions.map { it.label })
            assertTrue(statusMode.positions.all { it.kind == AgingPositionKind.STATUS })
            val byKey = statusMode.items.associateBy { it.issueKey }
            assertEquals("10", byKey.getValue("11").statusId, "the open row, not the closed 'Backlog' one")
            assertEquals("In Dev", byKey.getValue("11").statusName)
            assertEquals("10", byKey.getValue("11").position)
            assertEquals("WAITING", byKey.getValue("14").stage)
            assertEquals(true, byKey.getValue("14").waiting)
            assertEquals("Parked", byKey.getValue("14").statusName)
            assertEquals("IN_PROGRESS", byKey.getValue("12").stage)
            assertEquals("40", byKey.getValue("EP-31").position, "an epic follows its own status")
            assertEquals("WAITING", byKey.getValue("EP-33").stage)
            assertTrue(statusMode.items.all { it.stage == (if (it.waiting) "WAITING" else "IN_PROGRESS") }, "stage and waiting agree")
            // UNIT level sees team Y's task too (same status 10, one position); a team with no board stays in STATUS mode.
            assertEquals(statusMode.positions, service().agingWip(filter(connId), now).positions)
            val boardless = service().agingWip(filter(connId, teamY), now)
            assertEquals(listOf("10"), boardless.positions.map { it.key })
            assertEquals(AgingPositionKind.STATUS, boardless.positions.single().kind)

            // Map a board to team X: COLUMN mode in board order, an unused column omitted, (no column) last.
            suspendTransaction(sharedDatabaseForTests()) {
                val c = WorkItemStore.BoardColumns
                listOf("Doing" to """["10","40"]""", "Review" to """["20"]""", "Never used" to """["77"]""", "Hold" to """["30"]""")
                    .forEachIndexed { index, (name, ids) ->
                        c.insert {
                            it[connectionId] = connId
                            it[boardId] = 7L
                            it[seq] = index + 1
                            it[c.name] = name
                            it[statusIds] = ids
                        }
                    }
                MetricsConfigService.BoardTeamMap.insert {
                    it[MetricsConfigService.BoardTeamMap.connectionId] = connId
                    it[boardId] = 7L
                    it[teamId] = teamX
                }
            }
            val columnMode = service().agingWip(filter(connId, teamX), now)
            assertEquals(listOf("Doing", "Review", "Hold", "(no column)"), columnMode.positions.map { it.key })
            assertTrue(columnMode.positions.all { it.kind == AgingPositionKind.COLUMN && it.label == it.key })
            assertEquals(
                mapOf(
                    "11" to "Doing", "12" to "Review", "13" to "Review", "14" to "Hold", "15" to "(no column)", "EP-31" to "Doing",
                    "EP-33" to "(no column)",
                ),
                columnMode.items.associate { it.issueKey to it.position },
            )
            assertEquals("10", columnMode.items.single { it.issueKey == "11" }.statusId, "the status stays on the item in COLUMN mode")
            // USER level keeps the team's board: that person's tasks only, and only their columns.
            val user = service().agingWip(filter(connId, teamX, "acc-1"), now)
            assertEquals(listOf("Doing"), user.positions.map { it.key })
            assertEquals(listOf("Doing"), user.items.map { it.position })
            // UNIT level has no single board: statuses again.
            assertEquals(AgingPositionKind.STATUS, service().agingWip(filter(connId), now).positions.first().kind)
        } finally {
            cleanUp(connId, store)
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    @Test
    fun `the positions are computed over the capped list so the chart and the table agree`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "aging-cap", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val team = TestTeams.seed(SyncedStubFixture.unique("aging-cap"))
        val now = noonUtc("2026-01-14")
        seedStatuses(connId, listOf("1" to "Old status", "2" to "Young status"))
        // 500 tasks of the same age in status 1 fill the list; the youngest (the 501st) sits alone in status 2 and is cut.
        val tasks = (1L..AGING_MAX_ITEMS).map {
            handTask(it, noonUtc("2026-01-02"), null, currentTeamId = team).copy(currentStage = "IN_PROGRESS")
        } + handTask(AGING_MAX_ITEMS + 1L, noonUtc("2026-01-13"), null, currentTeamId = team).copy(currentStage = "IN_PROGRESS")
        val stages = tasks.map { ItemStageRow(it.issueId, "IN_PROGRESS", if (it.issueId <= AGING_MAX_ITEMS) "1" else "2", 1L, null) }
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
            store.insertItemStage(connId, stages)
        }
        try {
            val body = service().agingWip(filter(connId, team), now)
            assertEquals(AGING_MAX_ITEMS, body.items.size)
            assertTrue(body.itemsTruncated)
            assertEquals(listOf("1"), body.positions.map { it.key }, "the cut-off task's status is not an x-axis category")
            assertTrue(body.items.all { it.position == "1" })
        } finally {
            cleanUp(connId, store)
            cleanUpTeams(listOf(team))
        }
    }

    private class FixtureStages(
        val openRows: Map<Long, List<Pair<String, String>>>,
        val idOfKey: Map<String, Long>,
        val names: Map<String, String>,
        val board: List<Pair<String, List<String>>>,
        val teamId: UInt,
    )

    @Test
    fun `the fixture's positions equal an independent computation from item_stage and the board columns`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-aging-positions")
        val data = suspendTransaction(sharedDatabaseForTests()) {
            val s = MetricsTables.ItemStage
            val w = WorkItemStore.WorkItems
            val st = WorkItemStore.Statuses
            val c = WorkItemStore.BoardColumns
            val map = MetricsConfigService.BoardTeamMap
            FixtureStages(
                openRows = s.selectAll().where { (s.connectionId eq connId) and s.validTo.isNull() }.toList()
                    .groupBy { it[s.issueId] }.mapValues { (_, rows) -> rows.map { it[s.statusId] to it[s.stage] } },
                idOfKey = w.selectAll().where { w.connectionId eq connId }.toList().associate { it[w.issueKey] to it[w.issueId] },
                names = st.selectAll().where { st.connectionId eq connId }.toList().associate { it[st.statusId] to it[st.name] },
                board = c.selectAll().where { (c.connectionId eq connId) and (c.boardId eq 1L) }.toList().sortedBy { it[c.seq] }
                    .map { it[c.name] to Json.parseToJsonElement(it[c.statusIds]).jsonArray.map { id -> id.jsonPrimitive.content } },
                teamId = map.selectAll().where { (map.connectionId eq connId) and (map.boardId eq 1L) }.toList().single()[map.teamId].value,
            )
        }
        // The invariant the chart leans on: no issue of the connection has two open stage intervals.
        assertTrue(data.openRows.values.all { it.size == 1 }, "at most one open item_stage row per issue")

        val unit = client.aging("connectionId=$connId")
        assertTrue(unit.items.isNotEmpty())
        for (item in unit.items) {
            val rows = data.openRows[data.idOfKey.getValue(item.issueKey)].orEmpty()
            assertEquals(1, rows.size, "${item.issueKey} has exactly one open item_stage row")
            assertEquals(rows.single().first, item.statusId)
            assertEquals(rows.single().second, item.stage)
            assertEquals(item.waiting, item.stage == "WAITING")
            assertEquals(data.names[item.statusId], item.statusName)
            assertEquals(item.statusId, item.position, "UNIT level plots statuses")
        }
        val rankOf = unit.items.groupBy { it.statusId!! }
            .mapValues { (_, items) -> items.minOf { if (it.stage == "IN_PROGRESS") 0 else 1 } }
        fun nameOf(statusId: String) = data.names[statusId] ?: statusId
        assertEquals(
            rankOf.keys.sortedWith(compareBy<String> { rankOf.getValue(it) }.thenBy { nameOf(it) }.thenBy { it }),
            unit.positions.map { it.key },
        )
        assertTrue(unit.positions.all { it.kind == AgingPositionKind.STATUS && it.label == nameOf(it.key) })

        val team = client.aging("connectionId=$connId&teamId=${data.teamId}")
        assertTrue(team.items.isNotEmpty(), "the mapped team must have work in progress")
        val columnOf = data.board.flatMap { (name, ids) -> ids.map { it to name } }.toMap()
        for (item in team.items) assertEquals(columnOf[item.statusId] ?: "(no column)", item.position, item.issueKey)
        val used = team.items.map { it.position }.toSet()
        val expected = data.board.map { it.first }.distinct().filter { it in used } + listOfNotNull("(no column)".takeIf { it in used })
        assertEquals(expected, team.positions.map { it.key })
        assertTrue(team.positions.all { it.kind == AgingPositionKind.COLUMN })
    }
}
