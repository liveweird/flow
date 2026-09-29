package ch.nokillswit

import ch.nokillswit.reports.BacklogReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9

/**
 * The independent mean: per team, the last [window] sprints completed before [asOfEndMs] (newest first), each team's
 * mean summed over [teams]; the sprints averaged are counted too. Written out here, not shared with the report.
 */
private fun expectedMean(sprints: List<ClosedSprint>, teams: Set<UInt>?, window: Int, asOfEndMs: Long): Pair<Double?, Int> {
    val perTeam = sprints.filter { it.completeAt < asOfEndMs && (teams == null || it.teamId in teams) }
        .groupBy { it.teamId }
        .map { (_, rows) -> rows.sortedByDescending { it.completeAt }.take(window).map { it.deliveredMd } }
    val used = perTeam.sumOf { it.size }
    return if (used == 0) null to 0 else perTeam.sumOf { it.sum() / it.size } to used
}

/**
 * `GET /api/v1/reports/backlog` (v0.3.0 M5 commit 15, Reports 10 and 13, `.claude/docs/measures.md` "Reports 10, 13").
 * The stub fixture grades the daily trend, `current` and the backlog in sprints against SUMs and means taken straight
 * off the persisted `agg_daily_flow` / `fact_sprint` rows; the zero-fill, the sprints-as-of-the-period-end rule, the
 * fewer-than-N and mean-of-zero cases and the scope isolation are pinned on hand-built rows in a fresh DISABLED
 * connection with hand-computed answers.
 */
class ReportBacklogTest {

    private suspend fun HttpClient.backlog(query: String): BacklogReport {
        val response = get("/api/v1/reports/backlog?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET backlog?$query")
        return response.body()
    }

    private fun BacklogReport.assertTrend(label: String, rows: List<FlowBacklogRow>, days: List<String>) {
        assertEquals(days, trend.map { it.day }, "$label: one point per calendar day")
        val byDay = rows.groupBy { it.day }
        for (point in trend) {
            val dayRows = byDay[point.day].orEmpty()
            assertEquals(dayRows.sumOf { it.items }, point.items, "$label ${point.day} items")
            assertEquals(dayRows.fold(BigDecimal.ZERO) { sum, row -> sum + row.md }.toDouble(), point.md, EPS, "$label ${point.day} md")
        }
        val last = trend.last()
        assertEquals(last.day, current.asOfDay, "$label: current reads the last day")
        assertEquals(last.items, current.items)
        assertEquals(last.md, current.md, EPS)
    }

    private val from = "2025-06-01"
    private val to = "2026-03-05"

    @Test
    fun `UNIT trend and current equal an independent sum of agg_daily_flow and the scopes isolate`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-backlog-unit")
        val rows = readFlowBacklogRows(connId).filter { it.day in isoDays(from, to) }
        val days = isoDays(from, to)
        assertEquals(dayOfInstant(DerivedStubFixture.PINNED_NOW, reportZone()), to, "the test range must end on the pinned DERIVE day")

        val unit = client.backlog("connectionId=$connId&from=$from&to=$to")
        unit.assertTrend("UNIT", rows.filter { it.scopeKind == "TEAM" }, days)
        assertEquals("UNIT", unit.meta.level.name)
        assertTrue(unit.trend.any { it.items > 0 && it.md > 0.0 }, "the fixture must carry an estimated backlog")
        assertNull(unit.note)
        assertEquals("TASK", client.backlog("connectionId=$connId&from=$from&to=$to&domainView=EPIC").meta.domainView.name)

        val domain = rows.filter { it.scopeKind == "DOMAIN" }.groupBy { it.scopeId }.maxBy { (_, group) -> group.sumOf { it.items } }.key
        client.backlog("connectionId=$connId&from=$from&to=$to&domain=$domain")
            .assertTrend("domain $domain", rows.filter { it.scopeKind == "DOMAIN" && it.scopeId == domain }, days)

        val teamId = rows.filter { it.scopeKind == "TEAM" }.map { it.scopeId }.filter { it.all(Char::isDigit) }.distinct().single()
        val team = client.backlog("connectionId=$connId&from=$from&to=$to&teamId=$teamId")
        team.assertTrend("team $teamId", rows.filter { it.scopeKind == "TEAM" && it.scopeId == teamId }, days)
        assertEquals("TEAM", team.meta.level.name)
        assertTrue(team.trend.any { it.items > 0 }, "the mapped team owns FLO backlog")

        val unowned = rows.filter { it.scopeKind == "TEAM" && it.scopeId == "UNOWNED" }
        assertTrue(unowned.isNotEmpty(), "the fixture must carry UNOWNED backlog")
        val unownedBody = client.backlog("connectionId=$connId&from=$from&to=$to&teamId=0")
        unownedBody.assertTrend("UNOWNED", unowned, days)
        assertNull(unownedBody.current.meanDeliveredMd, "the UNOWNED backlog has no velocity")
        assertNull(unownedBody.current.backlogInSprints)
        assertEquals(0, unownedBody.current.sprintsUsed)
    }

    @Test
    fun `backlog in sprints equals md over an independent mean of the last N closed sprints as of the period end`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-backlog-sprints")
        val zone = reportZone()
        val sprints = readClosedSprints(connId)
        val teamId = sprints.map { it.teamId }.distinct().single()
        val flow = readFlowBacklogRows(connId)
        assertTrue(sprints.size >= 2, "the fixture must carry closed team sprints")

        // Period ends inside the derived coverage (the fixture derives at 2026-03-05, the sprints run on to August):
        // the day the 1st, 2nd and 5th closed sprint completes, and the last covered day.
        val ordered = sprints.filter { dayOfInstant(it.completeAt, zone) < to }
        assertTrue(ordered.size >= 5, "the fixture must carry at least five team sprints closed before the derive day")
        val periodEnds = listOf(to) + listOf(4, 1, 0).map { dayOfInstant(ordered[it].completeAt, zone) }
        val windowSprints = client.backlog("connectionId=$connId&from=$from&to=$to").current.windowSprints
        assertTrue(windowSprints >= 1)
        var sawNonNull = false
        for (end in periodEnds) {
            val asOfEnd = windowBounds(zone, end, end).second
            val (mean, used) = expectedMean(sprints, setOf(teamId), windowSprints, asOfEnd)
            for (query in listOf("teamId=$teamId", "")) { // TEAM level, and UNIT level (one team ⇒ the same mean)
                val current = client.backlog("connectionId=$connId&from=$from&to=$end&$query").current
                assertEquals(end, current.asOfDay, "as of $end $query")
                assertEquals(used, current.sprintsUsed, "sprints used as of $end $query")
                assertEquals(windowSprints, current.windowSprints)
                if (mean == null) {
                    assertNull(current.meanDeliveredMd)
                } else {
                    assertEquals(mean, assertNotNull(current.meanDeliveredMd), EPS, "mean as of $end $query")
                }
                val md = if (query.isEmpty()) {
                    flow.filter { it.scopeKind == "TEAM" && it.day == end }.sumOf { it.md.toDouble() }
                } else {
                    flow.filter { it.scopeKind == "TEAM" && it.scopeId == teamId.toString() && it.day == end }.sumOf { it.md.toDouble() }
                }
                assertEquals(md, current.md, EPS, "md as of $end $query")
                val want = if (mean != null && mean > 0.0) md / mean else null
                if (want == null) assertNull(current.backlogInSprints) else assertEquals(want, assertNotNull(current.backlogInSprints), EPS)
                if (want != null && md > 0.0) sawNonNull = true
            }
        }
        assertTrue(sawNonNull, "at least one period end must give a non-null backlog in sprints")
        // The window is a cap: never more than N sprints, and fewer only when fewer have closed by the period end.
        val closedByEnd = sprints.count { it.completeAt < windowBounds(zone, to, to).second }
        val used = client.backlog("connectionId=$connId&from=$from&to=$to&teamId=$teamId").current.sprintsUsed
        assertEquals(minOf(windowSprints, closedByEnd), used)
    }

    @Test
    fun `sprint periods read the envelope, the trend stops at the last derived day, USER level is empty`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-backlog-window")

        val clamped = client.backlog("connectionId=$connId&from=2026-02-20&to=2026-12-31")
        assertEquals(isoDays("2026-02-20", to), clamped.trend.map { it.day }, "days past the last derived day are cut off, not zero")
        assertEquals(to, clamped.current.asOfDay)
        val beyond = client.backlog("connectionId=$connId&from=2026-06-01&to=2026-06-30")
        assertTrue(beyond.trend.isEmpty())
        assertNull(beyond.current.asOfDay)
        assertEquals(0, beyond.current.items)
        assertNull(beyond.current.meanDeliveredMd)
        assertNull(beyond.current.backlogInSprints)

        // lastSprints=2 resolves the two newest sprints — closed in August, past the derived coverage: nothing to read yet.
        val newest = client.backlog("connectionId=$connId&lastSprints=2")
        assertTrue(newest.meta.resolvedSprints.flatMap { it.sprintIds }.isNotEmpty() && newest.trend.isEmpty())
        assertNull(newest.current.asOfDay)

        // sprintId reads that sprint's own envelope, day by day.
        val zone = reportZone()
        val sprint = readClosedSprints(connId).filter { dayOfInstant(it.completeAt, zone) < to }[4]
        val body = client.backlog("connectionId=$connId&sprintId=${sprint.sprintId}")
        assertEquals(listOf(sprint.teamId), body.meta.resolvedSprints.map { it.teamId })
        assertNull(body.meta.from)
        body.assertTrend(
            "sprintId",
            readFlowBacklogRows(connId).filter { it.scopeKind == "TEAM" },
            isoDays(dayOfInstant(sprint.startAt ?: sprint.completeAt, zone), dayOfInstant(sprint.completeAt, zone)),
        )

        val teamId = sprint.teamId
        val user = client.backlog("connectionId=$connId&from=$from&to=$to&teamId=$teamId&accountId=nobody")
        assertTrue(user.trend.isEmpty())
        assertNotNull(user.note)
        assertEquals("USER", user.meta.level.name)
        assertEquals(0, user.current.items)
    }

    @Test
    fun `bad filters are 400`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("reports-backlog-400")
        val team = TestTeams.seed(SyncedStubFixture.unique("backlog-400"))
        try {
            for (query in listOf(
                "from=2026-01-01&to=2025-01-01", "accountId=abc", "connectionId=999999", "teamId=999999", "sprintId=999999999",
                "domain=AAA&teamId=$team", "activityType=Story", "workCategory=UNCATEGORIZED",
            )) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/backlog?$query").status, query)
            }
        } finally {
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `a connection that never derived reads as not derived yet, never as zeros`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "backlog-underived", enabled = false)
        insertFlowBacklogRows(connId, listOf(FlowBacklogRow("DOMAIN", "ZZ-underived", "2026-01-05", 3, BigDecimal("9.00"))))
        try {
            val client = seededClient("reports-backlog-underived")
            val body = client.backlog("connectionId=$connId&from=2026-01-01&to=2026-01-31&domain=ZZ-underived")
            assertTrue(body.trend.isEmpty(), "no successful DERIVE: nothing to list, not 31 zeros")
            assertTrue(assertNotNull(body.note).startsWith("Not derived yet"))
            assertNull(body.current.asOfDay)
            assertEquals(0, body.current.items)
            insertSucceededDerive(connId, noonUtc("2026-06-01"))
            val derived = client.backlog("connectionId=$connId&from=2026-01-01&to=2026-01-31&domain=ZZ-underived")
            assertEquals(31, derived.trend.size)
            assertEquals(3, derived.trend.single { it.day == "2026-01-05" }.items)
            assertNull(derived.note)
        } finally {
            deleteFlowRows(connId)
            deleteDeriveRuns(connId)
        }
    }

    /** Two connections derived through different days read together: the trend ends at the OLDEST one (see `ReportWipTest`). */
    @Test
    fun `the cut-off is the oldest last derived day across the connections in scope`() = testApplication {
        usePostgresTestcontainer()
        val connA = SyncedStubFixture.createConnection(namePrefix = "backlog-lag-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "backlog-lag-b", enabled = false)
        val connC = SyncedStubFixture.createConnection(namePrefix = "backlog-lag-c", enabled = false)
        val domain = "ZZ-lag"
        fun day(n: Int) = "2025-01-%02d".format(n)
        insertFlowBacklogRows(connA, (5..9).map { FlowBacklogRow("DOMAIN", domain, day(it), it - 4, BigDecimal("1.50")) })
        insertFlowBacklogRows(connB, (5..7).map { FlowBacklogRow("DOMAIN", domain, day(it), 100, BigDecimal("10.00")) })
        insertSucceededDerive(connA, noonUtc("2025-01-09"))
        insertSucceededDerive(connB, noonUtc("2025-01-07"))
        try {
            val client = seededClient("reports-backlog-lag")
            val body = client.backlog("from=2025-01-05&to=2025-01-11&domain=$domain")
            assertEquals(listOf(day(5), day(6), day(7)), body.trend.map { it.day })
            assertEquals(listOf(101, 102, 103), body.trend.map { it.items })
            assertEquals(listOf(11.5, 11.5, 11.5), body.trend.map { it.md })
            assertEquals(day(7), body.current.asOfDay)
            assertTrue(assertNotNull(body.note).contains(connC.toString()), "the never-derived connection is named")
            val onlyA = client.backlog("connectionId=$connA&from=2025-01-05&to=2025-01-11&domain=$domain")
            assertEquals((5..9).map { day(it) }, onlyA.trend.map { it.day })
            assertNull(onlyA.note)
        } finally {
            deleteFlowRows(connA)
            deleteFlowRows(connB)
            deleteDeriveRuns(connA)
            deleteDeriveRuns(connB)
        }
    }

    @Test
    fun `teamId 0 with a sprint-relative period is empty with a note`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val body = seededClient("reports-backlog-unassigned-sprints").backlog("connectionId=$connId&teamId=0&lastSprints=2")
        assertTrue(body.trend.isEmpty())
        assertTrue(assertNotNull(body.note).contains("UNASSIGNED"))
    }

    /**
     * Hand-built rows (fresh DISABLED connection), period Mon 2026-01-05 .. Wed 2026-01-07, window 3 sprints.
     * Team X backlog: day 5 = 4 items / 10 MD, day 6 nothing, day 7 = 5 / 24. X's closed sprints (delivered MD):
     * Dec 1 = 4, Dec 15 = 6, Dec 29 = 8, Jan 6 = 10, Jan 20 = 100 — as of Jan 7 the last three are 10, 8, 6 (mean 8,
     * sprintsUsed 3), as of Jan 5 they are 8, 6, 4 (mean 6); the Jan 20 sprint is after every period end. Y has ONE closed
     * sprint (5) — fewer than N; Z closed sprints that all delivered 0 — a mean of 0; W has no sprint at all.
     */
    @Test
    fun `hand-built rows pin the zero-fill, the window, fewer than N, a zero mean and the scope split exactly`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "backlog-hand", enabled = false)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val teamX = TestTeams.seed(SyncedStubFixture.unique("backlog-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("backlog-y"))
        val teamZ = TestTeams.seed(SyncedStubFixture.unique("backlog-z"))
        val teamW = TestTeams.seed(SyncedStubFixture.unique("backlog-w"))
        fun row(kind: String, id: String, day: String, items: Int, md: String) = FlowBacklogRow(kind, id, day, items, BigDecimal(md))
        val d5 = "2026-01-05"
        val d6 = "2026-01-06"
        val d7 = "2026-01-07"
        insertFlowBacklogRows(
            connId,
            listOf(
                row("TEAM", teamX.toString(), d5, 4, "10.00"), row("TEAM", teamX.toString(), d7, 5, "24.00"),
                row("TEAM", teamY.toString(), d7, 1, "2.50"), row("TEAM", teamZ.toString(), d7, 2, "3.00"),
                row("TEAM", "UNOWNED", d7, 1, "0.50"),
                row("DOMAIN", "AAA", d5, 3, "9.00"), row("DOMAIN", "AAA", d7, 6, "12.50"),
            ),
        )
        insertHandSprints(
            connId,
            listOf(
                HandSprint(1, teamX, noonUtc("2025-12-01"), 4.0), HandSprint(2, teamX, noonUtc("2025-12-15"), 6.0),
                HandSprint(3, teamX, noonUtc("2025-12-29"), 8.0), HandSprint(4, teamX, noonUtc("2026-01-06"), 10.0),
                HandSprint(5, teamX, noonUtc("2026-01-20"), 100.0),
                HandSprint(6, teamY, noonUtc("2025-12-10"), 5.0),
                HandSprint(7, teamZ, noonUtc("2025-12-10"), 0.0), HandSprint(8, teamZ, noonUtc("2025-12-24"), 0.0),
                HandSprint(9, teamX, null, 1000.0), // an open sprint (no completion) never counts
            ),
        )
        try {
            val client = seededClient("reports-backlog-hand-built")
            val base = "connectionId=$connId&from=$d5&to=$d7"

            val x = client.backlog("$base&teamId=$teamX")
            assertEquals(listOf(d5, d6, d7), x.trend.map { it.day })
            assertEquals(listOf(4, 0, 5), x.trend.map { it.items })
            assertEquals(listOf(10.0, 0.0, 24.0), x.trend.map { it.md })
            assertEquals(d7, x.current.asOfDay)
            assertEquals(5, x.current.items)
            assertEquals(24.0, x.current.md, EPS)
            assertEquals(3, x.current.windowSprints)
            assertEquals(3, x.current.sprintsUsed)
            assertEquals(8.0, x.current.meanDeliveredMd!!, EPS) // (10 + 8 + 6) / 3
            assertEquals(3.0, x.current.backlogInSprints!!, EPS) // 24 / 8

            // As of Jan 5 the Jan 6 sprint has not completed yet: 8, 6, 4.
            val early = client.backlog("connectionId=$connId&from=$d5&to=$d5&teamId=$teamX").current
            assertEquals(6.0, early.meanDeliveredMd!!, EPS)
            assertEquals(3, early.sprintsUsed)
            assertEquals(10.0 / 6.0, early.backlogInSprints!!, EPS)

            // Fewer than N: one sprint. A mean of 0: the ratio is undefined. No sprint at all: nothing.
            val y = client.backlog("$base&teamId=$teamY").current
            assertEquals(1, y.sprintsUsed)
            assertEquals(5.0, y.meanDeliveredMd!!, EPS)
            assertEquals(0.5, y.backlogInSprints!!, EPS)
            val z = client.backlog("$base&teamId=$teamZ").current
            assertEquals(2, z.sprintsUsed)
            assertEquals(0.0, z.meanDeliveredMd!!, EPS)
            assertNull(z.backlogInSprints)
            val w = client.backlog("$base&teamId=$teamW").current
            assertEquals(0, w.sprintsUsed)
            assertNull(w.meanDeliveredMd)
            assertNull(w.backlogInSprints)
            assertEquals(listOf(0, 0, 0), client.backlog("$base&teamId=$teamW").trend.map { it.items })

            // UNIT: every TEAM scope incl. UNOWNED (24 + 2.5 + 3 + 0.5 = 30 MD, 9 items); the mean is the SUM of the team means
            // (X 8 + Y 5 + Z 0 = 13); sprintsUsed is the MINIMUM across the teams (X 3, Y 1, Z 2), so Y's "fewer than N" shows.
            // A DOMAIN row never leaks into it.
            val unit = client.backlog(base)
            assertEquals(listOf(4, 0, 9), unit.trend.map { it.items })
            assertEquals(listOf(10.0, 0.0, 30.0), unit.trend.map { it.md })
            assertEquals(13.0, unit.current.meanDeliveredMd!!, EPS)
            assertEquals(1, unit.current.sprintsUsed)
            assertEquals(30.0 / 13.0, unit.current.backlogInSprints!!, EPS)

            // A domain reads its own scope and has no velocity of its own; teamId=0 is the UNOWNED backlog.
            val domain = client.backlog("$base&domain=AAA")
            assertEquals(listOf(3, 0, 6), domain.trend.map { it.items })
            assertEquals(12.5, domain.current.md, EPS)
            assertNull(domain.current.meanDeliveredMd)
            assertNull(domain.current.backlogInSprints)
            assertEquals(0, domain.current.sprintsUsed)
            val unowned = client.backlog("$base&teamId=0")
            assertEquals(listOf(0, 0, 1), unowned.trend.map { it.items })
            assertEquals(0.5, unowned.current.md, EPS)
            assertNull(unowned.current.backlogInSprints)
            val teamsMd = x.current.md + y.md + z.md + unowned.current.md
            assertTrue(abs(unit.current.md - teamsMd) < EPS, "UNIT md is the sum of its team scopes")
        } finally {
            deleteFlowRows(connId)
            deleteFactSprints(connId)
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamX, teamY, teamZ, teamW))
        }
    }
}
