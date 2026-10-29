package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.BlockedShareExcluded
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
import org.junit.BeforeClass

private const val EPS = 1e-9

/** One DONE item as stored: the columns the blocked-time report reads. */
internal data class DoneFact(
    val kind: String,
    val issueId: Long,
    val doneAt: Long,
    val team: UInt?,
    val account: String?,
    val blocked: Double,
    val cycle: Double?,
    val domain: String? = null,
) {
    val share: Double? get() = cycle?.takeIf { it > 0.0 }?.let { blocked / it }
}

/** The independent re-derivation of the report's rule: the items blocked at all (blocked working days above zero). */
internal fun List<DoneFact>.blockedOnly(): List<DoneFact> = filter { it.blocked > 0.0 }

internal suspend fun readDoneFacts(connId: UInt): List<DoneFact> = suspendTransaction(sharedDatabaseForTests()) {
    val t = MetricsTables.FactTaskDelivery
    val e = MetricsTables.FactEpicDelivery
    val tasks = t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList().filter { it[t.doneAt] != null }.map {
        DoneFact(
            "TASK", it[t.issueId], it[t.doneAt]!!, it[t.creditTeamId]?.value, it[t.assigneeAccountIdAtDone],
            it[t.blockedWorkingDays].toDouble(), it[t.cycleWorkingDays]?.toDouble(), it[t.domainKey],
        )
    }
    val epics = e.selectAll().where { e.connectionId eq connId }.toList().filter { it[e.doneAt] != null }.map {
        DoneFact(
            "EPIC", it[e.issueId], it[e.doneAt]!!, it[e.ownerTeamId]?.value, null,
            it[e.blockedWorkingDays].toDouble(), it[e.cycleWorkingDays]?.toDouble(), it[e.domainKey],
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
        val blocked = items.blockedOnly()
        assertDistribution("$label blocked", blocked.map { it.blocked }, blockedWorkingDays, min)
        assertDistribution("$label share", blocked.mapNotNull { it.share }, shareOfCycle, min)
        assertEquals(items.size, excluded.population, "$label population")
        assertEquals(items.count { it.blocked == 0.0 }, excluded.neverBlocked, "$label neverBlocked")
        assertEquals(blocked.count { it.cycle == null }, excluded.neverStarted, "$label neverStarted")
        assertEquals(blocked.count { it.cycle == 0.0 }, excluded.zeroCycle, "$label zeroCycle")
        assertEquals(blocked.size, blockedItems, "$label blockedItems")
        assertEquals(excluded.population - excluded.neverBlocked, blockedItems, "$label blockedItems = population - neverBlocked")
        assertEquals(blockedWorkingDays.n + excluded.neverBlocked, excluded.population.toLong(), "$label blocked-days partition")
        assertEquals(
            shareOfCycle.n + excluded.neverBlocked + excluded.neverStarted + excluded.zeroCycle,
            excluded.population.toLong(),
            "$label share partition",
        )
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
        val inWindow = readDoneFacts(connId).filter { it.doneAt >= bounds.first && it.doneAt < bounds.second }
        // UNIT level is the FLO domain read (a `domain` with no `teamId`); the team reads below are graded against the whole window.
        val all = inWindow.filter { it.domain == DerivedStubFixture.DOMAIN }
        val tasks = all.filter { it.kind == "TASK" }
        val epics = all.filter { it.kind == "EPIC" }
        val teamTasks = inWindow.filter { it.kind == "TASK" }
        val range = "connectionId=$connId&from=$from&to=$to&domain=${DerivedStubFixture.DOMAIN}"
        val teamRange = "connectionId=$connId&from=$from&to=$to"
        assertTrue(tasks.isNotEmpty() && tasks.any { it.blocked > 0.0 }, "the fixture must carry blocked tasks")
        assertTrue(tasks.any { it.blocked == 0.0 }, "the fixture must carry never-blocked tasks, the ones the histograms leave out")

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
        assertEquals(byTask.excluded.neverBlocked, byTask.groups.sumOf { it.excluded.neverBlocked })
        for (group in byTask.groups) {
            val rows = tasks.filter { it.team == group.teamId }
            val min = byTask.meta.minSampleSize
            assertDistribution("team ${group.teamId} blocked", rows.blockedOnly().map { it.blocked }, group.blockedWorkingDays, min)
            assertDistribution("team ${group.teamId} share", rows.blockedOnly().mapNotNull { it.share }, group.shareOfCycle, min)
            assertEquals(rows.count { it.blocked > 0.0 }, group.blockedItems)
            assertEquals(rows.count { it.blocked == 0.0 }, group.excluded.neverBlocked)
            assertEquals(group.excluded.population.toLong(), group.blockedWorkingDays.n + group.excluded.neverBlocked)
        }
        val teamId = byTask.groups.first { it.teamId != null }.teamId!!
        val team = client.blocked("$teamRange&teamId=$teamId")
        team.assertMatches("team $teamId", teamTasks.filter { it.team == teamId })
        assertTrue(team.groups.isNotEmpty() && team.groups.all { it.teamId == null })
        assertEquals(team.blockedWorkingDays.n, team.groups.sumOf { it.blockedWorkingDays.n })
        val account = team.groups.mapNotNull { it.accountId }.first()
        val user = client.blocked("$teamRange&teamId=$teamId&accountId=$account")
        assertTrue(user.groups.isEmpty())
        user.assertMatches("user $account", teamTasks.filter { it.team == teamId && it.account == account })
        client.blocked("$teamRange&teamId=0").assertMatches("UNASSIGNED", teamTasks.filter { it.team == null })
    }

    /**
     * Hand-computed rows (settings pinned to a minimum sample size of 2), window January 2026. Tasks: t1 blocked 1 of 4
     * (X, acc-1), t2 blocked 0 of 2 (X, acc-1), t3 blocked 3 of 6 (X, acc-2), t4 never started and never blocked (Y), t5 a
     * zero-working-day cycle and never blocked (Y), t6 blocked 2 of 8 (Y), t11 blocked 1 with no cycle and t12 blocked 1.5
     * with a zero-working-day cycle (both Y; impossible after a DERIVE, they pin the defensive `neverStarted`/`zeroCycle`
     * buckets of the BLOCKED items); never counted: a sub-task, an open task, one done outside the window. Epics, all owned by
     * X: e1 blocked 5 of 10, e2 blocked 0 of 4, e3 blocked 2 of 8.
     */
    @Test
    fun `hand-built rows pin the never-blocked exclusion, the other exclusions, the item kinds and the drill exactly`() = testApplication {
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
            task(11, "2026-01-29", 1.0, null, teamY), task(12, "2026-01-30", 1.5, 0.0, teamY),
            task(8, "2026-01-10", 9.0, 9.0, teamX, subtask = true), task(9, null, 9.0, 9.0, teamX),
            task(10, "2025-05-01", 9.0, 9.0, teamX),
        )
        val epics = listOf(
            handEpic(21, s, noonUtc("2026-01-20"), ownerTeamId = teamX)
                .copy(blockedMs = 1L, blockedWorkingDays = 5.0, cycleMs = 1L, cycleWorkingDays = 10.0),
            handEpic(22, s, noonUtc("2026-01-21"), ownerTeamId = teamX)
                .copy(blockedMs = 0L, blockedWorkingDays = 0.0, cycleMs = 1L, cycleWorkingDays = 4.0),
            handEpic(23, s, noonUtc("2026-01-22"), ownerTeamId = teamX)
                .copy(blockedMs = 1L, blockedWorkingDays = 2.0, cycleMs = 1L, cycleWorkingDays = 8.0),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
            store.replaceFactEpicDelivery(connId, epics, configRevision = 1L)
        }
        try {
            val client = seededClient("reports-blocked-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            val unit = "$query&domain=AAA"
            val metricsSettings = DerivedStubFixture.metricsSettings()
            suspend fun get(q: String) = withMetricsSettings(metricsSettings, { it.copy(minSampleSize = 2) }) { client.blocked(q) }

            // Tasks: 8 in the window; t2, t4, t5 were never blocked (out of BOTH histograms); of the five blocked ones t11 has
            // no cycle and t12 a zero cycle (out of the share only).
            val body = get(unit)
            assertEquals("TASK", body.itemKind.name)
            assertEquals(8, body.excluded.population)
            assertEquals(3, body.excluded.neverBlocked)
            assertEquals(1, body.excluded.neverStarted)
            assertEquals(1, body.excluded.zeroCycle)
            assertEquals(5, body.blockedItems)
            assertDistribution("blocked", listOf(1.0, 3.0, 2.0, 1.0, 1.5), body.blockedWorkingDays, 2)
            assertDistribution("share", listOf(0.25, 0.5, 0.25), body.shareOfCycle, 2)
            assertEquals(5L, body.blockedWorkingDays.n)
            assertEquals(1.7, body.blockedWorkingDays.mean!!, EPS)
            assertEquals(1.0, body.blockedWorkingDays.min!!, EPS)
            assertEquals(3L, body.shareOfCycle.n)
            assertEquals(0.25, body.shareOfCycle.p50!!, EPS)
            // The two identities: 5 + 3 == 8 and 3 + 3 + 1 + 1 == 8.
            assertEquals(8L, body.blockedWorkingDays.n + body.excluded.neverBlocked)
            assertEquals(
                8L,
                body.shareOfCycle.n + body.excluded.neverBlocked + body.excluded.neverStarted + body.excluded.zeroCycle,
            )
            // Top items: only blocked ones, most blocked first, the newest first among equals (t11 before t1); no cycle = no share.
            assertEquals(listOf(3.0, 2.0, 1.5, 1.0, 1.0), body.topItems.map { it.blockedWorkingDays })
            assertEquals(listOf(0.5, 0.25, null, null, 0.25), body.topItems.map { it.share })
            assertEquals(List(5) { "TASK" }, body.topItems.map { it.itemKind })
            // UNIT drill: X = t1 t2 t3 (blocked n 2, share n 2, one never blocked), Y = t4 t5 t6 t11 t12 (blocked n 3, share n 1 hidden).
            val x = body.groups.single { it.teamId == teamX }
            assertDistribution("X blocked", listOf(1.0, 3.0), x.blockedWorkingDays, 2)
            assertDistribution("X share", listOf(0.25, 0.5), x.shareOfCycle, 2)
            assertEquals(2, x.blockedItems)
            assertEquals(BlockedShareExcluded(population = 3, neverStarted = 0, zeroCycle = 0, neverBlocked = 1), x.excluded)
            val y = body.groups.single { it.teamId == teamY }
            assertEquals(BlockedShareExcluded(population = 5, neverStarted = 1, zeroCycle = 1, neverBlocked = 2), y.excluded)
            assertDistribution("Y blocked", listOf(2.0, 1.0, 1.5), y.blockedWorkingDays, 2)
            assertEquals(3, y.blockedItems)
            assertTrue(y.shareOfCycle.hidden)
            assertEquals(1L, y.shareOfCycle.n)
            assertEquals(body.excluded.neverBlocked, body.groups.sumOf { it.excluded.neverBlocked })

            // Epics: e1 blocked 5 (share 0.5), e3 blocked 2 (share 0.25), e2 never blocked; owner X.
            val epicBody = get("$unit&itemKind=EPIC")
            assertEquals(3, epicBody.excluded.population)
            assertEquals(1, epicBody.excluded.neverBlocked)
            assertEquals(2, epicBody.blockedItems)
            assertDistribution("epic blocked", listOf(5.0, 2.0), epicBody.blockedWorkingDays, 2)
            assertDistribution("epic share", listOf(0.5, 0.25), epicBody.shareOfCycle, 2)
            assertEquals(listOf("EPIC", "EPIC"), epicBody.topItems.map { it.itemKind })
            assertEquals(listOf(teamX), epicBody.groups.map { it.teamId })
            // Both: eleven items, four never blocked; e1 tops the list; UNIT groups add the epics to X, TEAM groups cover the tasks only.
            val both = get("$unit&itemKind=BOTH")
            assertEquals(11, both.excluded.population)
            assertEquals(4, both.excluded.neverBlocked)
            assertDistribution("both blocked", listOf(1.0, 3.0, 2.0, 1.0, 1.5, 5.0, 2.0), both.blockedWorkingDays, 2)
            assertEquals(
                listOf("EPIC", "TASK", "TASK", "EPIC", "TASK", "TASK", "TASK"),
                both.topItems.map { it.itemKind },
            )
            assertEquals(4, both.groups.single { it.teamId == teamX }.blockedWorkingDays.n)
            val teamBoth = get("$query&itemKind=BOTH&teamId=$teamX")
            assertEquals(6, teamBoth.excluded.population, "TEAM level totals include the owned epics")
            assertEquals(listOf("acc-1", "acc-2"), teamBoth.groups.mapNotNull { it.accountId }.sorted())
            assertEquals(2L, teamBoth.groups.sumOf { it.blockedWorkingDays.n }, "epics have no user: the user drill is the tasks")
            // USER level: that assignee's tasks, no epics, no groups. teamId=0: no task without a credit team here.
            val user = get("$query&itemKind=BOTH&teamId=$teamX&accountId=acc-1")
            assertEquals(2, user.excluded.population)
            assertEquals(1, user.excluded.neverBlocked)
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
        val bad = listOf(
            "itemKind=NONE&domain=FLO", "from=2026-01-01&to=2025-01-01&domain=FLO", "accountId=abc&domain=FLO",
            "connectionId=999999&domain=FLO", "teamId=999999",
        )
        for (query in bad) {
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/blocked-time?$query").status, query)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
