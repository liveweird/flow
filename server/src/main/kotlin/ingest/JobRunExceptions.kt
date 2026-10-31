package ch.nokillswit.ingest

/** The lease was reclaimed by another worker (or the job left RUNNING) — the run stops without touching cursors. */
class LeaseLostException(jobId: UInt) : Exception("Lease lost for sync job $jobId")

/** `cancel_requested_at` was set against this RUNNING job — the run stops cooperatively. */
class JobCancelRequestedException(jobId: UInt) : Exception("Cancel requested for sync job $jobId")

/**
 * The `last_sync_error_code` / `sync_jobs.error_code` of a GitLab job refused because its token carries a write-capable scope (or is
 * inactive): a member of [SYNC_ERROR_CODES], a refusal no retry of the same job can fix until the token is replaced.
 */
const val TOKEN_SCOPE_TOO_BROAD = "TOKEN_SCOPE_TOO_BROAD"

/**
 * A connector refused to run the job for a reason it can name with a closed code ([errorCode], a member of [SYNC_ERROR_CODES]);
 * [IngestWorker] closes the job with that code instead of the generic `RUN_FAILED`. [message] is operator text and carries no secret.
 */
class JobRefusedException(val errorCode: String, detail: String) : Exception(detail)

/**
 * The closed error code a connector-side failure carries ([ConnectorUnavailableException], [JobRefusedException]), or null for
 * any other.
 */
internal fun connectorFailureCode(cause: Exception): String? = when (cause) {
    is ConnectorUnavailableException -> CONNECTOR_UNAVAILABLE
    is JobRefusedException -> cause.errorCode
    else -> null
}
