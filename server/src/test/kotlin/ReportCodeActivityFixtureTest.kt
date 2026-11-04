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
import ch.nokillswit.reports.CodeActivityGroup
import ch.nokillswit.reports.CodeActivityReport
import ch.nokillswit.reports.ReportServiceKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass

/**
 * Report 21 on the processed GitLab stub (`.claude/docs/reports.md` "Report 21"): a CLONE of the shared GitLab fixture's `norm.code_*`
 * rows in a disabled connection, the proposals confirmed over HTTP against the Jira stub's people, every linked human a member of one team
 * from the beginning of time, `now` pinned to the stub's reference time. Every count, sum and percentile of the report is compared with an
 * INDEPENDENT SQL re-derivation over `norm.code_*` and `public.code_identity_links` (plain JDBC, never the report's code). The
 * re-derivation identifies an actor by key, which equals the report's rule here: the stub links one key per person. Working days need
 * the calendar, so the cycle is compared in elapsed days and the first review by its count. Prints
 * `in-test<TAB>code-activity-90d<TAB><ms>`.
 */
class ReportCodeActivityFixtureTest {
    private val host = sourceHostOf(GitLabStubServer.baseUrl)
    private val links = CodeIdentityLinkStore(sharedDatabaseForTests())

    private val reviewKinds = "('COMMENT','DIFF_COMMENT','APPROVAL','REVIEWED','CHANGES_REQUESTED')"

    private val known = "additions_counted IS NOT NULL AND deletions_counted IS NOT NULL AND files_counted IS NOT NULL"

    private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",", "(", ")")

    private fun long(value: Any?): Long = (value as Number?)?.toLong() ?: 0L

    private fun dbl(value: Any?): Double? = (value as Number?)?.toDouble()

    private fun close(label: String, want: Double?, got: Double?) {
        if (want == null) {
            assertNull(got, label)
        } else {
            assertNotNull(got, label)
            assertTrue(abs(want - got) < 1e-6, "$label: expected $want but was $got")
        }
    }

    /** What the SQL says about a window: `[from, to)` bounds, the connection and every key of the team's members. */
    private inner class Sql(val connId: UInt, val from: Long, val to: Long, val now: Long) {
        fun rows(sql: String, vararg params: Any?) = jdbcRows(sql, *params)

        fun commits(keys: List<String>): List<Any?> = rows(
            """SELECT count(*),
                 coalesce(sum(files_counted) FILTER (WHERE $known), 0), coalesce(sum(additions_counted) FILTER (WHERE $known), 0),
                 coalesce(sum(deletions_counted) FILTER (WHERE $known), 0),
                 count(*) FILTER (WHERE additions_counted IS NULL OR deletions_counted IS NULL OR files_counted IS NULL)
               FROM norm.code_commits
               WHERE connection_id = ? AND NOT is_merge AND deleted_at IS NULL AND committed_at >= ? AND committed_at < ?
                 AND identity_key IN ${placeholders(keys.size)}""",
            connId.toInt(), from, to, *keys.toTypedArray(),
        ).single()

        fun mergeRequests(keys: List<String>): List<Any?> = rows(
            """SELECT count(*) FILTER (WHERE state = 'merged' AND merged_at >= ? AND merged_at < ?),
                 count(*) FILTER (WHERE created_at >= ? AND created_at < ?), count(*) FILTER (WHERE state = 'opened')
               FROM norm.code_merge_requests WHERE connection_id = ? AND deleted_at IS NULL AND author_key IN ${placeholders(keys.size)}""",
            from, to, from, to, connId.toInt(), *keys.toTypedArray(),
        ).single()

        fun acts(keys: List<String>): List<Any?> = rows(
            """SELECT count(DISTINCT e.mr_id) FILTER (WHERE e.kind IN $reviewKinds AND m.author_key IS DISTINCT FROM e.actor_key),
                 count(DISTINCT e.mr_id) FILTER (WHERE e.kind = 'APPROVAL' AND m.author_key IS DISTINCT FROM e.actor_key),
                 count(DISTINCT e.mr_id) FILTER (WHERE e.kind = 'CHANGES_REQUESTED' AND m.author_key IS DISTINCT FROM e.actor_key),
                 count(*) FILTER (WHERE e.kind IN ('COMMENT','DIFF_COMMENT'))
               FROM norm.code_mr_events e JOIN norm.code_merge_requests m ON m.connection_id = e.connection_id AND m.mr_id = e.mr_id
               WHERE e.connection_id = ? AND m.deleted_at IS NULL AND e.event_at >= ? AND e.event_at < ?
                 AND e.actor_key IN ${placeholders(keys.size)}""",
            connId.toInt(), from, to, *keys.toTypedArray(),
        ).single()

        fun requests(keys: List<String>): Long = long(
            rows(
                """SELECT count(DISTINCT e.mr_id)
                   FROM norm.code_mr_events e JOIN norm.code_merge_requests m ON m.connection_id = e.connection_id AND m.mr_id = e.mr_id
                   WHERE e.connection_id = ? AND m.deleted_at IS NULL AND e.kind = 'REVIEW_REQUESTED' AND e.event_at >= ? AND e.event_at < ?
                     AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(e.target_keys) t WHERE t IN ${placeholders(keys.size)})""",
                connId.toInt(), from, to, *keys.toTypedArray(),
            ).single()[0],
        )
    }

    private fun CodeActivityGroup.assertMatches(sql: Sql, keys: List<String>, min: Int, label: String) {
        val c = sql.commits(keys)
        assertEquals(c[0].int(), commits, "$label commits")
        assertEquals(listOf(long(c[1]), long(c[2]), long(c[3])), listOf(filesChanged, linesAdded, linesDeleted), "$label counted figures")
        assertEquals(c[4].int(), commitsLinesUnknown, "$label commitsLinesUnknown")
        val m = sql.mergeRequests(keys)
        assertEquals(listOf(m[0].int(), m[1].int(), m[2].int()), listOf(mrsMerged, mrsOpened, openMrs), "$label merge requests")
        assertEquals(mrsMerged >= min, mrCycleWorkingDaysP50 != null, "$label p50 follows the minimum sample")
        val a = sql.acts(keys)
        assertEquals(
            listOf(a[0].int(), a[1].int(), a[2].int(), a[3].int()), listOf(reviewsGiven, approvalsGiven, changesRequestedGiven, comments),
            "$label acts",
        )
        assertEquals(sql.requests(keys).toInt(), reviewRequestsReceived, "$label review requests")
    }

    private fun Any?.int(): Int = (this as Number).toInt()

    private fun CodeActivityReport.assertFlowMatches(sql: Sql, keys: List<String>, zone: ZoneId) {
        val own = placeholders(keys.size)
        val p = arrayOf<Any?>(sql.connId.toInt(), sql.from, sql.to, *keys.toTypedArray())
        val merged = "connection_id = ? AND deleted_at IS NULL AND state = 'merged' AND merged_at >= ? AND merged_at < ? " +
            "AND author_key IN $own"
        val mergedP = p
        fun one(select: String, where: String, vararg params: Any?) =
            sql.rows("SELECT $select FROM norm.code_merge_requests WHERE $where", *params).single()
        assertEquals(long(one("count(*)", merged, *mergedP)[0]).toInt(), flow.merged, "merged")
        val closed = one(
            "count(*)",
            "connection_id = ? AND deleted_at IS NULL AND state = 'closed' AND closed_at >= ? AND closed_at < ? AND author_key IN $own", *p,
        )
        assertEquals(long(closed[0]).toInt(), flow.closedUnmerged, "closedUnmerged")

        val elapsed = one(
            "percentile_cont(0.5) WITHIN GROUP (ORDER BY (merged_at - created_at) / 86400000.0), " +
                "percentile_cont(0.9) WITHIN GROUP (ORDER BY (merged_at - created_at) / 86400000.0)",
            merged, *mergedP,
        )
        assertEquals(flow.merged.toLong(), flow.cycleElapsedDays.n, "elapsed n")
        assertEquals(flow.merged.toLong(), flow.cycleWorkingDays.n, "working n")
        if (!flow.cycleElapsedDays.hidden) {
            close("elapsed p50", dbl(elapsed[0]), flow.cycleElapsedDays.p50)
            close("elapsed p90", dbl(elapsed[1]), flow.cycleElapsedDays.p90)
        }
        val size = one(
            "count(*) FILTER (WHERE additions IS NOT NULL AND deletions IS NOT NULL), " +
                "percentile_cont(0.5) WITHIN GROUP (ORDER BY additions + deletions) " +
                "FILTER (WHERE additions IS NOT NULL AND deletions IS NOT NULL), " +
                "count(file_count), percentile_cont(0.5) WITHIN GROUP (ORDER BY file_count)",
            merged, *mergedP,
        )
        assertEquals(long(size[0]), flow.sizeLines.n, "sizeLines n")
        assertEquals(long(size[2]), flow.sizeFiles.n, "sizeFiles n")
        if (!flow.sizeLines.hidden) close("sizeLines p50", dbl(size[1]), flow.sizeLines.p50)
        if (!flow.sizeFiles.hidden) close("sizeFiles p50", dbl(size[3]), flow.sizeFiles.p50)

        // Review coverage over the merged merge requests whose details were read: another human is an actor that is not the author and
        // not decided BOT / IGNORED, and only what happened up to the merge counts (an undated APPROVAL still does).
        val byHuman = { kinds: String, dated: String ->
            """SELECT count(*) FROM norm.code_merge_requests m WHERE m.connection_id = ? AND m.deleted_at IS NULL AND m.state = 'merged'
                 AND m.details_state = 'READ' AND m.merged_at >= ? AND m.merged_at < ? AND m.author_key IN $own
                 AND NOT EXISTS (SELECT 1 FROM norm.code_mr_events e WHERE e.connection_id = m.connection_id AND e.mr_id = m.mr_id
                   AND e.kind IN $kinds $dated AND e.actor_key IS NOT NULL AND e.actor_key <> m.author_key
                   AND NOT EXISTS (SELECT 1 FROM public.code_identity_links b WHERE b.source_host = '$host' AND b.identity_key = e.actor_key
                                   AND b.decision IN ('BOT','IGNORED')))"""
        }
        val read = long(one("count(*)", "$merged AND details_state = 'READ'", *mergedP)[0])
        val withoutReview = long(sql.rows(byHuman(reviewKinds, "AND e.event_at IS NOT NULL AND e.event_at <= m.merged_at"), *p).single()[0])
        val approvalBound = "AND (e.event_at IS NULL OR e.event_at <= m.merged_at)"
        val withoutApproval = long(sql.rows(byHuman("('APPROVAL')", approvalBound), *p).single()[0])
        assertEquals(withoutReview.toInt(), flow.mergedWithoutReview.count, "mergedWithoutReview")
        assertEquals(withoutApproval.toInt(), flow.mergedWithoutApproval.count, "mergedWithoutApproval")
        if (read == 0L) {
            assertNull(flow.mergedWithoutReview.share)
        } else {
            close("share", withoutReview.toDouble() / read, flow.mergedWithoutReview.share)
        }
        assertEquals(read - withoutReview, flow.timeToFirstReviewWorkingDays.n, "time to first review n")
        assertEquals(if (read > 0) read.toInt() else null, this.review?.n, "review concentration n")

        // The weekly bars: Monday-started weeks of the report zone.
        val weekly = sql.rows(
            "SELECT date_trunc('week', to_timestamp(merged_at / 1000.0) AT TIME ZONE '${zone.id}')::date::text, count(*) " +
                "FROM norm.code_merge_requests WHERE $merged GROUP BY 1",
            *mergedP,
        ).associate { it[0] as String to long(it[1]).toInt() }
        assertEquals(weekly, flow.weekly.filter { it.merged > 0 }.associate { it.bucketStart to it.merged }, "weekly bars")
        assertEquals(flow.merged, flow.weekly.sumOf { it.merged }, "the bars add up to merged")

        val open = sql.rows(
            "SELECT count(*) FILTER (WHERE NOT draft), count(*) FILTER (WHERE draft) FROM norm.code_merge_requests " +
                "WHERE connection_id = ? AND deleted_at IS NULL AND state = 'opened' AND author_key IN $own",
            sql.connId.toInt(), *keys.toTypedArray(),
        ).single()
        assertEquals(listOf(long(open[0]).toInt(), long(open[1]).toInt()), listOf(this.open.count, this.open.drafts), "open merge requests")
    }

    private fun CodeActivityReport.assertQualityMatches(sql: Sql, undecidedCommits: Long) {
        val activity = sql.rows(
            """WITH activity AS (
                 SELECT identity_key k, 'c' t FROM norm.code_commits
                   WHERE connection_id = ? AND NOT is_merge AND deleted_at IS NULL AND committed_at >= ? AND committed_at < ?
                 UNION ALL SELECT author_key, 'm' FROM norm.code_merge_requests
                   WHERE connection_id = ? AND deleted_at IS NULL AND author_key IS NOT NULL
                     AND ((created_at >= ? AND created_at < ?) OR (merged_at >= ? AND merged_at < ?))
                 UNION ALL SELECT e.actor_key, 'e' FROM norm.code_mr_events e
                   JOIN norm.code_merge_requests m ON m.connection_id = e.connection_id AND m.mr_id = e.mr_id
                   WHERE e.connection_id = ? AND m.deleted_at IS NULL AND e.actor_key IS NOT NULL AND e.event_at >= ? AND e.event_at < ?)
               SELECT count(DISTINCT k), count(*) FILTER (WHERE t = 'c'), count(*) FILTER (WHERE t = 'm'), count(*) FILTER (WHERE t = 'e')
               FROM activity a WHERE NOT EXISTS (
                 SELECT 1 FROM public.code_identity_links l WHERE l.source_host = '$host' AND l.identity_key = a.k)""",
            sql.connId.toInt(), sql.from, sql.to, sql.connId.toInt(), sql.from, sql.to, sql.from, sql.to,
            sql.connId.toInt(), sql.from, sql.to,
        ).single()
        assertEquals(
            listOf(long(activity[0]).toInt(), long(activity[1]), long(activity[2]), long(activity[3])),
            listOf(quality.unlinkedIdentities, quality.unlinkedCommits, quality.unlinkedMergeRequests, quality.unlinkedEvents), "unlinked",
        )
        assertEquals(undecidedCommits, quality.unlinkedCommits)
        val c = sql.connId.toInt()
        fun count(from: String, where: String, vararg params: Any?) =
            long(sql.rows("SELECT count(*) FROM $from WHERE $where", *params).single()[0])
        val unknown = listOf(
            count(
                "norm.code_commits",
                "connection_id = ? AND NOT is_merge AND deleted_at IS NULL AND committed_at >= ? AND committed_at < ? AND " +
                    "(additions_counted IS NULL OR deletions_counted IS NULL OR files_counted IS NULL)",
                c, sql.from, sql.to,
            ),
            count(
                "norm.code_merge_requests",
                "connection_id = ? AND deleted_at IS NULL AND state = 'merged' AND merged_at >= ? AND merged_at < ? AND " +
                    "(additions IS NULL OR deletions IS NULL OR file_count IS NULL)",
                c, sql.from, sql.to,
            ),
            count(
                "norm.code_merge_requests",
                "connection_id = ? AND deleted_at IS NULL AND state = 'merged' AND merged_at >= ? AND merged_at < ? " +
                    "AND details_state <> 'READ'",
                c, sql.from, sql.to,
            ),
            count(
                "norm.code_mr_events e JOIN norm.code_merge_requests m ON m.connection_id = e.connection_id AND m.mr_id = e.mr_id",
                "e.connection_id = ? AND m.deleted_at IS NULL AND e.event_at IS NULL AND " +
                    "((m.created_at >= ? AND m.created_at < ?) OR (m.merged_at >= ? AND m.merged_at < ?))",
                c, sql.from, sql.to, sql.from, sql.to,
            ),
        )
        assertEquals(
            unknown,
            listOf(quality.commitsLinesUnknown, quality.mrsSizeUnknown, quality.mrsDetailsNotRead, quality.eventsUndated),
            "unknown counts",
        )
    }

    /**
     * Compare people's `CODE` cells of [pair] over [from]..[to] against the SQL re-derivation (counts, the counted lines, the size p50) and
     * Report 21's own member cells of the team [report] (the cycle p50 needs the working calendar); returns how many cells it compared.
     */
    private suspend fun assertPeopleCodeRows(
        client: HttpClient,
        sql: Sql,
        pair: List<String>,
        keys: List<List<String>>,
        from: String,
        to: String,
        min: Int,
        report: CodeActivityReport,
    ): Int {
        val compare = client.comparePeopleReport("from=$from&to=$to&accountId=${pair[0]}&accountId=${pair[1]}")
        fun cell(key: String, side: Int) = compare.rows.single { it.key == key }.let { if (side == 0) it.a else it.b }
        var compared = 0
        pair.indices.forEach { side ->
            val label = "$from ${pair[side]}"
            val c = sql.commits(keys[side])
            val m = sql.mergeRequests(keys[side])
            val a = sql.acts(keys[side])
            val group = report.groups.single { it.accountId == pair[side] }
            val want = mapOf(
                "commits" to c[0].int(),
                "linesChanged" to (long(c[2]) + long(c[3])).toInt(),
                "mrsMerged" to m[0].int(),
                "reviewsGiven" to a[0].int(),
                "approvalsGiven" to a[1].int(),
                "comments" to a[3].int(),
            )
            want.forEach { (key, count) ->
                assertEquals(count.toDouble(), cell(key, side).value, "$label $key")
                compared++
            }
            assertEquals(m[0].int(), cell("mrCycleWorkingDaysP50", side).n, "$label cycle n")
            assertEquals(group.mrCycleWorkingDaysP50, cell("mrCycleWorkingDaysP50", side).value, "$label cycle p50 is Report 21's")
            assertEquals(group.mrSizeLinesP50, cell("mrSizeLinesP50", side).value, "$label size p50 is Report 21's")
            val merged = sql.rows(
                """SELECT count(*) FILTER (WHERE additions IS NOT NULL AND deletions IS NOT NULL),
                     percentile_cont(0.5) WITHIN GROUP (ORDER BY additions + deletions)
                       FILTER (WHERE additions IS NOT NULL AND deletions IS NOT NULL)
                   FROM norm.code_merge_requests
                   WHERE connection_id = ? AND deleted_at IS NULL AND state = 'merged' AND merged_at >= ? AND merged_at < ?
                     AND author_key IN ${placeholders(keys[side].size)}""",
                sql.connId.toInt(), sql.from, sql.to, *keys[side].toTypedArray(),
            ).single()
            assertEquals(long(merged[0]).toInt(), cell("mrSizeLinesP50", side).n, "$label size n")
            if (long(merged[0]).toInt() >= min) close("$label size p50", dbl(merged[1]), cell("mrSizeLinesP50", side).value)
            assertEquals(long(merged[0]).toInt() < min, cell("mrSizeLinesP50", side).hidden, "$label size hidden")
            compared += 3
        }
        return compared
    }

    private suspend fun HttpClient.code(query: String): CodeActivityReport {
        val response = get("/api/v1/reports/code-activity?$query")
        assertEquals(HttpStatusCode.OK, response.status, query)
        return response.body()
    }

    @Test
    fun `every figure of the report equals an independent SQL re-derivation over the processed stub`() = testApplication {
        configureApp("gitlab.stubBaseUrl" to GitLabStubServer.start())
        startApplication()
        val admin = seededClient("code-fixture-admin", FlowUserRole.ADMIN)
        val user = seededClient("code-fixture-user", FlowUserRole.USER)
        application.attributes[ReportServiceKey].clock = { REFERENCE_TIME }
        val zone = reportZone()
        val teamId = TestTeams.seed("code-fixture-${System.nanoTime()}")
        val db = sharedDatabaseForTests()
        val settings = MetricsSettingsService(db, dataSources, SyncJobsService(db, 3))
        val memberships = TeamMembershipService(db, settings)
        val membershipIds = mutableListOf<UInt>()
        try {
            // The proposals are the stub's email matches against the Jira stub's people; the linked humans are the team, from 1970 on.
            val confirmed = admin.post("/api/v1/data-sources/$ownId/code-identities/confirm-proposals").body<CodeIdentityConfirmResult>()
            assertEquals(CodeIdentityConfirmResult(linked = 6, bots = 1), confirmed)
            val linked = links.forHost(host).filter { it.decision == CodeIdentityDecision.LINKED }
            val members = linked.map { it.jiraAccountId!! }.distinct()
            // Through the service, as an admin would: it validates the person and bumps the settings revision.
            members.forEach { membershipIds += memberships.create(teamId, TeamMembershipCreateRequest(it, validFrom = 0L)).id }
            val keysOf = linked.groupBy({ it.jiraAccountId!! }, { it.key })
            val allKeys = keysOf.values.flatten()

            for ((from, to) in listOf("2026-03-01" to "2026-08-31", "2026-06-03" to "2026-08-31")) {
                val (fromMs, toMs) = windowBounds(zone, from, to)
                val sql = Sql(ownId, fromMs, toMs, REFERENCE_TIME)
                val report = user.code("teamId=$teamId&from=$from&to=$to&connectionId=$ownId&tailPercent=0")
                val min = report.meta.minSampleSize
                assertEquals(members.toSet(), report.groups.mapNotNull { it.accountId }.toSet(), "$from: one group per member")
                assertTrue(report.flow.merged > 0 && report.groups.sumOf { it.commits } > 0, "$from: the window is not empty")
                report.groups.forEach { it.assertMatches(sql, keysOf.getValue(it.accountId!!), min, "$from ${it.accountId}") }
                // Compare people's CODE rows (A50) for the same window: the first two members head to head, ACROSS teams.
                val pair = members.take(2)
                val compared = assertPeopleCodeRows(user, sql, pair, pair.map { keysOf.getValue(it) }, from, to, min, report)
                assertTrue(compared > 0, "$from: Compare people's code rows are not vacuous")
                report.assertFlowMatches(sql, allKeys, zone)

                val unassigned = user.code("teamId=0&from=$from&to=$to&connectionId=$ownId&tailPercent=0")
                // Every linked human is a member, so the residue is the undecided identities; the BOT is in no group.
                assertTrue(unassigned.groups.none { it.accountId != null }, "$from: no linked person in the UNASSIGNED bucket")
                val residue = unassigned.groups.sumOf { it.commits.toLong() }
                assertEquals(unassigned.quality.unlinkedCommits, residue, "$from: the residue's commits")
                report.assertQualityMatches(sql, unassigned.quality.unlinkedCommits)
            }

            // Person trend's code points are Compare people's cells over each window (the one-truth gate), on real stub rows.
            val trend = user.personTrend("from=2026-06-03&to=2026-08-31&accountId=${members[0]}")
            val truth = user.assertPersonTrendIsComparePeople(trend, members[0], members[1], "")
            assertEquals(trend.windows.size * trend.rows.size, truth.cells)
            val mrs = trend.rows.single { it.key == "mrsMerged" }.points
            assertTrue(mrs.any { (it.value ?: 0.0) > 0.0 }, "the trend's merged merge requests are not all zero")

            // The timing target (build-times.md "In-test targets"): the 90-day request, the second one.
            val url = "teamId=$teamId&from=2026-06-03&to=2026-08-31&connectionId=$ownId"
            user.code(url)
            val started = System.nanoTime()
            user.code(url)
            println("in-test\tcode-activity-90d\t${(System.nanoTime() - started) / 1_000_000L}")
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
                ownId = GitLabFixtures.createConnection(dataSources, namePrefix = "code-activity-fixture")
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
