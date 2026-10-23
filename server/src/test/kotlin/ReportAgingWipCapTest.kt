package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.AGING_MAX_ITEMS
import ch.nokillswit.reports.AgingWipReport
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.OpenItem
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.UNASSIGNED_TEAM_ID
import ch.nokillswit.reports.ageOf
import ch.nokillswit.reports.agingWip
import ch.nokillswit.reports.fetchOpenEpics
import ch.nokillswit.reports.fetchOpenTasks
import ch.nokillswit.reports.resolveReportScope
import ch.nokillswit.reports.workItemLabels
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 500-item cap of `GET /api/v1/reports/aging-wip` is applied inside Postgres (`reports/AgingOpenItems.kt`): the listed items,
 * their order and `itemsTruncated` must equal what the old read gave - every open item fetched, then sorted by age (oldest
 * first, connection id, issue id) and cut in the JVM. That old read is rebuilt here from `fetchOpenTasks` / `fetchOpenEpics`
 * (the Home overview still uses them) and graded against the report over a hand-built world with more than 500 open tasks AND more
 * than 500 open epics, a block of items that tie on age across the cut (all started on a weekend, issue ids unrelated to the start
 * order), items not yet started at the clock (age 0), items outside the filters, and a second connection.
 */
class ReportAgingWipCapTest {

    private fun filter(connId: UInt?, teamId: UInt? = null, accountId: String? = null) = ReportFilter(
        period = ReportPeriod.DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 0L, 1L),
        level = when {
            teamId == null -> ReportLevel.UNIT
            accountId == null -> ReportLevel.TEAM
            else -> ReportLevel.USER
        },
        teamId = teamId, accountIds = listOfNotNull(accountId), domainView = DomainView.TASK, domain = null, activityType = null,
        workCategory = null, connectionId = connId,
    )

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    /** What the old implementation listed: (kind, key, age) in order, and whether it cut. */
    private suspend fun legacy(service: ReportService, f: ReportFilter, now: Long): Pair<List<Triple<String, String, Double>>, Boolean> =
        service.reportTransaction {
            val scope = service.resolveReportScope(f, now)
            val open: List<OpenItem> = fetchOpenTasks(f, scope.connectionIds) + fetchOpenEpics(f, scope.connectionIds)
            val aged = open.map { it to ageOf(scope.calendar, it.startedAt, now) }
                .sortedWith(
                    compareByDescending<Pair<OpenItem, Double>> { it.second }.thenBy { it.first.connectionId }.thenBy { it.first.issueId },
                )
            val listed = aged.take(AGING_MAX_ITEMS)
            val labels = workItemLabels(listed.map { it.first.connectionId to it.first.issueId })
            listed.map { (item, age) ->
                val key = labels[item.connectionId to item.issueId]?.first
                Triple(item.kind, key ?: item.issueId.toString(), age)
            } to (aged.size > AGING_MAX_ITEMS)
        }

    private fun AgingWipReport.listed() = items.map { Triple(it.itemKind, it.issueKey, it.ageWorkingDays) }

    @Test
    fun `the cap in the query lists exactly what sorting every open item in the JVM listed`() = testApplication {
        usePostgresTestcontainer()
        val connA = SyncedStubFixture.createConnection(namePrefix = "aging-cap-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "aging-cap-b", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("aging-cap-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("aging-cap-y"))
        val now = noonUtc("2026-01-14") // Wednesday
        val saturday = noonUtc("2026-01-03") - 12 * HOUR
        val old = noonUtc("2025-12-01")

        // Start times: `old` days apart (distinct ages), the weekend block (one age for all of it), and the future (age 0).
        fun startOf(i: Int, group: Int): Long = when (group) {
            0 -> old + i * 2 * HOUR
            1 -> saturday + i * 5 * 60_000L // 5 minutes apart: all of it inside Saturday and Sunday
            else -> now + HOUR + i * HOUR
        }
        // Issue ids unrelated to the start order: i -> a permutation of 0..n-1 (7919 is coprime with every n used here).
        fun scrambled(i: Int, n: Int) = (i * 7919L) % n

        fun tasksOf(count: IntArray, ownerOf: (Int) -> UInt?, connTag: Int) = buildList {
            var i = 0
            count.forEachIndexed { group, c ->
                repeat(c) { inGroup ->
                    // The weekend block's ids fall as the start rises: the LAST items to start win the tie.
                    val id = (if (group == 1) 4000L + 2 * (c - inGroup) else scrambled(i, count.sum()) * 2) + 1000 * connTag
                    val startedAt = startOf(inGroup, group)
                    val idx = i
                    add(
                        handTask(
                            id, startedAt, null, currentTeamId = ownerOf(idx), currentAssignee = if (idx % 5 == 0) "acc-1" else "acc-$idx",
                        )
                            .copy(currentStage = listOf("IN_PROGRESS", "WAITING", "BLOCKED", "NOT_STARTED", "IN_PROGRESS")[idx % 5]),
                    )
                    i++
                }
            }
        }
        // 5 of 6 tasks are open in an aging stage; team X holds most, Y some, a few have no team. Counts per group: old, weekend, future.
        val ownerOf = { i: Int -> if (i % 11 == 0) teamY else if (i % 13 == 0) null else teamX }
        val tasksA = tasksOf(intArrayOf(330, 360, 120), ownerOf, 0) + handTask(5, old, null, currentTeamId = teamX, subtask = true)
        val tasksB = tasksOf(intArrayOf(10, 40, 5), ownerOf, 1)
        val epicsA = buildList {
            var i = 0
            intArrayOf(120, 330, 110).forEachIndexed { group, c ->
                repeat(c) { inGroup ->
                    val id = if (group == 1) 4001L + 2 * (c - inGroup) else scrambled(i, 560) * 2 + 1
                    add(handEpic(id, startOf(inGroup, group), null, ownerTeamId = if (i % 9 == 0) teamY else teamX))
                    i++
                }
            }
        }
        val dimsA = epicsA.mapIndexed { i, e ->
            val stage = listOf("IN_PROGRESS", "WAITING", "BLOCKED", "NOT_STARTED", "IN_PROGRESS", "IN_PROGRESS", "IN_PROGRESS")[i % 7]
            DimEpicRow(e.issueId, "EP-${e.issueId}", null, "AAA", null, stage, null, null)
        }
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connA, tasksA, configRevision = 1L)
            store.replaceFactEpicDelivery(connA, epicsA, configRevision = 1L)
            store.insertEpics(connA, dimsA, configRevision = 1L)
            store.replaceFactTaskDelivery(connB, tasksB, configRevision = 1L)
        }
        try {
            val svc = service()
            val reads = listOf(
                "connection A, unit" to filter(connA),
                "connection A, team X" to filter(connA, teamX),
                "connection A, team Y" to filter(connA, teamY),
                "connection A, unassigned" to filter(connA, UNASSIGNED_TEAM_ID),
                "connection A, user acc-1" to filter(connA, teamX, "acc-1"),
                "both connections, unit" to filter(null),
                "both connections, team X" to filter(null, teamX),
            )
            for ((label, f) in reads) {
                val (want, wantTruncated) = legacy(svc, f, now)
                val got = svc.agingWip(f, now)
                assertEquals(want.size, got.items.size, "$label: how many are listed")
                assertEquals(want, got.listed(), "$label: the listed items and their order")
                assertEquals(wantTruncated, got.itemsTruncated, "$label: itemsTruncated")
            }
            // The world really exercises the cut: both kinds exceed the cap, and the age ties straddle it.
            val unit = legacy(svc, filter(connA), now)
            assertTrue(unit.second, "more than 500 open items")
            val lastTwo = unit.first.takeLast(2)
            assertEquals(lastTwo[0].third, lastTwo[1].third, "the cut falls inside a block of equal ages")
            assertTrue(unit.first.any { it.first == "EPIC" } && unit.first.any { it.first == "TASK" })
            val epicsOnly = legacy(svc, filter(connA, teamX), now).first.count { it.first == "EPIC" }
            assertTrue(epicsOnly > 0)
            val small = svc.agingWip(filter(connA, teamX, "acc-1"), now)
            assertTrue(!small.itemsTruncated && small.items.isNotEmpty(), "a small read is not cut")
        } finally {
            suspendTransaction(sharedDatabaseForTests()) {
                for (c in listOf(connA, connB)) {
                    store.deleteFactTaskDelivery(c)
                    store.deleteFactEpicDelivery(c)
                    store.deleteDims(c)
                }
            }
            cleanUpTeams(listOf(teamX, teamY))
        }
    }

    companion object {
        private const val HOUR = 3_600_000L

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
