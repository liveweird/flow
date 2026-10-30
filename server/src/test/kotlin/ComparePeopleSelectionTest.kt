package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.parseComparePeople
import io.ktor.http.parametersOf
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure unit tests of report 18's parser (`ComparePeopleSelection.kt`): exactly two people, a from/to period, no team. */
class ComparePeopleSelectionTest {

    private val calendar = WorkingCalendar(ZoneId.of("UTC"), weekendDays = emptySet(), holidays = emptySet())
    private val now = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun parse(vararg pairs: Pair<String, List<String>>) = parametersOf(*pairs).parseComparePeople(calendar, now, null)

    @Test
    fun `two distinct people with no team parse to a team-less USER filter on the default 90 days`() {
        val filter = parse("accountId" to listOf("acc-b", "acc-a"))
        assertEquals(ReportLevel.USER, filter.level)
        assertNull(filter.teamId)
        assertEquals(listOf("acc-b", "acc-a"), filter.accountIds, "request order is side A then side B")
        assertEquals(DomainView.TASK, filter.domainView)
        val period = assertIs<ReportPeriod.DateRange>(filter.period)
        assertEquals(LocalDate.of(2026, 3, 18), period.fromDate)
        assertEquals(LocalDate.of(2026, 6, 15), period.toDate)
    }

    @Test
    fun `an explicit range and the common slices pass through`() {
        val filter = parse(
            "accountId" to listOf("a", "b"), "from" to listOf("2026-01-01"), "to" to listOf("2026-01-31"),
            "domain" to listOf("FLO"), "activityType" to listOf("Bug"), "workCategory" to listOf("UNCATEGORIZED"),
            "tailPercent" to listOf("0"),
        )
        assertEquals("FLO", filter.domain)
        assertEquals("Bug", filter.activityType)
        assertEquals("UNCATEGORIZED", filter.workCategory)
        assertEquals(0, filter.tailPercent)
        assertEquals(LocalDate.of(2026, 1, 31), assertIs<ReportPeriod.DateRange>(filter.period).toDate)
    }

    @Test
    fun `one, three, none or a duplicate collapsing to one person is 400`() {
        for (accounts in listOf(emptyList(), listOf("a"), listOf("a", "a"), listOf("a", "b", "c"), listOf("a", " a "))) {
            val failure = assertFailsWith<BadRequestException>("accountId=$accounts") { parse("accountId" to accounts) }
            assertTrue(failure.message!!.contains("accountId"), failure.message)
        }
        assertEquals(2, parse("accountId" to listOf("a", "b", "a")).accountIds.size, "a repeat collapses before the count")
    }

    @Test
    fun `teamId, lastSprints and sprintId are not accepted`() {
        for (extra in listOf("teamId" to "3", "teamId" to "0", "lastSprints" to "2", "sprintId" to "9")) {
            val failure = assertFailsWith<BadRequestException>(extra.first) {
                parse("accountId" to listOf("a", "b"), extra.first to listOf(extra.second))
            }
            assertTrue(failure.message!!.contains("from/to period only"), failure.message)
        }
    }

    @Test
    fun `the shared parser's own 400s still apply`() {
        val two = "accountId" to listOf("a", "b")
        assertFailsWith<BadRequestException> { parse(two, "from" to listOf("2026-02-01"), "to" to listOf("2026-01-01")) }
        assertFailsWith<BadRequestException> { parse(two, "from" to listOf("2020-01-01"), "to" to listOf("2026-01-01")) }
        assertFailsWith<BadRequestException> { parse(two, "tailPercent" to listOf("26")) }
        assertFailsWith<BadRequestException> { parse(two, "domainView" to listOf("NOPE")) }
        assertFailsWith<BadRequestException> { parse(two, "from" to listOf("2026-01-01", "2026-01-02")) }
    }
}
