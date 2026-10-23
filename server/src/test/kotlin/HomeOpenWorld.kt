package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.FactEpicDeliveryRow
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsStore
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The open-item world of `HomeWipPushdownGoldenTest`: the Home overview's WIP figures and `WIP_TASKS`/`WIP_EPICS` drills read the open
 * items of the whole unit, so this world is built to break every shortcut that is not the old read. Two disabled connections, five
 * fresh teams (A, B, C, G picked in the tests; D never picked; E soft-deleted after seeding, so its work is "of no ACTIVE team") and
 * team-less rows, pinned at [HomeScenario.NOW] (Europe/Warsaw, a Wednesday midnight; weekends are non-working):
 *
 * - A: 450 tasks started every 5 hours over the days before (every age distinct), a block of 120 started inside ONE weekend (one age,
 *   120 distinct `started_at`; the 500th place of the age order falls inside it, and the drill's within-tie order is `started_at`
 *   DESCENDING, the opposite of the SQL read order), 150 more recent distinct ones, 30 started after NOW (age 0, 30 `started_at`),
 *   plus excluded rows (not started, NOT_STARTED stage). The same shape for epics (600+ so the epic drill is cut too).
 * - B: exactly 3 open tasks and 3 epics; C: 2 and 2 (under `minSampleSize` 3, hidden); G: exactly 1 task and 1 epic (n == 1, the
 *   `percentileContinuous` single-value branch, shown at `minSampleSize` 1); D: 50 tasks; E (inactive): 10 tasks; no team: 30 tasks.
 * - The second connection repeats a few of A's `started_at` values so ties break by connection id.
 */

private const val HOUR_MS = 3_600_000L
private const val DAY_HOURS = 24L

/** The i-th of the weekend block's instants: 19 min 1 s apart from Saturday 2026-01-10 00:00 (Warsaw); 120 of them end Sunday afternoon. */
private fun weekendInstant(i: Int): Long = homeAt("2026-01-10T00:00:00+01:00") + i * (19L * 60_000L + 1_000L)

internal class HomeOpenWorld(
    val connA: UInt,
    val connB: UInt,
    val a: UInt,
    val b: UInt,
    val c: UInt,
    val d: UInt,
    val e: UInt,
    val g: UInt,
) {
    val now: Long get() = HomeScenario.NOW
    val picked get() = listOf(a, b, c)
    val others get() = listOf(g, d, b)
    val allTeams get() = listOf(a, b, c, d, e, g)
}

private class Ids(var next: Long) { fun take() = next++ }

private fun open(ids: Ids, started: Long?, team: UInt?, stage: String = "IN_PROGRESS", account: String? = "acc-w") =
    handTask(ids.take(), startedAt = started, currentTeamId = team, currentAssignee = account).copy(currentStage = stage)

private fun HomeOpenWorld.tasksOfA(ids: Ids): List<FactTaskDeliveryRow> {
    val rows = ArrayList<FactTaskDeliveryRow>()
    // Old, one start every 2 hours from 78 days before NOW (so the weekends among them tie by age too, in shorter blocks).
    repeat(450) { rows += open(ids, now - 78 * DAY_HOURS * HOUR_MS + it * 2L * HOUR_MS, a, STAGES[it % STAGES.size]) }
    // One weekend: Saturday 2026-01-10 .. Sunday 2026-01-11 (Warsaw), 120 distinct instants, one age.
    repeat(120) { rows += open(ids, weekendInstant(it), a, STAGES[(it + 1) % STAGES.size]) }
    // Recent and distinct.
    repeat(150) { rows += open(ids, homeAt("2026-01-13T09:00:00+01:00") + it * 5L * HOUR_MS, a) }
    // After NOW: age 0, all different instants.
    repeat(30) { rows += open(ids, now + (it + 1L) * 61_000L, a) }
    rows += open(ids, null, a)
    rows += open(ids, now - 3 * 24 * HOUR_MS, a, stage = "NOT_STARTED")
    return rows
}

private val STAGES = listOf("IN_PROGRESS", "WAITING", "BLOCKED", "IN_PROGRESS")

private fun HomeOpenWorld.smallTasks(ids: Ids): List<FactTaskDeliveryRow> {
    val rows = ArrayList<FactTaskDeliveryRow>()
    listOf("2026-02-16T10:00:00+01:00", "2026-02-09T09:00:00+01:00", "2026-01-26T15:30:00+01:00")
        .forEach { rows += open(ids, homeAt(it), b) }
    listOf("2026-02-12T10:00:00+01:00", "2026-02-02T09:00:00+01:00").forEach { rows += open(ids, homeAt(it), c) }
    rows += open(ids, homeAt("2026-02-05T11:00:00+01:00"), g)
    repeat(50) { rows += open(ids, now - (it * 31L + 5) * HOUR_MS, d) }
    repeat(10) { rows += open(ids, now - (it * 53L + 9) * HOUR_MS, e) }
    repeat(30) { rows += open(ids, now - (it * 17L + 2) * HOUR_MS, null) }
    return rows
}

private fun HomeOpenWorld.tasksOfSecondConnection(ids: Ids): List<FactTaskDeliveryRow> {
    // The same instants as the first connection's weekend block head, so the (connection, issue) tie-break decides between the two.
    return List(40) { open(ids, weekendInstant(it), a) } +
        List(10) { open(ids, now - (it * 11L + 3) * HOUR_MS, d) }
}

private fun HomeOpenWorld.epics(ids: Ids, team: UInt, count: Int, startFor: (Int) -> Long, stageFor: (Int) -> String) = List(count) {
    handEpic(ids.take(), startedAt = startFor(it), ownCurrent = 4.0, childSum = 4.0, ownerTeamId = team) to stageFor(it)
}

private fun HomeOpenWorld.allEpics(ids: Ids): List<Pair<FactEpicDeliveryRow, String>> {
    val rows = ArrayList<Pair<FactEpicDeliveryRow, String>>()
    rows += epics(ids, a, 480, { now - 78 * DAY_HOURS * HOUR_MS + it * 90L * 60_000L }) { STAGES[it % STAGES.size] }
    rows += epics(ids, a, 110, { weekendInstant(it) + 7_000L }) { STAGES[(it + 2) % STAGES.size] }
    rows += epics(ids, a, 20, { now + (it + 1L) * 61_000L }) { "IN_PROGRESS" }
    rows += epics(ids, a, 5, { now - 5 * 24 * HOUR_MS }) { "NOT_STARTED" }
    rows += epics(ids, b, 3, { homeAt("2026-02-0${it + 3}T10:00:00+01:00") }) { "IN_PROGRESS" }
    rows += epics(ids, c, 2, { homeAt("2026-02-1${it}T10:00:00+01:00") }) { "WAITING" }
    rows += epics(ids, g, 1, { homeAt("2026-02-05T11:00:00+01:00") }) { "BLOCKED" }
    rows += epics(ids, d, 7, { now - (it * 29L + 4) * HOUR_MS }) { "IN_PROGRESS" }
    // An epic with no owner team and one that never started: counted nowhere (the second is not open WIP).
    rows += handEpic(ids.take(), startedAt = now - 40 * HOUR_MS, ownCurrent = 2.0, childSum = 2.0, ownerTeamId = null) to "IN_PROGRESS"
    rows += handEpic(ids.take(), startedAt = null, ownCurrent = 2.0, childSum = 2.0, ownerTeamId = a) to "IN_PROGRESS"
    return rows
}

/** Seeds the world, runs [block], and removes what it wrote (the connections stay, disabled). */
internal suspend fun <T> withHomeOpenWorld(block: suspend (HomeOpenWorld) -> T): T {
    val connA = SyncedStubFixture.createConnection(namePrefix = "home-open-a", enabled = false)
    val connB = SyncedStubFixture.createConnection(namePrefix = "home-open-b", enabled = false)
    val teams = listOf("a", "b", "c", "d", "e", "g").map { TestTeams.seed(SyncedStubFixture.unique("hwip-$it")) }
    val world = HomeOpenWorld(connA, connB, teams[0], teams[1], teams[2], teams[3], teams[4], teams[5])
    val store = MetricsStore(sharedDatabaseForTests())
    val ids = Ids(1_000)
    val epicRows = world.allEpics(ids)
    val dims = epicRows.map { (fact, stage) ->
        DimEpicRow(fact.issueId, "OE-${fact.issueId}", "Epic ${fact.issueId}", "AAA", null, stage, null, null)
    }
    suspendTransaction(sharedDatabaseForTests()) {
        store.replaceFactTaskDelivery(connA, world.tasksOfA(ids) + world.smallTasks(ids), configRevision = 1L)
        store.replaceFactTaskDelivery(connB, world.tasksOfSecondConnection(ids), configRevision = 1L)
        store.replaceFactEpicDelivery(connA, epicRows.map { it.first }, configRevision = 1L)
        store.insertEpics(connA, dims, configRevision = 1L)
    }
    TestTeams.service.delete(world.e)
    return try {
        block(world)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            for (conn in listOf(connA, connB)) {
                store.deleteFactTaskDelivery(conn)
                store.deleteFactEpicDelivery(conn)
                store.deleteDims(conn)
            }
        }
        cleanUpTeams(world.allTeams - world.e)
    }
}
