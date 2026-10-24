package ch.nokillswit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The telemetry read API reaches other packages' tables only through their services (`DataSourceService`, `SyncJobsService`,
 * `MetricsStore`), inside the telemetry read transaction, so its 5 s `queryTimeout` must bound THEIR statements too
 * (`persistence.md` "Statement timeouts": only the transaction's `queryTimeout` sets it, and a service's own
 * `suspendTransaction(database)` has to JOIN the caller's transaction for that to hold). Pinned with a real owner read blocked by an
 * ACCESS EXCLUSIVE lock: it must be cancelled by the 1 s timeout, not wait for the lock.
 */
class TelemetryReadBudgetTest {
    @Test
    fun `an owner service read inside the telemetry read transaction is cancelled by its statement timeout`() = runBlocking {
        val database = sharedDatabaseForTests()
        val dataSources = SyncedStubFixture.dataSources()
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            suspendTransaction(database) {
                exec("LOCK TABLE public.source_connections IN ACCESS EXCLUSIVE MODE")
                locked.complete(Unit)
                release.await()
            }
        }
        try {
            locked.await()
            val started = System.nanoTime()
            val failure = runCatching {
                telemetryStore().transaction(timeoutSeconds = 1) { dataSources.connectionHealth() }
            }.exceptionOrNull()
            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
            assertNotNull(failure, "the blocked owner read is cancelled, not served")
            assertTrue(seconds < 8, "cancelled by the 1 s statement timeout, took $seconds s")
        } finally {
            release.complete(Unit)
            holder.join()
        }
    }
}
