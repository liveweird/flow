package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamMembershipCreateRequest
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.CostsReport
import ch.nokillswit.reports.ReportDrillItems
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.AfterClass
import org.junit.BeforeClass

/**
 * Costs' sprint widgets on the stub's real data: every listed sprint's AC, epic split, capacity and the time outside the sprints equal an
 * INDEPENDENT re-derivation straight from the stored `fact_worklog`, `dim_task` and `dim_sprint` rows (plain sums over the connection's
 * worklogs by the sprint's stored dates - never the report's slice or fetch), and every bar's drill equals the chart. The shared derived
 * fixture maps the FLO board to a team with NO members (every stub worklog has a null author team), so this class clones its own
 * processed connection, maps the board, makes the three busiest authors members of the team and derives ONCE per class (`@BeforeClass`,
 * shared by every test here, which only read it) - a membership mutation (`testing.md` "Derived fixture"). `@AfterClass` deletes only the
 * memberships (`metrics.team_membership` is global by account); the clone stays, like the other clone tests'. The ranking and the fold on
 * known figures are pinned by `ReportCostsSprintsTest`.
 */
class ReportCostsSprintsFixtureTest {

    private class Worklog(
        val issueId: Long,
        val teamId: UInt?,
        val startedAt: Long,
        val md: BigDecimal,
        val ownEpic: Long?,
        val category: String?,
    )

    private class StoredSprint(val sprintId: Long, val startAt: Long, val completeAt: Long, val capacityMd: Double?)

    private class Stored(val worklogs: List<Worklog>, val sprints: List<StoredSprint>, val epicOfTask: Map<Long, Long?>, val team: UInt)

    private suspend fun stored(connId: UInt, team: UInt): Stored = suspendTransaction(sharedDatabaseForTests()) {
        val f = MetricsTables.FactSprint
        val d = MetricsTables.DimSprint
        val fact = f.selectAll().where { f.connectionId eq connId }.toList().filter { it[f.teamId] != null && it[f.completeAt] != null }
        val dims = d.selectAll().where { d.connectionId eq connId }.toList().associateBy { it[d.sprintId] }
        val sprints = fact.filter { it[f.teamId]!!.value == team }.mapNotNull { row ->
            val start = dims[row[f.sprintId]]?.get(d.startAt) ?: return@mapNotNull null
            StoredSprint(row[f.sprintId], start, row[f.completeAt]!!, row[f.capacityMd]?.toDouble())
        }
        val w = MetricsTables.FactWorklog
        val worklogs = w.selectAll().where { w.connectionId eq connId }.toList().map {
            val own = it[w.epicId]?.takeIf { epic -> epic == it[w.issueId] }
            Worklog(it[w.issueId], it[w.authorTeamId]?.value, it[w.startedAt], it[w.md], own, it[w.workCategory])
        }
        val t = MetricsTables.DimTask
        val epicOfTask = t.selectAll().where { t.connectionId eq connId }.toList().associate { it[t.issueId] to it[t.epicId] }
        Stored(worklogs, sprints, epicOfTask, team)
    }

    /** The CURRENT epic of a worklog by the stored rows alone: its task's `dim_task.epic_id`, the issue itself when it is an epic. */
    private fun Stored.epicOf(log: Worklog): Long? = if (log.issueId in epicOfTask) epicOfTask[log.issueId] else log.ownEpic

    private fun Stored.inSprint(sprint: StoredSprint) =
        worklogs.filter { it.teamId == team && it.startedAt >= sprint.startAt && it.startedAt < sprint.completeAt }

    private fun List<Worklog>.exact(): BigDecimal = fold(BigDecimal.ZERO) { sum, log -> sum + log.md }

    private fun BigDecimal.r2(): Double = setScale(2, RoundingMode.HALF_UP).toDouble()

    /** The class's clone: the connection, the team mapped to the FLO board and the ids of the memberships [tearDown] removes. */
    private class MemberTeam(val connId: UInt, val teamId: UInt, val membershipIds: List<UInt>)

    private suspend inline fun <reified T> HttpClient.report(path: String): T {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, "GET $path")
        return response.body()
    }

    private fun assertWidgets(label: String, report: CostsReport, s: Stored, outsideWindow: Pair<Long, Long>) {
        val listed = report.sprints.map { listedSprint -> s.sprints.single { it.sprintId == listedSprint.sprintId } }
        assertTrue(listed.isNotEmpty(), "$label: the team has sprints in the period")
        val scores = HashMap<Long?, BigDecimal>()
        for (sprint in listed) s.inSprint(sprint).forEach { scores.merge(s.epicOf(it), it.md, BigDecimal::add) }
        val tops = report.sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.toSet()
        // The fold is a true top-k by Σ AC: no epic outside the tops outranks one inside (ties may go either way).
        val weakestTop = tops.minOfOrNull { scores.getValue(it) }
        scores.filterKeys { it != null && it !in tops }.values.forEach {
            assertTrue(weakestTop == null || it <= weakestTop, "$label: top epics")
        }
        assertTrue(tops.size <= 6)
        for (sprint in report.sprints) {
            val stored = s.sprints.single { it.sprintId == sprint.sprintId }
            val logs = s.inSprint(stored)
            assertEquals(logs.exact().r2(), sprint.acMd, 1e-9, "$label/${sprint.sprintId} AC")
            assertEquals(stored.capacityMd, sprint.capacityMd, "$label/${sprint.sprintId} capacity")
            val expected = logs.groupBy { log ->
                when (val epic = s.epicOf(log)) {
                    null -> "NO_EPIC"
                    in tops -> epic.toString()
                    else -> "OTHER_EPICS"
                }
            }
            assertEquals(expected.keys, sprint.byEpic.map { it.key }.toSet(), "$label/${sprint.sprintId} segment keys")
            for (segment in sprint.byEpic) {
                assertEquals(expected.getValue(segment.key).exact().r2(), segment.acMd, 1e-9, "$label/${sprint.sprintId}/${segment.key}")
            }
        }
        val outside = s.worklogs.filter {
            it.teamId == s.team && it.startedAt >= outsideWindow.first && it.startedAt < outsideWindow.second &&
                listed.none { sprint -> it.startedAt >= sprint.startAt && it.startedAt < sprint.completeAt }
        }
        assertEquals(outside.exact().r2(), report.outsideSprintsMd!!, 1e-9, "$label: outside the sprints")
    }

    @Test
    fun `fixture - every sprint's AC, epic split, capacity and the outside time equal an independent read of the stored worklogs`() =
        testApplication {
            usePostgresTestcontainer()
            val connId = derived.connId
            val teamId = derived.teamId
            val s = stored(connId, teamId)
            val client = seededClient("cs-fixture")
            val team = "connectionId=$connId&teamId=$teamId"
            val last = client.report<CostsReport>("/api/v1/reports/costs?$team&lastSprints=52")
            assertEquals(s.sprints.map { it.sprintId }.toSet(), last.sprints.map { it.sprintId }.toSet(), "every closed sprint of the team")
            assertTrue(last.sprints.any { it.acMd > 0 } && last.sprints.any { sprint -> sprint.byEpic.any { it.epicId != null } })
            assertWidgets("lastSprints", last, s, s.sprints.minOf { it.startAt } to s.sprints.maxOf { it.completeAt } + 1)
            // A date range lists the sprints completed inside it; the outside time is the range's own.
            val range = client.report<CostsReport>("/api/v1/reports/costs?$team&from=2024-01-01&to=2026-12-31")
            val window = windowBounds(reportZone(), "2024-01-01", "2026-12-31")
            assertEquals(
                s.sprints.filter { it.completeAt >= window.first && it.completeAt < window.second }.map { it.sprintId }.toSet(),
                range.sprints.map { it.sprintId }.toSet(),
            )
            assertWidgets("range", range, s, window)
        }

    @Test
    fun `fixture - every sprint bar and every epic segment drill equals the chart`() = testApplication {
        usePostgresTestcontainer()
        val team = "connectionId=${derived.connId}&teamId=${derived.teamId}"
        val client = seededClient("cs-fixture-drill")
        val report = client.report<CostsReport>("/api/v1/reports/costs?$team&lastSprints=52")
        val tops = report.sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.distinct()
        var drilled = 0
        for (sprint in report.sprints) {
            val one = "$team&sprintId=${sprint.sprintId}"
            val whole = client.report<ReportDrillItems>("/api/v1/reports/costs/items?$one")
            assertEquals(sprint.acMd, whole.bar.sumMd, "${sprint.sprintId} whole bar")
            for (segment in sprint.byEpic) {
                val topIds = tops.joinToString("") { "&topEpicId=$it" }
                val narrow = "epicSegment=${segment.key}" + if (segment.key == "OTHER_EPICS") topIds else ""
                val drill = client.report<ReportDrillItems>("/api/v1/reports/costs/items?$one&by=EPIC&$narrow")
                assertEquals(segment.acMd, drill.bar.sumMd, "${sprint.sprintId}/${segment.key}")
                drilled++
            }
        }
        assertTrue(drilled > 5, "the fixture exercised the segment drill ($drilled)")
    }

    @Test
    fun `fixture - the set-based drills equal the JVM implementation for every sprint bar, epic segment and slice`() = testApplication {
        usePostgresTestcontainer()
        val team = "connectionId=${derived.connId}&teamId=${derived.teamId}"
        val client = seededClient("cs-fixture-golden")
        val report = client.report<CostsReport>("/api/v1/reports/costs?$team&lastSprints=52")
        val tops = report.sprints.flatMap { it.byEpic }.mapNotNull { it.epicId }.distinct()
        var compared = 0
        for (sprint in report.sprints) {
            val one = "$team&sprintId=${sprint.sprintId}"
            client.assertCostsDrillGolden(one)
            for (segment in sprint.byEpic) {
                val topIds = if (segment.key == "OTHER_EPICS") tops.joinToString("") { "&topEpicId=$it" } else ""
                val narrow = "epicSegment=${segment.key}$topIds"
                client.assertCostsDrillGolden("$one&by=EPIC&$narrow")
                compared++
            }
        }
        assertTrue(compared > 5, "the fixture exercised the segment drills ($compared)")
        // The donut slices of the team and of the whole connection: every printed key, then all of them at once (the folded "Other").
        val range = "from=2024-01-01&to=2026-12-31"
        for (scope in listOf("$team&$range", "connectionId=${derived.connId}&domain=${DerivedStubFixture.DOMAIN}&$range")) {
            val breakdown = client.report<CostsReport>("/api/v1/reports/costs?$scope")
            for ((by, slices) in listOf("ACTIVITY_TYPE" to breakdown.byActivityType, "WORK_CATEGORY" to breakdown.byWorkCategory)) {
                assertTrue(slices.isNotEmpty(), "$by has slices")
                for (slice in slices) client.assertCostsDrillGolden("$scope&by=$by&key=${java.net.URLEncoder.encode(slice.key, "UTF-8")}")
                val allKeys = slices.joinToString("") { "&key=${java.net.URLEncoder.encode(it.key, "UTF-8")}" }
                client.assertCostsDrillGolden("$scope&by=$by$allKeys")
            }
        }
    }

    @Test
    fun `fixture - the category widgets equal an independent sum of the stored worklogs, week by week`() = testApplication {
        usePostgresTestcontainer()
        val s = stored(derived.connId, derived.teamId)
        val zone = reportZone()
        val window = windowBounds(zone, "2024-01-01", "2026-12-31")
        val logs = s.worklogs.filter { it.teamId == s.team && it.startedAt >= window.first && it.startedAt < window.second }
        assertTrue(logs.isNotEmpty(), "the team's members logged time in the period")
        val report = seededClient("cs-fixture-cat").report<CostsReport>(
            "/api/v1/reports/costs?connectionId=${derived.connId}&teamId=${derived.teamId}&from=2024-01-01&to=2026-12-31",
        )
        assertEquals(logs.exact().r2(), report.totalMd, 1e-9, "the report's total")
        // The stub maps no work category, so every stored worklog is "no category": one row, the whole total, share 1, no target.
        assertTrue(logs.all { it.category == null })
        val targets = report.categoryTargets!!
        assertEquals(false, targets.configured)
        assertEquals(report.totalMd, targets.totalMd, 1e-9)
        val row = targets.rows.single()
        assertEquals("UNCATEGORIZED", row.key)
        assertEquals(report.totalMd, row.actualMd, 1e-9)
        assertEquals(1.0, row.actualShare!!, 1e-9)
        // The weekly cumulative: each Monday-start week of the period (java.time directly, not the report's bucket helper).
        fun monday(ms: Long): LocalDate =
            Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstWeek = monday(window.first)
        val lastWeek = monday(window.second - 1)
        val weeks = report.categoryShareTrend!!.weeks
        assertEquals(ChronoUnit.WEEKS.between(firstWeek, lastWeek) + 1, weeks.size.toLong())
        weeks.forEachIndexed { i, week ->
            val monday = firstWeek.plusWeeks(i.toLong())
            assertEquals(monday.toString(), week.weekStart)
            val cumulative = logs.filter { !monday(it.startedAt).isAfter(monday) }.exact().r2()
            assertEquals(cumulative, week.cumulativeMd, 1e-9, "cumulative through $monday")
            assertEquals(cumulative, week.byCategory.single().cumulativeMd, 1e-9)
        }
        assertEquals(report.totalMd, weeks.last().cumulativeMd, 1e-9)
    }

    private companion object {
        const val MEMBERS = 3

        lateinit var derivedTeam: MemberTeam

        /** The one clone every test of the class reads (set by [deriveOnce]). */
        val derived: MemberTeam get() = derivedTeam

        private suspend fun deriveMemberTeam(): MemberTeam {
            val shared = DerivedStubFixture.connectionId()
            val busiest = suspendTransaction(sharedDatabaseForTests()) {
                val w = MetricsTables.FactWorklog
                w.selectAll().where { w.connectionId eq shared }.toList().mapNotNull { it[w.authorAccountId] }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(MEMBERS).map { it.key }
            }
            assertEquals(MEMBERS, busiest.size)
            val connId = SyncedStubFixture.createConnection(namePrefix = "costs-sprints-derive", enabled = false)
            SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
            val team = DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "cs-fixture-team")
            val memberships = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())
            val created = busiest.map { memberships.create(team, TeamMembershipCreateRequest(it, 0L, null)) }
            val member = MemberTeam(connId, team, created.map { it.id })
            var derivedOk = false
            try {
                DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }
                derivedOk = true
            } finally {
                // A failed class setup never reaches @AfterClass's state: the global memberships must not leak into later classes.
                if (!derivedOk) removeMemberships(member)
            }
            return member
        }

        private suspend fun removeMemberships(member: MemberTeam) {
            val memberships = TeamMembershipService(sharedDatabaseForTests(), DerivedStubFixture.metricsSettings())
            member.membershipIds.forEach { memberships.delete(member.teamId, it) }
        }

        @JvmStatic
        @BeforeClass
        fun deriveOnce() {
            // The shared fixtures first (their one-time cost lands outside testApplication's 60 s limit), then the class's own derive.
            warmStubFixtures()
            derivedTeam = runBlocking { deriveMemberTeam() }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::derivedTeam.isInitialized) runBlocking { removeMemberships(derivedTeam) }
        }
    }
}
