package ch.nokillswit

import ch.nokillswit.metrics.DimDomainRow
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.SprintTotals
import ch.nokillswit.reports.ResolvedSprintGroup
import ch.nokillswit.reports.CostMatrixReport
import ch.nokillswit.reports.CostRow
import ch.nokillswit.reports.DomainView
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9

/** The UNASSIGNED sentinel team id on the wire (authors in no team). */
private const val UNASSIGNED = 0u

/** One `fact_worklog` row as stored — the independent read the report is graded against (never the report's own query). */
private data class Wl(
    val team: UInt?,
    val account: String?,
    val startedAt: Long,
    val md: BigDecimal,
    val taskDomain: String?,
    val epicDomain: String?,
    val foreign: Boolean,
) {
    /** The column a worklog lands in: TASK = its task's domain; EPIC = its epic's, an epic-less one falling back to its own. */
    fun domain(view: DomainView): String? = if (view == DomainView.EPIC) epicDomain ?: taskDomain else taskDomain
}

private suspend fun readWorklogs(connId: UInt, bounds: Pair<Long, Long>): List<Wl> = suspendTransaction(sharedDatabaseForTests()) {
    val w = MetricsStore.FactWorklog
    w.selectAll().where { w.connectionId eq connId }.toList()
        .map {
            Wl(
                it[w.authorTeamId]?.value, it[w.authorAccountId], it[w.startedAt], it[w.md], it[w.taskDomainKey],
                it[w.epicDomainKey], it[w.foreignWork],
            )
        }
        .filter { it.startedAt >= bounds.first && it.startedAt < bounds.second }
}

private fun Iterable<Wl>.exact(): BigDecimal = fold(BigDecimal.ZERO) { sum, log -> sum + log.md }

/** The report's one rounding rule: 2 decimals, half up, applied to an EXACT sum. */
private fun BigDecimal.r2(): Double = setScale(2, RoundingMode.HALF_UP).toDouble()

private fun CostRow.cellMap(): Map<String?, Double> = cells.associate { it.domain to it.md }

/**
 * `GET /api/v1/reports/cost-matrix` (v0.3.0 M5 commit 17b, Report 16, `.claude/docs/measures.md` "Report 16"). The stub
 * fixture grades every total, row, column and foreign figure against an INDEPENDENT sum over the raw `fact_worklog` rows in
 * both domain views; the exact cells, the TASK/EPIC switch, the rounding rule and the foreign shares are pinned on hand-built
 * rows in a fresh DISABLED connection with hand-computed answers. Every request runs through a non-admin `seededClient` (D12).
 */
class ReportCostMatrixTest {

    private suspend fun HttpClient.cost(query: String): CostMatrixReport {
        val response = get("/api/v1/reports/cost-matrix?$query")
        assertEquals(HttpStatusCode.OK, response.status, "GET cost-matrix?$query")
        return response.body()
    }

    /** Every figure of [body] against the independent sums over [logs] in [view]; the invariant-6 reconciliation included. */
    private fun assertMatrix(label: String, body: CostMatrixReport, logs: List<Wl>, view: DomainView) {
        assertEquals(view, body.meta.domainView, "$label: domainView")
        assertEquals(logs.exact().r2(), body.totalMd, EPS, "$label: grand total")
        assertEquals(logs.filter { it.foreign }.exact().r2(), body.foreignMd, EPS, "$label: foreign MD")
        val expectedTotal = logs.exact()
        val expectedForeign = logs.filter { it.foreign }.exact()
        val expectedShare = expectedTotal.takeIf { it.signum() != 0 }?.let { expectedForeign.toDouble() / it.toDouble() }
        if (expectedShare == null) assertNull(body.foreignShare) else assertEquals(expectedShare, body.foreignShare!!, EPS, "$label: share")

        val domains = logs.map { it.domain(view) }.distinct().sortedWith(compareBy(nullsLast()) { it })
        assertEquals(domains, body.columns.map { it.domain }, "$label: columns")
        for (column in body.columns) {
            val expected = logs.filter { it.domain(view) == column.domain }.exact().r2()
            assertEquals(expected, column.totalMd, EPS, "$label: column ${column.domain}")
        }

        val byTeam = logs.groupBy { it.team ?: UNASSIGNED }
        assertEquals(byTeam.keys, body.rows.map { it.teamId }.toSet(), "$label: one row per author team, UNASSIGNED included")
        if (UNASSIGNED in byTeam.keys) assertEquals(UNASSIGNED, body.rows.last().teamId, "$label: UNASSIGNED is last")
        for (row in body.rows) {
            val own = byTeam.getValue(row.teamId!!)
            assertEquals(own.exact().r2(), row.totalMd, EPS, "$label: row ${row.teamId}")
            assertEquals(own.filter { it.foreign }.exact().r2(), row.foreignMd, EPS, "$label: row ${row.teamId} foreign")
            assertEquals(domains, row.cells.map { it.domain }, "$label: dense cells in column order")
            for (cell in row.cells) {
                val expected = own.filter { it.domain(view) == cell.domain }.exact().r2()
                assertEquals(expected, cell.md, EPS, "$label: ${row.teamId}/${cell.domain}")
            }
        }
        // Invariant 6, from the report's side: nothing dropped or doubled - the rounded parts stay within half a cent each of the whole.
        assertEquals(logs.exact().toDouble(), body.rows.sumOf { it.totalMd }, 0.005 * body.rows.size + EPS, "$label: rows sum to the total")
        assertEquals(logs.exact().toDouble(), body.columns.sumOf { it.totalMd }, 0.005 * body.columns.size + EPS, "$label: columns sum")
        assertEquals(
            logs.exact().toDouble(), body.rows.sumOf { row -> row.cells.sumOf { it.md } },
            0.005 * body.rows.size * body.columns.size + EPS, "$label: cells sum to the total",
        )
    }

    @Test
    fun `fixture - the grand total, every row, column and foreign figure equal independent sums in both views`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cost-fixture")
        val zone = reportZone()
        for ((from, to) in listOf("2024-01-01" to "2026-12-31", "2025-11-01" to "2025-12-31")) {
            val logs = readWorklogs(connId, windowBounds(zone, from, to))
            assertTrue(logs.isNotEmpty() && logs.exact().signum() > 0, "window $from..$to must hold worklogs")
            val range = "connectionId=$connId&from=$from&to=$to"
            assertMatrix("$from..$to default (EPIC)", client.cost(range), logs, DomainView.EPIC)
            for (view in DomainView.entries) {
                assertMatrix("$from..$to $view", client.cost("$range&domainView=$view"), logs, view)
            }
        }
        // A domain slice keeps exactly that column's worklogs (EPIC view: the epic's domain, else the task's own).
        val logs = readWorklogs(connId, windowBounds(zone, "2024-01-01", "2026-12-31"))
        val busiest = logs.mapNotNull { it.domain(DomainView.EPIC) }.groupingBy { it }.eachCount().maxBy { it.value }.key
        val sliced = logs.filter { it.domain(DomainView.EPIC) == busiest }
        val query = "connectionId=$connId&from=2024-01-01&to=2026-12-31&domain=$busiest"
        assertMatrix("domain $busiest", client.cost(query), sliced, DomainView.EPIC)
    }

    /** The hand-built world of one test: three teams, six authors and a fresh disabled connection. Removed by [withCostFixture]. */
    private class CostFixture(
        val connId: UInt,
        val t1: UInt,
        val t2: UInt,
        val t3: UInt,
        val alice: String,
        val bob: String,
        val carol: String,
        val erin: String,
        val dave: String,
        val zed: String,
    ) {
        val range get() = "connectionId=$connId&from=2026-01-01&to=2026-01-31"
    }

    /** A row builder: every parameter is one column of the stored fact. */
    private fun wl(
        id: Long,
        team: UInt?,
        account: String?,
        day: String,
        md: Double,
        taskDomain: String,
        epicDomain: String? = null,
        foreign: Boolean = false,
        activity: String = "Story",
        category: String? = null,
        startedAtMs: Long? = null,
    ) = FactWorklogRow(
        worklogId = id, issueId = id, authorAccountId = account, authorTeamId = team, startedAt = startedAtMs ?: noonUtc(day),
        createdAt = null, lateMs = null, md = md, taskDomainKey = taskDomain, epicId = null, epicDomainKey = epicDomain,
        activityType = activity, workCategory = category, sprintIdAtStarted = null, sprintTeamIdAtStarted = null,
        foreignWork = foreign, assigneeAccountIdAtStarted = null, assigneeTeamIdAtStarted = null,
    )

    /**
     * January 2026 (noon UTC, far from any day boundary). Domain columns as EPIC / TASK view:
     *  - w1 T1 alice 1.0, foreign — a cross-domain task: task AAA under an epic of BBB (EPIC: BBB, TASK: AAA)
     *  - w2 T1 bob 2.5, a Bug — epic-less: AAA in both
     *  - c1..c3 T2 carol 0.3333 each (thirds): AAA epic-less; task AAA under an epic of BBB; CCC epic-less, foreign
     *  - w4 T2 erin 1.5, category Ops — logged on an epic: BBB in both (A21)
     *  - w5 UNASSIGNED dave 4.0 on BBB; w6 UNASSIGNED with no known author 0.5 on CCC
     *  - w7 T3 zed 0.0 on AAA
     *  - w8/w9 are outside January (2026-02-20 and 2025-12-31, 99 and 50 MD) and never count in it.
     * Domains AAA "Alpha" and BBB "Beta" have `dim_domain` rows, CCC has none (its column has no name).
     */
    private suspend fun withCostFixture(block: suspend (CostFixture) -> Unit) {
        val connId = SyncedStubFixture.createConnection(namePrefix = "cost-matrix", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val t1 = TestTeams.seed(SyncedStubFixture.unique("cm-t1"))
        val t2 = TestTeams.seed(SyncedStubFixture.unique("cm-t2"))
        val t3 = TestTeams.seed(SyncedStubFixture.unique("cm-t3"))
        val tag = SyncedStubFixture.unique("cm")
        val f = CostFixture(connId, t1, t2, t3, "$tag-alice", "$tag-bob", "$tag-carol", "$tag-erin", "$tag-dave", "$tag-zed")
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertDomains(
                connId,
                listOf(DimDomainRow("AAA", "Alpha", emptyList(), null), DimDomainRow("BBB", "Beta", emptyList(), null)),
                configRevision = 1L,
            )
            store.insertFactWorklog(
                connId,
                listOf(
                    wl(1, t1, f.alice, "2026-01-10", 1.0, "AAA", "BBB", foreign = true),
                    wl(2, t1, f.bob, "2026-01-11", 2.5, "AAA", activity = "Bug"),
                    wl(3, t2, f.carol, "2026-01-12", 0.3333, "AAA"),
                    wl(4, t2, f.carol, "2026-01-13", 0.3333, "AAA", "BBB"),
                    wl(5, t2, f.carol, "2026-01-14", 0.3333, "CCC", foreign = true),
                    wl(6, t2, f.erin, "2026-01-15", 1.5, "BBB", "BBB", category = "Ops"),
                    wl(7, null, f.dave, "2026-01-16", 4.0, "BBB"),
                    wl(8, null, null, "2026-01-17", 0.5, "CCC"),
                    wl(9, t3, f.zed, "2026-01-18", 0.0, "AAA"),
                    wl(10, t1, f.alice, "2026-02-20", 99.0, "AAA"),
                    wl(11, t1, f.alice, "2025-12-31", 50.0, "AAA"),
                ),
                configRevision = 1L,
            )
        }
        try {
            block(f)
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                store.deleteFactWorklog(connId)
                store.deleteDims(connId)
            }
            cleanUpTeams(listOf(t1, t2, t3))
        }
    }

    /**
     * The EPIC view (the default) and the TASK view over January. w1 is the cross-domain task: BBB in the EPIC view (its epic's
     * domain), AAA in the TASK view (its own); w4, logged on an epic, is BBB in both; an epic-less task is its own domain in both.
     * Rows are the author teams by name with UNASSIGNED (id 0, no label) last; cells are dense in column order.
     */
    @Test
    fun `hand-built rows pin the cells and the task versus epic column switch`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-cells")
            val epic = client.cost(f.range)
            assertEquals(DomainView.EPIC, epic.meta.domainView, "the default is the EPIC view")
            assertEquals(listOf("AAA", "BBB", "CCC"), epic.columns.map { it.domain })
            assertEquals(listOf("Alpha", "Beta", null), epic.columns.map { it.name })
            assertEquals(listOf(2.83, 6.83, 0.83), epic.columns.map { it.totalMd })
            assertEquals(listOf(f.t1, f.t2, f.t3, UNASSIGNED), epic.rows.map { it.teamId })
            assertNull(epic.rows.last().label, "UNASSIGNED carries no name")
            assertTrue(epic.rows.dropLast(1).all { it.label != null && it.accountId == null })
            assertEquals(mapOf<String?, Double>("AAA" to 2.5, "BBB" to 1.0, "CCC" to 0.0), epic.rows[0].cellMap())
            assertEquals(mapOf<String?, Double>("AAA" to 0.33, "BBB" to 1.83, "CCC" to 0.33), epic.rows[1].cellMap())
            assertEquals(mapOf<String?, Double>("AAA" to 0.0, "BBB" to 0.0, "CCC" to 0.0), epic.rows[2].cellMap())
            assertEquals(mapOf<String?, Double>("AAA" to 0.0, "BBB" to 4.0, "CCC" to 0.5), epic.rows[3].cellMap())
            assertEquals(listOf(3.5, 2.5, 0.0, 4.5), epic.rows.map { it.totalMd })
            assertEquals(10.5, epic.totalMd)

            val task = client.cost("${f.range}&domainView=TASK")
            assertEquals(DomainView.TASK, task.meta.domainView)
            assertEquals(listOf("AAA", "BBB", "CCC"), task.columns.map { it.domain })
            assertEquals(listOf(4.17, 5.5, 0.83), task.columns.map { it.totalMd })
            assertEquals(mapOf<String?, Double>("AAA" to 3.5, "BBB" to 0.0, "CCC" to 0.0), task.rows[0].cellMap(), "w1 moved to AAA")
            assertEquals(mapOf<String?, Double>("AAA" to 0.67, "BBB" to 1.5, "CCC" to 0.33), task.rows[1].cellMap())
            assertEquals(1.5, task.rows[1].cellMap()["BBB"], "the epic-logged worklog is BBB in both views (A21)")
            assertEquals(epic.totalMd, task.totalMd, "the views move MD between columns, never in or out")
            assertEquals(listOf(3.5, 2.5, 0.0, 4.5), task.rows.map { it.totalMd })
        }
    }

    /**
     * Carol's thirds: 0.3333 in each of AAA, BBB and CCC (EPIC view) - each cell rounds to 0.33, so the cells add up to 0.99, yet
     * her row total is the rounded EXACT sum 0.9999 -> 1.00; likewise the columns add up to 10.49 while the grand total is the
     * rounded exact 10.4999 -> 10.50. A total is never the sum of rounded cells (`reports.md` "Report 16").
     */
    @Test
    fun `totals are the rounded exact sums, never the sums of rounded cells`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-rounding")
            val carol = client.cost("${f.range}&teamId=${f.t2}&accountId=${f.carol}").rows.single()
            assertEquals(listOf(0.33, 0.33, 0.33), carol.cells.map { it.md })
            assertEquals(1.0, carol.totalMd, "0.9999 rounds to 1.00, not 0.99 (the sum of the cells)")
            assertEquals(0.33, carol.foreignMd)

            val body = client.cost(f.range)
            assertEquals(10.49, body.columns.sumOf { it.totalMd }, 1e-6, "the rounded columns add up to 10.49 ...")
            assertEquals(10.5, body.totalMd, "... the grand total is the rounded exact 10.4999")
            assertEquals(10.5, readWorklogs(f.connId, windowBounds(reportZone(), "2026-01-01", "2026-01-31")).exact().r2())
            assertEquals(2.5, body.rows.first { it.teamId == f.t2 }.totalMd, "0.9999 + 1.5 = 2.4999 -> 2.50")
        }
    }

    /**
     * Foreign share = exact foreign MD / exact MD. T1: 1.0 of 3.5; T2: 0.3333 of 2.4999; UNASSIGNED: 0 of 4.5, never foreign, so
     * 0.0 (not null); T3 logged 0.0 MD, so its share is undefined (null). The report's own share is 1.3333 of 10.4999. A slice
     * that selects nothing has a null share and no rows.
     */
    @Test
    fun `foreign share is foreign MD over MD per row and overall, null when nothing was logged`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-foreign")
            val body = client.cost(f.range)
            assertEquals(listOf(1.0, 0.33, 0.0, 0.0), body.rows.map { it.foreignMd })
            assertEquals(1.0 / 3.5, body.rows[0].foreignShare!!, EPS)
            assertEquals(0.3333 / 2.4999, body.rows[1].foreignShare!!, EPS)
            assertNull(body.rows[2].foreignShare, "no MD logged: undefined, never 0")
            assertEquals(0.0, body.rows[3].foreignShare!!, EPS)
            assertEquals(1.33, body.foreignMd)
            assertEquals(1.3333 / 10.4999, body.foreignShare!!, EPS)

            val none = client.cost("${f.range}&domain=NOPE")
            assertTrue(none.rows.isEmpty() && none.columns.isEmpty())
            assertEquals(0.0, none.totalMd)
            assertEquals(0.0, none.foreignMd)
            assertNull(none.foreignShare)
        }
    }

    /** The period is `started_at` in the configured zone (inclusive dates); every slice applies to the worklogs. */
    @Test
    fun `the period and the domain, activity type and work category slices apply to the worklogs`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-slices")
            val wide = client.cost("connectionId=${f.connId}&from=2025-12-01&to=2026-02-28")
            assertEquals(10.4999 + 99.0 + 50.0, wide.totalMd, 0.005, "the out-of-January worklogs count in a wider period")
            assertEquals(50.0, client.cost("connectionId=${f.connId}&from=2025-12-31&to=2025-12-31").totalMd, "one inclusive day")

            val beta = client.cost("${f.range}&domain=BBB")
            assertEquals(listOf("BBB"), beta.columns.map { it.domain })
            assertEquals(6.83, beta.totalMd)
            assertEquals(listOf(f.t1, f.t2, UNASSIGNED), beta.rows.map { it.teamId }, "EPIC view: the cross-domain w1 is BBB's")
            assertEquals(listOf(1.0, 1.83, 4.0), beta.rows.map { it.totalMd })
            val betaTask = client.cost("${f.range}&domain=BBB&domainView=TASK")
            assertEquals(5.5, betaTask.totalMd, "TASK view: only the tasks of BBB")
            assertEquals(listOf(f.t2, UNASSIGNED), betaTask.rows.map { it.teamId })

            val bug = client.cost("${f.range}&activityType=Bug")
            assertEquals(2.5, bug.totalMd)
            assertEquals(listOf(f.t1), bug.rows.map { it.teamId })
            val ops = client.cost("${f.range}&workCategory=Ops")
            assertEquals(1.5, ops.totalMd)
            val uncategorized = client.cost("${f.range}&workCategory=UNCATEGORIZED")
            assertEquals(9.0, uncategorized.totalMd, 0.005, "everything but the Ops worklog")
            assertEquals(10.5 - 1.5, uncategorized.totalMd)

            // A connection slice: another connection sees none of these rows.
            val other = client.cost("connectionId=${DerivedStubFixture.connectionId()}&from=2026-01-01&to=2026-01-31")
            assertTrue(other.rows.none { it.teamId in listOf(f.t1, f.t2, f.t3) })
            // breakdown is accepted and changes nothing.
            assertEquals(client.cost(f.range).rows, client.cost("${f.range}&breakdown=DOMAIN").rows)
        }
    }

    /**
     * The drill: a team lists its authors (by display name - the account id when `norm.people` has none) over the columns its
     * own worklogs use; `teamId` + `accountId` is that one author's row; `teamId=0` lists the authors in no team, the one with no
     * known author last. The drill's total is the team's row total.
     */
    @Test
    fun `a team drills to its authors and a user is one row`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-drill")
            val team = client.cost("${f.range}&teamId=${f.t1}")
            assertEquals(listOf(f.alice, f.bob), team.rows.map { it.accountId })
            assertEquals(listOf(f.alice, f.bob), team.rows.map { it.label }, "no display name known: the account id")
            assertTrue(team.rows.all { it.teamId == f.t1 })
            assertEquals(3.5, team.totalMd)
            assertEquals(listOf("AAA", "BBB"), team.columns.map { it.domain }, "only the domains this team's worklogs reach")
            assertEquals(mapOf<String?, Double>("AAA" to 0.0, "BBB" to 1.0), team.rows[0].cellMap())
            assertEquals(mapOf<String?, Double>("AAA" to 2.5, "BBB" to 0.0), team.rows[1].cellMap())
            assertEquals(listOf(1.0, 0.0), team.rows.map { it.foreignShare }, "alice's 1.0 MD was all foreign, bob's none")
            assertEquals(team.totalMd, client.cost(f.range).rows.first { it.teamId == f.t1 }.totalMd)

            val user = client.cost("${f.range}&teamId=${f.t1}&accountId=${f.bob}")
            assertEquals(listOf(f.bob), user.rows.map { it.accountId })
            assertEquals(2.5, user.totalMd)
            assertEquals(2.5, user.rows.single().totalMd)
            assertTrue(client.cost("${f.range}&teamId=${f.t1}&accountId=${f.erin}").rows.isEmpty(), "erin is not in T1: no row")

            val unassigned = client.cost("${f.range}&teamId=0")
            assertEquals(listOf(f.dave, null), unassigned.rows.map { it.accountId }, "the worklogs with no known author come last")
            assertTrue(unassigned.rows.all { it.teamId == UNASSIGNED })
            assertEquals(listOf(4.0, 0.5), unassigned.rows.map { it.totalMd })
            assertEquals(4.5, unassigned.totalMd)
        }
    }

    /** UTC millis of [day] at [hour]:[minute] — a worklog placed exactly where a zone's day boundary can move it. */
    private fun utcMs(day: String, hour: Int, minute: Int): Long =
        LocalDate.parse(day).atStartOfDay(ZoneOffset.UTC).plusHours(hour.toLong()).plusMinutes(minute.toLong()).toInstant().toEpochMilli()

    private fun closedSprint(id: Long, team: UInt, start: String, complete: String) = DimSprintRow(
        id, null, team, "Sprint $id", "closed", noonUtc(start), noonUtc(complete), noonUtc(complete), null, null,
    ) to FactSprintRow(id, team, noonUtc(complete), SprintTotals(0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0, 0.0, 0), null, null)

    /**
     * A sprint-relative period reads the resolved sprints' ENVELOPE `[min(start), max(complete)]` (inclusive end). T1's sprint 101 runs
     * Jan 9-11 (w1, w2), T2's sprint 201 Jan 13-15 (c2, c3, w6; c1 on Jan 12 falls outside). `lastSprints=1` at UNIT level unions both
     * envelopes into ONE window Jan 9 - Jan 15, so T2's UNIT row also holds c1 (2.50) while T2's own drill reads only its sprint
     * (2.17): the two can differ (`reports.md`). `meta` names the resolved sprints per team and no `from`/`to`.
     */
    @Test
    fun `a sprint-relative period reads the resolved envelope and names the sprints in meta`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-sprints")
            val store = MetricsStore(sharedDatabaseForTests())
            val (dim1, fact1) = closedSprint(101, f.t1, "2026-01-09", "2026-01-11")
            val (dim2, fact2) = closedSprint(201, f.t2, "2026-01-13", "2026-01-15")
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertDimSprints(f.connId, listOf(dim1, dim2), configRevision = 1L)
                store.insertFactSprint(f.connId, listOf(fact1, fact2), configRevision = 1L)
            }
            try {
                val unit = client.cost("connectionId=${f.connId}&lastSprints=1")
                assertNull(unit.meta.from)
                assertNull(unit.meta.to)
                assertEquals(
                    setOf(ResolvedSprintGroup(f.t1, listOf(101)), ResolvedSprintGroup(f.t2, listOf(201))),
                    unit.meta.resolvedSprints.toSet(),
                )
                assertEquals(listOf(f.t1, f.t2), unit.rows.map { it.teamId }, "dave, zed and the null author fall outside Jan 9-15")
                assertEquals(listOf(3.5, 2.5), unit.rows.map { it.totalMd }, "T2 reads the UNION window, so c1 (Jan 12) counts")
                assertEquals(6.0, unit.totalMd, "1.0 + 2.5 + 0.9999 + 1.5 = 5.9999")

                val drill = client.cost("connectionId=${f.connId}&lastSprints=1&teamId=${f.t2}")
                assertEquals(listOf(ResolvedSprintGroup(f.t2, listOf(201))), drill.meta.resolvedSprints)
                assertEquals(2.17, drill.totalMd, "T2's own envelope Jan 13-15 excludes c1: 0.3333 + 0.3333 + 1.5")
                assertEquals(listOf(f.carol, f.erin), drill.rows.map { it.accountId })
                assertEquals(listOf(0.67, 1.5), drill.rows.map { it.totalMd })

                val one = client.cost("connectionId=${f.connId}&sprintId=101")
                assertEquals(listOf(ResolvedSprintGroup(f.t1, listOf(101))), one.meta.resolvedSprints)
                assertEquals(listOf(f.t1), one.rows.map { it.teamId })
                assertEquals(3.5, one.totalMd, "the inclusive end: w2 starts exactly at the sprint's completion")
            } finally {
                suspendTransaction(sharedDatabaseForTests()) { store.deleteSprintFacts(f.connId) }
            }
        }
    }

    /**
     * The period's days are calendar days in the CONFIGURED zone. Under UTC+1 (`Etc/GMT-1`) a worklog at 22:30 UTC on Jan 20 is
     * 23:30 local (Jan 20) and one at 23:30 UTC is 00:30 local on Jan 21: the day filter puts them on different days. Under UTC
     * both are Jan 20.
     */
    @Test
    fun `the period days are days in the configured zone`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-zone")
            val store = MetricsStore(sharedDatabaseForTests())
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertFactWorklog(
                    f.connId,
                    listOf(
                        wl(30, f.t3, f.zed, "2026-01-20", 1.25, "AAA", startedAtMs = utcMs("2026-01-20", 22, 30)),
                        wl(31, f.t3, f.zed, "2026-01-20", 2.0, "AAA", startedAtMs = utcMs("2026-01-20", 23, 30)),
                    ),
                    configRevision = 1L,
                )
            }
            suspend fun total(day: String) = client.cost("connectionId=${f.connId}&from=$day&to=$day").totalMd
            val config = DerivedStubFixture.metricsConfig()
            withMetricsSettings(config, { it.copy(timeZone = "Etc/GMT-1") }) {
                assertEquals(1.25, total("2026-01-20"), "23:30 local is still Jan 20; 00:30 local is Jan 21")
                assertEquals(2.0, total("2026-01-21"))
            }
            withMetricsSettings(config, { it.copy(timeZone = "UTC") }) {
                assertEquals(3.25, total("2026-01-20"))
                assertEquals(0.0, total("2026-01-21"))
            }
        }
    }

    /**
     * The `domain` slice follows the view's column. EPIC view: an epic-less worklog falls back to its own task domain, so AAA holds
     * w2, c1 (epic-less) and zed's 0 but NOT w1/c2 (task AAA under an epic of BBB); CCC holds c3 and w6. TASK view: AAA holds all of
     * w1, w2, c1, c2 (and zed's 0).
     */
    @Test
    fun `the domain slice follows the view column, an epic-less worklog falling back to its task domain`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-domain")
            val alphaEpic = client.cost("${f.range}&domain=AAA")
            assertEquals(listOf("AAA"), alphaEpic.columns.map { it.domain })
            assertEquals(listOf(f.t1, f.t2, f.t3), alphaEpic.rows.map { it.teamId })
            assertEquals(listOf(2.5, 0.33, 0.0), alphaEpic.rows.map { it.totalMd }, "w1 and c2 belong to BBB's epic")
            assertEquals(2.83, alphaEpic.totalMd)
            val alphaTask = client.cost("${f.range}&domain=AAA&domainView=TASK")
            assertEquals(listOf(3.5, 0.67, 0.0), alphaTask.rows.map { it.totalMd })
            assertEquals(4.17, alphaTask.totalMd)
            val gamma = client.cost("${f.range}&domain=CCC")
            assertEquals(listOf(f.t2, UNASSIGNED), gamma.rows.map { it.teamId })
            assertEquals(0.83, gamma.totalMd, "epic-less: CCC in both views")
        }
    }

    /**
     * A soft-deleted author team that logged work keeps its UNIT row, named, with `active = false`; UNASSIGNED and live teams are
     * `true`; drilled rows leave `active` out; the deleted team's own drill is a 400.
     */
    @Test
    fun `a soft-deleted author team keeps its row marked inactive and its drill is a 400`() = testApplication {
        usePostgresTestcontainer()
        withCostFixture { f ->
            val client = seededClient("reports-cost-inactive")
            val gone = TestTeams.seed(SyncedStubFixture.unique("cm-gone"))
            val store = MetricsStore(sharedDatabaseForTests())
            suspendTransaction(sharedDatabaseForTests()) {
                store.insertFactWorklog(f.connId, listOf(wl(40, gone, "${f.zed}-x", "2026-01-19", 0.5, "AAA")), configRevision = 1L)
            }
            TestTeams.service.delete(gone)
            val body = client.cost(f.range)
            val row = body.rows.first { it.teamId == gone }
            assertEquals(false, row.active)
            assertEquals(0.5, row.totalMd)
            assertTrue(row.label!!.startsWith("cm-gone"), "the deleted team keeps its name")
            assertTrue(body.rows.filter { it.teamId != gone }.all { it.active == true }, "live teams and UNASSIGNED are active")
            assertEquals(UNASSIGNED, body.rows.last().teamId)
            assertTrue(client.cost("${f.range}&teamId=${f.t1}").rows.all { it.active == null }, "author rows carry no flag")
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cost-matrix?${f.range}&teamId=$gone").status)
        }
    }

    /**
     * Two connections read together (no `connectionId`) sum into one matrix. Scoped independently of the rest of the shared
     * database by a unique domain and unique teams: A logs 1.0 and B 2.0 for T1 plus 0.5 UNASSIGNED; the column name is the lowest
     * connection id's; `connectionId=A` alone sees only A's 1.0.
     */
    @Test
    fun `connections in scope are summed and a connectionId narrows to one`() = testApplication {
        usePostgresTestcontainer()
        val connA = SyncedStubFixture.createConnection(namePrefix = "cost-multi-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "cost-multi-b", enabled = false)
        val team = TestTeams.seed(SyncedStubFixture.unique("cm-multi"))
        val domain = SyncedStubFixture.unique("ZZ-cm").uppercase()
        val store = MetricsStore(sharedDatabaseForTests())
        suspendTransaction(sharedDatabaseForTests()) {
            for ((conn, name) in listOf(connA to "Multi A", connB to "Multi B")) {
                store.insertDomains(conn, listOf(DimDomainRow(domain, name, emptyList(), null)), configRevision = 1L)
            }
            store.insertFactWorklog(connA, listOf(wl(1, team, "multi-a", "2026-03-10", 1.0, domain)), configRevision = 1L)
            store.insertFactWorklog(
                connB,
                listOf(wl(1, team, "multi-b", "2026-03-11", 2.0, domain), wl(2, null, "multi-c", "2026-03-12", 0.5, domain)),
                configRevision = 1L,
            )
        }
        try {
            val client = seededClient("reports-cost-multi")
            val both = client.cost("from=2026-03-01&to=2026-03-31&domain=$domain")
            assertEquals(listOf(team, UNASSIGNED), both.rows.map { it.teamId })
            assertEquals(listOf(3.0, 0.5), both.rows.map { it.totalMd })
            assertEquals(3.5, both.totalMd)
            assertEquals(listOf("Multi A"), both.columns.map { it.name }, "the lowest connection id names the domain")
            val onlyA = client.cost("connectionId=$connA&from=2026-03-01&to=2026-03-31&domain=$domain")
            assertEquals(1.0, onlyA.totalMd)
            assertEquals(listOf(team), onlyA.rows.map { it.teamId })
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                for (conn in listOf(connA, connB)) {
                    store.deleteFactWorklog(conn)
                    store.deleteDims(conn)
                }
            }
            cleanUpTeams(listOf(team))
        }
    }

    @Test
    fun `bad filters are 400, never 404, and a plain user is allowed`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val client = seededClient("reports-cost-400")
        val team = TestTeams.seed(SyncedStubFixture.unique("cost-400"))
        try {
            val known = "connectionId=$connId"
            for (query in listOf(
                "$known&accountId=someone", // accountId needs teamId (the shared parser)
                "$known&from=2026-01-01&to=2025-01-01",
                "$known&from=2026-01-01&lastSprints=3", // mutually exclusive periods
                "$known&lastSprints=0",
                "$known&domainView=NOPE",
                "$known&teamId=999999", // unknown team
                "$known&teamId=$team&teamId=$team", // a repeated scalar key
                "connectionId=999999", // unknown connection
                "$known&sprintId=999999999", // unknown sprint
            )) {
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/reports/cost-matrix?$query").status, query)
            }
            // D12: a plain user reads every level; every optional parameter is accepted.
            for (query in listOf(
                known, "$known&teamId=$team", "$known&teamId=$team&accountId=nobody", "$known&teamId=0", "$known&domainView=TASK",
                "$known&breakdown=DOMAIN", "$known&domain=NOPE&activityType=Story&workCategory=UNCATEGORIZED",
                "$known&lastSprints=3", "$known&teamId=0&lastSprints=3",
            )) {
                assertEquals(HttpStatusCode.OK, client.get("/api/v1/reports/cost-matrix?$query").status, query)
            }
        } finally {
            cleanUpTeams(listOf(team))
        }
    }
}
