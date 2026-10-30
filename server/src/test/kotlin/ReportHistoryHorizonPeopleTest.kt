package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.reports.CompareDrillView
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.TrendCoverage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.BeforeClass

/**
 * The history horizon (domain-model A48) on the people reports, over the derived stub fixture with the report clock pinned to
 * 2026-06-01 and the horizon at six months (cutoff 2025-12-01; DERIVE ran on 2026-03-05, the backfill starts on 2025-09-01):
 * Compare people over a window straddling the cutoff IS Compare people over the window from the cutoff; Person trend's points before
 * it are labelled PARTIAL like a window before the backfill, each point still equals Compare people over its (clamped) window, and its
 * drill is that Compare-people drill.
 */
class ReportHistoryHorizonPeopleTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()

        private val PINNED_NOW = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
        private const val CUTOFF = "2025-12-01"
        private const val WIDE_FROM = "2025-09-01"
        private const val TO = "2026-03-05"
        private const val YEAR_FROM = "2025-03-06"
    }

    private val settings = DerivedStubFixture.metricsSettings()

    private fun ApplicationTestBuilder.pinReportClock() {
        application.attributes[ReportServiceKey].clock = { PINNED_NOW }
    }

    private suspend fun <T> withHorizon(months: Int?, block: suspend () -> T): T = withMetricsSettings(
        settings,
        { it.copy(historyHorizonMonths = months, hoursPerDay = DerivedStubFixture.HOURS_PER_DAY) },
        block,
    )

    /** The two assignees with the most done tasks in the fixture's year, busiest first. */
    private suspend fun busiestPair(connId: UInt): List<String> {
        val bounds = windowBounds(reportZone(), YEAR_FROM, TO)
        return jdbcRows(
            """SELECT assignee_account_id_at_done, count(*) c FROM metrics.fact_task_delivery
               WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ?
                 AND assignee_account_id_at_done IS NOT NULL AND cycle_ms IS NOT NULL
               GROUP BY 1 ORDER BY c DESC, 1 LIMIT 2""",
            connId.toInt(), bounds.first, bounds.second,
        ).map { it[0] as String }
    }

    private suspend fun HttpClient.json(url: String): JsonObject {
        val response = get(url)
        assertEquals(HttpStatusCode.OK, response.status, "$url: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** The body minus what a horizon legitimately changes: `meta.historyHorizon` and the free-text `note`. */
    private fun JsonObject.withoutHorizon(): JsonObject = JsonObject(
        (this - "note").mapValues { (key, value) ->
            if (key == "meta") JsonObject(value.jsonObject - "historyHorizon") else value
        },
    )

    @Test
    fun `compare people over a window straddling the cutoff equals compare people over the window from the cutoff`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val (person, other) = busiestPair(connId)
        val client = seededClient("horizon-compare", FlowUserRole.USER)
        val people = "connectionId=$connId&accountId=$person&accountId=$other"
        val limited = withHorizon(6) { client.json("/api/v1/reports/compare-people?$people&from=$WIDE_FROM&to=$TO") }
        val clamped = withHorizon(null) { client.json("/api/v1/reports/compare-people?$people&from=$CUTOFF&to=$TO") }
        val wide = withHorizon(null) { client.json("/api/v1/reports/compare-people?$people&from=$WIDE_FROM&to=$TO") }
        assertEquals(clamped.withoutHorizon(), limited.withoutHorizon(), "one truth")
        assertEquals("\"$CUTOFF\"", limited.getValue("meta").jsonObject.getValue("from").toString(), "meta.from is the clamped day")
        assertEquals("""{"months":6,"from":"$CUTOFF"}""", limited.getValue("meta").jsonObject.getValue("historyHorizon").toString())
        assertNotEquals(wide.withoutHorizon(), limited.withoutHorizon(), "the cut must remove something")
        withHorizon(6) {
            val refused = client.get("/api/v1/reports/compare-people?$people&from=2025-01-01&to=2025-11-30")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue("from/to lies entirely before the history horizon ($CUTOFF)" in refused.bodyAsText(), refused.bodyAsText())
        }
    }

    @Test
    fun `person trend labels the points before the cutoff partial and every point still equals compare people over its window`() =
        testApplication {
            usePostgresTestcontainer()
            pinReportClock()
            val connId = DerivedStubFixture.connectionId()
            val (person, other) = busiestPair(connId)
            val client = seededClient("horizon-trend", FlowUserRole.USER)
            val base = "connectionId=$connId"
            val query = "$base&from=$WIDE_FROM&to=$TO&accountId=$person"
            val limited = withHorizon(6) {
                val trend = client.personTrend(query)
                val count = client.assertPersonTrendIsComparePeople(trend, person, other, base)
                assertEquals(trend.windows.size * trend.rows.size, count.cells)
                trend
            }
            val plain = withHorizon(null) { client.personTrend(query) }

            assertEquals(CUTOFF, limited.meta.from, "meta.from is the clamped day")
            assertEquals(
                listOf("2025-12-11", "2025-12-25", "2026-01-08", "2026-01-22", "2026-02-05", "2026-02-19", TO),
                limited.windows.map { it.end },
                "the grid counts back from `to`; no point ends before the cutoff",
            )
            assertTrue(plain.windows.size > limited.windows.size)
            val cutoff = LocalDate.parse(CUTOFF)
            for (window in limited.windows) {
                val expected = if (LocalDate.parse(window.from).isBefore(cutoff)) TrendCoverage.PARTIAL else TrendCoverage.FULL
                assertEquals(expected, window.coverage, "window ${window.from}..${window.end}")
            }
            assertTrue(limited.windows.any { it.coverage == TrendCoverage.PARTIAL })
            assertEquals(TrendCoverage.FULL, limited.windows.last().coverage)
            assertEquals(
                TrendCoverage.FULL, plain.windows.single { it.end == "2025-12-11" }.coverage,
                "without the horizon the same window starts after the backfill: full",
            )
            val sharedEnds = limited.windows.map { it.end }
            fun values(trend: ch.nokillswit.reports.PersonTrendReport) =
                trend.rows.flatMap { row -> row.points.filter { it.end in sharedEnds }.map { it.value to it.n } }
            assertNotEquals(values(plain), values(limited), "the cut removes the person's early work from the early windows")
        }

    @Test
    fun `a person trend point's drill is the compare people drill over the window from the cutoff`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val (person, other) = busiestPair(connId)
        val client = seededClient("horizon-trend-drill", FlowUserRole.USER)
        val base = "connectionId=$connId"
        val trendQuery = "$base&from=$WIDE_FROM&to=$TO&accountId=$person"
        val (windows, views) = withHorizon(6) { client.personTrend(trendQuery) }.let { trend ->
            trend.windows.filter { it.end == "2025-12-11" || it.end == "2026-02-19" || it.end == TO } to
                trend.rows.mapNotNull { it.drill }.toSet()
        }
        assertTrue(CompareDrillView.DELIVERED in views && windows.size == 3)
        val drills = withHorizon(6) {
            windows.flatMap { w ->
                views.map { view -> (w to view) to client.drillItems("person-trend", "$trendQuery&view=${view.name}&pointEnd=${w.end}") }
            }
        }
        var listed = 0
        withHorizon(null) {
            for (entry in drills) {
                val (window, view) = entry.first
                val drill = entry.second
                val from = maxOf(LocalDate.parse(window.from), LocalDate.parse(CUTOFF)).toString()
                val people = "accountId=$person&accountId=$other&segmentAccountId=$person"
                val query = "$base&from=$from&to=${window.end}&$people&view=${view.name}"
                val want = client.drillItems("compare-people", query)
                val where = "${view.name} @ ${window.end}"
                assertEquals(want.items, drill.items, "$where items")
                assertEquals(want.bar, drill.bar, "$where bar")
                assertEquals(want.total, drill.total, "$where total")
                assertEquals(from, drill.meta.from, "$where: meta.from is the clamped window start")
                listed += drill.items.size
            }
        }
        assertTrue(listed > 0, "the drills list the person's items")
    }
}
