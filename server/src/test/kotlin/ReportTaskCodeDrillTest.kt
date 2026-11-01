package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.reports.DrillItemKind
import ch.nokillswit.reports.Distribution
import ch.nokillswit.reports.ReportDrillItems
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.TaskCodeReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Report 22's drill over HTTP (`GET /api/v1/reports/task-code/items`, `.claude/docs/reports.md` "Report 22") on the hand-written two-layer
 * [TaskCodeWorld] (its KDoc lists the tasks and merge requests). The tasks and merge-request keys of every view are written out by hand;
 * [reconcile] is the gate: for a request, EVERY figure of the report (with, without, merged after done, every bucket of both histograms)
 * equals its drill's `bar.count` / `total`, the listed items are the bar's and the bucket range is the histogram's own.
 */
class ReportTaskCodeDrillTest {
    private suspend fun <T> inWorld(minSample: Int = 3, body: suspend TaskCodeWorld.() -> T): T =
        withTaskCodeWorld(reportZone()) { withMinSampleSize(minSample) { body() } }

    private fun ApplicationTestBuilder.pin(world: TaskCodeWorld) {
        application.attributes[ReportServiceKey].clock = { world.now }
    }

    private fun TaskCodeWorld.team(team: UInt = teamA) = "teamId=$team&$period&connectionId=$jiraA"

    private suspend fun HttpClient.report(query: String): TaskCodeReport {
        val response = get("/api/v1/reports/task-code?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    private suspend fun HttpClient.drill(query: String, identity: String): ReportDrillItems {
        val response = get("/api/v1/reports/task-code/items?$query&$identity")
        assertEquals(HttpStatusCode.OK, response.status, "$query $identity")
        return response.body()
    }

    private suspend fun HttpClient.refused(query: String, identity: String): String {
        val response = get("/api/v1/reports/task-code/items?$query&$identity")
        assertEquals(HttpStatusCode.BadRequest, response.status, "$query $identity")
        return assertNotNull(response.body<ProblemDetail>().detail)
    }

    private fun ReportDrillItems.keys() = items.map { it.issueKey }

    private fun ReportDrillItems.linked() = items.associate { it.issueKey to it.linkedKeys }

    /**
     * The reconciliation gate for one request: the five views against the report's figures. A shown histogram's every bucket is drilled
     * (count, range, items inside the range, the buckets together hold exactly the distribution's `n`), a hidden one has no bar to list.
     */
    private suspend fun reconcile(client: HttpClient, query: String) {
        val report = client.report(query)
        val with = client.drill(query, "view=TASKS_WITH_MR")
        val without = client.drill(query, "view=TASKS_WITHOUT_MR")
        assertEquals(report.tasks.withMergeRequest, with.bar.count, "$query with")
        assertEquals(with.bar.count, with.total, "$query with total")
        assertEquals(report.tasks.withoutMergeRequest, without.bar.count, "$query without")
        assertEquals(without.bar.count, without.total, "$query without total")
        assertTrue(with.items.size == with.total && without.items.size == without.total && !with.truncated && !without.truncated)
        assertEquals(report.tasks.done, with.total + without.total, "$query with + without = done")
        assertTrue(with.keys().toSet().intersect(without.keys().toSet()).isEmpty(), "a task is in one of the two lists")
        assertTrue(with.items.all { it.itemKind == DrillItemKind.TASK && it.anchorAt != null }, "task items anchored by done_at")
        val late = client.drill(query, "view=MERGED_AFTER_DONE")
        assertEquals(report.mergedAfterDone.count, late.bar.count, "$query merged after done")
        assertEquals(late.bar.count, late.total)
        assertTrue(late.keys().toSet().all { it in with.keys() }, "merged after done is a subset of the tasks with a merge request")
        assertHistogram(client, query, "CODE_LEAD_TIME", report.codeLeadTimeWorkingDays, with.keys().toSet())
        assertHistogram(client, query, "MERGED_AFTER_DONE_LAG", report.mergedAfterDone.lagWorkingDays, late.keys().toSet())
    }

    private suspend fun assertHistogram(client: HttpClient, query: String, view: String, distribution: Distribution, within: Set<String>) {
        if (distribution.hidden) {
            assertTrue(client.refused(query, "view=$view&bucketIndex=0").contains("hidden"), "$view hidden: no bar to list")
            return
        }
        val seen = mutableListOf<String>()
        distribution.histogram.forEachIndexed { index, bucket ->
            val drill = client.drill(query, "view=$view&bucketIndex=$index")
            assertEquals(bucket.count.toInt(), drill.bar.count, "$query $view bucket $index")
            assertEquals(drill.bar.count, drill.total)
            assertEquals(bucket.from, drill.bar.from, "$view bucket $index from")
            assertEquals(bucket.to, drill.bar.to, "$view bucket $index to")
            val upper = bucket.to ?: Double.POSITIVE_INFINITY
            assertTrue(drill.items.all { item -> item.figure.let { it != null && it >= bucket.from && it < upper } })
            seen += drill.keys()
        }
        assertEquals(distribution.n.toInt(), seen.size, "$view: the buckets hold exactly n tasks")
        assertEquals(seen.toSet(), seen.toSet().intersect(within), "$view: every measured task is one of the figure's tasks")
    }

    @Test
    fun `each view lists the hand-computed tasks with their merge requests, and every figure equals its drill`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("task-code-drill", FlowUserRole.USER)
        inWorld {
            pin(this)
            val query = team()
            // With a merged merge request: figure = the code lead time (TCA-4 never started: none). The last merge decides it, and the list
            // names every MERGED merge request (the same mr id on two sources is two): TCA-1 has glA 1 and 2 and glB 1; TCB-7 the one
            // naming its OLD key (TCA-50) and the one naming the new. Order: figure descending, newest done first, key.
            val with = client.drill(query, "view=TASKS_WITH_MR")
            assertEquals(listOf("TCA-2", "TCB-7", "TCA-1", "TCA-4"), with.keys())
            assertEquals(listOf(5.0, 3.5, 3.5, null), with.items.map { it.figure })
            assertEquals(
                mapOf(
                    "TCA-2" to listOf("grp/app!3"),
                    "TCB-7" to listOf("grp/app!7", "grp/app!8"),
                    "TCA-1" to listOf("grp/app!1", "grp/app!2", "grp/web!1"),
                    "TCA-4" to listOf("grp/app!6"),
                ),
                with.linked(),
            )
            assertEquals(listOf(teamA), with.items.map { it.teamId }.distinct())
            assertEquals(with.items.map { it.anchorAt }, listOf(at(3), at(10), at(4), at(9)))
            // Without: the unmerged merge requests that kept TCA-3 out; TCA-6's only merge request is deleted, so it has none.
            val without = client.drill(query, "view=TASKS_WITHOUT_MR")
            assertEquals(listOf("TCA-6", "TCA-3"), without.keys())
            assertEquals(mapOf("TCA-6" to emptyList<String>(), "TCA-3" to listOf("grp/app!4", "grp/app!5")), without.linked())
            assertTrue(without.items.all { it.figure == null })
            // Merged after done: the lag from done to the last merge.
            val late = client.drill(query, "view=MERGED_AFTER_DONE")
            assertEquals(listOf("TCA-2", "TCB-7"), late.keys())
            assertEquals(listOf(3.0, 0.5), late.items.map { it.figure })
            assertEquals(mapOf("TCA-2" to listOf("grp/app!3"), "TCB-7" to listOf("grp/app!7", "grp/app!8")), late.linked())
            // The lead time histogram (n = 3 shown): its bars together are the three tasks with a lead time, never TCA-4.
            val seen = client.report(query).codeLeadTimeWorkingDays.histogram.indices
                .flatMap { client.drill(query, "view=CODE_LEAD_TIME&bucketIndex=$it").keys() }
            assertEquals(listOf("TCA-1", "TCA-2", "TCB-7"), seen.sorted())
            reconcile(client, query)
        }
    }

    @Test
    fun `the lag histogram lists each bar's tasks when shown, and a team, a domain and no credit team reconcile`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("task-code-drill-lag", FlowUserRole.USER)
        inWorld(minSample = 2) {
            pin(this)
            val query = team()
            val report = client.report(query)
            assertEquals(2L, report.mergedAfterDone.lagWorkingDays.n)
            val bars = report.mergedAfterDone.lagWorkingDays.histogram.indices
                .map { client.drill(query, "view=MERGED_AFTER_DONE_LAG&bucketIndex=$it") }
            // 0.5 and 3.0 working days: whatever the layout, TCB-7 is in an earlier bar than TCA-2 and each is listed once.
            assertEquals(listOf("TCA-2", "TCB-7"), bars.flatMap { it.keys() }.sorted())
            assertTrue(bars.indexOfFirst { "TCB-7" in it.keys() } < bars.indexOfFirst { "TCA-2" in it.keys() })
            reconcile(client, query)
            reconcile(client, "domain=$domain&$period&connectionId=$jiraA")
            reconcile(client, team(teamB))
            reconcile(client, team(0u))
            // The other team's single task and the credit-less one: the drills are theirs alone.
            assertEquals(listOf("TCA-11"), client.drill(team(teamB), "view=TASKS_WITH_MR").keys())
            assertEquals(listOf("TCA-13"), client.drill(team(0u), "view=TASKS_WITH_MR").keys())
        }
    }

    @Test
    fun `an ambiguous key moves its task to the without list with no merge request, and the lists still reconcile`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("task-code-drill-ambiguous", FlowUserRole.USER)
            inWorld {
                pin(this)
                task(107, "TCA-9", 1, 4)
                publish()
                write(glA, mr(19, "TCA-9: shared", 2))
                val query = "teamId=$teamA&$period"
                assertEquals(listOf("grp/app!19"), client.drill(query, "view=TASKS_WITH_MR").linked()["TCA-9"])
                reconcile(client, query)
                val second = withSecondJira()
                task(207, "TCA-9", null, null, connId = second)
                // Ambiguous: it links to none, so it is without, and has no merge request to list (not even an unmerged one).
                val after = client.drill(query, "view=TASKS_WITHOUT_MR")
                assertTrue("TCA-9" in after.keys())
                assertEquals(emptyList<String>(), after.linked()["TCA-9"])
                assertTrue("TCA-9" !in client.drill(query, "view=TASKS_WITH_MR").keys())
                reconcile(client, query)
            }
        }

    @Test
    fun `the 400 matrix - identity, a bucket outside the histogram, a hidden distribution, an accountId and the parent's scope`() =
        testApplication {
            usePostgresTestcontainer()
            val client = seededClient("task-code-drill-400", FlowUserRole.USER)
            inWorld {
                pin(this)
                val query = team()
                assertTrue(client.refused(query, "").contains("view is required"))
                assertTrue(client.refused(query, "view=NOPE").contains("Unknown view"), "an unknown view")
                assertEquals("bucketIndex is required for the CODE_LEAD_TIME view", client.refused(query, "view=CODE_LEAD_TIME"))
                assertEquals(
                    "bucketIndex applies to the histogram views only",
                    client.refused(query, "view=TASKS_WITH_MR&bucketIndex=0"),
                )
                assertTrue(client.refused(query, "view=CODE_LEAD_TIME&bucketIndex=-1").contains("Invalid bucketIndex"))
                assertTrue(client.refused(query, "view=CODE_LEAD_TIME&bucketIndex=x").contains("Invalid bucketIndex"))
                val last = client.report(query).codeLeadTimeWorkingDays.histogram.size
                assertTrue(client.refused(query, "view=CODE_LEAD_TIME&bucketIndex=$last").contains("outside the histogram"))
                // n = 2 lags under a minimum sample of 3: no bar exists.
                assertTrue(client.refused(query, "view=MERGED_AFTER_DONE_LAG&bucketIndex=0").contains("hidden"))
                assertEquals(
                    "accountId is not available: Tasks and code has no user level",
                    client.refused("$query&accountId=someone", "view=TASKS_WITH_MR"),
                )
                assertTrue(client.refused(period, "view=TASKS_WITH_MR").startsWith("teamId or domain is required"))
                client.refused("teamId=987654321&$period&connectionId=$jiraA", "view=TASKS_WITH_MR")
                // A sprint period with no sprint to resolve reads nothing: every list is empty, and a histogram has no bar.
                val empty = client.drill("teamId=$teamA&lastSprints=1&connectionId=$jiraA", "view=TASKS_WITH_MR")
                assertEquals(0, empty.total)
                assertEquals(0, empty.bar.count)
                assertTrue(empty.items.isEmpty() && !empty.truncated)
                assertNull(empty.bar.from)
                val noSprint = "teamId=$teamA&lastSprints=1&connectionId=$jiraA"
                assertTrue(client.refused(noSprint, "view=CODE_LEAD_TIME&bucketIndex=0").contains("hidden"))
            }
        }

    @Test
    fun `an anonymous caller is refused and the code-row budget is a 400 that says to narrow it`() = testApplication {
        configureApp("reports.codeMaxRows" to "3")
        startApplication()
        val client = seededClient("task-code-drill-budget", FlowUserRole.USER)
        inWorld {
            pin(this)
            val url = "/api/v1/reports/task-code/items?${team()}&view=TASKS_WITH_MR"
            assertEquals(HttpStatusCode.Unauthorized, jsonClient().get(url).status)
            val detail = "The selection reads more than 3 code rows (commits, merge requests and review events) - " +
                "narrow the period or pick fewer people"
            assertEquals(detail, client.refused(team(), "view=TASKS_WITH_MR"))
            assertEquals(listOf("TCA-11"), client.drill(team(teamB), "view=TASKS_WITH_MR").keys())
        }
    }
}
