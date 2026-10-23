package ch.nokillswit

import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.metrics.deriveLockTimeoutMs
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The DERIVE transaction's `lock_timeout` (`MetricsDeriver`'s `DEFAULT_DERIVE_LOCK_TIMEOUT_MS`, `.claude/docs/metrics.md`
 * "Failure and cancellation", build-times WHY 24): a DERIVE queued behind a session that never ends its transaction fails
 * with PostgreSQL's lock timeout instead of waiting forever, its `derive_runs` row is FAILED like any other failed run (the
 * worker then closes the job `RUN_FAILED`, the path `IngestWorkerTest` pins for every throwing run), and the next DERIVE
 * once the holder is gone succeeds. On its own DISABLED clone, derived under the pinned clock (`.claude/docs/testing.md`).
 */
class DeriveLockTimeoutTest {
    private suspend fun latestRunStatus(connId: UInt): String? = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.DeriveRuns.selectAll().where { MetricsTables.DeriveRuns.connectionId eq connId.toInt() }
            .orderBy(MetricsTables.DeriveRuns.id to SortOrder.DESC).limit(1).toList().singleOrNull()
            ?.get(MetricsTables.DeriveRuns.status)
    }

    private suspend fun derive(connId: UInt, jobId: UInt, lockTimeoutMs: Long? = null) =
        DerivedStubFixture.withPinnedSettings(DerivedStubFixture.metricsSettings()) {
            DerivedStubFixture.derivePinned(connId, jobId = jobId, deriveLockTimeoutMs = lockTimeoutMs)
        }

    @Test
    fun `a DERIVE blocked by a foreign row lock fails with the lock timeout and the next one succeeds`() = runBlocking {
        val connId = SyncedStubFixture.createConnection(namePrefix = "jira-lock-timeout-clone", enabled = false)
        SyncedStubFixture.cloneProcessedData(SyncedStubFixture.connectionId(), connId)
        DerivedStubFixture.mapFloBoardToNewTeam(connId, DerivedStubFixture.metricsConfig(), "lock-timeout-team")
        derive(connId, jobId = 1u)
        assertEquals("SUCCEEDED", latestRunStatus(connId))

        // A foreign session holding row locks on the derived rows the next DERIVE deletes first, never committing.
        val failure = PostgresTestSupport.connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { st ->
                st.executeQuery("SELECT 1 FROM metrics.fact_task_delivery WHERE connection_id = ${connId.toInt()} FOR UPDATE").use { rs ->
                    assertTrue(rs.next(), "the first DERIVE wrote task facts to lock")
                }
            }
            val startedAt = System.nanoTime()
            val thrown = runCatching { derive(connId, jobId = 2u, lockTimeoutMs = LOCK_TIMEOUT_MS) }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            holder.rollback()
            assertTrue(elapsedMs < MAX_FAILURE_MS, "the blocked DERIVE must give up near its lock timeout, took $elapsedMs ms")
            thrown
        }
        assertNotNull(failure, "a DERIVE blocked past its lock timeout must fail")
        val sqlState = generateSequence(failure) { it.cause }.filterIsInstance<R2dbcException>().firstNotNullOfOrNull { it.sqlState }
        assertEquals(LOCK_NOT_AVAILABLE, sqlState, "the failure is PostgreSQL's lock timeout: $failure")
        assertEquals("FAILED", latestRunStatus(connId))

        derive(connId, jobId = 3u)
        assertEquals("SUCCEEDED", latestRunStatus(connId), "with the holder gone the next DERIVE succeeds")
    }

    @Test
    fun `the rebuild lock timeout is 300 s and never below the pool's idle-in-transaction timeout`() {
        assertEquals(300_000L, deriveLockTimeoutMs(0)) // idle timeout off
        assertEquals(300_000L, deriveLockTimeoutMs(300_000))
        assertEquals(300_000L, deriveLockTimeoutMs(60_000))
        assertEquals(900_000L, deriveLockTimeoutMs(900_000))
    }

    companion object {
        private const val LOCK_NOT_AVAILABLE = "55P03"
        private const val LOCK_TIMEOUT_MS = 1_000L

        // The lock wait plus the rest of a failing derive (its reads before the delete, the FAILED write) on a slow runner.
        private const val MAX_FAILURE_MS = 30_000L

        @JvmStatic
        @BeforeClass
        fun warmFixtures() = warmStubFixtures()
    }
}
