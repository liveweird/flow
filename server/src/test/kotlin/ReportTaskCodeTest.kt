package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.TaskCodeQuality
import ch.nokillswit.reports.TaskCodeReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Report 22 over HTTP (`GET /api/v1/reports/task-code`, `.claude/docs/reports.md` "Report 22", A51) on the hand-written two-layer
 * [TaskCodeWorld]: every figure is computed by hand from its rows (the world's KDoc lists the tasks and their merge requests), the old key
 * of a moved issue, the junk and unknown keys, a deleted merge request, two GitLab sources, team vs domain scope, the ambiguous key across
 * two Jira sources, the row budget and the `400` / `304` paths. The world's days are midnights of the report zone and `now` is pinned to
 * day 30. `minSampleSize` is pinned to 3, so the code lead time (n = 3) shows and the merged-after-done lag (n = 2) is hidden.
 */
class ReportTaskCodeTest {
    private suspend fun <T> inWorld(body: suspend TaskCodeWorld.() -> T): T =
        withTaskCodeWorld(reportZone()) { withMinSampleSize(3) { body() } }

    private fun ApplicationTestBuilder.pin(world: TaskCodeWorld) {
        application.attributes[ReportServiceKey].clock = { world.now }
    }

    private fun TaskCodeWorld.team(team: UInt = teamA, extra: String = "") = "teamId=$team&$period&connectionId=$jiraA$extra"

    private suspend fun HttpClient.taskCode(query: String): TaskCodeReport {
        val response = get("/api/v1/reports/task-code?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    private suspend fun HttpClient.refused(query: String): String {
        val response = get("/api/v1/reports/task-code?$query")
        assertEquals(HttpStatusCode.BadRequest, response.status, query)
        return assertNotNull(response.body<ProblemDetail>().detail)
    }

    private fun TaskCodeReport.counts() = listOf(tasks.done, tasks.withMergeRequest, tasks.withoutMergeRequest)

    private fun TaskCodeQuality.asList() = listOf(neverStarted, onlyUnmergedMrs, tasksWithAmbiguousKey)

    @Test
    fun `a team's figures are the hand-computed ones - the last merge, an old key, two sources, a deleted and an unmerged merge request`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("task-code-team", FlowUserRole.USER)
            inWorld {
                pin(this)
                val report = client.taskCode(team())
                assertEquals(3, report.meta.minSampleSize)
                // Done: TCA-1, -2, -3, -4, TCB-7, TCA-6. With a merged merge request: all but TCA-3 (open and closed only) and TCA-6
                // (its one merge request is deleted). The other team's task, the credit-less one, the one done on day 40 and the sub-task
                // are not in the team's population.
                assertEquals(listOf(6, 4, 2), report.counts())
                assertEquals(2.0 / 6.0, assertNotNull(report.tasks.withoutShare), 1e-12)
                // Code lead time = start -> LAST merge, in working days: TCA-1 d0 -> d3 12:00 (the glB merge request, the same mr id as
                // glA's first) = 3.5; TCA-2 d1 -> d8 over the weekend = 5; TCB-7 d7 -> d10 12:00 (the merge request naming its OLD key,
                // TCA-50, is the last) = 3.5. TCA-4 never started: no lead time.
                assertDistribution("lead", listOf(3.5, 5.0, 3.5), report.codeLeadTimeWorkingDays, 3)
                // Merged after done: TCA-2 (d3 -> d8: Thu, Fri, Mon = 3) and TCB-7 (done d10 00:00, merged d10 12:00 = 0.5), over the
                // 4 tasks with a merged merge request; the lag is hidden under the minimum sample of 3.
                assertEquals(2, report.mergedAfterDone.count)
                assertEquals(0.5, assertNotNull(report.mergedAfterDone.share), 1e-12)
                assertDistribution("lag", listOf(3.0, 0.5), report.mergedAfterDone.lagWorkingDays, 3)
                // Quality: TCA-4 never started; TCA-3 has only unmerged merge requests; no task has an ambiguous key: the deleted and the
                // moved-out issues that also carry TCA-1 are not live, so TCA-1 still names its task alone (and its three merge requests
                // link: the lead time above is 3.5).
                assertEquals(listOf(1, 1, 0), report.quality.asList())
                // Both GitLab sources are printed (others of the shared database may be too).
                val sources = report.sources.associateBy { it.connectionId }
                assertTrue(glA in sources && glB in sources)
                assertTrue(sources.getValue(glA).baseUrl.startsWith("https://task-code-"))
            }
        }

    @Test
    fun `a domain reads every team's done tasks and teamId 0 the ones with no credit team`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("task-code-domain", FlowUserRole.USER)
        inWorld {
            pin(this)
            // The domain adds the other team's TCA-11 (d1 -> d3 = 2) and the credit-less TCA-13 (d1 -> d4 = 3), both merged on time.
            val domainReport = client.taskCode("domain=$domain&$period&connectionId=$jiraA")
            assertEquals(listOf(8, 6, 2), domainReport.counts())
            assertDistribution("domain lead", listOf(3.5, 5.0, 3.5, 2.0, 3.0), domainReport.codeLeadTimeWorkingDays, 3)
            assertEquals(2, domainReport.mergedAfterDone.count)
            assertEquals(2.0 / 6.0, assertNotNull(domainReport.mergedAfterDone.share), 1e-12)
            // A team and the domain agree on the team's own tasks: the other team is its own.
            val other = client.taskCode(team(teamB))
            assertEquals(listOf(1, 1, 0), other.counts())
            assertDistribution("other lead", listOf(2.0), other.codeLeadTimeWorkingDays, 3)
            val unassigned = client.taskCode(team(0u))
            assertEquals(listOf(1, 1, 0), unassigned.counts())
            assertDistribution("unassigned lead", listOf(3.0), unassigned.codeLeadTimeWorkingDays, 3)
            assertEquals(0.0, assertNotNull(unassigned.tasks.withoutShare), 1e-12)
        }
    }

    @Test
    fun `a key naming issues of two Jira sources links to none and is counted, whatever connectionId narrows the tasks to`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("task-code-ambiguous", FlowUserRole.USER)
            inWorld {
                pin(this)
                task(107, "TCA-9", 1, 4)
                publish()
                write(glA, mr(19, "TCA-9: shared", 2))
                val all = "teamId=$teamA&$period"
                val scoped = "teamId=$teamA&$period&connectionId=$jiraA"
                val before = client.taskCode(all)
                assertEquals(listOf(7, 5, 2), before.counts(), "TCA-9 links while only one source names it")
                assertEquals(0, before.quality.tasksWithAmbiguousKey)
                val second = withSecondJira()
                task(207, "TCA-9", null, null, connId = second)
                // Every active Jira source decides ambiguity: `connectionId` narrows only the tasks, so naming one changes nothing.
                for (query in listOf(all, scoped)) {
                    val after = client.taskCode(query)
                    assertEquals(listOf(7, 4, 3), after.counts(), "TCA-9 now names two issues: it links to none ($query)")
                    assertEquals(1, after.quality.tasksWithAmbiguousKey, query)
                    assertDistribution("lead", listOf(3.5, 5.0, 3.5), after.codeLeadTimeWorkingDays, 3)
                }
            }
        }

    @Test
    fun `a selection over the code-row budget is a 400 that says to narrow it, and one under it answers`() = testApplication {
        configureApp("reports.codeMaxRows" to "3")
        startApplication()
        val client = seededClient("task-code-budget", FlowUserRole.USER)
        inWorld {
            pin(this)
            // The team's keys name six merge requests' worth of link rows (TCA-1 alone three): over a budget of 3, never a cut figure.
            val detail = "The selection reads more than 3 code rows (commits, merge requests and review events) - " +
                "narrow the period or pick fewer people"
            assertEquals(detail, client.refused(team()))
            // The other team's one task has one link row: under the budget it answers as ever.
            assertEquals(listOf(1, 1, 0), client.taskCode(team(teamB)).counts())
        }
    }

    @Test
    fun `an accountId, a missing scope, a GitLab or unknown connection and an unknown team are 400s, an empty sprint period is empty`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("task-code-400", FlowUserRole.USER)
            inWorld {
                pin(this)
                val noUser = client.refused(team(extra = "&accountId=someone"))
                assertEquals("accountId is not available: Tasks and code has no user level", noUser)
                assertTrue(client.refused(period).startsWith("teamId or domain is required"))
                // A GITLAB connection is not a Jira source, like an unknown one (nothing is disclosed).
                val gitlab = client.refused("teamId=$teamA&$period&connectionId=$glA")
                assertTrue(gitlab.startsWith("Unknown or inactive connectionId"), gitlab)
                val unknown = client.refused("teamId=$teamA&$period&connectionId=987654321")
                assertEquals(gitlab.replace(glA.toString(), "<id>"), unknown.replace("987654321", "<id>"))
                client.refused("teamId=987654321&$period&connectionId=$jiraA")
                // A sprint period with no sprint to resolve reads nothing: every figure is empty, the quality included.
                val empty = client.taskCode("teamId=$teamA&lastSprints=1&connectionId=$jiraA")
                assertEquals(listOf(0, 0, 0), empty.counts())
                assertNull(empty.tasks.withoutShare)
                assertNull(empty.mergedAfterDone.share)
                assertTrue(empty.codeLeadTimeWorkingDays.hidden && empty.mergedAfterDone.lagWorkingDays.hidden)
                assertEquals(listOf(0, 0, 0), empty.quality.asList())
            }
        }

    @Test
    fun `a matching If-None-Match is a 304 with no body, and an anonymous caller is refused`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("task-code-304")
        inWorld {
            pin(this)
            val url = "/api/v1/reports/task-code?${team()}"
            val ok = client.get(url)
            assertEquals(HttpStatusCode.OK, ok.status)
            val etag = assertNotNull(ok.headers[HttpHeaders.ETag])
            val notModified = client.get(url) { header(HttpHeaders.IfNoneMatch, etag) }
            assertEquals(HttpStatusCode.NotModified, notModified.status)
            assertEquals(etag, notModified.headers[HttpHeaders.ETag])
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(url).status)
        }
    }
}
