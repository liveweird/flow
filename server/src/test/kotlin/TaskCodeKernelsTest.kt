package ch.nokillswit

import ch.nokillswit.metrics.WorkingCalendar
import ch.nokillswit.reports.CodeMrRef
import ch.nokillswit.reports.IssueRef
import ch.nokillswit.reports.TaskCodeLink
import ch.nokillswit.reports.TaskCodeTask
import ch.nokillswit.reports.linkTasks
import ch.nokillswit.reports.taskCodeFigures
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of Report 22 (`TaskCodeKernels.kt`; domain-model A51 D6, measures.md "Report 22"): every figure computed by hand. Days are
 * counted from Monday 2026-03-02 (UTC, Saturday and Sunday off), so day 0 is a Monday and days 5-6 and 12-13 are weekends.
 */
class TaskCodeKernelsTest {
    private val zone = ZoneId.of("UTC")
    private val calendar = WorkingCalendar(zone, setOf(6, 7), emptySet<LocalDate>())
    private val base = LocalDate.of(2026, 3, 2)

    private fun at(day: Int, hour: Int = 0): Long =
        base.plusDays(day.toLong()).atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L

    private fun ref(id: Long) = IssueRef(1u, id)

    private fun task(id: Long, started: Int?, done: Int, key: String = "FLO-$id") =
        TaskCodeTask(ref(id), key, started?.let { at(it) }, at(done))

    private fun merged(key: String, mr: Long, day: Int, hour: Int = 0) = TaskCodeLink(key, CodeMrRef(9u, mr), at(day, hour))

    private fun open(key: String, mr: Long) = TaskCodeLink(key, CodeMrRef(9u, mr), null)

    /** The tasks, each resolved to itself alone (the plain case), linked by [links]. */
    private fun link(tasks: List<TaskCodeTask>, links: List<TaskCodeLink>, resolution: Map<String, Set<IssueRef>>? = null) = linkTasks(
        tasks,
        keysOf = tasks.associate { it.ref to setOf(it.issueKey) },
        linksByKey = links.groupBy { it.key },
        resolution = resolution ?: tasks.associate { it.issueKey to setOf(it.ref) },
    )

    @Test
    fun `code lead time runs from the start to the LAST merged merge request in working days`() {
        // Started Mon day 0; merged Wed day 2 and Fri day 4 (the last): 4 working days.
        val rows = link(listOf(task(1, 0, 8)), listOf(merged("FLO-1", 10, 2), merged("FLO-1", 11, 4)))
        val only = rows.single()
        assertEquals(2, only.merged)
        assertEquals(at(4), only.lastMergedAt)
        assertEquals(4.0, only.codeLeadWorkingDays(calendar))
        // The weekend between Friday and the next Wednesday is not counted: start Fri day 4, merge Wed day 9 -> Fri, Mon, Tue = 3.
        assertEquals(3.0, link(listOf(task(2, 4, 12)), listOf(merged("FLO-2", 12, 9))).single().codeLeadWorkingDays(calendar))
    }

    @Test
    fun `a merge before the start is zero working days and a task that never started has no lead time`() {
        assertEquals(0.0, link(listOf(task(1, 3, 8)), listOf(merged("FLO-1", 10, 1))).single().codeLeadWorkingDays(calendar))
        val neverStarted = link(listOf(task(2, null, 8)), listOf(merged("FLO-2", 11, 4))).single()
        assertNull(neverStarted.codeLeadWorkingDays(calendar))
        // It still counts as with a merge request, and as never started.
        val figures = taskCodeFigures(listOf(neverStarted), calendar)
        assertEquals(1, figures.withMergeRequest)
        assertEquals(1, figures.neverStarted)
        assertTrue(figures.leadTimes.isEmpty())
    }

    @Test
    fun `merged after done is the last merge past done_at, with its lag in working days`() {
        // Done Wed day 2; one merge on day 1 (before) and one on Mon day 7 (after, the last): the lag Wed -> Mon is Wed, Thu, Fri = 3.
        val late = link(listOf(task(1, 0, 2)), listOf(merged("FLO-1", 10, 1), merged("FLO-1", 11, 7))).single()
        assertTrue(late.mergedAfterDone)
        assertEquals(3.0, late.mergeLagWorkingDays(calendar))
        // All merges up to done_at: not late (a merge at the very instant of done is not after).
        val onTime = link(listOf(task(2, 0, 3)), listOf(merged("FLO-2", 12, 1), merged("FLO-2", 13, 3))).single()
        assertTrue(!onTime.mergedAfterDone)
        assertNull(onTime.mergeLagWorkingDays(calendar))
        // A merge some hours after done on a Saturday is late with a lag of 0 working days (a real value).
        val weekend = link(listOf(task(3, 0, 5)), listOf(merged("FLO-3", 14, 5, hour = 10))).single()
        assertTrue(weekend.mergedAfterDone)
        assertEquals(0.0, weekend.mergeLagWorkingDays(calendar))
    }

    @Test
    fun `the last merge is chosen across merge requests and a merge request naming two keys of one task counts once`() {
        val tasks = listOf(task(1, 0, 8, key = "FLO-1"))
        val keysOf = mapOf(ref(1) to setOf("FLO-1", "OLD-7"))
        val resolution = mapOf("FLO-1" to setOf(ref(1)), "OLD-7" to setOf(ref(1)))
        val rows = linkTasks(
            tasks, keysOf,
            listOf(merged("FLO-1", 10, 3), merged("OLD-7", 10, 3), merged("OLD-7", 11, 6), open("FLO-1", 12)).groupBy { it.key },
            resolution,
        )
        val only = rows.single()
        assertEquals(2, only.merged, "MR 10 names both keys: one merge request")
        assertEquals(1, only.unmerged)
        assertEquals(at(6), only.lastMergedAt)
        assertTrue(only.hasMerged && !only.onlyUnmerged)
    }

    @Test
    fun `a task with only unmerged merge requests is without a merge request and counted apart, never a zero lead time`() {
        val rows = link(listOf(task(1, 0, 8), task(2, 0, 8)), listOf(open("FLO-1", 10), open("FLO-1", 11), merged("FLO-2", 12, 3)))
        val figures = taskCodeFigures(rows, calendar)
        assertEquals(2, figures.done)
        assertEquals(1, figures.withMergeRequest)
        assertEquals(1, figures.withoutMergeRequest)
        assertEquals(1, figures.onlyUnmerged)
        assertEquals(listOf(3.0), figures.leadTimes)
        assertEquals(0.5, figures.withoutShare)
        assertNull(rows[0].lastMergedAt)
        assertNull(rows[0].codeLeadWorkingDays(calendar))
    }

    @Test
    fun `an ambiguous key links to no task and is counted, a key naming no live issue links to none and is not`() {
        val tasks = listOf(task(1, 0, 8), task(2, 0, 8))
        val other = IssueRef(2u, 1L)
        val links = listOf(merged("FLO-1", 10, 3), merged("FLO-2", 11, 3))
        // FLO-1 also names an issue of another Jira source: ambiguous, linked to none. FLO-2 names nobody live: linked to none.
        val resolution = mapOf("FLO-1" to setOf(ref(1), other), "FLO-2" to emptySet())
        val rows = link(tasks, links, resolution)
        assertTrue(rows.none { it.hasMerged || it.merged + it.unmerged > 0 })
        assertEquals(listOf(true, false), rows.map { it.ambiguousKey })
        assertEquals(1, taskCodeFigures(rows, calendar).ambiguousKeys)
    }

    @Test
    fun `an old key links only while it names the one issue, and is ambiguous once another issue has it`() {
        val tasks = listOf(task(1, 0, 8, key = "NEW-5"))
        val keysOf = mapOf(ref(1) to setOf("NEW-5", "OLD-5"))
        val links = listOf(merged("OLD-5", 10, 3)).groupBy { it.key }
        val unique = linkTasks(tasks, keysOf, links, mapOf("NEW-5" to setOf(ref(1)), "OLD-5" to setOf(ref(1)))).single()
        assertEquals(1, unique.merged)
        assertTrue(!unique.ambiguousKey)
        // The old key was reused by another issue: ambiguous, so it links to none (the current key alone would still link).
        val reused = linkTasks(tasks, keysOf, links, mapOf("OLD-5" to setOf(ref(1), ref(2)))).single()
        assertEquals(0, reused.merged)
        assertTrue(reused.ambiguousKey)
    }

    @Test
    fun `the figures count the shares against their own populations and are absent over an empty one`() {
        val empty = taskCodeFigures(emptyList(), calendar)
        assertEquals(0, empty.done)
        assertNull(empty.withoutShare)
        assertNull(empty.mergedAfterDoneShare)
        // Three done tasks: one with an on-time merge, one late, one with none.
        val rows = link(
            listOf(task(1, 0, 8), task(2, 0, 2), task(3, 0, 8)),
            listOf(merged("FLO-1", 10, 4), merged("FLO-2", 11, 7)),
        )
        val figures = taskCodeFigures(rows, calendar)
        val counts = listOf(figures.done, figures.withMergeRequest, figures.withoutMergeRequest, figures.mergedAfterDone)
        assertEquals(listOf(3, 2, 1, 1), counts)
        assertEquals(1.0 / 3.0, figures.withoutShare)
        assertEquals(0.5, figures.mergedAfterDoneShare, "late over the tasks with a merged merge request, not over the done tasks")
        assertEquals(listOf(4.0, 5.0), figures.leadTimes)
        assertEquals(listOf(3.0), figures.lags)
        assertEquals(0, figures.neverStarted)
    }
}
