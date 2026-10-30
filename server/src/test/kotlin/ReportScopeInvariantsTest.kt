package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ProgressTarget
import ch.nokillswit.reports.ReportScopeRule
import ch.nokillswit.reports.SnapshotScope
import ch.nokillswit.reports.parseReportFilter
import ch.nokillswit.reports.progressTargetOf
import ch.nokillswit.reports.snapshotScopeOf
import io.ktor.http.parametersOf
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The invariants behind the reports scope guard (`reports.md` "Scope"): the report readers that map a filter to a scope never see a
 * request naming none, so they hold no whole-unit branch. Pure unit tests; a filter that skipped the guard
 * ([ReportScopeRule.UNSCOPED], Home's own default-period filter) is refused instead of read as the whole unit.
 */
class ReportScopeInvariantsTest {

    private val calendar = WorkingCalendar(ZoneId.of("UTC"), weekendDays = emptySet(), holidays = emptySet())
    private val now = 1_780_000_000_000L

    private fun filter(vararg query: Pair<String, List<String>>) =
        parametersOf(*query).parseReportFilter(calendar, now, DomainView.TASK, null, scope = ReportScopeRule.UNSCOPED)

    @Test
    fun `the snapshot scope is the domain, UNASSIGNED or the team, and a filter naming neither is refused`() {
        assertEquals(SnapshotScope.Domain("FLO"), snapshotScopeOf(filter("domain" to listOf("FLO"))))
        assertEquals(SnapshotScope.Unassigned, snapshotScopeOf(filter("teamId" to listOf("0"))))
        assertEquals(SnapshotScope.Team(7u), snapshotScopeOf(filter("teamId" to listOf("7"))))
        assertFailsWith<IllegalArgumentException> { snapshotScopeOf(filter()) }
    }

    @Test
    fun `the epic-progress target is the epic, the domain or the team, and a filter naming none is refused`() {
        val epicView = "domainView" to listOf("EPIC")
        assertEquals(ProgressTarget.Epic("FLO-1"), progressTargetOf(filter(epicView), "FLO-1"))
        assertEquals(ProgressTarget.Domain("FLO"), progressTargetOf(filter("domain" to listOf("FLO"), epicView), null))
        assertEquals(ProgressTarget.Team(0u), progressTargetOf(filter("teamId" to listOf("0"), epicView), null))
        assertFailsWith<IllegalArgumentException> { progressTargetOf(filter(epicView), null) }
    }
}
