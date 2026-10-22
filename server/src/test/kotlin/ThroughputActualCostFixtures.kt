package ch.nokillswit

import ch.nokillswit.metrics.DimSprintRow
import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.FactSprintRow
import ch.nokillswit.metrics.FactSprintScopeRow
import ch.nokillswit.metrics.FactTaskDeliveryRow
import ch.nokillswit.metrics.FactWorklogRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.SprintScopeItem
import ch.nokillswit.metrics.sprintTotals
import java.time.LocalDate
import java.time.ZoneOffset
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/*
 * The hand-built world of the Throughput ACTUAL COST tests (`ReportThroughputActualCostHandBuiltTest`): one disabled connection, two teams,
 * level-0 tasks A..F with a sub-task of B, one closed sprint with a removed item, and worklogs placed (noon UTC, far from any local
 * midnight) so every rule has a literal answer. The expected figures live in the test; the layout is documented here.
 *
 *   task A (101) Story, credit T: started Jan 6, done Jan 8   -> delivered in week Jan 5 (its later logs are out: done before)
 *   task B (102) Story, credit T: started Jan 7, done Jan 15  -> not done before week Jan 5, delivered in week Jan 12; sub-task B1 (103)
 *   task C (104) Bug, OPEN, current team T: started Jan 13    -> open: counts from the first bucket on, even its Jan 6 log
 *   task D (105) Story, credit T: started Dec 18, done Dec 20 -> done before the range
 *   task E (106) Story, OPEN, current team T, never started   -> still To Do, yet its logs count (no started_at condition)
 *   task F (107) Story, credit T2: started Jan 7, done Jan 8  -> another team's delivery
 *   issue 9999: no task (an epic / an issue above the epic level); task R (108) is in no scope of the sprint but was removed from it
 *   sprint 930001 of T: Jan 6 00:00Z .. Jan 20 00:00Z; final scope A, B, C; R was removed
 *   sprint 930002 of T: ACTIVE since Jan 15 (an open sprint ends now), scope C; sprint 930003 of T: not started until 2100, scope C
 *   sprint 930004 of T: Jan 10 00:00Z .. Jan 17 00:00Z, OVERLAPPING sprint 930001, scope C
 */
internal class ActualCostWorld(
    val connId: UInt,
    val team: UInt,
    val otherTeam: UInt,
    val alice: String,
    val bob: String,
) {
    val sprintId = SPRINT_ID
    val openSprintId = OPEN_SPRINT_ID
    val futureSprintId = FUTURE_SPRINT_ID
    val overlapSprintId = OVERLAP_SPRINT_ID
    val range = "connectionId=$connId&from=2026-01-05&to=2026-01-25"

    companion object {
        const val SPRINT_ID = 930_001L
        const val OPEN_SPRINT_ID = 930_002L
        const val FUTURE_SPRINT_ID = 930_003L
        const val OVERLAP_SPRINT_ID = 930_004L
        const val A = 101L
        const val B = 102L
        const val B1 = 103L
        const val C = 104L
        const val D = 105L
        const val E = 106L
        const val F = 107L
        const val R = 108L
        const val NO_TASK = 9_999L
    }
}

private const val HALF_DAY_MS = 12 * 3_600_000L

/** 2100-01-01T00:00:00Z: a sprint that has not started. */
private const val FAR_FUTURE_MS = 4_102_444_800_000L

/** Noon UTC of an ISO day in January 2026 (or December 2025). */
internal fun noon(day: String): Long = LocalDate.parse(day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

private fun acTask(
    issueId: Long,
    startedAt: Long?,
    doneAt: Long?,
    credit: UInt?,
    current: UInt?,
    activity: String = "Story",
) = FactTaskDeliveryRow(
    issueId = issueId, issueKey = "AC-$issueId", createdAt = 0, startedAt = startedAt, doneAt = doneAt, reopenCount = 0,
    estimateAtStartMd = null, estimateAtDoneMd = 3.0, estimateCurrentMd = null, estimateSource = "OWN",
    estimateChangesAfterStart = 0, estimatedLate = false, estimatedAfterDone = false, actualMd = 0.0, hasWorklogs = false,
    blockedMs = 0, blockedWorkingDays = 0.0, cycleMs = null, cycleWorkingDays = null, leadMs = null, leadWorkingDays = null,
    activeMs = 0, waitMs = 0, assigneeAccountIdAtDone = null, assigneeTeamIdAtDone = null, sprintIdAtDone = null,
    sprintTeamIdAtDone = null, creditTeamId = if (doneAt != null) credit else null, currentTeamId = current,
    currentAssigneeAccountId = null, domainKey = "AAA", epicId = null, epicDomainKey = "AAA", crossDomain = false,
    activityType = activity, workCategory = null, isSubtask = false, parentTaskId = null,
    currentStage = if (doneAt == null) "IN_PROGRESS" else "DONE", flags = emptyList(),
)

private fun acLog(id: Long, issue: Long, author: String?, day: String, md: Double) = FactWorklogRow(
    worklogId = id, issueId = issue, authorAccountId = author, authorTeamId = null, startedAt = noon(day), createdAt = null,
    lateMs = null, md = md, taskDomainKey = "AAA", epicId = null, epicDomainKey = "AAA", activityType = "Story", workCategory = null,
    sprintIdAtStarted = null, sprintTeamIdAtStarted = null, foreignWork = false, assigneeAccountIdAtStarted = null,
    assigneeTeamIdAtStarted = null,
)

private fun dimSprint(id: Long, team: UInt, state: String, startAt: Long, completeAt: Long?) =
    DimSprintRow(id, id, team, "AC sprint $id", state, startAt, completeAt, completeAt, null, null)

private fun scopeItem(issue: Long, done: Boolean, removed: Boolean = false) = SprintScopeItem(
    issueId = issue, addedAtMs = null, removedAtMs = if (removed) 2L else null, committed = true, inScopeAtClose = !removed,
    estimateAtCommitmentMd = 3.0, estimateAtCloseMd = if (removed) null else 3.0, estimateAtDoneMd = if (done) 3.0 else null,
    assigneeAtCommitment = null, doneInSprint = done && !removed, carriedOver = !done && !removed, dropped = false,
)

/** Seeds the world, runs [block], and removes everything it wrote. */
internal suspend fun withActualCostWorld(block: suspend (ActualCostWorld) -> Unit) {
    val connId = SyncedStubFixture.createConnection(namePrefix = "throughput-ac", enabled = false)
    val team = TestTeams.seed(SyncedStubFixture.unique("ac-team"))
    val other = TestTeams.seed(SyncedStubFixture.unique("ac-other"))
    val tag = SyncedStubFixture.unique("ac")
    val world = ActualCostWorld(connId, team, other, "$tag-alice", "$tag-bob")
    val (alice, bob) = world.alice to world.bob
    val store = MetricsStore(sharedDatabaseForTests())
    val tasks = listOf(
        acTask(ActualCostWorld.A, noon("2026-01-06"), noon("2026-01-08"), team, team),
        acTask(ActualCostWorld.B, noon("2026-01-07"), noon("2026-01-15"), team, team),
        acTask(ActualCostWorld.C, noon("2026-01-13"), null, null, team, activity = "Bug"),
        acTask(ActualCostWorld.D, noon("2025-12-18"), noon("2025-12-20"), team, team),
        acTask(ActualCostWorld.E, null, null, null, team),
        acTask(ActualCostWorld.F, noon("2026-01-07"), noon("2026-01-08"), other, other),
    )
    val logs = listOf(
        acLog(1, ActualCostWorld.A, alice, "2026-01-06", 1.0),
        acLog(2, ActualCostWorld.A, bob, "2026-01-07", 0.5),
        acLog(3, ActualCostWorld.A, null, "2026-01-07", 0.5),
        acLog(4, ActualCostWorld.A, alice, "2026-01-09", 0.25), // after done, same week
        acLog(5, ActualCostWorld.A, alice, "2026-01-13", 2.0), // after done, a later week
        acLog(6, ActualCostWorld.B, alice, "2026-01-08", 1.5),
        acLog(7, ActualCostWorld.B, bob, "2026-01-14", 2.0),
        acLog(8, ActualCostWorld.B1, alice, "2026-01-14", 0.75), // a sub-task's: counts toward B
        acLog(9, ActualCostWorld.C, alice, "2026-01-06", 9.0), // before C started
        acLog(10, ActualCostWorld.C, bob, "2026-01-14", 1.0),
        acLog(11, ActualCostWorld.C, bob, "2026-01-20", 3.0),
        acLog(12, ActualCostWorld.D, alice, "2026-01-06", 7.0), // done before the range
        acLog(13, ActualCostWorld.E, bob, "2026-01-14", 5.0), // never started
        acLog(14, ActualCostWorld.F, alice, "2026-01-07", 4.0), // another team's item
        acLog(15, ActualCostWorld.NO_TASK, alice, "2026-01-07", 6.0), // no task at all
        acLog(16, ActualCostWorld.R, alice, "2026-01-08", 6.0), // removed from the sprint
    )
    val scope = listOf(
        scopeItem(ActualCostWorld.A, done = true), scopeItem(ActualCostWorld.B, done = true),
        scopeItem(ActualCostWorld.C, done = false), scopeItem(ActualCostWorld.R, done = false, removed = true),
    )
    val openScope = listOf(scopeItem(ActualCostWorld.C, done = false))
    val start = noon("2026-01-06") - HALF_DAY_MS
    val end = start + 14 * 86_400_000L
    val overlapStart = noon("2026-01-10") - HALF_DAY_MS
    val overlapEnd = overlapStart + 7 * 86_400_000L
    suspendTransaction(sharedDatabaseForTests()) {
        store.insertTasks(
            connId,
            listOf(DimTaskRow(ActualCostWorld.B1, "AC-103", "Sub-task", "Development", null, "NONE", true, ActualCostWorld.B, "AAA", null)),
            configRevision = 1L,
        )
        store.replaceFactTaskDelivery(connId, tasks, configRevision = 1L)
        store.insertFactWorklog(connId, logs, configRevision = 1L)
        store.insertDimSprints(
            connId,
            listOf(
                dimSprint(ActualCostWorld.SPRINT_ID, team, "closed", start, end),
                dimSprint(ActualCostWorld.OPEN_SPRINT_ID, team, "active", noon("2026-01-15") - HALF_DAY_MS, null),
                dimSprint(ActualCostWorld.FUTURE_SPRINT_ID, team, "future", FAR_FUTURE_MS, null),
                dimSprint(ActualCostWorld.OVERLAP_SPRINT_ID, team, "closed", overlapStart, overlapEnd),
            ),
            configRevision = 1L,
        )
        store.insertFactSprint(
            connId,
            listOf(
                FactSprintRow(ActualCostWorld.SPRINT_ID, team, end, sprintTotals(scope), capacityMd = null, load = null),
                FactSprintRow(ActualCostWorld.OPEN_SPRINT_ID, team, null, sprintTotals(openScope), capacityMd = null, load = null),
                FactSprintRow(ActualCostWorld.FUTURE_SPRINT_ID, team, null, sprintTotals(openScope), capacityMd = null, load = null),
                FactSprintRow(ActualCostWorld.OVERLAP_SPRINT_ID, team, overlapEnd, sprintTotals(openScope), capacityMd = null, load = null),
            ),
            1L,
        )
        store.insertFactSprintScope(
            connId,
            scope.map { FactSprintScopeRow(ActualCostWorld.SPRINT_ID, it) } +
                openScope.map { FactSprintScopeRow(ActualCostWorld.OPEN_SPRINT_ID, it) } +
                openScope.map { FactSprintScopeRow(ActualCostWorld.FUTURE_SPRINT_ID, it) } +
                openScope.map { FactSprintScopeRow(ActualCostWorld.OVERLAP_SPRINT_ID, it) },
            1L,
        )
    }
    try {
        block(world)
    } finally {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteSprintFacts(connId)
            store.deleteFactWorklog(connId)
            store.deleteFactTaskDelivery(connId)
            store.deleteDims(connId)
        }
        cleanUpTeams(listOf(team, other))
    }
}
