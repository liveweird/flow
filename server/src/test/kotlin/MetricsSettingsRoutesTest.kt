package ch.nokillswit

import ch.nokillswit.metrics.MetricsSettingsRequest
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.flowusers.FlowUserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ONE global `metrics.settings` singleton (v0.3.0 M1 commit 3): GET/PUT ADMIN-only,
 * `.claude/docs/domain-model.md` "Configuration". `metrics.settings` is shared, suite-wide state
 * (a singleton row, not a per-test fixture) — every test restores it via a trailing PUT of the
 * defaults so later tests (and re-runs) see a clean slate, the `TestSeedState` idiom.
 */
class MetricsSettingsRoutesTest {

    private val defaults = MetricsSettingsRequest(
        hoursPerDay = 8.0,
        timeZone = "Europe/Warsaw",
        weekendDays = listOf(6, 7),
        commitmentGraceMinutes = 0,
        minSampleSize = 5,
        agingWindowItems = 50,
        agingPercentiles = listOf(50, 85, 95),
        backlogWindowSprints = 3,
        epicDriftDays = 14,
        epicEstimateGapTolerancePercent = 25,
        // The suite's baseline (`PostgresTestSupport` switches the horizon off after migrating): restoring it must not re-arm a horizon.
        historyHorizonMonths = null,
    )

    private suspend fun restoreDefaults(client: io.ktor.client.HttpClient) {
        client.putJson("/api/v1/metrics-settings", defaults)
    }

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/metrics-settings").status)
        assertEquals(HttpStatusCode.Unauthorized, client.putJson("/api/v1/metrics-settings", defaults).status)
    }

    @Test
    fun `non-admin is 403 even with a malformed body - guard before decode`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("metricsuser")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/metrics-settings").status)
        assertEquals(HttpStatusCode.Forbidden, client.put("/api/v1/metrics-settings") { setBody("{ not json") }.status)
    }

    @Test
    fun `GET returns the seeded defaults - Europe-Warsaw, not UTC`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsdefaults", FlowUserRole.ADMIN)
        val settings = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>()
        // Another test in this shared suite may have PUT since boot; only the zone/weekend
        // defaults are pinned here — the values a fresh V15 migration seeds and every other test
        // restores afterward.
        assertTrue(settings.configRevision >= 1)
        assertEquals(8.0, settings.hoursPerDay)
        try {
            assertEquals("Europe/Warsaw", settings.timeZone)
            assertEquals(listOf(6, 7), settings.weekendDays)
        } finally {
            restoreDefaults(admin)
        }
    }

    @Test
    fun `PUT full-replaces and bumps the revision - then restores`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsput", FlowUserRole.ADMIN)
        val before = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>()
        val changed = defaults.copy(
            hoursPerDay = 7.5,
            timeZone = "UTC",
            weekendDays = listOf(7),
            commitmentGraceMinutes = 60,
            minSampleSize = 10,
            agingWindowItems = 25,
            agingPercentiles = listOf(50, 85, 90),
            backlogWindowSprints = 2,
            epicDriftDays = 7,
            epicEstimateGapTolerancePercent = 50,
            historyHorizonMonths = 60,
        )
        try {
            assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/metrics-settings", changed).status)
            val after = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>()
            assertEquals(before.configRevision + 1, after.configRevision)
            assertEquals(7.5, after.hoursPerDay)
            assertEquals("UTC", after.timeZone)
            assertEquals(listOf(7), after.weekendDays)
            assertEquals(60, after.commitmentGraceMinutes)
            assertEquals(10, after.minSampleSize)
            assertEquals(25, after.agingWindowItems)
            assertEquals(listOf(50, 85, 90), after.agingPercentiles)
            assertEquals(2, after.backlogWindowSprints)
            assertEquals(7, after.epicDriftDays)
            assertEquals(50, after.epicEstimateGapTolerancePercent)
            assertEquals(60, after.historyHorizonMonths)
            assertNotNull(after.updatedByUserId)
        } finally {
            restoreDefaults(admin)
        }
    }

    @Test
    fun `validation - bad zone, out-of-range numbers are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsbad", FlowUserRole.ADMIN)
        val cases: List<Pair<MetricsSettingsRequest, String>> = listOf(
            defaults.copy(hoursPerDay = 0.0) to "hoursPerDay = 0",
            defaults.copy(hoursPerDay = 25.0) to "hoursPerDay = 25",
            defaults.copy(timeZone = "Not/AZone") to "bad zone",
            defaults.copy(weekendDays = listOf(0)) to "weekendDays out of range (0)",
            defaults.copy(weekendDays = listOf(8)) to "weekendDays out of range (8)",
            defaults.copy(weekendDays = (1..7).toList()) to "all seven days marked non-working",
            defaults.copy(commitmentGraceMinutes = -1) to "negative commitmentGraceMinutes",
            defaults.copy(minSampleSize = 0) to "minSampleSize = 0",
            defaults.copy(agingWindowItems = 0) to "agingWindowItems = 0",
            defaults.copy(agingPercentiles = emptyList()) to "empty aging percentiles",
            defaults.copy(agingPercentiles = listOf(0)) to "aging percentile 0",
            defaults.copy(agingPercentiles = listOf(100)) to "aging percentile 100",
            defaults.copy(agingPercentiles = listOf(50, 50, 85)) to "duplicate aging percentiles",
            defaults.copy(agingPercentiles = listOf(50, 90)) to "aging percentiles missing the required 85",
            defaults.copy(backlogWindowSprints = 0) to "backlogWindowSprints = 0",
            defaults.copy(epicDriftDays = -1) to "negative epicDriftDays",
            defaults.copy(epicEstimateGapTolerancePercent = 101) to "epicEstimateGapTolerancePercent = 101",
            defaults.copy(epicEstimateGapTolerancePercent = -1) to "epicEstimateGapTolerancePercent = -1",
            defaults.copy(historyHorizonMonths = 5) to "historyHorizonMonths = 5 (below the minimum)",
            defaults.copy(historyHorizonMonths = 121) to "historyHorizonMonths = 121 (above the maximum)",
            defaults.copy(historyHorizonMonths = 0) to "historyHorizonMonths = 0",
        )
        for ((case, description) in cases) {
            assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/v1/metrics-settings", case).status, "expected 400 for $description")
        }
    }

    @Test
    fun `history horizon accepts null (off) and echoes historyHorizonFrom in the configured zone`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricshorizon", FlowUserRole.ADMIN)
        val zone = ZoneId.of("Pacific/Kiritimati") // UTC+14: the zone's "today" differs from the UTC one for ten hours of every day
        try {
            val dayBefore = LocalDate.now(zone)
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson("/api/v1/metrics-settings", defaults.copy(timeZone = zone.id, historyHorizonMonths = 6)).status,
            )
            val limited = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>()
            val dayAfter = LocalDate.now(zone)
            assertEquals(6, limited.historyHorizonMonths)
            // Computed before AND after the call so a midnight roll in the zone cannot flake the test.
            assertTrue(
                limited.historyHorizonFrom in setOf(dayBefore, dayAfter).map { it.minusMonths(6).toString() },
                "historyHorizonFrom ${limited.historyHorizonFrom} is today in ${zone.id} minus six months",
            )

            val offStatus = admin.putJson("/api/v1/metrics-settings", defaults.copy(historyHorizonMonths = null)).status
            assertEquals(HttpStatusCode.NoContent, offStatus)
            val off = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>()
            assertEquals(null, off.historyHorizonMonths, "null turns the horizon off")
            assertEquals(null, off.historyHorizonFrom, "no horizon, no first day")
            assertEquals(limited.configRevision + 1, off.configRevision, "turning it off bumps the revision like every setting")
        } finally {
            restoreDefaults(admin)
        }
    }

    @Test
    fun `a PUT without the historyHorizonMonths key is 400 - required-nullable, not defaulted`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricshorizonabsent", FlowUserRole.ADMIN)
        val body = """
            {"hoursPerDay":8.0,"timeZone":"Europe/Warsaw","weekendDays":[6,7],"commitmentGraceMinutes":0,"minSampleSize":5,
             "agingWindowItems":50,"agingPercentiles":[50,85,95],"backlogWindowSprints":3,"epicDriftDays":14,
             "epicEstimateGapTolerancePercent":25}
        """.trimIndent()
        val response = admin.put("/api/v1/metrics-settings") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a no-op PUT resubmitting identical settings bumps no revision and audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsnoop", FlowUserRole.ADMIN)
        restoreDefaults(admin)
        withAuditCapture { capture ->
            val before = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision
            val beforeCount = capture.events.count { it.message == "metrics_settings.updated" }
            assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/metrics-settings", defaults).status)
            val after = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision
            assertEquals(before, after, "identical settings must not bump the revision")
            assertEquals(
                beforeCount,
                capture.events.count { it.message == "metrics_settings.updated" },
                "identical settings must not audit",
            )
        }
    }

    @Test
    fun `changing only the epic estimate gap tolerance bumps the revision like every setting`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsgaprev", FlowUserRole.ADMIN)
        restoreDefaults(admin)
        try {
            val before = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>().configRevision
            assertEquals(
                HttpStatusCode.NoContent,
                admin.putJson("/api/v1/metrics-settings", defaults.copy(epicEstimateGapTolerancePercent = 30)).status,
            )
            val after = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>()
            assertEquals(before + 1, after.configRevision)
            assertEquals(30, after.epicEstimateGapTolerancePercent)
        } finally {
            restoreDefaults(admin)
        }
    }

    @Test
    fun `PUT audits metrics_settings-updated - then restores`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsaudit", FlowUserRole.ADMIN)
        withAuditCapture { capture ->
            try {
                admin.putJson("/api/v1/metrics-settings", defaults.copy(epicDriftDays = 21))
                val event = capture.awaitEvent { it.message == "metrics_settings.updated" }
                assertNotNull(event, "PUT must audit")
                assertTrue(event.keyValuePairs.any { it.key == "configRevision" })
            } finally {
                restoreDefaults(admin)
            }
        }
    }
}
