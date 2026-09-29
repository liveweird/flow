package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.EPIC_ACCURACY_MAX_ROWS
import ch.nokillswit.reports.EpicEstimationAccuracyReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the report must answer for [epics] (DONE epics in scope), computed independently from the raw fact rows. */
private data class ExpectedEpicAccuracy(
    val atStart: List<Double>,
    val atDone: List<Double>,
    val population: Int,
    val noActual: Int,
    val neverStarted: Int,
    val unestimatedAtStart: Int,
    val unestimatedAtDone: Int,
)

private fun expectedEpicAccuracy(epics: List<EpicFact>): ExpectedEpicAccuracy {
    val worked = epics.filter { it.actual.signum() > 0 }
    val started = worked.filter { it.startedAt != null }
    val atStart = started.filter { it.ownStart != null && it.ownStart.signum() > 0 }.map { it.actual.toDouble() / it.ownStart!!.toDouble() }
    val atDone = worked.filter { it.ownDone != null && it.ownDone.signum() > 0 }.map { it.actual.toDouble() / it.ownDone!!.toDouble() }
    return ExpectedEpicAccuracy(
        atStart = atStart,
        atDone = atDone,
        population = epics.size,
        noActual = epics.size - worked.size,
        neverStarted = worked.size - started.size,
        unestimatedAtStart = started.size - atStart.size,
        unestimatedAtDone = worked.size - atDone.size,
    )
}

/**
 * `GET /api/v1/reports/epic-estimation-accuracy` (v0.3.0 M4 commit 12, Report 4, `.claude/docs/measures.md`
 * "Reports 3, 4, 5"). The stub fixture's epics grade the distributions and the listing against an
 * INDEPENDENT read of `fact_epic_delivery`; the exclusion buckets, the owner-team groups, the 200-row cap and
 * the empty USER level are pinned exactly on hand-built epics in a fresh DISABLED connection.
 */
class ReportEpicEstimationAccuracyTest {

    private suspend fun HttpClient.epicAccuracy(query: String): EpicEstimationAccuracyReport {
        val response = get("/api/v1/reports/epic-estimation-accuracy?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET epic-estimation-accuracy?$query")
        return response.body()
    }

    private fun EpicEstimationAccuracyReport.assertMatches(label: String, expected: ExpectedEpicAccuracy) {
        assertDistribution("$label atStart", expected.atStart, atStart, meta.minSampleSize)
        assertDistribution("$label atDone", expected.atDone, atDone, meta.minSampleSize)
        assertEquals(expected.population, excluded.population, "$label population")
        assertEquals(expected.noActual, excluded.noActual, "$label noActual")
        assertEquals(expected.neverStarted, excluded.neverStarted, "$label neverStarted")
        assertEquals(expected.unestimatedAtStart, excluded.unestimatedAtStart, "$label unestimatedAtStart")
        assertEquals(expected.unestimatedAtDone, excluded.unestimatedAtDone, "$label unestimatedAtDone")
        assertEquals(excluded.population.toLong(), atStart.n + excluded.noActual + excluded.neverStarted + excluded.unestimatedAtStart)
        assertEquals(excluded.population.toLong(), atDone.n + excluded.noActual + excluded.unestimatedAtDone)
    }

    private suspend fun doneInWindow(connId: UInt, from: String, to: String): List<EpicFact> {
        val bounds = windowBounds(reportZone(), from, to)
        return readEpicFacts(connId).filter { it.doneAt != null && it.doneAt >= bounds.first && it.doneAt < bounds.second }
    }

    @Test
    fun `distributions, exclusion counts and the epic listing equal an independent computation`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-epic-accuracy-unit")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val epics = doneInWindow(connId, "2024-01-01", "2026-12-31")
        assertTrue(epics.isNotEmpty(), "the stub fixture must carry DONE epics")
        val expected = expectedEpicAccuracy(epics)
        assertTrue(expected.atStart.isNotEmpty() && expected.atDone.isNotEmpty(), "the stub epics must yield ratios in both views")

        val body = client.epicAccuracy(range)
        body.assertMatches("whole fixture", expected)
        assertEquals("EPIC", body.meta.domainView.name, "epic accuracy defaults to the EPIC domain view")
        assertEquals(epics.size.coerceAtMost(EPIC_ACCURACY_MAX_ROWS), body.epics.size)
        assertEquals(epics.size > EPIC_ACCURACY_MAX_ROWS, body.epicsTruncated)
        assertTrue(body.epics.zipWithNext().all { (a, b) -> a.doneAt >= b.doneAt }, "epics are newest doneAt first")
        assertTrue(body.epics.all { it.issueKey.isNotBlank() })
        // Every listed epic's own figures and ratios equal the raw facts; the ratio is null exactly when excluded.
        val byDone = epics.groupBy { it.doneAt }
        for (row in body.epics) {
            val fact = byDone.getValue(row.doneAt)
                .single { it.actual.toDouble() == row.actualMd && it.childSum.toDouble() == row.childSumMd }
            assertEquals(fact.ownStart?.toDouble(), row.ownEstimateAtStartMd)
            assertEquals(fact.ownDone?.toDouble(), row.ownEstimateAtDoneMd)
            val start = fact.ownStart
            val estimated = fact.actual.signum() > 0 && start != null && start.signum() > 0
            val wantRatio = if (estimated) fact.actual.toDouble() / start.toDouble() else null
            if (wantRatio == null) assertNull(row.ratio) else assertEquals(wantRatio, row.ratio!!, 1e-9)
        }
        assertEquals(expected.atStart.size, body.epics.count { it.ratio != null })
        assertEquals(expected.atDone.size, body.epics.count { it.ratioAtDone != null })

        val narrow = doneInWindow(connId, "2025-11-01", "2026-12-31")
        client.epicAccuracy("connectionId=$connId&from=2025-11-01&to=2026-12-31")
            .assertMatches("narrow window", expectedEpicAccuracy(narrow))
    }

    @Test
    fun `owner-team groups sum to the totals, TEAM narrows without groups and USER level is empty`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-epic-accuracy-groups")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val epics = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val unit = client.epicAccuracy(range)
        assertTrue(unit.groups.isNotEmpty())
        assertEquals(unit.atStart.n, unit.groups.sumOf { it.atStart.n })
        assertEquals(unit.atDone.n, unit.groups.sumOf { it.atDone.n })
        assertEquals(unit.excluded.population, unit.groups.sumOf { it.excluded.population })
        for (group in unit.groups) {
            val expected = expectedEpicAccuracy(epics.filter { it.owner == group.teamId })
            assertDistribution("owner ${group.teamId} atStart", expected.atStart, group.atStart, unit.meta.minSampleSize)
            assertEquals(expected.noActual, group.excluded.noActual)
        }

        val owner = unit.groups.mapNotNull { it.teamId }.firstOrNull()
        assertNotNull(owner, "the FLO epics' domain owner is the FLO board's team (A19)")
        val team = client.epicAccuracy("$range&teamId=$owner")
        team.assertMatches("owner $owner", expectedEpicAccuracy(epics.filter { it.owner == owner }))
        assertTrue(team.groups.isEmpty(), "epics carry no user, so TEAM level has no groups")
        assertEquals("TEAM", team.meta.level.name)

        // teamId=0 selects the UNOWNED epics.
        val unowned = epics.filter { it.owner == null }
        client.epicAccuracy("$range&teamId=0").assertMatches("UNOWNED", expectedEpicAccuracy(unowned))

        // USER level: epics are not attributed to users — empty, never a silently-team-wide answer.
        val user = client.epicAccuracy("$range&teamId=$owner&accountId=someone")
        assertEquals(0, user.excluded.population)
        assertTrue(user.epics.isEmpty() && user.groups.isEmpty())
        assertEquals(0, user.atStart.n)
        assertEquals("USER", user.meta.level.name)
    }

    @Test
    fun `domain and work category slices use the epic's own space and category`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-epic-accuracy-slices")
        val range = "connectionId=$connId&from=2024-01-01&to=2026-12-31"
        val epics = doneInWindow(connId, "2024-01-01", "2026-12-31")

        val domain = epics.mapNotNull { it.domainKey }.groupingBy { it }.eachCount().maxBy { it.value }.key
        val expected = epics.filter { it.domainKey == domain }
        client.epicAccuracy("$range&domain=$domain").assertMatches("domain $domain", expectedEpicAccuracy(expected))
        // Either domain view is the epic's own space, and the view is echoed.
        val taskView = client.epicAccuracy("$range&domain=$domain&domainView=TASK")
        taskView.assertMatches("domain $domain, TASK view", expectedEpicAccuracy(expected))
        assertEquals("TASK", taskView.meta.domainView.name)
        // activityType is not an epic attribute: ignored, never a filter.
        client.epicAccuracy("$range&activityType=Nothing").assertMatches("activityType ignored", expectedEpicAccuracy(epics))

        val categorized = epics.mapNotNull { it.workCategory }
        if (categorized.isNotEmpty()) {
            val category = categorized.first()
            client.epicAccuracy("$range&workCategory=$category")
                .assertMatches("category $category", expectedEpicAccuracy(epics.filter { it.workCategory == category }))
        }
        client.epicAccuracy("$range&workCategory=UNCATEGORIZED")
            .assertMatches("UNCATEGORIZED", expectedEpicAccuracy(epics.filter { it.workCategory == null }))
    }

    /**
     * Hand-computed buckets and groups on hand-built epics (settings pinned to a minimum sample size of 2), plus the
     * 200-row cap on a second connection of 205 epics. Six DONE epics in the window; an open epic and one done
     * outside the window never count.
     */
    @Test
    fun `hand-built epics pin the exclusion buckets, the owner groups and the row cap exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "epic-accuracy", enabled = false)
        val capConnId = SyncedStubFixture.createConnection(namePrefix = "epic-accuracy-cap", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamA = TestTeams.seed(SyncedStubFixture.unique("epic-owner-a"))
        val teamB = TestTeams.seed(SyncedStubFixture.unique("epic-owner-b"))
        val started = noonUtc("2026-01-05")
        val rows = listOf(
            handEpic(
                1, started, noonUtc("2026-01-20"),
                ownStart = 10.0, ownDone = 12.0, ownCurrent = 12.0, childSum = 20.0, actualMd = 15.0, ownerTeamId = teamA,
            ),
            handEpic(
                2, started, noonUtc("2026-01-19"),
                ownStart = 8.0, ownDone = 8.0, ownCurrent = 8.0, actualMd = 4.0, ownerTeamId = teamA,
            ),
            handEpic(3, started, noonUtc("2026-01-18"), ownDone = 6.0, ownCurrent = 6.0, actualMd = 3.0), // unestimatedAtStart, UNOWNED
            // noActual
            handEpic(
                4, started, noonUtc("2026-01-17"),
                ownStart = 5.0, ownDone = 5.0, ownCurrent = 5.0, actualMd = 0.0, ownerTeamId = teamB,
            ),
            handEpic(5, started, noonUtc("2026-01-16"), actualMd = 0.0, ownerTeamId = teamB), // noActual wins over unestimated
            handEpic(6, started, noonUtc("2026-01-15"), childSum = 9.0, actualMd = 2.0), // unestimated both, UNOWNED
            // never started (no started_at) but worked: neverStarted for atStart; atDone 0.5, UNOWNED
            handEpic(9, null, noonUtc("2026-01-14"), ownDone = 4.0, ownCurrent = 4.0, actualMd = 2.0),
            handEpic(7, started, null, ownStart = 1.0, ownCurrent = 1.0, actualMd = 1.0, ownerTeamId = teamA), // open
            handEpic(
                8, started, noonUtc("2025-06-15"),
                ownStart = 1.0, ownDone = 1.0, ownCurrent = 1.0, actualMd = 1.0, ownerTeamId = teamA,
            ),
        )
        val dims = rows.map { DimEpicRow(it.issueId, "EP-${it.issueId}", "Epic ${it.issueId}", "AAA", null, "DONE", null, null) }
        val capRows = (1L..205L).map {
            handEpic(it, started, noonUtc("2026-01-10") + it * 60_000, ownStart = 1.0, ownDone = 1.0, ownCurrent = 1.0, actualMd = 1.0)
        }
        val capDims = capRows.map { DimEpicRow(it.issueId, "CAP-${it.issueId}", null, "AAA", null, "DONE", null, null) }
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactEpicDelivery(connId, rows, configRevision = 1L)
            store.insertEpics(connId, dims, configRevision = 1L)
            store.replaceFactEpicDelivery(capConnId, capRows, configRevision = 1L)
            store.insertEpics(capConnId, capDims, configRevision = 1L)
        }
        try {
            val client = seededClient("reports-epic-accuracy-hand-built")
            val query = "connectionId=$connId&from=2026-01-01&to=2026-01-31"

            val body = withMinSampleSize(2) { client.epicAccuracy(query) }
            assertEquals(7, body.excluded.population)
            assertEquals(2, body.excluded.noActual)
            assertEquals(1, body.excluded.neverStarted)
            assertEquals(2, body.excluded.unestimatedAtStart)
            assertEquals(1, body.excluded.unestimatedAtDone)
            assertDistribution("atStart", listOf(1.5, 0.5), body.atStart, 2)
            assertDistribution("atDone", listOf(1.25, 0.5, 0.5, 0.5), body.atDone, 2)
            assertEquals(1.0, body.atStart.mean!!, 1e-9) // (1.5 + 0.5) / 2
            assertEquals(1.0, body.atStart.p50!!, 1e-9)
            assertEquals(1.4, body.atStart.p90!!, 1e-9) // 0.5 + 0.9 * (1.5 - 0.5)

            // The listing: newest done first, own vs child estimates side by side, null ratios when excluded.
            assertEquals(listOf("EP-1", "EP-2", "EP-3", "EP-4", "EP-5", "EP-6", "EP-9"), body.epics.map { it.issueKey })
            assertEquals("Epic 1", body.epics[0].summary)
            assertEquals(1.5, body.epics[0].ratio!!, 1e-9)
            assertEquals(1.25, body.epics[0].ratioAtDone!!, 1e-9)
            assertEquals(20.0, body.epics[0].childSumMd)
            assertEquals(10.0, body.epics[0].ownEstimateAtStartMd)
            assertNull(body.epics[2].ratio, "EP-3 has no own estimate at start")
            assertEquals(0.5, body.epics[2].ratioAtDone!!, 1e-9)
            assertNull(body.epics[3].ratio, "EP-4 has no actual cost")
            assertNull(body.epics[3].ratioAtDone)
            assertNull(body.epics[5].ratio)
            assertNull(body.epics[5].ratioAtDone)
            assertEquals(9.0, body.epics[5].childSumMd, "the child sum is shown even when the epic is excluded")
            assertTrue(!body.epicsTruncated)

            // Owner groups: A = EP-1, EP-2; B = EP-4, EP-5; UNOWNED = EP-3, EP-6.
            val groups = withMinSampleSize(2) { client.epicAccuracy(query).groups }
            assertEquals(3, groups.size)
            val a = groups.single { it.teamId == teamA }
            assertEquals(2, a.excluded.population)
            assertEquals(2, a.atStart.n)
            assertTrue(!a.atStart.hidden)
            val b = groups.single { it.teamId == teamB }
            assertEquals(2, b.excluded.noActual)
            assertEquals(0, b.atStart.n)
            val unowned = groups.single { it.teamId == null }
            assertNull(unowned.label)
            assertEquals(3, unowned.excluded.population)
            assertEquals(1, unowned.excluded.neverStarted)
            assertEquals(2, unowned.atDone.n)
            assertEquals(0, unowned.atStart.n)
            assertTrue(unowned.atStart.hidden, "no epic reached atStart: below a minimum sample size of 2")

            // Sliced to team A only.
            val teamOnly = withMinSampleSize(2) { client.epicAccuracy("$query&teamId=$teamA") }
            assertEquals(2, teamOnly.excluded.population)
            assertEquals(listOf("EP-1", "EP-2"), teamOnly.epics.map { it.issueKey })
            assertTrue(teamOnly.groups.isEmpty())

            // The cap: 205 DONE epics, the 200 newest are listed, the distribution still counts all 205.
            val capped = client.epicAccuracy("connectionId=$capConnId&from=2026-01-01&to=2026-01-31")
            assertEquals(EPIC_ACCURACY_MAX_ROWS, capped.epics.size)
            assertTrue(capped.epicsTruncated)
            assertEquals(205, capped.excluded.population)
            assertEquals(205, capped.atStart.n)
            assertEquals("CAP-205", capped.epics.first().issueKey, "newest doneAt first")
            assertTrue(capped.epics.none { it.issueKey in setOf("CAP-1", "CAP-5") }, "the oldest five are cut")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                for (id in listOf(connId, capConnId)) {
                    store.deleteFactEpicDelivery(id)
                    store.deleteDims(id)
                }
            }
            cleanUpTeams(listOf(teamA, teamB))
        }
    }

    @Test
    fun `bad filters are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-epic-accuracy-400")

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/epic-estimation-accuracy?from=2026-01-01&to=2025-01-01").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/epic-estimation-accuracy?accountId=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/epic-estimation-accuracy?connectionId=999999").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/epic-estimation-accuracy?lastSprints=0").status)
    }
}
