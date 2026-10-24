package ch.nokillswit

import ch.nokillswit.metrics.FactTaskDeliveryRow

/*
 * The done-task half of `HomeOpenWorld` (graded by `HomeDonePushdownGoldenTest`): the Home overview reads the level-0 tasks done in
 * its two 90-day windows, so this world puts done tasks wherever a shortcut could go wrong, at [HomeScenario.NOW] (Europe/Warsaw):
 *
 * - A: 400 tasks on the first connection spread over both windows, 120 more on the second connection, plus one task at each edge of
 *   the 180 days (a millisecond before and at the previous window's start, before and at the current window's start, last millisecond
 *   and first one after the current window's end: the two outer ones are in NO window);
 * - B: exactly 3 done tasks per window; C: 2 per window (under `minSampleSize` 3) and 1 more on the second connection; G: 1 in the
 *   current window and none before; D (never picked): 60 + 40 on the second connection; E (soft-deleted after seeding): 25 current
 *   and 10 previous; no credit team: 30 current and 12 previous (+ 8 on the second connection, + 1 in the current window's last
 *   millisecond);
 * - rows whose cycle columns disagree (`cycle_ms` set without `cycle_working_days`, and the reverse), in both windows;
 * - every figure's "missing" branch: no estimate (`estimate_source` NONE), no assignee, never started (no cycle), unworked (no
 *   worklogs), estimated late, a sub-task (never read) and a not-started task credited to a team (not done; the open-item reads
 *   skip it too, so `HomeOpenWorld`'s open counts stay as its header says).
 */

private const val DAY_MS = 86_400_000L
private const val HOUR_MS = 3_600_000L

/** The previous window's first instant, the current window's first instant and the end (exclusive) of the 180 days the read covers. */
internal val HOME_DONE_FROM: Long = homeAt("2025-08-23T00:00:00+02:00")
internal val HOME_DONE_CURRENT_FROM: Long = homeAt("2025-11-21T00:00:00+01:00")
internal val HOME_DONE_TO: Long = homeAt("2026-02-19T00:00:00+01:00")

/** One done task whose every optional column depends on [i], so the `i % k` branches all occur in any sample of a few dozen. */
internal fun doneTaskOf(id: Long, team: UInt?, doneAt: Long, i: Int): FactTaskDeliveryRow {
    val estimate = if (i % 7 == 0) null else (i % 5 + 1) * 0.5
    val row = handTask(
        id,
        startedAt = if (i % 8 == 0) null else doneAt - (i % 17 + 1) * DAY_MS,
        doneAt = doneAt,
        hasWorklogs = i % 13 != 0,
        actualMd = (i % 9) * 0.5,
        estimateAtStartMd = if (i % 11 == 0) null else estimate,
        estimateAtDoneMd = estimate,
        estimatedLate = i % 10 == 0,
        creditTeamId = team,
        account = if (i % 6 == 0) null else "acc-${i % 4}",
        currentTeamId = team,
        cycleMs = if (i % 8 == 0) null else (i % 17 + 1) * DAY_MS,
        cycleWorkingDays = if (i % 8 == 0) null else (i % 17 + 1) * 0.5,
        activeMs = (i % 5) * HOUR_MS,
    )
    return if (estimate == null) row.copy(estimateSource = "NONE") else row
}

/** [count] done tasks of [team] spread evenly over [from]..[to] (the position of each also feeds its `i % k` shape). */
private fun spread(ids: Ids, team: UInt?, count: Int, from: Long, to: Long): List<FactTaskDeliveryRow> =
    List(count) { doneTaskOf(ids.take(), team, from + it * ((to - from) / count), it) }

private fun HomeOpenWorld.edges(ids: Ids): List<FactTaskDeliveryRow> =
    listOf(
        HOME_DONE_FROM - 1, HOME_DONE_FROM, HOME_DONE_CURRENT_FROM - 1, HOME_DONE_CURRENT_FROM, HOME_DONE_TO - 1, HOME_DONE_TO,
    ).mapIndexed { i, at -> doneTaskOf(ids.take(), a, at, i + 1) }

/** The done tasks of the first connection (the one with the edges). */
internal fun HomeOpenWorld.doneTasksOfFirstConnection(ids: Ids): List<FactTaskDeliveryRow> {
    val rows = ArrayList<FactTaskDeliveryRow>()
    rows += spread(ids, a, 400, HOME_DONE_FROM, HOME_DONE_TO)
    rows += edges(ids)
    rows += spread(ids, b, 3, HOME_DONE_CURRENT_FROM, HOME_DONE_TO) + spread(ids, b, 3, HOME_DONE_FROM, HOME_DONE_CURRENT_FROM)
    rows += spread(ids, c, 2, HOME_DONE_CURRENT_FROM, HOME_DONE_TO) + spread(ids, c, 2, HOME_DONE_FROM, HOME_DONE_CURRENT_FROM)
    rows += spread(ids, g, 1, HOME_DONE_CURRENT_FROM + DAY_MS, HOME_DONE_TO)
    rows += spread(ids, d, 60, HOME_DONE_FROM, HOME_DONE_TO)
    rows += spread(ids, e, 25, HOME_DONE_CURRENT_FROM, HOME_DONE_TO) + spread(ids, e, 10, HOME_DONE_FROM, HOME_DONE_CURRENT_FROM)
    rows += spread(ids, null, 30, HOME_DONE_CURRENT_FROM, HOME_DONE_TO) + spread(ids, null, 12, HOME_DONE_FROM, HOME_DONE_CURRENT_FROM)
    // The count's upper edge on its own: a team-less task in the very last millisecond of the current window.
    rows += doneTaskOf(ids.take(), null, HOME_DONE_TO - 1, 1)
    // The read folds `cycle_ms`/`cycle_working_days`, `has_worklogs`/`actual_md` and `started_at`/`estimate_at_start_md` into one column
    // each: one half of a pair set without the other (the DERIVE never writes these, the fold must still equal the old per-item values).
    rows += doneTaskOf(ids.take(), a, HOME_DONE_CURRENT_FROM + 5 * DAY_MS, 1).copy(cycleWorkingDays = null)
    rows += doneTaskOf(ids.take(), a, HOME_DONE_CURRENT_FROM + 6 * DAY_MS, 1).copy(cycleMs = null)
    rows += doneTaskOf(ids.take(), a, HOME_DONE_FROM + 5 * DAY_MS, 1).copy(cycleWorkingDays = null)
    rows += doneTaskOf(ids.take(), a, HOME_DONE_FROM + 6 * DAY_MS, 1).copy(cycleMs = null)
    // Never read: a sub-task, and a not-started task credited to a team (a credit team on a row that is not done, and that the WIP
    // reads skip: no `started_at`, NOT_STARTED).
    rows += doneTaskOf(ids.take(), a, HOME_DONE_CURRENT_FROM + 3 * DAY_MS, 1).copy(isSubtask = true)
    rows += handTask(ids.take(), creditTeamId = a, currentTeamId = a, currentAssignee = "acc-w").copy(currentStage = "NOT_STARTED")
    return rows
}

/** The second connection's done tasks: the same teams again, so the connection filter and the (connection, issue) identity both matter. */
internal fun HomeOpenWorld.doneTasksOfSecondConnection(ids: Ids): List<FactTaskDeliveryRow> =
    spread(ids, a, 120, HOME_DONE_FROM, HOME_DONE_TO) + spread(ids, d, 40, HOME_DONE_FROM, HOME_DONE_TO) +
        spread(ids, c, 1, HOME_DONE_CURRENT_FROM, HOME_DONE_TO) + spread(ids, null, 8, HOME_DONE_FROM, HOME_DONE_TO)
