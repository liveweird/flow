package ch.nokillswit

import ch.nokillswit.infra.crypto.DEV_DATA_ENCRYPTION_KEY
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.ingest.DataSourceRequest
import ch.nokillswit.ingest.DataSourceResponse
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.JiraAuthScheme
import ch.nokillswit.ingest.JiraConnectionRequest
import ch.nokillswit.ingest.JobHandlerRegistry
import ch.nokillswit.ingest.ScopeRules
import ch.nokillswit.ingest.backfillPolicyFor
import ch.nokillswit.ingest.jobHandlerRegistry
import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backfill floor (domain-model A48, `.claude/docs/ingestion.md` "Data sources"): a data source's `backfillFrom` may not be
 * created or MOVED before the history horizon's first day, applied at WRITE time only - a stored older date stays legal, since the
 * stored date is a running backfill's identity. The floor reaches `ingest/` through `JobHandlerRegistry` (`metrics/` registers it),
 * so a service built without a registered source keeps today's rules.
 */
class DataSourceBackfillFloorTest {

    private val settings = DerivedStubFixture.metricsSettings()

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun request(
        backfillFrom: String?,
        name: String = "floor-${UUID.randomUUID().toString().take(8)}",
        keys: List<String> = uniqueProjectKeys(1),
        apiToken: String? = "token-${UUID.randomUUID()}",
    ) = DataSourceRequest(
        name = name,
        enabled = false,
        syncIntervalMinutes = 60,
        backfillFrom = backfillFrom,
        reconcileHourUtc = 3,
        jira = JiraConnectionRequest(TEST_JIRA_SITE_URL, "svc-${UUID.randomUUID()}@example.com", apiToken, keys, JiraAuthScheme.BASIC),
    )

    /** The horizon's first day as the settings show it, read before and after [block] so a midnight roll cannot flake the equality. */
    private suspend fun horizonFrom(): LocalDate = LocalDate.parse(checkNotNull(settings.read().historyHorizonFrom))

    private suspend fun detailOf(response: HttpResponse): String? = response.body<ProblemDetail>().detail

    @Test
    fun `create below the history horizon floor is 400 and a day inside it is accepted`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("floorcreate", FlowUserRole.ADMIN)
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = 6) }) {
            val floor = horizonFrom()
            val tooOld = admin.postJson("/api/v1/data-sources", request(floor.minusDays(1).toString()))
            assertEquals(HttpStatusCode.BadRequest, tooOld.status)
            val detail = assertNotNull(detailOf(tooOld))
            assertTrue("backfillFrom must not be before" in detail && "(the history horizon)" in detail, detail)
            // 24 months back is the default horizon's day: far below a 6-month floor.
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/data-sources", request(today().minusMonths(24).toString())).status,
            )
            val onTheFloor = admin.postJson("/api/v1/data-sources", request(floor.toString()))
            assertEquals(HttpStatusCode.Created, onTheFloor.status, "the floor day itself is allowed")
            assertEquals(floor.toString(), onTheFloor.body<DataSourceResponse>().backfillFrom)
            val inside = admin.postJson("/api/v1/data-sources", request(floor.plusDays(1).toString()))
            assertEquals(HttpStatusCode.Created, inside.status)
            assertEquals(floor.plusDays(1).toString(), inside.body<DataSourceResponse>().backfillFrom)
        }
    }

    @Test
    fun `update keeping an older stored backfillFrom is accepted and moving it earlier is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("floorupdate", FlowUserRole.ADMIN)
        val keys = uniqueProjectKeys(1)
        val stored = today().minusMonths(20).toString()
        val id = withMetricsSettings(settings, { it.copy(historyHorizonMonths = 24) }) {
            admin.postJson("/api/v1/data-sources", request(stored, keys = keys)).body<DataSourceResponse>().id
        }
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = 6) }) {
            val floor = horizonFrom()
            assertTrue(LocalDate.parse(stored).isBefore(floor), "the stored date is older than the new floor")
            suspend fun put(backfillFrom: String) =
                admin.putJson("/api/v1/data-sources/$id", request(backfillFrom, name = "renamed-$id", keys = keys, apiToken = null))
            assertEquals(HttpStatusCode.NoContent, put(stored).status, "an unchanged older date is not re-checked")
            assertEquals(HttpStatusCode.BadRequest, put(today().minusMonths(21).toString()).status, "moving earlier is checked")
            val stillOld = put(floor.minusDays(1).toString())
            assertEquals(HttpStatusCode.BadRequest, stillOld.status, "moving to another day before the floor is checked too")
            assertTrue("(the history horizon)" in assertNotNull(detailOf(stillOld)))
            assertEquals(HttpStatusCode.NoContent, put(floor.plusDays(1).toString()).status, "moving later, inside the floor, is fine")
            assertEquals(floor.plusDays(1).toString(), admin.get("/api/v1/data-sources/$id").body<DataSourceResponse>().backfillFrom)
        }
    }

    /** Creates with a blank date and returns the stored one; [expected] is read before and after so a midnight roll cannot flake it. */
    private suspend fun HttpClient.createBlankAndCheck(expected: suspend () -> LocalDate) {
        val before = expected()
        val created = postJson("/api/v1/data-sources", request(null)).body<DataSourceResponse>()
        val after = expected()
        assertTrue(created.backfillFrom in setOf(before.toString(), after.toString()), created.backfillFrom)
    }

    @Test
    fun `a blank backfillFrom defaults to 24 months back, raised to the horizon day when that is later`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("floordefault", FlowUserRole.ADMIN)
        // 9 months: the floor is later than 24 months back, so the horizon day wins.
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = 9) }) { admin.createBlankAndCheck { horizonFrom() } }
        // 36 months: the horizon only floors, the default does not widen to it.
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = 36) }) {
            admin.createBlankAndCheck { today().minusMonths(24) }
        }
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = null) }) {
            admin.createBlankAndCheck { today().minusMonths(24) }
            // Off: only the legacy 10-year floor binds, so an old date is legal again.
            val old = admin.postJson("/api/v1/data-sources", request(today().minusYears(5).toString()))
            assertEquals(HttpStatusCode.Created, old.status)
        }
    }

    @Test
    fun `a blank backfillFrom on PUT stores the default even when the stored date is older than the horizon`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("floorblankput", FlowUserRole.ADMIN)
        val keys = uniqueProjectKeys(1)
        val stored = today().minusMonths(30).toString()
        val id = withMetricsSettings(settings, { it.copy(historyHorizonMonths = null) }) {
            admin.postJson("/api/v1/data-sources", request(stored, keys = keys)).body<DataSourceResponse>().id
        }
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = 12) }) {
            val floor = horizonFrom()
            assertTrue(LocalDate.parse(stored).isBefore(floor), "the stored date is older than the horizon")
            val put = admin.putJson("/api/v1/data-sources/$id", request(null, name = "blank-$id", keys = keys, apiToken = null))
            assertEquals(HttpStatusCode.NoContent, put.status, "a blank date resets to the default, which passes the floor")
            // 24 months back raised to the 12-month floor = the floor.
            val storedNow = admin.get("/api/v1/data-sources/$id").body<DataSourceResponse>().backfillFrom
            assertTrue(storedNow in setOf(floor.toString(), horizonFrom().toString()), storedNow)
        }
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = 60) }) {
            val before = today().minusMonths(24)
            val put = admin.putJson("/api/v1/data-sources/$id", request(null, name = "blank2-$id", keys = keys, apiToken = null))
            assertEquals(HttpStatusCode.NoContent, put.status)
            // A long horizon does not widen the default to the horizon: still 24 months back.
            val storedNow = admin.get("/api/v1/data-sources/$id").body<DataSourceResponse>().backfillFrom
            assertTrue(storedNow in setOf(before.toString(), today().minusMonths(24).toString()), storedNow)
        }
    }

    @Test
    fun `the floor is the later of the horizon and the legacy floor, and the default is 24 months back raised to it`() {
        val legacyFloor = backfillPolicyFor(null).floor
        assertEquals(today().minusYears(10).let { maxOf(LocalDate.of(2000, 1, 1), it) }, legacyFloor)
        assertEquals(today().minusMonths(24), backfillPolicyFor(null).defaultFrom, "off: today's 24-month default")

        val sixMonths = today().minusMonths(6)
        assertEquals(backfillPolicyFor(sixMonths).floor, sixMonths)
        assertEquals(sixMonths, backfillPolicyFor(sixMonths).defaultFrom, "a floor later than 24 months back raises the default")

        val threeYears = today().minusMonths(36)
        assertEquals(threeYears, backfillPolicyFor(threeYears).floor)
        assertEquals(today().minusMonths(24), backfillPolicyFor(threeYears).defaultFrom, "a horizon never widens the default")

        // A 120-month horizon computed in a zone BEHIND UTC lands a day earlier than the UTC-based legacy floor: the legacy floor wins.
        val tooFar = today().minusYears(10).minusDays(1)
        val policy = backfillPolicyFor(tooFar)
        assertEquals(legacyFloor, policy.floor)
        assertEquals(today().minusMonths(24), policy.defaultFrom, "a blank backfillFrom is 24 months back, inside the floor")
    }

    @Test
    fun `a service with no floor source registered keeps the 24-month default and the 10-year floor`() = runBlocking<Unit> {
        val service = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY), ScopeRules.OFF)
        assertEquals(backfillPolicyFor(null), service.backfillPolicy())
        // A date no horizon would allow (the horizon is 24 months by default) is accepted when nothing registered a floor ...
        val id = service.create(request(today().minusYears(9).toString()))
        assertEquals(today().minusYears(9).toString(), assertNotNull(service.read(id)).backfillFrom)
        // ... and the legacy 10-year floor still binds.
        val failure = assertFailsWith<BadRequestException> { service.create(request(today().minusYears(11).toString())) }
        assertTrue("backfillFrom must not be before" in failure.message.orEmpty())
        // A source that returns a horizon is the floor.
        val registry = JobHandlerRegistry().apply { registerBackfillFloorSource { today().minusMonths(6) } }
        val floored = DataSourceService(sharedDatabaseForTests(), FieldCipher(DEV_DATA_ENCRYPTION_KEY), ScopeRules.OFF, registry)
        assertEquals(today().minusMonths(6), floored.backfillPolicy().floor)
        assertFailsWith<BadRequestException> { floored.create(request(today().minusMonths(7).toString())) }
    }

    @Test
    fun `the running app registers the floor source, which follows the history horizon setting`() = testApplication {
        usePostgresTestcontainer()
        startApplication()
        val source = assertNotNull(application.jobHandlerRegistry().backfillFloorSource, "metrics registers the floor source at boot")
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = 12) }) {
            val before = horizonFrom()
            val floor = assertNotNull(source.floor())
            assertTrue(floor in setOf(before, horizonFrom()), "the source reads the live setting: $floor")
        }
        withMetricsSettings(settings, { it.copy(historyHorizonMonths = null) }) {
            assertNull(source.floor(), "horizon off: no floor of its own")
        }
    }
}
