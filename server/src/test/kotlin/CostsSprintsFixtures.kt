package ch.nokillswit

import ch.nokillswit.metrics.DimEpicRow
import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.sprintTotals
import java.time.Instant
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The hand-built world of the Costs SPRINT widgets (`ReportCostsSprintsTest`): one disabled connection, a team T (members alice and
 * bob, whose worklogs carry `author_team_id` T), another team T2 (carol) and an author in no team (dave), eight epics, four sprints of T
 * and worklogs placed so every rule has a literal answer. The expected figures live in the test; the layout is documented here.
 *
 *   sprint S1 closed  Jan  5 00:00Z .. Jan 19 00:00Z  capacity 20 CALENDAR
 *   sprint S2 closed  Jan 19 00:00Z .. Feb  2 00:00Z  capacity 18 DEFAULT   (abuts S1: the instant Jan 19 00:00Z belongs to S2)
 *   sprint S3 closed  Jan 28 00:00Z .. Feb  6 00:00Z  no capacity           (OVERLAPS S2 from Jan 28 to Feb 2)
 *   sprint S4 ACTIVE  since Feb 10 00:00Z (open: its window runs to now; listed only by its sprintId)
 *   tasks 101..108 sit under epics E1..E8 (941001..941008); sub-task 109 of 101 carries E1 in dim_task; task 110 has no epic;
 *   a worklog on issue EPIC2 (= epic E2 itself, no dim_task row, stored epic_id = its own id) counts toward E2; issue 9999 is above the
 *   epic level (no dim_task row, no epic)
 *
 * Worklogs (md; noon UTC unless an instant is given), T's members unless marked:
 *   S1: a1 alice 101 4.0 | a2 bob 109 1.0 | a3 alice 102 3.0 (Bug) | a4 bob EPIC2 0.5 | a5 alice 103 2.0 | a6 bob 104 1.5
 *       a7 alice 105 1.0 | a8 bob 106 0.75 | a9 alice 107 0.5 | a10 bob 108 0.25 | a11 alice 110 0.75 | a12 bob 9999 0.25
 *       a13 carol (T2) 101 8.0 and a14 dave (no team) 101 6.0 are not T's: in no figure of T
 *   S2 only: b1 alice 101 0.5 AT Jan 19 00:00Z | b2 bob 102 2.0 | b3 alice 107 1.0 | b4 alice 109 0.25
 *   S2 and S3 (Jan 28 .. Feb 2): b5 bob 103 0.5 (Jan 29) | b6 bob 108 0.5 (Feb 1)
 *   S3 only: c1 alice 104 3.0 (Feb 3) | c2 bob 9999 0.25 (Feb 4)
 *   outside S1..S3 in the period Jan 1 .. Feb 28: o1 alice 101 1.0 (Jan 2) | o2 alice 102 2.0 (Feb 8, Bug) | o3 bob 103 0.5 (Feb 20)
 *   S4 (Feb 10 ..): d1 alice 101 1.25 (Feb 11); o3 (Feb 20) is inside it too. S4 is OPEN, so a date-range period does not list it: d1 and
 *       o3 are 'outside the listed sprints' there
 *   outside the period: x1 alice 101 7.0 (Dec 30)
 */
internal class CostsSprintsWorld(
    val connId: UInt,
    val team: UInt,
    val otherTeam: UInt,
    val alice: String,
    val bob: String,
    val carol: String,
    val dave: String,
) {
    val range = "connectionId=$connId&from=2026-01-01&to=2026-02-28"
    val epics = (1..EPIC_COUNT).map { EPIC_BASE + it }

    companion object {
        const val S1 = 940_001L
        const val S2 = 940_002L
        const val S3 = 940_003L
        const val S4 = 940_004L
        const val EPIC_BASE = 941_000L
        const val EPIC_COUNT = 8
        const val EPIC2_ISSUE = 941_002L
        const val ABOVE_EPIC_ISSUE = 9_999L
        const val SUBTASK = 109L
        const val NO_EPIC_TASK = 110L
    }
}

/** An instant of the world, UTC. */
internal fun utc(iso: String): Long = Instant.parse(iso).toEpochMilli()

private fun csLog(
    id: Long,
    issue: Long,
    author: String,
    team: UInt?,
    at: Long,
    md: Double,
    epic: Long?,
    activity: String = "Story",
) = FactWorklogRow(
    worklogId = id, issueId = issue, authorAccountId = author, authorTeamId = team, startedAt = at, createdAt = null, lateMs = null,
    md = md, taskDomainKey = "AAA", epicId = epic, epicDomainKey = "AAA", activityType = activity, workCategory = null,
    sprintIdAtStarted = null, sprintTeamIdAtStarted = null, foreignWork = false, assigneeAccountIdAtStarted = null,
    assigneeTeamIdAtStarted = null,
)

private fun csSprint(id: Long, team: UInt, state: String, start: Long, complete: Long?, capacity: Double?, source: String?) =
    DimSprintRow(id, id, team, "Cost sprint $id", state, start, complete, complete, capacity, source)

/** Seeds the world, runs [block], and removes everything it wrote. */
internal suspend fun withCostsSprintsWorld(block: suspend (CostsSprintsWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "costs-sprints", enabled = false)
    val team = TestTeams.seed(SyncedStubFixture.unique("cs-team"))
    val other = TestTeams.seed(SyncedStubFixture.unique("cs-other"))
    val tag = SyncedStubFixture.unique("cs")
    val w = CostsSprintsWorld(connId, team, other, "$tag-alice", "$tag-bob", "$tag-carol", "$tag-dave")
    val (alice, bob) = w.alice to w.bob
    val e = w.epics
    val epicRows = e.mapIndexed { i, id -> DimEpicRow(id, "CST-${i + 1}", "Epic ${i + 1}", "AAA", null, "IN_PROGRESS", null, null) }
    val taskRows = (0 until CostsSprintsWorld.EPIC_COUNT).map { i ->
        DimTaskRow(101L + i, "CST-T${101 + i}", "Story", "Development", null, "NONE", false, null, "AAA", e[i])
    } + listOf(
        DimTaskRow(CostsSprintsWorld.SUBTASK, "CST-T109", "Sub-task", "Development", null, "NONE", true, 101L, "AAA", e[0]),
        DimTaskRow(CostsSprintsWorld.NO_EPIC_TASK, "CST-T110", "Story", "Development", null, "NONE", false, null, "AAA", null),
    )
    val epic2 = CostsSprintsWorld.EPIC2_ISSUE
    val above = CostsSprintsWorld.ABOVE_EPIC_ISSUE
    var id = 0L
    fun log(issue: Long, author: String, at: String, md: Double, epic: Long?, activity: String = "Story", authorTeam: UInt? = w.team) =
        csLog(++id, issue, author, authorTeam, if (at.contains('T')) utc(at) else noon(at), md, epic, activity)
    val logs = listOf(
        log(101, alice, "2026-01-06", 4.0, e[0]), log(109, bob, "2026-01-07", 1.0, e[0]),
        log(102, alice, "2026-01-08", 3.0, e[1], activity = "Bug"), log(epic2, bob, "2026-01-09", 0.5, epic2),
        log(103, alice, "2026-01-12", 2.0, e[2]), log(104, bob, "2026-01-12", 1.5, e[3]), log(105, alice, "2026-01-13", 1.0, e[4]),
        log(106, bob, "2026-01-14", 0.75, e[5]), log(107, alice, "2026-01-15", 0.5, e[6]), log(108, bob, "2026-01-15", 0.25, e[7]),
        log(110, alice, "2026-01-16", 0.75, null), log(above, bob, "2026-01-16", 0.25, null),
        log(101, w.carol, "2026-01-08", 8.0, e[0], authorTeam = w.otherTeam), log(101, w.dave, "2026-01-08", 6.0, e[0], authorTeam = null),
        log(101, alice, "2026-01-19T00:00:00Z", 0.5, e[0]), log(102, bob, "2026-01-20", 2.0, e[1]),
        log(107, alice, "2026-01-21", 1.0, e[6]),
        log(109, alice, "2026-01-22", 0.25, e[0]), log(103, bob, "2026-01-29", 0.5, e[2]), log(108, bob, "2026-02-01", 0.5, e[7]),
        log(104, alice, "2026-02-03", 3.0, e[3]), log(above, bob, "2026-02-04", 0.25, null),
        log(101, alice, "2026-01-02", 1.0, e[0]), log(102, alice, "2026-02-08", 2.0, e[1], activity = "Bug"),
        log(103, bob, "2026-02-20", 0.5, e[2]), log(101, alice, "2026-02-11", 1.25, e[0]), log(101, alice, "2025-12-30", 7.0, e[0]),
    )
    val store = MetricsStore(sharedDatabaseForTests())
    val dims = listOf(
        csSprint(CostsSprintsWorld.S1, team, "closed", utc("2026-01-05T00:00:00Z"), utc("2026-01-19T00:00:00Z"), 20.0, "CALENDAR"),
        csSprint(CostsSprintsWorld.S2, team, "closed", utc("2026-01-19T00:00:00Z"), utc("2026-02-02T00:00:00Z"), 18.0, "DEFAULT"),
        csSprint(CostsSprintsWorld.S3, team, "closed", utc("2026-01-28T00:00:00Z"), utc("2026-02-06T00:00:00Z"), null, null),
        csSprint(CostsSprintsWorld.S4, team, "active", utc("2026-02-10T00:00:00Z"), null, null, null),
    )
    suspendTransaction(sharedDatabaseForTests()) {
        store.insertTasks(connId, taskRows, configRevision = 1L)
        store.insertEpics(connId, epicRows, configRevision = 1L)
        store.insertFactWorklog(connId, logs, configRevision = 1L)
        store.insertDimSprints(connId, dims, configRevision = 1L)
        store.insertFactSprint(
            connId,
            dims.map { FactSprintRow(it.sprintId, team, it.completeAt, sprintTotals(emptyList()), it.capacityMd, load = null) },
            1L,
        )
    }
    try {
        block(w)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteSprintFacts(connId)
            store.deleteFactWorklog(connId)
            store.deleteDims(connId)
        }
        cleanUpTeams(listOf(team, other))
    }
}
