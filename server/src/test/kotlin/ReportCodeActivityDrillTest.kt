package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.identities.CodeIdentityConfirmResult
import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.NormCodeCommit
import ch.nokillswit.norm.NormCodeProject
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.reports.CodeActivityReport
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.ReportServiceKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass

/**
 * Report 21's drill (`GET /api/v1/reports/code-activity/items`, `.claude/docs/reports.md` "Report 21") on the hand-written [CodeWorld] and
 * on the processed GitLab stub. The gate is "every bar = its drill" ([CodeDrillReconciler]): every bar of every histogram (and each
 * person's share of it), every flow tile, every weekly bar and every member's cell equals the count of its drill, at TEAM, USER and
 * `teamId=0`; the world additionally pins the items themselves by hand (their keys, titles, figures, anchors and people), the `400`
 * matrix and the 500-item cap, and the stub's clone compares the merged merge requests and the commits with an INDEPENDENT SQL listing.
 */
class ReportCodeActivityDrillTest {
    private val db = sharedDatabaseForTests()
    private val host = sourceHostOf(GitLabStubServer.baseUrl)
    private val links = CodeIdentityLinkStore(db)
    private val settings = MetricsSettingsService(db, GitLabFixtures.dataSources(), SyncJobsService(db, 3))

    private suspend fun reportZone(): ZoneId = ZoneId.of(settings.read().timeZone)

    private suspend fun <T> inWorld(minSample: Int? = 1, body: suspend CodeWorld.() -> T): T = withCodeWorld(reportZone()) {
        // The links in the report's rows resolve a project path only when the project is stored.
        val project = NormCodeProject(1, "grp/app", "grp", null, archived = false, movedOutAt = null, deletedAt = null)
        GitLabFixtures.codeStore().replaceProjects(connId, listOf(project))
        if (minSample == null) body() else withMetricsSettings(settings, { it.copy(minSampleSize = minSample) }) { body() }
    }

    private fun ApplicationTestBuilder.pin(world: CodeWorld) {
        application.attributes[ReportServiceKey].clock = { world.now }
    }

    private fun CodeWorld.filterOf(team: UInt, picks: List<String>) = ReportFilter(
        period = ReportPeriod.DateRange(LocalDate.parse("2026-03-02"), LocalDate.parse("2026-03-15"), window.first, window.second),
        level = if (picks.isEmpty()) ReportLevel.TEAM else ReportLevel.USER, teamId = team, accountIds = picks,
        domainView = DomainView.TASK, domain = null, activityType = null, workCategory = null, connectionId = connId,
    )

    private fun CodeWorld.base(team: UInt = teamId, extra: String = "") =
        "teamId=$team&from=2026-03-02&to=2026-03-15&connectionId=$connId$extra"

    private suspend fun HttpClient.items(query: String, extra: String): ReportDrillItems {
        val response = get("/api/v1/reports/code-activity/items?$query&$extra")
        assertEquals(HttpStatusCode.OK, response.status, "$query&$extra")
        return response.body()
    }

    private suspend fun HttpClient.refused(query: String, extra: String = ""): ProblemDetail {
        val response = get("/api/v1/reports/code-activity/items?$query&$extra")
        assertEquals(HttpStatusCode.BadRequest, response.status, "$query&$extra")
        return response.body()
    }

    private suspend fun HttpClient.report(query: String): CodeActivityReport {
        val response = get("/api/v1/reports/code-activity?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    /** `(issueKey, summary, figure, anchor)` of every listed item. */
    private fun ReportDrillItems.rows(): List<List<Any?>> = items.map { listOf(it.issueKey, it.summary, it.figure, it.anchorAt) }

    @Test
    fun `every figure of the report equals the count of its drill on the hand-written world at every level`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-drill-reconcile", FlowUserRole.USER)
        inWorld {
            pin(this)
            // Merge requests at the two edges of a week (Sunday 23:59, Monday 00:00 in the report zone) join the world: a drift between the
            // weekly bars and their drill would move one of them to the wrong week.
            addWeekEdgeMergeRequests()
            val service = application.attributes[ReportServiceKey]
            // Every figure is reconciled in-process from ONE read per filter; the route (labels, wire, the member override) agrees with the
            // same selection for one request per view.
            suspend fun reconcile(label: String, team: UInt, picks: List<String>, least: Int) {
                val query = base(team, picks.joinToString("") { "&accountId=$it" })
                val inProcess = InProcessCodeDrills(service, filterOf(team, picks), now)
                val report = client.report(query)
                assertRouteAgrees(HttpCodeDrills(client, query), inProcess, report)
                val reconciler = CodeDrillReconciler(inProcess, report)
                reconciler.reconcile(label)
                assertTrue(reconciler.compared > least, "$label: compared ${reconciler.compared} figures")
            }
            reconcile("TEAM", teamId, emptyList(), 40)
            reconcile("USER", teamId, listOf(accA, accB), 40)
            // Everything that is nobody's member: Bob (linked, no team), the undecided human and the author-less merge request.
            reconcile("UNASSIGNED", 0u, emptyList(), 20)
            val alice = base(extra = "&accountId=$accA")
            suspend fun week(start: String) = client.items(alice, "view=WEEK&bucketStart=$start").items.map { it.issueKey }.sorted()
            assertEquals(listOf("grp/app!1", "grp/app!20"), week("2026-03-02"))
            assertEquals(listOf("grp/app!2", "grp/app!21"), week("2026-03-09"))
        }
    }

    @Test
    fun `the items of a TEAM-level member are the hand-computed rows, with their keys, titles, figures and anchors`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-drill-items", FlowUserRole.USER)
        inWorld {
            pin(this)
            val alice = base(extra = "&accountId=$accA")
            // Alice's two non-merge, live commits in the window: a1 has 3 files, +10 -2 (12 counted lines), a2 has no counted figures.
            val commits = client.items(alice, "view=COMMITS")
            assertEquals(listOf(listOf("grp/app@a1", "t", 12.0, at(1)), listOf("grp/app@a2", "t", null, at(2))), commits.rows())
            assertEquals(listOf("COMMIT", "COMMIT"), commits.items.map { it.itemKind.name })
            assertEquals(listOf(accA, accA), commits.items.map { it.accountId })
            assertEquals(listOf(teamId, teamId), commits.items.map { it.teamId })

            // Merge requests 1 (Tue -> Fri: 3 working days) and 2 (Sun -> Tue: 1) merged; cycle in working days is the figure.
            val merged = client.items(alice, "view=MRS_MERGED")
            assertEquals(listOf(listOf("grp/app!1", "title 1", 3.0, at(4)), listOf("grp/app!2", "title 2", 1.0, at(8))), merged.rows())
            assertEquals("MERGE_REQUEST", merged.items.first().itemKind.name)
            assertEquals(listOf(3.0, 2.0), client.items(alice, "view=MR_CYCLE_ELAPSED_DAYS").items.map { it.figure })
            assertEquals(listOf(25.0, 2.0), client.items(alice, "view=MR_SIZE_LINES").items.map { it.figure })
            assertEquals(listOf(3.0, 1.0), client.items(alice, "view=MR_SIZE_FILES").items.map { it.figure })
            // Only merge request 1 was reviewed by another human (Bob's REVIEWED on day 2: one working day after creation).
            assertEquals(listOf(listOf("grp/app!1", "title 1", 1.0, at(4))), client.items(alice, "view=TIME_TO_FIRST_REVIEW").rows())
            assertEquals(listOf("grp/app!2"), client.items(alice, "view=MERGED_WITHOUT_REVIEW").items.map { it.issueKey })
            assertEquals(listOf("grp/app!2"), client.items(alice, "view=MERGED_WITHOUT_APPROVAL").items.map { it.issueKey })

            // Opened: 1 (day 1), 8 (day 2, later closed), 2 (day 6), 3 (day 7, a draft), by the newest anchor (no figure but the size).
            val opened = client.items(alice, "view=MRS_OPENED")
            assertEquals(listOf("grp/app!1", "grp/app!2", "grp/app!3", "grp/app!8"), opened.items.map { it.issueKey }.sorted())
            // Open now: 3 (a draft) and 4 (24 working days old); the waiting tile has only 4.
            assertEquals(setOf("grp/app!3", "grp/app!4"), client.items(alice, "view=OPEN_MRS").items.map { it.issueKey }.toSet())
            assertEquals(listOf(listOf("grp/app!4", "title 4", 24.0, at(-20))), client.items(alice, "view=OPEN_MRS_WAITING").rows())

            // Alice's own comment on merge request 1 (day 2) is an act of hers; the request on merge request 2 (day 3) names her.
            assertEquals(listOf(listOf("grp/app!1", "title 1", null, at(2))), client.items(alice, "view=COMMENTS").rows())
            assertEquals(listOf(listOf("grp/app!2", "title 2", null, at(3))), client.items(alice, "view=REVIEW_REQUESTS_RECEIVED").rows())
            assertEquals(0, client.items(alice, "view=REVIEWS_GIVEN").total)

            // The weekly bars: week of 2026-03-02 holds merge request 1, the next week merge request 2.
            assertEquals(listOf("grp/app!1"), client.items(alice, "view=WEEK&bucketStart=2026-03-02").items.map { it.issueKey })
            assertEquals(listOf("grp/app!2"), client.items(alice, "view=WEEK&bucketStart=2026-03-09").items.map { it.issueKey })

            // The histogram bar of the cycle: two values (1 and 3 working days), the layout is the report's; each bar's items add up.
            val histogram = client.items(alice, "view=MR_CYCLE_WORKING_DAYS&bucketIndex=0")
            assertNotNull(histogram.bar.from)
            assertEquals(histogram.bar.count, histogram.items.size)
        }
    }

    @Test
    fun `team 0 names the linked reviewer, the undecided human by username and nobody for an author-less one`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-drill-team0", FlowUserRole.USER)
        inWorld {
            pin(this)
            val team0 = base(team = 0u)
            // Bob's acts: reviews of merge requests 1 and 2 (the comment on 2 came after its merge but is still an act), ONE approval for
            // two APPROVAL events, two comments; the earliest such act is the anchor of a distinct merge request.
            val bobQuery = "$team0&accountId=$accB"
            val reviews = client.items(bobQuery, "view=REVIEWS_GIVEN")
            val reviewed = reviews.items.map { listOf(it.issueKey, it.anchorAt) }
            assertEquals(listOf(listOf("grp/app!2", at(10)), listOf("grp/app!1", at(2))), reviewed)
            assertEquals(listOf("grp/app!1"), client.items(bobQuery, "view=APPROVALS_GIVEN").items.map { it.issueKey })
            val comments = client.items(bobQuery, "view=COMMENTS")
            assertEquals(2, comments.total)
            assertEquals(setOf(at(3), at(10)), comments.items.map { it.anchorAt }.toSet())
            assertEquals(setOf(accB), comments.items.map { it.accountId }.toSet())
            // The unlinked human and the author-less merge request, unsegmented: no account, the username as the assignee, never the key.
            val merged = client.items(team0, "view=MRS_MERGED")
            assertEquals(2, merged.total)
            val byKey = merged.items.associateBy { it.issueKey }
            assertEquals("unlinked-user", byKey.getValue("grp/app!7").assignee)
            assertNull(byKey.getValue("grp/app!7").accountId)
            assertNull(byKey.getValue("grp/app!9").assignee)
            val everything = client.get("/api/v1/reports/code-activity/items?$team0&view=COMMITS").body<String>()
            listOf(kA, kB, kU, kBot).forEach { assertTrue(it !in everything, "the identity key $it is on the wire") }
            assertTrue("unlinked-user" in client.items(team0, "view=COMMITS").items.mapNotNull { it.assignee })
        }
    }

    @Test
    fun `a USER-level segment narrows to one picked person, the bar and the total with it`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-drill-segment", FlowUserRole.USER)
        inWorld {
            pin(this)
            val picks = base(extra = "&accountId=$accA&accountId=$accB")
            assertEquals(2, client.items(picks, "view=MRS_MERGED").total)
            assertEquals(2, client.items(picks, "view=MRS_MERGED&segmentAccountId=$accA").bar.count)
            assertEquals(0, client.items(picks, "view=MRS_MERGED&segmentAccountId=$accB").total)
            // Bob is a pick although nobody's team member: his acts are in no group of the team, so his segment is empty here.
            assertEquals(0, client.items(picks, "view=REVIEWS_GIVEN&segmentAccountId=$accB").total)
        }
    }

    @Test
    fun `more than 500 items are cut to the first 500 and the total, the bar and truncated still cover them all`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-drill-cap", FlowUserRole.USER)
        inWorld {
            pin(this)
            val bulk = (0..500).map { i ->
                NormCodeCommit(1, "bulk%04d".format(i), kA, "bulk $i", at(1, 1), at(1, 1), false, 1, 1, 1, 1, 1, 1, false, null)
            }
            GitLabFixtures.codeStore().replaceCommits(connId, bulk, now)
            val commits = client.items(base(), "view=COMMITS")
            assertEquals(listOf(503, 503, 500, true), listOf(commits.total, commits.bar.count, commits.items.size, commits.truncated))
            // The cut keeps the largest figures first: a1's 12 lines, then the bulk commits' 2 lines each.
            assertEquals("grp/app@a1", commits.items.first().issueKey)
            // And the member's cell is the same 503 (the report's own figure).
            assertEquals(503, client.report(base()).groups.single().commits)
            assertEquals(503, HttpCodeDrills(client, base()).list("COMMITS", member = accA)?.count)
        }
    }

    @Test
    fun `the 400 matrix - view, bucket identity, segment, scope and the parent's own parameters`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-drill-400", FlowUserRole.USER)
        inWorld {
            pin(this)
            val q = base()
            val picked = base(extra = "&accountId=$accA")
            assertTrue(client.refused(q).detail!!.startsWith("view is required"))
            assertTrue(client.refused(q, "view=NOPE").detail!!.contains("view"))
            for (bad in listOf(
                "view=COMMITS&bucketIndex=0", "view=WEEK&bucketIndex=0", "view=MR_CYCLE_WORKING_DAYS&bucketIndex=-1",
                "view=MR_CYCLE_WORKING_DAYS&bucketIndex=abc", "view=MR_CYCLE_WORKING_DAYS&bucketIndex=99", "view=WEEK",
                "view=WEEK&bucketStart=nonsense", "view=WEEK&bucketStart=2026-03-03", "view=WEEK&bucketStart=2026-02-23",
                "view=MRS_MERGED&bucketStart=2026-03-02",
                // The segment: not below USER level, and one of the picks.
                "view=COMMITS&segmentAccountId=$accA",
            )) {
                client.refused(q, bad)
            }
            client.refused(picked, "view=COMMITS&segmentAccountId=$accB")
            client.refused(picked, "view=COMMITS&segmentAccountId=acc-stranger")
            // The parent's own refusals come first.
            client.refused("from=2026-03-02&to=2026-03-15&connectionId=$connId", "view=COMMITS")
            client.refused("teamId=99999999&connectionId=$connId", "view=COMMITS")
            client.refused("$q&tailPercent=26", "view=COMMITS")
            client.refused("teamId=$teamId&connectionId=99999999", "view=COMMITS")
            // A sprint period with no sprint to resolve reads nothing: lists are empty and there is no bar to name.
            val empty = "teamId=$teamId&lastSprints=1&connectionId=$connId"
            assertEquals(0, client.items(empty, "view=MRS_MERGED").total)
            client.refused(empty, "view=MR_CYCLE_WORKING_DAYS&bucketIndex=0")
            client.refused(empty, "view=WEEK&bucketStart=2026-03-02")
        }
    }

    @Test
    fun `a hidden distribution has no bar to list, but its measured set is still listed`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-drill-hidden", FlowUserRole.USER)
        inWorld(minSample = 3) {
            pin(this)
            assertTrue(client.refused(base(), "view=MR_CYCLE_WORKING_DAYS&bucketIndex=0").detail!!.contains("hidden"))
            assertEquals(2, client.items(base(), "view=MR_CYCLE_WORKING_DAYS").total)
        }
    }

    @Test
    fun `anonymous callers are refused, a plain user is served and a matching If-None-Match is a 304`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("code-drill-304", FlowUserRole.USER)
        inWorld {
            pin(this)
            val url = "/api/v1/reports/code-activity/items?${base()}&view=COMMITS"
            val ok = client.get(url)
            assertEquals(HttpStatusCode.OK, ok.status)
            val etag = assertNotNull(ok.headers[HttpHeaders.ETag])
            assertEquals(HttpStatusCode.NotModified, client.get(url) { header(HttpHeaders.IfNoneMatch, etag) }.status)
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(url).status)
            // A different figure is a different tag.
            assertTrue(client.get(url.replace("COMMITS", "MRS_MERGED")).headers[HttpHeaders.ETag] != etag)
        }
    }

    @Test
    fun `the stub's merged merge requests and commits equal an independent SQL listing and every figure reconciles`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("code-drill-fixture-admin", FlowUserRole.ADMIN)
        val user = seededClient("code-drill-fixture-user", FlowUserRole.USER)
        application.attributes[ReportServiceKey].clock = { REFERENCE_TIME }
        val zone = ZoneId.of(settings.read().timeZone)
        val teamId = TestTeams.seed("code-drill-fixture-${System.nanoTime()}")
        val memberships = TeamMembershipService(db, settings)
        val membershipIds = mutableListOf<UInt>()
        try {
            val confirmed = admin.post("/api/v1/data-sources/$ownId/code-identities/confirm-proposals").body<CodeIdentityConfirmResult>()
            assertEquals(CodeIdentityConfirmResult(linked = 6, bots = 1), confirmed)
            val linked = links.forHost(host).filter { it.decision == CodeIdentityDecision.LINKED }
            val accounts = linked.map { it.jiraAccountId!! }.distinct()
            accounts.forEach { membershipIds += memberships.create(teamId, TeamMembershipCreateRequest(it, validFrom = 0L)).id }
            val keys = linked.map { it.key }

            val from = "2026-03-01"
            val to = "2026-08-31"
            val (fromMs, toMs) = windowBounds(zone, from, to)
            val base = "teamId=$teamId&from=$from&to=$to&connectionId=$ownId&tailPercent=0"
            val placeholders = keys.joinToString(",", "(", ")") { "?" }
            val mergedKeys = jdbcRows(
                """SELECT p.path_with_namespace || '!' || m.iid FROM norm.code_merge_requests m
                   JOIN norm.code_projects p ON p.connection_id = m.connection_id AND p.project_id = m.project_id
                   WHERE m.connection_id = ? AND m.deleted_at IS NULL AND m.state = 'merged' AND m.merged_at >= ? AND m.merged_at < ?
                     AND m.author_key IN $placeholders""",
                ownId.toInt(), fromMs, toMs, *keys.toTypedArray(),
            ).map { it[0] as String }
            val commitKeys = jdbcRows(
                """SELECT p.path_with_namespace || '@' || substr(c.sha, 1, 12) FROM norm.code_commits c
                   JOIN norm.code_projects p ON p.connection_id = c.connection_id AND p.project_id = c.project_id
                   WHERE c.connection_id = ? AND NOT c.is_merge AND c.deleted_at IS NULL AND c.committed_at >= ? AND c.committed_at < ?
                     AND c.identity_key IN $placeholders""",
                ownId.toInt(), fromMs, toMs, *keys.toTypedArray(),
            ).map { it[0] as String }
            assertTrue(mergedKeys.isNotEmpty() && commitKeys.isNotEmpty(), "the stub's window is not empty")
            val mergedIds = jdbcRows(
                """SELECT m.mr_id FROM norm.code_merge_requests m
                   WHERE m.connection_id = ? AND m.deleted_at IS NULL AND m.state = 'merged' AND m.merged_at >= ? AND m.merged_at < ?
                     AND m.author_key IN $placeholders""",
                ownId.toInt(), fromMs, toMs, *keys.toTypedArray(),
            ).map { (it[0] as Number).toLong() }
            val commitIds = jdbcRows(
                """SELECT 'c:' || c.connection_id || ':' || c.project_id || ':' || c.sha FROM norm.code_commits c
                   WHERE c.connection_id = ? AND NOT c.is_merge AND c.deleted_at IS NULL AND c.committed_at >= ? AND c.committed_at < ?
                     AND c.identity_key IN $placeholders""",
                ownId.toInt(), fromMs, toMs, *keys.toTypedArray(),
            ).map { it[0] as String }

            // One request per view over HTTP (route, labels, wire) against the same figure selected in-process; then every figure of
            // the report is reconciled in-process, through the same selection, from ONE read of the code layer per filter.
            val service = application.attributes[ReportServiceKey]
            fun filterOf(team: UInt, picks: List<String>) = ReportFilter(
                period = ReportPeriod.DateRange(LocalDate.parse(from), LocalDate.parse(to), fromMs, toMs),
                level = if (picks.isEmpty()) ReportLevel.TEAM else ReportLevel.USER, teamId = team, accountIds = picks,
                domainView = DomainView.TASK, domain = null, activityType = null, workCategory = null, connectionId = ownId,
                tailPercent = 0,
            )
            val over = HttpCodeDrills(user, base)
            val inProcess = InProcessCodeDrills(service, filterOf(teamId, emptyList()), REFERENCE_TIME)
            val report = user.report(base)
            assertRouteAgrees(over, inProcess, report)
            val merged = assertNotNull(over.list("MRS_MERGED"))
            assertEquals(mergedKeys.sorted(), merged.ids.sorted(), "the merged merge requests")
            val commits = assertNotNull(over.list("COMMITS"))
            assertEquals(commitKeys.size, commits.total)
            assertEquals(minOf(commitKeys.size, 500), commits.listed)
            assertTrue(commits.ids.all { it in commitKeys.toSet() }, "the listed commits are the SQL's")
            val mergedRows = assertNotNull(inProcess.list("MRS_MERGED")).ids.sorted()
            assertEquals(mergedIds.map { "mr:$ownId:$it" }.sorted(), mergedRows, "the merged merge requests, by identity")
            assertEquals(commitIds.sorted(), assertNotNull(inProcess.list("COMMITS")).ids.sorted(), "the commits, by identity")

            val picks = accounts.take(2)
            val team = CodeDrillReconciler(inProcess, report)
            team.reconcile("fixture TEAM")
            assertTrue(team.compared > 100, "compared ${team.compared} figures")
            val usersFilter = filterOf(teamId, picks)
            val pickQuery = base + picks.joinToString("") { "&accountId=$it" }
            CodeDrillReconciler(InProcessCodeDrills(service, usersFilter, REFERENCE_TIME), user.report(pickQuery)).reconcile("fixture USER")
            val unassignedQuery = "teamId=0&from=$from&to=$to&connectionId=$ownId&tailPercent=0"
            CodeDrillReconciler(InProcessCodeDrills(service, filterOf(0u, emptyList()), REFERENCE_TIME), user.report(unassignedQuery))
                .reconcile("fixture UNASSIGNED")
        } finally {
            membershipIds.forEach { memberships.delete(teamId, it) }
            TestTeams.service.delete(teamId)
        }
    }

    companion object {
        private val REFERENCE_TIME = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
        private val dataSources = GitLabFixtures.dataSources()
        private var ownId: UInt = 0u

        @JvmStatic
        @BeforeClass
        fun warmFixtures() {
            warmStubFixtures()
            warmGitLabFixture()
            runBlocking {
                CodeIdentityLinkStore(sharedDatabaseForTests()).deleteHost(sourceHostOf(GitLabStubServer.baseUrl))
                ownId = GitLabFixtures.createConnection(dataSources, namePrefix = "code-drill-fixture")
                SyncedGitLabFixture.cloneProcessedData(SyncedGitLabFixture.connectionId(), ownId)
            }
        }

        @JvmStatic
        @AfterClass
        fun removeOwnConnection() = runBlocking<Unit> {
            CodeIdentityLinkStore(sharedDatabaseForTests()).deleteHost(sourceHostOf(GitLabStubServer.baseUrl))
            GitLabFixtures.purgeAll(ownId)
            dataSources.delete(ownId)
        }
    }
}
