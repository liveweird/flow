package ch.nokillswit

import ch.nokillswit.metrics.DataSourceMetricsConfigRequest
import ch.nokillswit.metrics.MetricsStage
import ch.nokillswit.metrics.MetricsStatusStage
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.norm.WorkItemStore
import ch.nokillswit.reports.AgingWipReport
import ch.nokillswit.reports.DomainView
import ch.nokillswit.reports.ReportFilter
import ch.nokillswit.reports.ReportLevel
import ch.nokillswit.reports.ReportPeriod
import ch.nokillswit.reports.ReportService
import ch.nokillswit.reports.agingWip
import java.time.LocalDate
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The WAITING stage end to end (domain-model A30, `.claude/docs/metrics.md` "The WAITING stage"): a PRIVATE disabled clone of
 * the stub is derived under its default configuration (no status defaults to WAITING), then the stub's queue-like statuses
 * "In Review" and "Waiting" are mapped to WAITING through the config service and it is derived again. WAITING time must stop
 * being active (flow efficiency), WIP must gain WAITING rows without changing the in-progress-plus-waiting total, and
 * aging WIP must keep the same items, marking the ones that now sit in WAITING. Never touches the shared fixtures.
 */
class MetricsWaitingStageTest {
    private val waitingStatusNames = setOf("In Review", "Waiting")

    private data class TaskFacts(val startedAt: Long?, val doneAt: Long?, val activeMs: Long, val waitMs: Long, val cycleMs: Long?)

    private suspend fun doneTasks(connId: UInt): Map<Long, TaskFacts> = suspendTransaction(sharedDatabaseForTests()) {
        val t = MetricsTables.FactTaskDelivery
        t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList()
            .filter { it[t.doneAt] != null }
            .associate { it[t.issueId] to TaskFacts(it[t.startedAt], it[t.doneAt], it[t.activeMs], it[t.waitMs], it[t.cycleMs]) }
    }

    /** For each DONE task, the time its WAITING stage intervals spend inside `[started_at, done_at)`. */
    private suspend fun waitingMsInCycle(connId: UInt, tasks: Map<Long, TaskFacts>): Map<Long, Long> =
        suspendTransaction(sharedDatabaseForTests()) {
            val s = MetricsTables.ItemStage
            s.selectAll().where { (s.connectionId eq connId) and (s.stage eq "WAITING") }.toList()
                .groupBy { it[s.issueId] }
                .mapValues { (issueId, rows) ->
                    val facts = tasks[issueId]
                    if (facts?.startedAt == null || facts.doneAt == null) {
                        0L
                    } else {
                        rows.sumOf {
                            val from = maxOf(it[s.validFrom], facts.startedAt)
                            val to = minOf(it[s.validTo] ?: facts.doneAt, facts.doneAt)
                            maxOf(0L, to - from)
                        }
                    }
                }
        }

    private suspend fun blockedIssueIds(connId: UInt): Set<Long> = suspendTransaction(sharedDatabaseForTests()) {
        val b = MetricsTables.ItemBlocked
        b.selectAll().where { b.connectionId eq connId }.toList().map { it[b.issueId] }.toSet()
    }

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    private suspend fun aging(connId: UInt): AgingWipReport {
        val filter = ReportFilter(
            period = ReportPeriod.DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 0L, 1L),
            level = ReportLevel.UNIT, teamId = null, accountId = null, domainView = DomainView.TASK, domain = null,
            activityType = null, workCategory = null, connectionId = connId,
        )
        return service().agingWip(filter, DerivedStubFixture.PINNED_NOW)
    }

    @Test
    fun `mapping In Review and Waiting to WAITING moves wait out of active, adds WIP rows and marks aging items`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-waiting-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "waiting-stage-team")
        val config = DerivedStubFixture.metricsConfig()

        // Baseline: the default configuration, under which nothing is WAITING.
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId, jobId = 1u) }
        val baseTasks = doneTasks(connId)
        val baseWip = readWipRows(connId)
        val baseAging = aging(connId)
        assertTrue(baseWip.none { it.stage == "WAITING" }, "no status defaults to WAITING (A30)")
        assertTrue(baseAging.items.none { it.waiting }, "no item is waiting under the default configuration")

        val waitingIds = suspendTransaction(sharedDatabaseForTests()) {
            val s = WorkItemStore.Statuses
            s.selectAll().where { s.connectionId eq connId }.toList()
                .filter { it[s.name] in waitingStatusNames }.map { it[s.statusId] }.toSet()
        }
        assertEquals(2, waitingIds.size, "the stub carries both queue-like statuses")
        val current = config.effectiveConfig(connId)
        config.replaceConfig(
            connId,
            DataSourceMetricsConfigRequest(
                statusStages = current.statusStages.filterNot { it.statusId in waitingIds } +
                    waitingIds.map { MetricsStatusStage(it, MetricsStage.WAITING) },
                domainStatusStages = current.domainStatusStages,
                fields = current.fields,
                domains = current.domains,
                boards = current.boards,
                activityTypes = current.activityTypes,
                workCategories = current.workCategories,
                blockedStatuses = current.blockedStatuses,
                sprintCapacities = current.sprintCapacities,
            ),
        )
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId, jobId = 2u) }

        // Flow efficiency: no task is MORE active, and the drop equals the WAITING time inside the cycle for every task with no
        // blocked time (blocked time inside WAITING was never subtracted, so a blocked task's drop is at most that time).
        val tasks = doneTasks(connId)
        val waitingMs = waitingMsInCycle(connId, tasks)
        val blocked = blockedIssueIds(connId)
        assertEquals(baseTasks.keys, tasks.keys, "the same tasks are delivered")
        assertTrue(waitingMs.values.sum() > 0, "the stub's delivered tasks sat in the mapped statuses")
        for ((issueId, now) in tasks) {
            val before = baseTasks.getValue(issueId)
            assertTrue(now.startedAt!! <= before.startedAt!!, "WAITING can only start an item earlier ($issueId)")
            assertEquals(now.cycleMs, now.activeMs + now.waitMs, "active + wait = cycle ($issueId)")
            if (now.startedAt == before.startedAt) {
                val drop = before.activeMs - now.activeMs
                val waited = waitingMs[issueId] ?: 0L
                assertTrue(drop in 0..waited, "active drops by at most the WAITING time ($issueId)")
                if (issueId !in blocked) {
                    assertEquals(waited, drop, "an unblocked task's active drops by exactly its WAITING time ($issueId)")
                }
            }
        }
        assertTrue(tasks.values.sumOf { it.activeMs } < baseTasks.values.sumOf { it.activeMs }, "flow efficiency's active time drops")
        assertNotEquals(baseTasks.values.sumOf { it.waitMs }, tasks.values.sumOf { it.waitMs })

        // WIP: WAITING rows appear, and in-progress plus waiting is exactly the old in-progress.
        val wip = readWipRows(connId)
        assertTrue(wip.any { it.stage == "WAITING" && it.count > 0 }, "the aggregate carries WAITING rows")
        fun total(rows: List<WipRow>, stages: Set<String>) =
            rows.filter { it.stage in stages }.groupBy { Triple(it.scopeKind, it.scopeId, it.day) to it.itemKind }
                .mapValues { (_, group) -> group.sumOf { it.count } }
        assertEquals(
            total(baseWip, setOf("IN_PROGRESS")),
            total(wip, setOf("IN_PROGRESS", "WAITING")),
            "WIP = in progress + waiting: re-mapping a status moves items between the two, never in or out of WIP",
        )

        // Aging WIP: the same open items (started + not done), the ones now in WAITING marked.
        val aged = aging(connId)
        assertEquals(baseAging.items.size, aged.items.size, "re-mapping In Review/Waiting does not change which items are aging")
        assertTrue(aged.items.any { it.waiting }, "the stub has open items sitting in a queue-like status")
        suspendTransaction(sharedDatabaseForTests()) {
            val t = MetricsTables.FactTaskDelivery
            val stageByKey = t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList()
            val waitingTasks = stageByKey.filter { it[t.currentStage] == "WAITING" && it[t.doneAt] == null && it[t.startedAt] != null }
            assertEquals(waitingTasks.size, aged.items.count { it.itemKind == "TASK" && it.waiting }, "waiting = the open WAITING tasks")
        }
    }
}
