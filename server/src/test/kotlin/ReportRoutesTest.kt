package ch.nokillswit

import ch.nokillswit.reports.ReportFilters
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /api/v1/reports/filters` (v0.3.0 M4 commit 10a) — any authenticated user (D12), no query
 * params. Reads against [DerivedStubFixture]'s connection (one FLO-mapped team, one completed
 * DERIVE) so every section of the response has something real to assert over.
 * `AnonymousAccessTest` covers the 401 sweep automatically (no `security: []` in the spec).
 */
class ReportRoutesTest {

    @Test
    fun `a non-admin caller gets 200 with the derived reference data`() = testApplication {
        usePostgresTestcontainer()
        val connectionId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-filters")

        val response = client.get("/api/v1/reports/filters")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ReportFilters>()

        assertTrue(body.teams.isNotEmpty(), "expected at least the derived fixture's own team")
        assertTrue(body.teams.any { it.sprints.isNotEmpty() }, "the FLO-mapped team should carry sprints from dim_sprint")
        assertTrue(body.domains.isNotEmpty(), "expected at least the derived fixture's own domain(s)")
        assertTrue(body.activityTypes.isNotEmpty(), "expected at least the derived fixture's own activity type(s)")
        assertTrue(
            body.connections.any { it.id == connectionId },
            "the derived fixture's own connection should be listed (active, even if disabled)",
        )
        assertTrue(body.derivedAt != null, "a SUCCEEDED derive_runs row exists — derivedAt must not be null")
        assertTrue(body.configRevision >= 1L)
        assertTrue(body.minSampleSize >= 1)
    }
}
