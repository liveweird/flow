package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.ReportScopeRule
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

    /** The scope of a UNIT-level parse: a `domain` with no `teamId` (a report is read per team or domain, never the whole unit). */
    private val floScope = "domain" to listOf("FLO")

    private val utcCalendar = WorkingCalendar(ZoneId.of("UTC"), weekendDays = emptySet(), holidays = emptySet())

    /** 2026-06-15T00:00:00Z — an arbitrary fixed "now" so the default-window assertions are exact. */
    private val now = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test
    fun `defaults - trailing 90 days ending today, UNIT level via a domain`() {
        val filter = parametersOf(floScope).parseReportFilter(utcCalendar, now, DomainView.TASK)
        val period = assertIs<ReportPeriod.DateRange>(filter.period)
        assertEquals(LocalDate.of(2026, 6, 15), period.toDate)
        assertEquals(LocalDate.of(2026, 3, 18), period.fromDate) // 90 days inclusive of both ends
        assertEquals(ReportLevel.UNIT, filter.level)
        assertNull(filter.teamId)
        assertEquals("FLO", filter.domain)
        assertNull(filter.accountId)
        assertEquals(DomainView.TASK, filter.domainView)
    }

    @Test
    fun `defaultDomainView is per-caller, overridable by the domainView param`() {
        val default = parametersOf(floScope).parseReportFilter(utcCalendar, now, DomainView.EPIC)
        assertEquals(DomainView.EPIC, default.domainView)
        val overridden = parametersOf(floScope, "domainView" to listOf("TASK")).parseReportFilter(utcCalendar, now, DomainView.EPIC)
        assertEquals(DomainView.TASK, overridden.domainView)
    }

    @Test
    fun `explicit from-to resolves to UTC-millis day boundaries, to inclusive`() {
        val params = parametersOf(floScope, "from" to listOf("2026-01-01"), "to" to listOf("2026-01-03"))
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
        val params = parametersOf(floScope, "from" to listOf("2026-01-10"), "to" to listOf("2026-01-01"))
        val failure = assertFailsWith<BadRequestException> { params.parseReportFilter(utcCalendar, now, DomainView.TASK) }
        assertTrue(failure.message!!.contains("to must not be before from"))
    }

    @Test
    fun `a span over 1100 days is 400, exactly 1100 days is accepted`() {
        val tooWide = parametersOf(floScope, "from" to listOf("2020-01-01"), "to" to listOf("2023-06-01"))
        assertFailsWith<BadRequestException> { tooWide.parseReportFilter(utcCalendar, now, DomainView.TASK) }

        val from = LocalDate.of(2020, 1, 1)
        val to = from.plusDays(1099) // 1100 days inclusive of both ends
        val exact = parametersOf(floScope, "from" to listOf(from.toString()), "to" to listOf(to.toString()))
        val filter = exact.parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertIs<ReportPeriod.DateRange>(filter.period)
    }

    @Test
    fun `a malformed date is 400`() {
        val params = parametersOf(floScope, "from" to listOf("not-a-date"))
        val failure = assertFailsWith<BadRequestException> { params.parseReportFilter(utcCalendar, now, DomainView.TASK) }
        assertTrue(failure.message!!.contains("from must be an ISO date"))
    }

    @Test
    fun `lastSprints in range parses, out of range is 400`() {
        val ok = parametersOf(floScope, "lastSprints" to listOf("3")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(ReportPeriod.LastSprints(3), ok.period)

        assertFailsWith<BadRequestException> {
            parametersOf(floScope, "lastSprints" to listOf("0")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
        assertFailsWith<BadRequestException> {
            parametersOf(floScope, "lastSprints" to listOf("53")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
        assertFailsWith<BadRequestException> {
            parametersOf(floScope, "lastSprints" to listOf("abc")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    @Test
    fun `sprintId parses, a non-numeric value is 400`() {
        val ok = parametersOf(floScope, "sprintId" to listOf("42")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(ReportPeriod.BySprintId(42L), ok.period)
        assertFailsWith<BadRequestException> {
            parametersOf(floScope, "sprintId" to listOf("nope")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    @Test
    fun `sprintId below 1 is 400 - the spec's minimum`() {
        for (value in listOf("0", "-1", "-9223372036854775808")) {
            val failure = assertFailsWith<BadRequestException> {
                parametersOf(floScope, "sprintId" to listOf(value)).parseReportFilter(utcCalendar, now, DomainView.TASK)
            }
            assertTrue(failure.message!!.contains("sprintId must be at least 1"), value)
        }
        val one = parametersOf(floScope, "sprintId" to listOf("1")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(ReportPeriod.BySprintId(1L), one.period)
    }

    @Test
    fun `from-to, lastSprints and sprintId are mutually exclusive`() {
        val combos = listOf(
            parametersOf(floScope, "from" to listOf("2026-01-01"), "lastSprints" to listOf("3")),
            parametersOf(floScope, "to" to listOf("2026-01-01"), "sprintId" to listOf("1")),
            parametersOf(floScope, "lastSprints" to listOf("3"), "sprintId" to listOf("1")),
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
    fun `a repeated accountId is 400 unless the caller opts in, then 1 to 8 distinct values in request order`() {
        val repeated = parametersOf("teamId" to listOf("7"), "accountId" to listOf("b", "a", "b"))
        assertFailsWith<BadRequestException> { repeated.parseReportFilter(utcCalendar, now, DomainView.TASK) }

        val filter = repeated.parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true)
        assertEquals(ReportLevel.USER, filter.level)
        assertEquals(listOf("b", "a"), filter.accountIds)
        assertEquals("b", filter.accountId)

        val single = parametersOf("teamId" to listOf("7"), "accountId" to listOf("acc-1"))
            .parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals(listOf("acc-1"), single.accountIds)
        val teamOnly = parametersOf("teamId" to listOf("7")).parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true)
        assertEquals(emptyList(), teamOnly.accountIds)

        fun many(count: Int) = parametersOf("teamId" to listOf("7"), "accountId" to (1..count).map { "acc-$it" })
        assertEquals(8, many(8).parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true).accountIds.size)
        assertFailsWith<BadRequestException> { many(9).parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true) }
        assertFailsWith<BadRequestException> {
            parametersOf("accountId" to listOf("a", "b")).parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true)
        }
    }

    @Test
    fun `accountId is derived from accountIds, so a copy can never leave the two disagreeing`() {
        val filter = parametersOf("teamId" to listOf("7"), "accountId" to listOf("b", "a"))
            .parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true)
        assertEquals("b", filter.accountId)
        assertNull(filter.copy(accountIds = emptyList()).accountId)
        assertEquals("c", filter.copy(accountIds = listOf("c", "a")).accountId)
        assertEquals("b", filter.copy(domain = "FLO").accountId)
    }

    @Test
    fun `accountId without teamId is 400`() {
        val failure = assertFailsWith<BadRequestException> {
            parametersOf("accountId" to listOf("acc-1")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
        assertTrue(failure.message!!.contains("accountId requires teamId"))
    }

    @Test
    fun `teamless lifts accountId requires teamId - USER level with no team - and only when asked`() {
        val params = parametersOf("accountId" to listOf("acc-1", "acc-2"))
        val filter = params.parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true, teamless = true)
        assertEquals(ReportLevel.USER, filter.level)
        assertNull(filter.teamId)
        assertEquals(listOf("acc-1", "acc-2"), filter.accountIds)
        // Without the flag the very same query is still 400, with it or without multipleAccounts.
        assertFailsWith<BadRequestException> { params.parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true) }
        assertFailsWith<BadRequestException> {
            parametersOf("accountId" to listOf("acc-1")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
        // A team still wins if one is given, and no account stays UNIT/TEAM as ever.
        assertEquals(
            ReportLevel.USER,
            parametersOf("accountId" to listOf("a"), "teamId" to listOf("3"))
                .parseReportFilter(utcCalendar, now, DomainView.TASK, multipleAccounts = true, teamless = true).level,
        )
        assertEquals(ReportLevel.UNIT, parametersOf(floScope).parseReportFilter(utcCalendar, now, DomainView.TASK, teamless = true).level)
    }

    @Test
    fun `an unknown domainView value is 400`() {
        assertFailsWith<BadRequestException> {
            parametersOf(floScope, "domainView" to listOf("BOGUS")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    @Test
    fun `the removed breakdown param is ignored like any unknown param name - never a 400`() {
        // list-endpoints.md: unknown parameter names stay ignored by deliberate leniency. `breakdown` was parsed but
        // changed nothing in any report; it was removed until a report actually slices `groups` by it.
        val plain = parametersOf(floScope).parseReportFilter(utcCalendar, now, DomainView.TASK)
        for (value in listOf("DOMAIN", "NONE", "BOGUS")) {
            val withBreakdown = parametersOf(floScope, "breakdown" to listOf(value)).parseReportFilter(utcCalendar, now, DomainView.TASK)
            assertEquals(plain, withBreakdown, value)
        }
    }

    @Test
    fun `a repeated scalar param is 400 - the singleValue rule`() {
        assertFailsWith<BadRequestException> {
            parametersOf("teamId" to listOf("1", "2")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    @Test
    fun `optional filters parse through`() {
        val params = parametersOf(
            "domain" to listOf("FLO"),
            "activityType" to listOf("Bug"),
            "workCategory" to listOf("UNCATEGORIZED"),
            "connectionId" to listOf("3"),
        )
        val filter = params.parseReportFilter(utcCalendar, now, DomainView.TASK)
        assertEquals("FLO", filter.domain)
        assertEquals("Bug", filter.activityType)
        assertEquals("UNCATEGORIZED", filter.workCategory)
        assertEquals(3u, filter.connectionId)
    }

    @Test
    fun `tailPercent defaults to 5, takes 0 to 25 and anything else is 400`() {
        assertEquals(5, parametersOf(floScope).parseReportFilter(utcCalendar, now, DomainView.TASK).tailPercent)
        for (ok in listOf(0, 1, 5, 25)) {
            val parsed = parametersOf(floScope, "tailPercent" to listOf("$ok")).parseReportFilter(utcCalendar, now, DomainView.TASK)
            assertEquals(ok, parsed.tailPercent)
        }
        for (bad in listOf("26", "-1", "x", "2.5", "100")) {
            assertFailsWith<BadRequestException>(bad) {
                parametersOf(floScope, "tailPercent" to listOf(bad)).parseReportFilter(utcCalendar, now, DomainView.TASK)
            }
        }
        assertFailsWith<BadRequestException> {
            parametersOf(floScope, "tailPercent" to listOf("5", "6")).parseReportFilter(utcCalendar, now, DomainView.TASK)
        }
    }

    // The scope guard (CLAUDE.md: a report is read per team or per domain, never for the whole unit).

    private fun refusal(rule: ReportScopeRule, vararg query: Pair<String, List<String>>, teamless: Boolean = false): String? = try {
        parametersOf(*query).parseReportFilter(utcCalendar, now, DomainView.TASK, teamless = teamless, scope = rule)
        null
    } catch (failure: BadRequestException) {
        failure.message
    }

    @Test
    fun `TEAM_OR_DOMAIN refuses a request naming neither, accepts a team (0 included) or a domain`() {
        val rule = ReportScopeRule.TEAM_OR_DOMAIN
        val refused = "teamId or domain is required: a report is read per team or per domain, never for the whole unit"
        assertEquals(refused, refusal(rule))
        assertEquals(refused, refusal(rule, "from" to listOf("2026-01-01")), "the period alone is no scope")
        assertEquals(refused, refusal(rule, "domain" to listOf("")), "a blank domain is absent")
        assertEquals(refused, refusal(rule, "domain" to listOf("  ")), "a blank domain is absent")
        assertEquals(refused, refusal(rule, "epicId" to listOf("FLO-1")), "an epic is no scope here")
        assertEquals(refused, refusal(rule, "connectionId" to listOf("3")), "a connection is no scope")
        assertNull(refusal(rule, "teamId" to listOf("3")))
        assertNull(refusal(rule, "teamId" to listOf("0")), "0 is the UNASSIGNED bucket, a real team value")
        assertNull(refusal(rule, "domain" to listOf("FLO")))
        assertNull(refusal(rule, "domain" to listOf("FLO"), "teamId" to listOf("3")))
    }

    @Test
    fun `TEAM_ONLY wants a team and a domain is no scope`() {
        val rule = ReportScopeRule.TEAM_ONLY
        val refused = "teamId is required: this report carries no domain slice and is read per team, never for the whole unit"
        assertEquals(refused, refusal(rule))
        assertEquals(refused, refusal(rule, "domain" to listOf("FLO")), "a domain slices nothing on these reports")
        assertEquals(refused, refusal(rule, "epicId" to listOf("FLO-1")))
        assertNull(refusal(rule, "teamId" to listOf("3")))
        assertNull(refusal(rule, "teamId" to listOf("0")))
        assertNull(refusal(rule, "teamId" to listOf("3"), "domain" to listOf("FLO")))
    }

    @Test
    fun `TEAM_DOMAIN_OR_EPIC takes an epic, a domain or a team, an epic by raw presence`() {
        val rule = ReportScopeRule.TEAM_DOMAIN_OR_EPIC
        val refused =
            "epicId, domain or teamId is required: epic progress is read per epic, per domain or per team, never for the whole unit"
        assertEquals(refused, refusal(rule))
        assertEquals(refused, refusal(rule, "domain" to listOf(" ")))
        assertNull(refusal(rule, "epicId" to listOf("FLO-1")))
        assertNull(refusal(rule, "domain" to listOf("FLO")))
        assertNull(refusal(rule, "teamId" to listOf("0")))
        // A blank epicId is present: the guard lets it through so the route keeps its own "epicId must not be blank".
        assertNull(refusal(rule, "epicId" to listOf("")))
        // A repeated epicId is the shared singleValue 400, not a scope refusal.
        assertEquals("Parameter 'epicId' must not be repeated", refusal(rule, "epicId" to listOf("A-1", "B-2")))
    }

    @Test
    fun `a teamless person is a scope, a teamless request without one is not, UNSCOPED lifts the guard`() {
        val person = "accountId" to listOf("acc-1")
        assertNull(refusal(ReportScopeRule.TEAM_OR_DOMAIN, person, teamless = true))
        assertEquals(
            "teamId or domain is required: a report is read per team or per domain, never for the whole unit",
            refusal(ReportScopeRule.TEAM_OR_DOMAIN, teamless = true),
        )
        assertNull(refusal(ReportScopeRule.UNSCOPED))
        val unscoped = parametersOf().parseReportFilter(utcCalendar, now, DomainView.TASK, scope = ReportScopeRule.UNSCOPED)
        assertEquals(ReportLevel.UNIT, unscoped.level)
    }

    @Test
    fun `the scope guard follows the period checks and accountId requires teamId, and precedes tailPercent`() {
        val rule = ReportScopeRule.TEAM_OR_DOMAIN
        // A bad period is reported first, whatever the scope.
        assertEquals("from/to span must not exceed 1100 days", refusal(rule, "from" to listOf("2020-01-01"), "to" to listOf("2026-01-01")))
        // accountId without a team keeps its own message (the team is the missing piece).
        assertEquals("accountId requires teamId", refusal(rule, "accountId" to listOf("acc-1"), "domain" to listOf("FLO")))
        // The scope precedes the later structural checks.
        val refused = refusal(rule, "tailPercent" to listOf("99"))
        assertTrue(refused!!.startsWith("teamId or domain is required"), refused)
    }
}
