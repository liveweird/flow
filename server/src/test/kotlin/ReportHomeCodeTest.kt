package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.identities.CodeIdentityDecision
import ch.nokillswit.identities.CodeIdentityLinkStore
import ch.nokillswit.identities.sourceHostOf
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.CodeActivityReport
import ch.nokillswit.reports.HomeOverview
import ch.nokillswit.reports.HomeTeam
import ch.nokillswit.reports.HomeTeamCode
import ch.nokillswit.reports.HomeWindow
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.home
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
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
 * Home's Code section (`.claude/docs/reports.md` "Home overview", 1.44.0) on the processed GitLab stub, the proposals confirmed over
 * HTTP against the Jira stub's people, on a CLONE of the fixture's processed rows (the fixture's own source is soft-deleted). Three
 * teams of 3 / 3 / 1 linked people and a fourth with none; one of them (the mover) is in the second team until a switch inside the
 * previous window and in the first after it, so a row of hers is split by its anchor. The clock is pinned so both 90-day windows hold
 * stub data. Every team's figures are compared with Report 21's OWN answer for the same team and window
 * (`GET /reports/code-activity`), with an INDEPENDENT JDBC derivation (`oracleCode`), and the over-budget path with
 * `reports.codeMaxRows`. Prints `in-test<TAB>home-code-90d<TAB><ms>` (three teams, the second request).
 */
class ReportHomeCodeTest {
    private val host = sourceHostOf(GitLabStubServer.baseUrl)
    private val links = CodeIdentityLinkStore(sharedDatabaseForTests())

    /** The arranged world: four teams, the people of the stub and the mover with the instant her team changes. */
    private class World(
        val first: UInt,
        val second: UInt,
        val third: UInt,
        val empty: UInt,
        val mover: String,
        val switchAt: Long,
        val connIds: List<UInt>,
        val zone: ZoneId,
        val min: Int,
    ) {
        val picked get() = listOf(first, second, third)
        val cur: OWindow get() = oracleWindows(zone, NOW).first
        val prev: OWindow get() = oracleWindows(zone, NOW).second
    }

    private suspend fun ApplicationTestBuilder.inWorld(body: suspend World.(HttpClient) -> Unit) {
        val admin = seededClient("home-code-admin", FlowUserRole.ADMIN)
        val user = seededClient("home-code-user", FlowUserRole.USER)
        application.attributes[ReportServiceKey].clock = { NOW }
        val zone = reportZone()
        val db = sharedDatabaseForTests()
        val settings = MetricsSettingsService(db, dataSources, SyncJobsService(db, 3))
        val memberships = TeamMembershipService(db, settings)
        val tag = System.nanoTime()
        val teams = listOf("A", "B", "C", "D").map { TestTeams.seed("home-code-$it-$tag") }
        val created = mutableListOf<Pair<UInt, UInt>>()
        suspend fun join(team: UInt, account: String, from: Long, to: Long? = null) {
            created += team to memberships.create(team, TeamMembershipCreateRequest(account, validFrom = from, validTo = to)).id
        }
        try {
            val confirm = admin.post("/api/v1/data-sources/$ownId/code-identities/confirm-proposals")
            assertEquals(HttpStatusCode.OK, confirm.status, confirm.bodyAsText())
            val linked = links.forHost(host).filter { it.decision == CodeIdentityDecision.LINKED }
            val people = linked.map { it.jiraAccountId!! }.distinct().sorted()
            assertEquals(6, people.size, "the stub's six linked humans")
            val connIds = dataSources.activeGitLabBaseUrls().keys.toList()
            val prev = oracleWindows(zone, NOW).second
            // The mover: the person with the most merge requests merged in the previous window; she switches at her second one's instant.
            val merged = jdbcRows(
                """SELECT l.jira_account_id, m.merged_at FROM norm.code_merge_requests m
                   JOIN public.code_identity_links l ON l.source_host = ? AND l.identity_key = m.author_key AND l.decision = 'LINKED'
                   WHERE m.connection_id IN (${connIds.joinToString(",") { it.toInt().toString() }}) AND m.deleted_at IS NULL
                     AND m.state = 'merged' AND m.merged_at >= ? AND m.merged_at < ? ORDER BY l.jira_account_id, m.merged_at""",
                host, prev.fromMs, prev.toMs,
            ).groupBy({ it[0] as String }) { (it[1] as Number).toLong() }
            val mover = merged.entries.maxBy { it.value.size }
            assertTrue(mover.value.size >= 3, "the mover has merge requests to split")
            val others = people - mover.key
            val switchAt = mover.value.sorted()[1]
            join(teams[1], mover.key, 0L, switchAt)
            join(teams[0], mover.key, switchAt)
            join(teams[0], others[0], 0L)
            join(teams[0], others[1], 0L)
            join(teams[1], others[2], 0L)
            join(teams[1], others[3], 0L)
            join(teams[2], others[4], 0L)
            World(teams[0], teams[1], teams[2], teams[3], mover.key, switchAt, connIds, zone, minSample()).body(user)
        } finally {
            created.forEach { (team, id) -> memberships.delete(team, id) }
            teams.forEach { TestTeams.service.delete(it) }
        }
    }

    private suspend fun minSample(): Int = DerivedStubFixture.metricsSettings().read().minSampleSize

    private suspend fun HttpClient.home(teams: List<UInt>): HomeOverview {
        val response = get("/api/v1/reports/home?" + teams.joinToString("&") { "teamId=$it" })
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun HttpClient.code(team: UInt, window: HomeWindow): CodeActivityReport {
        val response = get("/api/v1/reports/code-activity?teamId=$team&from=${window.from}&to=${window.to}")
        assertEquals(HttpStatusCode.OK, response.status, "$team ${window.from}")
        return response.body()
    }

    /** One window's expected Code figures, whoever derived them. */
    private class Expected(val merged: Core, val cycle: Core, val withoutReview: Core, val open: Core, val age: Core, val drafts: Int)

    private fun CodeActivityReport.expected(): Expected {
        val read = review?.n ?: 0
        val min = meta.minSampleSize
        val share = flow.mergedWithoutReview
        return Expected(
            merged = countCore(flow.merged),
            cycle = Core(flow.cycleWorkingDays.p50, flow.cycleWorkingDays.n.toInt(), flow.cycleWorkingDays.hidden),
            withoutReview = Core(share.share.takeIf { read >= min }, read, read < min, share.count),
            open = countCore(open.count),
            age = Core(open.age.p50, open.age.n, open.age.hidden),
            drafts = open.drafts,
        )
    }

    private fun OCode.expected(min: Int) = Expected(
        merged = countCore(merged),
        cycle = Core(cycleP50, merged, merged < min),
        withoutReview = Core(if (read >= min) withoutReview.toDouble() / read else null, read, read < min, withoutReview),
        open = countCore(openWaiting),
        age = Core(openAgeP50, openWaiting, openWaiting < min),
        drafts = openDrafts,
    )

    private fun assertCode(label: String, code: HomeTeamCode, now: Expected, before: Expected, min: Int) {
        checkFigure("$label merged", code.merged, now.merged, before.merged, false, 0.0, itemsUnit = true, min = min)
        checkFigure("$label cycle", code.cycleP50, now.cycle, before.cycle, true, 0.05, itemsUnit = false, min = min)
        checkFigure("$label open", code.openCount, now.open, null, true, 0.0, itemsUnit = true, min = min)
        checkFigure("$label age", code.openAgeP50, now.age, null, true, 0.05, itemsUnit = false, min = min)
        checkFigure(
            "$label without review", code.mergedWithoutReview, now.withoutReview, before.withoutReview, true, 0.005,
            itemsUnit = false, min = min,
        )
        assertEquals(now.drafts, code.openDrafts, "$label drafts")
    }

    private fun HomeTeam.code(): HomeTeamCode = assertNotNull(code, "$name has a code section")

    @Test
    fun `every team's code figures equal Report 21's own for the same team and window, the mover's rows split by anchor`() =
        testApplication {
            configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
            startApplication()
            inWorld { user ->
                val overview = user.home(picked)
                assertEquals(connIds.toSet(), overview.code.sources.map { it.connectionId }.toSet(), "Report 21's sources[]")
                assertTrue(!overview.code.overBudget)
                var someMerged = 0
                var someVerdict = 0
                for (team in overview.teams) {
                    val now = user.code(team.teamId, overview.window).expected()
                    val before = user.code(team.teamId, overview.previousWindow).expected()
                    assertCode(team.name, team.code(), now, before, min)
                    someMerged += now.merged.n + before.merged.n
                    if (team.code().merged.previous?.verdict != null) someVerdict++
                }
                assertTrue(someMerged > 0, "the arranged world holds merged merge requests")
                assertTrue(someVerdict > 0, "at least one merged-count trend reads BETTER or WORSE")
                // The mover's merge requests belong to the second team before the switch and to the first after it.
                val before = user.code(second, overview.previousWindow).groups.single { it.accountId == mover }
                val after = user.code(first, overview.previousWindow).groups.single { it.accountId == mover }
                assertTrue(before.mrsMerged > 0 && after.mrsMerged > 0, "the mover merged on both sides of her switch")
            }
        }

    @Test
    fun `an independent SQL derivation over norm and the memberships agrees with every team and both windows`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        inWorld { user ->
            val overview = user.home(picked)
            val now = application.attributes[ReportServiceKey].clock()
            for (team in overview.teams) {
                val current = oracleCode(connIds, host, team.teamId, cur, now, zone, min)
                val previous = oracleCode(connIds, host, team.teamId, prev, now, zone, min)
                assertCode(team.name, team.code(), current.expected(min), previous.expected(min), min)
            }
            // The mover's merge requests split at the switch instant (the half-open edge goes to the first team): a row at the very
            // instant is the new team's.
            val ids = connIds.joinToString(",") { it.toInt().toString() }
            val atEdge = jdbcRows(
                "SELECT count(*) FROM norm.code_merge_requests WHERE connection_id IN ($ids) AND state = 'merged' AND merged_at = ?",
                switchAt,
            ).single()[0] as Number
            assertTrue(atEdge.toInt() >= 1, "a merge request sits exactly on the switch")
        }
    }

    @Test
    fun `linked members count the people with a linked identity, a team without people has zero figures, the default pick keeps working`() =
        testApplication {
            configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
            startApplication()
            inWorld { user ->
                val overview = user.home(picked)
                val linkedOf = listOf(first, second, third).map { id -> overview.teams.single { it.teamId == id }.code().linkedMembers }
                assertEquals(listOf(3, 3, 1), linkedOf)
                val none = user.home(listOf(empty)).teams.single().code()
                assertEquals(0, none.linkedMembers)
                assertEquals(listOf(0, 0, 0), listOf(none.merged.n, none.openCount.n, none.mergedWithoutReview.n))
                assertEquals(0, none.mergedWithoutReview.numerator)
                assertNull(none.openCount.previous, "the open count has no trend")
                assertNull(none.openAgeP50.previous, "the open age has no trend")
                assertNotNull(none.merged.previous, "the merged count has one")
                // Without a pick Home still answers: whichever three teams come first have a code section (or none, with no GitLab source).
                assertEquals(HttpStatusCode.OK, user.get("/api/v1/reports/home").status)
            }
        }

    private suspend fun HttpClient.drill(path: String): ReportDrillItems {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, path)
        return response.body()
    }

    @Test
    fun `every code drill lists what its figure counted, equals Report 21's own drill and an independent SQL listing`() =
        testApplication {
            configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
            startApplication()
            inWorld { user ->
                val overview = user.home(picked)
                val now = application.attributes[ReportServiceKey].clock()
                val views = mapOf(
                    "CODE_MERGED" to "MRS_MERGED",
                    "CODE_OPEN" to "OPEN_MRS_WAITING",
                    "CODE_MERGED_WITHOUT_REVIEW" to "MERGED_WITHOUT_REVIEW",
                )
                val listed = views.keys.associateWith { 0 }.toMutableMap()
                for (team in overview.teams) {
                    val code = team.code()
                    val counts = mapOf(
                        "CODE_MERGED" to code.merged.n,
                        "CODE_OPEN" to code.openCount.n,
                        "CODE_MERGED_WITHOUT_REVIEW" to code.mergedWithoutReview.numerator!!,
                    )
                    for ((view, reportView) in views) {
                        val label = "${team.name} $view"
                        val home = user.drill("/api/v1/reports/home/items?view=$view&teamId=${team.teamId}")
                        assertEquals(counts.getValue(view), home.bar.count, "$label: bar.count is the figure's count")
                        assertEquals(home.bar.count, home.total, "$label: total")
                        assertEquals(home.total, home.items.size, "$label: nothing cut at this size")
                        assertTrue(!home.truncated && home.items.all { it.itemKind.name == "MERGE_REQUEST" }, label)
                        val sql = oracleCodeKeys(connIds, host, team.teamId, cur, now, view)
                        assertEquals(sql.sorted(), home.items.map { it.issueKey }.sorted(), "$label: the SQL listing")
                        val period = "from=${overview.window.from}&to=${overview.window.to}"
                        val report = user.drill("/api/v1/reports/code-activity/items?teamId=${team.teamId}&$period&view=$reportView")
                        assertEquals(report.bar, home.bar, "$label: Report 21's bar")
                        assertEquals(report.total, home.total, "$label: Report 21's total")
                        assertEquals(report.items, home.items, "$label: Report 21's items, figure and anchor included")
                        listed[view] = listed.getValue(view) + home.total
                    }
                }
                listed.forEach { (view, total) -> assertTrue(total > 0, "$view lists something across the arranged teams") }
            }
        }

    @Test
    fun `a code read over reports codeMaxRows degrades the section, never the page`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start(), "reports.codeMaxRows" to "3")
        startApplication()
        inWorld { user ->
            val overview = user.home(picked)
            assertTrue(overview.code.overBudget)
            assertTrue(overview.code.sources.isNotEmpty(), "the sources stay listed")
            assertEquals(3, overview.teams.size)
            overview.teams.forEach { assertNull(it.code, "${it.name}: no code section") }
            // The Jira sections are intact: a within-budget answer differs from this one in the code part alone (a service with no code
            // layer wired answers the Jira half of the very same overview, at the same pinned clock).
            val settings = DerivedStubFixture.metricsSettings()
            val jiraOnly = ReportService(sharedDatabaseForTests(), settings, TeamMembershipService(sharedDatabaseForTests(), settings))
                .home(NOW, picked)
            assertEquals(jiraOnly, overview.copy(code = jiraOnly.code), "the Jira half equals a within-budget answer's")
            // The drill is a user action with a clear message: Report 21's plain 400, not a degraded list.
            val drill = user.get("/api/v1/reports/home/items?view=CODE_MERGED&teamId=$first")
            assertEquals(HttpStatusCode.BadRequest, drill.status)
            val detail = "The selection reads more than 3 code rows (commits, merge requests and review events) - " +
                "narrow the period or pick fewer people"
            assertEquals(detail, drill.body<ProblemDetail>().detail)
            // Report 21 itself keeps the plain 400 for the same budget.
            val report = user.get("/api/v1/reports/code-activity?teamId=$first&from=${overview.window.from}&to=${overview.window.to}")
            assertEquals(HttpStatusCode.BadRequest, report.status)
        }
    }

    @Test
    fun `the timing target - three teams over 90 days, the second request`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        inWorld { user ->
            user.home(picked)
            val started = System.nanoTime()
            user.home(picked)
            println("in-test\thome-code-90d\t${(System.nanoTime() - started) / 1_000_000L}")
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-08-31T10:00:00Z").toEpochMilli()
        private val dataSources = GitLabFixtures.dataSources()
        private var ownId: UInt = 0u

        @JvmStatic
        @BeforeClass
        fun warmFixtures() {
            warmStubFixtures()
            warmGitLabFixture()
            runBlocking {
                CodeIdentityLinkStore(sharedDatabaseForTests()).deleteHost(sourceHostOf(GitLabStubServer.baseUrl))
                ownId = GitLabFixtures.createConnection(dataSources, namePrefix = "home-code")
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
