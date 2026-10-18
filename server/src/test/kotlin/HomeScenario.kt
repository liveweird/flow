package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.FactEpicDeliveryRow
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.MetricsStore
import java.time.ZonedDateTime
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The hand-built rows of the Home overview tests (`ReportHomeTest`, `ReportHomeDrillTest`): ONE disabled connection and two fresh
 * teams ("ahome-…" listed before "bhome-…") carrying a small world with hand-computed answers, pinned at [HomeScenario.NOW]
 * (Europe/Warsaw, midnight 2026-02-18) with `minSampleSize = 3`. The current window is 2025-11-21 .. 2026-02-18 and the previous
 * one 2025-08-23 .. 2025-11-20 (the 25-hour day 2025-10-26 lies inside it); rows sit exactly on the window edges.
 */

internal fun homeAt(iso: String): Long = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

private const val DAY_MS = 86_400_000L

internal class HomeScenario(val connId: UInt, val team: UInt, val other: UInt) {
    companion object {
        /** Midnight at the start of 2026-02-18 in Europe/Warsaw -- "today" and, for the open items, the clock their ages run to. */
        val NOW = homeAt("2026-02-18T00:00:00+01:00")
        const val MIN_SAMPLE = 3
    }
}

private fun HomeScenario.tasks(): List<FactTaskDeliveryRow> {
    fun done(
        id: Long, doneAt: String, estStart: Double?, estDone: Double?, actual: Double, cycleWd: Double?, account: String?,
        started: String? = null, late: Boolean = false, none: Boolean = false, worked: Boolean = actual > 0,
    ) = handTask(
        id, startedAt = started?.let(::homeAt), doneAt = homeAt(doneAt), hasWorklogs = worked, actualMd = actual,
        estimateAtStartMd = estStart,
        estimateAtDoneMd = estDone, estimatedLate = late, creditTeamId = team, account = account,
        cycleMs = cycleWd?.let { (it * DAY_MS).toLong() }, cycleWorkingDays = cycleWd,
    ).let { if (none) it.copy(estimateSource = "NONE") else it }
    fun open(id: Long, started: String?, stage: String, current: UInt? = team) = handTask(
        id, startedAt = started?.let(::homeAt), currentTeamId = current, currentAssignee = "acc-open",
    ).copy(currentStage = stage)
    return listOf(
        // The current window.
        done(101, "2025-11-21T00:00:00+01:00", 1.0, 1.0, 1.0, 3.0, "acc-1", started = "2025-11-14T09:00:00+01:00"),
        done(102, "2025-12-10T12:00:00+01:00", 2.0, 2.0, 3.0, 5.0, "acc-1", started = "2025-12-03T09:00:00+01:00"),
        done(103, "2026-01-15T12:00:00+01:00", 4.0, 3.0, 3.2, 2.0, "acc-2", started = "2026-01-12T09:00:00+01:00", late = true),
        done(104, "2026-02-18T23:59:59.999+01:00", 5.0, 5.0, 5.5, 8.0, "acc-2", started = "2026-02-02T09:00:00+01:00"),
        done(105, "2026-02-02T12:00:00+01:00", null, null, 0.0, 1.0, null, started = "2026-02-02T09:00:00+01:00", none = true),
        // One millisecond outside each end of the 180 days: in no window.
        done(106, "2026-02-19T00:00:00+01:00", 9.0, 9.0, 9.0, 1.0, "acc-1", started = "2026-02-10T09:00:00+01:00"),
        done(107, "2025-08-22T23:59:59.999+02:00", 9.0, 9.0, 9.0, 1.0, "acc-1", started = "2025-08-10T09:00:00+02:00"),
        // The previous window.
        done(111, "2025-11-20T23:59:59.999+01:00", 2.0, 2.0, 2.0, 4.0, "acc-1", started = "2025-11-10T09:00:00+01:00"),
        done(112, "2025-10-26T23:30:00+01:00", 1.0, 1.0, 2.0, 6.0, "acc-1", started = "2025-10-20T09:00:00+02:00"),
        done(113, "2025-08-23T00:00:00+02:00", 4.0, 4.0, 4.0, 2.0, "acc-2", started = "2025-08-20T09:00:00+02:00"),
        done(114, "2025-09-15T12:00:00+02:00", null, null, 0.0, null, null, none = true),
        done(115, "2025-10-01T12:00:00+02:00", null, null, 0.0, null, null, none = true),
        // Another team's and a team-less task: out of this team's figures.
        done(121, "2026-01-10T12:00:00+01:00", 7.0, 7.0, 7.0, 3.0, "acc-3", started = "2026-01-05T09:00:00+01:00")
            .copy(creditTeamId = other),
        done(122, "2026-01-10T12:00:00+01:00", 3.0, 3.0, 3.0, 3.0, "acc-3", started = "2026-01-05T09:00:00+01:00")
            .copy(creditTeamId = null),
        // Open items: ages (working days to NOW) 2, 3, 7, 2 and 0.5; not started / not in progress / another team's / team-less excluded.
        open(131, "2026-02-16T00:00:00+01:00", "IN_PROGRESS"),
        open(132, "2026-02-13T00:00:00+01:00", "WAITING"),
        open(133, "2026-02-09T00:00:00+01:00", "BLOCKED"),
        open(134, "2026-02-14T00:00:00+01:00", "IN_PROGRESS"),
        open(139, "2026-02-17T12:00:00+01:00", "IN_PROGRESS"),
        open(135, null, "IN_PROGRESS"),
        open(136, "2026-02-10T00:00:00+01:00", "NOT_STARTED"),
        open(137, "2026-02-10T00:00:00+01:00", "IN_PROGRESS", current = other),
        open(138, "2026-02-10T00:00:00+01:00", "IN_PROGRESS", current = null),
    )
}

private fun HomeScenario.epics(): Pair<List<FactEpicDeliveryRow>, List<DimEpicRow>> {
    val facts = listOf(
        handEpic(201, startedAt = homeAt("2026-02-02T09:00:00+01:00"), ownCurrent = null, childSum = 8.0, ownerTeamId = team),
        handEpic(202, startedAt = homeAt("2026-02-04T09:00:00+01:00"), ownCurrent = 4.0, childSum = 8.0, ownerTeamId = team),
        handEpic(
            203, startedAt = homeAt("2025-12-01T09:00:00+01:00"), doneAt = homeAt("2026-01-20T12:00:00+01:00"),
            ownStart = 10.0, ownDone = 10.0, ownCurrent = 10.0, childSum = 12.5, actualMd = 12.0, ownerTeamId = team,
        ),
        handEpic(
            204, startedAt = homeAt("2025-09-01T09:00:00+02:00"), doneAt = homeAt("2025-10-15T12:00:00+02:00"),
            ownStart = 4.0, ownDone = 4.0, ownCurrent = 4.0, childSum = 4.0, actualMd = 4.0, ownerTeamId = team,
        ),
        handEpic(
            205, startedAt = homeAt("2025-04-01T09:00:00+02:00"), doneAt = homeAt("2025-05-01T12:00:00+02:00"),
            ownStart = 4.0, ownDone = 4.0, ownCurrent = 4.0, childSum = 8.0, actualMd = 4.0, ownerTeamId = team,
        ),
        handEpic(206, startedAt = homeAt("2026-02-04T09:00:00+01:00"), ownCurrent = 4.0, childSum = 8.0, ownerTeamId = other),
        handEpic(207, startedAt = homeAt("2026-02-04T09:00:00+01:00"), ownCurrent = 4.0, childSum = 8.0, ownerTeamId = null),
    )
    val start = homeAt("2026-01-01T00:00:00+01:00")
    val due = homeAt("2026-03-01T00:00:00+01:00")
    val dims = facts.map {
        val stage = when {
            it.doneAt != null -> "DONE"
            else -> "IN_PROGRESS"
        }
        DimEpicRow(it.issueId, "HE-${it.issueId}", "Epic ${it.issueId}", "AAA", null, stage, if (it.issueId == 202L) null else start, due)
    }
    return facts to dims
}

/** Seeds the world, runs [block], and removes what it wrote (facts, dims, teams) -- the connection stays, disabled. */
internal suspend fun <T> withHomeScenario(block: suspend (HomeScenario) -> T): T {
    val connId = SyncedStubFixture.createConnection(namePrefix = "home-hand", enabled = false)
    val team = TestTeams.seed(SyncedStubFixture.unique("ahome-t"))
    val other = TestTeams.seed(SyncedStubFixture.unique("bhome-o"))
    val scenario = HomeScenario(connId, team, other)
    val store = MetricsStore(sharedDatabaseForTests())
    val (epics, dims) = scenario.epics()
    suspendTransaction(sharedDatabaseForTests()) {
        store.replaceFactTaskDelivery(connId, scenario.tasks(), configRevision = 1L)
        store.replaceFactEpicDelivery(connId, epics, configRevision = 1L)
        store.insertEpics(connId, dims, configRevision = 1L)
    }
    return try {
        withMinSampleSize(HomeScenario.MIN_SAMPLE) { block(scenario) }
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteFactTaskDelivery(connId)
            store.deleteFactEpicDelivery(connId)
            store.deleteDims(connId)
        }
        cleanUpTeams(listOf(team, other))
    }
}
