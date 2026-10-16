package ch.nokillswit

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.SnapshotPlan
import ch.nokillswit.reports.WipReport
import ch.nokillswit.reports.backfillStart
import ch.nokillswit.reports.planSnapshotDays
import ch.nokillswit.reports.resolveReportScope
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Report 9 and the backfill window (decision C, `.claude/docs/measures.md` "Report 9 — WIP"): the series lists no day before
 * the LATEST `backfill_from` of the connections in scope and says why in its `note`. Hand-built `agg_daily_wip` rows in fresh
 * DISABLED connections, including rows DATED BEFORE the window (what a DERIVE from before the rule left behind) that the
 * report must not list. Every date below is hand-computed (Mon 2026-01-05 .. Sun 2026-01-11).
 */
class ReportWipBackfillTest {
    private val domain = "ZZ-backfill"

    private suspend fun HttpClient.wip(query: String): WipReport {
        val response = get("/api/v1/reports/wip?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET wip?$query")
        return response.body()
    }

    private fun rows(days: IntRange) =
        days.map { WipRow("DOMAIN", domain, "2026-01-%02d".format(it), "TASK", "s1", "NOT_STARTED", it) }

    @Test
    fun `a connection's series starts at its backfill date, earlier stored days are not listed and the note says why`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-backfill", enabled = false, backfillFrom = "2026-01-07")
        insertWipRows(connId, rows(5..11)) // days 5 and 6 predate the window: a stale row set must not leak through
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        try {
            val client = seededClient("reports-wip-backfill")
            val base = "connectionId=$connId&domain=$domain"
            val early = client.wip("$base&from=2026-01-05&to=2026-01-11")
            assertEquals((7..11).map { "2026-01-%02d".format(it) }, early.series.map { it.day }, "no day before the backfill date")
            assertEquals((7..11).toList(), early.series.map { it.counts.getValue("NOT_STARTED") }, "the stored counts of the listed days")
            assertEquals("Before 2026-01-07 the connection's history is incomplete (backfill window)", early.note)

            // A period starting on the backfill date, or later, loses nothing and says nothing.
            val onTheDate = client.wip("$base&from=2026-01-07&to=2026-01-11")
            assertEquals(early.series, onTheDate.series)
            assertNull(onTheDate.note)
            assertNull(client.wip("$base&from=2026-01-09&to=2026-01-11").note)

            // A period entirely before the window is empty, and the note is the reason (no "not derived", no zeros).
            val before = client.wip("$base&from=2026-01-05&to=2026-01-06")
            assertTrue(before.series.isEmpty())
            assertEquals(early.note, before.note)
            assertEquals(6, before.keys.size, "the legend is still the six stages")
        } finally {
            deleteWipRows(connId)
            deleteDeriveRuns(connId)
        }
    }

    @Test
    fun `a backfill date older than every day read changes nothing`() = testApplication {
        usePostgresTestcontainer()
        val connId = SyncedStubFixture.createConnection(namePrefix = "wip-backfill-old", enabled = false, backfillFrom = EARLY_BACKFILL)
        insertWipRows(connId, rows(5..11))
        insertSucceededDerive(connId, noonUtc("2026-06-01"))
        try {
            val body = seededClient("reports-wip-backfill-old").wip("connectionId=$connId&domain=$domain&from=2026-01-05&to=2026-01-11")
            assertEquals((5..11).map { "2026-01-%02d".format(it) }, body.series.map { it.day })
            assertNull(body.note)
        } finally {
            deleteWipRows(connId)
            deleteDeriveRuns(connId)
        }
    }

    private fun service(): ReportService {
        val settings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), settings, TeamMembershipService(sharedDatabaseForTests(), settings))
    }

    private suspend fun filterFor(connectionId: UInt, from: String, to: String): ReportFilter {
        val (fromMs, toMs) = windowBounds(reportZone(), from, to)
        return ReportFilter(
            period = ReportPeriod.DateRange(LocalDate.parse(from), LocalDate.parse(to), fromMs, toMs),
            level = ReportLevel.UNIT, teamId = null, domainView = DomainView.TASK, domain = domain,
            activityType = null, workCategory = null, connectionId = connectionId,
        )
    }

    /** The plan of `[from, 2026-01-11]` over exactly [connections], with the WIP rule on and with the shared default (off). */
    private suspend fun plans(connections: List<UInt>, from: String): Pair<SnapshotPlan, SnapshotPlan> {
        val service = service()
        val filter = filterFor(connections.first(), from, "2026-01-11")
        return service.reportTransaction { with(service) { planBoth(filter, connections) } }
    }

    private suspend fun ReportService.planBoth(filter: ReportFilter, connections: List<UInt>): Pair<SnapshotPlan, SnapshotPlan> {
        val scope = resolveReportScope(filter, noonUtc("2026-06-02")).copy(connectionIds = connections)
        return planSnapshotDays(scope, filter, scope.calendar, "user level", startAtBackfill = true) to
            planSnapshotDays(scope, filter, scope.calendar, "user level")
    }

    /**
     * Two connections with DIFFERENT backfill dates in one scope: the series starts at the LATER one (every shown day counts
     * every connection) and the note names that date; a connection that never derived does not move it. The plan is built
     * over exactly the pair (the scope of one connection widened with `copy`), because the suite's other connections make a
     * unit-wide HTTP read start at THEIR backfill dates, so a hand-built pair cannot be isolated through the filter.
     */
    @Test
    fun `with several connections the series starts at the latest backfill date among them and the note names it`() = runBlocking {
        val connA = SyncedStubFixture.createConnection(namePrefix = "wip-bf-a", enabled = false, backfillFrom = "2026-01-07")
        val connB = SyncedStubFixture.createConnection(namePrefix = "wip-bf-b", enabled = false, backfillFrom = "2026-01-09")
        val neverDerived = SyncedStubFixture.createConnection(namePrefix = "wip-bf-c", enabled = false, backfillFrom = "2026-01-11")
        insertSucceededDerive(connA, noonUtc("2026-06-01"))
        insertSucceededDerive(connB, noonUtc("2026-06-01"))
        try {
            val (both, withoutRule) = plans(listOf(connA, connB), "2026-01-05")
            assertEquals(listOf("2026-01-09", "2026-01-10", "2026-01-11"), both.days.map { it.toString() }, "the LATER backfill date wins")
            assertEquals(
                "Before 2026-01-09 the history of at least one connection in scope is incomplete " +
                    "(backfill window: the latest of their backfill dates)",
                both.note,
            )
            assertEquals(7, withoutRule.days.size, "the rule is the WIP report's opt-in: the shared plan keeps every day")
            assertNull(withoutRule.note)

            val (alone, _) = plans(listOf(connA), "2026-01-05")
            assertEquals(listOf(7, 8, 9, 10, 11), alone.days.map { it.dayOfMonth })
            assertEquals("Before 2026-01-07 the connection's history is incomplete (backfill window)", alone.note)

            // The never-derived connection (backfill 2026-01-11) is named for the cut-off but moves nothing.
            val (withIdle, _) = plans(listOf(connA, connB, neverDerived), "2026-01-05")
            assertEquals(both.days, withIdle.days)
            assertTrue(assertNotNull(withIdle.note).contains(neverDerived.toString()), "named as never derived")

            // A period that begins on the later date needs no explanation.
            assertNull(plans(listOf(connA, connB), "2026-01-09").first.note)

            val latest = service().reportTransaction { backfillStart(listOf(connB, connA)) }
            assertEquals(LocalDate.parse("2026-01-09"), assertNotNull(latest).date)
            assertEquals(2, latest.connections)
            assertNull(service().reportTransaction { backfillStart(emptyList()) })
        } finally {
            deleteDeriveRuns(connA)
            deleteDeriveRuns(connB)
        }
    }
}
