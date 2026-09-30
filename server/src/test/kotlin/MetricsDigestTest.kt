package ch.nokillswit

import ch.nokillswit.jira.JiraProcessStream
import ch.nokillswit.metrics.MetricsTables
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Invariant 12 — "every live number is reproducible from `norm` + one configuration revision" —
 * proven over the PERSISTED `metrics.*` rows with the reprocess-digest pattern
 * (`.claude/docs/testing.md`): [DerivedStubFixture.metricsDigest] hashes every derived table, so a
 * re-derive (or a REPROCESS followed by a re-derive) that reordered, reworded, re-rounded or
 * re-attributed a single row fails here, not silently in a report.
 *
 * Both tests own a PRIVATE disabled clone ([SyncedStubFixture.cloneProcessedData], raw AND
 * processed `norm.*` rows) — the shared fixtures are never touched — and derive under the fixture's
 * pinned clock and `hoursPerDay`, inside ONE `withMetricsSettings` block so every derive stamps the
 * SAME `config_revision` (each wrapper call would bump it, and every derived row carries it).
 */
class MetricsDigestTest {
    private suspend fun snapshotRowCount(connId: UInt): Int = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.FactSprintSnapshot.selectAll().where { MetricsTables.FactSprintSnapshot.connectionId eq connId }.toList().size
    }

    /** A private, disabled, FLO-board-mapped processed clone — the same setup `DerivedStubFixture` derives. */
    private suspend fun preparedClone(teamPrefix: String): UInt {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-digest-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), teamPrefix)
        return connId
    }

    @Test
    fun `re-deriving the same norm under the same config revision yields a byte-identical metrics digest`() = runBlocking {
        val connId = preparedClone("digest-rederive-team")
        val config = DerivedStubFixture.metricsConfig()

        val run = DerivedStubFixture.withPinnedSettings(config) {
            DerivedStubFixture.derivePinned(connId, config, jobId = 1u)
            val first = DerivedStubFixture.metricsDigest(connId, includeDimDate = true)
            val firstSnapshots = snapshotRowCount(connId)

            DerivedStubFixture.derivePinned(connId, config, jobId = 2u)
            TwoDerives(first, firstSnapshots, DerivedStubFixture.metricsDigest(connId, includeDimDate = true), snapshotRowCount(connId))
        }

        assertTrue(
            run.snapshotsAfterFirst > 0,
            "the FLO board is team-mapped, so the first DERIVE must have frozen at least one closed sprint",
        )
        assertEquals(
            run.snapshotsAfterFirst,
            run.snapshotsAfterSecond,
            "a second DERIVE must never write a second fact_sprint_snapshot row",
        )
        assertEquals(
            run.firstDigest,
            run.secondDigest,
            "a re-DERIVE over the SAME norm rows and config revision must write byte-identical metrics rows (invariant 12)",
        )
    }

    @Test
    fun `REPROCESS then DERIVE reproduces the same metrics digest`() = runBlocking {
        val connId = preparedClone("digest-reprocess-team")
        val config = DerivedStubFixture.metricsConfig()
        val store = SyncedStubFixture.rawStore()
        val items = SyncedStubFixture.workItems()

        val (before, after) = DerivedStubFixture.withPinnedSettings(config) {
            DerivedStubFixture.derivePinned(connId, config, jobId = 1u)
            val digestBefore = DerivedStubFixture.metricsDigest(connId, includeDimDate = true)

            // The clone reproduces the shared connection's POST-process raw state (needs_processing =
            // false), so — exactly like a real REPROCESS job (`JiraConnector.runReprocess`) — every
            // issue is flagged again before PROCESS has anything to rebuild.
            store.markAllNeedsProcessing(connId)
            JiraProcessStream(store, items).run(SyncedStubFixture.freshContext(connId))

            DerivedStubFixture.derivePinned(connId, config, jobId = 2u)
            digestBefore to DerivedStubFixture.metricsDigest(connId, includeDimDate = true)
        }

        assertEquals(
            before,
            after,
            "REPROCESS (rebuilding norm from raw) followed by a re-DERIVE must reproduce the same metrics rows (invariant 12)",
        )
    }

    @Test
    fun `the metrics digest is sensitive - a nudged fact value or a deleted bridge row changes it`() = runBlocking {
        val connId = preparedClone("digest-sensitivity-team")
        val config = DerivedStubFixture.metricsConfig()
        DerivedStubFixture.withPinnedSettings(config) { DerivedStubFixture.derivePinned(connId, config, jobId = 1u) }
        val baseline = DerivedStubFixture.metricsDigest(connId)

        // A 0.0001 nudge (the column's own scale) on ONE task's fact value.
        val fact = MetricsTables.FactTaskDelivery
        val (issueId, original) = suspendTransaction(sharedDatabaseForTests()) {
            val row = fact.selectAll().where { fact.connectionId eq connId }.toList().first()
            row[fact.issueId] to row[fact.blockedWorkingDays]
        }
        suspend fun nudge(value: BigDecimal) = suspendTransaction(sharedDatabaseForTests()) {
            fact.update({ (fact.connectionId eq connId) and (fact.issueId eq issueId) }) { it[blockedWorkingDays] = value }
        }
        nudge(original.add(BigDecimal("0.0001")))
        assertNotEquals(baseline, DerivedStubFixture.metricsDigest(connId), "a 0.0001 change to one fact value must change the digest")
        nudge(original)
        assertEquals(baseline, DerivedStubFixture.metricsDigest(connId), "restoring the value must restore the digest")

        // One row of a surrogate-id bridge table (no natural key — its whole row is the sort key).
        val bridge = MetricsTables.TaskSprint
        val deleted = suspendTransaction(sharedDatabaseForTests()) {
            val row = bridge.selectAll().where { bridge.connectionId eq connId }.toList().first()
            bridge.deleteWhere { (bridge.connectionId eq connId) and (bridge.id eq row[bridge.id]) }
        }
        assertEquals(1, deleted, "exactly one task_sprint row must have been deleted")
        assertNotEquals(baseline, DerivedStubFixture.metricsDigest(connId), "deleting one task_sprint row must change the digest")
    }

    /** Digest and `fact_sprint_snapshot` row count after each of two consecutive DERIVEs. */
    private data class TwoDerives(
        val firstDigest: String,
        val snapshotsAfterFirst: Int,
        val secondDigest: String,
        val snapshotsAfterSecond: Int,
    )
}
