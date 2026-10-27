package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.reports.PersonTrendReport
import ch.nokillswit.reports.PersonTrendRow
import ch.nokillswit.reports.TREND_ROW_KEYS
import ch.nokillswit.reports.TrendCoverage
import ch.nokillswit.reports.TrendVerdict
import ch.nokillswit.reports.backfillStart
import ch.nokillswit.reports.coverageOf
import ch.nokillswit.reports.derivedCoverage
import ch.nokillswit.reports.resolveConnectionScope
import ch.nokillswit.reports.trendWindowOf
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

/**
 * Report 20, Person trend (`GET /api/v1/reports/person-trend`): THE one-truth gate (every point of every row and every work-mix point
 * equals `GET /compare-people` side A over the point's own window, in the hand-built worlds of Compare people: facts, worklogs and the
 * WIP bridges), the hand-computed anchors and cues, the coverage labels, the 400 matrix, D12 and the grid. The derived-fixture
 * reconciliation (every window of the busiest person, WIP included) is `ReportPersonTrendFixtureTest`.
 */
class ReportPersonTrendTest {

    private fun PersonTrendReport.row(key: String): PersonTrendRow = rows.single { it.key == key }

    private fun PersonTrendRow.values(): List<Double?> = points.map { it.value }

    @Test
    fun `no DERIVE - every point equals Compare people over its window, the mix too, every window is NONE with no cue`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val admin = seededClient("trend-table", FlowUserRole.ADMIN)
            val base = "connectionId=${w.connId}"
            withMinSampleSize(2) {
                val trend = admin.personTrend("$base&from=2026-01-01&to=2026-01-29&accountId=${w.alice}")
                assertEquals(listOf("2026-01-01", "2026-01-15", "2026-01-29"), trend.windows.map { it.end })
                assertEquals(listOf("2025-10-04", "2025-10-18", "2025-11-01"), trend.windows.map { it.from }, "89 days before each end")
                assertEquals(90, trend.windowDays)
                assertEquals(14, trend.stepDays)
                assertEquals(w.alice, trend.person.accountId)
                assertEquals(displayNamesOf(listOf(w.alice)).getValue(w.alice), trend.person.label)
                assertEquals("2026-01-01", trend.meta.from, "meta is the requested range, not the first window")
                assertEquals("2026-01-29", trend.meta.to)
                assertEquals("USER", trend.meta.level.name)
                assertEquals(TREND_ROW_KEYS, trend.rows.map { it.key }, "the fourteen rows in display order")
                assertTrue(trend.rows.all { row -> row.points.size == 3 && row.points.map { it.end } == trend.windows.map { it.end } })

                val count = admin.assertPersonTrendIsComparePeople(trend, w.alice, w.bob, base)
                assertEquals(3, count.windows)
                assertEquals(3 * 14, count.cells)
                assertTrue(count.mixPoints > 0, "the work mix was compared")

                // Nothing is derived for the hand-built connection: every window is NONE, so no row has a cue.
                assertEquals(listOf(TrendCoverage.NONE), trend.windows.map { it.coverage }.distinct())
                assertTrue(trend.rows.all { it.trend == null })
                assertTrue(trend.note!!.startsWith("Not derived yet"), trend.note)

                // The other person, and a slice that applies to every window alike.
                val bob = admin.personTrend("$base&from=2026-01-01&to=2026-01-29&accountId=${w.bob}&activityType=Bug")
                admin.assertPersonTrendIsComparePeople(bob, w.bob, w.alice, "$base&activityType=Bug")
                assertEquals(listOf(0.0, 0.0, 1.0), bob.row("deliveredItems").values(), "bob's one Bug (task 13) is done on the 26th")
            }
        }
    }

    @Test
    fun `hand-computed - the anchors per window, and the cue of every kind once the connection is derived`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            // Derived through June: the January windows are FULL (the connection's backfill starts on 2025-09-01).
            insertSucceededDerive(w.connId, noonUtc("2026-06-01"))
            try {
                val admin = seededClient("trend-cue", FlowUserRole.ADMIN)
                val base = "connectionId=${w.connId}"
                val trend = withMinSampleSize(2) { admin.personTrend("$base&from=2026-01-01&to=2026-01-29&accountId=${w.alice}") }
                assertEquals(listOf(TrendCoverage.FULL), trend.windows.map { it.coverage }.distinct())
                assertNull(trend.note?.takeIf { it.startsWith("Not derived yet") })

                // Alice's tasks: 1 (done Jan 12, wd 4), 2 (Jan 15, wd 6, 3/3, blocked), 3 (Jan 20, wd 2, 1/2), 4 (Jan 22, wd 1, none).
                // Windows end Jan 1 (nothing done), Jan 15 (tasks 1 and 2) and Jan 29 (all four).
                assertEquals(listOf(0.0, 2.0, 4.0), trend.row("deliveredItems").values())
                assertEquals(listOf(0.0, 5.0, 7.0), trend.row("deliveredMd").values())
                assertEquals(listOf(0, 2, 4), trend.row("deliveredItems").points.map { it.n })
                assertEquals(listOf(null, 5.0, 3.0), trend.row("cycleWorkingDaysP50").values(), "[4,6] then [4,6,2,1]")
                assertEquals(listOf(true, false, false), trend.row("cycleWorkingDaysP50").points.map { it.hidden }, "none, then 2 of 2")
                assertEquals(listOf(null, 2.5, 2.0), trend.row("taskSizeP50").values(), "estimate at start [2,3] then [2,3,1]")
                assertEquals(listOf(null, 0.0, 0.0), trend.row("reopenedShare").values(), "no done task, then 0 of 2 and 0 of 4")
                assertEquals(listOf(null, 0.0, 0.25), trend.row("noEstimateShare").values(), "task 4 has no estimate")
                assertEquals(listOf(null, 0.5, 0.25), trend.row("blockedItemsShare").values(), "task 2 is blocked")

                // The cue: first against last of the FULL, shown points.
                val items = assertNotNull(trend.row("deliveredItems").trend)
                assertEquals("2026-01-01", items.fromEnd)
                assertEquals("2026-01-29", items.toEnd)
                assertEquals(TrendVerdict.BETTER, items.verdict, "four items against none: higher is better")
                assertEquals(4.0, items.difference)
                val cycle = assertNotNull(trend.row("cycleWorkingDaysP50").trend)
                assertEquals("2026-01-15", cycle.fromEnd, "the first window is hidden, so the cue starts at the second")
                assertEquals("2026-01-29", cycle.toEnd)
                assertEquals(TrendVerdict.BETTER, cycle.verdict, "5 -> 3 working days: lower is better")
                assertEquals(-2.0, cycle.difference)
                val size = assertNotNull(trend.row("taskSizeP50").trend)
                assertEquals(TrendVerdict.BETTER, size.verdict, "2.5 -> 2.0 MD: smaller is better")
                assertEquals(-0.5, size.difference)
                val reopened = assertNotNull(trend.row("reopenedShare").trend)
                assertEquals("2026-01-15", reopened.fromEnd)
                assertNull(reopened.verdict, "0 -> 0 is a tie")
                assertEquals(0.0, reopened.difference)
                val noEstimate = assertNotNull(trend.row("noEstimateShare").trend)
                assertEquals(TrendVerdict.WORSE, noEstimate.verdict, "0 -> 25 % without an estimate: lower is better")
                assertEquals(0.25, noEstimate.difference)

                withMinSampleSize(2) { admin.assertPersonTrendIsComparePeople(trend, w.alice, w.bob, base) }
                // With a minimum of 3 the 2-sample window is hidden: the cell has no value and drops out of the cue.
                val strict = withMinSampleSize(3) { admin.personTrend("$base&from=2026-01-01&to=2026-01-29&accountId=${w.alice}") }
                assertEquals(listOf(null, null, 3.0), strict.row("cycleWorkingDaysP50").values())
                assertEquals(listOf(true, true, false), strict.row("cycleWorkingDaysP50").points.map { it.hidden })
                assertNull(strict.row("cycleWorkingDaysP50").trend, "one shown point has nothing to be compared with")
                assertNotNull(strict.row("deliveredItems").trend, "a pure count is never hidden")
            } finally {
                deleteDeriveRuns(w.connId)
            }
        }
    }

    @Test
    fun `a person nobody has delivered for is a flat, valueless trend, labelled by the account id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("trend-ghost", FlowUserRole.USER)
        val ghost = "ghost-${System.nanoTime()}"
        val trend = client.personTrend("from=2026-01-01&to=2026-03-01&accountId=$ghost")
        assertEquals(ghost, trend.person.label)
        assertEquals(5, trend.windows.size, "Mar 1, Feb 15, Feb 1, Jan 18, Jan 4")
        // avgWip counts working days (the plan lists days whoever holds nothing); every other row counts the person's own items.
        assertTrue(trend.rows.filter { it.key != "avgWip" }.all { row -> row.points.all { it.n == 0 } }, "no sample anywhere")
        // A pure count of zero is a value (never hidden), so a row with FULL windows has a cue -- of two equal zeros: a tie, no verdict.
        assertTrue(trend.rows.all { it.trend?.verdict == null && (it.trend?.difference ?: 0.0) == 0.0 })
        assertEquals(emptyList(), trend.workMix.activityType)
        assertEquals(emptyList(), trend.workMix.workCategory)
    }

    @Test
    fun `the WIP rows equal Compare people over every window, for both people - the days, the counts, the tasks held and their age`() =
        testApplication {
            usePostgresTestcontainer()
            withCompareWipWorld { w ->
                val admin = seededClient("trend-wip", FlowUserRole.ADMIN)
                val base = "connectionId=${w.connId}"
                withMinSampleSize(1) {
                    var held = 0
                    for ((person, other) in listOf(w.a1 to w.a2, w.a2 to w.a1, w.a3 to w.a1)) {
                        val trend = admin.personTrend("$base&from=2026-01-01&to=2026-01-29&accountId=$person")
                        assertEquals(3, trend.windows.size)
                        assertEquals(listOf(TrendCoverage.FULL), trend.windows.map { it.coverage }.distinct(), "derived in June")
                        admin.assertPersonTrendIsComparePeople(trend, person, other, base)
                        held += trend.row("avgWip").points.count { (it.value ?: 0.0) > 0.0 }
                    }
                    assertTrue(held > 0, "the world holds work in progress, so WIP was really compared ($held)")
                    // A hand-computed anchor: at the end of Jan 15 a2 holds task 3 (BLOCKED since Jan 5) and task 2 (from the
                    // end of Jan 14); both have started (the 5th and the 12th), so both are aged.
                    val a2 = admin.personTrend("$base&from=2026-01-01&to=2026-01-29&accountId=${w.a2}")
                    assertEquals("2026-01-15", a2.windows[1].end)
                    val age = a2.row("wipAtEndMedianAgeWd").points[1]
                    assertEquals(2, age.n, "two tasks held at the end of Jan 15 have started: 2 and 3")
                    assertNotNull(age.value)
                }
            }
        }

    @Test
    fun `coverage follows the last derived day and the backfill start - PARTIAL at either edge, NONE outside, WIP clamped alike`() =
        testApplication {
            usePostgresTestcontainer()
            withCompareWipWorld(backfillFrom = "2025-12-01") { w ->
                // Replace the world's June DERIVE with one on March 4: the daily span reaches that day and no further.
                deleteDeriveRuns(w.connId)
                insertSucceededDerive(w.connId, noonUtc("2026-03-04"))
                val admin = seededClient("trend-cover", FlowUserRole.ADMIN)
                val base = "connectionId=${w.connId}"
                withMinSampleSize(1) {
                    val trend = admin.personTrend("$base&from=2026-02-04&to=2026-03-18&accountId=${w.a1}")
                    assertEquals(listOf("2026-02-04", "2026-02-18", "2026-03-04", "2026-03-18"), trend.windows.map { it.end })
                    assertEquals(
                        listOf(TrendCoverage.PARTIAL, TrendCoverage.PARTIAL, TrendCoverage.FULL, TrendCoverage.PARTIAL),
                        trend.windows.map { it.coverage },
                        "Feb 4/18 start before the Dec 1 backfill; Mar 4 starts Dec 5 and ends on the derived day; Mar 18 is past it",
                    )
                    admin.assertPersonTrendIsComparePeople(trend, w.a1, w.a2, base)
                    // The cue reads FULL points only: a lone FULL point has nothing to be compared with.
                    assertTrue(trend.rows.all { it.trend == null })
                    assertTrue(trend.row("avgWip").points.any { (it.value ?: 0.0) > 0.0 })

                    // Wholly after the derived span, and wholly before the backfill start.
                    val after = admin.personTrend("$base&from=2026-06-10&to=2026-06-10&accountId=${w.a1}")
                    assertEquals(listOf(TrendCoverage.NONE), after.windows.map { it.coverage })
                    admin.assertPersonTrendIsComparePeople(after, w.a1, w.a2, base)
                    val before = admin.personTrend("$base&from=2025-11-15&to=2025-11-29&accountId=${w.a1}")
                    assertEquals(listOf(TrendCoverage.NONE, TrendCoverage.NONE), before.windows.map { it.coverage }, "ends before Dec 1")
                    admin.assertPersonTrendIsComparePeople(before, w.a1, w.a2, base)
                }
            }
        }

    @Test
    fun `the grid - a year by default is 27 points ending today, the 1100-day cap is 79, one more day is 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("trend-grid", FlowUserRole.USER)
        val ghost = "ghost-${System.nanoTime()}"
        val year = client.personTrend("accountId=$ghost")
        assertEquals(27, year.windows.size)
        assertEquals(year.meta.to, year.windows.last().end, "the last point is `to`")
        assertEquals(year.meta.from, year.windows.first().end, "364 days of range is exactly 26 steps: a point on `from`")
        val capped = client.personTrend("accountId=$ghost&from=2023-06-01&to=2026-06-04")
        assertEquals(79, capped.windows.size)
        val tooLong = client.get("/api/v1/reports/person-trend?accountId=$ghost&from=2023-05-31&to=2026-06-04")
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
    }

    @Test
    fun `the 400 matrix - no person, two people, a team or sprint period, a bad range, an unknown connection`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("trend-400")
        val bad = listOf(
            "",
            "accountId=",
            "accountId=a&accountId=b",
            "accountId=a&accountId=a",
            "accountId=a&teamId=1",
            "accountId=a&teamId=0",
            "accountId=a&lastSprints=2",
            "accountId=a&sprintId=5",
            "accountId=a&from=2026-02-01&to=2026-01-01",
            "accountId=a&from=2020-01-01&to=2026-01-01",
            "accountId=a&from=not-a-date",
            "accountId=a&from=2026-01-01&from=2026-01-02",
            "accountId=a&connectionId=999999",
            "accountId=a&domainView=NOPE",
        )
        for (query in bad) {
            val response = client.get("/api/v1/reports/person-trend?$query")
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
        }
    }

    @Test
    fun `any signed-in user may read any person's trend - D12 - and nothing else is in the body`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val plain = seededClient("trend-d12", FlowUserRole.USER)
            val raw = plain.get("/api/v1/reports/person-trend?connectionId=${w.connId}&from=2026-01-01&to=2026-01-29&accountId=${w.alice}")
            assertEquals(HttpStatusCode.OK, raw.status)
            val body = raw.bodyAsText()
            for (capacity in listOf("availableMd", "loggedPerAvailableMd", "deliveredMdPerAvailableMd", "capacity")) {
                assertFalse(capacity in body, "no capacity key ($capacity) for anyone")
            }
            val admin = seededClient("trend-d12-admin", FlowUserRole.ADMIN)
            val query = "connectionId=${w.connId}&from=2026-01-01&to=2026-01-29&accountId=${w.alice}"
            assertEquals(body, admin.get("/api/v1/reports/person-trend?$query").bodyAsText(), "the same for an admin")
        }
    }

    /**
     * Two connections in scope (no `connectionId`): one derived, one never derived, the person delivering in BOTH. The facts of both are
     * read (a connection's DERIVE state does not gate its facts), the cut-off follows the derived one only, and the note names the
     * never-derived one -- all as Compare people does. The days sit in 2020, before every other connection's DERIVE in the suite (the
     * idiom of `ReportWipTest`'s cut-off case), so the derived connection is the oldest and sets the cut-off.
     */
    @Test
    fun `two connections - one derived, one never derived - are both read, the coverage and the note follow the derived one`() =
        testApplication {
            usePostgresTestcontainer()
            val derived = SyncedStubFixture.createConnection(namePrefix = "trend-two-a", enabled = false)
            val never = SyncedStubFixture.createConnection(namePrefix = "trend-two-b", enabled = false)
            val person = "trend-two-${System.nanoTime()}"
            val other = "trend-two-other-${System.nanoTime()}"
            val store = MetricsStore(sharedDatabaseForTests())
            fun task(id: Long, done: String, wd: Double) = handTask(
                id, startedAt = noonUtc(done) - 3 * CMP_DAY_MS, doneAt = noonUtc(done), actualMd = wd, estimateAtStartMd = wd,
                estimateAtDoneMd = wd, account = person, cycleMs = 3 * CMP_DAY_MS, cycleWorkingDays = wd, activeMs = CMP_DAY_MS,
            )
            suspendTransaction(sharedDatabaseForTests()) {
                store.replaceFactTaskDelivery(derived, listOf(task(1, "2020-01-08", 2.0), task(2, "2020-01-12", 4.0)), 1L)
                store.replaceFactTaskDelivery(never, listOf(task(1, "2020-01-10", 3.0), task(2, "2020-01-25", 5.0)), 1L)
            }
            insertSucceededDerive(derived, noonUtc("2020-01-20"))
            try {
                val admin = seededClient("trend-two", FlowUserRole.ADMIN)
                val calendar = DerivedStubFixture.metricsSettings().readCalendar()
                withMinSampleSize(1) {
                    val trend = admin.personTrend("from=2020-01-01&to=2020-01-29&accountId=$person")
                    assertEquals(listOf("2020-01-01", "2020-01-15", "2020-01-29"), trend.windows.map { it.end })
                    admin.assertPersonTrendIsComparePeople(trend, person, other, "tailPercent=0")

                    // Both connections' tasks count, the never-derived one's too: derived 8th and 12th, never-derived 10th and 25th.
                    assertEquals(listOf(0.0, 3.0, 4.0), trend.row("deliveredItems").values())
                    assertEquals(listOf(0.0, 9.0, 14.0), trend.row("deliveredMd").values(), "2 + 3 + 4, then + 5")

                    // The cut-off is the DERIVED connection's day and the never-derived one is reported, not counted ...
                    val (coverage, backfill) = suspendTransaction(sharedDatabaseForTests()) {
                        val ids = resolveConnectionScope(null)
                        val found = derivedCoverage(ids, calendar)
                        found to backfillStart(ids - found.notDerived.toSet())
                    }
                    assertEquals(LocalDate.parse("2020-01-20"), coverage.day)
                    assertTrue(never in coverage.notDerived)
                    assertTrue(derived !in coverage.notDerived)
                    // ... so the labels are the coverage rule on exactly that day and the latest backfill start of the derived connections.
                    assertEquals(
                        trend.windows.map { coverageOf(trendWindowOf(LocalDate.parse(it.end), calendar), coverage.day, backfill?.date) },
                        trend.windows.map { it.coverage },
                    )
                    assertTrue(assertNotNull(trend.note).contains(never.toString()), "the never-derived connection is named: ${trend.note}")
                    // Compare people over the last window names it too.
                    val compare = admin.comparePeopleReport("from=2019-10-31&to=2020-01-29&accountId=$person&accountId=$other")
                    assertTrue(assertNotNull(compare.note).contains(never.toString()), compare.note)
                }
            } finally {
                suspendTransaction(sharedDatabaseForTests()) {
                    store.deleteFactTaskDelivery(derived)
                    store.deleteFactTaskDelivery(never)
                }
                deleteDeriveRuns(derived)
            }
        }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
