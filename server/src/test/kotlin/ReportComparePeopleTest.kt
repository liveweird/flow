package ch.nokillswit

import ch.nokillswit.reports.CapacityGrant
import ch.nokillswit.reports.CompareCell
import ch.nokillswit.reports.CompareDirection
import ch.nokillswit.reports.CompareRow
import ch.nokillswit.reports.CompareSide
import ch.nokillswit.reports.ComparePeopleReport
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.comparePeople
import ch.nokillswit.reports.parseComparePeople
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.testApplication
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOL = 1e-6

/** One expected cell: value, n (hidden = a distribution-shaped figure under the minimum sample size). */
private data class Want(val value: Double?, val n: Int, val hidden: Boolean = false)

/** One expected row: both cells and the verdict (`better`, `difference` = B − A, `margin`). */
private class WantRow(val a: Want, val b: Want, val better: CompareSide? = null, val difference: Double? = null, val margin: Double? = null)

private fun close(label: String, want: Double?, got: Double?) {
    if (want == null) {
        assertNull(got, label)
    } else {
        assertNotNull(got, label)
        assertTrue(abs(want - got) < TOL, "$label: expected $want but was $got")
    }
}

private fun assertCell(label: String, want: Want, got: CompareCell) {
    close("$label value", want.value, got.value)
    assertEquals(want.n, got.n, "$label n")
    assertEquals(want.hidden, got.hidden, "$label hidden")
}

private fun assertRow(key: String, want: WantRow, got: CompareRow) {
    assertCell("$key A", want.a, got.a)
    assertCell("$key B", want.b, got.b)
    assertEquals(want.better, got.better, "$key better")
    close("$key difference", want.difference, got.difference)
    close("$key margin", want.margin, got.margin)
}

/**
 * Report 18, Compare people (`GET /api/v1/reports/compare-people`): the hand-computed table of the hand-built world
 * (`ComparePeopleFixtures.kt` -- two people in two teams, one of them moving team mid-range), the hidden/no-cue rule, the 400
 * matrix, who sees the per-person capacity (A39) and D12. The derived-fixture reconciliation is `ReportComparePeopleFixtureTest`.
 */
class ReportComparePeopleTest {

    private suspend fun HttpClient.compare(query: String): ComparePeopleReport {
        val response = get("/api/v1/reports/compare-people?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET compare-people?$query: ${response.bodyAsText()}")
        return response.body()
    }

    private fun ComparePeopleReport.row(key: String): CompareRow = rows.single { it.key == key }

    /** Every row of the world at a minimum sample size of 2, hand-computed from the fixture's table (see the file header). */
    private val expected: Map<String, WantRow> = mapOf(
        "deliveredMd" to WantRow(Want(7.0, 4), Want(8.0, 4), CompareSide.B, 1.0, 1.0),
        "deliveredItems" to WantRow(Want(4.0, 4), Want(4.0, 4), difference = 0.0),
        // Estimate at start of the started, estimated tasks: alice [2,3,1] -> 2, bob [1,4,2] -> 2 (task 4 has none, task 14 never started).
        "taskSizeP50" to WantRow(Want(2.0, 3), Want(2.0, 3), difference = 0.0),
        // Reopened at least once: alice 0 of 4, bob tasks 11 and 12 = 2 of 4.
        "reopenedShare" to WantRow(Want(0.0, 4), Want(0.5, 4), CompareSide.A, 0.5, 0.5),
        // 20 working days each: 7/20 and 8/20; 4/20 twice.
        "deliveredMdPerAvailableMd" to WantRow(Want(0.35, 4), Want(0.4, 4), CompareSide.B, 0.05, 0.05),
        "deliveredItemsPerAvailableMd" to WantRow(Want(0.2, 4), Want(0.2, 4), difference = 0.0),
        // Working days alice [4,6,2,1], bob [5,8,3] (the never-started 14 has no cycle): p50 3 / 5, p90 5.4 / 7.4.
        "cycleWorkingDaysP50" to WantRow(Want(3.0, 4), Want(5.0, 3), CompareSide.A, 2.0, 2.0),
        "cycleWorkingDaysP90" to WantRow(Want(5.4, 4), Want(7.4, 3), CompareSide.A, 2.0, 2.0),
        // Elapsed days alice [6,8,3,2], bob [7,10,4]: p50 4.5 / 7, p90 7.4 / 9.4.
        "cycleElapsedDaysP50" to WantRow(Want(4.5, 4), Want(7.0, 3), CompareSide.A, 2.5, 2.5),
        "cycleElapsedDaysP90" to WantRow(Want(7.4, 4), Want(9.4, 3), CompareSide.A, 2.0, 2.0),
        // Active / elapsed: alice [.5,.25,1,.5] -> .5; bob [2/7,.5,.25] -> 2/7.
        "flowEfficiencyP50" to WantRow(Want(0.5, 4), Want(2.0 / 7.0, 3), CompareSide.A, 2.0 / 7.0 - 0.5, 0.5 - 2.0 / 7.0),
        // Logged / working days: alice [.5,.75,1,1] -> .875; bob [.2,1/3,.5] -> 1/3; the target 1.0: alice is 0.125 away, bob 2/3.
        "reportedTimeRatioP50" to WantRow(Want(0.875, 4), Want(1.0 / 3.0, 3), CompareSide.A, 1.0 / 3.0 - 0.875, (2.0 / 3.0) - 0.125),
        // PRED(25): at start alice [1,1.5,2] -> 1/3, bob [1,1,.5] -> 2/3; at done alice [1,1.5,1] -> 2/3, bob [1,1,1] -> 1.
        "accuracyAtStartPred25" to WantRow(Want(1.0 / 3.0, 3), Want(2.0 / 3.0, 3), CompareSide.B, 1.0 / 3.0, 1.0 / 3.0),
        "accuracyAtDonePred25" to WantRow(Want(2.0 / 3.0, 3), Want(1.0, 3), CompareSide.B, 1.0 / 3.0, 1.0 / 3.0),
        // Started in January: alice 1,2,3,4 + the open 5 (current assignee) = 5, changed 3 and 5; bob 11,12,13 = 3, changed 13.
        "adjustedShare" to WantRow(Want(0.4, 5), Want(1.0 / 3.0, 3), difference = 1.0 / 3.0 - 0.4),
        // One changed done item each (alice 3: +100%, bob 13: -50%): under the minimum of 2.
        "adjustmentMedianChange" to WantRow(Want(null, 1, hidden = true), Want(null, 1, hidden = true)),
        // Nothing derived for the hand-built connection: no WIP series, so no figure (not a zero).
        "avgWip" to WantRow(Want(null, 0), Want(null, 0)),
        "wipAtEndItems" to WantRow(Want(null, 0), Want(null, 0)),
        "wipAtEndMedianAgeWd" to WantRow(Want(null, 0), Want(null, 0)),
        "blockedItemsShare" to WantRow(Want(0.25, 4), Want(0.5, 4), CompareSide.A, 0.25, 0.25),
        // Σ blocked / Σ cycle over the items with a cycle: alice 1.5/13, bob 3/16.
        "blockedShareOfCycle" to WantRow(
            Want(1.5 / 13.0, 4), Want(3.0 / 16.0, 3), CompareSide.A, 3.0 / 16.0 - 1.5 / 13.0, 3.0 / 16.0 - 1.5 / 13.0,
        ),
        "loggedMd" to WantRow(Want(4.0, 3), Want(4.25, 4), difference = 0.25),
        "availableMd" to WantRow(Want(20.0, 20), Want(20.0, 20), difference = 0.0),
        // Logged / available: 4/20 and 4.25/20 against the target 1.0.
        "loggedPerAvailableMd" to WantRow(Want(0.2, 3), Want(0.2125, 4), CompareSide.B, 0.0125, 0.0125),
        "foreignShare" to WantRow(Want(0.5, 3), Want(1.0 / 4.25, 4), difference = 1.0 / 4.25 - 0.5),
        "worklogCoverage" to WantRow(Want(1.0, 4), Want(0.75, 4), CompareSide.A, -0.25, 0.25),
        // Worklogs with a known creation time: alice 2 (1 over a day), bob 4 (3 over a day).
        "lateLoggingShare" to WantRow(Want(0.5, 2), Want(0.75, 4), CompareSide.A, 0.25, 0.25),
        "noEstimateShare" to WantRow(Want(0.25, 4), Want(0.0, 4), CompareSide.B, -0.25, 0.25),
    )

    @Test
    fun `every row of two people in two teams - one a mover - is the hand-computed figure, with its verdict`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val admin = seededClient("cmp-table-admin", FlowUserRole.ADMIN)
            val report = withMinSampleSize(2) { admin.compare(w.query()) }

            assertEquals("USER", report.meta.level.name)
            assertEquals("2026-01-01", report.meta.from)
            assertEquals("2026-01-31", report.meta.to)
            assertEquals(listOf(w.alice, w.bob), report.people.map { it.accountId })
            val names = displayNamesOf(listOf(w.alice, w.bob))
            assertEquals(listOf(names.getValue(w.alice), names.getValue(w.bob)), report.people.map { it.label })
            assertEquals(expected.keys.toList(), report.rows.map { it.key }, "every row, in table order")
            for ((key, want) in expected) assertRow(key, want, report.row(key))

            // How each row reads: the contract of the table.
            assertEquals(CompareDirection.TARGET, report.row("reportedTimeRatioP50").direction)
            assertEquals(1.0, report.row("reportedTimeRatioP50").target)
            assertEquals(CompareDirection.TARGET, report.row("loggedPerAvailableMd").direction)
            assertEquals(CompareDirection.NEUTRAL, report.row("adjustedShare").direction)
            assertEquals(CompareDirection.LOWER, report.row("avgWip").direction)
            assertEquals(CompareDirection.LOWER, report.row("taskSizeP50").direction)
            assertEquals(CompareDirection.LOWER, report.row("reopenedShare").direction)
            assertEquals("TASK_SIZE", report.row("taskSizeP50").drill?.name)
            assertEquals("REOPENED", report.row("reopenedShare").drill?.name)
            assertNull(report.row("availableMd").drill)
            assertEquals("DELIVERED", report.row("deliveredMd").drill?.name)
            assertEquals(0.005, report.row("deliveredMd").tolerance)
            assertEquals(0.0, report.row("deliveredItems").tolerance)

            // The wip block names what was (not) read; the hand-built connection has no DERIVE run.
            assertEquals(0, report.wipDays.workingDays)
            assertNull(report.wipDays.firstDay)
            assertTrue(report.note!!.startsWith("Not derived yet"), report.note)
            assertEquals(31, report.capacity!!.days, "every January day is before the real clock")
        }
    }

    @Test
    fun `the cycle-time histograms are the union of the two people with a per-person split, the work mix groups both`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val admin = seededClient("cmp-hist-admin", FlowUserRole.ADMIN)
            val report = withMinSampleSize(2) { admin.compare(w.query()) }
            val names = displayNamesOf(listOf(w.alice, w.bob))
            fun raw(alice: List<Double>, bob: List<Double>) = listOf(
                Triple(w.alice, names.getValue(w.alice), alice), Triple(w.bob, names.getValue(w.bob), bob),
            )
            assertDistribution("working", listOf(4.0, 6.0, 2.0, 1.0, 5.0, 8.0, 3.0), report.cycleTime.workingDays, 2)
            assertDistribution("elapsed", listOf(6.0, 8.0, 3.0, 2.0, 7.0, 10.0, 4.0), report.cycleTime.elapsedDays, 2)
            assertSharesInUnionEdges("working", raw(listOf(4.0, 6.0, 2.0, 1.0), listOf(5.0, 8.0, 3.0)), report.cycleTime.workingDays)
            assertSharesInUnionEdges("elapsed", raw(listOf(6.0, 8.0, 3.0, 2.0), listOf(7.0, 10.0, 4.0)), report.cycleTime.elapsedDays)

            // Delivered md by key: alice (md 7, items 4) / bob (md 8, items 4); shares of each person's own total.
            val mix = report.workMix
            assertEquals(listOf("Story", "Bug"), mix.activityType.map { it.key })
            val story = mix.activityType.first()
            assertEquals(5.0, story.a.md); assertEquals(3, story.a.items); close("story share A", 5.0 / 7.0, story.a.share)
            assertEquals(7.0, story.b.md); assertEquals(3, story.b.items); close("story share B", 7.0 / 8.0, story.b.share)
            assertEquals(listOf("UNCATEGORIZED", "Ops"), mix.workCategory.map { it.key })
            val ops = mix.workCategory.last()
            assertEquals(3.0, ops.a.md); assertEquals(0.0, ops.b.md); assertEquals(0, ops.b.items); close("ops share B", 0.0, ops.b.share)
            assertEquals(listOf("AAA", "BBB"), mix.domain.map { it.key })
            assertEquals(4.0, mix.domain.last().b.md, "bob's task 12 is in domain BBB")
            assertEquals(0.0, mix.domain.last().a.md)
            for (list in listOf(mix.activityType, mix.workCategory, mix.domain)) {
                assertEquals(7.0, list.sumOf { it.a.md }, 1e-9)
                assertEquals(8.0, list.sumOf { it.b.md }, 1e-9)
            }
        }
    }

    @Test
    fun `a side under the minimum sample size shows no value and no cue, and the slices apply to both people`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val admin = seededClient("cmp-hidden-admin", FlowUserRole.ADMIN)
            // Minimum 4: bob has 3 measured cycles (hidden), alice 4 (shown); every 3-sample figure is withheld.
            val report = withMinSampleSize(4) { admin.compare(w.query()) }
            val working = report.row("cycleWorkingDaysP50")
            assertCell("alice working p50", Want(3.0, 4), working.a)
            assertCell("bob working p50", Want(null, 3, hidden = true), working.b)
            assertNull(working.better); assertNull(working.difference); assertNull(working.margin)
            assertNull(report.row("accuracyAtStartPred25").better)
            assertTrue(report.row("accuracyAtStartPred25").a.hidden && report.row("accuracyAtStartPred25").b.hidden)
            // Task size is a distribution: 3 measured tasks a side, so both are withheld and there is no cue.
            assertCell("alice taskSizeP50", Want(null, 3, hidden = true), report.row("taskSizeP50").a)
            assertCell("bob taskSizeP50", Want(null, 3, hidden = true), report.row("taskSizeP50").b)
            assertNull(report.row("taskSizeP50").better)
            // Counts are never hidden; they still get a cue when both sides clear the minimum (4 items each: a tie here).
            assertNull(report.row("deliveredItems").better)
            assertEquals(CompareSide.B, report.row("deliveredMd").better)
            // loggedMd has alice 3 worklogs: under 4, so a neutral row shows its values and difference but nothing more.
            assertEquals(0.25, report.row("loggedMd").difference)
            // A row whose side is under the minimum gets no cue although both values exist: alice's 3 worklogs vs bob's 4.
            assertNull(report.row("loggedPerAvailableMd").better)
            assertNotNull(report.row("loggedPerAvailableMd").difference)
            // The reopened share is a plain share (never hidden) but sample-gated: 4 done tasks a side clears 4, not 5.
            assertEquals(CompareSide.A, report.row("reopenedShare").better)
            val strict = withMinSampleSize(5) { admin.compare(w.query()) }.row("reopenedShare")
            assertEquals(0.5, strict.b.value); assertFalse(strict.b.hidden)
            assertNull(strict.better); assertEquals(0.5, strict.difference)

            // The slices apply to both people alike: only the Bug tasks.
            val bugs = withMinSampleSize(1) { admin.compare(w.query("activityType=Bug")) }
            assertEquals(2.0, bugs.row("deliveredMd").a.value) // alice's task 3
            assertEquals(1.0, bugs.row("deliveredMd").b.value) // bob's task 13
            assertEquals(1, bugs.row("deliveredItems").a.n)
            assertEquals(CompareSide.A, bugs.row("deliveredMd").better)
            // Task size is LOWER-is-better: alice's Bug (task 3) was estimated 1 at start, bob's (task 13) 2.
            assertCell("bug taskSizeP50 A", Want(1.0, 1), bugs.row("taskSizeP50").a)
            assertCell("bug taskSizeP50 B", Want(2.0, 1), bugs.row("taskSizeP50").b)
            assertEquals(CompareSide.A, bugs.row("taskSizeP50").better)
        }
    }

    @Test
    fun `a mover's capacity is summed across both teams with the transfer day counted once`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val admin = seededClient("cmp-mover-admin", FlowUserRole.ADMIN)
            val report = withMinSampleSize(2) { admin.compare(w.query()) }
            // Bob: team 1 until noon of the 15th (Jan 2, 5, 7, 8, 9, 12, 13, 14, 15 = 9 working days), team 2 from then on
            // (Jan 15, 16, 19-23, 26-30 = 12); the 15th is covered by both memberships and counts once: 20, not 21.
            assertEquals(20.0, report.row("availableMd").b.value)
            assertEquals(20.0, report.row("availableMd").a.value, "alice: all 20 working days of January (the 1st and 6th are holidays)")
            // A window inside team 2's stretch: only bob's team-2 days (Jan 19-23).
            val laterQuery = w.query().replace("to=2026-01-31", "to=2026-01-23").replace("from=2026-01-01", "from=2026-01-19")
            val later = withMinSampleSize(2) { admin.compare(laterQuery) }
            assertEquals(5.0, later.row("availableMd").b.value)
            assertEquals(5.0, later.row("availableMd").a.value)
        }
    }

    @Test
    fun `the report re-checks the route's team set against the memberships it reads - a team outside it drops the capacity`() =
        testApplication {
            usePostgresTestcontainer()
            startApplication()
            withCompareWorld { w ->
                val service = application.attributes[ReportServiceKey]
                val calendar = DerivedStubFixture.metricsSettings().readCalendar()
                val now = System.currentTimeMillis()
                val params = parametersOf(
                    "connectionId" to listOf(w.connId.toString()), "from" to listOf("2026-01-01"), "to" to listOf("2026-01-31"),
                    "accountId" to listOf(w.alice, w.bob),
                )
                val filter = params.parseComparePeople(calendar, now)
                withMinSampleSize(2) {
                    assertNotNull(service.comparePeople(filter, now, CapacityGrant.Admin).capacity)
                    val both = service.comparePeople(filter, now, CapacityGrant.Teams(setOf(w.t1, w.t2)))
                    assertNotNull(both.capacity)
                    assertEquals(28, both.rows.size)
                    // Bob also belongs to team 2: a route that checked only team 1 (a membership appeared since) gets no capacity at all.
                    val stale = service.comparePeople(filter, now, CapacityGrant.Teams(setOf(w.t1)))
                    assertNull(stale.capacity)
                    assertEquals(24, stale.rows.size)
                    assertNull(service.comparePeople(filter, now, CapacityGrant.None).capacity)
                    assertNull(service.comparePeople(filter, now, CapacityGrant.Teams(emptySet())).capacity)
                }
            }
        }

    @Test
    fun `the 400 matrix - the wrong number of people, a team or sprint period, a bad range, an unknown connection`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cmp-400")
        val two = "accountId=a&accountId=b"
        val bad = listOf(
            "",
            "accountId=a",
            "accountId=a&accountId=a",
            "accountId=a&accountId=b&accountId=c",
            "$two&teamId=1",
            "$two&teamId=0",
            "$two&lastSprints=2",
            "$two&sprintId=5",
            "$two&from=2026-02-01&to=2026-01-01",
            "$two&from=2020-01-01&to=2026-01-01",
            "$two&from=not-a-date",
            "$two&connectionId=999999",
            "$two&tailPercent=26",
            "$two&domainView=NOPE",
        )
        for (query in bad) {
            val response = client.get("/api/v1/reports/compare-people?$query")
            assertEquals(HttpStatusCode.BadRequest, response.status, query)
        }
    }

    @Test
    fun `capacity is shown only to an admin or the manager of every team either person belonged to - all or nothing`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val admin = seededClient("cmp-vis-admin", FlowUserRole.ADMIN)
            val bothEmail = uniqueEmail("cmp-vis-mgr-both")
            val bothId = TestUsers.seed(email = bothEmail, password = "pw", role = FlowUserRole.USER, name = "both")
            val oneEmail = uniqueEmail("cmp-vis-mgr-one")
            val oneId = TestUsers.seed(email = oneEmail, password = "pw", role = FlowUserRole.USER, name = "one")
            val managerClient = authedClient(bothEmail, "pw")
            val otherClient = authedClient(oneEmail, "pw")
            val plain = seededClient("cmp-vis-plain", FlowUserRole.USER)

            suspend fun setManager(team: UInt, name: String, userId: UInt?) {
                val request = TeamUpdateRequest(name = name, managerUserId = userId)
                assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/teams/$team", request).status)
            }
            setManager(w.t1, w.t1Name, bothId)
            setManager(w.t2, w.t2Name, bothId)
            withMinSampleSize(2) {
                for ((who, client) in listOf("admin" to admin, "the manager of both teams" to managerClient)) {
                    val report = client.compare(w.query())
                    assertNotNull(report.capacity, who)
                    assertEquals(28, report.rows.size, "$who: all rows")
                    assertEquals(20.0, report.row("availableMd").a.value, who)
                }
                // Team 1 now has another manager: bob also belonged to team 2, so neither manager is enough on their own.
                setManager(w.t1, w.t1Name, oneId)
                val withoutCapacity = listOf(
                    "the manager of team 1" to otherClient, "the manager of team 2" to managerClient, "a user" to plain,
                )
                for ((who, client) in withoutCapacity) {
                    val raw = client.get("/api/v1/reports/compare-people?${w.query()}")
                    assertEquals(HttpStatusCode.OK, raw.status, who)
                    val body = raw.bodyAsText()
                    assertFalse("capacity" in body, "$who: no capacity key")
                    val capacityRows = listOf(
                        "availableMd", "loggedPerAvailableMd", "deliveredMdPerAvailableMd", "deliveredItemsPerAvailableMd",
                    )
                    for (key in capacityRows) {
                        assertFalse("\"$key\"" in body, "$who: row $key absent")
                    }
                    val report = client.compare(w.query())
                    assertNull(report.capacity, who)
                    assertEquals(24, report.rows.size, "$who: the other rows are the same for everyone")
                    assertEquals(7.0, report.row("deliveredMd").a.value, who)
                }
                // Alice alone is a team-1 member only: the manager of team 1 manages every team she belonged to.
                val ghost = "ghost-${System.nanoTime()}"
                val aliceOnly = otherClient.compare("from=2026-01-01&to=2026-01-31&accountId=${w.alice}&accountId=$ghost")
                assertNotNull(aliceOnly.capacity, "a pair whose only team is the manager's")
                assertEquals(20.0, aliceOnly.row("availableMd").a.value)
            }
        }
    }

    @Test
    fun `a pair with no team in the period has nobody to manage it - only an admin sees capacity, zero`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("cmp-ghost-admin", FlowUserRole.ADMIN)
        val plain = seededClient("cmp-ghost-plain", FlowUserRole.USER)
        val query = "from=2026-01-01&to=2026-01-31&accountId=ghost-${System.nanoTime()}-a&accountId=ghost-${System.nanoTime()}-b"
        val shown = admin.compare(query)
        assertEquals(31, shown.capacity!!.days)
        assertEquals(0.0, shown.row("availableMd").a.value)
        assertEquals(0, shown.row("availableMd").a.n)
        assertNull(shown.row("loggedPerAvailableMd").a.value, "no available man-days, no ratio")
        assertEquals(0.0, shown.row("deliveredMd").a.value)
        val hidden = plain.get("/api/v1/reports/compare-people?$query")
        assertEquals(HttpStatusCode.OK, hidden.status)
        assertFalse("capacity" in hidden.bodyAsText())
    }

    @Test
    fun `any signed-in user may compare any two people - D12 - and the labels fall back to the account id`() = testApplication {
        usePostgresTestcontainer()
        withCompareWorld { w ->
            val plain = seededClient("cmp-d12", FlowUserRole.USER)
            val report = withMinSampleSize(2) { plain.compare(w.query()) }
            assertEquals(7.0, report.row("deliveredMd").a.value)
            assertEquals(8.0, report.row("deliveredMd").b.value)
            val stranger = "stranger-${System.nanoTime()}"
            val unknown = plain.compare("from=2026-01-01&to=2026-01-31&accountId=$stranger&accountId=${w.alice}")
            assertEquals(stranger, unknown.people.first().label, "no known name: the account id")
        }
    }

}
