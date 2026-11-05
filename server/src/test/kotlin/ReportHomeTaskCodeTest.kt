package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.reports.HomeOverview
import ch.nokillswit.reports.HomeTaskCodeTasks
import ch.nokillswit.reports.HomeTeamCode
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.TaskCodeReport
import ch.nokillswit.reports.TaskCodeTasks
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Home's "done tasks without a merge request" figure (`.claude/docs/reports.md` "Home overview", 1.53.0) on Report 22's hand-written
 * two-layer [TaskCodeWorld]: two teams with done tasks (6 of which 2 are without a merged merge request, and 1 of which none) and a
 * third with no done task at all. Every team's `code.tasks` is compared with the hand-computed block AND with `GET /reports/task-code`
 * for the same team and Home's window, the `CODE_TASKS_WITHOUT_MR` drill with the figure and with `GET /reports/task-code/items`, and
 * the absent layer, the row budget and the `400` matrix with the other `CODE_*` views. `minSampleSize` is pinned to 3 (it gates no figure
 * of this block). The clock is pinned to day 30 of the world, so Home's 90-day window holds every done day of the world.
 */
class ReportHomeTaskCodeTest {
    private suspend fun <T> inWorld(body: suspend TaskCodeWorld.(teamC: UInt) -> T): T =
        withTaskCodeWorld(reportZone()) {
            val teamC = TestTeams.seed("task-code-c-${UUID.randomUUID().toString().take(8)}")
            try {
                withMinSampleSize(3) { body(teamC) }
            } finally {
                TestTeams.service.delete(teamC)
            }
        }

    private suspend fun HttpClient.home(teams: List<UInt>): HomeOverview {
        val response = get("/api/v1/reports/home?" + teams.joinToString("&") { "teamId=$it" })
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun HttpClient.taskCode(team: UInt, overview: HomeOverview): TaskCodeReport {
        val response = get("/api/v1/reports/task-code?teamId=$team&from=${overview.window.from}&to=${overview.window.to}")
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun HttpClient.drill(path: String): ReportDrillItems {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, path)
        return response.body()
    }

    private fun HomeOverview.codeOf(team: UInt): HomeTeamCode =
        assertNotNull(teams.single { it.teamId == team }.code, "team $team has a code section")

    private fun HomeTaskCodeTasks.counts() = listOf(done, withMergeRequest, withoutMergeRequest)

    /** Report 22's block as Home prints it: the same four figures, without the commit subset Home never reads. */
    private fun TaskCodeTasks.forHome() = HomeTaskCodeTasks(done, withMergeRequest, withoutMergeRequest, withoutShare)

    @Test
    fun `every picked team's figure is the hand-computed block and equals Report 22's own for the same team and window`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("home-task-code", FlowUserRole.USER)
            inWorld { teamC ->
                application.attributes[ReportServiceKey].clock = { now }
                val overview = client.home(listOf(teamA, teamB, teamC))
                assertTrue(!overview.code.overBudget)
                // Team A: TCA-1, -2, -3, -4, TCB-7, TCA-6 are done; TCA-3 (open and closed merge requests only) and TCA-6 (its one merge
                // request is deleted) have no MERGED one. Team B's one task is merged on time; team C has no done task.
                val a = assertNotNull(overview.codeOf(teamA).tasks)
                assertEquals(listOf(6, 4, 2), a.counts())
                assertEquals(2.0 / 6.0, assertNotNull(a.withoutShare), 1e-12)
                val b = assertNotNull(overview.codeOf(teamB).tasks)
                assertEquals(listOf(1, 1, 0), b.counts())
                assertEquals(0.0, assertNotNull(b.withoutShare), 1e-12)
                // No done task: zeros and NO share (not 0 %, not NaN), whatever the team's linked people (there are none).
                val c = assertNotNull(overview.codeOf(teamC).tasks)
                assertEquals(listOf(0, 0, 0), c.counts())
                assertNull(c.withoutShare)
                assertEquals(0, overview.codeOf(teamC).linkedMembers)
                // The very block of Report 22 for each team over Home's window (the old key of TCB-7, the two Jira sources' keys and the
                // deleted merge request included): one combined read, the same numbers. Report 22 also counts the tasks only a commit names
                // (team A: TCA-6, one of its two without); Home's combined read skips that statement and prints no such figure.
                for (team in listOf(teamA, teamB, teamC)) {
                    val report = client.taskCode(team, overview).tasks
                    assertEquals(report.forHome(), overview.codeOf(team).tasks, "team $team")
                    assertEquals(report.withoutMergeRequest, overview.codeOf(team).tasks?.withoutMergeRequest, "team $team: Home's column")
                }
                assertEquals(1, client.taskCode(teamA, overview).tasks.namedByCommitsOnly)
                // A pick of one team reads the same block: the figure does not depend on who else is listed.
                assertEquals(a, client.home(listOf(teamA)).codeOf(teamA).tasks)
            }
        }

    @Test
    fun `the drill lists the tasks behind the figure - an unmerged-only task and a task nothing names - and equals Report 22's own list`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("home-task-code-drill", FlowUserRole.USER)
            inWorld { teamC ->
                application.attributes[ReportServiceKey].clock = { now }
                val overview = client.home(listOf(teamA, teamB, teamC))
                for (team in listOf(teamA, teamB, teamC)) {
                    val figure = assertNotNull(overview.codeOf(team).tasks)
                    val home = client.drill("/api/v1/reports/home/items?view=CODE_TASKS_WITHOUT_MR&teamId=$team")
                    assertEquals(figure.withoutMergeRequest, home.bar.count, "$team: bar.count is the figure's count")
                    assertEquals(home.bar.count, home.total, "$team: total")
                    assertEquals(home.total, home.items.size)
                    assertTrue(!home.truncated, "$team: nothing cut at this size")
                    assertTrue(home.items.all { it.itemKind.name == "TASK" && it.figure == null }, "$team: tasks, no figure")
                    val period = "from=${overview.window.from}&to=${overview.window.to}"
                    val report = client.drill("/api/v1/reports/task-code/items?teamId=$team&$period&view=TASKS_WITHOUT_MR")
                    assertEquals(report.bar, home.bar, "$team: Report 22's bar")
                    assertEquals(report.total, home.total)
                    assertEquals(report.items, home.items, "$team: Report 22's items, merge request keys and anchors included")
                }
                // Team A: TCA-6 (done day 11, its only merge request is deleted: nothing linked) before TCA-3 (done day 4; an open and
                // a closed merge request, listed by their keys, sorted by number: it is "without", counted in onlyUnmergedMrs).
                val items = client.drill("/api/v1/reports/home/items?view=CODE_TASKS_WITHOUT_MR&teamId=$teamA").items
                assertEquals(listOf("TCA-6", "TCA-3"), items.map { it.issueKey })
                assertEquals(listOf(emptyList(), listOf("grp/app!4", "grp/app!5")), items.map { it.linkedKeys })
                assertEquals(listOf(at(11), at(4)), items.map { it.anchorAt })
                assertTrue(items.all { it.teamId == teamA && it.measuredKey == null })
                // Team B and the team with no task have nothing to list.
                assertEquals(0, client.drill("/api/v1/reports/home/items?view=CODE_TASKS_WITHOUT_MR&teamId=$teamB").total)
                assertEquals(0, client.drill("/api/v1/reports/home/items?view=CODE_TASKS_WITHOUT_MR&teamId=$teamC").items.size)
            }
        }

    @Test
    fun `the drill of a team that was not picked lists it, and unknown, team 0 and no team are the 400s the other code views answer`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("home-task-code-400", FlowUserRole.USER)
            inWorld { teamC ->
                application.attributes[ReportServiceKey].clock = { now }
                // The drill has no pick: team A is listed although the overview below names the others.
                val overview = client.home(listOf(teamB, teamC))
                assertEquals(2, client.drill("/api/v1/reports/home/items?view=CODE_TASKS_WITHOUT_MR&teamId=$teamA").total)
                assertEquals(2, overview.teams.size)
                for (query in listOf("teamId=987654321", "teamId=0", "")) {
                    val other = client.get("/api/v1/reports/home/items?view=CODE_MERGED&$query")
                    val tasks = client.get("/api/v1/reports/home/items?view=CODE_TASKS_WITHOUT_MR&$query")
                    assertEquals(HttpStatusCode.BadRequest, other.status, query)
                    assertEquals(other.status, tasks.status, query)
                    assertEquals(other.body<ProblemDetail>().detail, tasks.body<ProblemDetail>().detail, query)
                }
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/home/items?view=CODE_NOPE&teamId=$teamA").status)
            }
        }

    @Test
    fun `without an active GitLab source the figure is absent and its drill is empty, like the other code views`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("home-task-code-none", FlowUserRole.USER)
        val team = TestTeams.seed("task-code-none-${UUID.randomUUID().toString().take(8)}")
        try {
            val overview = client.home(listOf(team))
            assertTrue(overview.code.sources.isEmpty(), "the layer is absent: no GitLab source is active in this test")
            assertNull(overview.teams.single().code, "no code section, so no figure: never 'every task is without a merge request'")
            for (view in listOf("CODE_MERGED", "CODE_TASKS_WITHOUT_MR")) {
                val items = client.drill("/api/v1/reports/home/items?view=$view&teamId=$team")
                assertEquals(listOf(0, 0, false), listOf(items.bar.count, items.total, items.truncated), view)
                assertTrue(items.items.isEmpty(), view)
            }
        } finally {
            TestTeams.service.delete(team)
        }
    }

    @Test
    fun `a read over reports codeMaxRows drops this figure alone and the drill is Report 22's plain 400`() = testApplication {
        configureApp("reports.codeMaxRows" to "3")
        startApplication()
        val client = seededClient("home-task-code-budget", FlowUserRole.USER)
        inWorld { teamC ->
            application.attributes[ReportServiceKey].clock = { now }
            // Team A's keys alone name more than three link rows, so the combined read of A and B is over the budget: the figure is
            // null for both, the rest of the section (and the page) is intact.
            val both = client.home(listOf(teamA, teamB, teamC))
            assertTrue(!both.code.overBudget && both.code.sources.isNotEmpty())
            both.teams.forEach {
                assertNull(assertNotNull(it.code, "${it.name}: the section stays").tasks, "${it.name}: this figure is over")
            }
            // Team B alone is one link row: under the budget it answers as ever, and so does its drill. Team A's drill is Report 22's 400.
            assertEquals(listOf(1, 1, 0), assertNotNull(client.home(listOf(teamB)).codeOf(teamB).tasks).counts())
            assertEquals(0, client.drill("/api/v1/reports/home/items?view=CODE_TASKS_WITHOUT_MR&teamId=$teamB").total)
            val refused = client.get("/api/v1/reports/home/items?view=CODE_TASKS_WITHOUT_MR&teamId=$teamA")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            val detail = "The selection reads more than 3 code rows (commits, merge requests and review events) - " +
                "narrow the period or pick fewer people"
            assertEquals(detail, refused.body<ProblemDetail>().detail)
        }
    }
}
