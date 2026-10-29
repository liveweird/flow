package ch.nokillswit

import ch.nokillswit.reports.HeldTask
import ch.nokillswit.reports.WipTaskDay
import ch.nokillswit.reports.WipTaskDaysRequest
import ch.nokillswit.reports.readWipUserDayCounts
import ch.nokillswit.reports.readWipUserHeld
import ch.nokillswit.reports.readWipUserTaskCounts
import ch.nokillswit.reports.readWipUserTaskDays
import io.ktor.server.testing.testApplication
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

/**
 * Compare-people's WIP figures are aggregated in Postgres (`wipUserDayCountsSql`, `wipUserTaskCountsSql`: one row per day and person /
 * per task, not one object per task per day; `wipUserHeldSql` lists the tasks of the listed days, WHY 47). The old way --
 * every (day, task, person) row of `wipUserTaskDaysSql` expanded in the JVM and counted there -- is kept HERE as the oracle: the
 * set-based reads must equal it, over a hand-built world (assignee changes mid-interval and exactly at a day end, intervals crossing
 * both period edges, a null-assignee gap, weekends and holidays through the period choice, one to three people) and over the derived
 * stub fixture. `ReportComparePeopleFixtureTest` grades the report's cells over the same fixture with independent SQL; the
 * JSON-level golden comparison is recorded in `build-times.md` WHY 28.
 */
class CompareWipPushdownTest {

    private fun request(connId: UInt, accounts: List<String>, first: String, last: String) =
        WipTaskDaysRequest(listOf(connId), accounts, first, last)

    /** The old expansion: every task-day row, counted in Kotlin. */
    private suspend fun expanded(request: WipTaskDaysRequest): List<WipTaskDay> =
        suspendTransaction(sharedDatabaseForTests()) { readWipUserTaskDays(request) }

    /** The per-day counts statement alone. */
    private suspend fun dayCounts(request: WipTaskDaysRequest): Map<String, Map<LocalDate, Int>> =
        suspendTransaction(sharedDatabaseForTests()) { readWipUserDayCounts(request) }

    /**
     * The held arrays of every listed day equal the expansion's rows of that day, each with its `fact_task_delivery.started_at` (looked
     * up by plain SQL; a task without a fact row is `null`), and no other day carries a task. Returns the number of tasks compared.
     */
    private suspend fun assertHeldEqualsExpansion(request: WipTaskDaysRequest, listed: List<LocalDate>, label: String): Int {
        val expansion = expanded(request)
        val startedAt = expansion.map { it.connectionId to it.issueId }.distinct().associateWith { (conn, issue) ->
            jdbcRows(
                "SELECT started_at FROM metrics.fact_task_delivery WHERE connection_id = ? AND issue_id = ?", conn.toInt(), issue,
            ).singleOrNull()?.get(0) as Long?
        }
        val listedIso = listed.map { it.toString() }.toSet()
        fun List<HeldTask>.ordered() = sortedWith(compareBy({ it.connectionId }, { it.issueId }))
        val want = expansion.filter { it.day in listedIso }.groupBy { it.day }.mapValues { (_, rows) ->
            rows.groupBy { it.accountId }.mapValues { (_, held) ->
                held.map { HeldTask(it.connectionId, it.issueId, startedAt.getValue(it.connectionId to it.issueId)) }.ordered()
            }
        }
        val read = suspendTransaction(sharedDatabaseForTests()) { readWipUserHeld(request, listed) }
        val got = read.held.mapValues { (_, byAccount) -> byAccount.mapValues { it.value.ordered() } }
        assertEquals(want, got, "$label: the held arrays of the listed days")
        val counts = expansion.groupBy { it.accountId }.mapValues { (_, rows) -> rows.groupingBy { LocalDate.parse(it.day) }.eachCount() }
        assertEquals(counts, read.dayCounts, "$label: the combined read returns the same counts")
        return want.values.sumOf { byAccount -> byAccount.values.sumOf { it.size } }
    }

    private suspend fun assertSameAsExpansion(request: WipTaskDaysRequest, workingDays: List<LocalDate>, label: String) {
        val old = expanded(request)
        val oldDayCounts = old.groupBy { it.accountId }
            .mapValues { (_, rows) -> rows.groupingBy { LocalDate.parse(it.day) }.eachCount() }
        assertEquals(oldDayCounts, dayCounts(request), "$label: per-day counts")
        assertHeldEqualsExpansion(request, listOf(LocalDate.parse(request.lastDay)), "$label: last day")

        val workingSet = workingDays.map { it.toString() }.toSet()
        for (account in request.accountIds) {
            val oldTaskCounts = old.filter { it.accountId == account && it.day in workingSet }
                .groupBy { it.connectionId to it.issueId }.mapValues { (_, rows) -> rows.size }
            val newTaskCounts = suspendTransaction(sharedDatabaseForTests()) {
                readWipUserTaskCounts(request.copy(accountIds = listOf(account)), workingDays)
            }
            assertEquals(oldTaskCounts, newTaskCounts, "$label: per-task counts of $account")
        }
        val lastRows = expanded(request.copy(firstDay = request.lastDay))
        assertEquals(old.filter { it.day == request.lastDay }.toSet(), lastRows.toSet(), "$label: the tasks held at the last day")
    }

    @Test
    fun `hand-built world - the set-based reads equal the expansion for every period and pick`() = testApplication {
        usePostgresTestcontainer()
        withCompareWipWorld { w ->
            val periods = listOf(
                "2026-01-01" to "2026-01-31", "2026-01-10" to "2026-01-20", "2026-01-20" to "2026-01-31", "2025-11-01" to "2025-11-30",
                "2025-12-15" to "2026-01-05", "2026-01-14" to "2026-01-14", "2026-01-15" to "2026-01-15",
            )
            for ((first, last) in periods) {
                val days = generateSequence(LocalDate.parse(first)) { it.plusDays(1) }.takeWhile { it <= LocalDate.parse(last) }.toList()
                // Weekends and the January holidays are left out as the report's calendar would: the per-task figure counts working days.
                val working = days.filter { it.dayOfWeek.value < 6 && it.toString() !in setOf("2026-01-01", "2026-01-06") }
                for (accounts in listOf(listOf(w.a1, w.a2), listOf(w.a2, w.a3), listOf(w.a1, w.a2, w.a3), listOf(w.a1))) {
                    assertSameAsExpansion(request(w.connId, accounts, first, last), working, "$first..$last ${accounts.size} picks")
                }
            }
        }
    }

    @Test
    fun `hand-built world - the counts match the hand-computed answers`() = testApplication {
        usePostgresTestcontainer()
        withCompareWipWorld { w ->
            val counts = dayCounts(request(w.connId, listOf(w.a1, w.a2), "2026-01-09", "2026-01-31"))
            fun count(account: String, day: String): Int = counts[account].orEmpty()[LocalDate.parse(day)] ?: 0
            // Jan 9: a1 holds task 1 (WAITING) and task 5; task 4 is still NOT_STARTED. a2 holds task 3 (BLOCKED).
            assertEquals(2, count(w.a1, "2026-01-09"))
            assertEquals(1, count(w.a2, "2026-01-09"))
            // Jan 14: the reassignment of task 2 sits exactly at this day end and counts for a1 (with tasks 1 and 5); task 4 is in its
            // unassigned gap. Jan 15: task 2 is a2's.
            assertEquals(3, count(w.a1, "2026-01-14"))
            assertEquals(2, count(w.a1, "2026-01-15"))
            assertEquals(2, count(w.a2, "2026-01-15"))
            // Jan 20: task 1 is DONE by this day end and task 5 moved to a2 at noon; a1 keeps task 4 (assigned again since the 16th).
            assertEquals(1, count(w.a1, "2026-01-20"))
            assertEquals(3, count(w.a2, "2026-01-20"))
            // The sub-task (6), the UNMAPPED (9) and DONE (10) tasks never count, whatever the day.
            assertTrue(counts.values.all { byDay -> byDay.values.all { it <= 4 } }, "no more than the four WIP tasks per person: $counts")
            assertTrue(count(w.a1, "2026-01-31") == 1 && count(w.a2, "2026-01-31") == 2, "the open intervals reach the last day: $counts")
        }
    }

    @Test
    fun `hand-built world - a days-narrowed read is the full read cut to those days, for the rows and the counts`() = testApplication {
        usePostgresTestcontainer()
        withCompareWipWorld { w ->
            val accounts = listOf(w.a1, w.a2, w.a3)
            val full = request(w.connId, accounts, "2025-12-15", "2026-02-05")
            val everything = expanded(full)
            val everyCount = dayCounts(full)
            val choices = listOf(
                listOf("2026-01-14"), listOf("2026-01-14", "2026-01-15", "2026-01-31"), listOf("2026-01-01", "2026-02-05"),
                listOf("2025-12-14", "2026-02-06"), emptyList(),
            )
            for (days in choices) {
                val narrowed = full.copy(days = days)
                val rows = expanded(narrowed)
                assertEquals(everything.filter { it.day in days }.toSet(), rows.toSet(), "rows for $days")
                assertTrue(rows.all { it.day in days }, "no other day leaks in: $days")
                val counts = dayCounts(narrowed)
                val wantCounts = everyCount.mapValues { (_, byDay) -> byDay.filterKeys { it.toString() in days } }
                    .filterValues { it.isNotEmpty() }
                assertEquals(wantCounts, counts, "day counts for $days")
            }
            // The two days Person trend asks for are the last listed day of each window: rows exist there, so the test is not vacuous.
            assertTrue(expanded(full.copy(days = listOf("2026-01-14"))).isNotEmpty())
        }
    }

    @Test
    fun `hand-built world - the held arrays of every listed day equal the expansion's rows plus started_at`() = testApplication {
        usePostgresTestcontainer()
        withCompareWipWorld { w ->
            val full = request(w.connId, listOf(w.a1, w.a2, w.a3), "2025-12-15", "2026-02-05")
            // The reassignment exactly at a day end, a mid-interval stage change, an open interval, the unassigned gap, the last day, and
            // a day nobody held a task on.
            val listed = listOf("2026-01-09", "2026-01-14", "2026-01-15", "2026-01-31", "2026-02-05", "2025-12-15").map(LocalDate::parse)
            val compared = assertHeldEqualsExpansion(full, listed, "world")
            assertTrue(compared >= 10, "the comparison is not vacuous: $compared tasks")
            // Tasks 2, 3, 4 and 7 have a fact row, tasks 1 and 5 have none: a missing fact keeps its row with a null started_at.
            val read = suspendTransaction(sharedDatabaseForTests()) { readWipUserHeld(full, listOf(LocalDate.parse("2026-01-15"))) }
            val started = read.held.getValue("2026-01-15").values.flatten()
            assertTrue(started.any { it.startedAt != null } && started.any { it.startedAt == null }, "$started")
            assertEquals(started.size, started.map { it.connectionId to it.issueId }.distinct().size, "no task repeats within a day")
            assertEquals(setOf("2026-01-15"), read.held.keys, "only the listed day carries tasks")
            val none = suspendTransaction(sharedDatabaseForTests()) { readWipUserHeld(full, emptyList()) }
            assertEquals(emptyMap(), none.held, "no listed day, no task arrays")
            assertEquals(dayCounts(full), none.dayCounts)
        }
    }

    @Test
    fun `an empty pick or connection list reads nothing`() = testApplication {
        usePostgresTestcontainer()
        withCompareWipWorld { w ->
            suspendTransaction(sharedDatabaseForTests()) {
                val none = listOf(LocalDate.parse("2026-01-31"))
                val noConnection = WipTaskDaysRequest(emptyList(), listOf(w.a1), "2026-01-01", "2026-01-31")
                assertEquals(emptyMap(), readWipUserHeld(noConnection, none).held)
                assertEquals(emptyMap(), readWipUserHeld(request(w.connId, emptyList(), "2026-01-01", "2026-01-31"), none).dayCounts)
                assertEquals(emptyMap(), readWipUserTaskCounts(request(w.connId, listOf(w.a1), "2026-01-01", "2026-01-31"), emptyList()))
            }
        }
    }

    @Test
    fun `the derived fixture - the set-based reads equal the expansion for the busiest people`() = testApplication {
        usePostgresTestcontainer()
        val connId = DerivedStubFixture.connectionId()
        val busiest = jdbcRows(
            """SELECT ta.account_id, count(*) c FROM metrics.task_assignee ta
               JOIN metrics.item_stage s ON s.connection_id = ta.connection_id AND s.issue_id = ta.issue_id AND s.stage = 'IN_PROGRESS'
                 AND s.valid_from < COALESCE(ta.valid_to, 9223372036854775807) AND ta.valid_from < COALESCE(s.valid_to, 9223372036854775807)
               WHERE ta.connection_id = ? AND ta.account_id IS NOT NULL GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 3""",
            connId.toInt(),
        ).map { it[0] as String }
        assertEquals(3, busiest.size, "the fixture must have three assignees who worked in progress")
        for ((first, last) in listOf("2025-01-01" to "2026-03-05", "2025-06-01" to "2025-06-30", "2026-02-01" to "2026-03-05")) {
            val days = generateSequence(LocalDate.parse(first)) { it.plusDays(1) }.takeWhile { it <= LocalDate.parse(last) }.toList()
            val working = days.filter { it.dayOfWeek.value < 6 }
            val request = request(connId, busiest.take(2), first, last)
            assertSameAsExpansion(request, working, "fixture $first..$last")
            val held = assertHeldEqualsExpansion(request, working, "fixture $first..$last held")
            assertTrue(held > 0 || first == "2025-06-01", "the busiest pair holds tasks on the listed days of $first..$last")
            assertTrue(expanded(request).isNotEmpty() || first == "2025-06-01", "the busiest pair holds work in $first..$last")
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
