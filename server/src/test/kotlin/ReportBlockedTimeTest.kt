package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.BlockedTimeReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9

/** One DONE item as stored: the columns the blocked-time report reads. */
private data class DoneFact(
    val kind: String,
    val issueId: Long,
    val doneAt: Long,
    val team: UInt?,
    val account: String?,
    val blocked: Double,
    val cycle: Double?,
) {
    val share: Double? get() = cycle?.takeIf { it > 0.0 }?.let { blocked / it }
}

private suspend fun readDoneFacts(connId: UInt): List<DoneFact> = suspendTransaction(sharedDatabaseForTests()) {
    val t = MetricsTables.FactTaskDelivery
    val e = MetricsTables.FactEpicDelivery
    val tasks = t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList().filter { it[t.doneAt] != null }.map {
        DoneFact(
            "TASK", it[t.issueId], it[t.doneAt]!!, it[t.creditTeamId]?.value, it[t.assigneeAccountIdAtDone],
            it[t.blockedWorkingDays].toDouble(), it[t.cycleWorkingDays]?.toDouble(),
        )
    }
    val epics = e.selectAll().where { e.connectionId eq connId }.toList().filter { it[e.doneAt] != null }.map {
        DoneFact(
            "EPIC", it[e.issueId], it[e.doneAt]!!, it[e.ownerTeamId]?.value, null,
            it[e.blockedWorkingDays].toDouble(), it[e.cycleWorkingDays]?.toDouble(),
        )
    }
    tasks + epics
}

/**
 * `GET /api/v1/reports/blocked-time` (v0.3.0 M5 commit 15, Report 12, `.claude/docs/measures.md` "Report 12"). The stub
 * fixture grades every distribution, exclusion bucket, group and the top list against an independent computation over the
 * raw fact rows; the zero blocked time, the never-started and zero-cycle exclusions, the item kinds and the org drill are
 * pinned on hand-built rows in a fresh DISABLED connection with hand-computed answers.
 */
class ReportBlockedTimeTest {

    private suspend fun HttpClient.blocked(query: String): BlockedTimeReport {
        val response = get("/api/v1/reports/blocked-time?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET blocked-time?$query")
        return response.body()
    }

    private fun BlockedTimeReport.assertMatches(label: String, items: List<DoneFact>) {
        val min = meta.minSampleSize
        assertDistribution("$label blocked", items.map { it.blocked }, blockedWorkingDays, min)
        assertDistribution("$label share", items.mapNotNull { it.share }, shareOfCycle, min)
        assertEquals(items.size, excluded.population, "$label population")
        assertEquals(items.count { it.cycle == null }, excluded.neverStarted, "$label neverStarted")
        assertEquals(items.count { it.cycle == 0.0 }, excluded.zeroCycle, "$label zeroCycle")
        assertEquals(items.count { it.blocked > 0.0 }, blockedItems, "$label blockedItems")
        assertEquals(shareOfCycle.n + excluded.neverStarted + excluded.zeroCycle, excluded.population.toLong(), "$label partition")
    }

    private val from = "2024-01-01"
    private val to = "2026-12-31"

    @Test
    fun `the fixture's distributions, exclusions, groups and top items equal an independent computation`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-blocked-fixture")
        val zone = reportZone()
        val bounds = windowBounds(zone, from, to)
        val all = readDoneFacts(connId).filter { it.doneAt >= bounds.first && it.doneAt < bounds.second }
        val tasks = all.filter { it.kind == "TASK" }
        val epics = all.filter { it.kind == "EPIC" }
        val range = "connectionId=$connId&from=$from&to=$to"
        assertTrue(tasks.isNotEmpty() && tasks.any { it.blocked > 0.0 }, "the fixture must carry blocked tasks")

        val byTask = client.blocked(range)
        assertEquals("TASK", byTask.itemKind.name)
        byTask.assertMatches("tasks", tasks)
        client.blocked("$range&itemKind=EPIC").assertMatches("epics", epics)
        val both = client.blocked("$range&itemKind=BOTH")
        both.assertMatches("both", all)
        assertEquals("BOTH", both.itemKind.name)

        // Top items: the most-blocked first, at most 20, each one a real blocked item of the population.
        val expectedTop = tasks.filter { it.blocked > 0.0 }.sortedByDescending { it.blocked }.take(20).map { it.blocked }
        assertEquals(expectedTop.size, byTask.topItems.size)
        byTask.topItems.map { it.blockedWorkingDays }.zip(expectedTop).forEach { (got, want) -> assertEquals(want, got, EPS) }
        assertTrue(byTask.topItems.all { it.issueKey.isNotBlank() && it.itemKind == "TASK" })

        // UNIT groups: one per credit team, Σ n == n for both measures; TEAM groups: per assignee.
        assertTrue(byTask.groups.isNotEmpty() && byTask.groups.all { it.accountId == null })
        assertEquals(byTask.blockedWorkingDays.n, byTask.groups.sumOf { it.blockedWorkingDays.n })
        assertEquals(byTask.shareOfCycle.n, byTask.groups.sumOf { it.shareOfCycle.n })
        for (group in byTask.groups) {
            val rows = tasks.filter { it.team == group.teamId }
            assertDistribution("team ${group.teamId}", rows.map { it.blocked }, group.blockedWorkingDays, byTask.meta.minSampleSize)
            assertEquals(rows.count { it.blocked > 0.0 }, group.blockedItems)
        }
        val teamId = byTask.groups.first { it.teamId != null }.teamId!!
        val team = client.blocked("$range&teamId=$teamId")
        team.assertMatches("team $teamId", tasks.filter { it.team == teamId })
        assertTrue(team.groups.isNotEmpty() && team.groups.all { it.teamId == null })
        assertEquals(team.blockedWorkingDays.n, team.groups.sumOf { it.blockedWorkingDays.n })
        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = client.blocked("$range&teamId=$teamId&accountId=$account")
        assertTrue(user.groups.isEmpty())
        user.assertMatches("user $account", tasks.filter { it.team == teamId && it.account == account })
        client.blocked("$range&teamId=0").assertMatches("UNASSIGNED", tasks.filter { it.team == null })
    }

    /**
     * Hand-computed rows (settings pinned to a minimum sample size of 2), window January 2026. Tasks: t1 blocked 1 of 4
     * (X, acc-1), t2 blocked 0 of 2 (X, acc-1), t3 blocked 3 of 6 (X, acc-2), t4 never started (Y), t5 a zero-working-day cycle (Y),
     * t6 blocked 2 of 8 (Y); never counted: a sub-task, an open task, one done outside the window. Epics: e1 blocked 5 of 10
     * and e2 blocked 0 of 4, both owned by X.
     */
    @Test
    fun `hand-built rows pin the zero blocked time, the exclusions, the item kinds and the drill exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "blocked-hand", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("blocked-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("blocked-y"))
        val s = noonUtc("2026-01-02")
        fun task(
            id: Long,
            done: String?,
            blocked: Double,
            cycle: Double?,
            team: UInt? = null,
            account: String? = null,
            subtask: Boolean = false,
        ) =
            handTask(
                id, if (cycle == null) null else s, done?.let { noonUtc(it) }, creditTeamId = team, account = account, subtask = subtask,
                cycleMs = cycle?.let { 1L }, cycleWorkingDays = cycle,
            ).copy(blockedMs = 1L, blockedWorkingDays = blocked)
        val tasks = listOf(
            task(1, "2026-01-06", 1.0, 4.0, teamX, "acc-1"), task(2, "2026-01-07", 0.0, 2.0, teamX, "acc-1"),
            task(3, "2026-01-08", 3.0, 6.0, teamX, "acc-2"), task(4, "2026-01-15", 0.0, null, teamY),
            task(5, "2026-01-16", 0.0, 0.0, teamY), task(6, "2026-01-28", 2.0, 8.0, teamY),
            task(8, "2026-01-10", 9.0, 9.0, teamX, subtask = true), task(9, null, 9.0, 9.0, teamX),
            task(10, "2025-05-01", 9.0, 9.0, teamX),
        )
        val epics = listOf(
            handEpic(21, s, noonUtc("2026-01-20"), ownerTeamId = teamX)
                .copy(blockedMs = 1L, blockedWorkingDays = 5.0, cycleMs = 1L, cycleWorkingDays = 10.0),
            handEpic(22, s, noonUtc("2026-01-21"), ownerTeamId = teamX)
                .copy(blockedMs = 0L, blockedWorkingDays = 0.0, cycleMs = 1L, cycleWorkingDays = 4.0),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
            store.replaceFactEpicDelivery(connId, epics, configRevision = 1L)
        }
        try {
            val client = seededClient("reports-blocked-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            val metricsSettings = DerivedStubFixture.metricsSettings()
            suspend fun get(q: String) = withMetricsSettings(metricsSettings, { it.copy(minSampleSize = 2) }) { client.blocked(q) }

            val body = get(query)
            assertEquals("TASK", body.itemKind.name)
            assertEquals(6, body.excluded.population)
            assertEquals(1, body.excluded.neverStarted)
            assertEquals(1, body.excluded.zeroCycle)
            assertEquals(3, body.blockedItems)
            assertDistribution("blocked", listOf(1.0, 0.0, 3.0, 0.0, 0.0, 2.0), body.blockedWorkingDays, 2)
            assertDistribution("share", listOf(0.25, 0.0, 0.5, 0.25), body.shareOfCycle, 2)
            assertEquals(1.0, body.blockedWorkingDays.mean!!, EPS)
            assertEquals(4L, body.shareOfCycle.n)
            assertEquals(0.25, body.shareOfCycle.p50!!, EPS)
            // Top items: only blocked ones, most blocked first; a task with no measurable cycle has no share.
            assertEquals(listOf(3.0, 2.0, 1.0), body.topItems.map { it.blockedWorkingDays })
            assertEquals(listOf(0.5, 0.25, 0.25), body.topItems.map { it.share })
            assertEquals(listOf("TASK", "TASK", "TASK"), body.topItems.map { it.itemKind })
            // UNIT drill: X = t1 t2 t3 (share n 3), Y = t4 t5 t6 (share n 1, hidden).
            val x = body.groups.single { it.teamId == teamX }
            assertDistribution("X blocked", listOf(1.0, 0.0, 3.0), x.blockedWorkingDays, 2)
            assertEquals(2, x.blockedItems)
            val y = body.groups.single { it.teamId == teamY }
            assertEquals(1, y.excluded.neverStarted)
            assertEquals(1, y.excluded.zeroCycle)
            assertTrue(y.shareOfCycle.hidden)
            assertEquals(1L, y.shareOfCycle.n)

            // Epics: e1 blocked 5 (share 0.5), e2 blocked 0 (share 0), owner X.
            val epicBody = get("$query&itemKind=EPIC")
            assertEquals(2, epicBody.excluded.population)
            assertDistribution("epic blocked", listOf(5.0, 0.0), epicBody.blockedWorkingDays, 2)
            assertDistribution("epic share", listOf(0.5, 0.0), epicBody.shareOfCycle, 2)
            assertEquals(listOf("EPIC"), epicBody.topItems.map { it.itemKind })
            assertEquals(listOf(teamX), epicBody.groups.map { it.teamId })
            // Both: eight items; the epic tops the list; UNIT groups add the epics to X, TEAM groups cover the tasks only.
            val both = get("$query&itemKind=BOTH")
            assertEquals(8, both.excluded.population)
            assertDistribution("both blocked", listOf(1.0, 0.0, 3.0, 0.0, 0.0, 2.0, 5.0, 0.0), both.blockedWorkingDays, 2)
            assertEquals(listOf("EPIC", "TASK", "TASK", "TASK"), both.topItems.map { it.itemKind })
            assertEquals(5, both.groups.single { it.teamId == teamX }.blockedWorkingDays.n)
            val teamBoth = get("$query&itemKind=BOTH&teamId=$teamX")
            assertEquals(5, teamBoth.excluded.population, "TEAM level totals include the owned epics")
            assertEquals(listOf("acc-1", "acc-2"), teamBoth.groups.mapNotNull { it.accountId }.sorted())
            assertEquals(3L, teamBoth.groups.sumOf { it.blockedWorkingDays.n }, "epics have no user: the user drill is the tasks")
            // USER level: that assignee's tasks, no epics, no groups. teamId=0: no task without a credit team here.
            val user = get("$query&itemKind=BOTH&teamId=$teamX&accountId=acc-1")
            assertEquals(2, user.excluded.population)
            assertTrue(user.groups.isEmpty())
            assertEquals(0, get("$query&teamId=0&itemKind=EPIC").excluded.population)
            assertNull(get("$query&teamId=0").blockedWorkingDays.p50, "an empty read is hidden")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactTaskDelivery(connId)
                store.deleteFactEpicDelivery(connId)
            }
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    @Test
    fun `bad filters are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-blocked-400")
        for (query in listOf("itemKind=NONE", "from=2026-01-01&to=2025-01-01", "accountId=abc", "connectionId=999999", "teamId=999999")) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/blocked-time?$query").status, query)
        }
    }
}
