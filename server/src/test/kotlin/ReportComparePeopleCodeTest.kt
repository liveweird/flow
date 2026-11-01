package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.norm.DetailsState
import ch.nokillswit.norm.NormCodeMergeRequest
import ch.nokillswit.metrics.MetricsSettingsService
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.reports.CodeActivityReport
import ch.nokillswit.reports.CompareCell
import ch.nokillswit.reports.CompareDirection
import ch.nokillswit.reports.CompareGroup
import ch.nokillswit.reports.CompareRow
import ch.nokillswit.reports.CompareUnit
import ch.nokillswit.reports.ComparePeopleReport
import ch.nokillswit.reports.PersonTrendReport
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.comparePeople
import ch.nokillswit.reports.comparePeopleItems
import ch.nokillswit.reports.CapacityGrant
import ch.nokillswit.reports.parseComparePeople
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `CODE` rows of Compare people and Person trend (A50, `.claude/docs/reports.md` "Report 18" / "Report 20") on the hand-written
 * [CodeWorld]: alice (`accA`, the team's member) and bob (`accB`, a reviewer with no team) are compared ACROSS teams, every row
 * hand-computed from the world's rows and equal to Report 21's member cell of the same person; the Jira slices do not apply to them;
 * a person with no link has zeros; every cell equals its drill (reconciled in-process); the trend's code points are Compare people's
 * cells over each window. The processed GitLab stub's version is in `ReportCodeActivityFixtureTest`.
 */
class ReportComparePeopleCodeTest {
    private val db = sharedDatabaseForTests()
    private val settings = MetricsSettingsService(db, GitLabFixtures.dataSources(), SyncJobsService(db, 3))

    private suspend fun reportZone(): ZoneId = ZoneId.of(settings.read().timeZone)

    private suspend fun <T> inWorld(minSample: Int = 1, body: suspend CodeWorld.() -> T): T = withCodeWorld(reportZone()) {
        withMetricsSettings(settings, { it.copy(minSampleSize = minSample) }) { body() }
    }

    private fun ApplicationTestBuilder.pin(world: CodeWorld) {
        application.attributes[ReportServiceKey].clock = { world.now }
    }

    /** The world's window, days 0-13, for alice (A) and bob (B); no Jira connection is named (it would name a Jira source). */
    private fun CodeWorld.query(extra: String = "") = "from=2026-03-02&to=2026-03-15&accountId=$accA&accountId=$accB$extra"

    private fun ComparePeopleReport.row(key: String): CompareRow = rows.single { it.key == key }

    private suspend fun HttpClient.compare(query: String): ComparePeopleReport = comparePeopleReport(query)

    private suspend fun HttpClient.code(query: String): CodeActivityReport {
        val response = get("/api/v1/reports/code-activity?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    private fun assertCell(label: String, value: Double?, n: Int, hidden: Boolean, got: CompareCell) {
        assertEquals(value, got.value, "$label value")
        assertEquals(n, got.n, "$label n")
        assertEquals(hidden, got.hidden, "$label hidden")
    }

    @Test
    fun `the CODE rows of a linked pair are the hand-computed Report 21 member cells, whatever the team, with no cue on a volume`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("cmp-code", FlowUserRole.USER)
            inWorld {
                pin(this)
                // A third merged merge request of alice's whose size is unknown (no GraphQL totals): it counts for the cycle, not for the
                // size, so a size p50 whose `n` were the merged count would be caught.
                val unsized = NormCodeMergeRequest(
                    30, 1, 30, "merged", false, kA, null, at(1), at(1), at(5), null, "feature", "main", "unsized", null, null, null,
                    emptyList(), emptyList(), null, null, null, false, DetailsState.READ, null, emptyList(),
                )
                GitLabFixtures.codeStore().replaceMergeRequests(connId, listOf(unsized), now)
                val report = client.compare(query())
                val code = report.rows.filter { it.group == CompareGroup.CODE }
                assertEquals(
                    listOf(
                        "commits", "linesChanged", "mrsMerged", "mrCycleWorkingDaysP50", "mrSizeLinesP50", "reviewsGiven", "approvalsGiven",
                        "comments",
                    ),
                    code.map { it.key }, "the group, in table order, after the last Jira group",
                )
                assertEquals(CompareGroup.CODE, report.rows.last().group)

                // Alice (the only author, in team `teamId`): commits a1 (3 files, +10 -2) and a2 (counts unknown: adds nothing, still
                // listed); merge requests 1 (3 working days), 2 (1) and the unsized 30 (4), sizes 25 and 2 lines; one comment on her own.
                // Bob (no membership at all): commit b1 (+4 -4); on merge request 1 a REVIEWED, a COMMENT and two APPROVALs (one approval),
                // on 2 a comment AFTER the merge (still a review-kind act of his): 2 merge requests reviewed, 1 approved, 2 comments.
                assertCell("commits A", 2.0, 2, false, report.row("commits").a)
                assertCell("commits B", 1.0, 1, false, report.row("commits").b)
                assertCell("linesChanged A", 12.0, 2, false, report.row("linesChanged").a)
                assertCell("linesChanged B", 8.0, 1, false, report.row("linesChanged").b)
                assertCell("mrsMerged A", 3.0, 3, false, report.row("mrsMerged").a)
                assertCell("mrsMerged B", 0.0, 0, false, report.row("mrsMerged").b)
                assertCell("cycle A", 3.0, 3, false, report.row("mrCycleWorkingDaysP50").a)
                assertCell("cycle B", null, 0, true, report.row("mrCycleWorkingDaysP50").b)
                assertCell("size A", 13.5, 2, false, report.row("mrSizeLinesP50").a)
                assertCell("size B", null, 0, true, report.row("mrSizeLinesP50").b)
                assertCell("reviews A", 0.0, 0, false, report.row("reviewsGiven").a)
                assertCell("reviews B", 2.0, 2, false, report.row("reviewsGiven").b)
                assertCell("approvals A", 0.0, 0, false, report.row("approvalsGiven").a)
                assertCell("approvals B", 1.0, 1, false, report.row("approvalsGiven").b)
                assertCell("comments A", 1.0, 1, false, report.row("comments").a)
                assertCell("comments B", 2.0, 2, false, report.row("comments").b)

                // The volumes are NEUTRAL: B minus A, never a better side. The two p50 rows are LOWER but bob has none: no verdict.
                val volumes = listOf("commits", "linesChanged", "mrsMerged", "reviewsGiven", "approvalsGiven", "comments")
                assertEquals(listOf(-1.0, -4.0, -3.0, 2.0, 1.0, 1.0), volumes.map { report.row(it).difference })
                assertTrue(code.all { it.better == null && it.margin == null }, "no side is better: ${code.map { it.better }}")
                val directions = code.associate { it.key to it.direction }
                assertEquals(CompareDirection.LOWER, directions["mrCycleWorkingDaysP50"])
                assertEquals(CompareDirection.LOWER, directions["mrSizeLinesP50"])
                assertTrue(volumes.all { directions[it] == CompareDirection.NEUTRAL })
                assertEquals(CompareUnit.LINES, report.row("mrSizeLinesP50").unit)
                assertEquals(CompareUnit.DAYS, report.row("mrCycleWorkingDaysP50").unit)
                assertEquals(1.0, report.row("mrSizeLinesP50").tolerance)
                assertEquals(
                    listOf(
                        "COMMITS", "COMMITS", "MRS_MERGED", "MR_CYCLE_WORKING_DAYS", "MR_SIZE_LINES", "REVIEWS_GIVEN", "APPROVALS_GIVEN",
                        "COMMENTS",
                    ),
                    code.map { it.drill?.name },
                )

                // ONE TRUTH with Report 21: alice is the team's one member; bob (linked, no membership) is in its UNASSIGNED bucket.
                val team = client.code("teamId=$teamId&from=2026-03-02&to=2026-03-15&connectionId=$connId").groups.single()
                val teamless = client.code("teamId=0&from=2026-03-02&to=2026-03-15&connectionId=$connId")
                val unassigned = teamless.groups.single { it.accountId == accB }
                for ((side, group) in listOf("A" to team, "B" to unassigned)) {
                    fun cell(key: String) = report.row(key).let { if (side == "A") it.a else it.b }
                    assertEquals(group.commits.toDouble(), cell("commits").value, "$side commits")
                    assertEquals((group.linesAdded + group.linesDeleted).toDouble(), cell("linesChanged").value, "$side lines")
                    assertEquals(group.mrsMerged.toDouble(), cell("mrsMerged").value, "$side mrsMerged")
                    assertEquals(group.mrCycleWorkingDaysP50, cell("mrCycleWorkingDaysP50").value, "$side cycle p50")
                    assertEquals(group.mrSizeLinesP50, cell("mrSizeLinesP50").value, "$side size p50")
                    assertEquals(group.reviewsGiven.toDouble(), cell("reviewsGiven").value, "$side reviews")
                    assertEquals(group.approvalsGiven.toDouble(), cell("approvalsGiven").value, "$side approvals")
                    assertEquals(group.comments.toDouble(), cell("comments").value, "$side comments")
                }

                // A minimum sample above a cell's n withholds it, and only it: alice's size p50 (n 2) is hidden at 3, her cycle p50 is not.
                val strict = withMetricsSettings(settings, { it.copy(minSampleSize = 3) }) { client.compare(query()) }
                assertCell("size A at min 3", null, 2, true, strict.row("mrSizeLinesP50").a)
                assertCell("cycle A at min 3", 3.0, 3, false, strict.row("mrCycleWorkingDaysP50").a)
                assertCell("mrsMerged A at min 3", 3.0, 3, false, strict.row("mrsMerged").a)
            }
        }

    @Test
    fun `the Jira slices do not apply to the CODE rows, a person with no link has zeros, and a GitLab connectionId is not a Jira one`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("cmp-code-slices", FlowUserRole.USER)
            inWorld {
                pin(this)
                val plain = client.compare(query())
                // Every Jira slice at once: the Jira rows go to zero, the code rows do not move.
                val sliced = client.compare(query("&activityType=Bug&workCategory=Ops&domain=NOPE&domainView=EPIC"))
                assertEquals(0, sliced.row("deliveredItems").a.n)
                val codeKeys = plain.rows.filter { it.group == CompareGroup.CODE }.map { it.key }
                assertEquals(codeKeys.map { plain.row(it).a to plain.row(it).b }, codeKeys.map { sliced.row(it).a to sliced.row(it).b })

                // A person whose identity nobody linked has no row of code anywhere: zeros (a count is never hidden), the p50s withheld.
                val ghost = "ghost-${System.nanoTime()}"
                val withGhost = client.compare("from=2026-03-02&to=2026-03-15&accountId=$accA&accountId=$ghost")
                for (key in listOf("commits", "linesChanged", "mrsMerged", "reviewsGiven", "approvalsGiven", "comments")) {
                    assertCell("ghost $key", 0.0, 0, false, withGhost.row(key).b)
                }
                assertCell("ghost cycle", null, 0, true, withGhost.row("mrCycleWorkingDaysP50").b)
                assertCell("ghost size", null, 0, true, withGhost.row("mrSizeLinesP50").b)
                // The undecided identity (kU) and the BOT are nobody's: bob's and alice's figures above already exclude them.

                // `connectionId` names a JIRA source in this report: the world's GitLab one is the same 400 as an unknown one.
                val named = client.get("/api/v1/reports/compare-people?${query("&connectionId=$connId")}")
                assertEquals(HttpStatusCode.BadRequest, named.status)
            }
        }

    @Test
    fun `a pair whose code rows are over the row budget is a 400 on Compare people, its drill and Person trend`() = testApplication {
        configureApp("reports.codeMaxRows" to "3")
        startApplication()
        val client = seededClient("cmp-code-budget", FlowUserRole.USER)
        inWorld {
            pin(this)
            val detail = "The selection reads more than 3 code rows (commits, merge requests and review events) - " +
                "narrow the period or pick fewer people"
            val urls = listOf(
                "/api/v1/reports/compare-people?${query()}",
                "/api/v1/reports/compare-people/items?${query("&segmentAccountId=$accA&view=COMMITS")}",
                "/api/v1/reports/person-trend?from=2026-03-02&to=2026-03-16&accountId=$accA",
            )
            for (url in urls) {
                val response = client.get(url)
                assertEquals(HttpStatusCode.BadRequest, response.status, url)
                assertEquals(detail, response.body<ProblemDetail>().detail, url)
            }
            // The refusal is not cached: the same request is refused again (a 400 stores no peer cell, no value).
            assertEquals(HttpStatusCode.BadRequest, client.get(urls.last()).status)
        }
    }

    @Test
    fun `every CODE cell equals the count of its drill, in-process, and the HTTP drill names commits and merge requests with no team`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("cmp-code-drill", FlowUserRole.USER)
            inWorld {
                pin(this)
                val service = application.attributes[ReportServiceKey]
                val calendar = settings.readCalendar()
                val params = parametersOf("from" to listOf("2026-03-02"), "to" to listOf("2026-03-15"), "accountId" to listOf(accA, accB))
                val filter = params.parseComparePeople(calendar, now, null)
                val report = service.comparePeople(filter, now, CapacityGrant.None)
                var compared = 0
                val ids = mutableMapOf<String, List<String>>()
                for (row in report.rows.filter { it.group == CompareGroup.CODE }) {
                    for ((person, cell) in listOf(accA to row.a, accB to row.b)) {
                        val drilled: ReportDrillItems = service.comparePeopleItems(filter, row.drill!!, null, person, now)
                        assertEquals(cell.n, drilled.bar.count, "${row.key} $person: bar.count is the cell's n")
                        assertEquals(drilled.bar.count, drilled.total, "${row.key} $person: total")
                        assertEquals(drilled.total, drilled.items.size, "${row.key} $person: nothing is capped here")
                        assertTrue(!drilled.truncated, "${row.key} $person")
                        val theirs = drilled.items.all { it.teamId == null && it.accountId == person }
                        assertTrue(theirs, "${row.key} $person: the person's, in no team")
                        ids["${row.key}/$person"] = drilled.items.map { it.issueKey }
                        compared++
                    }
                }
                assertEquals(16, compared, "8 rows x 2 people")
                // The lists by hand (the project is unknown to the world, so a key is `#<project id>`): alice's commits a1 and a2 (lines
                // 12 / unknown) and merge requests 1 and 2; bob's two reviewed merge requests, his one approval and his two comments.
                assertEquals(setOf("#1@a1", "#1@a2"), ids.getValue("commits/$accA").toSet())
                assertEquals(setOf("#1@a1", "#1@a2"), ids.getValue("linesChanged/$accA").toSet(), "unknown lines are listed with no figure")
                assertEquals(setOf("#1!1", "#1!2"), ids.getValue("mrsMerged/$accA").toSet())
                assertEquals(ids.getValue("mrsMerged/$accA").toSet(), ids.getValue("mrCycleWorkingDaysP50/$accA").toSet())
                assertEquals(setOf("#1!1", "#1!2"), ids.getValue("reviewsGiven/$accB").toSet())
                assertEquals(listOf("#1!1"), ids.getValue("approvalsGiven/$accB"))
                assertEquals(2, ids.getValue("comments/$accB").size)

                // The wire: a commit item is a COMMIT with its counted lines as the figure (null where unknown); a merge request item a
                // MERGE_REQUEST whose figure is the cycle in working days; and the 400s of a drill.
                val commits = client.get("/api/v1/reports/compare-people/items?${query("&segmentAccountId=$accA&view=COMMITS")}")
                assertEquals(HttpStatusCode.OK, commits.status)
                val body = commits.body<ReportDrillItems>()
                assertEquals(listOf("COMMIT", "COMMIT"), body.items.map { it.itemKind.name })
                assertEquals(setOf(12.0, null), body.items.map { it.figure }.toSet())
                val merged = client.get("/api/v1/reports/compare-people/items?${query("&segmentAccountId=$accA&view=MRS_MERGED")}")
                assertEquals(setOf(3.0, 1.0), merged.body<ReportDrillItems>().items.map { it.figure }.toSet())
                val own = "&segmentAccountId=$accA"
                val refused = listOf("${own}&view=COMMITS&bucketIndex=0", "&view=COMMITS", "${own}&view=NOPE")
                for (bad in refused) {
                    assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/compare-people/items?${query(bad)}").status, bad)
                }
            }
        }

    @Test
    fun `Person trend's code points are Compare people's cells over each window, with the hand-computed anchors`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("cmp-code-trend", FlowUserRole.USER)
        inWorld {
            pin(this)
            // Two points: the windows of 90 days ending 03-02 and 03-16. Alice merged MR 5 on Feb 5 (inside both), 1 and 2 on days 4 and 8.
            val aliceQuery = "from=2026-03-02&to=2026-03-16&accountId=$accA"
            val trend = client.personTrend(aliceQuery)
            assertEquals(listOf("2026-03-02", "2026-03-16"), trend.windows.map { it.end })
            fun values(report: PersonTrendReport, key: String) = report.rows.single { it.key == key }.points.map { it.value }
            assertEquals(listOf(1.0, 3.0), values(trend, "mrsMerged"))
            assertEquals(listOf(0.0, 0.0), values(trend, "reviewsGiven"))
            assertEquals(CompareDirection.NEUTRAL, trend.rows.single { it.key == "mrsMerged" }.direction)
            assertEquals(CompareDirection.LOWER, trend.rows.single { it.key == "mrCycleWorkingDaysP50" }.direction)
            assertEquals(CompareGroup.CODE, trend.rows.last().group)
            // Bob: his reviews are on days 2-10, after the first window's end.
            val bob = client.personTrend("from=2026-03-02&to=2026-03-16&accountId=$accB")
            assertEquals(listOf(0.0, 2.0), values(bob, "reviewsGiven"))

            // THE one-truth gate over every row and window, the code rows included.
            val aliceTruth = client.assertPersonTrendIsComparePeople(trend, accA, accB, "")
            val bobTruth = client.assertPersonTrendIsComparePeople(bob, accB, accA, "")
            assertEquals(2 * trend.rows.size, aliceTruth.cells)
            assertEquals(2 * bob.rows.size, bobTruth.cells)
            assertNotNull(trend.rows.single { it.key == "mrCycleWorkingDaysP50" }.points.last().value)
            assertNull(bob.rows.single { it.key == "mrCycleWorkingDaysP50" }.points.last().value, "bob merged nothing")
        }
    }
}
