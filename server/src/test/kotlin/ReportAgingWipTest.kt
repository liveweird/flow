package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.ItemBlockedRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.AgingWipReport
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportBreakdown
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9

/**
 * `GET /api/v1/reports/aging-wip` (v0.3.0 M5 commit 15, Report 11, `.claude/docs/measures.md` "Report 11"). Ages are
 * "as of the request's clock", so the exact figures are pinned by calling the service with an INJECTED clock over
 * hand-built rows (never wall time); the stub fixture (whose derive clock is pinned but whose request clock is not) is
 * graded on the populations, the thresholds against an independent percentile of the raw `fact_task_delivery` /
 * `fact_epic_delivery` rows, and the shape.
 */
class ReportAgingWipTest {

    private suspend fun HttpClient.aging(query: String): AgingWipReport {
        val response = get("/api/v1/reports/aging-wip?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET aging-wip?$query")
        return response.body()
    }

    /** A filter for a direct service call — a TEAM/UNIT read on one connection; the period is ignored by aging-wip. */
    private fun filter(connId: UInt, teamId: UInt? = null, accountId: String? = null) = ReportFilter(
        period = ReportPeriod.DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 0L, 1L),
        level = when {
            teamId == null -> ReportLevel.UNIT
            accountId == null -> ReportLevel.TEAM
            else -> ReportLevel.USER
        },
        teamId = teamId, accountId = accountId, domainView = DomainView.TASK, domain = null, activityType = null,
        workCategory = null, connectionId = connId, breakdown = ReportBreakdown.NONE,
    )

    private fun service(): ReportService {
        val config = DerivedStubFixture.metricsConfig()
        return ReportService(sharedDatabaseForTests(), config, TeamMembershipService(sharedDatabaseForTests(), config))
    }

    @Test
    fun `the fixture's populations and thresholds equal an independent computation`() = testApplication {
        usePostgresTestcontainer()
        val connId = derivedFixtureConnectionId()
        val client = seededClient("reports-aging-fixture")
        val facts = suspendTransaction(sharedDatabaseForTests()) {
            val t = MetricsStore.FactTaskDelivery
            val e = MetricsStore.FactEpicDelivery
            val d = MetricsStore.DimEpic
            val inProgressEpics = d.selectAll().where { (d.connectionId eq connId) and (d.currentStage eq "IN_PROGRESS") }.toList()
                .map { it[d.issueId] }.toSet()
            Triple(
                t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList(),
                e.selectAll().where { e.connectionId eq connId }.toList().filter { it[e.issueId] in inProgressEpics },
                MetricsStore.DeriveRuns.selectAll().toList().size,
            )
        }
        val t = MetricsStore.FactTaskDelivery
        val e = MetricsStore.FactEpicDelivery
        val openTasks = facts.first.count { it[t.doneAt] == null && it[t.startedAt] != null && it[t.currentStage] == "IN_PROGRESS" }
        val openEpics = facts.second.count { it[e.doneAt] == null && it[e.startedAt] != null }
        assertTrue(openTasks > 0 && openEpics >= 0, "the fixture must carry in-progress tasks")

        val body = client.aging("connectionId=$connId")
        // `blocked` = an item_blocked row covering the connection's derive clock (the deriver closes open spells AT it).
        val clock = DerivedStubFixture.PINNED_NOW
        val blockedIds = suspendTransaction(sharedDatabaseForTests()) {
            val b = MetricsStore.ItemBlocked
            b.selectAll().where { b.connectionId eq connId }.toList()
                .filter { it[b.validFrom] <= clock && (it[b.validTo]?.let { to -> to >= clock } ?: true) }.map { it[b.issueId] }.toSet()
        }
        val expectedBlockedTasks = facts.first.count {
            it[t.doneAt] == null && it[t.startedAt] != null && it[t.currentStage] == "IN_PROGRESS" && it[t.issueId] in blockedIds
        }
        val expectedItems = openTasks + openEpics
        assertEquals(minOf(expectedItems, 500), body.items.size)
        assertEquals(expectedItems > 500, body.itemsTruncated)
        if (!body.itemsTruncated) {
            assertEquals(openTasks, body.items.count { it.itemKind == "TASK" })
            assertEquals(openEpics, body.items.count { it.itemKind == "EPIC" })
        }
        if (!body.itemsTruncated) {
            assertTrue(expectedBlockedTasks > 0, "the fixture must carry an in-progress task blocked at its derive clock")
            val blockedTasks = body.items.count { it.itemKind == "TASK" && it.blocked }
            assertEquals(expectedBlockedTasks, blockedTasks, "blocked is read at the derive clock")
        }
        assertEquals(body.items.map { it.ageWorkingDays }, body.items.map { it.ageWorkingDays }.sortedDescending(), "oldest first")
        assertTrue(body.items.all { it.ageWorkingDays >= 0.0 && it.startedAt > 0 })
        assertTrue(body.items.all { it.issueKey.isNotBlank() && it.issueKey.any { c -> c == '-' } }, "keys come from norm.work_items")

        // Thresholds: the configured percentiles of the LAST N done tasks' cycle working days, from the raw rows.
        val settings = suspendTransaction(sharedDatabaseForTests()) { DerivedStubFixture.metricsConfig().read() }
        val done = facts.first.filter { it[t.doneAt] != null && it[t.cycleWorkingDays] != null }
            .sortedByDescending { it[t.doneAt] }.take(settings.agingWindowItems).map { it[t.cycleWorkingDays]!!.toDouble() }.sorted()
        assertEquals(done.size.toLong(), body.thresholds.n)
        assertEquals(done.size < settings.minSampleSize, body.thresholds.hidden)
        assertEquals(settings.agingPercentiles.sorted(), body.thresholds.percentiles.map { it.percentile })
        if (!body.thresholds.hidden) {
            for (p in body.thresholds.percentiles) assertEquals(quantile(done, p.percentile / 100.0), assertNotNull(p.workingDays), EPS)
            assertTrue(body.items.filter { it.itemKind == "TASK" }.all { it.band != null }, "visible thresholds band every task")
        }
        assertEquals("UNIT", body.meta.level.name)
    }

    @Test
    fun `hand-built rows pin the age, the bands, the thresholds window, blocked and the populations exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "aging-hand", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("aging-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("aging-y"))
        val now = noonUtc("2026-01-14") // Wednesday
        insertSucceededDerive(connId, now) // the connection's derive clock: what "blocked right now" is read at
        fun open(
            id: Long,
            startedDay: String?,
            team: UInt? = teamX,
            stage: String = "IN_PROGRESS",
            account: String? = null,
            subtask: Boolean = false,
        ) =
            handTask(id, startedDay?.let { noonUtc(it) }, null, currentTeamId = team, currentAssignee = account, subtask = subtask)
                .copy(currentStage = stage)
        fun done(id: Long, doneDay: String, cycle: Double) =
            handTask(id, noonUtc("2025-12-01"), noonUtc(doneDay), cycleMs = 1L, cycleWorkingDays = cycle, creditTeamId = teamX)
        val tasks = listOf(
            // Team X's DONE window (N = 5): cycles 1..5 in January; the oldest, 100, is outside the window.
            done(1, "2025-12-10", 100.0), done(2, "2026-01-05", 1.0), done(3, "2026-01-06", 2.0), done(4, "2026-01-07", 3.0),
            done(5, "2026-01-08", 4.0), done(6, "2026-01-09", 5.0),
            // Open in-progress tasks of X at the fixed clock: ages 1, 2, 3, 4, 5, 8 working days.
            open(11, "2026-01-13", account = "acc-1"), open(12, "2026-01-12"), open(13, "2026-01-09"), open(14, "2026-01-08"),
            open(15, "2026-01-07"), open(16, "2026-01-02"),
            // Never listed: another team, not in progress, never started, a sub-task.
            open(21, "2026-01-02", team = teamY), open(22, "2026-01-02", stage = "NOT_STARTED"), open(23, null),
            open(24, "2026-01-02", subtask = true),
        )
        val epics = listOf(
            handEpic(31, noonUtc("2026-01-12"), null, ownerTeamId = teamX), handEpic(32, noonUtc("2026-01-12"), null, ownerTeamId = teamX),
        )
        val dims = listOf(
            DimEpicRow(31, "EP-31", "Open epic", "AAA", null, "IN_PROGRESS", null, null),
            DimEpicRow(32, "EP-32", null, "AAA", null, "NOT_STARTED", null, null),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
            store.replaceFactEpicDelivery(connId, epics, configRevision = 1L)
            store.insertEpics(connId, dims, configRevision = 1L)
            // The deriver's shape: a spell still open at the derive clock is closed AT it (never NULL). Task 16 is blocked at
            // the clock; 14's and 15's spells ended earlier; 13's began after it.
            store.insertItemBlocked(
                connId,
                listOf(
                    ItemBlockedRow(16, "FLAGGED", noonUtc("2026-01-10"), now), ItemBlockedRow(14, "STATUS", 1L, 2L),
                    ItemBlockedRow(15, "FLAGGED", noonUtc("2026-01-08"), noonUtc("2026-01-12")),
                    ItemBlockedRow(13, "FLAGGED", now + 1, now + 2),
                ),
            )
        }
        try {
            val config = DerivedStubFixture.metricsConfig()
            val body = withMetricsSettings(config, { it.copy(agingWindowItems = 5, minSampleSize = 2) }) {
                service().agingWip(filter(connId, teamX), now)
            }
            // Keys fall back to the issue id: hand-built issues have no norm.work_items rows.
            val taskItems = body.items.filter { it.itemKind == "TASK" }
            assertEquals(listOf(16L, 15L, 14L, 13L, 12L, 11L), taskItems.map { it.issueKey.toLong() })
            val ages = body.items.filter { it.itemKind == "TASK" }.map { it.ageWorkingDays }
            listOf(8.0, 5.0, 4.0, 3.0, 2.0, 1.0).zip(ages).forEach { (want, got) -> assertEquals(want, got, 1e-6) }
            // The window keeps the newest five done tasks: 1..5 → p50 3, p85 4.4, p95 4.8; the older 100 is outside.
            assertEquals(5, body.thresholds.n)
            assertEquals(listOf(3.0, 4.4, 4.8), body.thresholds.percentiles.map { it.workingDays!! })
            assertEquals(listOf(50, 85, 95), body.thresholds.percentiles.map { it.percentile })
            // Bands: 8 and 5 pass p95 (4.8); 4 passes p50 only; 3, 2, 1 pass none.
            assertEquals(listOf("P95", "P95", "P50", "WITHIN", "WITHIN", "WITHIN"), taskItems.map { it.band })
            assertEquals(listOf(true, false, false, false, false, false), body.items.filter { it.itemKind == "TASK" }.map { it.blocked })
            val assigned = body.items.single { it.issueKey == "11" }
            assertEquals("acc-1", assigned.assigneeAccountId)
            assertEquals("acc-1", assigned.assignee, "no display name known: the account id stands in")
            assertEquals(teamX, assigned.teamId)

            // The epic: owner team X, its own thresholds hidden (no done epic), so no band; the NOT_STARTED one is not listed.
            val epic = body.items.single { it.itemKind == "EPIC" }
            assertEquals("EP-31", epic.issueKey)
            assertEquals("Open epic", epic.summary)
            assertEquals(2.0, epic.ageWorkingDays, EPS)
            assertNull(epic.assigneeAccountId)
            assertNull(epic.band)
            assertTrue(body.epicThresholds.hidden)
            assertEquals(0, body.epicThresholds.n)
            assertTrue(body.epicThresholds.percentiles.all { it.workingDays == null })
            assertEquals(false, body.itemsTruncated)

            // Hidden thresholds (minimum sample size above n) leave every task without a band.
            val hidden = withMetricsSettings(config, { it.copy(agingWindowItems = 5, minSampleSize = 6) }) {
                service().agingWip(filter(connId, teamX), now)
            }
            assertTrue(hidden.thresholds.hidden && hidden.thresholds.percentiles.all { it.workingDays == null })
            assertTrue(hidden.items.all { it.band == null })

            // USER level narrows the listed tasks to that assignee (epics have no user) but keeps the team's thresholds.
            val user = withMetricsSettings(config, { it.copy(agingWindowItems = 5, minSampleSize = 2) }) {
                service().agingWip(filter(connId, teamX, "acc-1"), now)
            }
            assertEquals(listOf("11"), user.items.map { it.issueKey })
            assertEquals(5, user.thresholds.n)
            assertEquals("WITHIN", user.items.single().band)
            // UNIT level lists team Y's task too.
            val unit = withMetricsSettings(config, { it.copy(agingWindowItems = 5, minSampleSize = 2) }) {
                service().agingWip(filter(connId), now)
            }
            assertTrue(unit.items.any { it.issueKey == "21" && it.teamId == teamY })
            assertEquals(7, unit.items.count { it.itemKind == "TASK" })
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteFactEpicDelivery(connId)
                store.deleteDims(connId)
                MetricsStore.ItemBlocked.deleteWhere { MetricsStore.ItemBlocked.connectionId eq connId }
            }
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    @Test
    fun `the endpoint answers any signed-in user and 400s a bad filter`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-aging-400")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/aging-wip").status)
        val bad = listOf("accountId=abc", "connectionId=999999", "teamId=999999", "from=2026-01-01&to=2025-01-01", "sprintId=999999999")
        for (query in bad) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/aging-wip?$query").status, query)
        }
    }
}
