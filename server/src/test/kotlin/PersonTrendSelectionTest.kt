package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.DEFAULT_TREND_PERIOD_DAYS
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.parsePersonTrend
import io.ktor.http.parametersOf
import io.ktor.server.plugins.BadRequestException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure unit tests of report 20's parser (`PersonTrendSelection.kt`): one person, a from/to period (a year by default), no team. */
class PersonTrendSelectionTest {

    private val calendar = WorkingCalendar(ZoneId.of("UTC"), weekendDays = emptySet(), holidays = emptySet())
    private val now = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun parse(vararg pairs: Pair<String, List<String>>) = parametersOf(*pairs).parsePersonTrend(calendar, now, null)

    @Test
    fun `one person with no team parses to a team-less USER filter on the default year`() {
        val filter = parse("accountId" to listOf("acc-a"))
        assertEquals(ReportLevel.USER, filter.level)
        assertNull(filter.teamId)
        assertEquals(listOf("acc-a"), filter.accountIds)
        assertEquals(DomainView.TASK, filter.domainView)
        val period = assertIs<ReportPeriod.DateRange>(filter.period)
        assertEquals(365, DEFAULT_TREND_PERIOD_DAYS)
        assertEquals(LocalDate.of(2025, 6, 16), period.fromDate, "365 days ending today, today included")
        assertEquals(LocalDate.of(2026, 6, 15), period.toDate)
    }

    @Test
    fun `an explicit range and the common slices pass through, a lone from or to keeps its own default`() {
        val filter = parse(
            "accountId" to listOf("a"), "from" to listOf("2026-01-01"), "to" to listOf("2026-01-31"),
            "domain" to listOf("FLO"), "activityType" to listOf("Bug"), "workCategory" to listOf("UNCATEGORIZED"),
        )
        assertEquals("FLO", filter.domain)
        assertEquals("Bug", filter.activityType)
        assertEquals("UNCATEGORIZED", filter.workCategory)
        assertEquals(LocalDate.of(2026, 1, 1), assertIs<ReportPeriod.DateRange>(filter.period).fromDate)
        // Only `to`: a year ending there (not 90 days).
        val toOnly = assertIs<ReportPeriod.DateRange>(parse("accountId" to listOf("a"), "to" to listOf("2026-03-31")).period)
        assertEquals(LocalDate.of(2025, 4, 1), toOnly.fromDate)
        // The span cap of the shared parser is the report's cap: 1100 days is the most.
        val cap = assertIs<ReportPeriod.DateRange>(
            parse("accountId" to listOf("a"), "from" to listOf("2023-06-01"), "to" to listOf("2026-06-04")).period,
        )
        assertEquals(1100, ChronoUnit.DAYS.between(cap.fromDate, cap.toDate) + 1)
    }

    @Test
    fun `a blank, missing or repeated accountId is 400, a repeat of the same value too`() {
        for (accounts in listOf(listOf(""), listOf(" "), listOf("a", "b"), listOf("a", "a"))) {
            val failure = assertFailsWith<BadRequestException>("accountId=$accounts") { parse("accountId" to accounts) }
            assertTrue(failure.message!!.contains("accountId"), failure.message)
        }
        assertFailsWith<BadRequestException> { parse() }
    }

    @Test
    fun `teamId, lastSprints and sprintId are not accepted`() {
        for (extra in listOf("teamId" to "3", "teamId" to "0", "lastSprints" to "2", "sprintId" to "9")) {
            val failure = assertFailsWith<BadRequestException>(extra.first) {
                parse("accountId" to listOf("a"), extra.first to listOf(extra.second))
            }
            assertTrue(failure.message!!.contains("from/to period only"), failure.message)
        }
    }

    @Test
    fun `the shared parser's own 400s still apply`() {
        val one = "accountId" to listOf("a")
        assertFailsWith<BadRequestException> { parse(one, "from" to listOf("2026-02-01"), "to" to listOf("2026-01-01")) }
        assertFailsWith<BadRequestException> { parse(one, "from" to listOf("2020-01-01"), "to" to listOf("2026-01-01")) }
        assertFailsWith<BadRequestException> { parse(one, "from" to listOf("2026-01-01", "2026-01-02")) }
        assertFailsWith<BadRequestException> { parse(one, "from" to listOf("not-a-date")) }
        assertFailsWith<BadRequestException> { parse(one, "domainView" to listOf("NOPE")) }
    }
}
