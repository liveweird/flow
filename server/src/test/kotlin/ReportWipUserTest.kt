package ch.nokillswit

import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.ItemStageRow
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TaskAssigneeRow
import ch.nokillswit.metrics.teamTaskWipSql
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.BoardColumnRef
import ch.nokillswit.norm.BoardRef
import ch.nokillswit.norm.StatusCategory
import ch.nokillswit.norm.StatusRef
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.WipReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val STAGES = listOf("NOT_STARTED", "IN_PROGRESS", "WAITING", "DONE", "UNMAPPED")

private fun accountParams(accounts: List<String>): String = accounts.joinToString("&") { "accountId=" + URLEncoder.encode(it, "UTF-8") }

/**
 * `GET /api/v1/reports/wip` at USER level (brief 04, `.claude/docs/reports.md` "Reports 9, 10, 13"): the picked people's WIP
 * computed at query time, inside the page's team (`taskFactSlice`'s rule), with the DONE band attributed as at done. The stub
 * fixture grades the INVARIANT against the aggregate with independent SQL (`ReportSnapshotTestSupport.kt`); the keys, the team
 * rule, the day-end boundary, the item kinds and the request validation are pinned on hand-built rows in a fresh DISABLED
 * connection with hand-computed answers.
 */
class ReportWipUserTest {

    private suspend fun HttpClient.wip(query: String): WipReport {
        val response = get("/api/v1/reports/wip?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET wip?$query")
        return response.body()
    }

    private fun WipReport.counts(key: String) = series.map { it.counts.getValue(key) }

    private fun WipReport.userCounts(accountId: String, key: String) =
        series.map { point -> point.byUser.single { it.accountId == accountId }.counts.getValue(key) }

    @Test
    fun `per-person counts plus the tasks nobody holds equal the TEAM aggregate for every day and stage`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-wip-user-invariant")
        val from = "2025-06-01"
        val to = "2026-03-05"
        val days0 = isoDays(from, to)
        val aggregate = readWipRowsWithDone(connId).filter { it.scopeKind == "TEAM" && it.itemKind == "TASK" && it.day in days0 }
        val accounts = readAssigneeAccounts(connId)
        assertTrue(accounts.size >= 2, "the fixture must have several assignees")
        val fixtureTeam = aggregate.map { it.scopeId }.filter { it.all(Char::isDigit) }.distinct().single()

        for ((team, scopeId) in listOf(fixtureTeam to fixtureTeam, "0" to "UNASSIGNED")) {
            val expected = aggregate.filter { it.scopeId == scopeId }.groupBy { it.day to it.stage }
                .mapValues { (_, g) -> g.sumOf { it.count } }
            val held = HashMap<Pair<String, String>, Int>()
            var days = emptyList<String>()
            for (chunk in accounts.chunked(8)) {
                val body = client.wip("connectionId=$connId&from=$from&to=$to&teamId=$team&${accountParams(chunk)}")
                assertEquals("USER", body.meta.level.name)
                days = body.series.map { it.day }
                for (point in body.series) {
                    assertEquals(chunk, point.byUser.map { it.accountId }, "byUser lists the picks in request order")
                    for (stage in STAGES) {
                        val shares = point.byUser.sumOf { it.counts.getValue(stage) }
                        assertEquals(point.counts.getValue(stage), shares, "byUser sums to counts")
                        held.merge(point.day to stage, point.counts.getValue(stage), Int::plus)
                    }
                }
            }
            assertTrue(days.isNotEmpty())
            val nobody = independentUnassignedWip(connId, team.toUInt(), days.first(), days.last())
            for (day in days) {
                for (stage in STAGES) {
                    val key = day to stage
                    val total = (held[key] ?: 0) + (nobody[key] ?: 0)
                    assertEquals(expected[key] ?: 0, total, "team $team $day $stage: held + unheld = aggregate")
                }
            }
            if (team != "0") {
                assertTrue(held.filterKeys { it.second == "IN_PROGRESS" }.values.any { it > 0 }, "the team's people hold work in progress")
                assertTrue(held.filterKeys { it.second == "DONE" }.values.any { it > 0 }, "the team's people finished work")
            }
        }
    }

    /**
     * Hand-built rows (fresh DISABLED connection, Mon 2026-01-05 .. Sun 2026-01-11, transitions at noon UTC unless noted). Teams X and Y;
     * a1, a2 are in X, a3 in Y, a4 in no team; board "Doing" holds `prog`, "To do" holds `todo`; sprint 900 (open) belongs to Y.
     *
     * | task | what it is | counts for |
     * |---|---|---|
     * | 1 | a1 the whole time, `prog` | a1 / X, every day |
     * | 2 | a1 until the END of the 7th, a2 after; `todo` until the 9th noon, then `prog` | a1 on 5..7, a2 on 8..11 (boundary: a1) |
     * | 3 | a sub-task of a1, `prog` | nobody |
     * | 5 | a3, `prog` | a3 / Y |
     * | 6 | a1, `prog`, in sprint 900 (team Y) | a1 / Y (the sprint wins over a1's own team X) |
     * | 7 | done on the 5th, credit X, assignee at done a2 | a2 / X, DONE 5..11 |
     * | 9 | an unassigned interval (null account), `prog` | nobody |
     * | 10 | a4, `prog` | a4 / teamless |
     * | 12 | done on the 5th, credit Y, assignee at done a1 | a1 / Y, DONE 5..11 |
     * | epic 20 | `prog` | nobody (an epic has no user) |
     */
    @Test
    fun `hand-built rows pin the boundary, the team rule, the item kinds, the keys and byUser`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-user-hand", enabled = false, backfillFrom = EARLY_BACKFILL)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val store = MetricsStore(sharedDatabaseForTests())
        val workItems = SyncedStubFixture.workItems()
        val teamX = TestTeams.seed(SyncedStubFixture.unique("wipu-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("wipu-y"))
        val a1 = SyncedStubFixture.unique("acc-a1")
        val a2 = SyncedStubFixture.unique("acc-a2")
        val a3 = SyncedStubFixture.unique("acc-a3")
        val a4 = SyncedStubFixture.unique("acc-a4")
        val created = noonUtc("2026-01-01")
        fun at(day: String) = noonUtc(day)
        val dayEnd7 = suspendTransaction(sharedDatabaseForTests()) {
            val d = MetricsTables.DimDate
            d.selectAll().where { d.day eq "2026-01-07" }.toList().single()[d.dayEndMs]
        }
        fun stage(issue: Long, stage: String, status: String, from: Long?, to: Long?) =
            ItemStageRow(issue, stage, status, from ?: created, to)
        fun task(id: Long, subtask: Boolean = false) = DimTaskRow(id, "HU-$id", "Story", "Story", null, "NONE", subtask, null, "AAA", null)
        val stages = listOf(
            stage(1, "IN_PROGRESS", "prog", null, null),
            stage(2, "NOT_STARTED", "todo", null, at("2026-01-09")), stage(2, "IN_PROGRESS", "prog", at("2026-01-09"), null),
            stage(3, "IN_PROGRESS", "prog", null, null), stage(5, "IN_PROGRESS", "prog", null, null),
            stage(6, "IN_PROGRESS", "prog", null, null),
            stage(7, "IN_PROGRESS", "prog", null, at("2026-01-05")), stage(7, "DONE", "done", at("2026-01-05"), null),
            stage(9, "IN_PROGRESS", "prog", null, null), stage(10, "IN_PROGRESS", "prog", null, null),
            stage(12, "IN_PROGRESS", "prog", null, at("2026-01-05")), stage(12, "DONE", "done", at("2026-01-05"), null),
            stage(20, "IN_PROGRESS", "prog", null, null),
        )
        val assignees = listOf(
            TaskAssigneeRow(1, a1, created, null), TaskAssigneeRow(2, a1, created, dayEnd7), TaskAssigneeRow(2, a2, dayEnd7, null),
            TaskAssigneeRow(3, a1, created, null), TaskAssigneeRow(5, a3, created, null), TaskAssigneeRow(6, a1, created, null),
            TaskAssigneeRow(7, a2, created, null), TaskAssigneeRow(9, null, created, null), TaskAssigneeRow(10, a4, created, null),
            TaskAssigneeRow(12, a1, created, null),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(connId, listOf(DimDomainRow("AAA", "Alpha", listOf("AA"), teamX)), 1L)
            store.insertTasks(connId, listOf(1L, 2, 5, 6, 7, 9, 10, 12).map { task(it) } + task(3, subtask = true), 1L)
            store.insertEpics(connId, listOf(DimEpicRow(20, "EP-20", null, "AAA", null, "IN_PROGRESS", null, null)), 1L)
            store.insertItemStage(connId, stages)
            store.insertTaskAssignee(connId, assignees)
            store.insertFactTaskDelivery(
                connId,
                listOf(
                    handTask(7, doneAt = at("2026-01-05"), creditTeamId = teamX, account = a2),
                    handTask(12, doneAt = at("2026-01-05"), creditTeamId = teamY, account = a1),
                ),
                1L,
            )
            val sprint = DimSprintRow(900, null, teamY, "Y sprint", "active", created, null, null, null, null)
            store.insertDimSprints(connId, listOf(sprint), 1L)
            WorkItemStore.FieldIntervals.insert {
                it[WorkItemStore.FieldIntervals.connectionId] = connId
                it[issueId] = 6
                it[field] = "SPRINT"
                it[seq] = 0
                it[valueId] = "900"
                it[fromAt] = created
                it[toAt] = null
            }
            for ((account, team) in listOf(a1 to teamX, a2 to teamX, a3 to teamY)) {
                TeamMembershipService.TeamMembership.insert {
                    it[accountId] = account
                    it[teamId] = team
                    it[validFrom] = created
                    it[validTo] = null
                    it[createdAt] = 1L
                    it[updatedAt] = 1L
                }
            }
            workItems.replaceStatuses(
                connId,
                listOf(
                    StatusRef("todo", "Open", StatusCategory.TODO), StatusRef("prog", "Dev", StatusCategory.IN_PROGRESS),
                    StatusRef("done", "Done", StatusCategory.DONE),
                ),
            )
            workItems.replaceBoards(
                connId,
                listOf(
                    BoardRef(
                        77L, "X board", "scrum", null,
                        listOf(BoardColumnRef("To do", listOf("todo")), BoardColumnRef("Doing", listOf("prog"))),
                    ),
                ),
            )
            MetricsConfigService.BoardTeamMap.insert {
                it[MetricsConfigService.BoardTeamMap.connectionId] = connId
                it[MetricsConfigService.BoardTeamMap.boardId] = 77L
                it[MetricsConfigService.BoardTeamMap.teamId] = teamX
            }
        }
        try {
            val client = seededClient("reports-wip-user-hand")
            val base = "connectionId=$connId&from=2026-01-05&to=2026-01-11"
            val one = listOf(1, 1, 1, 1, 1, 1, 1)
            val none = listOf(0, 0, 0, 0, 0, 0, 0)

            // Team X, a1 + a2: task 1 (a1), task 2 (a1 on 5..7, a2 on 8..11 — the reassignment sits exactly at the END of the 7th and
            // counts for a1 only), task 7 (a2, DONE). Tasks 6 and 12 are a1's but belong to team Y; the sub-task, the null-assignee
            // task, the epic and a3/a4's tasks count for nobody.
            val x = client.wip("$base&teamId=$teamX&${accountParams(listOf(a1, a2))}")
            assertEquals(STAGES, x.keys.map { it.key })
            assertEquals(isoDays("2026-01-05", "2026-01-11"), x.series.map { it.day })
            assertEquals(listOf(1, 1, 1, 1, 0, 0, 0), x.counts("NOT_STARTED"))
            assertEquals(listOf(1, 1, 1, 1, 2, 2, 2), x.counts("IN_PROGRESS"))
            assertEquals(none, x.counts("WAITING"))
            assertEquals(one, x.counts("DONE"))
            assertEquals(none, x.counts("UNMAPPED"))
            assertEquals(listOf(a1, a2), x.series.first().byUser.map { it.accountId })
            assertEquals(listOf(a1, a2), x.series.first().byUser.map { it.label }, "no display name known: the account id")
            assertEquals(listOf(1, 1, 1, 0, 0, 0, 0), x.userCounts(a1, "NOT_STARTED"))
            assertEquals(one, x.userCounts(a1, "IN_PROGRESS"))
            assertEquals(none, x.userCounts(a1, "DONE"), "a1's done task is credited to team Y")
            assertEquals(listOf(0, 0, 0, 1, 0, 0, 0), x.userCounts(a2, "NOT_STARTED"))
            assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), x.userCounts(a2, "IN_PROGRESS"))
            assertEquals(one, x.userCounts(a2, "DONE"))
            assertTrue(x.series.all { point -> point.byUser.all { it.counts.keys == point.counts.keys } }, "every share carries every key")
            assertNull(x.note)

            // The request order is the byUser order, and a pick with no task is an all-zero entry.
            val reversed = client.wip("$base&teamId=$teamX&${accountParams(listOf(a4, a2, a1))}")
            assertEquals(listOf(a4, a2, a1), reversed.series.first().byUser.map { it.accountId })
            assertEquals(none, reversed.userCounts(a4, "IN_PROGRESS"), "a4 is in no team, so nothing of his is team X's")
            assertEquals(x.counts("IN_PROGRESS"), reversed.counts("IN_PROGRESS"), "the union ignores the order and an empty pick")
            // One account keeps the shape with a one-entry byUser; a duplicate collapses.
            val single = client.wip("$base&teamId=$teamX&${accountParams(listOf(a1, a1))}")
            assertEquals(listOf(a1), single.series.first().byUser.map { it.accountId })
            assertEquals(one, single.counts("IN_PROGRESS"))

            // Team Y, a3 + a1: a3's task 5; a1's task 6 (the sprint's team wins over a1's own team X) and DONE task 12.
            val y = client.wip("$base&teamId=$teamY&${accountParams(listOf(a3, a1))}")
            assertEquals(listOf(2, 2, 2, 2, 2, 2, 2), y.counts("IN_PROGRESS"))
            assertEquals(one, y.counts("DONE"))
            assertEquals(one, y.userCounts(a3, "IN_PROGRESS"))
            assertEquals(one, y.userCounts(a1, "IN_PROGRESS"))
            assertEquals(one, y.userCounts(a1, "DONE"))
            // teamId=0: a4 (no team, no sprint); a1's work all has a team.
            val teamless = client.wip("$base&teamId=0&${accountParams(listOf(a4, a1))}")
            assertEquals(one, teamless.counts("IN_PROGRESS"))
            assertEquals(one, teamless.userCounts(a4, "IN_PROGRESS"))
            assertEquals(none, teamless.userCounts(a1, "IN_PROGRESS"))

            // Item kinds: an epic has no user — EPIC lists nothing, BOTH counts the tasks, both say so in the note.
            val epics = client.wip("$base&teamId=$teamX&${accountParams(listOf(a1))}&itemKind=EPIC")
            assertTrue(epics.series.isEmpty())
            assertTrue(assertNotNull(epics.note).contains("epic"))
            val both = client.wip("$base&teamId=$teamX&${accountParams(listOf(a1, a2))}&itemKind=BOTH")
            assertEquals(x.series, both.series)
            assertTrue(assertNotNull(both.note).contains("epics are left out"))
            assertNull(client.wip("$base&teamId=$teamX&${accountParams(listOf(a1))}&itemKind=TASK").note)

            // STATUS: the statuses seen in the period, by earliest stage; labelled from norm.statuses.
            val status = client.wip("$base&teamId=$teamX&${accountParams(listOf(a1, a2))}&by=STATUS")
            assertEquals(listOf("todo", "prog", "done"), status.keys.map { it.key })
            assertEquals(listOf("Open", "Dev", "Done"), status.keys.map { it.label })
            assertEquals(x.counts("IN_PROGRESS"), status.counts("prog"))
            assertEquals(one, status.userCounts(a2, "done"))
            // COLUMN: team X's board.
            val column = client.wip("$base&teamId=$teamX&${accountParams(listOf(a1, a2))}&by=COLUMN")
            assertEquals(listOf("To do", "Doing", "(no column)"), column.keys.map { it.key })
            assertEquals(listOf(1, 1, 1, 1, 0, 0, 0), column.counts("To do"))
            assertEquals(listOf(1, 1, 1, 1, 2, 2, 2), column.counts("Doing"))
            assertEquals(one, column.counts("(no column)"))
            assertEquals(none, column.userCounts(a1, "(no column)"))
            for (query in listOf("teamId=0&by=COLUMN", "teamId=$teamY&by=COLUMN")) {
                val rejected = client.get("/api/v1/reports/wip?$base&$query&${accountParams(listOf(a1))}")
                assertEquals(HttpStatusCode.BadRequest, rejected.status, query)
            }

            // The aggregate is untouched: team X without a person still reads agg_daily_wip (empty here — nothing was derived).
            assertTrue(client.wip("$base&teamId=$teamX").series.all { it.byUser.isEmpty() })
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteBridges(connId)
                store.deleteSprintFacts(connId)
                store.deleteDims(connId)
                MetricsTables.ItemStage.deleteWhere { MetricsTables.ItemStage.connectionId eq connId }
                WorkItemStore.FieldIntervals.deleteWhere { WorkItemStore.FieldIntervals.connectionId eq connId }
                MetricsConfigService.BoardTeamMap.deleteWhere { MetricsConfigService.BoardTeamMap.connectionId eq connId }
                workItems.replaceBoards(connId, emptyList())
                workItems.replaceStatuses(connId, emptyList())
                val membership = TeamMembershipService.TeamMembership
                membership.deleteWhere { membership.accountId inList listOf(a1, a2, a3, a4) }
            }
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    private suspend fun dayEndMs(day: String): Long = suspendTransaction(sharedDatabaseForTests()) {
        val d = MetricsTables.DimDate
        d.selectAll().where { d.day eq day }.toList().single()[d.dayEndMs]
    }

    private suspend fun insertMembership(account: String, team: UInt, from: Long, to: Long?) =
        suspendTransaction(sharedDatabaseForTests()) {
        TeamMembershipService.TeamMembership.insert {
            it[accountId] = account
            it[teamId] = team
            it[validFrom] = from
            it[validTo] = to
            it[createdAt] = 1L
            it[updatedAt] = 1L
        }
    }

    /**
     * A sprint that CLOSES mid-period and a team change, hand-computed (Mon 2026-01-05 .. Sun 2026-01-11, idx 0..6), teams X and Y.
     * Task 1 is b1's all period; b1 is in X until the END of the 8th and in Y from then (the boundary instant still reads X), so
     * the task-day is X's on 5..8 and Y's on 9..11. Task 2 is b2's (always in X) and sits in sprint 910 of team Y, whose
     * `complete_at` is exactly the END of the 7th: the sprint counts while `complete_at > day end`, so it is Y's on 5 and 6 and,
     * from the 7th on (the closing instant itself and every later day), the assignee's team X. The TEAM aggregate (the DERIVE WIP
     * statement run on the same rows) agrees on every day.
     */
    @Test
    fun `a sprint closing mid-period and a team change move the task-day between teams, as the TEAM aggregate does`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-user-move", enabled = false, backfillFrom = EARLY_BACKFILL)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("wipm-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("wipm-y"))
        val b1 = SyncedStubFixture.unique("acc-b1")
        val b2 = SyncedStubFixture.unique("acc-b2")
        val created = noonUtc("2026-01-01")
        val dayEnd7 = dayEndMs("2026-01-07")
        val dayEnd8 = dayEndMs("2026-01-08")
        insertMembership(b1, teamX, created, dayEnd8)
        insertMembership(b1, teamY, dayEnd8, null)
        insertMembership(b2, teamX, created, null)
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(connId, listOf(DimDomainRow("AAA", "Alpha", listOf("AA"), teamX)), 1L)
            val tasks = listOf(1L, 2L).map { DimTaskRow(it, "HM-$it", "Story", "Story", null, "NONE", false, null, "AAA", null) }
            store.insertTasks(connId, tasks, 1L)
            store.insertItemStage(connId, listOf(1L, 2L).map { ItemStageRow(it, "IN_PROGRESS", "prog", created, null) })
            store.insertTaskAssignee(connId, listOf(TaskAssigneeRow(1, b1, created, null), TaskAssigneeRow(2, b2, created, null)))
            val sprint = DimSprintRow(910, null, teamY, "Y sprint", "closed", created, dayEnd7, dayEnd7, null, null)
            store.insertDimSprints(connId, listOf(sprint), 1L)
            WorkItemStore.FieldIntervals.insert {
                it[WorkItemStore.FieldIntervals.connectionId] = connId
                it[issueId] = 2
                it[field] = "SPRINT"
                it[seq] = 0
                it[valueId] = "910"
                it[fromAt] = created
                it[toAt] = null
            }
            // The TEAM aggregate of the same rows: the DERIVE WIP team/task statement, through the 11th.
            store.execAggDailyWip(teamTaskWipSql(connId, noonUtc("2026-01-11"), 0))
        }
        try {
            val client = seededClient("reports-wip-user-move")
            val base = "connectionId=$connId&from=2026-01-05&to=2026-01-11"
            val picks = accountParams(listOf(b1, b2))

            val x = client.wip("$base&teamId=$teamX&$picks")
            assertEquals(listOf(1, 1, 1, 1, 0, 0, 0), x.userCounts(b1, "IN_PROGRESS"), "b1 is team X's up to the end of the 8th")
            assertEquals(listOf(0, 0, 1, 1, 1, 1, 1), x.userCounts(b2, "IN_PROGRESS"), "sprint closed AT the end of the 7th: X from then")
            assertEquals(listOf(1, 1, 2, 2, 1, 1, 1), x.counts("IN_PROGRESS"))
            val y = client.wip("$base&teamId=$teamY&$picks")
            assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), y.userCounts(b1, "IN_PROGRESS"), "b1 moved to team Y after the 8th")
            assertEquals(listOf(1, 1, 0, 0, 0, 0, 0), y.userCounts(b2, "IN_PROGRESS"), "the open sprint's team Y wins until it closes")
            assertEquals(listOf(1, 1, 0, 0, 1, 1, 1), y.counts("IN_PROGRESS"))

            // The persons' union agrees with the TEAM aggregate on every day (nobody else holds work here).
            val aggregateX = client.wip("$base&teamId=$teamX")
            val aggregateY = client.wip("$base&teamId=$teamY")
            assertEquals(x.counts("IN_PROGRESS"), aggregateX.counts("IN_PROGRESS"), "team X aggregate")
            assertEquals(y.counts("IN_PROGRESS"), aggregateY.counts("IN_PROGRESS"), "team Y aggregate")
        } finally {
            deleteWipRows(connId)
            deleteDeriveRuns(connId)
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteBridges(connId)
                store.deleteSprintFacts(connId)
                store.deleteDims(connId)
                MetricsTables.ItemStage.deleteWhere { MetricsTables.ItemStage.connectionId eq connId }
                WorkItemStore.FieldIntervals.deleteWhere { WorkItemStore.FieldIntervals.connectionId eq connId }
                val membership = TeamMembershipService.TeamMembership
                membership.deleteWhere { membership.accountId inList listOf(b1, b2) }
            }
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    /** The backfill start (decision C) and the derived cut-off apply to USER level like to every other: days 7..9 of 5..11. */
    @Test
    fun `USER level starts at the backfill date, stops at the last derived day and says why`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-user-window", enabled = false, backfillFrom = "2026-01-07")
        insertSucceededDerive(connId, noonUtc("2026-01-09"))
        val store = MetricsStore(sharedDatabaseForTests())
        val team = TestTeams.seed(SyncedStubFixture.unique("wipw-x"))
        val c1 = SyncedStubFixture.unique("acc-c1")
        val created = noonUtc("2026-01-01")
        insertMembership(c1, team, created, null)
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(connId, listOf(DimDomainRow("AAA", "Alpha", listOf("AA"), team)), 1L)
            store.insertTasks(connId, listOf(DimTaskRow(1, "HW-1", "Story", "Story", null, "NONE", false, null, "AAA", null)), 1L)
            store.insertItemStage(connId, listOf(ItemStageRow(1, "IN_PROGRESS", "prog", created, null)))
            store.insertTaskAssignee(connId, listOf(TaskAssigneeRow(1, c1, created, null)))
        }
        try {
            val body = seededClient("reports-wip-user-window")
                .wip("connectionId=$connId&from=2026-01-05&to=2026-01-11&teamId=$team&${accountParams(listOf(c1))}")
            assertEquals(listOf("2026-01-07", "2026-01-08", "2026-01-09"), body.series.map { it.day }, "backfill date .. last derived day")
            assertEquals(listOf(1, 1, 1), body.counts("IN_PROGRESS"))
            assertEquals("Before 2026-01-07 the connection's history is incomplete (backfill window)", body.note)
        } finally {
            deleteDeriveRuns(connId)
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteBridges(connId)
                store.deleteDims(connId)
                MetricsTables.ItemStage.deleteWhere { MetricsTables.ItemStage.connectionId eq connId }
                val membership = TeamMembershipService.TeamMembership
                membership.deleteWhere { membership.accountId eq c1 }
            }
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `accountId takes one to eight distinct accounts and the other 400 combinations are unchanged`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-wip-user-400")
        val team = TestTeams.seed(SyncedStubFixture.unique("wipu-400"))
        try {
            val base = "connectionId=$connId&from=2026-03-01&to=2026-03-05&teamId=$team"
            val eight = (1..8).map { "p$it" }
            val ok = client.wip("$base&${accountParams(eight)}")
            assertEquals(eight, ok.series.first().byUser.map { it.accountId })
            val zeros = ok.series.all { point -> point.byUser.all { share -> share.counts.values.all { it == 0 } } }
            assertTrue(zeros, "unknown people hold nothing")
            val duplicated = client.wip("$base&${accountParams(listOf("p1", "p1", "p1"))}")
            assertEquals(1, duplicated.series.first().byUser.size, "duplicates collapse")
            for (query in listOf(
                "$base&${accountParams(eight + "p9")}", // nine distinct accounts
                "connectionId=$connId&${accountParams(listOf("p1"))}", // accountId without teamId
                "$base&${accountParams(listOf("p1"))}&domain=AAA", // domain with teamId (hence with a user) has no split
                "$base&${accountParams(listOf("p1"))}&activityType=Story",
                "$base&${accountParams(listOf("p1"))}&workCategory=UNCATEGORIZED",
                "$base&${accountParams(listOf("p1"))}&by=WEEK",
            )) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/wip?$query").status, query)
            }
            // The backlog is not a multi-account report: a repeat stays a 400 there.
            val repeat = client.get("/api/v1/reports/backlog?$base&${accountParams(listOf("p1", "p2"))}")
            assertEquals(HttpStatusCode.BadRequest, repeat.status)
        } finally {
            cleanUpTeams(listOf(team))
        }
    }
}
