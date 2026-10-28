package ch.nokillswit

import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.reports.BlockedItemKind
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.fetchAccuracyTasks
import ch.nokillswit.reports.fetchBlockedItems
import ch.nokillswit.reports.fetchCompareDoneTasks
import ch.nokillswit.reports.fetchDeliveredFacts
import ch.nokillswit.reports.fetchDoneCycleTasks
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * Person trend reads the four populations of `fact_task_delivery` from ONE scan (`fetchDeliveredFacts`, `build-times.md` WHY 46)
 * instead of the four statements Compare people runs. The scan must be exactly those four: same rows, same objects, for the
 * derived fixture's busiest people over the year, with and without a slice -- the guard against one of the four predicates or row
 * mappers changing without the shared scan following.
 */
class PersonTrendFactsTest {

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    private fun filter(connId: UInt, accounts: List<String>, activityType: String?) = ReportFilter(
        period = ReportPeriod.DateRange(LocalDate.parse(FACTS_FROM), LocalDate.parse(FACTS_TO), 0L, 1L),
        level = ReportLevel.USER, teamId = null, accountIds = accounts, domainView = DomainView.TASK, domain = null,
        activityType = activityType, workCategory = null, connectionId = connId,
    )

    @Test
    fun `the one scan returns exactly the four separate fetches`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val window = windowBounds(reportZone(), FACTS_FROM, FACTS_TO)
        val accounts = jdbcRows(
            """SELECT assignee_account_id_at_done FROM metrics.fact_task_delivery
               WHERE connection_id = ? AND is_subtask = false AND done_at >= ? AND done_at < ? AND assignee_account_id_at_done IS NOT NULL
               GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 4""",
            connId.toInt(), window.first, window.second,
        ).map { it[0] as String }
        assertEquals(4, accounts.size, "the derived fixture has four assignees with done tasks in the year")
        val activityType = jdbcRows(
            """SELECT activity_type FROM metrics.fact_task_delivery WHERE connection_id = ? AND done_at IS NOT NULL
               GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1""",
            connId.toInt(),
        ).single()[0] as String
        val service = service()
        val filters = listOf("no slice" to filter(connId, accounts, null), "an activity slice" to filter(connId, accounts, activityType))
        for ((label, f) in filters) {
            service.reportTransaction {
                val scan = fetchDeliveredFacts(f, listOf(connId), window)
                assertTrue(scan.done.isNotEmpty(), "$label: the scan reads rows")
                assertEquals(fetchCompareDoneTasks(f, listOf(connId), window).toSet(), scan.done.toSet(), "$label: done tasks")
                assertEquals(fetchDoneCycleTasks(f, listOf(connId), window).toSet(), scan.cycle.toSet(), "$label: cycle tasks")
                assertEquals(fetchAccuracyTasks(f, listOf(connId), window).toSet(), scan.accuracy.toSet(), "$label: accuracy tasks")
                val blocked = fetchBlockedItems(f, BlockedItemKind.TASK, listOf(connId), window)
                assertEquals(blocked.toSet(), scan.blocked.toSet(), "$label: blocked")
                assertEquals(blocked.size, scan.blocked.size, "$label: no duplicated row in the scan")
                val sizes = listOf(scan.done.size, scan.cycle.size, scan.accuracy.size, scan.blocked.size)
                assertEquals(1, sizes.toSet().size, "$label: one row per task in every population ($sizes)")
            }
        }
    }

    companion object {
        private const val FACTS_FROM = "2025-03-06"
        private const val FACTS_TO = "2026-03-05"

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
