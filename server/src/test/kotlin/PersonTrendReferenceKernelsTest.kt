package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.CompareCell
import ch.nokillswit.reports.CompareData
import ch.nokillswit.reports.CompareDoneTask
import ch.nokillswit.reports.CompareWip
import ch.nokillswit.reports.CompareWorklog
import ch.nokillswit.reports.MIN_REFERENCE_PEERS
import ch.nokillswit.reports.PeerCellEntry
import ch.nokillswit.reports.PersonTrendReference
import ch.nokillswit.reports.PersonTrendTeam
import ch.nokillswit.reports.TREND_ROWS
import ch.nokillswit.reports.TrendReferenceTeam
import ch.nokillswit.reports.PersonMembership
import ch.nokillswit.reports.hadActivity
import ch.nokillswit.reports.peersOf
import ch.nokillswit.reports.referenceOf
import ch.nokillswit.reports.referencesOf
import ch.nokillswit.reports.referenceTeamOf
import ch.nokillswit.reports.trendWindowOf
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of report 20's team reference (`PersonTrendReference.kt`): the team of a point (mover, overlapping memberships, a gap),
 * its peers and the median with its minimum -- hand-computed, no database. The wiring is `ReportPersonTrendReferenceTest`.
 */
class PersonTrendReferenceKernelsTest {

    private val calendar = WorkingCalendar(ZoneId.of("UTC"), weekendDays = emptySet(), holidays = emptySet())

    /** Jan 1 .. Mar 31 2026: 90 days. */
    private val window = trendWindowOf(LocalDate.parse("2026-03-31"), calendar)

    private fun ms(date: String, hours: Long = 0): Long = calendar.dayBoundsMs(LocalDate.parse(date)).first + hours * 3_600_000L

    private fun membership(team: UInt, from: Long?, to: Long?, account: String = "p") =
        PersonMembership(account, team, from ?: 0L, to)

    @Test
    fun `one open membership is the team of the point`() {
        assertEquals(5u, referenceTeamOf(listOf(membership(5u, null, null)), window, calendar))
    }

    @Test
    fun `a mover compares with the team they belong to on the END day, not the one with more days overall`() {
        // Team 5 for Jan 1 .. Feb 28 (59 days of the window), team 7 from Mar 1 (31 days): the END-day team is the SHORTER stay.
        val mover = listOf(membership(5u, null, ms("2026-03-01")), membership(7u, ms("2026-03-01"), null))
        assertEquals(7u, referenceTeamOf(mover, window, calendar))
        // The window ending on Feb 14 (an earlier point) is still team 5's.
        assertEquals(5u, referenceTeamOf(mover, trendWindowOf(LocalDate.parse("2026-02-14"), calendar), calendar))
    }

    @Test
    fun `several memberships on the end day - the team with the most days in the window wins, then the lowest id`() {
        // The transfer day: team 9 until noon of Mar 31, team 4 from noon. 9 has 90 days in the window, 4 has 1.
        val transfer = listOf(membership(9u, null, ms("2026-03-31", 12)), membership(4u, ms("2026-03-31", 12), null))
        assertEquals(9u, referenceTeamOf(transfer, window, calendar), "more days beats the lower id")
        // Team 3 [..Jan 10), team 9 [Jan 10 .. Mar 31 noon), team 4 [Mar 31 noon ..): 9 has 81 days, 4 has 1; team 3 left the window.
        val chain = listOf(
            membership(3u, null, ms("2026-01-10")), membership(9u, ms("2026-01-10"), ms("2026-03-31", 12)),
            membership(4u, ms("2026-03-31", 12), null),
        )
        assertEquals(9u, referenceTeamOf(chain, window, calendar))
        // A tie on days: two open memberships (the lowest id), whichever order they are listed in.
        val tie = listOf(membership(8u, null, null), membership(2u, null, null))
        assertEquals(2u, referenceTeamOf(tie, window, calendar))
        assertEquals(2u, referenceTeamOf(tie.reversed(), window, calendar))
        // Days count once per team even when the team has two rows of the person (a gap-free re-entry).
        val twoRows = listOf(
            membership(6u, null, ms("2026-02-01")), membership(6u, ms("2026-02-01"), null), membership(1u, ms("2026-03-01"), null),
        )
        assertEquals(6u, referenceTeamOf(twoRows, window, calendar), "team 6: 90 days over two rows; team 1: 31")
    }

    @Test
    fun `a gap on the end day has no team, whatever came before or after`() {
        assertNull(referenceTeamOf(emptyList(), window, calendar), "no membership at all")
        assertNull(referenceTeamOf(listOf(membership(5u, null, ms("2026-03-31"))), window, calendar), "ended at the start of the end day")
        assertNull(referenceTeamOf(listOf(membership(5u, ms("2026-04-01"), null)), window, calendar), "starts the day after")
        val gap = listOf(membership(5u, null, ms("2026-03-10")), membership(6u, ms("2026-04-10"), null))
        assertNull(referenceTeamOf(gap, window, calendar), "left in March, joined in April")
        // A gap INSIDE the window does not matter when the end day is covered; only the days covered count.
        val rejoined = listOf(
            membership(5u, null, ms("2026-02-01")), membership(5u, ms("2026-03-20"), null), membership(6u, ms("2026-02-01"), null),
        )
        assertEquals(6u, referenceTeamOf(rejoined, window, calendar), "team 6: 59 days; team 5: 31 + 12 = 43")
        val earlier = trendWindowOf(LocalDate.parse("2026-01-20"), calendar)
        assertEquals(5u, referenceTeamOf(rejoined, earlier, calendar), "that window: only team 5")
    }

    @Test
    fun `the peers are the other members of the team covering the end day`() {
        val roster = listOf(
            membership(5u, null, null, "me"),
            membership(5u, null, null, "zed"),
            membership(5u, ms("2026-02-01"), null, "amy"),
            membership(5u, null, ms("2026-03-31"), "left"),
            membership(5u, ms("2026-04-01"), null, "late"),
            membership(5u, null, ms("2026-03-31", 12), "transfer-out"),
            membership(6u, null, null, "other-team"),
            membership(5u, null, ms("2026-01-15"), "amy"),
        )
        val peers = peersOf(roster, 5u, "me", window, calendar)
        assertEquals(listOf("amy", "transfer-out", "zed"), peers, "sorted, distinct, never the person")
        assertEquals(listOf("other-team"), peersOf(roster, 6u, "me", window, calendar))
        assertEquals(emptyList(), peersOf(roster, 7u, "me", window, calendar))
    }

    private fun data(
        done: List<CompareDoneTask> = emptyList(),
        worklogs: List<CompareWorklog> = emptyList(),
        wipDays: List<String> = emptyList(),
        held: Map<String, Int> = emptyMap(),
    ) = CompareData(
        minSample = 5, window = window.bounds, calendar = calendar, done = done, cycle = emptyList(), accuracy = emptyList(),
        adjustments = emptyList(), blocked = emptyList(), worklogs = worklogs,
        wip = CompareWip(
            days = wipDays.map(LocalDate::parse), workingDays = wipDays.map(LocalDate::parse), connectionIds = emptyList(),
            dayCounts = mapOf("p" to held.mapKeys { LocalDate.parse(it.key) }), held = emptyMap(), note = null,
        ),
        capacity = null,
    )

    private val aTask = CompareDoneTask(1u, 1L, 0L, "p", null, null, "OWN", "Story", null, null, 0)
    private val aWorklog = CompareWorklog(1u, 1L, 1L, "p", 0L, BigDecimal.ONE, null, false)

    @Test
    fun `a member is active with a delivered task, a worklog or work in progress on any listed day, and not otherwise`() {
        assertFalse(data().hadActivity("p"), "nothing at all")
        assertTrue(data(done = listOf(aTask)).hadActivity("p"))
        assertTrue(data(worklogs = listOf(aWorklog)).hadActivity("p"))
        val days = listOf("2026-01-05", "2026-01-06")
        assertTrue(data(wipDays = days, held = mapOf("2026-01-06" to 2)).hadActivity("p"), "work in progress on one day")
        assertFalse(data(wipDays = days, held = mapOf("2026-01-06" to 0)).hadActivity("p"), "a zero count is not a day of WIP")
        assertFalse(data(wipDays = days, held = mapOf("2026-02-01" to 3)).hadActivity("p"), "a count on a day the window does not list")
        assertFalse(data(wipDays = days).hadActivity("p"), "listed days, nothing held")
    }

    private fun cells(vararg values: Double?) = values.map { CompareCell(it, 10, hidden = false) }

    @Test
    fun `the reference is the median of the peers' shown values, null under the minimum with the count`() {
        assertEquals(3, MIN_REFERENCE_PEERS)
        val three = referenceOf(cells(4.0, 1.0, 2.0))
        assertEquals(2.0, three.value)
        assertEquals(3, three.peers)
        assertEquals(2.5, referenceOf(cells(1.0, 2.0, 3.0, 9.0)).value, "an even count interpolates the two middles")
        val two = referenceOf(cells(1.0, 2.0))
        assertNull(two.value)
        assertEquals(2, two.peers)
        assertEquals(0, referenceOf(emptyList()).peers)
        assertNull(referenceOf(emptyList()).value)
    }

    @Test
    fun `the CODE rows' pool is the peers with a LINKED GitLab identity, every other row's the Jira-active ones`() {
        fun entry(mrs: Double, delivered: Double, active: Boolean, linked: Boolean) = PeerCellEntry.of(
            TREND_ROWS.associate { spec ->
                val value = when (spec.key) {
                    "mrsMerged" -> mrs
                    "deliveredItems" -> delivered
                    else -> 1.0
                }
                spec.key to CompareCell(value, 5, hidden = false)
            },
            active, linked,
        )
        val entries = mapOf(
            // Jira-active but with no link: an unlinked 0 is no fact about merge requests, so the code median leaves them out.
            "po" to entry(mrs = 0.0, delivered = 4.0, active = true, linked = false),
            // Linked with no merge request in the window: a real 0, counted.
            "a" to entry(mrs = 0.0, delivered = 1.0, active = true, linked = true),
            "b" to entry(mrs = 4.0, delivered = 2.0, active = true, linked = true),
            "c" to entry(mrs = 6.0, delivered = 3.0, active = true, linked = true),
            // Linked but with no Jira activity in the window: in the code pool, in no Jira row's.
            "dev" to entry(mrs = 8.0, delivered = 9.0, active = false, linked = true),
        )
        val references = referencesOf(TrendReferenceTeam(PersonTrendTeam(1u, "T"), entries.keys.toList()), entries)!!
        // Code: a, b, c, dev -> [0, 4, 6, 8] -> 5 (with the unlinked 0 it would be 4 over five).
        assertEquals(PersonTrendReference(5.0, 4), references.getValue("mrsMerged"))
        // Jira: po, a, b, c -> [4, 1, 2, 3] -> 2.5; the code-only dev is not a peer of a Jira row.
        assertEquals(PersonTrendReference(2.5, 4), references.getValue("deliveredItems"))
    }

    @Test
    fun `a hidden or valueless peer is left out and does not count`() {
        val mixed = listOf(
            CompareCell(1.0, 10, hidden = false), CompareCell(null, 1, hidden = true), CompareCell(null, 0, hidden = false),
            CompareCell(5.0, 10, hidden = false), CompareCell(3.0, 10, hidden = false),
        )
        val reference = referenceOf(mixed)
        assertEquals(3.0, reference.value)
        assertEquals(3, reference.peers, "two of five contributed nothing")
        val short = referenceOf(mixed.drop(1))
        assertNull(short.value)
        assertEquals(2, short.peers)
        // A count row's zero is a value (Compare people shows 0, never hidden), so a quiet peer pulls the median down.
        assertEquals(0.0, referenceOf(cells(0.0, 0.0, 4.0)).value)
    }
}
