package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass

/**
 * A DERIVE's figures and its SUCCEEDED stamp commit together (`MetricsDeriver.derive`: ONE transaction around the rebuild and
 * `markRunSucceeded`): a failure between the two, or a run a newer DERIVE already swept FAILED as abandoned, leaves NEITHER
 * committed — the connection's figures stay what its previous SUCCEEDED run vouches for. The sabotage rides the job clock, which
 * the deriver reads once at the start and once for the stamp's `finished_at` (inside the transaction), so no production seam is
 * needed. Both tests share one disabled clone and assert against its state on entry, so their order does not matter.
 */
class MetricsDeriveAtomicityTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()

        private val cloneLock = Mutex()
        private var clone: UInt? = null
    }

    private suspend fun derivedClone(): UInt = cloneLock.withLock {
        clone ?: run {
            val connId = SyncedStubFixture.createConnection(namePrefix = "jira-atomic-derive", enabled = false)
            SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
            DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "atomic-derive-team")
            DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) { DerivedStubFixture.derivePinned(connId) }
            connId.also { clone = it }
        }
    }

    private suspend fun runStatuses(connId: UInt): List<String> = suspendTransaction(sharedDatabaseForTests()) {
        val r = MetricsTables.DeriveRuns
        r.select(r.id, r.status).where { r.connectionId eq connId.toInt() }.toList().sortedBy { it[r.id] }.map { it[r.status] }
    }

    private suspend fun newestErrorDetail(connId: UInt): String? = suspendTransaction(sharedDatabaseForTests()) {
        val r = MetricsTables.DeriveRuns
        r.select(r.id, r.errorDetail).where { r.connectionId eq connId.toInt() }.toList().maxBy { it[r.id] }[r.errorDetail]
    }

    /**
     * Runs one DERIVE of the clone whose job clock calls [sabotage] on its second reading (the SUCCEEDED stamp's), expects it to
     * fail with [expectedMessage] and leave its run FAILED with [expectedDetail], and asserts nothing of it committed — then that
     * the same derive un-sabotaged DOES move the digest, so the "unchanged" assertion is not vacuous.
     */
    private fun assertSabotagedDeriveCommitsNothing(
        expectedMessage: String,
        expectedDetail: String,
        sabotage: (connId: UInt) -> Unit,
    ) = runBlocking {
        val connId = derivedClone()
        val settings = DerivedStubFixture.metricsSettings()
        // Every derived row carries the config revision, which this wrap bumps: a committed rebuild here would move the digest.
        withMetricsSettings(settings, { it.copy(hoursPerDay = 6.0) }) {
            val digestBefore = DerivedStubFixture.metricsDigest(connId)
            val runsBefore = runStatuses(connId)
            assertEquals("SUCCEEDED", runsBefore.last())

            var reads = 0
            val failure = assertFailsWith<IllegalStateException> {
                DerivedStubFixture.derivePinned(connId, jobId = 2u) {
                    if (++reads == 2) sabotage(connId)
                    DerivedStubFixture.PINNED_NOW
                }
            }
            assertEquals(expectedMessage, failure.message?.substringBefore(" (run"))
            assertEquals(digestBefore, DerivedStubFixture.metricsDigest(connId), "the rebuild was rolled back with the failed stamp")
            assertEquals(runsBefore + "FAILED", runStatuses(connId), "the previous SUCCEEDED run still vouches for the figures")
            assertEquals(expectedDetail, newestErrorDetail(connId), "the failed run's own detail")

            DerivedStubFixture.derivePinned(connId, jobId = 3u)
            assertNotEquals(digestBefore, DerivedStubFixture.metricsDigest(connId))
            assertEquals(runsBefore + "FAILED" + "SUCCEEDED", runStatuses(connId))
        }
    }

    @Test
    fun `a failure between the rebuild and the SUCCEEDED stamp commits neither`() =
        assertSabotagedDeriveCommitsNothing("injected before the SUCCEEDED stamp", "injected before the SUCCEEDED stamp") {
            error("injected before the SUCCEEDED stamp")
        }

    @Test
    fun `a run swept FAILED as abandoned while it rebuilt commits none of its figures`() =
        // The zombie's own markRunFailed must not overwrite the sweep's detail (it only touches a RUNNING row).
        assertSabotagedDeriveCommitsNothing("derive run is no longer RUNNING", "abandoned: worker lost its lease") { connId ->
            // What a newer DERIVE's `openDeriveRun` does to a lease-expired zombie, from another session while this run rebuilds.
            PostgresTestSupport.connect().use { conn ->
                conn.prepareStatement(
                    "UPDATE metrics.derive_runs SET status = 'FAILED', finished_at = ?, " +
                        "error_detail = 'abandoned: worker lost its lease' WHERE connection_id = ? AND status = 'RUNNING'",
                ).use { st ->
                    st.setLong(1, DerivedStubFixture.PINNED_NOW)
                    st.setInt(2, connId.toInt())
                    assertEquals(1, st.executeUpdate())
                }
            }
        }
}
