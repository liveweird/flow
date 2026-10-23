package ch.nokillswit.metrics

import ch.nokillswit.infra.json.replaceNul
import ch.nokillswit.norm.PROCESSING_VERSION
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

// The `metrics.derive_runs` bookkeeping of one DERIVE job (`MetricsDeriver.derive` brackets the derivation with it): the
// RUNNING row opened up front, then SUCCEEDED or FAILED once the run ends. Each is its own short transaction.

private const val MAX_ERROR_DETAIL_LENGTH = 1000
private const val ABANDONED_RUN_DETAIL = "abandoned: worker lost its lease"

/**
 * Sweeps every other RUNNING row of the connection to FAILED and inserts this run's own RUNNING row; returns its id.
 * Only one DERIVE per connection can run (the job queue's lease guarantees it), so any OTHER RUNNING row for this
 * connection belongs to a worker that died mid-DERIVE (SIGKILL, lost lease) and can never finish — nothing else would
 * ever mark it terminal, and `pruneDeriveRuns` only deletes SUCCEEDED/FAILED rows. Same transaction as our own insert.
 */
internal suspend fun openDeriveRun(
    database: R2dbcDatabase,
    connectionId: UInt,
    jobId: UInt,
    configRevision: Long,
    startedAt: Long,
): Int = suspendTransaction(database) {
    MetricsTables.DeriveRuns.update({
        (MetricsTables.DeriveRuns.connectionId eq connectionId.toInt()) and (MetricsTables.DeriveRuns.status eq "RUNNING")
    }) {
        it[status] = "FAILED"
        it[MetricsTables.DeriveRuns.finishedAt] = startedAt
        it[errorDetail] = ABANDONED_RUN_DETAIL
    }
    MetricsTables.DeriveRuns.insert {
        it[MetricsTables.DeriveRuns.connectionId] = connectionId.toInt()
        it[MetricsTables.DeriveRuns.jobId] = jobId.toInt()
        it[MetricsTables.DeriveRuns.configRevision] = configRevision
        it[MetricsTables.DeriveRuns.processingVersion] = PROCESSING_VERSION
        it[MetricsTables.DeriveRuns.startedAt] = startedAt
        it[MetricsTables.DeriveRuns.status] = "RUNNING"
    }[MetricsTables.DeriveRuns.id]
}

internal suspend fun markRunSucceeded(database: R2dbcDatabase, runId: Int, counts: DeriveRowCounts, finishedAt: Long) {
    val countsJson = counts.toJson()
    suspendTransaction(database) {
        // Only while still RUNNING: a newer DERIVE of the same connection may already have swept this run
        // FAILED as abandoned (a lease-expired zombie) — its late write must not overwrite that.
        MetricsTables.DeriveRuns.update({ (MetricsTables.DeriveRuns.id eq runId) and (MetricsTables.DeriveRuns.status eq "RUNNING") }) {
            it[status] = "SUCCEEDED"
            it[MetricsTables.DeriveRuns.finishedAt] = finishedAt
            it[rowCounts] = countsJson
        }
    }
}

internal suspend fun markRunFailed(
    database: R2dbcDatabase,
    runId: Int,
    failure: Exception,
    finishedAt: Long,
) = withContext(NonCancellable) {
    suspendTransaction(database) {
        // Only while still RUNNING: a newer DERIVE may have swept this run FAILED as abandoned; its late write must not overwrite that.
        MetricsTables.DeriveRuns.update({ (MetricsTables.DeriveRuns.id eq runId) and (MetricsTables.DeriveRuns.status eq "RUNNING") }) {
            it[status] = "FAILED"
            it[MetricsTables.DeriveRuns.finishedAt] = finishedAt
            it[errorDetail] = failure.message?.replaceNul()?.take(MAX_ERROR_DETAIL_LENGTH)
        }
    }
    Unit
}
