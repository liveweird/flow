package ch.nokillswit

import ch.nokillswit.metrics.MetricsSettingsRequest
import ch.nokillswit.metrics.MetricsSettingsResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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
        holidays = emptyList(),
        commitmentGraceMinutes = 0,
        minSampleSize = 5,
        agingWindowItems = 50,
        agingPercentiles = listOf(50, 85, 95),
        backlogWindowSprints = 3,
        epicDriftDays = 14,
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
        val admin = seededClient("metricsdefaults", UserRole.ADMIN)
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
        val admin = seededClient("metricsput", UserRole.ADMIN)
        val before = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>()
        val changed = defaults.copy(
            hoursPerDay = 7.5,
            timeZone = "UTC",
            weekendDays = listOf(7),
            holidays = listOf("2026-12-25"),
            commitmentGraceMinutes = 60,
            minSampleSize = 10,
            agingWindowItems = 25,
            agingPercentiles = listOf(50, 85, 90),
            backlogWindowSprints = 2,
            epicDriftDays = 7,
        )
        try {
            assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/metrics-settings", changed).status)
            val after = admin.get("/api/v1/metrics-settings").body<MetricsSettingsResponse>()
            assertEquals(before.configRevision + 1, after.configRevision)
            assertEquals(7.5, after.hoursPerDay)
            assertEquals("UTC", after.timeZone)
            assertEquals(listOf(7), after.weekendDays)
            assertEquals(listOf("2026-12-25"), after.holidays)
            assertEquals(60, after.commitmentGraceMinutes)
            assertEquals(10, after.minSampleSize)
            assertEquals(25, after.agingWindowItems)
            assertEquals(listOf(50, 85, 90), after.agingPercentiles)
            assertEquals(2, after.backlogWindowSprints)
            assertEquals(7, after.epicDriftDays)
            assertNotNull(after.updatedByUserId)
        } finally {
            restoreDefaults(admin)
        }
    }

    @Test
    fun `validation - bad zone, bad holiday, out-of-range numbers are 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsbad", UserRole.ADMIN)
        val tooManyHolidays = (0 until 367).map { java.time.LocalDate.of(2020, 1, 1).plusDays(it.toLong()).toString() }
        val cases: List<Pair<MetricsSettingsRequest, String>> = listOf(
            defaults.copy(hoursPerDay = 0.0) to "hoursPerDay = 0",
            defaults.copy(hoursPerDay = 25.0) to "hoursPerDay = 25",
            defaults.copy(timeZone = "Not/AZone") to "bad zone",
            defaults.copy(weekendDays = listOf(0)) to "weekendDays out of range (0)",
            defaults.copy(weekendDays = listOf(8)) to "weekendDays out of range (8)",
            defaults.copy(weekendDays = (1..7).toList()) to "all seven days marked non-working",
            defaults.copy(holidays = listOf("not-a-date")) to "bad holiday date",
            defaults.copy(holidays = tooManyHolidays) to "more than 366 distinct holidays",
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
        )
        for ((case, description) in cases) {
            assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/v1/metrics-settings", case).status, "expected 400 for $description")
        }
    }

    @Test
    fun `a no-op PUT resubmitting identical settings bumps no revision and audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsnoop", UserRole.ADMIN)
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
    fun `PUT audits metrics_settings-updated - then restores`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("metricsaudit", UserRole.ADMIN)
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
