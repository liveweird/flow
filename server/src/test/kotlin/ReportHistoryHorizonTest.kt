package ch.nokillswit

import ch.nokillswit.reports.AgingWipReport
import ch.nokillswit.reports.BacklogReport
import ch.nokillswit.reports.EpicProgressReport
import ch.nokillswit.reports.HistoryHorizonWire
import ch.nokillswit.reports.ReportFilters
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.ThroughputReport
import ch.nokillswit.reports.WipReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.BeforeClass

/**
 * The reporting history horizon at READ time (domain-model A48, `.claude/docs/reports.md` "History horizon") over the derived stub
 * fixture: the report clock is pinned to 2026-06-01 and the horizon to six months, so the cutoff is 2025-12-01 - inside the stub's
 * 2025-09 .. 2026-07 facts and sprints. The ONE-TRUTH rule is the spine: a report under the horizon over a period reaching back before
 * it is the same report, figure for figure, as the one WITHOUT a horizon over the period starting at the cutoff - and a drill's bar is
 * still its report's bar.
 */
class ReportHistoryHorizonTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()

        private val PINNED_NOW = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
        private const val CUTOFF = "2025-12-01"
        private const val WIDE_FROM = "2025-09-01"
        private const val TO = "2026-03-04"
        private const val TWO_DAYS_MS = 2 * 86_400_000L
    }

    private val settings = DerivedStubFixture.metricsSettings()

    private fun ApplicationTestBuilder.pinReportClock(at: Long = PINNED_NOW) {
        application.attributes[ReportServiceKey].clock = { at }
    }

    /** Runs [block] under a history horizon of [months] (null = off), the aging window wide open and `hoursPerDay` as the fixture's. */
    private suspend fun <T> withHorizon(months: Int?, backlogWindowSprints: Int? = null, block: suspend () -> T): T = withMetricsSettings(
        settings,
        {
            it.copy(
                historyHorizonMonths = months, hoursPerDay = DerivedStubFixture.HOURS_PER_DAY, agingWindowItems = 1000,
                backlogWindowSprints = backlogWindowSprints ?: it.backlogWindowSprints,
            )
        },
        block,
    )

    private suspend fun HttpClient.json(url: String): JsonObject {
        val response = get(url)
        assertEquals(HttpStatusCode.OK, response.status, url)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    /** The body minus what a horizon legitimately changes: `meta.historyHorizon` and the free-text `note`. */
    private fun JsonObject.withoutHorizon(): JsonObject = JsonObject(
        (this - "note" - "notes").mapValues { (key, value) ->
            if (key == "meta") JsonObject(value.jsonObject - "historyHorizon") else value
        },
    )

    private fun JsonObject.horizonOfMeta() = getValue("meta").jsonObject.getValue("historyHorizon")

    private suspend fun HttpClient.sprintsOf(teamId: UInt) = get("/api/v1/reports/filters").also {
        assertEquals(HttpStatusCode.OK, it.status)
    }.body<ReportFilters>().teams.first { team -> team.id == teamId }.sprints

    private fun cutoffMs(zone: String): Long = LocalDate.parse(CUTOFF).atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun `throughput, cycle time and costs under a horizon equal the same reports without it over the period from the cutoff`() =
        testApplication {
            usePostgresTestcontainer()
            pinReportClock()
            val connId = DerivedStubFixture.connectionId()
            val client = seededClient("horizon-one-truth")
            val scope = "connectionId=$connId&domain=${DerivedStubFixture.DOMAIN}"
            for (path in listOf("throughput", "cycle-time", "costs")) {
                val limited = withHorizon(6) { client.json("/api/v1/reports/$path?$scope&from=$WIDE_FROM&to=$TO") }
                val unlimited = withHorizon(null) { client.json("/api/v1/reports/$path?$scope&from=$CUTOFF&to=$TO") }
                val unlimitedWide = withHorizon(null) { client.json("/api/v1/reports/$path?$scope&from=$WIDE_FROM&to=$TO") }
                assertEquals(unlimited.withoutHorizon(), limited.withoutHorizon(), "$path: one truth")
                assertEquals("\"$CUTOFF\"", limited.getValue("meta").jsonObject.getValue("from").toString(), "$path: meta.from is clamped")
                assertEquals("""{"months":6,"from":"$CUTOFF"}""", limited.horizonOfMeta().toString(), path)
                assertEquals("null", unlimited.horizonOfMeta().toString(), "$path: off is an explicit null")
                assertNotEquals(unlimitedWide.withoutHorizon(), limited.withoutHorizon(), "$path: the cut must remove something")
            }
        }

    @Test
    fun `a throughput bar and its drill agree under the horizon and equal the drill without it over the cutoff period`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("horizon-drill")
        val scope = "connectionId=$connId&domain=${DerivedStubFixture.DOMAIN}&bucket=MONTH"
        val report = withHorizon(6) { client.get("/api/v1/reports/throughput?$scope&from=$WIDE_FROM&to=$TO").body<ThroughputReport>() }
        val bars = report.byBucket.filter { it.deliveredItems > 0 }
        assertTrue(bars.isNotEmpty(), "the cutoff period has deliveries")
        assertTrue(report.byBucket.all { it.bucketStart >= CUTOFF }, "no bucket before the cutoff's month")
        for (bar in bars) {
            val items = "/api/v1/reports/throughput/items?$scope"
            val drill = withHorizon(6) { client.json("$items&from=$WIDE_FROM&to=$TO&bucketStart=${bar.bucketStart}") }
            val plain = withHorizon(null) { client.json("$items&from=$CUTOFF&to=$TO&bucketStart=${bar.bucketStart}") }
            val drilledCount = drill.getValue("bar").jsonObject.getValue("count").toString().toInt()
            assertEquals(bar.deliveredItems, drilledCount, "bar ${bar.bucketStart}")
            assertEquals(plain.withoutHorizon(), drill.withoutHorizon(), "drill ${bar.bucketStart}: one truth")
        }
    }

    @Test
    fun `lastSprints counts only sprints completed on or after the cutoff and a sprintId closed before it is 400`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val teamId = DerivedStubFixture.teamId()
        val client = seededClient("horizon-sprints")
        val zone = withHorizon(null) { client.get("/api/v1/reports/filters").body<ReportFilters>().timeZone }
        val cutoff = cutoffMs(zone)
        val allSprints = withHorizon(null) { client.sprintsOf(teamId) }
        val closedBefore = allSprints.filter { s -> s.completeAt != null && s.completeAt < cutoff }
        val expected = allSprints.filter { s -> s.completeAt != null && s.completeAt >= cutoff }.map { it.sprintId }.toSet()
        assertTrue(closedBefore.isNotEmpty() && expected.isNotEmpty() && allSprints.size <= 52, "the cutoff splits the fixture's sprints")

        val scope = "connectionId=$connId&teamId=$teamId"
        withHorizon(6) {
            val report = client.get("/api/v1/reports/throughput?$scope&lastSprints=52").body<ThroughputReport>()
            assertEquals(expected, report.meta.resolvedSprints.flatMap { it.sprintIds }.toSet())
            val refused = client.get("/api/v1/reports/throughput?$scope&sprintId=${closedBefore.first().sprintId}")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(
                "sprintId ${closedBefore.first().sprintId} closed before the history horizon ($CUTOFF)" in refused.bodyAsText(),
                refused.bodyAsText(),
            )
            val kept = client.get("/api/v1/reports/throughput?$scope&sprintId=${expected.first()}")
            assertEquals(HttpStatusCode.OK, kept.status)
        }
        val unlimited = withHorizon(null) { client.get("/api/v1/reports/throughput?$scope&lastSprints=52").body<ThroughputReport>() }
        assertEquals(
            allSprints.filter { it.completeAt != null }.map { it.sprintId }.toSet(),
            unlimited.meta.resolvedSprints.flatMap { it.sprintIds }.toSet(),
        )
    }

    @Test
    fun `filters echo the horizon and list no sprint closed before it, a period entirely before it is 400`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val teamId = DerivedStubFixture.teamId()
        val client = seededClient("horizon-filters")
        val zone = withHorizon(null) { client.get("/api/v1/reports/filters").body<ReportFilters>().timeZone }
        val before = withHorizon(null) { client.get("/api/v1/reports/filters").body<ReportFilters>() }
        assertNull(before.historyHorizon)
        withHorizon(6) {
            val filters = client.get("/api/v1/reports/filters").body<ReportFilters>()
            assertEquals(HistoryHorizonWire(6, CUTOFF), filters.historyHorizon)
            val cutoff = cutoffMs(zone)
            val sprints = filters.teams.first { it.id == teamId }.sprints
            assertTrue(sprints.isNotEmpty() && sprints.all { it.completeAt == null || it.completeAt >= cutoff }, "$sprints")
            val all = before.teams.first { it.id == teamId }.sprints
            assertEquals(all.filter { it.completeAt == null || it.completeAt >= cutoff }.map { it.sprintId }, sprints.map { it.sprintId })
            assertTrue(sprints.size < all.size)

            val refused = client.get("/api/v1/reports/throughput?connectionId=$connId&teamId=$teamId&from=2025-01-01&to=2025-11-30")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue("from/to lies entirely before the history horizon ($CUTOFF)" in refused.bodyAsText(), refused.bodyAsText())
            val edge = client.get("/api/v1/reports/throughput?connectionId=$connId&teamId=$teamId&from=2025-01-01&to=$CUTOFF")
            assertEquals(HttpStatusCode.OK, edge.status, "a period ending on the cutoff day still overlaps it")
        }
    }

    @Test
    fun `the wip series starts at the cutoff, a sprint window straddling it says why`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val teamId = DerivedStubFixture.teamId()
        val client = seededClient("horizon-wip")
        val zone = withHorizon(null) { client.get("/api/v1/reports/filters").body<ReportFilters>().timeZone }
        val cutoff = cutoffMs(zone)
        val straddling = withHorizon(null) { client.sprintsOf(teamId) }.first { s ->
            s.startAt != null && s.completeAt != null && s.startAt < cutoff - TWO_DAYS_MS && s.completeAt >= cutoff + TWO_DAYS_MS
        }
        val scope = "connectionId=$connId&teamId=$teamId"
        val note = "Before $CUTOFF history is hidden (history horizon: 6 months)"
        withHorizon(6) {
            val bySprint = client.get("/api/v1/reports/wip?$scope&sprintId=${straddling.sprintId}").body<WipReport>()
            assertEquals(CUTOFF, bySprint.series.first().day)
            assertTrue(note in bySprint.note.orEmpty(), bySprint.note)
            val byRange = client.get("/api/v1/reports/wip?$scope&from=$WIDE_FROM&to=$TO").body<WipReport>()
            assertEquals(CUTOFF, byRange.series.first().day, "a requested range is clamped by the parser")
        }
        val unlimited = withHorizon(null) { client.get("/api/v1/reports/wip?$scope&sprintId=${straddling.sprintId}").body<WipReport>() }
        assertTrue(unlimited.series.first().day < CUTOFF && note !in unlimited.note.orEmpty(), "off keeps the whole sprint window")
    }

    @Test
    fun `home lists no last sprint for a team whose last sprint closed before the horizon`() = testApplication {
        usePostgresTestcontainer()
        // Two years on, six months back is past every sprint of the fixture; the Home windows themselves are not clamped.
        pinReportClock(Instant.parse("2028-06-01T00:00:00Z").toEpochMilli())
        DerivedStubFixture.connectionId()
        val teamId = DerivedStubFixture.teamId()
        val client = seededClient("horizon-home")
        fun JsonObject.lastSprintOfTheTeam() = getValue("teams").jsonArray.single().jsonObject.getValue("lastSprint").toString()
        val limited = withHorizon(6) { client.json("/api/v1/reports/home?teamId=$teamId") }
        val unlimited = withHorizon(null) { client.json("/api/v1/reports/home?teamId=$teamId") }
        assertEquals("null", limited.lastSprintOfTheTeam())
        assertTrue(unlimited.lastSprintOfTheTeam().startsWith("{"), "off keeps the team's last sprint")
        assertEquals("""{"months":6,"from":"2027-12-01"}""", limited.horizonOfMeta().toString())
    }

    @Test
    fun `aging thresholds and the backlog mean skip work that finished before the cutoff`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connId = DerivedStubFixture.connectionId()
        val teamId = DerivedStubFixture.teamId()
        val client = seededClient("horizon-reference")
        val scope = "connectionId=$connId&teamId=$teamId"
        val limitedAging = withHorizon(6) { client.get("/api/v1/reports/aging-wip?$scope").body<AgingWipReport>() }
        val unlimitedAging = withHorizon(null) { client.get("/api/v1/reports/aging-wip?$scope").body<AgingWipReport>() }
        assertTrue(unlimitedAging.thresholds.n > 0, "the fixture has finished tasks")
        assertTrue(limitedAging.thresholds.n < unlimitedAging.thresholds.n, "done tasks before the cutoff leave the reference set")

        // A window wider than the fixture's sprint count, so the mean reaches back across the cutoff.
        val period = "from=$WIDE_FROM&to=$TO"
        val limitedBacklog = withHorizon(6, backlogWindowSprints = 50) {
            client.get("/api/v1/reports/backlog?$scope&$period").body<BacklogReport>()
        }
        val unlimitedBacklog = withHorizon(null, backlogWindowSprints = 50) {
            client.get("/api/v1/reports/backlog?$scope&$period").body<BacklogReport>()
        }
        assertTrue(unlimitedBacklog.current.sprintsUsed > 0, "sprints closed in the fixture feed the mean")
        assertTrue(limitedBacklog.current.sprintsUsed > 0, "sprints closed after the cutoff still feed it")
        assertTrue(
            limitedBacklog.current.sprintsUsed < unlimitedBacklog.current.sprintsUsed,
            "sprints closed before the cutoff are skipped",
        )
        assertNotEquals(unlimitedBacklog.current.meanDeliveredMd, limitedBacklog.current.meanDeliveredMd, "so is their delivery")
        assertEquals(CUTOFF, limitedBacklog.trend.first().day)
    }

    @Test
    fun `a cutoff in the middle of a month gives a partial first bucket, and its bar still equals its drill`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock(Instant.parse("2026-06-15T00:00:00Z").toEpochMilli())
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("horizon-mid-month")
        val midCutoff = "2025-12-15"
        val scope = "connectionId=$connId&domain=${DerivedStubFixture.DOMAIN}&bucket=MONTH"
        val limited = withHorizon(6) { client.json("/api/v1/reports/throughput?$scope&from=$WIDE_FROM&to=$TO") }
        val plain = withHorizon(null) { client.json("/api/v1/reports/throughput?$scope&from=$midCutoff&to=$TO") }
        val unlimited = withHorizon(null) { client.json("/api/v1/reports/throughput?$scope&from=$CUTOFF&to=$TO") }
        assertEquals(plain.withoutHorizon(), limited.withoutHorizon(), "the clamped range is the no-horizon range from the cutoff")
        assertEquals("""{"months":6,"from":"$midCutoff"}""", limited.horizonOfMeta().toString())
        assertNotEquals(unlimited.withoutHorizon(), limited.withoutHorizon(), "the half month before the cutoff really is left out")
        val bars = withHorizon(6) {
            client.get("/api/v1/reports/throughput?$scope&from=$WIDE_FROM&to=$TO").body<ThroughputReport>().byBucket
        }
        assertTrue(bars.any { it.deliveredItems > 0 })
        for (bar in bars.filter { it.deliveredItems > 0 }) {
            val drill = withHorizon(6) {
                client.json("/api/v1/reports/throughput/items?$scope&from=$WIDE_FROM&to=$TO&bucketStart=${bar.bucketStart}")
            }
            assertEquals(bar.deliveredItems, drill.getValue("bar").jsonObject.getValue("count").toString().toInt(), bar.bucketStart)
        }
    }

    @Test
    fun `epic progress over a sprint straddling the cutoff starts its series at the cutoff, as the clamped from-to period does`() =
        testApplication {
            usePostgresTestcontainer()
            pinReportClock()
            val connId = DerivedStubFixture.connectionId()
            val teamId = DerivedStubFixture.teamId()
            val client = seededClient("horizon-epic-progress")
            val zone = withHorizon(null) { client.get("/api/v1/reports/filters").body<ReportFilters>().timeZone }
            val cutoff = cutoffMs(zone)
            val straddling = withHorizon(null) { client.sprintsOf(teamId) }.first { s ->
                s.startAt != null && s.completeAt != null && s.startAt < cutoff - TWO_DAYS_MS && s.completeAt >= cutoff + TWO_DAYS_MS
            }
            val endDay = Instant.ofEpochMilli(straddling.completeAt!!).atZone(ZoneId.of(zone)).toLocalDate()
            val scope = "connectionId=$connId&teamId=$teamId"
            val bySprint = withHorizon(6) {
                client.get("/api/v1/reports/epic-progress?$scope&sprintId=${straddling.sprintId}").body<EpicProgressReport>()
            }
            val clamped = withHorizon(null) {
                client.get("/api/v1/reports/epic-progress?$scope&from=$CUTOFF&to=$endDay").body<EpicProgressReport>()
            }
            val whole = withHorizon(null) {
                client.get("/api/v1/reports/epic-progress?$scope&sprintId=${straddling.sprintId}").body<EpicProgressReport>()
            }
            assertEquals(CUTOFF, bySprint.series.first().date)
            assertEquals(clamped.series, bySprint.series, "one truth: the series")
            assertEquals(clamped.asOf, bySprint.asOf)
            assertEquals(clamped.period, bySprint.period)
            assertEquals(clamped.trend, bySprint.trend)
            assertTrue("Before $CUTOFF history is hidden (history horizon: 6 months)" in bySprint.note.orEmpty(), bySprint.note)
            assertTrue(whole.series.first().date < CUTOFF, "off keeps the whole sprint window")
            assertEquals(whole.asOf, bySprint.asOf, "the cumulative baselines stay whole-life")
        }

    @Test
    fun `when the horizon hides every sprint the empty answer says so`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock(Instant.parse("2028-06-01T00:00:00Z").toEpochMilli())
        val connId = DerivedStubFixture.connectionId()
        val teamId = DerivedStubFixture.teamId()
        val client = seededClient("horizon-no-sprint")
        val scope = "connectionId=$connId&teamId=$teamId&lastSprints=5"
        val limited = withHorizon(6) { client.get("/api/v1/reports/wip?$scope").body<WipReport>() }
        val unlimited = withHorizon(null) { client.get("/api/v1/reports/wip?$scope").body<WipReport>() }
        val said = "history horizon: 6 months hides the sprints completed before 2027-12-01"
        assertTrue(limited.series.isEmpty() && said in limited.note.orEmpty(), limited.note)
        assertTrue("history horizon" !in unlimited.note.orEmpty(), "off: no horizon talk")
    }
}
