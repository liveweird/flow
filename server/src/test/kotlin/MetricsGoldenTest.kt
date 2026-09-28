package ch.nokillswit

import ch.nokillswit.metrics.DeriveKernels
import ch.nokillswit.metrics.EstimatePoint
import ch.nokillswit.metrics.SprintMembershipInterval
import ch.nokillswit.metrics.SprintScopeItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A hand-built micro-fixture (v0.3.0 M3 commit 8, `.claude/docs/testing.md`'s "hand-computed
 * golden micro-fixture" pattern) — every number below is computed BY HAND in this file's own
 * comments, independent of both the generator (`sample-data/jira/generate.mjs`) and
 * `DeriveKernels`' own code path, exercising `DeriveKernels.sprintScope`/`sprintTotals` (the
 * `fact_sprint_scope`/`fact_sprint` kernels) directly — pure, no DB.
 *
 * **Six tasks, not the plan's illustrative three** — a deliberate, documented deviation. Every
 * bucket the plan names (committed/added/removed/delivered/carried/dropped) is mutually exclusive
 * PER TASK against several of the others by construction (`DeriveKernels.sprintScope`'s own
 * doc — a REMOVED row can never also be committed/final/delivered/carried/dropped in the
 * aggregate sense, and dropped/carried require the FINAL branch a removed row never reaches), so
 * three tasks cannot populate all six buckets at once. Five tasks populate one task per
 * bucket-combination; a sixth (task 6) exists ONLY to exercise A17 — an ADDED task that is not
 * done and has no later sprint, which BEFORE A17 contributed to no bucket at all (invisible in
 * `final = delivered + carried + dropped`) and now lands in `dropped`, same as a committed one.
 *
 * Sprint: `start = 1_000`, `close = 2_000`, `grace = 0` (so `commitAt = start = 1_000`).
 */
class MetricsGoldenTest {
    private val sprintStart = 1_000L
    private val sprintClose = 2_000L

    private fun scope(
        issueId: Long,
        enteredAt: Long,
        exitedAt: Long? = null,
        estimateAtCommit: Double,
        estimateAtClose: Double,
        doneAtMs: Long? = null,
        inLaterSprintOfTeam: Boolean = false,
    ): SprintScopeItem {
        // The re-estimate point sits strictly AFTER `commitAt` (`sprintStart + 100`, never exactly
        // at it) so `estimateAt(timeline, commitAt)` unambiguously reads the FIRST point.
        val timeline = mutableListOf(EstimatePoint(0, estimateAtCommit))
        if (estimateAtClose != estimateAtCommit) timeline += EstimatePoint(sprintStart + 100, estimateAtClose)
        return DeriveKernels.sprintScope(
            issueId = issueId,
            sprintStartAtMs = sprintStart,
            sprintCloseAtMs = sprintClose,
            graceMs = 0,
            membershipIntervals = listOf(SprintMembershipInterval(3003L, enteredAt, exitedAt)),
            estimateTimeline = timeline,
            assigneeIntervals = emptyList(),
            doneAtMs = doneAtMs,
            inLaterSprintOfTeam = inLaterSprintOfTeam,
        )!!
    }

    @Test
    fun `hand-computed sprint scope totals over five tasks`() {
        // Task 1 — committed at 3 MD, done inside the sprint at 5 MD (a mid-sprint re-estimate
        // before completion): committed += 3, delivered += 5 (read at the sprint's own close
        // instant, same as the final-bucket value since nothing changes after done_at here).
        val task1 = scope(1L, enteredAt = 500, estimateAtCommit = 3.0, estimateAtClose = 5.0, doneAtMs = 1_500)
        // Task 2 — committed at 5 MD, re-estimated DOWN to 2 MD by close, never done, present in
        // a later sprint of the same team: committed += 5, final += 2, carried-over += 2.
        val task2 = scope(2L, enteredAt = 200, estimateAtCommit = 5.0, estimateAtClose = 2.0, inLaterSprintOfTeam = true)
        // Task 3 — added mid-sprint (entered AFTER commitAt) at 4 MD, done inside the sprint at
        // the same 4 MD: added += 4, delivered += 4, final += 4.
        val task3 = scope(3L, enteredAt = 1_200, estimateAtCommit = 4.0, estimateAtClose = 4.0, doneAtMs = 1_800)
        // Task 4 — committed at 6 MD, never done, NO later sprint: committed += 6, final += 6,
        // dropped += 6.
        val task4 = scope(4L, enteredAt = 800, estimateAtCommit = 6.0, estimateAtClose = 6.0)
        // Task 5 — committed at 7 MD, EXITS the sprint before completion (never reaches the final
        // branch at all): removed += 7 — contributes to NO other bucket.
        val task5 = scope(5L, enteredAt = 900, exitedAt = 1_400, estimateAtCommit = 7.0, estimateAtClose = 7.0)
        // Task 6 (A17) — added mid-sprint at 2 MD, never done, NO later sprint: added += 2,
        // final += 2, dropped += 2 — BEFORE A17 this task contributed to no bucket beyond
        // added/final (carried-over/dropped were committed-only); now it drops like task 4 does.
        val task6 = scope(6L, enteredAt = 1_300, estimateAtCommit = 2.0, estimateAtClose = 2.0)

        val totals = DeriveKernels.sprintTotals(listOf(task1, task2, task3, task4, task5, task6))

        // committed = task1(3) + task2(5) + task4(6) = 14 — task5 is committed but REMOVED (see
        // sprintTotals' own doc: the committed bucket excludes removed rows).
        assertEquals(14.0, totals.committedMd)
        assertEquals(3, totals.committedItems)
        // added = task3(4) + task6(2) = 6.
        assertEquals(6.0, totals.addedMd)
        assertEquals(2, totals.addedItems)
        // removed = task5(7).
        assertEquals(7.0, totals.removedMd)
        assertEquals(1, totals.removedItems)
        // final (in scope at close) = task1(5) + task2(2) + task3(4) + task4(6) + task6(2) = 19.
        assertEquals(19.0, totals.finalMd)
        assertEquals(5, totals.finalItems)
        // delivered = task1(5) + task3(4) = 9.
        assertEquals(9.0, totals.deliveredMd)
        assertEquals(2, totals.deliveredItems)
        // carried-over = task2(2).
        assertEquals(2.0, totals.carriedOverMd)
        assertEquals(1, totals.carriedOverItems)
        // dropped (A17) = task4(6) + task6(2) = 8.
        assertEquals(8.0, totals.droppedMd)
        assertEquals(2, totals.droppedItems)

        // A17 partition: final = committed + added (items), and final = delivered + carried + dropped.
        assertEquals(totals.finalItems, totals.committedItems + totals.addedItems)
        assertEquals(totals.finalItems, totals.deliveredItems + totals.carriedOverItems + totals.droppedItems)
    }
}
