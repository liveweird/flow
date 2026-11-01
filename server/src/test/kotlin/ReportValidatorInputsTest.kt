package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.IdentityLink
import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MIN_HISTORY_HORIZON_MONTHS
import ch.nokillswit.metrics.SprintCalendarCell
import ch.nokillswit.metrics.SprintCalendarDay
import ch.nokillswit.metrics.SprintCalendarDocument
import ch.nokillswit.metrics.SprintCalendarPerson
import ch.nokillswit.metrics.SprintCalendarService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamCapacityRequest
import ch.nokillswit.metrics.TeamWorkCategoryTarget
import ch.nokillswit.metrics.TeamWorkCategoryTargetsRequest
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamUpdateRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.junit.BeforeClass

/**
 * What moves a report's validator beyond the derive stamp (`.claude/docs/reports.md` "Cache validators", the stamp-input table): the inputs
 * a report reads AT QUERY TIME rather than through DERIVE - work-category targets (no revision bump of their own), a connection's
 * `updated_at`, public holidays and team capacity (through the shared revision) - and Compare people's caller-shaped capacity verdict.
 */
class ReportValidatorInputsTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }

    private val costs = "/api/v1/reports/costs"

    private fun ApplicationTestBuilder.pinReportClock() {
        val pinned = System.currentTimeMillis()
        application.attributes[ReportServiceKey].clock = { pinned }
    }

    private suspend fun HttpClient.etagOf(url: String): String {
        val response = get(url)
        assertEquals(HttpStatusCode.OK, response.status, url)
        return assertNotNull(response.headers[HttpHeaders.ETag], "$url carries an ETag")
    }

    private suspend fun HttpClient.conditional(url: String, ifNoneMatch: String): HttpResponse =
        get(url) { header(HttpHeaders.IfNoneMatch, ifNoneMatch) }

    @Test
    fun `a team's work-category targets move the etag although their write bumps no revision`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val admin = seededClient("etag-targets", FlowUserRole.ADMIN)
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-targets", enabled = false)
        val teamId = TestTeams.seed("etag-targets-${System.nanoTime()}")
        val settings = DerivedStubFixture.metricsSettings()
        val url = "$costs?connectionId=$connectionId&domain=${DerivedStubFixture.DOMAIN}"
        val targets = "/api/v1/teams/$teamId/work-category-targets"
        try {
            val before = admin.etagOf(url)
            val revision = settings.currentRevision()
            val request = TeamWorkCategoryTargetsRequest(listOf(TeamWorkCategoryTarget("UNCATEGORIZED", 100)))
            assertEquals(HttpStatusCode.NoContent, admin.putJson(targets, request).status)
            val unchanged = settings.currentRevision()
            assertEquals(revision, unchanged, "the targets PUT bumps no shared revision, so the stamp reads the targets itself")
            val set = admin.etagOf(url)
            assertNotEquals(before, set, "setting a target")
            assertEquals(HttpStatusCode.OK, admin.conditional(url, before).status, "the old validator no longer matches")

            assertEquals(HttpStatusCode.NoContent, admin.putJson(targets, request).status)
            assertEquals(set, admin.etagOf(url), "an identical re-PUT writes nothing, so the validator holds")

            assertEquals(HttpStatusCode.NoContent, admin.putJson(targets, TeamWorkCategoryTargetsRequest(emptyList())).status)
            assertEquals(before, admin.etagOf(url), "clearing the set returns the stamp to where it was")
        } finally {
            TestTeams.service.delete(teamId)
        }
    }

    @Test
    fun `a connection's printed site, a public holiday and a team's default capacity each move the etag, its updated_at does not`() =
        testApplication {
            usePostgresTestcontainer()
            pinReportClock()
            val client = seededClient("etag-inputs")
            val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-inputs", enabled = false)
            val url = "$costs?connectionId=$connectionId&domain=${DerivedStubFixture.DOMAIN}"
            val adminId = TestUsers.seed(uniqueEmail("etag-inputs-admin"), "pw", role = FlowUserRole.ADMIN)
            val teamId = TestTeams.seed("etag-inputs-${System.nanoTime()}")
            val c = DataSourceService.Connections
            try {
                val base = client.etagOf(url)

                // `updated_at` moves on every ADMIN edit (a token rotation too): a USER must not be able to read that off a tag.
                suspendTransaction(sharedDatabaseForTests()) { c.update({ c.id eq connectionId }) { it[updatedAt] = nowPlus(1) } }
                assertEquals(base, client.etagOf(url), "updated_at is not stamped")

                // The `siteUrl` is what /filters prints (read out of `settings`; the host here is a reserved test name).
                suspendTransaction(sharedDatabaseForTests()) {
                    val site = "https://etag-${System.nanoTime()}.example.invalid"
                    c.update({ c.id eq connectionId }) { it[settings] = """{"siteUrl":"$site"}""" }
                }
                val edited = client.etagOf(url)
                assertNotEquals(base, edited, "the printed site changed")

                val duringHoliday = TestHolidays.with("2031-05-05") { client.etagOf(url) }
                assertNotEquals(edited, duringHoliday, "a public holiday folds into the working calendar every report reads")
                assertNotEquals(duringHoliday, client.etagOf(url), "so does removing it")

                val beforeCapacity = client.etagOf(url)
                CapacityFixtures.service().replaceTeamCapacity(teamId, TeamCapacityRequest(7.0), adminId, callerIsAdmin = true)
                assertNotEquals(beforeCapacity, client.etagOf(url), "a team's default capacity feeds the Costs capacity read")
            } finally {
                TestTeams.service.delete(teamId)
            }
        }

    @Test
    fun `a data-source PUT that moves backfillFrom moves the etag, although it bumps no settings revision`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val client = seededClient("etag-backfill")
        val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-backfill", enabled = false)
        val settings = DerivedStubFixture.metricsSettings()
        val url = "$costs?connectionId=$connectionId&domain=${DerivedStubFixture.DOMAIN}"
        val before = client.etagOf(url)
        val revision = settings.currentRevision()
        SyncedStubFixture.putBackfillFrom(connectionId, "2025-10-01")
        assertEquals(revision, settings.currentRevision(), "a data-source PUT bumps the connection's revision, not the settings one")
        val moved = client.etagOf(url)
        assertNotEquals(before, moved, "the WIP series start at backfill_from, so the stamp carries it")
        SyncedStubFixture.putBackfillFrom(connectionId, "2025-10-01")
        assertEquals(moved, client.etagOf(url), "an identical PUT leaves the validator")
        SyncedStubFixture.putBackfillFrom(connectionId, SyncedStubFixture.BACKFILL_FROM)
        assertEquals(before, client.etagOf(url), "restoring the day restores the stamp")
    }

    @Test
    fun `ingest activity, a last successful sync and a connection's deletion and purge move the etag, with no derive involved`() =
        testApplication {
            usePostgresTestcontainer()
            pinReportClock()
            val client = seededClient("etag-ingest")
            val connectionId = SyncedStubFixture.createConnection(namePrefix = "etag-ingest", enabled = false)
            val adminId = TestUsers.seed(uniqueEmail("etag-ingest-admin"), "pw", role = FlowUserRole.ADMIN)
            val url = "$costs?connectionId=$connectionId&domain=${DerivedStubFixture.DOMAIN}"
            val jobs = SyncJobsService(sharedDatabaseForTests(), 3)
            val j = SyncJobsService.Jobs
            val c = DataSourceService.Connections
            val before = client.etagOf(url)

            // A SYNC that commits pages then fails chains no DERIVE: only its own job row moves, page by page (the heartbeat), then ends.
            val job = jobs.requestJob(connectionId, SyncJobKind.SYNC, adminId, configRevision = 1L).jobId
            val queued = client.etagOf(url)
            assertNotEquals(before, queued, "a job enqueued")
            suspendTransaction(sharedDatabaseForTests()) { j.update({ j.id eq job }) { it[heartbeatAt] = 1_000L } }
            val page1 = client.etagOf(url)
            assertNotEquals(queued, page1, "the first committed page's heartbeat")
            suspendTransaction(sharedDatabaseForTests()) { j.update({ j.id eq job }) { it[heartbeatAt] = 2_000L } }
            val page2 = client.etagOf(url)
            assertNotEquals(page1, page2, "the next page's heartbeat")
            suspendTransaction(sharedDatabaseForTests()) { j.update({ j.id eq job }) { it[finishedAt] = 3_000L; it[status] = "FAILED" } }
            val failed = client.etagOf(url)
            assertNotEquals(page2, failed, "the failed run's end")

            // Home's freshness (`last_sync_succeeded_at`) is printed, so it is stamped on its own too.
            suspendTransaction(sharedDatabaseForTests()) { c.update({ c.id eq connectionId }) { it[lastSyncSucceededAt] = 4_000L } }
            val synced = client.etagOf(url)
            assertNotEquals(failed, synced, "a last successful sync")

            // Names and /filters read a deleted connection's people until its PURGE removes them: both transitions are stamped.
            suspendTransaction(sharedDatabaseForTests()) { c.update({ c.id eq connectionId }) { it[markedAsDeleted] = true } }
            val deleted = client.conditional("/api/v1/reports/filters", "*").headers[HttpHeaders.ETag]
            assertNotNull(deleted)
            suspendTransaction(sharedDatabaseForTests()) { c.update({ c.id eq connectionId }) { it[purgedAt] = 5_000L } }
            assertNotEquals(deleted, client.conditional("/api/v1/reports/filters", "*").headers[HttpHeaders.ETag], "the purge")
        }

    @Test
    fun `the code report's validator also carries the GitLab inputs - links, sync activity, the source - and a Jira report's does not`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("etag-code")
            val adminId = TestUsers.seed(uniqueEmail("etag-code-admin"), "pw", role = FlowUserRole.ADMIN)
            val db = sharedDatabaseForTests()
            val links = CodeIdentityLinkStore(db)
            val zone = ZoneId.of(DerivedStubFixture.metricsSettings().read().timeZone)
            withCodeWorld(zone) {
                application.attributes[ReportServiceKey].clock = { now }
                val code = "/api/v1/reports/code-activity?teamId=$teamId&from=2026-03-02&to=2026-03-15&connectionId=$connId"
                val jiraSide = listOf("/api/v1/reports/filters", "/api/v1/reports/home")
                val jiraBefore = jiraSide.map { client.etagOf(it) }
                val j = SyncJobsService.Jobs
                val c = DataSourceService.Connections
                val tags = mutableListOf(client.etagOf(code))
                suspend fun moved(what: String) {
                    val next = client.etagOf(code)
                    assertNotEquals(tags.last(), next, what)
                    assertEquals(jiraBefore, jiraSide.map { client.etagOf(it) }, "$what must not move a Jira report's tag")
                    tags += next
                }
                try {
                    // `links:<host>:<count>:<newest decided_at>`: an insert (count), an overwrite (newest instant) and a delete (count).
                    links.insertIfAbsent(IdentityLink(source.host, kU, CodeIdentityDecision.IGNORED, null, adminId.toLong(), 1L))
                    moved("a link written")
                    links.update(IdentityLink(source.host, kU, CodeIdentityDecision.BOT, null, adminId.toLong(), 7L))
                    moved("a link overwritten")
                    links.delete(source.host, kU)
                    moved("a link removed")

                    // `code-ingest:`: a SYNC (its PROCESS pages) moves with the heartbeat, page by page, and with its end.
                    val job = SyncJobsService(db, 3).requestJob(connId, SyncJobKind.SYNC, adminId, configRevision = 1L).jobId
                    moved("a job enqueued")
                    suspendTransaction(db) { j.update({ j.id eq job }) { it[heartbeatAt] = 1_000L } }
                    moved("the first committed page")
                    suspendTransaction(db) { j.update({ j.id eq job }) { it[heartbeatAt] = 2_000L } }
                    moved("the next page")
                    suspendTransaction(db) { j.update({ j.id eq job }) { it[finishedAt] = 3_000L; it[status] = "SUCCEEDED" } }
                    moved("the run's end")

                    // `code:`: what sources[] prints.
                    suspendTransaction(db) { c.update({ c.id eq connId }) { it[lastSyncSucceededAt] = 4_000L } }
                    moved("a last successful sync")
                    suspendTransaction(db) { c.update({ c.id eq connId }) { it[name] = "etag-code-renamed-${System.nanoTime()}" } }
                    moved("a source rename")
                    // The negative control for the stamp itself: an unrelated write to the row moves nothing.
                    suspendTransaction(db) { c.update({ c.id eq connId }) { it[updatedAt] = nowPlus(1) } }
                    assertEquals(tags.last(), client.etagOf(code), "updated_at is not stamped")
                } finally {
                    PostgresTestSupport.connect().use { conn ->
                        conn.prepareStatement("DELETE FROM sync_jobs WHERE connection_id = ?").use {
                            it.setInt(1, connId.toInt())
                            it.executeUpdate()
                        }
                    }
                    TestUsers.softDelete(adminId)
                }
            }
        }

    private fun nowPlus(extraMs: Long): Long = System.currentTimeMillis() + extraMs

    @Test
    fun `a history horizon change moves every report's etag through the settings revision, with no stamp line of its own`() =
        testApplication {
            usePostgresTestcontainer()
            pinReportClock()
            val client = seededClient("etag-horizon")
            val connectionId = DerivedStubFixture.connectionId()
            val settings = DerivedStubFixture.metricsSettings()
            val scope = "connectionId=$connectionId&domain=${DerivedStubFixture.DOMAIN}"
            val urls = listOf("throughput", "cycle-time", "costs", "wip", "backlog", "aging-wip").map { "/api/v1/reports/$it?$scope" } +
                listOf("/api/v1/reports/filters", "/api/v1/reports/home")
            val before = urls.associateWith { client.etagOf(it) }
            val current = settings.read().historyHorizonMonths
            val changed = if (current == null) MIN_HISTORY_HORIZON_MONTHS else null
            withMetricsSettings(settings, { it.copy(historyHorizonMonths = changed) }) {
                urls.forEach { assertNotEquals(before.getValue(it), client.etagOf(it), "$it follows the horizon through settings:") }
            }
        }

    @Test
    fun `a sprint-calendar save moves the etag through the revision bump`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        val client = seededClient("etag-calendar")
        val seeded = CapacityFixtures.seed("etag-calendar")
        val adminId = TestUsers.seed(uniqueEmail("etag-calendar-admin"), "pw", role = FlowUserRole.ADMIN)
        val calendar = TestHolidays.calendar()
        val service = CapacityFixtures.service()
        val url = "$costs?connectionId=${seeded.connectionId}&domain=${DerivedStubFixture.DOMAIN}"
        fun document(availability: Double) = SprintCalendarDocument(
            teamCoefficient = 1.0,
            days = CapacityFixtures.WINDOW_DAYS.map { SprintCalendarDay(it, enabled = it !in setOf("2026-03-07", "2026-03-08")) },
            people = seeded.members.map { account ->
                SprintCalendarPerson(account, true, 1.0, CapacityFixtures.WINDOW_DAYS.map { SprintCalendarCell(it, availability) })
            },
        )
        try {
            val before = client.etagOf(url)
            service.replace(seeded.teamId, seeded.sprintId, document(1.0), adminId, callerIsAdmin = true, calendar = calendar)
            val saved = client.etagOf(url)
            assertNotEquals(before, saved, "the first save of a sprint's calendar")
            service.replace(seeded.teamId, seeded.sprintId, document(0.5), adminId, callerIsAdmin = true, calendar = calendar)
            assertNotEquals(saved, client.etagOf(url), "a changed calendar")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                MetricsTables.DimSprint.deleteWhere { MetricsTables.DimSprint.connectionId eq seeded.connectionId }
                val defaults = SprintCalendarService.TeamCapacityDefaults
                defaults.deleteWhere { defaults.teamId inList listOf(seeded.teamId) }
            }
            service.purgeConnection(seeded.connectionId)
            TestTeams.service.delete(seeded.teamId)
        }
    }

    @Test
    fun `compare people carries the caller's capacity verdict in its validator, so a 304 never crosses a verdict`() = testApplication {
        usePostgresTestcontainer()
        pinReportClock()
        withCompareWorld { w ->
            val admin = seededClient("etag-cmp-admin", FlowUserRole.ADMIN)
            val managerEmail = uniqueEmail("etag-cmp-manager")
            val managerId = TestUsers.seed(email = managerEmail, password = "pw", role = FlowUserRole.USER, name = "manager")
            val manager = authedClient(managerEmail, "pw")
            val plain = seededClient("etag-cmp-plain", FlowUserRole.USER)
            val url = "/api/v1/reports/compare-people?${w.query()}"

            suspend fun setManager(team: UInt, name: String, userId: UInt?) {
                val request = TeamUpdateRequest(name, managerUserId = userId)
                assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/teams/$team", request).status)
            }
            setManager(w.t1, w.t1Name, managerId)
            setManager(w.t2, w.t2Name, managerId)

            val asAdmin = admin.etagOf(url)
            val asManager = manager.etagOf(url)
            val asPlain = plain.etagOf(url)
            assertEquals(3, setOf(asAdmin, asManager, asPlain).size, "admin, manager of every team and everyone else differ")
            assertEquals(HttpStatusCode.NotModified, manager.conditional(url, asManager).status, "a verdict revalidates against itself")
            val crossed = plain.conditional(url, asManager)
            assertEquals(HttpStatusCode.OK, crossed.status, "a plain user sending the manager's tag runs the report")
            assertFalse("availableMd" in crossed.bodyAsText(), "and gets no capacity rows")

            // The manager loses team 2 BEHIND the stamp's back (a raw update leaves `teams.updated_at` alone, which the PUT would
            // move): the verdict flips to "none", the old tag stops matching, and the stamp itself - a plain user's tag - does not move.
            suspendTransaction(sharedDatabaseForTests()) {
                TeamService.Teams.update({ TeamService.Teams.id eq w.t2 }) { it[managerUserId] = null }
            }
            assertEquals(asPlain, plain.etagOf(url), "nothing but the verdict changed")
            val demoted = manager.conditional(url, asManager)
            assertEquals(HttpStatusCode.OK, demoted.status, "a demoted manager's old tag no longer answers 304")
            assertFalse("availableMd" in demoted.bodyAsText())
            assertNotEquals(asManager, demoted.headers[HttpHeaders.ETag])
            assertTrue(demoted.headers[HttpHeaders.ETag] != null)
        }
    }
}
