package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.CycleTimeReport
import ch.nokillswit.reports.EpicCycleTime
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPIC_DAY_MS = 86_400_000L

private data class ExpectedEpicCycle(val elapsed: List<Double>, val working: List<Double>, val population: Int, val neverStarted: Int)

/** Measurable = started (`cycle_ms` set); the only exclusion is an epic that never started — the task rule, over the epic facts. */
private fun expectedEpicCycle(epics: List<EpicFact>): ExpectedEpicCycle {
    val measurable = epics.filter { it.cycleMs != null && it.cycleWorkingDays != null }
    return ExpectedEpicCycle(
        elapsed = measurable.map { it.cycleMs!!.toDouble() / EPIC_DAY_MS },
        working = measurable.map { it.cycleWorkingDays!!.toDouble() },
        population = epics.size,
        neverStarted = epics.size - measurable.size,
    )
}

/**
 * The `epics` block of `GET /api/v1/reports/cycle-time` (BACKLOG "Report 7: an `epics` block", `.claude/docs/measures.md`
 * "Reports 7, 8", "Epic cycle time"). The stub fixture grades every distribution and owner group against an INDEPENDENT
 * read of `fact_epic_delivery`; the exclusion, the zero-working-day cycle, the hidden state, the owner groups and the
 * empty USER level are pinned exactly on hand-built epics in a fresh DISABLED connection. The task half of the report
 * is `ReportCycleTimeTest`.
 */
class ReportCycleTimeEpicsTest {

    private suspend fun HttpClient.cycleTime(query: String): CycleTimeReport {
        val response = get("/api/v1/reports/cycle-time?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cycle-time?$query")
        return response.body()
    }

    private fun EpicCycleTime.assertMatches(label: String, minSample: Int, expected: ExpectedEpicCycle) {
        assertDistribution("$label elapsedDays", expected.elapsed, elapsedDays, minSample)
        assertDistribution("$label workingDays", expected.working, workingDays, minSample)
        assertEquals(expected.population, excluded.population, "$label population")
        assertEquals(expected.neverStarted, excluded.neverStarted, "$label neverStarted")
        assertEquals((excluded.population - excluded.neverStarted).toLong(), workingDays.n, "$label n == population - neverStarted")
        assertEquals(elapsedDays.n, workingDays.n, "$label both views share one partition")
    }

    private suspend fun epicsDoneIn(connId: UInt, bounds: Pair<Long, Long>): List<EpicFact> =
        readEpicFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }

    private suspend fun doneInWindow(connId: UInt, from: String, to: String): List<EpicFact> =
        epicsDoneIn(connId, windowBounds(reportZone(), from, to))

    @Test
    fun `the fixture's epic distributions and exclusion equal an independent computation over fact_epic_delivery`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cycle-time-epics-unit")

        for ((from, to) in listOf("2024-01-01" to "2026-12-31", "2025-11-01" to "2026-12-31")) {
            val epics = doneInWindow(connId, from, to)
            val expected = expectedEpicCycle(epics)
            val body = client.cycleTime("connectionId=$connId&from=$from&to=$to")
            body.epics.assertMatches("$from..$to", body.meta.minSampleSize, expected)
            // The epic cycle is the same done_at − started_at rule as a task's, stored as cycle_ms.
            assertTrue(
                epics.all { it.startedAt == null || it.cycleMs == it.doneAt!! - it.startedAt },
                "an epic's cycle_ms is done_at - started_at",
            )
            if (from == "2024-01-01") assertTrue(expected.working.isNotEmpty(), "the stub fixture must carry DONE epics with a cycle")
        }

        // The sprint-relative period reads the resolved sprints' envelope, as the task view does.
        val sprint = client.cycleTime("connectionId=$connId&lastSprints=52")
        val sprintIds = sprint.meta.resolvedSprints.flatMap { it.sprintIds }
        assertTrue(sprintIds.isNotEmpty())
        val bounds = suspendTransaction(sharedDatabaseForTests()) {
            val d = MetricsTables.DimSprint
            val rows = d.selectAll().where { (d.connectionId eq connId) and (d.sprintId inList sprintIds) }.toList()
            rows.minOf { it[d.startAt] ?: it[d.completeAt]!! } to rows.maxOf { it[d.completeAt]!! } + 1
        }
        val inEnvelope = epicsDoneIn(connId, bounds)
        assertTrue(inEnvelope.isNotEmpty(), "the lastSprints=52 envelope must hold DONE epics, or this pins nothing")
        sprint.epics.assertMatches("lastSprints=52 envelope", sprint.meta.minSampleSize, expectedEpicCycle(inEnvelope))
    }

    @Test
    fun `owner-team groups sum to the totals, TEAM narrows to the owner without groups and USER level is empty`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cycle-time-epics-groups")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val epics = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val unitReport = client.cycleTime(range)
        val unit = unitReport.epics
        val min = unitReport.meta.minSampleSize
        assertTrue(unit.groups.isNotEmpty())
        assertEquals(unit.workingDays.n, unit.groups.sumOf { it.workingDays.n }, "Σ group n")
        assertEquals(unit.excluded.population, unit.groups.sumOf { it.excluded.population })
        assertEquals(unit.excluded.neverStarted, unit.groups.sumOf { it.excluded.neverStarted })
        for (group in unit.groups) {
            val expected = expectedEpicCycle(epics.filter { it.owner == group.teamId })
            assertDistribution("owner ${group.teamId} working", expected.working, group.workingDays, min)
            assertDistribution("owner ${group.teamId} elapsed", expected.elapsed, group.elapsedDays, min)
            assertEquals(expected.population, group.excluded.population)
            assertEquals(expected.neverStarted, group.excluded.neverStarted)
        }
        val labels = unit.groups.map { it.label }
        assertEquals(labels.sortedWith(nullsLast(naturalOrder())), labels, "groups are ordered by label, null last")

        val owner = unit.groups.mapNotNull { it.teamId }.firstOrNull { id -> unit.groups.single { it.teamId == id }.workingDays.n > 0 }
        assertTrue(owner != null, "the FLO epics' domain owner is the FLO board's team (A19)")
        val team = client.cycleTime("$range&teamId=$owner")
        team.epics.assertMatches("owner $owner", min, expectedEpicCycle(epics.filter { it.owner == owner }))
        assertTrue(team.epics.groups.isEmpty(), "epics carry no user, so TEAM level has no groups")
        assertEquals("TEAM", team.meta.level.name)

        // teamId=0 selects the UNOWNED epics.
        client.cycleTime("$range&teamId=0").epics.assertMatches("UNOWNED", min, expectedEpicCycle(epics.filter { it.owner == null }))

        // USER level: epics are not attributed to users — an all-zero block, never a silently team-wide answer.
        val user = client.cycleTime("$range&teamId=$owner&accountId=someone")
        assertEquals("USER", user.meta.level.name)
        assertEquals(0, user.epics.excluded.population)
        assertEquals(0, user.epics.excluded.neverStarted)
        assertEquals(0, user.epics.workingDays.n)
        assertEquals(0, user.epics.elapsedDays.n)
        assertTrue(user.epics.groups.isEmpty())
    }

    @Test
    fun `domain and work category slice the epics by their own space, activityType is ignored`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cycle-time-epics-slices")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val epics = doneInWindow(connId, "2024-01-01", "2026-12-31")
        val min = client.cycleTime(range).meta.minSampleSize

        val domain = epics.mapNotNull { it.domainKey }.groupingBy { it }.eachCount().maxBy { it.value }.key
        val domainEpics = epics.filter { it.domainKey == domain }
        client.cycleTime("$range&domain=$domain").epics.assertMatches("domain $domain", min, expectedEpicCycle(domainEpics))
        // The domain VIEW is a task attribute: the epic's own space answers under either view.
        client.cycleTime("$range&domain=$domain&domainView=EPIC").epics
            .assertMatches("domain $domain, EPIC view", min, expectedEpicCycle(domainEpics))
        // activityType is not an epic attribute: ignored, never a filter.
        client.cycleTime("$range&activityType=Nothing").epics.assertMatches("activityType ignored", min, expectedEpicCycle(epics))
        client.cycleTime("$range&workCategory=UNCATEGORIZED").epics
            .assertMatches("UNCATEGORIZED", min, expectedEpicCycle(epics.filter { it.workCategory == null }))
    }

    /**
     * Hand-computed epic cycle times on hand-built rows (settings pinned to a minimum sample size of 2). Window: January
     * 2026. Six DONE epics — owner A: E1, E2, E6 (a cycle of zero working days stays in); owner B: E3; UNOWNED: E4 and E5
     * (never started, excluded) — plus an open epic and one done outside the window, which never count.
     */
    @Test
    fun `hand-built epics pin the exclusion, a zero-working-day cycle, the owner groups and levels exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "cycle-time-epics", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamA = TestTeams.seed(SyncedStubFixture.unique("cycle-epic-a"))
        val teamB = TestTeams.seed(SyncedStubFixture.unique("cycle-epic-b"))
        val s = noonUtc("2026-01-02")
        val rows = listOf(
            handEpic(1, s, noonUtc("2026-01-20"), cycleMs = 10 * EPIC_DAY_MS, cycleWorkingDays = 8.0, ownerTeamId = teamA),
            handEpic(2, s, noonUtc("2026-01-21"), cycleMs = 20 * EPIC_DAY_MS, cycleWorkingDays = 14.0, ownerTeamId = teamA),
            handEpic(3, s, noonUtc("2026-01-15"), cycleMs = 6 * EPIC_DAY_MS, cycleWorkingDays = 4.0, ownerTeamId = teamB),
            handEpic(4, s, noonUtc("2026-01-16"), cycleMs = 2 * EPIC_DAY_MS, cycleWorkingDays = 2.0, domain = "BBB", category = "Ops"),
            handEpic(5, null, noonUtc("2026-01-14"), domain = "BBB"), // never started: excluded, no cycle
            handEpic(6, s, noonUtc("2026-01-08"), cycleMs = EPIC_DAY_MS, cycleWorkingDays = 0.0, ownerTeamId = teamA),
            // never counted:
            handEpic(7, s, null, ownerTeamId = teamA), // open
            handEpic(
                8, noonUtc("2025-05-01"), noonUtc("2025-06-01"),
                cycleMs = 30 * EPIC_DAY_MS, cycleWorkingDays = 20.0, ownerTeamId = teamA,
            ),
        )
        suspendTransaction(sharedDatabaseForTests()) { store.replaceFactEpicDelivery(connId, rows, configRevision = 1L) }
        try {
            val client = seededClient("reports-cycle-time-epics-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
            val body = withMinSampleSize(2) { client.cycleTime(query) }.epics

            assertEquals(6, body.excluded.population)
            assertEquals(1, body.excluded.neverStarted)
            assertDistribution("working", listOf(8.0, 14.0, 4.0, 2.0, 0.0), body.workingDays, 2)
            assertDistribution("elapsed", listOf(10.0, 20.0, 6.0, 2.0, 1.0), body.elapsedDays, 2)
            assertEquals(5.6, body.workingDays.mean!!, 1e-9) // 0 2 4 8 14
            assertEquals(4.0, body.workingDays.p50!!, 1e-9)
            assertEquals(11.6, body.workingDays.p90!!, 1e-9) // rank 3.6: 8 + 0.6 * (14 - 8)
            assertEquals(7.8, body.elapsedDays.mean!!, 1e-9) // 1 2 6 10 20

            // Owner groups: A = E1 E2 E6; B = E3; UNOWNED = E4 E5 (one never started).
            assertEquals(3, body.groups.size)
            val a = body.groups.single { it.teamId == teamA }
            assertEquals(3, a.workingDays.n)
            assertEquals(3, a.excluded.population)
            assertEquals(22.0 / 3, a.workingDays.mean!!, 1e-9)
            assertEquals(8.0, a.workingDays.p50!!, 1e-9)
            assertEquals(1.0, a.elapsedDays.min!!, 1e-9, "the zero-working-day epic stays in")
            val b = body.groups.single { it.teamId == teamB }
            assertEquals(1, b.workingDays.n)
            assertTrue(b.workingDays.hidden, "one epic is below a minimum sample size of 2")
            assertEquals(null, b.workingDays.p50)
            val unowned = body.groups.single { it.teamId == null }
            assertEquals(null, unowned.label)
            assertEquals(2, unowned.excluded.population)
            assertEquals(1, unowned.excluded.neverStarted)
            assertEquals(1, unowned.workingDays.n)
            assertTrue(unowned.workingDays.hidden)

            // TEAM level: owner A's three epics, no groups; teamId=0 is the two UNOWNED epics.
            val team = withMinSampleSize(2) { client.cycleTime("$query&teamId=$teamA") }.epics
            assertEquals(3, team.excluded.population)
            assertEquals(3, team.workingDays.n)
            assertTrue(team.groups.isEmpty())
            val unownedOnly = withMinSampleSize(2) { client.cycleTime("$query&teamId=0") }.epics
            assertEquals(2, unownedOnly.excluded.population)
            assertEquals(1, unownedOnly.excluded.neverStarted)

            // USER level is an all-zero block even though the owner team has epics.
            val user = withMinSampleSize(2) { client.cycleTime("$query&teamId=$teamA&accountId=acc-1") }.epics
            assertEquals(0, user.excluded.population)
            assertEquals(0, user.workingDays.n)
            assertTrue(user.groups.isEmpty())

            // Slices: the epic's own domain and work category; the neverStarted epic stays in its domain.
            val domain = withMinSampleSize(2) { client.cycleTime("$query&domain=BBB") }.epics
            assertEquals(2, domain.excluded.population)
            assertEquals(1, domain.excluded.neverStarted)
            val category = withMinSampleSize(2) { client.cycleTime("$query&workCategory=Ops") }.epics
            assertEquals(1, category.excluded.population)
            assertEquals(1, category.workingDays.n)
            assertEquals(5, withMinSampleSize(2) { client.cycleTime("$query&workCategory=UNCATEGORIZED") }.epics.excluded.population)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) { store.deleteFactEpicDelivery(connId) }
            cleanUpTeams(listOf(teamA, teamB))
        }
    }
}
