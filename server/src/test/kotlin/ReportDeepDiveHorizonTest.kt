package ch.nokillswit

import ch.nokillswit.reports.DeepDiveEpicPageResponse
import ch.nokillswit.reports.DeepDiveReport
import ch.nokillswit.reports.DeepDiveSprintPageResponse
import ch.nokillswit.reports.HistoryHorizonWire
import ch.nokillswit.reports.ReportServiceKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.BeforeClass

private const val HZ_BASE = "/api/v1/reports/deep-dive"

/**
 * The history horizon (domain-model A48) on the Deep dive, over the derived stub fixture with the report clock pinned to 2026-06-01 and
 * the horizon at six months (cutoff 2025-12-01): the range never starts before the cutoff and is then the same answer as the one without a
 * horizon over a range starting there (totals whole-life, A40); the envelope of an epic finished before it is the single empty day on the
 * cutoff with a `HISTORY_HORIZON` note; a `to` or a sprint before it is `400`; the two option lists skip what finished before it.
 */
class ReportDeepDiveHorizonTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()

        private val PINNED_NOW = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
        private const val CUTOFF = "2025-12-01"
        private const val TWO_DAYS_MS = 2 * 86_400_000L
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

    private suspend fun HttpClient.dive(query: String): DeepDiveReport {
        val response = get("$HZ_BASE?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET deep-dive?$query: ${response.bodyAsText()}")
        return response.body()
    }

    private suspend fun HttpClient.sprintOptions(connId: UInt) =
        get("$HZ_BASE/sprints?domain=${DerivedStubFixture.DOMAIN}&connectionId=$connId&pageSize=100")
            .body<DeepDiveSprintPageResponse>().items

    private suspend fun HttpClient.epicOptions(connId: UInt) =
        get("$HZ_BASE/epics?domain=${DerivedStubFixture.DOMAIN}&connectionId=$connId&pageSize=100").body<DeepDiveEpicPageResponse>().items

    private suspend fun cutoffMs(): Long = windowBounds(reportZone(), CUTOFF, CUTOFF).first

    /** The sprint query for [ids] of the fixture's domain. */
    private fun sprintQuery(connId: UInt, ids: List<Long>) =
        "connectionId=$connId&domain=${DerivedStubFixture.DOMAIN}" + ids.joinToString("") { "&sprintId=$it" }

    @Test
    fun `a sprint selection straddling the cutoff is the same answer as the one without a horizon over a range from the cutoff`() =
        testApplication {
            usePostgresTestcontainer()
            pinReportClock()
            val connId = DerivedStubFixture.connectionId()
            val client = seededClient("horizon-dive-sprints")
            val cutoff = cutoffMs()
            val sprints = withHorizon(null) { client.sprintOptions(connId) }
            val straddling = sprints.first { s ->
                s.startAt != null && s.completeAt != null && s.startAt < cutoff - TWO_DAYS_MS && s.completeAt >= cutoff + TWO_DAYS_MS
            }
            val query = sprintQuery(connId, listOf(straddling.id))

            val limited = withHorizon(6) { client.dive(query) }
            val whole = withHorizon(null) { client.dive(query) }
            val clipped = withHorizon(null) { client.dive("$query&from=$CUTOFF&to=${limited.range.to}") }
            assertEquals(CUTOFF, limited.range.from, "the range starts at the cutoff")
            assertTrue(whole.range.from < CUTOFF, "off keeps the whole envelope")
            assertEquals(whole.range.to, limited.range.to)
            assertEquals(clipped.copy(meta = clipped.meta.copy(historyHorizon = limited.meta.historyHorizon)), limited, "one truth")
            assertEquals(HistoryHorizonWire(6, CUTOFF), limited.meta.historyHorizon)
            assertEquals(null, whole.meta.historyHorizon)
            // Totals are whole-life (A40): the clip never reaches them.
            assertEquals(whole.tasks.map { it.key to it.totals }, limited.tasks.map { it.key to it.totals })
            assertNotEquals(whole.tasks.map { it.pv }, limited.tasks.map { it.pv }, "the part before the cutoff is out of the series")
        }

    @Test
    fun `a sprint closed before the cutoff and a to before it are 400, the sprint pickers list neither`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("horizon-dive-400")
        val cutoff = cutoffMs()
        val all = withHorizon(null) { client.sprintOptions(connId) }
        val closedBefore = all.filter { it.completeAt != null && it.completeAt < cutoff }
        val kept = all.filter { it.completeAt == null || it.completeAt >= cutoff }
        assertTrue(closedBefore.isNotEmpty() && kept.isNotEmpty(), "the cutoff splits the domain's sprints")
        withHorizon(6) {
            val refused = client.get("$HZ_BASE?${sprintQuery(connId, listOf(closedBefore.first().id))}")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(
                "sprintId ${closedBefore.first().id} closed before the history horizon ($CUTOFF)" in refused.bodyAsText(),
                refused.bodyAsText(),
            )
            val mixed = client.get("$HZ_BASE?${sprintQuery(connId, listOf(kept.first().id, closedBefore.first().id))}")
            assertEquals(HttpStatusCode.BadRequest, mixed.status, "one old sprint among the picks refuses the request")
            assertEquals(HttpStatusCode.OK, client.get("$HZ_BASE?${sprintQuery(connId, listOf(kept.first().id))}").status)

            val base = sprintQuery(connId, listOf(kept.first().id))
            for (range in listOf("from=2025-01-01&to=2025-11-30", "to=2025-11-30")) {
                val response = client.get("$HZ_BASE?$base&$range")
                assertEquals(HttpStatusCode.BadRequest, response.status, range)
                assertTrue("from/to lies entirely before the history horizon ($CUTOFF)" in response.bodyAsText(), response.bodyAsText())
            }
            assertEquals(HttpStatusCode.OK, client.get("$HZ_BASE?$base&to=$CUTOFF").status, "a `to` on the cutoff day still overlaps it")
            val earlierFrom = client.dive("$base&from=2025-01-01&to=2026-02-01")
            assertEquals(CUTOFF, earlierFrom.range.from, "an earlier from is raised to the cutoff")

            assertEquals(kept.map { it.id }, client.sprintOptions(connId).map { it.id }, "the picker lists no sprint closed before it")
        }
    }

    @Test
    fun `an epic finished before the cutoff is clipped to the cutoff, totals whole-life, and the epic picker skips it`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("horizon-dive-epics")
        val cutoff = cutoffMs()
        val doneBefore = jdbcRows(
            """SELECT e.issue_key FROM metrics.dim_epic e
               JOIN metrics.fact_epic_delivery d ON d.connection_id = e.connection_id AND d.issue_id = e.issue_id
               WHERE e.connection_id = ? AND d.done_at < ? ORDER BY 1""",
            connId.toInt(), cutoff,
        ).map { it[0] as String }
        assertTrue(doneBefore.isNotEmpty(), "the fixture has an epic finished before the cutoff")
        val epic = doneBefore.first()

        // The stub's epics keep planned windows (and so marks) past the cutoff: the range starts there, nothing is emptied.
        val limited = withHorizon(6) { client.dive("connectionId=$connId&epicId=$epic") }
        val whole = withHorizon(null) { client.dive("connectionId=$connId&epicId=$epic") }
        val clipped = withHorizon(null) { client.dive("connectionId=$connId&epicId=$epic&from=$CUTOFF&to=${limited.range.to}") }
        assertEquals(CUTOFF, limited.range.from)
        assertEquals(whole.range.to, limited.range.to)
        assertEquals(clipped.copy(meta = clipped.meta.copy(historyHorizon = limited.meta.historyHorizon)), limited, "one truth")
        assertTrue(limited.note == null && whole.tasks.isNotEmpty())
        assertEquals(whole.tasks.map { it.key to it.totals }, limited.tasks.map { it.key to it.totals }, "totals stay whole-life")

        val offered = withHorizon(6) { client.epicOptions(connId) }.map { it.key }
        val allEpics = withHorizon(null) { client.epicOptions(connId) }.map { it.key }
        assertTrue(doneBefore.any { it in allEpics }, "the epic is offered without a horizon")
        assertEquals(allEpics.filterNot { it in doneBefore }, offered, "the epic picker skips what finished before the cutoff")
    }

    @Test
    fun `the not-derived answer carries the horizon in its meta too`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = SyncedStubFixture.createConnection(namePrefix = "dd-horizon-undrived", enabled = false)
        val client = seededClient("horizon-dive-meta")
        val report = withHorizon(6) { client.dive("connectionId=$connId&epicId=NOPE-1&from=2026-02-02&to=2026-02-08") }
        assertEquals(HistoryHorizonWire(6, CUTOFF), report.meta.historyHorizon)
        assertTrue(report.note!!.startsWith("Not derived yet"))
        assertEquals("2026-02-02" to "2026-02-08", report.range.from to report.range.to, "a range inside the horizon is echoed as asked")
        // An earlier `from` is raised to the cutoff in the echo too (`to` stays); a lone `from` is also its own `to`: the cutoff day.
        val early = withHorizon(6) { client.dive("connectionId=$connId&epicId=NOPE-1&from=2025-01-01&to=2026-02-08") }
        assertEquals(CUTOFF to "2026-02-08", early.range.from to early.range.to)
        assertEquals(CUTOFF, early.meta.from)
        val lone = withHorizon(6) { client.dive("connectionId=$connId&epicId=NOPE-1&from=2025-01-01") }
        assertEquals(CUTOFF to CUTOFF, lone.range.from to lone.range.to)
    }
}
