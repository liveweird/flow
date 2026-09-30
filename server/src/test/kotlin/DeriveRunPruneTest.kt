package ch.nokillswit

import ch.nokillswit.metrics.MetricsStore
import ch.nokillswit.metrics.MetricsTables
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `MetricsStore.pruneDeriveRuns` (the per-DERIVE retention prune of `metrics.derive_runs`) must never delete a
 * connection's NEWEST SUCCEEDED run, however old: it is the connection's DERIVE clock, and the snapshot reports
 * (`reports/SnapshotSupport.kt` `deriveClocks`) would otherwise read a derived connection as "not derived yet".
 */
class DeriveRunPruneTest {
    @Test
    fun `prune keeps each connection's newest succeeded run and drops the other expired terminal runs`() = testApplication {
        usePostgresTestcontainer()
        val store = MetricsStore(sharedDatabaseForTests())
        val connA = SyncedStubFixture.createConnection(namePrefix = "prune-a", enabled = false)
        val connB = SyncedStubFixture.createConnection(namePrefix = "prune-b", enabled = false)
        // Clock in 1970: the prune is global, so its cutoff (now - retention) must reach only these rows — every
        // real run in the shared database finished decades later and stays untouched.
        val retention = 30L * 24 * 60 * 60 * 1000
        val now = 3 * retention
        val old = retention

        // The runs are deleted afterwards: a SUCCEEDED run dated 1970 would otherwise become these connections'
        // DERIVE clock and drag every unit-level snapshot report's cut-off back to 1970 (`ReportWipTest`).
        try {
            insertRun(connA, startedAt = old - 2_000, status = "SUCCEEDED")
            val aNewest = insertRun(connA, startedAt = old - 1_000, status = "SUCCEEDED")
            insertRun(connA, startedAt = old, status = "FAILED")
            val aRecent = insertRun(connA, startedAt = now - 1_000, status = "FAILED")
            // B's only success is expired, and a newer FAILED run exists: the success still stays.
            val bOnly = insertRun(connB, startedAt = old - 5_000, status = "SUCCEEDED")
            insertRun(connB, startedAt = old, status = "FAILED")

            store.pruneDeriveRuns(retention, now)

            assertEquals(setOf(aNewest, aRecent), runIds(connA), "A keeps its newest success and its in-window run")
            assertEquals(setOf(bOnly), runIds(connB), "B keeps its only success; the expired FAILED run goes")
        } finally {
            deleteDeriveRuns(connA)
            deleteDeriveRuns(connB)
        }
    }

    private suspend fun insertRun(connId: UInt, startedAt: Long, status: String): Int =
        suspendTransaction(sharedDatabaseForTests()) {
            MetricsTables.DeriveRuns.insert {
                it[connectionId] = connId.toInt()
                it[configRevision] = 1L
                it[processingVersion] = 1
                it[this.startedAt] = startedAt
                it[finishedAt] = startedAt + 500
                it[this.status] = status
            }[MetricsTables.DeriveRuns.id]
        }

    private suspend fun runIds(connId: UInt): Set<Int> = suspendTransaction(sharedDatabaseForTests()) {
        MetricsTables.DeriveRuns.selectAll().where { MetricsTables.DeriveRuns.connectionId eq connId.toInt() }
            .toList().map { it[MetricsTables.DeriveRuns.id] }.toSet()
    }
}
