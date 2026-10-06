package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * A DERIVE's data and its SUCCEEDED stamp commit together (`MetricsDeriver.derive`: ONE transaction around the rebuild and
 * `markRunSucceeded`): a failure between the data write and the mark leaves NEITHER committed — the connection's figures stay what
 * the previous SUCCEEDED run vouches for, and the failed run is FAILED. The failure is injected through the job clock, which the
 * deriver reads once at the start and once right after the rebuild (the `finished_at`), so no production seam is needed.
 */
class MetricsDeriveAtomicityTest {
    private suspend fun runStatuses(connId: UInt): List<Pair<Int, String>> = suspendTransaction(sharedDatabaseForTests()) {
        val r = MetricsTables.DeriveRuns
        r.select(r.id, r.status).where { r.connectionId eq connId.toInt() }.toList().map { it[r.id] to it[r.status] }.sortedBy { it.first }
    }

    @Test
    fun `a failure between the rebuild and the SUCCEEDED mark commits neither`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-atomic-derive", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "atomic-derive-team")
        val settings = DerivedStubFixture.metricsSettings()

        DerivedStubFixture.withPinnedSettings(settings) { DerivedStubFixture.derivePinned(connId, jobId = 1u) }
        val committedDigest = DerivedStubFixture.metricsDigest(connId)
        val committedRuns = runStatuses(connId)
        assertEquals(listOf("SUCCEEDED"), committedRuns.map { it.second })

        // A different config revision: every row a committed second rebuild wrote would carry it, so the digest would move.
        withMetricsSettings(settings, { it.copy(hoursPerDay = 6.0) }) {
            var reads = 0
            val failure = assertFailsWith<IllegalStateException> {
                DerivedStubFixture.deriveWith(connId, jobId = 2u) {
                    if (++reads == 2) error("injected after the rebuild, before the SUCCEEDED mark") else DerivedStubFixture.PINNED_NOW
                }
            }
            assertEquals("injected after the rebuild, before the SUCCEEDED mark", failure.message)

            assertEquals(committedDigest, DerivedStubFixture.metricsDigest(connId), "the rebuild was rolled back with the failed mark")
            val after = runStatuses(connId)
            assertEquals(committedRuns.single(), after.first(), "the previous SUCCEEDED run still vouches for the data")
            assertEquals(listOf("SUCCEEDED", "FAILED"), after.map { it.second })

            // Sensitivity: the same rebuild, un-sabotaged, DOES move the digest — the assertion above is not vacuous.
            DerivedStubFixture.deriveWith(connId, jobId = 3u) { DerivedStubFixture.PINNED_NOW }
            assertNotEquals(committedDigest, DerivedStubFixture.metricsDigest(connId))
            assertEquals(listOf("SUCCEEDED", "FAILED", "SUCCEEDED"), runStatuses(connId).map { it.second })
        }
    }
}
