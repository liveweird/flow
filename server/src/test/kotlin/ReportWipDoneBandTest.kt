package ch.nokillswit

import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.ItemStageRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TaskDomainRow
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.wipStatements
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.SnapshotScope
import ch.nokillswit.reports.WipDoneCell
import ch.nokillswit.reports.WipDoneRequest
import ch.nokillswit.reports.WipReport
import ch.nokillswit.reports.readWipDoneBand
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val DONE_FROM = "2025-06-01"
private const val DONE_TO = "2026-03-05"

/**
 * Report 9's DONE band, computed at query time (brief 03 decision B, `.claude/docs/measures.md` "Report 9 — WIP",
 * `.claude/docs/domain-model.md` A34): DERIVE stores no `stage = DONE` row; the band is read off `item_stage` and attributed as at
 * the item's done time. Three layers: the stub fixture against the independent oracle of `ReportWipDoneSupport.kt` (every scope,
 * kind and day); a clone proving the four stored bands are byte-for-byte what the unfiltered statements wrote (the DONE filter is
 * the ONLY change) and that the unit-wide DONE band did not move, only its split; and hand-built rows with hand-computed answers
 * for a team change after done, a reopen, Done -> Closed, a domain change after done and a reopened-still-open item; a boundary
 * case (a domain move in the very entry that sets Done) and the account-filter edge cases (empty list, tasks excluded).
 */
class ReportWipDoneBandTest {

    private suspend fun HttpClient.wip(query: String): WipReport {
        val response = get("/api/v1/reports/wip?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET wip?$query")
        return response.body()
    }

    /**
     * The DONE band of the tasks plus that of the epics, per day, over [query] (the API never sums the two kinds: `itemKind=BOTH`
     * is a 400; the oracle comparisons still grade a scope's whole population, so the test adds the two reads itself).
     */
    private suspend fun HttpClient.doneBoth(query: String, key: String = "DONE"): Map<String, Int> {
        val tasks = wip("$query&itemKind=TASK")
        val epics = wip("$query&itemKind=EPIC")
        return tasks.series.associate { point ->
            point.day to (point.counts[key] ?: 0) + (epics.series.single { it.day == point.day }.counts[key] ?: 0)
        }
    }

    private suspend fun HttpClient.doneBothList(query: String): List<Int> = doneBoth(query).values.toList()

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    private fun WipReport.doneByDay(): Map<String, Int> = series.associate { it.day to it.counts.getValue("DONE") }

    /** The oracle's per-day DONE count of the rows [matching] a scope/kind, over the report's listed days. */
    private fun List<WipRow>.expectedDone(days: List<String>, matching: (WipRow) -> Boolean): Map<String, Int> {
        val perDay = filter(matching).groupBy { it.day }.mapValues { (_, group) -> group.sumOf { it.count } }
        return days.associateWith { perDay[it] ?: 0 }
    }

    @Test
    fun `the stub's DONE band per scope, kind and day equals the independent computation and DERIVE stores no DONE row`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val client = seededClient("reports-wip-done")
            assertTrue(readWipRows(connId).none { it.stage == "DONE" }, "no agg_daily_wip row has stage = DONE")
            val oracle = independentDoneRows(connId, DONE_FROM, DONE_TO)
            val kinds = oracle.map { it.itemKind }.toSet()
            assertTrue(kinds == setOf("TASK", "EPIC"), "the fixture must carry done tasks and epics")
            val query = "connectionId=$connId&from=$DONE_FROM&to=$DONE_TO"
            // UNIT level is the FLO domain read (a `domain` with no `teamId`): the DOMAIN scope of the stub's one populated domain.
            val unitQuery = "$query&domain=${DerivedStubFixture.DOMAIN}"

            fun matching(scopeKind: String, scopeId: String?, kinds: Set<String>): (WipRow) -> Boolean =
                { it.scopeKind == scopeKind && (scopeId == null || it.scopeId == scopeId) && it.itemKind in kinds }

            val unit = client.doneBoth(unitQuery)
            val days = client.wip("$unitQuery&itemKind=TASK").series.map { it.day }
            val flo = DerivedStubFixture.DOMAIN
            assertEquals(unit, oracle.expectedDone(days, matching("DOMAIN", flo, setOf("TASK", "EPIC"))), "UNIT, both kinds")
            assertTrue(unit.values.any { it > 0 })
            assertEquals(
                client.wip("$unitQuery&itemKind=TASK").doneByDay(),
                oracle.expectedDone(days, matching("DOMAIN", flo, setOf("TASK"))),
                "UNIT, tasks",
            )
            assertEquals(
                client.wip("$unitQuery&itemKind=EPIC").doneByDay(),
                oracle.expectedDone(days, matching("DOMAIN", flo, setOf("EPIC"))),
                "UNIT, epics",
            )
            // Every TEAM scope the oracle knows (a real team, UNASSIGNED tasks, UNOWNED epics) and every domain.
            val teamIds = oracle.filter { it.scopeKind == "TEAM" && it.scopeId.all(Char::isDigit) }.map { it.scopeId }.distinct()
            assertTrue(teamIds.isNotEmpty(), "the fixture must credit at least one real team")
            for (team in teamIds) {
                val body = client.doneBoth("$query&teamId=$team")
                assertEquals(body, oracle.expectedDone(days, matching("TEAM", team, setOf("TASK", "EPIC"))), "team $team")
            }
            val unassigned = client.doneBoth("$query&teamId=0")
            val unassignedRows = { row: WipRow ->
                val unassignedTask = row.itemKind == "TASK" && row.scopeId == "UNASSIGNED"
                val unownedEpic = row.itemKind == "EPIC" && row.scopeId == "UNOWNED"
                row.scopeKind == "TEAM" && (unassignedTask || unownedEpic)
            }
            assertEquals(
                unassigned, oracle.expectedDone(days, unassignedRows), "teamId=0 is UNASSIGNED tasks plus UNOWNED epics",
            )
            val domains = oracle.filter { it.scopeKind == "DOMAIN" }.map { it.scopeId }.distinct()
            assertTrue(domains.isNotEmpty())
            for (domain in domains) {
                val body = client.doneBoth("$query&domain=$domain")
                val want = oracle.expectedDone(days, matching("DOMAIN", domain, setOf("TASK", "EPIC")))
                assertEquals(body, want, "domain $domain")
            }
            // Keyed by status the band is the same cells, per status (the statuses only DONE items sit in, e.g. Done and Closed).
            val storedStatuses = readWipRows(connId).map { it.statusId }.toSet()
            val doneOnlyStatuses = oracle.filter { it.scopeKind == "DOMAIN" && it.scopeId == flo }.map { it.statusId }.distinct()
                .filter { it !in storedStatuses }
            assertTrue(doneOnlyStatuses.isNotEmpty(), "the fixture must have a status only finished items sit in")
            for (status in doneOnlyStatuses) {
                val want = oracle.filter { it.scopeKind == "DOMAIN" && it.scopeId == flo && it.statusId == status }
                    .groupBy { it.day }.mapValues { (_, g) -> g.sumOf { it.count } }
                val got = client.doneBoth("$unitQuery&by=STATUS", status)
                assertEquals(days.associateWith { want[it] ?: 0 }, got, "status $status")
            }
        }

    /** DERIVE on a private clone, once with the new statements and once with their DONE filter stripped (the old behaviour). */
    @Test
    fun `the four stored bands are exactly what the unfiltered statements wrote, and the unit-wide DONE band only changes its split`() =
        runBlocking {
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-wip-done-clone", enabled = false)
            SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
            DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "wip-done-team")
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }
            val sortKey = compareBy<WipRow>({ it.scopeKind }, { it.scopeId }, { it.day }, { it.itemKind }, { it.statusId }, { it.stage })
            val stored = readWipRows(connId)
            assertTrue(stored.isNotEmpty() && stored.none { it.stage == "DONE" })

            val statements = wipStatements(connId, DerivedStubFixture.PINNED_NOW, configRevision = 0)
            val unfiltered = statements.map { it.replace("s.stage <> 'DONE' AND ", "") }
            assertTrue(unfiltered.zip(statements).all { (old, new) -> old != new }, "every statement carries the DONE filter")
            deleteWipRows(connId)
            val store = MetricsStore(sharedDatabaseForTests())
            suspendTransaction(sharedDatabaseForTests()) { unfiltered.forEach { store.execAggDailyWip(it) } }
            val before = readWipRows(connId)
            assertTrue(before.any { it.stage == "DONE" }, "the old statements did write the DONE band")
            assertEquals(
                before.filter { it.stage != "DONE" }.sortedWith(sortKey), stored.sortedWith(sortKey),
                "NOT_STARTED, IN_PROGRESS, WAITING, BLOCKED and UNMAPPED are exactly what they were",
            )

            // The unit-wide DONE band (every TEAM scope summed) is the same set of items either way; only its team/domain split moves.
            val days = before.map { it.day }
            val after = independentDoneRows(connId, days.min(), days.max())
            fun perDay(rows: List<WipRow>, kind: String) =
                rows.filter { it.scopeKind == "TEAM" && it.itemKind == kind && it.stage == "DONE" }
                    .groupBy { it.day to it.statusId }.mapValues { (_, g) -> g.sumOf { it.count } }
            for (kind in listOf("TASK", "EPIC")) {
                assertEquals(perDay(before, kind), perDay(after, kind), "the UNIT-wide $kind DONE band per day and status did not move")
            }
            assertTrue(perDay(after, "TASK").isNotEmpty())
        }

    /**
     * Hand-built rows (fresh DISABLED connection, Mon 2026-01-05 .. Sun 2026-01-11, every transition at noon UTC): team X owns
     * domain AAA, team Y domain BBB, domain CCC has no owner. Every figure below is hand-computed.
     *
     * | task | what it does | credit | band (days 5..11) |
     * |---|---|---|---|
     * | 1 | Done on 5 (status `done`), current team Y | X | 5..11 |
     * | 2 | Done on 5, Closed on 7 (`done` then `closed`) | X | 5..11, `done` 5-6, `closed` 7+ |
     * | 3 | Done 5, reopened 7, done again 9 | X | 5, 6, 9, 10, 11 (not 7, 8) |
     * | 4 | Done on 5; domain AAA until 8 noon, BBB after | Y | 5..11, in AAA (domain at done) |
     * | 5 | Done 5, reopened 8 and still open (no `done_at`, no credit) | - | 5, 6, 7, as UNASSIGNED |
     * | 6 | a sub-task, done on 5 | X | never |
     * | 7 | never done | - | never |
     * | epic 8 | done on 6, domain AAA (owner X) | - | 6..11 |
     * | epic 9 | done on 6, domain CCC (no owner) | - | 6..11, UNOWNED |
     */
    @Test
    fun `hand-built rows pin the team at done, a reopen, Done to Closed, the domain at done and a reopened open item`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026 (the derive widens the global table)
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-done-hand", enabled = false, backfillFrom = EARLY_BACKFILL)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("wipdone-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("wipdone-y"))
        val x = teamX.toString()
        val y = teamY.toString()
        fun at(day: String) = noonUtc(day)
        val created = at("2026-01-01")
        fun stage(issue: Long, stage: String, status: String, from: String?, to: String?) =
            ItemStageRow(issue, stage, status, from?.let { at(it) } ?: created, to?.let { at(it) })
        fun task(id: Long, domain: String = "AAA", subtask: Boolean = false) =
            DimTaskRow(id, "HB-$id", "Story", "Story", null, "NONE", subtask, null, domain, null)
        val stages = listOf(
            stage(1, "IN_PROGRESS", "prog", null, "2026-01-05"), stage(1, "DONE", "done", "2026-01-05", null),
            stage(2, "IN_PROGRESS", "prog", null, "2026-01-05"), stage(2, "DONE", "done", "2026-01-05", "2026-01-07"),
            stage(2, "DONE", "closed", "2026-01-07", null),
            stage(3, "IN_PROGRESS", "prog", null, "2026-01-05"), stage(3, "DONE", "done", "2026-01-05", "2026-01-07"),
            stage(3, "IN_PROGRESS", "prog", "2026-01-07", "2026-01-09"), stage(3, "DONE", "done", "2026-01-09", null),
            stage(4, "IN_PROGRESS", "prog", null, "2026-01-05"), stage(4, "DONE", "done", "2026-01-05", null),
            stage(5, "IN_PROGRESS", "prog", null, "2026-01-05"), stage(5, "DONE", "done", "2026-01-05", "2026-01-08"),
            stage(5, "IN_PROGRESS", "prog", "2026-01-08", null),
            stage(6, "IN_PROGRESS", "prog", null, "2026-01-05"), stage(6, "DONE", "done", "2026-01-05", null),
            stage(7, "IN_PROGRESS", "prog", null, null),
            stage(8, "IN_PROGRESS", "prog", null, "2026-01-06"), stage(8, "DONE", "edone", "2026-01-06", null),
            stage(9, "IN_PROGRESS", "prog", null, "2026-01-06"), stage(9, "DONE", "edone", "2026-01-06", null),
        )
        val facts = listOf(
            handTask(1, doneAt = at("2026-01-05"), creditTeamId = teamX, currentTeamId = teamY, account = "acc-1"),
            handTask(2, doneAt = at("2026-01-05"), creditTeamId = teamX, account = "acc-2"),
            handTask(3, doneAt = at("2026-01-09"), creditTeamId = teamX, account = "acc-1"),
            handTask(4, doneAt = at("2026-01-05"), creditTeamId = teamY, domain = "AAA", account = "acc-2"),
            handTask(5, doneAt = null),
            handTask(6, doneAt = at("2026-01-05"), creditTeamId = teamX, subtask = true),
            handTask(7),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(
                connId,
                listOf(
                    DimDomainRow("AAA", "Alpha", listOf("AA"), teamX), DimDomainRow("BBB", "Beta", listOf("BB"), teamY),
                    DimDomainRow("CCC", "Gamma", listOf("CC"), null),
                ),
                1L,
            )
            store.insertTasks(
                connId,
                listOf(task(1), task(2), task(3), task(4, domain = "BBB"), task(5), task(6, subtask = true), task(7)),
                1L,
            )
            store.insertEpics(
                connId,
                listOf(
                    DimEpicRow(8, "EP-8", null, "AAA", null, "DONE", null, null),
                    DimEpicRow(9, "EP-9", null, "CCC", null, "DONE", null, null),
                ),
                1L,
            )
            store.insertItemStage(connId, stages)
            // Task 4: AAA until the 8th, BBB after — its CURRENT domain is BBB, but at done (the 5th) it was AAA.
            store.insertTaskDomain(
                connId,
                listOf(TaskDomainRow(4, "AAA", created, at("2026-01-08")), TaskDomainRow(4, "BBB", at("2026-01-08"), null)),
            )
            store.insertFactTaskDelivery(connId, facts, 1L)
        }
        try {
            val client = seededClient("reports-wip-done-hand")
            val base = "connectionId=$connId&from=2026-01-05&to=2026-01-11"
            fun WipReport.done() = series.map { it.counts.getValue("DONE") }
            fun WipReport.key(key: String) = series.map { it.counts.getValue(key) }

            // TEAM X: tasks 1, 2, 3 (task 4's credit is Y); the team change of task 1 afterwards (current team Y) changes nothing.
            assertEquals(listOf(3, 3, 2, 2, 3, 3, 3), client.wip("$base&teamId=$x&itemKind=TASK").done(), "team X tasks")
            // TEAM Y: task 4 only, on every day (its domain moving to BBB on the 8th is a domain matter).
            assertEquals(List(7) { 1 }, client.wip("$base&teamId=$y&itemKind=TASK").done(), "team Y tasks")
            // UNASSIGNED (teamId=0): the reopened-still-open task 5 while it was done (5, 6, 7); tasks only by default.
            assertEquals(listOf(1, 1, 1, 0, 0, 0, 0), client.wip("$base&teamId=0&itemKind=TASK").done(), "UNASSIGNED tasks")
            // Epics: epic 8 -> owner X, epic 9 -> UNOWNED; both from the 6th.
            assertEquals(listOf(0, 1, 1, 1, 1, 1, 1), client.wip("$base&teamId=$x&itemKind=EPIC").done(), "team X epics")
            assertEquals(listOf(0, 1, 1, 1, 1, 1, 1), client.wip("$base&teamId=0&itemKind=EPIC").done(), "UNOWNED epics")
            assertEquals(listOf(3, 4, 3, 3, 4, 4, 4), client.doneBothList("$base&teamId=$x"), "team X, both kinds")
            // UNIT sums every scope: tasks 1-5 (the sub-task and the never-done task are out) plus the two epics.
            val unitTasks = client.wip("$base&itemKind=TASK").done()
            assertEquals(listOf(5, 5, 4, 3, 4, 4, 4), unitTasks, "UNIT tasks (task 3 is out on 7-8, task 5 from the 8th)")
            assertEquals(listOf(5, 7, 6, 5, 6, 6, 6), client.doneBothList(base), "UNIT, both kinds")

            // DOMAIN: the task's domain AT DONE — task 4 stays in AAA although it moved to BBB on the 8th; task 5 (no done_at)
            // falls back to its current domain AAA; epics their own.
            assertEquals(listOf(5, 5, 4, 3, 4, 4, 4), client.wip("$base&domain=AAA&itemKind=TASK").done(), "domain AAA tasks")
            assertEquals(List(7) { 0 }, client.wip("$base&domain=BBB&itemKind=TASK").done(), "domain BBB tasks (task 4 left it at done)")
            assertEquals(listOf(0, 1, 1, 1, 1, 1, 1), client.doneBothList("$base&domain=CCC"), "domain CCC (an unowned epic)")
            assertEquals(listOf(5, 6, 5, 4, 5, 5, 5), client.doneBothList("$base&domain=AAA"), "domain AAA, both kinds")

            // Done -> Closed still moves the item between status keys; the band stays one item. Reopened task 3 is out on 7 and 8.
            val status = client.wip("$base&teamId=$x&itemKind=TASK&by=STATUS")
            assertEquals(listOf("closed", "done"), status.keys.map { it.key })
            assertEquals(listOf(0, 0, 1, 1, 1, 1, 1), status.key("closed"), "task 2 is Closed from the 7th")
            assertEquals(listOf(3, 3, 1, 1, 2, 2, 2), status.key("done"), "tasks 1, 2 (until the 6th) and 3 (not while reopened)")

            // The other bands are untouched: only the DONE stage appears (no stored aggregate rows at all).
            val stage = client.wip("$base&teamId=$x&itemKind=TASK")
            assertTrue(stage.series.all { point -> point.counts.filterKeys { it != "DONE" }.values.all { it == 0 } })

            // The reusable read (brief 04's per-person WIP): the same statement with an account filter. Epics have no assignee.
            val report = service()
            suspend fun read(scope: SnapshotScope, accounts: List<String>?): List<WipDoneCell> =
                report.reportTransaction {
                    readWipDoneBand(WipDoneRequest(listOf(connId), scope, true, true, "2026-01-05", "2026-01-11", accounts))
                }
            fun List<WipDoneCell>.perDay() = (5..11).map { d -> filter { it.day == "2026-01-%02d".format(d) }.sumOf { it.count } }
            assertEquals(listOf(2, 2, 1, 1, 2, 2, 2), read(SnapshotScope.AllTeams, listOf("acc-1")).perDay(), "acc-1 done tasks 1 and 3")
            assertEquals(listOf(2, 2, 2, 2, 2, 2, 2), read(SnapshotScope.AllTeams, listOf("acc-2")).perDay(), "acc-2 done tasks 2 and 4")
            val teamYBoth = read(SnapshotScope.Team(teamY), listOf("acc-1", "acc-2")).perDay()
            assertEquals(listOf(1, 1, 1, 1, 1, 1, 1), teamYBoth, "acc-1/2 in team Y")
            assertEquals(List(7) { 0 }, read(SnapshotScope.AllTeams, listOf("nobody")).perDay())
            assertEquals(listOf(5, 7, 6, 5, 6, 6, 6), read(SnapshotScope.AllTeams, null).perDay(), "no filter: tasks and epics")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteBridges(connId)
                store.deleteDims(connId)
                MetricsTables.ItemStage.deleteWhere { MetricsTables.ItemStage.connectionId eq connId }
            }
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    /**
     * Hand-built boundary (fresh DISABLED connection, Mon 2026-01-05 .. Thu 2026-01-08, transitions at noon UTC): DERIVE resolves
     * the domain at done with `[from, to)` (`domainRowAt`) and stores it in `fact_task_delivery.domain_key`, so a task moved AAA ->
     * BBB and set Done in the SAME changelog entry (`task_domain` boundary exactly at `done_at`) is in BBB; the band must agree.
     *
     * | task | done at | `task_domain` | domain at done | band days 5..8 |
     * |---|---|---|---|---|
     * | 1 | 6th noon | AAA until 6th noon, BBB from it | BBB (the boundary belongs to the new row) | 6, 7, 8 |
     * | 2 | 5th noon | AAA until 6th noon, BBB from it | AAA (done a day before the move) | 5, 6, 7, 8 |
     */
    @Test
    fun `a task moved and set Done in the same entry counts under the new domain`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-done-boundary", enabled = false, backfillFrom = EARLY_BACKFILL)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val store = MetricsStore(sharedDatabaseForTests())
        val created = noonUtc("2026-01-01")
        val move = noonUtc("2026-01-06")
        fun task(id: Long, domain: String) = DimTaskRow(id, "HB-$id", "Story", "Story", null, "NONE", false, null, domain, null)
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(
                connId, listOf(DimDomainRow("AAA", "Alpha", listOf("AA"), null), DimDomainRow("BBB", "Beta", listOf("BB"), null)), 1L,
            )
            store.insertTasks(connId, listOf(task(1, "BBB"), task(2, "BBB")), 1L)
            store.insertItemStage(
                connId,
                listOf(
                    ItemStageRow(1, "IN_PROGRESS", "prog", created, move), ItemStageRow(1, "DONE", "done", move, null),
                    ItemStageRow(2, "IN_PROGRESS", "prog", created, noonUtc("2026-01-05")),
                    ItemStageRow(2, "DONE", "done", noonUtc("2026-01-05"), null),
                ),
            )
            val history = listOf(TaskDomainRow(1, "AAA", created, move), TaskDomainRow(1, "BBB", move, null))
            store.insertTaskDomain(connId, history + history.map { it.copy(issueId = 2) })
            store.insertFactTaskDelivery(
                connId,
                listOf(
                    handTask(1, doneAt = move, domain = "BBB", account = "acc-1"),
                    handTask(2, doneAt = noonUtc("2026-01-05"), domain = "AAA", account = "acc-2"),
                ),
                1L,
            )
        }
        try {
            val client = seededClient("reports-wip-done-boundary")
            val base = "connectionId=$connId&from=2026-01-05&to=2026-01-08&itemKind=TASK"
            fun WipReport.done() = series.map { it.counts.getValue("DONE") }
            assertEquals(listOf(0, 1, 1, 1), client.wip("$base&domain=BBB").done(), "BBB: task 1 (the boundary belongs to the new row)")
            assertEquals(listOf(1, 1, 1, 1), client.wip("$base&domain=AAA").done(), "AAA: task 2 only")
            // The independent oracle (task_domain rows read with [from, to)) agrees on both domains.
            val oracle = independentDoneRows(connId, "2026-01-05", "2026-01-08").filter { it.scopeKind == "DOMAIN" }
            fun List<WipRow>.perDay(domain: String) = (5..8).map { d ->
                filter { it.scopeId == domain && it.day == "2026-01-%02d".format(d) }.sumOf { it.count }
            }
            assertEquals(listOf(0, 1, 1, 1), oracle.perDay("BBB"))
            assertEquals(listOf(1, 1, 1, 1), oracle.perDay("AAA"))

            // The reusable read returns no cells, instead of a syntax error or a failed `require`, when nothing can match.
            val report = service()
            suspend fun read(tasks: Boolean, accounts: List<String>?): List<WipDoneCell> = report.reportTransaction {
                readWipDoneBand(WipDoneRequest(listOf(connId), SnapshotScope.AllTeams, tasks, true, "2026-01-05", "2026-01-08", accounts))
            }
            assertTrue(read(tasks = true, accounts = listOf("acc-1", "acc-2")).isNotEmpty(), "the plain account read finds both tasks")
            assertEquals(emptyList(), read(tasks = true, accounts = emptyList()), "an empty account list reads nothing (no `IN ()`)")
            assertEquals(emptyList(), read(tasks = false, accounts = listOf("acc-1")), "a person filter without tasks reads nothing")
            assertEquals(emptyList(), read(tasks = false, accounts = emptyList()), "an empty account list without tasks reads nothing")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteBridges(connId)
                store.deleteDims(connId)
                MetricsTables.ItemStage.deleteWhere { MetricsTables.ItemStage.connectionId eq connId }
            }
            deleteDeriveRuns(connId)
        }
    }

    /** The helper brief 04 reuses, against the oracle on the real fixture: an account filter on tasks. */
    @Test
    fun `the account-filtered DONE read equals the independent computation on the stub`() = runBlocking {
        val connId = DerivedStubFixture.connectionId()
        val accounts = suspendTransaction(sharedDatabaseForTests()) {
            val f = MetricsTables.FactTaskDelivery
            f.select(f.assigneeAccountIdAtDone).where { f.connectionId eq connId }.toList()
                .mapNotNull { it[f.assigneeAccountIdAtDone] }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(2).map { it.key }
        }
        assertEquals(2, accounts.size, "the fixture must have done tasks with at least two assignees")
        val oracle = independentDoneRows(connId, DONE_FROM, DONE_TO, accounts.toSet()).filter { it.scopeKind == "TEAM" }
        val cells = service().reportTransaction {
            readWipDoneBand(WipDoneRequest(listOf(connId), SnapshotScope.AllTeams, true, true, DONE_FROM, DONE_TO, accounts))
        }
        val got = cells.groupBy { it.day to it.statusId }.mapValues { (_, g) -> g.sumOf { it.count } }
        val want = oracle.groupBy { it.day to it.statusId }.mapValues { (_, g) -> g.sumOf { it.count } }
        assertTrue(want.isNotEmpty(), "the chosen assignees must have done work")
        assertEquals(want, got)
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
