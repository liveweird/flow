package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportBreakdown
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.parseReportFilter
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

/**
 * Pure unit tests of `reports/ReportFilter.kt`'s parser — no DB, no route (id existence is
 * validated by each report's own service downstream, never here — see the file's own doc comment).
 */
class ReportFilterTest {

    private val utcCalendar = WorkingCalendar(ZoneId.of("UTC"), weekendDays = emptySet(), holidays = emptySet())

    /** 2026-06-15T00:00:00Z — an arbitrary fixed "now" so the default-window assertions are exact. */
    private val now = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test
    fun `defaults - trailing 90 days ending today, UNIT level, no breakdown`() {
        val filter = parametersOf().parseReportFilter(utcCalendar, now, DomainView.TASK)
        val period = assertIs<ReportPeriod.DateRange>(filter.period)
        assertEquals(LocalDate.of(2026, 6, 15), period.toDate)
        assertEquals(LocalDate.of(2026, 3, 18), period.fromDate) // 90 days inclusive of both ends
        assertEquals(ReportLevel.UNIT, filter.level)
        assertNull(filter.teamId)
        assertNull(filter.accountId)
        assertEquals(DomainView.TASK, filter.domainView)
        assertEquals(ReportBreakdown.NONE, filter.breakdown)
    }

    @Test
    fun `defaultDomainView is per-caller, overridable by the domainView param`() {
        val default = parametersOf().parseReportFilter(utcCalendar, now, DomainView.EPIC)
        assertEquals(DomainView.EPIC, default.domainView)
        val overridden = parametersOf("domainView" to listOf("TASK")).parseReportFilter(utcCalendar, now, DomainView.EPIC)
        assertEquals(DomainView.TASK, overridden.domainView)
    }

    @Test
    fun `explicit from-to resolves to UTC-millis day boundaries, to inclusive`() {
        val params = parametersOf("from" to listOf("2026-01-01"), "to" to listOf("2026-01-03"))
        val filter = params.parseReportFilter(utcCalendar, now, DomainView.TASK)
        val period = assertIs<ReportPeriod.DateRange>(filter.period)
        assertEquals(LocalDate.of(2026, 1, 1), period.fromDate)
        assertEquals(LocalDate.of(2026, 1, 3), period.toDate)
        assertEquals(utcCalendar.dayBoundsMs(LocalDate.of(2026, 1, 1)).first, period.fromMs)
        // toMs is the START of the day AFTER `to` (half-open upper bound, `to` itself included)
        assertEquals(utcCalendar.dayBoundsMs(LocalDate.of(2026, 1, 3)).second, period.toMs)
    }

    @Test
    fun `to before from is 400`() {
        val params = parametersOf("from" to listOf("2026-01-10"), "to" to listOf("2026-01-01"))
        val failure = assertFailsWith<BadRequestException> { params.parseReportFilter(utcCalendar, now, DomainView.TASK) }
        assertTrue(failure.message!!.contains("to must not be before from"))
    }

    @Test
    fun `a span over 1100 days is 400, exactly 1100 days is accepted`() {
        val tooWide = parametersOf("from" to listOf("2020-01-01"), "to" to listOf("2023-06-01"))
        assertFailsWith<BadRequestException> { tooWide.parseReportFilter(utcCalendar, now, DomainView.TASK) }

        val from = LocalDate.of(2020, 1, 1)
        val to = from.plusDays(1099) // 1100 days inclusive of both ends
        val exact = parametersOf("from" to listOf(from.toString()), "to" to listOf(to.toString()))
        val filter = exact.parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertIs<ReportPeriod.DateRange>(filter.period)
    }

    @Test
    fun `a malformed date is 400`() {
        val params = parametersOf("from" to listOf("not-a-date"))
        val failure = assertFailsWith<BadRequestException> { params.parseReportFilter(utcCalendar, now, DomainView.TASK) }
        assertTrue(failure.message!!.contains("from must be an ISO date"))
    }

    @Test
    fun `lastSprints in range parses, out of range is 400`() {
        val ok = parametersOf("lastSprints" to listOf("3")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(ReportPeriod.LastSprints(3), ok.period)

        assertFailsWith<BadRequestException> {
            parametersOf("lastSprints" to listOf("0")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
        assertFailsWith<BadRequestException> {
            parametersOf("lastSprints" to listOf("53")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
        assertFailsWith<BadRequestException> {
            parametersOf("lastSprints" to listOf("abc")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    @Test
    fun `sprintId parses, a non-numeric value is 400`() {
        val ok = parametersOf("sprintId" to listOf("42")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(ReportPeriod.BySprintId(42L), ok.period)
        assertFailsWith<BadRequestException> {
            parametersOf("sprintId" to listOf("nope")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    @Test
    fun `from-to, lastSprints and sprintId are mutually exclusive`() {
        val combos = listOf(
            parametersOf("from" to listOf("2026-01-01"), "lastSprints" to listOf("3")),
            parametersOf("to" to listOf("2026-01-01"), "sprintId" to listOf("1")),
            parametersOf("lastSprints" to listOf("3"), "sprintId" to listOf("1")),
        )
        combos.forEach { params ->
            val failure = assertFailsWith<BadRequestException> { params.parseReportFilter(utcCalendar, now, DomainView.TASK) }
            assertTrue(failure.message!!.contains("mutually exclusive"))
        }
    }

    @Test
    fun `teamId alone is TEAM level, 0 is a valid UNASSIGNED sentinel`() {
        val team = parametersOf("teamId" to listOf("7")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(ReportLevel.TEAM, team.level)
        assertEquals(7u, team.teamId)

        val unassigned = parametersOf("teamId" to listOf("0")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(ReportLevel.TEAM, unassigned.level)
        assertEquals(0u, unassigned.teamId)
    }

    @Test
    fun `teamId plus accountId is USER level`() {
        val filter = parametersOf("teamId" to listOf("7"), "accountId" to listOf("acc-1"))
            .parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(ReportLevel.USER, filter.level)
        assertEquals(7u, filter.teamId)
        assertEquals("acc-1", filter.accountId)
    }

    @Test
    fun `accountId without teamId is 400`() {
        val failure = assertFailsWith<BadRequestException> {
            parametersOf("accountId" to listOf("acc-1")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
        assertTrue(failure.message!!.contains("accountId requires teamId"))
    }

    @Test
    fun `an unknown domainView or breakdown value is 400`() {
        assertFailsWith<BadRequestException> {
            parametersOf("domainView" to listOf("BOGUS")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
        assertFailsWith<BadRequestException> {
            parametersOf("breakdown" to listOf("BOGUS")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    @Test
    fun `a repeated scalar param is 400 - the singleValue rule`() {
        assertFailsWith<BadRequestException> {
            parametersOf("teamId" to listOf("1", "2")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    @Test
    fun `optional filters and breakdown parse through`() {
        val params = parametersOf(
            "domain" to listOf("FLO"),
            "activityType" to listOf("Bug"),
            "workCategory" to listOf("UNCATEGORIZED"),
            "connectionId" to listOf("3"),
            "breakdown" to listOf("DOMAIN"),
        )
        val filter = params.parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals("FLO", filter.domain)
        assertEquals("Bug", filter.activityType)
        assertEquals("UNCATEGORIZED", filter.workCategory)
        assertEquals(3u, filter.connectionId)
        assertEquals(ReportBreakdown.DOMAIN, filter.breakdown)
    }
}
