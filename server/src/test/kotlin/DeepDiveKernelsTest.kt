package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.AuthorDayCost
import ch.nokillswit.reports.CostEntry
import ch.nokillswit.reports.DayAmount
import ch.nokillswit.reports.DayFraction
import ch.nokillswit.reports.NoPlanReason
import ch.nokillswit.reports.PlanSource
import ch.nokillswit.reports.SprintWindow
import ch.nokillswit.reports.StageSpan
import ch.nokillswit.reports.TaskPlan
import ch.nokillswit.reports.costDays
import ch.nokillswit.reports.executionDays
import ch.nokillswit.reports.planDays
import ch.nokillswit.reports.spreadCumulative
import ch.nokillswit.reports.taskPlan
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `reports/DeepDiveKernels.kt` (report 17, A29 — `.claude/docs/domain-model.md`, `.claude/docs/measures.md` "Report 17"): pure, no DB.
 * 2026-10-05 is a Monday; 2026-03-29 is Europe/Warsaw's spring-forward day (a 23-hour day). Every expected figure is hand-computed.
 */
class DeepDiveKernelsTest {

    private val utc = ZoneId.of("UTC")
    private val warsaw = ZoneId.of("Europe/Warsaw")
    private val weekdays = WorkingCalendar(utc, setOf(6, 7), emptySet())
    private val origin = LocalDate.of(2026, 10, 5)

    private fun utcMs(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun warsawMs(local: String): Long = LocalDateTime.parse(local).atZone(warsaw).toInstant().toEpochMilli()

    private fun md(value: String) = BigDecimal(value)

    private fun sprint(start: String?, complete: String?, end: String?, estimate: String?) =
        SprintWindow(start?.let(::utcMs), complete?.let(::utcMs), end?.let(::utcMs), estimate?.let(::md))

    private fun TaskPlan.offsets() = pv.map { it.offset }

    private fun TaskPlan.sum() = pv.fold(BigDecimal.ZERO) { acc, day -> acc + day.md }

    // ---- spreadCumulative ----------------------------------------------------------------------

    @Test
    fun `spreadCumulative splits 1 MD over 3 days as 0_33, 0_34, 0_33 so the sum is exact`() {
        // Running: ROUND(1*1/3,2)=0.33, ROUND(1*2/3,2)=0.67, ROUND(1*3/3,2)=1.00 -> differences 0.33, 0.34, 0.33.
        val spread = spreadCumulative(md("1"), listOf("a", "b", "c"))
        assertEquals(listOf("a" to md("0.33"), "b" to md("0.34"), "c" to md("0.33")), spread)
        assertEquals(md("1.00"), spread.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v })
    }

    @Test
    fun `spreadCumulative rounds half up on the running total and handles an empty day list`() {
        // 0.10 over 4: running 0.025->0.03, 0.05, 0.075->0.08, 0.10 -> differences 0.03, 0.02, 0.03, 0.02.
        assertEquals(
            listOf(md("0.03"), md("0.02"), md("0.03"), md("0.02")),
            spreadCumulative(md("0.10"), listOf(1, 2, 3, 4)).map { it.second },
        )
        assertEquals(emptyList(), spreadCumulative(md("1"), emptyList<Int>()))
    }

    // ---- taskPlan ------------------------------------------------------------------------------

    @Test
    fun `taskPlan spreads the estimate over the working days of one sprint and skips the weekend`() {
        // Mon 5 Oct .. Fri 16 Oct = 10 working days (offsets 0-4 and 7-11); 5 MD -> 0.50 a day.
        val plan = taskPlan(listOf(sprint("2026-10-05T09:00:00Z", "2026-10-16T17:00:00Z", "2026-10-19T09:00:00Z", "5")), weekdays, origin)
        assertEquals(PlanSource.EARLIEST, plan.source)
        assertEquals(md("5"), plan.basisMd)
        assertNull(plan.noPlanReason)
        assertEquals(listOf(0, 1, 2, 3, 4, 7, 8, 9, 10, 11), plan.offsets())
        assertTrue(plan.pv.all { it.md == md("0.50") })
        assertEquals(md("5.00"), plan.sum())
    }

    @Test
    fun `taskPlan skips a configured holiday and the cumulative rounding still sums to the estimate`() {
        // Thu 8 Oct is a holiday: Mon-Fri 5-9 and 12-16 leave 9 working days. 1 MD over 9: running 0.11, 0.22, 0.33, 0.44, 0.56, 0.67,
        // 0.78, 0.89, 1.00 -> differences 0.11 x4, 0.12, 0.11 x4 (the 5th day takes the 0.56 round-up).
        val calendar = WorkingCalendar(utc, setOf(6, 7), setOf(LocalDate.of(2026, 10, 8)))
        val plan = taskPlan(listOf(sprint("2026-10-05T09:00:00Z", "2026-10-16T17:00:00Z", null, "1")), calendar, origin)
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9, 10, 11), plan.offsets())
        assertEquals(
            listOf("0.11", "0.11", "0.11", "0.11", "0.12", "0.11", "0.11", "0.11", "0.11").map(::md),
            plan.pv.map { it.md },
        )
        assertEquals(md("1.00"), plan.sum())
    }

    @Test
    fun `taskPlan counts a day shared by two overlapping sprints once and the sum is the estimate`() {
        // S1 Mon 5 .. Fri 9 (complete). S2 Wed 7 .. Tue 13 (no complete, so end_at). Union of working days:
        // 5,6,7,8,9 + 12,13 = 7 days (7, 8 and 9 are in both). 7 MD -> 1.00 a day; S2's own 3 MD is not used.
        val plan = taskPlan(
            listOf(
                sprint("2026-10-05T09:00:00Z", "2026-10-09T17:00:00Z", "2026-10-09T17:00:00Z", "7"),
                sprint("2026-10-07T09:00:00Z", null, "2026-10-13T09:00:00Z", "3"),
            ),
            weekdays,
            origin,
        )
        assertEquals(PlanSource.EARLIEST, plan.source)
        assertEquals(listOf(0, 1, 2, 3, 4, 7, 8), plan.offsets())
        assertTrue(plan.pv.all { it.md == md("1.00") })
        assertEquals(md("7.00"), plan.sum())
    }

    @Test
    fun `taskPlan takes the earliest sprint's estimate, else the first later one, else none`() {
        val window = "2026-10-05T09:00:00Z" to "2026-10-06T17:00:00Z" // Mon-Tue, 2 working days
        fun at(estimate: String?) = sprint(window.first, window.second, null, estimate)

        val earliest = taskPlan(listOf(at("2"), at("9")), weekdays, origin)
        assertEquals(PlanSource.EARLIEST, earliest.source)
        assertEquals(md("2"), earliest.basisMd)

        val fallback = taskPlan(listOf(at(null), at("4"), at("6")), weekdays, origin)
        assertEquals(PlanSource.LATER_FALLBACK, fallback.source)
        assertEquals(md("4"), fallback.basisMd)
        assertEquals(md("4.00"), fallback.sum())

        val none = taskPlan(listOf(at(null), at(null)), weekdays, origin)
        assertEquals(PlanSource.NONE, none.source)
        assertNull(none.basisMd)
        assertEquals(NoPlanReason.NO_ESTIMATE, none.noPlanReason)
        assertEquals(emptyList(), none.pv)
    }

    @Test
    fun `taskPlan says why a task has no plan - no sprint, no estimate, or nowhere to place it`() {
        val never = taskPlan(emptyList(), weekdays, origin)
        assertEquals(NoPlanReason.NEVER_IN_SPRINT, never.noPlanReason)
        assertEquals(PlanSource.NONE, never.source)
        // Sat 10 .. Sun 11 holds no working day: the estimate and its source are kept, the series is empty.
        val weekendOnly = taskPlan(listOf(sprint("2026-10-10T09:00:00Z", "2026-10-11T17:00:00Z", null, "3")), weekdays, origin)
        assertEquals(PlanSource.EARLIEST, weekendOnly.source)
        assertEquals(md("3"), weekendOnly.basisMd)
        assertEquals(NoPlanReason.NO_WORKING_DAY, weekendOnly.noPlanReason)
        assertEquals(emptyList(), weekendOnly.pv)
        // A fallback estimate keeps its LATER_FALLBACK source the same way; sprints with no close at all add no day.
        val undated = taskPlan(listOf(sprint(null, null, null, null), sprint("2026-10-05T09:00:00Z", null, null, "3")), weekdays, origin)
        assertEquals(PlanSource.LATER_FALLBACK, undated.source)
        assertEquals(md("3"), undated.basisMd)
        assertEquals(NoPlanReason.NO_WORKING_DAY, undated.noPlanReason)
    }

    @Test
    fun `taskPlan gives a sprint with no start a one-day window on its close`() {
        // No start_at: the window is the close day alone - complete_at (Wed 7 Oct, offset 2) wins over end_at.
        val byComplete = taskPlan(listOf(sprint(null, "2026-10-07T17:00:00Z", "2026-10-09T17:00:00Z", "2")), weekdays, origin)
        assertEquals(listOf(DayAmount(2, md("2.00"))), byComplete.pv)
        // Only end_at (Thu 8 Oct, offset 3).
        val byEnd = taskPlan(listOf(sprint(null, null, "2026-10-08T09:00:00Z", "1")), weekdays, origin)
        assertEquals(listOf(DayAmount(3, md("1.00"))), byEnd.pv)
    }

    @Test
    fun `taskPlan clamps a window longer than 1100 days to its first 1100 days`() {
        val calendar = WorkingCalendar(utc, emptySet(), emptySet())
        val windows = listOf(sprint("2026-10-05T09:00:00Z", "2030-12-31T17:00:00Z", null, "11"))
        val days = planDays(windows, calendar)
        assertEquals(1100, days.size)
        assertEquals(origin, days.first())
        assertEquals(origin.plusDays(1099), days.last())
        // 11 MD over 1100 days is exactly 0.01 a day, and the series still sums to the estimate.
        val plan = taskPlan(windows, calendar, origin)
        assertEquals(1100, plan.pv.size)
        assertEquals(1099, plan.pv.last().offset)
        assertEquals(md("11.00"), plan.sum())
    }

    @Test
    fun `planDays is the sorted union of working days, each shared day once`() {
        val windows = listOf(
            sprint("2026-10-07T09:00:00Z", "2026-10-13T17:00:00Z", null, null),
            sprint("2026-10-05T09:00:00Z", "2026-10-08T17:00:00Z", null, null),
        )
        assertEquals(
            listOf(5, 6, 7, 8, 9, 12, 13).map { LocalDate.of(2026, 10, it) },
            planDays(windows, weekdays),
        )
    }

    @Test
    fun `taskPlan treats a zero estimate as an estimate with an empty series`() {
        val plan = taskPlan(listOf(sprint("2026-10-05T09:00:00Z", "2026-10-06T17:00:00Z", null, "0")), weekdays, origin)
        assertEquals(PlanSource.EARLIEST, plan.source)
        assertEquals(md("0"), plan.basisMd)
        assertEquals(emptyList(), plan.pv)
    }

    @Test
    fun `taskPlan reads window days in the configured zone - 23_45 UTC on a Sunday is Monday in Warsaw`() {
        // Start Sun 4 Oct 22:30Z = Mon 5 Oct 00:30 Warsaw, complete 23:45Z = 01:45 Warsaw: ONE day (Monday) in Warsaw, while the same
        // instants read in UTC are a Sunday and give no working day at all.
        val sameDay = sprint("2026-10-04T22:30:00Z", "2026-10-04T23:45:00Z", null, "3")
        val inWarsaw = taskPlan(listOf(sameDay), WorkingCalendar(warsaw, setOf(6, 7), emptySet()), origin)
        assertEquals(listOf(DayAmount(0, md("3.00"))), inWarsaw.pv)
        assertEquals(NoPlanReason.NO_WORKING_DAY, taskPlan(listOf(sameDay), weekdays, origin).noPlanReason)
    }

    @Test
    fun `taskPlan reads a window straddling the Warsaw spring-forward hour as one day`() {
        // 00:30Z = 01:30 CET and 01:30Z = 03:30 CEST (the clocks jumped at 01:00Z): the same Sunday 29 Mar, one day, all of the estimate.
        val calendar = WorkingCalendar(warsaw, emptySet(), emptySet())
        val plan = taskPlan(listOf(sprint("2026-03-29T00:30:00Z", "2026-03-29T01:30:00Z", null, "1")), calendar, LocalDate.of(2026, 3, 29))
        assertEquals(listOf(DayAmount(0, md("1.00"))), plan.pv)
    }

    @Test
    fun `taskPlan counts calendar days across the Warsaw daylight-saving change`() {
        // Sat 28 Mar 10:00 .. Mon 30 Mar 10:00 Warsaw, no weekend: 28, 29 (the 23-hour day), 30 = 3 days; 1 MD -> 0.33, 0.34, 0.33.
        val calendar = WorkingCalendar(warsaw, emptySet(), emptySet())
        val window = SprintWindow(warsawMs("2026-03-28T10:00:00"), warsawMs("2026-03-30T10:00:00"), null, md("1"))
        val plan = taskPlan(listOf(window), calendar, LocalDate.of(2026, 3, 28))
        assertEquals(listOf(DayAmount(0, md("0.33")), DayAmount(1, md("0.34")), DayAmount(2, md("0.33"))), plan.pv)
    }

    // ---- executionDays -------------------------------------------------------------------------

    private fun sumOf(days: List<DayFraction>) = days.sumOf { it.taskDays }

    @Test
    fun `executionDays skips the weekend and a holiday and the sum equals workingDaysBetween`() {
        // Fri 9 Oct 12:00 .. Mon 12 Oct 12:00: Friday half (0.5), Sat and Sun none, Monday half (0.5) = 1.0.
        val from = utcMs("2026-10-09T12:00:00Z")
        val to = utcMs("2026-10-12T12:00:00Z")
        val days = executionDays(listOf(StageSpan(from, to)), utcMs("2026-10-20T00:00:00Z"), weekdays, origin)
        assertEquals(listOf(4, 7), days.map { it.offset })
        assertEquals(listOf(0.5, 0.5), days.map { it.taskDays })
        assertEquals(weekdays.workingDaysBetween(from, to), sumOf(days), 1e-9)

        // Make Monday a holiday: only Friday's half remains.
        val withHoliday = WorkingCalendar(utc, setOf(6, 7), setOf(LocalDate.of(2026, 10, 12)))
        val holidayDays = executionDays(listOf(StageSpan(from, to)), utcMs("2026-10-20T00:00:00Z"), withHoliday, origin)
        assertEquals(listOf(4), holidayDays.map { it.offset })
        assertEquals(0.5, holidayDays.single().taskDays, 1e-9)
    }

    @Test
    fun `executionDays gives a partial day its covered fraction and adds spans that share a day`() {
        // Tue 6 Oct 06:00-12:00 = 6h/24h = 0.25; 18:00 to Wed 00:00 = 0.25 -> 0.5 on offset 1.
        val spans = listOf(
            StageSpan(utcMs("2026-10-06T06:00:00Z"), utcMs("2026-10-06T12:00:00Z")),
            StageSpan(utcMs("2026-10-06T18:00:00Z"), utcMs("2026-10-07T00:00:00Z")),
        )
        val days = executionDays(spans, utcMs("2026-10-20T00:00:00Z"), weekdays, origin)
        assertEquals(listOf(1), days.map { it.offset })
        assertEquals(0.5, days.single().taskDays, 1e-9)
    }

    @Test
    fun `executionDays cuts an open span at the derive clock and ignores one that starts after it`() {
        // Open from Tue 6 Oct 18:00, clock Wed 7 Oct 12:00: Tuesday 6h/24h = 0.25, Wednesday 12h/24h = 0.5.
        val from = utcMs("2026-10-06T18:00:00Z")
        val clock = utcMs("2026-10-07T12:00:00Z")
        val days = executionDays(listOf(StageSpan(from, null)), clock, weekdays, origin)
        assertEquals(listOf(1, 2), days.map { it.offset })
        assertEquals(listOf(0.25, 0.5), days.map { it.taskDays })
        assertEquals(weekdays.workingDaysBetween(from, clock), sumOf(days), 1e-9)

        assertEquals(emptyList(), executionDays(listOf(StageSpan(clock, null)), from, weekdays, origin))
        assertEquals(emptyList(), executionDays(listOf(StageSpan(from, from)), clock, weekdays, origin))
    }

    @Test
    fun `executionDays uses the real length of the Warsaw spring-forward day`() {
        val calendar = WorkingCalendar(warsaw, emptySet(), emptySet())
        val dstOrigin = LocalDate.of(2026, 3, 28)
        // Sat 28 Mar 18:00 CET (17:00Z) .. Sun 29 Mar 12:00 CEST (10:00Z): Saturday 6h of 24h = 0.25; the 29th starts 00:00 CET
        // (23:00Z) and is 23h long, so 11h covered = 11/23.
        val from = warsawMs("2026-03-28T18:00:00")
        val to = warsawMs("2026-03-29T12:00:00")
        val days = executionDays(listOf(StageSpan(from, to)), to, calendar, dstOrigin)
        assertEquals(listOf(0, 1), days.map { it.offset })
        assertEquals(0.25, days[0].taskDays, 1e-9)
        assertEquals(11.0 / 23.0, days[1].taskDays, 1e-9)
        assertEquals(calendar.workingDaysBetween(from, to), sumOf(days), 1e-9)

        // The whole 23-hour day is exactly one working day, never 23/24.
        val whole = executionDays(
            listOf(StageSpan(warsawMs("2026-03-29T00:00:00"), warsawMs("2026-03-30T00:00:00"))),
            to,
            calendar,
            dstOrigin,
        )
        assertEquals(listOf(1), whole.map { it.offset })
        assertEquals(1.0, whole.single().taskDays, 1e-9)
    }

    // ---- costDays ------------------------------------------------------------------------------

    @Test
    fun `costDays buckets by the configured zone - 23_30 UTC lands on the next Warsaw day`() {
        val calendar = WorkingCalendar(warsaw, setOf(6, 7), emptySet())
        val worklogs = listOf(
            // 21:00Z Mon 5 Oct = 23:00 Warsaw (CEST) Mon 5 Oct -> offset 0.
            CostEntry(utcMs("2026-10-05T21:00:00Z"), "alice", md("0.50")),
            // 23:30Z Mon 5 Oct = 01:30 Warsaw Tue 6 Oct -> offset 1 (UTC would say offset 0).
            CostEntry(utcMs("2026-10-05T23:30:00Z"), "alice", md("0.50")),
            CostEntry(utcMs("2026-10-06T09:00:00Z"), "alice", md("0.25")),
            CostEntry(utcMs("2026-10-06T10:00:00Z"), "bob", md("1.00")),
            CostEntry(utcMs("2026-10-06T11:00:00Z"), null, md("0.10")),
            // Sat 10 Oct is kept: cost is what was logged, not what was planned.
            CostEntry(utcMs("2026-10-10T10:00:00Z"), "bob", md("0.75")),
        )
        assertEquals(
            listOf(
                AuthorDayCost(0, "alice", md("0.50")),
                AuthorDayCost(1, null, md("0.10")),
                AuthorDayCost(1, "alice", md("0.75")),
                AuthorDayCost(1, "bob", md("1.00")),
                AuthorDayCost(5, "bob", md("0.75")),
            ),
            costDays(worklogs, calendar, origin),
        )
        // The same instants read in UTC put the 23:30Z worklog on offset 0, with the 21:00Z one.
        assertEquals(md("1.00"), costDays(worklogs, weekdays, origin).first { it.author == "alice" && it.offset == 0 }.md)
    }

    @Test
    fun `costDays is empty without worklogs and a day before the origin has a negative offset`() {
        assertEquals(emptyList(), costDays(emptyList(), weekdays, origin))
        assertEquals(
            listOf(AuthorDayCost(-2, "a", md("1.00"))),
            costDays(listOf(CostEntry(utcMs("2026-10-03T10:00:00Z"), "a", md("1.00"))), weekdays, origin),
        )
    }
}
