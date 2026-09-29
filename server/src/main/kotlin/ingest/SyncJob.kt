package ch.nokillswit.ingest

import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The sync-job queue (v0.2.0 plan §4/§5/§9, V9 `sync_jobs`): one table serves as both history and
 * command queue. `ingest/SyncJobs.kt` is the service; `ingest/IngestWorker.kt` is the sole claimer
 * (the worker role); `ingest/SyncJobRoutes.kt` is the ADMIN-only job API (the web role).
 */
/**
 * `DERIVE` (v0.3.0 M3 commit 7, `.claude/docs/ingestion.md` "The DERIVE job kind") is a
 * connector-agnostic job — it reads `norm`/the metrics config and writes `metrics.*`, never
 * touching Jira. `ingest/IngestWorker.kt` dispatches it to `metrics/MetricsDeriver.kt` BEFORE
 * consulting the connector registry.
 */
enum class SyncJobKind { SYNC, RECONCILE, REPROCESS, PURGE, DERIVE }

enum class SyncJobStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

/** 0 = a manual request ("Sync now"/"Reconcile now"/"Reprocess") preempts the schedule; 10 = scheduler-enqueued. */
const val SYNC_JOB_PRIORITY_MANUAL = 0
const val SYNC_JOB_PRIORITY_SCHEDULED = 10

/** `POST /api/v1/data-sources/{id}/sync-jobs` — `kind` alone; `PURGE` is internal-only (400). */
@Serializable
data class SyncJobRequest(val kind: SyncJobKind)

@Serializable
data class SyncJobResponse(
    val id: UInt,
    val connectionId: UInt,
    val kind: SyncJobKind,
    val status: SyncJobStatus,
    val priority: Int,
    val requestedByUserId: UInt? = null,
    val configRevision: Long,
    val requestedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val attempt: Int,
    val maxAttempts: Int,
    val leaseUntil: Long? = null,
    val heartbeatAt: Long? = null,
    val cancelRequestedAt: Long? = null,
    val currentStream: String? = null,
    val progress: JsonObject? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
)

typealias SyncJobPageResponse = PageResponse<SyncJobResponse>

/** `POST .../sync-jobs` (202) and `POST .../sync-jobs/{jobId}/cancel` (202) both echo the job row plus an outcome flag. */
@Serializable
data class SyncJobActionResult(val job: SyncJobResponse, val coalesced: Boolean)

data class SyncJobListFilter(val kind: SyncJobKind? = null, val status: SyncJobStatus? = null)

data class SyncJobListResult(val items: List<SyncJobResponse>, val total: Long)

/** A successfully claimed job, ready to run. */
data class SyncJobClaim(
    val id: UInt,
    val connectionId: UInt,
    val connectorKind: DataSourceKind,
    val kind: SyncJobKind,
    val attempt: Int,
    val maxAttempts: Int,
    val syncIntervalMinutes: Int,
)

/** The outcome of [ch.nokillswit.ingest.SyncJobsService.requestCancel]. */
enum class CancelOutcome { CANCELLED_NOW, CANCEL_REQUESTED, ALREADY_TERMINAL }

/**
 * The outcome of [ch.nokillswit.ingest.SyncJobsService.requestJob] — `coalesced` when an open job
 * for the same (connection, kind) already existed.
 */
data class SyncJobEnqueueResult(val jobId: UInt, val coalesced: Boolean)
