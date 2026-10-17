package ch.nokillswit

import ch.nokillswit.metrics.MetricsStage
import ch.nokillswit.metrics.MetricsStatusStage
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.TeamMembershipService
import ch.nokillswit.metrics.asRequest
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
import kotlin.test.assertTrue

/**
 * The BLOCKED stage end to end (domain-model A36, `.claude/docs/metrics.md` "The BLOCKED stage"): a PRIVATE disabled clone of the
 * stub is derived under its default configuration (no status defaults to BLOCKED), then the stub's "Blocked" and "On Hold" statuses
 * (both Jira category IN_PROGRESS) are mapped to BLOCKED through the config service and it is derived again. Every figure is graded
 * against an INDEPENDENT recomputation from the persisted `item_stage` and Flagged intervals (plain interval arithmetic, never the
 * deriver's kernels): `started_at` (an entry into BLOCKED starts the item), `blocked_ms` (the BLOCKED spans unioned with Flagged,
 * no double count where they overlap), active/wait (BLOCKED time is wait, only a Flagged span is subtracted from active), the WIP
 * band, the backlog (unchanged) and Aging WIP (listed, `blocked`, never `waiting`). Deep dive execution excluding BLOCKED is pinned
 * on hand-built rows in `ReportDeepDiveTest`. Never touches the shared fixtures.
 */
class MetricsBlockedStageTest {
    private val blockedStatusNames = setOf("Blocked", "On Hold")

    private data class TaskFacts(
        val startedAt: Long?,
        val doneAt: Long?,
        val blockedMs: Long,
        val activeMs: Long,
        val waitMs: Long,
        val cycleMs: Long?,
        val currentStage: String,
    )

    private data class Span(val from: Long, val to: Long)

    private suspend fun tasks(connId: UInt): Map<Long, TaskFacts> = suspendTransaction(sharedDatabaseForTests()) {
        val t = MetricsTables.FactTaskDelivery
        t.selectAll().where { (t.connectionId eq connId) and (t.isSubtask eq false) }.toList().associate {
            it[t.issueId] to TaskFacts(
                it[t.startedAt], it[t.doneAt], it[t.blockedMs], it[t.activeMs], it[t.waitMs], it[t.cycleMs], it[t.currentStage],
            )
        }
    }

    private suspend fun stageSpans(connId: UInt): Map<Long, List<Pair<String, Span>>> = suspendTransaction(sharedDatabaseForTests()) {
        val s = MetricsTables.ItemStage
        s.selectAll().where { s.connectionId eq connId }.toList()
            .groupBy({ it[s.issueId] }) { it[s.stage] to Span(it[s.validFrom], it[s.validTo] ?: Long.MAX_VALUE) }
    }

    private suspend fun flaggedSpans(connId: UInt): Map<Long, List<Span>> = suspendTransaction(sharedDatabaseForTests()) {
        val f = WorkItemStore.FieldIntervals
        f.selectAll().where { (f.connectionId eq connId) and (f.field eq "FLAGGED") and (f.valueId eq "true") }.toList()
            .groupBy({ it[f.issueId] }) { Span(it[f.fromAt], it[f.toAt] ?: Long.MAX_VALUE) }
    }

    private fun clip(spans: List<Span>, from: Long, to: Long): List<Span> =
        spans.map { Span(maxOf(it.from, from), minOf(it.to, to)) }.filter { it.to > it.from }

    /** Overlapping or touching spans collapsed into disjoint ones. */
    private fun union(spans: List<Span>): List<Span> {
        val merged = mutableListOf<Span>()
        for (span in spans.sortedBy { it.from }) {
            val last = merged.lastOrNull()
            if (last != null && span.from <= last.to) {
                merged[merged.lastIndex] = Span(last.from, maxOf(last.to, span.to))
            } else {
                merged += span
            }
        }
        return merged
    }

    private fun overlapMs(a: List<Span>, b: List<Span>): Long =
        a.sumOf { x -> b.sumOf { y -> maxOf(0L, minOf(x.to, y.to) - maxOf(x.from, y.from)) } }

    private fun service(): ReportService {
        val metricsSettings = DerivedStubFixture.metricsSettings()
        return ReportService(sharedDatabaseForTests(), metricsSettings, TeamMembershipService(sharedDatabaseForTests(), metricsSettings))
    }

    private suspend fun aging(connId: UInt): AgingWipReport {
        val filter = ReportFilter(
            period = ReportPeriod.DateRange(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), 0L, 1L),
            level = ReportLevel.UNIT, teamId = null, domainView = DomainView.TASK, domain = null,
            activityType = null, workCategory = null, connectionId = connId,
        )
        return service().agingWip(filter, DerivedStubFixture.PINNED_NOW)
    }

    @Test
    fun `mapping Blocked and On Hold to BLOCKED starts items, adds a WIP band, makes the time wait and lists aging items`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-blocked-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "blocked-stage-team")
        val config = DerivedStubFixture.metricsConfig()

        // Baseline: the default configuration, under which nothing is BLOCKED.
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId, jobId = 1u) }
        val baseWip = readWipRows(connId)
        val baseAging = aging(connId)
        val baseBacklog = readFlowBacklogRows(connId).toSet()
        assertTrue(baseBacklog.isNotEmpty(), "the stub carries backlog figures")
        assertTrue(baseWip.none { it.stage == "BLOCKED" }, "no status defaults to BLOCKED (A36)")
        assertTrue(baseAging.items.none { it.stage == "BLOCKED" })

        val blockedIds = suspendTransaction(sharedDatabaseForTests()) {
            val s = WorkItemStore.Statuses
            s.selectAll().where { s.connectionId eq connId }.toList()
                .filter { it[s.name] in blockedStatusNames }.map { it[s.statusId] }.toSet()
        }
        assertEquals(2, blockedIds.size, "the stub carries both impediment-like statuses")
        val current = config.effectiveConfig(connId)
        config.replaceConfig(
            connId,
            current.asRequest().copy(
                statusStages = current.statusStages.filterNot { it.statusId in blockedIds } +
                    blockedIds.map { MetricsStatusStage(it, MetricsStage.BLOCKED) },
            ),
        )
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId, jobId = 2u) }

        // Every task, graded against the persisted stage and Flagged intervals.
        val facts = tasks(connId)
        val stages = stageSpans(connId)
        val flagged = flaggedSpans(connId)
        var withBlockedStage = 0
        var overlappingFlagged = 0
        for ((issueId, fact) in facts) {
            val rows = stages[issueId].orEmpty()
            val started = rows.filter { it.first in setOf("IN_PROGRESS", "WAITING", "BLOCKED") }.minOfOrNull { it.second.from }
            assertEquals(started, fact.startedAt, "started_at = the first entry into IN_PROGRESS, WAITING or BLOCKED ($issueId)")
            if (started == null) continue
            val end = fact.doneAt ?: DerivedStubFixture.PINNED_NOW
            val blockedStage = rows.filter { it.first == "BLOCKED" }.map { it.second }
            if (blockedStage.isNotEmpty()) withBlockedStage++
            val flaggedHere = flagged[issueId].orEmpty()
            if (overlapMs(clip(blockedStage, started, end), clip(flaggedHere, started, end)) > 0) overlappingFlagged++
            val blockedUnion = union(clip(blockedStage + flaggedHere, started, end))
            val blockedMs = blockedUnion.sumOf { it.to - it.from }
            assertEquals(blockedMs, fact.blockedMs, "blocked_ms = BLOCKED-stage spans union Flagged ($issueId)")
            if (fact.doneAt != null) {
                val inProgress = clip(rows.filter { it.first == "IN_PROGRESS" }.map { it.second }, started, end)
                val expectedActive = inProgress.sumOf { it.to - it.from } - overlapMs(inProgress, blockedUnion)
                assertEquals(expectedActive, fact.activeMs, "active = IN_PROGRESS minus blocked-while-IN_PROGRESS ($issueId)")
                assertEquals(fact.doneAt - started - expectedActive, fact.waitMs, "BLOCKED time is wait ($issueId)")
                assertEquals(fact.cycleMs, fact.activeMs + fact.waitMs, "active + wait = cycle ($issueId)")
            }
        }
        assertTrue(withBlockedStage > 0, "the stub's tasks sat in the mapped statuses")
        assertTrue(overlappingFlagged > 0, "some task is both Flagged and in a BLOCKED stage at once: the overlap must not count twice")

        // WIP: a BLOCKED band appears and in progress plus blocked is exactly the old in-progress (both statuses were IN_PROGRESS).
        val wip = readWipRows(connId)
        assertTrue(wip.any { it.stage == "BLOCKED" && it.count > 0 }, "the aggregate carries BLOCKED rows")
        fun total(rows: List<WipRow>, stages: Set<String>) =
            rows.filter { it.stage in stages }.groupBy { Triple(it.scopeKind, it.scopeId, it.day) to it.itemKind }
                .mapValues { (_, group) -> group.sumOf { it.count } }
        assertEquals(
            total(baseWip, setOf("IN_PROGRESS")),
            total(wip, setOf("IN_PROGRESS", "BLOCKED")),
            "WIP = in progress + waiting + blocked: re-mapping a status moves items between the bands, never in or out of WIP",
        )
        assertEquals(baseBacklog, readFlowBacklogRows(connId).toSet(), "re-mapping to BLOCKED leaves the backlog untouched (invariant 9)")

        // Aging WIP: the same open items; the ones now in BLOCKED are blocked, with their own stage, and never waiting.
        val aged = aging(connId)
        assertEquals(baseAging.items.size, aged.items.size, "re-mapping Blocked/On Hold does not change which items are aging")
        val agedBlockedStage = aged.items.filter { it.stage == "BLOCKED" }
        assertTrue(agedBlockedStage.isNotEmpty(), "the stub has open items sitting in an impediment-like status")
        assertTrue(agedBlockedStage.none { it.waiting }, "a BLOCKED-stage item is never waiting")
        // Blocked is read at the derive clock: the stub also holds items that start AFTER the pinned clock, which have no blocked span yet.
        val startedByClock = agedBlockedStage.filter { it.startedAt < DerivedStubFixture.PINNED_NOW }
        assertTrue(startedByClock.isNotEmpty(), "some BLOCKED-stage items started before the derive clock")
        assertTrue(startedByClock.all { it.blocked }, "a BLOCKED-stage item is blocked once it has started")
        assertTrue(aged.items.none { it.waiting }, "nothing is mapped to WAITING here")
        val openBlockedTasks = facts.filter { (_, f) -> f.currentStage == "BLOCKED" && f.doneAt == null && f.startedAt != null }
        assertEquals(openBlockedTasks.size, agedBlockedStage.count { it.itemKind == "TASK" }, "aging lists every open BLOCKED task")
    }
}
