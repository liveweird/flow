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
// RUNNING row opened up front, then SUCCEEDED inside the rebuild's own transaction or FAILED in a short one of its own.

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

/**
 * Stamps the run SUCCEEDED inside the CALLER's transaction — `MetricsDeriver.derive`'s rebuild — never one of its own, so the
 * figures and the run that vouches for them commit together. Only while still RUNNING: a newer DERIVE of the same connection may
 * already have swept this run FAILED as abandoned (a lease-expired zombie); its late write must not overwrite that, and its figures
 * must not commit under a FAILED run either, so a missing row fails the stamp and rolls the rebuild back with it.
 */
internal suspend fun markRunSucceeded(runId: Int, counts: DeriveRowCounts, finishedAt: Long) {
    val runs = MetricsTables.DeriveRuns
    val stamped = runs.update({ (runs.id eq runId) and (runs.status eq "RUNNING") }) {
        it[status] = "SUCCEEDED"
        it[runs.finishedAt] = finishedAt
        it[rowCounts] = counts.toJson()
    }
    check(stamped == 1) { "derive run is no longer RUNNING (run $runId was swept as abandoned or purged); its rebuild is rolled back" }
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
