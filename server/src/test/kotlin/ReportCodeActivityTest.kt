package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.reports.CodeActivityReport
import ch.nokillswit.reports.ReportServiceKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Report 21 over HTTP (`GET /api/v1/reports/code-activity`, `.claude/docs/reports.md` "Report 21", A50) on the hand-written
 * [CodeWorld]: every figure is computed by hand from its rows (the comments say how), the `400` matrix, `teamId=0`, the minimum sample,
 * a plain USER (D12) and the `304`. The world's days are midnights of the report zone, so the report's day bounds coincide with them;
 * `now` is pinned to day 14 (Monday 2026-03-16). Each request names the world's own `connectionId`, so another class's GitLab sources
 * never enter the figures.
 */
class ReportCodeActivityTest {
    private val db = sharedDatabaseForTests()
    private val settings = MetricsSettingsService(db, GitLabFixtures.dataSources(), SyncJobsService(db, 3))

    private suspend fun reportZone(): ZoneId = ZoneId.of(settings.read().timeZone)

    private suspend fun <T> inWorld(minSample: Int? = 1, body: suspend CodeWorld.() -> T): T = withCodeWorld(reportZone()) {
        if (minSample == null) body() else withMetricsSettings(settings, { it.copy(minSampleSize = minSample) }) { body() }
    }

    private fun ApplicationTestBuilder.pin(world: CodeWorld) {
        application.attributes[ReportServiceKey].clock = { world.now }
    }

    private fun CodeWorld.query(team: UInt = teamId, extra: String = "") =
        "teamId=$team&from=2026-03-02&to=2026-03-15&connectionId=$connId$extra"

    private suspend fun HttpClient.code(query: String): CodeActivityReport {
        val response = get("/api/v1/reports/code-activity?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    private suspend fun HttpClient.refused(query: String): ProblemDetail {
        val response = get("/api/v1/reports/code-activity?$query")
        assertEquals(HttpStatusCode.BadRequest, response.status, query)
        return response.body()
    }

    @Test
    fun `the team's flow, quality and member volumes are the hand-computed figures, for a plain user`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-activity-team", FlowUserRole.USER)
        inWorld {
            pin(this)
            val report = client.code(query())
            val meta = report.meta
            assertEquals(listOf("TEAM", "2026-03-02", "2026-03-15", 1), listOf(meta.level.name, meta.from, meta.to, meta.minSampleSize))

            val source = report.sources.single()
            assertEquals(listOf(connId, this.source.baseUrl), listOf(source.connectionId, source.baseUrl))
            assertNull(source.lastSyncAt)
            assertEquals(listOf(0L, 0L), listOf(source.commitFilesPending, source.mrDetailsPending))

            // Connection-wide: kU is the one undecided identity (the BOT is a decision), with a commit, merge request 7 and the
            // dated REVIEW_REQUESTED on 2; a2's counts, merge requests 7 and 9 (size, details) and kU's undated comment are unknown.
            val q = report.quality
            assertEquals(1, q.unlinkedIdentities)
            assertEquals(listOf(1L, 1L, 1L), listOf(q.unlinkedCommits, q.unlinkedMergeRequests, q.unlinkedEvents))
            assertEquals(listOf(1L, 2L, 2L, 1L), listOf(q.commitsLinesUnknown, q.mrsSizeUnknown, q.mrsDetailsNotRead, q.eventsUndated))

            // Alice's merge requests 1 (day 1 Tue -> day 4 Fri: 3 days, Tue-Thu) and 2 (Sun day 6 -> Tue day 8: 2 days, 1 working).
            val flow = report.flow
            assertEquals(listOf(2, 1), listOf(flow.merged, flow.closedUnmerged))
            assertEquals(listOf(2L, 2.0), flow.cycleWorkingDays.let { listOf(it.n, it.p50) })
            assertEquals(listOf(2L, 2.5), flow.cycleElapsedDays.let { listOf(it.n, it.p50) })
            // Only merge request 1 was reviewed by another human (bob's REVIEWED on day 2): one working day after creation.
            assertEquals(listOf(1L, 1.0), flow.timeToFirstReviewWorkingDays.let { listOf(it.n, it.p50) })
            assertEquals(listOf(2L, 13.5), flow.sizeLines.let { listOf(it.n, it.p50) })
            assertEquals(listOf(2L, 2.0), flow.sizeFiles.let { listOf(it.n, it.p50) })
            assertEquals(listOf(1, 0.5), flow.mergedWithoutReview.let { listOf(it.count, it.share) })
            assertEquals(listOf(1, 0.5), flow.mergedWithoutApproval.let { listOf(it.count, it.share) })
            assertEquals(listOf("2026-03-02" to 1, "2026-03-09" to 1), flow.weekly.map { it.bucketStart to it.merged })

            // Merge request 4 (open since Feb 10, 24 working days) waits; 3 is a draft.
            assertEquals(listOf(1, 1, 1, false, 24.0, 24.0), report.open.let {
                listOf(it.count, it.drafts, it.age.n, it.age.hidden, it.age.p50, it.age.p85)
            })
            val review = assertNotNull(report.review)
            assertEquals(listOf(2, 0.5, 1.0), listOf(review.n, review.reviewersPerMergedMrP50, review.topReviewerShare))

            val alice = report.groups.single()
            assertEquals(listOf(accA, accA), listOf(alice.accountId, alice.label))
            assertEquals(2, alice.commits)
            assertEquals(listOf(3L, 10L, 2L), listOf(alice.filesChanged, alice.linesAdded, alice.linesDeleted))
            assertEquals(1, alice.commitsLinesUnknown)
            assertEquals(listOf(2, 4, 2), listOf(alice.mrsMerged, alice.mrsOpened, alice.openMrs))
            assertEquals(listOf(2.0, 13.5), listOf(alice.mrCycleWorkingDaysP50, alice.mrSizeLinesP50))
            assertEquals(listOf(0, 0, 0), listOf(alice.reviewsGiven, alice.approvalsGiven, alice.changesRequestedGiven))
            assertEquals(listOf(1, 1), listOf(alice.comments, alice.reviewRequestsReceived))
        }
    }

    @Test
    fun `a matching If-None-Match is a 304 with no body, and an anonymous caller is refused`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-activity-304")
        inWorld {
            pin(this)
            val url = "/api/v1/reports/code-activity?${query()}"
            val ok = client.get(url)
            assertEquals(HttpStatusCode.OK, ok.status)
            val etag = assertNotNull(ok.headers[HttpHeaders.ETag])
            val notModified = client.get(url) { header(HttpHeaders.IfNoneMatch, etag) }
            assertEquals(HttpStatusCode.NotModified, notModified.status)
            assertEquals(etag, notModified.headers[HttpHeaders.ETag])
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(url).status)
        }
    }

    @Test
    fun `a picked member is the USER level, every distribution splitting per person`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-activity-user")
        inWorld {
            pin(this)
            val report = client.code(query(extra = "&accountId=$accA"))
            assertEquals("USER", report.meta.level.name)
            assertEquals(listOf(accA), report.groups.map { it.accountId })
            val cycle = report.flow.cycleWorkingDays
            assertEquals(listOf(accA), cycle.byUser.map { it.accountId })
            assertEquals(cycle.histogram.map { it.count }, cycle.byUser.single().counts)
            assertEquals(2, report.flow.merged)
            // A pick who is nobody's member has no rows in the team: a zero group, never missing.
            val bob = client.code(query(extra = "&accountId=$accB"))
            assertEquals(listOf(accB), bob.groups.map { it.accountId })
            assertEquals(listOf(0, 0), listOf(bob.groups.single().commits, bob.flow.merged))
        }
    }

    @Test
    fun `team 0 lists the undecided unlinked identities and the linked people with no membership, never a BOT`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-activity-unassigned")
        inWorld {
            pin(this)
            val report = client.code(query(team = 0u))
            // The linked reviewer (named by his account id: no Jira person here), the undecided human by his GitLab username, and the
            // author-less merge request 9, which has no name at all. The BOT's comment is in no group.
            assertEquals(listOf(accB, "unlinked-user", null), report.groups.map { it.label })
            assertEquals(listOf(accB, null, null), report.groups.map { it.accountId })
            val (bob, unlinked, nobody) = report.groups
            // Bob: b1; merge request 1 reviewed with ONE approval (two events); one comment; the request naming him.
            assertEquals(
                listOf(1, 1, 1, 1, 1),
                listOf(bob.commits, bob.reviewsGiven, bob.approvalsGiven, bob.comments, bob.reviewRequestsReceived),
            )
            // kU: u1, merge request 7 (created day 3 Thu, merged day 6 Sun: 2 working days; no size).
            assertEquals(listOf(1, 1, 1), listOf(unlinked.commits, unlinked.mrsMerged, unlinked.mrsOpened))
            assertEquals(listOf(2.0, null), listOf(unlinked.mrCycleWorkingDaysP50, unlinked.mrSizeLinesP50))
            // Merge request 9: created Fri day 4, merged Wed day 9: 3 working days.
            assertEquals(listOf(1, 3.0), listOf(nobody.mrsMerged, nobody.mrCycleWorkingDaysP50))
            // Their details were not read: nothing is reviewed or unreviewed, there is no review block.
            val flow = report.flow
            assertEquals(2, flow.merged)
            assertEquals(listOf(0L, 0L), listOf(flow.timeToFirstReviewWorkingDays.n, flow.sizeLines.n))
            assertEquals(listOf(0, null), listOf(flow.mergedWithoutReview.count, flow.mergedWithoutReview.share))
            assertNull(report.review)
            assertEquals(listOf(0, 0), listOf(report.open.count, report.open.drafts))
        }
    }

    @Test
    fun `counts are never hidden but the distributions and the p50 cells are under the minimum sample`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-activity-min")
        inWorld(minSample = 3) {
            pin(this)
            val report = client.code(query())
            assertEquals(3, report.meta.minSampleSize)
            val flow = report.flow
            assertEquals(2, flow.merged)
            assertTrue(flow.cycleWorkingDays.hidden && flow.sizeLines.hidden)
            assertEquals(listOf(2L, null), listOf(flow.cycleWorkingDays.n, flow.cycleWorkingDays.p50))
            assertEquals(1, flow.mergedWithoutReview.count)
            assertEquals(listOf(1, 1, 1, true, null), report.open.let { listOf(it.count, it.drafts, it.age.n, it.age.hidden, it.age.p50) })
            val review = assertNotNull(report.review)
            assertEquals(listOf(2, null, null), listOf(review.n, review.reviewersPerMergedMrP50, review.topReviewerShare))
            val alice = report.groups.single()
            assertEquals(listOf(2, 4), listOf(alice.mrsMerged, alice.mrsOpened))
            assertNull(alice.mrCycleWorkingDaysP50)
            assertNull(alice.mrSizeLinesP50)
        }
    }

    @Test
    fun `a sprint period with no sprint to resolve reads nothing, and the sources are still listed`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-activity-empty")
        inWorld {
            pin(this)
            val report = client.code("teamId=$teamId&lastSprints=1&connectionId=$connId")
            assertEquals(listOf(connId), report.sources.map { it.connectionId })
            assertEquals(0, report.flow.merged)
            assertTrue(report.flow.weekly.isEmpty() && report.groups.isEmpty())
            assertNull(report.review)
        }
    }

    @Test
    fun `the 400 matrix - scope, team, connection, period and picks`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-activity-400")
        inWorld(minSample = null) {
            pin(this)
            val scope = "teamId is required: this report carries no domain slice and is read per team, never for the whole unit"
            assertEquals(scope, client.refused("from=2026-03-02&to=2026-03-15&connectionId=$connId").detail)
            assertEquals(scope, client.refused("domain=any&connectionId=$connId").detail)
            assertEquals("Unknown or inactive teamId: 99999999", client.refused("teamId=99999999&connectionId=$connId").detail)

            // A Jira source, an unknown id and a soft-deleted GitLab source are the same refusal.
            val jira = GitLabRowFixture.insertJira("code-activity-jira")
            val gone = GitLabFixtures.createConnection(baseUrl = "https://code-gone-${System.nanoTime()}.example.test", enabled = false)
            try {
                GitLabFixtures.dataSources().delete(gone)
                for (id in listOf(jira, 99_999_999u, gone)) {
                    assertEquals("Unknown or inactive connectionId: $id", client.refused("teamId=$teamId&connectionId=$id").detail, "$id")
                }
            } finally {
                GitLabRowFixture.remove(jira)
                GitLabFixtures.purgeAll(gone)
            }

            val base = "teamId=$teamId&connectionId=$connId"
            for (bad in listOf(
                "from=2026-03-15&to=2026-03-02", "from=nonsense", "lastSprints=0", "lastSprints=1&sprintId=1", "tailPercent=26",
                "tailPercent=abc", ('a'..'i').joinToString("&") { "accountId=$it" },
                "teamId=abc",
            )) {
                client.refused("$base&$bad")
            }
            assertFalse(client.refused("from=2026-03-02&to=2026-03-15").detail.isNullOrBlank())
        }
    }
}
