package ch.nokillswit

import ch.nokillswit.flowusers.FlowUserRole
import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.ReportServiceKey
import ch.nokillswit.reports.TaskCodeReport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass

/**
 * Report 22 on the two stub fixtures (`.claude/docs/reports.md` "Report 22"): the DERIVED Jira fixture (its `fact_task_delivery` rows and
 * `norm.work_items`) and the processed GitLab fixture (`norm.code_merge_requests` and the V46 issue-key candidates of the stub's titles
 * and branches; read through an own clone, the shared one being soft-deleted), both read and never mutated. Every figure is compared
 * with an INDEPENDENT re-derivation in plain JDBC and plain loops (never the report's code; only the working calendar's day arithmetic
 * is shared, as the cycle-time fixture reads `cycle_working_days`).
 * Every ACTIVE Jira source decides whether a key is ambiguous (`connectionId` narrows only the tasks), and the synced fixture is a second
 * active source holding the derived one's very keys: the test first pins that state (every key is ambiguous, nothing links), then
 * deactivates every other Jira source for the comparison (restored in a `finally`). The comparison runs under `minSampleSize` 1, so the
 * lead-time and lag VALUES are compared, not just their counts. Prints `in-test<TAB>task-code-90d<TAB><ms>`.
 *
 * **What overlaps.** The Jira stub's done tasks end at the derive's pinned 2026-03-05 and the GitLab stub's merge requests are dated
 * after it, so the stub keys hit done tasks (a title names `FLO-<n>` of the Jira stub's dense numbering) but nearly every linked merge
 * is MERGED AFTER the task was done. The figures are pinned on the whole history and on narrower windows; the hand-written world
 * (`ReportTaskCodeTest`) pins the other cases.
 */
class ReportTaskCodeFixtureTest {
    private class OracleTask(val issueId: Long, val key: String, val startedAt: Long?, val doneAt: Long)

    private class OracleMr(val key: String, val connectionId: Int, val mrId: Long, val mergedAt: Long?)

    private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",", "(", ")")

    private suspend fun calendar(): WorkingCalendar = DerivedStubFixture.metricsSettings().readWithCalendar().calendar

    private fun gitlabIds(): List<Int> = jdbcRows(
        "SELECT id FROM source_connections WHERE kind = ? AND NOT marked_as_deleted ORDER BY id", DataSourceKind.GITLAB.name,
    ).map { (it[0] as Number).toInt() }

    private fun tasks(jira: UInt, from: Long, to: Long, slice: String, vararg params: Any?): List<OracleTask> = jdbcRows(
        "SELECT issue_id, issue_key, started_at, done_at FROM metrics.fact_task_delivery WHERE connection_id = ? AND NOT is_subtask " +
            "AND done_at >= ? AND done_at < ? AND $slice",
        jira.toInt(), from, to, *params,
    ).map { OracleTask((it[0] as Number).toLong(), it[1] as String, (it[2] as Number?)?.toLong(), (it[3] as Number).toLong()) }

    /** The live merge requests of the active GitLab sources that name one of [keys]: one row per (merge request, key). */
    private fun links(keys: Collection<String>): List<OracleMr> {
        val gitlab = gitlabIds()
        if (keys.isEmpty() || gitlab.isEmpty()) return emptyList()
        return jdbcRows(
            "SELECT k.issue_key, k.connection_id, k.mr_id, m.state, m.merged_at FROM norm.code_mr_issue_keys k " +
                "JOIN norm.code_merge_requests m ON m.connection_id = k.connection_id AND m.mr_id = k.mr_id " +
                "WHERE m.deleted_at IS NULL AND k.connection_id IN ${placeholders(gitlab.size)} " +
                "AND k.issue_key IN ${placeholders(keys.size)}",
            *gitlab.toTypedArray(), *keys.toTypedArray(),
        ).map {
            val merged = it[3] == "merged"
            val mergedAt = if (merged) (it[4] as Number?)?.toLong() else null
            OracleMr(it[0] as String, (it[1] as Number).toInt(), (it[2] as Number).toLong(), mergedAt)
        }
    }

    /** The ids of the active Jira sources: the ones that decide whether a key names one issue. */
    private fun activeJiraIds(): List<Int> = jdbcRows(
        "SELECT id FROM source_connections WHERE kind = ? AND NOT marked_as_deleted ORDER BY id", DataSourceKind.JIRA_CLOUD.name,
    ).map { (it[0] as Number).toInt() }

    /** How many live issues of the active Jira sources carry each of [keys] as their current key; the oracle ignores `issuekey` moves. */
    private fun issuesPerKey(keys: Collection<String>): Map<String, Int> {
        val jira = activeJiraIds()
        if (keys.isEmpty() || jira.isEmpty()) return emptyMap()
        return jdbcRows(
            "SELECT issue_key, count(*) FROM norm.work_items WHERE connection_id IN ${placeholders(jira.size)} AND deleted_at IS NULL " +
                "AND moved_out_at IS NULL AND issue_key IN ${placeholders(keys.size)} GROUP BY issue_key",
            *jira.toTypedArray(), *keys.toTypedArray(),
        ).associate { it[0] as String to (it[1] as Number).toInt() }
    }

    /**
     * No live issue of an active Jira source has an `issuekey` change naming a key of the stub's projects (other classes' hand-written
     * sources may hold moves of their own projects, which meet no stub key): the oracle needs no old-key resolution.
     */
    private fun assertNoLiveMovesOfStubKeys() {
        val jira = activeJiraIds()
        val stub = SyncedStubFixture.IN_SCOPE_PROJECT_KEYS.joinToString("|")
        val named = listOf("from_value", "from_text", "to_value", "to_text").joinToString(" OR ") { "f.$it ~ '^($stub)-'" }
        val moved = jdbcRows(
            "SELECT count(*) FROM norm.work_item_field_changes f JOIN norm.work_items w ON w.connection_id = f.connection_id " +
                "AND w.issue_id = f.issue_id WHERE f.connection_id IN ${placeholders(jira.size)} AND f.field_id = 'issuekey' " +
                "AND w.deleted_at IS NULL AND w.moved_out_at IS NULL AND ($named)",
            *jira.toTypedArray(),
        ).single()[0] as Number
        assertEquals(0L, moved.toLong(), "the stub's one move leaves its scope: the oracle needs no old-key resolution")
    }

    private fun setDeleted(ids: List<Int>, deleted: Boolean) = PostgresTestSupport.connect().use { conn ->
        conn.prepareStatement("UPDATE source_connections SET marked_as_deleted = ? WHERE id = ?").use { st ->
            ids.forEach {
                st.setBoolean(1, deleted)
                st.setInt(2, it)
                st.executeUpdate()
            }
        }
    }

    private class Expected(
        val done: Int,
        val withMr: Int,
        val onlyUnmerged: Int,
        val neverStarted: Int,
        val ambiguous: Int,
        val late: Int,
        val leads: List<Double>,
        val lags: List<Double>,
    )

    private fun expected(population: List<OracleTask>, calendar: WorkingCalendar): Expected {
        val keys = population.map { it.key }.distinct()
        val perKey = issuesPerKey(keys)
        val byKey = links(keys.filter { perKey[it] == 1 }).groupBy { it.key }
        var withMr = 0
        var onlyUnmerged = 0
        var late = 0
        val leads = mutableListOf<Double>()
        val lags = mutableListOf<Double>()
        for (task in population) {
            val mrs = byKey[task.key].orEmpty().distinctBy { it.connectionId to it.mrId }
            val merges = mrs.mapNotNull { it.mergedAt }
            if (merges.isNotEmpty()) withMr++ else if (mrs.isNotEmpty()) onlyUnmerged++
            val last = merges.maxOrNull() ?: continue
            if (task.startedAt != null) leads += calendar.workingDaysBetween(task.startedAt, last)
            if (last > task.doneAt) {
                late++
                lags += calendar.workingDaysBetween(task.doneAt, last)
            }
        }
        val ambiguous = population.count { (perKey[it.key] ?: 0) > 1 }
        return Expected(population.size, withMr, onlyUnmerged, population.count { it.startedAt == null }, ambiguous, late, leads, lags)
    }

    private suspend fun HttpClient.taskCode(query: String): TaskCodeReport {
        val response = get("/api/v1/reports/task-code?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    private fun TaskCodeReport.assertMatches(label: String, want: Expected) {
        val min = meta.minSampleSize
        val counts = listOf(tasks.done, tasks.withMergeRequest, tasks.withoutMergeRequest)
        assertEquals(listOf(want.done, want.withMr, want.done - want.withMr), counts, "$label tasks")
        assertEquals(want.onlyUnmerged, quality.onlyUnmergedMrs, "$label only unmerged")
        assertEquals(want.neverStarted, quality.neverStarted, "$label never started")
        assertEquals(want.ambiguous, quality.tasksWithAmbiguousKey, "$label tasks with an ambiguous key")
        assertEquals(want.late, mergedAfterDone.count, "$label merged after done")
        assertDistribution("$label code lead time", want.leads, codeLeadTimeWorkingDays, min)
        assertDistribution("$label merge lag", want.lags, mergedAfterDone.lagWorkingDays, min)
        val figures = listOf(want.done, want.withMr, want.onlyUnmerged, want.ambiguous, want.late, want.leads.size)
        println("fixture\ttask-code\t$label\t${figures.joinToString("/")}")
    }

    @Test
    fun `every figure equals an independent re-derivation over the derived Jira fixture and the processed GitLab stub`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("task-code-fixture", FlowUserRole.USER)
        application.attributes[ReportServiceKey].clock = { REFERENCE_TIME }
        val jira = DerivedStubFixture.connectionId()
        val team = DerivedStubFixture.teamId()
        val calendar = calendar()
        val zone = reportZone()
        assertNoLiveMovesOfStubKeys()
        assertTrue(gitlabIds().isNotEmpty(), "the clone of the processed GitLab fixture is an active source")
        val whole = windowBounds(zone, "2024-01-01", "2026-12-31")

        withMinSampleSize(1) {
            // The synced fixture is a second ACTIVE Jira source with the derived one's keys: every key names two issues, so nothing links
            // although `connectionId` names the derived source alone.
            val doubled = client.taskCode("connectionId=$jira&domain=${DerivedStubFixture.DOMAIN}&from=2024-01-01&to=2026-12-31")
            val everyTask = tasks(jira, whole.first, whole.second, "domain_key = ?", DerivedStubFixture.DOMAIN)
            doubled.assertMatches("every Jira source decides", expected(everyTask, calendar))
            assertTrue(activeJiraIds().size > 1 && doubled.tasks.withMergeRequest == 0, "two active sources: nothing links")
            assertEquals(everyTask.size, doubled.quality.tasksWithAmbiguousKey, "every done task's key names two issues")

            val others = activeJiraIds().filter { it != jira.toInt() }
            setDeleted(others, deleted = true)
            try {
                compareWindows(client, jira, team, calendar, zone)
            } finally {
                setDeleted(others, deleted = false)
            }
        }
    }

    private suspend fun compareWindows(client: HttpClient, jira: UInt, team: UInt, calendar: WorkingCalendar, zone: ZoneId) {
        var linkedSomewhere = false
        for ((from, to) in listOf("2024-01-01" to "2026-12-31", "2025-06-01" to "2025-12-31", "2026-01-01" to "2026-03-05")) {
            val (fromMs, toMs) = windowBounds(zone, from, to)
            val query = "connectionId=$jira&from=$from&to=$to"
            val domain = client.taskCode("$query&domain=${DerivedStubFixture.DOMAIN}")
            val inDomain = tasks(jira, fromMs, toMs, "domain_key = ?", DerivedStubFixture.DOMAIN)
            domain.assertMatches("$from..$to domain", expected(inDomain, calendar))
            val teamReport = client.taskCode("$query&teamId=$team")
            val inTeam = tasks(jira, fromMs, toMs, "credit_team_id = ?", team.toInt())
            teamReport.assertMatches("$from..$to team", expected(inTeam, calendar))
            assertEquals(0, domain.quality.tasksWithAmbiguousKey, "one active Jira source: nothing is ambiguous")
            linkedSomewhere = linkedSomewhere || domain.tasks.withMergeRequest > 0
        }
        assertTrue(linkedSomewhere, "the stub's keys must hit the done tasks, or this test pins nothing")
        // What overlaps (measured 2026-11-01): of the 246 done tasks of the whole history 4 have a merged merge request, 3 only unmerged
        // ones and 3 of the 4 merged after done. The figures above are the oracle's; these keep the overlap from silently vanishing.
        val whole = client.taskCode("connectionId=$jira&domain=${DerivedStubFixture.DOMAIN}&from=2024-01-01&to=2026-12-31")
        assertTrue(whole.tasks.withMergeRequest > 0 && whole.quality.onlyUnmergedMrs > 0, "merged and unmerged-only links exist")
        assertTrue(whole.mergedAfterDone.count in 1..whole.tasks.withMergeRequest, "some merge after done")
        assertTrue(whole.codeLeadTimeWorkingDays.n > 0, "a lead time is measured")

        // The timing target (build-times.md "In-test targets"): the 90-day request, the second one.
        val url = "connectionId=$jira&domain=${DerivedStubFixture.DOMAIN}&from=2025-12-06&to=2026-03-05"
        client.taskCode(url)
        val started = System.nanoTime()
        val report = client.taskCode(url)
        println("in-test\ttask-code-90d\t${(System.nanoTime() - started) / 1_000_000L}")
        assertNotNull(report.sources.firstOrNull(), "the GitLab fixture is printed as a source")
    }

    companion object {
        private val REFERENCE_TIME = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()

        private val dataSources = GitLabFixtures.dataSources()
        private var ownId: UInt = 0u

        /**
         * BOTH fixtures are warmed here (the `CodeIdentityRoutesTest` precedent): the shared GitLab fixture is soft-deleted once synced,
         * so the report reads a CLONE of its processed rows in an own ACTIVE source.
         */
        @JvmStatic
        @BeforeClass
        fun warmFixtures() {
            warmStubFixtures()
            warmGitLabFixture()
            runBlocking {
                ownId = GitLabFixtures.createConnection(dataSources, namePrefix = "task-code-fixture")
                SyncedGitLabFixture.cloneProcessedData(SyncedGitLabFixture.connectionId(), ownId)
            }
        }

        @JvmStatic
        @AfterClass
        fun removeOwnConnection() = runBlocking<Unit> {
            GitLabFixtures.purgeAll(ownId)
            dataSources.delete(ownId)
        }
    }
}
