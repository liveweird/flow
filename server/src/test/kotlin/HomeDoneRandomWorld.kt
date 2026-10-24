package ch.nokillswit

import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * A seeded random world of DONE tasks for `HomeDoneFiguresGoldenTest` (the Home overview's figures aggregated in Postgres, graded against
 * the per-row Kotlin kernels of `HomeDoneOracle.kt`). Pinned at [HOME_RANDOM_NOW] (Europe/Warsaw, 2026-04-20): the current window starts
 * 2026-01-21 and holds the spring clock change (Sunday 2026-03-29, a 23-hour day inside a week), the previous one holds the autumn change
 * (2025-10-26, a 25-hour day). Two disabled connections, six listed teams whose done counts per (current, previous) window are
 * [WINDOW_COUNTS] (nothing, one, two, three, an even and an odd count, ties everywhere), an unpicked team, a soft-deleted team, team-less
 * rows, a sub-task and a second connection. Every optional column is null with a probability and drawn from small pools (heavy ties), with
 * the NUMERIC extremes and the PRED boundary pairs among them; the last two teams also carry a task at every window edge and one
 * millisecond either side of every Monday midnight of the zone.
 */

internal val HOME_RANDOM_NOW: Long = homeAt("2026-04-20T10:00:00+02:00")
internal val HOME_RANDOM_PREVIOUS_FROM: Long = homeAt("2025-10-23T00:00:00+02:00")
internal val HOME_RANDOM_CURRENT_FROM: Long = homeAt("2026-01-21T00:00:00+01:00")
internal val HOME_RANDOM_TO: Long = homeAt("2026-04-21T00:00:00+02:00")

private val WARSAW = ZoneId.of("Europe/Warsaw")

/** The done tasks of the six listed teams, `current to previous`: none; one; two, one; three, two; even and odd; the reverse. */
private val WINDOW_COUNTS = listOf(0 to 0, 1 to 0, 2 to 1, 3 to 2, 40 to 41, 61 to 60)

private val ESTIMATES = listOf(0.01, 0.5, 1.0, 1.0, 1.5, 2.0, 3.0, 5.0, 8.0, 999_999.99)
private val ACTUALS = listOf(0.0, 0.01, 0.5, 1.0, 2.0, 2.0, 3.0, 99_999_999.99)
private val CYCLES = listOf(0.0001, 0.5, 1.0, 1.0, 2.5, 3.0, 7.25, 123_456.7891)

/** `actual to estimate at start` pairs sitting exactly on a PRED threshold, or a hair either side of it. */
private val PRED_PAIRS = listOf(
    4.0 to 3.0, 3.0 to 4.0, 2.0 to 1.0, 1.0 to 2.0, 10.0 to 9.0, 9.0 to 10.0, 1.1 to 1.0, 1.5 to 1.0, 0.5 to 1.0,
)

private const val DAY_MS = 86_400_000L
private const val NULL_SHARE = 0.3

internal class HomeDoneRandomWorld(
    val connA: UInt,
    val connB: UInt,
    val listed: List<UInt>,
    val unpicked: UInt,
    val deleted: UInt,
) {
    val now: Long get() = HOME_RANDOM_NOW
}

private class RandomTasks(private val random: Random, private val ids: Ids) {
    private fun <T> pick(pool: List<T>): T = pool[random.nextInt(pool.size)]

    private fun maybe(): Boolean = random.nextDouble() < NULL_SHARE

    fun task(team: UInt?, doneAt: Long): FactTaskDeliveryRow {
        val pair = if (random.nextDouble() < 0.3) pick(PRED_PAIRS) else null
        val estimateAtDone = if (maybe()) null else pick(ESTIMATES + 0.0)
        val worked = pair != null || random.nextDouble() < 0.8
        val started = pair != null || random.nextDouble() > 0.25
        val row = handTask(
            ids.take(),
            startedAt = if (started) doneAt - (1 + random.nextInt(30)) * DAY_MS else null,
            doneAt = doneAt,
            hasWorklogs = worked,
            actualMd = pair?.first ?: pick(ACTUALS),
            estimateAtStartMd = pair?.second ?: if (maybe()) null else pick(ESTIMATES + 0.0),
            estimateAtDoneMd = estimateAtDone,
            estimatedLate = random.nextDouble() < 0.15,
            creditTeamId = team,
            account = if (maybe()) null else "acc-${random.nextInt(4)}",
            currentTeamId = team,
            cycleMs = if (maybe()) null else (1 + random.nextInt(20)) * DAY_MS,
            cycleWorkingDays = if (maybe()) null else pick(CYCLES),
        )
        return if (estimateAtDone == null || random.nextDouble() < 0.1) row.copy(estimateSource = "NONE") else row
    }
}

/** Every Monday 00:00 of the zone from the one on or before the current window's start to the window's last day, as epoch millis. */
private fun mondayMidnights(): List<Long> {
    val first = LocalDate.of(2026, 1, 21).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val last = LocalDate.of(2026, 4, 20)
    return generateSequence(first) { it.plusWeeks(1) }.takeWhile { !it.isAfter(last) }
        .map { it.atStartOfDay(WARSAW).toInstant().toEpochMilli() }.toList()
}

private fun RandomTasks.windowRows(team: UInt?, count: Int, random: Random, from: Long, to: Long) =
    List(count) { task(team, from + (random.nextDouble() * (to - from)).toLong()) }

private fun RandomTasks.edgeRows(team: UInt): List<FactTaskDeliveryRow> {
    val edges = listOf(
        HOME_RANDOM_PREVIOUS_FROM - 1, HOME_RANDOM_PREVIOUS_FROM, HOME_RANDOM_CURRENT_FROM - 1, HOME_RANDOM_CURRENT_FROM,
        HOME_RANDOM_TO - 1, HOME_RANDOM_TO,
    )
    val mondays = mondayMidnights().flatMap { listOf(it - 1, it, it + 1) }
    return (edges + mondays).map { task(team, it) }
}

private fun rowsOf(world: HomeDoneRandomWorld, seed: Long): Pair<List<FactTaskDeliveryRow>, List<FactTaskDeliveryRow>> {
    val random = Random(seed)
    val first = RandomTasks(random, Ids(1))
    val second = RandomTasks(random, Ids(1))
    val a = ArrayList<FactTaskDeliveryRow>()
    val b = ArrayList<FactTaskDeliveryRow>()
    world.listed.zip(WINDOW_COUNTS).forEachIndexed { i, (team, counts) ->
        a += first.windowRows(team, counts.first, random, HOME_RANDOM_CURRENT_FROM, HOME_RANDOM_TO)
        a += first.windowRows(team, counts.second, random, HOME_RANDOM_PREVIOUS_FROM, HOME_RANDOM_CURRENT_FROM)
        if (i >= WINDOW_COUNTS.size - 2) a += first.edgeRows(team)
        // The same team on the second connection (the connection filter and the (connection, issue) identity both matter).
        if (i >= 3) b += second.windowRows(team, 6, random, HOME_RANDOM_PREVIOUS_FROM, HOME_RANDOM_TO)
    }
    for (other in listOf(world.unpicked, world.deleted, null)) {
        a += first.windowRows(other, 30, random, HOME_RANDOM_CURRENT_FROM, HOME_RANDOM_TO)
        a += first.windowRows(other, 25, random, HOME_RANDOM_PREVIOUS_FROM, HOME_RANDOM_CURRENT_FROM)
    }
    b += second.windowRows(null, 5, random, HOME_RANDOM_PREVIOUS_FROM, HOME_RANDOM_TO)
    // Never read: a sub-task of a listed team.
    a += first.task(world.listed[4], HOME_RANDOM_CURRENT_FROM + DAY_MS).copy(isSubtask = true)
    return a to b
}

/** Seeds the world for [seed], runs [block], and removes what it wrote (the connections stay, disabled). */
internal suspend fun <T> withHomeDoneRandomWorld(seed: Long, block: suspend (HomeDoneRandomWorld) -> T): T {
    val connA = SyncedStubFixture.createConnection(namePrefix = "home-done-a", enabled = false)
    val connB = SyncedStubFixture.createConnection(namePrefix = "home-done-b", enabled = false)
    val teams = List(WINDOW_COUNTS.size + 2) { TestTeams.seed(SyncedStubFixture.unique("hdone-$it")) }
    val world = HomeDoneRandomWorld(connA, connB, teams.take(WINDOW_COUNTS.size), teams[WINDOW_COUNTS.size], teams.last())
    val (rowsA, rowsB) = rowsOf(world, seed)
    val store = MetricsStore(sharedDatabaseForTests())
    suspendTransaction(sharedDatabaseForTests()) {
        store.replaceFactTaskDelivery(connA, rowsA, configRevision = 1L)
        store.replaceFactTaskDelivery(connB, rowsB, configRevision = 1L)
    }
    TestTeams.service.delete(world.deleted)
    return try {
        block(world)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            for (conn in listOf(connA, connB)) store.deleteFactTaskDelivery(conn)
        }
        cleanUpTeams(world.listed + world.unpicked)
    }
}
