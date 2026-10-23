package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.FactEpicDeliveryRow
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
 * first, connection id, issue id) and cut in the JVM. That old read is rebuilt here from `legacyOpenTasks` / `legacyOpenEpics`
 * (`HomeWipOracle.kt`, the Home overview's old reads) and graded against the report over hand-built worlds, each a count of items per start
 * block: OLD (distinct ages), WEEKEND (one age for the whole block, issue ids falling as the start rises, so a first-500-by-start
 * shortcut picks the wrong ones) and FUTURE (started after the clock, age 0):
 * - both kinds above the cap (538 open epics, 648 open tasks), the cut inside a weekend tie block, a second connection, the
 *   team / unassigned / user / unit reads;
 * - the cut inside an EPIC tie block (the epic page itself fills and its tie extension pages twice);
 * - a kind with EXACTLY 500 open (not truncated), one with 501 and no tie at the cut (truncated), and two kinds under 500 each but
 *   over 500 together (truncated);
 * - a block of 1,100 age-0 ties past the 500th (the tie extension walks eleven keyset pages).
 */
class ReportAgingWipCapTest {

    private class Legacy(val listed: List<Triple<String, String, Double>>, val truncated: Boolean, val openTasks: Int, val openEpics: Int)

    /** Items per start block: OLD, WEEKEND, FUTURE. */
    private class Blocks(val old: Int, val weekend: Int, val future: Int) {
        val size get() = old + weekend + future
        val groups get() = intArrayOf(old, weekend, future)
    }

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

    /** What the old implementation listed: (kind, key, age) in order, whether it cut, and how many tasks and epics were open. */
    private suspend fun legacy(service: ReportService, f: ReportFilter, now: Long): Legacy =
        service.reportTransaction {
            val scope = service.resolveReportScope(f, now)
            val open: List<OpenItem> = legacyOpenTasks(f, scope.connectionIds) + legacyOpenEpics(f, scope.connectionIds)
            val aged = open.map { it to ageOf(scope.calendar, it.startedAt, now) }
                .sortedWith(
                    compareByDescending<Pair<OpenItem, Double>> { it.second }.thenBy { it.first.connectionId }.thenBy { it.first.issueId },
                )
            val listed = aged.take(AGING_MAX_ITEMS)
            val labels = workItemLabels(listed.map { it.first.connectionId to it.first.issueId })
            Legacy(
                listed.map { (item, age) ->
                    val key = labels[item.connectionId to item.issueId]?.first
                    Triple(item.kind, key ?: item.issueId.toString(), age)
                },
                aged.size > AGING_MAX_ITEMS, open.count { it.kind == "TASK" }, open.count { it.kind == "EPIC" },
            )
        }

    private fun AgingWipReport.listed() = items.map { Triple(it.itemKind, it.issueKey, it.ageWorkingDays) }

    private val now = noonUtc("2026-01-14") // Wednesday
    private val saturday = noonUtc("2026-01-03") - 12 * HOUR

    /** Start times: [OLD] days apart (distinct ages), the weekend block (one age for all of it), and the future (age 0). */
    private fun startOf(i: Int, group: Int): Long = when (group) {
        0 -> noonUtc("2025-12-01") + i * 2 * HOUR
        1 -> saturday + i * 5 * 60_000L // 5 minutes apart: all of it inside Saturday and Sunday
        else -> now + HOUR + i * HOUR
    }

    /** Issue ids unrelated to the start order: i -> a permutation of 0..n-1 (7919 is coprime with every n used here). */
    private fun scrambled(i: Int, n: Int) = (i * 7919L) % n

    /** [blocks] tasks; a task is open in an aging stage unless [allOpen] is false and its index says NOT_STARTED. */
    private fun tasksOf(blocks: Blocks, allOpen: Boolean, ownerOf: (Int) -> UInt?, connTag: Int) = buildList {
        var i = 0
        blocks.groups.forEachIndexed { group, c ->
            repeat(c) { inGroup ->
                // The weekend block's ids fall as the start rises: the LAST items to start win the tie.
                val id = (if (group == 1) 4000L + 2 * (c - inGroup) else scrambled(i, blocks.size) * 2) + 1000 * connTag
                val idx = i
                val stage = if (allOpen) {
                    "IN_PROGRESS"
                } else {
                    listOf("IN_PROGRESS", "WAITING", "BLOCKED", "NOT_STARTED", "IN_PROGRESS")[idx % 5]
                }
                add(
                    handTask(
                        id, startOf(inGroup, group), null, currentTeamId = ownerOf(idx),
                        currentAssignee = if (idx % 5 == 0) "acc-1" else "acc-$idx",
                    ).copy(currentStage = stage),
                )
                i++
            }
        }
    }

    private fun epicsOf(blocks: Blocks, ownerOf: (Int) -> UInt?) = buildList {
        var i = 0
        blocks.groups.forEachIndexed { group, c ->
            repeat(c) { inGroup ->
                val id = if (group == 1) 4001L + 2 * (c - inGroup) else scrambled(i, blocks.size) * 2 + 1
                add(handEpic(id, startOf(inGroup, group), null, ownerTeamId = ownerOf(i)))
                i++
            }
        }
    }

    private fun epicDims(epics: List<FactEpicDeliveryRow>, allOpen: Boolean) = epics.mapIndexed { i, e ->
        val stage = if (allOpen || i % 25 != 3) listOf("IN_PROGRESS", "WAITING", "BLOCKED")[i % 3] else "NOT_STARTED"
        DimEpicRow(e.issueId, "EP-${e.issueId}", null, "AAA", null, stage, null, null)
    }

    /** Seeds one connection (plus an optional second, tasks only), runs [check] and removes everything again. */
    private suspend fun world(
        tasks: Blocks,
        epics: Blocks,
        allOpen: Boolean,
        withSecondConnection: Boolean = false,
        check: suspend (ReportService, UInt, UInt, UInt) -> Unit,
    ) {
        val connA = SyncedStubFixture.createConnection(namePrefix = "aging-cap-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "aging-cap-b", enabled = false)
        val store = MetricsStore(sharedDatabaseForTests())
        val teamX = TestTeams.seed(SyncedStubFixture.unique("aging-cap-x"))
        val teamY = TestTeams.seed(SyncedStubFixture.unique("aging-cap-y"))
        // Team X holds most, Y some, a few have no team - except in the all-open worlds, where everything is X's.
        val ownerOf = { i: Int -> if (allOpen) teamX else if (i % 11 == 0) teamY else if (i % 13 == 0) null else teamX }
        val tasksA = tasksOf(tasks, allOpen, ownerOf, 0) + handTask(5, noonUtc("2025-12-01"), null, currentTeamId = teamX, subtask = true)
        val epicsA = epicsOf(epics, ownerOf)
        suspendTransaction(sharedDatabaseForTests()) {
            store.replaceFactTaskDelivery(connA, tasksA, configRevision = 1L)
            store.replaceFactEpicDelivery(connA, epicsA, configRevision = 1L)
            store.insertEpics(connA, epicDims(epicsA, allOpen), configRevision = 1L)
            if (withSecondConnection) {
                store.replaceFactTaskDelivery(connB, tasksOf(Blocks(10, 40, 5), false, ownerOf, 1), configRevision = 1L)
            }
        }
        try {
            check(service(), connA, teamX, teamY)
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

    /** The report must list what the old read listed, in the same order, with the same ages and flag. */
    private suspend fun assertMatchesLegacy(svc: ReportService, label: String, f: ReportFilter): Legacy {
        val want = legacy(svc, f, now)
        val got = svc.agingWip(f, now)
        assertEquals(want.listed.size, got.items.size, "$label: how many are listed")
        assertEquals(want.listed, got.listed(), "$label: the listed items and their order")
        assertEquals(want.truncated, got.itemsTruncated, "$label: itemsTruncated")
        return want
    }

    @Test
    fun `both kinds above the cap list exactly what sorting every open item in the JVM listed`() = testApplication {
        usePostgresTestcontainer()
        world(Blocks(330, 360, 120), Blocks(120, 330, 110), allOpen = false, withSecondConnection = true) { svc, connA, teamX, teamY ->
            val reads = listOf(
                "connection A, unit" to filter(connA),
                "connection A, team X" to filter(connA, teamX),
                "connection A, team Y" to filter(connA, teamY),
                "connection A, unassigned" to filter(connA, UNASSIGNED_TEAM_ID),
                "connection A, user acc-1" to filter(connA, teamX, "acc-1"),
                "both connections, unit" to filter(null),
                "both connections, team X" to filter(null, teamX),
            )
            for ((label, f) in reads) assertMatchesLegacy(svc, label, f)
            // The world really exercises the cut: BOTH kinds fill their page, and the age ties straddle it.
            val unit = legacy(svc, filter(connA), now)
            assertTrue(
                unit.openTasks > AGING_MAX_ITEMS && unit.openEpics > AGING_MAX_ITEMS,
                "open: ${unit.openTasks} tasks, ${unit.openEpics} epics",
            )
            assertTrue(unit.truncated)
            val lastTwo = unit.listed.takeLast(2)
            assertEquals(lastTwo[0].third, lastTwo[1].third, "the cut falls inside a block of equal ages")
            assertTrue(unit.listed.any { it.first == "EPIC" } && unit.listed.any { it.first == "TASK" })
            val small = svc.agingWip(filter(connA, teamX, "acc-1"), now)
            assertTrue(!small.itemsTruncated && small.items.isNotEmpty(), "a small read is not cut")
        }
    }

    @Test
    fun `a cut inside an epic tie block lists what the old read listed`() = testApplication {
        usePostgresTestcontainer()
        world(Blocks(100, 100, 0), Blocks(100, 600, 0), allOpen = false) { svc, connA, teamX, _ ->
            for ((label, f) in listOf("unit" to filter(connA), "team X" to filter(connA, teamX))) {
                val want = assertMatchesLegacy(svc, label, f)
                assertTrue(want.openEpics > AGING_MAX_ITEMS, "the epic page fills: ${want.openEpics} open epics")
                assertTrue(want.truncated)
                val last = want.listed.takeLast(2)
                assertTrue(last.all { it.first == "EPIC" } && last[0].third == last[1].third, "the cut lands inside the epic tie block")
                assertTrue(want.listed.any { it.first == "TASK" }, "the tasks' weekend block ties with it at the merge")
            }
        }
    }

    @Test
    fun `a kind with exactly 500 open is not truncated and two kinds under 500 each are, together`() = testApplication {
        usePostgresTestcontainer()
        val exact = Blocks(200, 200, 100)
        val none = Blocks(0, 0, 0)
        world(exact, none, allOpen = true) { svc, connA, _, _ ->
            val want = assertMatchesLegacy(svc, "500 open tasks", filter(connA))
            assertEquals(AGING_MAX_ITEMS, want.listed.size)
            assertEquals(false, svc.agingWip(filter(connA), now).itemsTruncated, "500 is not a cut")
        }
        world(Blocks(501, 0, 0), none, allOpen = true) { svc, connA, _, _ ->
            val want = assertMatchesLegacy(svc, "501 open tasks", filter(connA))
            assertEquals(AGING_MAX_ITEMS, want.listed.size)
            assertTrue(svc.agingWip(filter(connA), now).itemsTruncated, "the 501st open task is a cut (only the COUNT sees it)")
        }
        world(none, exact, allOpen = true) { svc, connA, _, _ ->
            val want = assertMatchesLegacy(svc, "500 open epics", filter(connA))
            assertEquals(AGING_MAX_ITEMS, want.listed.size)
            assertEquals(false, svc.agingWip(filter(connA), now).itemsTruncated, "500 is not a cut")
        }
        world(Blocks(100, 100, 100), Blocks(100, 100, 100), allOpen = true) { svc, connA, teamX, _ ->
            for ((label, f) in listOf("unit" to filter(connA), "team X" to filter(connA, teamX))) {
                val want = assertMatchesLegacy(svc, "300 + 300: $label", f)
                assertEquals(300, want.openTasks)
                assertEquals(300, want.openEpics)
                assertTrue(svc.agingWip(f, now).itemsTruncated, "$label: 600 open items are cut to 500")
            }
        }
    }

    @Test
    fun `a block of age-0 ties far past the 500th is walked page by page`() = testApplication {
        usePostgresTestcontainer()
        world(Blocks(100, 0, 1500), Blocks(0, 0, 0), allOpen = true) { svc, connA, _, _ ->
            val want = assertMatchesLegacy(svc, "1,500 items started after the clock", filter(connA))
            assertEquals(1600, want.openTasks)
            assertTrue(want.truncated)
            assertTrue(want.listed.takeLast(300).all { it.third == 0.0 }, "the cut is deep inside the age-0 block")
        }
    }

    companion object {
        private const val HOUR = 3_600_000L

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
