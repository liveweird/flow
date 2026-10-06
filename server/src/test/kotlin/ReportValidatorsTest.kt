package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.reports.DeepDiveEpicPageResponse
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.plugins.ProblemJson
import ch.nokillswit.plugins.REVALIDATED_CACHE_CONTROL
import ch.nokillswit.plugins.configureErrorHandling
import ch.nokillswit.plugins.configureHttp
import ch.nokillswit.plugins.configureSerialization
import ch.nokillswit.plugins.offerValidator
import ch.nokillswit.reports.IfNoneMatch
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.StampClock
import ch.nokillswit.reports.clockToken
import ch.nokillswit.reports.codeIdentityOf
import ch.nokillswit.reports.ReportFilters
import ch.nokillswit.reports.ifNoneMatch
import ch.nokillswit.reports.reportEtag
import ch.nokillswit.teams.TeamUpdateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.parametersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The report cache validators (`.claude/docs/reports.md` "Cache validators"): a weak `ETag` over the request and a data stamp read
 * BEFORE the report runs, `304` without running it on a matching `If-None-Match`. The pure pieces are unit-tested; the HTTP
 * ones read [DerivedStubFixture]'s connection (the all-operations sweep) or hand-built `derive_runs` rows on fresh DISABLED
 * connections (the stamp's sensitivity), always with an explicit `connectionId` where a shared fixture could collide.
 */
class ReportValidatorsTest {

    private val velocity = "/api/v1/reports/velocity"
    private val period = "from=2026-01-01&to=2026-01-31"

    // ---- the pure pieces ----------------------------------------------------------------------------------

    @Test
    fun `the etag is a weak sha256 base64url validator`() {
        val etag = reportEtag("jar-1", "/api/v1/reports/velocity", parametersOf("a" to listOf("1")), listOf("settings:1"))
        assertTrue(Regex("W/\"[A-Za-z0-9_-]{43}\"").matches(etag), etag)
    }

    @Test
    fun `the etag ignores the order of parameters but not repeated values, the path or the stamp`() {
        val stamp = listOf("settings:1", "run:1:2:3:4")
        val base = reportEtag("jar-1", "/p", parametersOf("a" to listOf("1"), "b" to listOf("x", "y")), stamp)
        assertEquals(base, reportEtag("jar-1", "/p", parametersOf("b" to listOf("x", "y"), "a" to listOf("1")), stamp), "parameter order")
        assertNotEquals(base, reportEtag("jar-1", "/p", parametersOf("a" to listOf("1"), "b" to listOf("y", "x")), stamp), "repeated order")
        assertNotEquals(base, reportEtag("jar-1", "/q", parametersOf("a" to listOf("1"), "b" to listOf("x", "y")), stamp), "path")
        assertNotEquals(base, reportEtag("jar-1", "/p", parametersOf("a" to listOf("2"), "b" to listOf("x", "y")), stamp), "value")
        val sameQuery = parametersOf("a" to listOf("1"), "b" to listOf("x", "y"))
        assertNotEquals(base, reportEtag("jar-1", "/p", sameQuery, stamp + "team:1"), "stamp")
        assertNotEquals(base, reportEtag("jar-2", "/p", parametersOf("a" to listOf("1"), "b" to listOf("x", "y")), stamp), "code identity")
    }

    @Test
    fun `stamp lines cannot blur into each other`() {
        val noParams = parametersOf()
        assertNotEquals(reportEtag("jar-1", "/p", noParams, listOf("ab", "c")), reportEtag("jar-1", "/p", noParams, listOf("a", "bc")))
    }

    @Test
    fun `If-None-Match follows RFC 9110 weak comparison`() {
        val etag = "W/\"abc\""
        assertEquals(IfNoneMatch.TAG_MATCH, ifNoneMatch(listOf("W/\"abc\""), etag), "same weak tag")
        assertEquals(IfNoneMatch.TAG_MATCH, ifNoneMatch(listOf("\"abc\""), etag), "strong form of the same opaque tag matches weakly")
        assertEquals(IfNoneMatch.TAG_MATCH, ifNoneMatch(listOf("W/\"x\", W/\"abc\" , \"y\""), etag), "a list")
        assertEquals(IfNoneMatch.TAG_MATCH, ifNoneMatch(listOf("\"x\"", "W/\"abc\""), etag), "several header lines")
        assertEquals(IfNoneMatch.NO_MATCH, ifNoneMatch(listOf("W/\"abd\""), etag), "another tag")
        assertEquals(IfNoneMatch.NO_MATCH, ifNoneMatch(listOf("abc"), etag), "an unquoted token is no entity-tag")
        assertEquals(IfNoneMatch.NO_MATCH, ifNoneMatch(emptyList(), etag))
        assertEquals(IfNoneMatch.NO_MATCH, ifNoneMatch(null, etag))
        assertEquals(IfNoneMatch.ANY, ifNoneMatch(listOf("*"), etag))
        assertEquals(IfNoneMatch.ANY, ifNoneMatch(listOf(" * "), etag))
        assertEquals(IfNoneMatch.TAG_MATCH, ifNoneMatch(listOf("*, W/\"abc\""), etag), "a listed match wins over the wildcard")
    }

    @Test
    fun `the code identity is the jar's hash, or a fresh id per boot for a directory`() {
        val dir = java.nio.file.Files.createTempDirectory("etag-code").toFile()
        try {
            val jarA = File(dir, "a.jar").apply { writeBytes("server code v1".toByteArray()) }
            val jarB = File(dir, "b.jar").apply { writeBytes("server code v1".toByteArray()) }
            val jarC = File(dir, "c.jar").apply { writeBytes("server code v2".toByteArray()) }
            val a = codeIdentityOf(jarA.toURI())
            assertTrue(a.startsWith("jar-"), a)
            assertEquals(a, codeIdentityOf(jarA.toURI()), "stable for the same artifact")
            assertEquals(a, codeIdentityOf(jarB.toURI()), "identical bytes, identical identity (every replica of one image agrees)")
            assertNotEquals(a, codeIdentityOf(jarC.toURI()), "a rebuilt artifact is a new identity")
            val first = codeIdentityOf(dir.toURI())
            assertTrue(first.startsWith("boot-"), "a classes directory has no artifact to hash: $first")
            assertNotEquals(first, codeIdentityOf(dir.toURI()), "random per call (REPORT_CODE_IDENTITY calls it once per boot)")
            assertTrue(codeIdentityOf(null).startsWith("boot-"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the clock token holds inside a bucket and moves across a five-minute bucket or local midnight`() {
        val warsaw = WorkingCalendar(ZoneId.of("Europe/Warsaw"), setOf(6, 7), emptySet())
        fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
        // Five-minute buckets: 10:00:00 and 10:04:59 share one, 10:05:00 starts the next.
        val tenAm = ms("2026-10-05T10:00:00Z")
        val fiveMin = StampClock.FIVE_MINUTES
        assertEquals(clockToken(fiveMin, warsaw, tenAm), clockToken(fiveMin, warsaw, ms("2026-10-05T10:04:59Z")))
        assertNotEquals(clockToken(fiveMin, warsaw, tenAm), clockToken(fiveMin, warsaw, ms("2026-10-05T10:05:00Z")))
        // Days: Warsaw is UTC+2 on 2026-10-05, so local midnight is 22:00Z — not the UTC midnight.
        val day = StampClock.DAY
        assertEquals(clockToken(day, warsaw, ms("2026-10-05T00:00:00Z")), clockToken(day, warsaw, ms("2026-10-05T21:59:59Z")))
        assertNotEquals(clockToken(day, warsaw, ms("2026-10-05T21:59:59Z")), clockToken(day, warsaw, ms("2026-10-05T22:00:00Z")))
        assertEquals("2026-10-06", clockToken(day, warsaw, ms("2026-10-05T22:00:00Z")))
        // The same instants are one day in UTC: the configured zone decides.
        val utc = WorkingCalendar(ZoneId.of("UTC"), setOf(6, 7), emptySet())
        assertEquals(clockToken(day, utc, ms("2026-10-05T21:59:59Z")), clockToken(day, utc, ms("2026-10-05T22:00:00Z")))
    }

    // ---- HTTP: the all-operations sweep -------------------------------------------------------------------

    /** Pins the report clock of the app under test (a fresh app per test, so no restore): the stamp's day/bucket cannot roll mid-test. */
    private fun ApplicationTestBuilder.pinReportClock() {
        val pinned = System.currentTimeMillis()
        application.attributes[ReportServiceKey].clock = { pinned }
    }

    private suspend fun HttpClient.conditional(url: String, ifNoneMatch: String? = null): HttpResponse =
        get(url) { ifNoneMatch?.let { header(HttpHeaders.IfNoneMatch, it) } }

    private suspend fun HttpClient.etagOf(url: String): String {
        val response = get(url)
        assertEquals(HttpStatusCode.OK, response.status, url)
        return assertNotNull(response.headers[HttpHeaders.ETag], "$url carries an ETag")
    }

    /** Every report GET of the spec with a request that answers 200 over [connectionId]'s derived data. */
    private suspend fun HttpClient.allReportOperations(connectionId: UInt): Map<String, String> {
        val reports = listOf(
            "velocity", "throughput", "sprint-consistency", "task-estimation-accuracy", "epic-estimation-accuracy", "estimate-adjustments",
            "cycle-time", "wip", "backlog", "aging-wip", "blocked-time", "epic-progress", "data-quality", "cost-matrix",
            "reported-time-ratio",
        )
        val filtersResponse = get("/api/v1/reports/filters")
        assertEquals(HttpStatusCode.OK, filtersResponse.status, filtersResponse.bodyAsText())
        val domain = filtersResponse.body<ReportFilters>().domains.first().domainKey
        val epicsResponse = get("/api/v1/reports/deep-dive/epics?connectionId=$connectionId&pageSize=1")
        assertEquals(HttpStatusCode.OK, epicsResponse.status, epicsResponse.bodyAsText())
        val epic = epicsResponse.body<DeepDiveEpicPageResponse>().items.firstOrNull()
        assertNotNull(epic, "the derived fixture has an epic for the deep dive")
        val base = "/api/v1/reports"
        return buildMap {
            put("$base/filters", "$base/filters")
            reports.forEach { put("$base/$it", "$base/$it?connectionId=$connectionId") }
            put("$base/deep-dive", "$base/deep-dive?epicId=${epic.key}&connectionId=$connectionId")
            put("$base/deep-dive/sprints", "$base/deep-dive/sprints?domain=$domain&connectionId=$connectionId")
            put("$base/deep-dive/epics", "$base/deep-dive/epics?connectionId=$connectionId")
            put("$base/deep-dive/epics/{epicKey}/tasks", "$base/deep-dive/epics/${epic.key}/tasks?connectionId=$connectionId")
        }
    }

    @Test
    fun `every report operation of the spec answers 200 with an ETag and 304 on a match`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connectionId = DerivedStubFixture.connectionId()
        val client = seededClient("etag-sweep")
        val operations = client.allReportOperations(connectionId)

        val spec = String(checkNotNull(ReportValidatorsTest::class.java.getResourceAsStream("/openapi/documentation.yaml")).readBytes())
        val declared = Regex("(?m)^  (/api/v1/reports/[^:]+):$").findAll(spec).map { it.groupValues[1] }.toSet()
        assertEquals(declared, operations.keys, "a new report operation must join the validators and this sweep")

        operations.forEach { (operation, url) ->
            val ok = client.get(url)
            assertEquals(HttpStatusCode.OK, ok.status, operation)
            val etag = assertNotNull(ok.headers[HttpHeaders.ETag], "$operation 200 ETag")
            assertTrue(etag.startsWith("W/\""), "$operation: a weak validator, was $etag")
            assertEquals(listOf(REVALIDATED_CACHE_CONTROL), ok.headers.getAll(HttpHeaders.CacheControl), "$operation 200 CC, once")
            assertTrue(ok.headers.getAll(HttpHeaders.Vary).orEmpty().any { "Authorization" in it }, "$operation Vary")

            val notModified = client.conditional(url, etag)
            assertEquals(HttpStatusCode.NotModified, notModified.status, operation)
            assertEquals("", notModified.bodyAsText(), "$operation: a 304 has no body")
            assertEquals(etag, notModified.headers[HttpHeaders.ETag], "$operation 304 ETag")
            assertEquals(listOf(REVALIDATED_CACHE_CONTROL), notModified.headers.getAll(HttpHeaders.CacheControl), "$operation 304 CC")
            assertTrue(notModified.headers.getAll(HttpHeaders.Vary).orEmpty().any { "Authorization" in it }, "$operation 304 Vary")
        }
    }

    @Test
    fun `an unauthenticated request is 401 whatever If-None-Match says`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val anonymous = jsonClient()
        listOf("*", "W/\"anything\"").forEach { validator ->
            val response = anonymous.conditional("$velocity?$period", validator)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertNull(response.headers[HttpHeaders.ETag])
        }
    }

    // ---- HTTP: matching rules and ordering ----------------------------------------------------------------

    @Test
    fun `If-None-Match matching and the ordering against validation`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-match", enabled = false)
        val client = seededClient("etag-match")
        val url = "$velocity?connectionId=$connectionId&$period"
        val etag = client.etagOf(url)
        val opaque = etag.removePrefix("W/")

        assertEquals(HttpStatusCode.NotModified, client.conditional(url, opaque).status, "the strong spelling matches weakly")
        assertEquals(HttpStatusCode.NotModified, client.conditional(url, "W/\"nope\", $etag").status, "a list holding it")
        val other = client.conditional(url, "W/\"nope\"")
        assertEquals(HttpStatusCode.OK, other.status, "a non-matching tag runs the report")
        assertEquals(etag, other.headers[HttpHeaders.ETag])
        val wildcard = client.conditional(url, "*")
        assertEquals(HttpStatusCode.NotModified, wildcard.status, "`*`: a representation exists")
        assertEquals("", wildcard.bodyAsText())
        assertEquals(etag, wildcard.headers[HttpHeaders.ETag])

        // Validation first: a malformed query is 400 even with `*`, and so is a request the data cannot satisfy (unknown team).
        val malformed = client.conditional("$velocity?connectionId=$connectionId&from=garbage", "*")
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        val unknownTeam = client.conditional("$url&teamId=2000000000", "*")
        assertEquals(HttpStatusCode.BadRequest, unknownTeam.status, "`*` never turns an unsatisfiable request into a 304")
        listOf(malformed, unknownTeam).forEach {
            assertNull(it.headers[HttpHeaders.ETag], "a problem response has no validator")
            assertEquals("no-store", it.headers[HttpHeaders.CacheControl], "a problem response stays no-store")
        }
    }

    // ---- HTTP: what moves the validator -------------------------------------------------------------------

    @Test
    fun `the etag follows the query, not its spelling`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val a = SyncedStubFixture.createConnection(namePrefix = "etag-query-a", enabled = false)
        val b = SyncedStubFixture.createConnection(namePrefix = "etag-query-b", enabled = false)
        val client = seededClient("etag-query")
        val etag = client.etagOf("$velocity?connectionId=$a&from=2026-01-01&to=2026-01-31")
        assertEquals(etag, client.etagOf("$velocity?to=2026-01-31&from=2026-01-01&connectionId=$a"), "a reordered query")
        assertNotEquals(etag, client.etagOf("$velocity?connectionId=$a&from=2026-01-01&to=2026-01-30"), "a changed parameter")
        assertNotEquals(etag, client.etagOf("$velocity?connectionId=$b&from=2026-01-01&to=2026-01-31"), "another connection scope")
        assertNotEquals(etag, client.etagOf("$velocity?from=2026-01-01&to=2026-01-31"), "the unit-wide scope")
        assertNotEquals(etag, client.etagOf("/api/v1/reports/throughput?connectionId=$a&from=2026-01-01&to=2026-01-31"), "another route")
    }

    private suspend fun insertRun(connId: UInt, startedAt: Long, finishedAt: Long, revision: Long = 1L) =
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.DeriveRuns.insert {
                it[MetricsTables.DeriveRuns.connectionId] = connId.toInt()
                it[configRevision] = revision
                it[processingVersion] = 1
                it[MetricsTables.DeriveRuns.startedAt] = startedAt
                it[MetricsTables.DeriveRuns.finishedAt] = finishedAt
                it[MetricsTables.DeriveRuns.status] = "SUCCEEDED"
            }
        }

    @Test
    fun `a new derive run of ANY active connection changes the etag, scoped or not`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val a = SyncedStubFixture.createConnection(namePrefix = "etag-derive-a", enabled = false)
        val b = SyncedStubFixture.createConnection(namePrefix = "etag-derive-b", enabled = false)
        val client = seededClient("etag-derive")
        val scopedA = "$velocity?connectionId=$a&$period"
        val unit = "$velocity?$period"
        try {
            val beforeA = client.etagOf(scopedA)
            val beforeUnit = client.etagOf(unit)
            assertEquals(beforeA, client.etagOf(scopedA), "stable while nothing changes")

            // Person names (norm.people) and /filters are read ACROSS connections, so another connection's derive moves a
            // report narrowed to connection a as well — the stamp covers every active connection, not just the one in scope.
            insertRun(b, startedAt = 1_000L, finishedAt = 2_000L)
            val afterB = client.etagOf(scopedA)
            assertNotEquals(beforeA, afterB, "another connection's derive, scoped report")
            assertNotEquals(beforeUnit, client.etagOf(unit), "another connection's derive, unit-wide report")

            insertRun(a, startedAt = 3_000L, finishedAt = 4_000L)
            val afterA = client.etagOf(scopedA)
            assertNotEquals(afterB, afterA, "a new derive of the scoped connection")
            // A 304 with the OLD validator no longer applies: the report runs again.
            assertEquals(HttpStatusCode.OK, client.conditional(scopedA, beforeA).status)
            assertEquals(HttpStatusCode.NotModified, client.conditional(scopedA, afterA).status)

            // The newest run is by id: an older-looking startedAt on a NEWER run still moves the stamp.
            insertRun(a, startedAt = 500L, finishedAt = 4_500L)
            assertNotEquals(afterA, client.etagOf(scopedA))
        } finally {
            deleteDeriveRuns(a)
            deleteDeriveRuns(b)
        }
    }

    @Test
    fun `a metrics settings revision bump changes the etag`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-settings", enabled = false)
        val client = seededClient("etag-settings")
        val url = "$velocity?connectionId=$connectionId&$period"
        val before = client.etagOf(url)
        val settings = DerivedStubFixture.metricsSettings()
        withMetricsSettings(settings, { it.copy(minSampleSize = it.minSampleSize + 1) }) {
            val during = client.etagOf(url)
            assertNotEquals(before, during, "a settings save bumps the live revision")
            assertEquals(HttpStatusCode.OK, client.conditional(url, before).status)
        }
    }

    @Test
    fun `team names and existence and the active connections move the etag`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val client = seededClient("etag-teams")
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-teams-base", enabled = false)
        val url = "$velocity?connectionId=$connectionId&$period"
        val unitUrl = "/api/v1/reports/filters"
        val teamId = TestTeams.seed("etag-team-${UUID.randomUUID()}")
        try {
            val before = client.etagOf(url)
            val filtersBefore = client.etagOf(unitUrl)
            TestTeams.service.update(teamId, TeamUpdateRequest(name = "etag-team-renamed-${UUID.randomUUID()}"))
            val renamed = client.etagOf(url)
            assertNotEquals(before, renamed, "a team rename relabels report groups, no DERIVE involved")
            assertNotEquals(filtersBefore, client.etagOf(unitUrl))
            TestTeams.service.delete(teamId)
            assertNotEquals(renamed, client.etagOf(url), "a soft-deleted team is no longer a valid teamId")

            val withConnection = client.etagOf(url)
            SyncedStubFixture.createConnection(namePrefix = "etag-teams-new", enabled = false)
            assertNotEquals(withConnection, client.etagOf(url), "the active connection list is part of the stamp")
        } finally {
            TestTeams.service.delete(teamId)
        }
    }

    @Test
    fun `a connection rename moves the etag`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-rename", enabled = false)
        val client = seededClient("etag-rename")
        val url = "$velocity?connectionId=$connectionId&$period"
        val before = client.etagOf(url)
        suspendTransaction(sharedDatabaseForTests()) {
            DataSourceService.Connections.update({ DataSourceService.Connections.id eq connectionId }) {
                it[name] = "etag-renamed-${UUID.randomUUID()}"
            }
        }
        assertNotEquals(before, client.etagOf(url), "the connection name is shown by /filters and the data-quality report")
    }

    @Test
    fun `a per-connection config PUT and a Jira membership edit move the etag through the revision bump`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-config", enabled = false)
        // A processed clone: the board mapping below needs the FLO board's `norm` rows to validate against.
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connectionId)
        val client = seededClient("etag-config")
        val url = "$velocity?connectionId=$connectionId&$period"
        val teamId = TestTeams.seed("etag-config-team-${UUID.randomUUID()}")
        val membershipService = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())
        try {
            val beforeConfig = client.etagOf(url)
            DerivedStubFixture.mapFloBoardToNewTeam(connectionId, DerivedStubFixture.metricsConfig(), "etag-config-board-team")
            val afterConfig = client.etagOf(url)
            assertNotEquals(beforeConfig, afterConfig, "a metrics-config PUT bumps the shared revision")

            val derivedConnectionId = DerivedStubFixture.connectionId()
            val account = suspendTransaction(sharedDatabaseForTests()) {
                WorkItemStore.People.select(WorkItemStore.People.accountId)
                    .where { WorkItemStore.People.connectionId eq derivedConnectionId }.limit(1).toList()
                    .single()[WorkItemStore.People.accountId]
            }
            val membership = membershipService.create(teamId, TeamMembershipCreateRequest(account, validFrom = 0L))
            val afterMembership = client.etagOf(url)
            assertNotEquals(afterConfig, afterMembership, "a Jira membership edit bumps the shared revision")
            membershipService.delete(teamId, membership.id)
            assertNotEquals(afterMembership, client.etagOf(url), "so does removing it")
        } finally {
            TestTeams.service.delete(teamId)
        }
    }

    // ---- the headers on a problem response (no DB: the HTTP plugins alone) ---------------------------------

    @Test
    fun `only a 200 or 304 carries the validator headers, a problem response never does`() = testApplication {
        environment { config = MapApplicationConfig("ktor.development" to "true") }
        application {
            configureSerialization()
            configureErrorHandling()
            configureHttp()
            routing {
                get("/api/probe-ok") {
                    call.offerValidator("W/\"probe\"")
                    call.respond(mapOf("ok" to true))
                }
                get("/api/probe-304") {
                    call.offerValidator("W/\"probe\"")
                    call.respond(HttpStatusCode.NotModified)
                }
                get("/api/probe-500") {
                    call.offerValidator("W/\"probe\"")
                    error("boom after the offer")
                }
                get("/api/probe-400") {
                    call.offerValidator("W/\"probe\"")
                    throw BadRequestException("nope")
                }
                get("/api/other") { call.respond(mapOf("ok" to true)) }
            }
        }
        val ok = client.get("/api/probe-ok")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("W/\"probe\"", ok.headers[HttpHeaders.ETag])
        assertEquals(listOf(REVALIDATED_CACHE_CONTROL), ok.headers.getAll(HttpHeaders.CacheControl))
        assertTrue(ok.headers.getAll(HttpHeaders.Vary).orEmpty().any { "Authorization" in it })

        val notModified = client.get("/api/probe-304")
        assertEquals(HttpStatusCode.NotModified, notModified.status)
        assertEquals("W/\"probe\"", notModified.headers[HttpHeaders.ETag])
        assertEquals(listOf(REVALIDATED_CACHE_CONTROL), notModified.headers.getAll(HttpHeaders.CacheControl))

        val failures = listOf("/api/probe-500" to HttpStatusCode.InternalServerError, "/api/probe-400" to HttpStatusCode.BadRequest)
        failures.forEach { (path, status) ->
            val problem = client.get(path)
            assertEquals(status, problem.status, path)
            assertEquals(ProblemJson.toString(), problem.contentType()?.withoutParameters()?.toString(), path)
            assertNull(problem.headers[HttpHeaders.ETag], "$path: a problem response carries no validator")
            assertEquals(listOf("no-store"), problem.headers.getAll(HttpHeaders.CacheControl), "$path stays no-store")
            assertNull(problem.headers[HttpHeaders.Vary]?.takeIf { "Authorization" in it })
        }
        // Any other API answer keeps the blanket no-store.
        assertEquals(listOf("no-store"), client.get("/api/other").headers.getAll(HttpHeaders.CacheControl))
    }
}
