package ch.nokillswit

import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.jira.JiraRawStore
import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.ItemEstimateRow
import ch.nokillswit.metrics.ItemStageRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.BacklogReport
import ch.nokillswit.reports.SnapshotScope
import ch.nokillswit.reports.connectionsToRead
import ch.nokillswit.reports.readTouchedBacklog
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
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

private const val EPS = 1e-9

/** The first `history_id` / `worklog_id` of the hand-built touch rows (unique within their fresh connection). */
private const val HAND_HISTORY_BASE = 900_000L

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
                "touchedWithinDays=3651", "touchedWithinDays=-1", "touchedWithinDays=abc", "touchedWithinDays=1.5",
                "touchedWithinDays=7&touchedWithinDays=8",
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
        fun day(n: Int) = "2020-01-%02d".format(n)
        insertFlowBacklogRows(connA, (5..9).map { FlowBacklogRow("DOMAIN", domain, day(it), it - 4, BigDecimal("1.50")) })
        insertFlowBacklogRows(connB, (5..7).map { FlowBacklogRow("DOMAIN", domain, day(it), 100, BigDecimal("10.00")) })
        insertSucceededDerive(connA, noonUtc("2020-01-09"))
        insertSucceededDerive(connB, noonUtc("2020-01-07"))
        try {
            val client = seededClient("reports-backlog-lag")
            val body = client.backlog("from=2020-01-05&to=2020-01-11&domain=$domain")
            assertEquals(listOf(day(5), day(6), day(7)), body.trend.map { it.day })
            assertEquals(listOf(101, 102, 103), body.trend.map { it.items })
            assertEquals(listOf(11.5, 11.5, 11.5), body.trend.map { it.md })
            assertEquals(day(7), body.current.asOfDay)
            assertTrue(assertNotNull(body.note).contains(connC.toString()), "the never-derived connection is named")
            val onlyA = client.backlog("connectionId=$connA&from=2020-01-05&to=2020-01-11&domain=$domain")
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

    private fun assertSamePoints(want: BacklogReport, got: BacklogReport, label: String) {
        assertEquals(want.trend.map { it.day }, got.trend.map { it.day }, "$label: the same days")
        for ((w, g) in want.trend.zip(got.trend)) {
            assertEquals(w.items, g.items, "$label ${w.day} items")
            assertEquals(w.md, g.md, EPS, "$label ${w.day} md")
        }
        assertEquals(want.current.asOfDay, got.current.asOfDay, "$label: current day")
        assertEquals(want.current.items, got.current.items, "$label: current items")
        assertEquals(want.current.md, got.current.md, EPS, "$label: current md")
        assertEquals(want.current.backlogInSprints, got.current.backlogInSprints, "$label: backlog in sprints")
    }

    /**
     * The slider's equality proof on the stub: at its maximum the filter hides nothing (every figure equals the stored aggregate,
     * pointwise, for the UNIT, the team, the UNOWNED backlog and a domain), and at a smaller window the shown and hidden
     * figures add up to the aggregate. `0` and an absent parameter are the same unfiltered read.
     */
    @Test
    fun `touchedWithinDays at its maximum equals the unfiltered trend on every day and scope, and items plus hidden equal it at a week`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = DerivedStubFixture.connectionId()
            val client = seededClient("reports-backlog-touched-equal")
            val rows = readFlowBacklogRows(connId)
            val teamId = rows.filter { it.scopeKind == "TEAM" }.map { it.scopeId }.filter { it.all(Char::isDigit) }.distinct().single()
            val domain = rows.filter { it.scopeKind == "DOMAIN" }.groupBy { it.scopeId }.maxBy { (_, g) -> g.sumOf { it.items } }.key
            val base = "connectionId=$connId&from=$from&to=$to"
            var sawHidden = false
            for (scope in listOf("", "&teamId=$teamId", "&teamId=0", "&domain=$domain")) {
                val label = scope.ifEmpty { "UNIT" }
                val unfiltered = client.backlog(base + scope)
                assertNull(unfiltered.touchedWithinDays, "$label: the filter is off")
                assertTrue(unfiltered.trend.all { it.hiddenItems == 0 && it.hiddenMd == 0.0 } && unfiltered.current.hiddenItems == 0)

                val off = client.backlog("$base$scope&touchedWithinDays=0")
                assertEquals(unfiltered.trend, off.trend, "$label: 0 is the unfiltered read")
                assertEquals(unfiltered.current, off.current, "$label: 0 is the unfiltered read")
                assertNull(off.touchedWithinDays)

                val max = client.backlog("$base$scope&touchedWithinDays=3650")
                assertEquals(3650, max.touchedWithinDays, "$label: the effective filter is echoed")
                assertSamePoints(unfiltered, max, "$label at 3650")
                assertTrue(max.trend.all { it.hiddenItems == 0 && it.hiddenMd == 0.0 }, "$label: nothing is hidden at 3650 days")
                assertEquals(0, max.current.hiddenItems)
                assertEquals(0.0, max.current.hiddenMd, EPS)

                val seven = client.backlog("$base$scope&touchedWithinDays=7")
                assertEquals(7, seven.touchedWithinDays)
                assertEquals(unfiltered.trend.map { it.day }, seven.trend.map { it.day })
                for ((all, point) in unfiltered.trend.zip(seven.trend)) {
                    assertEquals(all.items, point.items + point.hiddenItems, "$label ${all.day}: items plus hidden")
                    assertEquals(all.md, point.md + point.hiddenMd, EPS, "$label ${all.day}: md plus hidden")
                }
                assertEquals(seven.trend.last().hiddenItems, seven.current.hiddenItems, "$label: current hides what the last day hides")
                assertEquals(seven.trend.last().hiddenMd, seven.current.hiddenMd, EPS)
                assertEquals(seven.trend.last().items, seven.current.items)
                if (seven.trend.any { it.hiddenItems > 0 }) sawHidden = true
            }
            assertTrue(sawHidden, "the stub must leave some backlog item untouched for a week on some day, else seven days proves nothing")
        }

    /** Every touch instant of every item of [connId], read straight off the three tables (never the report's SQL). */
    private suspend fun readTouches(connId: UInt): Map<Long, List<Long>> = suspendTransaction(sharedDatabaseForTests()) {
        val ch = JiraRawStore.Changelogs
        val wl = WorkItemStore.Worklogs
        val f = MetricsTables.FactTaskDelivery
        val histories = ch.selectAll().where { ch.connectionId eq connId }.toList().map { it[ch.issueId] to it[ch.createdAt] }
        val worklogs = wl.selectAll().where { wl.connectionId eq connId }.toList().map { it[wl.issueId] to it[wl.startedAt] }
        val creations = f.selectAll().where { f.connectionId eq connId }.toList().map { it[f.issueId] to it[f.createdAt] }
        (histories + worklogs + creations).groupBy({ it.first }) { it.second }
    }

    /**
     * The per-item oracle: the backlog items of a few sampled days come from the Kotlin re-derivation off the bridges
     * (`IndependentBacklogOracle`, the one `MetricsDerivationTest` grades `agg_daily_flow` with), the touches off the raw/norm/fact
     * rows, and the rule is applied in Kotlin (`latest touch before the day's end >= end - N days`). The report's shown and hidden
     * figures must equal it.
     */
    @Test
    fun `hidden items equal an independent per-item oracle over the touch timestamps on sampled days`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-backlog-touched-oracle")
        val zone = reportZone()
        val oracle = IndependentBacklogOracle.load(connId)
        val touches = readTouches(connId)
        val owners = suspendTransaction(sharedDatabaseForTests()) {
            val d = MetricsTables.DimDomain
            d.selectAll().where { d.connectionId eq connId }.toList().associate { it[d.domainKey] to it[d.ownerTeamId]?.value }
        }
        val teamId = readFlowBacklogRows(connId).filter { it.scopeKind == "TEAM" }.map { it.scopeId }.filter { it.all(Char::isDigit) }
            .distinct().single().toUInt()
        val busy = client.backlog("connectionId=$connId&from=$from&to=$to").trend.filter { it.items > 0 }.map { it.day }
        assertTrue(busy.size >= 3, "the fixture needs at least three days with a backlog to sample")
        val sampled = listOf(busy.first(), busy[busy.size / 2], busy.last())
        val scopes = listOf<Triple<String, String, (IndependentBacklogItem) -> Boolean>>(
            Triple("UNIT", "") { true },
            Triple("TEAM", "&teamId=$teamId") { item -> item.domainKey?.let { owners[it] } == teamId },
        )
        var sawHidden = false
        var sawShown = false
        for ((label, scope, wanted) in scopes) {
            for (days in listOf(7, 30, 90)) {
                val report = client.backlog("connectionId=$connId&from=$from&to=$to$scope&touchedWithinDays=$days")
                for (day in sampled) {
                    val endMs = windowBounds(zone, day, day).second
                    val (shown, hidden) = oracle.itemsAt(endMs).filter(wanted).partition { item ->
                        val latest = touches[item.issueId].orEmpty().filter { it < endMs }.maxOrNull()
                        latest == null || latest >= endMs - days * MILLIS_PER_DAY
                    }
                    val point = report.trend.single { it.day == day }
                    fun mdOf(items: List<IndependentBacklogItem>) = items.fold(BigDecimal.ZERO) { sum, item -> sum + item.md }.toDouble()
                    assertEquals(hidden.size, point.hiddenItems, "$label $day N=$days hiddenItems")
                    assertEquals(mdOf(hidden), point.hiddenMd, EPS, "$label $day N=$days hiddenMd")
                    assertEquals(shown.size, point.items, "$label $day N=$days items")
                    assertEquals(mdOf(shown), point.md, EPS, "$label $day N=$days md")
                    if (hidden.isNotEmpty()) sawHidden = true
                    if (shown.isNotEmpty()) sawShown = true
                }
            }
        }
        assertTrue(sawHidden && sawShown, "the sampled days must hide some items and show others, else the oracle proves nothing")
    }

    private suspend fun insertHandHistories(connId: UInt, touches: List<Pair<Long, Long>>) = suspendTransaction(sharedDatabaseForTests()) {
        touches.forEachIndexed { index, (issueId, atMs) ->
            JiraRawStore.Changelogs.insert {
                it[JiraRawStore.Changelogs.connectionId] = connId
                it[historyId] = HAND_HISTORY_BASE + index
                it[JiraRawStore.Changelogs.issueId] = issueId
                it[createdAt] = atMs
                it[payload] = "{}"
                it[fetchedAt] = 0L
            }
        }
    }

    private suspend fun insertHandWorklog(connId: UInt, issueId: Long, startedAt: Long) = suspendTransaction(sharedDatabaseForTests()) {
        WorkItemStore.Worklogs.insert {
            it[WorkItemStore.Worklogs.connectionId] = connId
            it[worklogId] = HAND_HISTORY_BASE
            it[WorkItemStore.Worklogs.issueId] = issueId
            it[WorkItemStore.Worklogs.startedAt] = startedAt
            it[timeSpentSeconds] = 3_600L
        }
    }

    /**
     * Hand-built rows (fresh DISABLED connection; one domain AAA owned by team X; five level-0 tasks NOT_STARTED with an own estimate
     * of 3.00, all created at noon on Thu 2026-01-01), period 2026-01-01 .. 2026-01-10, `touchedWithinDays=3`: an item counts on day
     * d when its latest touch BEFORE the day's end instant is no older than 72 h before that end ("noon k" = day k's start + 12 h).
     *
     * | task | touches besides the creation | shown on days | why |
     * |---|---|---|---|
     * | A | a Jira history at noon 6 | 1-3, 6-8 | the changelog source |
     * | B | a worklog started at noon 6 | 1-3, 6-8 | the worklog source (same days as A) |
     * | C | a history on the 20th, AFTER the period | 1-3 | a touch after the period changes nothing |
     * | D | a history exactly at the END instant of day 6 | 1-3, 7-9 | that touch belongs to day 7, not 6 |
     * | E | histories at noon 2 and noon 9 | 1-4, 9-10 | hidden 5-8, back on 9 |
     *
     * Shown per day: 5, 5, 5, 1, 0, 2, 3, 3, 2, 1 (hidden is the complement of 5).
     */
    @Test
    fun `hand-built rows pin the touch rule - creation, a changelog, a worklog, the window boundary and the next day`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026 (the derive widens the global table)
        val connId = SyncedStubFixture.createConnection(namePrefix = "backlog-touched-hand", enabled = false)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val zone = reportZone()
        fun startOf(day: Int) = windowBounds(zone, "2026-01-%02d".format(day), "2026-01-%02d".format(day)).first
        fun noonOf(day: Int) = startOf(day) + 12 * 60 * 60 * 1000L
        val created = noonOf(1)
        val teamX = TestTeams.seed(SyncedStubFixture.unique("backlog-touched-x"))
        val teamOther = TestTeams.seed(SyncedStubFixture.unique("backlog-touched-other"))
        val store = MetricsStore(sharedDatabaseForTests())
        val ids = listOf(1L, 2L, 3L, 4L, 5L)
        val (a, b, c) = ids
        val (d, e) = ids.drop(3)
        fun task(id: Long) = DimTaskRow(id, "HT-$id", "Story", "Story", null, "NONE", false, null, "AAA", null)
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(connId, listOf(DimDomainRow("AAA", "Alpha", listOf("AA"), teamX)), 1L)
            store.insertTasks(connId, ids.map { task(it) }, 1L)
            store.insertItemStage(connId, ids.map { ItemStageRow(it, "NOT_STARTED", "todo", created, null) })
            store.insertItemEstimate(connId, ids.map { ItemEstimateRow(it, 3.0, created, null) })
            store.insertFactTaskDelivery(connId, ids.map { handTask(it).copy(createdAt = created) }, 1L)
        }
        insertHandHistories(
            connId,
            listOf(a to noonOf(6), c to noonOf(20), d to startOf(7), e to noonOf(2), e to noonOf(9)),
        )
        insertHandWorklog(connId, b, noonOf(6))
        try {
            val client = seededClient("reports-backlog-touched-hand")
            val base = "connectionId=$connId&from=2026-01-01&to=2026-01-10&touchedWithinDays=3"
            val shown = listOf(5, 5, 5, 1, 0, 2, 3, 3, 2, 1)
            val hidden = shown.map { 5 - it }
            for (scope in listOf("", "&teamId=$teamX", "&domain=AAA")) {
                val report = client.backlog(base + scope)
                assertEquals((1..10).map { "2026-01-%02d".format(it) }, report.trend.map { it.day }, "scope '$scope': one point per day")
                assertEquals(shown, report.trend.map { it.items }, "scope '$scope' items")
                assertEquals(shown.map { it * 3.0 }, report.trend.map { it.md }, "scope '$scope' md")
                assertEquals(hidden, report.trend.map { it.hiddenItems }, "scope '$scope' hiddenItems")
                assertEquals(hidden.map { it * 3.0 }, report.trend.map { it.hiddenMd }, "scope '$scope' hiddenMd")
                assertEquals("2026-01-10", report.current.asOfDay)
                assertEquals(1, report.current.items)
                assertEquals(3.0, report.current.md, EPS)
                assertEquals(4, report.current.hiddenItems)
                assertEquals(12.0, report.current.hiddenMd, EPS)
                assertEquals(3, report.touchedWithinDays)
            }
            // Another team's scope (no domain of it) and the UNOWNED backlog hold none of them.
            for (other in listOf(teamOther.toString(), "0")) {
                assertEquals(List(10) { 0 }, client.backlog("$base&teamId=$other").trend.map { it.items + it.hiddenItems }, "teamId=$other")
            }
            // A longer window shows more: at 30 days every item (created on the 1st) is touched recently on every day of the period.
            val wide = client.backlog("connectionId=$connId&from=2026-01-01&to=2026-01-10&touchedWithinDays=30")
            assertEquals(List(10) { 5 }, wide.trend.map { it.items })
            assertEquals(List(10) { 0 }, wide.trend.map { it.hiddenItems })
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Changelogs.deleteWhere { JiraRawStore.Changelogs.connectionId eq connId }
                WorkItemStore.Worklogs.deleteWhere { WorkItemStore.Worklogs.connectionId eq connId }
                store.deleteFactTaskDelivery(connId)
                store.deleteBridges(connId)
                store.deleteDims(connId)
            }
            deleteDeriveRuns(connId)
            cleanUpTeams(listOf(teamX, teamOther))
        }
    }

    private fun handTouchTask(id: Long) = DimTaskRow(id, "HT-$id", "Story", "Story", null, "NONE", false, null, "AAA", null)

    /** Removes every hand-built row a touch test left in [connIds] (raw/norm/fact/bridges/dims and the derive run). */
    private suspend fun cleanUpHandConnections(connIds: List<UInt>) {
        val store = MetricsStore(sharedDatabaseForTests())
        for (connId in connIds) {
            suspendTransaction(sharedDatabaseForTests()) {
                JiraRawStore.Changelogs.deleteWhere { JiraRawStore.Changelogs.connectionId eq connId }
                WorkItemStore.Worklogs.deleteWhere { WorkItemStore.Worklogs.connectionId eq connId }
                store.deleteFactTaskDelivery(connId)
                store.deleteBridges(connId)
                store.deleteDims(connId)
            }
            deleteDeriveRuns(connId)
        }
    }

    /**
     * The per-connection merge: [readTouchedBacklog] runs one statement per connection and SUMS the days, shown and hidden alike.
     * Two hand-built DISABLED connections (period 2026-01-01 .. 01-10, `touchedWithinDays=3`, all created at noon 1):
     * connection 1 has two tasks of 3.00 MD (one touched by a history at noon 6, one never again), connection 2 three tasks of
     * 2.00 MD (never again; a worklog at noon 6; a history at noon 9). A task is shown on days 1-3 on creation alone and for three
     * days after each later touch (6-8 after noon 6, 9-10 after noon 9, the window reaching back 72 h from the day end).
     */
    @Test
    fun `the per-connection merge sums shown and hidden items and md over two connections`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026
        val conn1 = SyncedStubFixture.createConnection(namePrefix = "backlog-merge-1", enabled = false)
        val conn2 = SyncedStubFixture.createConnection(namePrefix = "backlog-merge-2", enabled = false)
        val zone = reportZone()
        fun startOf(day: Int) = windowBounds(zone, "2026-01-%02d".format(day), "2026-01-%02d".format(day)).first
        fun noonOf(day: Int) = startOf(day) + 12 * 60 * 60 * 1000L
        val created = noonOf(1)
        val store = MetricsStore(sharedDatabaseForTests())
        suspend fun seed(connId: UInt, ids: List<Long>, md: Double) = suspendTransaction(sharedDatabaseForTests()) {
            store.insertTasks(connId, ids.map { handTouchTask(it) }, 1L)
            store.insertItemStage(connId, ids.map { ItemStageRow(it, "NOT_STARTED", "todo", created, null) })
            store.insertItemEstimate(connId, ids.map { ItemEstimateRow(it, md, created, null) })
            store.insertFactTaskDelivery(connId, ids.map { handTask(it).copy(createdAt = created) }, 1L)
        }
        seed(conn1, listOf(1L, 2L), 3.0)
        seed(conn2, listOf(3L, 4L, 5L), 2.0)
        insertHandHistories(conn1, listOf(1L to noonOf(6)))
        insertHandHistories(conn2, listOf(5L to noonOf(9)))
        insertHandWorklog(conn2, 4L, noonOf(6))
        try {
            val merged = suspendTransaction(sharedDatabaseForTests()) {
                readTouchedBacklog(listOf(conn1, conn2), SnapshotScope.AllTeams, "2026-01-01", "2026-01-10", 3)
            }
            val shown1 = listOf(2, 2, 2, 0, 0, 1, 1, 1, 0, 0)
            val shown2 = listOf(3, 3, 3, 0, 0, 1, 1, 1, 1, 1)
            assertEquals((1..10).map { "2026-01-%02d".format(it) }, merged.keys.sorted(), "every day carries a backlog row")
            for (index in 0 until 10) {
                val day = "2026-01-%02d".format(index + 1)
                val cell = merged.getValue(day)
                assertEquals(5, cell.allItems, "$day all items (2 + 3)")
                assertEquals(BigDecimal("12.00"), cell.allMd.setScale(2), "$day all md (2 x 3.00 + 3 x 2.00)")
                assertEquals(shown1[index] + shown2[index], cell.items, "$day shown items")
                assertEquals(BigDecimal.valueOf(shown1[index] * 3L + shown2[index] * 2L).setScale(2), cell.md.setScale(2), "$day shown md")
            }
            // One connection alone reads its own half: the merge is a plain sum.
            val only2 = suspendTransaction(sharedDatabaseForTests()) {
                readTouchedBacklog(listOf(conn2), SnapshotScope.AllTeams, "2026-01-01", "2026-01-10", 3)
            }
            assertEquals(shown2, (1..10).map { only2.getValue("2026-01-%02d".format(it)).items })
        } finally {
            cleanUpHandConnections(listOf(conn1, conn2))
        }
    }

    /**
     * The TEAM-level skip (`connectionsToRead`): a connection where the team owns no domain never runs its statement, and the
     * answer is the one the owning connection gives alone. Two hand-built DISABLED connections share the domain key AAA, owned by
     * team X in connection 1 (two tasks) and by another team in connection 2 (three tasks).
     */
    @Test
    fun `at TEAM level a connection where the team owns no domain is skipped and the answer is unchanged`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026
        val conn1 = SyncedStubFixture.createConnection(namePrefix = "backlog-skip-1", enabled = false)
        val conn2 = SyncedStubFixture.createConnection(namePrefix = "backlog-skip-2", enabled = false)
        val teamX = TestTeams.seed(SyncedStubFixture.unique("backlog-skip-x"))
        val teamOther = TestTeams.seed(SyncedStubFixture.unique("backlog-skip-other"))
        val teamNone = TestTeams.seed(SyncedStubFixture.unique("backlog-skip-none"))
        val created = windowBounds(reportZone(), "2026-01-01", "2026-01-01").first
        val store = MetricsStore(sharedDatabaseForTests())
        suspend fun seed(connId: UInt, owner: UInt, ids: List<Long>) = suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(connId, listOf(DimDomainRow("AAA", "Alpha", listOf("AA"), owner)), 1L)
            store.insertTasks(connId, ids.map { handTouchTask(it) }, 1L)
            store.insertItemStage(connId, ids.map { ItemStageRow(it, "NOT_STARTED", "todo", created, null) })
            store.insertItemEstimate(connId, ids.map { ItemEstimateRow(it, 1.0, created, null) })
            store.insertFactTaskDelivery(connId, ids.map { handTask(it).copy(createdAt = created) }, 1L)
        }
        try {
            seed(conn1, teamX, listOf(1L, 2L))
            seed(conn2, teamOther, listOf(3L, 4L, 5L))
            suspendTransaction(sharedDatabaseForTests()) {
                val both = listOf(conn1, conn2)
                assertEquals(listOf(conn1), connectionsToRead(both, SnapshotScope.Team(teamX)), "only the owning connection is read")
                assertEquals(listOf(conn2), connectionsToRead(both, SnapshotScope.Team(teamOther)))
                assertEquals(emptyList(), connectionsToRead(both, SnapshotScope.Team(teamNone)), "a team owning nothing reads nothing")
                for (scope in listOf(SnapshotScope.AllTeams, SnapshotScope.Unassigned, SnapshotScope.Domain("AAA"))) {
                    assertEquals(both, connectionsToRead(both, scope), "$scope reads every connection")
                }
                val team = readTouchedBacklog(both, SnapshotScope.Team(teamX), "2026-01-01", "2026-01-05", 3)
                assertEquals(readTouchedBacklog(listOf(conn1), SnapshotScope.Team(teamX), "2026-01-01", "2026-01-05", 3), team)
                assertEquals(2, team.getValue("2026-01-01").allItems, "connection 2's tasks belong to the other team")
                assertEquals(5, readTouchedBacklog(both, SnapshotScope.Domain("AAA"), "2026-01-01", "2026-01-05", 3)
                    .getValue("2026-01-01").allItems, "the domain slice still reads both connections")
            }
        } finally {
            cleanUpHandConnections(listOf(conn1, conn2))
            cleanUpTeams(listOf(teamX, teamOther, teamNone))
        }
    }

    /**
     * The `stagesOverlappingDayRange` pruning must drop only `item_stage` rows no day of the period can match. Period
     * 2026-01-03 .. 01-05 (E3 = day 3's end instant, E5 = day 5's), one NOT_STARTED interval per task:
     *
     * | task | interval | in the backlog on |
     * |---|---|---|
     * | P | ends exactly at E3 (`valid_to >= day end` still covers it) | day 3 only |
     * | Q | ends 1 ms before E3 | never |
     * | R | ended days before the period | never |
     * | S | starts exactly at E5 (`valid_from < day end` does not cover it) | never |
     * | T | open from creation | 3, 4, 5 |
     */
    @Test
    fun `the stage pruning keeps an interval ending exactly at the first day end and drops those outside the period`() = testApplication {
        usePostgresTestcontainer()
        DerivedStubFixture.connectionId() // dim_date must cover January 2026
        val connId = SyncedStubFixture.createConnection(namePrefix = "backlog-prune", enabled = false)
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        val zone = reportZone()
        val e3 = windowBounds(zone, "2026-01-03", "2026-01-03").second
        val e5 = windowBounds(zone, "2026-01-05", "2026-01-05").second
        val created = noonUtc("2025-12-20")
        val all = listOf(1L, 2L, 3L, 4L, 5L)
        val (p, q, r) = all
        val (s, t) = all.drop(3)
        val store = MetricsStore(sharedDatabaseForTests())
        val stages = listOf(
            ItemStageRow(p, "NOT_STARTED", "todo", created, e3),
            ItemStageRow(q, "NOT_STARTED", "todo", created, e3 - 1),
            ItemStageRow(r, "NOT_STARTED", "todo", created, noonUtc("2025-12-28")),
            ItemStageRow(s, "NOT_STARTED", "todo", e5, null),
            ItemStageRow(t, "NOT_STARTED", "todo", created, null),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertTasks(connId, all.map { handTouchTask(it) }, 1L)
            store.insertItemStage(connId, stages)
            store.insertItemEstimate(connId, all.map { ItemEstimateRow(it, 3.0, created, null) })
            store.insertFactTaskDelivery(connId, all.map { handTask(it).copy(createdAt = created) }, 1L)
        }
        try {
            val client = seededClient("reports-backlog-prune")
            val report = client.backlog("connectionId=$connId&from=2026-01-03&to=2026-01-05&touchedWithinDays=3650")
            assertEquals(listOf("2026-01-03", "2026-01-04", "2026-01-05"), report.trend.map { it.day })
            assertEquals(listOf(2, 1, 1), report.trend.map { it.items }, "P is in on day 3 only, T on all three; Q, R and S never")
            assertEquals(listOf(6.0, 3.0, 3.0), report.trend.map { it.md })
            assertEquals(listOf(0, 0, 0), report.trend.map { it.hiddenItems }, "created 2025-12-20 is within 3650 days")
        } finally {
            cleanUpHandConnections(listOf(connId))
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
