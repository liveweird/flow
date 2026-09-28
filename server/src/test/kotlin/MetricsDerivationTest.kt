package ch.nokillswit

import ch.nokillswit.ingest.DataSourceKind
import ch.nokillswit.ingest.SyncJobClaim
import ch.nokillswit.ingest.SyncJobKind
import ch.nokillswit.ingest.SyncJobRunContext
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsDeriver
import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.TeamMembershipService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `metrics/MetricsDeriver.kt` (v0.3.0 M3 commit 7): a full SYNC → PROCESS → DERIVE over a CLONE of
 * `SyncedStubFixture`'s shared connection (never the shared connection itself —
 * `.claude/docs/testing.md` "Shared synced fixture") with a pinned clock, asserting invariant 3
 * (status/stage intervals tile each item's lifetime) and invariant 4 (`started_at <= done_at`;
 * `done_at` set only while the current stage is DONE) as SQL sweeps over the PERSISTED
 * `metrics.item_stage`/`metrics.fact_task_delivery` rows. Runs with the computed DEFAULT
 * configuration (no admin metrics-config/team-membership setup) — `MetricsConfigService
 * .effectiveConfig` already falls back to safe defaults derived from `norm`/the data profile, so a
 * DERIVE run needs no prior admin configuration to succeed.
 */
class MetricsDerivationTest {
    private fun workItems() = SyncedStubFixture.workItems()
    private fun dataSources() = SyncedStubFixture.dataSources()
    private fun metricsConfig() =
        MetricsConfigService(sharedDatabaseForTests(), workItems(), dataSources(), SyncJobsService(sharedDatabaseForTests(), 3))
    private fun teamMembership(config: MetricsConfigService) = TeamMembershipService(sharedDatabaseForTests(), config)
    private fun metricsStore() = MetricsStore(sharedDatabaseForTests())

    private suspend fun clonedProcessedConnection(): UInt {
        val sharedConnId = SyncedStubFixture.connectionId()
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-derive-clone")
        SyncedStubFixture.cloneRawData(sharedConnId, connId)
        val store = SyncedStubFixture.rawStore()
        val items = workItems()
        store.markAllNeedsProcessing(connId)
        JiraProcessStream(store, items).run(SyncedStubFixture.freshContext(connId))
        return connId
    }

    @Test
    fun `DERIVE over a freshly processed connection writes non-empty fact_task_delivery satisfying invariants 3 and 4`() = runBlocking {
        val connId = clonedProcessedConnection()
        val config = metricsConfig()
        val deriver = MetricsDeriver(
            workItems(),
            config,
            teamMembership(config),
            metricsStore(),
            sharedDatabaseForTests(),
            clock = { PINNED_NOW },
        )
        val claim = SyncJobClaim(
            id = 1u,
            connectionId = connId,
            connectorKind = DataSourceKind.JIRA_CLOUD,
            kind = SyncJobKind.DERIVE,
            attempt = 1,
            maxAttempts = 3,
            syncIntervalMinutes = 60,
        )

        deriver.derive(SyncJobRunContext(claim) { _, _ -> true })

        val factRows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.FactTaskDelivery.selectAll().where { MetricsStore.FactTaskDelivery.connectionId eq connId }.toList()
        }
        assertTrue(factRows.isNotEmpty(), "fact_task_delivery must be non-empty after deriving a real processed connection")

        // Invariant 4: started_at <= done_at when both set; done_at set only while current stage is DONE.
        factRows.forEach { row ->
            val startedAt = row[MetricsStore.FactTaskDelivery.startedAt]
            val doneAt = row[MetricsStore.FactTaskDelivery.doneAt]
            val currentStage = row[MetricsStore.FactTaskDelivery.currentStage]
            if (doneAt != null) {
                assertTrue(startedAt != null && startedAt <= doneAt, "done_at requires a started_at no later than it")
                assertEquals("DONE", currentStage, "done_at is set only while the current stage is DONE")
            }
        }

        // Invariant 3: metrics.item_stage tiles each issue's lifetime the same way norm's status
        // intervals do — contiguous, exactly one open (valid_to IS NULL) interval per issue.
        // Ordered by (issueId, id) rather than (issueId, validFrom): zero-length intervals are
        // allowed (the same rule `norm`'s own status tiling follows), so two rows can legitimately
        // share one `validFrom` — the surrogate `id` preserves each issue's own insertion order
        // (`MetricsDeriver.compose` appends one issue's stage rows contiguously before the next).
        val stageRows = suspendTransaction(sharedDatabaseForTests()) {
            MetricsStore.ItemStage.selectAll().where { MetricsStore.ItemStage.connectionId eq connId }
                .orderBy(MetricsStore.ItemStage.issueId to SortOrder.ASC, MetricsStore.ItemStage.id to SortOrder.ASC)
                .toList()
        }
        assertTrue(stageRows.isNotEmpty(), "metrics.item_stage must be non-empty after DERIVE")
        val byIssue = stageRows.groupBy { it[MetricsStore.ItemStage.issueId] }
        byIssue.forEach { (issueId, rows) ->
            val openCount = rows.count { it[MetricsStore.ItemStage.validTo] == null }
            assertEquals(1, openCount, "issue $issueId must have exactly one open item_stage interval")
            rows.zipWithNext().forEach { (a, b) ->
                assertEquals(
                    a[MetricsStore.ItemStage.validTo],
                    b[MetricsStore.ItemStage.validFrom],
                    "issue $issueId's item_stage intervals must tile contiguously",
                )
            }
        }
    }

    private companion object {
        const val PINNED_NOW = 1_772_668_800_000L // 2026-09-02T00:00:00Z, per the v0.3.0 plan's pinned-clock convention
    }
}
