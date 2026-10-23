package ch.nokillswit

import ch.nokillswit.metrics.DimTaskRow
import ch.nokillswit.metrics.ItemStageRow
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TaskAssigneeRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlinx.coroutines.flow.toList

/*
 * The hand-built WIP world of the compare-people pushdown tests (`CompareWipPushdownTest`): a fresh DISABLED connection (a succeeded DERIVE
 * on 2026-06-01, so every January day is listed) with three people, level-0 tasks over `item_stage` x `task_assignee`, and the facts that
 * give the held tasks a `started_at`. The shapes it pins (transitions at noon UTC unless noted):
 * - task 1, a1: IN_PROGRESS 2025-12-20 .. Jan 8, WAITING Jan 8 .. Jan 20, then DONE -- an interval crossing the period's left edge and
 *   a stage change mid-interval;
 * - task 2, a1 until the END of Jan 14 and a2 after: IN_PROGRESS Jan 12 .. open -- the reassignment exactly at a day-end instant (a1's,
 *   `(from, to]`) and an open interval crossing the right edge;
 * - task 3, a2: BLOCKED Jan 5 .. Jan 30, then NOT_STARTED -- a long BLOCKED run, then not WIP;
 * - task 4, a1 with a null-account gap Jan 14 .. Jan 16: NOT_STARTED Dec 1 .. Jan 10, then IN_PROGRESS -- not-started days do not
 *   count, an unassigned gap counts for nobody;
 * - task 5, a1 then a2 from Jan 20 noon: IN_PROGRESS Jan 3 .. Jan 25, then DONE -- a mid-day reassignment, a weekend start;
 * - task 6, a1, a SUB-TASK: IN_PROGRESS the whole time -- excluded, only level-0 tasks count;
 * - task 7, a2: IN_PROGRESS Jan 30 .. open -- held at the last day of the period;
 * - task 8, a3: IN_PROGRESS the whole time -- a third person;
 * - task 9, a1: UNMAPPED the whole time -- not a working stage; task 10, a1: DONE the whole time -- not WIP.
 */
internal class CompareWipWorld(val connId: UInt, val a1: String, val a2: String, val a3: String) {
    val store = MetricsStore(sharedDatabaseForTests())

    suspend fun dayEndMs(day: String): Long = suspendTransaction(sharedDatabaseForTests()) {
        val d = MetricsTables.DimDate
        d.selectAll().where { d.day eq day }.toList().single()[d.dayEndMs]
    }

    suspend fun seed() {
        val created = noonUtc("2025-12-01")
        fun at(day: String) = noonUtc(day)
        fun stage(issue: Long, stage: String, status: String, from: String?, to: String?) =
            ItemStageRow(issue, stage, status, from?.let(::at) ?: created, to?.let(::at))
        fun task(id: Long, subtask: Boolean = false) = DimTaskRow(id, "HW-$id", "Story", "Story", null, "NONE", subtask, null, "AAA", null)
        val stages = listOf(
            stage(1, "IN_PROGRESS", "prog", "2025-12-20", "2026-01-08"), stage(1, "WAITING", "wait", "2026-01-08", "2026-01-20"),
            stage(1, "DONE", "done", "2026-01-20", null),
            stage(2, "IN_PROGRESS", "prog", "2026-01-12", null),
            stage(3, "BLOCKED", "blk", "2026-01-05", "2026-01-30"), stage(3, "NOT_STARTED", "todo", "2026-01-30", null),
            stage(4, "NOT_STARTED", "todo", null, "2026-01-10"), stage(4, "IN_PROGRESS", "prog", "2026-01-10", null),
            stage(5, "IN_PROGRESS", "prog", "2026-01-03", "2026-01-25"), stage(5, "DONE", "done", "2026-01-25", null),
            stage(6, "IN_PROGRESS", "prog", null, null),
            stage(7, "IN_PROGRESS", "prog", "2026-01-30", null),
            stage(8, "IN_PROGRESS", "prog", null, null),
            stage(9, "UNMAPPED", "odd", null, null),
            stage(10, "DONE", "done", null, null),
        )
        val dayEnd14 = dayEndMs("2026-01-14")
        val assignees = listOf(
            TaskAssigneeRow(1, a1, created, null),
            TaskAssigneeRow(2, a1, created, dayEnd14), TaskAssigneeRow(2, a2, dayEnd14, null),
            TaskAssigneeRow(3, a2, created, null),
            TaskAssigneeRow(4, a1, created, at("2026-01-14")), TaskAssigneeRow(4, null, at("2026-01-14"), at("2026-01-16")),
            TaskAssigneeRow(4, a1, at("2026-01-16"), null),
            TaskAssigneeRow(5, a1, created, at("2026-01-20")), TaskAssigneeRow(5, a2, at("2026-01-20"), null),
            TaskAssigneeRow(6, a1, created, null), TaskAssigneeRow(7, a2, created, null), TaskAssigneeRow(8, a3, created, null),
            TaskAssigneeRow(9, a1, created, null), TaskAssigneeRow(10, a1, created, null),
        )
        suspendTransaction(sharedDatabaseForTests()) {
            store.insertTasks(connId, (1L..10L).map { task(it, subtask = it == 6L) }, 1L)
            store.insertItemStage(connId, stages)
            store.insertTaskAssignee(connId, assignees)
            store.replaceFactTaskDelivery(
                connId,
                listOf(
                    handTask(2, startedAt = at("2026-01-12")), handTask(3, startedAt = at("2026-01-05")),
                    handTask(4, startedAt = at("2026-01-10")), handTask(7, startedAt = at("2026-01-30")),
                ),
                configRevision = 1L,
            )
        }
    }

    suspend fun cleanUp() {
        suspendTransaction(sharedDatabaseForTests()) {
            store.deleteFactTaskDelivery(connId)
            store.deleteBridges(connId)
            store.deleteDims(connId)
            MetricsTables.ItemStage.deleteWhere { MetricsTables.ItemStage.connectionId eq connId }
        }
        deleteDeriveRuns(connId)
    }
}

/** Runs [block] over a freshly seeded [CompareWipWorld], removing what it seeded afterwards. */
internal suspend fun withCompareWipWorld(block: suspend (CompareWipWorld) -> Unit) {
    // dim_date must cover January 2026: the calling class warms the derived fixture in its @BeforeClass (StubFixtureWarmupGuardTest).
    val connId = SyncedStubFixture.createConnection(namePrefix = "compare-wip", enabled = false, backfillFrom = EARLY_BACKFILL)
    insertSucceededDerive(connId, noonUtc("2026-06-01"))
    val world = CompareWipWorld(
        connId, SyncedStubFixture.unique("cwip-a1"), SyncedStubFixture.unique("cwip-a2"), SyncedStubFixture.unique("cwip-a3"),
    )
    world.seed()
    try {
        block(world)
    } finally {
        world.cleanUp()
    }
}
